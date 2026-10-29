import { useState } from 'react'
import type { RecordItem } from '../lib/types'

interface Props {
  record: RecordItem
  busy: boolean
  onRun: (force: boolean) => void
  onChanged: () => Promise<RecordItem[]>
}

const SOURCE_LABEL: Record<string, string> = {
  mic: '麦克风',
  system: '系统声音',
  mix: '麦克风 + 系统',
}

function fmtDuration(ms: number): string {
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h) return `${h} 小时 ${m} 分`
  if (m) return `${m} 分 ${s} 秒`
  return `${s} 秒`
}

function fmtTime(ms: number): string {
  const s = Math.floor(ms / 1000)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

export default function Detail({ record, busy, onRun, onChanged }: Props) {
  const [showRaw, setShowRaw] = useState(false)
  const [msg, setMsg] = useState('')

  const d = new Date(record.createdAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  const when = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`

  async function toggleTodo(todoId: string, done: boolean) {
    const todos = record.todos.map((t) => (t.id === todoId ? { ...t, done } : t))
    await window.caoji.update(record.id, { todos })
    await onChanged()
  }

  async function exportMd() {
    const r = await window.caoji.exportMarkdown(record.id)
    if (r.ok) setMsg(`已导出到 ${r.path}`)
    else if (r.error && r.error !== '已取消') setMsg(r.error)
  }

  async function remove() {
    if (!confirm('删除这条记录？音频与文字都会删掉，不能恢复。')) return
    await window.caoji.remove(record.id)
    await onChanged()
  }

  const working = record.status === 'transcribing' || record.status === 'analyzing'

  return (
    <article className="detail">
      <header className="detail-head">
        <h1>{record.title || '未命名记录'}</h1>
        <div className="detail-meta">
          <span>{when}</span>
          <span className="sep">·</span>
          <span>{fmtDuration(record.durationMs)}</span>
          <span className="sep">·</span>
          <span>{SOURCE_LABEL[record.source] ?? record.source}</span>
          {record.marks.length > 0 && (
            <>
              <span className="sep">·</span>
              <span className="marks">
                重点 {record.marks.length} 处（{record.marks.map(fmtTime).join(' / ')}）
              </span>
            </>
          )}
        </div>

        <div className="detail-actions">
          <button className="ghost" onClick={() => onRun(true)} disabled={busy || working}>
            {working ? '处理中…' : record.transcript ? '重新整理' : '转写并整理'}
          </button>
          <button className="ghost" onClick={exportMd} disabled={!record.transcript}>
            导出 Markdown
          </button>
          <button className="ghost danger" onClick={remove}>
            删除
          </button>
        </div>
      </header>

      {record.status === 'failed' && record.error && (
        <div className="banner err-banner">
          <b>没成功</b>
          <p>{record.error}</p>
          <p className="banner-hint">多半是 Key、接口地址或余额的问题，到设置里点一次「测试连接」就能定位。</p>
        </div>
      )}

      {working && (
        <div className="banner progress-banner">
          {record.status === 'transcribing' ? '正在把音频转成文字…' : '正在整理摘要与待办…'}
        </div>
      )}

      {msg && <div className="banner ok-banner">{msg}</div>}

      {record.summary && (
        <section className="block">
          <h2>摘要</h2>
          <p className="summary">{record.summary}</p>
        </section>
      )}

      {record.todos.length > 0 && (
        <section className="block">
          <h2>待办</h2>
          <ul className="todos">
            {record.todos.map((t) => (
              <li key={t.id} className={t.done ? 'done' : ''}>
                <label>
                  <input
                    type="checkbox"
                    checked={t.done}
                    onChange={(e) => toggleTodo(t.id, e.target.checked)}
                  />
                  <span className="todo-text">{t.text}</span>
                  {t.dueDate && <span className="due">{t.dueDate}</span>}
                </label>
              </li>
            ))}
          </ul>
        </section>
      )}

      {record.insight && (
        <section className="block">
          <h2>洞察</h2>
          <p className="insight">{record.insight}</p>
        </section>
      )}

      {record.transcript && (
        <section className="block">
          <h2>
            原文
            <button className="tiny" onClick={() => setShowRaw((v) => !v)}>
              {showRaw ? '收起' : '展开'}
            </button>
          </h2>
          {showRaw && <pre className="raw">{record.transcript}</pre>}
          {!showRaw && <p className="raw-peek">{record.transcript.slice(0, 160)}…</p>}
        </section>
      )}

      {!record.transcript && !working && record.status !== 'failed' && (
        <div className="banner">
          这条记录只存了音频。点上面的「转写并整理」，配置好 Key 之后就能出摘要与待办。
        </div>
      )}
    </article>
  )
}
