import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { SecureStore } from '../util/SecureStore';
import { Store } from './Store';

/** 偏好键：与 Android 版同名，方便两端配置口径一致 */
export class Keys {
  static readonly LLM_BASE: string = 'llm_base';
  static readonly LLM_KEY: string = 'llm_key';
  static readonly LLM_MODEL: string = 'llm_model';
  static readonly LLM_PROVIDER: string = 'llm_provider';
  static readonly ASR_URL: string = 'asr_url';
  static readonly ASR_KEY: string = 'asr_key';
  static readonly ASR_MODEL: string = 'asr_model';
  static readonly ASR_PROVIDER: string = 'asr_provider';
  static readonly ASR_DIARIZE: string = 'asr_diarize';
  /**
   * 识别语言：存的是「用户想识别什么」（见 SpeechLang），不是直接发给服务端的代码。
   * 各家的 language 口径不同，发送前要经 SpeechLangs.plan 换算，并把降级实情告诉用户。
   */
  static readonly ASR_LANG: string = 'asr_lang';
  /** 热词表：人名、公司名、专业词，逗号或换行分隔；只发给支持 prompt 的服务 */
  static readonly ASR_PROMPT: string = 'asr_prompt';
  /** 方言稿自动转普通话书面语（默认开）：摘要与待办用标准语版本，原文仍原样保留 */
  static readonly DIALECT_STD: string = 'dialect_std';
  /** 项目档案自举只做一次：之后以档案表为唯一事实来源 */
  static readonly PROJECT_SEEDED: string = 'project_seeded';
  static readonly AUTO_RECORD: string = 'auto_record';
  static readonly BT_MIC: string = 'bt_mic';
  /**
   * 耳机键归谁：recording_only / always / off
   *
   * 蓝牙耳机只有一组播放/暂停键，系统把它送给「当前活跃的媒体会话」。
   * 草记注册自己的媒体会话就能接管它——但接管意味着音乐拿不到，
   * 所以做成三档让用户自己选，默认只在录音中接管。
   */
  static readonly HEADSET_MODE: string = 'headset_mode';
  /**
   * 手表遥控开关
   *
   * 开着时手机在局域网里监听一个 UDP 端口，手表（安卓表）发几个字节就能
   * 控制开始/结束/打点。手表不碰麦克风也不联网上传，只在按的时候发一个包。
   */
  static readonly WATCH_LINK: string = 'watch_link';
  static readonly KEEP_AUDIO: string = 'keep_audio';
  static readonly SECURE_WINDOW: string = 'privacy_secure_window';
  static readonly PRIVACY_ACK: string = 'privacy_ack';
  static readonly DEFAULTS_SEEDED: string = 'defaults_seeded';
  static readonly WB_AUTO: string = 'wb_auto';
  static readonly WB_WEBHOOK: string = 'wb_webhook';
  static readonly WB_TOKEN: string = 'wb_token';
  static readonly FS_HOOK: string = 'fs_hook';
  static readonly FS_SECRET: string = 'fs_secret';
  static readonly DT_HOOK: string = 'dt_hook';
  static readonly DT_SECRET: string = 'dt_secret';
  static readonly REMIND_ENABLE: string = 'remind_enable';
  static readonly REMIND_HOUR: string = 'remind_hour';
  static readonly LAST_GROUP: string = 'last_group';
}

/** 需要加密落库的键：泄露即事故 */
const SECRET_KEYS: string[] = [
  Keys.LLM_KEY, Keys.ASR_KEY, Keys.WB_TOKEN, Keys.FS_SECRET, Keys.DT_SECRET
];

export class Repo {
  private static pref: preferences.Preferences | null = null;
  private static ctx: common.Context | null = null;

  /** 应用启动时调一次：加密存储、偏好、数据库、默认服务商 */
  static async init(ctx: common.Context): Promise<void> {
    if (Repo.pref !== null) {
      return;
    }
    Repo.ctx = ctx;
    await SecureStore.init(ctx);
    Repo.pref = await preferences.getPreferences(ctx, 'caoji_settings');
    await Store.init(ctx);
    await Repo.seedDefaultProviders();
  }

  static context(): common.Context {
    const c = Repo.ctx;
    if (c === null) {
      throw new Error('Repo 尚未初始化');
    }
    return c;
  }

  // ==================== 读写 ====================

  private static isSecret(key: string): boolean {
    return SECRET_KEYS.indexOf(key) >= 0;
  }

  static getStr(key: string, def: string = ''): string {
    if (Repo.isSecret(key)) {
      const v = SecureStore.get(key);
      return v.length > 0 ? v : def;
    }
    const p = Repo.pref;
    if (p === null) {
      return def;
    }
    const v = p.getSync(key, def);
    return typeof v === 'string' ? v : def;
  }

  static async setStr(key: string, value: string): Promise<void> {
    if (Repo.isSecret(key)) {
      await SecureStore.put(key, value);
      return;
    }
    const p = Repo.pref;
    if (p === null) {
      return;
    }
    await p.put(key, value);
    await p.flush();
  }

  static getBool(key: string, def: boolean = false): boolean {
    const p = Repo.pref;
    if (p === null) {
      return def;
    }
    const v = p.getSync(key, def);
    return typeof v === 'boolean' ? v : def;
  }

  static async setBool(key: string, value: boolean): Promise<void> {
    const p = Repo.pref;
    if (p === null) {
      return;
    }
    await p.put(key, value);
    await p.flush();
  }

  static getNum(key: string, def: number = 0): number {
    const p = Repo.pref;
    if (p === null) {
      return def;
    }
    const v = p.getSync(key, def);
    return typeof v === 'number' ? v : def;
  }

  static async setNum(key: string, value: number): Promise<void> {
    const p = Repo.pref;
    if (p === null) {
      return;
    }
    await p.put(key, value);
    await p.flush();
  }

  // ==================== 配置就绪判断 ====================

  static hasLlm(): boolean {
    return Repo.getStr(Keys.LLM_BASE).length > 0
      && Repo.getStr(Keys.LLM_KEY).length > 0
      && Repo.getStr(Keys.LLM_MODEL).length > 0;
  }

  static hasAsr(): boolean {
    return Repo.getStr(Keys.ASR_URL).length > 0
      && Repo.getStr(Keys.ASR_KEY).length > 0
      && Repo.getStr(Keys.ASR_MODEL).length > 0;
  }

  /** 首次启动写入默认服务商（硅基流动），用户只需补一个 Key */
  private static async seedDefaultProviders(): Promise<void> {
    if (Repo.getBool(Keys.DEFAULTS_SEEDED, false)) {
      return;
    }
    if (Repo.getStr(Keys.LLM_BASE).length === 0) {
      await Repo.setStr(Keys.LLM_BASE, 'https://api.siliconflow.cn/v1');
      await Repo.setStr(Keys.LLM_MODEL, 'Qwen/Qwen2.5-7B-Instruct');
      await Repo.setStr(Keys.LLM_PROVIDER, 'siliconflow');
    }
    if (Repo.getStr(Keys.ASR_URL).length === 0) {
      await Repo.setStr(Keys.ASR_URL, 'https://api.siliconflow.cn/v1');
      await Repo.setStr(Keys.ASR_MODEL, 'FunAudioLLM/SenseVoiceSmall');
      await Repo.setStr(Keys.ASR_PROVIDER, 'siliconflow-asr');
    }
    await Repo.setBool(Keys.AUTO_RECORD, true);
    await Repo.setBool(Keys.WB_AUTO, true);
    await Repo.setStr(Keys.HEADSET_MODE, 'recording_only');
    await Repo.setNum(Keys.REMIND_HOUR, 9);
    await Repo.setBool(Keys.DEFAULTS_SEEDED, true);
  }

  /** 清除全部数据（设置页「清除全部数据」） */
  static async wipeAll(): Promise<void> {
    await Store.clearAll();
    const keys: string[] = [
      Keys.LLM_BASE, Keys.LLM_KEY, Keys.LLM_MODEL, Keys.ASR_URL, Keys.ASR_KEY,
      Keys.ASR_MODEL, Keys.WB_WEBHOOK, Keys.WB_TOKEN, Keys.FS_HOOK, Keys.FS_SECRET,
      Keys.DT_HOOK, Keys.DT_SECRET, Keys.PRIVACY_ACK, Keys.DEFAULTS_SEEDED
    ];
    for (let i = 0; i < keys.length; i++) {
      if (Repo.isSecret(keys[i])) {
        await SecureStore.remove(keys[i]);
      } else {
        const p = Repo.pref;
        if (p !== null) {
          await p.delete(keys[i]);
        }
      }
    }
    const p = Repo.pref;
    if (p !== null) {
      await p.flush();
    }
  }
}
