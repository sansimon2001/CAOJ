package com.example.mindball.ai

import android.content.Context
import android.util.Log
import com.example.mindball.audio.MfccScanner
import com.example.mindball.audio.Voiceprint
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.data.SpeakerMap
import com.example.mindball.reminder.TodoReminder
import com.example.mindball.util.TimeUtil
import com.example.mindball.util.WorkBuddyBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI 处理流水线：转写（含说话人区分）→ 摘要 → 待办（含到期日）→ 归属项目 → 洞察
 *
 * 设计要点：
 * - **真正串行**：多发几条记录时依次处理，不会同时打满接口额度（429 的主因）
 * - 任一步失败只降级（会话保留，可手动重试），不丢数据
 * - 转写完成后先用**本人声纹**判定哪一段是「我」，再映射成「我 / 对方」，供大模型按角色归纳
 * - 转写完成后按设置决定是否删除原音（按「只留文字不留原音」）
 * - 产生带到期日的待办后，顺手调度一次提醒检查
 */
object AnalysisPipeline {

    private const val TAG = "Pipeline"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 串行闸门
     *
     * 之前只用 `running` 集合去重、然后各自 `scope.launch`，
     * 结果是**多条不同记录并发跑**——和注释写的「串行队列」正相反，
     * 一次导入十段录音就会同时打十几个请求，转写/摘要极易 429。
     */
    private val serial = Mutex()

    /** 处理中标记，避免同一会话重复入队 */
    private val running = mutableSetOf<Long>()

    /**
     * 入队处理
     * @param langOverride 指定本次转写使用的语言/方言 id（见 SpeechLang）。
     *        详情页「疑似粤语 → 按粤语重转」走这个参数，不改用户的全局设置。
     */
    fun enqueue(context: Context, sessionId: Long, langOverride: String? = null) {
        synchronized(running) {
            if (running.contains(sessionId)) return
            running.add(sessionId)
        }
        val app = context.applicationContext
        scope.launch {
            try {
                serial.withLock { process(app, sessionId, langOverride) }
            } catch (e: Exception) {
                Log.e(TAG, "处理会话 $sessionId 失败", e)
            } finally {
                synchronized(running) { running.remove(sessionId) }
            }
        }
    }

    private suspend fun process(
        context: Context,
        sessionId: Long,
        langOverride: String? = null,
    ) = withContext(Dispatchers.IO) {
        var session = Repo.getSession(context, sessionId) ?: return@withContext
        val audio = session.audioPath.takeIf { it.isNotBlank() }?.let { File(it) }

        // 1) 转写（含说话人区分）
        if (!session.isTranscribed) {
            var text = session.transcript
            var segments: List<AsrClient.Seg> = emptyList()
            if (text.isBlank()) {
                if (audio == null || !audio.exists()) {
                    Log.w(TAG, "会话 $sessionId 无音频且无文本，跳过")
                    return@withContext
                }
                val (result, err) = AsrClient.transcribeDetailed(context, audio, langOverride)
                if (result == null) {
                    Log.w(TAG, "会话 $sessionId 转写失败：${err.take(60)}")
                    return@withContext
                }
                text = result.text
                segments = result.segments
                // 记录本次实际使用的语言/方言，详情页据此显示标签、也用于「按检测结果重转」
                session.lang = result.langId
                if (result.downgraded) {
                    Log.w(TAG, "会话 $sessionId 语言降级：${result.note.take(80)}")
                }
            } else if (session.lang.isBlank()) {
                // 外部导入的转写稿：用当前设置推断语种，方便后续做方言归一化。
                // 只记下来，不改写原文——别人的稿子不该被我们擅自改字。
                session.lang = Repo.getStr(context, Repo.K_ASR_LANG, SpeechLangs.ID_AUTO)
            }

            // 说话人 → 我 / 对方（先看声纹能否认出「我」，认不出再用设置里的「我是说话人几」）
            var mySpeaker = Repo.getStr(context, Repo.K_MY_SPEAKER, "说话人1")
            if (audio != null && audio.exists() && segments.isNotEmpty() && Voiceprint.autoEnabled(context)) {
                val tpl = Voiceprint.load(context)
                if (tpl != null) {
                    val spans = segments.map {
                        MfccScanner.TimeSegment(it.speaker, it.startMs, it.endMs)
                    }
                    if (spans.any { it.endMs > it.startMs && it.label.isNotBlank() }) {
                        val m = Voiceprint.match(audio, spans, tpl)
                        if (m != null) {
                            session.voiceSpeaker = m.best
                            session.voiceScore = m.confidence
                            session.voiceMargin = m.margin
                            if (m.accepted) mySpeaker = m.best
                            Log.i(TAG, "会话 $sessionId 声纹：${m.reason}")
                        }
                    }
                }
            }
            // 先按「我是说话人几」定出「我/对方」，再套用用户以前纠正过的真实名字
            val mapped = AsrClient.mapSpeakersToRoles(text, mySpeaker)
            session.transcript = SpeakerMap.applyAliases(mapped, SpeakerMap.load(context))

            // 方言口语 → 普通话书面语（粤语/吴语/闽南语/客家话等）
            // 只对方言做，普通话录音白跑一次调用没有意义
            val lang = SpeechLangs.byId(session.lang)
            if (lang.isDialect && Repo.getBool(context, Repo.K_DIALECT_STD, true)) {
                val (std, stdErr) = LlmClient.normalizeDialect(context, session.transcript, lang.label)
                if (std != null) {
                    session.transcriptStd = std
                    Log.i(TAG, "会话 $sessionId 方言归一化完成（${session.transcript.length} → ${std.length} 字）")
                } else {
                    // 归一化失败不影响主流程：摘要照常用原话，只是可读性差一点
                    Log.w(TAG, "会话 $sessionId 方言归一化跳过：${stdErr.take(80)}")
                }
            }

            // 有效内容太少（多为噪音误识别）→ 直接标记待确认，不浪费大模型调用
            if (TranscriptCleaner.effectiveLength(session.transcript) < 6) {
                session.quality = Session.QUALITY_REVIEW
                session.summary = "有效内容过少，未生成摘要（可能环境嘈杂或无人说话）"
                session.status = Session.STATUS_ANALYZED
                Repo.updateSession(context, session)
                return@withContext
            }
            session.status = Session.STATUS_TRANSCRIBED
            Repo.updateSession(context, session)
        }

        // 2) 大模型分析（摘要按角色区分 + 待办到期日）
        if (session.status != Session.STATUS_ANALYZED) {
            val today = TimeUtil.dateFull(System.currentTimeMillis())
            // 方言稿优先用归一化后的普通话版本：同一件事，模型读普通话稿时
            // 待办与承诺的漏检率明显更低（方言词不会被当成语气词丢掉）
            val material = session.transcriptStd.ifBlank { session.transcript }
            // 先按原文预筛候选项目：清单短、且带别名与线索词，模型才可能选对。
            // 把全部项目名丢过去是「归错项目」的头号原因（名字越像越容易串）。
            val candidates = ProjectMind.candidates(context, material)
            val (analysis, err) = LlmClient.analyze(
                context, material, candidates, today, session.envType, session.lang
            )
            if (analysis == null) {
                Log.w(TAG, "会话 $sessionId 分析失败：${err.take(60)}")
                return@withContext
            }
            session.title = analysis.title.ifBlank { session.title }
            // 模型这次没给出可信归属时，不要清掉已有项目：
            // 重新分析 / 换语言重转都会走到这里，用户手动指定过的项目不能被覆盖成空
            if (analysis.project.isNotBlank() || session.project.isBlank()) {
                session.project = analysis.project
                session.projectConf = analysis.projectConf
                session.projectWhy = if (analysis.otherProjects.isEmpty()) {
                    analysis.projectWhy
                } else {
                    analysis.projectWhy + "；本次还提到 " + analysis.otherProjects.joinToString("、")
                }
            }
            session.summary = if (analysis.insight.isNotBlank()) {
                analysis.summary + "\n\n【洞察】" + analysis.insight
            } else {
                analysis.summary
            }
            session.quality = analysis.quality
            if (analysis.quality == Session.QUALITY_REVIEW && analysis.guardReason.isNotBlank()) {
                Log.w(TAG, "会话 $sessionId 摘要需人工确认：${analysis.guardReason}")
            }
            session.status = Session.STATUS_ANALYZED
            Repo.updateSession(context, session)
            Repo.addTodos(context, session.id, analysis.todos)

            // 有新待办 → 检查是否需要提醒
            if (analysis.todos.isNotEmpty()) {
                TodoReminder.checkAndNotify(context)
            }
        }

        // 3) 原音处理：默认只留文字
        val keepAudio = Repo.getBool(context, Repo.K_KEEP_AUDIO, false)
        if (!keepAudio && audio != null && audio.exists()) {
            runCatching { audio.delete() }
            session.audioPath = ""
            Repo.updateSession(context, session)
        }

        // 4) WorkBuddy 目录同步（只有绑定了目录且开着自动同步才动，失败不打扰用户）
        runCatching { WorkBuddyBridge.autoSync(context, session.id) }
    }

}
