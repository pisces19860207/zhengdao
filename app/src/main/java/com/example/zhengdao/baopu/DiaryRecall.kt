// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

/**
 * 情景记忆（日记）的**召回与维护判据**（147 阶段②，纯函数 JVM 直测）。
 *
 * 依据：方案 v1.2 §4/§8（判据 4/5/12/14/20）＋ 派工单-147 红线。
 * 与记忆召回（MemoryInjectionBudget）同款哲学：**读时挑选、不删除**——
 * 本对象没有任何写盘接口，没选上的条目只是不注入，文件一条不少（判据 5）。
 *
 * 段序铁律（判据 4）：EPISODE 段在 MEMORY 之后、COMPRESSION 之前，
 * 同会话内只增不改（提示词缓存判据）⇒ [recall] 的排序只用条目自带字段
 * （timeToMs/id），**不读时钟**——同一批输入必须得到同一批输出。
 */
internal object DiaryRecall {

    private const val DAY_MS = 86_400_000L

    /** 月度摘要条目的 id 前缀（`digest-<yyyy-MM>`；文件落 `记忆/日记/digest-<yyyy-MM>.md`）。 */
    const val DIGEST_PREFIX = "digest-"

    /** 默认最多注入几条日记（含月度摘要；实现批可调）。 */
    const val MAX_ITEMS = 5

    /** 默认字符预算（CJK 口径下 1 token ≈ 1 字符，与记忆召回 2k 同数量级取小头）。 */
    const val MAX_CHARS = 1_000

    /** 原文条目保留窗口（天）：超过 ⇒ 浓缩进月度摘要并转归档（软删）。 */
    const val RETAIN_DAYS = 30

    /** 归档件保留窗口（天）：超过且已被月度摘要完整承载 ⇒ 清理（只删日记归档件）。 */
    const val ARCHIVE_DAYS = 180

    data class Recall(val selected: List<DiaryEntry>, val omitted: Int)

    fun isDigest(id: String): Boolean = id.startsWith(DIGEST_PREFIX)

    /**
     * 召回（判据 4/5）：月度摘要优先（它承载更早历史的压缩形态），其余按 timeToMs 新的在前；
     * 条数/字符双上限挑，放不下的**只是不注入**（omitted 计数供段尾注明）。
     *
     * 157 追加 [query]（账 §B-待做 149「记得住、找不到」）：**摘要仍恒最前**（它承载
     * 30 天前的浓缩历史，正是原始条目检索够不着的部分），其余**检索命中的排在未命中前**
     * （同会话内查询不变 ⇒ 前缀稳定）；query 为 null 或不活跃 ⇒ 与 147 行为逐字相同
     * （回归判据 J5 钉）。预算与段序一概不动。
     */
    fun recall(entries: List<DiaryEntry>, query: DiaryQuery? = null): Recall {
        val hitIds = if (query != null && query.isActive) {
            search(entries, query).map { it.id }.toSet()
        } else {
            emptySet()
        }
        val ordered = entries.sortedWith(
            compareByDescending<DiaryEntry> { isDigest(it.id) }
                .thenByDescending { it.id in hitIds }
                .thenByDescending { it.timeToMs }
                .thenBy { it.id },
        )
        var chars = 0
        val picked = mutableListOf<DiaryEntry>()
        var omitted = 0
        for (e in ordered) {
            if (picked.size >= MAX_ITEMS) {
                omitted++
                continue
            }
            val cost = renderForContext(e).length
            if (chars + cost > MAX_CHARS) {
                omitted++
                continue
            }
            chars += cost
            picked += e
        }
        return Recall(picked, omitted)
    }

    /**
     * 证据档后缀（判据 12）：**实测不带后缀**（它就是本会话真实发生的事）；
     * 摘要标「月度摘要」、推断标「（推断）」——推断不许伪装成事实。
     */
    fun evidenceSuffix(e: DiaryEntry): String = when (e.evidence) {
        DiaryEvidence.OBSERVED -> ""
        DiaryEvidence.CITED -> "（月度摘要）"
        DiaryEvidence.INFERRED -> "（推断）"
    }

    /** 回灌一行（带档标注＋产物引用——引用让模型说得出「哪来的」，核对有门）。 */
    fun renderForContext(e: DiaryEntry): String = buildString {
        append("【日记 ").append(e.date).append("】")
        if (e.title.isNotBlank()) append(e.title).append("：")
        append(e.summary)
        val suffix = evidenceSuffix(e)
        if (suffix.isNotEmpty()) append(suffix)
        if (e.artifacts.isNotEmpty()) {
            append("（产物：").append(e.artifacts.joinToString("、")).append("）")
        }
    }

    /** 归档候选（软删第一步，判据 20 的前奏）：超过保留窗口的**非摘要**条目。 */
    fun archiveCandidates(
        entries: List<DiaryEntry>,
        nowMs: Long,
        retainDays: Int = RETAIN_DAYS,
    ): List<DiaryEntry> =
        entries.filter { !isDigest(it.id) && nowMs - it.timeToMs > retainDays * DAY_MS }

    /**
     * 月度摘要（判据 14：**细节可以丢，产物引用一条不许丢**）。
     *
     * 摘要本身是 `CITED` 档——内容提炼自日记文件，不是实测；
     * sessionId = 0（它不是某个会话的流水，是浓缩物）。
     */
    fun composeDigest(month: String, entries: List<DiaryEntry>, nowMs: Long): DiaryEntry {
        val arts = DiaryRules.sanitizeArtifacts(entries.flatMap { it.artifacts })
        val titles = entries.map { it.title }.filter { it.isNotBlank() }.distinct()
        val shown = titles.take(5)
        return DiaryEntry(
            id = DIGEST_PREFIX + month,
            date = month,
            sessionId = 0L,
            timeFromMs = entries.minOfOrNull { it.timeFromMs } ?: nowMs,
            timeToMs = entries.maxOfOrNull { it.timeToMs } ?: nowMs,
            title = "月度摘要",
            summary = buildString {
                append("本月 ").append(entries.size).append(" 个会话")
                if (shown.isNotEmpty()) {
                    append("；主题：").append(shown.joinToString("、"))
                    val more = titles.size - shown.size
                    if (more > 0) append("等 ").append(more).append(" 条")
                }
            },
            artifacts = arts,
            evidence = DiaryEvidence.CITED,
            okSteps = entries.sumOf { it.okSteps },
            failedSteps = entries.sumOf { it.failedSteps },
            createdAt = nowMs,
        )
    }

    /**
     * 归档清理的**覆盖判据**（纯函数半边；判据 20 的另一半「归档满 [ARCHIVE_DAYS] 天」
     * 由 [DiaryStore] 按归档文件的时间戳把关——纯函数不读时钟，两条在存储层会合）。
     *
     * 产物引用已被同月月度摘要完整承载 ⇒ 线索不断，可删；
     * 没被承载（删了就真没了）⇒ 留着。只判日记，产物与用户文件天然不在视野里。
     */
    fun cleanupCandidates(archived: List<DiaryEntry>, digests: List<DiaryEntry>): List<DiaryEntry> =
        archived.filter { e ->
            digests.any { d -> d.date == e.date.take(7) && e.artifacts.all { it in d.artifacts } }
        }

    // ── 第一百五十七批：检索（日期范围＋关键词）──────────────────────────
    // 来历：账 §B-待做 149「记得住、找不到」——召回原本只有「摘要优先＋新鲜度」两轴，
    // 超 30 天的日记已浓缩成主题列表、预算外的近期日记也召回不了 ⇒「三周前聊过的那件事」
    // 型提问找不到。本批在召回前加一道检索：命中条目在预算内提前。
    // 边界（派工单-157 红线）：不动存储、不动 MAX_ITEMS/MAX_CHARS、纯函数不读时钟、
    // 不改段序（EPISODE 仍在 MEMORY 后 COMPRESSION 前；摘要在召回里恒最前）。

    /** 关键词上限（拉丁词＋CJK 二元组合计；超了按出现序截断——防长句把 5 条预算吃光）。 */
    const val MAX_KEYWORDS = 12

    /**
     * 检索查询（157）。
     *
     * 日期范围用 `yyyy-MM-dd` **文本**比大小——[DiaryEntry.date] 就是这个格式
     * （`TimeFormats.dayStamp` 唯一真源），不引时钟、不引时区；null 表示该侧不限，
     * `dateFrom > dateTo` 天然空结果（不特殊化，调用方传畸形区间自负）。
     */
    data class DiaryQuery(
        val dateFrom: String? = null,
        val dateTo: String? = null,
        /** 关键词一律小写形态（提取时转好）；匹配对大小写不敏感。 */
        val keywords: List<String> = emptyList(),
    ) {
        /** 有没有检索意图。空查询 ⇒ 不检索，挑选行为与 147 逐字相同（负例 J5 钉）。 */
        val isActive: Boolean
            get() = dateFrom != null || dateTo != null || keywords.isNotEmpty()
    }

    /**
     * 从当前轮输入提取查询（157；**纯函数、不读时钟**）。
     *
     * 日期只认**显式** `yyyy-MM-dd`（`\d` 默认只匹配 ASCII 数字，全角数字不混入）；
     * 一个 ⇒ 当天；多个 ⇒ 最早/最晚为闭区间。「上周」「三天前」这类相对时间**不猜**
     * （猜年份要读时钟，会打破「同输入同输出」——那是前缀稳定与测试确定性的前提）。
     * 关键词两形态，**拉丁词排在二元组前**（更具体），合计截断到 [MAX_KEYWORDS]：
     * ① 拉丁/数字词：≥2 字符、**至少含一个字母**（纯数字词是噪音）、内部允许 `.`/`_`、
     *    去尾部 `.`/`_`、转小写；
     * ② CJK 二元组：每个连续中文串的 2 字滑窗（不依赖分词器，确定性可测）。
     */
    fun queryFromInput(text: String, maxKeywords: Int = MAX_KEYWORDS): DiaryQuery {
        if (text.isBlank()) return DiaryQuery()
        val dates = Regex("\\d{4}-\\d{2}-\\d{2}").findAll(text).map { it.value }.distinct().toList()
        val keywords = mutableListOf<String>()
        for (m in Regex("[A-Za-z0-9][A-Za-z0-9._]*").findAll(text)) {
            val w = m.value.trimEnd('.', '_').lowercase()
            if (w.length >= 2 && w.any { it.isLetter() } && w !in keywords) keywords += w
        }
        for (run in Regex("[\\u4e00-\\u9fff]+").findAll(text)) {
            val s = run.value
            for (i in 0..s.length - 2) {
                val bi = s.substring(i, i + 2)
                if (bi !in keywords) keywords += bi
            }
        }
        return DiaryQuery(
            dateFrom = dates.minOrNull(),
            dateTo = dates.maxOrNull(),
            keywords = keywords.take(maxKeywords),
        )
    }

    /**
     * 检索（157；纯函数、**零写接口**——读时挑选不删除，同 [recall] 哲学）。
     *
     * 日期范围**闭区间**过滤；关键词在「标题＋摘要＋产物引用」合文里查（大小写不敏感），
     * 命中数＝**distinct 关键词计数**（同一词在文中出现多次只算 1 次——防复读刷分）。
     * 结果按 命中数降 → timeToMs 降 → id 升；**输入换序同输出**（不读时钟、不用输入序）。
     *
     * 注意：月度摘要的 [DiaryEntry.date] 是 `yyyy-MM`（月粒度）⇒ 日期范围天然圈不住它——
     * 不是缺陷：召回里摘要恒最前（见 [recall]），检索不到摘要不影响它被注入。
     */
    fun search(entries: List<DiaryEntry>, query: DiaryQuery): List<DiaryEntry> {
        if (!query.isActive) return emptyList()
        val inRange = entries.filter { e ->
            (query.dateFrom == null || e.date >= query.dateFrom) &&
                (query.dateTo == null || e.date <= query.dateTo)
        }
        val kw = query.keywords
        if (kw.isEmpty()) {
            return inRange.sortedWith(compareByDescending<DiaryEntry> { it.timeToMs }.thenBy { it.id })
        }
        return inRange
            .map { e -> e to scoreOf(e, kw) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<DiaryEntry, Int>> { it.second }
                    .thenByDescending { it.first.timeToMs }
                    .thenBy { it.first.id },
            )
            .map { it.first }
    }

    /** 命中计数：distinct 关键词在「标题＋摘要＋产物」合文里出现几次（大小写不敏感）。 */
    fun scoreOf(e: DiaryEntry, keywords: List<String>): Int {
        if (keywords.isEmpty()) return 0
        val hay = (listOf(e.title, e.summary) + e.artifacts).joinToString("\n").lowercase()
        return keywords.count { hay.contains(it) }
    }
}
