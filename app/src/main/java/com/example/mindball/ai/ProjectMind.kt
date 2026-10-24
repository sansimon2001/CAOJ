package com.example.mindball.ai

import android.content.Context
import android.util.Log
import com.example.mindball.data.Repo

/**
 * 项目档案与归属判定
 *
 * 为什么原来的做法必然归错：
 * 1) 「已有项目」清单来自 `SELECT DISTINCT project FROM sessions`——它是**模型历史输出的回声**，
 *    不是真实的项目列表。模型曾写下「城市之光项目」，清单里就多一个和「城市之光」并存的假项目；
 *    碎片越滚越多，模型面对 20 个近义名字只能瞎挑。
 * 2) 清单只有名字，没有任何可判别特征。模型不知道「盛懋中心」是写字楼、「城市之光」是公寓，
 *    更不知道各自涉及哪些客户、楼号、街道。名字越像，越容易张冠李戴。
 * 3) 提示词写「尽量复用已有项目名」，又把它放在正文第一行——模型于是倾向**填一个**，
 *    哪怕原文根本没提项目。这就是「这件事被记到另一个项目」的直接来源。
 *
 * 这里的做法：
 * - 项目升级为**档案**（规范名 + 别名 + 关键词 + 一句话说明 + 最近记录），改名只改一次，历史自动跟着走；
 * - 分析前按原文做**候选预筛**，只把相关的几个项目连特征一起交给模型，而不是丢一整串名字；
 * - 模型的输出必须能**落到档案上**（精确→归一化→别名→唯一包含），落不上就置空并标记待确认，
 *   绝不允许再产生「XXX项目」这类新碎片；
 * - 用户手动纠正时把上下文记成别名/关键词，下次同类说法自动命中——越用越准。
 */
object ProjectMind {

    private const val TAG = "ProjectMind"

    /**
     * 项目档案
     * @param name 规范名（唯一，落库用这个）
     * @param aliases 别名：口语叫法、简称、英文名、客户内部叫法
     * @param keywords 关键词：楼栋、街道、客户、品牌、业态等只在谈这个项目时才会出现的词
     * @param note 一句话说明，帮助模型理解它是「什么项目」
     * @param recentTitles 最近几条记录的标题（运行时填充，作为语义参照）
     */
    data class Profile(
        val name: String,
        val aliases: List<String> = emptyList(),
        val keywords: List<String> = emptyList(),
        val note: String = "",
        val recentTitles: List<String> = emptyList(),
    ) {
        /** 参与匹配的全部词面（规范名 + 别名） */
        val surfaces: List<String> get() = (listOf(name) + aliases).filter { it.isNotBlank() }
    }

    /**
     * 归属判定结果
     * @param name 规范名；空字符串表示「未确认」（宁缺勿错）
     * @param confidence 0-1，仅作提示，不作为是否采用的唯一依据
     * @param evidence 模型抄回的原文依据句（可能为空）
     * @param reason 判定过程说明，展示给用户看「为什么归到这里」
     */
    data class Match(
        val name: String = "",
        val confidence: Double = 0.0,
        val evidence: String = "",
        val reason: String = "",
        /** 本次是否还有其它被提到的项目（一次会议可能跨项目） */
        val others: List<String> = emptyList(),
    ) {
        val confirmed: Boolean get() = name.isNotBlank()
    }

    // ==================== 名称归一化 ====================

    /** 参与比较前必须抹平的差异：空白、全半角、繁简、常见后缀 */
    private val TRAILING_SUFFIX = listOf("项目组", "项目部", "项目", "工程", "楼盘", "小区", "案场")

    // ==================== 缓存 ====================
    //
    // 一次分析里 norm() 会被调用几百次（每个档案的每个别名/线索词都要归一化，
    // 每条待办又要再判一次归属），而它每次都要跑一遍繁简转换 + 正则替换。
    // 这里缓存短词面（项目名、别名、线索词都短），长文本（转写稿）不缓存，
    // 避免把一条几万字的记录留在内存里。

    /** 短词面归一化缓存上限；超过就整体丢弃重来（比维护 LRU 便宜且够用） */
    private const val NORM_CACHE_MAX = 512

    /** 参与缓存的词面最大长度，超过视为长文本不入缓存 */
    private const val NORM_CACHE_MAX_LEN = 64

    private val normCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 档案缓存：一次分析里 load() 会被反复调用，避免每次都查库 */
    @Volatile
    private var profileCache: List<Profile>? = null

    /** 档案被改过（新建/改名/学习纠正）必须清缓存，否则下次判定还用旧档案 */
    private fun invalidate() {
        profileCache = null
        normCache.clear()
    }

    /**
     * 归一化用于**比较**，不用于落库。
     *
     * 「城市之光 」「城市之光项目」「城市之光工程」应被视为同一个名字；
     * 但落库仍然写规范名，避免把一个正经项目名改坏。
     */
    fun norm(context: Context?, raw: String): String {
        if (raw.isEmpty()) return ""
        val cacheable = context != null && raw.length <= NORM_CACHE_MAX_LEN
        if (cacheable) normCache[raw]?.let { return it }
        val normalized = normUncached(context, raw)
        if (cacheable) {
            if (normCache.size >= NORM_CACHE_MAX) normCache.clear()
            normCache[raw] = normalized
        }
        return normalized
    }

    private fun normUncached(context: Context?, raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        // 全角→半角（只处理常见区间，不做全量转换）
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when {
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    ch == '\u3000' -> ' '
                    else -> ch
                }
            )
        }
        s = sb.toString()
        // 纯简体文本跳过逐字映射（大表遍历是这里唯一的开销大头）
        if (context != null && ZhNormalizer.hasTraditional(context, s)) {
            s = ZhNormalizer.toSimplified(context, s)
        }
        // 去掉所有空白与常见分隔噪音
        s = s.replace(Regex("[\\s·・\\-—_()（）\\[\\]【】]"), "")
        // 去尾缀：只在长度足够时才去，避免「XX项目」本身就是全名时被削成空
        for (suf in TRAILING_SUFFIX) {
            if (s.length > suf.length + 1 && s.endsWith(suf)) {
                s = s.substring(0, s.length - suf.length)
                break
            }
        }
        return s.lowercase()
    }

    // ==================== 档案读写 ====================

    /**
     * 读取全部档案。
     *
     * 表为空时用历史会话的项目名自举一次：这些名字虽然可能是模型产出的碎片，
     * 但至少是用户见过的、认得的名字，作为起点比空表好。
     * 自举只做一次（K_PROJECT_SEEDED 标记），之后以档案表为唯一事实来源。
     */
    fun load(context: Context): List<Profile> {
        profileCache?.let { return it }
        ensureSeeded(context)
        val list = Repo.allProjectProfiles(context)
            .map { Profile(it.name, it.aliases, it.keywords, it.note) }
        profileCache = list
        return list
    }

    /**
     * 首次建档案：把历史项目名导入，并顺手治好存量碎片
     *
     * 这一步对老用户尤其重要——原来的项目名是模型历次输出的回声，
     * 同一个项目很可能已经裂成「城市之光」「城市之光项目」「城市之光 」三条，
     * 每条各挂一半记录，任何统计都对不上。这里按归一化后的名字分组：
     * 记录最多的写法当规范名，其余并入并把旧写法记成别名，从此模型写哪种都能认到同一条。
     */
    private fun ensureSeeded(context: Context) {
        if (Repo.getBool(context, Repo.K_PROJECT_SEEDED, false)) return
        runCatching {
            val summaries = Repo.projectSummaries(context, 500)

            // 1) 分组：归一化后相同即视为同一个项目
            val groups = LinkedHashMap<String, MutableList<Repo.ProjectSummary>>()
            for (s in summaries) {
                if (s.name.isBlank()) continue
                val key = norm(context, s.name)
                if (key.isEmpty()) continue
                groups.getOrPut(key) { mutableListOf() }.add(s)
            }

            var merged = 0
            val profiles = ArrayList<Profile>()
            for (g in groups.values) {
                // 记录最多的写法最可能是用户真正在用的名字
                val canonical = g.maxByOrNull { it.count }?.name ?: continue
                val aliases = g.map { it.name }.filter { it != canonical }.distinct()
                if (aliases.isNotEmpty()) {
                    // 会话、待办一起迁到规范名下，避免「改了一半」
                    for (a in aliases) {
                        Repo.renameProjectEverywhere(context, a, canonical)
                        merged++
                    }
                    Log.i(TAG, "项目碎片归并：${aliases.joinToString("、")} → $canonical")
                }
                profiles.add(Profile(canonical, aliases))
            }
            for (p in profiles) Repo.insertProjectProfile(context, profileRow(p))

            // 老数据的待办没有 project，用所属会话兜底
            Repo.backfillTodoProjects(context)
            Repo.setBool(context, Repo.K_PROJECT_SEEDED, true)
            invalidate()   // 自举写入的档案要让 load() 重新读，不能用旧缓存
            if (profiles.isNotEmpty()) {
                Log.i(TAG, "项目档案建立：${profiles.size} 个，归并同义名 $merged 条")
            }
        }.onFailure { Log.w(TAG, "项目档案自举失败：${it.message}") }
    }

    private fun profileRow(p: Profile) = Repo.ProjectProfileRow(
        name = p.name,
        aliases = p.aliases,
        keywords = p.keywords,
        note = p.note,
    )

    fun save(context: Context, p: Profile) {
        if (p.name.isBlank()) return
        Repo.insertProjectProfile(context, profileRow(p))
        invalidate()
    }

    /**
     * 确保档案存在（已存在则原样不动）
     *
     * 手动归入某个项目时用这个，**不要**直接用 [save]：save 是整行覆盖，
     * 用一个只有名字的空 Profile 去 save，会把该档案已积累的别名与线索词抹掉，
     * 项目判定立刻退化回「只能靠名字精确匹配」。
     */
    fun ensure(context: Context, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (Repo.getProjectProfile(context, n) != null) return
        save(context, Profile(n))
    }

    /** 新建/重命名：把旧名作为别名保留，历史记录一并迁移 */
    fun rename(context: Context, oldName: String, newName: String) {
        val to = newName.trim()
        if (to.isEmpty() || to == oldName) return
        val old = Repo.getProjectProfile(context, oldName)
        val aliases = (old?.aliases ?: emptyList()).toMutableList()
        if (oldName.isNotBlank() && !aliases.contains(oldName)) aliases.add(oldName)
        save(
            context,
            Profile(
                name = to,
                aliases = aliases.distinct(),
                keywords = old?.keywords ?: emptyList(),
                note = old?.note ?: "",
            ),
        )
        if (oldName.isNotBlank()) {
            Repo.renameProjectEverywhere(context, oldName, to)
            Repo.deleteProjectProfile(context, oldName)
        }
        invalidate()
    }

    // ==================== 候选预筛 ====================

    /**
     * 从原文里预筛候选项目
     *
     * 这一步解决的是「清单太长导致模型乱选」：与本次无关的项目根本不该出现在提示词里。
     * 打分只看词面命中（确定、可解释），不做语义猜测——
     * 语义判断交给后面的大模型，这里只负责把范围缩小。
     */
    fun candidates(context: Context, text: String, max: Int = 8): List<Profile> {
        val all = load(context)
        if (all.isEmpty()) return emptyList()
        val hay = norm(context, text)
        if (hay.isEmpty()) return all.take(max)

        val scored = ArrayList<Pair<Profile, Int>>()
        for (p in all) {
            var score = 0
            for (s in p.surfaces) {
                val n = norm(context, s)
                if (n.length < 2) continue
                if (hay.contains(n)) {
                    // 名字越长越具体，命中越可信
                    score = maxOf(score, if (n == hay) 100 else 60 + minOf(n.length * 2, 20))
                }
            }
            for (k in p.keywords) {
                val n = norm(context, k)
                if (n.length >= 2 && hay.contains(n)) score += 12
            }
            if (score > 0) scored.add(p to score)
        }

        val hits = scored.sortedByDescending { it.second }
        val picked = ArrayList<Profile>()
        for (h in hits) {
            if (picked.size >= max) break
            picked.add(h.first)
        }
        // 命中太少时补最近的活跃项目：模型需要一个「大概率范围」作为参照，
        // 但提示词里会明确写「没有证据就留空」，不会因为出现了就硬选
        if (picked.size < 2) {
            val fill = Repo.recentProjects(context, max - picked.size)
            for (name in fill) {
                if (picked.size >= max) break
                if (picked.any { it.name == name }) continue
                all.firstOrNull { it.name == name }?.let { picked.add(it) }
            }
        }
        // 补最近记录标题，作为「这个项目平时聊什么」的语义参照
        return picked.map { p ->
            p.copy(recentTitles = Repo.recentTitlesOfProject(context, p.name, 3))
        }
    }

    /**
     * 生成给模型的候选项目说明块
     *
     * 每条都带别名与关键词，模型才能判断「原文提到的是哪一个」，
     * 而不是在「城市之光 / 城市之光二期」之间掷硬币。
     */
    fun hintBlock(cands: List<Profile>): String {
        if (cands.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("候选项目（project 只能从这里选，或留空）：\n")
        for (p in cands) {
            sb.append("- ").append(p.name)
            if (p.aliases.isNotEmpty()) sb.append("｜别名：").append(p.aliases.joinToString("、"))
            if (p.keywords.isNotEmpty()) sb.append("｜线索词：").append(p.keywords.joinToString("、"))
            if (p.note.isNotBlank()) sb.append("｜说明：").append(p.note)
            sb.append('\n')
            if (p.recentTitles.isNotEmpty()) {
                sb.append("  平时聊：").append(p.recentTitles.joinToString("；")).append('\n')
            }
        }
        return sb.toString()
    }

    // ==================== 强校验 ====================

    /**
     * 把模型给的名字落到档案上
     *
     * 顺序：精确 → 归一化 → 别名 → 唯一包含。
     * 全部落不上就返回未确认——**这一步是「脏项目不再新增」的闸门**。
     * 模型给出「城市之光项目」而档案里叫「城市之光」时，这里会把它纠正成规范名；
     * 给出一个档案里完全没有的名字时，宁可留空让用户手动指定，也不新建。
     */
    fun resolve(context: Context, raw: String, cands: List<Profile>): Match {
        val name = raw.trim()
        if (name.isEmpty()) return Match()
        val pool = if (cands.isNotEmpty()) cands else load(context)
        if (pool.isEmpty()) return Match()

        // 1) 精确
        pool.firstOrNull { it.name == name }?.let {
            return Match(it.name, 1.0, reason = "与项目名完全一致")
        }
        // 2) 归一化后相等（吃空格/繁简/后缀差异）
        val n = norm(context, name)
        if (n.isNotEmpty()) {
            pool.firstOrNull { norm(context, it.name) == n }?.let {
                return Match(it.name, 0.95, reason = "名称归一化后一致（原写作「$name」）")
            }
            pool.firstOrNull { p -> p.aliases.any { norm(context, it) == n } }?.let {
                return Match(it.name, 0.9, reason = "命中别名「$name」")
            }
        }
        // 3) 唯一包含：模型多写了限定词，或原文用简称
        if (n.length >= 2) {
            val contains = pool.filter { p ->
                p.surfaces.any { s ->
                    val ns = norm(context, s)
                    ns.length >= 2 && (ns.contains(n) || n.contains(ns))
                }
            }.distinctBy { it.name }
            if (contains.size == 1) {
                return Match(contains[0].name, 0.8, reason = "名称包含匹配（原写作「$name」）")
            }
            if (contains.size > 1) {
                // 多个都像 → 不猜
                return Match(
                    confidence = 0.0,
                    reason = "「$name」同时像 " + contains.take(3).joinToString("、") { it.name } + "，已留空待确认",
                )
            }
        }
        return Match(confidence = 0.0, reason = "「$name」不在项目档案中，已留空（可在详情页手动指定）")
    }

    /**
     * 判定「模型抄回来的依据」是否真的在原文里
     *
     * 只做宽松校验：取依据中 6 字以上的片段看是否出现。
     * 方言归一化会改字，所以不能要求逐字相等；但**完全找不到**说明模型在编，
     * 这时候不能再用它的归属结论。
     */
    fun evidenceInText(context: Context, evidence: String, text: String): Boolean {
        val e = norm(context, evidence)
        val t = norm(context, text)
        if (e.isEmpty() || t.isEmpty()) return false
        if (t.contains(e)) return true
        // 逐段回扣：按标点切成短语，任意一段（≥6 字）出现即算有据
        // 字符类里的 . 和 ? 不需要转义，写成转义反而会被 Kotlin 判为非法转义
        val parts = evidence.split(Regex("[，。！？；、,.!?;\n]")).map { norm(context, it) }
        return parts.any { it.length >= 6 && t.contains(it) }
    }

    /**
     * 用户纠正时学习：把「用户选定项目时，原文里到底出现了什么」记下来
     *
     * 只学短词面：从原文里找与项目名/别名相近的片段没有意义，
     * 真正有用的是把用户**这次说的叫法**记住。做法是从原文中提取连续 2-6 个汉字的
     * 高频片段里、与该记录摘要重合度最高的一个——但这太容易学脏。
     * 折中：只记录项目名本身在原文中出现的**前后 3 字上下文**作为关键词候选，
     * 由用户在项目详情页确认；不自动无限扩张词表。
     */
    fun learnFromCorrection(context: Context, project: String, text: String) {
        if (project.isBlank() || text.isBlank()) return
        val p = Repo.getProjectProfile(context, project) ?: return
        val n = norm(context, project)
        if (n.length < 2) return
        val plain = norm(context, text)
        val idx = plain.indexOf(n)
        if (idx < 0) return
        // 记下项目名前后各 2 字，形成「XX城市之光YY」式线索词；长度不足则跳过
        val start = maxOf(0, idx - 2)
        val end = minOf(plain.length, idx + n.length + 2)
        val ctxWord = plain.substring(start, end)
        if (ctxWord.length <= n.length) return                      // 前后无字，学不到东西
        if (p.keywords.any { norm(context, it) == ctxWord }) return  // 已有
        val kws = (p.keywords + ctxWord).distinct().take(12)         // 词表有上限，避免膨胀
        save(context, Profile(p.name, p.aliases, kws, p.note))
    }

    // ==================== 展示辅助 ====================

    /** 供详情页展示的一句话归属说明 */
    fun describe(m: Match): String {
        if (!m.confirmed) return m.reason.ifBlank { "未归属项目" }
        val sb = StringBuilder("归入「").append(m.name).append("」")
        if (m.reason.isNotBlank()) sb.append("（").append(m.reason).append("）")
        if (m.evidence.isNotBlank()) sb.append("：").append(m.evidence.take(40))
        if (m.others.isNotEmpty()) sb.append("；本次还提到 ").append(m.others.joinToString("、"))
        return sb.toString()
    }
}
