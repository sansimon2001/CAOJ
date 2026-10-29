// 对照实验：不播任何声音，同样录 4.5 秒，看落盘体积。
// 与 e2e-record.js（播 1kHz）对比：如果两者体积接近，说明 Opus 是恒定码率，
// 光看体积判断不了有没有内容；如果静音明显更小，就说明前一次确实录到了声音。

;(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const findBtn = (text) => [...document.querySelectorAll('button')].find((b) => b.textContent.includes(text))
  const steps = []

  const before = (await window.caoji.list()).length

  findBtn('开始录音')?.click()
  steps.push('开始录音（不播声音）')
  await sleep(1400)
  const inRec = !!document.querySelector('.rec-time')
  await sleep(3400)
  findBtn('结束并整理')?.click()
  steps.push('结束')
  await sleep(4000)

  const list = await window.caoji.list()
  const generated = list.slice(0, list.length - before)

  return {
    inRecording: inRec,
    steps,
    generated: generated.map((r) => ({
      durationMs: r.durationMs,
      audioBytes: r.audioBytes,
      status: r.status,
    })),
    total: list.length,
  }
})()
