package com.example.mindball.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mindball.R
import com.example.mindball.data.Repo
import com.example.mindball.ui.MainActivity
import com.example.mindball.util.TimeUtil
import java.util.Calendar

/**
 * 待办到期提醒
 *
 * 机制：
 * - AlarmManager 每天在设定时间（默认 09:00）触发 [ReminderReceiver]
 * - 触发后查询「未完成且到期日 <= 今天」的待办：今天到期 + 已逾期
 * - 有内容才发通知（避免无效打扰）
 * - 另外每次 App 启动、以及新待办生成后会主动检查一次（不依赖闹钟）
 *
 * 用 inexact repeating（非精确闹钟），避免 Android 12+ 精确闹钟权限，
 * 误差在数分钟内，对「待办提醒」场景足够。
 */
object TodoReminder {

    private const val CH_ID = "mindball_remind"
    private const val NOTIFY_ID = 2001
    const val ACTION_DAILY = "com.example.mindball.DAILY_REMIND"
    private const val REQ = 3001

    /** 确保每日提醒已调度（App 启动时调用；用户关闭提醒则取消） */
    fun ensureScheduled(context: Context) {
        val enabled = Repo.getBool(context, Repo.K_REMIND_ENABLE, true)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)

        if (!enabled) {
            am.cancel(pi)
            return
        }
        // 重复设置是幂等的（相同 PendingIntent 会覆盖）
        val hour = Repo.getStr(context, Repo.K_REMIND_HOUR, "9").toIntOrNull()?.coerceIn(0, 23) ?: 9
        val first = nextTriggerAt(hour)
        runCatching {
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, first, AlarmManager.INTERVAL_DAY, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_DAILY)
        return PendingIntent.getBroadcast(
            context, REQ, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 下一次触发时间：今天的 hour:00，若已过则明天 */
    private fun nextTriggerAt(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    /**
     * 立即检查并发送提醒
     * @return 提醒的条数（0 表示无需提醒，未发通知）
     */
    fun checkAndNotify(context: Context): Int {
        if (!Repo.getBool(context, Repo.K_REMIND_ENABLE, true)) return 0

        val today = TimeUtil.dateFull(System.currentTimeMillis())
        val due = Repo.dueTodos(context, today)
        if (due.isEmpty()) return 0

        val overdue = due.filter { it.third < today }
        val todayDue = due.filter { it.third == today }

        // 通知内容：概览 + 前 3 条
        val title = buildString {
            if (overdue.isNotEmpty()) append("已逾期 ${overdue.size} 项")
            if (overdue.isNotEmpty() && todayDue.isNotEmpty()) append(" · ")
            if (todayDue.isNotEmpty()) append("今天到期 ${todayDue.size} 项")
        }
        val preview = due.take(3).joinToString("\n") { (_, text, date) ->
            val mark = if (date < today) "逾期" else "今天"
            "· [$mark] $text"
        }
        val more = if (due.size > 3) "\n…共 ${due.size} 项待处理" else ""

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 锁屏上只显示这一条「无内容」版本：待办原文（催款、人名、金额）不该出现在
        // 手机放桌上时的锁屏预览里。解锁后才展开真实内容。
        val publicVersion = NotificationCompat.Builder(context, CH_ID)
            .setSmallIcon(R.drawable.ic_nav_insight)
            .setContentTitle("草记 · 有待办需要处理")
            .setContentText("解锁后查看")
            .build()

        val n = NotificationCompat.Builder(context, CH_ID)
            .setSmallIcon(R.drawable.ic_nav_insight)
            .setContentTitle("待办提醒 · $title")
            .setContentText("点击查看并处理")
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview + more))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        runCatching { nm.notify(NOTIFY_ID, n) }
        return due.size
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CH_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "待办提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }
}
