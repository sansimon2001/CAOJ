import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';

/** 免责声明：随包内置（rawfile），不联网也能看 */
export class Legal {
  private static cache: string = '';

  static async read(ctx: common.UIAbilityContext): Promise<string> {
    if (Legal.cache.length > 0) {
      return Legal.cache;
    }
    try {
      const data = await ctx.resourceManager.getRawFileContent('legal_disclaimer.txt');
      const text = util.TextDecoder.create('utf-8').decodeWithStream(data);
      Legal.cache = text;
      return text;
    } catch (e) {
      return '免责声明读取失败。可发邮件到 sansimon2001@163.com 索取全文。';
    }
  }
}

/** 作者联系方式与反馈 */
export class Contact {
  static readonly EMAIL: string = 'sansimon2001@163.com';
  static readonly APP_NAME: string = '草记';
  static readonly VERSION: string = '1.25.0（鸿蒙原生版）';

  /** 反馈正文预填：带上版本与机型，省一轮来回追问 */
  static feedbackBody(deviceInfo: string): string {
    return `\n\n———\n应用：${Contact.APP_NAME} ${Contact.VERSION}\n设备：${deviceInfo}\n问题描述：\n`;
  }

  static async openMail(ctx: common.UIAbilityContext, deviceInfo: string): Promise<boolean> {
    try {
      const subject = encodeURIComponent(`${Contact.APP_NAME} 反馈（${Contact.VERSION}）`);
      const body = encodeURIComponent(Contact.feedbackBody(deviceInfo));
      await ctx.startAbility({
        action: 'ohos.want.action.sendToData',
        uri: `mailto:${Contact.EMAIL}?subject=${subject}&body=${body}`
      });
      return true;
    } catch (e) {
      return false;
    }
  }
}
