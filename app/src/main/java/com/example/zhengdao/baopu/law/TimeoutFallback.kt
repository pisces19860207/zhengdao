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
// - **同一事项不得因超时反复上奏**（[MAX_SUBMISSIONS] 次为上限）：超上限的那次上奏必被拒
//   （[TimeoutOutcome.REFILING_REJECTED]）并记一次违规，且**不许二次回退**（前次结算已回退过）。
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

    /** 同一事项反复上奏（超过 [TimeoutFallback.MAX_SUBMISSIONS] 次）：这次上奏必被拒＋记违规。 */
    REFILING_REJECTED,
}

/**
 * 一件在御案上等着被批的事（御案/208 行的最小投影，由调用方映射，本层不读盘）。
 *
 * [submissions] 含本次，所以"第一次上奏"传 1；[settlementRecorded] 是"这次的待御裁账已经落了吗"。
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

    /** 同一事项允许的上奏次数：超时之后最多再一次，再多必拒。 */
    const val MAX_SUBMISSIONS = 1

    /** 法条引用（同 L24：270 的 `LawRef` 枚举只到 L23 且归 272 片区，本批不碰）。 */
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
        val waited = nowMs - pending.raisedAtMs
        if (pending.submissions > MAX_SUBMISSIONS) {
            return TimeoutVerdict(
                lawRef = LAW_REF,
                outcome = TimeoutOutcome.REFILING_REJECTED,
                approved = false,
                keepOriginal = true,
                // 不许二次回退：上一轮超时结算已经把回退做掉了，再来一次会把已定的原状再改一遍
                rollbackRequested = false,
                mustRecord = true,
                recordNote = "待御裁（重复上奏被拒）",
                violation = "同一事项第 ${pending.submissions} 次上奏：超时不许反复上奏" +
                    "（上限 $MAX_SUBMISSIONS 次），记一次违规",
                waitedMs = waited,
            )
        }
        // 只数"真的等满了"：nowMs 早于上奏时刻（时钟回拨／填错）时 waited 为负 ⇒ 不算超时，继续等
        if (waited <= windowMs) {
            return TimeoutVerdict(
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
}
