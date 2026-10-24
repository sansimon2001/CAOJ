package com.example.mindball.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/**
 * 时间格式化
 *
 * 为什么用 java.time 而不是 SimpleDateFormat：
 * 这是单例 object，里面的 formatter 被**多个线程共享**（今日页后台线程、
 * AnalysisPipeline 的 IO 线程、导出线程都会调 dateFull）。SimpleDateFormat
 * 内部持有可变的 Calendar，并发使用会输出错误日期，极端情况还会抛
 * ArrayIndexOutOfBoundsException —— 属于那种「偶发、难复现、被 runCatching 吞掉」的脏 bug。
 * DateTimeFormatter 是不可变且线程安全的，且 minSdk 26 已原生支持 java.time。
 */
object TimeUtil {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
    private val ymd = DateTimeFormatter.ofPattern("MM-dd", Locale.CHINA)
    private val ymdFull = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA)

    private fun fmt(f: DateTimeFormatter, ts: Long): String =
        f.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

    fun time(ts: Long): String = if (ts <= 0) "--:--" else fmt(hhmm, ts)

    fun date(ts: Long): String = if (ts <= 0) "" else fmt(ymd, ts)

    fun dateFull(ts: Long): String = if (ts <= 0) "" else fmt(ymdFull, ts)

    fun duration(ms: Long): String {
        if (ms <= 0) return "0秒"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${s}秒"
            else -> "${s}秒"
        }
    }

    /** 今天 00:00 */
    fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 今天 23:59:59 */
    fun todayEnd(): Long = todayStart() + 24 * 3600 * 1000 - 1

    /** 是否今天 */
    fun isToday(ts: Long): Boolean = ts in todayStart()..todayEnd()

    /** 相对时间：刚刚 / 5 分钟前 / 今天 14:30 / 10-20 */
    fun relative(ts: Long): String {
        if (ts <= 0) return ""
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000} 分钟前"
            isToday(ts) -> "今天 ${time(ts)}"
            else -> "${date(ts)} ${time(ts)}"
        }
    }
}
