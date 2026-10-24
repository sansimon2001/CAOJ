package com.example.mindball.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 媒体文件的元信息与解码通路
 *
 * 收敛原因：`MediaMetadataRetriever` 取时长、`MediaExtractor` 选音轨再 `createDecoderByType`
 * 这三段样板代码原来散在 AudioProbe / MfccScanner / AsrClient / Importer 四处，
 * 各写一遍的结果是「一处踩过的坑，另外三处照旧踩」——最典型的是
 * MediaMetadataRetriever 到 API 29 才实现 AutoCloseable，用 `.use {}` 会在
 * Android 8/9 上抛 NoSuchMethodError。现在只保留一份正确写法。
 */
object MediaMeta {

    private const val TAG = "MediaMeta"

    /**
     * 读时长
     *
     * 不用 `use {}`：MediaMetadataRetriever 在 Android 10 (API 29) 才实现 AutoCloseable，
     * minSdk 26 的机器上调用 close() 会 NoSuchMethodError，只能显式 release()。
     * MP4 的 moov 缺失（录音被中断）时返回 0，调用方应据此区分「0 秒」与「读不到」。
     */
    fun durationOf(file: File): Long = readDuration { it.setDataSource(file.absolutePath) }

    /** 读时长（content:// 来源，导入外部录音时用） */
    fun durationOf(context: Context, uri: Uri): Long =
        readDuration { it.setDataSource(context, uri) }

    /** 两个数据源（File / Uri）只差一行 setDataSource，其余异常兜底与释放逻辑共用 */
    private fun readDuration(setSource: (MediaMetadataRetriever) -> Unit): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            setSource(retriever)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 已打开的音轨解码通路
     *
     * 持有 extractor + codec 两个原生资源，必须 close()。
     * `extractor` 对外暴露，是因为 AudioProbe 需要 `seekTo` 跳到采样窗口。
     */
    class Track(
        val extractor: MediaExtractor,
        val codec: MediaCodec,
        val format: MediaFormat,
        val sampleRate: Int,
        val channelCount: Int,
    ) : AutoCloseable {

        override fun close() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 打开文件里第一条音频轨并启动解码器
     *
     * @return null 表示没有音频轨 / 文件不可解析 / 解码器创建失败，调用方按「无法分析」处理，
     *         **不要**当成「没有声音」——文件可能只是没正常收尾。
     */
    fun openAudioTrack(file: File): Track? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                runCatching { extractor.release() }
                return null
            }

            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime == null) {
                runCatching { extractor.release() }
                return null
            }
            val sampleRate = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                .getOrDefault(44100)
            val channelCount = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1)

            extractor.selectTrack(trackIndex)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            return Track(extractor, codec, format, sampleRate, channelCount)
        } catch (e: Exception) {
            Log.w(TAG, "打开音轨失败：${file.name}", e)
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            return null
        }
    }
}
