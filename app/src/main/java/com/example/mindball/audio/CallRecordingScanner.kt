package com.example.mindball.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import java.io.File
import java.util.Locale

/**
 * 系统通话录音扫描（ColorOS / 部分国产 ROM 自带「通话自动录音」）
 *
 * 为什么这样设计：
 * - Android 10+ 起普通 App 无法录制通话上下行音频（VOICE_CALL 源已被移除），
 *   任何第三方 App 都拿不到对端声音；唯一现实路径是用系统自带录音机录音，
 *   再由本 App 读取公共音频文件做转写与分析。
 * - OPPO/一加/realme（ColorOS）通话录音默认保存为公共音频，常见目录：
 *     /Record/PhoneRecord/、/Music/Recordings/Call Recordings/、/Recorder/
 *   本类通过 MediaStore 按路径关键字匹配，不依赖固定目录，兼容性更好。
 *
 * 微信语音通话：音频不经系统通话通道，且微信会主动静音内录通道，
 * 因此系统录音机与第三方 App 均无法取得其音频（本 App 不承诺该能力）。
 */
object CallRecordingScanner {

    private const val TAG = "CallScan"

    /** 关键词：命中即视为通话录音候选 */
    private val KEYWORDS = listOf("record", "录音", "通话", "call", "recorder", "phonerecord")

    data class CallRecording(
        val uri: Uri,
        val displayName: String,
        val path: String,
        val durationMs: Long,
        val addedAt: Long,
        val size: Long,
    )

    /**
     * 扫描系统中新增的通话录音
     * @param since 只取此时间之后添加的文件（上次扫描时间；0=最近 7 天）
     */
    fun scanNewCallRecordings(context: Context, since: Long): List<CallRecording> {
        val from = if (since > 0) since else System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        val result = mutableListOf<CallRecording>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA,
        )

        // DATE_ADDED 单位是秒
        val selection = "${MediaStore.Audio.Media.DATE_ADDED} >= ?"
        val args = arrayOf((from / 1000).toString())

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val addCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: ""
                    val path = runCatching { c.getString(dataCol) }.getOrDefault("") ?: ""
                    val hay = (name + " " + path).lowercase(Locale.ROOT)
                    if (KEYWORDS.none { hay.contains(it) }) continue

                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val duration = c.getLong(durCol)
                    // 过滤极短音频（误触/提示音）
                    if (duration in 1..1500) continue

                    result.add(
                        CallRecording(
                            uri = uri,
                            displayName = name,
                            path = path,
                            durationMs = duration,
                            addedAt = c.getLong(addCol) * 1000,
                            size = c.getLong(sizeCol),
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "扫描通话录音失败（可能缺少媒体读取权限）", it) }

        return result
    }

    /**
     * 导入一条通话录音为会话（复制到 App 私有目录，避免系统清理后丢失）
     * @return 新建会话 id，失败返回 -1
     */
    fun importAsSession(context: Context, rec: CallRecording): Long {
        val dir = File(context.getExternalFilesDir(null), "calls").apply { mkdirs() }
        val safeName = rec.displayName.ifBlank { "call_${rec.addedAt}.m4a" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val target = File(dir, safeName)

        return runCatching {
            context.contentResolver.openInputStream(rec.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching -1L

            val session = Session(
                title = "通话录音 ${safeName.substringBeforeLast('.')}",
                source = Session.SOURCE_CALL,
                audioPath = target.absolutePath,
                startAt = rec.addedAt,
                endAt = rec.addedAt + rec.durationMs,
                durationMs = rec.durationMs,
                status = Session.STATUS_RECORDED,
            )
            Repo.insertSession(context, session)
        }.getOrElse {
            Log.w(TAG, "导入通话录音失败", it)
            -1L
        }
    }
}
