package com.example.mindball.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.mindball.util.dp
import com.example.mindball.R
import com.example.mindball.ai.LlmClient
import com.example.mindball.ai.ProjectMind
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.util.Privacy
import com.example.mindball.databinding.ActivityProjectDetailBinding
import com.example.mindball.util.TimeUtil
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 项目详情（对应心力球「项目」栏）
 *
 * 两块内容：
 * 1. 洞见卡片：把该项目多段沟通串成「当前进展 / 关键决定 / 卡点风险 / 下一步」
 * 2. 主题时间线：该项目全部记录按时间倒序，形成一条脉络
 */
class ProjectDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityProjectDetailBinding
    private lateinit var project: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProjectDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        Privacy.applySecureWindow(this)

        project = intent.getStringExtra("project").orEmpty()
        if (project.isBlank()) {
            finish()
            return
        }

        b.btnBack.setOnClickListener { finish() }
        b.tvProjectName.text = project

        b.btnGenInsight.setOnClickListener { generateInsight() }
        b.btnEditProfile.setOnClickListener { editProfile() }
        bindProfileSummary()

        render()
    }

    /**
     * 识别线索摘要
     *
     * 把「AI 凭什么认这个项目」摊开给用户看：别名、线索词、一句话说明。
     * 没有这些词时，模型只能靠项目名去猜，名字相近就必错——所以这里空着时要明确提示去补。
     */
    private fun bindProfileSummary() {
        val p = Repo.getProjectProfile(this, project)
        val sb = StringBuilder()
        if (p != null) {
            if (p.aliases.isNotEmpty()) sb.append("别名：").append(p.aliases.joinToString("、")).append('\n')
            if (p.keywords.isNotEmpty()) sb.append("线索词：").append(p.keywords.joinToString("、")).append('\n')
            if (p.note.isNotBlank()) sb.append("说明：").append(p.note)
        }
        b.tvProfileSummary.text = if (sb.isBlank()) {
            "还没有识别线索。补上别名和常出现的楼栋/客户/街道，能明显减少「归错项目」。"
        } else {
            sb.toString().trim()
        }
    }

    /**
     * 编辑项目档案：名称 / 别名 / 线索词 / 说明
     *
     * 改名走 [ProjectMind.rename]，会把该项目的会话、待办一起迁过去，
     * 并把旧名留作别名——「城市之光项目」和「城市之光」从此是同一条线，不再各算一半。
     */
    private fun editProfile() {
        val p = Repo.getProjectProfile(this, project)
        val pad = dp(16)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(4), pad, 0)
        }

        fun field(label: String, value: String, hint: String): EditText {
            box.addView(
                TextView(this).apply {
                    text = label
                    textSize = 12f
                    setTextColor(0xFF6B6B6B.toInt())
                    setPadding(0, dp(10), 0, dp(2))
                }
            )
            val e = EditText(this).apply {
                setText(value)
                this.hint = hint
                textSize = 14f
                setSingleLine()
            }
            box.addView(e)
            return e
        }

        val etName = field("项目名称", project, "规范名，如 城市之光")
        val etAlias = field("别名（顿号或逗号分隔）", p?.aliases?.joinToString("、") ?: "", "如：城光、城市之光公寓、城光二期")
        val etKeys = field("线索词（楼栋/客户/街道）", p?.keywords?.joinToString("、") ?: "", "如：12号楼、虞城、璟珹物业")
        val etNote = field("一句话说明", p?.note ?: "", "如：余姚城东公寓项目，主力 40-90㎡")

        AlertDialog.Builder(this)
            .setTitle("识别线索")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "项目名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val oldName = project
                // 先把内容存到旧名下，rename 时会一起搬到新名
                ProjectMind.save(
                    this,
                    ProjectMind.Profile(
                        name = oldName,
                        aliases = splitWords(etAlias.text.toString()),
                        keywords = splitWords(etKeys.text.toString()),
                        note = etNote.text.toString().trim(),
                    )
                )
                if (newName != oldName) {
                    ProjectMind.rename(this, oldName, newName)
                    project = newName
                    b.tvProjectName.text = project
                    Toast.makeText(this, "已重命名为「$newName」，记录已一并迁移", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "已保存，下次分析会按新线索归属", Toast.LENGTH_SHORT).show()
                }
                bindProfileSummary()
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 逗号/顿号/空白都当分隔符 */
    private fun splitWords(raw: String): List<String> =
        raw.split(Regex("[、,，;；\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * 渲染项目详情
     *
     * 三处改动都只为「打开页面立刻有反应」：
     * 1) 查库（可能上百条含全文的记录）挪到 IO 线程
     * 2) 卡片按 12 条一批分批 addView，首屏先出来、剩下的补在后面，
     *    避免一次性创建几百个 View 把主线程占住
     * 3) 洞见卡片与记录列表合并成同一次后台读取
     */
    private fun render() {
        lifecycleScope.launch {
            val (list, saved) = withContext(Dispatchers.IO) {
                Repo.sessionsByProjectLight(this@ProjectDetailActivity, project) to
                    Repo.getProjectInsight(this@ProjectDetailActivity, project)
            }
            if (isFinishing) return@launch

            b.tvProjectMeta.text = String.format(
                Locale.CHINA, "%d 段记录 · 最近 %s", list.size,
                TimeUtil.date(list.firstOrNull()?.startAt ?: 0)
            )

            // 洞见卡片
            if (saved != null && saved.first.isNotBlank()) {
                b.tvInsight.text = saved.first
                b.tvInsightTime.text = "更新于 " + TimeUtil.relative(saved.second)
            }

            // 主题时间线
            b.containerSessions.removeAllViews()
            if (list.isEmpty()) {
                b.tvEmpty.visibility = View.VISIBLE
                return@launch
            }
            b.tvEmpty.visibility = View.GONE
            renderChunk(list, 0)
        }
    }

    /** 分批把记录卡片挂上去（每批 [CHUNK] 条，批间让出主线程） */
    private fun renderChunk(list: List<Session>, start: Int) {
        if (isFinishing) return
        val end = minOf(start + CHUNK, list.size)
        for (i in start until end) {
            b.containerSessions.addView(buildCard(list[i]))
        }
        if (end < list.size) {
            b.containerSessions.post { renderChunk(list, end) }
        }
    }

    private fun buildCard(s: Session): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        val head = TextView(this).apply {
            text = String.format(
                Locale.CHINA,
                "%s %s · %s",
                TimeUtil.date(s.startAt), TimeUtil.time(s.startAt),
                when (s.source) {
                    Session.SOURCE_CALL -> "通话"
                    Session.SOURCE_VOICE -> "语音速记"
                    Session.SOURCE_SHARE -> "外部导入"
                    Session.SOURCE_IMPORT -> "外部录音"
                    Session.SOURCE_IMPORT_TEXT -> "转写稿"
                    else -> "耳机记录"
                }
            )
            setTextColor(0xFF9E9E9E.toInt())
            textSize = 11f
        }

        val title = TextView(this).apply {
            text = s.title
            setTextColor(0xFF1A1A1A.toInt())
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        }

        val summary = TextView(this).apply {
            val plain = s.summary.split("【洞察】")[0].trim()
            text = if (plain.isBlank()) "待转写 / 待分析" else plain
            setTextColor(0xFF6B6B6B.toInt())
            textSize = 12f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(5), 0, 0)
            maxLines = 4
        }

        val open = TextView(this).apply {
            text = "查看详情 ›"
            setTextColor(0xFF4A4A4A.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                startActivity(
                    Intent(this@ProjectDetailActivity, SessionDetailActivity::class.java)
                        .putExtra("id", s.id)
                )
            }
        }

        card.addView(head)
        card.addView(title)
        card.addView(summary)
        card.addView(open)
        return card
    }

    /** 生成 / 更新洞见卡片 */
    private fun generateInsight() {
        if (!LlmClient.isConfigured(this)) {
            Toast.makeText(this, "请先在「设置」配置大模型（可选免费模型）", Toast.LENGTH_LONG).show()
            return
        }

        b.btnGenInsight.isEnabled = false
        b.btnGenInsight.text = "正在生成…"

        lifecycleScope.launch {
            // 读库放 IO：项目下的记录可能上百条，主线程读会让按钮「按下去没反应」
            val list = withContext(Dispatchers.IO) { Repo.sessionsByProject(this@ProjectDetailActivity, project) }
            if (list.isEmpty()) {
                b.btnGenInsight.isEnabled = true
                b.btnGenInsight.text = "生成 / 更新洞见卡片"
                Toast.makeText(this@ProjectDetailActivity, "该项目还没有记录", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val records = list.map { s ->
                Triple(TimeUtil.dateFull(s.startAt), s.title, s.summary.ifBlank { s.transcript })
            }

            val (text, err) = withContext(Dispatchers.IO) { LlmClient.projectInsight(this@ProjectDetailActivity, project, records) }
            b.btnGenInsight.isEnabled = true
            b.btnGenInsight.text = "生成 / 更新洞见卡片"
            if (text.isNullOrBlank()) {
                Toast.makeText(this@ProjectDetailActivity, "生成失败：$err", Toast.LENGTH_LONG).show()
                return@launch
            }
            Repo.saveProjectInsight(this@ProjectDetailActivity, project, text)
            render()
            Toast.makeText(this@ProjectDetailActivity, "洞见卡片已更新", Toast.LENGTH_SHORT).show()
        }
    }


    private companion object {
        /** 每批挂载的卡片数：既有内容立刻出现，又不长时间霸占主线程 */
        const val CHUNK = 12
    }
}
