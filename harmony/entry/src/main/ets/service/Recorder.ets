import { media } from '@kit.MediaKit';
import { audio } from '@kit.AudioKit';
import { fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { backgroundTaskManager } from '@kit.BackgroundTasksKit';
import { wantAgent } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Repo, Keys } from '../data/Repo';
import { TimeUtil } from '../util/TimeUtil';

/**
 * AudioRoutingManager 上「指定采集设备」那个方法的最小声明。
 * 纯 OpenHarmony SDK（API 12）没有导出它，HarmonyOS SDK 有；用接口断言既能编译，
 * 又能在真机上走到真正的实现，方法缺失时抛错并被调用处 catch 回退。
 */
interface InputDeviceSetter {
  setPreferredInputDeviceForCapturerInfo(
    info: audio.AudioCapturerInfo,
    devices: audio.AudioDeviceDescriptors
  ): Promise<void>;
}

export class RecState {
  static readonly IDLE: string = 'idle';
  static readonly RECORDING: string = 'recording';
  /** 通话占用了麦克风：当前段已保存，等通话结束自动接着录 */
  static readonly WAITING: string = 'waiting';
}

export interface RecorderEvents {
  onState: (state: string) => void;
  onTick: (elapsedMs: number) => void;
  onSegment: (path: string, startAt: number, durationMs: number, index: number, reason: string) => void;
  onError: (message: string) => void;
}

/**
 * 录音器
 *
 * 与 Android 版 RecorderService 对齐的三条关键行为：
 *
 * 1) 分段：单文件最长 30 分钟自动切段。长会议不再是一个「一断全废」的大文件。
 * 2) 通话打断：轮询音频场景，识别到来电/微信语音时把当前段收尾保存、进入等待态，
 *    通话结束后自动开新段继续录——通话本身那几分钟录不到（系统不允许第三方抢麦），
 *    但通话前后的内容一段都不会丢。
 * 3) 音源：默认手机麦克风；选了耳机麦克风时尝试把采集设备切到蓝牙 SCO，
 *    切不成功就如实回退到手机麦（currentIsBtMic 反映「真实生效」的音源，不谎报）。
 */
export class Recorder {
  private static inst: Recorder | null = null;

  static get(): Recorder {
    if (Recorder.inst === null) {
      Recorder.inst = new Recorder();
    }
    return Recorder.inst;
  }

  private ctx: common.UIAbilityContext | null = null;
  private events: RecorderEvents | null = null;
  private recorder: media.AVRecorder | null = null;
  private state: string = RecState.IDLE;
  private segmentIndex: number = 1;
  private segmentStart: number = 0;
  private filePath: string = '';
  private segFile: fileIo.File | null = null;
  private marks: number[] = [];
  private timerId: number = -1;
  private wantContinuous: boolean = false;
  private callStreak: number = 0;
  private startedByBluetooth: boolean = false;
  private btMic: boolean = false;
  private wantBtMic: boolean = false;
  private bgRunning: boolean = false;

  /** 真实生效的音源（SCO 切换失败时会是 false） */
  currentIsBtMic: boolean = false;

  static readonly SEGMENT_LIMIT_MS: number = 30 * 60 * 1000;

  bind(ctx: common.UIAbilityContext, events: RecorderEvents): void {
    this.ctx = ctx;
    this.events = events;
  }

  isRecording(): boolean {
    return this.state === RecState.RECORDING;
  }

  isWaiting(): boolean {
    return this.state === RecState.WAITING;
  }

  isActive(): boolean {
    return this.state !== RecState.IDLE;
  }

  elapsedMs(): number {
    if (this.state === RecState.IDLE) {
      return 0;
    }
    return Date.now() - this.segmentStart;
  }

  segment(): number {
    return this.segmentIndex;
  }

  /** 这次记录是不是耳机连上后自动触发的（决定耳机断开要不要一起结束） */
  isStartedByBluetooth(): boolean {
    return this.startedByBluetooth;
  }

  markCount(): number {
    return this.marks.length;
  }

  // ==================== 开始 / 停止 ====================

  async start(fromBluetooth: boolean): Promise<boolean> {
    const ctx = this.ctx;
    if (ctx === null) {
      return false;
    }
    if (this.state !== RecState.IDLE) {
      return true;
    }
    this.segmentIndex = 1;
    this.marks = [];
    this.wantContinuous = true;
    this.startedByBluetooth = fromBluetooth;
    this.wantBtMic = Repo.getBool(Keys.BT_MIC, false);
    await this.beginBackground();
    const ok = await this.startSegment('start');
    if (ok) {
      this.startTimer();
    } else {
      await this.endBackground();
      this.wantContinuous = false;
    }
    return ok;
  }

  async stop(reason: string): Promise<void> {
    this.wantContinuous = false;
    this.stopTimer();
    await this.closeSegment(reason);
    this.state = RecState.IDLE;
    this.callStreak = 0;
    this.segmentIndex = 1;
    await this.endBackground();
    this.emitState();
  }

  /** 录音中切音源：当前段收尾保存，立刻用新音源开新段，一句不丢 */
  async switchSource(useBtMic: boolean): Promise<void> {
    this.wantBtMic = useBtMic;
    this.startedByBluetooth = false;
    if (!this.isRecording()) {
      return;
    }
    await this.closeSegment('switch');
    this.segmentIndex += 1;
    await this.startSegment('switch');
  }

  markPoint(): void {
    if (!this.isRecording()) {
      return;
    }
    this.marks.push(Date.now() - this.segmentStart);
  }

  // ==================== 段管理 ====================

  private outDir(): string {
    const ctx = this.ctx as common.UIAbilityContext;
    const dir = `${ctx.filesDir}/recordings`;
    if (!fileIo.accessSync(dir)) {
      fileIo.mkdirSync(dir);
    }
    return dir;
  }

  private async startSegment(reason: string): Promise<boolean> {
    const ctx = this.ctx;
    if (ctx === null) {
      return false;
    }
    const startAt = Date.now();
    const name = `REC_${TimeUtil.dateFull(startAt).replace(/-/g, '')}_${startAt}_${this.segmentIndex}.m4a`;
    const path = `${this.outDir()}/${name}`;
    let file: fileIo.File | null = null;
    try {
      file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE);
      this.segFile = file;
      const rec = await media.createAVRecorder();
      rec.on('error', (err: BusinessError) => {
        this.emitError(`录音器出错：${err.message}`);
      });
      const profile: media.AVRecorderProfile = {
        // 96 kbps：单声道 44.1 kHz 原始约 706 kbps，压到 64 是 11:1，
        // 辅音与气声先糊，转写错字大半来自这里。这一档是错字率的地板。
        audioBitrate: 96000,
        audioChannels: 1,
        audioCodec: media.CodecMimeType.AUDIO_AAC,
        audioSampleRate: 44100,
        fileFormat: media.ContainerFormatType.CFT_MPEG_4A
      };
      const config: media.AVRecorderConfig = {
        audioSourceType: media.AudioSourceType.AUDIO_SOURCE_TYPE_MIC,
        profile: profile,
        url: `fd://${file.fd}`
      };
      await rec.prepare(config);
      await rec.start();
      this.recorder = rec;
      this.filePath = path;
      this.segmentStart = startAt;
      this.state = RecState.RECORDING;
      this.currentIsBtMic = false;
      // 音源切换放在 start 之后：先有采集器，再改首选输入设备才生效
      if (this.wantBtMic) {
        this.applyInputDevice(true);
      }
      this.emitState();
      return true;
    } catch (e) {
      const err = e as BusinessError;
      this.emitError(`开始录音失败：${err.message}（${err.code}）`);
      this.closeSegFile();
      return false;
    }
  }

  /** AVRecorder 只借用 fd，释放后要由我们关掉，否则每段都漏一个句柄 */
  private closeSegFile(): void {
    const f = this.segFile;
    this.segFile = null;
    if (f !== null) {
      try {
        fileIo.closeSync(f);
      } catch (e) {
        // 已关闭则忽略
      }
    }
  }

  private async closeSegment(reason: string): Promise<void> {
    const rec = this.recorder;
    const path = this.filePath;
    const startAt = this.segmentStart;
    if (rec === null) {
      return;
    }
    this.recorder = null;
    this.filePath = '';
    try {
      await rec.stop();
    } catch (e) {
      // 已经停了就忽略
    }
    try {
      await rec.release();
    } catch (e) {
      // 同上
    }
    this.closeSegFile();
    const duration = Date.now() - startAt;
    if (path.length > 0 && duration > 500) {
      if (this.events !== null) {
        this.events.onSegment(path, startAt, duration, this.segmentIndex, reason);
      }
    } else if (path.length > 0) {
      // 太短的段（<0.5 秒）没有转写价值，直接删掉，避免污染记录列表
      try {
        fileIo.unlinkSync(path);
      } catch (e) {
        // 忽略
      }
    }
  }

  // ==================== 计时 / 巡查 ====================

  private startTimer(): void {
    this.stopTimer();
    this.timerId = setInterval(() => {
      if (this.state === RecState.RECORDING) {
        const elapsed = this.elapsedMs();
        if (this.events !== null) {
          this.events.onTick(elapsed);
        }
        if (elapsed >= Recorder.SEGMENT_LIMIT_MS) {
          this.rotateByLimit();
        }
      }
      this.checkCallScene();
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  private async rotateByLimit(): Promise<void> {
    await this.closeSegment('limit');
    this.segmentIndex += 1;
    await this.startSegment('limit');
  }

  /**
   * 通话场景检测
   *
   * 连续 2 次（约 2 秒）命中才动作，避免系统瞬时切换音频场景造成误判。
   * 命中时：当前段收尾保存 → 等待态；场景恢复正常：自动开新段。
   */
  private checkCallScene(): void {
    if (!this.wantContinuous) {
      return;
    }
    const busy = Recorder.isAudioSceneBusy();
    if (this.state === RecState.RECORDING && busy) {
      this.callStreak += 1;
      if (this.callStreak >= 2) {
        this.callStreak = 0;
        this.enterWaiting();
      }
    } else if (this.state === RecState.WAITING && !busy) {
      this.callStreak += 1;
      if (this.callStreak >= 2) {
        this.callStreak = 0;
        this.resumeAfterCall();
      }
    } else {
      this.callStreak = 0;
    }
  }

  private async enterWaiting(): Promise<void> {
    await this.closeSegment('call');
    this.state = RecState.WAITING;
    this.emitState();
  }

  private async resumeAfterCall(): Promise<void> {
    this.segmentIndex += 1;
    const ok = await this.startSegment('resume');
    if (!ok) {
      this.state = RecState.WAITING;
      this.emitState();
    }
  }

  /**
   * 当前音频场景是否被通话/响铃占用
   *
   * 注意：getAudioScene() 返回的是 Promise，同步取值必须用 getAudioSceneSync()。
   * 写成 getAudioScene() 再和枚举比较，DevEco 会直接报类型不兼容。
   */
  static isAudioSceneBusy(): boolean {
    try {
      const am = audio.getAudioManager();
      const scene: audio.AudioScene = am.getAudioSceneSync();
      return scene === audio.AudioScene.AUDIO_SCENE_RINGING
        || scene === audio.AudioScene.AUDIO_SCENE_PHONE_CALL
        || scene === audio.AudioScene.AUDIO_SCENE_VOICE_CHAT;
    } catch (e) {
      return false;
    }
  }

  // ==================== 音源 ====================

  /** 把采集设备切到蓝牙 SCO（或切回默认），失败静默回退并修正 currentIsBtMic */
  private applyInputDevice(useBt: boolean): void {
    try {
      const manager = audio.getAudioManager();
      const info: audio.AudioCapturerInfo = {
        source: audio.SourceType.SOURCE_TYPE_MIC,
        capturerFlags: 0
      };
      // 枚举全部输入设备：getPreferredInputDeviceForCapturerInfo 只返回「当前首选的那一个」，
      // 拿它去找蓝牙会永远找不到，必须用 getDevices(INPUT_DEVICES_FLAG)。
      // 这里用 Sync 版：getDevices 在 API 12 只有 Promise/回调重载，同步场景拿不到值。
      // getDevicesSync 挂在 AudioRoutingManager 上，AudioManager 本身没有
      const all: audio.AudioDeviceDescriptors = manager.getRoutingManager()
        .getDevicesSync(audio.DeviceFlag.INPUT_DEVICES_FLAG);
      const target = this.pickDevice(all, useBt);
      if (target === null) {
        this.btMic = false;
        this.currentIsBtMic = false;
        return;
      }
      // setPreferredInputDeviceForCapturerInfo 并非所有 API 版本都有（纯 OpenHarmony SDK 只有 get）。
      // 用接口断言调用：方法存在就真的切，不存在会抛 TypeError 被下面的 catch 接住，
      // 如实回退到系统默认路由，不谎报已切到耳机麦。
      const rm = manager.getRoutingManager() as object as InputDeviceSetter;
      rm.setPreferredInputDeviceForCapturerInfo(info, [target]).then(() => {
        this.btMic = useBt;
        this.currentIsBtMic = useBt;
        this.emitState();
      }).catch((err: BusinessError) => {
        // 部分机型不允许普通应用指定采集设备：如实回退到手机麦，不谎报
        console.info(`切换录音设备失败：${err.message}`);
        this.btMic = false;
        this.currentIsBtMic = false;
        this.emitState();
      });
    } catch (e) {
      this.currentIsBtMic = false;
    }
  }

  private pickDevice(list: audio.AudioDeviceDescriptors, useBt: boolean): audio.AudioDeviceDescriptor | null {
    if (list === undefined || list === null) {
      return null;
    }
    const wantBt = useBt;
    let btFallback: audio.AudioDeviceDescriptor | null = null;
    for (let i = 0; i < list.length; i++) {
      const d = list[i];
      if (wantBt) {
        if (d.deviceType === audio.DeviceType.BLUETOOTH_SCO) {
          return d;
        }
        // A2DP 耳机通常不能采集，只在没有 SCO 时才作为兜底候选
        if (d.deviceType === audio.DeviceType.BLUETOOTH_A2DP && btFallback === null) {
          btFallback = d;
        }
      } else if (d.deviceType === audio.DeviceType.MIC) {
        return d;
      }
    }
    if (wantBt && btFallback !== null) {
      return btFallback;
    }
    // 找不到目标设备：返回 null，由调用方保持原样
    return null;
  }

  // ==================== 后台长时任务 ====================

  private async beginBackground(): Promise<void> {
    if (this.bgRunning) {
      return;
    }
    const ctx = this.ctx;
    if (ctx === null) {
      return;
    }
    try {
      const info: wantAgent.WantAgentInfo = {
        wants: [
          {
            bundleName: ctx.abilityInfo.bundleName,
            abilityName: ctx.abilityInfo.name
          }
        ],
        actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: 0,
        actionFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG]
      };
      const agent = await wantAgent.getWantAgent(info);
      await backgroundTaskManager.startBackgroundRunning(
        ctx, backgroundTaskManager.BackgroundMode.AUDIO_RECORDING, agent
      );
      this.bgRunning = true;
    } catch (e) {
      const err = e as BusinessError;
      // 长时任务申请失败不影响前台录音，但要明确告诉用户「切后台可能被系统暂停」
      this.emitError(`后台录音可能被系统限制：${err.message}`);
    }
  }

  private async endBackground(): Promise<void> {
    if (!this.bgRunning) {
      return;
    }
    const ctx = this.ctx;
    this.bgRunning = false;
    if (ctx === null) {
      return;
    }
    try {
      await backgroundTaskManager.stopBackgroundRunning(ctx);
    } catch (e) {
      // 已经停了就忽略
    }
  }

  // ==================== 事件 ====================

  private emitState(): void {
    if (this.events !== null) {
      this.events.onState(this.state);
    }
  }

  private emitError(msg: string): void {
    if (this.events !== null) {
      this.events.onError(msg);
    }
  }
}
