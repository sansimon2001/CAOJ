// 摘要 / 纪要 / 方案 / 文字精修 —— 提示词移植自安卓 ai/LlmClient.kt
//
// 那段提示词是跟着 25 个版本一路改出来的，字句背后都有具体教训（比如
// 「摘要数字必须在原文可查」「待办找不到依据就丢弃」）。这里保留全部规则，
// 并在其基础上加了三个新产出：会议纪要、行动方案、转写稿精修。
//
// 长会议（1 小时起）会超过多数模型的上下文，所以超过阈值先分段抽要点，
// 再把要点汇总——这就是安卓端的 map-reduce。

import type { Minutes, Plan, Settings } from './store'
import { checkSummary } from './guard'
import { lengthSanity } from './textclean'

export interface TodoDraft {
  text: string
  due: string
}

export type KindGuess = 'meeting' | 'call' | 'talk' | 'note' | 'memo'

export interface AnalyzeResult {
  title: string
  summary: string
  insight: string
  todos: TodoDraft[]
  kind: KindGuess
  tags: string[]
  /** 模型判断的项目名与证据；证据对不上原文时会被丢弃 */
  project: string
  projectEvidence: string
}

export interface AnalyzeOutcome {
  ok: boolean
  result?: AnalyzeResult
  error?: string
  /** 守卫给出的提示（可能是「已过滤 N 条无依据待办」这类非阻塞信息） */
  guardNote?: string
  /** true 表示内容与原文对不上，需要人工确认 */
  needsReview?: boolean
  /** 长文本走了分段时的说明 */
  note?: string
}

const KINDS: KindGuess[] = ['meeting', 'call', 'talk', 'note', 'memo']

const MAIN_SYSTEM = (today: string, projectNames: string[]) => `你是「草记」的记录整理助手，工作方式类似个人认知教练。
今天是 ${today}。

用户会给你一段对话、通话或线上会议的语音转写文本，你需要输出严格 JSON（不要 markdown 代码块）：
{
  "title": "不超过 16 字的标题，体现这次沟通的核心事件",
  "kind": "meeting / call / talk / note / memo 五选一：多人会议选 meeting；电话或语音通话选 call；一对一面谈选 talk；个人想法口述选 note；备忘提醒选 memo",
  "summary": "3-6 句话的结构化摘要：讨论了什么、谁承诺了什么、结论是什么",
  "todos": [{"text": "待办内容（有动作、有对象）", "due": "YYYY-MM-DD，判断不出给空字符串"}],
  "insight": "1-2 句行为洞察：沟通习惯、判断依据或可复盘的得失（客观、不评价人品）",
  "tags": ["2-4 个主题标签，每个不超过 6 个字，用原文里出现过的说法"],
  "project": "项目名，必须从候选列表里选，没有合适的就给空字符串",
  "projectEvidence": "支持上述项目判断的原文片段，必须逐字摘自原文；不给项目时给空字符串"
}
${projectNames.length ? `\n【候选项目】${projectNames.join('、')}` : '\n【候选项目】无（project 一律给空字符串）'}

【硬性要求】
1. 只输出 JSON，不要任何多余文字、不要解释；
2. todos 只提取明确的行动项，没有就给空数组；due 由文本中的时间线索换算成具体日期
   （如「下周五」「10 月底前」），今天为 ${today}，无法判断就给空字符串；
3. 摘要里的数字、金额、百分比、日期，必须是原文里出现过的，不许推算或编造；
4. 摘要与洞察站在「我」的视角，明确指出我的承诺与待跟进事项；
5. 不编造未出现的人名、公司名、项目名；
6. project 只能从候选列表里挑，且 projectEvidence 必须能在原文中逐字找到；
7. tags 不要出现「会议」「沟通」这类没有信息量的词。

【会议场景补充】
线上会议常有大量寒暄与跑题，摘要只保留决策、结论、待办与分歧点；
发言人分不清时用「有人提到」「会上提出」，不要硬指派给某个具体的人。`

const EXTRACT_SYSTEM = `你在为长会议记录做「要点抽取」。规则：
1. 只保留事实：谁说了什么、定了什么、还差什么、有哪些数字与日期；
2. 每段输出 3-8 条短句，每条不超过 30 字，直接列，不要编号解释；
3. 原文没提到的，绝对不要写；
4. 不要总结成一句话，要保留可追溯的细节（人名、时间、金额、数量）。`

const MINUTES_SYSTEM = (today: string) => `你在整理正式会议纪要。今天是 ${today}。
用户会给你会议的语音转写文本，输出严格 JSON（不要 markdown 代码块）：
{
  "topic": "会议主题，不超过 20 字",
  "background": "一句话说明为什么开这个会",
  "agenda": ["讨论到的议题，3-6 条，每条不超过 18 字"],
  "decisions": ["已经明确的结论、决议、拍板结果，逐条列；没有就空数组"],
  "actions": [{"text": "具体要做什么", "owner": "负责人，原文没提就给空字符串", "due": "YYYY-MM-DD，判断不出给空字符串"}],
  "risks": ["没谈拢的分歧、遗留问题、风险点"],
  "next": "下一步最该推进的一件事，一句话"
}

【硬性要求】
1. 只写原文里有的内容，不许补背景、补结论；
2. 数字、金额、日期、比例必须原文出现过；
3. 说话人分不清时用「会上」「有人」表述，不要编名字；
4. 议题按讨论顺序排；
5. 寒暄、跑题、重复表述一律不写进纪要。`

const PLAN_SYSTEM = (today: string, projectName: string) => `你是一位务实的项目推进顾问。今天是 ${today}。
用户会给你一次沟通的转写文本${projectName ? `，属于项目「${projectName}」` : ''}。请据此产出可直接执行的行动方案，输出严格 JSON（不要 markdown 代码块）：
{
  "goal": "这次要达成的结果，一句话，能判断做没做成",
  "background": "现状与约束，2-3 句",
  "steps": [{"step": "步骤名，不超过 6 字", "detail": "具体做什么、做到什么程度", "owner": "谁做，原文没提就给空字符串", "when": "YYYY-MM-DD 或「本周内」这类相对时间"}],
  "resources": ["需要的人、钱、物料、信息，没有就给空数组"],
  "risks": ["可能卡住的地方，以及怎么绕开"],
  "metric": "怎么算做成了，一句话"
}

【硬性要求】
1. 步骤 4-7 个，按依赖顺序排，每步都能直接派给人；
2. 不许编造原文没有的人名、数字、金额、截止日期；
3. 信息不足的地方，在 detail 里写「需确认：xxx」，不要自己填一个看起来合理的数；
4. 不要复述背景凑字数，steps 才是重点。`

const CLEAN_SYSTEM = `你在给语音转写稿做「保守校对」。规则：
1. 只做四件事：断句分段、去掉口头禅与语气词、纠正明显同音错字、统一标点；
2. 绝不改写、绝不缩写、绝不总结、绝不添加原文没有的信息；
3. 人名、公司名、地名、数字、金额、日期一律原样保留；
4. 拿不准是否有错的地方，保持原样；
5. 只输出整理后的正文，不要标题、不要说明、不要 markdown 标记。`

/** 按长度切块，尽量在句号/换行处断开，避免把一句话劈成两半 */
function splitChunks(text: string, size: number): string[] {
  const chunks: string[] = []
  let i = 0
  while (i < text.length) {
    let end = Math.min(i + size, text.length)
    if (end < text.length) {
      const slice = text.slice(i, end)
      const cut = Math.max(slice.lastIndexOf('。'), slice.lastIndexOf('\n'), slice.lastIndexOf('？'))
      if (cut > size * 0.6) end = i + cut + 1
    }
    chunks.push(text.slice(i, end))
    i = end
  }
  return chunks
}

/** 从模型输出里取出 JSON：容忍 ```json 包裹与前后多余文字 */
function extractJson(content: string): string | null {
  let s = content.trim()
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/)
  if (fence) s = fence[1].trim()
  const start = s.indexOf('{')
  const end = s.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  return s.slice(start, end + 1)
}

async function chat(s: Settings, system: string, user: string): Promise<{ ok: boolean; content: string; error: string }> {
  if (!s.llmBaseUrl) return { ok: false, content: '', error: '还没有填写大模型接口地址' }
  if (!s.llmApiKey) return { ok: false, content: '', error: '还没有填写大模型 API Key' }

  const url = s.llmBaseUrl.replace(/\/+$/, '') + '/chat/completions'
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${s.llmApiKey}`,
      },
      body: JSON.stringify({
        model: s.llmModel,
        messages: [
          { role: 'system', content: system },
          { role: 'user', content: user },
        ],
        temperature: 0.3,
        stream: false,
      }),
    })
    const raw = await res.text()
    if (res.status !== 200) {
      if (res.status === 401 || res.status === 403) return { ok: false, content: '', error: 'Key 无效或没有权限' }
      if (res.status === 404) return { ok: false, content: '', error: '接口地址不对（404）' }
      if (res.status === 429) return { ok: false, content: '', error: '额度用尽或请求过快（429）' }
      return { ok: false, content: '', error: `请求失败（${res.status}）：${raw.slice(0, 200)}` }
    }
    const j = JSON.parse(raw)
    const content = j?.choices?.[0]?.message?.content
    if (typeof content !== 'string' || !content.trim()) {
      return { ok: false, content: '', error: '模型返回了空内容' }
    }
    return { ok: true, content, error: '' }
  } catch (e) {
    return { ok: false, content: '', error: `请求失败：${(e as Error).message}` }
  }
}

/** 长文本先分段抽要点，返回可直接喂给模型的材料。多个产出（摘要/纪要/方案）共用。 */
async function digest(
  transcript: string,
  s: Settings,
  threshold = 9000
): Promise<{ ok: boolean; material: string; note: string; error?: string }> {
  const trimmed = transcript.trim()
  if (trimmed.length <= threshold) return { ok: true, material: trimmed, note: '' }

  const chunks = splitChunks(trimmed, 6000)
  const points: string[] = []
  for (let i = 0; i < chunks.length; i++) {
    const r = await chat(s, EXTRACT_SYSTEM, `第 ${i + 1}/${chunks.length} 段：\n${chunks[i]}`)
    if (!r.ok) return { ok: false, material: '', note: '', error: `长文本分段抽取失败（第 ${i + 1} 段）：${r.error}` }
    points.push(`【第${i + 1}段】\n${r.content.trim()}`)
  }
  return {
    ok: true,
    material: points.join('\n\n'),
    note: `原文 ${trimmed.length} 字，分 ${chunks.length} 段抽取要点后汇总`,
  }
}

export async function analyze(
  transcript: string,
  s: Settings,
  opts: { projectNames?: string[] } = {}
): Promise<AnalyzeOutcome> {
  const trimmed = transcript.trim()
  if (trimmed.length < 4) return { ok: false, error: '转写内容太短，没有可整理的内容' }

  const today = new Date().toISOString().slice(0, 10)
  const d = await digest(trimmed, s)
  if (!d.ok) return { ok: false, error: d.error ?? '长文本处理失败' }
  const note = d.note

  const r = await chat(s, MAIN_SYSTEM(today, opts.projectNames ?? []), d.material)
  if (!r.ok) return { ok: false, error: r.error, note }

  const json = extractJson(r.content)
  if (!json) return { ok: false, error: '模型没有按要求输出 JSON', note }

  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(json)
  } catch {
    return { ok: false, error: '模型输出的 JSON 无法解析', note }
  }

  const title = String(parsed.title ?? '').trim()
  const summary = String(parsed.summary ?? '').trim()
  const insight = String(parsed.insight ?? '').trim()
  const kindRaw = String(parsed.kind ?? '').trim()
  const kind = (KINDS as string[]).includes(kindRaw) ? (kindRaw as KindGuess) : 'talk'
  const tags = Array.isArray(parsed.tags)
    ? (parsed.tags as unknown[])
        .map((t) => String(t ?? '').trim())
        .filter((t) => t.length > 0 && t.length <= 10)
        .slice(0, 4)
    : []

  const rawTodos: TodoDraft[] = Array.isArray(parsed.todos)
    ? (parsed.todos as Array<Record<string, unknown>>)
        .map((t) => ({
          text: String(t?.text ?? '').trim(),
          due: normalizeDue(String(t?.due ?? '')),
        }))
        .filter((t) => t.text.length > 0)
    : []

  if (!title && !summary) return { ok: false, error: '模型输出缺少标题与摘要', note }

  // 出口把关：数字回扣 + 待办溯源，无依据的待办直接丢掉
  const verdict = checkSummary(trimmed, title, summary, rawTodos.map((t) => t.text))
  const kept = rawTodos.filter((t) => verdict.todos.includes(t.text))

  return {
    ok: true,
    result: {
      title,
      summary,
      insight,
      todos: kept,
      kind,
      tags,
      project: String(parsed.project ?? '').trim(),
      projectEvidence: String(parsed.projectEvidence ?? '').trim(),
    },
    guardNote: verdict.reason || undefined,
    needsReview: !verdict.ok,
    note,
  }
}

export interface MinutesOutcome {
  ok: boolean
  minutes?: Minutes
  error?: string
  note?: string
}

export async function makeMinutes(transcript: string, s: Settings): Promise<MinutesOutcome> {
  const trimmed = transcript.trim()
  if (trimmed.length < 20) return { ok: false, error: '内容太短，不够整理纪要' }
  const today = new Date().toISOString().slice(0, 10)
  const d = await digest(trimmed, s)
  if (!d.ok) return { ok: false, error: d.error ?? '长文本处理失败' }

  const r = await chat(s, MINUTES_SYSTEM(today), d.material)
  if (!r.ok) return { ok: false, error: r.error, note: d.note }
  const json = extractJson(r.content)
  if (!json) return { ok: false, error: '模型没有按要求输出 JSON', note: d.note }

  let p: Record<string, unknown>
  try {
    p = JSON.parse(json)
  } catch {
    return { ok: false, error: '模型输出的 JSON 无法解析', note: d.note }
  }

  const strArr = (v: unknown) =>
    Array.isArray(v)
      ? (v as unknown[]).map((x) => String(x ?? '').trim()).filter(Boolean)
      : []

  const actions = Array.isArray(p.actions)
    ? (p.actions as Array<Record<string, unknown>>)
        .map((a) => ({
          text: String(a?.text ?? '').trim(),
          owner: String(a?.owner ?? '').trim(),
          due: normalizeDue(String(a?.due ?? '')),
        }))
        .filter((a) => a.text)
    : []

  const minutes: Minutes = {
    topic: String(p.topic ?? '').trim(),
    background: String(p.background ?? '').trim(),
    agenda: strArr(p.agenda),
    decisions: strArr(p.decisions),
    actions,
    risks: strArr(p.risks),
    next: String(p.next ?? '').trim(),
    generatedAt: new Date().toISOString(),
  }

  if (!minutes.topic && !minutes.agenda.length && !minutes.decisions.length) {
    return { ok: false, error: '模型输出太单薄，没拿到议题或结论', note: d.note }
  }
  return { ok: true, minutes, note: d.note }
}

export interface PlanOutcome {
  ok: boolean
  plan?: Plan
  error?: string
  note?: string
}

export async function makePlan(
  transcript: string,
  s: Settings,
  opts: { projectName?: string } = {}
): Promise<PlanOutcome> {
  const trimmed = transcript.trim()
  if (trimmed.length < 20) return { ok: false, error: '内容太短，不够生成方案' }
  const today = new Date().toISOString().slice(0, 10)
  const d = await digest(trimmed, s)
  if (!d.ok) return { ok: false, error: d.error ?? '长文本处理失败' }

  const r = await chat(s, PLAN_SYSTEM(today, opts.projectName ?? ''), d.material)
  if (!r.ok) return { ok: false, error: r.error, note: d.note }
  const json = extractJson(r.content)
  if (!json) return { ok: false, error: '模型没有按要求输出 JSON', note: d.note }

  let p: Record<string, unknown>
  try {
    p = JSON.parse(json)
  } catch {
    return { ok: false, error: '模型输出的 JSON 无法解析', note: d.note }
  }

  const steps = Array.isArray(p.steps)
    ? (p.steps as Array<Record<string, unknown>>)
        .map((x) => ({
          step: String(x?.step ?? '').trim(),
          detail: String(x?.detail ?? '').trim(),
          owner: String(x?.owner ?? '').trim(),
          when: String(x?.when ?? '').trim(),
        }))
        .filter((x) => x.step || x.detail)
    : []

  const plan: Plan = {
    goal: String(p.goal ?? '').trim(),
    background: String(p.background ?? '').trim(),
    steps,
    resources: Array.isArray(p.resources) ? (p.resources as unknown[]).map((x) => String(x ?? '').trim()).filter(Boolean) : [],
    risks: Array.isArray(p.risks) ? (p.risks as unknown[]).map((x) => String(x ?? '').trim()).filter(Boolean) : [],
    metric: String(p.metric ?? '').trim(),
    generatedAt: new Date().toISOString(),
  }

  if (!plan.goal && !steps.length) return { ok: false, error: '模型没产出可用的步骤', note: d.note }
  return { ok: true, plan, note: d.note }
}

export interface CleanOutcome {
  ok: boolean
  text: string
  error?: string
  /** AI 改写幅度异常时的说明；此时应保留规则清洗的结果 */
  note?: string
}

/** AI 精修：只断句、去口头禅、改同音错字。长度变化超出阈值就退回原文，避免被改写。 */
export async function cleanText(transcript: string, s: Settings): Promise<CleanOutcome> {
  const trimmed = transcript.trim()
  if (trimmed.length < 20) return { ok: false, text: '', error: '内容太短，不需要精修' }

  // 超长先切块，逐块精修后拼回，保持段序
  const chunks = trimmed.length > 7000 ? splitChunks(trimmed, 6000) : [trimmed]
  const out: string[] = []
  for (let i = 0; i < chunks.length; i++) {
    const r = await chat(s, CLEAN_SYSTEM, chunks[i])
    if (!r.ok) return { ok: false, text: '', error: r.error }
    const body = r.content
      .replace(/^```[a-z]*\s*/i, '')
      .replace(/```$/, '')
      .trim()
    const sanity = lengthSanity(chunks[i], body)
    if (!sanity.ok) {
      return {
        ok: false,
        text: '',
        error: `第 ${i + 1} 段改写幅度异常（长度变为 ${(sanity.ratio * 100).toFixed(0)}%），已放弃精修以免改变原意`,
      }
    }
    out.push(body)
  }
  return { ok: true, text: out.join('\n\n') }
}

/** due 只认 YYYY-MM-DD，别的写法一律当「没判断出来」，免得显示一个错日期 */
function normalizeDue(due: string): string {
  const m = due.match(/\d{4}-\d{2}-\d{2}/)
  return m ? m[0] : ''
}

/** 测连接：发一句最小请求，确认地址 / Key / 模型三者都对 */
export async function testLlm(s: Settings): Promise<{ ok: boolean; message: string }> {
  const r = await chat(s, '只回复两个字：可用', '测试')
  return r.ok ? { ok: true, message: r.content.slice(0, 60) } : { ok: false, message: r.error }
}
