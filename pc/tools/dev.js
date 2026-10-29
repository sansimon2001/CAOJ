// 开发模式：先起 Vite 开发服务器，再启动 Electron 加载它。
// 单独写脚本而不是用 concurrently，是为了顺手处理 ELECTRON_RUN_AS_NODE 那个坑。

const { spawn } = require('node:child_process')
const path = require('node:path')
const electronPath = require('electron')

const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE
delete env.NODE_OPTIONS

const vite = spawn(process.execPath, [path.join(__dirname, '../node_modules/vite/bin/vite.js')], {
  stdio: 'inherit',
  env,
})

let electron = null
let closing = false

function stopAll(code) {
  if (closing) return
  closing = true
  try { electron?.kill() } catch {}
  try { vite.kill() } catch {}
  setTimeout(() => process.exit(code), 200)
}

// 给 Vite 一点时间起来，再开窗口；端口是 strictPort，起不来会直接报错而不是静默换端口
setTimeout(() => {
  electron = spawn(electronPath, ['.'], {
    stdio: 'inherit',
    env: { ...env, CAOJI_DEV: '1', CAOJI_DEV_URL: 'http://localhost:5273' },
  })
  electron.on('exit', (code) => stopAll(code ?? 0))
}, 2200)

vite.on('exit', (code) => {
  if (!closing) stopAll(code ?? 0)
})

process.on('SIGINT', () => stopAll(0))
process.on('SIGTERM', () => stopAll(0))
