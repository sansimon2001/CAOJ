package com.example.mindball.ai

/**
 * 模型服务商预设
 *
 * 目的：不用手抄接口地址和模型名——选一个服务商，地址与常用模型自动带出，用户只填 Key 即可。
 * 全部走 OpenAI 兼容协议（/chat/completions），转写走 Whisper 兼容协议（/audio/transcriptions）。
 *
 * 关于「免费」：截至 2026 年，真正可长期白用的是智谱的 glm-4-flash 一类小模型；
 * 其余多为「新用户免费额度 / 极低价」。具体额度以各家官网为准，App 内只做提示。
 */
data class LlmProvider(
    val id: String,
    val name: String,
    /** OpenAI 兼容 base，如 https://api.deepseek.com/v1 */
    val base: String,
    /** 常用模型名（第一个为默认） */
    val models: List<String>,
    /** 成本提示（展示在设置页） */
    val costNote: String,
    /** 申请 Key 的入口说明 */
    val keyHint: String,
)

data class AsrProvider(
    val id: String,
    val name: String,
    /** Whisper 兼容转写地址 */
    val url: String,
    /** 请求时 multipart 的 model 参数 */
    val model: String,
    /**
     * 该服务是否**真实**支持说话人分离（diarization）
     *
     * 判定标准：接口除 `text` 外还会返回带时间戳的 `segments[]` / `utterances[]`，
     * 且每段带 speaker / speaker_id。只有纯文本的服务一律填 false——
     * 本地声纹（audio/Voiceprint）要靠分段去录音里切样本，没有分段就等于没有「我 / 对方」。
     */
    val supportsDiarize: Boolean,
    val costNote: String,
    /**
     * 服务端 `language` 参数**实际接受**的取值
     *
     * 这一栏决定「方言能不能真的按方言识别」：
     * - SenseVoice 原生支持 yue（粤语），所以粤语能真正生效；
     * - Whisper 只吃 ISO-639-1 的 zh/en/ja/ko，传 yue 有的版本直接 400；
     * - 吴语 wuu / 闽南语 nan / 客家话 hak 目前没有一家在 Whisper 协议里支持，
     *   只能按 zh 送，识别质量必然下降——这时必须**如实告诉用户**，
     *   否则用户只会以为是 App 识别不准。
     */
    val requestLangs: Set<String> = emptySet(),
    /** 是否接受字面量 `auto`（不支持时改为不传 language 字段） */
    val acceptsAuto: Boolean = false,
    /** 是否支持 `prompt` 提示词偏置（可用来灌热词表，显著提升人名/专名准确率） */
    val supportsPrompt: Boolean = false,
    /** 方言相关的补充说明（展示给用户） */
    val dialectNote: String = "",
)

object Providers {

    // ==================== 大模型 ====================

    val LLM: List<LlmProvider> = listOf(
        LlmProvider(
            id = "siliconflow",
            name = "硅基流动（默认｜注册送额度，部分模型免费）",
            base = "https://api.siliconflow.cn/v1",
            models = listOf(
                "Qwen/Qwen2.5-7B-Instruct",
                "THUDM/glm-4-9b-chat",
                "deepseek-ai/DeepSeek-V3",
            ),
            costNote = "默认服务商：注册送额度，部分小模型长期免费；转写也在同一家，一套 Key 全搞定",
            keyHint = "在 siliconflow.cn 控制台创建 API 密钥",
        ),
        LlmProvider(
            id = "zhipu",
            name = "智谱 GLM（有免费模型）",
            base = "https://open.bigmodel.cn/api/paas/v4",
            models = listOf("glm-4-flash", "glm-4-air", "glm-4-plus", "glm-4v-flash"),
            costNote = "glm-4-flash 目前可免费调用，日常摘要够用；glm-4-air/plus 效果更好但收费",
            keyHint = "在 bigmodel.cn 控制台创建 API Key",
        ),
        LlmProvider(
            id = "qwen",
            name = "通义千问（新用户免费额度）",
            base = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf("qwen-turbo", "qwen-plus", "qwen-max", "qwen-long"),
            costNote = "DashScope 新用户每个模型有免费 token 额度；qwen-turbo 单价很低",
            keyHint = "在阿里云百炼（bailian.console.aliyun.com）创建 API-KEY",
        ),
        LlmProvider(
            id = "deepseek",
            name = "DeepSeek（极低价）",
            base = "https://api.deepseek.com/v1",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            costNote = "无永久免费额度，但单价很低（百万 token 约 1~2 元），中文摘要质量好",
            keyHint = "在 platform.deepseek.com 创建 API Key",
        ),
        LlmProvider(
            id = "moonshot",
            name = "Kimi 月之暗面",
            base = "https://api.moonshot.cn/v1",
            models = listOf("moonshot-v1-8k", "moonshot-v1-32k", "kimi-latest"),
            costNote = "有免费额度，长文本处理见长（适合长会议记录）",
            keyHint = "在 platform.moonshot.cn 创建 API Key",
        ),
        LlmProvider(
            id = "openai",
            name = "OpenAI（无免费）",
            base = "https://api.openai.com/v1",
            models = listOf("gpt-4o-mini", "gpt-4o"),
            costNote = "无免费额度，需要外币卡；国内网络可能需要代理",
            keyHint = "在 platform.openai.com 创建 API Key",
        ),
        LlmProvider(
            id = "custom",
            name = "自定义（OpenAI 兼容）",
            base = "",
            models = emptyList(),
            costNote = "任何兼容 /chat/completions 的服务都可以，如自建 one-api、Ollama、vLLM",
            keyHint = "填写你自己的接口地址与模型名",
        ),
    )

    // ==================== 语音转写 ====================

    val ASR: List<AsrProvider> = listOf(
        AsrProvider(
            id = "siliconflow-asr",
            name = "硅基流动 SenseVoice（默认｜便宜/仅纯文本）",
            url = "https://api.siliconflow.cn/v1/audio/transcriptions",
            model = "FunAudioLLM/SenseVoiceSmall",
            // 官方响应只有 {"text": "..."}，不返回 segments / speaker，
            // 不能因为「便宜好用」就对外宣称支持说话人
            supportsDiarize = false,
            costNote = "中文识别效果好、单价低；与大模型同一账号时只需一套 Key；只返回纯文本，不做说话人分离",
            // SenseVoice 训练集含粤语，官方 language 支持 auto/zh/en/yue/ja/ko
            requestLangs = setOf("zh", "en", "yue", "ja", "ko"),
            acceptsAuto = true,
            supportsPrompt = false,
            dialectNote = "SenseVoice 原生带粤语，粤语可直接识别；其余方言（吴语/闽南语/客家话）只能按普通话送",
        ),
        AsrProvider(
            id = "openai-whisper",
            name = "OpenAI Whisper",
            url = "https://api.openai.com/v1/audio/transcriptions",
            model = "whisper-1",
            supportsDiarize = false,
            costNote = "按分钟计费，无免费额度；不返回说话人信息",
            // Whisper 的 language 只吃 ISO-639-1；传 yue 这类三位代码部分版本会 400，
            // 所以粤语按 zh 送——Whisper 面对粤语音频仍会输出粤语用字（多为繁体）
            requestLangs = setOf("zh", "en", "ja", "ko"),
            acceptsAuto = false,
            supportsPrompt = true,
            dialectNote = "Whisper 未对粤语做专门优化，但按普通话提示也能出粤语文本（常为繁体，App 会自动转简体）",
        ),
        AsrProvider(
            id = "custom-asr",
            name = "自定义（Whisper 兼容）",
            url = "",
            model = "whisper-1",
            supportsDiarize = false,
            costNote = "自建 faster-whisper / whisper.cpp 服务，或其它兼容接口（可返回 segments 带 speaker）",
            // 自建服务能力未知：把方言代码一并放行（faster-whisper 支持 yue），
            // 若服务端报 400，AsrClient 会自动去掉 language 重试一次
            requestLangs = setOf("zh", "en", "ja", "ko", "yue", "wuu", "nan", "hak", "hsn", "cjy"),
            acceptsAuto = true,
            supportsPrompt = true,
            dialectNote = "自建服务对语言代码的接受度取决于模型；若被拒绝，App 会自动改为自动判断语种",
        ),
    )

    /**
     * 默认服务商：硅基流动
     *
     * 选它的理由：转写和大模型在同一家、同一套 Key，装完只需要填一个 API Key
     * 就能把「录音 → 转写 → 摘要」整条链路跑通，不用分别注册两个平台。
     * （它不返回说话人分段，所以声纹相关能力会自动降级，见 AsrClient.diarizePossible）
     */
    const val DEFAULT_LLM_ID = "siliconflow"
    const val DEFAULT_ASR_ID = "siliconflow-asr"

    fun defaultLlm(): LlmProvider = LLM.firstOrNull { it.id == DEFAULT_LLM_ID } ?: LLM.first()
    fun defaultAsr(): AsrProvider = ASR.firstOrNull { it.id == DEFAULT_ASR_ID } ?: ASR.first()

    fun asrById(id: String): AsrProvider? = ASR.firstOrNull { it.id == id }

    /** 根据已保存的 base 反推属于哪个服务商（用于设置页回显） */
    fun guessLlmId(base: String): String {
        if (base.isBlank()) return "custom"
        val b = base.lowercase()
        return when {
            b.contains("bigmodel.cn") -> "zhipu"
            b.contains("dashscope") -> "qwen"
            b.contains("deepseek") -> "deepseek"
            b.contains("siliconflow") -> "siliconflow"
            b.contains("moonshot") -> "moonshot"
            b.contains("openai.com") -> "openai"
            else -> "custom"
        }
    }

    fun guessAsrId(url: String): String {
        if (url.isBlank()) return "custom-asr"
        val u = url.lowercase()
        return when {
            u.contains("siliconflow") -> "siliconflow-asr"
            u.contains("openai.com") -> "openai-whisper"
            else -> "custom-asr"
        }
    }
}
