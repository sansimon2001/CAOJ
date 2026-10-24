package com.example.mindball.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.mindball.ai.AnalysisPipeline
import com.example.mindball.audio.AudioProbe
import com.example.mindball.audio.MediaMeta
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import java.io.File
import java.util.Locale

/**
 * 外部录音 / 转写稿导入
 *
 * 这是「和其他录音设备、其他会议产品打通」的落地点。Android 的沙箱决定了
 * 我们**读不到**钉钉、飞书、录音笔 App 的私有目录，所以适配只能走系统协议：
 *
 * - **文件选择器（SAF）**：用户从任何 App 导出到「下载」的音频/文稿，都能选进来
 * - **分享接收**：在钉钉闪记、飞书妙记、微信里点「分享 → 草记」，直接收（见 MainActivity）
 *
 * 能收的东西分两类：
 * 1. **音频**（m4a/mp3/wav/aac/amr/ogg/flac/3gp…）→ 复制进应用私有目录 → 走自己的转写流水线
 * 2. **转写稿**（txt/md/srt/vtt/json/docx）→ 直接入库为「已转写」，省掉一次转写费用
 *
 * 设计取舍：音频一律**拷贝**一份再处理。三方 provider 给的 URI 可能在转写过程中失效
 * （拔 U 盘、退出 App），拷贝成本远低于重导一次。
 */
object Importer {

    private const val TAG = "Importer"

    /** 单份导入文本的上限（防 OOM；正常会议文稿远小于此） */
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    private val AUDIO_EXTS = setOf(
        "m4a", "mp3", "wav", "aac", "amr", "ogg", "opus", "flac", "3gp", "mp4", "wma", "mka", "webm"
    )

    data class Summary(
        val audioOk: Int = 0,
        val textOk: Int = 0,
        val failed: Int = 0,
        val detail: String = "",
    ) {
        val total: Int get() = audioOk + textOk
        fun message(): String = buildString {
            if (audioOk > 0) append("已导入 $audioOk 段录音（排队转写）")
            if (textOk > 0) {
                if (isNotEmpty()) append("；")
                append("已导入 $textOk 份转写稿")
            }
            if (failed > 0) {
                if (isNotEmpty()) append("；")
                append("$failed 个文件无法识别已跳过")
            }
            if (isEmpty()) append("没有可导入的文件")
            if (detail.isNotBlank()) append("\n").append(detail)
        }
    }

    /** 读取 SAF URI 的显示名（没有就退回路径尾段） */
    fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "导入文件" }
    }

    private fun mimeOf(ctx: Context, uri: Uri): String =
        runCatching { ctx.contentResolver.getType(uri) }.getOrNull().orEmpty()

    private fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isAudio(ctx: Context, uri: Uri, name: String): Boolean {
        val mime = mimeOf(ctx, uri)
        if (mime.startsWith("audio/") || mime == "application/ogg") return true
        return extOf(name) in AUDIO_EXTS
    }

    fun isTranscript(ctx: Context, uri: Uri, name: String): Boolean {
        if (isAudio(ctx, uri, name)) return false
        val ext = extOf(name)
        if (TranscriptParser.isTranscriptExt(ext)) return true
        val mime = mimeOf(ctx, uri)
        return mime.startsWith("text/") || mime.contains("wordprocessingml")
    }

    /**
     * 批量导入（调用方在后台线程调用；内部已做逐个异常隔离）
     *
     * 单条失败不影响其余文件——一次选 20 个录音里坏一个，不该全军覆没。
     */
    fun importUris(ctx: Context, uris: List<Uri>): Summary {
        if (uris.isEmpty()) return Summary()
        var audioOk = 0
        var textOk = 0
        var failed = 0
        val notes = StringBuilder()

        for (uri in uris) {
            val name = displayName(ctx, uri)
            try {
                when {
                    isAudio(ctx, uri, name) -> {
                        if (importAudio(ctx, uri, name) != null) audioOk++ else {
                            failed++
                            notes.append("· 音频读取失败：").append(name).append("\n")
                        }
                    }
                    isTranscript(ctx, uri, name) -> {
                        val (ok, why) = importTranscript(ctx, uri, name)
                        if (ok) textOk++ else {
                            failed++
                            if (why.isNotBlank()) notes.append("· ").append(name).append("：").append(why).append("\n")
                        }
                    }
                    else -> {
                        failed++
                        notes.append("· 不支持的格式：").append(name).append("\n")
                    }
                }
            } catch (t: Throwable) {
                failed++
                Log.w(TAG, "导入失败：$name", t)
                notes.append("· 导入失败：").append(name).append("\n")
            }
        }
        return Summary(audioOk, textOk, failed, notes.toString().trim())
    }

    // ==================== 音频 ====================

    private fun importAudio(ctx: Context, uri: Uri, name: String): Long? {
        val dir = File(ctx.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
            .format(System.currentTimeMillis())
        val ext = extOf(name).ifBlank { "m4a" }
        val dest = File(dir, "IMP_${stamp}_${System.nanoTime() % 1000}.$ext")

        val copied = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } != null
        }.getOrDefault(false)
        if (!copied || dest.length() < 1024) {
            runCatching { dest.delete() }
            return null
        }

        val duration = MediaMeta.durationOf(ctx, uri)
        // 录音文件的「真实录制时间」拿不到（三方 App 不暴露），用文件最后修改时间近似；
        // 拿不到就记导入时间，至少时间线不会乱到未来
        val fileTime = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) * 1000L else 0L } ?: 0L
        }.getOrDefault(0L).takeIf { it > 0 && it <= System.currentTimeMillis() }
            ?: System.currentTimeMillis()
        val startAt = if (duration > 0) (fileTime - duration).coerceAtLeast(0L) else fileTime

        // 环境分析（解码头尾几秒），失败不影响导入
        val probe = runCatching { AudioProbe.analyze(dest) }.getOrNull()

        val session = Session(
            title = titleFromFile(name),
            audioPath = dest.absolutePath,
            source = Session.SOURCE_IMPORT,
            startAt = startAt,
            endAt = startAt + duration.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            envType = probe?.envType ?: Session.ENV_UNKNOWN,
            noiseDb = probe?.noiseFloorDb ?: 0.0,
            speechRatio = probe?.speechRatio ?: 0.0,
            status = Session.STATUS_RECORDED,
        )
        val id = Repo.insertSession(ctx, session)
        session.id = id
        AnalysisPipeline.enqueue(ctx, id)
        Log.i(TAG, "音频已导入：$name → #$id（${duration}ms）")
        return id
    }

    // ==================== 转写稿 ====================

    private fun importTranscript(ctx: Context, uri: Uri, name: String): Pair<Boolean, String> {
        // 不用 readNBytes（Android 13 才有的 API，低版本会 NoSuchMethodError），手写带上限的读
        val bytes: ByteArray? = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                var tooBig = false
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_TEXT_BYTES) {
                        tooBig = true
                        break
                    }
                    out.write(buf, 0, n)
                }
                if (tooBig) null else out.toByteArray()
            }
        }.getOrNull()
        if (bytes == null) return false to "文件过大或无法读取"

        val parsed = TranscriptParser.parse(name, bytes) ?: return false to "没有解析出可用文本"
        // 正文太短的多半是空模板/导出失败：不入库，直接告诉用户
        if (parsed.text.length < 20) return false to "内容过短，可能导出为空"

        val now = System.currentTimeMillis()
        val session = Session(
            title = parsed.title,
            transcript = parsed.text,
            source = Session.SOURCE_IMPORT_TEXT,
            startAt = now,
            endAt = now,
            durationMs = 0,
            // 已有文稿 = 已转写，分析流水线会跳过 ASR 直接出摘要（省一次转写费用）
            status = Session.STATUS_TRANSCRIBED,
        )
        val id = Repo.insertSession(ctx, session)
        session.id = id
        AnalysisPipeline.enqueue(ctx, id)
        Log.i(TAG, "转写稿已导入：$name（${parsed.format}，${parsed.text.length} 字）→ #$id")
        return true to ""
    }

    /** 「钉钉闪记_1015产品评审」→「1015产品评审」，去掉产品名与多余标记 */
    private fun titleFromFile(name: String): String {
        val base = name.substringBeforeLast('.').trim()
        val cleaned = base
            .replace(Regex("(?i)^(录音|录制|recording|audio|voice|new recording)[-_ ]*"), "")
            .replace(Regex("(?i)[-_ ]*(钉钉闪记|飞书妙记|讯飞听见|通义听悟|腾讯会议)"), "")
            .trim(' ', '-', '_')
        return cleaned.ifBlank { "录音 " + TimeUtil.dateFull(System.currentTimeMillis()) }
    }
}
