package com.example.mindball.util

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import com.example.mindball.R

/**
 * 触摸体验工具
 *
 * 解决的问题：
 * 1) **双击弹出两个页面** —— 手指抖一下连点两次，会连续 startActivity 两次，
 *    观感上像"卡住/白屏一下"。这里做 600ms 防抖，一个点击只响应一次。
 * 2) **按压没有反馈** —— 关键操作补上轻微触感反馈，让"点了"这件事立刻可感知。
 */
object Touch {

    /** 默认防抖间隔（毫秒） */
    private const val DEFAULT_GAP = 600L

    /**
     * 防抖点击：同一 View 在 [gapMs] 内的重复点击只执行第一次
     */
    fun View.onClickOnce(gapMs: Long = DEFAULT_GAP, action: (View) -> Unit) {
        setOnClickListener { v ->
            val now = SystemClock.elapsedRealtime()
            val last = (v.getTag(R.id.tag_last_click) as? Long) ?: 0L
            if (now - last < gapMs) return@setOnClickListener
            v.setTag(R.id.tag_last_click, now)
            action(v)
        }
    }

    /** 轻微触感反馈（不需要 VIBRATE 权限，走的是系统 touch feedback 通道） */
    fun View.tapFeedback() {
        runCatching { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    }

    /** 较重的确认反馈（如打重点标记） */
    fun View.confirmFeedback() {
        runCatching { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }
}
