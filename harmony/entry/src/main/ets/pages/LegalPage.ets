import { router, promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { Legal, Contact } from '../util/Legal';

/**
 * 用户协议与免责声明
 *
 * 全文随包内置（rawfile），不联网也能看、能截图转发——
 * 所以这个页面故意不加防截屏：用户可能正需要把条款发给别人看。
 */
@Entry
@Component
struct LegalPage {
  @State content: string = '';
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.load();
  }

  private ctx(): common.UIAbilityContext {
    return getContext(this) as common.UIAbilityContext;
  }

  private async load(): Promise<void> {
    this.content = await Legal.read(this.ctx());
    this.loaded = true;
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
      Text('用户协议与免责声明')
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .fontColor('#FFFFFF')
      Blank()
      Text('复制')
        .fontSize(13)
        .fontColor('#FFD9DC')
        .onClick(async () => {
          const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, this.content);
          await pasteboard.getSystemPasteboard().setData(data);
          promptAction.showToast({ message: '全文已复制' });
        })
    }
    .width('100%')
    .height(52)
    .padding({ left: 14, right: 14 })
    .linearGradient({
      angle: 90,
      colors: [['#E60012', 0.0], ['#C4000F', 1.0]]
    })
  }

  build() {
    Column() {
      this.topBar()

      Scroll() {
        Column() {
          if (!this.loaded) {
            LoadingProgress().width(26).height(26).color('#E60012')
          } else {
            Text(this.content)
              .fontSize(12)
              .fontColor('#333333')
              .lineHeight(21)
              .width('100%')
              .copyOption(CopyOptions.LocalDevice)

            Column() {
              Text('反馈邮箱')
                .fontSize(11)
                .fontColor('#9E9E9E')
                .width('100%')
              Text(Contact.EMAIL)
                .fontSize(15)
                .fontWeight(FontWeight.Bold)
                .fontColor('#E60012')
                .margin({ top: 6 })
                .width('100%')
                .onClick(async () => {
                  const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, Contact.EMAIL);
                  await pasteboard.getSystemPasteboard().setData(data);
                  promptAction.showToast({ message: '邮箱已复制' });
                })
            }
            .width('100%')
            .padding(14)
            .backgroundColor('#FFF7F8')
            .borderRadius(12)
            .margin({ top: 20, bottom: 30 })
          }
        }
        .width('100%')
        .padding({ left: 18, right: 18, top: 16 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
      .edgeEffect(EdgeEffect.Spring)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }
}
