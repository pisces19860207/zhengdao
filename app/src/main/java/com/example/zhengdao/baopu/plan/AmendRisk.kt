// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百一十七批 · 改约（amend）落地后用**新参数**重算一次风险档（判定层）。
//
// 与第三百六十批的分工：360 管「改约透传到起单处、旧批准绑不上新要求」，本单管
// 「档位跟着变」——改约之后旧参数判出来的那一次结论不许罩住新参数。
//
// 三条硬口径：
// 1. **唯一口径**：档位只由 [RiskMatrix.classify] 给，旧参数与新参数各判一次；
//    本层不认「是不是目录」「删了几个」「要不要问」，一件事实都不判（派工单判据④）。
// 2. **三态只读读数**：升/降/不变两次比较的是矩阵自己吐出来的 [RiskVerdict] 字段，
//    不是本层另立的一张风险表；比较用的权重只决定「谁比谁严」，不决定「谁算几档」。
// 3. **零 IO**：不弹卡、不写盘、不读时钟；事实（两次工具调用参数、工作区根、已存在目标）
//    全由调用方放进 [AmendRiskProbe] 与 [RiskCtx]。
//
// 为什么「档位」要含「必问/硬拒」而不只看四档 ordinal：矩阵对「删单文件 → 删整目录」
// 的升档落在 `decision`（删 >10 个目标 ⇒ MUST_ASK），四档 ordinal 两次都是 R2。
// 只看 ordinal 就会把判据①那次改约判成「档位不变」⇒ 该重新弹的确认被静默吞掉。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.RiskCtx
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.agent.PlanTool
import com.example.zhengdao.baopu.agent.ToolCall

/**
 * 改约前／后的**参数对**（调用方给的事实，不是判定）。
 *
 * [before] 是改约前那次动作的工具调用，[after] 是改约落地后要执行的那次调用
 * ——「用新参数重算」重算的就是 [after]，[before] 只用来回答「比原来严了还是松了」。
 */
internal data class AmendRiskProbe(val before: ToolCall, val after: ToolCall)

/** 重算后的三态（派工单：升档／降档／档位不变）。 */
internal enum class AmendRiskShift { UP, DOWN, SAME }

/** 一次重算的完整读数：三态 ＋ 矩阵两次的原结论（落账、卡面、测试都从这里取）。 */
internal data class AmendRiskRecheck(
    val shift: AmendRiskShift,
    val before: RiskVerdict,
    val after: RiskVerdict,
) {
    /** 升档 ⇒ 重新过授权闸（该问的必须问）。 */
    val needsReauthorize: Boolean get() = shift == AmendRiskShift.UP

    /** 降档 ⇒ 落一行变更账（不许静默降）。 */
    val needsLedger: Boolean get() = shift == AmendRiskShift.DOWN

    /** 档位不变 ⇒ 不重复打扰（不弹卡、不记账）。 */
    val quiet: Boolean get() = shift == AmendRiskShift.SAME

    val beforeLabel: String get() = label(before)
    val afterLabel: String get() = label(after)

    /** 日志与卡面共用的一句；两次结论都带主判据，事后能回答「为什么升的」。 */
    fun brief(): String =
        "$beforeLabel（${before.reasonCode.id}） → $afterLabel（${after.reasonCode.id}）"

    private fun label(v: RiskVerdict): String = buildString {
        append(v.risk?.name ?: "未分级")
        if (v.rejected) append("·硬拒") else if (v.mustAsk) append("·必问")
    }
}

internal object AmendRiskRules {

    /** 一档抵几次「必问」：权重只用于排序，不参与任何风险判定。 */
    private const val TIER_WEIGHT = 4
    private const val ASK_BONUS = 1
    private const val REJECT_BONUS = 2

    /**
     * 重算一次：旧参数、新参数**都交给 [RiskMatrix.classify]**（生产表缺省；单测可喂改过的表）。
     *
     * 这里没有 `when (工具名)`、没有路径／数量／关键词判断、也没有第二张档位表——
     * 判据④「amend 链路里不许有第二套判断」的 grep 证据就落在这两行调用上。
     */
    fun recheck(
        probe: AmendRiskProbe,
        ctx: RiskCtx = RiskCtx(),
        table: RiskTable = RiskTable.DEFAULT,
    ): AmendRiskRecheck {
        val before = RiskMatrix.classify(probe.before, ctx, table)
        val after = RiskMatrix.classify(probe.after, ctx, table)
        return AmendRiskRecheck(shiftOf(before, after), before, after)
    }

    private fun shiftOf(a: RiskVerdict, b: RiskVerdict): AmendRiskShift = when {
        severity(b) > severity(a) -> AmendRiskShift.UP
        severity(b) < severity(a) -> AmendRiskShift.DOWN
        else -> AmendRiskShift.SAME
    }

    /**
     * 严度＝矩阵输出读数的加权和：**四档 ordinal ＋ 必问 ＋ 硬拒**。
     *
     * 三个加数全部取自 [RiskVerdict] 自己的字段（含它的派生 getter），本函数不判断
     * 任何一件事实。EXEMPT（`risk == null`）按最低档算：终端那一条路不归四档管。
     */
    private fun severity(v: RiskVerdict): Int =
        (v.risk?.ordinal ?: -1) * TIER_WEIGHT +
            (if (v.mustAsk) ASK_BONUS else 0) +
            (if (v.rejected) REJECT_BONUS else 0)
}

/**
 * 第四百一十七批 · 验收接管补的**测试缝**：改约基线「何时被消费」的状态转移。
 *
 * 为什么单独出来：这段原先写在 `RunEngine.amendGate` 里，靠引擎私有字段做判断，
 * JVM 单测够不着 ⇒ 只能拿源级 grep 钉形状（既不严谨，又会误红/误绿）。抽成纯函数后，
 * 「改约后先来一次 update_plan、再来那次动作」这条序列可以直接喂名字钉住。
 *
 * 语义（与 417 交付方的三态一致，本函数不判档、不看参数）：
 * - **改约后的 `update_plan` 不消费基线**：它只是「计划本身再写一遍」，不是带新参数的动作；
 *   若让它消费，会拿基线去和 R0 比出假降档、并把基线吃掉 ⇒ 真正那次动作不再重算。
 * - **任何非 `update_plan` 的调用消费基线**（无论本次是否真的重算出升/降/不变）：
 *   基线代表的「上一次动作」由此被这次动作取代。
 * - 每次非 `update_plan` 调用都刷新 [Outcome.lastAction]（留给下一次改约做旧参数）。
 */
internal object AmendRiskBaseline {

    /** 一次调用的转移结果：本次拿来重算的基线、调用后仍留存的基线、新的「最后一次动作」。 */
    data class Outcome(
        val taken: ToolCall?,
        val nextBaseline: ToolCall?,
        val lastAction: ToolCall?,
    )

    fun step(call: ToolCall, stored: ToolCall?): Outcome {
        if (call.name == PlanTool.NAME) return Outcome(taken = null, nextBaseline = stored, lastAction = null)
        return Outcome(taken = stored, nextBaseline = null, lastAction = call)
    }
}
