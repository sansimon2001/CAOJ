import { http } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Repo, Keys } from '../data/Repo';
import { AnalyzeResult, ChatMessage, Env, Quality, TodoDraft } from '../model/Models';
import { TimeUtil } from '../util/TimeUtil';
import { ProjectMind, ProjectProfile, ProjectMatch } from './ProjectMind';

/** OpenAI 兼容的 chat/completions 请求体（ArkTS 不允许无类型对象字面量） */
interface ChatPayload {
  model: string;
  temperature: number;
  max_tokens: number;
  messages: ChatMessage[];
}

interface TodoJson {
  text?: string;
  content?: string;
  due?: string;
}

interface AnalyzeJson {
  title?: string;
  summary?: string;
  todos?: TodoJson[];
  project?: string;
  insight?: string;
  /** 模型抄回的原文依据句（代码侧要回原文比对，防它编） */
  projectEvidence?: string;
  /** 模型自评的归属把握 0-1 */
  projectConfidence?: number;
}

interface ChoiceMessage {
  content?: string;
}

interface Choice {
  message?: ChoiceMessage;
}

interface ChatResponse {
  choices?: Choice[];
  error?: ErrorBody;
}

interface ErrorBody {
  message?: string;
}

export interface LlmOutcome {
  ok: boolean;
  result: AnalyzeResult;
  message: string;
}

/** 方言归一化的结果：失败时 text 为空，message 说明为什么跳过 */
export class NormResult {
  ok: boolean = false;
  text: string = '';
  message: string = '';

  static fail(msg: string): NormResult {
    const r = new NormResult();
    r.ok = false;
    r.message = msg;
    return r;
  }
}

function emptyResult(): AnalyzeResult {
  return {
    title: '未命名记录',
    summary: '',
    todos: [],
    project: '',
    insight: '',
    quality: Quality.OK,
    projectEvidence: '',
    projectConfidence: 0
  };
}

/**
 * 大模型客户端（OpenAI 兼容 /chat/completions）
 *
 * 三层治理与 Android 版一致：
 * 1) 长文本先分块抽要点（map-reduce），避免模型抓不住重点；
 * 2) prompt 强约束：只依据原文、不确定给空、按场景区分重点；
 * 3) 出口校验：摘要与原文短语回扣不足 → 标记 review，交给用户确认，不静默当真的展示。
 */
export class LlmClient {
  private static readonly MAX_INPUT: number = 12000;

  static isConfigured(): boolean {
    return Repo.hasLlm();
  }

  private static endpoint(): string {
    return Repo.getStr(Keys.LLM_BASE).replace(/\/+$/, '');
  }

  /** 明文 http 会把全文与 Key 暴露在链路上，直接拒发 */
  static guard(url: string): string {
    if (url.length === 0) {
      return '接口地址为空';
    }
    if (!url.startsWith('https://')) {
      return '接口必须是 https 地址（当前配置为明文 http，已拒绝发送以保护你的内容与密钥）';
    }
    return '';
  }

  private static systemPrompt(today: string, hasSpeakers: boolean): string {
    const speakerNote = hasSpeakers
      ? '（文本每行以「我：」或「对方：」标注说话人）'
      : '';
    const summaryHint = hasSpeakers
      ? '我承诺了什么、对方提出了什么要求、'
      : '谁承诺了什么、';
    return `你是「草记」的记录整理助手，工作方式类似个人认知教练。
今天是 ${today}。

用户会给你一段对话/通话的语音转写文本${speakerNote}，你需要输出严格 JSON（不要 markdown 代码块）：
{
  "title": "不超过 16 字的标题，体现这次沟通的核心事件",
  "summary": "3-6 句话的结构化摘要：讨论了什么、${summaryHint}结论是什么",
  "todos": [{"text": "待办内容", "due": "YYYY-MM-DD"}],
  "project": "归属项目名称（只能从候选项目里选，没有明确依据就给空字符串）",
  "projectEvidence": "原文里支持这个项目判断的原句（照抄，不要改写；没有依据给空字符串）",
  "projectConfidence": 0.0,
  "insight": "1-2 句行为洞察：我的沟通习惯、判断依据或可复盘的得失（客观、不评价人品）"
}

要求：
1. 只输出 JSON，不要任何多余文字；
2. todos 只提取明确的行动项（有动作、有对象），没有则给空数组；due 由文本中的时间线索换算成具体日期（如「下周五」「10 月底前」），今天为 ${today}，无法判断则给空字符串；
3. project 只能从下面给出的候选项目里挑一个，**原文没提到就留空**，绝对不要自己造一个新名字，也不要把「这件事大概属于哪个项目」当成依据；同时把原文里支持这个判断的那句话原样抄进 projectEvidence，projectConfidence 给 0-1 的把握程度；
4. 摘要与洞察优先站在「我」的视角，明确指出我的承诺与待跟进事项；
5. 不编造未出现的信息。`;
  }

  // ==================== 对外能力 ====================

  /**
   * 分析一段转写文本
   *
   * @param cands ProjectMind.candidates 预筛出的候选项目（带别名与线索词）。
   *        传空数组表示没有任何档案，此时提示词会让模型一律留空项目——
   *        宁可空着让用户手动指定，也不要再产生一个新碎片。
   */
  static async analyze(transcript: string, cands: ProjectProfile[], envType: string): Promise<LlmOutcome> {
    const out = emptyResult();
    if (!LlmClient.isConfigured()) {
      return { ok: false, result: out, message: '未配置大模型' };
    }
    const cleaned = LlmClient.clean(transcript);
    if (cleaned.length < 6) {
      return { ok: false, result: out, message: '转写内容过短或全是语气词' };
    }
    const base = LlmClient.endpoint();
    const guardMsg = LlmClient.guard(base);
    if (guardMsg.length > 0) {
      return { ok: false, result: out, message: guardMsg };
    }

    // 1) 长文本先抽要点
    const chunks = LlmClient.chunk(cleaned, 4000);
    let material = cleaned;
    if (chunks.length > 1) {
      const points: string[] = [];
      for (let i = 0; i < chunks.length; i++) {
        const p = await LlmClient.extractPoints(chunks[i], i + 1, chunks.length);
        if (p.length > 0) {
          points.push(p);
        }
      }
      if (points.length > 0) {
        material = points.join('\n');
      }
    }

    const hasSpeakers = cleaned.indexOf('我：') >= 0 || cleaned.indexOf('对方：') >= 0;
    const today = TimeUtil.today();
    const candidateHint = ProjectMind.hintBlock(cands);
    const user = LlmClient.buildUserContent(material, candidateHint, envType);
    const payload: ChatPayload = {
      model: Repo.getStr(Keys.LLM_MODEL),
      temperature: 0.2,
      max_tokens: 1200,
      messages: [
        { role: 'system', content: LlmClient.systemPrompt(today, hasSpeakers) } as ChatMessage,
        { role: 'user', content: user } as ChatMessage
      ]
    };
    const res = await LlmClient.postChat(base, JSON.stringify(payload), 90);
    if (!res.ok) {
      return { ok: false, result: out, message: res.message };
    }
    const json = LlmClient.extractJson(res.text);
    if (json.length === 0) {
      return { ok: false, result: out, message: '模型未返回可解析的 JSON' };
    }
    let obj: AnalyzeJson;
    try {
      obj = JSON.parse(json) as AnalyzeJson;
    } catch (e) {
      return { ok: false, result: out, message: '模型返回的 JSON 解析失败' };
    }
    out.title = LlmClient.pick(obj.title, '未命名记录');
    out.summary = LlmClient.pick(obj.summary, '');
    out.insight = LlmClient.pick(obj.insight, '');
    out.todos = LlmClient.parseTodos(obj.todos);
    out.quality = LlmClient.guardSummary(out.summary, cleaned);

    // ---------- 项目归属的出口校验 ----------
    // 模型给的名字必须能落到项目档案上，落不上宁可留空——这一步是
    // 「不再产生 XXX项目 / 项目二期 之类碎片」的闸门，也是防止张冠李戴的最后一道。
    const rawProject = LlmClient.pick(obj.project, '');
    const rawEvidence = LlmClient.pick(obj.projectEvidence, '');
    const rawConf = typeof obj.projectConfidence === 'number' ? obj.projectConfidence : 0;
    const pm: ProjectMatch = await ProjectMind.resolveWithEvidence(
      rawProject, rawEvidence, rawConf, material, cands);
    out.project = pm.name;
    out.projectEvidence = pm.evidence;
    out.projectConfidence = pm.confidence;
    // 有名字但没把握（没抄依据，或依据对不上、多个候选都像）→ 标待确认让用户复核
    if (pm.name.length > 0 && pm.confidence < 0.6) {
      out.quality = Quality.REVIEW;
    }

    let msg = out.quality === Quality.REVIEW ? '摘要与原文对应度偏低，已标记为待确认' : '';
    if (rawProject.length > 0 && pm.name.length === 0) {
      msg = pm.reason.length > 0 ? pm.reason : '项目归属证据不足，已留空待确认';
    }
    return { ok: true, result: out, message: msg };
  }

  /** 即时问答：拿最近的记录当上下文回答用户的提问 */
  static async ask(question: string, context: string): Promise<string> {
    if (!LlmClient.isConfigured()) {
      return '还没配置大模型：到「设置 → AI 服务」填一个 API Key 就能问答。';
    }
    const base = LlmClient.endpoint();
    const guardMsg = LlmClient.guard(base);
    if (guardMsg.length > 0) {
      return guardMsg;
    }
    const system = '你是用户的个人认知助手，回答问题要简洁、直接、可执行。'
      + '优先从给出的记录里找依据，记录里没有就说明没有，不要编造。';
    const user = `我的问题：${question}\n\n最近的记录：\n${context.substring(0, 9000)}`;
    const payload: ChatPayload = {
      model: Repo.getStr(Keys.LLM_MODEL),
      temperature: 0.3,
      max_tokens: 700,
      messages: [
        { role: 'system', content: system } as ChatMessage,
        { role: 'user', content: user } as ChatMessage
      ]
    };
    const res = await LlmClient.postChat(base, JSON.stringify(payload), 60);
    return res.ok ? res.text : `请求失败：${res.message}`;
  }

  /**
   * 方言口语 → 普通话书面语
   *
   * 只对方言稿做：普通话录音白跑一次调用没有意义。
   *
   * 出口必须校验长度畸变——模型一旦在「改写」时顺手做了总结或补了原文没有的内容，
   * 字数会明显偏离；宁可直接丢弃用原文，也不要让摘要建立在被改写过的稿子上。
   */
  static async normalizeDialect(transcript: string, langLabel: string): Promise<NormResult> {
    if (!LlmClient.isConfigured()) {
      return NormResult.fail('未配置大模型');
    }
    const src = transcript.trim();
    if (src.length < 12) {
      return NormResult.fail('文本过短');
    }
    // 太长的稿子归一化成本高、收益低（模型也容易在长文里丢行），直接跳过
    if (src.length > 12000) {
      return NormResult.fail('文本过长，跳过归一化');
    }
    const base = LlmClient.endpoint();
    const guardMsg = LlmClient.guard(base);
    if (guardMsg.length > 0) {
      return NormResult.fail(guardMsg);
    }

    const system = `你负责把${langLabel}口语的语音转写稿改写成普通话书面语。

硬性要求：
1. 逐行保留原文的说话人标记（[我] / [对方] / [说话人N]）与行序，不要合并或拆分说话人；
2. 人名、公司名、地名、金额、日期、时间、数字原样保留，不翻译、不改写、不换算；
3. 方言词换成对应的普通话说法（例：「唔该晒」→「多谢」，「啥子」→「什么」，「侬」→「你」），
   但不能为了通顺而删掉口语里的具体信息（数量、时间、条件）；
4. 不改写事实、不补充原文没有的内容、不做总结、不加评论；
5. 只输出改写后的正文，不要任何前后缀、不要解释。`;

    const payload: ChatPayload = {
      model: Repo.getStr(Keys.LLM_MODEL),
      temperature: 0.4,
      max_tokens: 1800,
      messages: [
        { role: 'system', content: system } as ChatMessage,
        { role: 'user', content: `原文：\n${src}` } as ChatMessage
      ]
    };
    const res = await LlmClient.postChat(base, JSON.stringify(payload), 120);
    if (!res.ok) {
      return NormResult.fail(res.message);
    }
    const out = res.text.trim();
    if (out.length === 0) {
      return NormResult.fail('大模型返回为空');
    }
    // 出口校验：长度畸变说明模型在总结或编造，宁可放弃
    if (out.length < src.length * 0.35) {
      return NormResult.fail('归一化结果过短，已丢弃');
    }
    if (out.length > src.length * 2.2) {
      return NormResult.fail('归一化结果异常膨胀，已丢弃');
    }
    const r = new NormResult();
    r.ok = true;
    r.text = out;
    r.message = '';
    return r;
  }

  /** 项目洞见：把同一项目的多段记录合成一份「这个项目的现状」 */
  static async projectInsight(project: string, material: string): Promise<string> {
    if (!LlmClient.isConfigured()) {
      return '';
    }
    const base = LlmClient.endpoint();
    if (LlmClient.guard(base).length > 0) {
      return '';
    }
    const system = '你是项目管理助手。用户会给你同一项目在一段时间内的多段沟通记录，'
      + '请输出一份不超过 400 字的项目洞见：当前进展、悬而未决的事项、主要风险、建议下一步。'
      + '只依据给出的记录，不要编造。用「一、二、三」分段，不要 markdown 标题。';
    const payload: ChatPayload = {
      model: Repo.getStr(Keys.LLM_MODEL),
      temperature: 0.3,
      max_tokens: 900,
      messages: [
        { role: 'system', content: system } as ChatMessage,
        { role: 'user', content: `项目：${project}\n\n记录：\n${material.substring(0, 12000)}` } as ChatMessage
      ]
    };
    const res = await LlmClient.postChat(base, JSON.stringify(payload), 90);
    return res.ok ? res.text : '';
  }

  /** 行为复盘：从一段时间的记录里找沟通模式 */
  static async retrospect(material: string): Promise<string> {
    if (!LlmClient.isConfigured()) {
      return '';
    }
    const base = LlmClient.endpoint();
    if (LlmClient.guard(base).length > 0) {
      return '';
    }
    const system = '你是个人成长教练，擅长从一个人的日常沟通记录里找行为模式。'
      + '请基于给出的记录，写一份不超过 500 字的复盘：'
      + '一、这段时间主要在忙什么；二、我的沟通习惯（含盲点）；三、承诺但我没推进的事；四、下周可执行的三件事。'
      + '客观、具体、不评价人品，不编造记录里没有的内容。';
    const payload: ChatPayload = {
      model: Repo.getStr(Keys.LLM_MODEL),
      temperature: 0.4,
      max_tokens: 1000,
      messages: [
        { role: 'system', content: system } as ChatMessage,
        { role: 'user', content: material.substring(0, 12000) } as ChatMessage
      ]
    };
    const res = await LlmClient.postChat(base, JSON.stringify(payload), 90);
    return res.ok ? res.text : '';
  }

  // ==================== 内部 ====================

  private static buildUserContent(material: string, candidateHint: string, envType: string): string {
    let text = '';
    if (candidateHint.length > 0) {
      text += candidateHint + '\n';
    }
    let envHint = '';
    if (envType === Env.MEETING) {
      envHint = '本次是会议/多人交谈录音，重点关注结论、承诺与待办；寒暄客套不要写进摘要。';
    } else if (envType === Env.QUIET) {
      envHint = '本次是安静环境下的独处语音（多为灵感或自述），主要提炼想法本身。';
    } else if (envType === Env.STREET) {
      envHint = '本次录制环境嘈杂，转写可能有个别错字或无关片段，只提取与工作相关的明确内容。';
    }
    if (envHint.length > 0) {
      text += `场景说明：${envHint}\n\n`;
    }
    text += `原文：\n${material.substring(0, LlmClient.MAX_INPUT)}`;
    return text;
  }

  private static async extractPoints(chunk: string, idx: number, total: number): Promise<string> {
    const system = '你在为后续摘要做「要点抽取」。规则：'
      + '1) 只提取事实、承诺、决定、数字、时间、人名；2) 每条一行，短句，不加评论；'
      + '3) 原文没有的不要补；4) 最多 12 条。';
    const user = `这是第 ${idx}/${total} 段：\n${chunk}`;
    const payload: ChatPayload = {
      model: Repo.getStr(Keys.LLM_MODEL),
      temperature: 0.1,
      max_tokens: 400,
      messages: [
        { role: 'system', content: system } as ChatMessage,
        { role: 'user', content: user } as ChatMessage
      ]
    };
    const res = await LlmClient.postChat(LlmClient.endpoint(), JSON.stringify(payload), 60);
    return res.ok ? res.text.trim() : '';
  }

  private static async postChat(base: string, body: string, timeoutSec: number): Promise<HttpOut> {
    const key = Repo.getStr(Keys.LLM_KEY);
    const client = http.createHttp();
    try {
      const header: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${key}`
      };
      const res = await client.request(`${base}/chat/completions`, {
        method: http.RequestMethod.POST,
        header: header,
        extraData: body,
        connectTimeout: 20000,
        readTimeout: timeoutSec * 1000
      });
      const raw = typeof res.result === 'string' ? (res.result as string) : JSON.stringify(res.result);
      if (res.responseCode !== 200) {
        return { ok: false, text: '', message: LlmClient.translateHttp(res.responseCode, raw) };
      }
      const parsed = JSON.parse(raw) as ChatResponse;
      const choices = parsed.choices;
      if (choices === undefined || choices.length === 0) {
        return { ok: false, text: '', message: '模型没有返回内容' };
      }
      const msg = choices[0].message;
      const content = msg === undefined ? '' : LlmClient.pick(msg.content, '');
      if (content.length === 0) {
        return { ok: false, text: '', message: '模型返回空内容' };
      }
      return { ok: true, text: content, message: '' };
    } catch (e) {
      const err = e as BusinessError;
      return { ok: false, text: '', message: `网络请求失败：${err.message}（${err.code}）` };
    } finally {
      client.destroy();
    }
  }

  private static translateHttp(code: number, raw: string): string {
    if (code === 401) {
      return 'API Key 无效或已过期（401）';
    }
    if (code === 402 || code === 403) {
      return '额度不足或无权限（' + code + '）';
    }
    if (code === 404) {
      return '接口地址或模型名不对（404）：检查 Base URL 是否要带 /v1';
    }
    if (code === 429) {
      return '请求太频繁或超出配额（429），稍后再试';
    }
    let detail = '';
    try {
      const body = JSON.parse(raw) as ChatResponse;
      const eb = body.error;
      if (eb !== undefined && eb.message !== undefined) {
        detail = eb.message;
      }
    } catch (e) {
      detail = raw.substring(0, 120);
    }
    return `请求失败（${code}）${detail}`;
  }

  /** 从模型输出里抽出 JSON（兼容 ```json 包裹、前后多余说明） */
  private static extractJson(text: string): string {
    let t = text.trim();
    const fence = t.indexOf('```');
    if (fence >= 0) {
      const start = t.indexOf('\n', fence);
      const end = t.lastIndexOf('```');
      if (start > 0 && end > start) {
        t = t.substring(start + 1, end).trim();
      }
    }
    const first = t.indexOf('{');
    const last = t.lastIndexOf('}');
    if (first < 0 || last <= first) {
      return '';
    }
    return t.substring(first, last + 1);
  }

  private static parseTodos(arr: TodoJson[] | undefined): TodoDraft[] {
    const out: TodoDraft[] = [];
    if (arr === undefined) {
      return out;
    }
    for (let i = 0; i < arr.length; i++) {
      const item: Object = arr[i];
      if (typeof item === 'string') {
        const s = (item as string).trim();
        if (s.length > 0) {
          out.push({ text: s, due: '' });
        }
        continue;
      }
      const t = item as TodoJson;
      let text = LlmClient.pick(t.text, '');
      if (text.length === 0) {
        text = LlmClient.pick(t.content, '');
      }
      text = text.trim();
      if (text.length === 0) {
        continue;
      }
      out.push({ text: text, due: LlmClient.normalizeDue(LlmClient.pick(t.due, '')) });
    }
    return out;
  }

  /** 只接受 YYYY-MM-DD，其它一律置空，避免脏日期污染到期提醒 */
  private static normalizeDue(due: string): string {
    const d = due.trim();
    if (d.length < 10) {
      return '';
    }
    const s = d.substring(0, 10);
    const re = new RegExp('^\\d{4}-\\d{2}-\\d{2}$');
    return re.test(s) ? s : '';
  }

  /**
   * 摘要质量守卫
   *
   * 做法：把摘要拆成 4 字以上的短语，看有多少能在原文里找到。
   * 命中率过低说明模型可能在编（或严重跑题），标成 review 让用户自己判断，
   * 而不是把不可信的摘要当真的一样展示。
   */
  private static guardSummary(summary: string, source: string): string {
    if (summary.length < 20) {
      return Quality.REVIEW;
    }
    const phrases: string[] = [];
    const segs = summary.split(/[。，、；：！？\n,.!?;:（）()「」“”"]/);
    for (let i = 0; i < segs.length; i++) {
      const s = segs[i].trim();
      if (s.length >= 4) {
        phrases.push(s.substring(0, 6));
      }
    }
    if (phrases.length === 0) {
      return Quality.OK;
    }
    let hit = 0;
    for (let i = 0; i < phrases.length; i++) {
      if (source.indexOf(phrases[i]) >= 0) {
        hit++;
      }
    }
    const ratio = hit / phrases.length;
    return ratio < 0.35 ? Quality.REVIEW : Quality.OK;
  }

  /** 清洗转写文本：去语气词堆叠与超长空白，避免把噪声喂给模型 */
  static clean(text: string): string {
    let t = text.replace(/\r/g, '');
    t = t.replace(/[ \t]+/g, ' ');
    t = t.replace(/\n{3,}/g, '\n\n');
    return t.trim();
  }

  /** 按句子边界分块，块间留 200 字重叠，避免关键信息正好被切断 */
  private static chunk(text: string, size: number): string[] {
    const out: string[] = [];
    if (text.length <= size) {
      out.push(text);
      return out;
    }
    let start = 0;
    while (start < text.length) {
      let end = Math.min(start + size, text.length);
      if (end < text.length) {
        const window = text.substring(end - 200, end);
        const cut = Math.max(window.lastIndexOf('。'), window.lastIndexOf('\n'));
        if (cut > 0) {
          end = end - 200 + cut + 1;
        }
      }
      out.push(text.substring(start, end));
      if (end >= text.length) {
        break;
      }
      start = Math.max(end - 200, start + 1);
    }
    return out;
  }

  private static pick(v: string | undefined, def: string): string {
    return v === undefined || v === null ? def : v;
  }
}

interface HttpOut {
  ok: boolean;
  text: string;
  message: string;
}
