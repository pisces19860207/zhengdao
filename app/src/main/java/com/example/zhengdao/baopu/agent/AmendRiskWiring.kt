// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百一十七批 · 改约风险档重算的**接线层**（判定在 `plan/AmendRisk.kt`）。
//
// 三态各走各的路，一句多余的话都不说：
// · 升档 ⇒ [AmendRiskGate.reauthorize] 重新过授权闸（该问的必须问）；没批 ⇒ 本次动作不执行；
// · 降档 ⇒ [AmendRiskGate.recordDowngrade] 落一行变更账（不许静默降）；
// · 档位不变 ⇒ [AmendRiskOutcome] 全空：不弹卡、不记账、不回灌（不重复打扰）。
//
// 为什么不在这里直接判 `RiskVerdict`：本层只把 [AmendRiskRules.recheck] 的三态翻成动作，
// 判定一个字节都不碰（派工单判据④）。弹卡与落账都做成注入接口，JVM 单测喂假件就能
// 数清「弹了几次、落了几行」，生产实现在 RunEngine（真卡）与 PlanPledgeBoard（真账）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskCtx
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.plan.AmendRiskProbe
import com.example.zhengdao.baopu.plan.AmendRiskRecheck
import com.example.zhengdao.baopu.plan.AmendRiskRules
import com.example.zhengdao.baopu.plan.AmendRiskShift
import org.json.JSONObject

/** 三态的两个执行口（生产由 RunEngine 实现；单测用假件数调用次数）。 */
internal interface AmendRiskGate {
    /** 升档：重新过授权闸。返回 false ＝ 她没批／超时 ⇒ 本次动作不许执行。 */
    fun reauthorize(recheck: AmendRiskRecheck): Boolean

    /** 降档：落一行变更账（同一本 `计划军令状变更.jsonl`）。 */
    fun recordDowngrade(recheck: AmendRiskRecheck, requirement: String)
}

/** 接线结果：[line] 为空 ⇒ 不打扰；[blocked] 为真 ⇒ 调用方拦下这次动作。 */
internal data class AmendRiskOutcome(val blocked: Boolean = false, val line: String? = null)

internal object AmendRiskWiring {

    /**
     * 拿不到可比对的参数对时的一句实话。
     *
     * 既不假装算过，也不静默沿用旧批准：改约已经落地，旧确认按 360 的口径作废，
     * 之后的每个动作照常由守卫管线逐次调矩阵过闸。这行不是弹卡、不是记账。
     */
    const val NO_PROBE_LINE =
        "【改约风险档】这次改约没有可比对的旧/新参数 ⇒ 不重算、也不静默沿用旧批准：" +
            "下一次动作照常按风险矩阵逐次过闸。"

    fun onAmendLanded(
        probe: AmendRiskProbe?,
        requirement: String,
        gate: AmendRiskGate,
        ctx: RiskCtx = RiskCtx(),
        table: RiskTable = RiskTable.DEFAULT,
    ): AmendRiskOutcome {
        if (probe == null) return AmendRiskOutcome(line = NO_PROBE_LINE)
        val recheck = AmendRiskRules.recheck(probe, ctx, table)
        return when (recheck.shift) {
            AmendRiskShift.UP -> reauthorize(recheck, gate)
            AmendRiskShift.DOWN -> recordDowngrade(recheck, requirement, gate)
            // 档位不变：三个动作一个都不做（不重复弹、不重复记）。
            AmendRiskShift.SAME -> AmendRiskOutcome()
        }
    }

    private fun reauthorize(recheck: AmendRiskRecheck, gate: AmendRiskGate): AmendRiskOutcome {
        val approved = gate.reauthorize(recheck)
        val line = if (approved) {
            "【重新授权】改约后风险升档（${recheck.brief()}）⇒ 旧确认作废，已重新过授权闸。"
        } else {
            "【重新授权未通过】改约后风险升档（${recheck.brief()}）⇒ 本次动作不执行。"
        }
        return AmendRiskOutcome(blocked = !approved, line = line)
    }

    private fun recordDowngrade(
        recheck: AmendRiskRecheck,
        requirement: String,
        gate: AmendRiskGate,
    ): AmendRiskOutcome {
        gate.recordDowngrade(recheck, requirement)
        return AmendRiskOutcome(
            line = "【降档留痕】改约后风险降档（${recheck.brief()}）⇒ 已落一行变更账，旧的高档结论不静默作废。",
        )
    }
}

/** 重新授权那张卡（房型照 343 的变更卡：两个键都不标推荐，没点「重新确认」＝不批）。 */
internal object AmendRiskCards {
    const val OPT_REAUTHORIZE = "amend-risk-reauthorize"
    const val OPT_KEEP = "amend-risk-keep"

    fun approved(pickedId: String?): Boolean = pickedId == OPT_REAUTHORIZE

    fun reauthorizeRequest(recheck: AmendRiskRecheck): AskUserRequest = AskUserRequest(
        question = "改约之后风险档升了（${recheck.beforeLabel} → ${recheck.afterLabel}）：" +
            "旧确认不能沿用，要不要按新要求重新授权？",
        options = listOf(
            AskUserOption(
                id = OPT_REAUTHORIZE,
                label = "重新确认，按新要求继续",
                detail = "旧批准作废；本次动作按新参数重过确认卡，该问的一张不少。",
            ),
            AskUserOption(
                id = OPT_KEEP,
                label = "先不动，保持旧要求",
                detail = "这次动作不执行；改约后的计划与军令状照常保留。",
            ),
        ),
    )
}

/** 降档那一行的形状（事件键稳定；与 343 的 `amend` 行同表不同事件，seedFrom 互不干扰）。 */
internal object AmendRiskLedger {
    const val EVENT = "amend_risk_downgrade"

    fun downgradeRow(recheck: AmendRiskRecheck, requirement: String, atMs: Long): JSONObject = JSONObject()
        .put("v", 1)
        .put("event", EVENT)
        .put("shift", recheck.shift.name)
        .put("before", recheck.beforeLabel)
        .put("after", recheck.afterLabel)
        .put("before_reason", recheck.before.reasonCode.id)
        .put("after_reason", recheck.after.reasonCode.id)
        .put("requirement", requirement)
        .put("at", atMs)
}
