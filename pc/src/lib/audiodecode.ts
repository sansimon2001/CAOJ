// 长音频切片 —— PC 端能处理「一小时会议录音」的关键。
//
// 为什么必须切：转写服务普遍限制单次 25MB / 若干分钟，而一小时 mp3 就有几十 MB，
// 直接整段上传不是被拒就是超时。项目里没有 ffmpeg（不想为了转写多带 80MB 二进制），
// 所以借用 Chromium 自带的解码器：decodeAudioData 解出 PCM，用 OfflineAudioContext
// 一次性降混为单声道 + 重采样到 16kHz，再按固定时长切成若干 WAV。
//
// 16kHz 单声道 16bit 正好是 32KB/秒：5 分钟一片约 9.6MB，稳稳落在各家限制之内，
// 而 Whisper / SenseVoice 内部本来就是按 16k 单声道处理，音质上不吃亏。

export interface WavChunk {
  wav: ArrayBuffer
  startMs: number
  durationMs: number
}

export interface DecodeResult {
  chunks: WavChunk[]
  totalMs: number
  sourceRate: number
  sourceChannels: number
}

const TARGET_RATE = 16000

/** 超过这个时长就不硬解了：解码需要把整段 PCM 装进内存，太长的文件会把渲染进程撑爆 */
export const MAX_DECODE_MS = 3 * 60 * 60 * 1000

/** Float32 [-1,1] → 16bit PCM 的 WAV 文件字节 */
function encodeWav(samples: Float32Array, sampleRate: number): ArrayBuffer {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)

  const writeStr = (offset: number, s: string) => {
    for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i))
  }

  writeStr(0, 'RIFF')
  view.setUint32(4, 36 + samples.length * 2, true)
  writeStr(8, 'WAVE')
  writeStr(12, 'fmt ')
  view.setUint32(16, 16, true) // fmt 块长度
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // 单声道
  view.setUint32(24, sampleRate, true)
  view.setUint32(28, sampleRate * 2, true) // 字节率
  view.setUint16(32, 2, true) // 块对齐
  view.setUint16(34, 16, true) // 位深
  writeStr(36, 'data')
  view.setUint32(40, samples.length * 2, true)

  let o = 44
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true)
    o += 2
  }
  return buffer
}

/**
 * 解码 + 切片。抛错时调用方应退回「整文件上传」。
 * chunkSec 默认 300 秒（5 分钟），一片约 9.6MB。
 */
export async function sliceToWav(
  bytes: ArrayBuffer,
  chunkSec = 300,
  onStage?: (stage: string) => void
): Promise<DecodeResult> {
  onStage?.('正在解码音频')

  // 解出来是原采样率的 PCM；临时 AudioContext 只为拿到时长与源参数
  const probeCtx = new AudioContext()
  let decoded: AudioBuffer
  try {
    decoded = await probeCtx.decodeAudioData(bytes.slice(0))
  } finally {
    void probeCtx.close()
  }

  const sourceRate = decoded.sampleRate
  const sourceChannels = decoded.numberOfChannels
  const totalMs = Math.round(decoded.duration * 1000)

  if (totalMs > MAX_DECODE_MS) {
    throw new Error(`音频长 ${(totalMs / 3600000).toFixed(1)} 小时，超出本地解码上限，请先切成小时以内的片段`)
  }

  onStage?.('正在重采样为 16kHz 单声道')

  // OfflineAudioContext 指定 1 通道 + 16kHz，解码后的多声道缓冲接进去会被
  // 按 Web Audio 规范自动降混并重采样，等于一次把两件事都办了
  const frameCount = Math.max(1, Math.ceil(decoded.duration * TARGET_RATE))
  const offline = new OfflineAudioContext(1, frameCount, TARGET_RATE)
  const src = offline.createBufferSource()
  src.buffer = decoded
  src.connect(offline.destination)
  src.start()
  const rendered = await offline.startRendering()
  const pcm = rendered.getChannelData(0)

  onStage?.('正在切片')

  const perChunk = Math.round(chunkSec * TARGET_RATE)
  const chunks: WavChunk[] = []
  for (let start = 0; start < pcm.length; start += perChunk) {
    const end = Math.min(start + perChunk, pcm.length)
    // 末尾不足 1 秒的碎片直接并进上一片，避免送一段几乎没声音的过去
    if (end - start < TARGET_RATE && chunks.length) {
      const prevStart = chunks[chunks.length - 1].startMs
      const prevSamples = pcm.subarray(Math.round((prevStart / 1000) * TARGET_RATE), pcm.length)
      chunks[chunks.length - 1] = {
        wav: encodeWav(prevSamples, TARGET_RATE),
        startMs: prevStart,
        durationMs: Math.round((pcm.length / TARGET_RATE) * 1000) - prevStart,
      }
      break
    }
    const slice = pcm.subarray(start, end)
    chunks.push({
      wav: encodeWav(slice, TARGET_RATE),
      startMs: Math.round((start / TARGET_RATE) * 1000),
      durationMs: Math.round(((end - start) / TARGET_RATE) * 1000),
    })
  }

  return { chunks, totalMs, sourceRate, sourceChannels }
}

/** 毫秒 → mm:ss（切片索引上显示用） */
export function msToClock(ms: number): string {
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}
