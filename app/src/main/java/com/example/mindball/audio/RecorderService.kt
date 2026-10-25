package com.example.mindball.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mindball.R
import com.example.mindball.ai.AnalysisPipeline
import com.example.mindball.data.Repo
import com.example.mindball.data.Session
import com.example.mindball.data.encodeMarks
import com.example.mindball.ui.MainActivity
import java.io.File
import java.util.Locale

/**
 * 前台录音服务
 *
 * 设计对应关系：
 * - 持续记录 = 前台常驻通知（相当于隐私提示灯，状态可见）
 * - 短按标记重点 = 通知栏「标记重点」按钮（给当前段落打标）
 * - 断开耳机 / 手动停止 = 结束本次会话，落库并进入转写分析流水线
 *
 * ===================== v1.15：修「录了很久却什么都没录进去」 =====================
 *
 * 用户反馈的现象是：戴着耳机开了很久的持续记录，事后没有任何对话与声音。
 * 排查出四条各自都能独立造成这个结果的原因，本版逐条处理：
 *
 * ① **来电 / 微信语音通话会夺走麦克风，而应用毫无察觉**
 *    通话属于系统最高优先级的录音场景。通话建立后，本应用的录音流会被系统**静默**
 *    （流还在、写进去的全是静音），也可能直接报错。原来既没监听通话状态，也没检查
 *    录音是否还有信号 —— `isRecording` 一直为 true，通知栏计时器一直走，用户以为在录。
 *    → 现在每 2 秒巡检一次：`AudioManager.mode` 进入通话态、或 `maxAmplitude()` 连续
 *      为 0，就判定「被打断」。
 *
 * ② **被打断时整段报废**
 *    原来只有一条录音文件，中途被打断后这个文件既没有正常收尾、后半段又全是静音，
 *    等于整段作废（MP4 甚至缺 moov 而完全无法解码）。
 *    → 现在**打断即分段**：立刻收尾并落库已录到的部分（不删），等通话结束自动接着
 *      录新的一段。一次长会话说到底可能被切成 2~3 条记录，但一条都不会丢。
 *
 * ③ **解码失败被当成「没人说话」→ 删文件**
 *    文件损坏时 AudioProbe 返回 unknown，其 hasSpeech=false，上层据此删除文件并提示
 *    「未检测到有效人声」。这是**数据丢失级**缺陷。
 *    → 现在只有「分析成功且确实无人声」才删；分析失败一律保留并入库。
 *
 * ④ **长录音只看开头 3 分钟就判死**
 *    → 已改为头/中/尾多点采样（见 AudioProbe）。
 *
 * 另外补上 `MediaRecorder.OnErrorListener`：录音器出错时原来完全不通知应用。
 *
 * ===================== v1.16：手动选择音源 =====================
 *
 * 原来音源只能在设置页里改一个开关，而且只在 App 里能看到，实际用起来很别扭：
 * 连上耳机自动开始录之后，想改用手机麦克风（耳机麦音质低、或耳机挂在包上收不到音）
 * 只能去设置里翻。
 *
 * 现在把「音源」变成一等公民：
 * - 启动时可用 `EXTRA_SOURCE` 显式指定 phone / bt，不改用户长期偏好也不影响本次
 * - 录音过程中可发 `ACTION_SWITCH_SOURCE` 换音源：当前段先收尾保存，紧接着开新段用新音源
 *   （复用定时切段逻辑，一段都不丢）
 * - `startedByBluetooth` 区分「耳机自动触发」与「手动开始」：手动用手机麦录的时候，
 *   耳机断开不该把记录一起停掉
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "RecorderService"
        private const val CH_ID = "mindball_recording"
        private const val CH_ID_ALERT = "mindball_alert"
        private const val NOTIFY_ID = 1001
        private const val ALERT_ID = 1002

        const val ACTION_START = "com.example.mindball.START_RECORD"
        const val ACTION_STOP = "com.example.mindball.STOP_RECORD"
        const val ACTION_MARK = "com.example.mindball.MARK_POINT"
        const val EXTRA_TRIGGER = "trigger"

        /** 录音中切换音源：当前段收尾保存，立刻用新音源开新段 */
        const val ACTION_SWITCH_SOURCE = "com.example.mindball.SWITCH_SOURCE"

        /** 显式指定音源（可选）。不传 = 用设置里的长期偏好 */
        const val EXTRA_SOURCE = "source"
        const val SOURCE_PHONE = "phone"
        const val SOURCE_BT = "bt"

        /** 巡检间隔：2 秒看一次「还在不在录」 */
        private const val GUARD_INTERVAL_MS = 2_000L

        /** 通话中最多等多久再自动续录（超过则彻底结束，避免服务挂一整天） */
        private const val RESUME_MAX_WAIT_MS = 30 * 60 * 1000L

        /** 连续多少次「振幅为 0」判定为被静音（5 × 2s = 10s） */
        private const val SILENT_STREAK_LIMIT = 5

        /** 连续多少次「处于通话模式」判定为被打断（2 × 2s = 4s） */
        private const val CALL_STREAK_LIMIT = 2

        /**
         * 单段最长时长：超长录音定时切段
         *
         * 两个理由：
         * 1) 单文件录越久，一次异常报废的内容越多（30 分钟封顶，最坏丢 30 分钟）
         * 2) 转写接口普遍有单文件时长/体积上限，1 小时的 m4a 直接传过去容易失败
         */
        private const val SEGMENT_MAX_MS = 30 * 60 * 1000L

        @Volatile
        var isRecording: Boolean = false
            private set

        @Volatile
        var startAt: Long = 0
            private set

        /**
         * 是否处于「被打断、等待麦克风恢复后自动续录」状态
         *
         * 此时 [isRecording] 是 false（确实没在录），但用户的记录并没有结束，
         * 界面必须如实显示「通话中已暂停」，否则用户会以为记录掉了而重新点开始。
         */
        @Volatile
        var isWaitingResume: Boolean = false
            private set

        /** 当前这一段用的是什么音源（true=耳机麦），界面据此显示「手机麦克风 / 蓝牙耳机麦克风」 */
        @Volatile
        var currentIsBtMic: Boolean = false
            private set

        /**
         * 本次记录是不是「蓝牙耳机连上自动触发」开始的
         *
         * 手动开始的记录不该因为耳机断开而结束（尤其是本来就用手机麦克风录的时候）。
         */
        @Volatile
        var startedByBluetooth: Boolean = false
            private set

        /** 打开主界面并提示（耳机已连接但未自动开始） */
        fun notifyHeadsetReady(context: Context, deviceName: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(context, CH_ID_ALERT, "耳机连接提示", NotificationManager.IMPORTANCE_DEFAULT)
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(context, CH_ID_ALERT)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("已连接蓝牙耳机")
                .setContentText("$deviceName 已连接，点击开始记录")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(ALERT_ID, n)
        }

        /**
         * 当前是否处于通话中（来电 / 通话 / 微信语音等 VoIP）
         *
         * 用 `AudioManager.mode` 判断，**不需要 READ_PHONE_STATE 权限**，
         * 也不需要区分是系统电话还是微信 —— 只要 mode 被切到通话态，麦克风就不归我们了。
         *
         * 供 MainActivity 使用：耳机在通话建立时断开一下是正常现象，
         * 不能因此把正在进行的记录停掉。
         */
        fun isInCall(context: Context): Boolean = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION
        }.getOrDefault(false)

        /** "bt"→true（耳机麦）/ "phone"→false（手机麦）/ 其它→null（跟随设置里的长期偏好） */
        fun parseBtMic(source: String?): Boolean? = when (source) {
            SOURCE_BT -> true
            SOURCE_PHONE -> false
            else -> null
        }

        /** 音源 → 传输用字符串 */
        fun sourceOf(btMic: Boolean): String = if (btMic) SOURCE_BT else SOURCE_PHONE

        private fun ensureChannel(context: Context, id: String, name: String, importance: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(id) == null) {
                    nm.createNotificationChannel(NotificationChannel(id, name, importance))
                }
            }
        }
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var markedPoints = mutableListOf<Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var trigger = "bluetooth"

    /** 本次记录的显式音源；null = 跟随设置（用户没在开始前指定） */
    private var btMicOverride: Boolean? = null

    // ===== v1.15 新增：打断检测与自动续录 =====

    /** 是否希望「一直录下去」（用户手动停止时置 false，打断时保持 true） */
    private var wantContinuous = false

    /** 巡检用 */
    private var silentStreak = 0
    private var callStreak = 0
    private var everHadSignal = false
    private var resumeWaitingSince = 0L

    /**
     * 巡检调度代次
     *
     * 切段时会重新 `startGuard()`，如果 run() 末尾再无条件 post 一次，
     * 两个循环会同时跑起来并且每次各自再排一个 —— 指数增长。用代次挡住这一次。
     */
    private var guardGen = 0

    /** 第几段（一次长会话被通话打断后会变成 2、3…） */
    private var segmentIndex = 0

    private val guardRunnable = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val gen = guardGen
            try {
                checkHealth()
            } catch (t: Throwable) {
                Log.w(TAG, "巡检异常", t)
            }
            // 若巡检中发生了「切段 / 续录」，startGuard() 已经重新排过，这里不要再排
            if (isRecording && gen == guardGen) handler.postDelayed(this, GUARD_INTERVAL_MS)
        }
    }

    private val resumeRunnable = object : Runnable {
        override fun run() {
            if (!wantContinuous) return
            if (System.currentTimeMillis() - resumeWaitingSince > RESUME_MAX_WAIT_MS) {
                Log.w(TAG, "等待续录超时，结束本次记录")
                wantContinuous = false
                notifyInterrupted("通话时间较长，本次记录已结束")
                finishService()
                return
            }
            if (isInCall(this@RecorderService)) {
                handler.postDelayed(this, 3_000)
                return
            }
            Log.i(TAG, "通话结束，自动续录第 ${segmentIndex + 1} 段")
            startSegment()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兜底：任何异常都不要让服务崩掉（服务崩溃会直接把 App 带走，表现为「自动退出」）
        runCatching {
            handleCommand(intent)
        }.onFailure {
            Log.e(TAG, "onStartCommand 处理异常", it)
            runCatching { finishService() }
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(intent: Intent?) {
        when (intent?.action) {
            ACTION_START -> startRecording(
                trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "bluetooth",
                btMic = parseBtMic(intent.getStringExtra(EXTRA_SOURCE)),
            )
            ACTION_STOP -> {
                // 用户主动结束：不要再自动续录
                wantContinuous = false
                handler.removeCallbacks(resumeRunnable)
                stopRecordingAndSave(intent.getStringExtra(EXTRA_TRIGGER) ?: "user")
            }
            ACTION_MARK -> markPoint()
            ACTION_SWITCH_SOURCE -> switchSource(intent.getStringExtra(EXTRA_SOURCE))
            // intent 为空 = 系统重启了服务（START_STICKY）。没有在录音就别占着前台通知
            null -> if (!isRecording) {
                Log.i(TAG, "服务被系统重启但无录音任务，直接退出")
                stopSelf()
            }
        }
    }

    /**
     * 切换音源
     *
     * 没在录：只记下选择，下次开始生效。
     * 正在录：复用定时切段 —— 当前段先收尾保存（一段都不丢），紧接着用新音源开新段。
     * 这样「耳机挂着但我想用手机麦」「手机麦太闷换耳机麦」都能在录音中途直接切。
     */
    private fun switchSource(source: String?) {
        val bt = parseBtMic(source) ?: return
        btMicOverride = bt
        // 记成长期偏好：下次自动触发/手动开始都按这个来，不用每次重选
        Repo.setBool(this, Repo.K_BT_MIC, bt)
        // 用户手动指定过音源 = 他已经接管了这次记录，
        // 那么耳机断开就不该再把记录一起停掉（后面按「手动记录」处理）
        startedByBluetooth = false
        Log.i(TAG, "音源切换为 ${if (bt) "耳机麦" else "手机麦"}，正在录音=${isRecording}")
        if (!isRecording) return
        rotateSegment()
    }

    // ==================== 开始录音 ====================

    /**
     * @param trigger 蓝牙自动触发 = "bluetooth"，手点按钮 = "manual"
     * @param btMic 显式音源；null 表示跟随设置里的长期偏好
     */
    private fun startRecording(trigger: String, btMic: Boolean? = null) {
        if (isRecording) return
        // 先清掉可能还在排队的「等待续录」与旧巡检，避免两个录音器同时跑
        handler.removeCallbacks(resumeRunnable)
        stopGuard()
        this.trigger = trigger
        this.segmentIndex = 1
        this.wantContinuous = true
        this.btMicOverride = btMic
        startedByBluetooth = trigger == "bluetooth"
        // 显式指定过音源就落成偏好，界面与设置页回显才一致
        btMic?.let { Repo.setBool(this, Repo.K_BT_MIC, it) }
        val notification = buildNotification(
            "正在记录",
            "${sourceLabel(btMic ?: Repo.getBool(this, Repo.K_BT_MIC, false))} · 可点「标记重点」打标",
            chrono = true
        )
        val fgOk = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        }.onFailure {
            // 权限被收回 / 系统限制前台服务时不要直接崩，安静退出
            Log.e(TAG, "startForeground 失败，放弃本次录音", it)
        }.isSuccess
        if (!fgOk) {
            isRecording = false
            wantContinuous = false
            runCatching { stopSelf() }
            return
        }

        // 注：不再起每秒计时器刷新通知（省电），时长交给通知里的系统计时器显示
        startSegment()
    }

    /**
     * 开始一段录音（第一次进入，或被打断后续录）
     *
     * 单独抽出来是因为「被打断 → 等通话结束 → 接着录」需要复用同一套初始化逻辑。
     */
    private fun startSegment() {
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
            .format(System.currentTimeMillis())
        val file = File(dir, "REC_${stamp}_${segmentIndex}.m4a")
        currentFile = file
        markedPoints = mutableListOf()

        isRecording = true
        isWaitingResume = false
        startAt = System.currentTimeMillis()
        silentStreak = 0
        callStreak = 0
        everHadSignal = false
        resumeWaitingSince = 0L

        // 音源选择（优先级：本次显式指定 > 设置里的长期偏好）：
        //   手机麦克风 = 44.1kHz，音质好，且完全不碰蓝牙音频通路，连接最稳
        //   耳机麦克风 = 需先切 SCO 通路，音质较低但离嘴更近
        val wantBtMic = btMicOverride ?: Repo.getBool(this, Repo.K_BT_MIC, false)
        val token = startAt
        if (wantBtMic) {
            // SCO 建立要几百毫秒，放后台线程等，避免阻塞主线程（否则可能 ANR 或被系统判定无响应）
            Thread {
                val scoOk = BluetoothSco.startAndWait(this, 900)
                handler.post {
                    val source = if (scoOk) MediaRecorder.AudioSource.VOICE_RECOGNITION
                                 else MediaRecorder.AudioSource.MIC
                    beginCapture(file, source, token)
                }
            }.apply { name = "sco-start" }.start()
        } else {
            // 顺手清掉上一次可能残留的 SCO：残留会把耳机卡在通话通路（听歌变闷/断续/断连）
            BluetoothSco.release(this)
            beginCapture(file, MediaRecorder.AudioSource.MIC, token)
        }

        // 通知：段号 + 音源 + 计时
        if (segmentIndex > 1) {
            updateNotification(
                "正在记录（第 ${segmentIndex} 段）",
                "${sourceLabel(wantBtMic)} · 前一段已单独保存",
                chrono = true
            )
        }
        startGuard()
    }

    /** 音源显示名 */
    private fun sourceLabel(btMic: Boolean): String =
        if (btMic) "蓝牙耳机麦克风" else "手机麦克风"

    /**
     * 真正创建并启动录音器
     *
     * 单独抽出来是因为「等 SCO 建立」要走后台线程，回来时本次录音可能已经被停止
     * （比如刚连上耳机又拔了），用 token 校验一下，避免留下一个没人管的录音器。
     *
     * @param source AudioSource.MIC（手机麦）或 VOICE_RECOGNITION（耳机麦 / SCO 通路）
     */
    private fun beginCapture(file: File, source: Int, token: Long) {
        if (!isRecording || startAt != token) {
            Log.i(TAG, "录音已被停止，跳过录音器创建")
            return
        }

        val r = newRecorder()
        val ok = runCatching {
            r.setAudioSource(source)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // 采样率维持 44.1 kHz：本地声纹模板与音频环境分析都按这个采样率提取，
            // 改 16 kHz 会让存量模板失配（用户得重录声纹），收益与风险不成正比。
            r.setAudioSamplingRate(44100)
            // 码率从 64 kbps 提到 96 kbps：64 kbps 单声道 44.1 kHz 的压缩比接近 11:1，
            // 是识别错字偏多的一个来源（辅音、气声先糊）。提到 96 kbps 后 30 分钟约 21 MB，
            // 仍远低于各家 25~50 MB 的上传上限；与「长按语音」那条链路的参数保持一致。
            r.setAudioEncodingBitRate(96_000)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            // 录音器出错（麦克风被抢、编码器异常）时原来完全静默 —— 现在立刻分段保存
            r.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaRecorder 报错 what=$what extra=$extra")
                handler.post { onInterrupted("录音器异常（麦克风可能被通话占用）") }
            }
            r.prepare()
            r.start()
        }.isSuccess

        if (ok) {
            recorder = r
            // 记录真实生效的音源（SCO 建立失败时会回退手机麦，界面不能显示成耳机麦）
            currentIsBtMic = source != MediaRecorder.AudioSource.MIC
            Log.i(TAG, "录音已开始，音源=${sourceLabel(currentIsBtMic)}($source)，第 $segmentIndex 段，文件=${file.name}")
            return
        }

        // 启动失败
        runCatching { r.release() }
        if (source != MediaRecorder.AudioSource.MIC) {
            // 耳机麦失败 → 释放 SCO 后回退手机麦，避免整段录音丢失
            Log.w(TAG, "耳机麦克风启动失败，回退手机麦克风")
            BluetoothSco.release(this)
            beginCapture(file, MediaRecorder.AudioSource.MIC, token)
        } else {
            // 手机麦克风都起不来：多半是权限被收回，或被通话独占
            Log.e(TAG, "录音启动失败（手机麦克风）")
            if (wantContinuous && !isInCall(this)) {
                // 不是通话导致的 → 权限问题，收尾
                isRecording = false
                wantContinuous = false
                notifyInterrupted("麦克风不可用，本次记录已结束（请检查录音权限）")
                finishService()
            } else {
                // 通话中起不来：等通话结束再试
                isRecording = false
                onInterrupted("麦克风被通话占用")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    // ==================== 健康巡检（v1.15 核心） ====================

    private fun startGuard() {
        guardGen++
        handler.removeCallbacks(guardRunnable)
        handler.postDelayed(guardRunnable, GUARD_INTERVAL_MS)
    }

    private fun stopGuard() {
        handler.removeCallbacks(guardRunnable)
    }

    /**
     * 判断「还在不在真的录」
     *
     * 两个互相独立的信号，任一命中即认为被打断：
     * - **通话态**：`AudioManager.mode` 变成 IN_CALL / IN_COMMUNICATION，麦克风归通话所有
     * - **静默**：`maxAmplitude()` 连续 10 秒严格为 0。真实环境哪怕很安静，麦克风本底
     *   噪声也不会是 0，所以连续 0 基本只能是「系统把我们的录音流静音了」
     */
    private fun checkHealth() {
        // 定时切段：录满 30 分钟就收一段、立刻接着录下一段（用户无感）
        if (System.currentTimeMillis() - startAt >= SEGMENT_MAX_MS) {
            Log.i(TAG, "单段已录满 ${SEGMENT_MAX_MS / 60000} 分钟，自动切段")
            rotateSegment()
            return
        }

        val amp = runCatching { recorder?.maxAmplitude ?: -1 }.getOrDefault(-1)
        if (amp > 0) {
            everHadSignal = true
            silentStreak = 0
        } else if (amp == 0) {
            silentStreak++
        }

        if (isInCall(this)) {
            callStreak++
        } else {
            callStreak = 0
        }

        if (callStreak >= CALL_STREAK_LIMIT) {
            Log.w(TAG, "检测到通话中，麦克风已不归本应用，立即分段保存")
            onInterrupted("通话打断（来电 / 微信语音）")
            return
        }

        // 只在「曾经有信号」之后才用静默判定，避免开头 10 秒静场就误切
        if (everHadSignal && silentStreak >= SILENT_STREAK_LIMIT) {
            Log.w(TAG, "录音连续 ${silentStreak * GUARD_INTERVAL_MS / 1000} 秒无任何信号，判定被静音")
            onInterrupted("录音被系统静音（麦克风被其他应用占用）")
        }
    }

    /**
     * 被打断：立刻把**已经录到的部分**收尾落库，然后等条件恢复再续录
     *
     * 关键点：宁可切成两条记录，也不能因为后半段没录上把前半段一起丢掉。
     */
    private fun onInterrupted(reason: String) {
        if (!isRecording) return
        Log.w(TAG, "记录被打断：$reason")
        stopGuard()

        // 先停并收尾（让 MP4 写入 moov，否则文件不可解码）
        val seg = cutSegment()
        val duration = seg.durationMs
        if (seg.usable) {
            // 后台分析 + 入库，不阻塞续录
            finalizeSegment(seg.file!!, seg.startAt, seg.endAt, seg.marks, seg.index, reason)
        } else {
            // 这段太短/没文件：直接丢掉，但它说明我们确实被打断了
            runCatching { seg.file?.delete() }
        }

        if (!wantContinuous) {
            finishService()
            return
        }

        // 进入等待恢复
        isWaitingResume = true
        resumeWaitingSince = System.currentTimeMillis()
        segmentIndex = seg.index + 1
        updateNotification(
            "通话中，已暂停记录",
            "前一段已保存（${formatDuration(duration)}），通话结束后自动继续",
            chrono = false
        )
        notifyInterrupted("$reason。已保存中断前的 ${
            formatDuration(duration)
        }，麦克风恢复后会自动接着录")
        handler.removeCallbacks(resumeRunnable)
        handler.postDelayed(resumeRunnable, 3_000)
    }

    /**
     * 定时切段：收尾当前段并立刻开始下一段（不等待，也不打扰用户）
     *
     * 与 [onInterrupted] 的区别：这里麦克风还是好的，所以切完马上续录，
     * 不需要进入「等通话结束」的等待态。
     */
    private fun rotateSegment() {
        if (!isRecording) return
        val seg = cutSegment()
        if (seg.usable) {
            finalizeSegment(seg.file!!, seg.startAt, seg.endAt, seg.marks, seg.index, "rotate")
        } else {
            runCatching { seg.file?.delete() }
        }
        segmentIndex = seg.index + 1
        startSegment()
    }

    /**
     * 当前一段的快照
     *
     * 字段必须在停录音器**之前**取好：停完 currentFile / startAt 可能已被清空。
     */
    private class Segment(
        val file: File?,
        val startAt: Long,
        val endAt: Long,
        val marks: List<Long>,
        val index: Int,
    ) {
        val durationMs: Long get() = endAt - startAt

        /**
         * 这段是否值得入库
         *
         * 集中在这里判定：原来「停止」「被打断」「定时切段」三处各写了一遍阈值，
         * 出现过切段用 >1024 而停止用 <1024 的不一致写法，改口径时极易漏掉一处。
         */
        val usable: Boolean
            get() = file != null && file.exists() && file.length() > 1024 && durationMs >= 2000
    }

    /** 收尾当前段：快照 → 停录音器 → 停表。入库还是丢弃由调用方决定 */
    private fun cutSegment(): Segment {
        val seg = Segment(
            file = currentFile,
            startAt = startAt,
            endAt = System.currentTimeMillis(),
            marks = markedPoints.sorted(),
            index = segmentIndex,
        )
        stopRecorderQuietly()
        isRecording = false
        return seg
    }

    private fun stopRecorderQuietly() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // 录音器被打断时 stop 抛异常是常态，文件仍会保留（只是可能缺 moov）
            Log.w(TAG, "停止录音异常（可能被系统抢占）", e)
        } finally {
            runCatching { recorder?.release() }
            recorder = null
            BluetoothSco.release(this)
        }
    }

    // ==================== 停止与落库 ====================

    private fun markPoint() {
        if (!isRecording) return
        val offset = System.currentTimeMillis() - startAt
        markedPoints.add(offset)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val mm = offset / 1000 / 60
        val ss = offset / 1000 % 60
        nm.notify(
            NOTIFY_ID,
            buildNotification(
                "已标记重点（第 ${markedPoints.size} 处）",
                String.format(Locale.CHINA, "时间点 %02d:%02d · 继续记录中", mm, ss),
                chrono = true
            )
        )
    }

    /**
     * 停止并落库，交给流水线转写 + 分析
     *
     * ⚠️ 这里最容易踩的坑：环境分析要解码音频（即使只取样本也要几百毫秒到数秒），
     * 而 onStartCommand 跑在主线程 —— 直接在主线程分析会导致**界面卡死（ANR）**，
     * 录音越长越明显。所以：停止录音（快）留在主线程，分析与入库全部丢到后台线程，
     * 期间保留前台通知（「正在整理…」）避免进程被系统回收。
     */
    private fun stopRecordingAndSave(reason: String) {
        stopGuard()
        handler.removeCallbacks(resumeRunnable)

        if (!isRecording) {
            finishService()
            return
        }
        val seg = cutSegment()
        if (!seg.usable) {
            Log.i(TAG, "录音过短（${seg.durationMs}ms）或文件无效，不入库")
            runCatching { seg.file?.delete() }
            finishService()
            return
        }

        // 通知改成「正在整理」，让用户知道还在处理，同时保住前台优先级
        updateNotification("正在整理录音…", "马上就好", chrono = false)
        finalizeSegment(
            seg.file!!, seg.startAt, seg.endAt, seg.marks, seg.index, reason, serviceStopsAfter = true
        )
    }

    /**
     * 后台收尾：环境分析 → 入库 → 交给 AI 流水线
     *
     * @param serviceStopsAfter 处理完是否结束前台服务（分段续录时为 false）
     */
    private fun finalizeSegment(
        file: File,
        segStart: Long,
        segEnd: Long,
        marks: List<Long>,
        segIndex: Int,
        reason: String,
        serviceStopsAfter: Boolean = false,
    ) {
        val duration = segEnd - segStart
        Thread {
            try {
                // ===== 环境分析（解码音频，耗时，必须在后台）=====
                val profile = AudioProbe.analyze(file)
                // ⚠️ 只有「分析成功且确实无人声」才丢弃；
                //    分析失败 = 不知道有没有声音，必须保留（原来这里会误删整段录音）
                if (profile.analyzed && !profile.hasSpeech) {
                    runCatching { file.delete() }
                    handler.post {
                        notifySkipped()
                        if (serviceStopsAfter) finishService()
                    }
                    return@Thread
                }

                val session = Session(
                    title = buildTitle(segStart, segIndex, reason),
                    source = if (trigger == "call_import") Session.SOURCE_CALL else Session.SOURCE_LIVE,
                    audioPath = file.absolutePath,
                    startAt = segStart,
                    endAt = segEnd,
                    durationMs = duration,
                    marked = marks.isNotEmpty(),
                    marks = encodeMarks(marks),
                    envType = profile.envType,
                    noiseDb = profile.noiseFloorDb,
                    speechRatio = profile.speechRatio,
                    status = Session.STATUS_RECORDED,
                )
                val id = Repo.insertSession(this, session)
                session.id = id

                // 进入 AI 流水线（转写 → 摘要 → 待办 → 洞察）
                AnalysisPipeline.enqueue(this, id)
                handler.post { if (serviceStopsAfter) finishService() }
            } catch (t: Throwable) {
                // 兜底：任何异常都不能把录音丢了，也不能让服务卡在前台不退出
                Log.e(TAG, "整理录音失败（文件保留在 ${file.absolutePath}）", t)
                handler.post { if (serviceStopsAfter) finishService() }
            }
        }.apply {
            name = "finalize-recording"
            isDaemon = true
        }.start()
    }

    /**
     * 标题：第一段「录音 10-22 15:03」
     * - 被打断后的续段标「接上段」（一次长会话被切成多条时能看出先后）
     * - 耳机断开导致的结束标「耳机断开」（区别于用户主动停止）
     */
    private fun buildTitle(segStart: Long, segIndex: Int, reason: String): String {
        val base = "录音 " + java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(segStart)
        return when {
            segIndex > 1 -> "$base（接上段）"
            reason == "bt" -> "$base（耳机断开）"
            else -> base
        }
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} 秒"
            s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
            else -> "${s / 3600} 小时 ${s % 3600 / 60} 分"
        }
    }

    /** 收尾：移除前台通知并结束服务（必须在主线程调用） */
    private fun finishService() {
        stopGuard()
        handler.removeCallbacks(resumeRunnable)
        wantContinuous = false
        isWaitingResume = false
        startedByBluetooth = false
        currentIsBtMic = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf() }
    }

    /** 通知「未检测到有效人声，已跳过」 */
    private fun notifySkipped() {
        alert("已跳过本次录音", "未检测到有效人声（可能只是环境噪音），未生成记录")
    }

    /** 通知「录音被打断，已分段保存」 */
    private fun notifyInterrupted(text: String) {
        alert("录音被中断，已保存已录部分", text)
    }

    private fun alert(title: String, text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(this, CH_ID_ALERT, "耳机连接提示", NotificationManager.IMPORTANCE_DEFAULT)
            val n = NotificationCompat.Builder(this, CH_ID_ALERT)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            nm.notify(ALERT_ID, n)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopGuard()
        handler.removeCallbacks(resumeRunnable)
        BluetoothSco.release(this)
        if (isRecording) {
            // 系统直接销毁服务：尽力把已录部分落库，不要静默丢掉
            val seg = cutSegment()
            if (seg.usable) {
                finalizeSegment(
                    seg.file!!, seg.startAt, seg.endAt, seg.marks, seg.index, "service_destroyed"
                )
            }
        }
    }

    // ==================== 通知 ====================

    private fun updateNotification(title: String, text: String, chrono: Boolean) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildNotification(title, text, chrono))
        }
    }

    /**
     * @param chrono true 时用系统计时器显示已录时长
     *   —— 这是省电关键：原来靠 App 每秒 nm.notify() 刷新，1 小时要发 3600 次通知更新
     *   （每次都跨进程 IPC + 系统 UI 重绘）；改成 setUsesChronometer 后由系统自己渲染
     */
    private fun buildNotification(title: String, text: String, chrono: Boolean = false): Notification {
        ensureChannel(this, CH_ID, "录音状态", NotificationManager.IMPORTANCE_LOW)
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val markPi = PendingIntent.getService(
            this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_MARK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(chrono)
            .setWhen(if (chrono) startAt else System.currentTimeMillis())
            .setUsesChronometer(chrono)
            .setContentIntent(openPi)
            .addAction(0, "标记重点", markPi)
            .addAction(0, "结束记录", stopPi)
            .build()
    }
}
