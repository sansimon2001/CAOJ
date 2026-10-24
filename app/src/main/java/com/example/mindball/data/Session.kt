package com.example.mindball.data

import org.json.JSONArray

/** 一条录音会话（对应心力球的一次「事件」） */
data class Session(
    var id: Long = 0,
    /** 自动生成的标题 */
    var title: String = "未命名记录",
    /** 原始转写文本 */
    var transcript: String = "",
    /**
     * 方言归一化后的普通话书面文本
     *
     * 粤语 / 吴语等方言的转写稿是「听感原话」（唔该晒、阿拉、啥子），
     * 本人看得懂、旁人看不懂，喂给大模型也容易抓错重点。
     * 这里存一份「同一个意思的普通话书面表达」，摘要用它、详情页可切换显示。
     * 空表示没有做归一化（普通话录音、或用户关了开关）。
     */
    var transcriptStd: String = "",
    /** 本条使用的识别语言/方言 id（见 ai/SpeechLang），空=未记录（老数据） */
    var lang: String = "",
    /** AI 摘要 */
    var summary: String = "",
    /** 归属项目（可为空） */
    var project: String = "",
    /**
     * 项目归属的置信度（0-1）与判定依据
     *
     * 只在 0.6 以上才当「已确认」；低于这个值说明只是线索像、没有硬证据，
     * 详情页会显示「待确认」并给出可改的入口。0=老数据/未判定。
     */
    var projectConf: Double = 0.0,
    var projectWhy: String = "",
    /** 来源：live=耳机触发的实时录音；call=系统通话录音导入 */
    var source: String = SOURCE_LIVE,
    /** 音频文件路径（转写后可删除以实现「只留文字不留原音」） */
    var audioPath: String = "",
    var startAt: Long = 0,
    var endAt: Long = 0,
    var durationMs: Long = 0,
    /** 是否被短按标记为重点 */
    var marked: Boolean = false,
    /** 重点标记的时间点（毫秒偏移，JSON 数组字符串，如 "[15200,47300]"） */
    var marks: String = "",
    /** 录音环境类型：quiet 独处 / meeting 会议 / street 嘈杂 / unknown */
    var envType: String = ENV_UNKNOWN,
    /** 环境噪声底噪（dBFS）与有效人声占比（0-1），用于展示与质量判断 */
    var noiseDb: Double = 0.0,
    var speechRatio: Double = 0.0,
    /** 声纹识别出的「我」对应的说话人标签（空=未识别/未录入声纹） */
    var voiceSpeaker: String = "",
    /** 声纹相似度（中心化余弦，越大越像）与领先第二名的幅度 */
    var voiceScore: Double = 0.0,
    var voiceMargin: Double = 0.0,
    /** 摘要质量：ok 可信 / review 需人工确认（关键词回扣不足或模型输出可疑） */
    var quality: String = QUALITY_OK,
    /** recorded=已录音待转写；transcribed=已转写；analyzed=已生成摘要 */
    var status: String = STATUS_RECORDED,
    var createdAt: Long = System.currentTimeMillis(),
) {
    val isTranscribed: Boolean get() = status == STATUS_TRANSCRIBED || status == STATUS_ANALYZED

    companion object {
        const val ENV_QUIET = "quiet"       // 安静独处
        const val ENV_MEETING = "meeting"   // 会议/多人交谈
        const val ENV_STREET = "street"     // 嘈杂环境（街道/餐厅）
        const val ENV_UNKNOWN = "unknown"

        const val QUALITY_OK = "ok"
        const val QUALITY_REVIEW = "review"

        const val SOURCE_LIVE = "live"      // 耳机触发的连续记录
        const val SOURCE_CALL = "call"      // 系统通话录音导入
        const val SOURCE_VOICE = "voice"    // 长按说话：灵感笔记 / 即时问答
        const val SOURCE_SHARE = "share"    // 从其它应用「分享到草记」的纯文本
        const val SOURCE_IMPORT = "import"  // 外部录音文件导入（录音笔 / 钉钉闪记 / 飞书妙记…）
        const val SOURCE_IMPORT_TEXT = "import_text" // 第三方转写稿导入（srt/vtt/txt/docx/json）

        const val STATUS_RECORDED = "recorded"
        const val STATUS_TRANSCRIBED = "transcribed"
        const val STATUS_ANALYZED = "analyzed"
    }
}

/** 待办事项 */
data class TodoItem(
    var id: Long = 0,
    var sessionId: Long = 0,
    var text: String = "",
    var done: Boolean = false,
    /** 到期日 YYYY-MM-DD，空=无期限 */
    var dueDate: String = "",
    /**
     * 这条待办自己归属的项目
     *
     * 会话级 project 只表达「这次主要在谈谁」，但一次会议完全可能同时落下
     * A 项目的报价和 B 项目的排期。待办级归属才不会把 A 的事算到 B 头上。
     * 空=沿用所属会话的项目。
     */
    var project: String = "",
    var createdAt: Long = System.currentTimeMillis(),
)

/** 大模型产出的待办草稿（未入库） */
data class TodoDraft(
    val text: String,
    val dueDate: String = "",
    /** 模型给出的该项所属项目（已归一化到档案名，可能为空=沿用会话项目） */
    val project: String = "",
)

/** AI 分析结果（一次性产出，便于写入数据库） */
data class AnalyzeResult(
    val title: String,
    val summary: String,
    val todos: List<TodoDraft>,
    val project: String,
    val insight: String,
    /** 项目归属置信度（0-1）与判定依据，供详情页展示「为什么归到这里」 */
    val projectConf: Double = 0.0,
    val projectWhy: String = "",
    /** 本次还提到、但非主项目的其它项目（一次会议跨项目时不为空） */
    val otherProjects: List<String> = emptyList(),
    /** 摘要质量：ok / review（SummaryGuard 校验结果） */
    val quality: String = Session.QUALITY_OK,
    /** 需人工确认的原因 */
    val guardReason: String = "",
)

/** 解析重点时间点（毫秒偏移列表） */
internal fun parseMarks(json: String): List<Long> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.optLong(it) }.filter { it > 0 }
    }.getOrDefault(emptyList())
}

/** 序列化重点时间点 */
internal fun encodeMarks(marks: List<Long>): String {
    val arr = JSONArray()
    marks.forEach { arr.put(it) }
    return arr.toString()
}
