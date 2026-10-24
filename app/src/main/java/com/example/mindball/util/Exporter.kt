package com.example.mindball.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.example.mindball.data.Session
import com.example.mindball.data.TodoItem
import java.util.Locale

/**
 * 「整理导出」与「分发」
 *
 * 设计原则：
 * 1. **一份文稿，多处复用**——详情页导出、复制、转发微信、存便签，用的都是同一段 Markdown，
 *    不会出现「发微信的字和导出的文件不一样」。
 * 2. 走系统标准协议（ACTION_SEND + text/plain），不依赖任何私有 SDK；
 *    微信、OPPO 便签、Keep、印象笔记这类能收文字的应用都能接。
 * 3. 长文截断保护：微信对单条文本有长度限制，超长会被截断或拒收，
 *    这里主动截到 [MAX_SHARE_CHARS] 并在末尾注明「已截断」。
 */
object Exporter {

    /** 单条文本分享上限（微信友好；再长建议改发文件） */
    const val MAX_SHARE_CHARS = 4500

    /** 单份文稿上限：超出部分不再拼接（防 OOM，也避免生成没人会读的超长文件） */
    private const val MAX_DOC_CHARS = 400_000

    /** 单条记录 → Markdown 文稿 */
    fun sessionMarkdown(s: Session, todos: List<TodoItem>): String {
        val sb = StringBuilder()
        sb.append("# ").append(s.title.ifBlank { "未命名记录" }).append("\n\n")
        sb.append("> ")
            .append(TimeUtil.dateFull(s.startAt)).append(" ")
            .append(TimeUtil.time(s.startAt))
            .append(" · ").append(TimeUtil.duration(s.durationMs))
        if (s.project.isNotBlank()) sb.append(" · 项目：").append(s.project)
        sb.append("\n\n")

        if (s.summary.isNotBlank()) {
            sb.append("## 摘要\n\n").append(s.summary.trim()).append("\n\n")
        }
        val open = todos.filter { !it.done }
        if (open.isNotEmpty()) {
            sb.append("## 待办\n\n")
            open.forEach { t ->
                sb.append("- [ ] ").append(t.text)
                if (t.dueDate.isNotBlank()) sb.append("（").append(t.dueDate).append("）")
                sb.append("\n")
            }
            sb.append("\n")
        }
        if (s.transcript.isNotBlank()) {
            sb.append("## 原文\n\n").append(s.transcript.trim()).append("\n\n")
        }
        sb.append("---\n由「草记」整理导出 · ")
            .append(TimeUtil.dateFull(System.currentTimeMillis())).append("\n")
        return sb.toString()
    }

    /** 多条记录 → 一份文稿（按时间倒序） */
    fun documentMarkdown(title: String, items: List<Pair<Session, List<TodoItem>>>): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")
        sb.append("> 共 ").append(items.size).append(" 段记录 · 导出于 ")
            .append(TimeUtil.dateFull(System.currentTimeMillis())).append("\n\n")

        // 上限保护：几千条记录一次导出会生成几 MB 字符串（拼接时还要翻倍扩容），
        // 低端机容易 OOM；超过上限就停在这里并注明，用户可以单条导出。
        var written = 0
        for ((index, pair) in items.withIndex()) {
            if (sb.length > MAX_DOC_CHARS) break
            val (s, todos) = pair
            sb.append("## ").append(index + 1).append(". ").append(s.title.ifBlank { "未命名记录" })
                .append("\n\n")
            sb.append("**")
                .append(TimeUtil.dateFull(s.startAt)).append(" ")
                .append(TimeUtil.time(s.startAt)).append("**")
            if (s.project.isNotBlank()) sb.append(" · 项目：").append(s.project)
            sb.append("\n\n")
            if (s.summary.isNotBlank()) {
                sb.append(s.summary.trim()).append("\n\n")
            } else if (s.transcript.isNotBlank()) {
                sb.append("（尚未生成摘要，以下为原文摘录）\n\n")
            }
            val open = todos.filter { !it.done }
            if (open.isNotEmpty()) {
                sb.append("待办：\n\n")
                open.forEach { t ->
                    sb.append("- ").append(t.text)
                    if (t.dueDate.isNotBlank()) sb.append("（").append(t.dueDate).append("）")
                    sb.append("\n")
                }
                sb.append("\n")
            }
            written++
        }
        if (written < items.size) {
            sb.append("> 篇幅过长，已导出前 ").append(written).append(" 条（共 ")
                .append(items.size).append(" 条）。其余记录可在详情页单独导出。\n\n")
        }
        sb.append("---\n由「草记」整理导出\n")
        return sb.toString()
    }

    /** 待办清单 → 文稿 */
    fun todosMarkdown(todos: List<TodoItem>): String {
        val sb = StringBuilder()
        sb.append("# 待办清单\n\n")
        sb.append("> 共 ").append(todos.count { !it.done }).append(" 项未完成 · 导出于 ")
            .append(TimeUtil.dateFull(System.currentTimeMillis())).append("\n\n")

        val pending = todos.filter { !it.done }.sortedBy { it.dueDate.ifBlank { "9999-99-99" } }
        if (pending.isEmpty()) {
            sb.append("目前没有未完成的待办。\n")
        } else {
            pending.forEach { t ->
                sb.append("- [ ] ").append(t.text)
                if (t.dueDate.isNotBlank()) sb.append(" —— ").append(t.dueDate)
                sb.append("\n")
            }
        }
        val done = todos.filter { it.done }
        if (done.isNotEmpty()) {
            sb.append("\n## 已完成\n\n")
            done.take(50).forEach { sb.append("- [x] ").append(it.text).append("\n") }
        }
        sb.append("\n---\n由「草记」整理导出\n")
        return sb.toString()
    }

    /**
     * 分享到指定应用（pkg 为空则弹系统选择器）
     * @return true = 已成功唤起；false = 目标不可用（已回退到系统选择器）
     */
    fun share(ctx: Context, text: String, pkg: String? = null, subject: String = ""): Boolean {
        val body = clamp(text)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        if (pkg != null) {
            val direct = Intent(send).setPackage(pkg)
            val ok = runCatching { ctx.startActivity(direct) }.isSuccess
            if (ok) return true
        }
        return runCatching {
            ctx.startActivity(
                Intent.createChooser(send, "分享到").apply {
                    if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
    }

    /**
     * 复制到剪贴板
     *
     * Android 13+ 会在左下角弹出「已复制」预览，并允许输入法/其他应用读取——
     * 记录内容属于敏感信息，标记 EXTRA_IS_SENSITIVE 让系统不展示预览内容、
     * 也不把它加入剪贴板历史。
     */
    fun copy(ctx: Context, text: String): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("草记", clamp(text))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)
        true
    }.getOrDefault(false)

    /**
     * 写入 SAF 选中的文件（UTF-8 文本）
     *
     * 先试 "wt"（截断写），个别三方 provider 不认这个模式，再退回 "w"，
     * 两者都失败才算失败——否则会出现「选了文件却提示导出失败」。
     */
    fun saveText(ctx: Context, uri: Uri, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return runCatching {
            writeWith(ctx, uri, "wt", bytes) || writeWith(ctx, uri, "w", bytes)
        }.getOrDefault(false)
    }

    private fun writeWith(ctx: Context, uri: Uri, mode: String, bytes: ByteArray): Boolean =
        runCatching {
            val os = ctx.contentResolver.openOutputStream(uri, mode) ?: return@runCatching false
            os.use {
                it.write(bytes)
                it.flush()
            }
            true
        }.getOrDefault(false)

    /** 文件名：草记-记录-20261022-1130.md */
    fun fileName(prefix: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA)
            .format(java.util.Date())
        return "草记-$prefix-$stamp.md"
    }

    /** 超长截断（保留尾部说明，用户知道内容被裁了） */
    private fun clamp(text: String): String {
        if (text.length <= MAX_SHARE_CHARS) return text
        return text.take(MAX_SHARE_CHARS) + "\n\n…（内容较长，已截断。完整内容请用「导出文稿」保存为文件）"
    }

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
