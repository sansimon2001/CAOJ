package com.example.mindball.data

import android.content.Context
import android.database.Cursor
import com.example.mindball.ai.Providers
import com.example.mindball.util.SecureStore

/**
 * 数据仓库：所有读写入口（UI 与后台服务共用）
 * - 读操作同步（数据量小，SQLite 本地毫秒级）
 * - 写操作同步，调用方自行切线程（后台线程/协程）
 */
object Repo {

    @Volatile
    private var dbRef: SessionDb? = null

    fun init(context: Context) {
        if (dbRef == null) {
            synchronized(this) {
                if (dbRef == null) dbRef = SessionDb(context)
            }
        }
    }

    private fun db(context: Context): SessionDb {
        init(context)
        return dbRef!!
    }

    // ==================== 配置 ====================
    const val K_LLM_BASE = "llm_base"          // 如 https://api.deepseek.com/v1
    const val K_LLM_KEY = "llm_key"
    const val K_LLM_MODEL = "llm_model"        // 如 deepseek-chat
    const val K_ASR_URL = "asr_url"            // 语音转写接口地址
    const val K_ASR_KEY = "asr_key"
    const val K_ASR_MODEL = "asr_model"        // 转写服务模型名，如 FunAudioLLM/SenseVoiceSmall
    const val K_ASR_LANG = "asr_lang"          // 识别语言/方言 id（见 ai/SpeechLang，如 zh / yue / en）
    const val K_ASR_PROMPT = "asr_prompt"      // 热词表（人名、公司、专业词的提示词偏置）
    const val K_DIALECT_STD = "dialect_std"    // 1=方言口语自动转成普通话书面语（默认 1）
    const val K_LLM_PROVIDER = "llm_provider"  // 选中的大模型服务商 id（仅用于设置页回显）
    const val K_ASR_PROVIDER = "asr_provider"  // 选中的转写服务商 id
    const val K_AUTO_RECORD = "auto_record"    // 1=连上耳机自动开始录音
    const val K_BT_MIC = "bt_mic"              // 1=用蓝牙耳机麦克风录音（默认 0 用手机麦）
    const val K_KEEP_AUDIO = "keep_audio"      // 1=保留原音（默认 0，只留文字）
    const val K_LAST_CALL_SCAN = "last_call_scan"

    // 说话人区分（声纹）
    const val K_ASR_DIARIZE = "asr_diarize"    // 1=转写时启用说话人分离
    const val K_MY_SPEAKER = "my_speaker"      // 我的说话人标签，如 说话人1
    const val K_VOICEPRINT = "voiceprint"        // 本人声纹模板（JSON）
    const val K_VOICEPRINT_AUTO = "voiceprint_auto" // 1=用声纹自动识别「我」

    // 待办提醒
    const val K_REMIND_ENABLE = "remind_enable"     // 1=开启待办到期提醒
    const val K_REMIND_HOUR = "remind_hour"         // 每日提醒小时（0-23），默认 9
    const val K_SPEAKER_MAP = "speaker_map"         // 说话人标签→角色名的全局映射（学到的）
    const val K_RETROSPECT_TEXT = "retrospect_text" // 最近一次行为复盘报告
    const val K_RETROSPECT_AT = "retrospect_at"     // 复盘生成时间戳

    /** 项目档案是否已从历史会话自举过（只做一次，之后档案表是唯一事实来源） */
    const val K_PROJECT_SEEDED = "project_seeded"

    // 隐私
    const val K_SECURE_WINDOW = "privacy_secure_window" // 1=禁止截屏与最近任务预览（默认 1）
    const val K_PRIVACY_ACK = "privacy_ack"             // 1=已阅读首次启动的数据说明
    const val K_DEFAULTS_SEEDED = "defaults_seeded"      // 1=已写入默认服务商（硅基流动）

    // ===== WorkBuddy 对接 =====
    const val K_WB_DIR = "wb_dir_uri"        // 绑定的 SAF 目录（tree uri）
    const val K_WB_AUTO = "wb_auto"          // 1=分析完成后自动写一条到目录（默认 1）
    const val K_WB_WEBHOOK = "wb_webhook"    // Webhook 地址（可选）
    const val K_WB_TOKEN = "wb_token"        // Webhook 鉴权 token（可选，属于敏感项）

    // ===== 飞书 / 钉钉群机器人（v1.18）=====
    const val K_FS_HOOK = "fs_hook"          // 飞书自定义机器人 Webhook
    const val K_FS_SECRET = "fs_secret"      // 飞书「加签」密钥（敏感项）
    const val K_DT_HOOK = "dt_hook"          // 钉钉自定义机器人 Webhook
    const val K_DT_SECRET = "dt_secret"      // 钉钉「加签」密钥（敏感项）

    /** 需要加密落库的配置项：API Key 与各类密钥都属于「泄露即事故」 */
    private val SENSITIVE_KEYS = setOf(
        K_LLM_KEY, K_ASR_KEY, K_VOICEPRINT, K_WB_TOKEN, K_FS_SECRET, K_DT_SECRET
    )

    /**
     * 配置读写
     *
     * 敏感项（API Key、声纹模板）落库前用 Android Keystore 的 AES-GCM 加密，
     * 读的时候透明解密；老版本存的明文会在第一次读到时就地加密（惰性迁移）。
     * 解密失败（换机、系统清掉密钥）按「未配置」处理，让用户重填，而不是崩。
     */
    fun getStr(context: Context, key: String, def: String = ""): String {
        val raw = db(context).getSetting(key, def)
        if (key !in SENSITIVE_KEYS || raw.isBlank()) return raw
        if (!SecureStore.isSealed(raw)) {
            // 旧数据：明文 → 加密回写，避免一直以明文躺在库里
            SecureStore.seal(raw)?.let { sealed ->
                runCatching { db(context).setSetting(key, sealed) }
            }
            return raw
        }
        return SecureStore.open(raw) ?: ""
    }

    fun setStr(context: Context, key: String, value: String) {
        if (key in SENSITIVE_KEYS && value.isNotBlank()) {
            val sealed = SecureStore.seal(value)
            if (sealed != null) {
                db(context).setSetting(key, sealed)
                return
            }
        }
        db(context).setSetting(key, value)
    }

    fun getBool(context: Context, key: String, def: Boolean = false): Boolean =
        db(context).getSetting(key, if (def) "1" else "0") == "1"
    fun setBool(context: Context, key: String, value: Boolean) =
        db(context).setSetting(key, if (value) "1" else "0")

    /**
     * 首次运行写入默认服务商（硅基流动：转写 + 大模型一套 Key）
     *
     * 只做两件事：把地址/模型/服务商标识填好，让用户**只需要填 API Key**。
     * - 不写 Key：Key 只能用户自己填，任何内置 Key 都是把别人额度送人
     * - 只写一次（K_DEFAULTS_SEEDED）：用户后来换成别家、或把地址清空，都不会被这里改回去
     * - 已有配置不覆盖：升级用户的设置原样保留
     */
    fun seedDefaultProviders(context: Context) {
        if (getBool(context, K_DEFAULTS_SEEDED, false)) return
        runCatching {
            if (getStr(context, K_LLM_BASE).isBlank()) {
                val llm = Providers.defaultLlm()
                setStr(context, K_LLM_BASE, llm.base)
                setStr(context, K_LLM_MODEL, llm.models.firstOrNull().orEmpty())
                setStr(context, K_LLM_PROVIDER, llm.id)
            }
            if (getStr(context, K_ASR_URL).isBlank()) {
                val asr = Providers.defaultAsr()
                setStr(context, K_ASR_URL, asr.url)
                setStr(context, K_ASR_MODEL, asr.model)
                setStr(context, K_ASR_PROVIDER, asr.id)
            }
            setBool(context, K_DEFAULTS_SEEDED, true)
        }
    }

    /** 一键清除：记录、待办、洞见、配置（含已加密的密钥）与本地录音文件 */
    fun wipeAll(context: Context) {
        val d = db(context)
        val w = d.writableDatabase
        w.delete(SessionDb.T_SESSION, null, null)
        w.delete(SessionDb.T_TODO, null, null)
        w.delete(SessionDb.T_PROJECT_INSIGHT, null, null)
        w.delete(SessionDb.T_SETTINGS, null, null)
        // 录音文件（若用户开了「保留原始录音」）
        runCatching {
            context.filesDir?.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".m4a") || f.name.endsWith(".aac"))) f.delete()
            }
        }
    }

    // ==================== 会话 ====================
    fun insertSession(context: Context, session: Session): Long = db(context).insertSession(session)
    fun updateSession(context: Context, session: Session) = db(context).updateSession(session)
    fun deleteSession(context: Context, id: Long) = db(context).deleteSession(id)
    fun deleteTodosOf(context: Context, sessionId: Long) = db(context).deleteTodosOf(sessionId)

    fun getSession(context: Context, id: Long): Session? {
        db(context).readableDatabase.query(
            SessionDb.T_SESSION, null, "id=?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.toSession() else null
        }
    }

    /**
     * 所有已入库记录的音频路径（用于「残留录音恢复」时判断哪些文件还没被记录引用）
     *
     * 只查一列，千条量级下开销可忽略；目的是避免把已入库的文件重复导入。
     */
    fun existingAudioPaths(context: Context): Set<String> {
        val set = HashSet<String>()
        runCatching {
            db(context).readableDatabase.query(
                SessionDb.T_SESSION, arrayOf("audioPath"), null, null, null, null, null
            ).use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0)
                    if (!p.isNullOrBlank()) set.add(p)
                }
            }
        }
        return set
    }

    /** 最近 N 条（今日 / 时间线）—— 含完整转写，只在需要全文时用 */
    fun recentSessions(context: Context, limit: Int = 100): List<Session> = querySessions(
        context, null, null, limit
    )

    /**
     * 未归属项目的记录
     *
     * 「归错」和「没归」是同一件事的两面：AI 拿不准时留空是对的，
     * 但用户得有个地方能看到这些悬空的记录并随手补上，否则它们永远消失在列表里。
     */
    fun unassignedSessions(context: Context, limit: Int = 30): List<Session> =
        querySessions(context, "project='' AND status='analyzed'", null, limit, light = true)

    fun unassignedCount(context: Context): Int {
        db(context).readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${SessionDb.T_SESSION} WHERE project='' AND status='analyzed'", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 最近 N 条（列表用）：转写只取前 200 字 */
    fun recentSessionsLight(context: Context, limit: Int = 100): List<Session> = querySessions(
        context, null, null, limit, light = true
    )

    /** 指定时间范围内（用于「今日」），含完整转写 */
    fun sessionsBetween(context: Context, from: Long, to: Long): List<Session> {
        db(context).readableDatabase.query(
            SessionDb.T_SESSION, null, "startAt>=? AND startAt<=?",
            arrayOf(from.toString(), to.toString()), null, null, "startAt DESC"
        ).use { c -> return c.readAll() }
    }

    /** 指定时间范围内（列表用）：转写只取前 200 字 */
    fun sessionsBetweenLight(context: Context, from: Long, to: Long): List<Session> =
        querySessions(context, "startAt>=? AND startAt<=?", arrayOf(from.toString(), to.toString()), -1, light = true)

    /** 按项目聚合 */
    fun sessionsByProject(context: Context, project: String): List<Session> = querySessions(
        context, "project=?", arrayOf(project), 200
    )

    /** 按项目聚合（列表用）：转写只取前 200 字，项目详情页只显示摘要 */
    fun sessionsByProjectLight(context: Context, project: String): List<Session> = querySessions(
        context, "project=?", arrayOf(project), 200, light = true
    )

    /** 项目聚合结果（只含列表页需要的三个字段） */
    data class ProjectSummary(val name: String, val count: Int, val lastAt: Long)

    /**
     * 项目聚合
     *
     * 原来项目页是把最近 500 条会话整行读出来再 groupBy —— 每行都带完整转写与摘要，
     * 主线程读会有明显卡顿。改成一个 GROUP BY 聚合查询，只回项目名/条数/最近时间。
     */
    fun projectSummaries(context: Context, limit: Int = 200): List<ProjectSummary> {
        db(context).readableDatabase.rawQuery(
            "SELECT project, COUNT(*), MAX(startAt) FROM ${SessionDb.T_SESSION} " +
                "WHERE project<>'' GROUP BY project ORDER BY MAX(startAt) DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val list = mutableListOf<ProjectSummary>()
            while (c.moveToNext()) {
                list.add(ProjectSummary(c.getString(0), c.getInt(1), c.getLong(2)))
            }
            return list
        }
    }
    /** 所有项目名（去重） */
    fun allProjects(context: Context): List<String> {
        db(context).readableDatabase.rawQuery(
            "SELECT DISTINCT project FROM ${SessionDb.T_SESSION} WHERE project<>'' ORDER BY project", null
        ).use { c ->
            val list = mutableListOf<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            return list
        }
    }

    // ==================== 项目档案 ====================
    //
    // 项目从「会话里出现过的字符串」升级为有档案的实体：
    // 名字之外还存别名、线索词和一句话说明（见 ai/ProjectMind）。
    // 好处是改名、合并同义名、给模型喂判别特征都有了落点。

    /** 档案行；aliases/keywords 已拆成列表 */
    data class ProjectProfileRow(
        val name: String,
        val aliases: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
        val note: String = "",
    )

    private const val PROFILE_SEP = "|"

    fun allProjectProfiles(context: Context): List<ProjectProfileRow> =
        db(context).allProjectProfiles().map { row ->
            ProjectProfileRow(
                name = row.first,
                aliases = row.second.split(PROFILE_SEP).filter { it.isNotBlank() },
                keywords = row.third.first.split(PROFILE_SEP).filter { it.isNotBlank() },
                note = row.third.second,
            )
        }

    fun getProjectProfile(context: Context, name: String): ProjectProfileRow? {
        if (name.isBlank()) return null
        val row = db(context).getProjectProfile(name) ?: return null
        return ProjectProfileRow(
            name = name,
            aliases = row.first.split(PROFILE_SEP).filter { it.isNotBlank() },
            keywords = row.second.first.split(PROFILE_SEP).filter { it.isNotBlank() },
            note = row.second.second,
        )
    }

    fun insertProjectProfile(context: Context, row: ProjectProfileRow) {
        if (row.name.isBlank()) return
        db(context).upsertProjectProfile(
            row.name,
            row.aliases.joinToString(PROFILE_SEP),
            row.keywords.joinToString(PROFILE_SEP),
            row.note,
        )
    }

    fun deleteProjectProfile(context: Context, name: String) = db(context).deleteProjectProfile(name)

    fun renameProjectEverywhere(context: Context, from: String, to: String) =
        db(context).renameProjectEverywhere(from, to)

    /** 手动改某条记录的项目（用户纠正入口），同时同步它的待办 */
    fun setSessionProject(context: Context, sessionId: Long, project: String, why: String = "手动指定") =
        db(context).setSessionProject(sessionId, project, 1.0, why)

    fun backfillTodoProjects(context: Context) = db(context).backfillTodoProjects()

    fun recentProjects(context: Context, limit: Int): List<String> = db(context).recentProjects(limit)

    fun recentTitlesOfProject(context: Context, project: String, limit: Int): List<String> =
        db(context).recentTitlesOfProject(project, limit)

    /**
     * 列表页查询
     *
     * `light = true` 时用 `substr(transcript,1,200)` 把转写截断：
     * 列表最多只展示摘要的前一两行，把每条几 KB 的全文读进内存纯粹是浪费——
     * 1000 条记录的全文能到 2~3MB，解成对象后还要再翻倍。
     * 字段名用 AS 对齐到原名，`Cursor.toSession()` 无需改动。
     */
    private fun querySessions(
        context: Context,
        where: String?,
        args: Array<String>?,
        limit: Int,
        light: Boolean = false,
    ): List<Session> {
        val d = db(context).readableDatabase
        if (!light) {
            d.query(
                SessionDb.T_SESSION, null, where, args, null, null, "startAt DESC", limit.toString()
            ).use { return it.readAll() }
        }
        val sql = buildString {
            append("SELECT ").append(LIGHT_COLUMNS).append(" FROM ").append(SessionDb.T_SESSION)
            if (!where.isNullOrBlank()) append(" WHERE ").append(where)
            append(" ORDER BY startAt DESC LIMIT ?")
        }
        val queryArgs = ((args?.toList() ?: emptyList()) + limit.toString()).toTypedArray()
        d.rawQuery(sql, queryArgs).use { return it.readAll() }
    }

    /** 列表用：转写只取前 N 字（够摘要兜底展示） */
    private const val TRANSCRIPT_PREVIEW_CHARS = 200

    /** 列表用字段集：除 transcript 外全取，transcript 截断后仍叫 transcript */
    private val LIGHT_COLUMNS = "id,title,substr(transcript,1,$TRANSCRIPT_PREVIEW_CHARS) AS transcript," +
        "summary,project,source,audioPath,startAt,endAt,durationMs,marked,marks,envType,noiseDb," +
        "speechRatio,quality,voiceSpeaker,voiceScore,voiceMargin,lang,projectConf,projectWhy," +
        "substr(transcriptStd,1,$TRANSCRIPT_PREVIEW_CHARS) AS transcriptStd,status,createdAt"

    private fun Cursor.readAll(): List<Session> {
        val list = mutableListOf<Session>()
        while (moveToNext()) list.add(toSession())
        return list
    }

    private fun Cursor.toSession(): Session = Session(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        transcript = getString(getColumnIndexOrThrow("transcript")),
        summary = getString(getColumnIndexOrThrow("summary")),
        project = getString(getColumnIndexOrThrow("project")),
        source = getString(getColumnIndexOrThrow("source")),
        audioPath = getString(getColumnIndexOrThrow("audioPath")),
        startAt = getLong(getColumnIndexOrThrow("startAt")),
        endAt = getLong(getColumnIndexOrThrow("endAt")),
        durationMs = getLong(getColumnIndexOrThrow("durationMs")),
        marked = getInt(getColumnIndexOrThrow("marked")) == 1,
        marks = runCatching { getString(getColumnIndexOrThrow("marks")) }.getOrDefault(""),
        envType = runCatching { getString(getColumnIndexOrThrow("envType")) }.getOrDefault(Session.ENV_UNKNOWN),
        noiseDb = runCatching { getDouble(getColumnIndexOrThrow("noiseDb")) }.getOrDefault(0.0),
        speechRatio = runCatching { getDouble(getColumnIndexOrThrow("speechRatio")) }.getOrDefault(0.0),
        quality = runCatching { getString(getColumnIndexOrThrow("quality")) }.getOrDefault(Session.QUALITY_OK),
        voiceSpeaker = runCatching { getString(getColumnIndexOrThrow("voiceSpeaker")) }.getOrDefault(""),
        voiceScore = runCatching { getDouble(getColumnIndexOrThrow("voiceScore")) }.getOrDefault(0.0),
        voiceMargin = runCatching { getDouble(getColumnIndexOrThrow("voiceMargin")) }.getOrDefault(0.0),
        lang = runCatching { getString(getColumnIndexOrThrow("lang")) }.getOrDefault(""),
        transcriptStd = runCatching { getString(getColumnIndexOrThrow("transcriptStd")) }.getOrDefault(""),
        projectConf = runCatching { getDouble(getColumnIndexOrThrow("projectConf")) }.getOrDefault(0.0),
        projectWhy = runCatching { getString(getColumnIndexOrThrow("projectWhy")) }.getOrDefault(""),
        status = getString(getColumnIndexOrThrow("status")),
        createdAt = getLong(getColumnIndexOrThrow("createdAt")),
    )

    // ==================== 待办 ====================
    fun addTodos(context: Context, sessionId: Long, todos: List<TodoDraft>) {
        todos.forEach { db(context).insertTodo(sessionId, it.text, it.dueDate, it.project) }
    }

    fun todosOf(context: Context, sessionId: Long): List<TodoItem> {
        db(context).readableDatabase.query(
            SessionDb.T_TODO, null, "sessionId=?", arrayOf(sessionId.toString()), null, null, "id ASC"
        ).use { c ->
            val list = mutableListOf<TodoItem>()
            while (c.moveToNext()) list.add(c.toTodo())
            return list
        }
    }

    fun allTodos(context: Context, limit: Int = 300): List<TodoItem> {
        db(context).readableDatabase.query(
            SessionDb.T_TODO, null, null, null, null, null, "done ASC, id DESC", limit.toString()
        ).use { c ->
            val list = mutableListOf<TodoItem>()
            while (c.moveToNext()) list.add(c.toTodo())
            return list
        }
    }

    /**
     * 批量取「每个会话的待办条数」
     *
     * 列表页原来在 onBindViewHolder 里逐条调 todosOf()——一屏 8 条就是 8 次查询，
     * 每次滑动都会重跑一遍，这是列表「划起来发涩」的直接原因。
     * 改一次 GROUP BY 全取回来，绑定阶段只剩 Map 查找。
     * （ids 全部来自库内 Long，不存在拼接注入问题）
     */
    fun todoCounts(context: Context, sessionIds: List<Long>): Map<Long, Int> {
        if (sessionIds.isEmpty()) return emptyMap()
        val ids = sessionIds.joinToString(",") { it.toString() }
        db(context).readableDatabase.rawQuery(
            "SELECT sessionId, COUNT(*) FROM ${SessionDb.T_TODO} " +
                "WHERE sessionId IN ($ids) GROUP BY sessionId",
            null
        ).use { c ->
            val map = HashMap<Long, Int>(c.count)
            while (c.moveToNext()) map[c.getLong(0)] = c.getInt(1)
            return map
        }
    }

    private fun Cursor.toTodo(): TodoItem = TodoItem(
        id = getLong(getColumnIndexOrThrow("id")),
        sessionId = getLong(getColumnIndexOrThrow("sessionId")),
        text = getString(getColumnIndexOrThrow("text")),
        done = getInt(getColumnIndexOrThrow("done")) == 1,
        dueDate = getString(getColumnIndexOrThrow("dueDate")),
        project = runCatching { getString(getColumnIndexOrThrow("project")) }.getOrDefault(""),
        createdAt = getLong(getColumnIndexOrThrow("createdAt")),
    )

    fun setTodoDone(context: Context, id: Long, done: Boolean) = db(context).setTodoDone(id, done)

    fun setTodoDue(context: Context, id: Long, dueDate: String) = db(context).setTodoDue(id, dueDate)

    /** 到期未完成待办（dueDate <= 指定日期），返回 (id, 文本, 到期日) */
    fun dueTodos(context: Context, onOrBefore: String): List<Triple<Long, String, String>> =
        db(context).dueTodos(onOrBefore)

    /** 待办完成率（觉察页用） */
    fun todoStats(context: Context): Pair<Int, Int> {
        db(context).readableDatabase.rawQuery(
            "SELECT SUM(CASE WHEN done=1 THEN 1 ELSE 0 END), COUNT(*) FROM ${SessionDb.T_TODO}", null
        ).use { c ->
            return if (c.moveToFirst()) {
                val done = if (c.isNull(0)) 0 else c.getInt(0)
                val total = c.getInt(1)
                done to total
            } else 0 to 0
        }
    }

    // ==================== 项目洞见（心力球「项目」栏的洞见卡片） ====================

    /** 保存/覆盖某项目的洞见卡片 */
    fun saveProjectInsight(context: Context, project: String, content: String) =
        db(context).upsertProjectInsight(project, content)

    /** 读取项目洞见卡片（内容, 更新时间） */
    fun getProjectInsight(context: Context, project: String): Pair<String, Long>? =
        db(context).getProjectInsight(project)

    /** (逾期数, 今日到期数) */
    fun todoDueStats(context: Context, today: String): Pair<Int, Int> {
        val due = db(context).dueTodos(today)
        return due.count { it.third < today } to due.count { it.third == today }
    }
}
