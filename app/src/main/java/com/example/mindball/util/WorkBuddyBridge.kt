package com.example.mindball.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * WorkBuddy 对接
 *
 * WorkBuddy 跑在电脑上（本机 / 云端工作区），而草记跑在手机上 —— 两者之间没有直连通道，
 * 所以这里只做「标准、可逆、不依赖私有协议」的三条路：
 *
 * 1. **目录同步（主路径）**：用户指定一个目录（手机本地、SD 卡、OneDrive/坚果云等同步盘），
 *    草记把每条记录写成 Markdown + 一份 index.json。电脑上的 WorkBuddy 读这个目录即可，
 *    同步盘会自动把文件带过去。
 * 2. **一键复制**：把最近记录拼成一段结构化 Markdown，粘到 WorkBuddy 对话里就能直接用。
 * 3. **Webhook 推送**：把同结构 JSON POST 到用户填的地址（自建服务 / 自动化平台）。
 *
 * 三条路都不需要草记知道 WorkBuddy 在哪、也不需要 WorkBuddy 反向访问手机。
 *
 * 目录写入全部走 SAF（DocumentsContract），因此**不需要任何存储权限**，
 * 也不要求用户把文件放进公共目录。
 */
object WorkBuddyBridge {

    private const val TAG = "WorkBuddy"

    /** 目录内的子文件夹名，避免把用户选中的目录塞满 */
    private const val SUB_DIR = "草记"
    private const val INDEX_NAME = "caoji-index.json"
    private const val SCHEMA_VERSION = 1

    /** 单次同步上限：几千条记录一次写会很久，也超出任何人的实际阅读量 */
    const val MAX_SYNC = 300

    // ==================== 绑定目录 ====================

    fun boundDir(ctx: Context): Uri? =
        Repo.getStr(ctx, Repo.K_WB_DIR).takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

    /** 绑定目录并持久化授权（否则重启后写不进去） */
    fun bindDir(ctx: Context, treeUri: Uri): Boolean = runCatching {
        ctx.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        Repo.setStr(ctx, Repo.K_WB_DIR, treeUri.toString())
        true
    }.getOrElse {
        // 少数 provider 不支持持久授权：仍然记下，当次会话内可用
        Log.w(TAG, "持久授权失败，退化为临时授权", it)
        Repo.setStr(ctx, Repo.K_WB_DIR, treeUri.toString())
        true
    }

    fun bindLabel(ctx: Context): String {
        val uri = boundDir(ctx) ?: return "未绑定目录"
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(uri)
            id.substringAfterLast(':').ifBlank { id }
        }.getOrDefault("已绑定")
    }

    fun autoEnabled(ctx: Context): Boolean = Repo.getBool(ctx, Repo.K_WB_AUTO, true)
    fun setAuto(ctx: Context, on: Boolean) = Repo.setBool(ctx, Repo.K_WB_AUTO, on)

    // ==================== 同步 ====================

    /**
     * 同步全部（增量覆盖）
     *
     * 不做「删除远端多余文件」——用户可能自己改过 Markdown，删掉是数据损失。
     * 只保证：本地有的记录，目录里一定有对应文件且内容是最新的。
     */
    fun syncAll(ctx: Context, limit: Int = MAX_SYNC): Pair<Int, String> {
        val tree = boundDir(ctx) ?: return 0 to "还没有绑定目录"
        if (!DocumentsContract.isTreeUri(tree)) return 0 to "目录授权已失效，请重新绑定"
        return runCatching {
            val dir = ensureSubDir(ctx, tree) ?: return 0 to "无法在所选目录中创建「$SUB_DIR」文件夹"
            val sessions = Repo.recentSessions(ctx, limit)
                .filter { it.transcript.isNotBlank() || it.summary.isNotBlank() }
            if (sessions.isEmpty()) return 0 to "还没有可同步的记录"

            // 目录只列一次：300 条记录逐条 query 子项在 SAF 上会慢到十几秒
            val known = childMap(ctx, tree, dir).toMutableMap()

            val index = JSONObject().apply {
                put("app", "草记")
                put("schema", SCHEMA_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("count", sessions.size)
            }
            val arr = JSONArray()
            var ok = 0
            for (s in sessions) {
                val todos = Repo.todosOf(ctx, s.id)
                val md = Exporter.sessionMarkdown(s, todos)
                val name = fileNameOf(s)
                if (writeText(ctx, tree, dir, name, md, "text/plain", known)) {
                    ok++
                    arr.put(sessionJson(s, todos, name))
                }
            }
            index.put("sessions", arr)
            val indexOk = writeText(ctx, tree, dir, INDEX_NAME, index.toString(2), "application/json", known)
            if (!indexOk) Log.w(TAG, "index.json 写入失败")
            ok to "已同步 $ok 条记录到 ${bindLabel(ctx)}/$SUB_DIR"
        }.getOrElse {
            Log.w(TAG, "同步失败", it)
            0 to "同步失败：${it.message ?: "未知错误"}"
        }
    }

    /** 单条同步（分析完成后自动调用） */
    fun syncOne(ctx: Context, sessionId: Long): Boolean = runCatching {
        val tree = boundDir(ctx) ?: return false
        val s = Repo.getSession(ctx, sessionId) ?: return false
        if (s.transcript.isBlank() && s.summary.isBlank()) return false
        val dir = ensureSubDir(ctx, tree) ?: return false
        val todos = Repo.todosOf(ctx, s.id)
        writeText(ctx, tree, dir, fileNameOf(s), Exporter.sessionMarkdown(s, todos), "text/plain")
    }.getOrDefault(false)

    /** 流水线收尾时调用：只在「已绑定 + 自动同步开」时动作，且失败不打扰用户 */
    fun autoSync(ctx: Context, sessionId: Long) {
        if (boundDir(ctx) == null || !autoEnabled(ctx)) return
        runCatching { syncOne(ctx, sessionId) }
            .onFailure { Log.w(TAG, "自动同步失败（记录 $sessionId）", it) }
    }

    /** 文件名：caoji-20261022-00123.md（日期 + id，天然按时间排序且不会重名） */
    private fun fileNameOf(s: Session): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
            .format(java.util.Date(s.startAt))
        return "caoji-$date-${s.id}.md"
    }

    private fun sessionJson(s: Session, todos: List<com.example.mindball.data.TodoItem>, file: String): JSONObject =
        JSONObject().apply {
            put("id", s.id)
            put("file", file)
            put("title", s.title)
            put("date", TimeUtil.dateFull(s.startAt))
            put("time", TimeUtil.time(s.startAt))
            put("durationMs", s.durationMs)
            put("project", s.project)
            put("summary", s.summary)
            put("source", s.source)
            put("status", s.status)
            put("url", "caoji://session/${s.id}")
            put("todos", JSONArray().apply {
                todos.forEach { t ->
                    put(JSONObject().apply {
                        put("text", t.text)
                        put("done", t.done)
                        put("due", t.dueDate)
                    })
                }
            })
        }

    // ==================== 复制（粘贴到 WorkBuddy 对话） ====================

    /**
     * 生成一段「喂给 WorkBuddy」的 Markdown
     *
     * 刻意带上前置指令：WorkBuddy 拿到这段话就知道要做什么
     * （归纳进展 / 提取待办 / 找风险），而不是只收到一堆原始记录。
     */
    fun promptMarkdown(ctx: Context, limit: Int = 10): String {
        val sessions = Repo.recentSessions(ctx, limit)
            .filter { it.transcript.isNotBlank() || it.summary.isNotBlank() }
        if (sessions.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# 草记记录（待整理）\n\n")
        sb.append("以下是手机端「草记」最近 ").append(sessions.size).append(" 段记录的整理稿，请：\n")
        sb.append("1. 按项目合并同类事项，输出「当前进展 / 关键结论 / 待办（含责任人） / 风险」四段；\n")
        sb.append("2. 时间相近、主题相同的记录合并成一条，不要逐条复述；\n")
        sb.append("3. 待办用 `- [ ] 事项 —— 截止日期` 的格式。\n\n")
        for (s in sessions) {
            val todos = Repo.todosOf(ctx, s.id)
            sb.append("---\n\n").append(Exporter.sessionMarkdown(s, todos))
        }
        return sb.toString()
    }

    // ==================== Webhook ====================

    private val http: OkHttpClient by lazy { Http.client(connectSec = 10, writeSec = 20, readSec = 20) }

    /**
     * 推送最近记录到 Webhook
     *
     * 载荷就是目录同步用的那份 JSON（同结构），方便对方只写一套解析。
     * 返回 (成功与否, 说明)。阻塞调用，必须在后台线程执行。
     */
    fun pushWebhook(ctx: Context, url: String, token: String, limit: Int = 20): Pair<Boolean, String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false to "地址需要以 http:// 或 https:// 开头"
        }
        if (BuildConfigGuard.isCleartextBlocked(url)) {
            return false to "出于隐私考虑，只允许 https（明文 http 会在传输中被看到）"
        }
        val sessions = Repo.recentSessions(ctx, limit)
            .filter { it.transcript.isNotBlank() || it.summary.isNotBlank() }
        if (sessions.isEmpty()) return false to "还没有可推送的记录"

        val payload = JSONObject().apply {
            put("app", "草记")
            put("schema", SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("sessions", JSONArray().apply {
                sessions.forEach { s -> put(sessionJson(s, Repo.todosOf(ctx, s.id), fileNameOf(s))) }
            })
        }

        return runCatching {
            val builder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", "Caoji/${android.os.Build.MODEL}")
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            http.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty().take(200)
                if (resp.isSuccessful) {
                    true to "已推送 ${sessions.size} 条记录（HTTP ${resp.code}）"
                } else {
                    false to "对方返回 HTTP ${resp.code}：$body"
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "Webhook 推送失败", e)
            false to "推送失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ==================== SAF 写入细节 ====================

    /** 确保「草记」子目录存在，返回其 documentId */
    private fun ensureSubDir(ctx: Context, tree: Uri): String? = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        // 先找同名目录
        children(ctx, tree, rootId).firstOrNull { it.second == SUB_DIR }
            ?.let { return it.first }
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val created = DocumentsContract.createDocument(
            ctx.contentResolver, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, SUB_DIR
        ) ?: return null
        DocumentsContract.getDocumentId(created)
    }.getOrNull()

    /** 列出目录下所有子项：(documentId, displayName) */
    private fun children(ctx: Context, tree: Uri, parentId: String): List<Pair<String, String>> = runCatching {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(c.getString(0) to (c.getString(1) ?: ""))
            }
        }
        out
    }.getOrDefault(emptyList())

    /** 目录下「文件名 → documentId」映射 */
    private fun childMap(ctx: Context, tree: Uri, dirId: String): Map<String, String> =
        children(ctx, tree, dirId).associate { it.second to it.first }

    /**
     * 写文本文件：存在则覆盖，不存在则创建
     *
     * @param known 已知的「文件名 → documentId」缓存（批量同步时传，省掉逐条列目录）
     *
     * 覆盖用 "wt"（截断写），个别 provider 不认，再退回 "w"（同 Exporter 的兼容策略）。
     */
    private fun writeText(
        ctx: Context,
        tree: Uri,
        dirId: String,
        name: String,
        text: String,
        mime: String,
        known: MutableMap<String, String>? = null,
    ): Boolean {
        return runCatching {
            var docId = known?.get(name)
                ?: children(ctx, tree, dirId).firstOrNull { it.second == name }?.first
            if (docId == null) {
                val dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
                docId = DocumentsContract.createDocument(ctx.contentResolver, dirDoc, mime, name)
                    ?.let { DocumentsContract.getDocumentId(it) }
                docId?.let { known?.put(name, it) }
            }
            if (docId == null) return false

            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val bytes = text.toByteArray(Charsets.UTF_8)
            openWrite(ctx, docUri, "wt", bytes) || openWrite(ctx, docUri, "w", bytes)
        }.getOrElse {
            Log.w(TAG, "写入 $name 失败", it)
            false
        }
    }

    private fun openWrite(ctx: Context, uri: Uri, mode: String, bytes: ByteArray): Boolean = runCatching {
        ctx.contentResolver.openOutputStream(uri, mode)?.use {
            it.write(bytes)
            it.flush()
        } != null
    }.getOrDefault(false)
}

/**
 * 只做一个判断：这个地址是不是明文 http
 *
 * 抽出来是因为 `network_security_config` 里已经禁了明文，
 * 但先用代码挡一下能给用户一句人话，而不是一个 OkHttp 异常。
 */
private object BuildConfigGuard {
    fun isCleartextBlocked(url: String): Boolean = url.startsWith("http://")
}
