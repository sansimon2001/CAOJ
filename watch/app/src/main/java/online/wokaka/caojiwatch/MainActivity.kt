package online.wokaka.caojiwatch

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 草记手表端
 *
 * 一块表上只有一个正经用途：**你在忙的时候，抬腕点一下就开始记录**。
 * 所以这一屏只放三样东西：
 *
 *   1. 中间那个大圆钮：开始 / 结束
 *   2. 下面「打点」：想到什么按一下，手机上会留下一个时间戳
 *   3. 顶部一行状态：找到手机没有、录了多久
 *
 * 录音本身在手机上跑。手表不碰麦克风、不做转写、不上传——
 * 这也是它能做到「一天点十次也不心疼电量」的原因。
 */
class MainActivity : AppCompatActivity(), WatchClient.Callback {

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var btnMain: TextView
    private lateinit var btnMark: TextView
    private lateinit var tvHint: TextView

    private val client = WatchClient(this)
    private val main = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null

    /** 手机报来的状态 */
    private var linked = false
    private var state = "idle"
    private var segment = 1
    private var marks = 0

    /** 本地秒表：手机给一次基准，之后自己走，不再每秒问 */
    private var baseElapsed = 0L
    private var baseAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            renderTimer()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTimer = findViewById(R.id.tvTimer)
        btnMain = findViewById(R.id.btnMain)
        btnMark = findViewById(R.id.btnMark)
        tvHint = findViewById(R.id.tvHint)

        btnMain.setOnClickListener { onMainTap() }
        btnMark.setOnClickListener {
            client.send("mark")
            marks += 1
            render()
        }
        // 长按顶部状态：重新找手机（换了 Wi-Fi、手机重启都能救回来）
        tvStatus.setOnLongClickListener {
            tvHint.text = "正在重新找手机…"
            linked = false
            client.stop()
            client.start()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        acquireWifi()
        client.start()
        main.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
        client.stop()
        releaseWifi()
    }

    // ==================== 交互 ====================

    private fun onMainTap() {
        if (state == "rec" || state == "wait") {
            client.send("stop")
        } else {
            client.send("start")
        }
    }

    // ==================== 回调 ====================

    override fun onState(s: WatchClient.WatchState) {
        linked = true
        // 状态翻转才重置秒表基准，否则本地计时会被心跳打回去（会看到时间往回跳）
        val flipped = s.st != state
        state = s.st
        segment = s.seg
        marks = s.mk
        if (flipped || Math.abs(s.el - baseElapsed) > 2500) {
            baseElapsed = s.el
            baseAt = System.currentTimeMillis()
        }
        render()
        renderTimer()
    }

    override fun onLink(found: Boolean, info: String) {
        linked = found
        tvHint.text = if (found) "已连 ${client.phoneTitle()}" else info
        if (!found) {
            state = "idle"
        }
        render()
    }

    // ==================== 渲染 ====================

    private fun render() {
        when {
            !linked -> {
                tvStatus.text = "未连上"
                tvTimer.text = "--:--"
                btnMain.text = "开始"
                btnMain.setBackgroundResource(R.drawable.bg_btn_idle)
            }
            state == "rec" -> {
                tvStatus.text = "录制中 · 第 $segment 段 · 打点 $marks"
                btnMain.text = "结束"
                btnMain.setBackgroundResource(R.drawable.bg_btn_rec)
            }
            state == "wait" -> {
                tvStatus.text = "通话占用，已暂停"
                btnMain.text = "结束"
                btnMain.setBackgroundResource(R.drawable.bg_btn_rec)
            }
            else -> {
                tvStatus.text = "空闲 · 打点 $marks"
                btnMain.text = "开始"
                btnMain.setBackgroundResource(R.drawable.bg_btn_idle)
            }
        }
    }

    private fun renderTimer() {
        if (!linked || (state != "rec" && state != "wait")) {
            tvTimer.text = "--:--"
            return
        }
        val ms = baseElapsed + (System.currentTimeMillis() - baseAt)
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        tvTimer.text = String.format("%02d:%02d", m, s)
    }

    // ==================== Wi-Fi 常连 ====================

    /**
     * 手表默认会为了省电把 Wi-Fi 睡下去，睡下去就收不到手机的回包。
     * 只在界面可见时拿这个锁，退到后台立刻放——不是常驻后台的东西。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWifi() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "caojiwatch")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            // 拿不到就算了：多数情况下 Wi-Fi 也在跑，只是偶尔会慢一拍
        }
    }

    private fun releaseWifi() {
        try {
            wifiLock?.release()
        } catch (e: Exception) {
            // 忽略
        }
        wifiLock = null
    }
}
