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
     * 判定门槛（只用于「要不要自动采用」，**不是**录入的及格线）
     *
     * 中心化余弦的**绝对**数值没有可解释意义（不同人之间也只有 0.0x），
     * 真正有判别力的是「第一名比第二名高多少」；所以用 pairwise logistic
     * 把「差距」换算成 0..1 的匹配度：
     *     confidence = 1 / (1 + exp(-(best - second) / T))
     *
     * T 取 0.008（离线实测标定，见 README「声纹标定」）：
     *   · 明显不同的人（男 vs 女）  差距约 0.09 → 匹配度 ~100%
     *   · 三人会议、其中一人音色接近 差距约 0.013 → 匹配度 ~84%
     *   · 同性别且音色接近          差距约 0.004 → 匹配度 ~63%
     *
     * v1.25 调整两处（起因：用户反馈「录十几次都到不了 80%」）：
     *   ① 两人场景单独收紧 T。三人以上是「从三个里挑最像的、还要压过第二名」，
     *      本身比两人对比难；两人场景沿用同一个 T，等于要求它跨过更高的绝对差距，
     *      于是同性别同事之间几乎永远打不到门槛——而两人对话恰恰是最常见的使用场景。
     *   ② 门槛从 0.80 降到 0.72。上面的标定里 63% / 84% 两侧仍有富余，
     *      降到 0.72 不会把「同性别音色接近」那种 63% 的情况放进自动采用。
     * 仍然保留「达不到就不猜」的原则：这时只是不自动改，按用户手选的说话人走。
     */
    private const val T_MARGIN = 0.008
    /** 只有两位说话人时用的温度：更容易把「确实更像」认出来 */
    private const val T_MARGIN_TWO = 0.006
    private const val MIN_CONFIDENCE = 0.72

    // ==================== 录入 ====================

    /**
     * 录入结果
     * - [template] 非空 = 已可保存；此时 [note] 可能带一句收音质量建议
     * - [template] 为空 = 没通过，[error] 说的是**具体**原因（声音太小 / 读得太短 / 解析不了）
     */
    data class BuildResult(
        val template: Template? = null,
        val note: String = "",
        val error: String = "",
    ) {
        val ok: Boolean get() = template != null
    }

    /**
     * 由整段录音构建模板
     *
     * v1.25 改口径（起因：用户反馈「录十几次都到不了 80%」）：
     *   旧版除了「有效发声 ≥ 2 秒」，还要求「有效人声占比 ≥ 25%」才肯保存，
     *   并且把占比当成一个百分比数字显示出来——用户很容易读成「要凑够某个百分比才算过」，
     *   于是反复重录。
     *
     *   但占比低只说明「这段话没读满、停顿多」，而特征均值**只取有声帧**，
     *   占比低并不会让特征变脏。所以把它从「拦人重录」降级为「提示一句」：
     *   唯一硬门槛只剩「有没有至少 2 秒的发声」。既保住了模板可用性，
     *   又不把用户挡在门外——先存下来能用的，比反复重录有意义得多。
     */
    fun build(file: File): BuildResult {
        val res = MfccScanner.scan(file, null)
            ?: return BuildResult(error = "没读到音频（文件损坏或解码失败），请靠近手机重录一次")
        val b = res.buckets[MfccScanner.ALL]
            ?: return BuildResult(error = "没提取到语音特征，请靠近手机、对着麦克风重录一次")

        val seconds = res.durationMs / 1000.0
        // 10ms 一帧：200 帧 = 2 秒有声
        val voicedSeconds = b.voiced * 10 / 1000.0
        if (b.voiced < 200) {
            return BuildResult(
                error = "有效发声只有 %.1f 秒，太少（至少要 2 秒）。请把提示句读完再点「停止并分析」"
                    .format(java.util.Locale.CHINA, voicedSeconds)
            )
        }

        val tpl = Template(
            vec = b.vec,
            seconds = seconds,
            createdAt = System.currentTimeMillis(),
            sampleRate = res.sampleRate,
            voicedRatio = res.voicedRatio,
        )
        // 质量只做提示，不拦人
        val note = when {
            res.voicedRatio >= 0.55 -> ""
            res.voicedRatio >= 0.30 -> "收音正常，这份声纹够用了。"
            else -> "这次读得断断续续（有效人声 ${(res.voicedRatio * 100).toInt()}%），" +
                "但特征只按有声的部分算，所以照样保存了。之后如果发现认不准，" +
                "换到安静处、一句接一句地读一遍会更稳。"
        }
        return BuildResult(tpl, note)
    }

    /** 收音质量的白话分级（替代原来那个容易误解的「有效人声 X%」） */
    fun qualityLabel(voicedRatio: Double): String = when {
        voicedRatio >= 0.55 -> "清晰"
        voicedRatio >= 0.35 -> "正常"
        voicedRatio >= 0.20 -> "偏吵"
        else -> "很吵"
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
        // 两人场景用更小的 T：见 T_MARGIN 上面的说明
        val t = if (cands.size <= 2) T_MARGIN_TWO else T_MARGIN
        val confidence = if (second != null) sigmoid(margin / t) else sigmoid(best.score / t)
        val accepted = confidence >= MIN_CONFIDENCE

        val reason = when {
            !accepted && second != null ->
                "声纹没把握（最像 ${best.speaker}，${pct(confidence)}），按你选的说话人处理，可一键改"
            !accepted ->
                "声纹没把握（${pct(confidence)}），按你选的说话人处理，可一键改"
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
        return "已录入 · %.1f 秒样本 · 收音 %s · %s".format(
            t.seconds, qualityLabel(t.voicedRatio), day
        )
    }

    /**
     * 相似度阈值说明（供设置页提示）
     *
     * v1.25 重写：原来写的是「自动采用门槛：匹配度 ≥ 80%」——用户会把它当成
     * **录入的及格线**，于是反复重录想凑到 80%。实际上录入从不打分，80% 只决定
     * 「这一次要不要自动改说话人」，达不到也不会出错。所以这里改成讲后果，不讲指标。
     */
    fun thresholdHint(): String =
        "录入没有百分比要求，读一遍就会存下来。这里的「匹配度」只决定记录里要不要自动判定是谁在说：" +
            "够高就自动认，不够高就按你手选的「我是说话人几」处理，两种都不会把说话人认错、" +
            "也不会弄坏摘要，详情里点一下就能改。"
}
