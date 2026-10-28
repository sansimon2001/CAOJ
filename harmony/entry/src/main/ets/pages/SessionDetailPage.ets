import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { Store } from '../data/Store';
import { Pipeline, PipelineListener } from '../service/Pipeline';
import { CollabBridge, Platform, PushResult } from '../service/CollabBridge';
import { ExportService } from '../service/ExportService';
import { Session, TodoItem, sourceLabel, statusLabel, Quality } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';
import { Contact } from '../util/Legal';
import { AsrClient } from '../service/AsrClient';
import { SpeechLangs, SpeechLang } from '../service/SpeechLang';
import { TranscriptCleaner } from '../util/TranscriptCleaner';
import { ProjectMind } from '../service/ProjectMind';

/**
 * 记录详情
 *
 * 一条记录能做的全部事情都在这：看摘要、勾待办、读原文、导出、推到群里、重新整理、删除。
 * 入口只有一个（今日/项目列表点进来），避免用户在两处找到不同的按钮。
 */
@Entry
@Component
struct SessionDetailPage {
  @State session: Session | null = null;
  @State todos: TodoItem[] = [];
  @State busy: boolean = false;
  @State showTranscript: boolean = true;
  /** 正在按指定语言重转（按钮期间置灰，避免连点发出多次请求） */
  @State retranslating: boolean = false;
  /** 正在改项目归属 */
  @State editingProject: boolean = false;
  @State projectInput: string = '';
  private sessionId: number = 0;
  private listener: PipelineListener | null = null;

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, Object>;
    if (params !== undefined && params !== null) {
      const id = params['id'];
      if (typeof id === 'number') {
        this.sessionId = id as number;
      }
    }
    this.listener = {
      onPipelineChanged: (sid: number) => {
        if (sid === this.sessionId) {
          this.reload();
        }
      }
    };
    Pipeline.addListener(this.listener);
    this.reload();
  }

  aboutToDisappear(): void {
    if (this.listener !== null) {
      Pipeline.removeListener(this.listener);
      this.listener = null;
    }
  }

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  /**
   * 保存手动指定的项目
   *
   * 改完顺手把学到的线索词记进档案：用户这次认定的归属，是比模型猜测
   * 更可靠的信号，下一次同类说法应当能自动命中。
   */
  private async saveProject(): Promise<void> {
    const s = this.session;
    if (s === null) {
      return;
    }
    const name = this.projectInput.trim();
    try {
      await Store.setSessionProject(s.id, name);
      if (name.length > 0) {
        await ProjectMind.ensure(name);
        const src = s.transcriptStd.length > 0 ? s.transcriptStd : s.transcript;
        await ProjectMind.learnFromCorrection(name, src);
      }
      this.editingProject = false;
      await this.reload();
      promptAction.showToast({ message: name.length > 0 ? `已归入「${name}」` : '已清空项目归属' });
    } catch (e) {
      promptAction.showToast({ message: '保存失败，请重试' });
    }
  }

  /**
   * 按指定语言重转这一条
   *
   * 只在详情页生效，不改全局设置：用户平时按普通话记，偶尔一条粤语记录
   * 想单独按粤语重转，不该被迫改完设置再改回来。
   */
  private async retranslate(langId: string): Promise<void> {
    const s = this.session;
    if (s === null || s.audioPath.length === 0 || this.retranslating) {
      promptAction.showToast({ message: s !== null && s.audioPath.length === 0 ? '这条记录没有保留音频，无法重转' : '正在重转，请稍候' });
      return;
    }
    this.retranslating = true;
    try {
      const r = await AsrClient.transcribe(s.audioPath, langId);
      if (!r.ok) {
        promptAction.showToast({ message: r.message });
        return;
      }
      const cleaned = TranscriptCleaner.clean(r.text);
      s.transcript = cleaned.length > 0 ? cleaned : r.text;
      s.lang = r.langId;
      s.langNote = r.note;
      // 按新语言重转后，之前那条「疑似某方言」的提示就失去意义了
      s.detected = '';
      await Store.updateSession(s);
      await this.reload();
      promptAction.showToast({ message: `已按「${SpeechLangs.byId(r.langId).label}」重新转写` });
    } finally {
      this.retranslating = false;
    }
  }

  private async reload(): Promise<void> {    const s = await Store.getSession(this.sessionId);
    this.session = s;
    if (s !== null) {
      this.todos = await Store.todosOf(s.id);
    }
  }

  @Builder
  topBar() {
    Row() {
      Text('‹ 返回')
        .fontSize(14)
        .fontColor('#FFFFFF')
        .onClick(() => {
          router.back();
        })
      Blank()
      Text('记录详情')
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .fontColor('#FFFFFF')
      Blank()
      Text('  ')
        .fontSize(14)
    }
    .width('100%')
    .height(52)
    .padding({ left: 14, right: 14 })
    .linearGradient({
      angle: 90,
      colors: [['#E60012', 0.0], ['#C4000F', 1.0]]
    })
  }

  @Builder
  metaRow(label: string, value: string) {
    Row() {
      Text(label)
        .fontSize(11)
        .fontColor('#9E9E9E')
        .width(52)
      Text(value)
        .fontSize(12)
        .fontColor('#1A1A1A')
        .layoutWeight(1)
    }
    .width('100%')
    .padding({ top: 5, bottom: 5 })
  }

  @Builder
  actionButton(label: string, primary: boolean, onTap: () => void) {
    Text(label)
      .fontSize(12)
      .fontColor(primary ? '#FFFFFF' : '#6B6B6B')
      .textAlign(TextAlign.Center)
      .height(40)
      .layoutWeight(1)
      .backgroundColor(primary ? '#E60012' : '#FFFFFF')
      .borderRadius(20)
      .border({ width: 1, color: primary ? '#E60012' : '#E8E8E8' })
      .onClick(() => {
        onTap();
      })
  }

  build() {
    Column() {
      this.topBar()

      if (this.session === null) {
        Column() {
          LoadingProgress().width(28).height(28).color('#E60012')
        }
        .layoutWeight(1)
        .width('100%')
        .justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            Column() {
              Text(this.session!.title)
                .fontSize(19)
                .fontWeight(FontWeight.Bold)
                .fontColor('#1A1A1A')
                .width('100%')

              Row() {
                Text(`${TimeUtil.dateFull(this.session!.startAt)} ${TimeUtil.time(this.session!.startAt)}`)
                  .fontSize(11)
                  .fontColor('#6B6B6B')
                Text('·')
                  .fontSize(11)
                  .fontColor('#D0D0D0')
                  .margin({ left: 6, right: 6 })
                Text(TimeUtil.duration(this.session!.durationMs))
                  .fontSize(11)
                  .fontColor('#6B6B6B')
                Text('·')
                  .fontSize(11)
                  .fontColor('#D0D0D0')
                  .margin({ left: 6, right: 6 })
                Text(sourceLabel(this.session!.source))
                  .fontSize(11)
                  .fontColor('#6B6B6B')
              }
              .width('100%')
              .margin({ top: 8 })

              Row() {
                Text(statusLabel(this.session!))
                  .fontSize(10)
                  .fontColor(this.session!.quality === Quality.REVIEW ? '#B0000E' : '#6B6B6B')
                  .padding({ left: 7, right: 7, top: 3, bottom: 3 })
                  .backgroundColor(this.session!.quality === Quality.REVIEW ? '#FDECEE' : '#F5F5F5')
                  .borderRadius(6)
                if (this.session!.project.length > 0) {
                  Text(this.session!.project)
                    .fontSize(10)
                    .fontColor('#6B6B6B')
                    .padding({ left: 7, right: 7, top: 3, bottom: 3 })
                    .backgroundColor('#F5F5F5')
                    .borderRadius(6)
                    .margin({ left: 6 })
                    .onClick(() => {
                      this.editingProject = true;
                      this.projectInput = this.session!.project;
                    })
                } else {
                  // 没有归属时也给入口：模型宁可留空也不新建碎片，补回来得靠用户
                  Text('+ 项目')
                    .fontSize(10)
                    .fontColor('#9E9E9E')
                    .padding({ left: 7, right: 7, top: 3, bottom: 3 })
                    .backgroundColor('#F5F5F5')
                    .borderRadius(6)
                    .margin({ left: 6 })
                    .onClick(() => {
                      this.editingProject = true;
                      this.projectInput = '';
                    })
                }
                if (this.session!.marked) {
                  Text('已标重点')
                    .fontSize(10)
                    .fontColor('#B0000E')
                    .padding({ left: 7, right: 7, top: 3, bottom: 3 })
                    .backgroundColor('#FDECEE')
                    .borderRadius(6)
                    .margin({ left: 6 })
                }
                Blank()
                if (this.busy) {
                  Text('整理中…')
                    .fontSize(10)
                    .fontColor('#B0000E')
                }
              }
              .width('100%')
              .margin({ top: 10 })

              // 改项目：只改这一条，同时把「这次原文里怎么叫它」记成线索词，
              // 下次同类说法才能自动归到同一个项目
              if (this.editingProject) {
                Column() {
                  TextInput({ text: this.projectInput, placeholder: '项目名（留空表示不归属）' })
                    .fontSize(13)
                    .height(40)
                    .backgroundColor('#FAFAFA')
                    .borderRadius(8)
                    .onChange((v: string) => {
                      this.projectInput = v;
                    })
                  Row() {
                    Blank()
                    Text('取消')
                      .fontSize(11)
                      .fontColor('#6B6B6B')
                      .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                      .onClick(() => {
                        this.editingProject = false;
                      })
                    Text('保存')
                      .fontSize(11)
                      .fontColor('#B0000E')
                      .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                      .onClick(() => {
                        this.saveProject();
                      })
                  }
                  .width('100%')
                  .margin({ top: 6 })
                }
                .width('100%')
                .margin({ top: 8 })
              }
            }
            .width('100%')
            .padding(16)
            .backgroundColor('#FFFFFF')

            if (this.session!.quality === Quality.REVIEW) {
              Text('这条摘要与原文的对应度偏低，可能有个别内容不准。金额、期限、责任等关键信息请对一下原文。')
                .fontSize(11)
                .fontColor('#B0000E')
                .lineHeight(17)
                .padding(12)
                .backgroundColor('#FDECEE')
                .borderRadius(12)
                .margin({ top: 10, left: 16, right: 16 })
            }

            if (this.session!.summary.length > 0) {
              Column() {
                Text('摘要')
                  .fontSize(12)
                  .fontWeight(FontWeight.Medium)
                  .fontColor('#B0000E')
                  .width('100%')
                Text(this.session!.summary)
                  .fontSize(13)
                  .fontColor('#1A1A1A')
                  .lineHeight(22)
                  .margin({ top: 8 })
                  .width('100%')
              }
              .width('100%')
              .padding(16)
              .backgroundColor('#FFFFFF')
              .borderRadius(14)
              .border({ width: 1, color: '#F0F0F0' })
              .margin({ top: 10, left: 16, right: 16 })
            }

            if (this.todos.length > 0) {
              Column() {
                Text(`待办 ${this.todos.length}`)
                  .fontSize(12)
                  .fontWeight(FontWeight.Medium)
                  .fontColor('#1A1A1A')
                  .width('100%')

                ForEach(this.todos, (t: TodoItem) => {
                  Row() {
                    Circle()
                      .width(15)
                      .height(15)
                      .fill(t.done ? '#E60012' : '#FFFFFF')
                      .stroke(t.done ? '#E60012' : '#CFCFCF')
                      .strokeWidth(1.4)
                      .onClick(async () => {
                        await Store.setTodoDone(t.id, !t.done);
                        this.reload();
                      })
                    Text(t.text)
                      .fontSize(13)
                      .fontColor(t.done ? '#9E9E9E' : '#1A1A1A')
                      .decoration({ type: t.done ? TextDecorationType.LineThrough : TextDecorationType.None })
                      .layoutWeight(1)
                      .margin({ left: 9 })
                    if (t.dueDate.length > 0) {
                      Text(t.dueDate.substring(5))
                        .fontSize(10)
                        .fontColor('#9E9E9E')
                    }
                  }
                  .width('100%')
                  .padding({ top: 9, bottom: 9 })
                  .alignItems(VerticalAlign.Top)
                }, (t: TodoItem) => `${t.id}-${t.done}`)
              }
              .width('100%')
              .padding(16)
              .backgroundColor('#FFFFFF')
              .borderRadius(14)
              .border({ width: 1, color: '#F0F0F0' })
              .margin({ top: 10, left: 16, right: 16 })
            }

            if (this.session!.transcript.length > 0) {
              Column() {
                Row() {
                  Text(`原文 ${this.session!.transcript.length} 字`)
                    .fontSize(12)
                    .fontWeight(FontWeight.Medium)
                    .fontColor('#1A1A1A')
                  Blank()
                  Text(this.showTranscript ? '收起' : '展开')
                    .fontSize(11)
                    .fontColor('#B0000E')
                    .onClick(() => {
                      this.showTranscript = !this.showTranscript;
                    })
                }
                .width('100%')

                // 降级实情：选了方言但服务端只认普通话，必须写出来，
                // 否则用户会以为是自己口齿不清
                if (this.session!.langNote.length > 0) {
                  Text(this.session!.langNote)
                    .fontSize(10)
                    .fontColor('#B26A00')
                    .lineHeight(15)
                    .margin({ top: 8 })
                    .width('100%')
                }

                // 事后检测到疑似方言，且与当前选择不是同一种：给一键重转
                if (this.session!.detected.length > 0 && this.session!.detected !== this.session!.lang) {
                  Row() {
                    Text(`疑似「${SpeechLangs.byId(this.session!.detected).label}」`)
                      .fontSize(11)
                      .fontColor('#4A4A4A')
                      .layoutWeight(1)
                    Text(this.retranslating ? '重转中…' : '按它重转')
                      .fontSize(11)
                      .fontColor('#B0000E')
                      .onClick(() => {
                        this.retranslate(this.session!.detected);
                      })
                  }
                  .width('100%')
                  .margin({ top: 8 })
                  .padding({ left: 10, right: 10, top: 8, bottom: 8 })
                  .backgroundColor('#FFF8EE')
                  .borderRadius(8)
                }

                if (this.showTranscript) {
                  Text(this.session!.transcript)
                    .fontSize(12)
                    .fontColor('#4A4A4A')
                    .lineHeight(21)
                    .margin({ top: 10 })
                    .width('100%')
                }
              }
              .width('100%')
              .padding(16)
              .backgroundColor('#FFFFFF')
              .borderRadius(14)
              .border({ width: 1, color: '#F0F0F0' })
              .margin({ top: 10, left: 16, right: 16 })
            }

            Column() {
              Row() {
                this.actionButton('导出 .md', true, () => {
                  this.exportMd();
                })
                this.actionButton('复制全文', false, () => {
                  this.copyAll();
                })
              }
              .width('100%')

              Row() {
                this.actionButton(this.busy ? '整理中…' : '重新整理', false, () => {
                  this.retry();
                })
                this.actionButton('推到飞书', false, () => {
                  this.push(Platform.FEISHU);
                })
                this.actionButton('推到钉钉', false, () => {
                  this.push(Platform.DINGTALK);
                })
              }
              .width('100%')
              .margin({ top: 8 })

              Row() {
                this.actionButton('删除这条记录', false, () => {
                  this.confirmDelete();
                })
              }
              .width('100%')
              .margin({ top: 8 })
            }
            .width('100%')
            .padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .margin({ top: 10 })

            Text(`问题反馈：${Contact.EMAIL}`)
              .fontSize(10)
              .fontColor('#BDBDBD')
              .margin({ top: 8, bottom: 28 })
          }
          .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)
        .backgroundColor('#FAFAFA')
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FAFAFA')
  }

  private async exportMd(): Promise<void> {
    const s = this.session;
    if (s === null) {
      return;
    }
    const md = ExportService.sessionMarkdown(s, this.todos);
    const msg = await ExportService.saveToFile(this.ctx(), md, ExportService.fileNameFor(s));
    if (msg.length > 0) {
      promptAction.showToast({ message: msg, duration: 2600 });
    }
  }

  private async copyAll(): Promise<void> {
    const s = this.session;
    if (s === null) {
      return;
    }
    const md = ExportService.sessionMarkdown(s, this.todos);
    await ExportService.copyToClipboard(md);
    promptAction.showToast({ message: '已复制（含摘要、待办、原文）' });
  }

  private async push(platform: string): Promise<void> {
    const s = this.session;
    if (s === null) {
      return;
    }
    if (!CollabBridge.isReady(platform)) {
      promptAction.showToast({ message: `还没配置${CollabBridge.label(platform)}：到「设置 → 打通与分发」` });
      return;
    }
    this.busy = true;
    const r: PushResult = await CollabBridge.pushSession(platform, s, this.todos);
    this.busy = false;
    promptAction.showToast({ message: r.message, duration: 2600 });
  }

  private async retry(): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    const msg = await Pipeline.retry(this.sessionId);
    this.busy = false;
    await this.reload();
    promptAction.showToast({ message: msg.length > 0 ? msg : '已重新整理', duration: 2600 });
  }

  private confirmDelete(): void {
    AlertDialog.show({
      title: '删除这条记录',
      message: '删除后不可恢复（导出的文件不受影响）。确定删除？',
      primaryButton: {
        value: '取消',
        action: () => {
        }
      },
      secondaryButton: {
        value: '删除',
        fontColor: '#E60012',
        action: async () => {
          await Store.deleteSession(this.sessionId);
          AppStorage.setOrCreate('refreshTick', Date.now());
          router.back();
        }
      }
    });
  }
}
