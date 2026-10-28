import { promptAction } from '@kit.ArkUI';
import { Store } from '../data/Store';
import { LlmClient } from '../service/LlmClient';
import { TodoItem } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';

/**
 * 觉察：把记录变成可复盘的东西
 *
 * 三块：这段时间的数据（记录数/时长/待办完成率）、到期提醒、行为复盘。
 * 复盘只基于本机记录生成，不带任何未在记录里出现的推测。
 */
@Component
export struct InsightView {
  @StorageLink('refreshTick') refreshTick: number = 0;
  @State sessionTotal: number = 0;
  @State minutesTotal: number = 0;
  @State todoOpen: number = 0;
  @State todoDone: number = 0;
  @State overdue: number = 0;
  @State dueToday: number = 0;
  @State todos: TodoItem[] = [];
  @State retrospect: string = '';
  @State busy: boolean = false;
  @State weekCount: number = 0;

  aboutToAppear(): void {
    this.reload();
  }

  onRefresh(): void {
    this.reload();
  }

  private async reload(): Promise<void> {
    const all = await Store.recent(1000);
    this.sessionTotal = all.length;
    let ms = 0;
    const weekAgo = Date.now() - 7 * 86400000;
    let week = 0;
    for (let i = 0; i < all.length; i++) {
      ms += all[i].durationMs;
      if (all[i].startAt >= weekAgo) {
        week += 1;
      }
    }
    this.minutesTotal = Math.round(ms / 60000);
    this.weekCount = week;

    const stats = await Store.todoStats();
    this.todoOpen = stats[0];
    this.todoDone = stats[1];
    const due = await Store.todoDueStats(TimeUtil.today());
    this.overdue = due[0];
    this.dueToday = due[1];
    this.todos = await Store.allTodos(40);
  }

  private rateText(): string {
    const total = this.todoOpen + this.todoDone;
    if (total === 0) {
      return '—';
    }
    return `${Math.round(this.todoDone * 100 / total)}%`;
  }

  @Builder
  statCard(value: string, label: string) {
    Column() {
      Text(value)
        .fontSize(20)
        .fontWeight(FontWeight.Medium)
        .fontColor('#1A1A1A')
      Text(label)
        .fontSize(11)
        .fontColor('#9E9E9E')
        .margin({ top: 4 })
    }
    .layoutWeight(1)
    .padding({ top: 14, bottom: 14 })
  }

  @Builder
  todoRow(t: TodoItem) {
    Row() {
      Circle()
        .width(16)
        .height(16)
        .fill(t.done ? '#E60012' : '#FFFFFF')
        .stroke(t.done ? '#E60012' : '#CFCFCF')
        .strokeWidth(1.4)
        .onClick(async () => {
          await Store.setTodoDone(t.id, !t.done);
          this.reload();
        })

      Column() {
        Text(t.text)
          .fontSize(13)
          .fontColor(t.done ? '#9E9E9E' : '#1A1A1A')
          .decoration({ type: t.done ? TextDecorationType.LineThrough : TextDecorationType.None })
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        if (t.dueDate.length > 0) {
          Text(t.done ? t.dueDate : this.dueText(t.dueDate))
            .fontSize(10)
            .fontColor(this.isOverdue(t.dueDate) && !t.done ? '#B0000E' : '#9E9E9E')
            .margin({ top: 3 })
            .width('100%')
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 10 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 11, bottom: 11 })
    .alignItems(VerticalAlign.Top)
  }

  private dueText(due: string): string {
    const diff = TimeUtil.daysBetween(TimeUtil.today(), due);
    if (diff === 0) {
      return `今天到期 · ${due}`;
    }
    if (diff === 1) {
      return `明天到期 · ${due}`;
    }
    if (diff < 0) {
      return `已逾期 ${-diff} 天 · ${due}`;
    }
    return `${diff} 天后到期 · ${due}`;
  }

  private isOverdue(due: string): boolean {
    return TimeUtil.daysBetween(TimeUtil.today(), due) < 0;
  }

  build() {
    Column() {
      Column() {
        Text('觉察')
          .fontSize(23)
          .fontWeight(FontWeight.Bold)
          .fontColor('#1A1A1A')
        Text('记录攒够了，才看得见自己的模式')
          .fontSize(12)
          .fontColor('#6B6B6B')
          .margin({ top: 4 })
      }
      .width('100%')
      .alignItems(HorizontalAlign.Start)
      .padding({ left: 18, right: 18, top: 14, bottom: 10 })

      List() {
        ListItem() {
          Row() {
            this.statCard(`${this.sessionTotal}`, '总记录')
            Divider().vertical(true).height(30).color('#EFEFEF')
            this.statCard(`${this.minutesTotal}`, '累计分钟')
            Divider().vertical(true).height(30).color('#EFEFEF')
            this.statCard(this.rateText(), '待办完成率')
          }
          .width('100%')
          .backgroundColor('#FFFFFF')
          .borderRadius(14)
          .border({ width: 1, color: '#F0F0F0' })
        }
        .padding({ left: 18, right: 18, top: 4 })

        if (this.overdue > 0 || this.dueToday > 0) {
          ListItem() {
            Row() {
              Text(this.overdue > 0
                ? `${this.overdue} 项待办已逾期`
                : `${this.dueToday} 项今天到期`)
                .fontSize(12)
                .fontColor('#B0000E')
              Blank()
              Text(`本周 ${this.weekCount} 段记录`)
                .fontSize(11)
                .fontColor('#9E9E9E')
            }
            .width('100%')
            .padding(12)
            .backgroundColor('#FDECEE')
            .borderRadius(12)
          }
          .padding({ left: 18, right: 18, top: 8 })
        }

        ListItem() {
          Column() {
            Row() {
              Text('行为复盘')
                .fontSize(14)
                .fontWeight(FontWeight.Medium)
                .fontColor('#1A1A1A')
              Blank()
              Text(this.busy ? '生成中…' : (this.retrospect.length > 0 ? '重新生成' : '生成复盘'))
                .fontSize(12)
                .fontColor('#B0000E')
                .onClick(() => {
                  this.generate();
                })
            }
            .width('100%')

            Text(this.retrospect.length > 0 ? this.retrospect
              : '基于最近 7 天的记录，看这段时间在忙什么、沟通习惯、承诺但没推进的事，以及下周可做的三件事。')
              .fontSize(12)
              .fontColor(this.retrospect.length > 0 ? '#1A1A1A' : '#9E9E9E')
              .lineHeight(20)
              .margin({ top: 10 })
              .width('100%')
          }
          .width('100%')
          .padding(14)
          .backgroundColor('#FFFFFF')
          .borderRadius(14)
          .border({ width: 1, color: '#F0F0F0' })
        }
        .padding({ left: 18, right: 18, top: 12 })

        ListItem() {
          Row() {
            Text('待办')
              .fontSize(13)
              .fontWeight(FontWeight.Medium)
              .fontColor('#6B6B6B')
            Blank()
            Text(`未完成 ${this.todoOpen} · 已完成 ${this.todoDone}`)
              .fontSize(11)
              .fontColor('#9E9E9E')
          }
          .width('100%')
        }
        .padding({ left: 18, right: 18, top: 18, bottom: 8 })

        ForEach(this.todos, (t: TodoItem) => {
          ListItem() {
            this.todoRow(t)
          }
          .backgroundColor('#FFFFFF')
          .borderRadius(12)
          .border({ width: 1, color: '#F0F0F0' })
          .margin({ left: 18, right: 18, top: 5 })
        }, (t: TodoItem) => `${t.id}-${t.done}-${t.dueDate}`)

        if (this.todos.length === 0) {
          ListItem() {
            Column() {
              Text('还没有待办')
                .fontSize(13)
                .fontColor('#6B6B6B')
              Text('整理记录时会自动提取「有动作、有对象」的事')
                .fontSize(11)
                .fontColor('#9E9E9E')
                .margin({ top: 5 })
            }
            .width('100%')
            .padding({ top: 30, bottom: 40 })
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

  private async generate(): Promise<void> {
    if (this.busy) {
      return;
    }
    if (!LlmClient.isConfigured()) {
      promptAction.showToast({ message: '还没配置大模型：到「设置 → AI 服务」填一个 API Key' });
      return;
    }
    this.busy = true;
    const since = Date.now() - 7 * 86400000;
    const list = await Store.between(since, Date.now());
    if (list.length === 0) {
      this.busy = false;
      promptAction.showToast({ message: '最近 7 天还没有记录' });
      return;
    }
    let material = '';
    for (let i = 0; i < list.length && i < 20; i++) {
      const s = list[i];
      material += `【${TimeUtil.dateFull(s.startAt)}】${s.title}\n`;
      material += s.summary.length > 0 ? s.summary : s.transcript.substring(0, 400);
      material += '\n\n';
    }
    const text = await LlmClient.retrospect(material);
    this.busy = false;
    if (text.length === 0) {
      promptAction.showToast({ message: '生成失败：检查网络或 API 配置' });
      return;
    }
    this.retrospect = text;
  }
}
