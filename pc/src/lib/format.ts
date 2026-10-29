// 视图共用的格式化与常量

import type { RecordKind } from './types'

export const KIND_LABEL: Record<RecordKind, string> = {
  meeting: '会议',
  call: '通话',
  talk: '面谈',
  note: '随笔',
  memo: '备忘',
}

export const KIND_LIST: RecordKind[] = ['meeting', 'call', 'talk', 'note', 'memo']

export const SOURCE_LABEL: Record<string, string> = {
  mic: '麦克风',
  system: '系统声音',
  mix: '麦克风 + 系统',
}

export const STATUS_TEXT: Record<string, string> = {
  recorded: '待整理',
  transcribing: '转写中',
  analyzing: '整理中',
  done: '已完成',
  failed: '失败',
}

const pad = (n: number) => String(n).padStart(2, '0')

export function fmtClock(ms: number): string {
  const s = Math.floor((ms || 0) / 1000)
  const mm = String(Math.floor(s / 60)).padStart(2, '0')
  const ss = String(s % 60).padStart(2, '0')
  return `${mm}:${ss}`
}

export function fmtDuration(ms: number): string {
  if (!ms) return '—'
  const total = Math.round(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h) return `${h} 小时 ${m} 分`
  if (m) return `${m} 分 ${s} 秒`
  return `${s} 秒`
}

export function fmtWhen(iso: string): string {
  const d = new Date(iso)
  const today = new Date()
  const sameDay =
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate()
  if (sameDay) return `今天 ${pad(d.getHours())}:${pad(d.getMinutes())}`
  const sameYear = d.getFullYear() === today.getFullYear()
  if (sameYear) return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function fmtDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** YYYY-MM-DD（本地时区，不能用 toISOString 那样会被 UTC 拽到前一天） */
export function ymd(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function todayYmd(): string {
  return ymd(new Date())
}

export function addDays(date: string, delta: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + delta)
  return ymd(dt)
}

/** 距离今天还有几天（负数=已过期） */
export function daysFromToday(date: string): number {
  const [y, m, d] = date.split('-').map(Number)
  const target = new Date(y, m - 1, d).getTime()
  const now = new Date()
  const base = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  return Math.round((target - base) / 86400000)
}

export function relativeDue(date: string): string {
  const n = daysFromToday(date)
  if (n === 0) return '今天'
  if (n === 1) return '明天'
  if (n === 2) return '后天'
  if (n === -1) return '昨天'
  if (n < 0) return `逾期 ${-n} 天`
  if (n <= 7) return `${n} 天后`
  return date.slice(5)
}

export function weekdayCN(date: string): string {
  const [y, m, d] = date.split('-').map(Number)
  return ['日', '一', '二', '三', '四', '五', '六'][new Date(y, m - 1, d).getDay()]
}
