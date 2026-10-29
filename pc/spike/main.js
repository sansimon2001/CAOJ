// 草记 PC 端 · 系统内录可行性验证
//
// 为什么必须有这一步：手机端录不到微信语音是系统限制，PC 端能不能绕过去，
// 全看 Electron 的 Chromium 在 Windows 上有没有开放 WASAPI loopback。
// 这个开关不同版本行为有差异，不能假设，只能实测。
//
// 测试方法（自证，不靠"有没有音频轨"这种不可靠信号）：
//   阶段 A：不播音，测 1kHz 附近的能量基线
//   阶段 B：播一个低音量 1kHz 正弦波（走系统默认输出），再从 loopback 录回来
//   判定：信号期的 1kHz 能量显著高于基线 → 说明录到的确实是自己播出去的声音
// 若只是"拿到了 audio track 但全是静音"，这个测试会判失败，不会误报成功。

const { app, BrowserWindow, desktopCapturer, session, ipcMain } = require('electron')
const path = require('path')
const fs = require('fs')

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required')

const report = { startedAt: new Date().toISOString(), events: [], ok: false }

function note(step, data) {
  report.events.push({ at: new Date().toISOString(), step, data })
  if (data === undefined) console.log(`[spike] ${step}`)
  else console.log(`[spike] ${step} :: ${JSON.stringify(data)}`)
}

app.whenReady().then(async () => {
  // 由主进程直接给源，不弹系统选择框（useSystemPicker:false），
  // audio:'loopback' 就是让 Chromium 去开 WASAPI 回环采集系统输出
  session.defaultSession.setDisplayMediaRequestHandler(
    async (request, callback) => {
      note('getDisplayMedia 请求', { video: request.videoRequested, audio: request.audioRequested })
      const sources = await desktopCapturer.getSources({ types: ['screen'] })
      note('可用屏幕源', sources.map((s) => ({ id: s.id, name: s.name })))
      if (!sources.length) {
        note('没有屏幕源，无法继续')
        return callback({})
      }
      callback({ video: sources[0], audio: 'loopback' })
    },
    { useSystemPicker: false }
  )

  const win = new BrowserWindow({
    width: 820,
    height: 620,
    show: false,
    webPreferences: { nodeIntegration: true, contextIsolation: false },
  })

  win.webContents.on('console-message', (_e, _level, message) => {
    if (message.startsWith('[page]')) console.log(message)
  })

  ipcMain.on('spike-result', (_e, r) => {
    report.ok = !!r.ok
    report.detail = r
    report.finishedAt = new Date().toISOString()
    fs.writeFileSync(path.join(__dirname, 'spike-result.json'), JSON.stringify(report, null, 2))
    console.log('\n===== 结论 =====')
    console.log(JSON.stringify(r, null, 2))
    app.quit()
  })

  await win.loadFile(path.join(__dirname, 'test.html'))

  setTimeout(() => {
    note('30 秒内没拿到结果，判失败')
    report.detail = { ok: false, notes: ['渲染进程超时未回报'] }
    fs.writeFileSync(path.join(__dirname, 'spike-result.json'), JSON.stringify(report, null, 2))
    app.quit()
  }, 30000)
})

app.on('window-all-closed', () => app.quit())
