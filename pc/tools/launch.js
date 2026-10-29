// 启动 Electron 的前置处理。
//
// 这一个文件是为了绕开一个很隐蔽的坑：某些宿主（比如 WorkBuddy 自己的 shell）
// 会往子进程环境里注入 ELECTRON_RUN_AS_NODE=1，Electron 于是以纯 Node 模式启动，
// require('electron') 拿不到 app / BrowserWindow，报 "Cannot read properties of
// undefined (reading 'commandLine')"。所以这里在 spawn 之前把它们剔掉。

const { spawn } = require('node:child_process')
const electronPath = require('electron')

const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE
delete env.NODE_OPTIONS

// 默认打开工程根目录；也可以传一个脚本路径先把 spike 之类跑起来
const entry = process.argv[2] || '.'
const args = [entry, ...process.argv.slice(3)]
const child = spawn(electronPath, args, { stdio: 'inherit', env })
child.on('exit', (code) => process.exit(code ?? 0))
