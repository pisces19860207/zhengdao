// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十五批 · 静默回想的**纯判据层**（零 Android、零 IO、零时钟、零调度）。
//
// 四条硬线（本文件把它们做成结构，不靠调用方自觉）：
//   ① recallCandidate 只把调用方传进来的既有账行/L0 记录映射成候选——没有自造 id 的路径；
//   ② 一条一行字，日历日闸复用 PatrolScheduler.dayBucket（全仓同一把尺）；
//   ③ reason 只陈述账上已有事实（结论/状态/日期/消息号），没有动作词、没有情绪词；
//   ④ 选择器入口对 knownIds 逐条验，出现一条指不到既有账行的候选 ⇒ 整轮拒绝。
//
// 与 676/677 的边界：不改它们的文件、不调它们的判据函数、不接它们的执行器；
// 共享去重靠只读三本账的 [SharedRecallDedupe]，本文件不碰任何账的写口。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerState
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.core.TimeFormats

/** 静默回想的唯一行数口径（一天最多一行；改这里＝改制度）。 */
internal object SilentRecallLimits {
    const val LINES_PER_DAY = 1
}

/**
 * 一条回想候选。**账行 id 必须非空且真实存在**（存在性由 [recallCandidate] 的入参与
 * [checkCandidateRefs] 双重保证）；[reason] 是点开后才给的那个「为什么想起」。
 */
internal data class SilentCandidate(
    val ledgerId: String,
    val sourceLabel: String,
    val title: String,
    val atMs: Long,
    val reason: String,
) {
    init {
        require(ledgerId.isNotBlank()) { "回想候选必须带账行 id（缺引用 ⇒ 拒）" }
        require(title.isNotBlank() && !title.contains('\n') && !title.contains('\t')) {
            "回想候选标题必须是一行"
        }
        require(reason.isNotBlank() && !reason.contains('\n')) { "想起缘由必须是一行" }
    }

    /** 与 676/677 共用的去重键：账行 id 本身。 */
    val dedupeKey: String get() = ledgerId
}

/** 一行字的产物（只此一条；点开前 [reason] 不进任何上下文）。 */
internal data class SilentLine(
    val ledgerId: String,
    val sourceLabel: String,
    val title: String,
    val reason: String,
    val atMs: Long,
    val dayKey: Long,
)

/** 项目账行上可陈述的事实（闭集；没有事实就不浮，不猜原因）。 */
private fun ledgerReasonOf(item: LedgerItem): String? {
    val stamp = TimeFormats.dayStamp(item.createdAtMs)
    return when {
        item.verdict == ECHO_VERDICT_FAIL -> "账行 ${item.id} 的结论是 FAIL"
        item.status == LedgerState.FLYING -> "账行 ${item.id} 立于 $stamp，状态在飞"
        item.status == LedgerState.FROZEN -> "账行 ${item.id} 立于 $stamp，状态冻结"
        item.status == LedgerState.WITHDRAWN -> "账行 ${item.id} 立于 $stamp，状态撤"
        else -> null
    }
}

/**
 * 候选来源（625 §①）：只从调用方给的既有账行与灵感 L0 里取 id。
 *
 * 归档项目账不浮（已经收拾过）；灵感原文只取 30 字预览，L0 原文本身一字不改。
 * 每一条候选的 [SilentCandidate.ledgerId] 都逐字来自入参对象的 id 字段。
 */
internal fun recallCandidate(
    items: List<LedgerItem>,
    inspirations: List<InspirationRecord>,
    nowMs: Long,
): List<SilentCandidate> = buildList {
    items.filter { it.archivedAtMs == 0L }.forEach { item ->
        val reason = ledgerReasonOf(item) ?: return@forEach
        add(
            SilentCandidate(
                ledgerId = item.id,
                sourceLabel = "项目账",
                title = item.title.replace('\n', ' '),
                atMs = item.createdAtMs,
                reason = reason,
            ),
        )
    }
    inspirations.forEach { record ->
        val stamp = TimeFormats.dayStamp(record.createdAtMs)
        add(
            SilentCandidate(
                ledgerId = record.id,
                sourceLabel = "灵感L0",
                title = record.preview.replace('\n', ' ').ifBlank { record.id },
                atMs = record.createdAtMs,
                reason = "原文记于 $stamp（L0 ${record.id}）",
            ),
        )
    }
}

/** 逐条验候选指回的账行；一条指不到 ⇒ 整轮拒绝（判据①的负例口）。 */
internal fun checkCandidateRefs(candidates: List<SilentCandidate>, knownIds: Set<String>) {
    val unknown = candidates.map { it.ledgerId }.filterNot(knownIds::contains).distinct()
    require(unknown.isEmpty()) { "回想候选指不到既有账行：$unknown" }
}

/**
 * 选一行字（判据①②⑤）：先验引用 → 天闸 → 共享 24h 去重 → 确定性取一条。
 *
 * @param lastFloatedDay 自身账最近一次浮出的日历日桶（null ＝ 从没浮过）
 * @param recentKeys 676/677/625 三本账 24h 内出现过的账行键
 */
internal fun selectSilentLine(
    candidates: List<SilentCandidate>,
    knownIds: Set<String>,
    nowMs: Long,
    lastFloatedDay: Long?,
    recentKeys: Set<String>,
): SilentLine? {
    checkCandidateRefs(candidates, knownIds)
    val dayKey = PatrolScheduler.dayBucket(nowMs)
    if (lastFloatedDay == dayKey) return null
    val best = candidates
        .filterNot { it.dedupeKey in recentKeys }
        .minWithOrNull(compareByDescending<SilentCandidate> { it.atMs }.thenBy { it.ledgerId })
        ?: return null
    return SilentLine(
        ledgerId = best.ledgerId,
        sourceLabel = best.sourceLabel,
        title = best.title,
        reason = best.reason,
        atMs = best.atMs,
        dayKey = dayKey,
    )
}
