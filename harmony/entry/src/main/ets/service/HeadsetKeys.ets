import { avSession } from '@kit.AVSessionKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';

/**
 * 耳机键归谁：三档
 *
 * 蓝牙耳机上只有一组键（播放/暂停），系统把它送给「当前活跃的媒体会话」。
 * 草记要拿到它，唯一的办法就是自己也注册一个媒体会话（AVSession），
 * 在需要的时候把自己激活成那一个——不需要的时候退出来，键就回到音乐 App 手里。
 * 这就是「同一个键，录音时管录音、平时管音乐」的全部原理。
 */
export class HeadsetMode {
  /** 只在录音中接管：录音时按一下=结束，其余时间完全还给音乐（默认） */
  static readonly RECORDING_ONLY: string = 'recording_only';
  /** 全程接管：不录音时按播放键=开始录音（代价：想听音乐得在手机上点） */
  static readonly ALWAYS: string = 'always';
  /** 不接管：耳机键完全不参与，开始录音靠连耳机自动开始或手动点 */
  static readonly OFF: string = 'off';

  static all(): string[] {
    return [HeadsetMode.RECORDING_ONLY, HeadsetMode.ALWAYS, HeadsetMode.OFF];
  }

  static label(mode: string): string {
    if (mode === HeadsetMode.ALWAYS) {
      return '全程接管';
    }
    if (mode === HeadsetMode.OFF) {
      return '不接管';
    }
    return '只在录音中';
  }

  static hint(mode: string): string {
    if (mode === HeadsetMode.ALWAYS) {
      return '不录音时按播放键就开始记录。代价是：想听音乐时按它也会开始录音，得在手机上点播放。';
    }
    if (mode === HeadsetMode.OFF) {
      return '耳机键只给音乐。开始记录走「连上耳机自动开始」或今日页按钮。';
    }
    return '录音时按播放/暂停键结束记录，下一首键打一个重点；不录音时键完全还给音乐，互不干扰。';
  }
}

export interface HeadsetKeyEvents {
  /** 耳机播放键、且当前没在录音 */
  onStart: () => void;
  /** 耳机暂停/停止键、且正在录音 */
  onStop: () => void;
  /** 下一首 / 上一首键 → 打一个重点标记 */
  onMark: () => void;
  /** 接管状态变化（给界面显示「耳机键：管录音 / 管音乐」） */
  onMode: (active: boolean, mode: string) => void;
  onError: (msg: string) => void;
}

/**
 * 耳机键接管
 *
 * 实现方式：注册一个类型为 audio 的 AVSession。
 *
 * - 录音中：`activate()` + 播放状态置为 PLAY。此时系统认为「草记在播放」，
 *   耳机上的播放/暂停键会送到我们的 `pause` 回调 → 结束录音。
 * - 空闲：`deactivate()`（或 playbackState 置为 PAUSE 后保持活跃，用于 ALWAYS 档）。
 *   只要我们不活跃，系统就把键还给音乐 App。
 *
 * 为什么不用「监听音量键」那条路：音量键是系统保留键，第三方应用拿不到，
 * 而且它会改音量——录音时按一下把手机调成静音，用户会以为坏了。
 *
 * 注意：AVSession 只是「会话」，不要求真的在输出声音。语音备忘类应用普遍这么做，
 * 不需要额外权限（后台响应按键靠录音已有的长时任务）。
 */
export class HeadsetKeys {
  private static inst: HeadsetKeys | null = null;

  static get(): HeadsetKeys {
    if (HeadsetKeys.inst === null) {
      HeadsetKeys.inst = new HeadsetKeys();
    }
    return HeadsetKeys.inst;
  }

  private ctx: common.UIAbilityContext | null = null;
  private events: HeadsetKeyEvents | null = null;
  private session: avSession.AVSession | null = null;
  private mode: string = HeadsetMode.RECORDING_ONLY;
  private recording: boolean = false;
  /** 当前是否已经把自己激活成「那个拿到键的会话」 */
  private active: boolean = false;

  bind(ctx: common.UIAbilityContext, events: HeadsetKeyEvents): void {
    this.ctx = ctx;
    this.events = events;
  }

  /** 切档位：设置页改完立刻生效，不用等下次录音 */
  async applyMode(mode: string): Promise<void> {
    this.mode = mode;
    await this.sync();
  }

  /** 录音开始/结束：只在 RECORDING_ONLY 档下改变「要不要接管」 */
  async setRecording(on: boolean): Promise<void> {
    if (this.recording === on) {
      return;
    }
    this.recording = on;
    await this.sync();
  }

  isActive(): boolean {
    return this.active;
  }

  // ==================== 会话生命周期 ====================

  private async ensureSession(): Promise<avSession.AVSession | null> {
    if (this.session !== null) {
      return this.session;
    }
    const ctx = this.ctx;
    if (ctx === null) {
      return null;
    }
    try {
      const s: avSession.AVSession = await avSession.createAVSession(
        ctx, 'caoji_record', 'audio'
      );
      // 播放键：只有「没在录音」时才响应。录音中我们标的是 PLAY，
      // 系统那一下会走 pause 分支，不会进这里
      s.on('play', () => {
        if (this.recording) {
          return;
        }
        const ev = this.events;
        if (ev !== null) {
          ev.onStart();
        }
      });
      // 暂停 / 停止：录音中按一下就收工
      s.on('pause', () => {
        this.fireStop();
      });
      s.on('stop', () => {
        this.fireStop();
      });
      // 下一首：双击耳机的常见键位，拿来打重点最顺手
      s.on('playNext', () => {
        this.fireMark();
      });
      s.on('playPrevious', () => {
        this.fireMark();
      });
      this.session = s;
      return s;
    } catch (e) {
      const err = e as BusinessError;
      this.emitError(`耳机键控制不可用：${err.message}（${err.code}）`);
      return null;
    }
  }

  private fireStop(): void {
    if (!this.recording) {
      return;
    }
    const ev = this.events;
    if (ev !== null) {
      ev.onStop();
    }
  }

  private fireMark(): void {
    if (!this.recording) {
      return;
    }
    const ev = this.events;
    if (ev !== null) {
      ev.onMark();
    }
  }

  private wantActive(): boolean {
    if (this.mode === HeadsetMode.OFF) {
      return false;
    }
    if (this.mode === HeadsetMode.ALWAYS) {
      return true;
    }
    // RECORDING_ONLY：只有录音中才抢键
    return this.recording;
  }

  private async sync(): Promise<void> {
    if (!this.wantActive()) {
      await this.detach();
      return;
    }
    const s = await this.ensureSession();
    if (s === null) {
      return;
    }
    try {
      // 元数据写清楚，耳机/锁屏上显示的是这句，而不是「未知媒体」
      // 注：AVMetadata.displayTags 在 API 12 是 number 位掩码，不是字符串数组，
      //     这里没有对应的公开常量，干脆不写 —— 标题/专辑两行已经够识别了
      const meta: avSession.AVMetadata = {
        assetId: 'caoji_record',
        title: this.recording ? '草记 · 正在记录' : '草记 · 按播放键开始记录',
        artist: '草记',
        album: this.recording ? '录音中，按暂停键结束' : '空闲'
      };
      await s.setAVMetadata(meta);
      const state: avSession.AVPlaybackState = {
        state: this.recording
          ? avSession.PlaybackState.PLAYBACK_STATE_PLAY
          : avSession.PlaybackState.PLAYBACK_STATE_PAUSE,
        speed: 1.0
      };
      await s.setAVPlaybackState(state);
      await s.activate();
      this.active = true;
      this.emitMode();
    } catch (e) {
      const err = e as BusinessError;
      this.active = false;
      this.emitError(`耳机键接管失败：${err.message}（${err.code}）`);
      this.emitMode();
    }
  }

  /** 让出键：键会回到系统认为的上一个媒体会话（通常是音乐 App） */
  private async detach(): Promise<void> {
    const s = this.session;
    if (s === null) {
      if (this.active) {
        this.active = false;
        this.emitMode();
      }
      return;
    }
    try {
      await s.setAVPlaybackState({
        state: avSession.PlaybackState.PLAYBACK_STATE_PAUSE,
        speed: 1.0
      });
    } catch (e) {
      // 会话已失效时忽略
    }
    try {
      await s.deactivate();
    } catch (e) {
      // 同上
    }
    if (this.active) {
      this.active = false;
      this.emitMode();
    }
  }

  /** 应用退出时调一次，避免桌面还挂着一条「草记」会话 */
  async release(): Promise<void> {
    const s = this.session;
    this.session = null;
    this.active = false;
    if (s === null) {
      return;
    }
    try {
      await s.deactivate();
    } catch (e) {
      // 忽略
    }
    try {
      await s.destroy();
    } catch (e) {
      // 忽略
    }
  }

  private emitMode(): void {
    const ev = this.events;
    if (ev !== null) {
      ev.onMode(this.active, this.mode);
    }
  }

  private emitError(msg: string): void {
    const ev = this.events;
    if (ev !== null) {
      ev.onError(msg);
    }
  }
}
