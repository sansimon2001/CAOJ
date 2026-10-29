import { useMemo, useState } from 'react'
import type { Project, RecordItem } from '../lib/types'
import { KIND_LABEL, fmtDuration, fmtWhen, relativeDue } from '../lib/format'
import Modal from '../components/Modal'

interface Props {
  records: RecordItem[]
  projects: Project[]
  busy: boolean
  onRefresh: () => Promise<RecordItem[]>
  onOpenRecord: (id: string) => void
}

interface Draft {
  id?: string
  name: string
  aliases: string
  keywords: string
  kind: string
  note: string
}

const EMPTY: Draft = { name: '', aliases: '', keywords: '', kind: '客户', note: '' }

export default function ProjectsView({ records, projects, busy, onRefresh, onOpenRecord }: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draft, setDraft] = useState<Draft | null>(null)
  const [formErr, setFormErr] = useState('')
  const [msg, setMsg] = useState('')

  const stats = useMemo(() => {
    const map = new Map<string, { count: number; minutes: number; openTodos: number; last: string }>()
    for (const r of records) {
      const key = r.projectId || ''
      const cur = map.get(key) ?? { count: 0, minutes: 0, openTodos: 0, last: '' }
      cur.count++
      cur.minutes += r.durationMs || 0
      cur.openTodos += r.todos.filter((t) => !t.done).length
      if (r.createdAt > cur.last) cur.last = r.createdAt
      map.set(key, cur)
    }
    return map
  }, [records])

  const selected = projects.find((p) => p.id === selectedId) ?? null
  const projectRecords = useMemo(
    () => (selected ? records.filter((r) => r.projectId === selected.id) : []),
    [records, selected]
  )
  const projectDuration = useMemo(
    () => projectRecords.reduce((sum, r) => sum + (r.durationMs || 0), 0),
    [projectRecords]
  )
  const openTodos = useMemo(
    () =>
      projectRecords
        .flatMap((r) => r.todos.map((t) => ({ ...t, record: r })))
        .filter((t) => !t.done)
        .sort((a, b) => (a.dueDate || '9999').localeCompare(b.dueDate || '9999')),
    [projectRecords]
  )

  async function save() {
    if (!draft || !draft.name.trim()) return
    // 同名项目会让人在归类时无从选择，直接拦住
    const dup = projects.find((p) => p.name === draft.name.trim() && p.id !== draft.id)
    if (dup) {
      setFormErr('已有同名项目，直接编辑它，或换个名字')
      return
    }
    await window.caoji.saveProject({
      id: draft.id,
      name: draft.name.trim(),
      aliases: splitList(draft.aliases),
      keywords: splitList(draft.keywords),
      kind: draft.kind,
      note: draft.note,
    })
    setDraft(null)
    setFormErr('')
    await onRefresh()
    setMsg('已保存')
    setTimeout(() => setMsg(''), 3000)
  }

  async function remove(p: Project) {
    if (!confirm(`删除项目「${p.name}」？项目下的记录不会被删，只会变成未归类。`)) return
    await window.caoji.removeProject(p.id)
    setSelectedId(null)
    await onRefresh()
  }

  async function exportAll() {
    if (!selected) return
    const ids = projectRecords.map((r) => r.id)
    const out = await window.caoji.exportBatch(ids, 'md')
    setMsg(out.ok ? `已导出 ${out.count} 条到所选目录` : out.error ?? '导出失败')
    setTimeout(() => setMsg(''), 5000)
  }

  const uncategorized = stats.get('')

  return (
    <div className="projects">
      <div className="proj-list">
        <header className="proj-list-head">
          <h1>项目</h1>
          <button className="primary sm" onClick={() => { setFormErr(''); setDraft({ ...EMPTY }) }} disabled={busy}>
            新建
          </button>
        </header>
        <p className="proj-tip">
          建好档案后，整理时会把记录自动归到对应项目：先比对名字与别名，再用原文里的说法作证据，
          证据对不上就留空等你确认。
        </p>

        <div className="proj-cards">
          {projects.map((p) => {
            const s = stats.get(p.id)
            return (
              <button
                key={p.id}
                className={`proj-card ${selectedId === p.id ? 'on' : ''}`}
                onClick={() => setSelectedId(p.id)}
              >
                <span className="proj-dot" style={{ background: p.color }} />
                <div className="proj-card-main">
                  <div className="proj-name">{p.name}</div>
                  <div className="proj-sub">
                    {p.kind || '未分类'} · {s?.count ?? 0} 条记录
                    {s?.openTodos ? ` · ${s.openTodos} 项待办` : ''}
                  </div>
                </div>
              </button>
            )
          })}

          <button
            className={`proj-card muted ${selectedId === '__none__' ? 'on' : ''}`}
            onClick={() => setSelectedId('__none__')}
          >
            <span className="proj-dot" style={{ background: '#d4d4d8' }} />
            <div className="proj-card-main">
              <div className="proj-name">未归类</div>
              <div className="proj-sub">{uncategorized?.count ?? 0} 条记录</div>
            </div>
          </button>
        </div>
      </div>

      <div className="proj-detail">
        {msg && <div className="banner ok-banner">{msg}</div>}

        {!selected && selectedId !== '__none__' && (
          <div className="placeholder small">
            <h1>选一个项目</h1>
            <p>左侧点开项目，可以看到它的全部记录、未完成待办和时间线。</p>
            <p className="weak">没有合适的？点「新建」建一个档案，之后整理时就会自动归类。</p>
          </div>
        )}

        {selectedId === '__none__' && (
          <div className="proj-panel">
            <h1>未归类</h1>
            <p className="proj-desc">
              还没指定项目的记录都在这里。打开任意一条，在标题下方选一个项目即可归类。
            </p>
            <RecordTimeline
              records={records.filter((r) => !r.projectId)}
              onOpen={onOpenRecord}
            />
          </div>
        )}

        {selected && (
          <div className="proj-panel">
            <header className="proj-head">
              <div>
                <h1>
                  <span className="proj-dot inline" style={{ background: selected.color }} />
                  {selected.name}
                </h1>
                <div className="proj-meta">
                  {selected.kind && <span>{selected.kind}</span>}
                  <span>{projectRecords.length} 条记录</span>
                  {projectDuration > 0 && <span>共 {fmtDuration(projectDuration)}</span>}
                  {openTodos.length > 0 && <span>{openTodos.length} 项待办未完成</span>}
                </div>
              </div>
              <div className="proj-actions">
                <button
                  className="ghost sm"
                  onClick={() => {
                    setFormErr('')
                    setDraft({
                      id: selected.id,
                      name: selected.name,
                      aliases: selected.aliases.join('、'),
                      keywords: selected.keywords.join('、'),
                      kind: selected.kind,
                      note: selected.note,
                    })
                  }}
                >
                  编辑
                </button>
                <button className="ghost sm" onClick={exportAll} disabled={!projectRecords.length}>
                  导出全部
                </button>
                <button className="ghost sm danger" onClick={() => remove(selected)}>
                  删除
                </button>
              </div>
            </header>

            {selected.note && <p className="proj-note">{selected.note}</p>}

            {(selected.aliases.length > 0 || selected.keywords.length > 0) && (
              <div className="proj-tags">
                {selected.aliases.map((a) => (
                  <span key={'a' + a} className="tag ghost-tag">
                    别名 · {a}
                  </span>
                ))}
                {selected.keywords.map((k) => (
                  <span key={'k' + k} className="tag">
                    {k}
                  </span>
                ))}
              </div>
            )}

            {openTodos.length > 0 && (
              <section className="block">
                <h2>未完成待办</h2>
                <ul className="todos">
                  {openTodos.map((t) => (
                    <li key={t.id}>
                      <label onClick={() => onOpenRecord(t.record.id)}>
                        <input type="checkbox" checked={false} readOnly />
                        <span className="todo-text">{t.text}</span>
                        {t.dueDate && <span className="due">{relativeDue(t.dueDate)}</span>}
                        <span className="from">{fmtWhen(t.record.createdAt)}</span>
                      </label>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            <RecordTimeline records={projectRecords} onOpen={onOpenRecord} />
          </div>
        )}
      </div>

      {draft && (
        <Modal title={draft.id ? '编辑项目' : '新建项目'} onClose={() => setDraft(null)}>
          <div className="form">
            <label>
              <span>项目名</span>
              <input
                value={draft.name}
                onChange={(e) => setDraft({ ...draft, name: e.target.value })}
                placeholder="如：虞懋府招商"
                autoFocus
              />
            </label>
            <label>
              <span>别名</span>
              <input
                value={draft.aliases}
                onChange={(e) => setDraft({ ...draft, aliases: e.target.value })}
                placeholder="用顿号或逗号分隔，如：虞懋府、虞城项目"
              />
            </label>
            <label>
              <span>线索词</span>
              <input
                value={draft.keywords}
                onChange={(e) => setDraft({ ...draft, keywords: e.target.value })}
                placeholder="只在这个项目里出现的词，如：璟珹、3 号楼"
              />
            </label>
            <label>
              <span>类型</span>
              <input
                value={draft.kind}
                onChange={(e) => setDraft({ ...draft, kind: e.target.value })}
                placeholder="客户 / 内部 / 产品 / 其他"
              />
            </label>
            <label>
              <span>备注</span>
              <textarea
                value={draft.note}
                onChange={(e) => setDraft({ ...draft, note: e.target.value })}
                placeholder="一句话说明这个项目在做什么"
              />
            </label>
            <p className="form-hint">
              别名和线索词越贴近实际叫法，自动归类越准。归属只作建议，界面上随时可以改。
            </p>
            {formErr && <p className="form-err">{formErr}</p>}
            <div className="modal-actions">
              <button className="ghost" onClick={() => setDraft(null)}>
                取消
              </button>
              <button className="primary" onClick={save} disabled={!draft.name.trim()}>
                保存
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

function RecordTimeline({ records, onOpen }: { records: RecordItem[]; onOpen: (id: string) => void }) {
  if (!records.length) return <p className="hint-line">这个项目下还没有记录。</p>
  return (
    <section className="block">
      <h2>记录时间线</h2>
      <ul className="timeline">
        {records.map((r) => (
          <li key={r.id}>
            <button onClick={() => onOpen(r.id)}>
              <span className="tl-date">{fmtWhen(r.createdAt)}</span>
              <span className="tl-title">{r.title || '未命名'}</span>
              <span className="tl-kind">{KIND_LABEL[r.kind] ?? ''}</span>
              {r.minutes && <span className="tl-flag">纪要</span>}
              {r.plan && <span className="tl-flag">方案</span>}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function splitList(s: string): string[] {
  return s
    .split(/[、,，\s]+/)
    .map((x) => x.trim())
    .filter(Boolean)
}
