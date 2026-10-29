import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Recorder, type SourceMode } from './lib/recorder'
import { runRecord, type Progress } from './lib/pipeline'
import { STATUS_TEXT, fmtClock, fmtWhen } from './lib/format'
import type {
  AsrProvider,
  CalEvent,
  LlmProvider,
  Project,
  RecordItem,
  SearchHit,
  Settings,
} from './lib/types'
import Detail from './views/Detail'
import ProjectsView from './views/ProjectsView'
import ScheduleView from './views/ScheduleView'
import SettingsPanel from './views/SettingsPanel'
import Modal from './components/Modal'

const SOURCES: { id: SourceMode; label: string; hint: string }[] = [
  { id: 'mic', label: '麦克风', hint: '线下见面、当面沟通' },
  { id: 'system', label: '系统声音', hint: '腾讯会议 / 飞书 / 微信语音 / 网课' },
  { id: 'mix', label: '两者都录', hint: '线上会议：我的声音与对方的声音都收进来' },
]

type View = 'records' | 'projects' | 'schedule' | 'settings'

export default function App() {
  const [records, setRecords] = useState<RecordItem[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [events, setEvents] = useState<CalEvent[]>([])

  const [view, setView] = useState<View>('records')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const [settings, setSettings] = useState<Settings | null>(null)
  const [llmList, setLlmList] = useState<LlmProvider[]>([])
  const [asrList, setAsrList] = useState<AsrProvider[]>([])

  const [source, setSource] = useState<SourceMode>('system')
  const [recording, setRecording] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [level, setLevel] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [progress, setProgress] = useState<Progress | null>(null)

  const [q, setQ] = useState('')
  const [hits, setHits] = useState<SearchHit[]>([])

  const [pasteOpen, setPasteOpen] = useState(false)
  const [pasteText, setPasteText] = useState('')

  const recRef = useRef<Recorder | null>(null)
  const rafRef = useRef<number>(0)

  const refresh = useCallback(async () => {
    const [list, ps, evs] = await Promise.all([
      window.caoji.list(),
      window.caoji.listProjects(),
      window.caoji.listEvents(),
    ])
    setRecords(list)
    setProjects(ps)
    setEvents(evs)
    return list
  }, [])

  useEffect(() => {
    ;(async () => {
      const { settings: s, llm, asr } = await window.caoji.getSettings()
      setSettings(s)
      setLlmList(llm)
      setAsrList(asr)
      await refresh()
    })()
  }, [refresh])

  // 主进程推进度时同步刷新（转写中 → 整理中 → 已完成）
  useEffect(() => {
    const off = window.caoji.onRecordChanged(() => {
      refresh()
    })
    return off
  }, [refresh])

  useEffect(() => {
    return () => {
      cancelAnimationFrame(rafRef.current)
      recRef.current?.dispose()
    }
  }, [])

  // 搜索防抖：每敲一个字都走一次 IPC 没必要
  useEffect(() => {
    const kw = q.trim()
    if (!kw) {
      setHits([])
      return
    }
    const t = setTimeout(async () => {
      setHits(await window.caoji.search(kw))
    }, 180)
    return () => clearTimeout(t)
  }, [q])

  const selected = useMemo(() => records.find((r) => r.id === selectedId) ?? null, [records, selectedId])

  const tick = useCallback(() => {
    const r = recRef.current
    if (!r) return
    setElapsed(r.elapsedMs())
    setLevel(r.level())
    rafRef.current = requestAnimationFrame(tick)
  }, [])

  function flash(msg: string) {
    setNotice(msg)
    setTimeout(() => setNotice(''), 4000)
  }

  // ---------- 录音 ----------

  async function startRecording() {
    setError('')
    setBusy(true)
    try {
      const r = new Recorder()
      await r.prepare(source)
      r.start()
      recRef.current = r
      setRecording(true)
      setElapsed(0)
      rafRef.current = requestAnimationFrame(tick)
    } catch (e) {
      setError(readableError(e))
      recRef.current?.dispose()
      recRef.current = null
    } finally {
      setBusy(false)
    }
  }

  async function stopRecording() {
    const r = recRef.current
    if (!r) return
    cancelAnimationFrame(rafRef.current)
    setRecording(false)
    setLevel(0)
    setBusy(true)
    try {
      const { blob, durationMs, marks } = await r.stop()
      recRef.current = null
      if (durationMs < 800) {
        setError('录得太短，没有保存')
        return
      }
      const buf = await blob.arrayBuffer()
      const rec = await window.caoji.saveAudio(buf, { durationMs, source, marks })
      await refresh()
      setSelectedId(rec.id)
      setView('records')

      if (settings?.asrApiKey && settings?.llmApiKey) {
        await process(rec.id)
      } else {
        flash('已保存。填好 Key 后可随时整理。')
      }
    } catch (e) {
      setError(readableError(e))
    } finally {
      setBusy(false)
    }
  }

  /** 跑完整链路，带进度显示 */
  async function process(id: string, force = false) {
    setBusy(true)
    setError('')
    try {
      const out = await runRecord(id, {
        force,
        onProgress: setProgress,
      })
      if (!out.ok) setError(out.error ?? '处理失败')
      else if (out.guardNote) flash(out.guardNote)
      await refresh()
    } catch (e) {
      setError(readableError(e))
    } finally {
      setProgress(null)
      setBusy(false)
    }
  }

  // ---------- 导入 ----------

  async function doImport() {
    setError('')
    const paths = await window.caoji.importPick()
    if (!paths?.length) return
    setBusy(true)
    setProgress({ stage: 'decode', current: 0, total: 0, note: `正在导入 ${paths.length} 个文件` })
    try {
      const out = await window.caoji.importRun(paths)
      await refresh()
      if (out.created.length) {
        setSelectedId(out.created[0].id)
        setView('records')
      }
      if (out.skipped.length) {
        setError(`${out.created.length} 个已导入，${out.skipped.length} 个跳过：${out.skipped[0].reason}`)
      } else {
        flash(`已导入 ${out.created.length} 个文件`)
      }
      // 配好 Key 就顺手把第一个跑完，其余留在待整理
      if (out.created.length && settings?.asrApiKey && settings?.llmApiKey) {
        await process(out.created[0].id)
      }
    } catch (e) {
      setError(readableError(e))
    } finally {
      setProgress(null)
      setBusy(false)
    }
  }

  async function createFromPaste() {
    const text = pasteText.trim()
    if (text.length < 4) return
    const rec = await window.caoji.createFromText(text)
    setPasteOpen(false)
    setPasteText('')
    if (!rec) return
    await refresh()
    setSelectedId(rec.id)
    setView('records')
    if (settings?.llmApiKey) await process(rec.id)
  }

  const noKey = settings && (!settings.asrApiKey || !settings.llmApiKey)

  const listItems = useMemo(() => {
    if (!q.trim()) return records.map((r) => ({ rec: r, field: '', snippet: '' }))
    return hits.map((h) => ({ rec: h.record, field: h.field, snippet: h.snippet }))
  }, [q, records, hits])

  return (
    <div className="app">
      <aside className="sidebar">
        <header className="brand">
          <span className="brand-dot" />
          <span className="brand-name">草记</span>
          <span className="brand-sub">PC</span>
        </header>

        <section className="recorder">
          {!recording ? (
            <>
              <div className="sources">
                {SOURCES.map((s) => (
                  <button
                    key={s.id}
                    className={`source ${source === s.id ? 'on' : ''}`}
                    onClick={() => setSource(s.id)}
                    disabled={busy}
                    title={s.hint}
                  >
                    {s.label}
                  </button>
                ))}
              </div>
              <p className="source-hint">{SOURCES.find((s) => s.id === source)?.hint}</p>
              <button className="rec-btn" onClick={startRecording} disabled={busy}>
                <span className="rec-dot" />
                {busy ? '处理中…' : '开始录音'}
              </button>
              <div className="mini-actions">
                <button className="mini" onClick={doImport} disabled={busy} title="导入 mp3 / m4a / wav，或 txt / srt 文稿">
                  导入文件
                </button>
                <button className="mini" onClick={() => setPasteOpen(true)} disabled={busy} title="把聊天记录、随手笔记贴进来整理">
                  粘贴文字
                </button>
              </div>
            </>
          ) : (
            <>
              <div className="rec-live">
                <span className="rec-pulse" />
                <span className="rec-time">{fmtClock(elapsed)}</span>
              </div>
              <div className="meter">
                <div className="meter-fill" style={{ width: `${Math.round(level * 100)}%` }} />
              </div>
              <div className="rec-actions">
                <button className="ghost" onClick={() => recRef.current?.mark()} title="标记重点时间点">
                  标记
                </button>
                <button className="stop-btn" onClick={stopRecording} disabled={busy}>
                  {busy ? '保存中…' : '结束并整理'}
                </button>
              </div>
            </>
          )}
        </section>

        <nav className="nav">
          <button className={`nav-item ${view === 'records' ? 'on' : ''}`} onClick={() => setView('records')}>
            记录
            <span className="nav-count">{records.length}</span>
          </button>
          <button className={`nav-item ${view === 'projects' ? 'on' : ''}`} onClick={() => setView('projects')}>
            项目
            <span className="nav-count">{projects.length}</span>
          </button>
          <button className={`nav-item ${view === 'schedule' ? 'on' : ''}`} onClick={() => setView('schedule')}>
            日程
            <span className="nav-count">{events.length}</span>
          </button>
        </nav>

        <div className="search">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="搜索标题、原文、待办…"
            spellCheck={false}
          />
          {q && (
            <button className="search-clear" onClick={() => setQ('')} title="清空">
              ×
            </button>
          )}
        </div>

        <div className="list">
          {listItems.map(({ rec, field, snippet }) => (
            <button
              key={rec.id}
              className={`item ${selectedId === rec.id && view === 'records' ? 'on' : ''}`}
              onClick={() => {
                setSelectedId(rec.id)
                setView('records')
              }}
            >
              <div className="item-title">
                {rec.project && <span className="item-proj">{rec.project}</span>}
                {rec.title || '未命名'}
              </div>
              {field ? (
                <div className="item-snippet">
                  <span className="hit-field">{field}</span>
                  {snippet}
                </div>
              ) : (
                <div className="item-meta">
                  <span>{fmtWhen(rec.createdAt)}</span>
                  <span className="sep">·</span>
                  <span>{rec.durationMs ? fmtClock(rec.durationMs) : '—'}</span>
                  <span className={`badge s-${rec.status}`}>{STATUS_TEXT[rec.status] ?? rec.status}</span>
                </div>
              )}
            </button>
          ))}
          {!listItems.length && (
            <p className="empty-list">
              {q ? '没有匹配的记录' : '还没有记录。录一段、导入一个文件，或粘贴一段文字。'}
            </p>
          )}
        </div>

        <footer className="side-foot">
          <button className={`link ${view === 'settings' ? 'on' : ''}`} onClick={() => setView('settings')}>
            设置
          </button>
          <button className="link" onClick={() => window.caoji.openDataDir()}>
            数据目录
          </button>
        </footer>
      </aside>

      <main className="main">
        {progress && (
          <div className="progress-bar">
            <span className="spin" />
            <span>{progress.note}</span>
            {progress.total > 0 && (
              <span className="progress-frac">
                {progress.current}/{progress.total}
              </span>
            )}
          </div>
        )}

        {notice && <div className="toast">{notice}</div>}

        {view === 'settings' ? (
          <SettingsPanel
            settings={settings}
            llmList={llmList}
            asrList={asrList}
            onSaved={(s) => setSettings(s)}
            onClose={() => setView('records')}
          />
        ) : view === 'projects' ? (
          <ProjectsView
            records={records}
            projects={projects}
            busy={busy}
            onRefresh={refresh}
            onOpenRecord={(id) => {
              setSelectedId(id)
              setView('records')
            }}
          />
        ) : view === 'schedule' ? (
          <ScheduleView
            records={records}
            projects={projects}
            events={events}
            onRefresh={refresh}
            onOpenRecord={(id) => {
              setSelectedId(id)
              setView('records')
            }}
          />
        ) : selected ? (
          <Detail
            record={selected}
            projects={projects}
            busy={busy}
            onProcess={process}
            onChanged={refresh}
            onGoSchedule={() => setView('schedule')}
          />
        ) : (
          <Placeholder noKey={!!noKey} onImport={doImport} onPaste={() => setPasteOpen(true)} onSettings={() => setView('settings')} />
        )}
      </main>

      {pasteOpen && (
        <Modal title="粘贴文字整理" onClose={() => setPasteOpen(false)}>
          <p className="modal-hint">
            会议纪要、聊天记录、随手笔记都可以贴进来。配好 Key 后会直接出摘要、待办与项目归属。
          </p>
          <textarea
            className="paste-area"
            value={pasteText}
            onChange={(e) => setPasteText(e.target.value)}
            placeholder="在这里粘贴文字…"
            autoFocus
          />
          <div className="modal-actions">
            <button className="ghost" onClick={() => setPasteOpen(false)}>
              取消
            </button>
            <button className="primary" onClick={createFromPaste} disabled={pasteText.trim().length < 4}>
              加入并整理
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function Placeholder({
  noKey,
  onImport,
  onPaste,
  onSettings,
}: {
  noKey: boolean
  onImport: () => void
  onPaste: () => void
  onSettings: () => void
}) {
  return (
    <div className="placeholder">
      <h1>把听到的，变成能查到的</h1>
      <p>
        录一段、导入一个录音文件，或把文字贴进来。选「系统声音」可以录下腾讯会议、飞书、微信语音、网课
        —— 这些内容在手机上录不到，是 PC 端才有的能力。
      </p>
      <div className="placeholder-actions">
        <button className="primary" onClick={onImport}>
          导入录音文件
        </button>
        <button className="ghost" onClick={onPaste}>
          粘贴文字
        </button>
      </div>
      <div className="steps">
        <div>
          <b>1</b> 录音或导入
        </div>
        <div>
          <b>2</b> 转写与整理
        </div>
        <div>
          <b>3</b> 纪要 · 待办 · 方案
        </div>
      </div>
      {noKey && (
        <p className="placeholder-tip" onClick={onSettings}>
          还没填 API Key —— 现在能录音、能存、能看原文，但不会转写与整理。去设置 →
        </p>
      )}
    </div>
  )
}

/** 把浏览器抛的英文异常翻成能照做的中文 */
function readableError(e: unknown): string {
  const msg = (e as Error)?.message ?? String(e)
  if (msg.includes('Permission denied') || msg.includes('NotAllowedError'))
    return '没有拿到权限：麦克风或屏幕采集被拒绝了'
  if (msg.includes('Requested device not found')) return '找不到麦克风设备'
  return msg
}
