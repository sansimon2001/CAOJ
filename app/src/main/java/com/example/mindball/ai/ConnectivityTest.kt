package com.example.mindball.ai

import com.example.mindball.util.Http
import com.example.mindball.util.EndpointGuard
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连通性测试：验证「地址 + Key + 模型名」是否真的能用
 *
 * 只测大模型（发一次极短对话）。转写接口没有标准探测方式，
 * 所以在保存时只校验配置完整性，真正的转写结果会在下一段录音处理时体现。
 */
object ConnectivityTest {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = Http.client(connectSec = 12, writeSec = 10, readSec = 45)

    /**
     * 测试大模型：发送一句「回复"ok"」
     * @return (是否成功, 说明文字)
     */
    fun testLlm(base: String, key: String, model: String): Pair<Boolean, String> {
        if (base.isBlank()) return false to "接口地址为空"
        if (key.isBlank()) return false to "API Key 为空"
        if (model.isBlank()) return false to "模型名为空"
        EndpointGuard.check(base)?.let { return false to it }

        val url = base.trimEnd('/') + "/chat/completions"
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 16)
            put("temperature", 0)
            put(
                "messages", JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", "只回复两个字：正常"))
                }
            )
        }

        return runCatching {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val reply = runCatching {
                            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message").optString("content")
                        }.getOrDefault("")
                        true to "连接成功 · 模型 $model 回复：${reply.take(20).ifBlank { "(空)" }}"
                    }
                    resp.code == 401 || resp.code == 403 ->
                        false to "Key 无效或没有权限（HTTP ${resp.code}）"
                    resp.code == 404 ->
                        false to "接口地址不对（HTTP 404），检查是否为 /v1 结尾的 base"
                    resp.code == 429 ->
                        false to "请求过于频繁或额度用尽（HTTP 429）"
                    else ->
                        false to "调用失败 HTTP ${resp.code}：${raw.take(120)}"
                }
            }
        }.getOrElse { false to "网络异常：${it.message}" }
    }

    /** 转写配置完整性检查（无法真正探测，只能校验字段） */
    fun checkAsrConfig(url: String, key: String, model: String): Pair<Boolean, String> {
        if (url.isBlank()) return false to "转写地址为空"
        if (key.isBlank()) return false to "转写 Key 为空"
        if (model.isBlank()) return false to "转写模型名为空"
        EndpointGuard.check(url)?.let { return false to it }
        return true to "转写配置格式正确（实际效果在下一段录音转写时体现）"
    }

}
