package com.example.mindball.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.mindball.R
import com.example.mindball.ai.AsrClient
import com.example.mindball.audio.Voiceprint
import com.example.mindball.util.Privacy
import com.example.mindball.databinding.ActivityEnrollVoiceBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 本人声纹录入
 *
 * 流程：读提示句 → 录音约 15 秒（44.1kHz 单声道，与记录时的录音参数一致，
 * 避免采样率差异影响特征可比性）→ 离线抽出 MFCC + 基频统计 → 存成声纹模板。
 *
 * 为什么要求「朗读提示句」而不是随便说话：
 * 提示句里音素覆盖较全（含不同元音与语调），得到的均值特征更稳定，
 * 也能保证一次录到足够多的有声帧。
 */
class EnrollVoiceActivity : AppCompatActivity() {

    private lateinit var b: ActivityEnrollVoiceBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var startElapsed = 0L
    private var analyzing = false
    private var promptIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - startElapsed
            val ratio = (elapsed.toFloat() / MAX_MS).coerceIn(0f, 1f)
            b.progress.progress = (ratio * 100).toInt()
            b.tvTimer.text = String.format(Locale.CHINA, "%.1f / %.1f 秒", elapsed / 1000f, MAX_MS / 1000f)
            if (elapsed >= MAX_MS) {
                stopRecording(analyze = true)
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityEnrollVoiceBinding.inflate(layoutInflater)
        setContentView(b.root)
        Privacy.applySecureWindow(this)

        b.btnBack.setOnClickListener { finish() }
        b.tvPrompt.text = PROMPTS[promptIndex]

        b.btnRecord.setOnClickListener {
            if (analyzing) return@setOnClickListener
            if (recorder != null) {
                stopRecording(analyze = true)
            } else {
                startRecording()
            }
        }

        b.btnDone.setOnClickListener { finish() }

        showExisting()
    }

    /** 已有声纹时先展示状态，允许直接重录 */
    private fun showExisting() {
        val t = Voiceprint.load(this) ?: return
        b.tvState.text = "已录入"
        b.tvResult.visibility = android.view.View.VISIBLE
        b.tvResult.text = "当前声纹：%.1f 秒样本 · 收音 %s\n重新录入会覆盖原有声纹".format(
            t.seconds, Voiceprint.qualityLabel(t.voicedRatio)
        )
    }

    // ==================== 录音 ====================

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }

        val f = File(cacheDir, "voice_enroll.m4a")
        runCatching { if (f.exists()) f.delete() }
        recordFile = f

        runCatching {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 与记录时的参数保持一致，保证特征可比
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(128_000)
                setAudioChannels(1)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
        }.onFailure {
            recorder = null
            toast("无法启动录音：${it.message}")
            return
        }

        startElapsed = SystemClock.elapsedRealtime()
        b.btnRecord.text = getString(R.string.enroll_stop)
        b.tvState.text = "录音中"
        b.tvResult.visibility = android.view.View.GONE
        b.btnDone.isEnabled = false
        b.progress.progress = 0
        b.tvPrompt.text = PROMPTS[promptIndex]
        handler.post(ticker)
    }

    private fun stopRecording(analyze: Boolean) {
        handler.removeCallbacks(ticker)
        val rec = recorder ?: return
        recorder = null

        runCatching { rec.stop() }
        runCatching { rec.release() }

        b.btnRecord.text = getString(R.string.enroll_start)
        b.tvState.text = "处理中"
        b.btnRecord.isEnabled = false

        val f = recordFile
        val elapsed = SystemClock.elapsedRealtime() - startElapsed
        if (!analyze || f == null || !f.exists() || elapsed < 1500) {
            b.btnRecord.isEnabled = true
            b.tvState.text = "待录入"
            b.tvTimer.text = String.format(Locale.CHINA, "0.0 / %.1f 秒", MAX_MS / 1000f)
            b.progress.progress = 0
            if (analyze) toast("只录了一两秒，请把提示句读完再点「停止并分析」")
            return
        }

        analyzing = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { Voiceprint.build(f) }
            analyzing = false
            b.btnRecord.isEnabled = true
            promptIndex = (promptIndex + 1) % PROMPTS.size
            b.tvPrompt.text = PROMPTS[promptIndex]

            val tpl = res.template
            if (tpl == null) {
                b.tvState.text = "未保存"
                b.tvResult.visibility = android.view.View.VISIBLE
                b.tvResult.text = res.error
                b.btnDone.isEnabled = false
                return@launch
            }

            Voiceprint.save(this@EnrollVoiceActivity, tpl)
            runCatching { f.delete() }

            b.tvState.text = "已保存"
            b.tvResult.visibility = android.view.View.VISIBLE
            b.tvResult.text = buildString {
                append("声纹已保存 ✅\n")
                // 不显示百分比：录入门槛只有「说完一遍」，摆一个数字出来只会让人反复重录
                append("样本 %.1f 秒 · 收音 %s\n".format(tpl.seconds, Voiceprint.qualityLabel(tpl.voicedRatio)))
                if (res.note.isNotBlank()) append(res.note).append("\n")
                if (AsrClient.diarizePossible(this@EnrollVoiceActivity)) {
                    append("之后录音会自动判断哪一段是「我」，不必再手动选「我是说话人几」。")
                } else {
                    append("注意：当前转写服务只返回纯文本、不提供说话人分段，")
                    append("声纹暂时无法自动认出「我」。换成支持 diarization 的转写服务后即刻生效，声纹本身不用重录。")
                }
            }
            b.btnDone.isEnabled = true
            toast("声纹已保存")
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                toast("未授予麦克风权限，无法录入声纹")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台时把正在进行的录音存下来，避免录到一半丢掉
        if (recorder != null) stopRecording(analyze = true)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_AUDIO = 2301
        private const val MAX_MS = 15_000L

        /** 提示句：覆盖较多音素，读起来自然 */
        private val PROMPTS = arrayOf(
            "今天的天气还算不错，我们先把上周提到的几个事情理一理，看看哪一件最急。",
            "我觉得这个方案的方向是对的，只是节奏上要再稳一点，先把眼前这一步做扎实。",
            "早上开完会，我记下了三件事：确认预算、安排人手、还有和客户再对一次时间。",
            "说话的时候不用太快，正常语速就可以，重点是把每句话都说完整。",
        )
    }
}
