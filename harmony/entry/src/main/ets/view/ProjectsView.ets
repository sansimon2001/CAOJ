import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import { Store } from '../data/Store';
import { LlmClient } from '../service/LlmClient';
import { Session, ProjectSummary } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';

/**
 * 项目：按项目聚合记录
 *
 * 两块视图共用一个组件：项目列表 → 项目详情（内嵌切换，避免为每个项目开一个页面）。
 * 详情里的「生成洞见」会把该项目最近的记录合成一份现状摘要，写进 project_insights，
 * 下次进来直接显示上次结果，不会每次打开都烧一次 token。
 */
@Component
export struct ProjectsView {
  @StorageLink('refreshTick') refreshTick: number = 0;
  @State items: ProjectSummary[] = [];
  @State openProject: string = '';
  @State detail: Session[] = [];
  @State insight: string = '';
  @State busy: boolean = false;
  @State detailCount: number = 0;
  @State detailMs: number = 0;

  aboutToAppear(): void {
    this.loadList();
  }

  onRefresh(): void {
    if (this.openProject.length > 0) {
      this.loadDetail(this.openProject);
    } else {
      this.loadList();
    }
  }

  private async loadList(): Promise<void> {
    this.items = await Store.projectSummaries(60);
  }

  private async loadDetail(project: string): Promise<void> {
    const list = await Store.byProject(project);
    this.detail = list;
    let ms = 0;
    for (let i = 0; i < list.length; i++) {
      ms += list[i].durationMs;
    }
    this.detailCount = list.length;
    this.detailMs = ms;
    this.insight = await Store.getProjectInsight(project);
  }

  @Builder
  listHeader() {
    Column() {
      Text('项目')
        .fontSize(23)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
      Text('记录会自动归到项目下，同名项目自动合并')
        .fontSize(12)
        .fontColor('#6B6B6B')
        .margin({ top: 4 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 18, right: 18, top: 14, bottom: 10 })
  }

  @Builder
  projectCard(item: ProjectSummary) {
    Column() {
      Row() {
        Text(item.project)
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .fontColor('#1A1A1A')
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(`${item.count} 段`)
          .fontSize(11)
          .fontColor('#6B6B6B')
      }
      .width('100%')

      Text(item.summary.length > 0 ? item.summary : '还没有摘要')
        .fontSize(12)
        .fontColor(item.summary.length > 0 ? '#6B6B6B' : '#BDBDBD')
        .maxLines(2)
        .lineHeight(18)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 8 })
        .width('100%')

      Row() {
        Text(`最近 ${TimeUtil.dayLabel(item.lastAt)} ${TimeUtil.time(item.lastAt)}`)
          .fontSize(11)
          .fontColor('#9E9E9E')
        Blank()
        Text('查看 →')
          .fontSize(11)
          .fontColor('#B0000E')
      }
      .width('100%')
      .margin({ top: 10 })
    }
    .width('100%')
    .padding(14)
    .backgroundColor('#FFFFFF')
    .borderRadius(14)
    .border({ width: 1, color: '#F0F0F0' })
    .onClick(() => {
      this.openProject = item.project;
      this.loadDetail(item.project);
    })
  }

  @Builder
  detailHeader() {
    Column() {
      Row() {
        Text('‹ 全部项目')
          .fontSize(13)
          .fontColor('#B0000E')
          .onClick(() => {
            this.openProject = '';
            this.insight = '';
            this.loadList();
          })
        Blank()
        if (this.busy) {
          LoadingProgress()
            .width(16)
            .height(16)
            .color('#E60012')
        }
      }
      .width('100%')

      Text(this.openProject)
        .fontSize(23)
        .fontWeight(FontWeight.Bold)
        .fontColor('#1A1A1A')
        .margin({ top: 10 })
        .width('100%')

      Text(`${this.detailCount} 段记录 · 共 ${TimeUtil.duration(this.detailMs)}`)
        .fontSize(12)
        .fontColor('#6B6B6B')
        .margin({ top: 4 })
        .width('100%')
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 18, right: 18, top: 14, bottom: 10 })
  }

  @Builder
  insightCard() {
    Column() {
      Row() {
        Text('项目洞见')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor('#1A1A1A')
        Blank()
        Text(this.busy ? '生成中…' : (this.insight.length > 0 ? '重新生成' : '生成'))
          .fontSize(12)
          .fontColor('#B0000E')
          .onClick(() => {
            this.generateInsight();
          })
      }
      .width('100%')

      Text(this.insight.length > 0 ? this.insight : '点「生成」把这段时间的记录合成一份现状：进展、悬而未决、风险、下一步。')
        .fontSize(12)
        .fontColor(this.insight.length > 0 ? '#1A1A1A' : '#9E9E9E')
        .lineHeight(20)
        .margin({ top: 10 })
        .width('100%')
    }
    .width('100%')
    .padding(14)
    .backgroundColor('#FFFDFD')
    .borderRadius(14)
    .border({ width: 1, color: '#FADCDE' })
  }

  @Builder
  detailItem(s: Session) {
    Column() {
      Text(s.title)
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor('#1A1A1A')
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
      if (s.summary.length > 0) {
        Text(s.summary)
          .fontSize(12)
          .fontColor('#6B6B6B')
          .maxLines(2)
          .lineHeight(18)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 5 })
          .width('100%')
      }
      Text(`${TimeUtil.dateFull(s.startAt)} ${TimeUtil.time(s.startAt)} · ${TimeUtil.duration(s.durationMs)}`)
        .fontSize(11)
        .fontColor('#9E9E9E')
        .margin({ top: 8 })
        .width('100%')
    }
    .width('100%')
    .padding(14)
    .backgroundColor('#FFFFFF')
    .borderRadius(14)
    .border({ width: 1, color: '#F0F0F0' })
    .onClick(() => {
      router.pushUrl({ url: 'pages/SessionDetailPage', params: { id: s.id } });
    })
  }

  @Builder
  emptyList() {
    Column() {
      Text('还没有归到项目的记录')
        .fontSize(14)
        .fontColor('#6B6B6B')
      Text('整理时模型会判断归属；也可以在记录详情里手动改项目名')
        .fontSize(12)
        .fontColor('#9E9E9E')
        .margin({ top: 6 })
        .textAlign(TextAlign.Center)
    }
    .width('100%')
    .padding({ left: 30, right: 30, top: 60 })
  }

  build() {
    Column() {
      if (this.openProject.length === 0) {
        this.listHeader()
        List() {
          ForEach(this.items, (item: ProjectSummary) => {
            ListItem() {
              this.projectCard(item)
            }
            .padding({ left: 18, right: 18, top: 5 })
          }, (item: ProjectSummary) => item.project)

          if (this.items.length === 0) {
            ListItem() {
              this.emptyList()
            }
          }
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)
      } else {
        this.detailHeader()
        List() {
          ListItem() {
            this.insightCard()
          }
          .padding({ left: 18, right: 18, top: 4, bottom: 4 })

          ForEach(this.detail, (s: Session) => {
            ListItem() {
              this.detailItem(s)
            }
            .padding({ left: 18, right: 18, top: 5 })
          }, (s: Session) => `${s.id}`)
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
    .onAppear(() => {
      this.onRefresh();
    })
  }

  private async generateInsight(): Promise<void> {
    if (this.busy) {
      return;
    }
    if (!LlmClient.isConfigured()) {
      promptAction.showToast({ message: '还没配置大模型：到「设置 → AI 服务」填一个 API Key' });
      return;
    }
    if (this.detail.length === 0) {
      promptAction.showToast({ message: '这个项目还没有记录' });
      return;
    }
    this.busy = true;
    let material = '';
    const limit = Math.min(this.detail.length, 8);
    for (let i = 0; i < limit; i++) {
      const s = this.detail[i];
      material += `【${TimeUtil.dateFull(s.startAt)}】${s.title}\n${s.summary.length > 0 ? s.summary : s.transcript.substring(0, 600)}\n\n`;
    }
    const text = await LlmClient.projectInsight(this.openProject, material);
    this.busy = false;
    if (text.length === 0) {
      promptAction.showToast({ message: '生成失败：检查网络或 API 配置' });
      return;
    }
    this.insight = text;
    await Store.saveProjectInsight(this.openProject, text);
  }
}
