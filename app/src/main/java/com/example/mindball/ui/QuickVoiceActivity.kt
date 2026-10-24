package com.example.mindball.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.mindball.ai.AsrClient
import com.example.mindball.ai.LlmClient
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.util.Privacy
import com.example.mindball.databinding.ActivityQuickVoiceBinding
import com.example.mindball.util.TimeUtil
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 长按说话（长按 0.5 秒唤醒语音输入，按住说话、松开结束）
 *
 * 松开后自动判断意图：
 * - 问句（含「吗/怎么/为什么/如何/是什么/多少」或带「？」）→ 当作**即时问答**，直接给答案
 * - 否则 → 当作**灵感/待记事项**，落库成一条带「重点」标记的记录
 *
 * 两种结果都提供「存为记录」，保证说过的话不会白说。
 */
class QuickVoiceActivity : AppCompatActivity() {

    private lateinit var b: ActivityQuickVoiceBinding
    private val handler = Handler(Looper.getMainLooper())

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startAt = 0L
    private var ticker: Runnable? = null
    private var recognizing = false

    private var lastText = ""
    private var isQuestion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityQuickVoiceBinding.inflate(layoutInflater)
        setContentView(b.root)
        Privacy.applySecureWindow(this)

        b.btnClose.setOnClickListener { finish() }

        // 按住说话：按下开始、抬起结束
        b.btnHold.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    if (!recognizing) startRecord()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!recognizing) stopRecordAndRecognize()
                    true
                }
                else -> false
            }
        }

        b.btnSave.setOnClickListener { saveAsRecord() }
        b.btnCloseResult.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ==================== 录音 ====================

    private fun startRecord() {
        val dir = File(cacheDir, "voice").apply { mkdirs() }
        file = File(dir, "QV_${System.currentTimeMillis()}.m4a")
        recorder = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(96_000)
            setAudioChannels(1)
            setOutputFile(file!!.absolutePath)
            runCatching {
                prepare()
                start()
            }.onFailure { Log.e("QuickVoice", "录音启动失败", it) }
        }
        startAt = System.currentTimeMillis()

        b.tvState.text = "正在听…松开发送"
        b.tvState.setTextColor(0xFFE60012.toInt())
        b.btnHold.text = "松开结束"

        ticker = object : Runnable {
            override fun run() {
                val sec = (System.currentTimeMillis() - startAt) / 1000
                b.tvTimer.text = String.format(Locale.CHINA, "%02d:%02d", sec / 60, sec % 60)
                handler.postDelayed(this, 200)
            }
        }
        handler.post(ticker!!)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    private fun stopRecordAndRecognize() {
        val f = file
        val duration = System.currentTimeMillis() - startAt
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null

        b.btnHold.text = "按住说话"
        b.tvTimer.text = "00:00"

        if (f == null || !f.exists() || duration < 700) {
            f?.delete()
            b.tvState.text = "按住说话，松开后自动识别"
            b.tvState.setTextColor(0xFF6B6B6B.toInt())
            if (duration < 700 && duration > 0) {
                Toast.makeText(this, "说话时间太短", Toast.LENGTH_SHORT).show()
            }
            return
        }

        recognizing = true
        b.tvState.text = "正在识别…"
        b.tvState.setTextColor(0xFF4A4A4A.toInt())

        lifecycleScope.launch {
            val (text, err) = withContext(Dispatchers.IO) { AsrClient.transcribe(this@QuickVoiceActivity, f) }
            runCatching { f.delete() }
            recognizing = false

            if (text.isNullOrBlank()) {
                b.tvState.text = "识别失败：$err"
                b.tvState.setTextColor(0xFFE60012.toInt())
                return@launch
            }

            lastText = text
            handleRecognized(text)
        }
    }

    // ==================== 意图判断：问答 or 灵感 ====================

    private fun handleRecognized(text: String) {
        isQuestion = looksLikeQuestion(text)
        b.tvRecognized.text = text
        b.tvRecognized.visibility = View.VISIBLE

        if (isQuestion) {
            b.tvState.text = "这是问题，正在思考…"
            b.tvState.setTextColor(0xFF4A4A4A.toInt())
            b.tvIntentTag.text = "即时问答"
            b.tvIntentTag.setBackgroundResource(com.example.mindball.R.drawable.bg_tag_mark)
            lifecycleScope.launch {
                val recent = buildRecentContext()
                val (answer, err) = withContext(Dispatchers.IO) {
                    LlmClient.ask(this@QuickVoiceActivity, text, recent)
                }
                if (answer.isNullOrBlank()) {
                    b.tvResult.text = "回答失败：$err"
                    b.tvState.text = "已识别，但没能回答"
                } else {
                    b.tvResult.text = answer
                    b.tvState.text = "回答完成"
                }
                b.tvState.setTextColor(0xFF6B6B6B.toInt())
                b.llResult.visibility = View.VISIBLE
            }
        } else {
            // 灵感：直接落库为「重点」记录，不必再问
            b.tvState.text = "已记录为灵感"
            b.tvState.setTextColor(0xFF4A4A4A.toInt())
            b.tvIntentTag.text = "灵感笔记"
            b.tvIntentTag.setBackgroundResource(com.example.mindball.R.drawable.bg_tag)
            b.tvResult.text = "已存为灵感记录（重点标记），可在「今日」查看"
            b.llResult.visibility = View.VISIBLE
            saveAsRecord(silent = true)
        }
    }

    /** 启发式判断是不是提问（够用即可，无需模型） */
    private fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("？") || t.endsWith("?")) return true
        val keywords = listOf("吗", "怎么", "为什么", "如何", "是什么", "多少", "哪个", "哪家", "能不能", "要不要", "是不是", "有没有")
        return keywords.any { t.contains(it) } && t.length <= 60
    }

    /** 最近的记录摘要，给问答做上下文 */
    private fun buildRecentContext(): String {
        val recent = Repo.recentSessionsLight(this, 5)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { s ->
            "- [${TimeUtil.date(s.startAt)}] ${s.title}：${s.summary.replace("\n", " ").take(200)}"
        }
    }

    // ==================== 存为记录 ====================

    private fun saveAsRecord(silent: Boolean = false) {
        if (lastText.isBlank()) {
            if (!silent) Toast.makeText(this, "还没有可保存的内容", Toast.LENGTH_SHORT).show()
            return
        }
        val now = System.currentTimeMillis()
        val title = if (isQuestion) "问答：${lastText.take(14)}" else lastText.take(16)
        val session = Session(
            title = title.ifBlank { "语音速记" },
            transcript = lastText,
            summary = if (isQuestion) b.tvResult.text.toString().ifBlank { lastText } else lastText,
            project = "",
            source = Session.SOURCE_VOICE,
            startAt = now,
            endAt = now,
            durationMs = 0,
            marked = true,
            status = Session.STATUS_ANALYZED,
        )
        val id = Repo.insertSession(this, session)
        if (!silent) {
            Toast.makeText(this, if (id > 0) "已存为记录" else "保存失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { recorder?.release() }
        recorder = null
        ticker?.let { handler.removeCallbacks(it) }
    }
}
