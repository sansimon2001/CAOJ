package com.example.mindball.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享的 HTTP 客户端
 *
 * 原来 AsrClient / LlmClient / ConnectivityTest / CollabBridge / WorkBuddyBridge
 * 各自 `OkHttpClient.Builder()` 新建一个实例，等于同时存在 5 个 Dispatcher（各自的线程池）
 * 和 5 个 ConnectionPool。OkHttp 官方的用法是**共享一个实例**，
 * 需要不同超时时用 `newBuilder()` 派生——派生出来的客户端复用同一个连接池与派发器，
 * 只是换了超时参数。
 *
 * 这里只提供最小封装，各调用点传入自己的超时秒数即可。
 */
object Http {

    /** 默认连接超时（秒）；各业务按需覆盖 */
    private const val CONNECT_SEC = 15L

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SEC, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 派生一个带指定超时的客户端（共享底层连接池与线程池）
     * @param writeSec 0 表示沿用默认值
     * @param readSec 0 表示沿用默认值
     */
    fun client(connectSec: Long, writeSec: Long = 0, readSec: Long = 0): OkHttpClient {
        val b = base.newBuilder().connectTimeout(connectSec, TimeUnit.SECONDS)
        if (writeSec > 0) b.writeTimeout(writeSec, TimeUnit.SECONDS)
        if (readSec > 0) b.readTimeout(readSec, TimeUnit.SECONDS)
        return b.build()
    }
}
