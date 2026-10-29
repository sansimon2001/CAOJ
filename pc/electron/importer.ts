// 文件导入 —— 把录音笔、手机录音、会议软件导出的文件纳进来。
//
// 三件事要做对：
// 1. 音频要原样拷进数据目录，不能只记原路径（用户删了源文件就废了）；
// 2. 时长要读出来，否则列表里全是 00:00，用户分不清哪条是两小时的会；
// 3. 字幕文件（srt / vtt）要剥掉序号与时间轴，否则转写原文里全是时间码。

import fs from 'node:fs'
import path from 'node:path'
import { dialog } from 'electron'
import { audioDir, audioPath, newId, saveRecord, type RecordItem } from './store'

const AUDIO_EXT = ['.mp3', '.m4a', '.wav', '.webm', '.ogg', '.oga', '.opus', '.flac', '.aac', '.amr', '.wma', '.aiff', '.aif', '.caf']
const TEXT_EXT = ['.txt', '.md', '.markdown', '.srt', '.vtt', '.lrc']

export const IMPORT_FILTERS = [
  { name: '音频与文稿', extensions: [...AUDIO_EXT, ...TEXT_EXT].map((e) => e.slice(1)) },
  { name: '音频', extensions: AUDIO_EXT.map((e) => e.slice(1)) },
  { name: '文稿与字幕', extensions: TEXT_EXT.map((e) => e.slice(1)) },
]

export function isAudio(p: string): boolean {
  return AUDIO_EXT.includes(path.extname(p).toLowerCase())
}

export function isText(p: string): boolean {
  return TEXT_EXT.includes(path.extname(p).toLowerCase())
}

/** 读时长；读不出来就返回 0，界面显示「时长未知」，不假装知道 */
async function probeDuration(file: string): Promise<number> {
  try {
    // music-metadata 7 是 CommonJS，可以直接 require（8 以后改成纯 ESM，主进程用不了）
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const mm = require('music-metadata') as typeof import('music-metadata')
    const meta = await mm.parseFile(file, { duration: true, skipCovers: true })
    const sec = meta.format?.duration ?? 0
    return Number.isFinite(sec) ? Math.round(sec * 1000) : 0
  } catch {
    return 0
  }
}

/** srt / vtt / lrc 去时间轴与序号，只留台词 */
export function stripSubtitles(raw: string): string {
  const lines = raw.split(/\r?\n/)
  const out: string[] = []
  let last = ''
  for (const line of lines) {
    const s = line.trim()
    if (!s) continue
    // 1) 纯数字序号  2) 时间轴 00:00:01,200 --> 00:00:03,400  3) vtt 头 WEBVTT
    if (/^\d+$/.test(s)) continue
    if (/^\d{1,2}:\d{2}(:\d{2})?[.,]\d{1,3}\s*-->/.test(s)) continue
    if (/^WEBVTT/i.test(s)) continue
    // lrc 的 [00:12.34] 行内标签
    const cleaned = s.replace(/^\[[\d:.]+\]\s*/, '').replace(/<[^>]+>/g, '').trim()
    if (!cleaned) continue
    // 字幕常把一句话拆成多条，去掉紧邻重复
    if (cleaned === last) continue
    out.push(cleaned)
    last = cleaned
  }
  // 中文台词拼接时补回句读：上一条不以标点结尾就补个逗号，读起来更顺
  return out
    .map((s, i, arr) => {
      if (i === arr.length - 1) return s
      return /[。！？；，、,.!?;:]$/.test(s) ? s : s + '，'
    })
    .join('')
    .replace(/，$/, '。')
}

function titleFromName(file: string): string {
  return path.basename(file, path.extname(file)).slice(0, 60)
}

export interface ImportOutcome {
  ok: boolean
  created: RecordItem[]
  skipped: { file: string; reason: string }[]
}

/**
 * 导入一批文件。
 * 音频：拷进 audio/，建 record（status='recorded'），等转写；
 * 文本：直接写进 transcript，跳过 ASR，可直接整理。
 */
export async function importFiles(paths: string[]): Promise<ImportOutcome> {
  const created: RecordItem[] = []
  const skipped: { file: string; reason: string }[] = []
  fs.mkdirSync(audioDir(), { recursive: true })

  for (const p of paths) {
    try {
      if (!fs.existsSync(p)) {
        skipped.push({ file: p, reason: '文件不存在' })
        continue
      }
      const stat = fs.statSync(p)
      const id = newId()
      const now = new Date()

      if (isAudio(p)) {
        const ext = path.extname(p).toLowerCase() || '.bin'
        const file = `${id}${ext}`
        fs.copyFileSync(p, audioPath(file))
        const durationMs = await probeDuration(p)
        const rec: RecordItem = {
          id,
          createdAt: now.toISOString(),
          durationMs,
          source: 'mic',
          title: titleFromName(p),
          transcript: '',
          summary: '',
          insight: '',
          project: '',
          projectId: '',
          projectConfidence: 0,
          projectEvidence: '',
          kind: 'meeting',
          tags: ['导入'],
          clean: '',
          minutes: null,
          plan: null,
          outline: [],
          todos: [],
          status: 'recorded',
          error: '',
          guardNote: '',
          audioFile: file,
          audioBytes: stat.size,
          lang: '',
          marks: [],
          analyzedAt: '',
          importedFrom: path.basename(p),
        }
        saveRecord(rec)
        created.push(rec)
        continue
      }

      if (isText(p)) {
        const raw = fs.readFileSync(p, 'utf-8')
        const ext = path.extname(p).toLowerCase()
        const text = ext === '.srt' || ext === '.vtt' || ext === '.lrc' ? stripSubtitles(raw) : raw.trim()
        if (text.length < 4) {
          skipped.push({ file: p, reason: '内容为空' })
          continue
        }
        const rec: RecordItem = {
          id,
          createdAt: now.toISOString(),
          durationMs: 0,
          source: 'mic',
          title: titleFromName(p),
          transcript: text,
          summary: '',
          insight: '',
          project: '',
          projectId: '',
          projectConfidence: 0,
          projectEvidence: '',
          kind: 'note',
          tags: ['导入'],
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
          importedFrom: path.basename(p),
        }
        saveRecord(rec)
        created.push(rec)
        continue
      }

      skipped.push({ file: p, reason: '不支持的格式' })
    } catch (e) {
      skipped.push({ file: p, reason: (e as Error).message })
    }
  }

  return { ok: created.length > 0, created, skipped }
}

/** 弹系统选择框。只返回路径，真正的复制与建记录交给 importFiles。 */
export async function pickImportFiles(parent: Electron.BrowserWindow | null): Promise<string[]> {
  const res = parent
    ? await dialog.showOpenDialog(parent, {
        title: '导入录音或文稿',
        properties: ['openFile', 'multiSelections'],
        filters: IMPORT_FILTERS,
      })
    : await dialog.showOpenDialog({
        title: '导入录音或文稿',
        properties: ['openFile', 'multiSelections'],
        filters: IMPORT_FILTERS,
      })
  return res.canceled ? [] : res.filePaths
}
