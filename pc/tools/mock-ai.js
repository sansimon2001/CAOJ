// 本地模拟的 ASR + LLM 服务，只用于验证协议实现与出口校验。
//
// 它故意返回「与原文对不上」的内容，用来确认两件事：
//   1) multipart 转写请求与 chat/completions 请求的格式是服务端能认的
//   2) SummaryGuard 真的会拦：数字在原文找不到要报警、编造的待办要被丢掉
//
// 用法：node tools/mock-ai.js   （监听 127.0.0.1:18080）

const http = require('node:http')

const PORT = 18080

// 转写返回的「原文」。故意用中文数字（二十四），而下面摘要用阿拉伯数字（24），
// 正好复现真实场景里模型把中文数字改写成数字后被守卫抓住的情况。
const TRANSCRIPT = '今天讨论了销售进度，三号楼还剩二十四套，月底前要收意向金。'

const REPLY = {
  title: '三号楼销售进度沟通',
  summary: '会上确认了 3 号楼剩余 24 套，10 月底前收齐意向金。',
  todos: [
    // 这一条能在原文里对上「月底前要收意向金」→ 应当保留
    { text: '月底前要收意向金', due: '2026-10-31' },
    // 这一条是编的，原文没有 → 应当被过滤
    { text: '整理客户名单并发给财务', due: '' },
  ],
  insight: '沟通直接，重点落在时间节点上。',
}

const server = http.createServer((req, res) => {
  const url = req.url || ''
  let body = ''
  req.on('data', (c) => {
    body += c
  })
  req.on('end', () => {
    if (url.includes('/audio/transcriptions')) {
      const isMultipart = (req.headers['content-type'] || '').includes('multipart/form-data')
      console.log(`[mock] 转写请求 multipart=${isMultipart} 体积=${body.length}`)
      if (!isMultipart) {
        res.writeHead(400, { 'Content-Type': 'application/json' })
        return res.end(JSON.stringify({ error: '不是 multipart' }))
      }
      res.writeHead(200, { 'Content-Type': 'application/json' })
      return res.end(JSON.stringify({ text: TRANSCRIPT }))
    }

    if (url.includes('/chat/completions')) {
      let parsed = {}
      try {
        parsed = JSON.parse(body)
      } catch {
        /* 让下面的日志显示空 */
      }
      console.log(`[mock] chat 请求 model=${parsed.model} messages=${parsed.messages?.length}`)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      return res.end(
        JSON.stringify({
          choices: [{ message: { role: 'assistant', content: JSON.stringify(REPLY) } }],
        })
      )
    }

    res.writeHead(404, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: 'not found: ' + url }))
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock] 已启动 http://127.0.0.1:${PORT}`)
})
