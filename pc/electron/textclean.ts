// 文字整理（规则层）—— 免费、瞬时、可解释。
//
// 原则：只做「不改意思」的清理，原文 transcript 一个字都不动，结果单独存 clean。
// 去同音错字、补句子结构这类需要判断的事交给 AI 精修（llm.cleanText），
// 两者可叠加：先规则清洗，再按需 AI 精修。

export interface CleanResult {
  text: string
  stats: {
    /** 去掉的句首语气词个数 */
    filler: number
    /** 去掉的方括号噪声（[音乐] 等） */
    noise: number
    /** 压缩的重复字（我我我 → 我） */
    dup: number
    /** 整理出的段落数 */
    paragraphs: number
  }
}

/** 各类语音识别服务插进正文的噪声标记 */
const NOISE = [
  /\[(?:music|音乐|掌声|applause|laughter|笑声|silence|静音|blank_audio|inaudible|听不清|噪音|noise)\]/gi,
  /\((?:music|音乐|掌声|laughter|笑声|inaudible|听不清)\)/gi,
  /[\[【](?:BLANK_AUDIO|NOISE|MUSIC)[\]】]/gi,
]

/** 句首语气词。只削句首、且后面跟标点或空格时才削，避免动到「阿姨」「啊呀」这类词 */
const FILLER = /^[\s，,、]*(嗯+|呃+|啊+|哦+|噢+|唉+|诶+|欸+|那个|然后呢|就是说)[\s，,、。]/g

/** 口头禅：后面紧跟逗号时才当填充词删，保留「就是说这件事」这种实义用法 */
const PLATITUDE = /(?:^|[，,。])\s*(?:就是说|这么讲吧|你知道吧|你懂我意思吧)[，,]/g

export function cleanTranscript(raw: string): CleanResult {
  let text = raw.replace(/\r\n?/g, '\n')
  let noise = 0
  let filler = 0
  let dup = 0

  for (const re of NOISE) {
    text = text.replace(re, () => {
      noise++
      return ''
    })
  }

  // 按句子切开处理句首语气词，切完再拼回去
  const sentences = text.split(/(?<=[。！？；!?;\n])/)
  const kept: string[] = []
  for (const s of sentences) {
    let cur = s
    // 一句话里可能连续多个语气词，循环削到削不动为止
    for (let i = 0; i < 6; i++) {
      const before = cur
      cur = cur.replace(FILLER, (m) => (m.length > 0 ? ((filler++), '') : m))
      if (cur === before) break
    }
    const beforePlat = cur
    cur = cur.replace(PLATITUDE, (m) => (m.length ? ((filler++), m.slice(-1)) : m))
    if (cur !== beforePlat && !cur.trim()) continue
    kept.push(cur)
  }
  text = kept.join('')

  // 叠字压缩：我我我 → 我，但要放过「哈哈哈」「呵呵」这类拟声
  text = text.replace(/([\u4e00-\u9fa5])\1{2,}/g, (m, ch: string) => {
    if ('哈呵嘿嘻'.includes(ch)) return m
    dup += m.length - 1
    return ch
  })

  // 中英标点统一：中文语境下的半角标点换成全角
  text = text
    .replace(/([\u4e00-\u9fa5])\s*,\s*/g, '$1，')
    .replace(/([\u4e00-\u9fa5])\s*\?\s*/g, '$1？')
    .replace(/([\u4e00-\u9fa5])\s*!\s*/g, '$1！')
    .replace(/([\u4e00-\u9fa5])\s*;\s*/g, '$1；')
    .replace(/([\u4e00-\u9fa5])\s*:\s*/g, '$1：')
    // 句号只在「汉字后面紧跟英文句点再接空格或结尾」时换，避免动到 3.5 / v1.25
    .replace(/([\u4e00-\u9fa5])\.(?=\s|$)/g, '$1。')

  // 收拾空白与空标点
  text = text
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/^[，。、；：\s]+/gm, '')
    .replace(/([，、])\s*[，、]/g, '$1')
    .trim()

  // 分句成段：每 4 句一段，长句单独成段。转写稿最劝退的就是一大坨没有段落
  const chunks = text.split(/(?<=[。！？；])/).filter((s) => s.trim())
  const paras: string[] = []
  let buf: string[] = []
  let bufLen = 0
  for (const c of chunks) {
    buf.push(c.trim())
    bufLen += c.length
    if (buf.length >= 4 || bufLen >= 220) {
      paras.push(buf.join(''))
      buf = []
      bufLen = 0
    }
  }
  if (buf.length) paras.push(buf.join(''))

  return {
    text: paras.join('\n\n') || text,
    stats: { filler, noise, dup, paragraphs: paras.length },
  }
}

/** 反过来的检查：AI 精修后长度是否离谱（改写过度或漏段） */
export function lengthSanity(before: string, after: string): { ok: boolean; ratio: number } {
  const a = before.replace(/\s/g, '').length
  const b = after.replace(/\s/g, '').length
  if (!a) return { ok: false, ratio: 0 }
  const ratio = b / a
  return { ok: ratio >= 0.7 && ratio <= 1.35, ratio }
}
