package com.example.mindball.audio

import android.content.Context
import com.example.mindball.data.Repo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本人声纹：模板的建立、持久化与「哪一段是我」的判定
 *
 * 三个环节：
 * 1) 录入（build）：用户读 15 秒左右自己的语音 → 取有声帧均值向量作为模板
 * 2) 判定（match）：转写拿到带时间戳的说话人分段 → 按段把该说话人的音频特征算出来
 *    → 与模板比对，得分最高且明显领先的那个说话人就是「我」
 * 3) 打分方式：**中心化余弦**（先减掉所有候选与模板的共同分量再算夹角）
 *    —— 直接算余弦时不同人之间的相似度都很高（0.9+）无法区分；
 *    去掉共同分量后，同人会明显高于他人，这才是可用的判别信号
 *
 * 若判定不够有把握，上层**不覆盖**用户在设置里指定的「我是说话人几」，
 * 只在记录详情里显示「声纹存疑」和分数，交给用户一次点击确认。
 */
object Voiceprint {

    /** 录入得到的模板 */
    data class Template(
        /** L2 归一化后的特征向量（长度 = MfccScanner.DIM） */
        val vec: DoubleArray,
        val seconds: Double,
        val createdAt: Long,
        val sampleRate: Int,
        val voicedRatio: Double,
    )

    data class Candidate(val speaker: String, val score: Double)

    data class Match(
        /** 最像我的说话人标签（可能为空） */
        val best: String,
        /** 相对匹配度 0..1（由「与第二名的差距」换算，可读、可设阈值） */
        val confidence: Double,
        /** 与第二名在中心化余弦上的原始差距（仅用于排查，不给用户看） */
        val margin: Double,
        /** 是否足够有把握自动采用 */
        val accepted: Boolean,
        val candidates: List<Candidate>,
        val reason: String,
    )

    /**
     * 判定门槛
     * - 中心化余弦的**绝对**数值没有可解释意义（不同人之间也只有 0.0x），
     *   真正有判别力的是「第一名比第二名高多少」；
     * 所以用 pairwise logistic 把「差距」换算成 0..1 的匹配度：
     *     confidence = 1 / (1 + exp(-(best - second) / T))
     * T 取 0.008（离线实测标定，见 README「声纹标定」）：
     *   · 明显不同的人（男 vs 女）  差距约 0.09 → 匹配度 ~100%
     *   · 三人会议、其中一人音色接近 差距约 0.013 → 匹配度 ~84%
     *   · 同性别且音色接近          差距约 0.004 → 匹配度 ~63%
     * 门槛 80% 恰好把后两者分开，且两侧都不贴边。
     * 达不到 0.80 就不自动采用——**宁可让用户手点一次，也不猜错说话人**。
     */
    private const val T_MARGIN = 0.008
    private const val MIN_CONFIDENCE = 0.80

    // ==================== 录入 ====================

    /**
     * 由整段录音构建模板
     * @return 模板；有效语音太少时返回 null（提示重录）
     */
    fun build(file: File): Pair<Template?, String> {
        val res = MfccScanner.scan(file, null)
            ?: return null to "音频解析失败，请靠近手机重录"
        val b = res.buckets[MfccScanner.ALL]
            ?: return null to "未提取到有效语音"

        val seconds = res.durationMs / 1000.0
        if (b.voiced < 200) {
            return null to "有效语音太少（${b.voiced * 10 / 1000.0} 秒），请读完提示句再结束"
        }
        if (res.voicedRatio < 0.25) {
            return null to "环境太吵或说话太少（有效人声 ${(res.voicedRatio * 100).toInt()}%），建议换到安静处重录"
        }
        val tpl = Template(
            vec = b.vec,
            seconds = seconds,
            createdAt = System.currentTimeMillis(),
            sampleRate = res.sampleRate,
            voicedRatio = res.voicedRatio,
        )
        return tpl to ""
    }

    // ==================== 判定 ====================

    /**
     * 在本次录音里找出哪个说话人最像「我」
     * @param segments 转写返回的说话人分段（毫秒）
     */
    fun match(
        file: File,
        segments: List<MfccScanner.TimeSegment>,
        template: Template,
    ): Match? {
        val usable = segments.filter { it.endMs > it.startMs && it.label.isNotBlank() }
        val labels = usable.map { it.label }.distinct()
        // 只有一位说话人时没有「区分谁是我」的意义，直接交给已有的手动设置
        // （「我是说话人几」会把唯一标签映射成「我」）
        if (labels.size < 2) return null

        val res = MfccScanner.scan(file, usable) ?: return null

        // 只保留有效语音够多的说话人（太短的分段特征不可信）
        val buckets = res.buckets.values.filter { it.voiced >= 50 && it.label != MfccScanner.ALL }
        // 去掉后不足两人：同样没有比较对象，跳过
        if (buckets.size < 2) return null

        // 中心化余弦：先减掉「模板 + 所有候选」的共同分量
        val all = ArrayList<DoubleArray>(buckets.size + 1)
        all.add(template.vec)
        buckets.forEach { all.add(it.vec) }
        val mean = DoubleArray(MfccScanner.DIM)
        all.forEach { v -> for (i in v.indices) mean[i] += v[i] / all.size }
        fun center(v: DoubleArray) = DoubleArray(v.size) { v[it] - mean[it] }

        val tplC = center(template.vec)
        val cands = buckets.map { b ->
            Candidate(b.label, cosine(tplC, center(b.vec)))
        }.sortedByDescending { it.score }

        val best = cands.first()
        val second = cands.getOrNull(1)
        val margin = if (second != null) best.score - second.score else 1.0
        val confidence = if (second != null) sigmoid(margin / T_MARGIN) else sigmoid(best.score / T_MARGIN)
        val accepted = confidence >= MIN_CONFIDENCE

        val reason = when {
            !accepted && second != null ->
                "声纹把握不足（最像 ${best.speaker}，匹配度 ${pct(confidence)}），已保留你的设置"
            !accepted ->
                "声纹把握不足（匹配度 ${pct(confidence)}），已保留你的设置"
            second != null ->
                "声纹判定：我 = ${best.speaker}（匹配度 ${pct(confidence)}）"
            else ->
                "声纹判定：全场只有 ${best.speaker}（匹配度 ${pct(confidence)}）"
        }

        return Match(
            best = best.speaker,
            confidence = confidence,
            margin = margin,
            accepted = accepted,
            candidates = cands,
            reason = reason,
        )
    }

    // ==================== 存储 ====================

    fun load(context: Context): Template? {
        val raw = Repo.getStr(context, Repo.K_VOICEPRINT)
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("vec") ?: return null
            if (arr.length() != MfccScanner.DIM) return null
            val vec = DoubleArray(arr.length()) { arr.optDouble(it) }
            Template(
                vec = vec,
                seconds = o.optDouble("seconds", 0.0),
                createdAt = o.optLong("createdAt", 0L),
                sampleRate = o.optInt("sampleRate", 16000),
                voicedRatio = o.optDouble("voicedRatio", 0.0),
            )
        }.getOrNull()
    }

    fun save(context: Context, t: Template) {
        val arr = JSONArray()
        t.vec.forEach { arr.put(it) }
        val o = JSONObject()
            .put("vec", arr)
            .put("seconds", t.seconds)
            .put("createdAt", t.createdAt)
            .put("sampleRate", t.sampleRate)
            .put("voicedRatio", t.voicedRatio)
        Repo.setStr(context, Repo.K_VOICEPRINT, o.toString())
    }

    fun clear(context: Context) {
        Repo.setStr(context, Repo.K_VOICEPRINT, "")
    }

    fun hasTemplate(context: Context): Boolean = load(context) != null

    /** 判断某次识别结果是否达到自动采用的门槛（详情页回显用） */
    fun isAccepted(confidence: Double): Boolean = confidence >= MIN_CONFIDENCE
    /** 是否启用「用声纹自动识别我」 */
    fun autoEnabled(context: Context): Boolean = Repo.getBool(context, Repo.K_VOICEPRINT_AUTO, true)

    // ==================== 工具 ====================

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.CHINA, "%.2f", v)

    private fun pct(v: Double): String = String.format(java.util.Locale.CHINA, "%.0f%%", v * 100)

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + Math.exp(-x))

    /** 供 UI 展示的状态文案 */
    fun describe(context: Context): String {
        val t = load(context) ?: return "未录入声纹（录一次即可让 App 自动认出你说的部分）"
        val day = com.example.mindball.util.TimeUtil.date(t.createdAt) + " " +
            com.example.mindball.util.TimeUtil.time(t.createdAt)
        return "已录入 · %.1f 秒样本 · 有效人声 %d%% · %s".format(
            t.seconds, (t.voicedRatio * 100).toInt(), day
        )
    }

    /** 相似度阈值说明（供设置页提示） */
    fun thresholdHint(): String =
        "自动采用门槛：匹配度 ≥ %d%%（即明显比其他人更像你）。达不到就保留你手动指定的「我是说话人几」，不会乱改。"
            .format((MIN_CONFIDENCE * 100).toInt())
}
