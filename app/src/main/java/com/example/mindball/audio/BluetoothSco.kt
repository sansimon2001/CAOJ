package com.example.mindball.audio

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 蓝牙 SCO（通话音频通路）管理
 *
 * 背景：录音时如果要用「耳机麦克风」，必须先把蓝牙切到 HFP/SCO 通路；
 * 而 SCO 是**独占**的——用完不释放，耳机就会一直卡在通话通路，
 * 表现为听歌变闷、声音断续、甚至被系统判定连接异常而断连。
 *
 * 所以策略是：
 * - 默认**不用**耳机麦克风（手机麦克风音质更好，且完全不碰蓝牙通路，最稳）
 * - 用户显式开启后才发起 SCO，且**停止录音时一定释放**
 * - 即使没用耳机麦克风，开始录音前也顺手释放一次残留 SCO（防止上次异常退出留下脏状态）
 *
 * 注意：SCO 是窄带/宽带语音通路（8~16kHz），音质明显低于手机麦克风的 44.1kHz，
 * 只适合「耳机麦离嘴更近」这一个优势场景。
 */
object BluetoothSco {

    private const val TAG = "BluetoothSco"

    private fun audioManager(context: Context): AudioManager? =
        runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()

    private fun hasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 蓝牙是否可用且已配对过设备（粗筛，真正能否用由 startAndWait 的结果决定） */
    fun isAvailable(context: Context): Boolean = runCatching {
        if (!hasConnectPermission(context)) return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        adapter != null && adapter.isEnabled && adapter.bondedDevices.orEmpty().isNotEmpty()
    }.getOrDefault(false)

    /**
     * 发起 SCO 并等待建立
     * @return true 表示已切到蓝牙通路（可用耳机麦克风）；false 应回退手机麦克风
     *
     * **会阻塞最多 timeoutMs，必须在后台线程调用**
     */
    @Suppress("DEPRECATION")
    fun startAndWait(context: Context, timeoutMs: Long = 900): Boolean {
        val am = audioManager(context) ?: return false
        if (!isAvailable(context)) return false
        return runCatching {
            if (am.isBluetoothScoOn) return true
            am.startBluetoothSco()
            am.setBluetoothScoOn(true)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (am.isBluetoothScoOn) {
                    Log.i(TAG, "SCO 已建立，使用耳机麦克风")
                    return true
                }
                Thread.sleep(60)
            }
            Log.w(TAG, "SCO 在 ${timeoutMs}ms 内未建立，回退手机麦克风")
            release(context)
            false
        }.getOrDefault(false)
    }

    /** 释放 SCO（停止录音、服务销毁、切换音源时都要调） */
    @Suppress("DEPRECATION")
    fun release(context: Context) {
        val am = audioManager(context) ?: return
        runCatching {
            if (am.isBluetoothScoOn) {
                am.setBluetoothScoOn(false)
                am.stopBluetoothSco()
                Log.i(TAG, "SCO 已释放，蓝牙回到音乐通路")
            }
        }.onFailure { Log.w(TAG, "释放 SCO 失败", it) }
    }
}
