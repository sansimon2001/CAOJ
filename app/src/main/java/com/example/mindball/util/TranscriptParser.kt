package com.example.mindball.util

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 第三方转写稿解析
 *
 * 为什么要单独写一个解析器：钉钉闪记、飞书妙记、讯飞听见、通义听悟这些产品导出的
 * 「文稿」格式完全不一样，但内容都是同一件事——**谁在什么时候说了什么**。
 * 草记要做的是把它们的导出物收敛成一份纯对白文本，剩下的摘要/待办交给自己的流水线，
 * 这样用户在哪家开会都能进同一个知识库。
 *
 * 支持（按文件扩展名 + 内容双重判断）：
 * - `.srt` / `.vtt`：去掉序号与时间轴，保留台词；能识别 `张三：内容` 这类说话人前缀
 * - `.txt` / `.md`：原样保留，但会剔除「说话人1 00:00」这类行内时间戳堆砌
 * - `.docx`：解压 `word/document.xml` 后按段落抽文本（钉钉/飞书最常导出的就是 docx）
 * - `.json`：尽力而为地找 `transcript` / `text` / `segments[]` 结构
 *
 * 一律做长度上限保护：转写接口与大模型都有输入上限，超长内容截断并注明。
 */
object TranscriptParser {

    /** 单次导入的文本上限（约 20 万字，远超任何一次会议的实际长度） */
    private const val MAX_CHARS = 200_000

    data class Parsed(
        /** 规范化后的纯对白文本 */
        val text: String,
        /** 识别到的说话人数（0 = 没识别出说话人标签） */
        val speakerCount: Int,
        /** 建议标题（多为文件名） */
        val title: String,
        /** 格式标识，用于界面回显：srt / vtt / txt / docx / json */
        val format: String,
    )

    /** 扩展名 → 是否是「转写稿」这类可当文本导入的文件 */
    fun isTranscriptExt(ext: String): Boolean =
        ext.lowercase() in setOf("txt", "md", "srt", "vtt", "json", "csv", "docx")

    /**
     * @param fileName 只用来判断扩展名与生成标题
     * @param bytes 文件内容（调用方负责限制体积，勿传超大文件）
     */
    fun parse(fileName: String, bytes: ByteArray): Parsed? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val baseTitle = fileName.substringBeforeLast('.').trim().ifBlank { "导入的转写稿" }

        val raw = when (ext) {
            "docx" -> parseDocx(bytes)
            else -> String(bytes, Charsets.UTF_8).trimStart('\uFEFF')
        } ?: return null
        if (raw.isBlank()) return null

        val (body, format) = when {
            ext == "srt" || looksLikeSrt(raw) -> cleanCues(raw, isVtt = false) to "srt"
            ext == "vtt" || looksLikeVtt(raw) -> cleanCues(raw, isVtt = true) to "vtt"
            ext == "json" -> parseJson(raw)?.let { it to "json" } ?: (raw to "txt")
            ext == "docx" -> normalizePlain(raw) to "docx"
            else -> normalizePlain(raw) to "txt"
        }
        val text = clamp(body)
        if (text.isBlank()) return null
        return Parsed(
            text = text,
            speakerCount = countSpeakers(text),
            title = baseTitle,
            format = format,
        )
    }

    /** 说话人标签：`说话人1：` / `张三:` / `[00:12] 李四 ` 这几种都能认出来 */
    private val SPEAKER_LINE = Regex("^\\s*(?:\\[[^\\]]{1,12}]\\s*)?([\\u4e00-\\u9fa5A-Za-z][\\u4e00-\\u9fa5A-Za-z0-9·._-]{0,11})\\s*[：:]\\s*")

    private fun looksLikeSrt(s: String): Boolean =
        s.contains("-->") && Regex("(?m)^\\s*\\d+\\s*$").containsMatchIn(s)

    private fun looksLikeVtt(s: String): Boolean = s.trimStart().startsWith("WEBVTT")

    /**
     * 处理 SRT / VTT：只保留台词
     *
     * 关键点不是「去掉时间轴」这么简单——同一句话常被切成 3 条 cue，
     * 逐条输出会变成一行一句的碎片。这里按说话人做**连读合并**：
     * 同一个说话人连续的多条 cue 合成一段，读起来才像对话。
     */
    private fun cleanCues(raw: String, isVtt: Boolean): String {
        val out = StringBuilder()
        var lastSpeaker = ""
        var paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotBlank()) {
                out.append(paragraph.toString().trim()).append("\n\n")
                paragraph = StringBuilder()
            }
        }

        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t == "WEBVTT" || t.startsWith("NOTE ") || t.startsWith("STYLE")) continue
            if (t.contains("-->")) continue                       // 时间轴
            if (t.matches(Regex("^\\d{1,6}$"))) continue          // cue 序号
            if (isVtt && t.startsWith("Kind:") || isVtt && t.startsWith("Language:")) continue

            val m = SPEAKER_LINE.find(t)
            val speaker = m?.groupValues?.getOrNull(1).orEmpty()
            val content = if (speaker.isNotBlank()) t.substring(m!!.range.last + 1).trim() else t

            if (speaker.isNotBlank() && speaker != lastSpeaker) {
                flush()
                lastSpeaker = speaker
                paragraph.append(speaker).append("：")
            }
            if (paragraph.isNotEmpty() && !paragraph.endsWith("：")) paragraph.append(" ")
            paragraph.append(content)
            // 没有说话人标签的字幕会一路合并成一大坨，按段落长度兜一下上限
            if (paragraph.length > 800) flush()
        }
        flush()
        return out.toString().trim()
    }

    /**
     * 纯文本规范化
     *
     * 三类脏数据各有各的处理：
     * 1. 行内时间戳（`00:12:34` / `[00:12]`）——删掉，保留后面的台词
     * 2. **说话人独占一行且带时间戳**（钉钉闪记常见：`说话人1 00:12`）——识别成段落头，
     *    后续内容归这位说话人，直到出现下一个段落头
     * 3. 说话人与台词同一行但用冒号分隔——直接规范成 `说话人：台词`
     */
    private fun normalizePlain(raw: String): String {
        val out = StringBuilder()
        var currentSpeaker = ""
        var lastEmitted = ""
        val timeOnly = Regex("^\\[?\\d{1,2}:\\d{2}(:\\d{2})?]?$")
        val timePrefix = Regex("^\\[?\\d{1,2}:\\d{2}(:\\d{2})?]?\\s*")
        // `说话人1 00:12` / `张三 [00:12:05]`：名字 + 时间戳独占一行 = 段落头
        val header = Regex("^\\s*(\\S{1,12})\\s+\\[?\\d{1,2}:\\d{2}(:\\d{2})?]?\\s*$")

        for (line in raw.lines()) {
            var t = line.trim()
            if (t.isEmpty()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n")
                continue
            }
            val h = header.matchEntire(t)
            if (h != null) {
                val name = h.groupValues[1]
                // 名字里带标点的多半是正文误判（"今天 10:30 开会"这类），只认干净短名字
                if (name.none { it in "。，,.;；:：" }) {
                    currentSpeaker = name
                    lastEmitted = ""
                    continue
                }
            }
            if (timeOnly.matches(t)) continue
            t = timePrefix.replace(t, "").trim()
            if (t.isEmpty()) continue

            val m = SPEAKER_LINE.find(t)
            if (m != null) {
                val speaker = m.groupValues[1]
                val rest = t.substring(m.range.last + 1).trim()
                if (rest.isEmpty()) {
                    currentSpeaker = speaker
                    lastEmitted = ""
                    continue
                }
                out.append(speaker).append("：").append(rest).append("\n")
                currentSpeaker = speaker
                lastEmitted = speaker
            } else if (currentSpeaker.isNotBlank() && currentSpeaker != lastEmitted) {
                out.append(currentSpeaker).append("：").append(t).append("\n")
                lastEmitted = currentSpeaker
            } else {
                out.append(t).append("\n")
            }
        }
        return out.toString().trim()
    }

    /**
     * JSON：各家结构差异大，按「最可能的几个字段」依次尝试
     * - {"transcript": "..."} / {"text": "..."}
     * - {"segments":[{"speaker":"张三","text":"..."}]}（飞书/讯飞常见）
     * - 顶层数组同上
     */
    private fun parseJson(raw: String): String? = runCatching {
        val root = org.json.JSONTokener(raw).nextValue()
        val sb = StringBuilder()

        fun walk(node: Any?) {
            when (node) {
                is org.json.JSONObject -> {
                    val text = node.optString("transcript").ifBlank { node.optString("text") }
                    val speaker = node.optString("speaker").ifBlank { node.optString("speakerName") }
                    if (text.isNotBlank()) {
                        if (speaker.isNotBlank()) sb.append(speaker).append("：")
                        sb.append(text.trim()).append("\n")
                        return
                    }
                    val segs = node.optJSONArray("segments")
                        ?: node.optJSONArray("sentences")
                        ?: node.optJSONArray("utterances")
                    if (segs != null) {
                        for (i in 0 until segs.length()) walk(segs.opt(i))
                        return
                    }
                    // 兜底：遍历所有值，找最大的那段字符串
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val v = node.opt(keys.next())
                        if (v is String && v.length > 40) sb.append(v.trim()).append("\n")
                    }
                }
                is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
            }
        }
        walk(root)
        sb.toString().trim().ifBlank { null }
    }.getOrNull()

    /** docx = zip + word/document.xml，抽出段落文本即可（不引第三方库） */
    private fun parseDocx(bytes: ByteArray): String? = runCatching {
        var xml: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                    break
                }
                entry = zip.nextEntry
            }
        }
        val doc = xml ?: return null
        // </w:p> 转换行，<w:tab/> 转空格，其余标签全去掉
        doc.replace("</w:p>", "\n")
            .replace("<w:tab/>", " ")
            .replace("<w:br/>", "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .trim()
    }.getOrNull()

    private fun countSpeakers(text: String): Int =
        text.lines().asSequence()
            .mapNotNull { SPEAKER_LINE.find(it)?.groupValues?.getOrNull(1) }
            .filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            .distinct()
            .take(30)
            .count()

    private fun clamp(text: String): String {
        val t = text.trim()
        if (t.length <= MAX_CHARS) return t
        return t.take(MAX_CHARS) + "\n\n…（内容过长，已截断到 ${MAX_CHARS / 1000} 千字）"
    }
}
