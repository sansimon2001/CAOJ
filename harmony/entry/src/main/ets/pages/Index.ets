import { common } from '@kit.AbilityKit';
import { promptAction, router } from '@kit.ArkUI';
import { abilityAccessCtrl, Permissions } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Repo, Keys } from '../data/Repo';
import { Recorder, RecState, RecorderEvents } from '../service/Recorder';
import { BluetoothWatch } from '../service/BluetoothWatch';
import { HeadsetKeys, HeadsetMode, HeadsetKeyEvents } from '../service/HeadsetKeys';
import { WatchLink, WatchLinkEvents } from '../service/WatchLink';
import { Pipeline } from '../service/Pipeline';
import { Importer } from '../util/Importer';
import { ZhNormalizer } from '../util/ZhNormalizer';
import { TodayView } from '../view/TodayView';
import { ProjectsView } from '../view/ProjectsView';
import { InsightView } from '../view/InsightView';
import { SettingsView } from '../view/SettingsView';

/**
 * 首页容器：四个分页 + 自绘底栏
 *
 * 分工：
 *   今日 —— 录音主控与记录流（最常用，放第一个）
 *   项目 —— 按项目聚合
 *   觉察 —— 统计、待办、行为复盘
 *   设置 —— 配置与法务
 *
 * 录音器、蓝牙监听、权限申请都在这里统一持有：它们跨分页存活，
 * 放在子视图里会随分页切换被销毁，导致「切到设置页录音就断了」。
 */
@Entry
@Component
struct Index {
  @State current: number = 0;
  @State showConsent: boolean = false;
  @State consentText: string = '';
  @StorageLink('recording') recording: boolean = false;
  @StorageLink('waiting') waiting: boolean = false;
  @StorageLink('headset') headset: boolean = false;
  @StorageLink('headsetName') headsetName: string = '';

  private recorder: Recorder = Recorder.get();
  private watch: BluetoothWatch = new BluetoothWatch();
  private keys: HeadsetKeys = HeadsetKeys.get();
  private link: WatchLink = WatchLink.get();
  private keysBound: boolean = false;
  private linkBound: boolean = false;
  private inited: boolean = false;
  /** 本次会话是否已同意隐私须知（未同意不申请权限、不开蓝牙监听） */
  private consented: boolean = false;

  aboutToAppear(): void {
    // 繁简表在 rawfile 里，注入一次上下文，之后转写与详情页都能直接用
    ZhNormalizer.init(this.ctx());
    AppStorage.setOrCreate('elapsed', 0);
    AppStorage.setOrCreate('segIndex', 1);
    AppStorage.setOrCreate('markCount', 0);
    AppStorage.setOrCreate('refreshTick', Date.now());
    AppStorage.setOrCreate('headsetKeyActive', false);
    AppStorage.setOrCreate('headsetKeyMode', HeadsetMode.RECORDING_ONLY);
    AppStorage.setOrCreate('watchLinkActive', false);
    AppStorage.setOrCreate('watchLinkInfo', '');
    this.bindRecorder();
    // 首启先过隐私门：华为审核要求「未同意前不得申请权限、不得联网」
    this.consented = Repo.getBool(Keys.PRIVACY_ACK, false);
    this.showConsent = !this.consented;
    this.loadConsentText();
  }

  onPageShow(): void {
    if (!this.consented) {
      // 从设置里的声明页返回、或冷启动未同意：都停在这里，不申请任何权限
      this.showConsent = true;
      return;
    }
    this.requestMic();
    this.startBluetoothWatch();
    this.bindHeadsetKeys();
    this.bindWatchLink();
    this.consumeSharedText();
    AppStorage.setOrCreate('refreshTick', Date.now());
  }

  aboutToDisappear(): void {
    this.watch.stop();
  }

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  private bindRecorder(): void {
    const events: RecorderEvents = {
      onState: (state: string) => {
        AppStorage.setOrCreate('recording', state === RecState.RECORDING);
        AppStorage.setOrCreate('waiting', state === RecState.WAITING);
        AppStorage.setOrCreate('segIndex', this.recorder.segment());
        AppStorage.setOrCreate('markCount', this.recorder.markCount());
        if (state === RecState.IDLE) {
          AppStorage.setOrCreate('elapsed', 0);
        }
        // 耳机键跟着录音状态走：录起来了才去抢键，录完立刻还回去（RECORDING_ONLY 档）
        this.keys.setRecording(state === RecState.RECORDING).then(() => {
        }).catch(() => {
        });
        // 手表那边：状态一变就推一次（不按每秒推，见 WatchLink.update）
        this.link.update(state, this.recorder.elapsedMs(), this.recorder.segment(), this.recorder.markCount());
        this.link.pushNow();
      },
      onTick: (elapsedMs: number) => {
        AppStorage.setOrCreate('elapsed', elapsedMs);
        // 只更新快照，不发包：手表自己跳秒
        this.link.update(RecState.RECORDING, elapsedMs, this.recorder.segment(), this.recorder.markCount());
      },
      onSegment: (path: string, startAt: number, durationMs: number, index: number, reason: string) => {
        Pipeline.ingest(path, startAt, durationMs, index, reason).then(() => {
          AppStorage.setOrCreate('refreshTick', Date.now());
        });
      },
      onError: (message: string) => {
        promptAction.showToast({ message: message, duration: 2600 });
      }
    };
    this.recorder.bind(this.ctx(), events);
  }

  private requestMic(): void {
    const ctx = this.ctx();
    const perms: Permissions[] = ['ohos.permission.MICROPHONE'];
    const atManager = abilityAccessCtrl.createAtManager();
    atManager.requestPermissionsFromUser(ctx, perms).then((result) => {
      const denied = result.authResults.length > 0 && result.authResults[0] !== 0;
      if (denied) {
        promptAction.showToast({ message: '没有麦克风权限：录音功能不可用，其余功能正常', duration: 3000 });
      }
    }).catch((err: BusinessError) => {
      console.error(`申请权限失败：${err.message}`);
    });
  }

  private startBluetoothWatch(): void {
    this.watch.start({
      onConnected: (name: string) => {
        AppStorage.setOrCreate('headset', true);
        AppStorage.setOrCreate('headsetName', name);
        const auto = Repo.getBool(Keys.AUTO_RECORD, true);
        if (auto && !this.recorder.isActive()) {
          this.recorder.start(true).then((ok: boolean) => {
            promptAction.showToast({
              message: ok ? `${name} 已连接，开始记录` : '耳机已连接，但录音启动失败（检查麦克风权限）'
            });
          });
        } else if (!this.recorder.isActive()) {
          promptAction.showToast({ message: `${name} 已连接，点「开始记录」即可` });
        }
      },
      onDisconnected: () => {
        AppStorage.setOrCreate('headset', false);
        if (!this.recorder.isActive()) {
          return;
        }
        // 通话中音频通路会从 A2DP 切到 SCO，表现之一就是「耳机断开一下」：
        // 这时不能停记录，否则通话前那段被截断、通话后更是全听不到
        if (Recorder.isAudioSceneBusy()) {
          promptAction.showToast({ message: '通话占用了耳机通路，记录继续（结束后自动续录）' });
          return;
        }
        if (this.recorder.isStartedByBluetooth()) {
          this.recorder.stop('bt').then(() => {
            promptAction.showToast({ message: '耳机已断开，记录结束' });
            AppStorage.setOrCreate('refreshTick', Date.now());
          });
        } else {
          // 手动开始的记录不被耳机状态带走（可能是接了个电话，或耳机没电了）
          promptAction.showToast({ message: '耳机已断开，记录继续' });
        }
      }
    });
  }

  /**
   * 耳机键接管
   *
   * 蓝牙耳机上只有一组播放/暂停键，系统把它送给「当前活跃的媒体会话」。
   * 录音时草记把自己激活成那个会话，键就管录音；录完退出，键回到音乐 App。
   * 三档由设置页决定（见 HeadsetMode）：只在录音中 / 全程 / 不接管。
   */
  private bindHeadsetKeys(): void {
    if (this.keysBound) {
      return;
    }
    this.keysBound = true;
    const events: HeadsetKeyEvents = {
      onStart: () => {
        this.remoteStart();
      },
      onStop: () => {
        this.remoteStop();
      },
      onMark: () => {
        this.recorder.markPoint();
        AppStorage.setOrCreate('markCount', this.recorder.markCount());
        promptAction.showToast({ message: `已打点（第 ${this.recorder.markCount()} 处）`, duration: 1400 });
      },
      onMode: (active: boolean, mode: string) => {
        AppStorage.setOrCreate('headsetKeyActive', active);
        AppStorage.setOrCreate('headsetKeyMode', mode);
      },
      onError: (msg: string) => {
        promptAction.showToast({ message: msg, duration: 2600 });
      }
    };
    this.keys.bind(this.ctx(), events);
    const mode = Repo.getStr(Keys.HEADSET_MODE, HeadsetMode.RECORDING_ONLY);
    this.keys.applyMode(mode).then(() => {
    }).catch(() => {
    });
  }

  private remoteStart(): void {
    if (this.recorder.isActive()) {
      return;
    }
    this.recorder.start(false).then((ok: boolean) => {
      promptAction.showToast({ message: ok ? '已开始记录' : '收到指令，但录音启动失败（检查麦克风权限）' });
    });
  }

  private remoteStop(): void {
    if (!this.recorder.isActive()) {
      return;
    }
    this.recorder.stop('headset').then(() => {
      promptAction.showToast({ message: '记录已结束' });
      AppStorage.setOrCreate('refreshTick', Date.now());
    });
  }

  /**
   * 手表遥控
   *
   * 手表（安卓表）和手机在同一个 Wi-Fi 下，手表发几个字节的 UDP 就能控制开始/结束/打点。
   * 为什么不用 Wear OS 官方通道：鸿蒙手机没有 GMS，Wearable Data Layer 那条路不存在，
   * 详见 service/WatchLink.ets 顶部说明。
   *
   * 手表端不碰麦克风、不做转写、不上传——所以它的耗电约等于一块普通表盘。
   */
  private bindWatchLink(): void {
    if (this.linkBound) {
      return;
    }
    this.linkBound = true;
    const events: WatchLinkEvents = {
      onStart: () => {
        this.remoteStart();
      },
      onStop: () => {
        this.remoteStop();
      },
      onMark: () => {
        this.recorder.markPoint();
        AppStorage.setOrCreate('markCount', this.recorder.markCount());
        promptAction.showToast({ message: `手表打点（第 ${this.recorder.markCount()} 处）`, duration: 1400 });
      },
      onStatus: (active: boolean, info: string) => {
        AppStorage.setOrCreate('watchLinkActive', active);
        AppStorage.setOrCreate('watchLinkInfo', info);
      },
      onError: (msg: string) => {
        promptAction.showToast({ message: msg, duration: 2600 });
      }
    };
    this.link.bind(events);
    if (Repo.getBool(Keys.WATCH_LINK, false)) {
      this.link.start().then(() => {
      }).catch(() => {
      });
    }
  }

  private consumeSharedText(): void {
    const text = AppStorage.get<string>('pendingSharedText');
    if (text === undefined || text.length === 0) {
      return;
    }
    AppStorage.setOrCreate('pendingSharedText', '');
    Importer.importSharedText(this.ctx(), text).then((id: number) => {
      if (id > 0) {
        promptAction.showToast({ message: '已接收分享，正在整理…' });
        this.current = 0;
        AppStorage.setOrCreate('refreshTick', Date.now());
      }
    });
  }

  // ==================== 首次启动隐私同意 ====================

  /**
   * 未同意前不申请权限、不联网、不开蓝牙监听。
   * 华为应用市场明确要求：用户同意隐私政策之前，不得申请任何敏感权限，
   * 否则以「提前申请权限」为由驳回。
   */
  private loadConsentText(): void {
    this.consentText = '一、录音只存在这台设备上\n'
      + '按下开始后，音频写入应用自己的沙箱目录，不上传到任何服务器。在「设置 → 隐私与安全」里可以关闭保留原音，'
      + '这样整理完成（或转写完成）后音频会被自动删除；也可以用「清除全部数据」一次性删掉所有记录与音频。\n\n'
      + '二、转写与摘要需要你自己配置的第三方服务\n'
      + '本应用不自建服务器。只有在「设置 → AI 服务」里填入你自己的 API Key 之后，音频才会发往你指定的转写服务，'
      + '文字才会发往你指定的大模型服务。用哪一家、传什么，由你自己决定；不填则转写与摘要都不会发生。\n\n'
      + '三、群机器人推送是主动行为\n'
      + '只有在「设置 → 打通与分发」里配好飞书 / 钉钉机器人或 Webhook 之后，整理结果才会被推到对应的群里。'
      + '推送内容是摘要与待办，不含音频；不配则不会外发任何内容。\n\n'
      + '四、申请的权限与用途\n'
      + '麦克风：录制你的对话记录，这是本应用的核心功能；\n'
      + '后台运行：蓝牙耳机连上后自动开始记录，需要在前台退出后继续录；\n'
      + '蓝牙：仅读取耳机连接状态，用来判断何时自动开始与结束，不扫描、不连接其它设备；\n'
      + '网络：只用于你配置的转写 / 大模型 / 机器人地址，不做任何埋点与统计上报。\n\n'
      + '五、合法使用与免责\n'
      + '请只录制你参与的、或已获得在场人员知情的对话，遵守当地法律法规。'
      + '在禁止录音的场所、或未经同意录制他人隐私内容，责任由使用者自负。'
      + '本应用按「现状」提供，不对转写与摘要的准确性作保证，重要内容请自行核对。\n\n'
      + '点「同意并开始使用」表示你已阅读并接受上述内容；点「不同意」将退出应用。完整条款见「设置 → 关于与法务 → 法律免责声明」。';
  }

  private agreeConsent(): void {
    this.consented = true;
    this.showConsent = false;
    Repo.setBool(Keys.PRIVACY_ACK, true).then(() => {
      // 同意之后才申请权限、才开蓝牙监听
      this.requestMic();
      this.startBluetoothWatch();
      this.bindHeadsetKeys();
      this.bindWatchLink();
      this.consumeSharedText();
      AppStorage.setOrCreate('refreshTick', Date.now());
    });
  }

  private refuseConsent(): void {
    this.ctx().terminateSelf();
  }

  @Builder
  consentLayer() {
    Column() {
      Column() {
        Text('隐私与使用须知')
          .fontSize(19)
          .fontWeight(FontWeight.Bold)
          .fontColor('#1A1A1A')
          .width('100%')
        Text('草记会在你确认后开始工作。请先看下面这五条——都是和你直接相关的。')
          .fontSize(12.5)
          .fontColor('#6B6B6B')
          .lineHeight(19)
          .margin({ top: 6 })
          .width('100%')

        Scroll() {
          Text(this.consentText)
            .fontSize(12.5)
            .fontColor('#333333')
            .lineHeight(20)
            .width('100%')
        }
        .height(292)
        .scrollBar(BarState.Auto)
        .margin({ top: 12 })
        .padding(12)
        .backgroundColor('#FAFAFA')
        .borderRadius(12)
        .align(Alignment.TopStart)

        Text('查看完整法律免责声明 ›')
          .fontSize(12.5)
          .fontColor('#E60012')
          .margin({ top: 12 })
          .onClick(() => {
            router.pushUrl({ url: 'pages/LegalPage' });
          })

        Button('同意并开始使用')
          .width('100%')
          .height(48)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor('#FFFFFF')
          .backgroundColor('#E60012')
          .borderRadius(24)
          .margin({ top: 16 })
          .onClick(() => {
            this.agreeConsent();
          })

        Text('不同意')
          .fontSize(13)
          .fontColor('#9E9E9E')
          .width('100%')
          .textAlign(TextAlign.Center)
          .padding({ top: 12, bottom: 2 })
          .onClick(() => {
            this.refuseConsent();
          })
      }
      .width('90%')
      .padding(20)
      .backgroundColor('#FFFFFF')
      .borderRadius(20)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#99000000')
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  bottomBar() {
    Row() {
      this.tabItem('今日', 0)
      this.tabItem('项目', 1)
      this.tabItem('觉察', 2)
      this.tabItem('设置', 3)
    }
    .width('100%')
    .height(58)
    .backgroundColor('#FFFFFF')
    .border({ width: { top: 1 }, color: '#EFEFEF' })
    .padding({ bottom: 4 })
  }

  @Builder
  tabItem(label: string, index: number) {
    Column() {
      if (index === 0 && this.recording) {
        Circle()
          .width(6)
          .height(6)
          .fill('#E60012')
          .margin({ bottom: 2 })
      }
      Text(label)
        .fontSize(12)
        .fontWeight(this.current === index ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.current === index ? '#E60012' : '#8A8A8A')
      Circle()
        .width(4)
        .height(4)
        .fill(this.current === index ? '#E60012' : '#00000000')
        .margin({ top: 5 })
    }
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .onClick(() => {
      this.current = index;
      AppStorage.setOrCreate('refreshTick', Date.now());
    })
  }

  build() {
    Stack({ alignContent: Alignment.Center }) {
      Column() {
        if (this.current === 0) {
          TodayView()
        } else if (this.current === 1) {
          ProjectsView()
        } else if (this.current === 2) {
          InsightView()
        } else {
          SettingsView()
        }
        this.bottomBar()
      }
      .width('100%')
      .height('100%')
      .backgroundColor('#FFFFFF')

      if (this.showConsent) {
        this.consentLayer()
      }
    }
    .width('100%')
    .height('100%')
  }
}
