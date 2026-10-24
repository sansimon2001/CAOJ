package com.example.mindball.ai

/**
 * 摘要质量守卫（对应反馈「AI 总结跑题乱写」的出口把关）
 *
 * 大模型偶尔会编造不存在的人名、数字、待办。这里做规则校验：
 * 1) 摘要里的数字/金额/百分比，必须能在原文找到
 * 2) 摘要的关键短语要能在原文中对上（子串回扣）
 * 3) 待办如果找不到原文依据，直接丢弃（宁可少，不可编）
 *
 * 校验不通过的记录会被标记为「需人工确认」，而不是静默当真的展示。
 */
object SummaryGuard {

    data class Verdict(
        val ok: Boolean,
        /** 需要人工确认的原因（ok=true 时为空） */
        val reason: String,
        /** 过滤后的待办（去掉无依据的） */
        val todos: List<String>,
        /** 回扣命中率，便于调试与展示 */
        val overlap: Double,
    )

    /** 校验摘要与待办 */
    fun check(transcript: String, title: String, summary: String, todos: List<String>): Verdict {
        val src = normalize(transcript)
        if (src.length < 20) {
            // 原文太短，无从校验，但也不必标红（多为灵感速记）
            return Verdict(true, "", todos, 1.0)
        }

        val reasons = ArrayList<String>()

        // 1) 数字回扣：编造金额/日期/数量是最常见幻觉
        val numbers = extractNumbers(summary) + extractNumbers(title)
        if (numbers.isNotEmpty()) {
            val missing = numbers.filter { !src.contains(it) }
            if (missing.size * 2 > numbers.size) {
                reasons.add("摘要中的数字在原文找不到（${missing.take(3).joinToString("、")}）")
            }
        }

        // 2) 关键短语回扣
        val phrases = extractPhrases(summary)
        val hit = phrases.count { src.contains(normalize(it)) }
        val overlap = if (phrases.isEmpty()) 1.0 else hit.toDouble() / phrases.size
        if (phrases.size >= 4 && overlap < 0.35) {
            reasons.add("摘要与原文重合度过低（${(overlap * 100).toInt()}%）")
        }

        // 3) 待办必须能在原文找到依据
        val validTodos = todos.filter { t ->
            val p = extractPhrases(t)
            p.isEmpty() || p.any { src.contains(normalize(it)) }
        }
        if (validTodos.size < todos.size) {
            reasons.add("已过滤 ${todos.size - validTodos.size} 条无原文依据的待办")
        }

        // 4) 摘要过短/过长
        if (summary.length in 1..14) reasons.add("摘要过短，可能未整理充分")

        val blocking = reasons.filterNot { it.startsWith("已过滤") }
        return Verdict(
            ok = blocking.isEmpty(),
            reason = reasons.joinToString("；"),
            todos = validTodos,
            overlap = overlap,
        )
    }

    // ==================== 内部工具 ====================

    private fun normalize(s: String): String =
        s.replace(Regex("""[\s，。！？、,.!?：:；;""''（）()\[\]【】]"""), "")

    /** 提取数字型 token（金额、百分比、日期、数量） */
    private fun extractNumbers(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // 带单位的数字：3万、4.28万元、41%、10月、5号、8套、320万
        Regex("""\d+(?:\.\d+)?(?:万|亿|元|块|%|％|套|个|人|天|月|号|日|点|层|栋|次|万/㎡)""")
            .findAll(text).forEach { out.add(normalize(it.value)) }
        // 纯数字（长度≥2，避免把「1」「2」当幻觉）
        Regex("""(?<![\d.])\d{2,}(?:\.\d+)?""").findAll(text).forEach { out.add(normalize(it.value)) }
        return out.toList()
    }

    /** 提取用于回扣校验的中文短语（3-8 字滑窗，去停用词） */
    private fun extractPhrases(text: String): List<String> {
        val clean = text
            .replace(Regex("""\[[^\]]{1,12}]"""), " ")
            .replace(Regex("""[^\u4e00-\u9fa5A-Za-z0-9]"""), " ")
        val tokens = clean.split(' ').filter { it.length >= 3 }
        val out = LinkedHashSet<String>()
        for (t in tokens) {
            if (t.length <= 8) {
                out.add(t)
            } else {
                var i = 0
                while (i + 6 <= t.length && out.size < 40) {
                    out.add(t.substring(i, i + 6))
                    i += 3
                }
            }
        }
        return out.filterNot { it in STOP }.take(24)
    }

    private val STOP = setOf(
        "这个", "那个", "我们", "他们", "你们", "然后", "所以", "但是", "因为", "如果",
        "可以", "应该", "需要", "就是", "还是", "没有", "什么", "怎么", "已经", "现在",
    )
}
