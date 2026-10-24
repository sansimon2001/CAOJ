package com.example.mindball.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.mindball.util.dp
import com.example.mindball.R
import com.example.mindball.ai.LlmClient
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.databinding.FragmentInsightBinding
import com.example.mindball.util.TimeUtil
import java.util.Locale

/** 觉察：行为洞察（对应心力球「觉察」页 / O-Mind） */
class InsightFragment : Fragment() {

    private var _b: FragmentInsightBinding? = null
    private val b get() = _b!!

    /** 后台取数进行中（防抖，避免快速切页叠起多个 1000 条查询） */
    @Volatile
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentInsightBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.btnRetrospect.setOnClickListener { generateRetrospect() }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 底部导航改为复用页面后，切回本页由这里触发刷新
        if (!hidden) refresh()
    }

    /** 生成行为复盘报告（对应心力球「觉察」栏的跨记录行为归纳） */
    private fun generateRetrospect() {
        val ctx = context ?: return
        if (!LlmClient.isConfigured(ctx)) {
            Toast.makeText(ctx, "请先在「设置」配置大模型（可选免费模型）", Toast.LENGTH_LONG).show()
            return
        }

        b.btnRetrospect.isEnabled = false
        b.btnRetrospect.text = "正在复盘…"

        viewLifecycleOwner.lifecycleScope.launch {
            // 读库放 IO：近 100 条记录带完整转写，主线程读会让按钮「按下后没反应」
            val all = withContext(Dispatchers.IO) { Repo.recentSessions(ctx, 100) }
            if (all.isEmpty()) {
                resetRetrospectButton()
                Toast.makeText(ctx, "最近没有可复盘的记录", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 只取近 30 天、且已有摘要的记录
            val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
            val records = all.filter { it.startAt >= since && it.summary.isNotBlank() }
                .map { Triple(TimeUtil.dateFull(it.startAt), it.title, it.summary) }
            if (records.isEmpty()) {
                resetRetrospectButton()
                Toast.makeText(ctx, "近 30 天还没有已整理的记录，先记录几段再复盘", Toast.LENGTH_LONG).show()
                return@launch
            }

            val (text, err) = withContext(Dispatchers.IO) { LlmClient.retrospect(ctx, records) }
            resetRetrospectButton()
            if (text.isNullOrBlank()) {
                Toast.makeText(ctx, "生成失败：$err", Toast.LENGTH_LONG).show()
                return@launch
            }
            Repo.setStr(ctx, Repo.K_RETROSPECT_TEXT, text)
            Repo.setStr(ctx, Repo.K_RETROSPECT_AT, System.currentTimeMillis().toString())
            refresh()
            Toast.makeText(ctx, "复盘报告已更新", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetRetrospectButton() {
        b.btnRetrospect.isEnabled = true
        b.btnRetrospect.text = "生成复盘报告（近 30 天）"
    }

    /** 一次刷新所需的数据（在后台线程读取，避免 1000 条带全文的记录卡住主线程） */
    private class Loaded(
        val all: List<Session>,
        val doneTodos: Int,
        val totalTodos: Int,
        val today: String,
        val overdue: Int,
        val dueToday: Int,
        val retrospect: String,
        val retrospectAt: Long,
    )

    fun refresh() {
        val ctx = context?.applicationContext ?: return
        // 觉察页要汇总最近 1000 条记录，主线程读会明显掉帧 → 后台取数 + 回主线程刷 UI。
        // light 查询把转写截到 200 字（本页只用 summary/title/project），
        // 1000 条能少读进 1~2MB 文本；loading 防抖避免快速切页时叠起多个查询线程。
        if (loading) return
        loading = true
        Thread {
            val data = runCatching {
                val all = Repo.recentSessionsLight(ctx, 1000)
                val (done, total) = Repo.todoStats(ctx)
                val today = TimeUtil.dateFull(System.currentTimeMillis())
                val (overdue, dueToday) = Repo.todoDueStats(ctx, today)
                Loaded(
                    all = all,
                    doneTodos = done,
                    totalTodos = total,
                    today = today,
                    overdue = overdue,
                    dueToday = dueToday,
                    retrospect = Repo.getStr(ctx, Repo.K_RETROSPECT_TEXT),
                    retrospectAt = Repo.getStr(ctx, Repo.K_RETROSPECT_AT).toLongOrNull() ?: 0L,
                )
            }.getOrNull()
            view?.post {
                if (data != null && isAdded && _b != null) applyRefresh(data)
            }
            loading = false
        }.apply { name = "insight-load"; isDaemon = true }.start()
    }

    private fun applyRefresh(d: Loaded) {
        if (!isAdded || _b == null) return
        val all = d.all
        val done = d.doneTodos
        val total = d.totalTodos
        val overdue = d.overdue
        val dueToday = d.dueToday
        val retrospect = d.retrospect
        val retrospectAt = d.retrospectAt
        val totalMs = all.sumOf { it.durationMs }
        val marked = all.count { it.marked }
        val calls = all.count { it.source == Session.SOURCE_CALL }

        b.tvTotalSessions.text = all.size.toString()
        b.tvTotalDuration.text = TimeUtil.duration(totalMs).ifBlank { "0分" }

        b.tvTodoRate.text = if (total > 0) "${done * 100 / total}%" else "—"
        b.tvInsightMeta.text = String.format(
            Locale.CHINA,
            "重点标记 %d 处 · 通话记录 %d 条 · 待办 %d/%d 已完成",
            marked, calls, done, total
        )

        // 待办到期情况（今日到期 / 已逾期）
        b.tvTodoDueInfo.text = when {
            overdue > 0 && dueToday > 0 -> "待办：今天到期 $dueToday 项，已逾期 $overdue 项（点开对应记录可设到期日）"
            overdue > 0 -> "待办：已逾期 $overdue 项，建议尽快处理"
            dueToday > 0 -> "待办：今天到期 $dueToday 项"
            else -> "待办：今天没有到期项"
        }
        b.tvTodoDueInfo.setTextColor(
            when {
                overdue > 0 -> 0xFFE60012.toInt()
                dueToday > 0 -> 0xFFE60012.toInt()
                else -> 0xFF6B6B6B.toInt()
            }
        )

        // 行为复盘报告
        if (retrospect.isNotBlank()) {
            b.tvRetrospect.text = retrospect
            b.tvRetrospectTime.text = "更新于 " + TimeUtil.relative(retrospectAt)
        } else {
            b.tvRetrospectTime.text = ""
        }

        // 近期洞察：从已分析会话的摘要中提取【洞察】段落
        b.containerInsights.removeAllViews()
        val insights = all.filter { it.summary.contains("【洞察】") }.take(8)
        if (insights.isEmpty()) {
            b.tvInsightEmpty.visibility = View.VISIBLE
            return
        }
        b.tvInsightEmpty.visibility = View.GONE

        insights.forEach { s ->
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(8)
                layoutParams = lp
            }

            val title = TextView(requireContext()).apply {
                text = s.title
                setTextColor(0xFF1A1A1A.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val meta = TextView(requireContext()).apply {
                text = "${TimeUtil.date(s.startAt)} ${TimeUtil.time(s.startAt)} · ${s.project.ifBlank { "未归项目" }}"
                setTextColor(0xFF9E9E9E.toInt())
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            }

            val body = TextView(requireContext()).apply {
                text = s.summary.substringAfter("【洞察】").trim()
                setTextColor(0xFF6B6B6B.toInt())
                textSize = 12f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(8), 0, 0)
            }

            card.addView(title)
            card.addView(meta)
            card.addView(body)
            card.setOnClickListener {
                startActivity(Intent(requireContext(), SessionDetailActivity::class.java).putExtra("id", s.id))
            }
            b.containerInsights.addView(card)
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
