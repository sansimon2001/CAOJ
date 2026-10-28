import { AsrProvider } from './Providers';

/**
 * 识别语言 / 中国方言清单与「服务端能力映射」
 *
 * 为什么需要这一层：
 * 各家转写服务的 language 参数口径并不一致——
 * OpenAI Whisper 只认 ISO-639-1（zh / en / ja / ko），传 yue 这类三位代码有的版本直接 400；
 * 硅基流动 SenseVoice 反而原生支持 yue（粤语）；
 * wuu（吴语）、nan（闽南语）、hak（客家话）目前没有任何主流服务在 Whisper 兼容协议里
 * 直接支持，只能按 zh 送，靠模型自身能力，识别质量下降是必然的。
 *
 * 所以 App 里不能只存一个字符串，必须存「用户想识别什么」，再由这里按所选服务
 * 换算成「实际能发什么」，并把降级事实如实告诉用户——否则用户选了上海话、
 * 实际一直是按普通话在认，却以为是自己口齿不清。
 */
export class SpeechLang {
  id: string = '';
  label: string = '';
  /** 传给服务端的 language 字面值（auto 表示自动） */
  requestCode: string = '';
  /** 是否为中国方言（决定要不要做「方言 → 普通话」归一化） */
  isDialect: boolean = false;
  /** 是否保留繁体输出（不套用繁转简） */
  keepTraditional: boolean = false;
  /** 是否非中文语种（决定摘要提示词要不要写「请用中文输出」） */
  foreign: boolean = false;
  /** 设置页说明 */
  hint: string = '';
  /** 本地检测用的特征词（方言识别前先猜一下用户说的是哪一种） */
  detectWords: string[] = [];
}

/** 一次转写请求实际要发什么，以及降级实情 */
export class LangPlan {
  /** null 表示不发这个字段（让服务端自己判断） */
  requestValue: string | null = null;
  downgraded: boolean = false;
  /** 给用户看的一句实话；无降级为空 */
  note: string = '';
}

/** 事后从文本反推「实际说的是什么方言」 */
export class LangDetect {
  langId: string = '';
  label: string = '';
  hits: number = 0;
  sample: string[] = [];
  reason: string = '';
}

export class SpeechLangs {
  static readonly ID_AUTO: string = 'auto';
  static readonly ID_ZH: string = 'zh';
  static readonly ID_YUE: string = 'yue';

  private static readonly LIST: SpeechLang[] = [
    SpeechLangs.mk('auto', '自动识别（多语种场景）', 'auto', false, false, false,
      '只在中英混说、或经常切换语种时才选它。中文对话请选「普通话（简体）」——自动判断在短句、带口音、有背景人声时容易猜错，猜错会让整段文字跟着走样。', []),
    SpeechLangs.mk('zh', '普通话（简体）', 'zh', false, false, false,
      '最稳的选择：强制按普通话识别，输出简体中文。', []),
    SpeechLangs.mk('zh_hant', '普通话（保留繁体）', 'zh', false, true, false,
      '港台用语、繁体字习惯场景用。App 不会把繁体转成简体。', []),
    SpeechLangs.mk('yue', '粤语（广东话）', 'yue', true, false, false,
      'SenseVoice 与 Whisper 都能出粤语文本，常带「唔 / 嘅 / 咗 / 佢」等粤语用字；不认识粤语的读者可打开「方言自动转普通话」。',
      ['唔', '嘅', '咗', '佢', '係', '喺', '哋', '咁', '乜', '嚟', '冇', '睇', '嗰', '噉', '俾']),
    SpeechLangs.mk('wuu', '吴语（上海话 / 苏州话）', 'wuu', true, false, false,
      '目前没有主流云服务在 Whisper 协议里直接支持吴语，会按普通话识别，口音重时错字偏多，建议保留原音并人工校对。',
      ['侬', '阿拉', '勿要', '白相', '哪能', '搿', '迭个', '伊拉', '邪气']),
    SpeechLangs.mk('nan', '闽南语（福建 / 台湾）', 'nan', true, false, false,
      '同吴语：服务端无原生支持，按普通话识别为主；闽南语用字（阮 / 厝 / 毋）可能被改写成普通话词。',
      ['阮', '汝', '厝', '夭寿', '食饱', '拢总', '毋', '偌', '嘛是', '阿公']),
    SpeechLangs.mk('hak', '客家话', 'hak', true, false, false,
      '服务端无原生支持，按普通话识别；建议配合热词表提升专名准确率。',
      ['𠊎', '恁', '暗晡', '头家', '毋', '係', '食朝']),
    SpeechLangs.mk('hsn', '湘语（湖南话）', 'hsn', true, false, false,
      '服务端无原生支持，按普通话识别；语气词（咯 / 哒）可能被吃掉，不影响大意。',
      ['咯', '哒', '莫', '何解', '霸蛮', '呷饭', '郎家']),
    SpeechLangs.mk('cjy', '晋语（山西话）', 'cjy', true, false, false,
      '服务端无原生支持，按普通话识别。',
      ['甚', '圪', '俺', '咋', '甚会儿']),
    SpeechLangs.mk('sichuan', '四川话（西南官话）', 'zh', true, false, false,
      '属西南官话，与普通话字面接近，按 zh 识别成功率已经不错；有口音时可加热词表。',
      ['啥子', '巴适', '晓得', '咋个', '安逸', '龙门阵', '啷个', '要得', '莫得', '瓜娃子']),
    SpeechLangs.mk('dongbei', '东北话', 'zh', true, false, false,
      '接近普通话，按 zh 识别即可；口语词（咋整 / 贼拉 / 唠嗑）一般能正常出字。',
      ['咋整', '贼拉', '唠嗑', '埋汰', '得瑟', '忽悠', '整点儿', '老鼻子']),
    SpeechLangs.mk('mixed', '中英混合（夹英文）', 'auto', false, false, false,
      '一段话里中英混说时用。Whisper 系效果最好；纯中文引擎会把英文按发音硬转成汉字。', []),
    SpeechLangs.mk('en', 'English', 'en', false, false, true,
      '原文为英文，摘要仍用简体中文输出，专有名词保留英文。', []),
    SpeechLangs.mk('ja', '日本語', 'ja', false, false, true,
      '原文为日文，摘要用简体中文输出，人名/公司名保留原文。', []),
    SpeechLangs.mk('ko', '한국어', 'ko', false, false, true,
      '原文为韩文，摘要用简体中文输出，专有名词保留原文。', [])
  ];

  private static mk(id: string, label: string, requestCode: string, isDialect: boolean,
    keepTraditional: boolean, foreign: boolean, hint: string, detectWords: string[]): SpeechLang {
    const s = new SpeechLang();
    s.id = id;
    s.label = label;
    s.requestCode = requestCode;
    s.isDialect = isDialect;
    s.keepTraditional = keepTraditional;
    s.foreign = foreign;
    s.hint = hint;
    s.detectWords = detectWords;
    return s;
  }

  static all(): SpeechLang[] {
    return SpeechLangs.LIST;
  }

  static byId(id: string): SpeechLang {
    for (let i = 0; i < SpeechLangs.LIST.length; i++) {
      if (SpeechLangs.LIST[i].id === id) {
        return SpeechLangs.LIST[i];
      }
    }
    // 找不到时回落到「普通话」，与默认设置一致，避免选中项跑偏
    return SpeechLangs.LIST[1];
  }

  static indexOf(id: string): number {
    const target = SpeechLangs.byId(id);
    for (let i = 0; i < SpeechLangs.LIST.length; i++) {
      if (SpeechLangs.LIST[i].id === target.id) {
        return i;
      }
    }
    return 1;
  }

  // ==================== 服务端能力换算 ====================

  /**
   * 把「用户选的语言」换算成「这个服务能收的 language」
   *
   * requestValue 为 null 表示发不出这个字段（让服务端自己判断），
   * downgraded 为 true 时必须把 note 如实显示给用户。
   */
  static plan(lang: SpeechLang, provider: AsrProvider): LangPlan {
    // 1) 自动识别：支持 auto 字面量的服务才发；不支持的不发字段（等价于自动）
    if (lang.id === SpeechLangs.ID_AUTO || lang.id === 'mixed') {
      const p = new LangPlan();
      p.requestValue = provider.acceptsAuto ? 'auto' : null;
      p.downgraded = false;
      p.note = '';
      return p;
    }

    // 2) 服务端明确支持这个代码 → 直接用
    for (let i = 0; i < provider.requestLangs.length; i++) {
      if (provider.requestLangs[i] === lang.requestCode) {
        const p = new LangPlan();
        p.requestValue = lang.requestCode;
        p.downgraded = false;
        p.note = '';
        return p;
      }
    }

    // 3) 不支持：方言按普通话送（保证出中文），外语按 zh 送毫无意义 → 交给自动检测
    const p = new LangPlan();
    p.downgraded = true;
    if (lang.foreign) {
      p.requestValue = null;
      p.note = `${provider.name} 未声明支持「${lang.label}」，改为自动判断语种`;
    } else {
      const extra = provider.dialectNote.length === 0 ? '' : provider.dialectNote + '；';
      p.requestValue = 'zh';
      p.note = `${extra}${provider.name} 不支持「${lang.label}」，本次按普通话识别：方言词可能被改写成普通话表达，人名与专名建议核对。`;
    }
    return p;
  }

  // ==================== 本地语种/方言检测 ====================

  /**
   * 从转写文本反推「实际说的是什么方言」
   *
   * 用途不是替代服务端识别，而是事后纠错：用户选了普通话，结果文本里满是
   * 「唔、嘅、咗」，说明选错了；这时在详情页提示「疑似粤语，可切到粤语重转」，
   * 比让用户对着一堆错字猜原因有用。
   *
   * 阈值：特征词命中 ≥3 次，且每千字命中 ≥10 次（避免个别词偶然出现就误判）。
   */
  static detect(text: string): LangDetect | null {
    if (text.length < 20) {
      return null;
    }
    let cjk = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (c >= 0x4E00 && c <= 0x9FFF) {
        cjk++;
      }
    }

    if (cjk < 10) {
      // 纯外文：按拉丁/假名/谚文占比给个语种建议
      let latin = 0;
      let kana = 0;
      let hangul = 0;
      let total = 0;
      for (let i = 0; i < text.length; i++) {
        const c = text.charCodeAt(i);
        const ch = text.charAt(i);
        if (ch === ' ' || ch === '\n' || ch === '\t' || ch === '\r') {
          continue;
        }
        total++;
        if (c >= 0x3040 && c <= 0x30FF) {
          kana++;
        } else if (c >= 0xAC00 && c <= 0xD7AF) {
          hangul++;
        } else if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c < 0x0300) {
          latin++;
        }
      }
      if (total < 1) {
        total = 1;
      }
      if (kana * 100 / total > 15) {
        return SpeechLangs.mkDetect('ja', '日本語', kana, [], `假名占比 ${Math.floor(kana * 100 / total)}%`);
      }
      if (hangul * 100 / total > 15) {
        return SpeechLangs.mkDetect('ko', '한국어', hangul, [], `谚文占比 ${Math.floor(hangul * 100 / total)}%`);
      }
      if (latin * 100 / total > 70) {
        return SpeechLangs.mkDetect('en', 'English', latin, [], `拉丁字母占比 ${Math.floor(latin * 100 / total)}%`);
      }
      return null;
    }

    let best: LangDetect | null = null;
    for (let i = 0; i < SpeechLangs.LIST.length; i++) {
      const lang = SpeechLangs.LIST[i];
      if (lang.detectWords.length === 0) {
        continue;
      }
      let hits = 0;
      const sample: string[] = [];
      for (let j = 0; j < lang.detectWords.length; j++) {
        const w = lang.detectWords[j];
        const n = SpeechLangs.countOf(text, w);
        if (n > 0) {
          hits += n;
          if (sample.length < 5) {
            sample.push(w);
          }
        }
      }
      const perK = hits * 1000 / cjk;
      if (hits >= 3 && perK >= 10) {
        if (best === null || hits > best.hits) {
          best = SpeechLangs.mkDetect(lang.id, lang.label, hits, sample,
            `特征词 ${sample.join('、')} 共 ${hits} 处`);
        }
      }
    }
    if (best !== null) {
      return best;
    }

    // 中英混说：拉丁词占比 15%~60%，且中文也占一定比例
    const latinWords = SpeechLangs.countLatinWords(text);
    const total = cjk + latinWords * 2;
    if (latinWords >= 3 && total > 0) {
      const pct = Math.floor(latinWords * 200 / total);
      if (pct >= 15 && pct <= 60) {
        return SpeechLangs.mkDetect('mixed', '中英混合', latinWords, [], `英文词 ${latinWords} 个，约占 ${pct}%`);
      }
    }
    return null;
  }

  private static mkDetect(id: string, label: string, hits: number, sample: string[], reason: string): LangDetect {
    const d = new LangDetect();
    d.langId = id;
    d.label = label;
    d.hits = hits;
    d.sample = sample;
    d.reason = reason;
    return d;
  }

  private static countLatinWords(text: string): number {
    let n = 0;
    let inWord = false;
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      const isAlpha = (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c === 0x27 || c === 0x2D;
      if (isAlpha) {
        if (!inWord) {
          n++;
          inWord = true;
        }
      } else {
        inWord = false;
      }
    }
    return n;
  }

  private static countOf(text: string, word: string): number {
    if (word.length === 0) {
      return 0;
    }
    let i = text.indexOf(word);
    let n = 0;
    while (i >= 0) {
      n++;
      i = text.indexOf(word, i + word.length);
    }
    return n;
  }
}
