package com.example.mindball.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.util.Log
import com.example.mindball.data.Session
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 录音环境分析（对应反馈「不区分环境、不够智能」）
 *
 * 做法：录音结束后离线解码音频 → 逐 20ms 计算能量 → 得到
 * - 底噪（环境噪音水平）
 * - 有效人声占比（高于底噪且够响的帧比例）
 * - 峰值与动态范围
 *
 * 用途：
 * 1) 判断场景（安静独处 / 会议室 / 嘈杂街道），写进记录并在 UI 显示
 * 2) **确实没有有效人声的录音丢弃**，从源头减少「AI 拿噪音乱写摘要」
 * 3) 把场景作为上下文传给大模型，让它知道这是会议还是独处灵感
 *
 * ⚠️ v1.15 修掉两个会「吞掉整段录音」的判定缺陷：
 *
 * ① **解码失败 ≠ 没人说话**
 *    原来解码异常或文件未正常收尾（MP4 缺 moov）时返回 unknown()，而 unknown 的
 *    hasSpeech 也是 false —— 上层据此把文件删了。也就是说「文件坏了」被当成
 *    「这段没人说话」，1 小时的录音直接消失，且提示语还是「未检测到有效人声」。
 *    → 现在 Profile 带 [Profile.analyzed] 区分「分析过，确实没声音」与
 *      「没分析成功，不知道有没有声音」，上层只在前者才删文件。
 *
 * ② **长录音只看前 3 分钟就下结论**
 *    原来无论录多久都只解码开头 180 秒。若开头正是等待、静场、被打断的空段，
 *    后面对话再多也会被判「无人声」而删掉。
 *    → 现在按时长取 3 个采样窗口（头 / 中 / 尾），合并判定。
 */
object AudioProbe {

    private const val TAG = "AudioProbe"
    private const val FRAME_MS = 20

    /** 每个采样窗口最多分析多久（判断环境用几段样本就够，不必整段解码） */
    private const val WINDOW_MS = 180_000L

    data class Profile(
        /** 底噪 dBFS（越小越安静，-60 约等于安静房间，-30 属于嘈杂） */
        val noiseFloorDb: Double,
        /** 峰值 dBFS */
        val peakDb: Double,
        /** 有效人声帧占比 0..1 */
        val speechRatio: Double,
        /** 场景：Session.ENV_* */
        val envType: String,
        /** 时长（毫秒） */
        val durationMs: Long,
        /** 是否含有效人声（决定是否值得生成记录） */
        val hasSpeech: Boolean,
        /**
         * 是否真的解码分析成功。
         * false = 文件损坏 / 编码异常 / 未正常收尾，**不代表没人说话**，
         * 上游必须保留文件而不是删除。
         */
        val analyzed: Boolean = true,
    ) {
        val envLabel: String
            get() = when (envType) {
                Session.ENV_QUIET -> "安静独处"
                Session.ENV_MEETING -> "会议交谈"
                Session.ENV_STREET -> "嘈杂环境"
                else -> "未识别"
            }

        companion object {
            /** 分析失败：未知环境，且明确标记「没分析成功」 */
            fun unknown(durationMs: Long = 0): Profile =
                Profile(0.0, 0.0, 0.0, Session.ENV_UNKNOWN, durationMs, false, analyzed = false)
        }
    }

    /**
     * 分析音频文件（多点采样）
     *
     * 解码失败时返回 `analyzed = false`，上游据此**保留文件**并照常入库
     * （宁可留一段解析不了的录音让用户自己听，也不能替他决定删掉）。
     */
    fun analyze(file: File, maxMs: Long = WINDOW_MS): Profile {
        if (!file.exists() || file.length() < 1024) {
            Log.w(TAG, "文件不存在或过小：${file.name} (${file.length()}B)")
            return Profile.unknown()
        }

        val totalMs = MediaMeta.durationOf(file)
        val windows = sampleWindows(totalMs, maxMs)

        val framesDb = ArrayList<Double>(8192)
        var decodedAny = false
        for (startMs in windows) {
            if (decodeWindow(file, startMs, maxMs, framesDb)) decodedAny = true
        }

        if (!decodedAny || framesDb.isEmpty()) {
            Log.w(TAG, "解码失败（文件可能未正常收尾）：${file.name} len=${file.length()} dur=${totalMs}ms")
            return Profile.unknown(totalMs)
        }
        return judge(framesDb, totalMs)
    }

    /** 按时长决定采样窗口：短录音只取开头，长录音取头/中/尾 */
    private fun sampleWindows(totalMs: Long, maxMs: Long): List<Long> {
        if (totalMs <= 0 || totalMs <= maxMs + 5_000) return listOf(0L)
        val mid = (totalMs / 2 - maxMs / 2).coerceAtLeast(0)
        val tail = (totalMs - maxMs).coerceAtLeast(0)
        return listOf(0L, mid, tail).distinct()
    }

    /**
     * 从 [startMs] 起解码最多 [maxMs] 毫秒，把逐帧 dB 追加进 [out]
     * @return 是否成功解出至少一帧
     */
    private fun decodeWindow(file: File, startMs: Long, maxMs: Long, out: MutableList<Double>): Boolean {
        // 音轨发现与解码器创建统一走 MediaMeta（原来这里和 MfccScanner 各写了一份）
        val track = MediaMeta.openAudioTrack(file) ?: return false
        try {
            // 定位到窗口起点（seek 只能落到同步帧，稍微往前偏几帧不影响统计）
            if (startMs > 0) {
                runCatching { track.extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_NEXT_SYNC) }
            }

            val codec = track.codec
            val sampleRate = track.sampleRate
            val channelCount = track.channelCount

            val frameSamples = max(1, sampleRate * FRAME_MS / 1000)
            var sumSquares = 0.0
            var frameCount = 0
            var decodedSamples = 0L
            var produced = false

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val budget = maxMs

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = track.extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, track.extractor.sampleTime, 0)
                            track.extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shorts = ShortArray(bufferInfo.size / 2)
                        outBuf.asShortBuffer().get(shorts)

                        val step = max(1, channelCount)
                        decodedSamples += (shorts.size / max(1, channelCount)).toLong()
                        var i = 0
                        while (i < shorts.size) {
                            sumSquares += (shorts[i].toDouble() / 32768.0).let { it * it }
                            frameCount++
                            if (frameCount >= frameSamples) {
                                val rms = sqrt(sumSquares / frameCount)
                                out.add(Dsp.toDb(rms))
                                produced = true
                                sumSquares = 0.0
                                frameCount = 0
                            }
                            i += step
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (sampleRate > 0 && decodedSamples * 1000 / sampleRate >= budget) {
                        Log.d(TAG, "采样窗口 ${startMs}ms 已取 ${budget}ms，结束本窗口")
                        outputDone = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 忽略，采样率以输入 format 为准
                }
            }
            return produced
        } catch (e: Exception) {
            Log.w(TAG, "窗口 ${startMs}ms 解码失败", e)
            return false
        } finally {
            track.close()
        }
    }

    /** 由逐帧 dB 序列判定环境 */
    private fun judge(framesDb: List<Double>, totalMs: Long): Profile {
        val sorted = framesDb.sorted()
        val n = sorted.size
        if (n == 0) return Profile.unknown(totalMs)

        // 底噪取 15 分位（沉默段），峰值取 95 分位
        val noise = sorted[(n * 0.15).toInt().coerceIn(0, n - 1)]
        val peak = sorted[(n * 0.95).toInt().coerceIn(0, n - 1)]

        // 有效人声：明显高于底噪、且不至于过低（-48dBFS 以上）
        val voiceThreshold = max(noise + 8.0, -48.0)
        val speechFrames = framesDb.count { it >= voiceThreshold }
        val speechRatio = speechFrames.toDouble() / n

        val env = when {
            speechRatio < 0.04 -> Session.ENV_UNKNOWN          // 几乎没人说话
            noise >= -32.0 && (peak - noise) < 14.0 -> Session.ENV_STREET  // 底噪高、动态小 → 嘈杂
            noise <= -48.0 && speechRatio < 0.45 -> Session.ENV_QUIET      // 很安静、话不多 → 独处
            else -> Session.ENV_MEETING
        }

        // 有效人声判定：说话帧占比够，且峰值不过低（排除纯环境音）
        val hasSpeech = speechRatio >= 0.05 && peak >= -42.0

        return Profile(
            noiseFloorDb = Dsp.round1(noise),
            peakDb = Dsp.round1(peak),
            speechRatio = Dsp.round3(speechRatio),
            envType = env,
            durationMs = if (totalMs > 0) totalMs else framesDb.size.toLong() * FRAME_MS,
            hasSpeech = hasSpeech,
            analyzed = true,
        )
    }
}
