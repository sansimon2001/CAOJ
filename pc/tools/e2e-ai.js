// AI 链路验证：把设置临时指向本地 mock 服务，对已有录音跑一遍
// 「转写 → 摘要 → 待办 → 出口校验」，跑完把设置还原。
//
// 关注点不是「有没有出摘要」，而是守卫有没有真的把编造内容挡住。

;(async () => {
  const orig = (await window.caoji.getSettings()).settings

  const mockPatch = {
    asrProviderId: 'custom-asr',
    asrUrl: 'http://127.0.0.1:18080/v1/audio/transcriptions',
    asrModel: 'whisper-1',
    asrApiKey: 'mock-key',
    llmProviderId: 'custom',
    llmBaseUrl: 'http://127.0.0.1:18080/v1',
    llmModel: 'mock-model',
    llmApiKey: 'mock-key',
    language: 'zh',
  }
  await window.caoji.saveSettings(mockPatch)

  const list = await window.caoji.list()
  // 取最早那条（就是刚才播了 1kHz、确实有声音的那条）
  const target = list[list.length - 1]

  let run = null
  let err = ''
  try {
    run = await window.caoji.run(target.id)
  } catch (e) {
    err = (e && e.message) || String(e)
  }

  const after = await window.caoji.get(target.id)

  // 还原设置，别把 mock 地址留在用户的配置里
  await window.caoji.saveSettings({
    asrProviderId: orig.asrProviderId,
    asrUrl: orig.asrUrl,
    asrModel: orig.asrModel,
    asrApiKey: orig.asrApiKey,
    llmProviderId: orig.llmProviderId,
    llmBaseUrl: orig.llmBaseUrl,
    llmModel: orig.llmModel,
    llmApiKey: orig.llmApiKey,
    language: orig.language,
  })

  return {
    targetId: target.id,
    callError: err,
    run: run && {
      ok: run.ok,
      error: run.error,
      needsReview: run.needsReview,
      guardNote: run.guardNote,
      note: run.note,
    },
    record: after && {
      status: after.status,
      title: after.title,
      summary: after.summary,
      insight: after.insight,
      transcript: after.transcript,
      todos: after.todos.map((t) => ({ text: t.text, dueDate: t.dueDate })),
    },
    settingsRestored: (await window.caoji.getSettings()).settings.asrUrl === orig.asrUrl,
  }
})()
