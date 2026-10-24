package com.example.mindball.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.data.TodoItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 飞书 / 钉钉接入（群自定义机器人）
 *
 * 为什么是机器人 Webhook，而不是「官方应用 + OAuth」：
 * - 飞书开放平台与钉钉开放平台的企业自建应用都要企业管理员建应用、配权限、走审核，
 *   个人用户根本走不通；而「群自定义机器人」是两边都长期支持、任何人 2 分钟就能拿到的入口。
 * - 一条群消息的载荷足够装下一段记录的摘要 + 待办，正好是「开会后同步给同事」的真实需求。
 *
 * 两家的协议差异集中在这三处，别的地方都一样（HTTP POST + JSON）：
 * | 项目 | 飞书 | 钉钉 |
 * |---|---|---|
 * | 加签密钥 | `时间戳\n密钥`，对空串做 HMAC-SHA256 | `时间戳\n密钥` 作为数据，密钥做 key |
 * | 签名位置 | 放在 JSON body 的 sign / timestamp | 拼在 URL 的 query 上，且需 URL 编码 |
 * | 成功判定 | code == 0（旧版是 StatusCode） | errcode == 0 |
 *
 * 相关变量名/接口命名属于各自权利人，见《用户协议与免责声明》中的商标声明。
 */
object CollabBridge {

    private const val TAG = "Collab"

    /** 一条群消息里正文的上限：太长会被折叠，也没人读完 */
    private const val MAX_BODY_CHARS = 2500

    enum class Platform(
        val label: String,
        val hookKey: String,
        val secretKey: String,
        /** 界面上的申请路径提示 */
        val setupHint: String,
    ) {
        FEISHU(
            label = "飞书",
            hookKey = Repo.K_FS_HOOK,
            secretKey = Repo.K_FS_SECRET,
            setupHint = "飞书群 → 设置 → 群机器人 → 添加「自定义机器人」→ 复制 Webhook 地址。"
                + "若开启了「签名校验」，把密钥一并填到下面（推荐开启）。"
        ),
        DINGTALK(
            label = "钉钉",
            hookKey = Repo.K_DT_HOOK,
            secretKey = Repo.K_DT_SECRET,
            setupHint = "钉钉群 → 群设置 → 智能群助手 → 添加机器人「自定义」→ 复制 Webhook。"
                + "安全设置建议选「加签」并把密钥填到下面；选「自定义关键词」时请把关键词设为「草记」。"
        );

        val isFeishu: Boolean get() = this == FEISHU
    }

    // ==================== 配置读写 ====================

    fun hook(ctx: Context, p: Platform): String = Repo.getStr(ctx, p.hookKey)
    fun secret(ctx: Context, p: Platform): String = Repo.getStr(ctx, p.secretKey)

    fun save(ctx: Context, p: Platform, hook: String, secret: String) {
        Repo.setStr(ctx, p.hookKey, hook.trim())
        Repo.setStr(ctx, p.secretKey, secret.trim())
    }

    /** 是否已配置到「能发出去」的程度 */
    fun isReady(ctx: Context, p: Platform): Boolean = hook(ctx, p).startsWith("https://")

    /** 主页/设置页的状态一句话 */
    fun statusText(ctx: Context): String {
        val ready = Platform.values().filter { isReady(ctx, it) }.map { it.label }
        return if (ready.isEmpty()) "未接入" else ready.joinToString(" / ")
    }

    // ==================== 发送 ====================

    /**
     * 发一条纯文本群消息
     *
     * 用 text 而不是卡片：飞书的交互卡片一旦字段写错就整条发不出去，
     * 而群消息的价值在「能稳定送达」，排版好看是次要的。钉钉走 markdown 以便加粗标题。
     *
     * 阻塞调用，必须在后台线程执行。
     */
    fun send(ctx: Context, p: Platform, title: String, body: String): Pair<Boolean, String> {
        val hook = hook(ctx, p)
        if (hook.isBlank()) return false to "还没填${p.label}机器人地址"
        if (!hook.startsWith("https://")) return false to "地址要以 https:// 开头（在${p.label}群里复制完整 Webhook）"
        val secret = secret(ctx, p)

        val url = if (p.isFeishu) hook else appendSign(hook, secret)
        val payload = if (p.isFeishu) feishuPayload(title, body, secret) else dingPayload(title, body)

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", "Caoji/1.18 (Android)")
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@use false to "${p.label}返回 HTTP ${resp.code}"
                }
                parseResult(p, text)
            }
        }.getOrElse { e ->
            Log.w(TAG, "推送到${p.label}失败", e)
            false to when {
                e.message?.contains("UnknownHost") == true -> "网络不可用，或${p.label}域名被拦截"
                e.message?.contains("timeout", true) == true -> "连接超时，稍后再试"
                else -> "推送失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** 发一条测试消息（用户点「测试」时用，不消耗任何记录） */
    fun sendTest(ctx: Context, p: Platform): Pair<Boolean, String> =
        send(
            ctx, p, "草记接入测试",
            "这条消息来自「草记」的接入测试。\n看到它说明配置已生效，之后的记录可以一键同步到本群。"
        )

    /** 推送一条记录（摘要 + 待办） */
    fun pushSession(ctx: Context, p: Platform, s: Session, todos: List<TodoItem>): Pair<Boolean, String> {
        if (s.summary.isBlank() && s.transcript.isBlank()) return false to "这条记录还没有内容可推送"
        return send(ctx, p, "草记 · ${s.title}", buildMessage(s, todos))
    }

    /** 推送最近几条（「一键同步到群」用） */
    fun pushRecent(ctx: Context, p: Platform, limit: Int = 3): Pair<Boolean, String> {
        val sessions = Repo.recentSessions(ctx, limit)
            .filter { it.summary.isNotBlank() || it.transcript.isNotBlank() }
        if (sessions.isEmpty()) return false to "还没有可推送的记录"
        val body = sessions.joinToString("\n\n----------------\n\n") { s ->
            buildMessage(s, Repo.todosOf(ctx, s.id), withHeader = false)
        }
        val title = if (sessions.size == 1) "草记 · ${sessions[0].title}" else "草记 · 最近 ${sessions.size} 条记录"
        val (ok, msg) = send(ctx, p, title, body)
        return if (ok) true to "已推送到${p.label}（${sessions.size} 条）" else false to msg
    }

    // ==================== 消息体 ====================

    /**
     * 群消息正文
     *
     * 刻意不贴原始转写：群里没人读几千字的逐字稿，贴了反而刷屏且容易泄露更多内容。
     * 只给「摘要 + 待办 + 有多少原文在 App 里」，需要细节的人自己去 App 看。
     */
    private fun buildMessage(s: Session, todos: List<TodoItem>, withHeader: Boolean = true): String {
        val sb = StringBuilder()
        if (withHeader) sb.append("【草记】").append(s.title).append('\n')
        sb.append(TimeUtil.dateFull(s.startAt)).append(' ').append(TimeUtil.time(s.startAt))
        if (s.durationMs > 0) sb.append(" · ").append(durationText(s.durationMs))
        if (s.project.isNotBlank()) sb.append(" · 项目：").append(s.project)
        sb.append('\n')

        val summary = s.summary.trim()
        if (summary.isNotBlank()) {
            sb.append("\n摘要\n").append(summary).append('\n')
        }

        val pending = todos.filter { !it.done }
        if (pending.isNotEmpty()) {
            sb.append("\n待办（").append(pending.size).append("）\n")
            pending.take(8).forEach { t ->
                sb.append("☐ ").append(t.text)
                if (t.dueDate.length >= 10) sb.append("（").append(t.dueDate).append("）")
                sb.append('\n')
            }
            if (pending.size > 8) sb.append("… 还有 ").append(pending.size - 8).append(" 条，见草记 App\n")
        }

        val marks = s.marks.takeIf { it.isNotBlank() }?.let { runCatching { org.json.JSONArray(it).length() }.getOrDefault(0) } ?: 0
        if (marks > 0) sb.append("\n已标记重点 ").append(marks).append(" 处")

        if (s.transcript.isNotBlank()) {
            if (marks > 0) sb.append('\n')
            sb.append("\n原文 ").append(s.transcript.length).append(" 字，完整内容在草记 App 内查看。")
        }
        return clamp(sb.toString().trim())
    }

    private fun durationText(ms: Long): String {
        val min = ms / 60_000
        return when {
            min <= 0 -> "${ms / 1000} 秒"
            min < 60 -> "$min 分钟"
            else -> "${min / 60} 小时 ${min % 60} 分"
        }
    }

    private fun clamp(text: String): String =
        if (text.length <= MAX_BODY_CHARS) text
        else text.take(MAX_BODY_CHARS) + "\n…（内容过长已截断，完整内容见草记 App）"

    // ==================== 平台差异 ====================

    private fun feishuPayload(title: String, body: String, secret: String): JSONObject {
        // 飞书的 text 消息不渲染 Markdown，标题只能用「」框出来
        val text = if (title.isBlank()) body else "【$title】\n\n$body"
        return JSONObject().apply {
            if (secret.isNotBlank()) {
                // 飞书：key = "时间戳\n密钥"，对空字符串做 HMAC-SHA256，再 Base64
                val ts = (System.currentTimeMillis() / 1000).toString()
                put("timestamp", ts)
                put("sign", feishuSign(ts, secret))
            }
            put("msg_type", "text")
            put("content", JSONObject().put("text", text))
        }
    }

    private fun dingPayload(title: String, body: String): JSONObject = JSONObject().apply {
        put("msgtype", "markdown")
        put(
            "markdown", JSONObject().apply {
                put("title", title)
                put("text", "**$title**\n\n$body")
            }
        )
        put("at", JSONObject().put("isAtAll", false))
    }

    /** 飞书加签：HmacSHA256(key = "ts\nsecret", data = 空)，Base64 不进 body 的 URL 编码 */
    private fun feishuSign(ts: String, secret: String): String =
        Base64.encodeToString(hmac("$ts\n$secret".toByteArray(), ByteArray(0)), Base64.NO_WRAP)

    /** 钉钉加签：HmacSHA256(key = secret, data = "ts\nsecret")，Base64 后 URL 编码，拼在 query 上 */
    private fun appendSign(hook: String, secret: String): String {
        if (secret.isBlank()) return hook
        val ts = System.currentTimeMillis().toString()
        val raw = hmac(secret.toByteArray(), "$ts\n$secret".toByteArray())
        val sign = URLEncoder.encode(Base64.encodeToString(raw, Base64.NO_WRAP), "UTF-8")
        val sep = if (hook.contains('?')) '&' else '?'
        return "$hook${sep}timestamp=$ts&sign=$sign"
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * 解析返回体
     *
     * 两家都用「HTTP 200 + 业务错误码」表示失败，只看 HTTP 状态码会把
     * 「签名错误」「关键词不匹配」当成成功 —— 用户以为发出去了，群里其实什么都没有。
     */
    private fun parseResult(p: Platform, body: String): Pair<Boolean, String> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return true to "已推送到${p.label}（返回内容非 JSON，按成功处理）"
        return if (p.isFeishu) {
            val code = if (json.has("code")) json.optInt("code", -1) else json.optInt("StatusCode", -1)
            if (code == 0) true to "已推送到飞书"
            else false to "飞书拒绝：${translateFeishu(code, json.optString("msg").ifBlank { json.optString("StatusMessage") })}"
        } else {
            val code = json.optInt("errcode", -1)
            if (code == 0) true to "已推送到钉钉"
            else false to "钉钉拒绝：${translateDing(code, json.optString("errmsg"))}"
        }
    }

    /** 把常见错误码翻成「下一步该改什么」，比原始报错有用得多 */
    private fun translateFeishu(code: Int, raw: String): String = when (code) {
        19021, 19001, 19002 -> "签名校验没通过。请核对「加签密钥」是否与机器人设置里的一致（开启加签时必须填）"
        19024, 19007 -> "机器人已被移出群，或地址已失效，请重新复制 Webhook"
        11232 -> "消息内容为空"
        else -> if (raw.isNotBlank()) "错误 $code（$raw）" else "错误 $code"
    }

    private fun translateDing(code: Int, raw: String): String = when (code) {
        310000 -> "安全设置不匹配。若选了「自定义关键词」，消息里必须包含该关键词（建议设为「草记」）；" +
            "若选了「加签」，请把密钥填上；也可能是 IP 白名单把手机挡住了"
        300001 -> "access_token 无效：Webhook 地址复制不完整，或机器人已被移除"
        400101, 400013 -> "机器人未启用或已被停用"
        else -> if (raw.isNotBlank()) "错误 $code（$raw）" else "错误 $code"
    }

    private val http: OkHttpClient by lazy { Http.client(connectSec = 10, writeSec = 15, readSec = 15) }
}
