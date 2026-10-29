import { useMemo, useState } from 'react'
import type { CalEvent, Project, RecordItem } from '../lib/types'
import { addDays, relativeDue, todayYmd, weekdayCN, ymd } from '../lib/format'
import Modal from '../components/Modal'

interface Props {
  records: RecordItem[]
  projects: Project[]
  events: CalEvent[]
  onRefresh: () => Promise<RecordItem[]>
  onOpenRecord: (id: string) => void
}

interface Item {
  kind: 'event' | 'todo' | 'record'
  id: string
  title: string
  time: string
  done: boolean
  recordId: string
  event?: CalEvent
  projectId: string
}

const WEEK = ['一', '二', '三', '四', '五', '六', '日']

export default function ScheduleView({ records, projects, events, onRefresh, onOpenRecord }: Props) {
  const [cursor, setCursor] = useState(() => new Date())
  const [day, setDay] = useState(todayYmd())
  const [draft, setDraft] = useState<{ title: string; date: string; time: string; projectId: string; note: string } | null>(
    null
  )

  /** 把所有「有日期的东西」按天聚在一起：手动日程 + 待办到期 + 会议记录本身 */
  const byDate = useMemo(() => {
    const map = new Map<string, Item[]>()
    const push = (date: string, it: Item) => {
      if (!date) return
      const arr = map.get(date) ?? []
      arr.push(it)
      map.set(date, arr)
    }

    for (const e of events) {
      push(e.date, {
        kind: 'event',
        id: e.id,
        title: e.title,
        time: e.time,
        done: e.done,
        recordId: e.recordId,
        event: e,
        projectId: e.projectId,
      })
    }

    for (const r of records) {
      for (const t of r.todos) {
        if (!t.dueDate) continue
        push(t.dueDate, {
          kind: 'todo',
          id: `${r.id}:${t.id}`,
          title: t.text,
          time: '',
          done: t.done,
          recordId: r.id,
          projectId: r.projectId,
        })
      }
      // 有会议纪要的记录，本身也算一件事，落在录制的当天
      if (r.minutes) {
        const d = new Date(r.createdAt)
        push(ymd(d), {
          kind: 'record',
          id: r.id,
          title: r.title || '未命名会议',
          time: `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`,
          done: true,
          recordId: r.id,
          projectId: r.projectId,
        })
      }
    }

    for (const arr of map.values()) {
      arr.sort((a, b) => (a.time || '99:99').localeCompare(b.time || '99:99'))
    }
    return map
  }, [events, records])

  const grid = useMemo(() => buildGrid(cursor), [cursor])
  const monthLabel = `${cursor.getFullYear()} 年 ${cursor.getMonth() + 1} 月`

  const dayItems = byDate.get(day) ?? []
  const upcoming = useMemo(() => {
    const out: { date: string; items: Item[] }[] = []
    for (let i = 0; i < 14; i++) {
      const d = addDays(todayYmd(), i)
      const items = (byDate.get(d) ?? []).filter((x) => !(x.kind === 'todo' && x.done))
      if (items.length) out.push({ date: d, items })
    }
    return out
  }, [byDate])

  const openCount = useMemo(
    () =>
      records.reduce(
        (n, r) => n + r.todos.filter((t) => !t.done && t.dueDate).length,
        0
      ),
    [records]
  )

  function shiftMonth(delta: number) {
    setCursor(new Date(cursor.getFullYear(), cursor.getMonth() + delta, 1))
  }

  async function saveEvent() {
    if (!draft || !draft.title.trim()) return
    await window.caoji.saveEvent({
      title: draft.title.trim(),
      date: draft.date,
      time: draft.time,
      durationMin: 60,
      projectId: draft.projectId,
      note: draft.note,
    })
    setDraft(null)
    await onRefresh()
  }

  async function toggleEvent(e: CalEvent) {
    await window.caoji.saveEvent({ ...e, done: !e.done })
    await onRefresh()
  }

  async function removeEvent(id: string) {
    await window.caoji.removeEvent(id)
    await onRefresh()
  }

  async function toggleTodo(it: Item) {
    const [recordId, todoId] = it.id.split(':')
    const rec = records.find((r) => r.id === recordId)
    if (!rec) return
    const todos = rec.todos.map((t) => (t.id === todoId ? { ...t, done: !t.done } : t))
    await window.caoji.update(recordId, { todos })
    await onRefresh()
  }

  return (
    <div className="schedule">
      <header className="sched-head">
        <div>
          <h1>日程</h1>
          <div className="sched-meta">
            {openCount > 0 ? `${openCount} 项待办有截止日期` : '暂待到期的待办'}
            {events.length > 0 && ` · ${events.length} 个日程`}
          </div>
        </div>
        <button
          className="primary sm"
          onClick={() => setDraft({ title: '', date: day, time: '10:00', projectId: '', note: '' })}
        >
          新建日程
        </button>
      </header>

      <div className="sched-body">
        <section className="cal">
          <div className="cal-bar">
            <button className="cal-nav" onClick={() => shiftMonth(-1)}>
              ‹
            </button>
            <span className="cal-month">{monthLabel}</span>
            <button className="cal-nav" onClick={() => shiftMonth(1)}>
              ›
            </button>
            <button
              className="cal-today"
              onClick={() => {
                setCursor(new Date())
                setDay(todayYmd())
              }}
            >
              回到今天
            </button>
          </div>

          <div className="cal-grid cal-weekdays">
            {WEEK.map((w) => (
              <div key={w} className="cal-wd">
                {w}
              </div>
            ))}
          </div>

          <div className="cal-grid">
            {grid.map((cell) => {
              const items = byDate.get(cell.date) ?? []
              const openItems = items.filter((x) => !(x.kind === 'todo' && x.done))
              const isToday = cell.date === todayYmd()
              return (
                <button
                  key={cell.date}
                  className={`cal-cell ${cell.inMonth ? '' : 'out'} ${cell.date === day ? 'sel' : ''} ${
                    isToday ? 'today' : ''
                  }`}
                  onClick={() => setDay(cell.date)}
                >
                  <span className="cal-num">{cell.dayNum}</span>
                  <span className="cal-dots">
                    {openItems.slice(0, 3).map((it, i) => (
                      <i
                        key={i}
                        className={`dot ${it.kind === 'event' ? 'ev' : it.kind === 'record' ? 'rec' : 'td'}`}
                      />
                    ))}
                  </span>
                  {openItems.length > 0 && <span className="cal-first">{openItems[0].title.slice(0, 6)}</span>}
                </button>
              )
            })}
          </div>
        </section>

        <aside className="day-panel">
          <header className="day-head">
            <h2>
              {day.slice(5).replace('-', ' / ')} 周{weekdayCN(day)}
              {day === todayYmd() && <span className="today-tag">今天</span>}
            </h2>
            <button
              className="tiny"
              onClick={() => setDraft({ title: '', date: day, time: '10:00', projectId: '', note: '' })}
            >
              ＋ 添加
            </button>
          </header>

          {!dayItems.length && <p className="hint-line">这天没有安排。</p>}

          <ul className="day-list">
            {dayItems.map((it) => (
              <li key={it.kind + it.id} className={it.done && it.kind !== 'record' ? 'done' : ''}>
                <span className={`day-kind k-${it.kind}`}>
                  {it.kind === 'event' ? '日程' : it.kind === 'record' ? '会议' : '待办'}
                </span>
                <div className="day-main">
                  <button
                    className="day-title"
                    onClick={() => it.recordId && onOpenRecord(it.recordId)}
                    title={it.recordId ? '打开对应记录' : ''}
                  >
                    {it.title}
                  </button>
                  <span className="day-sub">
                    {it.time || '全天'}
                    {it.projectId && ` · ${projects.find((p) => p.id === it.projectId)?.name ?? ''}`}
                    {it.kind === 'todo' && !it.done && ` · ${relativeDue(day)}`}
                  </span>
                </div>
                {it.kind === 'event' ? (
                  <div className="day-act">
                    <button onClick={() => toggleEvent(it.event!)} title={it.done ? '标为未完成' : '标为完成'}>
                      {it.done ? '↺' : '✓'}
                    </button>
                    <button onClick={() => removeEvent(it.id)} title="删除日程">
                      ×
                    </button>
                  </div>
                ) : it.kind === 'todo' ? (
                  <div className="day-act">
                    <button onClick={() => toggleTodo(it)} title="勾选完成">
                      ✓
                    </button>
                  </div>
                ) : null}
              </li>
            ))}
          </ul>

          <header className="day-head second">
            <h2>接下来两周</h2>
          </header>
          {!upcoming.length && <p className="hint-line">两周内还没有要到期的事。</p>}
          {upcoming.map(({ date, items }) => (
            <div key={date} className="up-row">
              <span className="up-date">
                {date.slice(5)} 周{weekdayCN(date)}
                <em>{relativeDue(date)}</em>
              </span>
              <span className="up-items">
                {items.map((it) => (
                  <button
                    key={it.kind + it.id}
                    className={`up-item k-${it.kind}`}
                    onClick={() => it.recordId && onOpenRecord(it.recordId)}
                  >
                    {it.title}
                  </button>
                ))}
              </span>
            </div>
          ))}
        </aside>
      </div>

      {draft && (
        <Modal title="新建日程" onClose={() => setDraft(null)}>
          <div className="form">
            <label>
              <span>标题</span>
              <input
                value={draft.title}
                onChange={(e) => setDraft({ ...draft, title: e.target.value })}
                placeholder="如：虞懋府招商复盘"
                autoFocus
              />
            </label>
            <div className="form-row">
              <label>
                <span>日期</span>
                <input type="date" value={draft.date} onChange={(e) => setDraft({ ...draft, date: e.target.value })} />
              </label>
              <label>
                <span>时间</span>
                <input type="time" value={draft.time} onChange={(e) => setDraft({ ...draft, time: e.target.value })} />
              </label>
            </div>
            <label>
              <span>所属项目</span>
              <select value={draft.projectId} onChange={(e) => setDraft({ ...draft, projectId: e.target.value })}>
                <option value="">不指定</option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              <span>备注</span>
              <input
                value={draft.note}
                onChange={(e) => setDraft({ ...draft, note: e.target.value })}
                placeholder="可选"
              />
            </label>
            <div className="modal-actions">
              <button className="ghost" onClick={() => setDraft(null)}>
                取消
              </button>
              <button className="primary" onClick={saveEvent} disabled={!draft.title.trim()}>
                保存
              </button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  )
}

/** 生成 6×7 的月历网格，周一开头 */
function buildGrid(cursor: Date): { date: string; dayNum: number; inMonth: boolean }[] {
  const y = cursor.getFullYear()
  const m = cursor.getMonth()
  const first = new Date(y, m, 1)
  // getDay(): 0=周日，转成周一为 0
  const offset = (first.getDay() + 6) % 7
  const start = new Date(y, m, 1 - offset)
  const cells: { date: string; dayNum: number; inMonth: boolean }[] = []
  for (let i = 0; i < 42; i++) {
    const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i)
    cells.push({ date: ymd(d), dayNum: d.getDate(), inMonth: d.getMonth() === m })
  }
  return cells
}
