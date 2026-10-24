package com.example.mindball.audio

/**
 * 音频计算的共享小工具
 *
 * round1 / round3 原来在 AudioProbe 与 MfccScanner 各写了一份完全相同的实现，
 * 一旦想把「入库保留几位」这个口径改掉，很容易只改一处，导致同一段录音
 * 在详情页和环境卡片上显示不同的精度。统一放这里，口径只有一处。
 */
internal object Dsp {

    /** 保留 1 位小数（底噪、峰值用） */
    fun round1(v: Double): Double = Math.round(v * 10) / 10.0

    /** 保留 3 位小数（占比、相似度用） */
    fun round3(v: Double): Double = Math.round(v * 1000) / 1000.0

    /** 线性 RMS（0..1）转 dBFS；静音给 -100 兜底，避免 log10(0) = -Inf */
    fun toDb(rms: Double): Double {
        if (rms <= 1e-7) return -100.0
        return 20.0 * kotlin.math.log10(rms)
    }
}
