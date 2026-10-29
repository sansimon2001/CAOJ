import { useState } from 'react'
import type { AsrProvider, LlmProvider, Settings } from '../lib/types'

interface Props {
  settings: Settings | null
  llmList: LlmProvider[]
  asrList: AsrProvider[]
  onSaved: (s: Settings) => void
  onClose: () => void
}

const LANGS = [
  { id: 'zh', label: '普通话（推荐）' },
  { id: 'auto', label: '自动判断语种' },
  { id: 'yue', label: '粤语' },
  { id: 'en', label: '英语' },
  { id: 'ja', label: '日语' },
  { id: 'ko', label: '韩语' },
]

export default function SettingsPanel({ settings, llmList, asrList, onSaved, onClose }: Props) {
  const [s, setS] = useState<Settings | null>(settings)
  const [msg, setMsg] = useState('')
  const [saving, setSaving] = useState(false)

  if (!s) return <div className="placeholder">读取设置…</div>

  const llm = llmList.find((p) => p.id === s.llmProviderId)
  const asr = asrList.find((p) => p.id === s.asrProviderId)

  function patch(p: Partial<Settings>) {
    setS({ ...s!, ...p })
  }

  function pickLlm(id: string) {
    const p = llmList.find((x) => x.id === id)
    if (!p) return
    patch({
      llmProviderId: id,
      llmBaseUrl: p.base,
      llmModel: p.models[0] ?? '',
    })
  }

  function pickAsr(id: string) {
    const p = asrList.find((x) => x.id === id)
    if (!p) return
    patch({
      asrProviderId: id,
      asrUrl: p.url,
      asrModel: p.model,
    })
  }

  async function save() {
    setSaving(true)
    setMsg('')
    try {
      const next = await window.caoji.saveSettings(s!)
      onSaved(next)
      setMsg('已保存')
    } finally {
      setSaving(false)
    }
  }

  async function test() {
    setMsg('测试中…')
    // 先落盘再测，否则测的是旧配置
    const next = await window.caoji.saveSettings(s!)
    onSaved(next)
    const r = await window.caoji.testLlm()
    setMsg(r.ok ? `连接正常，模型回复：${r.message}` : `连接失败：${r.message}`)
  }

  return (
    <div className="settings">
      <header className="detail-head">
        <h1>设置</h1>
        <p className="sub">填一次 Key 就能用。地址和模型名已按服务商预填好，通常只需要填 Key。</p>
        <div className="detail-actions">
          <button className="ghost" onClick={onClose}>
            返回
          </button>
          <button className="primary" onClick={save} disabled={saving}>
            {saving ? '保存中…' : '保存'}
          </button>
          <button className="ghost" onClick={test}>
            测试连接
          </button>
        </div>
        {msg && <div className="banner ok-banner">{msg}</div>}
      </header>

      <section className="block">
        <h2>语音转写</h2>
        <div className="field">
          <label>服务商</label>
          <select value={s.asrProviderId} onChange={(e) => pickAsr(e.target.value)}>
            {asrList.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        {asr && <p className="note">{asr.costNote}</p>}

        <div className="field">
          <label>接口地址</label>
          <input value={s.asrUrl} onChange={(e) => patch({ asrUrl: e.target.value })} placeholder="https://…/audio/transcriptions" />
        </div>
        <div className="field">
          <label>模型</label>
          <input value={s.asrModel} onChange={(e) => patch({ asrModel: e.target.value })} />
        </div>
        <div className="field">
          <label>API Key</label>
          <input
            type="password"
            value={s.asrApiKey}
            onChange={(e) => patch({ asrApiKey: e.target.value })}
            placeholder="sk-…"
          />
        </div>
      </section>

      <section className="block">
        <h2>摘要与待办</h2>
        <div className="field">
          <label>服务商</label>
          <select value={s.llmProviderId} onChange={(e) => pickLlm(e.target.value)}>
            {llmList.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </div>
        {llm && <p className="note">{llm.costNote}</p>}

        <div className="field">
          <label>接口地址</label>
          <input value={s.llmBaseUrl} onChange={(e) => patch({ llmBaseUrl: e.target.value })} placeholder="https://…/v1" />
        </div>
        <div className="field">
          <label>模型</label>
          <input value={s.llmModel} onChange={(e) => patch({ llmModel: e.target.value })} />
        </div>
        <div className="field">
          <label>API Key</label>
          <input
            type="password"
            value={s.llmApiKey}
            onChange={(e) => patch({ llmApiKey: e.target.value })}
            placeholder="sk-…"
          />
        </div>
        {llm && llm.models.length > 0 && (
          <p className="note">
            可选模型：{llm.models.join(' / ')}
          </p>
        )}
      </section>

      <section className="block">
        <h2>识别</h2>
        <div className="field">
          <label>语言</label>
          <select value={s.language} onChange={(e) => patch({ language: e.target.value })}>
            {LANGS.map((l) => (
              <option key={l.id} value={l.id}>
                {l.label}
              </option>
            ))}
          </select>
        </div>
        <p className="note">
          方言（粤语之外）在多数转写服务里没有对应语言代码，只能按普通话送，App 不会假装支持。
        </p>
        <div className="field">
          <label>热词表</label>
          <textarea
            value={s.hotwords}
            onChange={(e) => patch({ hotwords: e.target.value })}
            placeholder="人名、公司名、产品名，用顿号或逗号隔开。只对支持 prompt 的转写服务生效。"
            rows={3}
          />
        </div>
      </section>

      <section className="block">
        <h2>关于</h2>
        <p className="note">
          Key 存在本机，落盘前做了加密（Windows 走 DPAPI），换机器需要重新填写。
          记录与音频都在「数据目录」里，可以整个备份走。
        </p>
        <button className="ghost" onClick={() => window.caoji.openDataDir()}>
          打开数据目录
        </button>
      </section>
    </div>
  )
}
