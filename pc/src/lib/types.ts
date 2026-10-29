// 渲染进程用的类型与全局 API 声明。字段和主进程 store.ts 保持一致。

export type SourceMode = 'mic' | 'system' | 'mix'
export type RecordStatus = 'recorded' | 'transcribing' | 'analyzing' | 'done' | 'failed'
export type RecordKind = 'meeting' | 'call' | 'talk' | 'note' | 'memo'

export interface Todo {
  id: string
  text: string
  dueDate: string
  done: boolean
  project: string
}

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
  aliases: string[]
  keywords: string[]
  color: string
  kind: string
  note: string
  createdAt: string
  archived: boolean
}

export interface CalEvent {
  id: string
  title: string
  date: string
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
  project: string
  projectId: string
  projectConfidence: number
  projectEvidence: string
  kind: RecordKind
  tags: string[]
  clean: string
  minutes: Minutes | null
  plan: Plan | null
  outline: { at: string; text: string }[]
  todos: Todo[]
  status: RecordStatus
  error: string
  guardNote: string
  audioFile: string
  audioBytes: number
  lang: string
  marks: number[]
  analyzedAt: string
  importedFrom: string
}

export interface Settings {
  llmProviderId: string
  llmBaseUrl: string
  llmModel: string
  asrProviderId: string
  asrUrl: string
  asrModel: string
  llmApiKey: string
  asrApiKey: string
  language: string
  hotwords: string
  keepAudio: boolean
  dataDir: string
}

export interface LlmProvider {
  id: string
  name: string
  base: string
  models: string[]
  costNote: string
  keyHint: string
}

export interface AsrProvider {
  id: string
  name: string
  url: string
  model: string
  costNote: string
  requestLangs: string[]
  acceptsAuto: boolean
  supportsPrompt: boolean
  dialectNote: string
}

export interface SearchHit {
  record: RecordItem
  field: string
  snippet: string
}

export interface RunResult {
  ok: boolean
  error?: string
  needsReview?: boolean
  guardNote?: string
  note?: string
  record?: RecordItem
}

export interface CaojiApi {
  info(): Promise<{ version: string; dataDir: string; dev: boolean }>
  openDataDir(): Promise<void>
  getSettings(): Promise<{ settings: Settings; llm: LlmProvider[]; asr: AsrProvider[] }>
  saveSettings(patch: Partial<Settings>): Promise<Settings>
  testLlm(): Promise<{ ok: boolean; message: string }>

  saveAudio(
    bytes: ArrayBuffer,
    meta: { durationMs: number; source: SourceMode; marks: number[] }
  ): Promise<RecordItem>
  createFromText(text: string, title?: string): Promise<RecordItem | null>
  list(): Promise<RecordItem[]>
  get(id: string): Promise<RecordItem | null>
  update(id: string, patch: Partial<RecordItem>): Promise<RecordItem | null>
  remove(id: string): Promise<boolean>
  audioExists(id: string): Promise<boolean>
  audioBytes(id: string): Promise<ArrayBuffer | null>
  setTranscript(
    id: string,
    text: string,
    meta?: { durationMs?: number; lang?: string; outline?: { at: string; text: string }[] }
  ): Promise<RecordItem | null>

  importPick(): Promise<string[]>
  importRun(paths: string[]): Promise<{
    ok: boolean
    created: RecordItem[]
    skipped: { file: string; reason: string }[]
  }>

  transcribe(id: string): Promise<{ ok: boolean; text?: string; error?: string; usedLang?: string }>
  asrChunk(
    bytes: ArrayBuffer,
    fileName: string,
    mime: string
  ): Promise<{ ok: boolean; text: string; error: string; usedLang: string }>

  analyze(id: string): Promise<RunResult>
  makeMinutes(id: string): Promise<{ ok: boolean; minutes?: Minutes; error?: string; note?: string }>
  makePlan(id: string): Promise<{ ok: boolean; plan?: Plan; error?: string; note?: string }>
  cleanText(
    id: string,
    opts?: { ai?: boolean }
  ): Promise<{ ok: boolean; text?: string; error?: string; stats?: unknown; aiNote?: string }>

  search(q: string): Promise<SearchHit[]>

  listProjects(): Promise<Project[]>
  saveProject(p: Partial<Project> & { name: string }): Promise<Project>
  removeProject(id: string): Promise<boolean>

  listEvents(): Promise<CalEvent[]>
  saveEvent(e: Partial<CalEvent> & { title: string; date: string }): Promise<CalEvent>
  removeEvent(id: string): Promise<boolean>

  exportRecord(id: string, format: 'md' | 'html'): Promise<{ ok: boolean; path?: string; error?: string }>
  exportBatch(
    ids: string[],
    format: 'md' | 'html'
  ): Promise<{ ok: boolean; count: number; error?: string }>
  markdownOf(id: string): Promise<string>
  copyText(text: string): Promise<boolean>

  onRecordChanged(cb: (p: { id: string; status: string; error?: string }) => void): () => void
}

declare global {
  interface Window {
    caoji: CaojiApi
  }
}
