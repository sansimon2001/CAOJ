package com.example.mindball.util

import android.app.Activity
import android.view.WindowManager
import com.example.mindball.data.Repo

/**
 * 隐私保护：截屏与最近任务预览
 *
 * `FLAG_SECURE` 会同时做两件事：
 * 1. 系统截屏 / 录屏 / 投屏拿不到这个窗口的内容（截图是一张黑图）
 * 2. 最近任务（多任务）里看不到内容缩略图——这一条是很多人忽略的泄露点：
 *    手机借人、放桌上、录屏演示时，缩略图里就是完整的会议摘要
 *
 * 默认开启（本应用的每一屏都是私人对话内容），设置里可以关掉
 * —— 有些用户需要截图分享给同事，硬锁死反而碍事。
 * 关掉只影响「截屏/预览」，不影响数据本身的加密与访问控制。
 */
object Privacy {

    fun secureWindowEnabled(activity: Activity): Boolean =
        Repo.getBool(activity, Repo.K_SECURE_WINDOW, true)

    fun applySecureWindow(activity: Activity) {
        val lp = activity.window.attributes
        activity.window.setFlags(
            if (secureWindowEnabled(activity)) {
                lp.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                lp.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            },
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }
}
