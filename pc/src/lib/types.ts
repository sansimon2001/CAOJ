// 渲染进程用的类型与全局 API 声明。字段和主进程 store.ts 保持一致。

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
  list(): Promise<RecordItem[]>
  get(id: string): Promise<RecordItem | null>
  update(id: string, patch: Partial<RecordItem>): Promise<RecordItem | null>
  remove(id: string): Promise<boolean>
  audioExists(id: string): Promise<boolean>
  audioBytes(id: string): Promise<ArrayBuffer | null>
  run(id: string, opts?: { force?: boolean }): Promise<RunResult>
  exportMarkdown(id: string): Promise<{ ok: boolean; path?: string; error?: string }>
  markdown(id: string): Promise<string>
  onRecordChanged(cb: (p: { id: string; status: string; error?: string }) => void): () => void
}

declare global {
  interface Window {
    caoji: CaojiApi
  }
}
