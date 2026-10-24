package com.example.mindball.util

import android.content.Context
import androidx.fragment.app.Fragment

/**
 * 界面尺寸换算
 *
 * `dp()` 原来在 InsightFragment / ProjectDetailActivity / SessionDetailActivity
 * 各写了一份一模一样的实现，改密度口径时容易漏改。这里只保留一份。
 *
 * 同时给 Context 与 Fragment 两个重载：Activity 走 Context 版本，
 * Fragment 走 Fragment 版本（内部取的就是它挂载的 Context 的资源）。
 */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Fragment.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
