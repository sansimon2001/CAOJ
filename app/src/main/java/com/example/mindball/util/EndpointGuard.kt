package com.example.mindball.util

/**
 * 接口地址守卫：拒绝明文 HTTP
 *
 * 会把什么发出去？
 * - 转写：**整段录音文件**
 * - 摘要：**完整对话文本**（含人名、金额、承诺）
 * - 每次请求的请求头里还有 **API Key**
 *
 * 这些走 http:// 就是在同一个 Wi-Fi 下裸奔。所以除了系统层
 * （network_security_config 禁明文）之外，这里也在发请求前拦一道，
 * 给用户一句能看懂的中文提示，而不是 OkHttp 的
 * `CLEARTEXT communication to x.x.x.x not permitted`。
 */
object EndpointGuard {

    /** @return null = 允许；否则为可直接展示给用户的错误文案 */
    fun check(url: String): String? {
        val u = url.trim()
        if (u.isBlank()) return "接口地址为空"
        if (u.startsWith("https://", ignoreCase = true)) return null
        if (isLocalPlainHttp(u)) return null
        if (u.startsWith("http://", ignoreCase = true)) {
            return "为保护隐私，接口地址必须是 https://（录音与对话内容不能明文传输）。" +
                "自建服务可先用 http://127.0.0.1 在本机调试。"
        }
        return "接口地址需要以 https:// 开头"
    }

    /** 本机回环地址的 http 不出设备，允许（自建转写服务调试用） */
    private fun isLocalPlainHttp(url: String): Boolean {
        if (!url.startsWith("http://", ignoreCase = true)) return false
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }
}
