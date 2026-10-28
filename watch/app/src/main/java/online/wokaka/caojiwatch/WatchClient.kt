package online.wokaka.caojiwatch

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 手表 → 手机：局域网 UDP
 *
 * ## 为什么不是 Wear OS 官方通道
 *
 * Wear OS 手表与 Android 手机之间的标准通道是 Google Play 服务里的
 * Wearable Data Layer（MessageClient / DataClient）。草记的手机端是**鸿蒙**，
 * 没有 GMS、装不了 Wear OS 配套应用——那条路根本不存在。
 *
 * 所以自己开一条：手机在 47321 端口上监听 UDP，手表先广播一句 `ping`，
 * 手机收到后**单播**回状态包（包里带自己的名字），手表记下这个来源地址，
 * 之后所有指令都直接发过去。全程不需要输入 IP、不需要配对。
 *
 * ## 功耗
 *
 * 只在界面可见时跑（MainActivity 的 onResume / onPause）：
 * 每 3 秒一个几十字节的包，其余时间不做任何事。
 * 不做麦克风、不做转写、不连服务器——这块表在待机时就是一块表。
 */
class WatchClient(private val cb: Callback) {

    interface Callback {
        /** 收到手机回的状态 */
        fun onState(s: WatchState)
        /** 有没有找到手机（found=false 时 info 是给用户看的原因） */
        fun onLink(found: Boolean, info: String)
    }

    data class WatchState(
        val ok: Boolean,
        val st: String,
        val el: Long,
        val seg: Int,
        val mk: Int,
        val name: String
    )

    companion object {
        /** 与鸿蒙端 WatchLink.PORT 必须一致 */
        const val PORT = 47321
        /** 界面可见时的心跳：3 秒。再快没意义，再慢按下去会有半秒空窗 */
        const val HEARTBEAT_MS = 3000L
        private const val CMD_PING = "{\"c\":\"ping\"}"
    }

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var host: InetAddress? = null

    @Volatile
    private var phoneName: String = "手机"

    fun start() {
        if (running) {
            return
        }
        running = true
        host = null
        val t = Thread { loop() }
        t.isDaemon = true
        t.start()
        thread = t
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // 已关就忽略
        }
        socket = null
        thread?.interrupt()
        thread = null
        host = null
    }

    /** 发一条指令（start / stop / mark）。没找到手机时提示先连上 */
    fun send(cmd: String) {
        val h = host
        if (h == null) {
            postLink(false, "还没找到手机")
            return
        }
        sendTo(h, "{\"c\":\"$cmd\"}")
    }

    fun phoneTitle(): String = phoneName

    // ==================== 内部 ====================

    private fun loop() {
        try {
            val s = DatagramSocket()
            s.broadcast = true
            s.soTimeout = 1200
            socket = s
            var lastPing = 0L
            while (running) {
                val now = System.currentTimeMillis()
                if (now - lastPing >= HEARTBEAT_MS) {
                    lastPing = now
                    // 找到之前一直广播；找到之后改成单播，少打扰同网段的其它设备
                    val h = host
                    if (h == null) {
                        sendTo(InetAddress.getByName("255.255.255.255"), CMD_PING)
                    } else {
                        sendTo(h, CMD_PING)
                    }
                }
                val buf = ByteArray(512)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    s.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    handlePacket(text, pkt)
                } catch (e: java.net.SocketTimeoutException) {
                    // 正常：这一轮没回包。第一次没回就告诉用户「没找到手机」
                    if (host == null) {
                        postLink(false, "没找到手机（同一 Wi-Fi？手机端开关开了？）")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                postLink(false, "网络不可用：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun handlePacket(text: String, pkt: DatagramPacket) {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        if (!obj.optBoolean("ok", false)) {
            return
        }
        // 第一次收到就记住来源地址，之后不再广播
        val addr = pkt.address
        if (host == null) {
            host = addr
            phoneName = obj.optString("name", "手机")
            postLink(true, "${addr.hostAddress}:$PORT")
        }
        val st = WatchState(
            ok = true,
            st = obj.optString("st", "idle"),
            el = obj.optLong("el", 0L),
            seg = obj.optInt("seg", 1),
            mk = obj.optInt("mk", 0),
            name = phoneName
        )
        main.post { cb.onState(st) }
    }

    private fun sendTo(addr: InetAddress, text: String) {
        val s = socket ?: return
        try {
            val data = text.toByteArray(Charsets.UTF_8)
            s.send(DatagramPacket(data, data.size, addr, PORT))
        } catch (e: Exception) {
            if (host != null) {
                host = null
                postLink(false, "和手机断了，正在重找…")
            }
        }
    }

    private fun postLink(found: Boolean, info: String) {
        main.post { cb.onLink(found, info) }
    }
}
