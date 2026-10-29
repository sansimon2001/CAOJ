// 本地存储 —— 记录、项目档案、日程三份数据各自落盘。
//
// 为什么不用 SQLite：better-sqlite3 要按 Electron 的 ABI 重编译，装一次卡半天；
// 而「一条记录一个文件」天然没有并发覆盖问题，单条写坏也只损失一条，
// 比全量重写一个大 JSON 安全。记录量到几千条时再换库也不迟（结构已经是平的）。
//
// 项目与日程是「少量、整体读写」的数据，用单个 JSON 数组更省事，不必拆文件。

import { app, safeStorage } from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

export type SourceMode = 'mic' | 'system' | 'mix'
export type RecordStatus = 'recorded' | 'transcribing' | 'analyzing' | 'done' | 'failed'
/** 会议 / 通话 / 面谈 / 随笔 / 备忘 —— 决定详情页默认展示纪要还是摘要 */
export type RecordKind = 'meeting' | 'call' | 'talk' | 'note' | 'memo'

export interface Todo {
  id: string
  text: string
  dueDate: string
  done: boolean
  project: string
}

/** 会议纪要。字段刻意分开，导出时能按小标题排，不用再从一段话里切。 */
export interface Minutes {
  topic: string
  background: string
  agenda: string[]
  decisions: string[]
  actions: { text: string; owner: string; due: string }[]
  risks: string[]
  next: string
  generatedAt: string
}

/** 行动方案。与纪要的区别：纪要是「当时发生了什么」，方案是「接下来怎么做」。 */
export interface Plan {
  goal: string
  background: string
  steps: { step: string; detail: string; owner: string; when: string }[]
  resources: string[]
  risks: string[]
  metric: string
  generatedAt: string
}

export interface Project {
  id: string
  name: string
  /** 别名与简称，用户口中的叫法往往和档案名不一致 */
  aliases: string[]
  /** 线索词：只在这个项目里出现的人名、地名、产品名 */
  keywords: string[]
  color: string
  /** 客户 / 内部 / 产品 / 其他 */
  kind: string
  note: string
  createdAt: string
  archived: boolean
}

export interface CalEvent {
  id: string
  title: string
  /** YYYY-MM-DD */
  date: string
  /** HH:mm，空串表示全天 */
  time: string
  durationMin: number
  projectId: string
  recordId: string
  note: string
  done: boolean
  createdAt: string
}

export interface RecordItem {
  id: string
  createdAt: string
  durationMs: number
  source: SourceMode
  title: string
  transcript: string
  summary: string
  insight: string
  /** 项目名快照，项目被删后仍能显示 */
  project: string
  projectId: string
  projectConfidence: number
  projectEvidence: string
  kind: RecordKind
  tags: string[]
  /** 整理后的文本：去口水词、分段。原文 transcript 永远保留不动 */
  clean: string
  minutes: Minutes | null
  plan: Plan | null
  outline: { at: string; text: string }[]
  todos: Todo[]
  status: RecordStatus
  error: string
  /** 出口校验的说明：可能是「已过滤 N 条无原文依据的待办」这类非阻塞提示 */
  guardNote: string
  audioFile: string
  audioBytes: number
  lang: string
  /** 录音中手动标记的时间点（毫秒） */
  marks: number[]
  analyzedAt: string
  /** 导入时的原始文件名，空表示是应用内录的 */
  importedFrom: string
}

export interface Settings {
  llmProviderId: string
  llmBaseUrl: string
  llmModel: string
  asrProviderId: string
  asrUrl: string
  asrModel: string
  /** 落盘时加密（Windows 走 DPAPI），不存明文 */
  llmApiKey: string
  asrApiKey: string
  language: string
  hotwords: string
  keepAudio: boolean
  dataDir: string
}

const DEFAULT_SETTINGS: Settings = {
  llmProviderId: 'siliconflow',
  llmBaseUrl: 'https://api.siliconflow.cn/v1',
  llmModel: 'Qwen/Qwen2.5-7B-Instruct',
  asrProviderId: 'siliconflow-asr',
  asrUrl: 'https://api.siliconflow.cn/v1/audio/transcriptions',
  asrModel: 'FunAudioLLM/SenseVoiceSmall',
  llmApiKey: '',
  asrApiKey: '',
  language: 'zh',
  hotwords: '',
  keepAudio: true,
  dataDir: '',
}

let cachedSettings: Settings | null = null

export function dataRoot(): string {
  if (cachedSettings?.dataDir) return cachedSettings.dataDir
  return path.join(app.getPath('userData'), 'data')
}

export function recordsDir(): string {
  return path.join(dataRoot(), 'records')
}

export function audioDir(): string {
  return path.join(dataRoot(), 'audio')
}

export function ensureDirs(): void {
  fs.mkdirSync(recordsDir(), { recursive: true })
  fs.mkdirSync(audioDir(), { recursive: true })
}

/** 先写临时文件再 rename：写到一半断电不会留下半个 JSON */
function writeJsonAtomic(file: string, data: unknown): void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const tmp = file + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2), 'utf-8')
  fs.renameSync(tmp, file)
}

function readJsonArray<T>(file: string): T[] {
  try {
    const v = JSON.parse(fs.readFileSync(file, 'utf-8'))
    return Array.isArray(v) ? (v as T[]) : []
  } catch {
    return []
  }
}

// ---------------- 设置 ----------------

function settingsPath(): string {
  return path.join(app.getPath('userData'), 'settings.json')
}

/** 加密后的密文形如 enc:<base64>；解密失败就当空串，不抛错拖垮启动 */
function encryptSecret(plain: string): string {
  if (!plain) return ''
  try {
    if (safeStorage.isEncryptionAvailable()) {
      return 'enc:' + safeStorage.encryptString(plain).toString('base64')
    }
  } catch {
    /* 落到明文 */
  }
  return 'raw:' + plain
}

function decryptSecret(stored: string): string {
  if (!stored) return ''
  if (stored.startsWith('enc:')) {
    try {
      return safeStorage.decryptString(Buffer.from(stored.slice(4), 'base64'))
    } catch {
      return ''
    }
  }
  if (stored.startsWith('raw:')) return stored.slice(4)
  return stored
}

export function loadSettings(): Settings {
  if (cachedSettings) return cachedSettings
  let raw: Partial<Settings> = {}
  try {
    raw = JSON.parse(fs.readFileSync(settingsPath(), 'utf-8'))
  } catch {
    /* 首次运行没有文件 */
  }
  cachedSettings = {
    ...DEFAULT_SETTINGS,
    ...raw,
    llmApiKey: decryptSecret((raw as Record<string, string>).llmApiKey ?? ''),
    asrApiKey: decryptSecret((raw as Record<string, string>).asrApiKey ?? ''),
  }
  return cachedSettings
}

export function saveSettings(patch: Partial<Settings>): Settings {
  const cur = loadSettings()
  const next: Settings = { ...cur, ...patch, dataDir: patch.dataDir ?? cur.dataDir }
  // dataDir 变了要立刻生效，否则目录还是旧路径
  if (patch.dataDir !== undefined) {
    cachedSettings = next
    ensureDirs()
  }
  cachedSettings = next

  const onDisk = {
    ...next,
    llmApiKey: encryptSecret(next.llmApiKey),
    asrApiKey: encryptSecret(next.asrApiKey),
  }
  writeJsonAtomic(settingsPath(), onDisk)
  return next
}

// ---------------- 记录 ----------------

export function newId(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  return `${stamp}-${crypto.randomBytes(3).toString('hex')}`
}

export function recordPath(id: string): string {
  return path.join(recordsDir(), `${id}.json`)
}

/** 老版本记录缺新字段，读出来统一补齐，避免界面到处判空 */
function normalizeRecord(r: Partial<RecordItem>): RecordItem {
  return {
    id: r.id ?? '',
    createdAt: r.createdAt ?? new Date().toISOString(),
    durationMs: r.durationMs ?? 0,
    source: r.source ?? 'mic',
    title: r.title ?? '',
    transcript: r.transcript ?? '',
    summary: r.summary ?? '',
    insight: r.insight ?? '',
    project: r.project ?? '',
    projectId: r.projectId ?? '',
    projectConfidence: r.projectConfidence ?? 0,
    projectEvidence: r.projectEvidence ?? '',
    kind: r.kind ?? 'talk',
    tags: Array.isArray(r.tags) ? r.tags : [],
    clean: r.clean ?? '',
    minutes: r.minutes ?? null,
    plan: r.plan ?? null,
    outline: Array.isArray(r.outline) ? r.outline : [],
    todos: Array.isArray(r.todos)
      ? r.todos.map((t) => ({
          id: t.id ?? crypto.randomBytes(4).toString('hex'),
          text: t.text ?? '',
          dueDate: t.dueDate ?? '',
          done: !!t.done,
          project: t.project ?? '',
        }))
      : [],
    status: r.status ?? 'recorded',
    error: r.error ?? '',
    guardNote: r.guardNote ?? '',
    audioFile: r.audioFile ?? '',
    audioBytes: r.audioBytes ?? 0,
    lang: r.lang ?? '',
    marks: Array.isArray(r.marks) ? r.marks : [],
    analyzedAt: r.analyzedAt ?? '',
    importedFrom: r.importedFrom ?? '',
  }
}

export function getRecord(id: string): RecordItem | null {
  try {
    const raw = JSON.parse(fs.readFileSync(recordPath(id), 'utf-8')) as Partial<RecordItem>
    if (!raw?.id) return null
    return normalizeRecord(raw)
  } catch {
    return null
  }
}

export function saveRecord(r: RecordItem): void {
  ensureDirs()
  writeJsonAtomic(recordPath(r.id), r)
}

export function listRecords(): RecordItem[] {
  ensureDirs()
  const out: RecordItem[] = []
  for (const f of fs.readdirSync(recordsDir())) {
    if (!f.endsWith('.json')) continue
    try {
      const raw = JSON.parse(fs.readFileSync(path.join(recordsDir(), f), 'utf-8')) as Partial<RecordItem>
      if (raw?.id) out.push(normalizeRecord(raw))
    } catch {
      /* 跳过坏文件 */
    }
  }
  out.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
  return out
}

export function deleteRecord(id: string): void {
  const r = getRecord(id)
  try {
    fs.unlinkSync(recordPath(id))
  } catch {
    /* 已不存在 */
  }
  if (r?.audioFile) {
    try {
      fs.unlinkSync(path.join(audioDir(), r.audioFile))
    } catch {
      /* 音频可能已被清理 */
    }
  }
  // 关联日程里的 recordId 置空，不留悬空引用
  const evs = listEvents()
  let touched = false
  for (const e of evs) {
    if (e.recordId === id) {
      e.recordId = ''
      touched = true
    }
  }
  if (touched) writeJsonAtomic(eventsPath(), evs)
}

export function audioPath(file: string): string {
  return path.join(audioDir(), file)
}

// ---------------- 项目档案 ----------------

function projectsPath(): string {
  return path.join(dataRoot(), 'projects.json')
}

const PROJECT_COLORS = ['#e60012', '#12805c', '#2563eb', '#b45309', '#7c3aed', '#0e7490', '#be123c', '#4d7c0f']

export function listProjects(): Project[] {
  const out = readJsonArray<Project>(projectsPath()).map((p) => ({
    id: p.id,
    name: p.name ?? '',
    aliases: Array.isArray(p.aliases) ? p.aliases : [],
    keywords: Array.isArray(p.keywords) ? p.keywords : [],
    color: p.color || PROJECT_COLORS[0],
    kind: p.kind ?? '',
    note: p.note ?? '',
    createdAt: p.createdAt ?? new Date().toISOString(),
    archived: !!p.archived,
  }))
  out.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
  return out
}

export function saveProject(p: Partial<Project> & { name: string }): Project {
  const all = listProjects()
  const idx = p.id ? all.findIndex((x) => x.id === p.id) : -1
  const base: Project =
    idx >= 0
      ? all[idx]
      : {
          id: 'p' + crypto.randomBytes(4).toString('hex'),
          name: '',
          aliases: [],
          keywords: [],
          color: PROJECT_COLORS[all.length % PROJECT_COLORS.length],
          kind: '',
          note: '',
          createdAt: new Date().toISOString(),
          archived: false,
        }
  const next: Project = {
    ...base,
    ...p,
    name: (p.name ?? base.name).trim(),
    aliases: p.aliases ?? base.aliases,
    keywords: p.keywords ?? base.keywords,
  }
  if (idx >= 0) all[idx] = next
  else all.push(next)
  writeJsonAtomic(projectsPath(), all)
  // 项目改名后，记录里的名字快照要跟着改，否则列表还显示旧名
  if (idx >= 0 && base.name !== next.name) {
    for (const r of listRecords()) {
      if (r.projectId === next.id) {
        r.project = next.name
        saveRecord(r)
      }
    }
  }
  return next
}

export function deleteProject(id: string): void {
  writeJsonAtomic(
    projectsPath(),
    listProjects().filter((p) => p.id !== id)
  )
  for (const r of listRecords()) {
    if (r.projectId === id) {
      r.projectId = ''
      r.project = ''
      r.projectConfidence = 0
      r.projectEvidence = ''
      saveRecord(r)
    }
  }
  const evs = listEvents()
  let touched = false
  for (const e of evs) {
    if (e.projectId === id) {
      e.projectId = ''
      touched = true
    }
  }
  if (touched) writeJsonAtomic(eventsPath(), evs)
}

// ---------------- 日程 ----------------

function eventsPath(): string {
  return path.join(dataRoot(), 'events.json')
}

export function listEvents(): CalEvent[] {
  const out = readJsonArray<CalEvent>(eventsPath()).map((e) => ({
    id: e.id ?? 'e' + crypto.randomBytes(4).toString('hex'),
    title: e.title ?? '',
    date: e.date ?? '',
    time: e.time ?? '',
    durationMin: e.durationMin ?? 60,
    projectId: e.projectId ?? '',
    recordId: e.recordId ?? '',
    note: e.note ?? '',
    done: !!e.done,
    createdAt: e.createdAt ?? new Date().toISOString(),
  }))
  out.sort((a, b) => (a.date + (a.time || '00:00') < b.date + (b.time || '00:00') ? -1 : 1))
  return out
}

export function saveEvent(e: Partial<CalEvent> & { title: string; date: string }): CalEvent {
  const all = listEvents()
  const idx = e.id ? all.findIndex((x) => x.id === e.id) : -1
  const base: CalEvent =
    idx >= 0
      ? all[idx]
      : {
          id: 'e' + crypto.randomBytes(4).toString('hex'),
          title: '',
          date: '',
          time: '',
          durationMin: 60,
          projectId: '',
          recordId: '',
          note: '',
          done: false,
          createdAt: new Date().toISOString(),
        }
  const next: CalEvent = { ...base, ...e, title: (e.title ?? base.title).trim(), date: e.date || base.date }
  if (idx >= 0) all[idx] = next
  else all.push(next)
  writeJsonAtomic(eventsPath(), all)
  return next
}

export function deleteEvent(id: string): void {
  writeJsonAtomic(
    eventsPath(),
    listEvents().filter((e) => e.id !== id)
  )
}

// ---------------- 搜索 ----------------

export interface SearchHit {
  record: RecordItem
  /** 命中字段：标题 / 原文 / 摘要 / 待办 / 纪要 */
  field: string
  /** 命中位置前后的一小段，用于列表里显示上下文 */
  snippet: string
}

export function searchRecords(q: string): SearchHit[] {
  const kw = q.trim().toLowerCase()
  if (!kw) return []
  const hits: SearchHit[] = []
  for (const r of listRecords()) {
    const idx = (v: string) => (v ?? '').toLowerCase().indexOf(kw)
    let field = ''
    let snippet = ''
    let pos = -1

    pos = idx(r.title)
    if (pos >= 0) {
      field = '标题'
      snippet = r.title
    }
    if (!field) {
      pos = idx(r.summary)
      if (pos >= 0) {
        field = '摘要'
        snippet = clip(r.summary, pos, kw.length)
      }
    }
    if (!field) {
      pos = idx(r.minutes ? JSON.stringify(r.minutes) : '')
      if (pos >= 0) {
        field = '纪要'
        snippet = clip(JSON.stringify(r.minutes), pos, kw.length)
      }
    }
    if (!field) {
      const t = r.todos.find((x) => x.text.toLowerCase().includes(kw))
      if (t) {
        field = '待办'
        snippet = t.text
      }
    }
    if (!field) {
      pos = idx(r.transcript)
      if (pos >= 0) {
        field = '原文'
        snippet = clip(r.transcript, pos, kw.length)
      }
    }
    if (!field) {
      pos = idx(r.project)
      if (pos >= 0) {
        field = '项目'
        snippet = r.project
      }
    }
    if (field) hits.push({ record: r, field, snippet })
  }
  return hits
}

function clip(text: string, pos: number, len: number): string {
  const start = Math.max(0, pos - 30)
  const end = Math.min(text.length, pos + len + 40)
  return (start > 0 ? '…' : '') + text.slice(start, end).replace(/\s+/g, ' ') + (end < text.length ? '…' : '')
}
