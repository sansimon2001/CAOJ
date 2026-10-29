// 语音转写 —— 移植自安卓 ai/AsrClient.kt 的核心逻辑
//
// 走 Whisper 兼容协议（multipart/form-data + model + language + prompt）。
// 语言因为各服务接受度不同，做了降级：用户选的 → 服务不接受就退回 zh → 仍失败就去掉 language。

import fs from 'node:fs'
import path from 'node:path'
import type { Settings } from './store'
import { asrById } from './providers'

export interface AsrOutcome {
  ok: boolean
  text: string
  error: string
  usedLang: string
}

function guessMime(file: string): string {
  const ext = path.extname(file).toLowerCase()
  if (ext === '.webm') return 'audio/webm'
  if (ext === '.wav') return 'audio/wav'
  if (ext === '.mp3') return 'audio/mpeg'
  if (ext === '.m4a') return 'audio/mp4'
  if (ext === '.ogg') return 'audio/ogg'
  return 'application/octet-stream'
}

/** 选出这个服务商真正能接受的 language 取值；空串表示不传 */
function resolveLang(want: string, provider: ReturnType<typeof asrById>): string {
  if (!want) return ''
  if (want === 'auto') return provider.acceptsAuto ? 'auto' : ''
  if (provider.requestLangs.includes(want)) return want
  // 方言代码（yue/wuu/nan…）不被接受时退回普通话，而不是报错中断
  if (provider.requestLangs.includes('zh')) return 'zh'
  return ''
}

export async function transcribe(file: string, s: Settings): Promise<AsrOutcome> {
  if (!s.asrUrl) return { ok: false, text: '', error: '还没有填写转写接口地址', usedLang: '' }
  if (!s.asrApiKey) return { ok: false, text: '', error: '还没有填写转写 API Key', usedLang: '' }
  if (!fs.existsSync(file)) return { ok: false, text: '', error: `音频文件不存在：${file}`, usedLang: '' }

  const provider = asrById(s.asrProviderId)
  const buf = new Uint8Array(await fs.promises.readFile(file))
  const lang = resolveLang(s.language, provider)

  async function attempt(withLang: string) {
    const form = new FormData()
    form.append('file', new Blob([buf], { type: guessMime(file) }), path.basename(file))
    form.append('model', s.asrModel)
    if (withLang) form.append('language', withLang)
    // 只给支持热词的服务传 prompt，其余传了会被 400
    if (provider.supportsPrompt && s.hotwords.trim()) form.append('prompt', s.hotwords.trim())

    const res = await fetch(s.asrUrl, {
      method: 'POST',
      headers: { Authorization: `Bearer ${s.asrApiKey}` },
      body: form,
    })
    return { status: res.status, raw: await res.text() }
  }

  try {
    let out = await attempt(lang)

    // 400/422 多为参数不被接受（language 取值、prompt 字段），去掉语言再试一次
    if ((out.status === 400 || out.status === 422) && lang) {
      out = await attempt('')
    }

    if (out.status !== 200) {
      return { ok: false, text: '', error: describeHttp(out.status, out.raw), usedLang: '' }
    }

    let text = ''
    try {
      const j = JSON.parse(out.raw)
      // 多数服务返回 {text}；个别返回 {segments:[...]}，拼起来
      text = typeof j.text === 'string' ? j.text : ''
      if (!text && Array.isArray(j.segments)) {
        text = j.segments.map((x: { text?: string }) => x.text ?? '').join('')
      }
    } catch {
      // 不是 JSON，可能直接就是纯文本
      text = out.raw
    }
    text = text.trim()
    if (!text) return { ok: false, text: '', error: '服务返回了空文本（可能整段无人声）', usedLang: lang }
    return { ok: true, text, error: '', usedLang: lang }
  } catch (e) {
    return { ok: false, text: '', error: `请求失败：${(e as Error).message}`, usedLang: '' }
  }
}

/** 把 HTTP 状态翻成「下一步该改什么」，而不是只丢一个数字 */
function describeHttp(status: number, raw: string): string {
  const snippet = raw.slice(0, 200)
  if (status === 401 || status === 403) return `Key 无效或没有权限（${status}）`
  if (status === 404) return `接口地址不对（404），检查是否少了 /audio/transcriptions`
  if (status === 413) return '音频太大（413），录音过长请分段'
  if (status === 429) return '额度用尽或请求过快（429）'
  return `转写失败（${status}）：${snippet}`
}
