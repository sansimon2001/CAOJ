// 项目归属 —— 移植自安卓 ai/ProjectMind.kt 的思路，规则更简单但守住同一条底线：
// 只有拿到「原文里真实出现过的证据」，才允许自动归属；证据抄不出来就留空等人确认。
//
// 为什么不用向量检索：项目档案通常只有十几个，靠名字/别名/线索词的直接命中就能覆盖
// 绝大多数情况，而且每一步都能解释给用户看（命中了哪个词、在哪句话里）。

import type { Project } from './store'

export interface ProjectMatch {
  projectId: string
  projectName: string
  confidence: number
  evidence: string
  /** 命中的词，用来在界面上说明「为什么归到这个项目」 */
  hits: string[]
}

interface Hit {
  term: string
  weight: number
  count: number
  at: number
}

/** 单次出现的权重：规范名最重，别名次之，线索词最轻 */
const W_NAME = 3
const W_ALIAS = 2
const W_KEYWORD = 1
/** 同一个词重复出现不再加分，避免「提到十次」压过「名字命中」 */
const MAX_COUNT = 3

function countOccurrences(haystack: string, needle: string): { count: number; at: number } {
  if (!needle) return { count: 0, at: -1 }
  const h = haystack.toLowerCase()
  const n = needle.toLowerCase()
  let count = 0
  let at = -1
  let i = h.indexOf(n)
  while (i >= 0) {
    if (at < 0) at = i
    count++
    i = h.indexOf(n, i + n.length)
  }
  return { count, at }
}

/** 从命中位置截一句原话作为证据。截出来的片段必然存在于原文，因为它就是从原文切的。 */
function quoteAround(text: string, at: number, len: number): string {
  const start = Math.max(0, at - 26)
  const end = Math.min(text.length, at + len + 34)
  // 尽量在句读处收边，读起来像一句话而不是半截
  let s = text.slice(start, end).replace(/\s+/g, '')
  if (start > 0) {
    const cut = Math.max(s.indexOf('。'), s.indexOf('，'), s.indexOf('？'), s.indexOf('！'))
    if (cut > 0 && cut < 20) s = s.slice(cut + 1)
    s = '…' + s
  }
  if (end < text.length) s = s + '…'
  return s
}

export function matchProject(text: string, projects: Project[]): ProjectMatch | null {
  if (!text || text.length < 8) return null
  const active = projects.filter((p) => !p.archived && p.name.trim())
  if (!active.length) return null

  let best: { p: Project; score: number; hits: Hit[] } | null = null

  for (const p of active) {
    const hits: Hit[] = []
    const name = countOccurrences(text, p.name)
    if (name.count) hits.push({ term: p.name, weight: W_NAME, count: Math.min(name.count, MAX_COUNT), at: name.at })

    for (const a of p.aliases) {
      if (!a.trim()) continue
      const c = countOccurrences(text, a)
      if (c.count) hits.push({ term: a, weight: W_ALIAS, count: Math.min(c.count, MAX_COUNT), at: c.at })
    }
    for (const k of p.keywords) {
      if (!k.trim()) continue
      const c = countOccurrences(text, k)
      if (c.count) hits.push({ term: k, weight: W_KEYWORD, count: Math.min(c.count, MAX_COUNT), at: c.at })
    }

    const score = hits.reduce((s, h) => s + h.weight * h.count, 0)
    if (score > 0 && (!best || score > best.score)) best = { p, score, hits }
  }

  if (!best) return null

  // 证据取「权重最高、位置最靠前」的那个词所在的那句话
  const top = [...best.hits].sort((a, b) => b.weight - a.weight || a.at - b.at)[0]
  const evidence = quoteAround(text, top.at, top.term.length)

  // 证据必须真的在原文里（切片保证成立，这里再校验一次，防止以后改坏）
  const bare = evidence.replace(/^…|…$/g, '')
  if (!text.includes(bare.slice(0, Math.min(12, bare.length)))) {
    return null
  }

  let confidence = 0
  if (best.score >= 6) confidence = 0.85
  else if (best.score >= 3) confidence = 0.65
  else confidence = 0.45

  // 只有一个轻量线索词命中时，不该表现得很确定
  if (confidence === 0.45 && best.hits.every((h) => h.weight === W_KEYWORD)) confidence = 0.3

  return {
    projectId: best.p.id,
    projectName: best.p.name,
    confidence,
    evidence,
    hits: best.hits.map((h) => h.term),
  }
}

/** 校验 AI 给出的证据是否真在原文里。编造的引用一律作废。 */
export function verifyEvidence(text: string, evidence: string): boolean {
  const e = (evidence ?? '').replace(/[\s…"“”'']/g, '')
  if (e.length < 4) return false
  const flat = text.replace(/\s/g, '')
  // 取前 10 个字做核验：整段偶有标点差异，但开头必须对得上
  return flat.includes(e.slice(0, Math.min(10, e.length)))
}
