package com.example.mindball.ui

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.mindball.R
import com.example.mindball.util.Touch.onClickOnce
import com.example.mindball.util.Touch.tapFeedback
import com.example.mindball.data.Session
import com.example.mindball.data.TodoItem
import com.example.mindball.databinding.ItemProjectBinding
import com.example.mindball.databinding.ItemSessionBinding
import com.example.mindball.databinding.ItemTodoBinding
import com.example.mindball.util.TimeUtil

/**
 * 会话（记录卡片）适配器
 *
 * 待办条数用外部传入的 Map（一次 GROUP BY 查全），**不要**在 onBindViewHolder 里查库：
 * 绑定发生在滚动过程中，逐条查库会让列表划起来一顿一顿。
 */
class SessionAdapter(
    private val items: MutableList<Session>,
    private val onOpen: (Session) -> Unit,
) : RecyclerView.Adapter<SessionAdapter.VH>() {

    private var todoCounts: Map<Long, Int> = emptyMap()

    inner class VH(val b: ItemSessionBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val b = holder.b

        b.tvTime.text = TimeUtil.time(s.startAt)
        // 时长 + 场景（对应「不区分环境」：让用户一眼知道这段是在什么环境录的）
        val envLabel = when (s.envType) {
            Session.ENV_QUIET -> "安静"
            Session.ENV_MEETING -> "会议"
            Session.ENV_STREET -> "嘈杂"
            else -> ""
        }
        b.tvDuration.text = if (envLabel.isBlank()) {
            TimeUtil.duration(s.durationMs)
        } else {
            "${TimeUtil.duration(s.durationMs)} · $envLabel"
        }
        when (s.source) {
            Session.SOURCE_CALL -> {
                b.tvSourceTag.text = "通话"
                b.tvSourceTag.setBackgroundResource(R.drawable.bg_tag_mark)
            }
            Session.SOURCE_VOICE -> {
                b.tvSourceTag.text = "语音速记"
                b.tvSourceTag.setBackgroundResource(R.drawable.bg_tag)
            }
            Session.SOURCE_SHARE -> {
                b.tvSourceTag.text = "外部导入"
                b.tvSourceTag.setBackgroundResource(R.drawable.bg_tag)
            }
            Session.SOURCE_IMPORT -> {
                b.tvSourceTag.text = "外部录音"
                b.tvSourceTag.setBackgroundResource(R.drawable.bg_tag)
            }
            Session.SOURCE_IMPORT_TEXT -> {
                b.tvSourceTag.text = "转写稿"
                b.tvSourceTag.setBackgroundResource(R.drawable.bg_tag)
            }
            else -> {
                b.tvSourceTag.text = "耳机记录"
                b.tvSourceTag.setBackgroundResource(R.drawable.bg_tag)
            }
        }
        // 重点标记：显示处数（短按打标）
        // 先判空再解析：列表滚动时每行都会走这里，绝大多数记录没有标记，
        // 没必要为一个空字符串去构造 JSONArray（还要包一层异常处理）
        val markCount = if (s.marks.isBlank()) 0 else com.example.mindball.data.parseMarks(s.marks).size
        if (s.marked || markCount > 0) {
            b.tvMarkedTag.visibility = android.view.View.VISIBLE
            b.tvMarkedTag.text = if (markCount > 1) "重点 $markCount" else "重点"
        } else {
            b.tvMarkedTag.visibility = android.view.View.GONE
        }

        b.tvTitle.text = s.title
        b.tvSummary.text = when {
            s.summary.isNotBlank() -> s.summary
            s.transcript.isNotBlank() -> s.transcript.take(120)
            else -> "待转写（配置转写接口后自动处理）"
        }

        if (s.project.isNotBlank()) {
            b.tvProjectTag.visibility = android.view.View.VISIBLE
            // 置信度低的（模型只凭线索词猜、或没抄出依据）加问号，
            // 让「疑似归错」在列表层面就能被看见，不必点进去才发现
            b.tvProjectTag.text =
                if (s.projectConf > 0 && s.projectConf < 0.6) "${s.project}?" else s.project
        } else {
            b.tvProjectTag.visibility = android.view.View.GONE
        }

        val todoCount = todoCounts[s.id] ?: 0
        if (todoCount > 0) {
            b.tvTodoTag.visibility = android.view.View.VISIBLE
            b.tvTodoTag.text = "待办 $todoCount"
        } else {
            b.tvTodoTag.visibility = android.view.View.GONE
        }

        when (s.status) {
            Session.STATUS_RECORDED -> {
                b.tvStatus.text = "待转写"
                b.tvStatus.setTextColor(0xFFE60012.toInt())
            }
            Session.STATUS_TRANSCRIBED -> {
                b.tvStatus.text = "待分析"
                b.tvStatus.setTextColor(0xFFE60012.toInt())
            }
            else -> {
                b.tvStatus.text = "已整理"
                b.tvStatus.setTextColor(0xFF4A4A4A.toInt())
            }
        }

        // 防抖 + 触感反馈：避免连点打开两个详情页（观感上像卡住）
        b.root.onClickOnce { v ->
            v.tapFeedback()
            onOpen(s)
        }
    }

    fun submit(list: List<Session>, counts: Map<Long, Int> = emptyMap()) {
        items.clear()
        items.addAll(list)
        todoCounts = counts
        notifyDataSetChanged()
    }
}

/** 待办适配器 */
class TodoAdapter(
    private val items: List<TodoItem>,
    private val onToggle: (TodoItem, Boolean) -> Unit,
) : RecyclerView.Adapter<TodoAdapter.VH>() {

    inner class VH(val b: ItemTodoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTodoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val b = holder.b
        b.cbDone.setOnCheckedChangeListener(null)
        b.cbDone.isChecked = t.done
        b.tvTodoText.text = t.text
        b.tvTodoText.paintFlags = if (t.done) {
            b.tvTodoText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            b.tvTodoText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        b.cbDone.setOnCheckedChangeListener { _, checked -> onToggle(t, checked) }
    }
}

/** 项目适配器 */
class ProjectAdapter(
    private val items: MutableList<ProjectRow>,
    private val onOpen: (ProjectRow) -> Unit,
) : RecyclerView.Adapter<ProjectAdapter.VH>() {

    data class ProjectRow(val name: String, val count: Int, val lastAt: Long)

    inner class VH(val b: ItemProjectBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.b.tvProjectName.text = p.name
        holder.b.tvProjectMeta.text = "${p.count} 段记录 · 最近 ${TimeUtil.date(p.lastAt)}"
        holder.b.root.onClickOnce { v ->
            v.tapFeedback()
            onOpen(p)
        }
    }

    fun submit(list: List<ProjectRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }
}
