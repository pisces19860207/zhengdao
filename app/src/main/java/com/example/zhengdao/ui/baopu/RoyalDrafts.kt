// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十八批（派工单 §1 · 中书舍人拟稿）：**把一条意见写成"人能一眼看懂"的卡面**。
//
// 三条硬要求（派工单原文）：
//   ① 现状行 ≤30 字（人话；不写"L7 未分类"这种）——字数由 `RoyalCardGate` 卡；
//   ② ≤3 个选项，**每个都带后果**（"点了会怎样"写在按钮下面，不让人猜）；
//   ③ 一个默认项（默认＝最保守那条：搁一搁，不替你做决定）。
//
// 另有本批新增的一条红线（判据①）：**技术字段禁止出现在卡面**。
// 法条号、机器键、枚举名、文件名一律只许进 [RoyalCard.evidence]（证据区）。
// 判据在 `RoyalCardGate.looksTechnical`，本文件只负责"写人话"。
//
// 为什么拟稿要依赖核验结果（[checks]）：卡面那三行的真假标注**由核验给**，
// 不是拟稿自己说了算——拟稿说"我核实过"不算数，那是 ③b 的事。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.ClaimCheck
import com.example.zhengdao.baopu.agent.ClaimVerdict
import com.example.zhengdao.baopu.agent.DraftClaim
import com.example.zhengdao.baopu.agent.Ruling
import com.example.zhengdao.baopu.inspect.LawRef

/** 一类意见的三行人话（资源 id；文案在 `strings_baopu_royal.xml`）。 */
private data class RoyalCopy(val what: Int, val why: Int, val impact: Int)

/**
 * 中书舍人（拟稿）。
 *
 * 无状态、不读盘、不看时钟：同一份意见＋同一份核验结果，永远拟出同一张卡。
 */
internal object RoyalDrafts {

    /** 选项 id（接线层按它分发：批/驳/搁置）。 */
    const val ID_APPROVE = "approve"
    const val ID_REJECT = "reject"
    const val ID_HOLD = "hold"

    /** 默认项永远是"搁一搁"：不确定时不替用户做决定，这条写死在拟稿里。 */
    const val DEFAULT_ID = ID_HOLD

    /**
     * 拟一张卡；**拟不成返回 null**（有主张被判为假——闸本该拦住，这里是第二道）。
     */
    fun draft(ruling: Ruling, checks: List<ClaimCheck>, s: DecisionStrRes): RoyalCard? {
        val truth = truthOf(checks) ?: return null
        val copy = copyFor(ruling.opinion.lawRef)
        return RoyalCard(
            key = ruling.id,
            lines = listOf(
                RoyalLine(RoyalLineKind.WHAT, s.get(copy.what), truth),
                RoyalLine(RoyalLineKind.WHY, s.get(copy.why), truth),
                RoyalLine(RoyalLineKind.IMPACT, s.get(copy.impact), truth),
            ),
            options = options(s),
            // 技术字段只活在这里（账本行 id＋证据键），卡面一个字都不许带
            evidence = ruling.opinion.evidence + "ledger:${ruling.id}",
        )
    }

    /** 卡面三选项（批／驳／搁置），每个带后果；默认＝搁置。 */
    fun options(s: DecisionStrRes): List<RoyalOption> = listOf(
        RoyalOption(
            id = ID_APPROVE,
            label = s.get(R.string.royal_opt_approve),
            consequence = s.get(R.string.royal_opt_approve_consequence),
        ),
        RoyalOption(
            id = ID_REJECT,
            label = s.get(R.string.royal_opt_reject),
            consequence = s.get(R.string.royal_opt_reject_consequence),
        ),
        RoyalOption(
            id = ID_HOLD,
            label = s.get(R.string.royal_opt_hold),
            consequence = s.get(R.string.royal_opt_hold_consequence),
            isDefault = true,
        ),
    )

    /**
     * 核验结论 ⇒ 卡面标注。
     *
     * 返回 null＝**这张单子不许上御案**（有"假"）。三条口径：
     * - 一条都没核过 ⇒ 未核实（不是"真"：没核就是没核）；
     * - 有"无法判定" ⇒ 未核实（保守取最弱）；
     * - 全"真" ⇒ 真。
     */
    fun truthOf(checks: List<ClaimCheck>): RoyalTruth? = when {
        checks.any { it.verdict == ClaimVerdict.FALSE } -> null
        checks.isEmpty() -> RoyalTruth.UNVERIFIED
        checks.any { it.verdict == ClaimVerdict.UNVERIFIABLE } -> RoyalTruth.UNVERIFIED
        else -> RoyalTruth.TRUE
    }

    /**
     * 这一条意见要核的**事实主张**（③b 的输入）。
     *
     * 只出两条：①"这一步被拦是因为什么"（带证据引用）②"我判了这一条"（带账本 id）。
     * 主张写的是机器能对的东西（gist＋法条），不是漂亮话——它是给暗卫核的，不是给人看的。
     */
    fun claimsOf(ruling: Ruling): List<DraftClaim> = listOf(
        DraftClaim(
            id = "C1",
            text = "${ruling.opinion.lawRef.name}｜${ruling.opinion.gist}",
            evidenceRef = ruling.opinion.evidence.first(),
        ),
        DraftClaim(
            id = "C2",
            text = "判定=${ruling.decision.wire}｜为什么=${ruling.why.wire}",
            evidenceRef = "ledger:${ruling.id}",
        ),
    )

    /** 法条 ⇒ 三行人话（没覆盖到的法条走"通用"一版，不硬编不存在的说法）。 */
    private fun copyFor(law: LawRef): RoyalCopy = when (law) {
        LawRef.L8 -> RoyalCopy(
            R.string.royal_what_l8,
            R.string.royal_why_l8,
            R.string.royal_impact_l8,
        )

        LawRef.L9 -> RoyalCopy(
            R.string.royal_what_l9,
            R.string.royal_why_l9,
            R.string.royal_impact_l9,
        )

        else -> RoyalCopy(
            R.string.royal_what_l7,
            R.string.royal_why_l7,
            R.string.royal_impact_l7,
        )
    }
}
