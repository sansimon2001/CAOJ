// 预加载：只暴露明确的几个方法，页面拿不到 Node 与 ipcRenderer 本体。

import { contextBridge, ipcRenderer } from 'electron'

const api = {
  info: () => ipcRenderer.invoke('app:info'),
  openDataDir: () => ipcRenderer.invoke('app:open-data-dir'),

  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (patch: unknown) => ipcRenderer.invoke('settings:save', patch),
  testLlm: () => ipcRenderer.invoke('settings:test-llm'),

  saveAudio: (bytes: ArrayBuffer, meta: unknown) => ipcRenderer.invoke('record:save-audio', bytes, meta),
  createFromText: (text: string, title?: string) => ipcRenderer.invoke('record:create-from-text', text, title),
  list: () => ipcRenderer.invoke('record:list'),
  get: (id: string) => ipcRenderer.invoke('record:get', id),
  update: (id: string, patch: unknown) => ipcRenderer.invoke('record:update', id, patch),
  remove: (id: string) => ipcRenderer.invoke('record:delete', id),
  audioExists: (id: string) => ipcRenderer.invoke('record:audio-exists', id),
  audioBytes: (id: string) => ipcRenderer.invoke('record:audio-bytes', id),
  setTranscript: (id: string, text: string, meta?: unknown) =>
    ipcRenderer.invoke('record:set-transcript', id, text, meta),

  importPick: () => ipcRenderer.invoke('import:pick'),
  importRun: (paths: string[]) => ipcRenderer.invoke('import:run', paths),

  transcribe: (id: string) => ipcRenderer.invoke('record:transcribe', id),
  asrChunk: (bytes: ArrayBuffer, fileName: string, mime: string) =>
    ipcRenderer.invoke('asr:chunk', bytes, fileName, mime),

  analyze: (id: string) => ipcRenderer.invoke('ai:analyze', id),
  makeMinutes: (id: string) => ipcRenderer.invoke('ai:minutes', id),
  makePlan: (id: string) => ipcRenderer.invoke('ai:plan', id),
  cleanText: (id: string, opts?: { ai?: boolean }) => ipcRenderer.invoke('text:clean', id, opts),

  search: (q: string) => ipcRenderer.invoke('search', q),

  listProjects: () => ipcRenderer.invoke('project:list'),
  saveProject: (p: unknown) => ipcRenderer.invoke('project:save', p),
  removeProject: (id: string) => ipcRenderer.invoke('project:delete', id),

  listEvents: () => ipcRenderer.invoke('event:list'),
  saveEvent: (e: unknown) => ipcRenderer.invoke('event:save', e),
  removeEvent: (id: string) => ipcRenderer.invoke('event:delete', id),

  exportRecord: (id: string, format: 'md' | 'html') => ipcRenderer.invoke('export:record', id, format),
  exportBatch: (ids: string[], format: 'md' | 'html') => ipcRenderer.invoke('export:batch', ids, format),
  markdownOf: (id: string) => ipcRenderer.invoke('export:markdown-text', id),
  copyText: (text: string) => ipcRenderer.invoke('clipboard:write', text),

  /** 返回取消订阅函数，组件卸载时务必调用，否则热更新会叠加监听 */
  onRecordChanged: (cb: (p: { id: string; status: string; error?: string }) => void) => {
    const h = (_e: unknown, p: { id: string; status: string; error?: string }) => cb(p)
    ipcRenderer.on('record:changed', h)
    return () => ipcRenderer.removeListener('record:changed', h)
  },
}

contextBridge.exposeInMainWorld('caoji', api)

export type CaojiApi = typeof api
