// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §2.5（2026-10-25 用户：「不然太长，翻半天都不一定能翻到」）：
// **记录页按对象分组**的纯判据层——分组、排序、筛选、分页全在这里，
// `DecisionCenterScreen` 只按 [DecisionFold] 渲染，不自己算。
//
// 六条规则（派工单 §2.5 + §2.7，逐条可在 JVM 里断言）：
//   ① 折叠态只显示组卡头（组内行只在 [DecisionGroupCard.rows] 里，展开才有）；
//   ② 排序：有可撤销的组优先，其次按组内最近时间；空组不出现；
//   ③ 组内行仍可单条跳原上下文、单条撤销（撤销后计数由重算自然更新）；
//   ④ 筛选 chips：全部／今天／本会话／仅可撤销；先筛再分组；
//   ⑤ 组数上限 6：第 7 组起折进「更多（N 组）」；
//   ⑥ 单页渲染行数上限 50（组间共用一个预算），超出的进 [DecisionGroupCard.hiddenByPage]。
//
// 纪律：零 Android、零 Compose、零 IO——输入记录列表，输出卡片模型。
package com.example.zhengdao.baopu

import java.time.Instant
import java.time.ZoneId

/** 历史 tab 的筛选 chips（§2.5 规则④）。 */
internal enum class DecisionFilter {
    ALL,
    TODAY,
    THIS_SESSION,
    REVERSIBLE,
}

/**
 * 一张组卡（折叠态只有组头：组名＋[count]＋[latestMs]＋[reversibleCount]）。
 *
 * @param all 组内全部记录（过滤后，新→旧；撤销后重算，计数自然跟上）
 * @param rows 本次要渲染的行——**没展开就是空表**（规则①）
 * @param hiddenByPage 组内没渲染出来的条数＝[all] 减去 [rows]（滚动到底再加载）
 * @param reversibleCount 组内**全部**可撤销条数（不是渲染出来的那些）
 */
internal data class DecisionGroupCard(
    val group: DecisionGroup,
    val all: List<DecisionRecord>,
    val rows: List<DecisionRecord>,
    val hiddenByPage: Int,
    val latestMs: Long,
    val reversibleCount: Int,
) {
    /** 组头条数（规则：组头计数必须等于组内真实条数）。 */
    val count: Int get() = all.size
}

/**
 * 一次折叠的结果。
 *
 * @param cards 要渲染的组卡（含待抽检组；≤ [MAX_GROUPS] 张）
 * @param overflowGroups 被折进「更多（N 组）」的组卡（默认不展开渲染）
 * @param overflowRecords 那些组里一共多少条
 */
internal data class DecisionFold(
    val cards: List<DecisionGroupCard>,
    val overflowGroups: List<DecisionGroupCard>,
    val overflowRecords: Int,
) {
    /** 本次一共渲染多少行（判据：≤ [PAGE_LIMIT]）。 */
    val renderedRowCount: Int get() = cards.sumOf { it.rows.size }

    /** 「更多（N 组）」要不要出现。 */
    val hasOverflow: Boolean get() = overflowGroups.isNotEmpty()
}

/** 组数上限（§2.5 规则⑤）。 */
internal const val MAX_GROUPS = 6

/** 单页渲染行数上限（§2.5 规则⑥；组间共用一个预算）。 */
internal const val PAGE_LIMIT = 50

/**
 * 这条记录在某个筛选下要不要出现。
 *
 * @param nowMs 现刻（「今天」按**本地**日历日算，不按 24 小时窗口——用户说的「今天」是日期）
 */
internal fun decisionRecordMatches(
    r: DecisionRecord,
    filter: DecisionFilter,
    nowMs: Long,
    sessionId: Long,
): Boolean = when (filter) {
    DecisionFilter.ALL -> true
    DecisionFilter.TODAY -> localDayOf(r.atMs) == localDayOf(nowMs)
    DecisionFilter.THIS_SESSION -> r.sessionId == sessionId && sessionId >= 0
    DecisionFilter.REVERSIBLE -> r.reversible
}

/** 本地日历日（`2026-10-26`；纯函数，测试可换时区不信环境）。 */
internal fun localDayOf(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().toString()

/**
 * 折叠成组卡（§2.5 全部规则在这一个函数里）。
 *
 * @param records 已读出来的记录（任意序；本函数自己排序）
 * @param pendingIds 「待抽检」组要放的记录 id（[DecisionSampling.pending] 的结果）
 * @param expanded 展开了哪几组（折叠态传空集 ⇒ 一张组卡里都没有行）
 */
internal fun foldDecisionRecords(
    records: List<DecisionRecord>,
    filter: DecisionFilter = DecisionFilter.ALL,
    nowMs: Long = 0L,
    sessionId: Long = -1L,
    pendingIds: Set<String> = emptySet(),
    expanded: Set<DecisionGroup> = emptySet(),
    pageLimit: Int = PAGE_LIMIT,
    maxGroups: Int = MAX_GROUPS,
): DecisionFold {
    val kept = records
        .filter { decisionRecordMatches(it, filter, nowMs, sessionId) }
        .sortedByDescending { it.atMs }
    if (kept.isEmpty()) return DecisionFold(emptyList(), emptyList(), 0)

    // 待抽检组：只放抽检命中的记录（§2.7）——它排最前，因为它是**要你动手**的队列
    val samplingRows = kept.filter { it.id in pendingIds }
    val byGroup = kept.filter { it.id !in pendingIds }.groupBy { it.group }

    val cards = mutableListOf<DecisionGroupCard>()
    if (samplingRows.isNotEmpty()) {
        cards += cardOf(DecisionGroup.SAMPLING, samplingRows, expanded, pageLimit)
    }
    byGroup.entries
        .map { (g, rows) -> cardOf(g, rows, expanded, pageLimit) }
        // 规则②：有可撤销的组优先，其次按组内最近时间
        .sortedWith(compareByDescending<DecisionGroupCard> { it.reversibleCount > 0 }.thenByDescending { it.latestMs })
        .forEach { cards += it }

    // 规则⑤：第 maxGroups+1 组起折进「更多（N 组）」
    val shown = cards.take(maxGroups)
    val hidden = cards.drop(maxGroups)

    // 规则⑥：组间共用一个行预算——按显示顺序消耗，超出的按「本组没渲染」计
    var budget = pageLimit
    val budgeted = shown.map { c ->
        val take = minOf(c.rows.size, budget)
        budget -= take
        if (take == c.rows.size) c else c.copy(rows = c.rows.take(take), hiddenByPage = c.all.size - take)
    }
    return DecisionFold(
        cards = budgeted,
        overflowGroups = hidden.map { it.copy(rows = emptyList(), hiddenByPage = it.all.size) },
        overflowRecords = hidden.sumOf { it.all.size },
    )
}

/** 一张组卡：折叠没展开 ⇒ [DecisionGroupCard.rows] 空表（规则①）。 */
private fun cardOf(
    group: DecisionGroup,
    rows: List<DecisionRecord>,
    expanded: Set<DecisionGroup>,
    pageLimit: Int,
): DecisionGroupCard {
    val sorted = rows.sortedByDescending { it.atMs }
    val visible = if (group in expanded) sorted.take(pageLimit) else emptyList()
    return DecisionGroupCard(
        group = group,
        all = sorted,
        rows = visible,
        hiddenByPage = sorted.size - visible.size,
        latestMs = sorted.firstOrNull()?.atMs ?: 0L,
        reversibleCount = sorted.count { it.reversible },
    )
}
