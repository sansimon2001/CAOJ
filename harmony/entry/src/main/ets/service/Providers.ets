/**
 * AI 服务商清单
 *
 * 与 Android 版保持完全一致的默认值与模型名单：
 * 默认硅基流动（转写 + 大模型同一账号，只需一套 Key），并如实标注不支持说话人分离。
 */

export interface LlmProvider {
  id: string;
  name: string;
  base: string;
  models: string[];
  costNote: string;
  keyHint: string;
}

export interface AsrProvider {
  id: string;
  name: string;
  url: string;
  model: string;
  supportsDiarize: boolean;
  costNote: string;
  /** 该服务在 Whisper 兼容协议里明确接受的 language 代码；不在表里的要按降级链换算 */
  requestLangs: string[];
  /** 是否认 'auto' 字面量（不认的话，自动识别只能靠「不发这个字段」实现） */
  acceptsAuto: boolean;
  /** 是否支持 prompt 热词（SenseVoice 没有这个参数，发了会 400） */
  supportsPrompt: boolean;
  /** 该服务对方言的真实能力，降级时原样告知用户，不美化 */
  dialectNote: string;
}

export class Providers {
  static readonly DEFAULT_LLM_ID: string = 'siliconflow';
  static readonly DEFAULT_ASR_ID: string = 'siliconflow-asr';

  static readonly LLM: LlmProvider[] = [
    {
      id: 'siliconflow',
      name: '硅基流动（默认｜注册送额度，部分模型免费）',
      base: 'https://api.siliconflow.cn/v1',
      models: ['Qwen/Qwen2.5-7B-Instruct', 'THUDM/glm-4-9b-chat', 'deepseek-ai/DeepSeek-V3'],
      costNote: '注册送额度，部分小模型长期免费；转写也在同一家，一套 Key 全搞定',
      keyHint: '在 siliconflow.cn 控制台创建 API 密钥'
    },
    {
      id: 'zhipu',
      name: '智谱 GLM（有免费模型）',
      base: 'https://open.bigmodel.cn/api/paas/v4',
      models: ['glm-4-flash', 'glm-4-air', 'glm-4-plus'],
      costNote: 'glm-4-flash 目前可免费调用，日常摘要够用',
      keyHint: '在 bigmodel.cn 控制台创建 API Key'
    },
    {
      id: 'qwen',
      name: '通义千问（新用户免费额度）',
      base: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      models: ['qwen-turbo', 'qwen-plus', 'qwen-max', 'qwen-long'],
      costNote: 'DashScope 新用户每个模型有免费 token 额度',
      keyHint: '在阿里云百炼创建 API-KEY'
    },
    {
      id: 'deepseek',
      name: 'DeepSeek（极低价）',
      base: 'https://api.deepseek.com/v1',
      models: ['deepseek-chat', 'deepseek-reasoner'],
      costNote: '无永久免费额度，但单价很低，中文摘要质量好',
      keyHint: '在 platform.deepseek.com 创建 API Key'
    },
    {
      id: 'moonshot',
      name: 'Kimi 月之暗面',
      base: 'https://api.moonshot.cn/v1',
      models: ['moonshot-v1-8k', 'moonshot-v1-32k', 'kimi-latest'],
      costNote: '有免费额度，长文本处理见长',
      keyHint: '在 platform.moonshot.cn 创建 API Key'
    },
    {
      id: 'openai',
      name: 'OpenAI（无免费）',
      base: 'https://api.openai.com/v1',
      models: ['gpt-4o-mini', 'gpt-4o'],
      costNote: '无免费额度，需要外币卡；国内网络可能需要代理',
      keyHint: '在 platform.openai.com 创建 API Key'
    },
    {
      id: 'custom',
      name: '自定义（OpenAI 兼容）',
      base: '',
      models: [],
      costNote: '任何兼容 /chat/completions 的服务都可以，如自建 one-api、Ollama、vLLM',
      keyHint: '填写你自己的接口地址与模型名'
    }
  ];

  static readonly ASR: AsrProvider[] = [
    {
      id: 'siliconflow-asr',
      name: '硅基流动 SenseVoice（默认｜便宜/仅纯文本）',
      url: 'https://api.siliconflow.cn/v1/audio/transcriptions',
      model: 'FunAudioLLM/SenseVoiceSmall',
      supportsDiarize: false,
      costNote: '中文识别好、单价低；只返回纯文本，不做说话人分离',
      // SenseVoice 训练集含粤语，官方 language 支持 auto/zh/en/yue/ja/ko
      requestLangs: ['zh', 'en', 'yue', 'ja', 'ko'],
      acceptsAuto: true,
      supportsPrompt: false,
      dialectNote: 'SenseVoice 原生带粤语，粤语可直接识别；其余方言（吴语/闽南语/客家话）只能按普通话送'
    },
    {
      id: 'openai-whisper',
      name: 'OpenAI Whisper',
      url: 'https://api.openai.com/v1/audio/transcriptions',
      model: 'whisper-1',
      supportsDiarize: false,
      costNote: '按分钟计费，无免费额度；不返回说话人信息',
      // Whisper 的 language 只吃 ISO-639-1，传 yue 这类三位代码部分版本会 400
      requestLangs: ['zh', 'en', 'ja', 'ko'],
      acceptsAuto: false,
      supportsPrompt: true,
      dialectNote: 'Whisper 未对粤语做专门优化，但按普通话提示也能出粤语文本（常为繁体，App 会自动转简体）'
    },
    {
      id: 'groq-whisper',
      name: 'Groq Whisper large-v3（最准｜需能访问境外服务）',
      url: 'https://api.groq.com/openai/v1/audio/transcriptions',
      model: 'whisper-large-v3',
      supportsDiarize: false,
      costNote: 'Whisper 最大的一档模型，远场、口音、专业词下的错字明显少于轻量模型；有免费额度、速度极快',
      requestLangs: ['zh', 'en', 'ja', 'ko'],
      acceptsAuto: true,
      supportsPrompt: true,
      dialectNote: '支持热词提示；粤语按普通话送也能输出粤语用字（常为繁体，App 会自动转简体）'
    },
    {
      id: 'custom-asr',
      name: '自定义（Whisper 兼容）',
      url: '',
      model: 'whisper-1',
      supportsDiarize: false,
      costNote: '自建 faster-whisper 服务，或其它兼容接口',
      // 自建服务能力未知：把方言代码一并放行，若服务端 400 会去掉 language 重试
      requestLangs: ['zh', 'en', 'ja', 'ko', 'yue', 'wuu', 'nan', 'hak', 'hsn', 'cjy'],
      acceptsAuto: true,
      supportsPrompt: true,
      dialectNote: '自建服务对语言代码的接受度取决于模型；若被拒绝，App 会自动改为自动判断语种'
    }
  ];

  static defaultLlm(): LlmProvider {
    return Providers.llmById(Providers.DEFAULT_LLM_ID);
  }

  static defaultAsr(): AsrProvider {
    return Providers.asrById(Providers.DEFAULT_ASR_ID);
  }

  static llmById(id: string): LlmProvider {
    for (let i = 0; i < Providers.LLM.length; i++) {
      if (Providers.LLM[i].id === id) {
        return Providers.LLM[i];
      }
    }
    return Providers.LLM[0];
  }

  static asrById(id: string): AsrProvider {
    for (let i = 0; i < Providers.ASR.length; i++) {
      if (Providers.ASR[i].id === id) {
        return Providers.ASR[i];
      }
    }
    return Providers.ASR[0];
  }

  /** 反向识别：用户手填了转写地址时，猜回它属于哪个预设（老版本只存地址没存 id） */
  static guessAsrId(url: string): string {
    if (url.indexOf('siliconflow') >= 0) {
      return 'siliconflow-asr';
    }
    if (url.indexOf('openai.com') >= 0) {
      return 'openai-whisper';
    }
    if (url.indexOf('groq.com') >= 0) {
      return 'groq-whisper';
    }
    return 'custom-asr';
  }

  /** 反向识别：用户手填了 base 地址时，猜回它属于哪个服务商（用于设置页回显） */
  static guessLlmId(base: string): string {
    if (base.indexOf('siliconflow') >= 0) {
      return 'siliconflow';
    }
    if (base.indexOf('bigmodel') >= 0) {
      return 'zhipu';
    }
    if (base.indexOf('dashscope') >= 0) {
      return 'qwen';
    }
    if (base.indexOf('deepseek') >= 0) {
      return 'deepseek';
    }
    if (base.indexOf('moonshot') >= 0) {
      return 'moonshot';
    }
    if (base.indexOf('openai') >= 0) {
      return 'openai';
    }
    return 'custom';
  }
}
