// 服务商预设 —— 与安卓端 ai/Providers.kt 保持同一套 id、地址与模型名。
// 两端共用一份配置的好处：用户在手机上填好的 Key 和模型，拷到 PC 端能直接用。

export interface LlmProvider {
  id: string
  name: string
  base: string
  models: string[]
  costNote: string
  keyHint: string
}

export interface AsrProvider {
  id: string
  name: string
  url: string
  model: string
  costNote: string
  /** 该服务接受哪些 language 取值；不在集合内就退回 auto 或不传 */
  requestLangs: string[]
  acceptsAuto: boolean
  supportsPrompt: boolean
  dialectNote: string
}

export const LLM_PROVIDERS: LlmProvider[] = [
  {
    id: 'siliconflow',
    name: '硅基流动（默认｜注册送额度，部分模型免费）',
    base: 'https://api.siliconflow.cn/v1',
    models: ['Qwen/Qwen2.5-7B-Instruct', 'THUDM/glm-4-9b-chat', 'deepseek-ai/DeepSeek-V3'],
    costNote: '默认服务商：注册送额度，部分小模型长期免费；转写也在同一家，一套 Key 全搞定',
    keyHint: '在 siliconflow.cn 控制台创建 API 密钥',
  },
  {
    id: 'zhipu',
    name: '智谱 GLM（有免费模型）',
    base: 'https://open.bigmodel.cn/api/paas/v4',
    models: ['glm-4-flash', 'glm-4-air', 'glm-4-plus', 'glm-4v-flash'],
    costNote: 'glm-4-flash 目前可免费调用，日常摘要够用；glm-4-air/plus 效果更好但收费',
    keyHint: '在 bigmodel.cn 控制台创建 API Key',
  },
  {
    id: 'qwen',
    name: '通义千问（新用户免费额度）',
    base: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: ['qwen-turbo', 'qwen-plus', 'qwen-max', 'qwen-long'],
    costNote: 'DashScope 新用户每个模型有免费 token 额度；qwen-turbo 单价很低',
    keyHint: '在阿里云百炼（bailian.console.aliyun.com）创建 API-KEY',
  },
  {
    id: 'deepseek',
    name: 'DeepSeek（极低价）',
    base: 'https://api.deepseek.com/v1',
    models: ['deepseek-chat', 'deepseek-reasoner'],
    costNote: '无永久免费额度，但单价很低（百万 token 约 1~2 元），中文摘要质量好',
    keyHint: '在 platform.deepseek.com 创建 API Key',
  },
  {
    id: 'moonshot',
    name: 'Kimi 月之暗面',
    base: 'https://api.moonshot.cn/v1',
    models: ['moonshot-v1-8k', 'moonshot-v1-32k', 'kimi-latest'],
    costNote: '有免费额度，长文本处理见长（适合长会议记录）',
    keyHint: '在 platform.moonshot.cn 创建 API Key',
  },
  {
    id: 'openai',
    name: 'OpenAI（无免费）',
    base: 'https://api.openai.com/v1',
    models: ['gpt-4o-mini', 'gpt-4o'],
    costNote: '无免费额度，需要外币卡；国内网络可能需要代理',
    keyHint: '在 platform.openai.com 创建 API Key',
  },
  {
    id: 'custom',
    name: '自定义（OpenAI 兼容）',
    base: '',
    models: [],
    costNote: '任何兼容 /chat/completions 的服务都可以，如自建 one-api、Ollama、vLLM',
    keyHint: '填写你自己的接口地址与模型名',
  },
]

export const ASR_PROVIDERS: AsrProvider[] = [
  {
    id: 'siliconflow-asr',
    name: '硅基流动 SenseVoice（默认｜便宜/仅纯文本）',
    url: 'https://api.siliconflow.cn/v1/audio/transcriptions',
    model: 'FunAudioLLM/SenseVoiceSmall',
    costNote: '中文识别效果好、单价低；与大模型同一账号时只需一套 Key；只返回纯文本',
    requestLangs: ['zh', 'en', 'yue', 'ja', 'ko'],
    acceptsAuto: true,
    supportsPrompt: false,
    dialectNote: 'SenseVoice 原生带粤语，粤语可直接识别；其余方言只能按普通话送',
  },
  {
    id: 'openai-whisper',
    name: 'OpenAI Whisper',
    url: 'https://api.openai.com/v1/audio/transcriptions',
    model: 'whisper-1',
    costNote: '按分钟计费，无免费额度',
    requestLangs: ['zh', 'en', 'ja', 'ko'],
    acceptsAuto: false,
    supportsPrompt: true,
    dialectNote: 'Whisper 未对粤语专门优化，按普通话提示也能出粤语文本（常为繁体）',
  },
  {
    id: 'groq-whisper',
    name: 'Groq Whisper large-v3（最准｜需能访问境外服务）',
    url: 'https://api.groq.com/openai/v1/audio/transcriptions',
    model: 'whisper-large-v3',
    costNote: 'Whisper 最大一档，远场、口音、专业词下错字明显更少；有免费额度、速度极快',
    requestLangs: ['zh', 'en', 'ja', 'ko'],
    acceptsAuto: true,
    supportsPrompt: true,
    dialectNote: '支持热词提示；粤语按普通话送也能输出粤语用字（常为繁体）',
  },
  {
    id: 'custom-asr',
    name: '自定义（Whisper 兼容）',
    url: '',
    model: 'whisper-1',
    costNote: '自建 faster-whisper / whisper.cpp 服务，或其它兼容接口',
    requestLangs: ['zh', 'en', 'ja', 'ko', 'yue', 'wuu', 'nan', 'hak', 'hsn', 'cjy'],
    acceptsAuto: true,
    supportsPrompt: true,
    dialectNote: '自建服务对语言代码接受度取决于模型；被拒绝时会自动改为自动判断语种',
  },
]

export const DEFAULT_LLM_ID = 'siliconflow'
export const DEFAULT_ASR_ID = 'siliconflow-asr'

export const llmById = (id: string) => LLM_PROVIDERS.find((p) => p.id === id) ?? LLM_PROVIDERS[0]
export const asrById = (id: string) => ASR_PROVIDERS.find((p) => p.id === id) ?? ASR_PROVIDERS[0]
