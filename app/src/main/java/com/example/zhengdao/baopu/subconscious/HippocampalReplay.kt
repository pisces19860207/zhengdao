// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十八批 · 522 海马重放（机会式离线重放）的**纯判据层**——零 Android、零 IO、零时钟：
// 时刻/灭屏/忙碌全由装配层注入，JVM 单测直测（时间注入夹具）。
// 派工单：`_zd-private/docs/派工单-2026-11-16-第六百七十八批-海马重放.md`。
//
// 仿生口径（`docs/bio-analog-registry.md` 海马行）：海马「记录何时发生了什么，给情景打突显
// 标签，供回放候选」——**不改写记忆、不决定记什么忘什么、不输出结论**。重放＝把 L2 结构化
// 层里已有的条目在空闲期重新过一遍：每条经**海马情景键**锚回 L0 原消息（索引理论：海马存
// 指针，皮层存内容），产「经验条目＋合并建议」浮进「建议」页签——只出建议，一个写口都不碰。
//
// 三条硬线（判据的结构保证，不靠自觉）：
//   1. **缺 L0 指针即丢**（判据①）：候选没锚上情景键 ⇒ 不进产物；[L0Pointer] 构造期
//      拒空——没有指针的条目**根本活不成对象**，负例「无指针条目出现在结果里」结构不可能；
//   2. **只在空闲窗口跑**（判据②）：[idleVerdict] 三态闭集——前台忙碌（任务在跑）不开、
//      非夜间且未灭屏不开；夜间边界**复用 [SessionShape.atNight]**（QuietLimits 22/7，
//      不重造第二把钟点尺）；
//   3. **合并只出建议**（判据③）：[MergeSuggestion] 只有「建议」文案与两条指针，
//      没有任何「执行合并」的出口；相似度用**中文 BM25**（485 口径：结构化过滤＋BM25，
//      切词复用 [MemoryRules.tokensOf]，不引向量检索）。
//
// 给 694 留的缝（依赖声明：694 未合入 ⇒ 按其派工单约定形状写适配，合入后回归）：
// 694 海马·情景键约定「输入账行/事件，输出 {sessionId, seq, hint}，只出键不取正文」——
// 本文件只认这个形状（[HippocampusKey]），键从哪来归装配层（694 合入前由账行适配喂入）。
//
// 执行器边界（判据⑤）：本文件零调度构造、零 Runner 类——执行节奏归 676 的
// SubconsciousRunner（未合入，见装配层机会式调用点）；「一天 ≤1 次」复用
// [PatrolScheduler.dayBucket] 同一把日历尺（676/677 同款，不另立日期真源）。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.agent.QuietLimits
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.inspect.PatrolScheduler

/** 限值唯一住处（改任何一条＝改重放制度，不许调用处各写一把）。 */
internal object ReplayLimits {

    /** 一轮最多浮几条经验条目（重放是夜里静悄悄的整理，不是清单；676 ROWS_MAX 同房型）。 */
    const val ENTRIES_MAX = 3

    /** 一轮最多几条合并建议（建议要人工一条条看，多了就是刷屏）。 */
    const val MERGES_MAX = 2

    /** 候选扫描上限（L2 条目再多也只看最近的一批——重放不是全库审计）。 */
    const val SCAN_CAP = 24

    /** 锚定的词面重合下限（hint 与条目词面重合 ≥2 才算锚上；677 三重命中同口径）。 */
    const val ANCHOR_MIN_OVERLAP = 2

    /** 合并检测的最小词面重合 token 数（低于它连重合率都不算——单 token 撞上是噪声）。 */
    const val MERGE_MIN_OVERLAP = 3

    /**
     * 合并检测的重合率下限（词面重合 ÷ 较短一侧词数；485 口径 BM25 管**排序**、
     * 阈值管**准入**——只出建议、人工确认后合并，阈值略松无妨）。
     */
    const val MERGE_MIN_OVERLAP_RATIO = 0.4

    /** 经验条目里 L2 摘要的引用字数上限（一段话不是全文抄送）。 */
    const val SUMMARY_CHARS = 80

    /** 卡面原话引用的字数上限（装配层读出的 L0 原文截到这里）。 */
    const val QUOTE_CHARS = 120
}

/**
 * 海马·情景键（**694 派工单约定形状**，只读索引键）：这件事在哪一轮/哪个会话发生过。
 *
 * 只出键不取正文（694 判据⑤）：键里没有消息正文，[hint] 只是线索短语；
 * L0 指针＝[pointer]（`session:<id>#seq:<n>`），装配层拿它到消息库**只读**回原文。
 * 694 未合入 ⇒ 键由装配层按约定从账行适配喂入（见 ReplayWiring），合入后整缝交接。
 */
internal data class HippocampusKey(
    val sessionId: Long,
    val seq: Long,
    val hint: String,
) {
    /** L0 指针（能点回原消息的那一格；判据①的锚）。 */
    val pointer: String get() = "session:$sessionId#seq:$seq"
}

/** 情景键的来源缝（694 合入前由装配层按约定喂；合入后由其 Hippocampus 件供给）。 */
internal fun interface KeySource {
    fun keys(): List<HippocampusKey>
}

/**
 * L0 指针（判据①的**结构保证**）：空白构造期就炸——缺指针的产物根本构造不出来。
 */
internal data class L0Pointer(val value: String) {
    init {
        require(value.isNotBlank()) { "重放产物必须带 L0 指针（缺即丢，判据①）" }
    }
}

/**
 * 一条 L2 候选（装配层从 [com.example.zhengdao.baopu.MemoryStore] 读出的**有效**条目
 * 投影；纯层不读库）。[l0Value] 是装配层按 [replayAnchorOf] 锚上情景键后填的 L0 指针——
 * 没锚上（null）的候选在纯层**直接丢**（判据①缺即丢）；[quote] 是装配层只读回的
 * L0 原文截引（空 ＝ 没读到，不编）。
 */
internal data class ReplayCandidate(
    val memoryId: String,
    val kind: String,
    val kindLabel: String,
    val scope: String,
    val scopeLabel: String,
    val title: String,
    val body: String,
    val sourceSession: String,
    val updatedAtMs: Long,
    val l0Value: String? = null,
    val quote: String = "",
)

/**
 * 空闲窗口快照（判据②的输入，全由装配层注入；本层不读钟不读屏）。
 *
 * @param nowMs 本轮时刻
 * @param hourOfDay 钟点 0..23（null ＝ 没报——按 [SessionShape] 同款纪律：没报不算夜间）
 * @param screenOn 屏幕亮着吗（null ＝ 读不到——不据此放行，只夜间能撑开窗口）
 * @param engineBusy 有任务在跑吗（引擎 THINKING/ACTING/AWAIT_CONFIRM/COMPRESSING）
 */
internal data class IdleSnapshot(
    val nowMs: Long,
    val hourOfDay: Int?,
    val screenOn: Boolean?,
    val engineBusy: Boolean,
) {
    init {
        hourOfDay?.let { require(it in 0..23) { "钟点必须在 0..23，给的是 $it" } }
    }
}

/** 空闲窗口三态（闭集，不许开第四态——310 口径）。 */
internal enum class IdleVerdict(val cn: String) {

    /** 夜间或已灭屏，且没有任务在跑：窗口开。 */
    OPEN("空闲窗口开"),

    /** 任务在跑（前台忙碌）：这一格不开（判据②负例的靶子）。 */
    BUSY("任务在跑"),

    /** 不忙但不暗：非夜间且未灭屏（灭屏读不到也不算暗）。 */
    NOT_DARK("非夜间且未灭屏"),
    ;
}

/**
 * 空闲窗口判定（判据②本体）：先忙后暗——
 *   · engineBusy ＝ 前台忙碌 ⇒ 不开（负例「前台忙碌时跑 ⇒ 红」就拦在这）；
 *   · 夜间（[SessionShape.atNight]，QuietLimits 22/7 同一把尺）**或** 灭屏（screenOn == false）
 *     ⇒ 开；screenOn 读不到（null）不据此放行——拿"看起来正常"的默认值顶上去等于
 *     机器自己给自己批窗口（SessionShape 同款纪律）。
 */
internal fun idleVerdict(s: IdleSnapshot): IdleVerdict = when {
    s.engineBusy -> IdleVerdict.BUSY
    s.hourOfDay != null && SessionShape(hourOfDay = s.hourOfDay).atNight -> IdleVerdict.OPEN
    s.screenOn == false -> IdleVerdict.OPEN
    else -> IdleVerdict.NOT_DARK
}

/** 夜间边界的说明（进 note，让读的人知道用的是哪把尺）。 */
internal fun nightBoundary(): String =
    "${QuietLimits.NIGHT_FROM_HOUR} 点后/${QuietLimits.NIGHT_TO_HOUR} 点前"

/**
 * 锚定（海马索引功能：线索 → 键）：候选 ↔ 情景键的确定性配对。
 *   · 会话直配最硬：条目的 sourceSession 就是键的 sessionId；
 *   · 否则词面锚定：hint 与条目标题＋正文的词面重合 ≥ [ReplayLimits.ANCHOR_MIN_OVERLAP]；
 *   · 同输入同输出：会话直配 > 重合多 > sessionId 小 > seq 小。
 * 锚不上 ⇒ null（判据①：缺指针即丢——丢弃发生在进产物**之前**）。
 */
internal fun replayAnchorOf(candidate: ReplayCandidate, keys: List<HippocampusKey>): HippocampusKey? {
    if (keys.isEmpty()) return null
    val session = candidate.sourceSession.toLongOrNull()
    val cue = MemoryRules.tokensOf(candidate.title + "\n" + candidate.body)
    data class Scored(val key: HippocampusKey, val bySession: Boolean, val overlap: Int)
    val scored = keys.map { k ->
        Scored(k, session != null && k.sessionId == session, cue.intersect(MemoryRules.tokensOf(k.hint)).size)
    }.filter { it.bySession || it.overlap >= ReplayLimits.ANCHOR_MIN_OVERLAP }
    return scored.maxWithOrNull(
        compareByDescending<Scored> { it.bySession }
            .thenByDescending { it.overlap }
            .thenBy { it.key.sessionId }
            .thenBy { it.key.seq },
    )?.key
}

/** 一条经验条目（重放产物；只读、无动作——产物里没有「去做某事」的出口）。 */
internal data class ExperienceEntry(
    val id: String,
    val roundId: String,
    val l0: L0Pointer,
    val memoryId: String,
    val title: String,
    val kindLabel: String,
    val scopeLabel: String,
    /** 一段话（机械拼装自 L2 摘要＋指针——不生成、不改写，判据③）。 */
    val text: String,
    /** L0 原话引用（装配层只读回，截到 [ReplayLimits.QUOTE_CHARS]；空 ＝ 没读到）。 */
    val quote: String,
    val atMs: Long,
)

/** 一条合并建议（判据②③：只出建议，不自动合并——类型里没有执行口）。 */
internal data class MergeSuggestion(
    val id: String,
    val roundId: String,
    val l0a: L0Pointer,
    val l0b: L0Pointer,
    val memoryIdA: String,
    val memoryIdB: String,
    val titleA: String,
    val titleB: String,
    val kindLabel: String,
    val scopeLabel: String,
    /** 重合率（词面重合 ÷ 较短一侧词数，0..1；BM25 管排序、它管准入——确定性：同输入同值）。 */
    val similarity: Double,
    val atMs: Long,
)

/**
 * 「建议」页签重放类行（进程级行槽的数据源；行必带 L0 指针——判据①）。
 * 合并行另有第二条指针（[l0Second]）：建议合并的两条证据都能回原消息。
 */
internal data class ReplayRow(
    val id: String,
    val roundId: String,
    val kind: ReplayRowKind,
    val l0: L0Pointer,
    val l0Second: L0Pointer?,
    val memoryRef: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val atMs: Long,
)

/** 行的两类（闭集）。 */
internal enum class ReplayRowKind(val cn: String) {
    EXPERIENCE("经验"),
    MERGE("合并建议"),
    ;
}

/**
 * 中文 BM25（485 口径：结构化过滤之后在同一池里打分；**不引向量检索**）。
 * 切词复用 [MemoryRules.tokensOf]（CJK 二字元＋ASCII 词，全仓唯一一把切词尺）。
 * 自比归一：similarity(a,b) = score(a 当查询, b 当文档) / score(a 当查询, a 当文档)
 * ——BM25 原始分无量纲可比较性差，除以自评分得 0..1 的确定性比值（同文 1.0）。
 */
internal object ReplayBm25 {

    /** BM25 参数（485 未钉值；取教科书常用 k1=1.2、b=0.75，一处说了算）。 */
    private const val K1 = 1.2
    private const val B = 0.75

    /** BM25 打分（查询词表 × 单篇文档；df/idf 由调用方按池预聚合——纯函数可 JVM 直测）。 */
    fun score(query: List<String>, docTokens: List<String>, df: Map<String, Int>, nDocs: Int, avgLen: Double): Double {
        if (query.isEmpty() || docTokens.isEmpty() || nDocs <= 0 || avgLen <= 0.0) return 0.0
        val tf = HashMap<String, Int>()
        docTokens.forEach { t -> tf[t] = (tf[t] ?: 0) + 1 }
        val len = docTokens.size
        var sum = 0.0
        query.forEach { t ->
            val n = df[t] ?: 0
            if (n > 0) {
                val f = (tf[t] ?: 0).toDouble()
                val idf = Math.log(1.0 + (nDocs - n + 0.5) / (n + 0.5))
                sum += idf * f * (K1 + 1.0) / (f + K1 * (1.0 - B + B * len / avgLen))
            }
        }
        return sum
    }

    /**
     * 重合率（合并检测的**准入**读数）：词面重合 token 数 ÷ 较短一侧的词数，0..1——
     * 同文 1.0、重合低于 [ReplayLimits.MERGE_MIN_OVERLAP] ⇒ 0.0（单 token 撞上是噪声，不硬凑）。
     * BM25（[score]）在合并池里管**排序**，这里管**阈值**——485 口径两件事分工。
     */
    fun similarity(a: String, b: String): Double {
        val ta = MemoryRules.tokensOf(a).toList()
        val tb = MemoryRules.tokensOf(b).toList()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val overlap = ta.toSet().intersect(tb.toSet())
        if (overlap.size < ReplayLimits.MERGE_MIN_OVERLAP) return 0.0
        val shorter = minOf(ta.size, tb.size).toDouble()
        return overlap.size / shorter
    }
}

/** 一轮重放的产物（装配层照它 publish/落账/写状态；测试照它断言）。 */
internal data class ReplayRound(
    val ran: Boolean,
    val roundId: String,
    val dayKey: Long,
    val entries: List<ExperienceEntry>,
    val merges: List<MergeSuggestion>,
    val rows: List<ReplayRow>,
    val note: String,
)

/** 没跑成的那种空轮（窗口没开/今天已跑；结构上 0 产物）。 */
private fun notRun(roundId: String, dayKey: Long, why: String) = ReplayRound(
    ran = false,
    roundId = roundId,
    dayKey = dayKey,
    entries = emptyList(),
    merges = emptyList(),
    rows = emptyList(),
    note = why,
)

/** 经验条目的一段话（机械拼装：L2 摘要＋原话引用＋指针——不生成新事实，判据③）。 */
private fun entryText(c: ReplayCandidate): String = buildString {
    append("重放确认：这条")
    append(c.kindLabel)
    append("（")
    append(c.scopeLabel)
    append("）仍在档：「")
    append(c.title)
    append("」")
    val summary = c.body.take(ReplayLimits.SUMMARY_CHARS)
    if (summary.isNotBlank()) append("——").append(summary)
    if (c.quote.isNotBlank()) append("。原话：「").append(c.quote).append("」")
    append("。出处在 L0，可点开回原消息核对。")
}

/** 一对条目的合并候选（重合率管准入、BM25 管排序——485 口径的两件事分工）。 */
private data class ScoredPair(val a: ReplayCandidate, val b: ReplayCandidate, val ratio: Double, val bm25: Double)

/**
 * 合并建议检测（485 口径：**结构化过滤在前，BM25 在后**——只在同类同池的条目间判；
 * BM25 在池里给**排序**读数，重合率给**准入**阈值 [ReplayLimits.MERGE_MIN_OVERLAP_RATIO]）。
 * 确定性：BM25 分高在前 ⇒ memoryIdA 小在前；上限 [ReplayLimits.MERGES_MAX]。
 */
internal fun mergeSuggestions(candidates: List<ReplayCandidate>, roundId: String, atMs: Long): List<MergeSuggestion> {
    val pool = candidates.filter { !it.l0Value.isNullOrBlank() }
    // 池级统计（BM25 的 df/avgLen 在整个同类池上聚合一次——485「倒排＋BM25 取候选」的池口径）。
    val docTokens = pool.map { MemoryRules.tokensOf(it.title + "\n" + it.body).toList() }
    val df = HashMap<String, Int>()
    docTokens.forEach { toks -> toks.toSet().forEach { t -> df[t] = (df[t] ?: 0) + 1 } }
    val avgLen = if (docTokens.isEmpty()) 0.0 else docTokens.sumOf { it.size }.toDouble() / docTokens.size
    val scored = ArrayList<ScoredPair>()
    for (i in pool.indices) {
        for (j in i + 1 until pool.size) {
            val a = pool[i]
            val b = pool[j]
            if (a.kind != b.kind || a.scope != b.scope) continue
            val ratio = ReplayBm25.similarity(a.title + "\n" + a.body, b.title + "\n" + b.body)
            if (ratio < ReplayLimits.MERGE_MIN_OVERLAP_RATIO) continue
            val bm25 = ReplayBm25.score(docTokens[i], docTokens[j], df, docTokens.size, avgLen)
            scored.add(ScoredPair(a, b, ratio, bm25))
        }
    }
    return scored
        .sortedWith(compareByDescending<ScoredPair> { it.bm25 }.thenBy { it.a.memoryId })
        .take(ReplayLimits.MERGES_MAX)
        .map { s ->
            MergeSuggestion(
                id = "replaymerge:${s.a.memoryId}+${s.b.memoryId}",
                roundId = roundId,
                l0a = L0Pointer(s.a.l0Value!!),
                l0b = L0Pointer(s.b.l0Value!!),
                memoryIdA = s.a.memoryId,
                memoryIdB = s.b.memoryId,
                titleA = s.a.title,
                titleB = s.b.title,
                kindLabel = s.a.kindLabel,
                scopeLabel = s.a.scopeLabel,
                similarity = s.ratio,
                atMs = atMs,
            )
        }
}

/** 经验条目 ⇒ 建议页签行（行逐条映自产物；判据①的指针直接搬）。 */
private fun entryRow(e: ExperienceEntry): ReplayRow = ReplayRow(
    id = e.id,
    roundId = e.roundId,
    kind = ReplayRowKind.EXPERIENCE,
    l0 = e.l0,
    l0Second = null,
    memoryRef = e.memoryId,
    title = e.title,
    subtitle = "经验条目 · ${e.kindLabel} · L0 ${e.l0.value}",
    body = e.text,
    atMs = e.atMs,
)

/** 合并建议 ⇒ 建议页签行（两条证据指针都在行上；文案明说不自动合并）。 */
private fun mergeRow(m: MergeSuggestion): ReplayRow = ReplayRow(
    id = m.id,
    roundId = m.roundId,
    kind = ReplayRowKind.MERGE,
    l0 = m.l0a,
    l0Second = m.l0b,
    memoryRef = "${m.memoryIdA}／${m.memoryIdB}",
    title = "建议合并：「${m.titleA}」与「${m.titleB}」",
    subtitle = "合并建议 · ${m.kindLabel} · 相似 ${"%.2f".format(m.similarity)}",
    body = "这两条${m.kindLabel}（${m.scopeLabel}）讲的是同一件事（BM25 相似 " +
        "${"%.2f".format(m.similarity)}），建议人工确认后合并。本建议不自动合并、不改记忆正文；" +
        "证据：L2 ${m.memoryIdA}／${m.memoryIdB}，L0 ${m.l0a.value}／${m.l0b.value}。",
    atMs = m.atMs,
)

/**
 * 一轮海马重放（判据全在这里过）：
 *   ① 空闲窗口（判据②）：没开 ⇒ 空轮（延后不耗当日额度——676 同款，不写状态）；
 *   ② 天闸 ≤1 次/天（[PatrolScheduler.dayBucket]，676/677 同一把日历尺）；
 *   ③ 锚定与丢（判据①）：l0Value 空白的候选在此丢弃，[ExperienceEntry]/[MergeSuggestion]
 *      的 [L0Pointer] 构造期再拒一道（结构保证：无指针条目不可能出现在产物里）；
 *   ④ 经验条目 ≤ [ReplayLimits.ENTRIES_MAX] ＋ 合并建议 ≤ [ReplayLimits.MERGES_MAX]；
 *   ⑤ 行（[ReplayRow]）逐条映自产物——0 产物 ⇒ 0 行，行数与产物数一致是结构保证。
 */
internal fun runHippocampalReplay(
    candidates: List<ReplayCandidate>,
    idle: IdleSnapshot,
    lastRunDayKey: Long?,
    roundId: String,
): ReplayRound {
    val dayKey = PatrolScheduler.dayBucket(idle.nowMs)
    val verdict = idleVerdict(idle)
    if (verdict != IdleVerdict.OPEN) {
        return notRun(roundId, dayKey, "空闲窗口没开（${verdict.cn}；夜间＝${nightBoundary()}）——延后")
    }
    if (lastRunDayKey == dayKey) {
        return notRun(roundId, dayKey, "今天已重放过（一天 ≤1 次）")
    }
    val anchored = candidates
        .asSequence()
        .filter { !it.l0Value.isNullOrBlank() }
        .take(ReplayLimits.SCAN_CAP)
        .toList()
    val entries = anchored.take(ReplayLimits.ENTRIES_MAX).map { c ->
        ExperienceEntry(
            id = "replay:${c.memoryId}",
            roundId = roundId,
            l0 = L0Pointer(c.l0Value!!),
            memoryId = c.memoryId,
            title = c.title,
            kindLabel = c.kindLabel,
            scopeLabel = c.scopeLabel,
            text = entryText(c),
            quote = c.quote,
            atMs = idle.nowMs,
        )
    }
    val merges = mergeSuggestions(anchored, roundId, idle.nowMs)
    val rows = entries.map(::entryRow) + merges.map(::mergeRow)
    val note = "重放：条目 ${entries.size}、合并建议 ${merges.size}（候选锚上 ${anchored.size}/${candidates.size}）"
    return ReplayRound(
        ran = true,
        roundId = roundId,
        dayKey = dayKey,
        entries = entries,
        merges = merges,
        rows = rows,
        note = note,
    )
}
