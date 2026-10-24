package com.example.mindball.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mindball.R
import com.example.mindball.audio.RecorderService
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.databinding.FragmentTodayBinding
import com.example.mindball.util.TimeUtil
import java.util.Locale

/** 今日：时间线（对应心力球「今日」页） */
class TodayFragment : Fragment() {

    private var _b: FragmentTodayBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: SessionAdapter

    /** 后台刷新进行中（避免切页/返回时叠起多个线程） */
    @Volatile
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentTodayBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SessionAdapter(mutableListOf()) { s -> openDetail(s) }
        b.rvSessions.layoutManager = LinearLayoutManager(requireContext())
        // 列表项高度固定 + 多缓存两屏，滚动时少一次重新布局、少一次绑定
        b.rvSessions.setHasFixedSize(true)
        b.rvSessions.setItemViewCacheSize(8)
        b.rvSessions.adapter = adapter

        // 长按说话：灵感速记 / 即时问答（对应心力球长按 AI 键）
        b.btnQuickVoice.setOnClickListener {
            startActivity(Intent(requireContext(), QuickVoiceActivity::class.java))
        }

        b.btnToggleRecord.setOnClickListener {
            if (RecorderService.isRecording) {
                startServiceWithAction(RecorderService.ACTION_STOP)
            } else {
                // 手动开始：把当前选中的音源一起带过去，避免「刚点完手机麦又被设置改回去」
                startServiceWithAction(
                    RecorderService.ACTION_START,
                    "manual",
                    RecorderService.sourceOf(selectedBtMic()),
                )
            }
            b.root.postDelayed({ refresh() }, 800)
        }

        // 音源：手机麦克风 / 蓝牙耳机（手动指定，与「连耳机自动触发」并存）
        b.chipMicPhone.setOnClickListener { pickSource(btMic = false) }
        b.chipMicBt.setOnClickListener { pickSource(btMic = true) }
        renderSourceChips()
    }

    /** 当前选中的音源是否耳机麦（录音中看真实生效值，空闲时看偏好） */
    private fun selectedBtMic(): Boolean {
        val ctx = context?.applicationContext ?: return false
        return if (RecorderService.isRecording) RecorderService.currentIsBtMic
        else Repo.getBool(ctx, Repo.K_BT_MIC, false)
    }

    private fun headsetConnected(): Boolean =
        (activity as? MainActivity)?.isHeadsetConnected() ?: false

    /** 耳机插拔时由 Activity 调用：只刷音源选项，不查库 */
    fun refreshSourceUi() {
        renderSourceChips()
    }

    /**
     * 手动切换音源
     *
     * - 没在录：只改偏好，下次开始就用它（不影响当前任何东西）
     * - 正在录：交给服务切一段 —— 当前这段先收尾保存，紧接着用新音源继续，不丢内容
     */
    private fun pickSource(btMic: Boolean) {
        val ctx = context?.applicationContext ?: return
        if (btMic && !headsetConnected()) {
            Toast.makeText(ctx, "蓝牙耳机未连接：连上后再选它，或继续用手机麦克风", Toast.LENGTH_SHORT).show()
            return
        }
        if (RecorderService.isRecording) {
            // 服务已在前台运行，用普通 startService 即可（不走 startForegroundService，避免 5s 限制）
            val intent = Intent(ctx, RecorderService::class.java)
                .setAction(RecorderService.ACTION_SWITCH_SOURCE)
                .putExtra(RecorderService.EXTRA_SOURCE, RecorderService.sourceOf(btMic))
            runCatching { ctx.startService(intent) }
                .onFailure { Log.w("TodayFragment", "切换音源失败", it) }
            Toast.makeText(
                ctx,
                if (btMic) "已切到蓝牙耳机麦克风：当前这段已单独保存，后面接着录"
                else "已切到手机麦克风：当前这段已单独保存，后面接着录",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Repo.setBool(ctx, Repo.K_BT_MIC, btMic)
            Toast.makeText(
                ctx,
                if (btMic) "已选择蓝牙耳机麦克风，开始记录时生效" else "已选择手机麦克风，开始记录时生效",
                Toast.LENGTH_SHORT
            ).show()
        }
        renderSourceChips()
        b.root.postDelayed({ refresh() }, 700)
    }

    /** 绘制音源选项的选中态与可用态 */
    private fun renderSourceChips() {
        if (!isAdded || _b == null) return
        val bt = selectedBtMic()
        val connected = headsetConnected()

        b.chipMicPhone.setBackgroundResource(
            if (bt) R.drawable.bg_chip_normal else R.drawable.bg_chip_selected
        )
        b.chipMicPhone.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                requireContext(), if (bt) R.color.text_secondary else R.color.brand_dark
            )
        )
        b.chipMicBt.setBackgroundResource(
            if (bt) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal
        )
        b.chipMicBt.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                requireContext(), if (bt) R.color.brand_dark else R.color.text_secondary
            )
        )
        // 耳机没连时把这一项压暗，明确「现在点它没用」
        b.chipMicBt.alpha = if (connected) 1f else 0.4f
        b.tvSourceState.text = when {
            RecorderService.isRecording -> "录音中 · 当前用" + (if (bt) "耳机麦" else "手机麦")
            bt -> "耳机麦（连上耳机自动开始）"
            connected -> "已连耳机 · 也可自动开始"
            else -> ""
        }
    }

    override fun onResume() {
        super.onResume()
        // 页面被 hide 时不必刷新（等 onHiddenChanged(false) 再刷），省掉一次无用查库
        if (!isHidden) refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 底部导航改成 add/hide/show 复用后，切回本页不会走 onResume，改由这里触发
        if (!hidden) refresh()
    }

    /** 从语音速记返回后刷新（onResume 已覆盖，此处保留语义入口） */

    private fun startServiceWithAction(action: String, trigger: String? = null, source: String? = null) {
        val ctx = context?.applicationContext ?: return
        val intent = Intent(ctx, RecorderService::class.java).setAction(action)
        trigger?.let { intent.putExtra(RecorderService.EXTRA_TRIGGER, it) }
        source?.let { intent.putExtra(RecorderService.EXTRA_SOURCE, it) }
        // 后台限制前台服务时不要崩（Android 12+）
        runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, intent) }
            .onFailure { Log.w("TodayFragment", "启动录音服务失败", it) }
    }

    /**
     * 刷新今日列表
     *
     * 查库 + 聚合全部放后台线程：一次要读最多 50 条含转写/摘要的记录，
     * 主线程做这件事就是「点底部导航后画面停半拍」的来源。
     * 待办条数也从「每条一次查询」改成一次 GROUP BY（见 Repo.todoCounts）。
     */
    fun refresh() {
        if (!isAdded || _b == null) return
        val ctx = context?.applicationContext ?: return
        if (loading) return
        loading = true

        Thread {
            try {
                val today = Repo.sessionsBetweenLight(ctx, TimeUtil.todayStart(), TimeUtil.todayEnd())
                // 今日无记录时回退展示全部（避免空白焦虑，同时便于查看历史）
                val list = if (today.isEmpty()) Repo.recentSessionsLight(ctx, 50) else today
                val counts = Repo.todoCounts(ctx, list.map { it.id })
                val totalMs = list.sumOf { it.durationMs }
                val marked = list.count { it.marked }
                val isTodayList = today.isNotEmpty() || list.isEmpty()
                val recording = RecorderService.isRecording
                val waitingResume = RecorderService.isWaitingResume
                // 默认服务商已预置，但 Key 还得用户自己填：没填就明确告诉他在哪填
                val needKey = Repo.getStr(ctx, Repo.K_LLM_KEY).isBlank() ||
                    Repo.getStr(ctx, Repo.K_ASR_KEY).isBlank()
                view?.post { applyRefresh(list, counts, isTodayList, totalMs, marked, recording, needKey, waitingResume) }
            } catch (t: Throwable) {
                Log.w("TodayFragment", "刷新今日列表失败", t)
            } finally {
                loading = false
            }
        }.start()
    }

    /** 主线程：只做绑定与文案，不碰数据库 */
    private fun applyRefresh(
        list: List<Session>,
        counts: Map<Long, Int>,
        isTodayList: Boolean,
        totalMs: Long,
        marked: Int,
        recording: Boolean,
        needKey: Boolean,
        waitingResume: Boolean,
    ) {
        if (!isAdded || _b == null) return
        adapter.submit(list, counts)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        // 日期分组标题（心力球风格：今天 / 10月22日 周四）
        b.tvDateTitle.text = if (isTodayList) "今天" else "最近记录"
        val cal = java.util.Calendar.getInstance()
        val weekNames = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        val dateStr = String.format(
            Locale.CHINA, "%d月%d日 %s",
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            weekNames[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        )
        b.tvTodayStats.text = String.format(
            Locale.CHINA,
            "%s · %d 段记录 · %s%s",
            dateStr,
            list.size,
            TimeUtil.duration(totalMs),
            if (marked > 0) " · 重点 $marked 处" else ""
        )

        b.btnToggleRecord.text = getString(if (recording) R.string.stop_record else R.string.start_record)
        b.tvConnectTip.text = when {
            recording -> getString(R.string.recording_hint)
            waitingResume -> "通话占用了麦克风，已暂停；通话结束后会自动接着录（前面已录的部分已保存）"
            needKey -> "还没填 API Key：到「设置」填入硅基流动 Key，录音结束会自动转写 + 总结"
            else -> getString(R.string.idle_hint)
        }
        // 音源选项跟随「耳机是否连接 / 是否正在录」实时更新
        renderSourceChips()
    }

    private fun openDetail(s: Session) {
        startActivity(Intent(requireContext(), SessionDetailActivity::class.java).putExtra("id", s.id))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
