// 流水线编排 —— 为什么放在渲染进程：切片解码要用 Chromium 的音频能力，
// 主进程没有解码器。所以「切 → 送 → 拼」这半段在这里，摘要/纪要/方案仍在主进程。
//
// 顺序固定：转写 → 保存 → 分析。任何一步失败都保留上一步的成果，
// 用户重跑时不必再花一次转写的钱。

import { msToClock, sliceToWav } from './audiodecode'
import type { RecordItem, RunResult } from './types'

const api = () => window.caoji

export interface Progress {
  stage: 'decode' | 'asr' | 'analyze'
  /** 当前第几片（从 1 开始） */
  current: number
  /** 总共几片 */
  total: number
  note: string
}

export interface RunOptions {
  force?: boolean
  onProgress?: (p: Progress) => void
}

/**
 * 把一条记录从头跑到「有摘要」。
 * - 有音频且还没转写（或 force）：解码切片后逐片转写
 * - 已有文本（粘贴/导入文稿）：直接分析
 */
export async function runRecord(id: string, opts: RunOptions = {}): Promise<RunResult> {
  const rec = await api().get(id)
  if (!rec) return { ok: false, error: '记录不存在' }

  let current = rec
  const needTranscript = (!current.transcript || opts.force) && !!current.audioFile

  if (needTranscript) {
    const t = await transcribeChunked(current, opts.onProgress)
    if (!t.ok) return { ok: false, error: t.error }
    const updated = await api().get(id)
    if (updated) current = updated
  }

  if (!current.transcript || current.transcript.trim().length < 4) {
    return { ok: false, error: '这条记录没有可整理的文本（也没有音频）' }
  }

  opts.onProgress?.({ stage: 'analyze', current: 1, total: 1, note: '正在生成摘要与待办' })
  const out = await api().analyze(id)
  return out
}

export interface TranscribeResult {
  ok: boolean
  error?: string
  chunks?: number
}

/** 逐片转写并拼成整段文本。单片失败重试一次，仍失败就把已完成的部分存下来。 */
export async function transcribeChunked(
  rec: RecordItem,
  onProgress?: (p: Progress) => void
): Promise<TranscribeResult> {
  const bytes = await api().audioBytes(rec.id)
  if (!bytes) return { ok: false, error: '音频文件读不到，可能已被移动或删除' }

  let chunks: { wav: ArrayBuffer; startMs: number }[] | null = null
  try {
    const decoded = await sliceToWav(bytes, 300, (stage) =>
      onProgress?.({ stage: 'decode', current: 0, total: 0, note: stage })
    )
    chunks = decoded.chunks
    // 时长以解码结果为准，比文件头里的更可靠（尤其导入的文件）
    if (decoded.totalMs > 0) {
      onProgress?.({
        stage: 'asr',
        current: 0,
        total: chunks.length,
        note: `共 ${chunks.length} 段，约 ${msToClock(decoded.totalMs)}`,
      })
    }
  } catch (e) {
    // 解码失败（格式特别、文件损坏、超长）就退回整文件上传：短文件通常能过
    onProgress?.({ stage: 'asr', current: 1, total: 1, note: '本地解码失败，改为整段上传' })
    const r = await api().transcribe(rec.id)
    if (!r.ok) {
      return { ok: false, error: `${r.error ?? '转写失败'}（本地解码也失败：${(e as Error).message}）` }
    }
    return { ok: true, chunks: 1 }
  }

  if (!chunks.length) return { ok: false, error: '音频解码后是空的' }

  const parts: string[] = []
  const outline: { at: string; text: string }[] = []
  let lang = ''
  let lastError = ''

  for (let i = 0; i < chunks.length; i++) {
    const c = chunks[i]
    onProgress?.({
      stage: 'asr',
      current: i + 1,
      total: chunks.length,
      note: `第 ${i + 1}/${chunks.length} 段（${msToClock(c.startMs)} 起）`,
    })

    let r = await api().asrChunk(c.wav, `part-${i + 1}.wav`, 'audio/wav')
    // 网络抖动重试一次；额度类错误不必重试，直接抛
    if (!r.ok && !/401|403|429|Key/.test(r.error)) {
      await new Promise((res) => setTimeout(res, 1500))
      r = await api().asrChunk(c.wav, `part-${i + 1}.wav`, 'audio/wav')
    }

    if (!r.ok) {
      lastError = `第 ${i + 1}/${chunks.length} 段转写失败：${r.error}`
      break
    }
    if (r.usedLang) lang = r.usedLang
    const text = r.text.trim()
    if (text) {
      parts.push(text)
      outline.push({ at: msToClock(c.startMs), text: text.slice(0, 28).replace(/\s+/g, ' ') })
    }
  }

  if (!parts.length) {
    return { ok: false, error: lastError || '整段都没有识别出文字（可能全程无人声）' }
  }

  // 有失败但拿到了部分结果：先存下来，让用户能看能改，同时把错误告诉他
  await api().setTranscript(rec.id, parts.join('\n'), { lang, outline })

  if (lastError) {
    return { ok: false, error: `${lastError}。已保存前 ${parts.length} 段的结果，可点「重新转写」补齐。` }
  }
  return { ok: true, chunks: chunks.length }
}
