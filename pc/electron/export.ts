// 导出 —— Markdown 给归档与再加工，HTML 给「发给人看 / 打印成 PDF」。
//
// 之所以不用 docx：生成 docx 要么带一个重库，要么排版很难看；
// 而 HTML 排版可控，浏览器「打印 → 另存为 PDF」就是一份能发的纪要，
// 中文不会掉字，表格与标题层级也保得住。

import fs from 'node:fs'
import path from 'node:path'
import type { RecordItem } from './store'

const KIND_LABEL: Record<string, string> = {
  meeting: '会议',
  call: '通话',
  talk: '面谈',
  note: '随笔',
  memo: '备忘',
}

const SOURCE_LABEL: Record<string, string> = {
  mic: '麦克风',
  system: '系统声音',
  mix: '麦克风 + 系统',
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function fmtWhen(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function fmtDuration(ms: number): string {
  if (!ms) return '时长未知'
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  if (h) return `${h} 小时 ${m} 分`
  if (m) return `${m} 分`
  return `${total} 秒`
}

export function recordToMarkdown(r: RecordItem, projectName = ''): string {
  const L: string[] = []
  L.push(`# ${r.title || '未命名记录'}`)
  L.push('')
  const meta = [
    fmtWhen(r.createdAt),
    fmtDuration(r.durationMs),
    SOURCE_LABEL[r.source] ?? r.source,
    KIND_LABEL[r.kind] ?? '',
    projectName || r.project,
  ].filter(Boolean)
  L.push(`> ${meta.join(' · ')}`)
  if (r.tags.length) L.push(`> 标签：${r.tags.join('、')}`)
  L.push('')

  if (r.minutes) {
    const m = r.minutes
    L.push('## 会议纪要')
    L.push('')
    if (m.topic) L.push(`**主题**：${m.topic}`)
    if (m.background) L.push(`**背景**：${m.background}`)
    L.push('')
    if (m.agenda.length) {
      L.push('### 议题')
      L.push('')
      m.agenda.forEach((a, i) => L.push(`${i + 1}. ${a}`))
      L.push('')
    }
    if (m.decisions.length) {
      L.push('### 结论与决议')
      L.push('')
      m.decisions.forEach((d) => L.push(`- ${d}`))
      L.push('')
    }
    if (m.actions.length) {
      L.push('### 行动项')
      L.push('')
      L.push('| 事项 | 负责人 | 截止 |')
      L.push('| --- | --- | --- |')
      m.actions.forEach((a) => L.push(`| ${a.text} | ${a.owner || '—'} | ${a.due || '—'} |`))
      L.push('')
    }
    if (m.risks.length) {
      L.push('### 分歧与风险')
      L.push('')
      m.risks.forEach((x) => L.push(`- ${x}`))
      L.push('')
    }
    if (m.next) {
      L.push(`**下一步**：${m.next}`)
      L.push('')
    }
  }

  if (r.summary) {
    L.push(r.minutes ? '## 摘要' : '## 概述')
    L.push('')
    L.push(r.summary)
    L.push('')
  }

  if (r.todos.length) {
    L.push('## 待办')
    L.push('')
    for (const t of r.todos) {
      L.push(`- [${t.done ? 'x' : ' '}] ${t.text}${t.dueDate ? `（${t.dueDate}）` : ''}`)
    }
    L.push('')
  }

  if (r.plan) {
    const p = r.plan
    L.push('## 行动方案')
    L.push('')
    if (p.goal) L.push(`**目标**：${p.goal}`)
    if (p.background) L.push(`**现状**：${p.background}`)
    L.push('')
    if (p.steps.length) {
      L.push('| 步骤 | 具体做法 | 负责 | 时间 |')
      L.push('| --- | --- | --- | --- |')
      p.steps.forEach((s) => L.push(`| ${s.step} | ${s.detail} | ${s.owner || '—'} | ${s.when || '—'} |`))
      L.push('')
    }
    if (p.resources.length) {
      L.push('**需要**：' + p.resources.join('；'))
      L.push('')
    }
    if (p.risks.length) {
      L.push('**风险**：')
      p.risks.forEach((x) => L.push(`- ${x}`))
      L.push('')
    }
    if (p.metric) {
      L.push(`**验收**：${p.metric}`)
      L.push('')
    }
  }

  if (r.insight) {
    L.push('## 洞察')
    L.push('')
    L.push(r.insight)
    L.push('')
  }

  const body = r.clean || r.transcript
  if (body) {
    L.push(r.clean ? '## 整理稿' : '## 原文')
    L.push('')
    L.push(body)
    L.push('')
  }
  if (r.clean && r.transcript) {
    L.push('---')
    L.push('')
    L.push('<details><summary>对照：原始转写</summary>')
    L.push('')
    L.push(r.transcript)
    L.push('')
    L.push('</details>')
    L.push('')
  }
  return L.join('\n')
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function paragraphs(text: string): string {
  return text
    .split(/\n{2,}/)
    .map((p) => `<p>${esc(p).replace(/\n/g, '<br>')}</p>`)
    .join('\n')
}

export function recordToHtml(r: RecordItem, projectName = ''): string {
  const meta = [
    fmtWhen(r.createdAt),
    fmtDuration(r.durationMs),
    SOURCE_LABEL[r.source] ?? r.source,
    KIND_LABEL[r.kind] ?? '',
    projectName || r.project,
  ].filter(Boolean)

  const S: string[] = []

  if (r.minutes) {
    const m = r.minutes
    S.push('<section class="block"><h2>会议纪要</h2>')
    if (m.topic) S.push(`<p class="kv"><span>主题</span>${esc(m.topic)}</p>`)
    if (m.background) S.push(`<p class="kv"><span>背景</span>${esc(m.background)}</p>`)
    if (m.agenda.length) {
      S.push('<h3>议题</h3><ol class="plain">')
      m.agenda.forEach((a) => S.push(`<li>${esc(a)}</li>`))
      S.push('</ol>')
    }
    if (m.decisions.length) {
      S.push('<h3>结论与决议</h3><ul class="plain">')
      m.decisions.forEach((d) => S.push(`<li>${esc(d)}</li>`))
      S.push('</ul>')
    }
    if (m.actions.length) {
      S.push('<h3>行动项</h3><table><thead><tr><th>事项</th><th class="c">负责人</th><th class="c">截止</th></tr></thead><tbody>')
      m.actions.forEach((a) =>
        S.push(`<tr><td>${esc(a.text)}</td><td class="c">${esc(a.owner || '—')}</td><td class="c">${esc(a.due || '—')}</td></tr>`)
      )
      S.push('</tbody></table>')
    }
    if (m.risks.length) {
      S.push('<h3>分歧与风险</h3><ul class="plain">')
      m.risks.forEach((x) => S.push(`<li>${esc(x)}</li>`))
      S.push('</ul>')
    }
    if (m.next) S.push(`<p class="kv"><span>下一步</span>${esc(m.next)}</p>`)
    S.push('</section>')
  }

  if (r.summary) {
    S.push(`<section class="block"><h2>${r.minutes ? '摘要' : '概述'}</h2>${paragraphs(r.summary)}</section>`)
  }

  if (r.todos.length) {
    S.push('<section class="block"><h2>待办</h2><ul class="todos">')
    r.todos.forEach((t) => {
      const cls = t.done ? ' class="done"' : ''
      const box = t.done ? '☑' : '☐'
      const due = t.dueDate ? `<em>${esc(t.dueDate)}</em>` : ''
      S.push(`<li${cls}><span class="box">${box}</span>${esc(t.text)}${due}</li>`)
    })
    S.push('</ul></section>')
  }

  if (r.plan) {
    const p = r.plan
    S.push('<section class="block"><h2>行动方案</h2>')
    if (p.goal) S.push(`<p class="kv"><span>目标</span>${esc(p.goal)}</p>`)
    if (p.background) S.push(`<p class="kv"><span>现状</span>${esc(p.background)}</p>`)
    if (p.steps.length) {
      S.push('<table><thead><tr><th>步骤</th><th>具体做法</th><th class="c">负责</th><th class="c">时间</th></tr></thead><tbody>')
      p.steps.forEach((s) =>
        S.push(
          `<tr><td class="nowrap">${esc(s.step)}</td><td>${esc(s.detail)}</td><td class="c">${esc(s.owner || '—')}</td><td class="c nowrap">${esc(s.when || '—')}</td></tr>`
        )
      )
      S.push('</tbody></table>')
    }
    if (p.resources.length) S.push(`<p class="kv"><span>需要</span>${p.resources.map(esc).join('；')}</p>`)
    if (p.risks.length) {
      S.push('<h3>风险</h3><ul class="plain">')
      p.risks.forEach((x) => S.push(`<li>${esc(x)}</li>`))
      S.push('</ul>')
    }
    if (p.metric) S.push(`<p class="kv"><span>验收</span>${esc(p.metric)}</p>`)
    S.push('</section>')
  }

  if (r.insight) {
    S.push(`<section class="block insight"><h2>洞察</h2>${paragraphs(r.insight)}</section>`)
  }

  const body = r.clean || r.transcript
  if (body) {
    S.push(`<section class="block"><h2>${r.clean ? '整理稿' : '原文'}</h2><div class="transcript">${paragraphs(body)}</div></section>`)
  }

  const tags = r.tags.length ? `<div class="tags">${r.tags.map((t) => `<span>${esc(t)}</span>`).join('')}</div>` : ''

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>${esc(r.title || '记录')}</title>
<style>
  :root { --text:#18181b; --mid:#52525b; --weak:#a1a1aa; --line:#e8e8ea; --red:#e60012; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 44px 20px 80px;
    font: 15px/1.75 -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    color: var(--text); background: #f4f4f5;
  }
  .page { max-width: 780px; margin: 0 auto; background: #fff; padding: 52px 56px 60px; border-radius: 14px; }
  h1 { font-size: 26px; line-height: 1.4; margin: 0 0 14px; letter-spacing: -.01em; }
  .meta { color: var(--weak); font-size: 13px; margin-bottom: 6px; }
  .tags { margin: 14px 0 8px; }
  .tags span { display:inline-block; font-size: 12px; color: var(--mid); background:#f4f4f5; border-radius: 5px; padding: 2px 8px; margin: 0 6px 6px 0; }
  hr.top { border: 0; border-top: 2px solid var(--red); width: 36px; margin: 22px 0 30px; }
  .block { margin: 0 0 34px; }
  h2 { font-size: 15px; margin: 0 0 14px; padding-left: 10px; border-left: 3px solid var(--red); letter-spacing: .02em; }
  h3 { font-size: 13.5px; color: var(--mid); margin: 22px 0 8px; font-weight: 600; }
  p { margin: 0 0 12px; }
  .kv { display: grid; grid-template-columns: 62px 1fr; gap: 10px; }
  .kv span { color: var(--weak); font-size: 13px; padding-top: 1px; }
  ol.plain, ul.plain { margin: 0 0 6px; padding-left: 20px; }
  ol.plain li, ul.plain li { margin-bottom: 7px; }
  ul.todos { list-style: none; margin: 0; padding: 0; }
  ul.todos li { display: flex; gap: 9px; align-items: baseline; padding: 7px 0; border-bottom: 1px solid #f4f4f5; }
  ul.todos li.done { color: var(--weak); text-decoration: line-through; }
  ul.todos .box { color: var(--red); font-size: 13px; }
  ul.todos em { margin-left: auto; font-style: normal; font-size: 12px; color: var(--weak); }
  table { width: 100%; border-collapse: collapse; margin: 6px 0 4px; font-size: 13.5px; }
  th { text-align: left; font-weight: 600; color: var(--mid); font-size: 12.5px; padding: 8px 10px; border-bottom: 1px solid var(--line); background: #fafafa; }
  td { padding: 9px 10px; border-bottom: 1px solid #f4f4f5; vertical-align: top; }
  th.c, td.c { text-align: center; white-space: nowrap; }
  td.nowrap { white-space: nowrap; }
  .insight p { color: var(--mid); background: #fafafa; border-radius: 8px; padding: 14px 16px; }
  .transcript { font-size: 14.5px; color: #27272a; }
  .transcript p { margin-bottom: 14px; }
  @media print {
    body { background: #fff; padding: 0; font-size: 12.5px; }
    .page { box-shadow: none; border-radius: 0; padding: 0; max-width: none; }
    .block { break-inside: avoid; }
    h2 { break-after: avoid; }
  }
</style>
</head>
<body>
<div class="page">
  <h1>${esc(r.title || '未命名记录')}</h1>
  <div class="meta">${meta.map(esc).join(' · ')}</div>
  ${tags}
  <hr class="top">
  ${S.join('\n  ')}
</div>
</body>
</html>`
}

export function safeFileName(name: string): string {
  return (name || '未命名').replace(/[\\/:*?"<>|\r\n]/g, '_').slice(0, 80)
}

/** 一键导出多条：按记录各写一个文件，名字前面带日期，排序即时间顺序 */
export function writeBatch(
  dir: string,
  items: { record: RecordItem; projectName: string }[],
  format: 'md' | 'html'
): { ok: boolean; count: number; error?: string } {
  try {
    fs.mkdirSync(dir, { recursive: true })
    let count = 0
    for (const { record, projectName } of items) {
      const d = new Date(record.createdAt)
      const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
      const base = `${stamp}-${safeFileName(record.title)}`
      const ext = format === 'md' ? '.md' : '.html'
      const body = format === 'md' ? recordToMarkdown(record, projectName) : recordToHtml(record, projectName)
      let file = path.join(dir, base + ext)
      let n = 2
      while (fs.existsSync(file)) file = path.join(dir, `${base}-${n++}${ext}`)
      fs.writeFileSync(file, body, 'utf-8')
      count++
    }
    return { ok: true, count }
  } catch (e) {
    return { ok: false, count: 0, error: (e as Error).message }
  }
}
