package com.example.mindball.ai

/**
 * 识别语言 / 中国方言清单与「服务端能力映射」
 *
 * 为什么需要这一层：
 * 各家转写服务的 `language` 参数口径并不一致——
 * - OpenAI Whisper 只认 ISO-639-1（zh / en / ja / ko），传 `yue` 这类三位代码有的版本直接 400；
 * - 硅基流动 SenseVoice 反而原生支持 `yue`（粤语）；
 * - `wuu`（吴语）、`nan`（闽南语）、`hak`（客家话）目前**没有任何**主流服务在
 *   Whisper 兼容协议里直接支持，只能按 `zh` 送，靠模型自身能力，识别质量下降是必然的。
 *
 * 所以 App 里不能只存一个字符串，必须存「用户想识别什么」，再由这里按所选服务
 * 换算成「实际能发什么」，并把降级事实如实告诉用户——否则用户选了上海话、
 * 实际一直是按普通话在认，却以为是自己口齿不清。
 */
data class SpeechLang(
    val id: String,
    val label: String,
    /** 传给服务端的 language 字面值（auto 表示自动） */
    val requestCode: String,
    /** 是否为中国方言（决定要不要做「方言 → 普通话」归一化） */
    val isDialect: Boolean,
    /** 是否保留繁体输出（不套用繁转简） */
    val keepTraditional: Boolean = false,
    /** 是否非中文语种（决定摘要提示词要不要写「请用中文输出」） */
    val foreign: Boolean = false,
    /** 设置页说明 */
    val hint: String = "",
    /** 本地检测用的特征词（方言识别前先猜一下用户说的是哪一种） */
    val detectWords: List<String> = emptyList(),
)

object SpeechLangs {

    const val ID_AUTO = "auto"
    const val ID_ZH = "zh"
    const val ID_YUE = "yue"

    // ==================== 清单 ====================

    val ALL: List<SpeechLang> = listOf(
        SpeechLang(
            id = ID_AUTO,
            label = "自动识别（多语种场景）",
            requestCode = "auto",
            isDialect = false,
            hint = "只在中英混说、或经常切换语种时才选它。中文对话请选「普通话（简体）」——" +
                "自动判断在短句、带口音、有背景人声时容易猜错，猜错会让整段文字跟着走样。",
        ),
        SpeechLang(
            id = ID_ZH,
            label = "普通话（简体）",
            requestCode = "zh",
            isDialect = false,
            hint = "最稳的选择：强制按普通话识别，输出简体中文。",
        ),
        SpeechLang(
            id = "zh_hant",
            label = "普通话（保留繁体）",
            requestCode = "zh",
            isDialect = false,
            keepTraditional = true,
            hint = "港台用语、繁体字习惯场景用。App 不会把繁体转成简体。",
        ),
        SpeechLang(
            id = ID_YUE,
            label = "粤语（广东话）",
            requestCode = "yue",
            isDialect = true,
            hint = "SenseVoice 与 Whisper 都能出粤语文本，常带「唔 / 嘅 / 咗 / 佢」等粤语用字；" +
                "不认识粤语的读者可打开下面的「方言自动转普通话」。",
            detectWords = listOf("唔", "嘅", "咗", "佢", "係", "喺", "哋", "咁", "乜", "嚟", "冇", "睇", "嗰", "噉", "俾", "冇問題"),
        ),
        SpeechLang(
            id = "wuu",
            label = "吴语（上海话 / 苏州话）",
            requestCode = "wuu",
            isDialect = true,
            hint = "目前没有主流云服务在 Whisper 协议里直接支持吴语，会按普通话识别，口音重时错字偏多，" +
                "建议保留原音并人工校对。",
            detectWords = listOf("侬", "阿拉", "勿要", "白相", "哪能", "搿", "迭个", "勿", "伊拉", "邪气"),
        ),
        SpeechLang(
            id = "nan",
            label = "闽南语（福建 / 台湾）",
            requestCode = "nan",
            isDialect = true,
            hint = "同吴语：服务端无原生支持，按普通话识别为主；闽南语用字（阮 / 厝 / 毋）可能被改写成普通话词。",
            detectWords = listOf("阮", "汝", "厝", "夭寿", "食饱", "拢总", "毋", "偌", "嘛是", "阿公"),
        ),
        SpeechLang(
            id = "hak",
            label = "客家话",
            requestCode = "hak",
            isDialect = true,
            hint = "服务端无原生支持，按普通话识别；建议配合热词表提升专名准确率。",
            detectWords = listOf("𠊎", "恁", "暗晡", "头家", "毋", "係", "食朝", "打斗叙"),
        ),
        SpeechLang(
            id = "hsn",
            label = "湘语（湖南话）",
            requestCode = "hsn",
            isDialect = true,
            hint = "服务端无原生支持，按普通话识别；语气词（咯 / 哒）可能被吃掉，不影响大意。",
            detectWords = listOf("咯", "哒", "莫", "何解", "霸蛮", "呷饭", "郎家"),
        ),
        SpeechLang(
            id = "cjy",
            label = "晋语（山西话）",
            requestCode = "cjy",
            isDialect = true,
            hint = "服务端无原生支持，按普通话识别。",
            detectWords = listOf("甚", "圪", "俺", "咋", "甚会儿"),
        ),
        SpeechLang(
            id = "sichuan",
            label = "四川话（西南官话）",
            requestCode = "zh",
            isDialect = true,
            hint = "属西南官话，与普通话字面接近，按 zh 识别成功率已经不错；有口音时可加热词表。",
            detectWords = listOf("啥子", "巴适", "晓得", "咋个", "安逸", "龙门阵", "啷个", "要得", "耍", "莫得", "瓜娃子"),
        ),
        SpeechLang(
            id = "dongbei",
            label = "东北话",
            requestCode = "zh",
            isDialect = true,
            hint = "接近普通话，按 zh 识别即可；口语词（咋整 / 贼拉 / 唠嗑）一般能正常出字。",
            detectWords = listOf("咋整", "贼拉", "唠嗑", "埋汰", "得瑟", "忽悠", "整点儿", "老鼻子"),
        ),
        SpeechLang(
            id = "mixed",
            label = "中英混合（夹英文）",
            requestCode = "auto",
            isDialect = false,
            hint = "一段话里中英混说时用。Whisper 系效果最好；纯中文引擎会把英文按发音硬转成汉字。",
        ),
        SpeechLang(
            id = "en",
            label = "English",
            requestCode = "en",
            isDialect = false,
            foreign = true,
            hint = "原文为英文，摘要仍用简体中文输出，专有名词保留英文。",
        ),
        SpeechLang(
            id = "ja",
            label = "日本語",
            requestCode = "ja",
            isDialect = false,
            foreign = true,
            hint = "原文为日文，摘要用简体中文输出，人名/公司名保留原文。",
        ),
        SpeechLang(
            id = "ko",
            label = "한국어",
            requestCode = "ko",
            isDialect = false,
            foreign = true,
            hint = "原文为韩文，摘要用简体中文输出，专有名词保留原文。",
        ),
    )

    fun byId(id: String): SpeechLang = ALL.firstOrNull { it.id == id } ?: ALL[1]

    /** 下拉框回显：找不到时回落到默认项（与 [byId] 一致），避免选中项跑偏 */
    fun indexOf(id: String): Int {
        val target = byId(id)                       // 只查一次，原来在 indexOfFirst 的闭包里重复查
        return ALL.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
    }

    // ==================== 服务端能力换算 ====================

    /**
     * 实际发出去的 `language` 值
     *
     * @param requestValue null 表示**不发这个字段**（让服务端自己判断）
     * @param downgraded 是否发生了「用户选的方言不被支持」的降级
     * @param note 给用户看的一句实话；无降级为空
     */
    data class Plan(
        val requestValue: String?,
        val downgraded: Boolean,
        val note: String,
    )

    fun plan(lang: SpeechLang, provider: AsrProvider): Plan {
        // 1) 自动识别：支持 auto 字面量的服务才发；不支持的不发字段（等价于自动）
        if (lang.id == ID_AUTO || lang.id == "mixed") {
            return if (provider.acceptsAuto) {
                Plan("auto", false, "")
            } else {
                Plan(null, false, "")
            }
        }

        // 2) 服务端明确支持这个代码 → 直接用
        if (provider.requestLangs.contains(lang.requestCode)) {
            return Plan(lang.requestCode, false, "")
        }

        // 3) 不支持：方言按普通话送（保证出中文），外语按 zh 送毫无意义 → 交给自动检测
        return if (lang.foreign) {
            Plan(null, true, "${provider.name} 未声明支持「${lang.label}」，改为自动判断语种")
        } else {
            val extra = if (provider.dialectNote.isBlank()) "" else provider.dialectNote + "；"
            Plan(
                "zh",
                true,
                "$extra${provider.name} 不支持「${lang.label}」，本次按普通话识别：" +
                    "方言词可能被改写成普通话表达，人名与专名建议核对。"
            )
        }
    }

    // ==================== 本地语种/方言检测 ====================

    data class Detect(
        val langId: String,
        val label: String,
        val hits: Int,
        val sample: List<String>,
        val reason: String,
    )

    /**
     * 从转写文本反推「实际说的是什么方言」
     *
     * 用途不是替代服务端识别，而是**事后纠错**：
     * 用户选了普通话，结果文本里满是「唔、嘅、咗」，说明选错了；
     * 这时在详情页提示「疑似粤语，可切到粤语重转」，比让用户对着一堆错字猜原因有用。
     *
     * 阈值：特征词命中 ≥3 次，且每千字命中 ≥10 次（避免个别词偶然出现就误判）。
     */
    fun detect(text: String): Detect? {
        if (text.length < 20) return null
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        if (cjk < 10) {
            // 纯外文：按拉丁/假名/谚文占比给个语种建议
            val latin = text.count { it.isLetter() && it.code < 0x0300 }
            val kana = text.count { it.code in 0x3040..0x30FF }
            val hangul = text.count { it.code in 0xAC00..0xD7AF }
            val total = text.count { !it.isWhitespace() }.coerceAtLeast(1)
            return when {
                kana * 100 / total > 15 -> Detect("ja", "日本語", kana, emptyList(), "假名占比 ${kana * 100 / total}%")
                hangul * 100 / total > 15 -> Detect("ko", "한국어", hangul, emptyList(), "谚文占比 ${hangul * 100 / total}%")
                latin * 100 / total > 70 -> Detect("en", "English", latin, emptyList(), "拉丁字母占比 ${latin * 100 / total}%")
                else -> null
            }
        }

        var best: Detect? = null
        ALL.filter { it.detectWords.isNotEmpty() }.forEach { lang ->
            var hits = 0
            val sample = ArrayList<String>()
            lang.detectWords.forEach { w ->
                val n = countOf(text, w)
                if (n > 0) {
                    hits += n
                    if (sample.size < 5) sample.add(w)
                }
            }
            val perK = hits * 1000 / cjk
            if (hits >= 3 && perK >= 10) {
                if (best == null || hits > best!!.hits) {
                    best = Detect(lang.id, lang.label, hits, sample, "特征词 ${sample.joinToString("、")} 共 $hits 处")
                }
            }
        }

        // 中英混说：拉丁词占比 15%~60%，且中文也占一定比例
        if (best == null) {
            val latinWords = Regex("[A-Za-z][A-Za-z'\\-]{1,}").findAll(text).count()
            val total = cjk + latinWords * 2
            if (latinWords >= 3 && total > 0) {
                val pct = latinWords * 200 / total
                if (pct in 15..60) {
                    return Detect("mixed", "中英混合", latinWords, emptyList(), "英文词 $latinWords 个，约占 $pct%")
                }
            }
        }
        return best
    }

    private fun countOf(text: String, word: String): Int {
        if (word.isEmpty()) return 0
        var i = text.indexOf(word)
        var n = 0
        while (i >= 0) {
            n++
            i = text.indexOf(word, i + word.length)
        }
        return n
    }
}
