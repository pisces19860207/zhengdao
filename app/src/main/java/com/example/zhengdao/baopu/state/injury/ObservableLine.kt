// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-19-第七百二十八批 §一②③、判据③／④。
//
// 恢复档随轮次**单调回落**，超过最慢档必回 [GONE]；
// 所有输出只走 [render] 一个出口，末尾统一带 [DISCLAIMER]。
package com.example.zhengdao.baopu.state.injury

/** 已回落态的说法（不算 3 档体系里的档，体系严格 3 档）。 */
internal const val GONE = "看不出还在"

/** 恢复档随轮次只降不升；到点回 null（＝[GONE]）。 */
internal object RecoveryModel {
    fun at(start: RecoveryGrade, elapsedRounds: Int): RecoveryGrade? {
        val spent = elapsedRounds.coerceAtLeast(0)
        if (spent >= start.budgetRounds) return null
        val left = start.budgetRounds - spent
        return when {
            left <= RecoveryGrade.SAME_TURN.budgetRounds -> RecoveryGrade.SAME_TURN
            left <= RecoveryGrade.LATER.budgetRounds -> RecoveryGrade.LATER
            else -> RecoveryGrade.LONGER
        }
    }
}

/** 唯一的渲染出口：三档拼一行，末尾永远带「非医疗判断」。 */
internal object ObservableLine {
    fun render(tier: Int, elapsedRounds: Int): String {
        val injury = InjuryGrade.of(ImpactLink.injuryStart(tier))
        val pain = PainGrade.of(ImpactLink.painStart(tier))
        val recovery = RecoveryModel.at(ImpactLink.recoveryStart(tier), elapsedRounds)
        val recoveryText = recovery?.observable ?: GONE
        return "${injury.observable}；${pain.observable}；$recoveryText（$DISCLAIMER）"
    }

    /** 727 未接时的入口：冲击档取 [UnwiredImpact] 的 0 档，其余照常。 */
    fun renderUnwired(elapsedRounds: Int): String = render(UnwiredImpact.tier(), elapsedRounds)
}
