package com.example.mindball.ai

import android.content.Context
import com.example.mindball.audio.MediaMeta
import com.example.mindball.data.Repo
import com.example.mindball.util.Http
import com.example.mindball.util.EndpointGuard
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 语音转写客户端（OpenAI Whisper 兼容协议）
 *
 * 支持直接对接：
 * - OpenAI：https://api.openai.com/v1/audio/transcriptions
 * - 硅基流动：https://api.siliconflow.cn/v1/audio/transcriptions
 * - 自建 whisper / 带 diarization 的服务（同协议 multipart 上传）
 *
 * 能力（对应需求「声纹区分我 / 对方」）：
 * - 默认纯文本转写
 * - 仅对「可能返回分段」的服务（自建 WhisperX / faster-whisper 等）请求
 *   verbose_json + diarize，解析 segments[].speaker / utterances[].speaker_id，
 *   同时**保留每段的起止时间**——有时间戳，本地声纹（audio/Voiceprint）才能去录音里
 *   切出「说话人1 说的那几段」做比对，从而自动认出「我」
 * - 预设里明确只返回纯文本的服务（硅基流动 SenseVoice、OpenAI whisper-1）不发这些扩展参数：
 *   发了也拿不到分段，个别服务还会因未知参数返回 400
 * - 服务端未返回说话人信息时自动降级为纯文本（不报错，声纹比对自动跳过）
 *
 * 时间单位兼容：`*_time` / `*_ms` 视为毫秒，`start` / `end` 视为秒（OpenAI 口径），
 * 解析后还会用音频真实时长做一次校验纠偏，防止个别服务把秒字段填成毫秒。
 */
object AsrClient {

    private val client = Http.client(connectSec = 20, writeSec = 180, readSec = 300)

    /** 一段带说话人与时间的转写 */
    data class Seg(
        val speaker: String,
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    /** 完整转写结果：拼接文本 + 结构化分段 + 本次实际使用的语言 */
    data class Transcript(
        val text: String,
        val segments: List<Seg>,
        /** 本次请求设定的语言 id（见 SpeechLang），用于回显与二次转写 */
        val langId: String = SpeechLangs.ID_AUTO,
        /** 服务端不支持所选方言、已按普通话降级 */
        val downgraded: Boolean = false,
        /** 降级原因（无降级为空），要如实显示给用户 */
        val note: String = "",
        /** 文本疑似繁体（已按设置自动转简体或保留） */
        val traditional: Boolean = false,
    )

    /** 是否已配置 */
    fun isConfigured(context: Context): Boolean =
        Repo.getStr(context, Repo.K_ASR_URL).isNotBlank() && Repo.getStr(context, Repo.K_ASR_KEY).isNotBlank()

    /**
     * 当前配置的转写服务是否**可能**返回说话人分段
     *
     * 自定义服务能力未知，按「可能」处理（用户自建的 WhisperX 是支持的）；
     * 只有预设里明确写了 supportsDiarize = false 的（硅基流动 SenseVoice、OpenAI whisper-1）
     * 才判定为不可能——它们只返回 {"text": "..."}。
     */
    fun diarizePossible(context: Context): Boolean {
        // 老版本可能只存了地址没存服务商 id，这里用地址反推一次，避免漏判
        val savedId = Repo.getStr(context, Repo.K_ASR_PROVIDER).ifBlank {
            Providers.guessAsrId(Repo.getStr(context, Repo.K_ASR_URL))
        }
        val preset = Providers.asrById(savedId) ?: return true
        return preset.supportsDiarize || preset.id == "custom-asr"
    }

    /** 只要文本（长按语音等场景用） */
    fun transcribe(context: Context, audio: File): Pair<String?, String> {
        val (t, err) = transcribeDetailed(context, audio)
        return (t?.text) to err
    }

    /**
     * 转写音频文件（含说话人分段、语言/方言适配）
     *
     * @param langOverride 指定本次使用的语言 id（见 SpeechLang）；为空时读设置。
     *        详情页「疑似粤语，按粤语重转」就走这个参数，不用改全局设置。
     * @return (转写结果, 错误信息)；失败时结果为 null
     */
    fun transcribeDetailed(
        context: Context,
        audio: File,
        langOverride: String? = null,
    ): Pair<Transcript?, String> {
        val url = Repo.getStr(context, Repo.K_ASR_URL)
        val key = Repo.getStr(context, Repo.K_ASR_KEY)
        if (url.isBlank() || key.isBlank()) return null to "未配置语音转写接口"
        if (!audio.exists() || audio.length() == 0L) return null to "音频文件不存在"
        // 录音原文件 + Key 不能走明文 HTTP
        EndpointGuard.check(url)?.let { return null to it }

        // 服务商预设明确「只返回纯文本」时不再发 verbose_json / diarize 等扩展参数：
        // 硅基流动 SenseVoice、OpenAI whisper-1 都不做说话人分离，发了也拿不到分段，
        // 有的服务还会因未知参数直接返回 400，反而把转写搞挂。
        // 自定义服务（custom-asr）能力未知，仍尊重用户开关。
        val diarize = Repo.getBool(context, Repo.K_ASR_DIARIZE, true) && diarizePossible(context)
        // 模型名可配置：硅基流动用 FunAudioLLM/SenseVoiceSmall，OpenAI 用 whisper-1
        val asrModel = Repo.getStr(context, Repo.K_ASR_MODEL, "whisper-1").ifBlank { "whisper-1" }

        // ---------- 语言/方言：把「用户想识别什么」换算成「这个服务能收什么」 ----------
        val providerId = Repo.getStr(context, Repo.K_ASR_PROVIDER).ifBlank { Providers.guessAsrId(url) }
        val provider = Providers.asrById(providerId) ?: Providers.ASR.last()
        val langId = langOverride?.takeIf { it.isNotBlank() } ?: Repo.getStr(context, Repo.K_ASR_LANG, SpeechLangs.ID_ZH)
        val lang = SpeechLangs.byId(langId)
        val plan = SpeechLangs.plan(lang, provider)
        // 热词只在支持 prompt 的服务上发（SenseVoice 没有这个参数，发了会 400）
        val hotwords = Repo.getStr(context, Repo.K_ASR_PROMPT).trim()
        val prompt = if (provider.supportsPrompt && hotwords.isNotBlank()) {
            buildHotwordPrompt(hotwords)
        } else {
            ""
        }

        return runCatching {
            // 依次尝试：完整参数 → 去掉说话人 → 去掉 language → 去掉热词
            // 每一步都是「服务端明确拒绝（400/422）」才降级，网络错误不重复打
            val attempts = ArrayList<ExecOpts>(4)
            attempts.add(ExecOpts(diarize, plan.requestValue, prompt))
            if (diarize) attempts.add(ExecOpts(false, plan.requestValue, prompt))
            if (plan.requestValue != null) attempts.add(ExecOpts(false, null, prompt))
            if (prompt.isNotBlank()) attempts.add(ExecOpts(false, plan.requestValue, ""))

            var opts = attempts.first()
            var code = 0
            var raw = ""
            for ((i, a) in attempts.withIndex()) {
                val r = execute(url, key, asrModel, audio, a)
                code = r.first
                raw = r.second
                opts = a
                if (code in 200..299 || (code != 400 && code != 422)) break
                if (i == attempts.lastIndex) break
            }
            val usedDiarize = opts.diarize
            if (code !in 200..299) return@runCatching null to "转写接口返回 $code：${raw.take(200)}"

            val obj = runCatching { JSONObject(raw) }.getOrNull()
                ?: return@runCatching null to "转写结果不是合法 JSON"

            val durationMs = runCatching { MediaMeta.durationOf(audio) }.getOrDefault(0L)

            var segments: List<Seg> = emptyList()
            var text = ""
            if (usedDiarize) {
                segments = parseSegments(obj, durationMs)
                if (segments.isNotEmpty()) text = segmentsToText(segments)
            }
            if (text.isBlank()) {
                text = obj.optString("text").trim()
                if (segments.isNotEmpty()) segments = emptyList()
            }

            // ---------- 文字后处理：不可见字符 + 繁简 ----------
            text = ZhNormalizer.stripInvisible(text)
            var traditional = false
            if (!lang.keepTraditional && !lang.foreign) {
                traditional = ZhNormalizer.hasTraditional(context, text)
                if (traditional) text = ZhNormalizer.toSimplified(context, text)
            }

            if (text.isBlank()) {
                null to "转写结果为空"
            } else {
                Transcript(
                    text = text,
                    segments = segments,
                    langId = lang.id,
                    downgraded = plan.downgraded,
                    note = plan.note,
                    traditional = traditional,
                ) to ""
            }
        }.getOrElse { null to "转写请求异常：${it.message}" }
    }

    /** 一次请求的可变参数组合（降级重试时逐项去掉） */
    private data class ExecOpts(
        val diarize: Boolean,
        val language: String?,
        val prompt: String,
    )

    /**
     * 把用户填的热词表整理成 prompt
     *
     * 这是方言/专名识别最划算的一招：Whisper 系会把这些词当成上文，
     * 遇到发音相近的片段时优先按这些写法输出。人名、公司名、行业术语
     * 这类「普通词典里没有」的词，加与不加差别很明显。
     */
    private fun buildHotwordPrompt(hotwords: String): String {
        val words = hotwords
            .split(Regex("[，,、;；\\n\\r\\t]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(60)
        if (words.isEmpty()) return ""
        return "以下是对话中可能出现的人名、公司名与专业术语，请优先按这些写法输出：" +
            words.joinToString("、") + "。"
    }

    /**
     * 实际发一次 multipart 请求
     * @return (HTTP 状态码, 响应原文)；网络异常时抛出，由上层 runCatching 兜住
     */
    private fun execute(
        url: String,
        key: String,
        model: String,
        audio: File,
        opts: ExecOpts,
    ): Pair<Int, String> {
        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/m4a".toMediaType()))

        // language 为空表示「不传」，由服务端自动判断（Whisper 的默认行为）
        opts.language?.let { builder.addFormDataPart("language", it) }
        if (opts.prompt.isNotBlank()) builder.addFormDataPart("prompt", opts.prompt)

        if (opts.diarize) {
            builder.addFormDataPart("response_format", "verbose_json")
            builder.addFormDataPart("diarize", "true")
            builder.addFormDataPart("speaker_labels", "true")
            builder.addFormDataPart("timestamp_granularities[]", "segment")
        } else {
            builder.addFormDataPart("response_format", "json")
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(builder.build())
            .build()

        return client.newCall(req).execute().use { resp ->
            (resp.code) to (resp.body?.string().orEmpty())
        }
    }

    // ==================== 解析 ====================

    /** 把分段拼成「[说话人1] 文本」多行文本 */
    private fun segmentsToText(segments: List<Seg>): String {
        val sb = StringBuilder()
        segments.forEach { seg ->
            if (seg.speaker.isBlank()) {
                sb.append(seg.text).append('\n')
            } else {
                sb.append('[').append(seg.speaker).append("] ").append(seg.text).append('\n')
            }
        }
        return sb.toString().trim()
    }

    /** 兼容 segments / utterances / diarized_segments 三种结构 */
    private fun parseSegments(obj: JSONObject, durationMs: Long): List<Seg> {
        val keys = listOf("segments", "utterances", "diarized_segments")
        for (key in keys) {
            val arr: JSONArray = obj.optJSONArray(key) ?: continue
            if (arr.length() == 0) continue

            val list = ArrayList<Seg>(arr.length())
            for (i in 0 until arr.length()) {
                val seg = arr.optJSONObject(i) ?: continue
                val tx = firstNonBlank(seg.optString("text"), seg.optString("content"))
                if (tx.isBlank()) continue
                val speaker = normalizeSpeaker(
                    firstNonBlank(
                        seg.optString("speaker"),
                        seg.optString("speaker_id"),
                        seg.optString("speakerId"),
                        seg.optString("channel_id"),
                    )
                )
                val span = parseSpan(seg)
                list.add(Seg(speaker, span.first, span.second, tx))
            }
            if (list.isNotEmpty()) return fixSpanUnit(list, durationMs)
        }
        return emptyList()
    }

    /** 解析一段的起止毫秒；缺字段返回 -1 */
    private fun parseSpan(seg: JSONObject): Pair<Long, Long> {
        val msStart = firstDouble(seg, "start_time", "startTime", "start_ms", "begin_time", "beginTime")
        val msEnd = firstDouble(seg, "end_time", "endTime", "end_ms", "finish_time", "finishTime")
        if (msStart != null && msEnd != null) {
            return msStart.toLong() to msEnd.toLong()
        }
        val secStart = firstDouble(seg, "start", "start_sec")
        val secEnd = firstDouble(seg, "end", "end_sec")
        if (secStart != null || secEnd != null) {
            return ((secStart ?: -1.0) * 1000).toLong() to ((secEnd ?: -1.0) * 1000).toLong()
        }
        return -1L to -1L
    }

    /**
     * 单位纠偏：个别服务把毫秒写进按秒解析的字段，用音频真实时长校验——
     * 若最大结束时间远超音频时长，而除以 1000 后落在合理范围，则整体除以 1000
     */
    private fun fixSpanUnit(list: List<Seg>, durationMs: Long): List<Seg> {
        if (durationMs <= 0) return list
        val maxEnd = list.maxOfOrNull { it.endMs } ?: -1L
        if (maxEnd <= 0) return list
        if (maxEnd <= durationMs * 1.6) return list
        val scaled = maxEnd / 1000
        if (scaled > durationMs * 1.6) return list
        return list.map { it.copy(startMs = it.startMs / 1000, endMs = it.endMs / 1000) }
    }

    private fun firstDouble(obj: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val d = when (val v = obj.opt(k)) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
                if (d != null) return d
            }
        }
        return null
    }

    /** 统一成「说话人N」形式，便于后续映射成 我 / 对方 */
    private fun normalizeSpeaker(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("说话人") || s == "我" || s == "对方") return s
        val digits = s.filter { it.isDigit() }
        return if (digits.isNotEmpty()) "说话人$digits" else s
    }

    private fun firstNonBlank(vararg ss: String?): String =
        ss.firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

    // ==================== 角色映射 ====================

    /**
     * 说话人标签 → 本地语义：我的标签映射为「我」，其余映射为「对方 / 对方N」
     * @param mySpeaker 我的说话人标签：优先用声纹自动识别的结果，其次用设置里的「我是说话人几」
     */
    fun mapSpeakersToRoles(transcript: String, mySpeaker: String): String {
        if (transcript.isBlank()) return transcript
        val my = mySpeaker.trim()
        if (my.isBlank()) return transcript

        val labels = Regex("""\[([^\]]{1,12})]""").findAll(transcript)
            .map { it.groupValues[1] }
            .filter { it.startsWith("说话人") }
            .distinct()
            .toList()
        if (labels.isEmpty()) return transcript

        var others = 1
        val mapping = HashMap<String, String>()
        labels.forEach { label ->
            mapping[label] = if (label == my) {
                "我"
            } else if (labels.size == 2) {
                "对方"
            } else {
                val alias = "对方$others"
                others++
                alias
            }
        }

        var out = transcript
        mapping.forEach { (label, role) -> out = out.replace("[$label]", "[$role]") }
        return out
    }
}
