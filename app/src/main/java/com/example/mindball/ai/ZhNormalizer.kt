package com.example.mindball.ai

import android.content.Context
import android.util.Log

/**
 * 繁简与文本清理
 *
 * 为什么必须有这一步：
 * 粤语（yue）与港台口音的识别结果，云端**默认就吐繁体**——Whisper 出「今日嘅會議」、
 * SenseVoice 出「會議記錄」都很正常。中文用户读简体，摘要与待办里再混着繁体，
 * 既割裂又容易在搜索时漏掉（同一个词两种字形）。
 *
 * 表来自 OpenCC 的 TSCharacters（Apache-2.0），只收单字一对一的可靠映射：
 * - 一对多的歧义字（著 / 台 / 面 / 里 / 云 / 松 / 乾）**故意不收**，避免把
 *   「著名」转成「着名」、「乾隆」转成「干隆」这类经典翻车；
 * - 粤语用字（唔 / 嘅 / 咗 / 佢 / 哋）本身没有简化形，转换后仍在，语义不受影响。
 *
 * 表打包在 `assets/t2s.txt`（每行 40 组、每组两字），首用加载一次常驻内存（约 3.2k 组）。
 */
object ZhNormalizer {

    /** 简体化时不参与的歧义字：宁可不转，也不要转错 */
    private val AMBIGUOUS = setOf('乾')

    @Volatile
    private var map: HashMap<Char, Char>? = null

    @Volatile
    private var keySet: Set<Char>? = null

    private fun ensure(context: Context): HashMap<Char, Char> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val m = HashMap<Char, Char>(4096)
            runCatching {
                context.applicationContext.assets.open("t2s.txt").bufferedReader().use { r ->
                    // 先去掉换行再按两字一组切，避免行宽变化导致错位
                    val buf = r.readText().filter { !it.isWhitespace() }
                    var i = 0
                    // 每两字一组：繁体在前、简体在后
                    while (i + 1 < buf.length) {
                        val t = buf[i]
                        val s = buf[i + 1]
                        i += 2
                        if (t == s || t in AMBIGUOUS) continue
                        m[t] = s
                    }
                }
            }
            // 表没打进包（assets 漏了）时不要静默失效：繁转简会变成空操作，
            // 表现为「粤语稿还是繁体」，很难从界面上看出原因
            if (m.isEmpty()) {
                Log.w("ZhNormalizer", "assets/t2s.txt 缺失或解析为空，繁转简已降级为不转换")
            }
            map = m
            keySet = m.keys
            return m
        }
    }

    /**
     * 繁体 → 简体
     *
     * 对已经是简体的文本安全（表里只有繁体字做 key，简体字原样返回），
     * 因此转写结果是混排时也可以无脑调用。
     */
    fun toSimplified(context: Context, text: String): String {
        if (text.isBlank()) return text
        val m = ensure(context)
        if (m.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(m[ch] ?: ch)
        }
        return sb.toString()
    }

    /** 快速判断是否含繁体字（避免对纯简体文本做无谓的整串替换） */
    fun hasTraditional(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        val keys = keySet ?: run { ensure(context); keySet } ?: return false
        for (ch in text) {
            if (ch in keys) return true
        }
        return false
    }

    /**
     * 通用清理：去掉零宽字符与 BOM（部分服务端会在流式结果里带）
     * 这些字符在 UI 上看不见，但会让「一键复制」「搜索」失效。
     *
     * 一次遍历完成。原来对每个字符各做一次全串 `replace`，
     * 一万字的转写稿要扫五遍、产生五个中间字符串。
     */
    fun stripInvisible(text: String): String {
        if (text.isEmpty()) return text
        var i = 0
        while (i < text.length && !isInvisible(text[i])) i++
        if (i == text.length) return text          // 常见情况：一个都没有，零分配
        val sb = StringBuilder(text.length)
        sb.append(text, 0, i)
        for (j in i until text.length) {
            val c = text[j]
            if (!isInvisible(c)) sb.append(c)
        }
        return sb.toString()
    }

    private fun isInvisible(c: Char): Boolean = when (c) {
        '\u200B', '\u200C', '\u200D', '\uFEFF', '\u2060' -> true
        else -> false
    }
}
