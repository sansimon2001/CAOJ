package com.example.mindball

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.mindball.ai.ProjectMind
import com.example.mindball.audio.RecordingRecovery
import com.example.mindball.data.Repo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        // 首次运行把默认服务商（硅基流动）的地址与模型填好，用户只需填 API Key
        Repo.seedDefaultProviders(this)
        installCrashLogger()
        // 项目档案预热：首次运行会把历史项目名整理成档案，并归并「XX / XX项目 / XX 」
        // 这类同义写法（会改写旧数据的 project 字段），放后台线程，别拖慢启动
        Thread {
            runCatching { ProjectMind.load(this) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        // 残留录音恢复：上次若因来电 / 系统回收进程没来得及入库，这里把文件捞回来
        // 延迟 3 秒，避开「服务刚重启、文件还在写」的窗口
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { RecordingRecovery.recoverAsync(this) }
        }, 3_000)
    }

    /**
     * 记录未捕获异常的现场到 filesDir/crash_last.txt
     *
     * 不吞异常（仍然交给系统默认处理，该崩还是崩），只是把堆栈留一份——
     * 否则用户反馈「用着用着自动退出」，我们连一行日志都拿不到，只能靠猜。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val nl = System.lineSeparator()
                val sb = StringBuilder()
                sb.append("时间：")
                    .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
                    .append(nl)
                sb.append("线程：").append(thread.name).append(nl)
                sb.append("异常：").append(error.javaClass.name).append(": ")
                    .append(error.message ?: "").append(nl).append(nl)
                sb.append(Log.getStackTraceString(error))
                File(filesDir, "crash_last.txt").writeText(sb.toString())
            }.onFailure { Log.e("App", "写崩溃日志失败", it) }
            previous?.uncaughtException(thread, error)
        }
    }
}
