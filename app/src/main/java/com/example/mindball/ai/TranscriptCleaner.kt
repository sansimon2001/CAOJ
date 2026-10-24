package com.example.mindball.ai

/**
 * 转写文本清洗与分块（对应反馈「AI 总结跑题乱写」的前置治理）
 *
 * 问题根源：原始转写里有大量重复、语气词、无意义的短句与长上下文，
 * 模型容易被带跑，或把闲聊当成重点。这里做两件事：
 * 1) 清洗：去重复行、去纯语气词、合并过碎片段
 * 2) 分块：超过阈值时切成若干块，走「先抽要点、再写摘要」的 map-reduce，
 *    避免一次塞太长的文本导致抓不住重点
 */
object TranscriptCleaner {

    /** 单块目标字数（中文按字符算） */
    const val CHUNK_SIZE = 1800

    /** 纯语气词/填充词，整行只有这些则丢弃 */
    private val FILLER = setOf(
        "嗯", "啊", "哦", "呃", "额", "欸", "这个", "那个", "就是", "然后", "对吧", "是吧",
        "好的", "行", "嗯嗯", "哦哦", "对的", "是的", "哈哈", "呵呵",
    )

    /** 常见无信息量片段（出现即整行丢弃） */
    private val NOISE_PHRASES = listOf(
        "喂喂", "能听到吗", "听得到吗", "信号不好", "稍等", "等一下啊",
    )

    fun clean(transcript: String): String {
        if (transcript.isBlank()) return ""

        val lines = transcript.split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            // 去掉说话人标记后的纯内容，用于判断信息量
            val content = line.replace(Regex("""^\[[^\]]{1,12}]\s*"""), "").trim()
            if (content.isEmpty()) continue

            // 纯语气词
            val stripped = content.replace(Regex("""[，。！？、,.!?~\s]"""), "")
            if (stripped.isEmpty()) continue
            if (stripped in FILLER) continue

            // 无信息片段
            if (NOISE_PHRASES.any { content.contains(it) } && content.length < 16) continue

            // 连续重复行去重（同一句被 ASR 重复输出）
            if (kept.isNotEmpty() && kept.last() == line) continue

            kept.add(line)
        }

        // 相邻高度相似行折叠（相似度用「短的一半是长的子串」近似）
        val collapsed = ArrayList<String>(kept.size)
        for (line in kept) {
            val prev = collapsed.lastOrNull()
            if (prev != null) {
                val a = prev.replace(Regex("""^\[[^\]]{1,12}]\s*"""), "")
                val b = line.replace(Regex("""^\[[^\]]{1,12}]\s*"""), "")
                val shorter = if (a.length <= b.length) a else b
                val longer = if (a.length <= b.length) b else a
                if (shorter.length >= 6 && longer.contains(shorter)) continue
            }
            collapsed.add(line)
        }

        return collapsed.joinToString("\n")
    }

    /** 按段落/句末切块，尽量不把一句话截断 */
    fun chunk(text: String, size: Int = CHUNK_SIZE): List<String> {
        if (text.length <= size) return listOf(text)

        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        // 先按行切成单元，行过长再按句号切
        val units = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.length <= size) {
                units.add(line)
            } else {
                var rest = line
                while (rest.length > size) {
                    val cut = rest.lastIndexOf('。', size).let { if (it > 0) it + 1 else size }
                    units.add(rest.substring(0, cut))
                    rest = rest.substring(cut)
                }
                if (rest.isNotBlank()) units.add(rest)
            }
        }

        for (u in units) {
            if (sb.length + u.length + 1 > size && sb.isNotEmpty()) {
                chunks.add(sb.toString().trim())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(u)
        }
        if (sb.isNotBlank()) chunks.add(sb.toString().trim())
        return chunks
    }

    /** 有效字数（去掉说话人标记与标点） */
    fun effectiveLength(text: String): Int =
        text.replace(Regex("""\[[^\]]{1,12}]"""), "")
            .replace(Regex("""[\s，。！？、,.!?~]"""), "")
            .length
}
