package com.example.mindball.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.mindball.R
import com.example.mindball.ai.AnalysisPipeline
import com.example.mindball.audio.BluetoothWatcher
import com.example.mindball.audio.RecorderService
import com.example.mindball.data.Session
import com.example.mindball.util.Touch.confirmFeedback
import com.example.mindball.data.Repo
import com.example.mindball.reminder.TodoReminder
import com.example.mindball.util.Privacy
import com.example.mindball.databinding.ActivityMainBinding
import com.example.mindball.util.Importer
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var watcher: BluetoothWatcher
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var headsetConnected = false
    /** 上一次的录音状态，用于「只在变化时刷新界面」 */
    private var lastRecState = false
    /** 当前显示的底部导航页（-1 = 还没初始化），用于页面复用 */
    private var currentPage = -1

    /** 接受外部投递文本的上限（约 2 万字符，足够一篇长文） */
    private val MAX_SHARED_CHARS = 20_000

    private val todayFragment get() = findFragment<TodayFragment>()

    private inline fun <reified T : Fragment> findFragment(): T? =
        supportFragmentManager.fragments.filterIsInstance<T>().firstOrNull()

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioGranted = result[Manifest.permission.RECORD_AUDIO] ?: hasPerm(Manifest.permission.RECORD_AUDIO)
        if (!audioGranted) {
            Toast.makeText(this, "未授予麦克风权限，无法记录", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Privacy.applySecureWindow(this)
        Repo.init(this)

        requestRuntimePermissions()
        // 待办到期提醒：确保每日闹钟已设置，并当场检查一次（不依赖闹钟）
        // 闹钟设置是跨进程调用，很快；而「查库 + 建通知」放后台，避免拖慢首屏
        runCatching { TodoReminder.ensureScheduled(this) }
        Thread {
            runCatching { TodoReminder.checkAndNotify(this) }
        }.start()
        setupNav()
        setupBluetooth()
        setupRecordingBar()

        if (savedInstanceState == null) {
            switchTo(0)
        } else {
            // 系统已恢复 add/hide 状态：按可见页面还原选中项，不要重复 add
            val fm = supportFragmentManager
            currentPage = (0..3).firstOrNull { i ->
                fm.findFragmentByTag(tagOf(i))?.isHidden == false
            } ?: 0
            runCatching { b.bottomNav.selectedItemId = navIdOf(currentPage) }
        }

        // 从别的应用「分享到草记」
        handleIncomingShare(intent)

        showPrivacyNoticeIfNeeded()
    }

    /**
     * 首次启动的数据说明
     *
     * 这个 App 会录音、会把内容发给第三方接口。用户有权在「第一次打开时」
     * 就知道这件事去了哪、存在哪，而不是翻设置才看见。
     * 只弹一次（确认后写入配置），不影响后续使用。
     */
    private fun showPrivacyNoticeIfNeeded() {
        if (Repo.getBool(this, Repo.K_PRIVACY_ACK, false)) return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("关于你的数据")
            .setMessage(
                "· 录音、转写、摘要只存在本机应用私有目录，卸载即删除\n" +
                    "· 默认不上传；只有你配置了转写 / 大模型接口后，处理记录时才会把该段音频或文本发给你指定的服务商\n" +
                    "· 接口地址强制 https，API Key 用系统密钥库加密保存\n" +
                    "· 云备份与换机克隆已关闭，换机请用「导出全部记录」\n" +
                    "· 截屏保护默认开启，可随时在「设置 → 隐私与安全」里调整或清除全部数据\n\n" +
                    "请先确认：录音前你已获得在场人员的必要同意。是否可以对一段谈话录音，由当地法律与具体场景决定，" +
                    "本应用无法替你判断，也不对录音的使用后果负责。\n\n" +
                    "继续使用即表示你已阅读并同意《用户协议与免责声明》（可点下方「查看声明」阅读全文，或在" +
                    "「设置 → 关于与法务」中随时打开）。"
            )
            .setNeutralButton("查看声明") { _, _ ->
                runCatching { startActivity(Intent(this, LegalActivity::class.java)) }
                // 看完还得回来确认一次，不直接把 ack 写死
                showPrivacyNoticeIfNeeded()
            }
            .setPositiveButton("我已阅读并同意") { _, _ ->
                Repo.setBool(this, Repo.K_PRIVACY_ACK, true)
                // 默认服务商已填好，只差一个 Key：直接把用户带到设置页，省得自己找
                if (Repo.getStr(this, Repo.K_LLM_KEY).isBlank()) {
                    Toast.makeText(this, "下一步：在「设置」里填入硅基流动 API Key 即可开始使用", Toast.LENGTH_LONG).show()
                    switchTo(3)
                    runCatching { b.bottomNav.selectedItemId = R.id.nav_settings }
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 接收外部分享进来的内容（v1.17 扩展）
     *
     * 支持三种：
     * 1. **纯文本**（浏览器/笔记/微信里选一段文字）→ 直接入库并分析
     * 2. **音频文件**（钉钉闪记、飞书妙记、录音笔、微信"文件"里分享过来）→ 拷进私有目录走转写
     * 3. **转写稿文件**（txt/md/srt/vtt/docx/json）→ 解析后入库，跳过 ASR
     *
     * 走的是 Android 标准 `ACTION_SEND`，所以对方 App 不需要为草记做任何适配 ——
     * 这正是「和别的录音设备/会议产品打通」唯一可行、也最稳的方式：
     * 它们都支持"分享到其他应用"，我们只要能收。
     */
    private fun handleIncomingShare(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action ?: return false

        // 「用草记打开」：文件管理器 / 微信里点音频文件 → 选草记
        // 加了确认框：否则在音乐文件上误点「打开方式 → 草记」也会被当成会议录音去转写
        if (action == Intent.ACTION_VIEW) {
            val data = intent.data ?: return false
            if (data.scheme != "content" && data.scheme != "file") return false
            val name = runCatching { Importer.displayName(this, data) }.getOrDefault("该音频")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("导入这段音频？")
                .setMessage("$name\n\n将复制到草记并排队转写（会消耗你的转写额度）。")
                .setPositiveButton("导入") { _, _ -> importSharedFiles(listOf(data)) }
                .setNegativeButton("取消", null)
                .show()
            return true
        }

        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false

        val streams = streamUris(intent)
        if (streams.isNotEmpty()) {
            importSharedFiles(streams)
            return true
        }

        // 没有文件流：按纯文本处理（ACTION_SEND 的 text/plain）
        if (intent.type?.startsWith("text/") != true) return false
        // 上限保护：任何应用都能向我们投递文本，超长内容只会白占库、白耗分析额度
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(MAX_SHARED_CHARS).orEmpty()
        if (text.isBlank()) return false

        Toast.makeText(this, "已接收分享，正在整理…", Toast.LENGTH_SHORT).show()
        Thread {
            val now = System.currentTimeMillis()
            val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
            val s = Session(
                title = when {
                    firstLine.isBlank() -> "外部分享"
                    firstLine.length <= 30 -> firstLine
                    else -> firstLine.take(30) + "…"
                },
                transcript = text,
                source = Session.SOURCE_SHARE,
                startAt = now,
                endAt = now,
                status = Session.STATUS_TRANSCRIBED,
            )
            val id = runCatching { Repo.insertSession(this, s) }.getOrDefault(0L)
            if (id > 0) {
                AnalysisPipeline.enqueue(this, id)
                runOnUiThread { todayFragment?.refresh() }
            }
        }.start()
        return true
    }

    /** ACTION_SEND / SEND_MULTIPLE 里的文件 Uri（兼容旧版 EXTRA_STREAM） */
    private fun streamUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) cd.getItemAt(i)?.uri?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        }
        return out.filter { it.scheme == "content" || it.scheme == "file" }
    }

    /** 导入外部音频 / 转写稿（后台线程，逐个文件隔离失败） */
    private fun importSharedFiles(uris: List<Uri>) {
        Toast.makeText(this, "已接收 ${uris.size} 个文件，正在导入…", Toast.LENGTH_SHORT).show()
        Thread {
            val summary = runCatching { Importer.importUris(this, uris) }.getOrNull()
            runOnUiThread {
                Toast.makeText(
                    this,
                    summary?.message() ?: "导入失败，请重试",
                    Toast.LENGTH_LONG
                ).show()
                todayFragment?.refresh()
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    // ==================== 权限 ====================
    private fun hasPerm(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = list.filter { !hasPerm(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    // ==================== 导航 ====================
    private fun setupNav() {
        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_today -> switchTo(0)
                R.id.nav_projects -> switchTo(1)
                R.id.nav_insight -> switchTo(2)
                R.id.nav_settings -> switchTo(3)
            }
            true
        }
    }

    private fun switchTo(index: Int) {
        // 页面复用：点当前页不重建（原来每次点都会 new Fragment + replace，
        // 等于每次都重新 inflate 视图、重新查一遍库，这就是「切页要等一下」的来源）
        if (index == currentPage) return

        val fm = supportFragmentManager
        val tag = tagOf(index)
        val existing = fm.findFragmentByTag(tag)
        val tx = fm.beginTransaction().setReorderingAllowed(true)

        // 只隐藏「当前可见的页面」，不动已隐藏的（避免重复事务）
        fm.fragments.forEach { f ->
            if (f !== existing && !f.isHidden && f.tag?.startsWith("page_") == true) tx.hide(f)
        }
        if (existing == null) {
            tx.add(R.id.container, createPage(index), tag)
        } else {
            tx.show(existing)
        }
        // commitNow：保证事务立刻落地，连续点两个 Tab 时 findFragmentByTag 不会拿到旧状态
        tx.commitNow()
        currentPage = index
    }

    private fun createPage(index: Int): Fragment = when (index) {
        0 -> TodayFragment()
        1 -> ProjectsFragment()
        2 -> InsightFragment()
        else -> SettingsFragment()
    }

    private fun tagOf(index: Int) = "page_$index"

    private fun navIdOf(index: Int): Int = when (index) {
        0 -> R.id.nav_today
        1 -> R.id.nav_projects
        2 -> R.id.nav_insight
        else -> R.id.nav_settings
    }

    // ==================== 蓝牙耳机触发（核心差异点） ====================
    private fun setupBluetooth() {
        watcher = BluetoothWatcher(
            this,
            onConnected = { name ->
                headsetConnected = true
                updateHeadsetTag()
                todayFragment?.refreshSourceUi()
                val auto = Repo.getBool(this, Repo.K_AUTO_RECORD, true)
                if (auto && !RecorderService.isRecording) {
                    // 需求：连上蓝牙耳机后才提示/开始录音
                    if (hasPerm(Manifest.permission.RECORD_AUDIO)) {
                        startRecorderService(RecorderService.ACTION_START, "bluetooth")
                        val src = if (Repo.getBool(this, Repo.K_BT_MIC, false)) "耳机麦克风" else "手机麦克风"
                        Toast.makeText(this, "$name 已连接，开始记录（$src）", Toast.LENGTH_SHORT).show()
                    } else {
                        RecorderService.notifyHeadsetReady(this, name)
                    }
                } else if (!RecorderService.isRecording) {
                    RecorderService.notifyHeadsetReady(this, name)
                }
            },
            onDisconnected = {
                headsetConnected = false
                updateHeadsetTag()
                todayFragment?.refreshSourceUi()
                if (RecorderService.isRecording) {
                    when {
                        // ⚠️ 来电 / 微信语音接通时，系统会把音频通路从 A2DP 切到通话（SCO），
                        //    表现之一就是「耳机断开一下」。如果这里照样停掉录音，用户通话前
                        //    录的那段就被提前截断，通话后的对话更是完全录不到。
                        //    所以通话中只提示，不停记录 —— 服务自己会在通话结束后接着录。
                        RecorderService.isInCall(this) -> Toast.makeText(
                            this, "通话占用了耳机音频通路，记录继续（通话结束后自动续录）",
                            Toast.LENGTH_SHORT
                        ).show()

                        // 耳机连上自动触发的记录：耳机断开就是结束信号（原有行为）
                        RecorderService.startedByBluetooth -> {
                            startRecorderService(RecorderService.ACTION_STOP, "bt")
                            Toast.makeText(this, "耳机已断开，记录结束", Toast.LENGTH_SHORT).show()
                        }

                        // 手动开始、但选的是耳机麦：耳机没了不等于要停，转手机麦接着录
                        RecorderService.currentIsBtMic -> {
                            switchSourceTo(btMic = false)
                            Toast.makeText(
                                this, "耳机已断开，已自动切到手机麦克风继续记录",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        // 手动开始 + 手机麦克风：耳机状态与录音无关，什么都不做
                        else -> Toast.makeText(
                            this, "耳机已断开，手机麦克风记录继续", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
        watcher.register()
    }

    /** 供今日页音源选项判断耳机是否在线 */
    fun isHeadsetConnected(): Boolean = headsetConnected

    /** 录音中切换音源（服务已在前台运行，用普通 startService 即可） */
    private fun switchSourceTo(btMic: Boolean) {
        val intent = Intent(this, RecorderService::class.java)
            .setAction(RecorderService.ACTION_SWITCH_SOURCE)
            .putExtra(RecorderService.EXTRA_SOURCE, RecorderService.sourceOf(btMic))
        runCatching { startService(intent) }
            .onFailure { android.util.Log.w("MainActivity", "切换音源失败", it) }
    }

    private fun updateHeadsetTag() {
        b.tvHeadsetState.text = getString(
            if (headsetConnected) R.string.headset_connected else R.string.headset_disconnected
        )
    }

    /**
     * 启动录音服务
     *
     * Android 12+ 起，**App 在后台时禁止启动前台服务**，否则抛
     * ForegroundServiceStartNotAllowedException —— 直接闪退。
     * 「耳机连上时 App 恰好在后台」正是最容易踩到这条的场景，
     * 所以这里必须捕获，并退化成一条「点击开始记录」的通知。
     */
    private fun startRecorderService(action: String, trigger: String?) {
        val intent = Intent(this, RecorderService::class.java).setAction(action)
        trigger?.let { intent.putExtra(RecorderService.EXTRA_TRIGGER, it) }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { e ->
            android.util.Log.w("MainActivity", "系统拒绝在后台启动前台服务，降级为通知提示", e)
            runCatching { RecorderService.notifyHeadsetReady(this, "蓝牙耳机") }
        }
    }

    // ==================== 录音状态条 ====================
    private fun setupRecordingBar() {
        b.tvMarkBtn.setOnClickListener {
            if (RecorderService.isRecording) {
                b.tvMarkBtn.confirmFeedback()
                startRecorderService(RecorderService.ACTION_MARK, null)
                Toast.makeText(this, "已标记重点", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "当前未在记录", Toast.LENGTH_SHORT).show()
            }
        }

        // 省电：录音中 1Hz 刷新时长文本；空闲时降到 5s 只做一次状态检查
        ticker = object : Runnable {
            override fun run() {
                refreshRecordingBar()
                handler.postDelayed(this, if (RecorderService.isRecording) 1000L else 5000L)
            }
        }
    }

    private fun refreshRecordingBar() {
        val recording = RecorderService.isRecording
        // 关键省电点：原来这里每秒都调 todayFragment.refresh()（查库 + RecyclerView 全量重绘），
        // 现在只有「开始/结束录音」这种状态变化时才刷新
        if (recording != lastRecState) {
            lastRecState = recording
            b.llRecordingBar.visibility =
                if (recording) android.view.View.VISIBLE else android.view.View.GONE
            todayFragment?.refresh()
        }
        if (recording) {
            val sec = (System.currentTimeMillis() - RecorderService.startAt) / 1000
            b.tvRecordingText.text =
                String.format(Locale.CHINA, "正在记录 %02d:%02d · 可点右侧标记重点", sec / 60, sec % 60)
        }
    }

    override fun onResume() {
        super.onResume()
        updateHeadsetTag()
        handler.post(ticker!!)
        // 回到前台主动向系统查一次真实连接状态：
        // App 在后台期间可能收不到蓝牙广播（省电策略/系统回收），只靠广播会让状态长期不同步
        runCatching { watcher.refresh() }
    }

    override fun onPause() {
        super.onPause()
        ticker?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { watcher.unregister() }
        ticker?.let { handler.removeCallbacks(it) }
    }
}
