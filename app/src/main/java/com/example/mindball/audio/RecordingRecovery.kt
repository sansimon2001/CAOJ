package com.example.mindball.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mindball.R
import com.example.mindball.ai.AnalysisPipeline
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 残留录音恢复
 *
 * 背景：录音文件是先写到 `recordings/` 目录、停止时才入库的。凡是「write 完但没来得及入库」
 * 的情况，文件就会留在目录里没人管，用户视角就是「录了半天，记录里什么都没有」：
 *
 * - 长时间录音中途 App 被系统杀掉 / 服务被回收（onDestroy 没跑）
 * - 录音被打断后进程崩溃
 * - 早期版本把解码失败的文件当成「无人声」删掉了（v1.15 已修，但历史损失要能救回来）
 *
 * 做法：应用启动后在后台扫一遍目录，凡是「不在记录表里、且已经 90 秒没被写过」的
 * REC_*.m4a，就当作未完成录音重新入库并进入转写流水线。文件一个都不删。
 */
object RecordingRecovery {

    private const val TAG = "RecordingRecovery"
    private const val CH_ID_ALERT = "mindball_alert"

    /** 文件最后修改时间距今小于这个值，视为可能正在录，跳过 */
    private const val MIN_AGE_MS = 90_000L

    /** 小于这个大小基本是空文件 */
    private const val MIN_SIZE = 1024L

    /** 启动时在后台跑一次，有恢复就发通知告知 */
    fun recoverAsync(context: Context) {
        val app = context.applicationContext
        Thread {
            val n = runCatching { recover(app) }
                .onFailure { Log.w(TAG, "残留录音扫描失败", it) }
                .getOrDefault(0)
            if (n > 0) notifyRecovered(app, n)
        }.apply { name = "rec-recovery"; isDaemon = true }.start()
    }

    /**
     * 扫描并恢复
     * @return 恢复的条数
     */
    fun recover(context: Context): Int {
        val dir = File(context.getExternalFilesDir(null), "recordings")
        if (!dir.isDirectory) return 0

        val known = Repo.existingAudioPaths(context)
        val now = System.currentTimeMillis()
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("REC_") && f.name.endsWith(".m4a")
        } ?: return 0

        var count = 0
        for (f in files) {
            // 正在录的文件会被持续写入，跳过
            if (now - f.lastModified() < MIN_AGE_MS) continue
            if (f.length() < MIN_SIZE) {
                runCatching { f.delete() }
                continue
            }
            if (known.contains(f.absolutePath)) continue

            val startAt = parseStartAt(f.name) ?: f.lastModified()
            val profile = AudioProbe.analyze(f)

            // 只有「确实解析成功且没人声、且文件很小」才丢弃；
            // 解析失败一律保留 —— 分析失败不等于没声音。
            if (profile.analyzed && !profile.hasSpeech && f.length() < 20_000) {
                runCatching { f.delete() }
                continue
            }

            val duration = if (profile.durationMs > 0) profile.durationMs
                           else (f.lastModified() - startAt).coerceAtLeast(0L)

            runCatching {
                val session = Session(
                    title = "录音 " + SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                        .format(startAt) + "（已恢复）",
                    source = Session.SOURCE_LIVE,
                    audioPath = f.absolutePath,
                    startAt = startAt,
                    endAt = startAt + duration,
                    durationMs = duration,
                    status = Session.STATUS_RECORDED,
                    envType = profile.envType,
                    noiseDb = profile.noiseFloorDb,
                    speechRatio = profile.speechRatio,
                )
                val id = Repo.insertSession(context, session)
                session.id = id
                AnalysisPipeline.enqueue(context, id)
                count++
                Log.i(TAG, "已恢复未完成录音：${f.name}（${duration}ms）")
            }.onFailure { Log.w(TAG, "恢复 ${f.name} 失败", it) }
        }
        return count
    }

    /** 从 REC_yyyyMMdd_HHmmss_N.m4a 里解析开始时间 */
    private fun parseStartAt(name: String): Long? = runCatching {
        val core = name.removePrefix("REC_")
        val stamp = core.substringBefore('_').takeIf { it.length == 15 } ?: return null
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).parse(stamp)?.time
    }.getOrNull()

    private fun notifyRecovered(context: Context, count: Int) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (nm.getNotificationChannel(CH_ID_ALERT) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CH_ID_ALERT, "耳机连接提示", NotificationManager.IMPORTANCE_DEFAULT)
                    )
                }
            }
            val n = NotificationCompat.Builder(context, CH_ID_ALERT)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("已恢复 $count 条未完成的录音")
                .setContentText("上次未能正常收尾的录音已救回，正在转写")
                .setAutoCancel(true)
                .build()
            nm.notify(2001, n)
        }
    }
}
