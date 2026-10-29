// 草记 PC 端 · 主进程
//
// 分工：录音在渲染进程（loopback 是 Chromium 的能力，主进程拿不到），
// 落盘、调用 ASR / 大模型、读写记录都在主进程（避免 CORS，Key 也不进页面）。

import {
  app,
  BrowserWindow,
  desktopCapturer,
  dialog,
  ipcMain,
  session,
  shell,
} from 'electron'
import path from 'node:path'
import fs from 'node:fs'
import crypto from 'node:crypto'

import {
  audioPath,
  deleteRecord,
  ensureDirs,
  getRecord,
  listRecords,
  loadSettings,
  newId,
  recordsDir,
  saveRecord,
  saveSettings,
  dataRoot,
  type RecordItem,
  type Settings,
  type SourceMode,
} from './store'
import { ASR_PROVIDERS, LLM_PROVIDERS } from './providers'
import { transcribe } from './asr'
import { analyze, testLlm } from './llm'

const isDev = process.env.CAOJI_DEV === '1'
const DEV_URL = process.env.CAOJI_DEV_URL || 'http://localhost:5273'

// 让自动化测试能在没有真实点击的情况下播放测试音（也避免用户手动场景下的自动播放拦截）
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

let win: BrowserWindow | null = null

function send(channel: string, payload: unknown) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload)
}

function createWindow() {
  win = new BrowserWindow({
    width: 1180,
    height: 820,
    minWidth: 900,
    minHeight: 620,
    title: '草记',
    backgroundColor: '#F6F6F7',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })

  if (isDev) {
    win.loadURL(DEV_URL)
  } else {
    win.loadFile(path.join(__dirname, '../dist/index.html'))
  }

  // 外链交给系统浏览器，不在应用内开新窗口
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  win.on('closed', () => {
    win = null
  })

  // 调试钩子：CAOJI_SHOT=<png 路径> 时，界面加载完截一张图就退出。
  // 用于改样式后快速核对，不必每次都手动点开看。
  const shot = process.env.CAOJI_SHOT
  if (shot && win) {
    const w = win
    w.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        try {
          // 优先点开「已完成」的那条，截图能看到摘要与待办，而不是空壳记录
          await w.webContents.executeJavaScript(
            `(() => {
              const items = [...document.querySelectorAll('.item')]
              const done = items.find((i) => i.querySelector('.badge')?.textContent === '已完成')
              ;(done || items[0])?.click()
            })()`
          )
          await new Promise((r) => setTimeout(r, 900))
          const img = await w.capturePage()
          fs.writeFileSync(shot, img.toPNG())
          console.log('[shot] 已保存', shot)
        } catch (e) {
          console.error('[shot] 失败', e)
        } finally {
          app.quit()
        }
      }, 1800)
    })
  }

  // 调试钩子：CAOJI_E2E=<js 文件> 时，界面加载完把这段脚本丢进页面执行，跑完就退出。
  // 用来端到端验证「点开始录音 → 录几秒 → 点结束 → 记录落盘」这条链路。
  const e2e = process.env.CAOJI_E2E
  if (e2e && win) {
    const w = win
    w.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        try {
          const code = fs.readFileSync(e2e, 'utf-8')
          const r = await w.webContents.executeJavaScript(code)
          console.log('[e2e] 结果：' + JSON.stringify(r, null, 2))
        } catch (e) {
          console.error('[e2e] 失败', e)
        } finally {
          app.quit()
        }
      }, 1500)
    })
  }
}

app.whenReady().then(() => {
  ensureDirs()

  // 系统内录的关键：由自己给出音视频源，audio:'loopback' 让 Chromium 开 WASAPI 回环。
  // 已实测可录到系统输出（见 spike/），这里不再弹系统选择框。
  // 注：第二个参数 useSystemPicker 只对 macOS 有意义，Electron 31 的类型定义里还没收，
  // Windows 上本来也不弹系统选择器，所以不传。
  session.defaultSession.setDisplayMediaRequestHandler(
    async (_request, callback) => {
      try {
        const sources = await desktopCapturer.getSources({ types: ['screen'] })
        if (!sources.length) return callback({})
        callback({ video: sources[0], audio: 'loopback' })
      } catch {
        callback({})
      }
    }
  )

  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

// ==================== IPC ====================

ipcMain.handle('app:info', () => ({
  version: app.getVersion(),
  dataDir: dataRoot(),
  dev: isDev,
}))

ipcMain.handle('app:open-data-dir', async () => {
  await shell.openPath(dataRoot())
})

// ---------- 设置 ----------

ipcMain.handle('settings:get', () => ({
  settings: loadSettings(),
  llm: LLM_PROVIDERS,
  asr: ASR_PROVIDERS,
}))

ipcMain.handle('settings:save', (_e, patch: Partial<Settings>) => saveSettings(patch))

ipcMain.handle('settings:test-llm', async () => testLlm(loadSettings()))

// ---------- 录音落盘 ----------

ipcMain.handle(
  'record:save-audio',
  async (
    _e,
    bytes: ArrayBuffer,
    meta: { durationMs: number; source: SourceMode; marks: number[] }
  ) => {
    const id = newId()
    const file = `${id}.webm`
    const buf = Buffer.from(bytes)
    fs.writeFileSync(audioPath(file), buf)

    const created = new Date()
    const rec: RecordItem = {
      id,
      createdAt: created.toISOString(),
      durationMs: Math.round(meta.durationMs),
      source: meta.source,
      title: defaultTitle(created, meta.source),
      transcript: '',
      summary: '',
      insight: '',
      project: '',
      todos: [],
      status: 'recorded',
      error: '',
      audioFile: file,
      audioBytes: buf.length,
      lang: '',
      marks: meta.marks ?? [],
      analyzedAt: '',
    }
    saveRecord(rec)
    return rec
  }
)

function defaultTitle(d: Date, source: SourceMode): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const label = source === 'system' ? '系统声音' : source === 'mix' ? '麦克风+系统' : '麦克风'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())} ${label}`
}

// ---------- 记录读写 ----------

ipcMain.handle('record:list', () => listRecords())

ipcMain.handle('record:get', (_e, id: string) => getRecord(id))

ipcMain.handle('record:update', (_e, id: string, patch: Partial<RecordItem>) => {
  const r = getRecord(id)
  if (!r) return null
  const next = { ...r, ...patch, id: r.id }
  saveRecord(next)
  return next
})

ipcMain.handle('record:delete', (_e, id: string) => {
  deleteRecord(id)
  return true
})

ipcMain.handle('record:audio-exists', (_e, id: string) => {
  const r = getRecord(id)
  return !!r?.audioFile && fs.existsSync(audioPath(r.audioFile))
})

// 把音频读成 ArrayBuffer 交给页面：可用于核对录音内容，也是以后「听原音」的基础
ipcMain.handle('record:audio-bytes', (_e, id: string) => {
  const r = getRecord(id)
  if (!r?.audioFile) return null
  const p = audioPath(r.audioFile)
  if (!fs.existsSync(p)) return null
  const buf = fs.readFileSync(p)
  return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) as ArrayBuffer
})

// ---------- AI 流水线 ----------

function fail(id: string, message: string) {
  const r = getRecord(id)
  if (!r) return
  r.status = 'failed'
  r.error = message
  saveRecord(r)
  send('record:changed', { id, status: 'failed', error: message })
}

ipcMain.handle('ai:run', async (_e, id: string, opts: { force?: boolean } = {}) => {
  const rec0 = getRecord(id)
  if (!rec0) return { ok: false, error: '记录不存在' }
  const s = loadSettings()

  // 已转写过就不重复花钱，除非明确要求重跑
  if (!rec0.transcript || opts.force) {
    const r = getRecord(id)!
    r.status = 'transcribing'
    r.error = ''
    saveRecord(r)
    send('record:changed', { id, status: 'transcribing' })

    if (!fs.existsSync(audioPath(rec0.audioFile))) {
      fail(id, '音频文件不在了，无法重新转写')
      return { ok: false, error: '音频文件不在了' }
    }

    const asr = await transcribe(audioPath(rec0.audioFile), s)
    if (!asr.ok) {
      fail(id, `转写失败：${asr.error}`)
      return { ok: false, error: asr.error }
    }
    const r2 = getRecord(id)!
    r2.transcript = asr.text
    r2.lang = asr.usedLang
    // 转写成功就顺手按内容起个标题，比「11-03 14:20 麦克风」有用得多
    if (/^\d{2}-\d{2} \d{2}:\d{2} /.test(r2.title)) {
      r2.title = asr.text.slice(0, 16).replace(/\s+/g, ' ').trim() || r2.title
    }
    saveRecord(r2)
    send('record:changed', { id, status: 'transcribed' })
  }

  const r3 = getRecord(id)!
  if (r3.transcript.trim().length < 4) {
    fail(id, '转写内容太短，无法整理')
    return { ok: false, error: '转写内容太短' }
  }

  const r4 = getRecord(id)!
  r4.status = 'analyzing'
  saveRecord(r4)
  send('record:changed', { id, status: 'analyzing' })

  const an = await analyze(r3.transcript, s)
  if (!an.ok) {
    fail(id, `整理失败：${an.error}`)
    return { ok: false, error: an.error }
  }

  const fin = getRecord(id)!
  if (an.result) {
    fin.title = an.result.title || fin.title
    fin.summary = an.result.summary
    fin.insight = an.result.insight
    fin.todos = an.result.todos.map((t) => ({
      id: crypto.randomBytes(4).toString('hex'),
      text: t.text,
      dueDate: t.due,
      done: false,
      project: '',
    }))
  }
  fin.status = 'done'
  fin.error = ''
  fin.analyzedAt = new Date().toISOString()
  saveRecord(fin)
  send('record:changed', { id, status: 'done' })

  return { ok: true, needsReview: an.needsReview, guardNote: an.guardNote, note: an.note, record: fin }
})

// ---------- 导出 ----------

ipcMain.handle('record:export-markdown', async (_e, id: string) => {
  const r = getRecord(id)
  if (!r) return { ok: false, error: '记录不存在' }

  const md = toMarkdown(r)
  const res = await dialog.showSaveDialog(win!, {
    title: '导出为 Markdown',
    defaultPath: `${r.title.replace(/[\\/:*?"<>|]/g, '_')}.md`,
    filters: [{ name: 'Markdown', extensions: ['md'] }],
  })
  if (res.canceled || !res.filePath) return { ok: false, error: '已取消' }
  fs.writeFileSync(res.filePath, md, 'utf-8')
  return { ok: true, path: res.filePath }
})

function toMarkdown(r: RecordItem): string {
  const lines: string[] = []
  const when = new Date(r.createdAt)
  const pad = (n: number) => String(n).padStart(2, '0')
  lines.push(`# ${r.title}`)
  lines.push('')
  lines.push(
    `> ${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())} ${pad(when.getHours())}:${pad(when.getMinutes())} · ${Math.round(r.durationMs / 1000)} 秒 · ${r.source === 'system' ? '系统声音' : r.source === 'mix' ? '麦克风+系统' : '麦克风'}`
  )
  lines.push('')
  if (r.summary) {
    lines.push('## 摘要')
    lines.push('')
    lines.push(r.summary)
    lines.push('')
  }
  if (r.todos.length) {
    lines.push('## 待办')
    lines.push('')
    for (const t of r.todos) {
      const due = t.dueDate ? `（${t.dueDate}）` : ''
      lines.push(`- [${t.done ? 'x' : ' '}] ${t.text}${due}`)
    }
    lines.push('')
  }
  if (r.insight) {
    lines.push('## 洞察')
    lines.push('')
    lines.push(r.insight)
    lines.push('')
  }
  if (r.transcript) {
    lines.push('## 原文')
    lines.push('')
    lines.push(r.transcript)
    lines.push('')
  }
  return lines.join('\n')
}

ipcMain.handle('record:markdown', (_e, id: string) => {
  const r = getRecord(id)
  return r ? toMarkdown(r) : ''
})

// 调试用：确认数据目录位置
ipcMain.handle('debug:records-dir', () => recordsDir())
