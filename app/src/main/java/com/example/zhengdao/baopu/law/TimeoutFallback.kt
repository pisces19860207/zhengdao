// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十五批 · 法典卷九 **L27 超时保底**——御案压久了怎么办，由生产判定说死。
//
// 出处：第二百七十五批派工单（私有侧《落地排单-2026-10-29》§275，接线第一批＝纯判定、不动执行链）；
// 判据原文＝法典卷九 L27（私有侧《抱朴-法典-2026-10-27》；这两份文档不在本库内，别按库内路径找）。
// 本文件做的事：把 271 批（分支 `feat/law-gates`，未合并）写在测试侧的 L27 判据搬进 `app/src/main`，
// 并补上那份 spec 漏掉的半句法典原文——**「可回退者按原状回退」**（[TimeoutVerdict.rollbackRequested]）。
//
// ## 判据（输入→输出，逐条可执行）
// 一件在御案上等批的事，`nowMs - raisedAtMs` 超过 [WINDOW_MS]（默认 24 小时）⇒
// - **视为不批**（[TimeoutVerdict.approved] 恒为 false，超时永远不等于同意）；
// - **维持原状**（[TimeoutVerdict.keepOriginal] 为 true）；
// - **可回退者按原状回退**（[TimeoutVerdict.rollbackRequested] = 这件事项本身可不可回退）；
// - **必须落一条「待御裁」账**（[TimeoutVerdict.mustRecord]；账没落 ⇒ [TimeoutVerdict.violation] 报违规）；
// - **反复上奏取窄口径**（2026-10-29 裁定 Q2），两条独立的必拒：
//   ①事项**已被超时结算过**（[PendingRuling.timeoutSettled]）⇒ 再奏必被拒（[TimeoutOutcome.REFILING_REJECTED]）
//     ＋记一次违规，且**不许二次回退**（上一轮结算已经把回退做掉了，再来一次会把已定的原状再改一遍）；
//   ②没结算过（还在窗口里等）的事项**允许再提一次**，但同一事项累计以 [MAX_SUBMISSIONS] 次为上限，第 3 次照样必拒。
//
// ## 为什么超时算"不批"而不是"默认同意"
// 默认同意＝"人不看就自动过"，那 L20 的御批就白设了。默认不批，最坏结果是这件事没做；
// 默认同意，最坏结果是做错一件没人负责的事——两者的代价不对称，所以口径只能往保守一侧写死。
//
// ## 为什么只有判定
// 回退动作、落账动作都由执行层做（276 起）：本层零 IO、零时钟，[PendingRuling] 与 `nowMs` 由调用方给，
// 本层只把"该不该批、原状要不要保、要不要回退、账落没落、违规不违规"五件事一次算清。
package com.example.zhengdao.baopu.law

/** L27 的三种结局（除 WAITING 外都是"不批"）。 */
internal enum class TimeoutOutcome {
    /** 还在窗口内：继续等御批，什么都还不做。 */
    WAITING,

    /** 超窗口：按不批结算，维持原状（可回退者回退），并落一条「待御裁」。 */
    TIMEOUT_REJECTED,

    /**
     * 这次上奏必被拒＋记违规（窄口径两条触发之一，见文件头）：
     * 事项已被超时结算过，或累计上奏已达 [TimeoutFallback.MAX_SUBMISSIONS] 次上限。
     */
    REFILING_REJECTED,
}

/**
 * 一件在御案上等着被批的事（御案/208 行的最小投影，由调用方映射，本层不读盘）。
 *
 * [submissions] 含本次，所以"第一次上奏"传 1；[timeoutSettled] 是"这案此前结过没有"（Q2 窄口径只看它）；
 * [settlementRecorded] 是"这次的待御裁账已经落了吗"。后两者是不同的事实：结过案但账没落，正是那条违规。
 */
internal data class PendingRuling(
    /** 事项标识：L27 的"同一事项"按它判重。 */
    val caseId: String,
    /** 这次上奏的时刻（毫秒）。 */
    val raisedAtMs: Long,
    /** 这件事可不可回退（可回退者超时时按原状回退；不可回退者只能维持原状）。 */
    val reversible: Boolean,
    /** 该事项累计上奏次数（含本次）。 */
    val submissions: Int = 1,
    /**
     * 该事项此前**是否已被超时结算过**（御案行或 208 账里的既定事实，由调用方映射，本层不读盘）。
     *
     * Q2 的窄口径只看它：结过案 ⇒ 一次都不许多；没结过案（还在窗口里等）⇒ 允许再提一次。
     * 别与 [settlementRecorded] 混用——那个问的是"账落没落"（幂等口径），这个问的是"案结没结"。
     */
    val timeoutSettled: Boolean = false,
    /** 208 里是否已有这条「待御裁（未批）」账。 */
    val settlementRecorded: Boolean = false,
)

/**
 * L27 的一次结算结论。
 *
 * [mustRecord] 与 [violation] 的分工写死在这里，免得调用方猜：
 * `mustRecord`＝"这次结算必须有那条待御裁账"（幂等口径：账已在则不重复记，靠 [PendingRuling.settlementRecorded] 认）；
 * `violation`＝"账没落"或"反复上奏"的违规原文（null ⇒ 这次结算没违规）。
 */
internal data class TimeoutVerdict(
    val lawRef: String,
    val outcome: TimeoutOutcome,
    val approved: Boolean,
    val keepOriginal: Boolean,
    val rollbackRequested: Boolean,
    val mustRecord: Boolean,
    val recordNote: String,
    val violation: String?,
    val waitedMs: Long,
)

/** L27 的唯一判据实现（生产）。 */
internal object TimeoutFallback {

    /** 御案等待窗口：默认 24 小时（法典写死可配，所以 [settle] 收一个 windowMs）。 */
    const val WINDOW_MS = 24L * 60 * 60 * 1000

    /**
     * 同一事项的上奏上限：**2 次**（第 3 次必拒），Q2 裁定的"人点两下不至于永远不能再问"。
     *
     * 已被超时结算过的事项走 [settle] 里的另一条窄口径，与这个上限无关——结了就不再奏。
     */
    const val MAX_SUBMISSIONS = 2

    /** 法条引用（同 L24：`LawRef` 枚举归 272 片区，扩 L24–L27 按裁定由 276 两处同改，本批不碰）。 */
    const val LAW_REF = "L27"

    /** 待御裁那条账的固定抬头（执行层直接照抄落账，不许自己改口径）。 */
    const val PENDING_NOTE = "待御裁（超时未批，维持原状）"

    /**
     * 结算一次御案等待。
     *
     * @param pending 事项事实（上奏时刻／可回退性／已上奏几次／账落没落）
     * @param nowMs 现在（由调用方给，本层不读时钟）
     * @param windowMs 窗口，默认 [WINDOW_MS]；单测传别的值来验"改窗口 ⇒ 判定变"
     */
    fun settle(
        pending: PendingRuling,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
    ): TimeoutVerdict {
        require(pending.caseId.isNotBlank()) { "L27 结算的 caseId 不许为空：重复上奏靠它认事项" }
        require(pending.submissions >= 1) { "L27 的上奏次数至少含本次，即 >= 1" }
        require(!(pending.timeoutSettled && pending.submissions <= 1)) {
            "L27：已结算过的事项再奏必是第 2 次以上，submissions 要含本次"
        }
        val waited = nowMs - pending.raisedAtMs
        // 窄口径（Q2）两条必拒：结过案还想再来 / 没结案但累计已到上限
        val settledRefiling = pending.timeoutSettled && pending.submissions > 1
        val overCap = pending.submissions > MAX_SUBMISSIONS
        if (settledRefiling || overCap) {
            val why = if (settledRefiling) {
                "同一事项已被超时结算过，第 ${pending.submissions} 次上奏必拒：结了就不再奏"
            } else {
                "同一事项上奏上限 $MAX_SUBMISSIONS 次，第 ${pending.submissions} 次必拒：不许反复上奏"
            }
            return refilingVerdict(why, waited)
        }
        // 只数"真的等满了"：nowMs 早于上奏时刻（时钟回拨／填错）时 waited 为负 ⇒ 不算超时，继续等
        if (waited <= windowMs) {
            return waitingVerdict(waited)
        }
        return TimeoutVerdict(
            lawRef = LAW_REF,
            outcome = TimeoutOutcome.TIMEOUT_REJECTED,
            approved = false,
            keepOriginal = true,
            rollbackRequested = pending.reversible,
            mustRecord = true,
            recordNote = if (pending.reversible) "$PENDING_NOTE，已按原状回退" else PENDING_NOTE,
            violation = if (pending.settlementRecorded) {
                null
            } else {
                "超时未落账：必须记一条「$PENDING_NOTE」，不许静默作废"
            },
            waitedMs = waited,
        )
    }

    /** 再奏被拒那条结论的构造（判据在 [settle] 的窄口径里，这里只搬字段，不再判一次）。 */
    private fun refilingVerdict(why: String, waited: Long): TimeoutVerdict =
        TimeoutVerdict(
            lawRef = LAW_REF,
            outcome = TimeoutOutcome.REFILING_REJECTED,
            approved = false,
            keepOriginal = true,
            // 不许二次回退：上一轮超时结算已经把回退做掉了，再来一次会把已定的原状再改一遍
            rollbackRequested = false,
            mustRecord = true,
            recordNote = "待御裁（重复上奏被拒）",
            violation = "$why，记一次违规",
            waitedMs = waited,
        )

    /** 窗口内还没到期那条结论的构造：三件事都不做（不批、不落账、不违规）。 */
    private fun waitingVerdict(waited: Long): TimeoutVerdict =
        TimeoutVerdict(
            lawRef = LAW_REF,
            outcome = TimeoutOutcome.WAITING,
            approved = false,
            keepOriginal = false,
            rollbackRequested = false,
            mustRecord = false,
            recordNote = "",
            violation = null,
            waitedMs = waited,
        )
}
