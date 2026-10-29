// 本地存储 —— 每条记录一个 JSON 文件，音频单独放。
//
// 为什么不用 SQLite：better-sqlite3 要按 Electron 的 ABI 重编译，装一次卡半天；
// 而「一条记录一个文件」天然没有并发覆盖问题，单条写坏也只损失一条，
// 比全量重写一个大 JSON 安全。记录量到几千条时再换库也不迟（结构已经是平的）。

import { app, safeStorage } from 'electron'
import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'

export type SourceMode = 'mic' | 'system' | 'mix'
export type RecordStatus = 'recorded' | 'transcribing' | 'analyzing' | 'done' | 'failed'

export interface Todo {
  id: string
  text: string
  dueDate: string
  done: boolean
  project: string
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
  project: string
  todos: Todo[]
  status: RecordStatus
  error: string
  audioFile: string
  audioBytes: number
  lang: string
  /** 录音中手动标记的时间点（毫秒） */
  marks: number[]
  analyzedAt: string
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
  const next: Settings = { ...cur, ...patch }
  cachedSettings = next

  const onDisk = {
    ...next,
    llmApiKey: encryptSecret(next.llmApiKey),
    asrApiKey: encryptSecret(next.asrApiKey),
  }
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true })
  fs.writeFileSync(settingsPath(), JSON.stringify(onDisk, null, 2), 'utf-8')
  ensureDirs()
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

export function getRecord(id: string): RecordItem | null {
  try {
    return JSON.parse(fs.readFileSync(recordPath(id), 'utf-8')) as RecordItem
  } catch {
    return null
  }
}

export function saveRecord(r: RecordItem): void {
  ensureDirs()
  // 先写临时文件再 rename，避免写到一半断电留下半个 JSON
  const p = recordPath(r.id)
  const tmp = p + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(r, null, 2), 'utf-8')
  fs.renameSync(tmp, p)
}

export function listRecords(): RecordItem[] {
  ensureDirs()
  const out: RecordItem[] = []
  for (const f of fs.readdirSync(recordsDir())) {
    if (!f.endsWith('.json')) continue
    try {
      out.push(JSON.parse(fs.readFileSync(path.join(recordsDir(), f), 'utf-8')) as RecordItem)
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
}

export function audioPath(file: string): string {
  return path.join(audioDir(), file)
}
