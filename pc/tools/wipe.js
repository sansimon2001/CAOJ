// 清空测试残留 —— 走应用自己的删除接口，不直接动文件
(async () => {
  const api = window.caoji
  const recs = await api.list()
  for (const r of recs) {
    await api.remove(r.id)
  }
  const ps = await api.listProjects()
  for (const p of ps) {
    await api.removeProject(p.id)
  }
  const es = await api.listEvents()
  for (const e of es) {
    await api.removeEvent(e.id)
  }
  return {
    removedRecords: recs.length,
    removedProjects: ps.length,
    removedEvents: es.length,
    left: (await api.list()).length,
  }
})()
