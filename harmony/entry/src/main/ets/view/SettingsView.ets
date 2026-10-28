import { router, promptAction, window } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { deviceInfo, pasteboard } from '@kit.BasicServicesKit';
import { Repo, Keys } from '../data/Repo';
import { Providers, LlmProvider, AsrProvider } from '../service/Providers';
import { SpeechLang, SpeechLangs, LangPlan } from '../service/SpeechLang';
import { CollabBridge, Platform } from '../service/CollabBridge';
import { HeadsetKeys, HeadsetMode } from '../service/HeadsetKeys';
import { WatchLink } from '../service/WatchLink';
import { Importer } from '../util/Importer';
import { Contact } from '../util/Legal';

/** 剪贴板小工具（设置页多处用到） */
class ExportCopy {
  static async copy(text: string): Promise<void> {
    const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text);
    await pasteboard.getSystemPasteboard().setData(data);
    promptAction.showToast({ message: '已复制' });
  }
}

@Styles
function cardStyle() {
  .width('100%')
  .backgroundColor('#FFFFFF')
  .borderRadius(16)
  .border({ width: 1, color: '#EFEFEF' })
}

/**
 * 设置
 *
 * v1.18 改版的思路：原来是 740 行控件一路平铺，用户永远不知道「我配好了没有」。
 * 现在顶部先给结论（状态概览卡），下面 5 张可折叠分组卡，只展开你要改的那一组。
 */
@Component
export struct SettingsView {
  @StorageLink('refreshTick') refreshTick: number = 0;

  @State openAi: boolean = false;
  @State openRec: boolean = false;
  @State openInterop: boolean = false;
  @State openPrivacy: boolean = false;
  @State openAbout: boolean = false;

  @State llmOptions: SelectOption[] = [];
  @State asrOptions: SelectOption[] = [];
  @State langOptions: SelectOption[] = [];
  @State llmIndex: number = 0;
  @State asrIndex: number = 0;
  @State llmBase: string = '';
  @State llmModel: string = '';
  @State llmKeyInput: string = '';
  @State asrUrl: string = '';
  @State asrModel: string = '';
  @State asrKeyInput: string = '';
  @State diarize: boolean = false;
  @State diarizeHint: string = '';
  /** 识别语言下拉的选中项（对应 SpeechLangs 清单） */
  @State langIndex: number = 1;
  @State langHint: string = '';
  @State langNote: string = '';
  /** 热词表：人名、公司名、专业词 */
  @State hotwords: string = '';
  /** 方言稿自动转普通话书面语 */
  @State dialectStd: boolean = true;

  @State autoRecord: boolean = true;
  @State btMic: boolean = false;
  /** 耳机键归谁：recording_only / always / off */
  @State headsetMode: string = HeadsetMode.RECORDING_ONLY;
  /** 手表遥控 */
  @State watchLink: boolean = false;
  @StorageLink('watchLinkActive') watchLinkActive: boolean = false;
  @StorageLink('watchLinkInfo') watchLinkInfo: string = '';
  @State keepAudio: boolean = false;

  @State fsHook: string = '';
  @State fsSecret: string = '';
  @State dtHook: string = '';
  @State dtSecret: string = '';
  @State wbHook: string = '';
  @State wbToken: string = '';
  @State wbAuto: boolean = true;
  @State interopState: string = '';

  @State secureWindow: boolean = true;
  @State summary: string = '';
  @State busy: boolean = false;

  aboutToAppear(): void {
    this.buildOptions();
    this.loadAll();
  }

  onRefresh(): void {
    this.loadAll();
  }

  private buildOptions(): void {
    const l: SelectOption[] = [];
    for (let i = 0; i < Providers.LLM.length; i++) {
      l.push({ value: Providers.LLM[i].name });
    }
    this.llmOptions = l;
    const a: SelectOption[] = [];
    for (let i = 0; i < Providers.ASR.length; i++) {
      a.push({ value: Providers.ASR[i].name });
    }
    this.asrOptions = a;
    const langs: SelectOption[] = [];
    const all = SpeechLangs.all();
    for (let i = 0; i < all.length; i++) {
      langs.push({ value: all[i].label });
    }
    this.langOptions = langs;
  }

  private loadAll(): void {
    const llmId = Repo.getStr(Keys.LLM_PROVIDER, Providers.DEFAULT_LLM_ID);
    const asrId = Repo.getStr(Keys.ASR_PROVIDER, Providers.DEFAULT_ASR_ID);
    this.llmIndex = this.indexOfLlm(llmId);
    this.asrIndex = this.indexOfAsr(asrId);
    this.llmBase = Repo.getStr(Keys.LLM_BASE);
    this.llmModel = Repo.getStr(Keys.LLM_MODEL);
    this.llmKeyInput = Repo.getStr(Keys.LLM_KEY);
    this.asrUrl = Repo.getStr(Keys.ASR_URL);
    this.asrModel = Repo.getStr(Keys.ASR_MODEL);
    this.asrKeyInput = Repo.getStr(Keys.ASR_KEY);
    this.diarize = Repo.getBool(Keys.ASR_DIARIZE, false);
    this.hotwords = Repo.getStr(Keys.ASR_PROMPT);
    this.dialectStd = Repo.getBool(Keys.DIALECT_STD, true);
    this.langIndex = SpeechLangs.indexOf(Repo.getStr(Keys.ASR_LANG, SpeechLangs.ID_ZH));
    this.refreshDiarizeHint();
    this.refreshLangHint();

    this.autoRecord = Repo.getBool(Keys.AUTO_RECORD, true);
    this.btMic = Repo.getBool(Keys.BT_MIC, false);
    this.headsetMode = Repo.getStr(Keys.HEADSET_MODE, HeadsetMode.RECORDING_ONLY);
    this.watchLink = Repo.getBool(Keys.WATCH_LINK, false);
    this.keepAudio = Repo.getBool(Keys.KEEP_AUDIO, false);

    this.fsHook = Repo.getStr(Keys.FS_HOOK);
    this.fsSecret = Repo.getStr(Keys.FS_SECRET);
    this.dtHook = Repo.getStr(Keys.DT_HOOK);
    this.dtSecret = Repo.getStr(Keys.DT_SECRET);
    this.wbHook = Repo.getStr(Keys.WB_WEBHOOK);
    this.wbToken = Repo.getStr(Keys.WB_TOKEN);
    this.wbAuto = Repo.getBool(Keys.WB_AUTO, true);

    this.secureWindow = Repo.getBool(Keys.SECURE_WINDOW, true);

    // 概览卡的结论：按「缺什么」给下一步，而不是罗列所有配置项
    let s = '';
    if (!Repo.hasLlm()) {
      s = '还差一步：填一个 API Key（硅基流动默认已填好地址与模型）\n填好后录音结束会自动转写 + 生成摘要与待办。';
      this.openAi = true;
    } else if (!Repo.hasAsr()) {
      s = '大模型已就绪；转写服务还没配，录音会停在「待转写」';
    } else {
      s = `大模型：${Providers.llmById(this.providerId()).name}\n转写：${Providers.asrById(this.asrProviderId()).name}\n`;
      s += this.fsHook.length > 0 ? '飞书群已接通 · ' : '';
      s += this.dtHook.length > 0 ? '钉钉群已接通 · ' : '';
      s += this.wbHook.length > 0 ? 'WorkBuddy Webhook 已接通' : '';
      if (this.fsHook.length === 0 && this.dtHook.length === 0 && this.wbHook.length === 0) {
        s += '对外打通：未配置（可选）';
      }
    }
    this.summary = s;
  }

  private providerId(): string {
    return Providers.LLM[this.llmIndex].id;
  }

  private asrProviderId(): string {
    return Providers.ASR[this.asrIndex].id;
  }

  private indexOfLlm(id: string): number {
    for (let i = 0; i < Providers.LLM.length; i++) {
      if (Providers.LLM[i].id === id) {
        return i;
      }
    }
    return 0;
  }

  private indexOfAsr(id: string): number {
    for (let i = 0; i < Providers.ASR.length; i++) {
      if (Providers.ASR[i].id === id) {
        return i;
      }
    }
    return 0;
  }

  private refreshDiarizeHint(): void {
    const p = Providers.asrById(this.asrProviderId());
    if (!p.supportsDiarize) {
      this.diarizeHint = '当前服务只返回纯文本，不提供说话人信息：开了也不会有效果，所以这里置灰';
      return;
    }
    // 服务端能返回 speaker 不代表鸿蒙端能认「哪个是我」：那要靠本地声纹比对
    // （MFCC 特征 + 中心化余弦），鸿蒙版尚未实现，这里如实说明，不要让人以为开了就自动区分我/对方
    this.diarizeHint = '该服务会返回每个片段的说话人标签（说话人1 / 说话人2）；'
      + '鸿蒙版暂不做本地声纹比对，所以不会自动标出「我」，需要你自己看内容判断';
  }

  /**
   * 语言说明 + 降级实情
   *
   * 用户选了上海话而服务端只认普通话时，必须在设置页就说清楚，
   * 不能等到转写结果一堆错字才让人自己猜。
   */
  private refreshLangHint(): void {
    const lang: SpeechLang = SpeechLangs.byId(this.currentLangId());
    const provider: AsrProvider = Providers.asrById(this.asrProviderId());
    const plan: LangPlan = SpeechLangs.plan(lang, provider);
    this.langHint = lang.hint;
    this.langNote = plan.downgraded ? plan.note : '';
  }

  private currentLangId(): string {
    const all = SpeechLangs.all();
    if (this.langIndex < 0 || this.langIndex >= all.length) {
      return SpeechLangs.ID_ZH;
    }
    return all[this.langIndex].id;
  }

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  // ==================== 通用小组件 ====================

  @Builder
  groupHeader(title: string, sub: string, isOpen: boolean, onToggle: () => void) {
    Row() {
      Column() {
        Text(title)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor('#1A1A1A')
        Text(sub)
          .fontSize(11)
          .fontColor('#9E9E9E')
          .margin({ top: 3 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text(isOpen ? '︿' : '﹀')
        .fontSize(13)
        .fontColor('#BDBDBD')
    }
    .width('100%')
    .padding(14)
    .onClick(() => {
      onToggle();
    })
  }

  @Builder
  label(text: string) {
    Text(text)
      .fontSize(11)
      .fontColor('#6B6B6B')
      .margin({ top: 10, bottom: 5 })
      .width('100%')
  }

  @Builder
  switchRow(title: string, value: boolean, onSwitch: (v: boolean) => void) {
    Row() {
      Text(title)
        .fontSize(13)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: value })
        .selectedColor('#E60012')
        .onChange((on: boolean) => {
          onSwitch(on);
        })
    }
    .width('100%')
    .padding({ top: 8, bottom: 8 })
  }

  @Builder
  hint(text: string) {
    Text(text)
      .fontSize(11)
      .fontColor('#9E9E9E')
      .lineHeight(17)
      .padding(10)
      .backgroundColor('#FAFAFA')
      .borderRadius(8)
      .margin({ top: 8 })
      .width('100%')
  }

  @Builder
  smallButton(text: string, primary: boolean, onClick: () => void) {
    Text(text)
      .fontSize(12)
      .fontColor(primary ? '#FFFFFF' : '#6B6B6B')
      .textAlign(TextAlign.Center)
      .height(40)
      .layoutWeight(1)
      .backgroundColor(primary ? '#E60012' : '#FFFFFF')
      .borderRadius(20)
      .border({ width: 1, color: primary ? '#E60012' : '#E8E8E8' })
      .onClick(() => {
        onClick();
      })
  }

  /**
   * 三选一胶囊组
   *
   * 用胶囊而不是下拉：这三档的差别是「耳机键归谁」，用户需要同时看到三个选项
   * 才能明白「原来是让给音乐了」，下拉只会让他以为自己选错了。
   */
  @Builder
  segRow(title: string, values: string[], labels: string[], current: string, onPick: (v: string) => void) {
    Column() {
      Text(title)
        .fontSize(13)
        .fontColor('#1A1A1A')
        .width('100%')
        .margin({ top: 8, bottom: 8 })
      Row() {
        ForEach(labels, (label: string, i: number) => {
          Text(label)
            .fontSize(12)
            .fontColor(current === values[i] ? '#FFFFFF' : '#6B6B6B')
            .textAlign(TextAlign.Center)
            .height(36)
            .layoutWeight(1)
            .backgroundColor(current === values[i] ? '#E60012' : '#FFFFFF')
            .borderRadius(18)
            .border({ width: 1, color: current === values[i] ? '#E60012' : '#E8E8E8' })
            .margin({ left: i === 0 ? 0 : 8 })
            .onClick(() => {
              onPick(values[i]);
            })
        }, (label: string) => label)
      }
      .width('100%')
    }
    .width('100%')
  }

  // ==================== 分组内容 ====================

  @Builder
  aiGroup() {
    Column() {
      this.groupHeader('AI 服务', '大模型写摘要，转写服务听声音', this.openAi, () => {
        this.openAi = !this.openAi;
      })
      if (this.openAi) {
        Column() {
          this.label('大模型服务商')
          Select(this.llmOptions)
            .selected(this.llmIndex)
            .value(Providers.LLM[this.llmIndex].name)
            .width('100%')
            .onSelect((index: number) => {
              this.llmIndex = index;
              const p: LlmProvider = Providers.LLM[index];
              if (p.base.length > 0) {
                this.llmBase = p.base;
                this.llmModel = p.models.length > 0 ? p.models[0] : '';
              }
              this.refreshDiarizeHint();
            })

          this.hint(Providers.LLM[this.llmIndex].costNote)

          this.label('接口地址（Base URL）')
          TextInput({ text: this.llmBase, placeholder: 'https://api.siliconflow.cn/v1' })
            .fontSize(13)
            .height(44)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.llmBase = v;
            })

          this.label('API Key')
          TextInput({ text: this.llmKeyInput, placeholder: '粘贴你的 API Key' })
            .type(InputType.Password)
            .showPasswordIcon(true)
            .fontSize(13)
            .height(44)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.llmKeyInput = v;
            })

          this.label('模型名')
          TextInput({ text: this.llmModel, placeholder: '如 Qwen/Qwen2.5-7B-Instruct' })
            .fontSize(13)
            .height(44)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.llmModel = v;
            })

          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(Providers.LLM[this.llmIndex].models, (m: string) => {
              Text(m)
                .fontSize(10)
                .fontColor('#6B6B6B')
                .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .backgroundColor('#F5F5F5')
                .borderRadius(6)
                .margin({ right: 6, top: 6 })
                .onClick(() => {
                  this.llmModel = m;
                })
            }, (m: string) => m)
          }
          .width('100%')

          this.label('转写服务商')
          Select(this.asrOptions)
            .selected(this.asrIndex)
            .value(Providers.ASR[this.asrIndex].name)
            .width('100%')
            .onSelect((index: number) => {
              this.asrIndex = index;
              const p: AsrProvider = Providers.ASR[index];
              if (p.url.length > 0) {
                this.asrUrl = p.url;
                this.asrModel = p.model;
              }
              this.refreshDiarizeHint();
              // 换服务商可能改变「这个方言还能不能直接识别」，提示要跟着变
              this.refreshLangHint();
            })

          this.label('转写接口地址')
          TextInput({ text: this.asrUrl, placeholder: 'https://…/audio/transcriptions' })
            .fontSize(13)
            .height(44)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.asrUrl = v;
            })

          this.label('转写 API Key')
          TextInput({ text: this.asrKeyInput, placeholder: '与上面同一家时可填同一个 Key' })
            .type(InputType.Password)
            .showPasswordIcon(true)
            .fontSize(13)
            .height(44)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.asrKeyInput = v;
            })

          this.label('转写模型')
          TextInput({ text: this.asrModel, placeholder: 'FunAudioLLM/SenseVoiceSmall' })
            .fontSize(13)
            .height(44)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.asrModel = v;
            })

          this.label('识别语言')
          Select(this.langOptions)
            .selected(this.langIndex)
            .value(SpeechLangs.all()[this.langIndex].label)
            .width('100%')
            .onSelect((index: number) => {
              this.langIndex = index;
              this.refreshLangHint();
            })
          Text(this.langHint)
            .fontSize(10)
            .fontColor('#9E9E9E')
            .lineHeight(15)
            .margin({ top: 4 })
            .width('100%')
          if (this.langNote.length > 0) {
            Text(this.langNote)
              .fontSize(10)
              .fontColor('#B26A00')
              .lineHeight(15)
              .margin({ top: 4 })
              .width('100%')
          }

          this.label('热词表（人名 / 公司名 / 专业词）')
          TextArea({ text: this.hotwords, placeholder: '用逗号或换行分隔，最多 60 个' })
            .fontSize(13)
            .height(72)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.hotwords = v;
            })
          Text('只对支持 prompt 的服务生效（Whisper 系与 Groq）；SenseVoice 没有这个参数，填了也不会发出去。')
            .fontSize(10)
            .fontColor('#9E9E9E')
            .lineHeight(15)
            .margin({ top: 4 })
            .width('100%')

          Row() {
            Column() {
              Text('方言自动转普通话')
                .fontSize(13)
                .fontColor('#1A1A1A')
              Text('摘要与待办用规范书面语生成，原文仍原样保留；普通话录音不受影响')
                .fontSize(10)
                .fontColor('#9E9E9E')
                .margin({ top: 3 })
                .lineHeight(15)
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)

            Toggle({ type: ToggleType.Switch, isOn: this.dialectStd })
              .selectedColor('#E60012')
              .onChange((on: boolean) => {
                this.dialectStd = on;
              })
          }
          .width('100%')
          .margin({ top: 12 })
          .alignItems(VerticalAlign.Center)

          Row() {
            Column() {
              Text('说话人分离')
                .fontSize(13)
                .fontColor('#1A1A1A')
              Text(this.diarizeHint)
                .fontSize(10)
                .fontColor('#9E9E9E')
                .margin({ top: 3 })
                .lineHeight(15)
            }
            .alignItems(HorizontalAlign.Start)
            .layoutWeight(1)

            Toggle({ type: ToggleType.Switch, isOn: this.diarize })
              .selectedColor('#E60012')
              .enabled(Providers.asrById(this.asrProviderId()).supportsDiarize)
              .onChange((on: boolean) => {
                this.diarize = on;
              })
          }
          .width('100%')
          .margin({ top: 12 })
          .alignItems(VerticalAlign.Center)

          Row() {
            this.smallButton('保存配置', true, () => {
              this.saveAi();
            })
          }
          .width('100%')
          .margin({ top: 14 })
        }
        .padding({ left: 14, right: 14, bottom: 14 })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .cardStyle()
  }

  @Builder
  recordGroup() {
    Column() {
      this.groupHeader('录音与音源', '什么时候录、用哪个麦克风', this.openRec, () => {
        this.openRec = !this.openRec;
      })
      if (this.openRec) {
        Column() {
          this.switchRow('连上蓝牙耳机自动开始记录', this.autoRecord, (on: boolean) => {
            this.autoRecord = on;
            Repo.setBool(Keys.AUTO_RECORD, on).then(() => {
            });
          })
          this.switchRow('用蓝牙耳机麦克风录音', this.btMic, (on: boolean) => {
            this.btMic = on;
            Repo.setBool(Keys.BT_MIC, on).then(() => {
            });
          })
          this.hint('默认用手机麦克风：44.1kHz 音质更好，且完全不改动蓝牙音频通路——'
            + '音乐照放，录音同时进行，两件事互不干扰。'
            + '耳机麦克风离嘴更近、远处说话也能听清，但它走的是蓝牙通话通路（SCO），'
            + '那条通路是独占的：一旦切过去，耳机里的音乐会被系统暂停。'
            + '想「一边听音乐一边录」，就保持这个开关关着。')

          // 耳机键归谁。这是「连了蓝牙耳机，怎么在听音乐和录音之间切换」的正解：
          // 耳机只有一组播放/暂停键，系统送给当前活跃的媒体会话，谁活跃谁拿键。
          this.segRow(
            '耳机上的播放键归谁',
            HeadsetMode.all(),
            ['只在录音中', '全程接管', '不接管'],
            this.headsetMode,
            (v: string) => {
              this.headsetMode = v;
              Repo.setStr(Keys.HEADSET_MODE, v).then(() => {
              });
              // 立刻生效，不用等下次录音
              HeadsetKeys.get().applyMode(v).then(() => {
              }).catch(() => {
              });
            }
          )
          this.hint(HeadsetMode.hint(this.headsetMode))
          this.hint('原理：耳机键永远只送给「当前活跃的媒体会话」。草记录音时把自己注册成这个会话，'
            + '键就管录音；录完退出会话，键自动回到音乐 App。不需要断开蓝牙，也不用改系统设置。')

          this.switchRow('手表遥控（同一 Wi-Fi）', this.watchLink, (on: boolean) => {
            this.watchLink = on;
            Repo.setBool(Keys.WATCH_LINK, on).then(() => {
            });
            if (on) {
              WatchLink.get().start().then((ok: boolean) => {
                if (!ok) {
                  this.watchLink = false;
                  Repo.setBool(Keys.WATCH_LINK, false).then(() => {
                  });
                }
              }).catch(() => {
              });
            } else {
              WatchLink.get().stop().then(() => {
              }).catch(() => {
              });
            }
          })
          if (this.watchLinkActive && this.watchLinkInfo.length > 0) {
            Row() {
              Text(`手机地址 ${this.watchLinkInfo}`)
                .fontSize(13)
                .fontColor('#1A1A1A')
                .fontWeight(FontWeight.Medium)
                .layoutWeight(1)
              Text('复制')
                .fontSize(12)
                .fontColor('#E60012')
                .padding({ left: 10, right: 4, top: 6, bottom: 6 })
                .onClick(() => {
                  ExportCopy.copy(this.watchLinkInfo);
                })
            }
            .width('100%')
            .padding({ top: 8, bottom: 8 })
          }
          this.hint('手表上装草记手表端，填上面这个地址就能开始/结束记录、打点。'
            + '录音、转写、上传全在这台手机上跑，手表只发几个字节的指令——'
            + '所以手表那边耗电约等于一块普通表盘，不用为它配充电器。'
            + '前提是两台设备连同一个 Wi-Fi（手机开热点给手表连也行）。')
          this.hint('为什么不用手表自带的配套通道：鸿蒙手机没有 Google 服务，'
            + 'Wear OS 的官方数据通道在鸿蒙上不存在，只能自建一条局域网通道。')

          this.switchRow('保留原始录音', this.keepAudio, (on: boolean) => {
            this.keepAudio = on;
            Repo.setBool(Keys.KEEP_AUDIO, on).then(() => {
            });
          })
          this.hint('默认关闭：只留文字不留声音，最省空间也最不敏感。开启后音频留在应用私有目录，卸载即删。')

          Row() {
            this.smallButton('导入录音 / 转写稿', false, () => {
              this.doImport();
            })
          }
          .width('100%')
          .margin({ top: 12 })

          this.hint('支持 m4a/mp3/wav/aac 等音频与 srt/vtt/txt/md/json 转写稿。'
            + '钉钉闪记、飞书妙记里先导出文件，再从这里导入——它们不开放直连，这是唯一稳的方式。')
        }
        .padding({ left: 14, right: 14, bottom: 14 })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .cardStyle()
  }

  @Builder
  interopGroup() {
    Column() {
      this.groupHeader('打通与分发', '飞书 / 钉钉群机器人、WorkBuddy', this.openInterop, () => {
        this.openInterop = !this.openInterop;
      })
      if (this.openInterop) {
        Column() {
          this.label('飞书 Webhook')
          TextInput({ text: this.fsHook, placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/…' })
            .fontSize(12)
            .height(42)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.fsHook = v;
            })

          this.label('飞书加签密钥（开了签名校验才填）')
          TextInput({ text: this.fsSecret, placeholder: '留空表示没开加签' })
            .type(InputType.Password)
            .fontSize(12)
            .height(42)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.fsSecret = v;
            })

          Row() {
            this.smallButton('保存并测试', true, () => {
              this.saveAndTest(Platform.FEISHU);
            })
            this.smallButton('推送最近 3 条', false, () => {
              this.pushRecent(Platform.FEISHU);
            })
          }
          .width('100%')
          .margin({ top: 10 })

          this.label('钉钉 Webhook')
          TextInput({ text: this.dtHook, placeholder: 'https://oapi.dingtalk.com/robot/send?access_token=…' })
            .fontSize(12)
            .height(42)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.dtHook = v;
            })

          this.label('钉钉加签密钥')
          TextInput({ text: this.dtSecret, placeholder: '开了加签才需要填' })
            .type(InputType.Password)
            .fontSize(12)
            .height(42)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.dtSecret = v;
            })

          Row() {
            this.smallButton('保存并测试', true, () => {
              this.saveAndTest(Platform.DINGTALK);
            })
            this.smallButton('推送最近 3 条', false, () => {
              this.pushRecent(Platform.DINGTALK);
            })
          }
          .width('100%')
          .margin({ top: 10 })

          this.label('WorkBuddy Webhook')
          TextInput({ text: this.wbHook, placeholder: 'https://…（可选）' })
            .fontSize(12)
            .height(42)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.wbHook = v;
            })

          this.label('鉴权 Token（可选）')
          TextInput({ text: this.wbToken, placeholder: '以 Bearer 方式发送' })
            .type(InputType.Password)
            .fontSize(12)
            .height(42)
            .backgroundColor('#FAFAFA')
            .borderRadius(10)
            .onChange((v: string) => {
              this.wbToken = v;
            })

          this.switchRow('整理完自动推送', this.wbAuto, (on: boolean) => {
            this.wbAuto = on;
            Repo.setBool(Keys.WB_AUTO, on).then(() => {
            });
          })

          if (this.interopState.length > 0) {
            this.hint(this.interopState)
          }

          this.hint('只推摘要与待办，不推逐字稿：群里没人读几千字，也避免过度外泄。'
            + '钉钉若提示 310000，是机器人安全设置不匹配——关键词建议设为「草记」，或把加签密钥填上。')
        }
        .padding({ left: 14, right: 14, bottom: 14 })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .cardStyle()
  }

  @Builder
  privacyGroup() {
    Column() {
      this.groupHeader('隐私与安全', '截屏保护、数据清理', this.openPrivacy, () => {
        this.openPrivacy = !this.openPrivacy;
      })
      if (this.openPrivacy) {
        Column() {
          this.switchRow('禁止截屏与多任务预览', this.secureWindow, (on: boolean) => {
            this.secureWindow = on;
            Repo.setBool(Keys.SECURE_WINDOW, on).then(() => {
            });
            this.applySecureWindow(on);
          })
          this.hint('API Key、机器人密钥都在系统密钥库保护下加密存储（AES-256，每次随机 IV）。'
            + '记录默认只存本机，不上传云端；只有配置了转写/大模型接口后，处理时才把该段音频或文本发给你指定的服务商。')

          Row() {
            this.smallButton('清除全部数据', false, () => {
              this.confirmWipe();
            })
          }
          .width('100%')
          .margin({ top: 12 })
          this.hint('会删除所有记录、待办、配置与密钥，不可恢复。建议先在记录详情里导出需要的记录。')
        }
        .padding({ left: 14, right: 14, bottom: 14 })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .cardStyle()
  }

  @Builder
  aboutGroup() {
    Column() {
      this.groupHeader('关于与法务', `草记 ${Contact.VERSION}`, this.openAbout, () => {
        this.openAbout = !this.openAbout;
      })
      if (this.openAbout) {
        Column() {
          this.label('作者反馈邮箱')
          Text(Contact.EMAIL)
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor('#E60012')
            .onClick(async () => {
              await ExportCopy.copy(Contact.EMAIL);
            })

          Row() {
            this.smallButton('写邮件反馈', true, () => {
              this.openMail();
            })
            this.smallButton('复制邮箱', false, () => {
              ExportCopy.copy(Contact.EMAIL);
            })
          }
          .width('100%')
          .margin({ top: 12 })

          Row() {
            this.smallButton('用户协议与免责声明', false, () => {
              router.pushUrl({ url: 'pages/LegalPage' });
            })
          }
          .width('100%')
          .margin({ top: 10 })

          this.hint('钉钉、飞书、企业微信、微信、HarmonyOS 等名称与标识归其各自权利人所有。'
            + '本应用通过公开的群机器人接口与系统文件选择器接入，属独立第三方工具，与其无隶属、合作、代理或背书关系。'
            + '\n\n录音是否合法由使用者负责：录音前请取得在场人员必要同意，并遵守当地法律与单位制度。'
            + 'AI 转写与摘要可能出错，金额、期限、责任等关键信息请以原始录音或书面文件为准。')
        }
        .padding({ left: 14, right: 14, bottom: 14 })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .cardStyle()
  }

  // ==================== 行为 ====================

  private async applySecureWindow(on: boolean): Promise<void> {
    try {
      const win = await window.getLastWindow(this.ctx());
      await win.setWindowPrivacyMode(on);
    } catch (e) {
      // 部分设备不支持隐私模式：开关仍保存，下次启动再试
    }
  }

  private async saveAi(): Promise<void> {
    if (this.llmBase.length > 0 && !this.llmBase.startsWith('https://')) {
      promptAction.showToast({ message: '接口地址必须以 https:// 开头' });
      return;
    }
    await Repo.setStr(Keys.LLM_PROVIDER, this.providerId());
    await Repo.setStr(Keys.LLM_BASE, this.llmBase.trim());
    await Repo.setStr(Keys.LLM_MODEL, this.llmModel.trim());
    if (this.llmKeyInput.trim().length > 0) {
      await Repo.setStr(Keys.LLM_KEY, this.llmKeyInput.trim());
    }
    await Repo.setStr(Keys.ASR_PROVIDER, this.asrProviderId());
    await Repo.setStr(Keys.ASR_URL, this.asrUrl.trim());
    await Repo.setStr(Keys.ASR_MODEL, this.asrModel.trim());
    if (this.asrKeyInput.trim().length > 0) {
      await Repo.setStr(Keys.ASR_KEY, this.asrKeyInput.trim());
    }
    await Repo.setBool(Keys.ASR_DIARIZE, this.diarize);
    await Repo.setStr(Keys.ASR_LANG, this.currentLangId());
    await Repo.setStr(Keys.ASR_PROMPT, this.hotwords.trim());
    await Repo.setBool(Keys.DIALECT_STD, this.dialectStd);
    this.loadAll();
    promptAction.showToast({
      message: Repo.hasAsr() ? '配置已保存' : '已保存，但转写配置不完整，录音会停在待转写状态'
    });
  }

  private async saveAndTest(platform: string): Promise<void> {
    if (platform === Platform.FEISHU) {
      await Repo.setStr(Keys.FS_HOOK, this.fsHook.trim());
      await Repo.setStr(Keys.FS_SECRET, this.fsSecret.trim());
    } else {
      await Repo.setStr(Keys.DT_HOOK, this.dtHook.trim());
      await Repo.setStr(Keys.DT_SECRET, this.dtSecret.trim());
    }
    this.interopState = '正在发送测试消息…';
    const r = await CollabBridge.test(platform);
    this.interopState = r.ok ? `测试成功：${r.message}` : `测试失败：${r.message}`;
    promptAction.showToast({ message: this.interopState });
    this.loadAll();
  }

  private async pushRecent(platform: string): Promise<void> {
    this.interopState = '正在推送…';
    const r = await CollabBridge.pushRecent(platform, 3);
    this.interopState = r.ok ? r.message : `推送失败：${r.message}`;
    promptAction.showToast({ message: this.interopState });
  }

  private async doImport(): Promise<void> {
    const r = await Importer.pickAndImport(this.ctx());
    if (r.message.length > 0) {
      promptAction.showToast({ message: r.message });
    }
  }

  private confirmWipe(): void {
    AlertDialog.show({
      title: '清除全部数据',
      message: '所有记录、待办、配置与密钥都会被删除，无法恢复。确定继续？',
      primaryButton: {
        value: '取消',
        action: () => {
        }
      },
      secondaryButton: {
        value: '确定清除',
        fontColor: '#E60012',
        action: async () => {
          await Repo.wipeAll();
          this.loadAll();
          promptAction.showToast({ message: '已清除全部数据' });
        }
      }
    });
  }

  private async openMail(): Promise<void> {
    const info = `${deviceInfo.productModel} / HarmonyOS ${deviceInfo.osFullName}`;
    const ok = await Contact.openMail(this.ctx(), info);
    if (!ok) {
      await ExportCopy.copy(Contact.EMAIL);
      promptAction.showToast({ message: `本机没有邮件应用，邮箱已复制：${Contact.EMAIL}` });
    }
  }

  build() {
    Column() {
      Column() {
        Text('设置')
          .fontSize(23)
          .fontWeight(FontWeight.Bold)
          .fontColor('#1A1A1A')
        Text('AI 服务、录音方式、对外打通与隐私，全部在这里')
          .fontSize(12)
          .fontColor('#6B6B6B')
          .margin({ top: 4 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 18, right: 18, top: 14, bottom: 10 })

      Scroll() {
        Column() {
          Column() {
            Text('当前配置')
              .fontSize(12)
              .fontWeight(FontWeight.Bold)
              .fontColor('#B0000E')
              .width('100%')
            Text(this.summary)
              .fontSize(12)
              .fontColor('#1A1A1A')
              .lineHeight(19)
              .margin({ top: 8 })
              .width('100%')
          }
          .width('100%')
          .padding(14)
          .backgroundColor('#FFF7F8')
          .borderRadius(16)
          .border({ width: 1, color: '#FADCDE' })

          Column({ space: 10 }) {
            this.aiGroup()
            this.recordGroup()
            this.interopGroup()
            this.privacyGroup()
            this.aboutGroup()
          }
          .width('100%')
          .margin({ top: 12 })

          Text(`草记 ${Contact.VERSION} · 本地优先 · 无广告与埋点`)
            .fontSize(10)
            .fontColor('#BDBDBD')
            .margin({ top: 16, bottom: 24 })
        }
        .width('100%')
        .padding({ left: 18, right: 18, top: 4 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
      .edgeEffect(EdgeEffect.Spring)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
    .onAppear(() => {
      this.loadAll();
    })
  }
}
