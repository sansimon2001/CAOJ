// 草记 PC 端 · 主进程
//
// 分工：录音在渲染进程（loopback 是 Chromium 的能力，主进程拿不到），
// 落盘、调用 ASR / 大模型、读写记录都在主进程（避免 CORS，Key 也不进页面）。
//
// 长音频的切片解码也在渲染进程（主进程没有解码器），所以「转写」这件事是两边配合的：
// 渲染进程切好片，逐片交给主进程的 asr:chunk 转发出去，最后把整段文本写回来。

import {
  app,
  BrowserWindow,
  clipboard,
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
  deleteEvent,
  deleteProject,
  deleteRecord,
  ensureDirs,
  getRecord,
  listEvents,
  listProjects,
  listRecords,
  loadSettings,
  newId,
  recordsDir,
  saveEvent,
  saveProject,
  saveRecord,
  saveSettings,
  searchRecords,
  dataRoot,
  type CalEvent,
  type Minutes,
  type Plan,
  type Project,
  type RecordItem,
  type Settings,
  type SourceMode,
} from './store'
import { ASR_PROVIDERS, LLM_PROVIDERS } from './providers'
import { transcribe, transcribeBuffer } from './asr'
import { analyze, cleanText, makeMinutes, makePlan, testLlm } from './llm'
import { matchProject, verifyEvidence } from './project'
import { cleanTranscript } from './textclean'
import { pickImportFiles, importFiles } from './importer'
import { recordToHtml, recordToMarkdown, safeFileName, writeBatch } from './export'

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
    width: 1240,
    height: 860,
    minWidth: 960,
    minHeight: 640,
    title: '草记',
    backgroundColor: '#F4F4F5',
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
          // 支持 CAOJI_SHOT_CLICK 先点一下再截，便于截到某个视图。
          // 值可以是 CSS 选择器，也可以写 js: 开头的一小段脚本（要按文字定位时更好用）
          const sel = process.env.CAOJI_SHOT_CLICK
          if (sel) {
            const code = sel.startsWith('js:') ? sel.slice(3) : `document.querySelector(${JSON.stringify(sel)})?.click()`
            await w.webContents.executeJavaScript(`(() => { ${code} })()`)
          } else {
            // 默认优先点开「已完成」的那条，截图能看到摘要与待办，而不是空壳记录
            await w.webContents.executeJavaScript(
              `(() => {
                const items = [...document.querySelectorAll('.item')]
                const done = items.find((i) => i.querySelector('.badge')?.textContent === '已完成')
                ;(done || items[0])?.click()
              })()`
            )
          }
          await new Promise((r) => setTimeout(r, 1000))
          // 需要看下半屏时给个滚动量：CAOJI_SHOT_SCROLL=<像素>
          const scroll = Number(process.env.CAOJI_SHOT_SCROLL || 0)
          if (scroll > 0) {
            await w.webContents.executeJavaScript(
              `(() => { const m = document.querySelector('.main'); if (m) m.scrollTop = ${scroll} })()`
            )
            await new Promise((r) => setTimeout(r, 400))
          }
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
  session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
    try {
      const sources = await desktopCapturer.getSources({ types: ['screen'] })
      if (!sources.length) return callback({})
      callback({ video: sources[0], audio: 'loopback' })
    } catch {
      callback({})
    }
  })

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

function defaultTitle(d: Date, source: SourceMode): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const label = source === 'system' ? '系统声音' : source === 'mix' ? '麦克风+系统' : '麦克风'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())} ${label}`
}

function blankRecord(over: Partial<RecordItem> & { id: string }): RecordItem {
  const base: RecordItem = {
    id: over.id,
    createdAt: new Date().toISOString(),
    durationMs: 0,
    source: 'mic',
    title: '',
    transcript: '',
    summary: '',
    insight: '',
    project: '',
    projectId: '',
    projectConfidence: 0,
    projectEvidence: '',
    kind: 'talk',
    tags: [],
    clean: '',
    minutes: null,
    plan: null,
    outline: [],
    todos: [],
    status: 'recorded',
    error: '',
    guardNote: '',
    audioFile: '',
    audioBytes: 0,
    lang: '',
    marks: [],
    analyzedAt: '',
    importedFrom: '',
  }
  return { ...base, ...over }
}

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
    const rec = blankRecord({
      id,
      createdAt: created.toISOString(),
      durationMs: Math.round(meta.durationMs),
      source: meta.source,
      title: defaultTitle(created, meta.source),
      audioFile: file,
      audioBytes: buf.length,
      marks: meta.marks ?? [],
      // 录系统声音基本就是开会；录麦克风通常是当面聊
      kind: meta.source === 'system' || meta.source === 'mix' ? 'meeting' : 'talk',
    })
    saveRecord(rec)
    return rec
  }
)

/** 直接贴一段文字进来整理 —— 会议纪要、聊天记录、随手笔记都适用 */
ipcMain.handle('record:create-from-text', (_e, text: string, title = '') => {
  const body = (text ?? '').trim()
  if (body.length < 4) return null
  const id = newId()
  const rec = blankRecord({
    id,
    title: title.trim() || body.slice(0, 16).replace(/\s+/g, ' '),
    transcript: body,
    kind: 'note',
    tags: ['粘贴'],
  })
  saveRecord(rec)
  return rec
})

// ---------- 导入 ----------

ipcMain.handle('import:pick', async () => pickImportFiles(win))

ipcMain.handle('import:run', async (_e, paths: string[]) => {
  const out = await importFiles(paths ?? [])
  if (out.created.length) send('record:changed', { id: out.created[0].id, status: 'recorded' })
  return out
})

// ---------- 记录读写 ----------

ipcMain.handle('record:list', () => listRecords())

ipcMain.handle('record:get', (_e, id: string) => getRecord(id))

/** 手动指定项目时置信度记 1.0，之后 AI 重新分析不会再覆盖它 */
function applyProject(rec: RecordItem, projectId: string, projects: Project[]): RecordItem {
  if (!projectId) {
    rec.projectId = ''
    rec.project = ''
    rec.projectConfidence = 0
    rec.projectEvidence = ''
    return rec
  }
  const p = projects.find((x) => x.id === projectId)
  rec.projectId = p?.id ?? ''
  rec.project = p?.name ?? ''
  rec.projectConfidence = 1
  rec.projectEvidence = '手动指定'
  return rec
}

ipcMain.handle('record:update', (_e, id: string, patch: Partial<RecordItem>) => {
  const r = getRecord(id)
  if (!r) return null
  const next = { ...r, ...patch, id: r.id }
  // 前端只需传 projectId，名字由这里补齐，避免两处不一致
  if (patch.projectId !== undefined) applyProject(next, patch.projectId, listProjects())
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

// 把音频读成 ArrayBuffer：给渲染进程做解码切片，也是「听原音」的基础
ipcMain.handle('record:audio-bytes', (_e, id: string) => {
  const r = getRecord(id)
  if (!r?.audioFile) return null
  const p = audioPath(r.audioFile)
  if (!fs.existsSync(p)) return null
  const buf = fs.readFileSync(p)
  return buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) as ArrayBuffer
})

/** 切片转写完成后，渲染进程把整段结果写回来 */
ipcMain.handle(
  'record:set-transcript',
  (
    _e,
    id: string,
    text: string,
    meta: { durationMs?: number; lang?: string; outline?: { at: string; text: string }[] }
  ) => {
    const r = getRecord(id)
    if (!r) return null
    r.transcript = text
    if (meta.durationMs && meta.durationMs > 0) r.durationMs = Math.round(meta.durationMs)
    if (meta.lang) r.lang = meta.lang
    if (meta.outline?.length) r.outline = meta.outline
    r.error = ''
    // 标题还停留在「11-03 14:20 麦克风」或导入文件名时，用内容起个更认得出的
    if (!r.title || /^\d{2}-\d{2} \d{2}:\d{2} .+$/.test(r.title) || r.title === path.basename(r.importedFrom || '', path.extname(r.importedFrom || ''))) {
      const head = text.replace(/\s+/g, ' ').trim().slice(0, 16)
      if (head) r.title = head
    }
    saveRecord(r)
    send('record:changed', { id, status: 'transcribed' })
    return r
  }
)

// ---------- 转写（两条路） ----------

/** 整文件转写：短音频走这条，也是切片解码失败时的兜底 */
ipcMain.handle('record:transcribe', async (_e, id: string) => {
  const r = getRecord(id)
  if (!r) return { ok: false, error: '记录不存在' }
  if (!r.audioFile) return { ok: false, error: '这条记录没有音频' }
  if (!fs.existsSync(audioPath(r.audioFile))) return { ok: false, error: '音频文件不在了' }

  r.status = 'transcribing'
  saveRecord(r)
  send('record:changed', { id, status: 'transcribing' })

  const out = await transcribe(audioPath(r.audioFile), loadSettings())
  if (!out.ok) {
    r.status = 'failed'
    r.error = `转写失败：${out.error}`
    saveRecord(r)
    send('record:changed', { id, status: 'failed', error: r.error })
    return { ok: false, error: out.error }
  }

  const next = getRecord(id)!
  next.transcript = out.text
  next.lang = out.usedLang
  next.status = 'recorded'
  next.error = ''
  saveRecord(next)
  send('record:changed', { id, status: 'transcribed' })
  return { ok: true, text: out.text, usedLang: out.usedLang }
})

/** 单片转写：长音频由渲染进程切成 16k 单声道 wav 后一片片送进来 */
ipcMain.handle('asr:chunk', async (_e, bytes: ArrayBuffer, fileName: string, mime: string) => {
  const out = await transcribeBuffer(new Uint8Array(bytes), fileName, mime, loadSettings())
  return { ok: out.ok, text: out.text, error: out.error, usedLang: out.usedLang }
})

// ---------- AI ----------

function fail(id: string, message: string) {
  const r = getRecord(id)
  if (!r) return
  r.status = 'failed'
  r.error = message
  saveRecord(r)
  send('record:changed', { id, status: 'failed', error: message })
}

/** 把 id 标成处理中，让界面能显示进度 */
function markStatus(id: string, status: RecordItem['status']) {
  const r = getRecord(id)
  if (!r) return
  r.status = status
  r.error = ''
  saveRecord(r)
  send('record:changed', { id, status })
}

ipcMain.handle('ai:analyze', async (_e, id: string) => {
  const rec = getRecord(id)
  if (!rec) return { ok: false, error: '记录不存在' }
  if (rec.transcript.trim().length < 4) return { ok: false, error: '还没有转写内容' }

  markStatus(id, 'analyzing')
  const s = loadSettings()
  const projects = listProjects().filter((p) => !p.archived)
  const an = await analyze(rec.transcript, s, { projectNames: projects.map((p) => p.name) })
  if (!an.ok || !an.result) {
    fail(id, `整理失败：${an.error ?? '未知错误'}`)
    return { ok: false, error: an.error }
  }

  const r = getRecord(id)!
  const a = an.result
  r.title = a.title || r.title
  r.summary = a.summary
  r.insight = a.insight
  r.kind = a.kind
  r.tags = a.tags
  r.todos = a.todos.map((t) => ({
    id: crypto.randomBytes(4).toString('hex'),
    text: t.text,
    dueDate: t.due,
    done: false,
    project: '',
  }))

  // 项目归属：AI 猜的必须能在原文找到证据，否则退回本地打分；置信度 1.0 表示用户手动指定，不动
  if (r.projectConfidence < 1) {
    let assigned = false
    if (a.project) {
      const hit = projects.find((p) => p.name === a.project || p.aliases.includes(a.project))
      if (hit && verifyEvidence(r.transcript, a.projectEvidence)) {
        r.projectId = hit.id
        r.project = hit.name
        r.projectConfidence = 0.9
        r.projectEvidence = a.projectEvidence.slice(0, 80)
        assigned = true
      }
    }
    if (!assigned) {
      const m = matchProject(r.transcript, projects)
      if (m) {
        r.projectId = m.projectId
        r.project = m.projectName
        r.projectConfidence = m.confidence
        r.projectEvidence = m.evidence
      }
    }
  }

  r.status = 'done'
  r.error = ''
  r.guardNote = an.guardNote ?? ''
  r.analyzedAt = new Date().toISOString()
  saveRecord(r)
  send('record:changed', { id, status: 'done' })

  return { ok: true, needsReview: an.needsReview, guardNote: an.guardNote, note: an.note, record: r }
})

ipcMain.handle('ai:minutes', async (_e, id: string) => {
  const rec = getRecord(id)
  if (!rec) return { ok: false, error: '记录不存在' }
  if (rec.transcript.trim().length < 20) return { ok: false, error: '内容太短，不够整理纪要' }

  markStatus(id, 'analyzing')
  const out = await makeMinutes(rec.transcript, loadSettings())
  if (!out.ok || !out.minutes) {
    const r = getRecord(id)!
    r.status = rec.summary ? 'done' : 'recorded'
    saveRecord(r)
    send('record:changed', { id, status: r.status })
    return { ok: false, error: out.error }
  }

  const r = getRecord(id)!
  r.minutes = out.minutes as Minutes
  r.status = 'done'
  r.error = ''
  saveRecord(r)
  send('record:changed', { id, status: 'done' })
  return { ok: true, minutes: out.minutes, note: out.note }
})

ipcMain.handle('ai:plan', async (_e, id: string) => {
  const rec = getRecord(id)
  if (!rec) return { ok: false, error: '记录不存在' }
  if (rec.transcript.trim().length < 20) return { ok: false, error: '内容太短，不够生成方案' }

  markStatus(id, 'analyzing')
  const out = await makePlan(rec.transcript, loadSettings(), { projectName: rec.project })
  if (!out.ok || !out.plan) {
    const r = getRecord(id)!
    r.status = rec.summary ? 'done' : 'recorded'
    saveRecord(r)
    send('record:changed', { id, status: r.status })
    return { ok: false, error: out.error }
  }

  const r = getRecord(id)!
  r.plan = out.plan as Plan
  r.status = 'done'
  r.error = ''
  saveRecord(r)
  send('record:changed', { id, status: 'done' })
  return { ok: true, plan: out.plan, note: out.note }
})

/** 文字整理：默认走规则（免费、瞬时），ai=true 时再让模型精修一遍 */
ipcMain.handle('text:clean', async (_e, id: string, opts: { ai?: boolean } = {}) => {
  const rec = getRecord(id)
  if (!rec) return { ok: false, error: '记录不存在' }
  if (rec.transcript.trim().length < 4) return { ok: false, error: '还没有可整理的文本' }

  const rule = cleanTranscript(rec.transcript)
  let text = rule.text
  let aiNote = ''

  if (opts.ai) {
    const out = await cleanText(rec.transcript, loadSettings())
    if (!out.ok) {
      return { ok: false, error: out.error, text: rule.text, stats: rule.stats }
    }
    text = out.text
    aiNote = '已用模型精修'
  }

  const r = getRecord(id)!
  r.clean = text
  saveRecord(r)
  send('record:changed', { id, status: r.status })
  return { ok: true, text, stats: rule.stats, aiNote }
})

// ---------- 搜索 ----------

ipcMain.handle('search', (_e, q: string) => searchRecords(q))

// ---------- 项目 ----------

ipcMain.handle('project:list', () => listProjects())

ipcMain.handle('project:save', (_e, p: Partial<Project> & { name: string }) => saveProject(p))

ipcMain.handle('project:delete', (_e, id: string) => {
  deleteProject(id)
  return true
})

// ---------- 日程 ----------

ipcMain.handle('event:list', () => listEvents())

ipcMain.handle('event:save', (_e, e: Partial<CalEvent> & { title: string; date: string }) => saveEvent(e))

ipcMain.handle('event:delete', (_e, id: string) => {
  deleteEvent(id)
  return true
})

// ---------- 导出 ----------

function projectNameOf(rec: RecordItem): string {
  if (rec.project) return rec.project
  const p = listProjects().find((x) => x.id === rec.projectId)
  return p?.name ?? ''
}

ipcMain.handle('export:record', async (_e, id: string, format: 'md' | 'html') => {
  const r = getRecord(id)
  if (!r) return { ok: false, error: '记录不存在' }
  const name = projectNameOf(r)
  const body = format === 'md' ? recordToMarkdown(r, name) : recordToHtml(r, name)
  const ext = format === 'md' ? 'md' : 'html'
  const res = await dialog.showSaveDialog(win!, {
    title: format === 'md' ? '导出为 Markdown' : '导出为网页（可打印为 PDF）',
    defaultPath: `${safeFileName(r.title)}.${ext}`,
    filters: [
      format === 'md'
        ? { name: 'Markdown', extensions: ['md'] }
        : { name: '网页', extensions: ['html', 'htm'] },
    ],
  })
  if (res.canceled || !res.filePath) return { ok: false, error: '已取消' }
  fs.writeFileSync(res.filePath, body, 'utf-8')
  if (format === 'html') shell.openPath(res.filePath)
  return { ok: true, path: res.filePath }
})

/** 批量导出：选一个目录，把当前筛选范围（或全部）各写一份 */
ipcMain.handle('export:batch', async (_e, ids: string[], format: 'md' | 'html') => {
  const all = listRecords()
  const targets = (ids?.length ? all.filter((r) => ids.includes(r.id)) : all).map((r) => ({
    record: r,
    projectName: projectNameOf(r),
  }))
  if (!targets.length) return { ok: false, count: 0, error: '没有可导出的记录' }

  const res = await dialog.showOpenDialog(win!, {
    title: `导出 ${targets.length} 条到目录`,
    properties: ['openDirectory', 'createDirectory'],
  })
  if (res.canceled || !res.filePaths[0]) return { ok: false, count: 0, error: '已取消' }

  const out = writeBatch(res.filePaths[0], targets, format)
  if (out.ok) shell.openPath(res.filePaths[0])
  return out
})

ipcMain.handle('export:markdown-text', (_e, id: string) => {
  const r = getRecord(id)
  return r ? recordToMarkdown(r, projectNameOf(r)) : ''
})

ipcMain.handle('clipboard:write', (_e, text: string) => {
  clipboard.writeText(text ?? '')
  return true
})

// 调试用：确认数据目录位置
ipcMain.handle('debug:records-dir', () => recordsDir())
