package com.example.mindball.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.mindball.util.dp
import com.example.mindball.ai.AnalysisPipeline
import com.example.mindball.ai.ProjectMind
import com.example.mindball.ai.Providers
import com.example.mindball.ai.SpeechLang
import com.example.mindball.ai.SpeechLangs
import com.example.mindball.R
import com.example.mindball.audio.Voiceprint
import com.example.mindball.data.Repo
import com.example.mindball.util.CalendarBridge
import com.example.mindball.util.Exporter
import com.example.mindball.util.CollabBridge
import com.example.mindball.util.ShareTargets
import com.example.mindball.util.Touch.onClickOnce
import com.example.mindball.data.SpeakerMap
import com.example.mindball.data.Session
import com.example.mindball.data.parseMarks
import com.example.mindball.util.Privacy
import com.example.mindball.databinding.ActivitySessionDetailBinding
import com.example.mindball.databinding.ItemTodoBinding
import com.example.mindball.util.TimeUtil

/** 记录详情：摘要 / 待办 / 原始转写 / 整理分发 / 重新分析 / 删除 */
class SessionDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionDetailBinding
    private var session: Session? = null

    /** 等待写盘的那份文稿（SAF 选完文件后写进去） */
    private var pendingExport: String? = null

    /** 转写区当前显示哪一版：true=普通话书面版（若有），false=原话 */
    private var showStd: Boolean = true

    private val exportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@registerForActivityResult
        if (Exporter.saveText(this, uri, text)) {
            Exporter.toast(this, "已导出到所选位置")
        } else {
            Exporter.toast(this, "导出失败，换个位置再试")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        Privacy.applySecureWindow(this)

        val id = intent.getLongExtra("id", 0L)
        session = Repo.getSession(this, id)

        b.btnBack.setOnClickListener { finish() }

        val s = session
        if (s == null) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        render(s)

        b.btnRetry.setOnClickListener {
            // 强制重新走一遍分析：清空摘要并置为已转写（若已有转写文本则只重跑大模型）
            val cur = session ?: return@setOnClickListener
            cur.summary = ""
            cur.status = if (cur.transcript.isNotBlank()) Session.STATUS_TRANSCRIBED else Session.STATUS_RECORDED
            Repo.updateSession(this, cur)
            AnalysisPipeline.enqueue(this, cur.id)
            Toast.makeText(this, "已加入处理队列", Toast.LENGTH_SHORT).show()
            b.root.postDelayed({ Repo.getSession(this, cur.id)?.let { render(it) } }, 1500)
        }

        // 摘要可编辑：模型偶尔会跑题，允许直接改（对应「AI 总结跑题乱写」的兜底）
        b.btnEditSummary.setOnClickListener {
            val cur = session ?: return@setOnClickListener
            val input = EditText(this).apply {
                setText(cur.summary)
                minLines = 5
                setPadding(24, 24, 24, 24)
            }
            AlertDialog.Builder(this)
                .setTitle("编辑摘要")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    cur.summary = input.text.toString()
                    cur.quality = Session.QUALITY_OK
                    Repo.updateSession(this, cur)
                    render(cur)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        b.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("删除后无法恢复，确定删除？")
                .setPositiveButton("删除") { _, _ ->
                    session?.let {
                        Repo.deleteSession(this, it.id)
                        it.audioPath.takeIf { p -> p.isNotBlank() }?.let { p ->
                            runCatching { java.io.File(p).delete() }
                        }
                    }
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        setupExportButtons()
    }

    // ==================== 整理与分发 ====================

    private fun setupExportButtons() {
        // 复制：最轻的分发方式，粘到微信/便签/邮件都行
        b.btnCopyAll.setOnClickListener {
            val cur = session ?: return@setOnClickListener
            if (Exporter.copy(this, markdownOf(cur))) {
                Exporter.toast(this, "已复制全文，可直接粘贴到微信或便签")
            } else {
                Exporter.toast(this, "复制失败")
            }
        }

        // 导出 .md：走系统文件选择器，用户自己决定存哪
        b.btnExportMd.setOnClickListener {
            val cur = session ?: return@setOnClickListener
            pendingExport = markdownOf(cur)
            exportFile.launch(Exporter.fileName("记录"))
        }

        // 转发微信：直达微信的「选择聊天」页
        b.btnShareWechat.setOnClickListener {
            val cur = session ?: return@setOnClickListener
            val ok = Exporter.share(this, markdownOf(cur), ShareTargets.WECHAT, cur.title)
            if (!ok) Exporter.toast(this, "没找到微信，已打开系统分享面板")
        }

        // 存到便签 / 协作平台：列出本机可接收文字的笔记类应用 + 已配置的飞书、钉钉群
        b.btnSaveToNotes.setOnClickListener {
            val cur = session ?: return@setOnClickListener
            pickExportTarget(cur)
        }

        // 待办 → 系统日历（单条走 ACTION_INSERT，不需要权限）
        b.btnTodoToCalendar.setOnClickListener {
            val cur = session ?: return@setOnClickListener
            val todos = Repo.todosOf(this, cur.id).filter { !it.done && it.dueDate.length >= 10 }
            when {
                todos.isEmpty() -> Exporter.toast(this, "没有带到期日的未完成待办")
                todos.size == 1 -> addTodoToCalendar(cur, todos[0])
                else -> {
                    val labels = todos.map { "${it.text}（${it.dueDate}）" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("把哪条待办加入日历")
                        .setItems(labels) { _, which -> addTodoToCalendar(cur, todos[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    /** 这条记录的完整文稿（导出/复制/分享共用同一份） */
    private fun markdownOf(s: Session): String =
        Exporter.sessionMarkdown(s, Repo.todosOf(this, s.id))

    /**
     * 让用户挑一个出口
     *
     * 列表最前面是「推送到飞书 / 钉钉群」——但只在设置里配过机器人时才出现，
     * 没配就不显示这两项，避免点了却报「未接入」。
     * 之后是钉钉 / 飞书 / 企业微信等已安装的协作类应用，最后是便签与笔记。
     * 查询要跨包读其他应用信息（几十个），放后台线程；查不到就回退系统分享面板，
     * 不出现「点了没反应」。
     */
    private fun pickExportTarget(cur: Session) {
        val text = markdownOf(cur)
        val subject = cur.title
        val botReady = CollabBridge.Platform.values().filter { CollabBridge.isReady(this, it) }
        Thread {
            val collab = ShareTargets.collabTargets(this)
            val notes = ShareTargets.notesTargets(this)
            val list = (collab + (if (notes.isEmpty()) ShareTargets.textTargets(this) else notes))
                .distinctBy { it.pkg }
            runOnUiThread {
                val labels = mutableListOf<String>()
                val actions = mutableListOf<() -> Unit>()
                botReady.forEach { p ->
                    labels.add("推送到${p.label}群（机器人）")
                    actions.add { pushToCollab(p, cur) }
                }
                list.forEach { t ->
                    labels.add(t.label)
                    actions.add {
                        if (!Exporter.share(this, text, t.pkg, subject)) {
                            Exporter.toast(this, "打开「${t.label}」失败，已打开系统分享")
                        }
                    }
                }
                if (labels.isEmpty()) {
                    Exporter.share(this, text, null, subject)
                    Exporter.toast(this, "没有找到可接收的应用，已打开系统分享")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("发送到")
                    .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    /** 把这一条推到群里（摘要 + 待办，不发逐字稿） */
    private fun pushToCollab(p: CollabBridge.Platform, cur: Session) {
        Exporter.toast(this, "正在推送到${p.label}…")
        Thread {
            val todos = Repo.todosOf(this, cur.id)
            val (_, msg) = runCatching { CollabBridge.pushSession(this, p, cur, todos) }
                .getOrDefault(false to "推送失败")
            runOnUiThread { Exporter.toast(this, msg) }
        }.start()
    }

    private fun addTodoToCalendar(cur: Session, todo: com.example.mindball.data.TodoItem) {
        val day = CalendarBridge.utcMidnight(todo.dueDate)
        if (day == null) {
            Exporter.toast(this, "到期日格式无法识别：${todo.dueDate}")
            return
        }
        val desc = buildString {
            append("来自「草记」的待办\n\n")
            append(todo.text)
            append("\n\n关联记录：").append(cur.title)
            if (cur.summary.isNotBlank()) append("\n\n").append(cur.summary.take(300))
        }
        val title = "草记 · " + todo.text.replace("\n", " ").take(40)
        runCatching { startActivity(CalendarBridge.insertIntent(this, title, desc, day)) }
            .onFailure { Exporter.toast(this, "没有找到系统日历应用") }
    }

    private fun render(s: Session) {
        b.tvTitle.text = s.title
        b.tvMeta.text = buildString {
            append(TimeUtil.dateFull(s.startAt)).append(" ").append(TimeUtil.time(s.startAt))
            append(" · ").append(TimeUtil.duration(s.durationMs))
            append(" · ").append(
                when (s.source) {
                    Session.SOURCE_CALL -> "通话录音"
                    Session.SOURCE_VOICE -> "语音速记"
                    Session.SOURCE_SHARE -> "外部导入"
                    Session.SOURCE_IMPORT -> "外部录音导入"
                    Session.SOURCE_IMPORT_TEXT -> "转写稿导入"
                    else -> "耳机记录"
                }
            )
            // 项目已单独成行、可点击修改，这里不再重复拼进 meta
            append(" · ").append(
                when (s.status) {
                    Session.STATUS_RECORDED -> "待转写"
                    Session.STATUS_TRANSCRIBED -> "待分析"
                    else -> "已整理"
                }
            )
        }
        bindProjectChip(s)

        b.tvSummary.text = s.summary.ifBlank {
            if (s.transcript.isBlank()) "尚未转写：请在「设置」中配置语音转写与大模型接口，或点下方「重新分析」。"
            else "尚未生成摘要：请配置大模型后点「重新分析」。"
        }

        // ===== 环境与收音质量 =====
        val envLabel = when (s.envType) {
            Session.ENV_QUIET -> "安静独处"
            Session.ENV_MEETING -> "会议交谈"
            Session.ENV_STREET -> "嘈杂环境"
            else -> "未识别"
        }
        val speechPct = (s.speechRatio * 100).toInt()
        b.tvEnvInfo.text = buildString {
            append("环境：").append(envLabel)
            if (s.noiseDb < 0) append(" · 底噪 ").append(s.noiseDb.toInt()).append("dB")
            if (speechPct > 0) append(" · 有效人声 ").append(speechPct).append("%")
            append("\n")
            append(
                when {
                    s.envType == Session.ENV_STREET -> "环境较吵，部分内容可能识别不准，建议靠近说话人"
                    s.envType == Session.ENV_QUIET -> "收音清晰"
                    else -> "收音正常"
                }
            )
            // 声纹识别结果
            if (s.voiceSpeaker.isNotBlank()) {
                append("\n")
                val pct = String.format(java.util.Locale.CHINA, "%.0f%%", s.voiceScore * 100)
                if (Voiceprint.isAccepted(s.voiceScore)) {
                    append("声纹：已自动识别 · 我 = ").append(s.voiceSpeaker)
                    append("（匹配度 ").append(pct).append("）")
                } else {
                    append("声纹：把握不足（最像 ").append(s.voiceSpeaker)
                    append("，匹配度 ").append(pct).append("），本次按你设置的「我是说话人几」处理")
                }
            } else if (Voiceprint.hasTemplate(this@SessionDetailActivity) && s.isTranscribed) {
                append("\n声纹：本次无需/无法判定（只有一位说话人，或转写未返回带时间的说话人分段）")
            }
        }

        // ===== 摘要质量提示 =====
        if (s.quality == Session.QUALITY_REVIEW) {
            b.tvQualityWarn.visibility = View.VISIBLE
            b.tvQualityWarn.text = "本次摘要可能存在偏差（原文依据不足或内容过少），已标记为需人工确认，可直接点「编辑」修正"
        } else {
            b.tvQualityWarn.visibility = View.GONE
        }

        // ===== 说话人（可改名并全局记忆） =====
        b.containerSpeakers.removeAllViews()
        val labels = SpeakerMap.labelsIn(s.transcript)
        if (labels.isEmpty()) {
            b.tvSpeakerHint.text = "本次未识别到说话人（转写服务未返回说话人分离结果）"
        } else {
            b.tvSpeakerHint.text = "点一个说话人可改成真实名字，之后所有新记录自动沿用"
            val map = SpeakerMap.load(this)
            labels.forEach { label ->
                val chip = TextView(this).apply {
                    val alias = map[label]
                    text = if (alias.isNullOrBlank() || alias == label) label else "$label → $alias"
                    setTextColor(0xFFB0000E.toInt())
                    textSize = 12f
                    setBackgroundResource(R.drawable.bg_tag_ripple)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    isClickable = true
                    onClickOnce { renameSpeaker(s, label) }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(6)
                chip.layoutParams = lp
                b.containerSpeakers.addView(chip)
            }
        }

        // 待办
        b.containerTodos.removeAllViews()
        val todos = Repo.todosOf(this, s.id)
        // 只有「存在带到期日的未完成待办」时才出现日历按钮，避免点开一片空操作
        b.btnTodoToCalendar.visibility =
            if (todos.any { !it.done && it.dueDate.length >= 10 }) View.VISIBLE else View.GONE
        if (todos.isEmpty()) {
            b.tvTodoEmpty.visibility = View.VISIBLE
        } else {
            b.tvTodoEmpty.visibility = View.GONE
            todos.forEach { t ->
                val ib = ItemTodoBinding.inflate(layoutInflater, b.containerTodos, false)
                ib.cbDone.isChecked = t.done
                ib.tvTodoText.text = t.text
                if (t.done) {
                    ib.tvTodoText.paintFlags =
                        ib.tvTodoText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }
                // 到期日：未设置则提示「设置到期日」，逾期标红
                // 若这条待办属于别的项目，行首标出来——一次会议里 A 的报价和 B 的排期
                // 混在一起时，只靠会话级项目会让人误以为都是同一个项目的事
                val projTag = if (t.project.isNotBlank() && t.project != s.project) "「${t.project}」· " else ""
                val today = TimeUtil.dateFull(System.currentTimeMillis())
                if (t.dueDate.isBlank()) {
                    ib.tvTodoDue.text = projTag + "设置到期日 ›"
                    ib.tvTodoDue.setTextColor(0xFF9E9E9E.toInt())
                } else {
                    val overdue = !t.done && t.dueDate < today
                    val isToday = t.dueDate == today
                    ib.tvTodoDue.text = projTag + when {
                        t.done -> "到期 " + t.dueDate
                        overdue -> "已逾期 · " + t.dueDate
                        isToday -> "今天到期 · " + t.dueDate
                        else -> "到期 " + t.dueDate
                    }
                    ib.tvTodoDue.setTextColor(
                        when {
                            t.done -> 0xFF9E9E9E.toInt()
                            overdue -> 0xFFE60012.toInt()
                            isToday -> 0xFFE60012.toInt()
                            else -> 0xFF6B6B6B.toInt()
                        }
                    )
                }
                ib.tvTodoDue.setOnClickListener {
                    val cal = java.util.Calendar.getInstance()
                    if (t.dueDate.length >= 10) {
                        runCatching {
                            cal.time = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).parse(t.dueDate)!!
                        }
                    }
                    android.app.DatePickerDialog(
                        this,
                        { _, y, m, d ->
                            val due = String.format(java.util.Locale.CHINA, "%04d-%02d-%02d", y, m + 1, d)
                            Repo.setTodoDue(this, t.id, due)
                            render(Repo.getSession(this, s.id) ?: s)
                        },
                        cal.get(java.util.Calendar.YEAR),
                        cal.get(java.util.Calendar.MONTH),
                        cal.get(java.util.Calendar.DAY_OF_MONTH)
                    ).show()
                }

                ib.cbDone.setOnCheckedChangeListener { _, checked ->
                    Repo.setTodoDone(this, t.id, checked)
                    // 只更新这一行的观感：原来勾一下就 render() 整页重建
                    // （重查会话 + 重查待办 + 重建所有 chip），视觉上整页闪一下
                    t.done = checked
                    applyTodoStyle(ib, t.done)
                    b.btnTodoToCalendar.visibility =
                        if (todos.any { !it.done && it.dueDate.length >= 10 }) View.VISIBLE else View.GONE
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (6 * resources.displayMetrics.density).toInt()
                ib.root.layoutParams = lp
                b.containerTodos.addView(ib.root)
            }
        }

        // 重点标记时间点（对应心力球「短按打标 → 时间线定位」）
        val marks = parseMarks(s.marks)
        if (marks.isEmpty()) {
            b.tvMarks.visibility = View.GONE
        } else {
            b.tvMarks.visibility = View.VISIBLE
            b.tvMarks.text = "重点标记 " + marks.size + " 处：" + marks.joinToString("  ") { ms ->
                String.format(java.util.Locale.CHINA, "%02d:%02d", ms / 1000 / 60, ms / 1000 % 60)
            }
        }

        bindLangInfo(s)

        val std = s.transcriptStd
        if (std.isNotBlank()) {
            b.tvTranscriptToggle.visibility = View.VISIBLE
            b.tvTranscriptToggle.text = if (showStd) "查看原话 ›" else "查看普通话版 ›"
            b.tvTranscriptToggle.onClickOnce {
                showStd = !showStd
                render(s)
            }
            b.tvTranscript.text = if (showStd) std else s.transcript
        } else {
            b.tvTranscriptToggle.visibility = View.GONE
            b.tvTranscript.text = s.transcript.ifBlank { "暂无转写文本" }
        }
        b.tvTranscript.visibility = View.VISIBLE
    }

    /**
     * 识别语言与方言的说明条
     *
     * 三件事在这里汇总给用户：
     * 1) 这条录音实际按什么语言识别的；
     * 2) 服务端不支持所选方言时，说明「已经按普通话认了」，别让人误判成 App 不准；
     * 3) 本地检测到文本里有别的方言特征（选了普通话但满屏粤语用字），
     *    直接给一个「按粤语重转」的入口——这比让用户自己回设置页面猜原因有用。
     */
    /**
     * 项目归属条
     *
     * 「AI 归错项目」这件事，光靠提示词不可能 100% 解决，所以必须给用户一个
     * 一眼能看见、一按就能改的入口，并把 AI 的判断依据摊开——
     * 只有用户知道它为什么归到这里，纠正才会变成"喂数据"，而不是每次重来。
     */
    private fun bindProjectChip(s: Session) {
        val label = when {
            s.project.isBlank() -> "项目：未归属 · 点击指定"
            // 0.6 以下视为「疑似」：模型没抄出依据、或只靠线索词推断
            s.projectConf > 0 && s.projectConf < 0.6 -> "项目：${s.project}（待确认）· 点击修改"
            else -> "项目：${s.project} · 点击修改"
        }
        b.tvProject.text = label
        if (s.projectWhy.isNotBlank()) {
            b.tvProjectWhy.text = s.projectWhy
            b.tvProjectWhy.visibility = View.VISIBLE
        } else {
            b.tvProjectWhy.visibility = View.GONE
        }
        b.tvProject.onClickOnce { pickProject(s) }
    }

    /** 选择归属项目：本次相关项目排前面，全量档案与历史项目兜底 */
    private fun pickProject(s: Session) {
        val names = LinkedHashSet<String>()
        // 1) 与本次内容相关的候选（命中别名/线索词的）
        runCatching {
            ProjectMind.candidates(this, s.transcriptStd.ifBlank { s.transcript }, 6)
                .forEach { names.add(it.name) }
        }
        // 2) 全部档案 + 历史上出现过的项目名
        runCatching { Repo.allProjectProfiles(this).forEach { names.add(it.name) } }
        runCatching { Repo.allProjects(this).forEach { names.add(it) } }
        names.remove("")

        val list = names.toMutableList()
        val items = ArrayList<String>()
        items.add("不归属任何项目")
        items.addAll(list)
        items.add("＋ 新建项目…")

        // 选中态：0=不归属，1..n=项目，最后一项=新建
        val checked = if (s.project.isBlank()) 0 else (list.indexOf(s.project) + 1).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("这条记录属于哪个项目？")
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                d.dismiss()
                when {
                    which == 0 -> applyProject(s, "")
                    which == items.size - 1 -> newProject(s)
                    else -> applyProject(s, list[which - 1])
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 新建项目：建了就进档案，下次 AI 就认得 */
    private fun newProject(s: Session) {
        val et = EditText(this).apply {
            hint = "项目名（如 虞懋府 / 城市之光）"
            setSingleLine()
            val pad = dp(16)
            setPadding(pad, dp(12), pad, dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("新建项目")
            .setMessage("建好后可以直接纠正这条记录；并把它记进项目档案，以后同类说法 AI 会自动认到。")
            .setView(et)
            .setPositiveButton("创建并归入") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "项目名不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ProjectMind.ensure(this, name)
                applyProject(s, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 落库 + 学习：纠正一次，下次同类说法就能命中 */
    private fun applyProject(s: Session, project: String) {
        if (project == s.project) return
        runCatching { Repo.setSessionProject(this, s.id, project) }
            .onFailure {
                Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                return
            }
        if (project.isNotBlank()) {
            // 项目若还没有档案，顺手建一份（已有则不动，保住别名与线索词）；
            // 再把本次原文里项目名附近的词记成线索
            ProjectMind.ensure(this, project)
            ProjectMind.learnFromCorrection(this, project, s.transcriptStd.ifBlank { s.transcript })
        }
        s.project = project
        s.projectConf = if (project.isBlank()) 0.0 else 1.0
        s.projectWhy = if (project.isBlank()) "已手动清除归属" else "手动指定（已记入项目档案）"
        bindProjectChip(s)
        // 列表页在 onResume 里重查，回去自然是新项目；这里不必再 setResult
        Toast.makeText(
            this,
            if (project.isBlank()) "已清除项目归属" else "已归入「$project」",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun bindLangInfo(s: Session) {
        if (s.transcript.isBlank()) {
            b.tvLangInfo.visibility = View.GONE
            return
        }
        val lang = if (s.lang.isBlank()) null else SpeechLangs.byId(s.lang)
        val detect = SpeechLangs.detect(s.transcript)

        val sb = StringBuilder()
        sb.append("识别语言：").append(lang?.label ?: "未记录")
        if (s.transcriptStd.isNotBlank()) sb.append(" · 已转普通话书面语")

        // 所选方言是否被当前转写服务真正支持：不支持时把降级原因写出来，
        // 否则用户看到「识别语言：吴语」会以为真的按吴语认了，转头怪识别不准
        if (lang != null) {
            val providerId = Repo.getStr(this, Repo.K_ASR_PROVIDER).ifBlank {
                Providers.guessAsrId(Repo.getStr(this, Repo.K_ASR_URL))
            }
            val provider = Providers.asrById(providerId)
            if (provider != null) {
                val plan = SpeechLangs.plan(lang, provider)
                if (plan.downgraded) sb.append("\n⚠ ").append(plan.note)
            }
        }

        // 只有「当前按普通话/自动认的，但文本明显是某种方言」才提示重转，避免自说自话
        val det = detect
        val canRetryAs = det != null &&
            lang != null &&
            det.langId != lang.id &&
            (lang.id == SpeechLangs.ID_AUTO || lang.id == SpeechLangs.ID_ZH || lang.id == "zh_hant")
        var retryLang: SpeechLang? = null
        if (canRetryAs && det != null) {
            val target = SpeechLangs.byId(det.langId)
            retryLang = target
            sb.append("\n• 文中方言特征：").append(det.reason)
            sb.append("\n• 疑似").append(target.label)
            if (s.audioPath.isNotBlank() && java.io.File(s.audioPath).exists()) {
                sb.append("，点这里可按").append(target.label).append("重转")
            } else {
                sb.append("；原音已删除，无法重转（设置里可开启「保留原始录音」）")
            }
        } else if (lang != null && lang.isDialect && s.transcriptStd.isBlank()) {
            sb.append("\n• 方言稿未转普通话：可在设置 → 识别语言与方言里打开「方言口语自动转普通话」")
        }

        b.tvLangInfo.text = sb.toString()
        b.tvLangInfo.visibility = View.VISIBLE

        val target = retryLang
        val hasAudio = s.audioPath.isNotBlank() && java.io.File(s.audioPath).exists()
        if (target != null && hasAudio) {
            b.tvLangInfo.onClickOnce { confirmRetranscribe(s, target) }
        } else {
            b.tvLangInfo.setOnClickListener(null)
        }
    }

    /** 按指定方言重转：会重新调用转写接口（消耗额度）并覆盖当前转写与摘要 */
    private fun confirmRetranscribe(s: Session, target: SpeechLang) {
        AlertDialog.Builder(this)
            .setTitle("按${target.label}重新转写？")
            .setMessage(
                "会用原音重新调用转写接口（按分钟计费），并覆盖当前的转写文本、普通话版与摘要；" +
                    "原有待办会被替换成重新分析后的结果。"
            )
            .setPositiveButton("重转") { _, _ ->
                val audio = java.io.File(s.audioPath)
                if (!audio.exists()) {
                    Toast.makeText(this, "原音已删除，无法重转", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                s.transcript = ""
                s.transcriptStd = ""
                s.summary = ""
                s.lang = target.id
                s.status = Session.STATUS_RECORDED
                s.quality = Session.QUALITY_OK
                Repo.updateSession(this, s)
                Repo.deleteTodosOf(this, s.id)
                AnalysisPipeline.enqueue(this, s.id, target.id)
                Toast.makeText(this, "已排队，正在按${target.label}重转…", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 勾选状态变化时只改这一行（删除线 + 到期日变灰），不重建整个页面 */
    private fun applyTodoStyle(ib: ItemTodoBinding, done: Boolean) {
        val flags = ib.tvTodoText.paintFlags
        ib.tvTodoText.paintFlags = if (done) {
            flags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            flags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        ib.tvTodoDue.setTextColor(if (done) 0xFF9E9E9E.toInt() else 0xFF6B6B6B.toInt())
    }

    /** 重命名说话人：改这个会话的标签，并把映射记进全局（下次自动沿用） */
    private fun renameSpeaker(s: Session, label: String) {
        val input = EditText(this).apply {
            setText(SpeakerMap.load(this@SessionDetailActivity)[label] ?: label)
            setHint("例如：我 / 张总 / 王工")
            setPadding(36, 24, 36, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("「$label」实际是谁？")
            .setMessage("改完后，这条记录里的 $label 会全部替换，之后所有新记录也会沿用这个称呼。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val updated = SpeakerMap.renameSpeaker(this, s, label, input.text.toString())
                Repo.updateSession(this, updated)
                session = updated
                render(updated)
            }
            .setNegativeButton("取消", null)
            .show()
    }

}
