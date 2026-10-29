// 解码核对：把录下来的 webm 真正解码，算 RMS 与峰值。
// 这是唯一能证明「录到的不是一段静音」的办法——Opus 恒定码率，体积看不出内容。

;(async () => {
  const list = await window.caoji.list()
  const out = []

  for (const r of list) {
    const buf = await window.caoji.audioBytes(r.id)
    if (!buf) {
      out.push({ title: r.title, error: '读不到音频' })
      continue
    }
    const ctx = new AudioContext()
    try {
      const audio = await ctx.decodeAudioData(buf)
      const ch = audio.getChannelData(0)
      let sum = 0
      let peak = 0
      for (let i = 0; i < ch.length; i++) {
        const v = ch[i]
        sum += v * v
        const a = Math.abs(v)
        if (a > peak) peak = a
      }
      out.push({
        title: r.title,
        source: r.source,
        durationMs: r.durationMs,
        sampleRate: audio.sampleRate,
        samples: ch.length,
        rms: Number(Math.sqrt(sum / ch.length).toFixed(6)),
        peak: Number(peak.toFixed(4)),
        hasSound: Math.sqrt(sum / ch.length) > 0.005,
      })
    } catch (e) {
      out.push({ title: r.title, error: '解码失败: ' + (e && e.message) })
    } finally {
      await ctx.close()
    }
  }

  return { count: out.length, out }
})()
