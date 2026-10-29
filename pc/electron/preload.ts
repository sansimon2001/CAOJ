// 预加载：只暴露明确的几个方法，页面拿不到 Node 与 ipcRenderer 本体。

import { contextBridge, ipcRenderer } from 'electron'

const api = {
  info: () => ipcRenderer.invoke('app:info'),
  openDataDir: () => ipcRenderer.invoke('app:open-data-dir'),

  getSettings: () => ipcRenderer.invoke('settings:get'),
  saveSettings: (patch: unknown) => ipcRenderer.invoke('settings:save', patch),
  testLlm: () => ipcRenderer.invoke('settings:test-llm'),

  saveAudio: (bytes: ArrayBuffer, meta: unknown) => ipcRenderer.invoke('record:save-audio', bytes, meta),
  list: () => ipcRenderer.invoke('record:list'),
  get: (id: string) => ipcRenderer.invoke('record:get', id),
  update: (id: string, patch: unknown) => ipcRenderer.invoke('record:update', id, patch),
  remove: (id: string) => ipcRenderer.invoke('record:delete', id),
  audioExists: (id: string) => ipcRenderer.invoke('record:audio-exists', id),
  audioBytes: (id: string) => ipcRenderer.invoke('record:audio-bytes', id),
  run: (id: string, opts?: unknown) => ipcRenderer.invoke('ai:run', id, opts),

  exportMarkdown: (id: string) => ipcRenderer.invoke('record:export-markdown', id),
  markdown: (id: string) => ipcRenderer.invoke('record:markdown', id),

  /** 返回取消订阅函数，组件卸载时务必调用，否则热更新会叠加监听 */
  onRecordChanged: (cb: (p: { id: string; status: string; error?: string }) => void) => {
    const h = (_e: unknown, p: { id: string; status: string; error?: string }) => cb(p)
    ipcRenderer.on('record:changed', h)
    return () => ipcRenderer.removeListener('record:changed', h)
  },
}

contextBridge.exposeInMainWorld('caoji', api)

export type CaojiApi = typeof api
