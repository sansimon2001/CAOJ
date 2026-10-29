// 摘要与待办 —— 提示词移植自安卓 ai/LlmClient.kt
//
// 那段提示词是跟着 25 个版本一路改出来的，字句背后都有具体教训（比如
// 「摘要数字必须在原文可查」「待办找不到依据就丢弃」）。这里只去掉 PC 版
// 第一版用不到的项目档案部分，其余规则原样保留。
//
// 长会议（1 小时起）会超过多数模型的上下文，所以超过阈值先分段抽要点，
// 再把要点汇总成摘要——这就是安卓端的 map-reduce。

import type { Settings } from './store'
import { checkSummary } from './guard'

export interface TodoDraft {
  text: string
  due: string
}

export interface AnalyzeResult {
  title: string
  summary: string
  insight: string
  todos: TodoDraft[]
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

const MAIN_SYSTEM = (today: string) => `你是「草记」的记录整理助手，工作方式类似个人认知教练。
今天是 ${today}。

用户会给你一段对话、通话或线上会议的语音转写文本，你需要输出严格 JSON（不要 markdown 代码块）：
{
  "title": "不超过 16 字的标题，体现这次沟通的核心事件",
  "summary": "3-6 句话的结构化摘要：讨论了什么、谁承诺了什么、结论是什么",
  "todos": [{"text": "待办内容（有动作、有对象）", "due": "YYYY-MM-DD，判断不出给空字符串"}],
  "insight": "1-2 句行为洞察：沟通习惯、判断依据或可复盘的得失（客观、不评价人品）"
}

【硬性要求】
1. 只输出 JSON，不要任何多余文字、不要解释；
2. todos 只提取明确的行动项，没有就给空数组；due 由文本中的时间线索换算成具体日期
   （如「下周五」「10 月底前」），今天为 ${today}，无法判断就给空字符串；
3. 摘要里的数字、金额、百分比、日期，必须是原文里出现过的，不许推算或编造；
4. 摘要与洞察站在「我」的视角，明确指出我的承诺与待跟进事项；
5. 不编造未出现的人名、公司名、项目名。

【会议场景补充】
线上会议常有大量寒暄与跑题，摘要只保留决策、结论、待办与分歧点；
发言人分不清时用「有人提到」「会上提出」，不要硬指派给某个具体的人。`

const EXTRACT_SYSTEM = `你在为长会议记录做「要点抽取」。规则：
1. 只保留事实：谁说了什么、定了什么、还差什么、有哪些数字与日期；
2. 每段输出 3-8 条短句，每条不超过 30 字，直接列，不要编号解释；
3. 原文没提到的，绝对不要写；
4. 不要总结成一句话，要保留可追溯的细节（人名、时间、金额、数量）。`

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

export async function analyze(transcript: string, s: Settings): Promise<AnalyzeOutcome> {
  const trimmed = transcript.trim()
  if (trimmed.length < 4) return { ok: false, error: '转写内容太短，没有可整理的内容' }

  const today = new Date().toISOString().slice(0, 10)
  let material = trimmed
  let note = ''

  // 长文本先分段抽要点，避免超出模型上下文后只读了个开头
  if (trimmed.length > 9000) {
    const chunks = splitChunks(trimmed, 6000)
    const points: string[] = []
    for (let i = 0; i < chunks.length; i++) {
      const r = await chat(s, EXTRACT_SYSTEM, `第 ${i + 1}/${chunks.length} 段：\n${chunks[i]}`)
      if (r.ok) points.push(`【第${i + 1}段】\n${r.content.trim()}`)
      else return { ok: false, error: `长文本分段抽取失败（第 ${i + 1} 段）：${r.error}` }
    }
    material = points.join('\n\n')
    note = `原文 ${trimmed.length} 字，分 ${chunks.length} 段抽取要点后汇总`
  }

  const r = await chat(s, MAIN_SYSTEM(today), material)
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
    result: { title, summary, insight, todos: kept },
    guardNote: verdict.reason || undefined,
    needsReview: !verdict.ok,
    note,
  }
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
