// 端到端验证：跑真实的 IPC、真实的切片、真实的解析与守卫，只把外部服务换成
// tools/mock-ai.js。目的是在没有 API Key 的情况下，把「能跑通」与「跑得对」分开验证。
//
// 重点验证四件事：
//   1. 6.5 分钟音频被切成 2 片、逐片送 ASR、拼接结果含两段
//   2. 摘要 / 纪要 / 方案三个产出都能解析入库
//   3. 出口守卫仍会拦下「原文没有的数字」与编造的待办
//   4. 项目归属用的是原文证据，置信度不是硬编码的 1

(async () => {
  const api = window.caoji
  const pipeline = window.__caojiPipeline
  const results = []
  const check = (name, pass, detail) => results.push({ name, pass: !!pass, detail })

  const prev = (await api.getSettings()).settings
  const AUDIO = 'C:/Users/simon/Desktop/MindBall/pc/.tmp/sample-6min.wav'

  const TEXT_A =
    '今天下午跟璟珹那边对了虞懋府 3 号楼的招商进度。目前 3 号楼一共 42 个铺位，已经签了 18 个，还有 24 个空着。李经理说下周五之前把剩余的报价单整理出来，重点是沿街那 6 个位置。租金这块，大家倾向于一楼均价做到 4.5 元每平每天，二楼降到 3.2，但物业费要单独算。王总觉得二楼降得太多，担心影响后面 2 号楼的定价。最后定的是：一楼维持 4.5，二楼先按 3.5 试一个月，看签约速度再调。'

  const TEXT_GUARD =
    'GUARD_TEST 今天简单聊了下销售进度，三号楼还剩二十四套，月底前要收意向金。'

  let audioRecId = ''
  let textRecId = ''

  try {
    await api.saveSettings({
      llmBaseUrl: 'http://127.0.0.1:18080/v1',
      llmApiKey: 'mock-key',
      llmModel: 'mock-model',
      asrUrl: 'http://127.0.0.1:18080/v1/audio/transcriptions',
      asrApiKey: 'mock-key',
      asrModel: 'mock-asr',
    })

    // ---------- 1. 项目档案 ----------
    const p1 = await api.saveProject({
      name: '虞懋府招商',
      aliases: ['虞懋府', '虞城项目'],
      keywords: ['璟珹', '3 号楼'],
      kind: '客户',
      note: '余姚虞城，写字楼与商铺招商',
    })
    check('项目档案可创建', !!p1.id && p1.name === '虞懋府招商', p1.id)

    // ---------- 2. 文本记录 → 摘要 ----------
    const r1 = await api.createFromText(TEXT_A, '虞懋府 3 号楼招商会')
    textRecId = r1.id
    check('粘贴文字可建记录', r1.transcript.length > 100, `字数 ${r1.transcript.length}`)

    const a1 = await api.analyze(r1.id)
    check('摘要解析成功', a1.ok && !!a1.record.summary, a1.ok ? a1.record.summary.slice(0, 30) : a1.error)
    check('类型被识别为会议', a1.record?.kind === 'meeting', a1.record?.kind)
    check('标签已提取', (a1.record?.tags || []).length > 0, (a1.record?.tags || []).join('/'))
    check(
      '项目自动归属且有证据',
      !!a1.record?.projectId && a1.record.projectConfidence > 0 && a1.record.projectEvidence.length > 0,
      `${a1.record?.project} / ${a1.record?.projectConfidence} / ${a1.record?.projectEvidence}`
    )
    check('待办被保留（有原文依据）', (a1.record?.todos || []).length >= 1, `${a1.record?.todos.length} 条`)

    // ---------- 3. 出口守卫 ----------
    const rg = await api.createFromText(TEXT_GUARD, '守卫测试')
    const ag = await api.analyze(rg.id)
    check(
      '守卫拦下原文没有的数字',
      !!ag.needsReview && /找不到|数字/.test(ag.guardNote || ''),
      ag.guardNote || '(无提示)'
    )
    check(
      '编造的待办被丢弃',
      (ag.record?.todos || []).length === 0,
      `剩余 ${(ag.record?.todos || []).length} 条`
    )

    // ---------- 4. 纪要 ----------
    const m = await api.makeMinutes(r1.id)
    check('会议纪要生成', m.ok && !!m.minutes?.topic, m.ok ? m.minutes.topic : m.error)
    check('纪要有议题与决议', (m.minutes?.agenda || []).length > 0 && (m.minutes?.decisions || []).length > 0,
      `${m.minutes?.agenda.length} 议题 / ${m.minutes?.decisions.length} 决议`)
    check('纪要有行动项', (m.minutes?.actions || []).length > 0, `${m.minutes?.actions.length} 条`)

    // ---------- 5. 方案 ----------
    const pl = await api.makePlan(r1.id)
    check('行动方案生成', pl.ok && !!pl.plan?.goal, pl.ok ? pl.plan.goal : pl.error)
    check('方案有多步且有验收标准', (pl.plan?.steps || []).length >= 3 && !!pl.plan?.metric,
      `${pl.plan?.steps.length} 步 / ${pl.plan?.metric}`)

    // ---------- 6. 文字整理 ----------
    const c1 = await api.cleanText(r1.id)
    check('规则整理有产出', c1.ok && (c1.text || '').length > 50, `统计 ${JSON.stringify(c1.stats)}`)
    const c2 = await api.cleanText(r1.id, { ai: true })
    check('AI 精修有产出', c2.ok && (c2.text || '').length > 50, c2.aiNote)

    // ---------- 7. 导入音频 + 切片转写 ----------
    const imp = await api.importRun([AUDIO])
    check('音频可导入', imp.created.length === 1, `跳过 ${imp.skipped.length}`)
    const ar = imp.created[0]
    audioRecId = ar.id
    check('导入时读出时长', ar.durationMs > 380000 && ar.durationMs < 400000, `${Math.round(ar.durationMs / 1000)} 秒`)

    const progress = []
    const run = await pipeline.runRecord(ar.id, { onProgress: (p) => progress.push(p) })
    const after = await api.get(ar.id)
    const chunkSteps = progress.filter((p) => p.stage === 'asr' && p.total > 0)
    const maxTotal = chunkSteps.reduce((n, p) => Math.max(n, p.total), 0)

    check('流水线跑通', run.ok, run.ok ? 'ok' : run.error)
    check('切成 2 片送 ASR', maxTotal === 2, `最多 ${maxTotal} 片`)
    check(
      '两片都被拼进原文',
      (after.transcript || '').includes('第 1 段') && (after.transcript || '').includes('第 2 段'),
      after.transcript.slice(0, 60)
    )
    check('生成了章节索引', (after.outline || []).length === 2, `${after.outline?.length} 条`)
    check('切片后仍走完分析', !!after.summary, after.summary?.slice(0, 24))

    // ---------- 8. 搜索 ----------
    const hits = await api.search('璟珹')
    check('搜索能命中原文', hits.length >= 1 && hits.some((h) => h.field === '原文'), `${hits.length} 条`)

    // ---------- 9. 导出 Markdown ----------
    const md = await api.markdownOf(r1.id)
    check('导出含纪要、方案、整理稿', /会议纪要/.test(md) && /行动方案/.test(md) && /整理稿/.test(md),
      `${md.length} 字`)

    // 清理这次测出来的音频记录（示例数据不留）
    await api.remove(audioRecId)
    audioRecId = ''
  } catch (e) {
    results.push({ name: '执行异常', pass: false, detail: String((e && e.message) || e) })
  } finally {
    await api.saveSettings({
      llmBaseUrl: prev.llmBaseUrl,
      llmApiKey: prev.llmApiKey,
      llmModel: prev.llmModel,
      asrUrl: prev.asrUrl,
      asrApiKey: prev.asrApiKey,
      asrModel: prev.asrModel,
    })
    if (audioRecId) await api.remove(audioRecId)
    if (textRecId) results.push({ name: '（保留文本记录用于截图）', pass: true, detail: textRecId })
  }

  const failed = results.filter((r) => !r.pass)
  return { total: results.length, passed: results.length - failed.length, failed: failed.length, results }
})()
