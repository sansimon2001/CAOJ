import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { runRecord, transcribeChunked } from './lib/pipeline'
import './styles.css'

// 测试钩子：端到端脚本必须跑「同一份」流水线代码，不能在脚本里另写一份，
// 否则测的就不是真正在跑的东西了。与主进程里的 CAOJI_E2E / CAOJI_SHOT 是同一套思路。
;(window as unknown as Record<string, unknown>).__caojiPipeline = { runRecord, transcribeChunked }

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
