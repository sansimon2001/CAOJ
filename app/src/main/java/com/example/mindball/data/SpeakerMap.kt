package com.example.mindball.data

import android.content.Context
import org.json.JSONObject

/**
 * 说话人映射与纠正（对应反馈「声纹分辨力不够、说话对象搞不清」）
 *
 * 现实约束：手机端做真正的声纹识别需要跑说话人嵌入模型（几十 MB + ONNX Runtime），
 * 且准确率仍受限于录音质量。工程上更可靠的做法是「机器先分人 + 人来订正 + 系统记住」：
 *
 * 1) 转写服务做 diarization，先把人分成 说话人1 / 说话人2 / ...
 * 2) 用户在详情页点一下，把「说话人2」改成真实名字（张总/王工）
 * 3) 映射写入全局配置，之后所有新记录自动沿用这套名字
 * 4) 「我是说话人几」也已记住，新记录自动把那个编号标成「我」
 *
 * 这样即使声纹分不准，也能在两次操作后把说话对象稳定下来，而且不会串号。
 */
object SpeakerMap {

    /** 读取全局映射：标签 → 展示名（如 "说话人2" → "张总"） */
    fun load(context: Context): MutableMap<String, String> {
        val raw = Repo.getStr(context, Repo.K_SPEAKER_MAP)
        val map = LinkedHashMap<String, String>()
        if (raw.isBlank()) return map
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { k -> map[k] = obj.optString(k) }
        }
        return map
    }

    fun save(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        Repo.setStr(context, Repo.K_SPEAKER_MAP, obj.toString())
    }

    /**
     * 把会话里的某个说话人标签改名为指定名称
     * - 同时更新该会话 transcript 中的所有出现
     * - 并把映射记进全局配置（下次转写自动套用）
     */
    fun renameSpeaker(context: Context, session: Session, fromLabel: String, toName: String): Session {
        val name = toName.trim().ifBlank { fromLabel }
        val updated = session.transcript.replace("[$fromLabel]", "[$name]")
        session.transcript = updated

        val map = load(context)
        map[fromLabel] = name
        save(context, map)
        return session
    }

    /** 把全局映射套用到一段转写文本（转写完成后立即调用） */
    fun applyAliases(text: String, map: Map<String, String>): String {
        if (text.isBlank() || map.isEmpty()) return text
        var out = text
        map.forEach { (label, name) ->
            if (label != name && name.isNotBlank()) {
                out = out.replace("[$label]", "[$name]")
            }
        }
        return out
    }

    /** 从一段转写文本里提取出现的说话人标签（按出现顺序去重） */
    fun labelsIn(transcript: String): List<String> =
        Regex("""\[([^\]]{1,12})]""").findAll(transcript)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it.isNotBlank() }
            .toList()
}
