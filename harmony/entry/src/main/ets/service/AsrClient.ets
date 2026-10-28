import { http } from '@kit.NetworkKit';
import { fileIo } from '@kit.CoreFileKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { Repo, Keys } from '../data/Repo';
import { Providers, AsrProvider } from './Providers';
import { SpeechLang, SpeechLangs, LangPlan } from './SpeechLang';
import { ZhNormalizer } from '../util/ZhNormalizer';

interface AsrSegment {
  text?: string;
  speaker?: string;
  start?: number;
}

interface AsrResponse {
  text?: string;
  segments?: AsrSegment[];
  error?: AsrError;
}

interface AsrError {
  message?: string;
}

/** 一次请求的可变参数组合（降级重试时逐项去掉） */
class ExecOpts {
  diarize: boolean = false;
  /** null 表示不发 language 字段，让服务端自己判断 */
  language: string | null = null;
  prompt: string = '';

  constructor(d: boolean, l: string | null, p: string) {
    this.diarize = d;
    this.language = l;
    this.prompt = p;
  }
}

/** 一次请求的结果 */
class ExecResult {
  code: number = 0;
  raw: string = '';

  constructor(c: number, r: string) {
    this.code = c;
    this.raw = r;
  }
}

/**
 * 转写结果
 *
 * 除了正文，还要把「本次到底按什么语言在认」一并带回去：
 * 用户选了上海话、服务端只认普通话时，界面上必须写明降级，
 * 否则用户会以为是自己口齿不清。
 */
export class AsrOutcome {
  ok: boolean = false;
  text: string = '';
  message: string = '';
  /** 本次请求的语言 id（见 SpeechLang） */
  langId: string = 'zh';
  /** 服务端不支持所选方言、已按普通话降级 */
  downgraded: boolean = false;
  /** 降级原因（无降级为空），要如实显示给用户 */
  note: string = '';
  /** 文本疑似繁体（已转简体时仍为 true，用于提示） */
  traditional: boolean = false;

  static fail(msg: string): AsrOutcome {
    const o = new AsrOutcome();
    o.ok = false;
    o.message = msg;
    return o;
  }
}

/**
 * 语音转写客户端（OpenAI /audio/transcriptions 协议，硅基流动同构）
 *
 * 鸿蒙的 http 不能像 OkHttp 那样直接给一个 multipart 构造器，
 * 所以这里手拼 body：前缀文本 + 音频字节 + 结尾边界，一次请求发出。
 *
 * 三个不能省的处理：
 * 1) language 不是直接把用户选的字符串发出去，要经 SpeechLangs.plan 按服务商能力换算
 * 2) 服务端明确拒绝（400/422）时逐项降级重试：去说话人 → 去 language → 去热词；
 *    网络错误不重试，否则一个超时会变成四次超时
 * 3) 结果做不可见字符清理与繁简转换，粤语稿默认吐繁体，不转就会出现「一場會議」
 */
export class AsrClient {
  private static readonly BOUNDARY: string = '----CaojiFormBoundary7MA4YWxk';

  static isConfigured(): boolean {
    return Repo.hasAsr();
  }

  /** 当前配置的转写服务是否可能返回说话人分段 */
  static diarizePossible(): boolean {
    const saved = Repo.getStr(Keys.ASR_PROVIDER);
    const id = saved.length > 0 ? saved : Providers.guessAsrId(Repo.getStr(Keys.ASR_URL));
    const p = Providers.asrById(id);
    return p.supportsDiarize || p.id === 'custom-asr';
  }

  /**
   * 转写音频文件
   *
   * @param langOverride 指定本次使用的语言 id；为空时读设置。
   *        详情页「疑似粤语，按粤语重转」就走这个参数，不用改全局设置。
   */
  static async transcribe(audioPath: string, langOverride?: string): Promise<AsrOutcome> {
    if (!AsrClient.isConfigured()) {
      return AsrOutcome.fail('未配置转写服务');
    }
    const url = Repo.getStr(Keys.ASR_URL);
    if (!url.startsWith('https://')) {
      return AsrOutcome.fail('转写接口必须是 https 地址');
    }
    const model = Repo.getStr(Keys.ASR_MODEL);
    if (!fileIo.accessSync(audioPath)) {
      return AsrOutcome.fail('音频文件不存在（可能已被清理）');
    }

    const provider = AsrClient.currentProvider(url);
    const langId = (langOverride !== undefined && langOverride.length > 0)
      ? langOverride
      : AsrClient.savedLangId();
    const lang: SpeechLang = SpeechLangs.byId(langId);
    const plan: LangPlan = SpeechLangs.plan(lang, provider);
    // 热词只在支持 prompt 的服务上发（SenseVoice 没有这个参数，发了会 400）
    const hotwords = Repo.getStr(Keys.ASR_PROMPT).trim();
    const prompt = provider.supportsPrompt && hotwords.length > 0
      ? AsrClient.buildHotwordPrompt(hotwords) : '';

    const file = fileIo.openSync(audioPath, fileIo.OpenMode.READ_ONLY);
    let audioBytes: ArrayBuffer = new ArrayBuffer(0);
    try {
      const stat = fileIo.statSync(audioPath);
      if (stat.size > 40 * 1024 * 1024) {
        return AsrOutcome.fail('音频超过 40MB，请先切分成较短片段再转写');
      }
      audioBytes = new ArrayBuffer(stat.size);
      fileIo.readSync(file.fd, audioBytes);
    } finally {
      fileIo.closeSync(file);
    }

    const wantsDiarize = Repo.getBool(Keys.ASR_DIARIZE, false) && AsrClient.diarizePossible();
    const fileName = audioPath.substring(audioPath.lastIndexOf('/') + 1);

    // 依次尝试：完整参数 → 去掉说话人 → 去掉 language → 去掉热词
    const attempts: ExecOpts[] = [];
    attempts.push(new ExecOpts(wantsDiarize, plan.requestValue, prompt));
    if (wantsDiarize) {
      attempts.push(new ExecOpts(false, plan.requestValue, prompt));
    }
    if (plan.requestValue !== null) {
      attempts.push(new ExecOpts(false, null, prompt));
    }
    if (prompt.length > 0) {
      attempts.push(new ExecOpts(false, plan.requestValue, ''));
    }

    let used: ExecOpts = attempts[0];
    let last: ExecResult = new ExecResult(0, '');
    for (let i = 0; i < attempts.length; i++) {
      const r = await AsrClient.execute(url, model, fileName, audioBytes, attempts[i]);
      last = r;
      used = attempts[i];
      if ((r.code >= 200 && r.code <= 299) || (r.code !== 400 && r.code !== 422)) {
        break;
      }
    }

    if (last.code < 200 || last.code > 299) {
      return AsrOutcome.fail(AsrClient.translate(last.code, last.raw));
    }

    let parsed: AsrResponse;
    try {
      parsed = JSON.parse(last.raw) as AsrResponse;
    } catch (e) {
      return AsrOutcome.fail('转写服务返回的不是 JSON');
    }

    let text = '';
    if (used.diarize) {
      const segments = parsed.segments;
      if (segments !== undefined && segments.length > 0) {
        text = AsrClient.joinSegments(segments);
      }
    }
    if (text.trim().length === 0) {
      text = parsed.text === undefined ? '' : parsed.text.trim();
    }

    // 文字后处理：不可见字符 + 繁简
    text = ZhNormalizer.stripInvisible(text);
    let traditional = false;
    if (!lang.keepTraditional && !lang.foreign) {
      traditional = ZhNormalizer.hasTraditional(text);
      if (traditional) {
        text = ZhNormalizer.toSimplified(text);
      }
    }

    if (text.length === 0) {
      return AsrOutcome.fail('转写结果为空（可能整段是静音或噪声）');
    }

    const out = new AsrOutcome();
    out.ok = true;
    out.text = text;
    out.message = '';
    out.langId = lang.id;
    out.downgraded = plan.downgraded;
    out.note = plan.note;
    out.traditional = traditional;
    return out;
  }

  /** 用户选的语言 id（老版本没存过时按普通话，与 v1.24 的默认一致） */
  private static savedLangId(): string {
    const v = Repo.getStr(Keys.ASR_LANG);
    return v.length > 0 ? v : SpeechLangs.ID_ZH;
  }

  private static currentProvider(url: string): AsrProvider {
    const saved = Repo.getStr(Keys.ASR_PROVIDER);
    const id = saved.length > 0 ? saved : Providers.guessAsrId(url);
    return Providers.asrById(id);
  }

  private static async execute(url: string, model: string, fileName: string,
    audioBytes: ArrayBuffer, opts: ExecOpts): Promise<ExecResult> {
    let head = `--${AsrClient.BOUNDARY}\r\n`;
    head += `Content-Disposition: form-data; name="model"\r\n\r\n${model}\r\n`;
    // language 为空表示不传这个字段，由服务端自动判断（Whisper 的默认行为）
    if (opts.language !== null) {
      head += `--${AsrClient.BOUNDARY}\r\n`;
      head += `Content-Disposition: form-data; name="language"\r\n\r\n${opts.language}\r\n`;
    }
    if (opts.prompt.length > 0) {
      head += `--${AsrClient.BOUNDARY}\r\n`;
      head += `Content-Disposition: form-data; name="prompt"\r\n\r\n${opts.prompt}\r\n`;
    }
    if (opts.diarize) {
      head += `--${AsrClient.BOUNDARY}\r\n`;
      head += 'Content-Disposition: form-data; name="response_format"\r\n\r\nverbose_json\r\n';
      head += `--${AsrClient.BOUNDARY}\r\n`;
      head += 'Content-Disposition: form-data; name="diarize"\r\n\r\ntrue\r\n';
    }
    head += `--${AsrClient.BOUNDARY}\r\n`;
    head += `Content-Disposition: form-data; name="file"; filename="${fileName}"\r\n`;
    head += 'Content-Type: audio/m4a\r\n\r\n';
    const tail = `\r\n--${AsrClient.BOUNDARY}--\r\n`;

    const encoder = new util.TextEncoder();
    const body = AsrClient.concat([
      new Uint8Array(encoder.encodeInto(head)),
      new Uint8Array(audioBytes),
      new Uint8Array(encoder.encodeInto(tail))
    ]);

    const client = http.createHttp();
    try {
      const header: Record<string, string> = {
        'Content-Type': `multipart/form-data; boundary=${AsrClient.BOUNDARY}`,
        'Authorization': `Bearer ${Repo.getStr(Keys.ASR_KEY)}`
      };
      const res = await client.request(url, {
        method: http.RequestMethod.POST,
        header: header,
        extraData: body.buffer as ArrayBuffer,
        connectTimeout: 20000,
        readTimeout: 300000
      });
      const raw = typeof res.result === 'string' ? (res.result as string) : JSON.stringify(res.result);
      return new ExecResult(res.responseCode, raw);
    } catch (e) {
      const err = e as BusinessError;
      // 网络异常不当成 400/422，上层不会重试；这里给出可读原因
      return new ExecResult(-1, `转写请求失败：${err.message}（${err.code}）`);
    } finally {
      client.destroy();
    }
  }

  /**
   * 把用户填的热词表整理成 prompt
   *
   * 这是方言/专名识别最划算的一招：Whisper 系会把这些词当成上文，
   * 遇到发音相近的片段时优先按这些写法输出。人名、公司名、行业术语
   * 这类「普通词典里没有」的词，加与不加差别很明显。
   */
  private static buildHotwordPrompt(hotwords: string): string {
    const parts = hotwords.split(new RegExp('[，,、;；\\n\\r\\t]+'));
    const words: string[] = [];
    for (let i = 0; i < parts.length; i++) {
      const w = parts[i].trim();
      if (w.length === 0) {
        continue;
      }
      let dup = false;
      for (let j = 0; j < words.length; j++) {
        if (words[j] === w) {
          dup = true;
          break;
        }
      }
      if (!dup) {
        words.push(w);
      }
      if (words.length >= 60) {
        break;
      }
    }
    if (words.length === 0) {
      return '';
    }
    return '以下是对话中可能出现的人名、公司名与专业术语，请优先按这些写法输出：'
      + words.join('、') + '。';
  }

  /** 有声纹段时按「说话人：内容」拼起来，和导入的 srt 文稿口径保持一致 */
  private static joinSegments(segs: AsrSegment[]): string {
    const lines: string[] = [];
    let current = '';
    let buffer = '';
    for (let i = 0; i < segs.length; i++) {
      const s = segs[i];
      const sp = s.speaker === undefined ? '' : s.speaker.trim();
      const tx = s.text === undefined ? '' : s.text.trim();
      if (tx.length === 0) {
        continue;
      }
      if (sp.length > 0 && sp !== current) {
        if (buffer.length > 0) {
          lines.push(current.length > 0 ? `${current}：${buffer}` : buffer);
        }
        current = sp;
        buffer = tx;
      } else {
        buffer = buffer.length > 0 ? `${buffer} ${tx}` : tx;
      }
    }
    if (buffer.length > 0) {
      lines.push(current.length > 0 ? `${current}：${buffer}` : buffer);
    }
    return lines.join('\n');
  }

  private static translate(code: number, raw: string): string {
    if (code === -1) {
      // 网络层异常，raw 里已经是可读原因
      return raw;
    }
    if (code === 401) {
      return '转写服务的 API Key 无效（401）';
    }
    if (code === 413) {
      return '音频文件太大（413），请切分后重试';
    }
    if (code === 429) {
      return '转写请求超出配额（429），稍后再试';
    }
    let detail = raw.substring(0, 120);
    try {
      const body = JSON.parse(raw) as AsrResponse;
      const eb = body.error;
      if (eb !== undefined && eb.message !== undefined) {
        detail = eb.message;
      }
    } catch (e) {
      // 保留原始片段即可
    }
    return `转写失败（${code}）${detail}`;
  }

  private static concat(parts: Uint8Array[]): Uint8Array {
    let total = 0;
    for (let i = 0; i < parts.length; i++) {
      total += parts[i].length;
    }
    const out = new Uint8Array(total);
    let offset = 0;
    for (let i = 0; i < parts.length; i++) {
      out.set(parts[i], offset);
      offset += parts[i].length;
    }
    return out;
  }
}
