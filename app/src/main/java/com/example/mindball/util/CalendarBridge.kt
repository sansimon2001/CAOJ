package com.example.mindball.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.mindball.data.TodoItem
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 与系统日历打通
 *
 * 两条路，各有分工：
 * 1. **单条 → 打开系统日历的新建界面**（[insertIntent]）
 *    用 ACTION_INSERT，**不需要任何权限**，用户在日历里确认后保存。
 *    适合「这条待办我要放到日程里」。
 * 2. **批量 → 直接写入日历**（[writeTodos]）
 *    需要 READ/WRITE_CALENDAR。适合「把所有到期待办一次性同步进日历」。
 *
 * 全天事件的坑：CalendarContract 要求 ALL_DAY 事件的 DTSTART/DTEND 是
 * **UTC 午夜**，且 EVENT_TIMEZONE 必须是 "UTC"。按本地时区写会让事件
 * 出现在前一天或后一天，这是接入日历最常见的错误。
 */
object CalendarBridge {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun hasWritePermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun permissions(): Array<String> = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    /**
     * 打开系统日历的「新建日程」界面（无需权限）
     * @param dayMs UTC 午夜的时间戳
     */
    fun insertIntent(ctx: Context, title: String, desc: String, dayMs: Long): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, desc)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, dayMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, dayMs + DAY_MS)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * 批量把未完成待办写成全天事件
     * @return 实际写入条数；-1 = 没有可写日历（无权限或没有可写日历）
     */
    fun writeTodos(ctx: Context, todos: List<TodoItem>): Int {
        if (!hasWritePermission(ctx)) return -1
        val calId = writableCalendarId(ctx) ?: return -1

        val pending = todos.filter { !it.done && it.dueDate.length >= 10 }
        if (pending.isEmpty()) return 0

        // 已存在的不重复插入（同标题 + 同一天）
        val existing = HashSet<String>()
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART),
                "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.ALL_DAY}=1",
                arrayOf(calId.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    existing.add(c.getString(0) + "|" + c.getLong(1))
                }
            }
        }

        var written = 0
        pending.forEach { t ->
            val day = utcMidnight(t.dueDate) ?: return@forEach
            val title = eventTitle(t)
            if (!existing.add("$title|$day")) return@forEach
            val cv = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, "来自「草记」的待办：" + t.text)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, day)
                put(CalendarContract.Events.DTEND, day + DAY_MS)
            }
            if (runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv) }.getOrNull() != null) {
                written++
            }
        }
        return written
    }

    /** 可写日历：优先主日历，其次任意可写的可见日历 */
    private fun writableCalendarId(ctx: Context): Long? = runCatching {
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY,
            ),
            "${CalendarContract.Calendars.VISIBLE}=1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${CalendarContract.Calendars.IS_PRIMARY} DESC"
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }.getOrNull()

    /** "2026-10-22" → UTC 当天零点毫秒 */
    fun utcMidnight(ymd: String): Long? = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        f.parse(ymd.take(10))?.time
    }.getOrNull()

    private fun eventTitle(t: TodoItem): String {
        val text = t.text.replace("\n", " ").trim()
        return "草记 · " + if (text.length > 40) text.take(40) + "…" else text
    }
}
