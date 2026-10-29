// 端到端验证脚本（在页面上下文里执行，由 CAOJI_E2E 注入）
//
// 目的：不用手点，也能证明「点开始 → 录几秒系统声音 → 点结束 → 记录落盘」这条链路是通的。
// 做法：先播一个 1kHz 低音量音让系统有输出（否则系统声音源录到的是纯静音），
// 然后点按钮、等几秒、点结束，最后问主进程要记录列表来核对。

;(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  const steps = []
  const step = (s, d) => steps.push(d === undefined ? s : `${s}: ${JSON.stringify(d)}`)
  const findBtn = (text) => [...document.querySelectorAll('button')].find((b) => b.textContent.includes(text))

  // 1) 制造系统声音
  const ctx = new AudioContext()
  await ctx.resume()
  const osc = ctx.createOscillator()
  osc.frequency.value = 1000
  const gain = ctx.createGain()
  gain.gain.value = 0.06
  osc.connect(gain)
  gain.connect(ctx.destination)
  osc.start()
  step('播放 1kHz 测试音')

  await sleep(300)

  // 2) 开始录音
  const startBtn = findBtn('开始录音')
  if (!startBtn) {
    osc.stop()
    return { ok: false, steps, why: '找不到「开始录音」按钮' }
  }
  startBtn.click()
  step('点击开始录音')

  await sleep(1400)
  const inRecording = !!document.querySelector('.rec-time')
  step('进入录音状态', inRecording)
  if (!inRecording) {
    const err = document.querySelector('.err')?.textContent ?? ''
    osc.stop()
    return { ok: false, steps, why: '没有进入录音状态', err }
  }
  step('录音计时', document.querySelector('.rec-time')?.textContent)

  await sleep(3400)

  // 3) 结束
  const stopBtn = findBtn('结束并整理')
  if (!stopBtn) {
    osc.stop()
    return { ok: false, steps, why: '找不到「结束并整理」按钮' }
  }
  stopBtn.click()
  step('点击结束')

  await sleep(4000)
  try {
    osc.stop()
  } catch {}

  // 4) 核对
  const list = await window.caoji.list()
  const items = [...document.querySelectorAll('.item')]

  return {
    ok: list.length > 0,
    steps,
    domItemCount: items.length,
    domTitles: items.map((i) => i.querySelector('.item-title')?.textContent),
    domMetas: items.map((i) => i.querySelector('.item-meta')?.textContent?.replace(/\s+/g, ' ')),
    records: list.map((r) => ({
      id: r.id,
      title: r.title,
      durationMs: r.durationMs,
      source: r.source,
      status: r.status,
      audioBytes: r.audioBytes,
      audioFile: r.audioFile,
    })),
    errorShown: document.querySelector('.err')?.textContent ?? '',
  }
})()
