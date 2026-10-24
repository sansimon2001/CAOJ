package com.example.mindball.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 每日待办提醒接收器
 * - 由 AlarmManager 每日触发（action = TodoReminder.ACTION_DAILY）
 * - 设备重启后系统会清空闹钟，这里在开机广播里重新调度
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            TodoReminder.ACTION_DAILY -> {
                TodoReminder.checkAndNotify(context)
                // 重复闹钟会自行再触发；此处补一次调度，防止被系统清理后失效
                TodoReminder.ensureScheduled(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                TodoReminder.ensureScheduled(context)
            }
        }
    }
}
