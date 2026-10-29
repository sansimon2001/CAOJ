// 只用于截图核对：把已存在的那条测试记录补齐展示所需的字段（待办日期、日程、第二个项目）。
// 不造假的业务内容 —— 摘要、纪要、方案仍是 mock 服务经真实代码路径产生的。
// 截完图会连同数据一起清掉，见 tools/cleanup-demo.sh。

(async () => {
  const api = window.caoji
  const list = await api.list()
  // 挑有纪要的那条当主角；守卫测试留下的那条清掉，免得列表里混进半成品
  const main = list.find((r) => r.minutes) || list.find((r) => r.summary) || list[0]
  if (!main) return { ok: false, error: '没有可用的记录' }
  for (const r of list) {
    if (r.id !== main.id && (r.summary || '').includes('24 套')) await api.remove(r.id)
  }

  // 重新分析一次，让 guardNote 落库（新增字段，老记录里没有）
  await api.analyze(main.id)

  const pad = (n) => String(n).padStart(2, '0')
  const day = (delta) => {
    const d = new Date()
    d.setDate(d.getDate() + delta)
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  }

  const fresh = await api.get(main.id)
  const todos = fresh.todos.map((t, i) => ({ ...t, dueDate: day(i + 1) }))
  await api.update(main.id, { todos })

  const p2 = await api.saveProject({
    name: '草记 PC 端',
    aliases: ['草记'],
    keywords: ['Electron', 'loopback', '切片'],
    kind: '产品',
    note: '桌面端录音整理工具，能录系统声音',
  })

  await api.saveEvent({
    title: '虞懋府招商周会',
    date: day(2),
    time: '10:00',
    durationMin: 60,
    projectId: fresh.projectId,
    note: '',
  })
  await api.saveEvent({
    title: '草记 PC 端发布评审',
    date: day(5),
    time: '15:30',
    durationMin: 90,
    projectId: p2.id,
    note: '',
  })

  // 一条只有待办、没有内容的记录不好看，补一条待整理的，展示「待整理」状态
  const pending = await api.createFromText(
    '随手记一下：明天要问一下璟珹那边沿街铺位的到访情况，顺便把二楼试租的数据拉出来对比。',
    '明天要问的事'
  )

  return {
    ok: true,
    main: main.id,
    pending: pending ? pending.id : '',
    project: p2.id,
    todos: todos.length,
  }
})()
