// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十二批 · 记忆修剪与淘汰（巩固的另一半）的**纯判据层**——零 Android、零 IO、
// 零时钟：时刻/灭屏/忙碌全由装配层注入（[IdleSnapshot] 复用 678 的那一把）。
// 派工单：`_zd-private/docs/派工单-2026-11-19-第七百五十二批-记忆修剪与淘汰.md`。
//
// 与 678 的分工（派工单原话）：678 管「重放与合并**建议**」（零记忆写口，只出建议）；
// 本单管「**淘汰与退役**」——修剪是真动作，但动作的全部形态只有两样：
//   ① 冗余合并：语义近＋时间近 ⇒ 合并为一条，**保留全部来源指针**（merged_from 全集）；
//   ② 过期退役：超期且未被引用 ⇒ 标 `RETIRED`（**不物理删**——文件原地不动，
//      默认召回不带它，显式 include-retired 仍可检索且权重降）。
//
// 三条硬线（判据的结构保证，不靠自觉）：
//   1. **反向清单永不自动退役**（判据③）：自动清理白名单 [PruneLimits.PRUNE_ELIGIBLE_KINDS]
//      只含事实/术语两类——坑表（LESSON）与规矩（RULE）**不在白名单**（源码门禁钉死）；
//      偏好/画像也不在（451 口径「机器不许替人淡忘」）。白名单外的条目在判据里只可能
//      进「跳过 K」，永远进不了合并/退役产物——行为用例＋源码断言双道钉。
//   2. **丢指针的合并计划构造不出来**（判据①）：[MergePlan] 构造期 require
//      被吸收条目的 id 全在合并视图的 `mergedFrom` 里——负例「合并后追不到某条来源」
//      连对象都活不成（678 L0Pointer 同房型）。
//   3. **只标状态不碰内容**（边界）：合并/退役对条目的全部改动 = `status` ＋
//      `qualityFlags`（＋主体条的 mergedFrom/时间/用量聚合账）；标题/正文/来源/id
//      一个字不改（正文取信息量最大那条**原文**——不做语义压缩成摘要，派工单边界）。
//
// 执行器边界（判据⑤）：本文件零调度构造、零 Runner 类——只在空闲窗口跑，
// 判据复用 678 的 [idleVerdict]（同一把夜间/灭屏/忙碌尺），天闸 ≤1 次/天复用
// [PatrolScheduler.dayBucket]（676/677/678 同一把日历尺）。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryQuality
import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.subconscious.IdleSnapshot
import com.example.zhengdao.baopu.subconscious.IdleVerdict
import com.example.zhengdao.baopu.subconscious.idleVerdict
import com.example.zhengdao.baopu.subconscious.nightBoundary

/** 限值唯一住处（改任何一条＝改修剪制度，不许调用处各写一把）。 */
internal object PruneLimits {

    /**
     * 过期退役的「超期」天数（默认 90，**可配**：判据走 [runPruneRound] 的参数，
     * 装配层可以喂配置值；这里只是默认——不写死在逻辑里）。
     *
     * 与 [com.example.zhengdao.baopu.MemoryTiers.REVIEW_PERIOD_DAYS] 同取 90 天档但
     * **不是同一件事**：复核周期管「该重看一次」（读时判、零写入），本数管「超期且
     * 没被用过就退役」（真写状态）。两件事各是各，不许混写。
     */
    const val RETIRE_AFTER_DAYS = 90L

    /**
     * 「时间近」的窗口（天）：同一事实的重复录入发生在前后 [TIME_NEAR_DAYS] 天内
     * 才判冗余——时间差得远的两条近似正文可能是「同主题的两个阶段」，不并。
     */
    const val TIME_NEAR_DAYS = 7L

    /**
     * 「语义近」阈值：**复用 [MemoryRules.DUPLICATE_SIMILARITY] 同一把尺**（二字元
     * bigram Jaccard ≥0.9）——写入闸当时会拒收的那个近似度，正是存量里该合并的那个
     * 近似度。不另立第二份相似度真相。
     */
    const val MERGE_SIMILARITY = MemoryRules.DUPLICATE_SIMILARITY

    /** 一轮最多合并几组（修剪是夜里静悄悄的整理，不是大扫除）。 */
    const val MERGE_GROUPS_MAX = 8

    /** 一轮最多退役几条（超出的进「跳过 K」——下轮再继续，账上看得见）。 */
    const val RETIRES_MAX = 16

    /** 退役标记（写进 `quality_flags`；[MemoryQuality] 的三个标记之外本批只加这一个）。 */
    const val FLAG_RETIRED = "RETIRED"

    /**
     * **自动清理白名单**（判据③的唯一住处；源码门禁 PruneSourceGateTest 钉死）：
     * 只有事实/术语两类允许自动合并与自动退役。坑表（LESSON＝踩过的坑/教训）与
     * 规矩（RULE）**不在白名单**——反向清单永不自动退役，只允许人工标记；
     * 偏好/画像也不在（451 口径：偏好与规矩不设机器淡忘，画像同理）。
     */
    val PRUNE_ELIGIBLE_KINDS: Set<MemoryKind> = setOf(MemoryKind.FACT, MemoryKind.TERM)
}

/** 在不在自动清理白名单里（判据③的判定本体；一处说了算）。 */
internal fun pruneEligible(e: MemoryEntry): Boolean = e.kind in PruneLimits.PRUNE_ELIGIBLE_KINDS

/**
 * 一条合并计划（判据①的**结构保证**在这里）：[survivor] 是合并视图（复用
 * [MemoryQuality.merged] 同一房型——id 取最早立账那条，`merged_from` 记**全集**），
 * [absorbed] 是被吸收的原条目副本（只换 `status=RETIRED`＋MERGED 标记）。
 *
 * 构造期 require：被吸收的每条 id 都必须在主体的 `mergedFrom` 里——**丢一条来源
 * 的合并计划根本构造不出来**（判据①「丢一条 ⇒ 红」的落点：先红在构造期）。
 */
internal data class MergePlan(
    val roundId: String,
    val survivor: MemoryEntry,
    val absorbed: List<MemoryEntry>,
    val atMs: Long,
) {
    init {
        require(absorbed.size >= 1) { "合并计划至少要吸收一条（不然不叫合并）" }
        val ids = survivor.mergedFrom.toSet()
        val lost = absorbed.filter { it.id !in ids }
        require(lost.isEmpty()) {
            "合并计划丢了来源指针：${lost.joinToString(",") { it.id }} 不在主体的 merged_from 里（判据①）"
        }
    }

    /** 本组涉及的全部 id（主体＋被吸收；账面与断言用）。 */
    val allIds: List<String> get() = (survivor.mergedFrom + absorbed.map { it.id }).distinct().sorted()
}

/** 一条退役标记（判据②：只标状态；[entry] 是换好 `RETIRED` 的副本，id/正文/来源不动）。 */
internal data class RetireMark(
    val roundId: String,
    val entry: MemoryEntry,
    val atMs: Long,
) {
    init {
        require(entry.status == MemoryStatus.RETIRED) { "退役标记的条目必须是 RETIRED 态" }
    }

    val id: String get() = entry.id
}

/** 一次跳过的归类（闭集，不许开第四态——310 口径；跳过 K 的明细按这里聚合）。 */
internal enum class PruneSkip(val cn: String) {
    /** 反向清单/保护类：坑表、规矩、偏好、画像——永不自动清理（判据③）。 */
    REVERSE_LIST("反向清单（坑表/规矩/偏好不自动清理）"),

    /** 曾被引用过：不算死条目（判据②「未被引用」的反面）。 */
    REFERENCED("曾被引用过"),

    /** 语义近但时间差得远：可能是同主题的两个阶段，不并。 */
    TIME_FAR("语义近但时间差得远"),

    /** 到了该动但本轮限额已满：下轮继续（账上看得见）。 */
    CAPPED("本轮限额已满"),
    ;
}

/** 一轮修剪的产物（装配层照它落账/写回滚/应用；测试照它断言）。 */
internal data class PruneRound(
    val ran: Boolean,
    val roundId: String,
    val dayKey: Long,
    val merges: List<MergePlan>,
    val retires: List<RetireMark>,
    val skipped: Int,
    /** 跳过明细（「反向清单（…）2／曾被引用过 1」形态；空 ＝ 没有跳过）。 */
    val skipDetail: String,
    val note: String,
)

/** 没跑成的那种空轮（窗口没开/今天已跑；结构上 0 产物——延后不耗当日额度，676/678 同款）。 */
private fun notRun(roundId: String, dayKey: Long, why: String) = PruneRound(
    ran = false,
    roundId = roundId,
    dayKey = dayKey,
    merges = emptyList(),
    retires = emptyList(),
    skipped = 0,
    skipDetail = "",
    note = why,
)

/** 同类同 scope 的两条正文是否「语义近」（[PruneLimits.MERGE_SIMILARITY] 一把尺）。 */
private fun semanticallyNear(a: MemoryEntry, b: MemoryEntry): Boolean {
    if (a.scope != b.scope || a.kind != b.kind) return false
    return MemoryRules.bodySimilarity(a.body, b.body) >= PruneLimits.MERGE_SIMILARITY
}

/** 两条是否「时间近」（创建时刻差 ≤ 窗口；时钟由参数注入，本层不读钟）。 */
private fun temporallyNear(a: MemoryEntry, b: MemoryEntry, timeNearMillis: Long): Boolean =
    kotlin.math.abs(a.createdAt - b.createdAt) <= timeNearMillis

/**
 * 合并聚簇（确定性）：池按（scope, kind, createdAt, id）排序后，每条尝试并入
 * **簇头**（最早那条）语义近且时间近的簇——并入判据对簇头判，不对簇内逐条判
 * （与 [MemoryQuality.pass] 的聚簇同房型；主体＝簇头＝最早立账，id 稳定）。
 */
private fun clusterNearDuplicates(
    pool: List<MemoryEntry>,
    timeNearMillis: Long,
): List<List<MemoryEntry>> {
    val clusters = mutableListOf<MutableList<MemoryEntry>>()
    for (e in pool) {
        val hit = clusters.firstOrNull { head ->
            semanticallyNear(head.first(), e) && temporallyNear(head.first(), e, timeNearMillis)
        }
        if (hit != null) hit += e else clusters += mutableListOf(e)
    }
    return clusters.filter { it.size >= 2 }
}

/**
 * 一条退役副本（判据②「只标状态」的全部改动面：`status` ＋ `qualityFlags` 两位，
 * id/标题/正文/来源/时间/用量一个字不动——退役时刻的账在修剪账本里，不写进条目）。
 */
internal fun retiredCopyOf(e: MemoryEntry): MemoryEntry = e.copy(
    status = MemoryStatus.RETIRED,
    qualityFlags = (e.qualityFlags + PruneLimits.FLAG_RETIRED).distinct(),
)

/** 一条被吸收副本（主体之外的那些：退役＋MERGED 标记——顺 id 能回原文，也能查到它为什么退）。 */
private fun absorbedCopyOf(e: MemoryEntry): MemoryEntry = e.copy(
    status = MemoryStatus.RETIRED,
    qualityFlags = (e.qualityFlags + MemoryQuality.FLAG_MERGED).distinct(),
)

/**
 * 一轮记忆修剪（判据全在这里过）：
 *   ① 空闲窗口（判据⑤）：复用 678 [idleVerdict] 三态——没开 ⇒ 空轮延后不耗当日额度；
 *   ② 天闸 ≤1 次/天（[PatrolScheduler.dayBucket]，676/677/678 同一把日历尺）；
 *   ③ 白名单闸（判据③）：合并/退役的池子**先过 [pruneEligible]**——白名单外只可能进
 *      「跳过 K」（负例靶子：坑表/规矩出现在合并或退役产物里 ⇒ 红）；
 *   ④ 合并（判据①）：语义近＋时间近 ⇒ [MemoryQuality.merged] 视图（指针全集）＋
 *      被吸收条目标退役；[MergePlan] 构造期丢指针即炸；
 *   ⑤ 退役（判据②）：超期（[MemoryQuality.idleDays] 同一把闲置尺）且未被引用
 *      （useCount==0）⇒ 标 RETIRED；合并条目本轮不再退役（一件事一轮只做一样）；
 *   ⑥ 跳过 K（判据⑥）：被考虑过但因保护规则放下的，逐条归类计数。
 *
 * @param retireAfterDays 退役周期（可配；默认 [PruneLimits.RETIRE_AFTER_DAYS]）
 * @param timeNearDays 时间近窗口（可配；默认 [PruneLimits.TIME_NEAR_DAYS]）
 */
internal fun runPruneRound(
    entries: List<MemoryEntry>,
    idle: IdleSnapshot,
    lastRunDayKey: Long?,
    roundId: String,
    retireAfterDays: Long = PruneLimits.RETIRE_AFTER_DAYS,
    timeNearDays: Long = PruneLimits.TIME_NEAR_DAYS,
): PruneRound {
    val dayKey = PatrolScheduler.dayBucket(idle.nowMs)
    val verdict = idleVerdict(idle)
    if (verdict != IdleVerdict.OPEN) {
        return notRun(roundId, dayKey, "空闲窗口没开（${verdict.cn}；夜间＝${nightBoundary()}）——延后")
    }
    if (lastRunDayKey == dayKey) {
        return notRun(roundId, dayKey, "今天已修剪过（一天 ≤1 次）")
    }
    val timeNearMillis = timeNearDays * MemoryQuality.DAY_MILLIS
    val confirmed = entries.filter { it.status == MemoryStatus.CONFIRMED }
    // 白名单闸（判据③）：池子先过闸——白名单外的条目下面只可能出现在「跳过」账里。
    val eligible = confirmed
        .filter { pruneEligible(it) }
        .sortedWith(compareBy({ it.scope }, { it.kind }, { it.createdAt }, { it.id }))
    val merges = clusterNearDuplicates(eligible, timeNearMillis)
        .take(PruneLimits.MERGE_GROUPS_MAX)
        .map { cluster ->
            MergePlan(
                roundId = roundId,
                survivor = MemoryQuality.merged(cluster, idle.nowMs),
                absorbed = cluster.drop(1).map(::absorbedCopyOf),
                atMs = idle.nowMs,
            )
        }
    val handled = merges.flatMap { it.allIds }.toSet()
    val retires = eligible.asSequence()
        .filter { it.id !in handled }
        .filter { it.useCount == 0 } // 未被引用（判据②原话）
        .filter { MemoryQuality.idleDays(it, idle.nowMs) >= retireAfterDays } // 超期
        .take(PruneLimits.RETIRES_MAX)
        .map { RetireMark(roundId = roundId, entry = retiredCopyOf(it), atMs = idle.nowMs) }
        .toList()
    val skipCounts = countSkips(confirmed, merges, retires, idle.nowMs, retireAfterDays, timeNearMillis)
    val skipped = skipCounts.values.sum()
    val detail = skipCounts.entries
        .filter { it.value > 0 }
        .sortedBy { it.key.ordinal }
        .joinToString("／") { "${it.key.cn} ${it.value}" }
    val absorbedTotal = merges.sumOf { it.absorbed.size }
    return PruneRound(
        ran = true,
        roundId = roundId,
        dayKey = dayKey,
        merges = merges,
        retires = retires,
        skipped = skipped,
        skipDetail = detail,
        note = "合并 ${merges.size}（吸收 $absorbedTotal）/ 退役 ${retires.size} / 跳过 $skipped",
    )
}

/**
 * 跳过 K 的归类计数（判据⑥的明细）。口径：**被考虑过**才算跳过——
 * 被考虑＝（正文有语义近亲）或（闲置已满退役周期）；两样都不占的条目根本不进账，
 * 否则 K 会被一堆不相干的健康条目淹没。 acted 条目不进跳过账。
 *
 * 保护类条目（非白名单）只会落到 [PruneSkip.REVERSE_LIST]——这是判据③的行为面：
 * 坑表/规矩/偏好被考虑到了，结论也只会是「跳过」。逐条归类判据在 [skipReasonOf]。
 */
private fun countSkips(
    confirmed: List<MemoryEntry>,
    merges: List<MergePlan>,
    retires: List<RetireMark>,
    nowMs: Long,
    retireAfterDays: Long,
    timeNearMillis: Long,
): Map<PruneSkip, Int> {
    val actedIds = (merges.flatMap { it.allIds } + retires.map { it.id }).toSet()
    val counts = mutableMapOf<PruneSkip, Int>()
    for (e in confirmed) {
        val reason = skipReasonOf(e, confirmed, actedIds, nowMs, retireAfterDays, timeNearMillis)
        if (reason != null) counts.merge(reason, 1, Int::plus)
    }
    return counts
}

/** 一条被考虑过的条目落到哪个跳过归类；没被考虑过 ⇒ null（不进账）。 */
private fun skipReasonOf(
    e: MemoryEntry,
    confirmed: List<MemoryEntry>,
    actedIds: Set<String>,
    nowMs: Long,
    retireAfterDays: Long,
    timeNearMillis: Long,
): PruneSkip? {
    if (e.id in actedIds) return null
    val overAge = MemoryQuality.idleDays(e, nowMs) >= retireAfterDays
    val partners = confirmed.filter { o -> o.id != e.id && semanticallyNear(e, o) }
    if (!overAge && partners.isEmpty()) return null
    if (!pruneEligible(e)) return PruneSkip.REVERSE_LIST
    if (e.useCount > 0) return PruneSkip.REFERENCED
    // 白名单内、未被引用。没被合并 ⇒ 要么时间窗没满，要么本轮合并限额满了；
    // 没被退役（若超期）⇒ 只可能是退役限额满了。
    if (overAge) return PruneSkip.CAPPED
    return if (partners.any { !temporallyNear(e, it, timeNearMillis) }) PruneSkip.TIME_FAR else PruneSkip.CAPPED
}
