package com.example.mindball.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地数据库（SQLite 手写，无第三方依赖）
 * 三张表：sessions（录音会话）、todos（待办）、settings（配置键值）
 */
class SessionDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "mindball.db"
        private const val DB_VERSION = 7

        const val T_SESSION = "sessions"
        const val T_TODO = "todos"
        const val T_SETTINGS = "settings"
        const val T_PROJECT_INSIGHT = "project_insights"
        const val T_PROJECT = "projects"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_SESSION (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL DEFAULT '',
              transcript TEXT NOT NULL DEFAULT '',
              summary TEXT NOT NULL DEFAULT '',
              project TEXT NOT NULL DEFAULT '',
              source TEXT NOT NULL DEFAULT 'live',
              audioPath TEXT NOT NULL DEFAULT '',
              startAt INTEGER NOT NULL DEFAULT 0,
              endAt INTEGER NOT NULL DEFAULT 0,
              durationMs INTEGER NOT NULL DEFAULT 0,
              marked INTEGER NOT NULL DEFAULT 0,
              marks TEXT NOT NULL DEFAULT '',
              envType TEXT NOT NULL DEFAULT 'unknown',
              noiseDb REAL NOT NULL DEFAULT 0,
              speechRatio REAL NOT NULL DEFAULT 0,
              quality TEXT NOT NULL DEFAULT 'ok',
              voiceSpeaker TEXT NOT NULL DEFAULT '',
              voiceScore REAL NOT NULL DEFAULT 0,
              voiceMargin REAL NOT NULL DEFAULT 0,
              lang TEXT NOT NULL DEFAULT '',
              transcriptStd TEXT NOT NULL DEFAULT '',
              projectConf REAL NOT NULL DEFAULT 0,
              projectWhy TEXT NOT NULL DEFAULT '',
              status TEXT NOT NULL DEFAULT 'recorded',
              createdAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $T_TODO (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              sessionId INTEGER NOT NULL,
              text TEXT NOT NULL DEFAULT '',
              done INTEGER NOT NULL DEFAULT 0,
              dueDate TEXT NOT NULL DEFAULT '',
              project TEXT NOT NULL DEFAULT '',
              createdAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        // 项目档案：别名/关键词/说明让「名字」变成可判别特征（见 ai/ProjectMind）
        createProjectTable(db)
        // 项目洞见卡片（每个项目一份，重新生成时覆盖）
        createProjectInsightTable(db)
        db.execSQL(
            """
            CREATE TABLE $T_SETTINGS (
              k TEXT PRIMARY KEY,
              v TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_session_start ON $T_SESSION(startAt DESC)")
        db.execSQL("CREATE INDEX idx_todo_session ON $T_TODO(sessionId)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 → v2：待办增加到期日（用于到期提醒）
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE $T_TODO ADD COLUMN dueDate TEXT NOT NULL DEFAULT ''") }
        }
        // v3 → v4：环境感知与摘要质量字段
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN envType TEXT NOT NULL DEFAULT 'unknown'") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN noiseDb REAL NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN speechRatio REAL NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN quality TEXT NOT NULL DEFAULT 'ok'") }
        }

        if (oldVersion < 5) {
            // v5：本人声纹识别结果
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN voiceSpeaker TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN voiceScore REAL NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN voiceMargin REAL NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 6) {
            // v6：识别语言/方言 + 方言口语归一化后的普通话文本
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN lang TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN transcriptStd TEXT NOT NULL DEFAULT ''") }
        }
        if (oldVersion < 7) {
            // v7：项目档案 + 归属依据 + 待办级项目
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN projectConf REAL NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN projectWhy TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE $T_TODO ADD COLUMN project TEXT NOT NULL DEFAULT ''") }
            runCatching { createProjectTable(db) }
        }
        // v2 → v3：会话增加重点标记时间点；新增项目洞见表
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE $T_SESSION ADD COLUMN marks TEXT NOT NULL DEFAULT ''") }
            runCatching { createProjectInsightTable(db) }
        }
    }

    // ==================== 建表 DDL（onCreate / onUpgrade 共用） ====================

    /**
     * 项目档案表
     *
     * 必须被 onCreate 与 onUpgrade(v7) 共用一份：两边各抄一遍时，
     * 一旦改了列，全新安装与升级用户就会拿到两套不同的表结构，
     * 而这种问题只在「升级用户」身上出现，本地新装测不出来。
     */
    private fun createProjectTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_PROJECT (
              name TEXT PRIMARY KEY,
              aliases TEXT NOT NULL DEFAULT '',
              keywords TEXT NOT NULL DEFAULT '',
              note TEXT NOT NULL DEFAULT '',
              updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    /** 项目洞见卡片表（同上，onCreate 与 onUpgrade(v3) 共用） */
    private fun createProjectInsightTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_PROJECT_INSIGHT (
              project TEXT PRIMARY KEY,
              content TEXT NOT NULL DEFAULT '',
              updatedAt INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    // ==================== 写入辅助 ====================

    /**
     * 会话字段 → ContentValues
     *
     * insert 与 update 原来各抄了一份 23 行的 `put(...)`，加字段时必须两处都改，
     * 漏一处就会「新建时对、更新后丢」——最典型的就是声纹结果曾经只存内存。
     * 这里只保留一份，[withCreatedAt] 区分「新建要写创建时间，更新不覆盖」。
     */
    private fun valuesOf(session: Session, withCreatedAt: Boolean = false): ContentValues =
        ContentValues().apply {
            put("title", session.title)
            put("transcript", session.transcript)
            put("summary", session.summary)
            put("project", session.project)
            put("source", session.source)
            put("audioPath", session.audioPath)
            put("startAt", session.startAt)
            put("endAt", session.endAt)
            put("durationMs", session.durationMs)
            put("marked", if (session.marked) 1 else 0)
            put("marks", session.marks)
            put("envType", session.envType)
            put("noiseDb", session.noiseDb)
            put("speechRatio", session.speechRatio)
            put("quality", session.quality)
            put("voiceSpeaker", session.voiceSpeaker)
            put("voiceScore", session.voiceScore)
            put("voiceMargin", session.voiceMargin)
            put("lang", session.lang)
            put("transcriptStd", session.transcriptStd)
            put("projectConf", session.projectConf)
            put("projectWhy", session.projectWhy)
            put("status", session.status)
            if (withCreatedAt) put("createdAt", session.createdAt)
        }

    fun insertSession(session: Session): Long =
        writableDatabase.insert(T_SESSION, null, valuesOf(session, withCreatedAt = true))

    fun updateSession(session: Session) {
        writableDatabase.update(T_SESSION, valuesOf(session), "id=?", arrayOf(session.id.toString()))
    }

    fun deleteSession(id: Long) {
        writableDatabase.delete(T_SESSION, "id=?", arrayOf(id.toString()))
        writableDatabase.delete(T_TODO, "sessionId=?", arrayOf(id.toString()))
    }

    /** 只清待办，不动会话本身（重新分析/换方言重转时用，避免待办重复堆积） */
    fun deleteTodosOf(sessionId: Long) {
        writableDatabase.delete(T_TODO, "sessionId=?", arrayOf(sessionId.toString()))
    }

    fun insertTodo(sessionId: Long, text: String, dueDate: String = "", project: String = "") {
        val cv = ContentValues().apply {
            put("sessionId", sessionId)
            put("text", text)
            put("done", 0)
            put("dueDate", dueDate)
            put("project", project)
            put("createdAt", System.currentTimeMillis())
        }
        writableDatabase.insert(T_TODO, null, cv)
    }

    fun setTodoDue(id: Long, dueDate: String) {
        val cv = ContentValues().apply { put("dueDate", dueDate) }
        writableDatabase.update(T_TODO, cv, "id=?", arrayOf(id.toString()))
    }

    /** 到期未完成的待办（dueDate <= 指定日期） */
    fun dueTodos(onOrBefore: String): List<Triple<Long, String, String>> {
        readableDatabase.query(
            T_TODO, arrayOf("id", "text", "dueDate"),
            "done=0 AND dueDate<>'' AND dueDate<=?", arrayOf(onOrBefore), null, null, "dueDate ASC"
        ).use { c ->
            val list = mutableListOf<Triple<Long, String, String>>()
            while (c.moveToNext()) list.add(Triple(c.getLong(0), c.getString(1), c.getString(2)))
            return list
        }
    }

    fun setTodoDone(id: Long, done: Boolean) {
        val cv = ContentValues().apply { put("done", if (done) 1 else 0) }
        writableDatabase.update(T_TODO, cv, "id=?", arrayOf(id.toString()))
    }

    // ==================== 项目洞见 ====================

    fun upsertProjectInsight(project: String, content: String) {
        val cv = ContentValues().apply {
            put("project", project)
            put("content", content)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(T_PROJECT_INSIGHT, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getProjectInsight(project: String): Pair<String, Long>? {
        readableDatabase.query(
            T_PROJECT_INSIGHT, arrayOf("content", "updatedAt"), "project=?", arrayOf(project), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
        }
    }

    // ==================== 项目档案 ====================

    fun upsertProjectProfile(name: String, aliases: String, keywords: String, note: String) {
        val cv = ContentValues().apply {
            put("name", name)
            put("aliases", aliases)
            put("keywords", keywords)
            put("note", note)
            put("updatedAt", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(T_PROJECT, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun allProjectProfiles(): List<Triple<String, String, Pair<String, String>>> {
        readableDatabase.query(
            T_PROJECT, arrayOf("name", "aliases", "keywords", "note"), null, null, null, null, "name ASC"
        ).use { c ->
            val list = mutableListOf<Triple<String, String, Pair<String, String>>>()
            while (c.moveToNext()) {
                list.add(Triple(c.getString(0), c.getString(1), c.getString(2) to c.getString(3)))
            }
            return list
        }
    }

    fun getProjectProfile(name: String): Pair<String, Pair<String, String>>? {
        readableDatabase.query(
            T_PROJECT, arrayOf("aliases", "keywords", "note"), "name=?", arrayOf(name), null, null, null
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) to (c.getString(1) to c.getString(2)) else null
        }
    }

    fun deleteProjectProfile(name: String) {
        writableDatabase.delete(T_PROJECT, "name=?", arrayOf(name))
    }

    /** 项目改名：会话、待办、洞见、档案全部跟着走，不做「只改一半」 */
    fun renameProjectEverywhere(from: String, to: String) {
        writableDatabase.beginTransaction()
        try {
            val cv = ContentValues().apply { put("project", to) }
            writableDatabase.update(T_SESSION, cv, "project=?", arrayOf(from))
            writableDatabase.update(T_TODO, cv, "project=?", arrayOf(from))
            // 洞见表以 project 为主键，改名时先删旧再留给下次生成
            writableDatabase.delete(T_PROJECT_INSIGHT, "project=?", arrayOf(from))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 只改某条会话的项目，并把它的待办一起同步（待办本身没有独立归属时） */
    fun setSessionProject(sessionId: Long, project: String, confidence: Double, why: String) {
        writableDatabase.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("project", project)
                put("projectConf", confidence)
                put("projectWhy", why)
            }
            writableDatabase.update(T_SESSION, cv, "id=?", arrayOf(sessionId.toString()))
            // 待办若原本沿用会话项目（或为空），跟着改；单独指定过的不动
            writableDatabase.execSQL(
                "UPDATE $T_TODO SET project=? WHERE sessionId=? AND (project='' OR project=?)",
                arrayOf(project, sessionId, project)
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    /** 老数据补归属：待办没有 project 的，用所属会话的项目兜底 */
    fun backfillTodoProjects() {
        runCatching {
            writableDatabase.execSQL(
                "UPDATE $T_TODO SET project=(SELECT s.project FROM $T_SESSION s WHERE s.id=$T_TODO.sessionId) " +
                    "WHERE project='' AND EXISTS(SELECT 1 FROM $T_SESSION s WHERE s.id=$T_TODO.sessionId AND s.project<>'')"
            )
        }
    }

    /** 最近活跃的项目名（按最后一条记录时间倒序） */
    fun recentProjects(limit: Int): List<String> {
        readableDatabase.rawQuery(
            "SELECT project FROM $T_SESSION WHERE project<>'' GROUP BY project " +
                "ORDER BY MAX(startAt) DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            val list = mutableListOf<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            return list
        }
    }

    /** 某项目最近的标题（只取短文本，作为语义参照） */
    fun recentTitlesOfProject(project: String, limit: Int): List<String> {
        readableDatabase.rawQuery(
            "SELECT title FROM $T_SESSION WHERE project=? AND title<>'' ORDER BY startAt DESC LIMIT ?",
            arrayOf(project, limit.toString())
        ).use { c ->
            val list = mutableListOf<String>()
            while (c.moveToNext()) list.add(c.getString(0))
            return list
        }
    }

    fun getSetting(key: String, def: String = ""): String {
        readableDatabase.query(T_SETTINGS, arrayOf("v"), "k=?", arrayOf(key), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else def
        }
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("k", key)
            put("v", value)
        }
        writableDatabase.insertWithOnConflict(T_SETTINGS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
