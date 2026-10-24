package com.example.mindball.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mindball.data.Repo
import com.example.mindball.databinding.FragmentProjectsBinding
import com.example.mindball.util.TimeUtil

/** 项目：跨场景归集 */
class ProjectsFragment : Fragment() {

    private var _b: FragmentProjectsBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: ProjectAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentProjectsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ProjectAdapter(mutableListOf()) { row -> showProjectSessions(row.name) }
        b.rvProjects.layoutManager = LinearLayoutManager(requireContext())
        b.rvProjects.setHasFixedSize(true)
        b.rvProjects.setItemViewCacheSize(8)
        b.rvProjects.adapter = adapter
        b.tvUnsorted.setOnClickListener { showUnsorted() }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 页面复用后，切回本页由这里触发刷新
        if (!hidden) refresh()
    }

    fun refresh() {
        val ctx = context ?: return
        // 用聚合查询代替「读 500 条完整会话再分组」——列表只需要 名称/条数/最近时间，
        // 不必把每条记录的完整转写与摘要都读进内存（这是切页卡顿的来源之一）
        val rows = Repo.projectSummaries(ctx).map { s ->
            ProjectAdapter.ProjectRow(name = s.name, count = s.count, lastAt = s.lastAt)
        }

        adapter.submit(rows)
        b.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        // 未归属记录：给一个能随手清理的入口，否则这些记录只会消失在列表深处
        val unsorted = Repo.unassignedCount(ctx)
        if (unsorted > 0) {
            b.tvUnsorted.text = "有 $unsorted 条记录还没归属项目 · 点这里逐条归类"
            b.tvUnsorted.visibility = View.VISIBLE
        } else {
            b.tvUnsorted.visibility = View.GONE
        }
    }

    /**
     * 逐条归类未归属记录
     *
     * 直接弹一个「日期 + 标题」列表，选一条进详情改项目——
     * 比让用户回到今日页从上往下翻要快得多，也更容易发现 AI 当初为什么没归。
     */
    private fun showUnsorted() {
        val ctx = context ?: return
        val list = Repo.unassignedSessions(ctx)
        if (list.isEmpty()) {
            b.tvUnsorted.visibility = View.GONE
            return
        }
        val items = list.map { s ->
            TimeUtil.date(s.startAt) + "  " + s.title.take(24)
        }.toTypedArray()

        AlertDialog.Builder(ctx)
            .setTitle("未归属的记录")
            .setItems(items) { _, which ->
                startActivity(
                    Intent(ctx, SessionDetailActivity::class.java)
                        .putExtra("id", list[which].id)
                )
            }
            .setNegativeButton("稍后处理", null)
            .show()
    }

    /** 打开项目详情：洞见卡片 + 主题时间线 */
    private fun showProjectSessions(project: String) {
        startActivity(
            Intent(requireContext(), ProjectDetailActivity::class.java).putExtra("project", project)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
