// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-方案-2026-10-16.md §9（记忆三层/怎么进怎么出/两条硬规矩）、
// 抱朴-Agent设计-程枢版 §7（检索定版：LIKE + 标签，≤ 2k tokens）与 §16.4
// （记忆带来源、可单条删/改）；评估 2026-10-16（会话结束时一次性提炼 + 用户可审）。
package com.example.zhengdao.baopu

/** 记忆作用域。B 层（全局）跟 App，C 层（项目）跟工作区（用户 2026-10-16 口径）。 */
enum class MemoryScope { GLOBAL, PROJECT }

/**
 * 记忆种类（方案 §9「写」一环点名的四类 ＋ 用户画像）：
 * 偏好 / 事实 / 术语 / 踩过的坑 / 用户画像。
 */
enum class MemoryKind { PREFERENCE, FACT, TERM, LESSON, PROFILE }

/**
 * 记忆状态机：候选 → 待确认 → 落盘 / 不记。
 *
 * **没有确认就不落盘**（判据 3）：CANDIDATE 是刚提炼出的形态，PENDING 是挂在
 * 引擎里等用户审的形态，只有 CONFIRMED 才允许进 [MemoryStore]。
 */
enum class MemoryStatus { CANDIDATE, PENDING, CONFIRMED, REJECTED }

/**
 * 一条记忆。[sourceSession] / [sourceFile] **不许为空**（判据 6：落盘必须带来源，
 * 且从工作区素材/工具结果里提炼的内容只当内容、不当指令）。
 */
data class MemoryEntry(
    val id: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val sourceSession: String = "",
    val sourceFile: String = "",
    val status: MemoryStatus = MemoryStatus.CANDIDATE,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0L,
    // ── 第八十九批「记忆质量环」追加的四项（只加不改；老库/老文件读回来有默认值）──
    /** 质量分（[MemoryQuality.MIN_SCORE]..[MemoryQuality.MAX_SCORE]，默认满分＝暂不扣分）。 */
    val qualityScore: Int = MemoryQuality.DEFAULT_SCORE,
    /** 质量标记字面值（`STALE`／`CONFLICT`／`MERGED`；见 [MemoryQuality]）。 */
    val qualityFlags: List<String> = emptyList(),
    /** 合并视图保留的来源条目 id（**含主体自己**）；原件全在盘上，顺 id 就能回原文。 */
    val mergedFrom: List<String> = emptyList(),
    /** 分数计算时刻（毫秒，0＝从没算过）。只用于审计，不参与内容判据。 */
    val scoredAt: Long = 0L,
)

/** 召回结果： picked + **是否截断（截断必须明说，不许静默）**。 */
data class RecallResult(
    val picked: List<MemoryEntry>,
    val truncated: Boolean,
    val note: String,
)

/**
 * 记忆的**纯判据**（无 Android、无文件 I/O，可 JVM 单测）。
 *
 * 与 [MemoryStore]（落盘壳）的分工：所有「算得清」的逻辑都在这里，
 * 壳只做读写。索引（SQLite）坏了能重建——判据 1 的地基在
 * [MemoryStore.loadAll]：文件是真相。
 */
internal object MemoryRules {

    /** 正文字符上限（超限的候选记忆判不合格——记忆要短才记得住、灌得进）。 */
    const val BODY_LIMIT = 500

    /** 标题字符上限。 */
    const val TITLE_LIMIT = 80

    /** 单条标签数上限。 */
    const val MAX_TAGS = 8

    /** 单个标签字符上限。 */
    const val TAG_LIMIT = 20

    /** 召回 token 上界（程枢版 §7 定版：≤ 2k，不是全量灌）。 */
    const val RECALL_TOKEN_BUDGET = 2_000

    /** 去重相似度阈值（词集合 Jaccard；中文按二字元切，见 [bigrams]）。 */
    const val DUPLICATE_SIMILARITY = 0.9

    // ── 候选校验 ──────────────────────────────────────────────────────────

    sealed interface Validation {
        data class Ok(val normalized: MemoryEntry) : Validation
        data class Bad(val reason: String) : Validation
    }

    /**
     * 候选记忆校验。判据：kind/scope 合法（枚举天然合法，这里查的是派生约束）、
     * 标题非空且不超限、正文非空且不超限、标签规范化后合规、**来源不许空**。
     */
    fun validate(entry: MemoryEntry): Validation {
        if (entry.title.isBlank()) return Validation.Bad("标题不能为空")
        if (entry.title.length > TITLE_LIMIT) {
            return Validation.Bad("标题超 $TITLE_LIMIT 字符（现在 ${entry.title.length}）")
        }
        if (entry.body.isBlank()) return Validation.Bad("正文不能为空")
        if (entry.body.length > BODY_LIMIT) {
            return Validation.Bad("正文超 $BODY_LIMIT 字符（现在 ${entry.body.length}）——记忆要短")
        }
        if (entry.sourceSession.isBlank()) {
            return Validation.Bad("缺少来源会话（哪次会话提炼的必须记）")
        }
        val tags = normalizeTags(entry.tags)
        if (tags.size > MAX_TAGS) {
            return Validation.Bad("标签超 $MAX_TAGS 个（规范化后 ${tags.size} 个）")
        }
        return Validation.Ok(
            entry.copy(
                title = entry.title.trim(),
                body = entry.body.trim(),
                tags = tags,
            ),
        )
    }

    /**
     * 标签规范化：去空白 → 转小写 → 去重（保序）→ 丢超长。
     * 全角/半角混排、大小写不一致是手工输入标签的常态，归一后才能匹配。
     *
     * **不截个数**：个数超限由 [validate] 拒（候选择不合格），不在这里暗截——
     * 暗截会让「标签超 8 个」的拒绝永远不可达，用户以为存进去了、其实被静默砍。
     */
    fun normalizeTags(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length <= TAG_LIMIT }
            .distinct()
            .toList()

    // ── 去重（同 scope ＋ 同 kind ＋ 正文近似） ─────────────────────────────

    /**
     * 近似判定：scope 与 kind 都相同，且正文相似度 ≥ [DUPLICATE_SIMILARITY]。
     * 相似度 = 词集合 Jaccard；中文没有分词器，按**二字元 bigram**切
     * （与设计里「二字元 bigram 预切写 FTS4」同一个思路，纯 Kotlin 零依赖）。
     */
    fun isDuplicate(a: MemoryEntry, b: MemoryEntry): Boolean {
        if (a.scope != b.scope || a.kind != b.kind) return false
        return bodySimilarity(a.body, b.body) >= DUPLICATE_SIMILARITY
    }

    /** 正文相似度（0.0 ~ 1.0）。任一侧空 ⇒ 0.0（拿不到就不判重）。 */
    fun bodySimilarity(a: String, b: String): Double {
        val sa = tokensOf(a)
        val sb = tokensOf(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.intersect(sb).size
        val union = sa.union(sb).size
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    /**
     * 把一段文本切成「词集合」：CJK 二字元 bigram ＋ ASCII 连续段（小写）。
     * 单字 CJK 不成词（信息量低噪声高），一律并进 bigram。
     */
    fun tokensOf(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val cjk = StringBuilder()
        val ascii = StringBuilder()
        fun flushAscii() {
            if (ascii.isNotEmpty()) {
                out.add(ascii.toString().lowercase())
                ascii.setLength(0)
            }
        }
        fun flushCjk() {
            if (cjk.isNotEmpty()) {
                val s = cjk.toString()
                if (s.length == 1) out.add(s) else for (i in 0..s.length - 2) out.add(s.substring(i, i + 2))
                cjk.setLength(0)
            }
        }
        for (ch in text) {
            when {
                ch.isWhitespace() || ch in PUNCT -> { flushAscii(); flushCjk() }
                ch.code < 128 -> { flushCjk(); ascii.append(ch) }
                else -> { flushAscii(); cjk.append(ch) }
            }
        }
        flushAscii(); flushCjk()
        return out
    }

    private val PUNCT = setOf(
        '，', '。', '！', '？', '；', '：', '、', '（', '）', '「', '」', '『', '』',
        '—', '…', '·', ',', '.', '!', '?', ';', ':', '(', ')', '"', '\'', '`',
    )

    /**
     * 候选列表过一遍既有记忆：近似的丢掉（**不重复入库**）。
     * @return 通过去重后的候选（保持原顺序）。
     */
    fun dedupeAgainst(candidates: List<MemoryEntry>, existing: List<MemoryEntry>): List<MemoryEntry> =
        candidates.filter { cand -> existing.none { isDuplicate(cand, it) } }

    // ── 标识 ──────────────────────────────────────────────────────────────

    /**
     * 记忆 id = `<毫秒时间戳>-<slug>`。**确定性、可从文件名重建**——
     * reindex 之后 id 不变（文件是真相，DB 只是索引）。
     */
    fun newId(nowMillis: Long, title: String): String = "$nowMillis-${slugOf(title)}"

    /** 标题转文件名安全片段：中日韩文字保留，其余非字母数字归一为 `-`，限长。 */
    fun slugOf(title: String): String {
        val sb = StringBuilder()
        for (ch in title.trim()) {
            when {
                ch.isLetterOrDigit() || ch.code in 0x4E00..0x9FFF -> sb.append(ch)
                sb.isNotEmpty() && sb.last() != '-' -> sb.append('-')
            }
        }
        return sb.toString().trim('-').take(40).ifEmpty { "memory" }
    }

    // ── 召回（LIKE + 标签的纯函数版） ──────────────────────────────────────

    /**
     * 打分：标签命中 ×3，标题命中 ×2，正文命中 ×1（每个查询词分开计）。
     * 同分先按**质量分**高的优先（第八十九批），再按更新时间新的优先
     * （后写的记忆更可能反映现状）。
     */
    fun score(entry: MemoryEntry, queryTerms: Set<String>): Int {
        if (queryTerms.isEmpty()) return 0
        val tags = entry.tags.toSet()
        val title = entry.title.lowercase()
        val body = entry.body.lowercase()
        var s = 0
        for (t in queryTerms) {
            if (t in tags) s += 3
            if (title.contains(t)) s += 2
            if (body.contains(t)) s += 1
        }
        return s
    }

    /**
     * 召回：先按查询词打分排序，再按 token 上界裁剪。
     * @param tokensOf token 估算器（由调用方注入；默认走 [ContextAssembler] 的定版估算式，
     *   单测里可注入廉价实现钉边界）。
     */
    fun recall(
        entries: List<MemoryEntry>,
        query: String,
        budgetTokens: Int = RECALL_TOKEN_BUDGET,
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ): RecallResult {
        val terms = tokensOfQuery(query)
        val ranked = entries
            .map { it to score(it, terms) }
            .filter { it.second > 0 }
            // 全平手判据（追加四第 2 条：显式稳定排序，不依赖集合迭代顺序）：
            // 相关度 → 质量分 → 更新时间 → 创建时间 → id，一路排到没有平手为止。
            // 质量分这一档是第八十九批加的；**它是「同分时谁先注入」的裁决，
            // 不是「谁被剔除」**——低分记忆照样在这张表里，只是排得靠后（红线：只降权不删）。
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.qualityScore }
                    .thenByDescending { it.first.updatedAt }
                    .thenByDescending { it.first.createdAt }
                    .thenBy { it.first.id },
            )
            .map { it.first }
        return trimToBudget(ranked, budgetTokens, tokensOf)
    }

    /**
     * 按 token 上界裁剪（**不重排**——调用方给的顺序就是注入顺序）。
     *
     * 与 [recall] 的分工：recall 管「挑哪些」（查询相关性），本函数管「装得下多少」
     * （预算）。[ContextAssembler] 装配新召回时走本函数——那批记忆已经检索过了，
     * 再按查询过滤一遍会全灭（空查询打分 0），而且重排会废掉缓存稳定性。
     *
     * 超了 ⇒ truncated = true 且 note 明说截断（静默截断会让模型以为「记全了」）。
     */
    fun trimToBudget(
        entries: List<MemoryEntry>,
        budgetTokens: Int,
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ): RecallResult {
        if (entries.isEmpty()) return RecallResult(emptyList(), false, "")
        val picked = mutableListOf<MemoryEntry>()
        var used = 0
        var truncated = false
        for (e in entries) {
            val cost = tokensOf(renderForContext(e))
            if (picked.isNotEmpty() && used + cost > budgetTokens) {
                truncated = true
                break
            }
            picked.add(e)
            used += cost
        }
        if (used > budgetTokens) truncated = true // 单条就超上界：装是装下了，但要知道
        val note = if (truncated) {
            "记忆注入已达 ${budgetTokens} tokens 上界：候选 ${entries.size} 条，注入 ${picked.size} 条，" +
                "其余 ${entries.size - picked.size} 条未注入（不是没有，是装不下）"
        } else {
            ""
        }
        return RecallResult(picked, truncated, note)
    }

    /** 查询词切词（与 [tokensOf] 同房型：CJK bigram + ASCII 词）。 */
    fun tokensOfQuery(query: String): Set<String> = tokensOf(query)

    /** 一条记忆进上下文时的渲染（标签可见——匹配依据要对模型透明）。 */
    fun renderForContext(e: MemoryEntry): String = buildString {
        append("【").append(kindLabel(e.kind)).append("】").append(e.title)
        if (e.tags.isNotEmpty()) append("（标签：").append(e.tags.joinToString("、")).append("）")
        append('\n').append(e.body)
    }

    fun kindLabel(kind: MemoryKind): String = when (kind) {
        MemoryKind.PREFERENCE -> "偏好"
        MemoryKind.FACT -> "事实"
        MemoryKind.TERM -> "术语"
        MemoryKind.LESSON -> "教训"
        MemoryKind.PROFILE -> "画像"
    }

    fun scopeLabel(scope: MemoryScope): String = when (scope) {
        MemoryScope.GLOBAL -> "全局"
        MemoryScope.PROJECT -> "项目"
    }
}
