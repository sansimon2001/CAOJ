import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { Repo, Keys } from '../data/Repo';
import { Store } from '../data/Store';
import { Recorder } from '../service/Recorder';
import { Pipeline, PipelineListener } from '../service/Pipeline';
import { Session, statusLabel, Quality, emptySession } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';

class SessionRow {
  session: Session = emptySession();
  todoOpen: number = 0;
}

class DayGroup {
  label: string = '';
  rows: SessionRow[] = [];
}

/**
 * 今日：录音主控 + 记录流
 *
 * 布局逻辑（v1.18 定稿）：
 *   页头（日期 + 统计 + 一行状态）→ 录音卡（音源 / 计时 / 主按钮 / 标记）→ 记录列表
 * 状态提示独占一行而不是挤成小胶囊：文案一长（「还没填 API Key…」）就不会把标题压扁。
 */
@Component
export struct TodayView {
  @StorageLink('recording') recording: boolean = false;
  @StorageLink('waiting') waiting: boolean = false;
  @StorageLink('elapsed') elapsed: number = 0;
  @StorageLink('segIndex') segIndex: number = 1;
  @StorageLink('headset') headset: boolean = false;
  @StorageLink('headsetName') headsetName: string = '';
  /** 耳机键此刻是不是归草记（录音中或「全程接管」档） */
  @StorageLink('headsetKeyActive') headsetKeyActive: boolean = false;
  @StorageLink('refreshTick') refreshTick: number = 0;
  @StorageLink('markCount') markCount: number = 0;

  @State groups: DayGroup[] = [];
  @State todayCount: number = 0;
  @State todayMs: number = 0;
  @State btMic: boolean = false;
  @State needKey: boolean = false;

  private listener: PipelineListener | null = null;

  aboutToAppear(): void {
    this.listener = {
      onPipelineChanged: (sessionId: number) => {
        this.reload();
      }
    };
    Pipeline.addListener(this.listener);
    this.btMic = Repo.getBool(Keys.BT_MIC, false);
    this.needKey = !Repo.hasLlm();
    this.reload();
  }

  aboutToDisappear(): void {
    if (this.listener !== null) {
      Pipeline.removeListener(this.listener);
      this.listener = null;
    }
  }

  onRefresh(): void {
    this.btMic = Repo.getBool(Keys.BT_MIC, false);
    this.needKey = !Repo.hasLlm();
    this.reload();
  }

  async reload(): Promise<void> {
    const list = await Store.recent(40);
    const start = TimeUtil.todayStart();
    let count = 0;
    let ms = 0;
    for (let i = 0; i < list.length; i++) {
      if (list[i].startAt >= start) {
        count += 1;
        ms += list[i].durationMs;
      }
    }
    this.todayCount = count;
    this.todayMs = ms;

    const groups: DayGroup[] = [];
    let currentLabel = '';
    let current: DayGroup | null = null;
    for (let i = 0; i < list.length; i++) {
      const s = list[i];
      const label = TimeUtil.dayLabel(s.startAt);
      if (label !== currentLabel) {
        currentLabel = label;
        current = new DayGroup();
        current.label = label;
        groups.push(current);
      }
      const todos = await Store.todosOf(s.id);
      let open = 0;
      for (let j = 0; j < todos.length; j++) {
        if (!todos[j].done) {
          open += 1;
        }
      }
      const row = new SessionRow();
      row.session = s;
      row.todoOpen = open;
      if (current !== null) {
        current.rows.push(row);
      }
    }
    this.groups = groups;
  }

  private statusText(): string {
    if (this.recording) {
      if (this.headset && this.headsetKeyActive) {
        // 耳机键归我们：告诉用户「按一下就能停」，比说「点标记」有用得多
        return `正在记录 · 第 ${this.segIndex} 段 · 耳机键按一下结束，下一首键打重点`;
      }
      return `正在记录 · 第 ${this.segIndex} 段 · 点「标记」可打重点`;
    }
    if (this.waiting) {
      return '通话占用了麦克风，已暂停；通话结束后自动接着录（前面已保存）';
    }
    if (this.needKey) {
      return '还没填 API Key：到「设置 → AI 服务」填一个，录音结束会自动转写 + 总结';
    }
    if (this.headset) {
      if (this.headsetKeyActive) {
        return `${this.headsetName} 已连接 · 按耳机播放键开始记录`;
      }
      return `${this.headsetName} 已连接 · 耳机键还在管音乐，点下面按钮开始记录`;
    }
    return '戴上蓝牙耳机会自动开始记录，也可以直接点下面按钮手动录';
  }

  @Builder
  header() {
    Column() {
      Row() {
        Column() {
          Row() {
            Text(TimeUtil.dayLabel(Date.now()))
              .fontSize(23)
              .fontWeight(FontWeight.Bold)
              .fontColor('#1A1A1A')
            Text(` ${TimeUtil.dayOfWeek(Date.now())}`)
              .fontSize(13)
              .fontColor('#9E9E9E')
              .margin({ left: 6, top: 8 })
          }

          Text(`今天 ${this.todayCount} 段 · 共 ${TimeUtil.duration(this.todayMs)}`)
            .fontSize(12)
            .fontColor('#6B6B6B')
            .margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        if (this.recording) {
          Row() {
            Circle()
              .width(7)
              .height(7)
              .fill('#E60012')
            Text('录音中')
              .fontSize(11)
              .fontColor('#E60012')
              .margin({ left: 5 })
          }
          .padding({ left: 10, right: 10, top: 5, bottom: 5 })
          .backgroundColor('#FDECEE')
          .borderRadius(12)
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)

      Row() {
        Text(this.statusText())
          .fontSize(12)
          .fontColor(this.waiting ? '#B0000E' : '#6B6B6B')
          .maxLines(3)
          .lineHeight(18)
          .layoutWeight(1)
      }
      .width('100%')
      .padding({ top: 10 })
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 14, bottom: 6 })
  }

  @Builder
  sourceChip(label: string, bt: boolean) {
    Text(label)
      .fontSize(12)
      .fontColor(this.btMic === bt ? '#E60012' : '#6B6B6B')
      .fontWeight(this.btMic === bt ? FontWeight.Medium : FontWeight.Normal)
      .padding({ left: 12, right: 12, top: 7, bottom: 7 })
      .backgroundColor(this.btMic === bt ? '#FDECEE' : '#FFFFFF')
      .borderRadius(14)
      .border({
        width: 1,
        color: this.btMic === bt ? '#E60012' : '#E8E8E8'
      })
      .onClick(() => {
        this.pickSource(bt);
      })
  }

  @Builder
  recordCard() {
    Column() {
      Row() {
        Text('音源')
          .fontSize(11)
          .fontColor('#9E9E9E')
        Blank()
        Text(this.recording ? '切换会分成两段，不丢内容' : '点一下即刻生效')
          .fontSize(10)
          .fontColor('#BDBDBD')
      }
      .width('100%')

      Row() {
        this.sourceChip('手机麦克风', false)
        this.sourceChip('蓝牙耳机', true)
      }
      .width('100%')
      .margin({ top: 8 })

      Row() {
        Text(TimeUtil.clock(this.elapsed))
          .fontSize(30)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.recording ? '#E60012' : '#1A1A1A')
        if (this.segIndex > 1) {
          Text(`第 ${this.segIndex} 段`)
            .fontSize(11)
            .fontColor('#6B6B6B')
            .margin({ left: 8, top: 14 })
        }
        Blank()
        if (this.recording) {
          Text(`重点 ${this.markCount}`)
            .fontSize(11)
            .fontColor('#B0000E')
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .backgroundColor('#FDECEE')
            .borderRadius(8)
            .onClick(() => {
              Recorder.get().markPoint();
              this.markCount = Recorder.get().markCount();
              promptAction.showToast({ message: `已标记第 ${this.markCount} 处重点` });
            })
        }
      }
      .width('100%')
      .margin({ top: 14 })
      .alignItems(VerticalAlign.Center)

      Row() {
        Button(this.recording ? '结束记录' : (this.waiting ? '已暂停（等通话结束）' : '开始记录'))
          .fontSize(15)
          .fontColor('#FFFFFF')
          .height(48)
          .layoutWeight(1)
          .backgroundColor(this.recording ? '#B0000E' : '#E60012')
          .borderRadius(24)
          .enabled(!this.waiting)
          .onClick(() => {
            this.toggleRecord();
          })
      }
      .width('100%')
      .margin({ top: 12 })
    }
    .width('100%')
    .padding(14)
    .backgroundColor('#FFFFFF')
    .borderRadius(16)
    .border({ width: 1, color: '#EFEFEF' })
  }

  @Builder
  groupHeader(label: string) {
    Row() {
      Text(label)
        .fontSize(13)
        .fontWeight(FontWeight.Medium)
        .fontColor('#6B6B6B')
      Blank()
    }
    .width('100%')
    .padding({ left: 18, right: 18, top: 16, bottom: 8 })
  }

  @Builder
  sessionItem(row: SessionRow) {
    Column() {
      Row() {
        Text(row.session.title)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor('#1A1A1A')
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .layoutWeight(1)
        if (row.session.marked) {
          Text('重点')
            .fontSize(10)
            .fontColor('#B0000E')
            .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            .backgroundColor('#FDECEE')
            .borderRadius(6)
            .margin({ left: 8 })
        }
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)

      if (row.session.summary.length > 0) {
        Text(row.session.summary)
          .fontSize(12)
          .fontColor('#6B6B6B')
          .maxLines(2)
          .lineHeight(18)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 6 })
          .width('100%')
      } else if (row.session.transcript.length > 0) {
        Text(row.session.transcript)
          .fontSize(12)
          .fontColor('#9E9E9E')
          .maxLines(2)
          .lineHeight(18)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 6 })
          .width('100%')
      }

      Row() {
        Text(TimeUtil.time(row.session.startAt))
          .fontSize(11)
          .fontColor('#9E9E9E')
        Text('·')
          .fontSize(11)
          .fontColor('#D0D0D0')
          .margin({ left: 5, right: 5 })
        Text(TimeUtil.duration(row.session.durationMs))
          .fontSize(11)
          .fontColor('#9E9E9E')
        if (row.session.project.length > 0) {
          Text(row.session.project)
            .fontSize(11)
            .fontColor('#6B6B6B')
            .padding({ left: 6, right: 6, top: 2, bottom: 2 })
            .backgroundColor('#F5F5F5')
            .borderRadius(6)
            .margin({ left: 6 })
        }
        Blank()
        if (row.todoOpen > 0) {
          Text(`待办 ${row.todoOpen}`)
            .fontSize(11)
            .fontColor('#B0000E')
            .margin({ right: 8 })
        }
        Text(row.session.quality === Quality.REVIEW ? '待确认' : statusLabel(row.session))
          .fontSize(10)
          .fontColor(row.session.quality === Quality.REVIEW ? '#B0000E' : '#9E9E9E')
      }
      .width('100%')
      .margin({ top: 8 })
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
    .backgroundColor('#FFFFFF')
    .borderRadius(14)
    .border({ width: 1, color: '#F0F0F0' })
    .onClick(() => {
      router.pushUrl({ url: 'pages/SessionDetailPage', params: { id: row.session.id } });
    })
  }

  @Builder
  emptyState() {
    Column() {
      Text('今天还没有记录')
        .fontSize(14)
        .fontColor('#6B6B6B')
      Text('戴上蓝牙耳机自动开始，或点上面「开始记录」')
        .fontSize(12)
        .fontColor('#9E9E9E')
        .margin({ top: 6 })
    }
    .width('100%')
    .padding({ top: 46, bottom: 46 })
  }

  build() {
    Column() {
      this.header()

      List() {
        ListItem() {
          this.recordCard()
        }
        .padding({ left: 18, right: 18, top: 8, bottom: 4 })

        ForEach(this.groups, (g: DayGroup) => {
          ListItemGroup({ header: this.groupHeader(g.label) }) {
            ForEach(g.rows, (row: SessionRow) => {
              ListItem() {
                this.sessionItem(row)
              }
              .padding({ left: 18, right: 18, top: 5 })
            })
          }
        }, (g: DayGroup) => g.label)

        if (this.groups.length === 0) {
          ListItem() {
            this.emptyState()
          }
        }
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
      this.onRefresh();
    })
  }

  private async pickSource(bt: boolean): Promise<void> {
    if (bt && !this.headset) {
      promptAction.showToast({ message: '还没连上蓝牙耳机：连上后再选，或继续用手机麦克风' });
      return;
    }
    this.btMic = bt;
    await Repo.setBool(Keys.BT_MIC, bt);
    if (this.recording) {
      await Recorder.get().switchSource(bt);
      promptAction.showToast({ message: bt ? '已切到耳机麦克风（新开一段）' : '已切到手机麦克风（新开一段）' });
    }
  }

  private async toggleRecord(): Promise<void> {
    const rec = Recorder.get();
    if (rec.isRecording()) {
      await rec.stop('user');
      this.recording = false;
      this.elapsed = 0;
      promptAction.showToast({ message: '已结束，正在整理…' });
      this.reload();
      return;
    }
    const ok = await rec.start(false);
    if (!ok) {
      promptAction.showToast({ message: '无法开始记录：请检查麦克风权限是否已允许' });
    }
  }
}
