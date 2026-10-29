import { useState } from 'react'
import type { Minutes, Plan, Project, RecordItem } from '../lib/types'
import { KIND_LABEL, KIND_LIST, SOURCE_LABEL, fmtDate, fmtDuration, fmtWhen, relativeDue } from '../lib/format'

interface Props {
  record: RecordItem
  projects: Project[]
  busy: boolean
  onProcess: (id: string, force?: boolean) => Promise<void>
  onChanged: () => Promise<RecordItem[]>
  onGoSchedule: () => void
}

type TextTab = 'clean' | 'raw' | 'outline'

export default function Detail({ record, projects, busy, onProcess, onChanged, onGoSchedule }: Props) {
  const [msg, setMsg] = useState('')
  const [err, setErr] = useState('')
  const [tab, setTab] = useState<TextTab>('clean')
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState('')
  const [newTodo, setNewTodo] = useState('')
  const [working, setWorking] = useState('')

  const workingStatus = record.status === 'transcribing' || record.status === 'analyzing'
  const hasAudio = !!record.audioFile
  const hasText = record.transcript.trim().length > 0

  function flash(m: string) {
    setMsg(m)
    setErr('')
    setTimeout(() => setMsg(''), 4500)
  }
  function fail(m: string) {
    setErr(m)
    setMsg('')
  }

  async function act(label: string, fn: () => Promise<{ ok: boolean; error?: string }>, okMsg: string) {
    setWorking(label)
    setErr('')
    try {
      const r = await fn()
      if (r.ok) flash(okMsg)
      else if (r.error && r.error !== '已取消') fail(r.error)
      await onChanged()
    } catch (e) {
      fail((e as Error).message)
    } finally {
      setWorking('')
    }
  }

  async function toggleTodo(todoId: string, done: boolean) {
    const todos = record.todos.map((t) => (t.id === todoId ? { ...t, done } : t))
    await window.caoji.update(record.id, { todos })
    await onChanged()
  }

  async function removeTodo(todoId: string) {
    const todos = record.todos.filter((t) => t.id !== todoId)
    await window.caoji.update(record.id, { todos })
    await onChanged()
  }

  async function addTodo() {
    const text = newTodo.trim()
    if (!text) return
    const todos = [
      ...record.todos,
      { id: Math.random().toString(16).slice(2, 10), text, dueDate: '', done: false, project: '' },
    ]
    await window.caoji.update(record.id, { todos })
    setNewTodo('')
    await onChanged()
  }

  async function addToSchedule() {
    const d = new Date(record.createdAt)
    const pad = (n: number) => String(n).padStart(2, '0')
    await window.caoji.saveEvent({
      title: record.title || '未命名记录',
      date: fmtDate(record.createdAt),
      time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
      durationMin: Math.max(30, Math.round((record.durationMs || 3600000) / 60000)),
      projectId: record.projectId,
      recordId: record.id,
      note: '',
      done: false,
    })
    flash('已加入日程')
    onGoSchedule()
  }

  async function saveEdit() {
    await window.caoji.update(record.id, { clean: draft })
    setEditing(false)
    flash('整理稿已保存')
    await onChanged()
  }

  const text = tab === 'raw' ? record.transcript : record.clean || record.transcript

  return (
    <article className="detail">
      <header className="detail-head">
        <div className="detail-top">
          <div className="detail-titlewrap">
            <h1>{record.title || '未命名记录'}</h1>
            <div className="detail-meta">
              <span>{fmtWhen(record.createdAt)}</span>
              <span className="sep">·</span>
              <span>{fmtDuration(record.durationMs)}</span>
              <span className="sep">·</span>
              <span>{SOURCE_LABEL[record.source] ?? record.source}</span>
              {record.importedFrom && (
                <>
                  <span className="sep">·</span>
                  <span className="weak">导入自 {record.importedFrom}</span>
                </>
              )}
              {record.marks.length > 0 && (
                <>
                  <span className="sep">·</span>
                  <span className="weak">重点 {record.marks.length} 处</span>
                </>
              )}
            </div>
          </div>

          <div className="detail-actions">
            <button className="primary sm" onClick={() => onProcess(record.id, hasText)} disabled={busy || workingStatus}>
              {workingStatus ? '处理中…' : hasText ? '重新整理' : hasAudio ? '转写并整理' : '整理'}
            </button>
            <details className="dropdown">
              <summary className="ghost sm">导出</summary>
              <div className="dropdown-menu">
                <button
                  onClick={() =>
                    act('export', () => window.caoji.exportRecord(record.id, 'md'), '已导出 Markdown')
                  }
                >
                  Markdown（.md）
                </button>
                <button
                  onClick={() =>
                    act('export', () => window.caoji.exportRecord(record.id, 'html'), '已导出网页，可直接打印为 PDF')
                  }
                >
                  网页（可打印 PDF）
                </button>
                <button
                  onClick={async () => {
                    const md = await window.caoji.markdownOf(record.id)
                    await window.caoji.copyText(md)
                    flash('已复制整份记录到剪贴板')
                  }}
                >
                  复制全文
                </button>
              </div>
            </details>
            <button
              className="ghost sm danger"
              onClick={async () => {
                if (!confirm('删除这条记录？音频与文字都会删掉，不能恢复。')) return
                await window.caoji.remove(record.id)
                await onChanged()
              }}
            >
              删除
            </button>
          </div>
        </div>

        <div className="detail-tags">
          <select
            className="picker"
            value={record.projectId}
            onChange={(e) => {
              window.caoji.update(record.id, { projectId: e.target.value }).then(onChanged)
            }}
            title="归属项目"
          >
            <option value="">未归类</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>

          <select
            className="picker"
            value={record.kind}
            onChange={(e) => {
              window.caoji.update(record.id, { kind: e.target.value as RecordItem['kind'] }).then(onChanged)
            }}
            title="记录类型"
          >
            {KIND_LIST.map((k) => (
              <option key={k} value={k}>
                {KIND_LABEL[k]}
              </option>
            ))}
          </select>

          {record.tags.map((t) => (
            <span key={t} className="tag">
              {t}
            </span>
          ))}

          {record.projectConfidence > 0 && record.projectConfidence < 1 && record.projectEvidence && (
            <span className="evidence" title={record.projectEvidence}>
              自动归属依据：{record.projectEvidence}
            </span>
          )}
        </div>

        <div className="detail-tools">
          <button
            className="chip"
            disabled={busy || workingStatus || !hasText}
            onClick={() => act('minutes', () => window.caoji.makeMinutes(record.id), '会议纪要已生成')}
          >
            {working === 'minutes' ? '生成中…' : record.minutes ? '重出纪要' : '生成纪要'}
          </button>
          <button
            className="chip"
            disabled={busy || workingStatus || !hasText}
            onClick={() => act('plan', () => window.caoji.makePlan(record.id), '行动方案已生成')}
          >
            {working === 'plan' ? '生成中…' : record.plan ? '重出方案' : '生成方案'}
          </button>
          <button
            className="chip"
            disabled={busy || workingStatus || !hasText}
            onClick={() => act('clean', () => window.caoji.cleanText(record.id), '已按要求整理文字')}
          >
            {working === 'clean' ? '整理中…' : '整理文字'}
          </button>
          <button
            className="chip"
            disabled={busy || workingStatus || !hasText}
            onClick={() =>
              act(
                'cleanAI',
                () => window.caoji.cleanText(record.id, { ai: true }),
                '已用模型精修（长度异常会自动放弃，保留规则整理结果）'
              )
            }
            title="让模型断句、去口头禅、改同音错字；不改写、不总结"
          >
            {working === 'cleanAI' ? '精修中…' : 'AI 精修文字'}
          </button>
          <span className="tool-sep" />
          <button className="chip" onClick={addToSchedule} disabled={busy}>
            加入日程
          </button>
        </div>
      </header>

      {record.status === 'failed' && record.error && (
        <div className="banner err-banner">
          <b>没成功</b>
          <p>{record.error}</p>
          <p className="banner-hint">
            多半是 Key、接口地址或余额的问题，到设置里点一次「测试连接」就能定位。
          </p>
        </div>
      )}

      {workingStatus && (
        <div className="banner progress-banner">
          {record.status === 'transcribing' ? '正在把音频转成文字…' : '正在整理…'}
        </div>
      )}

      {record.guardNote && !workingStatus && (
        <div className={`banner ${/找不到|重合度过低|过短/.test(record.guardNote) ? 'err-banner' : 'guard-banner'}`}>
          <b>原文校验</b>
          <p>{record.guardNote}</p>
          <p className="banner-hint">
            被过滤的待办多半是模型把原文说法改写后对不上。需要的话在待办里手动补一条，比放宽校验更稳。
          </p>
        </div>
      )}

      {msg && <div className="banner ok-banner">{msg}</div>}
      {err && <div className="banner err-banner">{err}</div>}

      {record.minutes && <MinutesBlock m={record.minutes} />}

      {record.summary && (
        <section className="block">
          <h2>{record.minutes ? '摘要' : '概述'}</h2>
          <p className="summary">{record.summary}</p>
        </section>
      )}

      {(record.todos.length > 0 || hasText) && (
        <section className="block">
          <h2>
            待办
            <span className="h2-count">
              {record.todos.filter((t) => !t.done).length}/{record.todos.length}
            </span>
          </h2>
          {record.todos.length > 0 && (
            <ul className="todos">
              {record.todos.map((t) => (
                <li key={t.id} className={t.done ? 'done' : ''}>
                  <label>
                    <input type="checkbox" checked={t.done} onChange={(e) => toggleTodo(t.id, e.target.checked)} />
                    <span className="todo-text">{t.text}</span>
                    {t.dueDate && (
                      <span className={`due ${relativeDue(t.dueDate).startsWith('逾期') ? 'over' : ''}`}>
                        {relativeDue(t.dueDate)}
                      </span>
                    )}
                  </label>
                  <button className="row-x" onClick={() => removeTodo(t.id)} title="删除这条待办">
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="add-row">
            <input
              value={newTodo}
              onChange={(e) => setNewTodo(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') addTodo()
              }}
              placeholder="添加一条待办，回车确认"
            />
            <button className="ghost sm" onClick={addTodo} disabled={!newTodo.trim()}>
              添加
            </button>
          </div>
        </section>
      )}

      {record.plan && <PlanBlock p={record.plan} />}

      {record.insight && (
        <section className="block">
          <h2>洞察</h2>
          <p className="insight">{record.insight}</p>
        </section>
      )}

      {hasText && (
        <section className="block">
          <div className="text-head">
            <div className="tabs">
              {record.clean && (
                <button className={tab === 'clean' ? 'on' : ''} onClick={() => setTab('clean')}>
                  整理稿
                </button>
              )}
              <button className={tab === 'raw' ? 'on' : ''} onClick={() => setTab('raw')}>
                原文
              </button>
              {record.outline.length > 0 && (
                <button className={tab === 'outline' ? 'on' : ''} onClick={() => setTab('outline')}>
                  章节
                </button>
              )}
            </div>
            {tab !== 'outline' && !editing && (
              <button
                className="tiny"
                onClick={() => {
                  setDraft(text)
                  setEditing(true)
                }}
              >
                编辑
              </button>
            )}
            {editing && (
              <div className="edit-actions">
                <button className="tiny" onClick={() => setEditing(false)}>
                  取消
                </button>
                <button className="tiny accent" onClick={saveEdit}>
                  保存到整理稿
                </button>
              </div>
            )}
          </div>

          {tab === 'outline' ? (
            <ul className="outline">
              {record.outline.map((o, i) => (
                <li key={i}>
                  <span className="at">{o.at}</span>
                  <span>{o.text}</span>
                </li>
              ))}
            </ul>
          ) : editing ? (
            <textarea className="edit-area" value={draft} onChange={(e) => setDraft(e.target.value)} />
          ) : (
            <div className="transcript">{renderParas(text, tab === 'raw')}</div>
          )}

          <p className="text-foot">
            共 {text.length} 字
            {record.lang && ` · 识别语言 ${record.lang}`}
            {tab === 'raw' && record.clean && ' · 原文只是参考，导出默认用整理稿'}
          </p>
        </section>
      )}

      {!hasText && !workingStatus && record.status !== 'failed' && (
        <div className="banner">
          {hasAudio
            ? '这条记录只有音频。点右上角「转写并整理」，配好 Key 之后就能出摘要与待办。音频会一直留着，随时可以重转。'
            : '这条记录还没有内容。可以点右上角「整理」，或回到左侧导入文件。'}
        </div>
      )}
    </article>
  )
}

function MinutesBlock({ m }: { m: Minutes }) {
  return (
    <section className="block minutes">
      <h2>会议纪要</h2>
      {(m.topic || m.background) && (
        <div className="kv-list">
          {m.topic && (
            <p className="kv">
              <span>主题</span>
              {m.topic}
            </p>
          )}
          {m.background && (
            <p className="kv">
              <span>背景</span>
              {m.background}
            </p>
          )}
        </div>
      )}

      {m.agenda.length > 0 && (
        <>
          <h3>议题</h3>
          <ol className="plain">
            {m.agenda.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ol>
        </>
      )}

      {m.decisions.length > 0 && (
        <>
          <h3>结论与决议</h3>
          <ul className="plain">
            {m.decisions.map((d, i) => (
              <li key={i}>{d}</li>
            ))}
          </ul>
        </>
      )}

      {m.actions.length > 0 && (
        <>
          <h3>行动项</h3>
          <table className="mini-table">
            <thead>
              <tr>
                <th>事项</th>
                <th className="c">负责</th>
                <th className="c">截止</th>
              </tr>
            </thead>
            <tbody>
              {m.actions.map((a, i) => (
                <tr key={i}>
                  <td>{a.text}</td>
                  <td className="c">{a.owner || '—'}</td>
                  <td className="c">{a.due || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {m.risks.length > 0 && (
        <>
          <h3>分歧与风险</h3>
          <ul className="plain">
            {m.risks.map((r, i) => (
              <li key={i}>{r}</li>
            ))}
          </ul>
        </>
      )}

      {m.next && (
        <p className="kv next">
          <span>下一步</span>
          {m.next}
        </p>
      )}
    </section>
  )
}

function PlanBlock({ p }: { p: Plan }) {
  return (
    <section className="block plan">
      <h2>行动方案</h2>
      {p.goal && (
        <p className="kv">
          <span>目标</span>
          {p.goal}
        </p>
      )}
      {p.background && (
        <p className="kv">
          <span>现状</span>
          {p.background}
        </p>
      )}

      {p.steps.length > 0 && (
        <ol className="steps-list">
          {p.steps.map((s, i) => (
            <li key={i}>
              <div className="step-head">
                <b>{s.step}</b>
                {s.owner && <span className="who">{s.owner}</span>}
                {s.when && <span className="when">{s.when}</span>}
              </div>
              <p>{s.detail}</p>
            </li>
          ))}
        </ol>
      )}

      {p.resources.length > 0 && (
        <p className="kv">
          <span>需要</span>
          {p.resources.join('；')}
        </p>
      )}
      {p.risks.length > 0 && (
        <div className="plan-risks">
          {p.risks.map((r, i) => (
            <p key={i}>· {r}</p>
          ))}
        </div>
      )}
      {p.metric && (
        <p className="kv">
          <span>验收</span>
          {p.metric}
        </p>
      )}
    </section>
  )
}

/** 一段一段拆开渲染。整理稿按空行分段，原文按单换行分（切片处就是这样一行一段） */
function renderParas(text: string, raw: boolean) {
  const parts = raw ? text.split(/\n+/) : text.split(/\n{2,}/)
  return parts.filter((p) => p.trim()).map((p, i) => <p key={i}>{p.trim()}</p>)
}
