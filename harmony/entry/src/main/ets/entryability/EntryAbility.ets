import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { Repo, Keys } from '../data/Repo';
import { HeadsetKeys } from '../service/HeadsetKeys';

/**
 * 应用入口
 *
 * 启动顺序很关键：先初始化存储（加密密钥 → 偏好 → 数据库），再加载首页。
 * 否则首页 aboutToAppear 里读配置会撞上还没准备好的 Store，白屏一次。
 */
export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    // 从其它应用分享进来的文本：先存进 AppStorage，等首页就绪后再入库
    const shared = EntryAbility.extractSharedText(want);
    if (shared.length > 0) {
      AppStorage.setOrCreate('pendingSharedText', shared);
    }
  }

  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    const shared = EntryAbility.extractSharedText(want);
    if (shared.length > 0) {
      AppStorage.setOrCreate('pendingSharedText', shared);
      AppStorage.setOrCreate('refreshTick', Date.now());
    }
  }

  async onWindowStageCreate(windowStage: window.WindowStage): Promise<void> {
    try {
      await Repo.init(this.context);
    } catch (e) {
      const err = e as BusinessError;
      console.error(`初始化失败：${err.message}`);
    }
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        console.error(`加载首页失败：${err.code}`);
        return;
      }
      // 隐私开关：默认禁止截屏与多任务预览
      if (Repo.getBool(Keys.SECURE_WINDOW, true)) {
        try {
          const win = windowStage.getMainWindowSync();
          win.setWindowPrivacyMode(true);
        } catch (e) {
          // 设备不支持就跳过，不影响使用
        }
      }
    });
  }

  /**
   * 退出时把媒体会话放掉
   *
   * 不放的话，桌面媒体卡片、耳机侧可能还挂着一条「草记」，
   * 用户按播放键会被一个已经不工作的 App 接走 —— 正是「按键乱了」的来源。
   */
  onDestroy(): void {
    HeadsetKeys.get().release().then(() => {
    }).catch(() => {
    });
  }

  /**
   * 从 want 里捞分享文本
   *
   * 各家应用传的键名不统一（ability.params.text / text / 自定义），
   * 这里按「键名含 text」+ 字符串类型 + 非空 来找，取最长的一段。
   */
  private static extractSharedText(want: Want): string {
    let best = '';
    const params = want.parameters;
    if (params !== undefined && params !== null) {
      const rec = params as Record<string, Object>;
      const keys = Object.keys(rec);
      for (let i = 0; i < keys.length; i++) {
        const key = keys[i];
        if (key.toLowerCase().indexOf('text') < 0) {
          continue;
        }
        const v = rec[key];
        if (typeof v === 'string') {
          const s = v as string;
          if (s.length > best.length) {
            best = s;
          }
        }
      }
    }
    if (best.length === 0 && want.uri !== undefined && want.uri.length > 0) {
      const uri = want.uri;
      if (uri.indexOf('mailto:') >= 0 || uri.indexOf('http') === 0) {
        best = uri;
      }
    }
    return best;
  }
}
