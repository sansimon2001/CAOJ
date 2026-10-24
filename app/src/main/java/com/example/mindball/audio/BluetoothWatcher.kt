package com.example.mindball.audio

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 蓝牙耳机连接监听（需求核心触发机制）
 *
 * 之前版本「耳机老是断开」的真实原因有三个，本版逐个修掉：
 *
 * ① **中间态被当成断连**（主因）
 *    `STATE_CONNECTING` / `STATE_DISCONNECTING` 也被算进了状态判断。
 *    而开始录音时系统要做音频路由切换（A2DP ↔ HFP），两个 profile 会先后上报中间态，
 *    出现「两侧都不是 CONNECTED」的空窗 → 误判断开 → 正在进行的录音被自己停掉。
 *    → 现在**只认终态 CONNECTED / DISCONNECTED**，并加 3 秒断开宽限期，期间任一路恢复就撤销。
 *
 * ② **只靠广播，会漏**
 *    App 切后台、系统省电策略、耳机在别处已连好后才打开 App —— 广播可能收不到，
 *    界面就一直显示「未连接」。
 *    → 现在注册时和每次回到前台都用 `getProfileProxy` **主动读一次真实连接状态**做校正。
 *
 * ③ **单设备布尔值**
 *    连第二副耳机、或一副耳机同时挂 A2DP+HFP 时状态会互相覆盖。
 *    → 现在按**设备地址集合**记录，两路取并集，多设备也不会错判。
 *
 * 另外：蓝牙关闭时立即判定断开（不等待宽限期）；缺 BLUETOOTH_CONNECT 权限时安全降级。
 */
class BluetoothWatcher(
    private val context: Context,
    private val onConnected: (deviceName: String) -> Unit,
    private val onDisconnected: () -> Unit,
) {

    companion object {
        private const val TAG = "BluetoothWatcher"

        /** 连接确认延迟：等 A2DP / HFP 都上报完再触发，避免重复开始录音 */
        private const val CONNECT_GRACE_MS = 500L

        /** 断开确认延迟：录音中系统切换音频通路会短暂断连，等 3 秒确认，避免误停录音 */
        private const val DISCONNECT_GRACE_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 已连接设备：地址 → 名称（多设备、双 profile 都安全） */
    private val hfpDevices = LinkedHashMap<String, String>()
    private val a2dpDevices = LinkedHashMap<String, String>()

    private var registered = false
    private var lastConnected = false
    private var pendingConnect = false
    private var pendingDisconnect = false

    private val connectRunnable = Runnable { confirmConnect() }
    private val disconnectRunnable = Runnable { confirmDisconnect() }

    // ==================== 广播 ====================

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    val device = deviceFrom(intent)
                    val isHfp = action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
                    val map = if (isHfp) hfpDevices else a2dpDevices

                    when (state) {
                        // 只处理终态，中间态（CONNECTING / DISCONNECTING）一律忽略
                        BluetoothProfile.STATE_CONNECTED -> {
                            val addr = device?.address ?: ("anon-" + map.size)
                            map[addr] = deviceName(device)
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            val addr = runCatching { device?.address }.getOrNull()
                            if (addr != null) map.remove(addr) else map.clear()
                        }

                        else -> {
                            Log.d(TAG, "忽略中间态 state=$state（$action）")
                            return
                        }
                    }
                    reevaluate()
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        // 蓝牙被关掉：立即判定断开，不走宽限期
                        Log.i(TAG, "蓝牙关闭，判定耳机已断开")
                        hfpDevices.clear()
                        a2dpDevices.clear()
                        cancelPending()
                        if (lastConnected) {
                            lastConnected = false
                            onDisconnected()
                        }
                    }
                }
            }
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }.getOrNull()

    /** 读设备名需要 BLUETOOTH_CONNECT（Android 12+），没权限就用默认名 */
    private fun deviceName(device: BluetoothDevice?): String {
        if (device == null) return "蓝牙耳机"
        if (!hasConnectPermission()) return "蓝牙耳机"
        return runCatching { device.name?.takeIf { it.isNotBlank() } ?: "蓝牙耳机" }
            .getOrDefault("蓝牙耳机")
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    // ==================== 状态机（去抖） ====================

    private fun anyConnected(): Boolean = hfpDevices.isNotEmpty() || a2dpDevices.isNotEmpty()

    private fun currentName(): String =
        a2dpDevices.values.firstOrNull()
            ?: hfpDevices.values.firstOrNull()
            ?: "蓝牙耳机"

    private fun cancelPending() {
        pendingConnect = false
        pendingDisconnect = false
        handler.removeCallbacks(connectRunnable)
        handler.removeCallbacks(disconnectRunnable)
    }

    /** 每次状态变化后统一裁决：连接要等 0.5s 稳定，断开要等 3s 确认 */
    private fun reevaluate() {
        val now = anyConnected()
        if (now) {
            // 连上了 → 撤销待发的断开确认
            if (pendingDisconnect) {
                pendingDisconnect = false
                handler.removeCallbacks(disconnectRunnable)
                Log.i(TAG, "断开宽限期内重新连上，撤销断开")
            }
            if (!lastConnected && !pendingConnect) {
                pendingConnect = true
                handler.postDelayed(connectRunnable, CONNECT_GRACE_MS)
            }
        } else {
            if (pendingConnect) {
                pendingConnect = false
                handler.removeCallbacks(connectRunnable)
            }
            if (lastConnected && !pendingDisconnect) {
                pendingDisconnect = true
                handler.postDelayed(disconnectRunnable, DISCONNECT_GRACE_MS)
                Log.i(TAG, "检测到断开，${DISCONNECT_GRACE_MS}ms 后确认（等音频通路切换完成）")
            }
        }
    }

    private fun confirmConnect() {
        pendingConnect = false
        if (!anyConnected()) return
        if (lastConnected) return
        lastConnected = true
        val name = currentName()
        Log.i(TAG, "耳机已连接：$name")
        onConnected(name)
    }

    private fun confirmDisconnect() {
        pendingDisconnect = false
        // 宽限期结束时再复核一次，仍断开才真正上报
        if (anyConnected()) {
            Log.i(TAG, "复核时已重新连上，忽略本次断开")
            return
        }
        if (!lastConnected) return
        lastConnected = false
        Log.i(TAG, "耳机已断开（已确认）")
        onDisconnected()
    }

    // ==================== 主动校正 ====================

    /**
     * 用 Profile Proxy 读取真实连接状态，覆盖本地集合
     * 解决「广播漏收」导致界面一直显示未连接 / 或已断开却仍显示已连接
     */
    fun refresh() {
        val adapter = adapter() ?: return
        if (!hasConnectPermission()) {
            Log.w(TAG, "缺少 BLUETOOTH_CONNECT，无法主动校正状态")
            return
        }
        queryProfile(adapter, BluetoothProfile.HEADSET)
        queryProfile(adapter, BluetoothProfile.A2DP)
    }

    private fun adapter(): BluetoothAdapter? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private fun queryProfile(adapter: BluetoothAdapter, profile: Int) {
        runCatching {
            val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile?) {
                    val list = runCatching { proxy?.connectedDevices.orEmpty() }.getOrDefault(emptyList())
                    val map = if (p == BluetoothProfile.HEADSET) hfpDevices else a2dpDevices
                    handler.post {
                        map.clear()
                        list.forEach { d ->
                            val addr = runCatching { d.address }.getOrNull()
                            if (addr != null) map[addr] = deviceName(d)
                        }
                        runCatching { adapter.closeProfileProxy(p, proxy) }
                        Log.d(TAG, "profile=$p 实际连接 ${map.size} 台")
                        reevaluate()
                    }
                }

                override fun onServiceDisconnected(p: Int) = Unit
            }, profile)
            if (!ok) Log.w(TAG, "getProfileProxy 失败 profile=$profile")
        }.onFailure { Log.w(TAG, "查询 profile=$profile 异常", it) }
    }

    // ==================== 生命周期 ====================

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        // 注册时先读一次真实状态（可能 App 打开前耳机就连好了）
        refresh()
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        cancelPending()
    }

    /** 当前是否已连接（含待确认状态，用于界面显示） */
    fun isHeadsetConnected(): Boolean = anyConnected() || lastConnected
}
