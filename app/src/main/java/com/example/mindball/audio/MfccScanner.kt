package com.example.mindball.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.File
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 说话人声学特征扫描（本人声纹录入 / 声纹识别「我」的底层）
 *
 * 做什么：
 * 1) 流式解码音频（逐块处理，**不把整段 PCM 读进内存**，30 分钟通话也不爆内存）
 * 2) 抽取每 10ms 一帧的声学特征：MFCC（13 维）+ 帧能量 + 基频 F0
 * 3) 按时间归属把帧分到不同「桶」（每个说话人一个桶，或整段一个桶）
 * 4) 取有声帧的均值向量（含 MFCC 一阶差分 + 基频），L2 归一化 → 说话人特征向量
 *
 * 为什么用这套：
 * - 手机端跑深度学习声纹模型（ECAPA-TDNN / CAM++）要几十 MB 模型 + 推理框架，
 *   包体和功耗都不划算；MFCC 统计量 + 基频是经典轻量方案，能区分音色差异明显的两个人
 * - 基频（音高）对男女声区分极有效，所以单独给较高权重
 *
 * 边界：这是「轻量统计模型」，不是专业声纹库。同性别、音色接近的两人可能分不准，
 * 因此上层只在「相似度明显领先」时才自动采用，否则保留人工设置并提示存疑。
 */
object MfccScanner {

    private const val TAG = "MfccScanner"

    /** 特征维度：12 MFCC（丢弃 c0 以抗音量差异）+ 12 一阶差分 + 1 基频 */
    const val DIM = 25

    private const val N_MFCC = 12
    private const val N_MEL = 26
    private const val TARGET_SR = 16000
    private const val FRAME_MS = 25
    private const val HOP_MS = 10
    /** 基频不必每帧都算（很吃 CPU），每 6 帧取一次足够做统计 */
    private const val F0_EVERY = 6

    /**
     * 每位说话人最多计算多少帧特征（9000 帧 = 90 秒，10ms 一帧）
     *
     * 声纹模板只需要"这个人的声音统计量"，90 秒样本已经远多于所需
     * （录入端只给了 15 秒）。而 30 分钟会议原本要把整段 FFT 跑一遍，
     * 加上这个上限后计算量恒定在「说话人数 × 90 秒」，是长录音上最有效的省电点。
     */
    private const val MAX_FEAT_FRAMES = 9_000

    /** 未计算特征的帧用它占位，避免每帧新建数组 */
    private val ZERO_CEPS = DoubleArray(13)

    // 权重：MFCC 主体 / 动态 / 音高
    private const val W_MFCC = 1.0
    private const val W_DELTA = 0.7
    private const val W_F0 = 1.6

    /** 一段说话区间（来自转写结果的分段，含说话人标签与起止毫秒） */
    data class TimeSegment(val label: String, val startMs: Long, val endMs: Long)

    /** 单个「桶」的统计结果 */
    data class Bucket(
        val label: String,
        val frames: Int,
        val voiced: Int,
        /** 已加权并 L2 归一化的特征向量（长度 = DIM） */
        val vec: DoubleArray,
    ) {
        val voicedRatio: Double get() = if (frames == 0) 0.0 else voiced.toDouble() / frames
    }

    data class Result(
        val buckets: Map<String, Bucket>,
        val noiseDb: Double,
        val peakDb: Double,
        val totalFrames: Int,
        val voicedRatio: Double,
        val sampleRate: Int,
        val durationMs: Long,
    )

    /**
     * 扫描音频文件
     * @param segments 说话区间；传 null 表示整段当作同一个说话人（声纹录入用）
     */
    fun scan(file: File, segments: List<TimeSegment>? = null): Result? {
        if (!file.exists() || file.length() < 2048) return null

        // 音轨发现与解码器创建统一走 MediaMeta（原来这里和 AudioProbe 各写了一份）
        val track = MediaMeta.openAudioTrack(file) ?: return null
        try {
            val extractor = track.extractor
            val codec = track.codec
            var srcSr = track.sampleRate
            var srcCh = track.channelCount

            // ---- 运行时参数（随输出格式变化重建）----
            var dec = 1
            var effSr = srcSr
            var frameLen = (effSr * FRAME_MS / 1000).coerceAtLeast(64)
            var hopLen = (effSr * HOP_MS / 1000).coerceAtLeast(1)
            var nFft = nextPow2(frameLen)
            var melBank: Array<DoubleArray>? = null
            var dctTable: Array<DoubleArray>? = null

            var re = DoubleArray(nFft)
            var im = DoubleArray(nFft)
            var win = DoubleArray(frameLen)
            var pow = DoubleArray(nFft / 2 + 1)

            fun buildParams(sr: Int) {
                effSr = max(4000, sr / dec)
                frameLen = max(64, effSr * FRAME_MS / 1000)
                hopLen = max(1, effSr * HOP_MS / 1000)
                nFft = nextPow2(frameLen)
                if (re.size != nFft) {
                    re = DoubleArray(nFft)
                    im = DoubleArray(nFft)
                    pow = DoubleArray(nFft / 2 + 1)
                }
                if (win.size != frameLen) win = DoubleArray(frameLen)
                // 汉明窗
                for (i in 0 until frameLen) {
                    win[i] = 0.54 - 0.46 * cos(2.0 * Math.PI * i / (frameLen - 1).coerceAtLeast(1))
                }
                // 梅尔滤波器组（50Hz ~ min(7000, Nyquist)）
                val fMin = 50.0
                val fMax = min(7000.0, effSr / 2.0 * 0.95)
                val melMin = hz2mel(fMin)
                val melMax = hz2mel(fMax)
                val pts = DoubleArray(N_MEL + 2) { melMin + (melMax - melMin) * it / (N_MEL + 1) }
                val hz = DoubleArray(N_MEL + 2) { mel2hz(pts[it]) }
                val bins = IntArray(N_MEL + 2) { ((nFft + 1) * hz[it] / effSr).toInt().coerceIn(0, nFft / 2) }
                melBank = Array(N_MEL) { m ->
                    val w = DoubleArray(nFft / 2 + 1)
                    val l = bins[m]
                    val c = bins[m + 1]
                    val r = bins[m + 2]
                    for (k in l until c) if (c > l) w[k] = (k - l).toDouble() / (c - l)
                    for (k in c until r) if (r > c) w[k] = (r - k).toDouble() / (r - c)
                    w
                }
                // DCT-II 表
                dctTable = Array(N_MFCC + 1) { k ->
                    DoubleArray(N_MEL) { n -> cos(Math.PI * k * (n + 0.5) / N_MEL) }
                }
            }

            dec = max(1, (srcSr.toDouble() / TARGET_SR).roundToInt())
            buildParams(srcSr)

            // ---- 目标区间（省电核心）----
            // 有声纹匹配时只需要「这些人说话的那些片段」，
            // 其余时间的帧不必做 FFT（解码照常，但 FFT/梅尔/DCT/基频全部跳过）
            val segs = segments?.filter { it.endMs > it.startMs && it.label.isNotBlank() }
                ?.sortedBy { it.startMs }
            val keys = LinkedHashSet<String>()
            if (segs.isNullOrEmpty()) keys.add(ALL) else segs.forEach { keys.add(it.label) }
            val keyList = keys.toList()
            val keyIdx = HashMap<String, Int>().apply { keyList.forEachIndexed { i, k -> put(k, i) } }
            val featQuota = IntArray(keyList.size)   // 每个说话人已算的帧数
            var segPtr = 0

            /** 当前时间点属于第几个说话人；-1 表示不在任何目标片段内 */
            fun labelAt(t: Long): Int {
                if (segs.isNullOrEmpty()) return 0
                while (segPtr < segs.size && t > segs[segPtr].endMs) segPtr++
                val sg = segs.getOrNull(segPtr) ?: return -1
                if (t < sg.startMs) return -1
                return keyIdx[sg.label] ?: -1
            }

            // ---- 帧缓冲 ----
            val out = Grow(8192)
            var buf = DoubleArray(frameLen * 4)
            var bufLen = 0
            var frameStart = 0L          // 当前帧起点（effSr 采样序号）
            var totalDec = 0L            // 已抽取样本总数
            var decLeft = 0
            var decAcc = 0.0
            var produced = 0

            fun pushSample(v: Double) {
                if (bufLen >= buf.size) buf = buf.copyOf(buf.size * 2)
                buf[bufLen++] = v
                totalDec++
                while (bufLen >= frameLen) {
                    val centerMs = (frameStart + frameLen / 2) * 1000 / effSr
                    // 只在「目标说话人片段内」且「该人还没用满 90 秒配额」时才算特征
                    val bi = labelAt(centerMs)
                    val wantFeat = bi >= 0 && featQuota[bi] < MAX_FEAT_FRAMES
                    val wantF0 = wantFeat && produced % F0_EVERY == 0
                    if (wantFeat) featQuota[bi]++
                    processFrame(
                        buf, frameLen, nFft, win, re, im, pow,
                        melBank!!, dctTable!!, effSr,
                        centerMs, wantF0, wantFeat
                    )?.let { fr ->
                        out.add(fr)
                    }
                    produced++
                    // 滑动 hopLen
                    System.arraycopy(buf, hopLen, buf, 0, bufLen - hopLen)
                    bufLen -= hopLen
                    frameStart += hopLen
                }
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var paramsRev = srcSr

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 2) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val shorts = ShortArray(info.size / 2)
                        outBuf.asShortBuffer().get(shorts)
                        val ch = max(1, srcCh)
                        var i = 0
                        while (i + ch <= shorts.size) {
                            var sum = 0.0
                            for (c in 0 until ch) sum += shorts[i + c].toDouble() / 32768.0
                            val mono = sum / ch
                            decAcc += mono
                            decLeft++
                            if (decLeft >= dec) {
                                pushSample(decAcc / dec)
                                decAcc = 0.0
                                decLeft = 0
                            }
                            i += ch
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    val sr2 = runCatching { of.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(srcSr)
                    val ch2 = runCatching { of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(srcCh)
                    if (sr2 != paramsRev) {
                        paramsRev = sr2
                        srcSr = sr2
                        srcCh = ch2
                        dec = max(1, (srcSr.toDouble() / TARGET_SR).roundToInt())
                        buildParams(srcSr)
                        // 重建缓冲（丢掉极小残量，不影响统计）
                        buf = DoubleArray(frameLen * 4)
                        bufLen = 0
                        frameStart = totalDec
                    }
                }
            }

            val n = out.size
            if (n < 10) return null

            // ---- 1. 噪声底与有声帧判定 ----
            val sortedDb = FloatArray(n)
            System.arraycopy(out.db, 0, sortedDb, 0, n)
            sortedDb.sort()
            val noise = sortedDb[((n - 1) * 0.15).toInt().coerceIn(0, n - 1)].toDouble()
            val peak = sortedDb[((n - 1) * 0.95).toInt().coerceIn(0, n - 1)].toDouble()
            val thr = noise + max(6.0, 0.30 * max(0.0, peak - noise))

            var voicedTotal = 0
            for (i in 0 until n) if (out.db[i] >= thr) voicedTotal++
            val voicedRatio = voicedTotal.toDouble() / n

            // ---- 2. 按时间归属到桶（复用上面的区间指针逻辑）----
            val bucketOf = IntArray(n)
            var si2 = 0
            for (i in 0 until n) {
                if (!out.feat[i]) {           // 没算特征的帧直接排除
                    bucketOf[i] = -1
                    continue
                }
                val t = out.times[i]
                if (segs.isNullOrEmpty()) {
                    bucketOf[i] = 0
                    continue
                }
                while (si2 < segs.size && t > segs[si2].endMs) si2++
                val sg = segs.getOrNull(si2)
                bucketOf[i] = if (sg != null && t >= sg.startMs) (keyIdx[sg.label] ?: -1) else -1
            }

            // ---- 3. 均值向量（含一阶差分 + 基频）----
            val kList = keys.toList()
            val kb = kList.size
            // 用一维连续数组累积（比嵌套数组更省内存，也避免可变性推断问题）
            val acc = DoubleArray(kb * DIM)
            val cnt = IntArray(kb)
            val vCnt = IntArray(kb)
            val f0Sum = DoubleArray(kb)
            val f0Cnt = IntArray(kb)

            for (i2 in 0 until n) {
                val bi = bucketOf[i2]
                if (bi < 0 || bi >= kb) continue
                cnt[bi]++
                if (out.db[i2] < thr) continue
                vCnt[bi]++
                val base = bi * DIM
                val cb = i2 * 13
                // MFCC c1..c12
                for (jj in 0 until N_MFCC) {
                    acc[base + jj] += out.ceps[cb + jj + 1].toDouble()
                }
                // 一阶差分（回归公式，±2 帧）
                if (i2 >= 2 && i2 + 2 < n) {
                    for (jj in 0 until N_MFCC) {
                        val c1 = out.ceps[(i2 - 1) * 13 + jj + 1].toDouble()
                        val c2 = out.ceps[(i2 + 1) * 13 + jj + 1].toDouble()
                        val c3 = out.ceps[(i2 - 2) * 13 + jj + 1].toDouble()
                        val c4 = out.ceps[(i2 + 2) * 13 + jj + 1].toDouble()
                        acc[base + N_MFCC + jj] += (2.0 * (c2 - c1) + (c4 - c3)) / 10.0
                    }
                }
                val f0 = out.f0[i2]
                if (f0 > 60f && f0 < 500f) {
                    f0Sum[bi] += ln100(f0.toDouble())
                    f0Cnt[bi]++
                }
            }

            val buckets = LinkedHashMap<String, Bucket>(kb)
            for (bi in 0 until kb) {
                val c = max(1, vCnt[bi])
                val base = bi * DIM
                val raw = DoubleArray(DIM)
                for (jj in 0 until N_MFCC) {
                    raw[jj] = acc[base + jj] / c * W_MFCC
                    raw[N_MFCC + jj] = acc[base + N_MFCC + jj] / c * W_DELTA
                }
                raw[2 * N_MFCC] = if (f0Cnt[bi] > 0) W_F0 * (f0Sum[bi] / f0Cnt[bi]) else 0.0
                buckets[kList[bi]] = Bucket(
                    label = kList[bi],
                    frames = cnt[bi],
                    voiced = vCnt[bi],
                    vec = l2(raw),
                )
            }

            return Result(
                buckets = buckets,
                noiseDb = Dsp.round1(noise),
                peakDb = Dsp.round1(peak),
                totalFrames = n,
                voicedRatio = Dsp.round3(voicedRatio),
                sampleRate = effSr,
                durationMs = if (effSr > 0) totalDec * 1000 / effSr else 0L,
            )
        } catch (e: Exception) {
            Log.w(TAG, "声学特征扫描失败", e)
            return null
        } finally {
            track.close()
        }
    }

    const val ALL = "__all__"

    // ==================== 单帧处理 ====================

    private class Frame(
        val timeMs: Long,
        val db: Float,
        val ceps: DoubleArray,
        val f0: Float,
        /** false = 只算了能量，特征全零（不在目标区间 / 超出每人上限） */
        val hasFeat: Boolean,
    )

    private class Grow(cap: Int) {
        var times = LongArray(cap)
        var db = FloatArray(cap)
        var f0 = FloatArray(cap)
        var ceps = FloatArray(cap * 13)
        /** 该帧是否真的算了特征（未算的帧只有能量，用于噪声底统计） */
        var feat = BooleanArray(cap)
        var size = 0

        fun ensure(c: Int) {
            if (c <= times.size) return
            var n = times.size
            while (n < c) n *= 2
            times = times.copyOf(n)
            db = db.copyOf(n)
            f0 = f0.copyOf(n)
            ceps = ceps.copyOf(n * 13)
            feat = feat.copyOf(n)
        }

        fun add(f: Frame) {
            ensure(size + 1)
            val i = size
            times[i] = f.timeMs
            db[i] = f.db
            f0[i] = f.f0
            feat[i] = f.hasFeat
            for (j in 0..12) ceps[i * 13 + j] = f.ceps[j].toFloat()
            size++
        }
    }

    private fun processFrame(
        buf: DoubleArray,
        frameLen: Int,
        nFft: Int,
        win: DoubleArray,
        re: DoubleArray,
        im: DoubleArray,
        pow: DoubleArray,
        melBank: Array<DoubleArray>,
        dctTable: Array<DoubleArray>,
        sr: Int,
        centerMs: Long,
        wantF0: Boolean,
        wantFeat: Boolean,
    ): Frame? {
        if (frameLen > buf.size) return null

        // 0. 能量（dBFS，用于噪声底/有声帧判断）
        var energy = 0.0
        for (i in 0 until frameLen) energy += buf[i] * buf[i]
        energy /= frameLen
        val db = if (energy <= 1e-10) -100.0 else 10.0 * log10(energy)

        // 不在目标区间 / 超出配额：只保留能量（极便宜），跳过 FFT 等昂贵计算
        if (!wantFeat) {
            return Frame(centerMs, db.toFloat(), ZERO_CEPS, 0f, hasFeat = false)
        }

        // 1. 预加重 + 加窗
        java.util.Arrays.fill(re, 0.0)
        java.util.Arrays.fill(im, 0.0)
        var prev = buf[0]
        for (i in 0 until frameLen) {
            val x = buf[i] - 0.97 * prev
            prev = buf[i]
            re[i] = x * win[i]
        }

        // 2. FFT
        fft(re, im)

        // 3. 功率谱
        val half = nFft / 2
        for (k in 0..half) pow[k] = re[k] * re[k] + im[k] * im[k]

        // 4. 梅尔能量 → log
        val logMel = DoubleArray(melBank.size)
        for (m in melBank.indices) {
            val w = melBank[m]
            var s = 0.0
            for (k in w.indices) if (w[k] != 0.0) s += w[k] * pow[k]
            logMel[m] = kotlin.math.ln(s + 1e-10)
        }

        // 5. DCT-II → 13 维倒谱（保留 c0 供参考，用 1..12）
        val ceps = DoubleArray(13)
        for (k in 0..N_MFCC) {
            val t = dctTable[k]
            var s = 0.0
            for (n in t.indices) s += t[n] * logMel[n]
            ceps[k] = s
        }

        // 6. 基频（自相关）
        var f0 = 0f
        if (wantF0 && sr > 0) {
            f0 = estimateF0(buf, frameLen, sr)
        }

        return Frame(centerMs, db.toFloat(), ceps, f0, hasFeat = true)
    }

    /** 归一化自相关估算基频，返回 Hz；不可信时返回 0 */
    private fun estimateF0(x: DoubleArray, len: Int, sr: Int): Float {
        val minLag = (sr / 400.0).toInt().coerceAtLeast(2)
        val maxLag = min((sr / 70.0).toInt(), len - 2)
        if (maxLag <= minLag) return 0f

        var mean = 0.0
        for (i in 0 until len) mean += x[i]
        mean /= len

        var r0 = 0.0
        for (i in 0 until len) {
            val v = x[i] - mean
            r0 += v * v
        }
        if (r0 <= 1e-9) return 0f

        var bestLag = 0
        var bestR = 0.0
        var lag = minLag
        while (lag <= maxLag) {
            var s = 0.0
            var i = 0
            val lim = len - lag
            while (i < lim) {
                s += (x[i] - mean) * (x[i + lag] - mean)
                i += 2   // 隔点取样，提速一倍，精度足够
            }
            val r = s / r0
            if (r > bestR) {
                bestR = r
                bestLag = lag
            }
            lag++
        }
        if (bestLag <= 0 || bestR < 0.32) return 0f
        val hz = sr.toDouble() / bestLag
        return if (hz in 60.0..500.0) hz.toFloat() else 0f
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var i = 0
        var j = 0
        while (i < n) {
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
            i++
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var k = 0
            val halfLen = len / 2
            while (k < n) {
                var curR = 1.0
                var curI = 0.0
                for (q in 0 until halfLen) {
                    val i1 = k + q
                    val i2 = i1 + halfLen
                    val ur = re[i1]
                    val ui = im[i1]
                    val vr = re[i2] * curR - im[i2] * curI
                    val vi = re[i2] * curI + im[i2] * curR
                    re[i1] = ur + vr
                    im[i1] = ui + vi
                    re[i2] = ur - vr
                    im[i2] = ui - vi
                    val nwr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nwr
                }
                k += len
            }
            len = len shl 1
        }
    }

    // ==================== 工具 ====================

    private fun nextPow2(v: Int): Int {
        var n = 1
        while (n < v) n = n shl 1
        return n
    }

    private fun hz2mel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
    private fun mel2hz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)

    /** 基频取对数后压缩到与 MFCC 相近量级（100Hz → 0） */
    private fun ln100(f: Double): Double = kotlin.math.ln(f / 100.0)

    private fun l2(v: DoubleArray): DoubleArray {
        var s = 0.0
        for (x in v) s += x * x
        s = sqrt(s)
        if (s <= 1e-9) return v
        return DoubleArray(v.size) { v[it] / s }
    }

}
