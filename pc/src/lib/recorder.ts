// 录音引擎 —— 三种音源：
//   mic    只录麦克风
//   system 只录系统声音（WASAPI 回环，能录到腾讯会议 / 微信语音 / 网课）
//   mix    两者混成一条轨
//
// 混音时麦克风开回声消除：系统输出的声音已经由 loopback 单独录了一份，
// 若麦克风再把扬声器漏出来的声音收进来，同一句话就会出现两遍、还带时间差。

export type SourceMode = 'mic' | 'system' | 'mix'

export interface RecordResult {
  blob: Blob
  durationMs: number
  marks: number[]
}

export class Recorder {
  private ctx: AudioContext | null = null
  private streams: MediaStream[] = []
  private dest: MediaStreamAudioDestinationNode | null = null
  private recorder: MediaRecorder | null = null
  private chunks: Blob[] = []
  private analyser: AnalyserNode | null = null
  private levelBuf: Uint8Array<ArrayBuffer> | null = null
  private startedAt = 0
  private marks: number[] = []
  private sourceStreams: { node: MediaStreamAudioSourceNode; stream: MediaStream }[] = []

  /** 申请权限并建好流；必须在用户点击之后调用，否则 getDisplayMedia 会被拒绝 */
  async prepare(mode: SourceMode): Promise<void> {
    this.dispose()

    const ctx = new AudioContext()
    await ctx.resume()
    this.ctx = ctx

    const dest = ctx.createMediaStreamDestination()
    this.dest = dest

    const streams: MediaStream[] = []

    if (mode === 'mic' || mode === 'mix') {
      const mic = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      })
      streams.push(mic)
    }

    if (mode === 'system' || mode === 'mix') {
      const disp = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })
      // 只要声音，视频轨立刻停掉，否则屏幕采集会一直跑着
      disp.getVideoTracks().forEach((t) => t.stop())
      const audioTracks = disp.getAudioTracks()
      if (!audioTracks.length) {
        disp.getTracks().forEach((t) => t.stop())
        throw new Error('系统没有给出音频轨：请确认声卡正在输出声音，或系统禁用了回环采集')
      }
      streams.push(new MediaStream(audioTracks))
    }

    if (!streams.length) throw new Error('没有可用的音源')

    this.streams = streams
    for (const s of streams) {
      const node = ctx.createMediaStreamSource(s)
      node.connect(dest)
      this.sourceStreams.push({ node, stream: s })
    }

    const analyser = ctx.createAnalyser()
    analyser.fftSize = 1024
    analyser.smoothingTimeConstant = 0.6
    ctx.createMediaStreamSource(dest.stream).connect(analyser)
    this.analyser = analyser
    // 显式给 ArrayBuffer，否则推断成 ArrayBufferLike，getByteTimeDomainData 不接受
    this.levelBuf = new Uint8Array(new ArrayBuffer(analyser.fftSize))
  }

  start(): void {
    if (!this.dest) throw new Error('还没 prepare')
    this.chunks = []
    this.marks = []
    this.startedAt = performance.now()

    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : 'audio/webm'

    const rec = new MediaRecorder(this.dest.stream, {
      mimeType: mime,
      // 96k 而不是 64k：转写对辅音与远场说话更敏感，多出来的带宽在识别率上能看出来，
      // 一小时也就多 14MB，与手机端 v1.24 起的设置一致
      audioBitsPerSecond: 96000,
    })
    rec.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) this.chunks.push(e.data)
    }
    // 每秒切一个 chunk，长录音不会把所有数据憋在内存里
    rec.start(1000)
    this.recorder = rec
  }

  /** 录音中打一个点，导出时能看到「第 3 分 20 秒标了重点」 */
  mark(): void {
    if (!this.recorder) return
    this.marks.push(Math.round(performance.now() - this.startedAt))
  }

  elapsedMs(): number {
    return this.startedAt ? Math.round(performance.now() - this.startedAt) : 0
  }

  /** 当前电平 0~1，用于画电平条 */
  level(): number {
    if (!this.analyser || !this.levelBuf) return 0
    this.analyser.getByteTimeDomainData(this.levelBuf)
    let sum = 0
    for (let i = 0; i < this.levelBuf.length; i++) {
      const v = (this.levelBuf[i] - 128) / 128
      sum += v * v
    }
    return Math.min(1, Math.sqrt(sum / this.levelBuf.length) * 3)
  }

  async stop(): Promise<RecordResult> {
    const rec = this.recorder
    if (!rec) throw new Error('没有正在进行的录音')
    const durationMs = this.elapsedMs()

    const done = new Promise<void>((resolve) => {
      rec.onstop = () => resolve()
    })
    rec.stop()
    await done

    const blob = new Blob(this.chunks, { type: 'audio/webm' })
    this.recorder = null
    this.chunks = []
    this.startedAt = 0

    const marks = [...this.marks]
    this.marks = []
    this.dispose()

    return { blob, durationMs, marks }
  }

  /** 释放所有轨道。停录音必须调用，否则系统声音的采集会一直挂着 */
  dispose(): void {
    const rec = this.recorder
    if (rec && rec.state !== 'inactive') {
      try {
        rec.stop()
      } catch {
        /* 已经停了 */
      }
    }
    this.recorder = null

    for (const s of this.streams) {
      s.getTracks().forEach((t) => t.stop())
    }
    this.streams = []

    for (const { stream } of this.sourceStreams) {
      stream.getTracks().forEach((t) => t.stop())
    }
    this.sourceStreams = []

    try {
      this.ctx?.close()
    } catch {
      /* 已关闭 */
    }
    this.ctx = null
    this.dest = null
    this.analyser = null
    this.levelBuf = null
  }
}
