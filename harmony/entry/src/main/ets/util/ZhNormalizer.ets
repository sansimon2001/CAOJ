import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';

/**
 * 繁简转换与文本清理
 *
 * 为什么必须有这一步：
 * 粤语与港台口音的识别结果，云端默认就吐繁体——Whisper 出「今日嘅會議」、
 * SenseVoice 出「會議記錄」都很正常。中文用户读简体，摘要与待办里再混着繁体，
 * 既割裂又容易在搜索时漏掉（同一个词两种字形）。
 *
 * 表来自 OpenCC 的 TSCharacters（Apache-2.0），只收单字一对一的可靠映射，
 * 共 3221 组，随包放在 rawfile/t2s.txt。
 * 一对多的歧义字（著 / 台 / 面 / 里 / 云 / 松 / 乾）故意不收，避免把
 * 「著名」转成「着名」、「乾隆」转成「干隆」这类经典翻车。
 * 粤语用字（唔 / 嘅 / 咗 / 佢 / 哋）本身没有简化形，转换后仍在，语义不受影响。
 */
export class ZhNormalizer {
  /** 简体化时不参与的歧义字：宁可不转，也不要转错 */
  private static readonly AMBIGUOUS: string = '乾';

  private static ctx: common.UIAbilityContext | null = null;
  private static map: Map<string, string> | null = null;
  private static empty: boolean = false;

  /**
   * 启动时注入一次上下文
   *
   * 繁简表在 rawfile 里，读它需要 resourceManager。让它自己记住上下文，
   * 比在每个调用点（AsrClient / Pipeline / 详情页）都传一遍 ctx 干净；
   * 万一忘了注入，下面会如实退化为「不转换」，而不是静默出错。
   */
  static init(ctx: common.UIAbilityContext): void {
    ZhNormalizer.ctx = ctx;
  }

  private static ensure(): Map<string, string> {
    if (ZhNormalizer.map !== null) {
      return ZhNormalizer.map;
    }
    const m = new Map<string, string>();
    const ctx = ZhNormalizer.ctx;
    if (ctx === null) {
      ZhNormalizer.empty = true;
      ZhNormalizer.map = m;
      return m;
    }
    try {
      const bytes = ctx.resourceManager.getRawFileContentSync('t2s.txt');
      // 先去掉所有空白再按两字一组切，避免行宽变化导致错位
      const decoder = util.TextDecoder.create('utf-8');
      const raw = decoder.decodeToString(bytes);
      let buf = '';
      for (let i = 0; i < raw.length; i++) {
        const ch = raw.charAt(i);
        if (ch === '\n' || ch === '\r' || ch === ' ' || ch === '\t') {
          continue;
        }
        buf += ch;
      }
      // 每两字一组：繁体在前、简体在后
      let i = 0;
      while (i + 1 < buf.length) {
        const t = buf.charAt(i);
        const s = buf.charAt(i + 1);
        i += 2;
        if (t === s || ZhNormalizer.AMBIGUOUS.indexOf(t) >= 0) {
          continue;
        }
        m.set(t, s);
      }
    } catch (e) {
      // 表没打进包时不要静默失效：繁转简会变成空操作，
      // 表现为「粤语稿还是繁体」，很难从界面上看出原因
      ZhNormalizer.empty = true;
    }
    ZhNormalizer.map = m;
    return m;
  }

  /**
   * 繁体 → 简体
   *
   * 对已经是简体的文本安全（表里只有繁体字做 key，简体字原样返回），
   * 因此转写结果是混排时也可以无脑调用。
   */
  static toSimplified(text: string): string {
    if (text.length === 0) {
      return text;
    }
    const m = ZhNormalizer.ensure();
    if (m.size === 0) {
      return text;
    }
    let out = '';
    for (let i = 0; i < text.length; i++) {
      const ch = text.charAt(i);
      const hit = m.get(ch);
      out += hit === undefined ? ch : hit;
    }
    return out;
  }

  /** 快速判断是否含繁体字（避免对纯简体文本做无谓的整串替换） */
  static hasTraditional(text: string): boolean {
    if (text.length === 0) {
      return false;
    }
    const m = ZhNormalizer.ensure();
    if (m.size === 0) {
      return false;
    }
    for (let i = 0; i < text.length; i++) {
      if (m.has(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  /** 表是否真的加载成功（设置页用来如实提示，而不是假装能转） */
  static available(): boolean {
    ZhNormalizer.ensure();
    return !ZhNormalizer.empty;
  }

  /**
   * 去掉零宽字符与 BOM（部分服务端会在流式结果里带）
   *
   * 这些字符在界面上看不见，但会让「一键复制」「搜索」失效。
   * 一次遍历完成；一个都没有时直接返回原串，不做无谓分配。
   */
  static stripInvisible(text: string): string {
    if (text.length === 0) {
      return text;
    }
    let first = -1;
    for (let i = 0; i < text.length; i++) {
      if (ZhNormalizer.isInvisible(text.charAt(i))) {
        first = i;
        break;
      }
    }
    if (first < 0) {
      return text;
    }
    let out = text.substring(0, first);
    for (let j = first; j < text.length; j++) {
      const c = text.charAt(j);
      if (!ZhNormalizer.isInvisible(c)) {
        out += c;
      }
    }
    return out;
  }

  private static isInvisible(c: string): boolean {
    return c === '\u200B' || c === '\u200C' || c === '\u200D' || c === '\uFEFF' || c === '\u2060';
  }
}
