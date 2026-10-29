// 摘要质量守卫 —— 移植自安卓 ai/SummaryGuard.kt
//
// 大模型偶尔会编造不存在的人名、数字、待办。这里做规则校验：
//   1) 摘要里的数字/金额/百分比必须能在原文找到
//   2) 摘要关键短语要能在原文对上（子串回扣）
//   3) 待办找不到原文依据直接丢弃（宁可少，不可编）
// 校验不过标记为「需人工确认」，而不是静默当真展示。

export interface GuardVerdict {
  ok: boolean
  /** 需要人工确认的原因（ok=true 时为空） */
  reason: string
  /** 过滤后的待办 */
  todos: string[]
  /** 回扣命中率，便于调试 */
  overlap: number
}

const STOP = new Set([
  '这个', '那个', '我们', '他们', '你们', '然后', '所以', '但是', '因为', '如果',
  '可以', '应该', '需要', '就是', '还是', '没有', '什么', '怎么', '已经', '现在',
])

const normalize = (s: string): string =>
  s.replace(/[\s，。！？、,.!?：:；;"'（）()\[\]【】]/g, '')

/** 提取数字型 token（金额、百分比、日期、数量） */
function extractNumbers(text: string): string[] {
  const out = new Set<string>()
  // 带单位的：3万、4.28万元、41%、10月、5号、8套
  for (const m of text.matchAll(/\d+(?:\.\d+)?(?:万|亿|元|块|%|％|套|个|人|天|月|号|日|点|层|栋|次)/g)) {
    out.add(normalize(m[0]))
  }
  // 纯数字，长度≥2，避免把「1」「2」当幻觉
  for (const m of text.matchAll(/(?<![\d.])\d{2,}(?:\.\d+)?/g)) {
    out.add(normalize(m[0]))
  }
  return [...out]
}

/** 提取用于回扣校验的中文短语（3-8 字滑窗，去停用词） */
function extractPhrases(text: string): string[] {
  const clean = text.replace(/\[[^\]]{1,12}\]/g, ' ').replace(/[^\u4e00-\u9fa5A-Za-z0-9]/g, ' ')
  const tokens = clean.split(' ').filter((t) => t.length >= 3)
  const out = new Set<string>()
  for (const t of tokens) {
    if (t.length <= 8) {
      out.add(t)
    } else {
      let i = 0
      while (i + 6 <= t.length && out.size < 40) {
        out.add(t.slice(i, i + 6))
        i += 3
      }
    }
  }
  return [...out].filter((x) => !STOP.has(x)).slice(0, 24)
}

export function checkSummary(
  transcript: string,
  title: string,
  summary: string,
  todos: string[]
): GuardVerdict {
  const src = normalize(transcript)
  if (src.length < 20) {
    // 原文太短无从校验，也不必标红（多为灵感速记）
    return { ok: true, reason: '', todos, overlap: 1 }
  }

  const reasons: string[] = []

  // 1) 数字回扣
  const numbers = [...extractNumbers(summary), ...extractNumbers(title)]
  if (numbers.length) {
    const missing = numbers.filter((n) => !src.includes(n))
    if (missing.length * 2 > numbers.length) {
      reasons.push(`摘要中的数字在原文找不到（${missing.slice(0, 3).join('、')}）`)
    }
  }

  // 2) 关键短语回扣
  //    短文本不查这一条：几百字的原文，摘要天然是改写，短语重合度低是正常的，
  //    照搬阈值只会把好摘要标红。事实性仍由数字回扣把关。
  const phrases = extractPhrases(summary)
  const hit = phrases.filter((p) => src.includes(normalize(p))).length
  const overlap = phrases.length ? hit / phrases.length : 1
  if (src.length >= 300 && phrases.length >= 4 && overlap < 0.35) {
    reasons.push(`摘要与原文重合度过低（${Math.round(overlap * 100)}%）`)
  }

  // 3) 待办必须有原文依据
  const validTodos = todos.filter((t) => {
    const p = extractPhrases(t)
    return !p.length || p.some((x) => src.includes(normalize(x)))
  })
  if (validTodos.length < todos.length) {
    reasons.push(`已过滤 ${todos.length - validTodos.length} 条无原文依据的待办`)
  }

  // 4) 摘要长度异常
  if (summary.length >= 1 && summary.length <= 14) reasons.push('摘要过短，可能未整理充分')

  const blocking = reasons.filter((r) => !r.startsWith('已过滤'))
  return { ok: blocking.length === 0, reason: reasons.join('；'), todos: validTodos, overlap }
}
