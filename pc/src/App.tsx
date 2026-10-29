import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Recorder, type SourceMode } from './lib/recorder'
import type { AsrProvider, LlmProvider, RecordItem, Settings } from './lib/types'
import Detail from './views/Detail'
import SettingsPanel from './views/SettingsPanel'

const SOURCES: { id: SourceMode; label: string; hint: string }[] = [
  { id: 'mic', label: '麦克风', hint: '线下见面、当面沟通' },
  { id: 'system', label: '系统声音', hint: '腾讯会议 / 飞书 / 微信语音 / 网课' },
  { id: 'mix', label: '两者都录', hint: '线上会议：我的声音与对方的声音都收进来' },
]

function fmtClock(ms: number): string {
  const s = Math.floor(ms / 1000)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

function fmtWhen(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  const today = new Date()
  const sameDay =
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate()
  if (sameDay) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const STATUS_TEXT: Record<string, string> = {
  recorded: '待整理',
  transcribing: '转写中',
  analyzing: '整理中',
  done: '已完成',
  failed: '失败',
}

export default function App() {
  const [records, setRecords] = useState<RecordItem[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [tab, setTab] = useState<'record' | 'settings'>('record')

  const [settings, setSettings] = useState<Settings | null>(null)
  const [llmList, setLlmList] = useState<LlmProvider[]>([])
  const [asrList, setAsrList] = useState<AsrProvider[]>([])

  const [source, setSource] = useState<SourceMode>('system')
  const [recording, setRecording] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [level, setLevel] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  const recRef = useRef<Recorder | null>(null)
  const rafRef = useRef<number>(0)

  const refresh = useCallback(async () => {
    const list = await window.caoji.list()
    setRecords(list)
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

  const selected = useMemo(
    () => records.find((r) => r.id === selectedId) ?? null,
    [records, selectedId]
  )

  const tick = useCallback(() => {
    const r = recRef.current
    if (!r) return
    setElapsed(r.elapsedMs())
    setLevel(r.level())
    rafRef.current = requestAnimationFrame(tick)
  }, [])

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
      setTab('record')

      // 配好 Key 就直接跑完整链路；没配就停在「待整理」，用户点按钮再来
      if (settings?.asrApiKey && settings?.llmApiKey) {
        await window.caoji.run(rec.id)
        await refresh()
      }
    } catch (e) {
      setError(readableError(e))
    } finally {
      setBusy(false)
    }
  }

  async function runSelected(id: string, force = false) {
    setBusy(true)
    try {
      const r = await window.caoji.run(id, { force })
      if (!r.ok) setError(r.error ?? '整理失败')
      else setError('')
      await refresh()
    } finally {
      setBusy(false)
    }
  }

  const noKey = settings && (!settings.asrApiKey || !settings.llmApiKey)

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
                {busy ? '准备中…' : '开始录音'}
              </button>
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
                <button
                  className="ghost"
                  onClick={() => recRef.current?.mark()}
                  title="标记重点时间点"
                >
                  标记
                </button>
                <button className="stop-btn" onClick={stopRecording} disabled={busy}>
                  {busy ? '保存中…' : '结束并整理'}
                </button>
              </div>
            </>
          )}
          {error && <p className="err">{error}</p>}
          {noKey && !recording && (
            <p className="tip" onClick={() => setTab('settings')}>
              还没填 API Key，录音后只保存不整理 →
            </p>
          )}
        </section>

        <div className="list-head">
          <span>记录</span>
          <span className="count">{records.length}</span>
        </div>

        <div className="list">
          {records.map((r) => (
            <button
              key={r.id}
              className={`item ${selectedId === r.id && tab === 'record' ? 'on' : ''}`}
              onClick={() => {
                setSelectedId(r.id)
                setTab('record')
              }}
            >
              <div className="item-title">{r.title || '未命名'}</div>
              <div className="item-meta">
                <span>{fmtWhen(r.createdAt)}</span>
                <span className="sep">·</span>
                <span>{fmtClock(r.durationMs)}</span>
                <span className={`badge s-${r.status}`}>{STATUS_TEXT[r.status] ?? r.status}</span>
              </div>
            </button>
          ))}
          {!records.length && <p className="empty-list">还没有记录。选好音源，点上面的按钮开始。</p>}
        </div>

        <footer className="side-foot">
          <button className={`link ${tab === 'settings' ? 'on' : ''}`} onClick={() => setTab('settings')}>
            设置
          </button>
          <button className="link" onClick={() => window.caoji.openDataDir()}>
            数据目录
          </button>
        </footer>
      </aside>

      <main className="main">
        {tab === 'settings' ? (
          <SettingsPanel
            settings={settings}
            llmList={llmList}
            asrList={asrList}
            onSaved={(s) => setSettings(s)}
            onClose={() => setTab('record')}
          />
        ) : selected ? (
          <Detail
            record={selected}
            busy={busy}
            onRun={(force) => runSelected(selected.id, force)}
            onChanged={refresh}
          />
        ) : (
          <div className="placeholder">
            <h1>把听到的，变成能查到的</h1>
            <p>
              左侧选一个音源开始录音。选「系统声音」可以录下腾讯会议、飞书、微信语音、网课
              —— 这些内容在手机上录不到，是 PC 端才有的能力。
            </p>
            <div className="steps">
              <div>
                <b>1</b> 录音
              </div>
              <div>
                <b>2</b> 转写
              </div>
              <div>
                <b>3</b> 摘要与待办
              </div>
            </div>
          </div>
        )}
      </main>
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
