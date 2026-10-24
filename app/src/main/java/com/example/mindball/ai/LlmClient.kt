package com.example.mindball.ai

import android.content.Context
import com.example.mindball.data.AnalyzeResult
import com.example.mindball.data.Session
import com.example.mindball.data.Repo
import com.example.mindball.data.TodoDraft
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.mindball.util.Http
import com.example.mindball.util.EndpointGuard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 大模型客户端（OpenAI 兼容 /chat/completions）
 *
 * 可直接对接：DeepSeek、通义千问（兼容模式）、Kimi、OpenAI、硅基流动等。
 * 配置项：llm_base（如 https://api.deepseek.com/v1）、llm_key、llm_model（如 deepseek-chat）
 *
 * 职责（对应心力球「记录之后」的部分）：
 * - 生成标题
 * - 结构化摘要：按角色区分（我承诺了什么 / 对方提出了什么），需要转写文本带 [我]/[对方] 标记
 * - 提取待办事项，并解析出到期日（支持「下周五」这类相对时间，按今天日期换算）
 * - 判断归属项目，便于「项目」页做跨场景归集
 * - 行为洞察（对应 O-Mind：沟通习惯、承诺兑现、决策线索）
 */
object LlmClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = Http.client(connectSec = 20, writeSec = 90, readSec = 90)

    fun isConfigured(context: Context): Boolean =
        Repo.getStr(context, Repo.K_LLM_BASE).isNotBlank() &&
                Repo.getStr(context, Repo.K_LLM_KEY).isNotBlank() &&
                Repo.getStr(context, Repo.K_LLM_MODEL).isNotBlank()

    private fun systemPrompt(today: String, hasSpeakers: Boolean, langHint: String = "") = """
你是「草记」的记录整理助手，工作方式类似个人认知教练。
今天是 $today。
${if (langHint.isNotBlank()) "\n$langHint\n" else ""}
用户会给你一段对话/通话的语音转写文本${if (hasSpeakers) "（文本每行以 [我] 或 [对方] 标注说话人）" else ""}，你需要输出严格 JSON（不要 markdown 代码块）：
{
  "title": "不超过 16 字的标题，体现这次沟通的核心事件",
  "summary": "3-6 句话的结构化摘要：讨论了什么、${if (hasSpeakers) "我承诺了什么、对方提出了什么要求、" else "谁承诺了什么、"}结论是什么",
  "project": "主项目规范名；必须来自候选项目清单，无明确证据给空字符串",
  "projectEvidence": "原文中支撑该归属的一句原话（≤40字，照抄不改写）；project 为空时此项也为空",
  "projectConfidence": 0.0,
  "otherProjects": ["本次还明确提到的其它候选项目规范名"],
  "todos": [{"text": "待办内容", "due": "YYYY-MM-DD", "project": "该项属于别的项目时填规范名，否则留空"}],
  "insight": "1-2 句行为洞察：我的沟通习惯、判断依据或可复盘的得失（客观、不评价人品）"
}

【项目归属规则】这是最容易出错的地方，逐条遵守：
1. project 只能填「候选项目」里出现过的规范名，一个字都不要改写、不要加「项目」「工程」「小区」等后缀；
   候选清单里没有合适的，就填空字符串。
2. 只有出现明确线索才算归属：原文提到该项目名/别名/线索词，或提到与之绑定的楼栋、客户、街道。
   只是同行业、同话题、同类客户，不算线索。
3. 两个项目都像时，比较线索的具体程度——提到楼号、客户全名、街道名的优先；仍然分不出来就填空字符串。
   空比错有用：归错会让用户以后彻底找不到这条记录。
4. projectEvidence 必须从原文里照抄一句话，不能概括、不能补写、不能拼接。
   抄不出这样一句话，说明第 2 条不成立，project 必须给空字符串。
5. projectConfidence 填 0-1：项目名原文出现过给 0.9 以上；仅靠线索词推断给 0.6-0.8；拿不准就留空项目。
6. 一次对话可能同时谈到多个项目：主要在谈的进 project，其余明确提到的进 otherProjects（同样只能来自候选清单）。
7. todos 的每一条如果属于另一个项目，在该条的 project 里写明；与主项目相同则留空。

【其它要求】
1. 只输出 JSON，不要任何多余文字；
2. todos 只提取明确的行动项（有动作、有对象），没有则给空数组；due 由文本中的时间线索换算成具体日期（如「下周五」「10 月底前」），今天为 $today，无法判断则给空字符串；
3. 摘要与洞察优先站在「我」的视角，明确指出我的承诺与待跟进事项；
4. 不编造未出现的信息。"""

    /**
     * 语言/方言对提示词的影响
     *
     * 三件事必须在提示词里交代清楚，否则模型会做出错误处理：
     * - 方言稿：模型若不认识「唔该晒」、「啥子」，会把它们当人名或不重要的语气词丢掉；
     * - 外语稿：不交代就会用原文语言写摘要，中文用户看不懂；
     * - 混合稿：容易被当成错字纠正，把英文术语硬译成中文，反而丢了原本的准确说法。
     */
    fun langHintFor(langId: String): String {
        if (langId.isBlank()) return ""
        val l = SpeechLangs.byId(langId)
        return when {
            l.foreign -> "原文是${l.label}。请先理解原文，再用**简体中文**输出 title / summary / todos；" +
                "人名、公司名、产品名、专业术语保留原文写法，不要硬译。"
            l.id == "mixed" -> "原文中英混说。请用**简体中文**输出 title / summary / todos，" +
                "英文技术名词、产品名保留英文原文，不要译成中文。"
            l.isDialect -> "原文是${l.label}口语转写（可能含「唔 / 嘅 / 咗」「侬 / 阿拉」「啥子」这类方言词）。" +
                "请先按方言理解其真实含义，再用**规范简体中文书面语**输出 title / summary / todos，" +
                "不要把方言词当成无意义的语气词丢掉，也不要在摘要里保留方言口语表达。"
            else -> ""
        }
    }

    /**
     * 分析一段转写文本（针对「AI 总结跑题乱写」做了三层治理）
     *
     * 1) 前置：清洗 + 分块。长文本走 map-reduce（先抽要点、再写摘要），避免模型抓不住重点
     * 2) 中段：prompt 强约束——只依据原文、不确定给空、按场景区分重点
     * 3) 出口：SummaryGuard 校验数字与短语回扣，无依据的待办直接丢弃，
     *    校验不过标记为 review（UI 显示「需人工确认」），不静默当真的展示
     */
    fun analyze(
        context: Context,
        transcript: String,
        candidates: List<ProjectMind.Profile>,
        today: String,
        envType: String = Session.ENV_UNKNOWN,
        langId: String = "",
    ): Pair<AnalyzeResult?, String> {
        if (!isConfigured(context)) return null to "未配置大模型"
        val cleaned = TranscriptCleaner.clean(transcript)
        if (TranscriptCleaner.effectiveLength(cleaned) < 6) return null to "转写内容过短或全是语气词"

        // ---------- 1) 长文本先抽要点（map） ----------
        val chunks = TranscriptCleaner.chunk(cleaned)
        val material: String = if (chunks.size <= 1) {
            cleaned
        } else {
            val points = ArrayList<String>()
            chunks.forEachIndexed { idx, c ->
                // 单块失败不致命，跳过该块（只取结果，错误信息不参与后续流程）
                val p = extractPoints(context, c, idx + 1, chunks.size).first
                if (!p.isNullOrBlank()) points.add(p)
            }
            if (points.isEmpty()) cleaned else points.joinToString("\n")
        }

        val hasSpeakers = cleaned.contains("[我]") || cleaned.contains("[对方]")
        val langHint = langHintFor(langId)
        val envHint = when (envType) {
            Session.ENV_MEETING -> "本次是会议/多人交谈录音，重点关注结论、承诺与待办；寒暄客套不要写进摘要。"
            Session.ENV_QUIET -> "本次是安静环境下的独处语音（多为灵感或自述），主要提炼想法本身。"
            Session.ENV_STREET -> "本次录制环境嘈杂，转写可能有个别错字或无关片段，只提取与工作相关的明确内容，噪声不要写进摘要。"
            else -> ""
        }

        val userContent = buildString {
            // 只给「与本次相关」的候选项目，并且连别名与线索词一起给——
            // 原来是把全部项目名一行列出去，同名近似的项目一多，模型只能靠猜
            val hint = ProjectMind.hintBlock(candidates)
            if (hint.isNotBlank()) {
                append(hint).append('\n')
            } else {
                append("候选项目：（无）本次没有可比对的项目档案，project 一律给空字符串。\n\n")
            }
            if (envHint.isNotBlank()) append("场景说明：").append(envHint).append("\n\n")
            if (langHint.isNotBlank()) append("语言说明：").append(langHint).append("\n\n")
            append("原文：\n")
            append(material.take(12000))
        }

        // 走与 chat() 同一条传输路径，只改温度与输出上限（原来这里另抄了一份请求代码）
        val (rawBody, rawErr) = postChat(
            context = context,
            system = systemPrompt(today, hasSpeakers, langHint),
            user = userContent,
            maxTokens = 1200,
            temperature = 0.2,
        )
        val body = rawBody ?: return null to rawErr
        val content = runCatching {
            JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        }.getOrDefault("")

        val json = extractJson(content) ?: return null to "大模型未返回可解析 JSON"
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null to "JSON 解析失败"

        var title = obj.optString("title").ifBlank { "未命名记录" }
        var summary = obj.optString("summary")
        val insight = obj.optString("insight")

        // ---------- 项目归属的出口校验 ----------
        // 模型给的名字必须能落到项目档案上，落不上宁可留空——
        // 这一步是「不再产生 XXX项目 / 项目二期 之类碎片」的闸门，也是防止张冠李戴的最后一道。
        val projMatch = resolveProject(context, obj, cleaned, candidates)

        // 待办级项目同样要归一到规范名；归不上就留空（表示沿用会话项目）
        val todos = parseTodos(obj.optJSONArray("todos")).map { t ->
            if (t.project.isBlank()) t
            else {
                val r = ProjectMind.resolve(context, t.project, candidates)
                if (r.confirmed && r.name != projMatch.name) t.copy(project = r.name) else t.copy(project = "")
            }
        }
        val project = projMatch.name

        // ---------- 2) 出口校验 ----------
        val verdict = SummaryGuard.check(cleaned, title, summary, todos.map { it.text })

        // 校验严重不过 → 用更低温度重试一次，仍不过就降级标记
        var finalQuality = if (verdict.ok) Session.QUALITY_OK else Session.QUALITY_REVIEW
        var guardReason = verdict.reason

        // 两条出口（重试通过 / 原结果）只有待办列表不同，其余字段完全一致，
        // 原来各抄了一份 AnalyzeResult(...)，加字段时极易漏改其中一处
        fun buildResult(todoList: List<TodoDraft>): Pair<AnalyzeResult?, String> = AnalyzeResult(
            title = title,
            summary = summary,
            todos = todoList,
            project = project,
            insight = insight,
            projectConf = projMatch.confidence,
            projectWhy = projMatch.reason,
            otherProjects = projMatch.others,
            quality = finalQuality,
            guardReason = guardReason,
        ) to ""

        if (!verdict.ok && guardReason.contains("重合度过低")) {
            val (retryText, _) = askStrictRetry(context, cleaned, today, hasSpeakers, envHint, langHint)
            if (retryText != null) {
                val retryVerdict = SummaryGuard.check(
                    cleaned, retryText.first, retryText.second, retryText.third.map { it.text }
                )
                if (retryVerdict.ok || retryVerdict.overlap > verdict.overlap) {
                    title = retryText.first
                    summary = retryText.second
                    finalQuality = if (retryVerdict.ok) Session.QUALITY_OK else Session.QUALITY_REVIEW
                    guardReason = retryVerdict.reason
                    return buildResult(retryVerdict.todos.map { text -> TodoDraft(text, "") })
                }
            }
        }

        return buildResult(todos.filter { t -> verdict.todos.contains(t.text) })
    }

    /**
     * 项目归属的强校验
     *
     * 三道关：
     * 1) 名字必须落档（ProjectMind.resolve：精确→归一化→别名→唯一包含），落不上直接留空；
     * 2) 模型抄回的 projectEvidence 必须真的出现在原文里——找不到就判定这次归属不可信，
     *    整体留空。大模型最常见的失败不是"不知道"，而是"知道该填但又没证据，于是编一句"；
     * 3) 没有依据时强行把置信度压到 0.45 以下，界面上标成「待确认」，提示用户复核。
     *
     * 这三步都不依赖模型自觉，任何一条不满足就地降级，因此不会再出现
     * 「这件事明明在谈 A，被记到 B」而用户还完全不知情的情况。
     */
    private fun resolveProject(
        context: Context,
        obj: JSONObject,
        originalText: String,
        candidates: List<ProjectMind.Profile>,
    ): ProjectMind.Match {
        val raw = obj.optString("project").trim()
        val evidence = obj.optString("projectEvidence").trim()
        val modelConf = obj.optDouble("projectConfidence", 0.0).let { if (it.isNaN()) 0.0 else it }

        // 其它被提到的项目：同样逐个别名校验，防止一口气编出好几个
        val others = ArrayList<String>()
        obj.optJSONArray("otherProjects")?.let { arr ->
            for (i in 0 until arr.length()) {
                val n = arr.optString(i).trim()
                if (n.isEmpty()) continue
                val r = ProjectMind.resolve(context, n, candidates)
                if (r.confirmed && !others.contains(r.name)) others.add(r.name)
            }
        }

        if (raw.isEmpty()) {
            return ProjectMind.Match(reason = "未提到可归属的项目", others = others)
        }

        val m = ProjectMind.resolve(context, raw, candidates)
        if (!m.confirmed) {
            return ProjectMind.Match(reason = m.reason, others = others)
        }

        // 依据校验：编造的依据 → 整个归属作废
        if (evidence.isNotEmpty() && !ProjectMind.evidenceInText(context, evidence, originalText)) {
            return ProjectMind.Match(
                reason = "给出的依据「${evidence.take(20)}」在原文中找不到，已留空待确认",
                others = others.filter { it != m.name },
            )
        }

        val conf: Double
        val why: String
        if (evidence.isEmpty()) {
            // 没抄依据说明它并没有真的在原文里看到线索，按"疑似"处理
            conf = minOf(maxOf(m.confidence, modelConf.coerceIn(0.0, 1.0)), 0.45)
            why = m.reason + "；模型未给出原文依据，建议复核"
        } else {
            conf = maxOf(m.confidence, modelConf.coerceIn(0.0, 1.0)).coerceAtMost(1.0)
            why = m.reason + "；依据：「" + evidence.take(40) + "」"
        }
        return ProjectMind.Match(
            name = m.name,
            confidence = conf,
            evidence = evidence,
            reason = why,
            others = others.filter { it != m.name },
        )
    }

    /** 单块抽要点（map 阶段）：只要事实与承诺，不要发挥 */
    private fun extractPoints(context: Context, block: String, index: Int, total: Int): Pair<String?, String> {
        val system = """你在为后续摘要做「要点抽取」。规则：
1. 只抄原文里明确出现的事实、数字、承诺、结论、待办；
2. 每条一行，以「- 」开头，最多 8 条；
3. 不解释、不引申、不补充背景；
4. 原文没提到的，绝对不要写。"""
        val user = "这是第 $index/$total 段转写，请抽取要点：\n" + block.take(2200)
        return chat(context, system, user, maxTokens = 400)
    }

    /** 重合度过低时的严格重试（温度 0，明确要求回扣原文） */
    private fun askStrictRetry(
        context: Context,
        transcript: String,
        today: String,
        hasSpeakers: Boolean,
        envHint: String,
        langHint: String = "",
    ): Pair<Triple<String, String, List<TodoDraft>>?, String> {
        val system = systemPrompt(today, hasSpeakers, langHint) + """

补充硬性要求（上一次输出与原文重合度过低）：
1. 摘要中的人名、金额、日期、数量必须与原文完全一致，不得改写；
2. 每句话都要能在原文找到对应表述；
3. 拿不准的内容宁可不写。"""
        val user = buildString {
            if (envHint.isNotBlank()) append("场景说明：").append(envHint).append("\n\n")
            if (langHint.isNotBlank()) append("语言说明：").append(langHint).append("\n\n")
            append("原文：\n").append(transcript.take(9000))
        }
        val (text, err) = chat(context, system, user, maxTokens = 1200)
        if (text.isNullOrBlank()) return null to err
        val json = extractJson(text) ?: return null to "重试未返回 JSON"
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null to "重试 JSON 解析失败"
        return Triple(
            obj.optString("title").ifBlank { "未命名记录" },
            obj.optString("summary"),
            parseTodos(obj.optJSONArray("todos")),
        ) to ""
    }

    /** 解析 todos：兼容 ["文本"] 与 [{"text":..,"due":..}] 两种格式 */
    private fun parseTodos(arr: JSONArray?): List<TodoDraft> {
        if (arr == null || arr.length() == 0) return emptyList()
        val list = mutableListOf<TodoDraft>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> {
                    val text = item.optString("text").ifBlank { item.optString("content") }.trim()
                    val due = item.optString("due").trim()
                    val proj = item.optString("project").trim()
                    if (text.isNotBlank()) {
                        list.add(TodoDraft(text, normalizeDue(due), proj))
                    }
                }
                is String -> if (item.isNotBlank()) list.add(TodoDraft(item.trim(), ""))
                else -> {
                    val s = item?.toString()?.trim().orEmpty()
                    if (s.isNotBlank() && s != "null") list.add(TodoDraft(s, ""))
                }
            }
        }
        return list
    }

    /** 只接受 YYYY-MM-DD，其它（空、无法解析）一律置空，避免脏数据影响提醒 */
    private fun normalizeDue(due: String): String {
        val d = due.trim()
        if (d.length < 10) return ""
        val s = d.take(10)
        val ok = Regex("""\d{4}-\d{2}-\d{2}""").matches(s)
        return if (ok) s else ""
    }


    // ==================== 方言归一化（方言口语 → 普通话书面语） ====================

    /**
     * 把方言口语转写稿改写成普通话书面语
     *
     * 为什么不直接让摘要模型「理解方言」而是多花一次调用：
     * 1) 转写稿要给人看。粤语稿「唔該晒，聽日下晝三點喺會議室傾」自己看懂没问题，
     *    转给同事、写进周报就没法用了；
     * 2) 摘要模型拿方言稿时容易把方言词当语气词丢掉（「唔该晒」里的信息量就没了），
     *    先归一化再总结，待办与承诺的漏检率明显下降。
     *
     * 出口做长度校验：模型偷懒只写一句总结、或大段发挥都算失败，宁可用原文。
     */
    fun normalizeDialect(context: Context, transcript: String, langLabel: String): Pair<String?, String> {
        if (!isConfigured(context)) return null to "未配置大模型"
        val src = transcript.trim()
        if (src.length < 12) return null to "文本过短"
        // 太长的稿子归一化成本高、收益低（模型也容易在长文里丢行），直接跳过
        if (src.length > 12000) return null to "文本过长，跳过归一化"

        val system = """你负责把${langLabel}口语的语音转写稿改写成普通话书面语。

硬性要求：
1. 逐行保留原文的说话人标记（[我] / [对方] / [说话人N]）与行序，不要合并或拆分说话人；
2. 人名、公司名、地名、金额、日期、时间、数字**原样保留**，不翻译、不改写、不换算；
3. 方言词换成对应的普通话说法（例：「唔该晒」→「多谢」，「啥子」→「什么」，「侬」→「你」），
   但不能为了通顺而删掉口语里的具体信息（数量、时间、条件）；
4. 不改写事实、不补充原文没有的内容、不做总结、不加评论；
5. 只输出改写后的正文，不要任何前后缀、不要解释。"""

        val (text, err) = chat(context, system, "原文：\n" + src, maxTokens = 1800)
        if (text.isNullOrBlank()) return null to err

        val out = text.trim()
        // 出口校验：长度畸变说明模型在总结或编造，宁可放弃
        if (out.length < src.length * 0.35) return null to "归一化结果过短，已丢弃"
        if (out.length > src.length * 2.2) return null to "归一化结果异常膨胀，已丢弃"
        return out to ""
    }

    // ==================== 长按说话：即时问答 ====================

    /**
     * 即时问答（对应心力球「长按 AI 键提问」）
     * 用户口述一个问题 → 转写 → 这里直接给出回答，不落库成事件摘要
     */
    fun ask(context: Context, question: String, recentContext: String = ""): Pair<String?, String> {
        if (!isConfigured(context)) return null to "未配置大模型"
        if (question.isBlank()) return null to "问题为空"

        val system = """你是用户的个人认知助手，回答问题要简洁、直接、可执行。
用户常在开会或走路时用语音提问，所以：
1. 先给结论（不超过 2 句）；
2. 需要展开时给 2-4 条要点，不要长篇大论；
3. 涉及具体行动时给出明确下一步；
4. 不确定就直说不确定，不要编造。"""

        val user = buildString {
            if (recentContext.isNotBlank()) {
                append("用户最近的记录摘要（仅供理解上下文，不必逐条复述）：\n")
                append(recentContext.take(4000))
                append("\n\n")
            }
            append("问题：").append(question)
        }

        return chat(context, system, user, maxTokens = 700)
    }

    // ==================== 项目洞见卡片（对应心力球「项目」栏） ====================

    /**
     * 基于同一项目的多段记录，生成「洞见卡片」：
     * 当前进展 / 关键决定 / 卡点风险 / 下一步动作
     */
    fun projectInsight(
        context: Context,
        project: String,
        records: List<Triple<String, String, String>>, // (日期, 标题, 摘要)
    ): Pair<String?, String> {
        if (!isConfigured(context)) return null to "未配置大模型"
        if (records.isEmpty()) return null to "该项目还没有记录"

        val system = """你是项目管理助手。用户会给你同一项目在一段时间内的多段沟通记录，
你要把它们串成一条线，输出「洞见卡片」，帮助用户把事情推进。

输出格式（纯文本，不要 markdown 代码块）：
【当前进展】2-3 句话，说明这件事现在到哪一步了
【关键决定】列出已确认的决定（没有就写"暂无"）
【卡点与风险】列出尚未解决、可能拖慢进度的问题
【下一步】3 条以内、可立即执行的下一步动作，尽量带时间
要求：只基于给定记录，不编造；语言精炼。"""

        val user = buildString {
            append("项目：").append(project).append("\n\n")
            append("按时间倒序的记录：\n")
            records.take(15).forEach { (date, title, summary) ->
                append("- [").append(date).append("] ").append(title).append("：")
                append(summary.replace("\n", " ").take(400)).append("\n")
            }
        }

        return chat(context, system, user, maxTokens = 900)
    }

    // ==================== 觉察：复盘报告（对应心力球「觉察」栏 / O-Mind） ====================

    /**
     * 跨记录行为复盘：找行为模式、盲区、可改进点
     */
    fun retrospect(
        context: Context,
        records: List<Triple<String, String, String>>, // (日期, 标题, 摘要含洞察)
    ): Pair<String?, String> {
        if (!isConfigured(context)) return null to "未配置大模型"
        if (records.isEmpty()) return null to "最近没有可复盘的记录"

        val system = """你是个人成长教练，擅长从一个人的日常沟通记录里找行为模式。
用户会给你一段时间内的沟通记录（含摘要与已有洞察），你要输出复盘报告。

输出格式（纯文本，不要 markdown 代码块）：
【沟通模式】2-3 条反复出现的行为特征（例如承诺是否给明确时间、是否先给结论）
【盲区提示】2-3 条用户自己不容易察觉的问题，要具体、可对照
【值得保持】1-2 条做得好的地方
【下周可试】2-3 条具体可执行的调整建议
要求：基于给定记录的事实，语气客观不说教，不评价人品。"""

        val user = buildString {
            append("最近 ").append(records.size).append(" 段记录：\n")
            records.take(20).forEach { (date, title, summary) ->
                append("- [").append(date).append("] ").append(title).append("：")
                append(summary.replace("\n", " ").take(500)).append("\n")
            }
        }

        return chat(context, system, user, maxTokens = 1000)
    }

    // ==================== 通用对话调用 ====================

    /**
     * 发一次 chat/completions，返回模型输出的**原文**
     *
     * analyze() 要自己解析 JSON、chat() 只要纯文本，所以把传输层单独抽出来，
     * 两条路径共用同一份「校验配置 → 校验明文 HTTP → 发请求 → 判状态码」逻辑。
     */
    private fun postChat(
        context: Context,
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Double,
    ): Pair<String?, String> {
        val base = Repo.getStr(context, Repo.K_LLM_BASE).trimEnd('/')
        val key = Repo.getStr(context, Repo.K_LLM_KEY)
        val model = Repo.getStr(context, Repo.K_LLM_MODEL)
        if (base.isBlank() || key.isBlank() || model.isBlank()) return null to "大模型配置不完整"
        // 明文 HTTP 会把对话全文与 Key 暴露在链路上，直接拒发
        EndpointGuard.check(base)?.let { return null to it }

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put(
                "messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    put(JSONObject().put("role", "user").put("content", user))
                }
            )
        }

        return runCatching {
            val req = Request.Builder()
                .url("$base/chat/completions")
                .addHeader("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody(JSON))
                .build()

            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) null to "大模型返回 ${resp.code}：${raw.take(200)}"
                else raw to ""
            }
        }.getOrElse { null to "请求异常：${it.message}" }
    }

    /** 发一次对话并取出纯文本内容 */
    private fun chat(
        context: Context,
        system: String,
        user: String,
        maxTokens: Int,
    ): Pair<String?, String> {
        val (raw, err) = postChat(context, system, user, maxTokens, temperature = 0.4)
        if (raw == null) return null to err
        val text = runCatching {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content").trim()
        }.getOrDefault("")
        return if (text.isBlank()) null to "大模型返回为空" else text to ""
    }

    /** 从可能被 ``` 包裹的内容中提取 JSON 主体 */
    private fun extractJson(content: String): String? {
        if (content.isBlank()) return null
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }
}
