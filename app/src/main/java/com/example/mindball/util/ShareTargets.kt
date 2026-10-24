package com.example.mindball.util

import android.content.Context
import android.content.Intent

/**
 * 系统应用对接：找出「能接收一段文字」的应用
 *
 * 为什么不用系统分享面板一把梭：
 * - 面板里混着蓝牙、打印、各类 SDK，用户要在一屏图标里找「便签」；
 * - 我们按「笔记/便签」筛一份短列表，点一下就直达，符合「和系统笔记打通」的心智；
 * - 一个都没找到时**回退**系统面板，不会出现「点了没反应」。
 *
 * Android 11+ 的包可见性：查询依赖 Manifest 里的 <queries>（已在清单声明
 * action=SEND + mimeType=text/plain），否则 queryIntentActivities 会返回空列表。
 */
object ShareTargets {

    const val WECHAT = "com.tencent.mm"

    // v1.17：协作类应用（会议/录音产品都在这一挂），装了才出现在出口列表里
    const val DINGTALK = "com.alibaba.android.rimet"
    const val LARK = "com.ss.android.lark"
    const val WEWORK = "com.tencent.wework"

    data class Target(val pkg: String, val label: String)

    /** 协作应用：钉钉 / 飞书 / 企业微信 —— 只返回本机装了的 */
    fun collabTargets(ctx: Context): List<Target> = listOf(
        DINGTALK to "钉钉",
        LARK to "飞书",
        WEWORK to "企业微信",
    ).filter { isInstalled(ctx, it.first) }
        .map { Target(it.first, it.second) }

    /** 常见笔记/便签应用的包名（ColorOS 便签有两个历史包名，都留上） */
    private val NOTE_PKGS = listOf(
        "com.coloros.notepad",
        "com.oplus.notepad",
        "com.coloros.note",
        "com.google.android.keep",
        "com.yinxiang",              // 印象笔记
        "com.evernote",
        "com.youdao.note",           // 有道云笔记
        "com.netease.note",
        "com.microsoft.office.onenote",
        "md.obsidian",
        "notion.id",
        "com.flomo.app",
    )

    private val NOTE_KEYWORDS = listOf(
        "便签", "记事", "笔记", "备忘录", "keep", "note", "印象", "有道", "语雀", "notion", "obsidian", "flomo"
    )

    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** 所有能接收纯文本的应用（去重 + 按名称排序） */
    fun textTargets(ctx: Context): List<Target> {
        val pm = ctx.packageManager
        val probe = Intent(Intent.ACTION_SEND).setType("text/plain")
        val resolved = runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())
        return resolved.asSequence()
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                Target(pkg, label)
            }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
            .toList()
    }

    /** 笔记 / 便签类应用：先按已知包名，再按名称关键词兜住没见过的新应用 */
    fun notesTargets(ctx: Context): List<Target> {
        val all = textTargets(ctx)
        val notes = all.filter { t ->
            t.pkg in NOTE_PKGS || NOTE_KEYWORDS.any { k -> t.label.lowercase().contains(k) }
        }
        // 已知包名的排在前面（用户最可能想找的就是系统便签）
        return notes.sortedBy { if (it.pkg in NOTE_PKGS) 0 else 1 }
    }
}
