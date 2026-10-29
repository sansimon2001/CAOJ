// 本地模拟服务 —— 用来在没有真实 API Key 的情况下验证整条链路。
//
// 两个端点：
//   POST /v1/audio/transcriptions  → 假 ASR。按 multipart 里的 filename 回不同内容，
//                                    这样能证明「切片确实被逐片送过来并拼接」。
//   POST /v1/chat/completions      → 假大模型。按 system 提示词判断这次要什么：
//                                    摘要 / 纪要 / 方案 / 文字精修 / 长文本要点，各回各的。
//
// 默认返回与测试文本对齐的内容（数字都能在原文找到），避免触发出口守卫；
// 用户文本里带 GUARD_TEST 时改回「编造数字 + 无依据待办」，用来验证守卫仍然生效。

const http = require('node:http')

const PORT = process.env.MOCK_PORT ? Number(process.env.MOCK_PORT) : 18080

const ANALYZE_OK = {
  title: '虞懋府 3 号楼招商定价',
  kind: 'meeting',
  summary:
    '3 号楼共 42 个铺位，已签 18 个，剩 24 个待招商。租金定为一楼 4.5 元、二楼先按 3.5 元试一个月。李经理下周五前整理剩余报价单，重点是沿街 6 个位置。王总担心二楼降价影响 2 号楼定价，这点还没完全谈拢。',
  todos: [
    { text: '整理 3 号楼剩余铺位报价单，重点沿街 6 个位置', due: '' },
    { text: '二楼先按 3.5 元试一个月，看签约速度再调', due: '' },
  ],
  insight:
    '这次先把数字摆清楚再谈价格，效率比上次高；但对二楼降价的顾虑当场没回应，容易留成后患。',
  tags: ['招商', '租金定价', '铺位'],
  project: '虞懋府招商',
  projectEvidence: '对了虞懋府 3 号楼的招商进度',
}

const ANALYZE_BAD = {
  title: '虞懋府招商',
  kind: 'meeting',
  summary: '这次会定了 24 套的销售目标，10 月底前完成回款。',
  todos: [{ text: '整理客户名单并发给财务', due: '2026-11-15' }],
  insight: '沟通效率很高。',
  tags: ['招商'],
  project: '',
  projectEvidence: '',
}

const MINUTES = {
  topic: '虞懋府 3 号楼招商定价',
  background: '推进 3 号楼剩余铺位招商，确认一、二楼租金策略',
  agenda: ['3 号楼签约进度', '一楼与二楼租金定价', '剩余铺位报价单安排'],
  decisions: [
    '一楼均价维持 4.5 元每平每天',
    '二楼先按 3.5 元试一个月，再看签约速度调整',
    '物业费单独计算，不并入租金报价',
  ],
  actions: [
    { text: '整理剩余铺位报价单，重点沿街 6 个位置', owner: '李经理', due: '' },
    { text: '二楼试租一个月后复盘签约速度', owner: '', due: '' },
  ],
  risks: ['王总担心二楼降价影响 2 号楼后续定价，尚未达成一致'],
  next: '先出报价单，把沿街铺位推起来',
}

const PLAN = {
  goal: '一个月内把 3 号楼剩余 24 个铺位的签约率提到六成',
  background: '当前 42 个铺位已签 18 个，剩余集中在二楼与沿街位置；定价策略刚定，尚未对外发布。',
  steps: [
    { step: '出报价单', detail: '按一楼 4.5、二楼 3.5 出两版报价，标注物业费另计', owner: '李经理', when: '本周内' },
    { step: '推沿街铺位', detail: '优先推沿街 6 个位置，一对一约看', owner: '', when: '下周' },
    { step: '试租复盘', detail: '二楼试租满一个月，统计签约数与客户反馈', owner: '', when: '下月底' },
    { step: '同步 2 号楼', detail: '把二楼试租结果同步给王总，避免 2 号楼定价被动', owner: '', when: '需确认：具体时间' },
  ],
  resources: ['两版报价单模板', '沿街铺位平面图', '本月客户到访记录'],
  risks: ['二楼降价若被 2 号楼客户知道，可能引发比价', '试租期内签约过快可能被要求继续降价'],
  metric: '一个月内剩余 24 个铺位签约不少于 14 个',
}

const CLEANED =
  '今天下午跟璟珹那边对了虞懋府 3 号楼的招商进度。目前 3 号楼一共 42 个铺位，已经签了 18 个，还有 24 个空着。\n\n李经理说下周五之前把剩余的报价单整理出来，重点是沿街那 6 个位置。租金这块，大家倾向于一楼均价做到 4.5 元每平每天，二楼降到 3.2，但物业费要单独算。\n\n王总觉得二楼降得太多，担心影响后面 2 号楼的定价。最后定的是：一楼维持 4.5，二楼先按 3.5 试一个月，看签约速度再调。'

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => resolve(Buffer.concat(chunks)))
  })
}

function json(res, obj, code = 200) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' })
  res.end(JSON.stringify(obj))
}

const server = http.createServer(async (req, res) => {
  const url = req.url || ''
  const body = await readBody(req)

  if (url.includes('/audio/transcriptions')) {
    // 从 multipart 头里拿文件名，据此回「第几段」，用来验证切片拼接
    const head = body.toString('utf8', 0, Math.min(body.length, 4000))
    const m = head.match(/filename="([^"]+)"/)
    const name = m ? m[1] : 'unknown'
    const idx = (name.match(/part-(\d+)/) || [])[1] || '1'
    console.log(`[mock-asr] 收到切片 ${name}，${(body.length / 1024).toFixed(0)} KB`)
    return json(res, {
      text: `第 ${idx} 段转写内容：这一段是会上关于铺位与租金的讨论，识别结果用于验证切片拼接是否正确。`,
    })
  }

  if (url.includes('/chat/completions')) {
    let payload = {}
    try {
      payload = JSON.parse(body.toString('utf-8'))
    } catch {
      /* 忽略 */
    }
    const system = payload?.messages?.[0]?.content ?? ''
    const user = payload?.messages?.[1]?.content ?? ''
    const reply = (obj) => json(res, { choices: [{ message: { content: JSON.stringify(obj) } }] })

    if (system.includes('保守校对')) {
      console.log('[mock-llm] 文字精修')
      return json(res, { choices: [{ message: { content: CLEANED } }] })
    }
    if (system.includes('整理正式会议纪要')) {
      console.log('[mock-llm] 会议纪要')
      return reply(MINUTES)
    }
    if (system.includes('项目推进顾问')) {
      console.log('[mock-llm] 行动方案')
      return reply(PLAN)
    }
    if (system.includes('要点抽取')) {
      console.log('[mock-llm] 长文本要点抽取')
      return json(res, { choices: [{ message: { content: '· 3 号楼已签 18 个铺位\n· 一楼 4.5、二楼 3.5' } }] })
    }
    console.log('[mock-llm] 摘要与待办')
    if (user.includes('GUARD_TEST')) return reply(ANALYZE_BAD)
    return reply(ANALYZE_OK)
  }

  json(res, { ok: true, hint: 'mock ai server' })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock-ai] 已启动 http://127.0.0.1:${PORT}`)
})
