// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十八批 · **读数规则（法典条目层）**——三条规则的唯一判据真源。
//
// 为什么规则不住在调解器里（派工单 §二② + 判据⑤）：
//   · 规则是**法典条目**，属于判据层；调解器只是决定者，它读本层的"建议"，
//     结合自己的档位状态与必问铁律做最终决定——判定权不迁。
//   · 三条规则各自独立成一条判定，命中即产出一条 [Proposal]（含原因码/来源/决定变化）；
//     聚合在 [Advice] 里给出四个**行为建议位**。本层只说"因读数建议怎么变"，
//     不说"一定这么变"——mustAsk 铁律由 MediatorDecide 守，读数无权碰必问档。
//   · 优先级：越界无证据（规则③，安全项）命中时独占决定——"必须问人"与"少问/降级/
//     只提示"方向相反，安全优先（有据可查，见报告 §规则优先级）。
package com.example.zhengdao.baopu

/**
 * 三条读数规则的判据与阈值（法典条目 R1/R2/R3 的机器可读形态）。
 *
 * 阈值出处：R1 的"≥2 次"逐字来自派工单；R2 的"连续为负"派工单未给次数，
 * 本层取 ≥2（"连续"成串的最小连击），并在交付报告里标注该裁定。测试侧硬编码同值，
 * 不引用本常量，避免"测试引用被测常量"的假绿（参见 738 批教训）。
 */
object ReadingCodex {

    const val RETRY_MIN = 2
    const val RPE_NEG_MIN = 2

    const val REASON_STUCK = "R1_STUCK_RETRY"
    const val REASON_RPE = "R2_RPE_NEG_SAME"
    const val REASON_CROSS = "R3_CROSS_NO_EVIDENCE"

    /** 可观测行的固定前缀（判据 §二③ / 判据①）。 */
    const val AFFECT_PREFIX = "[readings-affect]"

    enum class Rule { STUCK_RETRY, RPE_NEG_SAME, CROSS_NO_EVIDENCE }

    /**
     * 一条命中：原因码 + 读数来源 + 决定变化 + 四个行为建议位（**建议，不是决定**）。
     */
    data class Proposal(
        val rule: Rule,
        val reasonCode: String,
        val source: String,
        val suppressAsk: Boolean,
        val requireAsk: Boolean,
        val degrade: Boolean,
        val hintOnce: Boolean,
    ) {
        /** 人读的"决定变化"片段（落 [readings-affect] 行第三段）。 */
        val decisionChange: String = buildString {
            val parts = mutableListOf<String>()
            if (suppressAsk) parts += "弹→压"
            if (requireAsk) parts += "放→问"
            if (degrade) parts += "正常→降级"
            if (hintOnce) parts += "无→提示一次"
            append(parts.joinToString(","))
        }

        /** 完整可观测行：`[readings-affect] 原因码 / 来源 / 决定变化`。 */
        fun affectLine(): String = "$AFFECT_PREFIX $reasonCode / $source / $decisionChange"
    }

    /** 一次评估的聚合结果（四条建议位 + 逐条可观测行）。 */
    data class Advice(
        val proposals: List<Proposal>,
        val suppressAsk: Boolean,
        val requireAsk: Boolean,
        val degrade: Boolean,
        val hintOnce: Boolean,
    ) {
        val hit: Boolean get() = proposals.isNotEmpty()
        val affectLines: List<String> get() = proposals.map { it.affectLine() }
    }

    /** 空建议（无读数 / 未装配）：所有位 false，调解器据此逐字节不变。 */
    val empty = Advice(emptyList(), false, false, false, false)

    /**
     * 唯一判据入口：事实快照 ⇒ 建议。纯函数、零 IO、零状态；同一 [facts] 调两次逐字相等。
     */
    fun evaluate(facts: ReadingFacts?): Advice {
        if (facts == null || facts.isEmpty) return empty
        val proposals = buildList {
            if (isStuckRetry(facts)) add(stuckProposal(facts))
            if (isRpeNegativeSame(facts)) add(rpeProposal(facts))
            if (isCrossNoEvidence(facts)) add(crossProposal())
        }
        if (proposals.isEmpty()) return empty
        // 安全优先：越界无证据独占决定，其余"少问"方向建议让位。
        if (proposals.any { it.requireAsk }) {
            val cross = proposals.first { it.requireAsk }
            return Advice(listOf(cross), false, true, false, false)
        }
        return Advice(
            proposals = proposals,
            suppressAsk = proposals.any { it.suppressAsk },
            requireAsk = false,
            degrade = proposals.any { it.degrade },
            hintOnce = proposals.any { it.hintOnce },
        )
    }

    // ── 三条规则（各拆成小函数，圈复杂度 < 15）────────────────────────────

    /** 规则①：卡步相同（本次与上次落同一环）+ 同参重试 ≥ [RETRY_MIN]。 */
    private fun isStuckRetry(f: ReadingFacts): Boolean =
        f.stuckLoop != null && f.stuckLoop == f.previousStuckLoop && f.sameParamRetries >= RETRY_MIN

    /** 规则②：RPE 连续为负成串 + 原因同类。 */
    private fun isRpeNegativeSame(f: ReadingFacts): Boolean =
        f.rpeNegativeStreak >= RPE_NEG_MIN && f.rpeCauseSame

    /** 规则③：越界无证据（安全项）。 */
    private fun isCrossNoEvidence(f: ReadingFacts): Boolean = f.crossedWithoutEvidence

    private fun stuckProposal(f: ReadingFacts) = Proposal(
        rule = Rule.STUCK_RETRY,
        reasonCode = REASON_STUCK,
        source = "753:loop=${f.stuckLoop}:retries=${f.sameParamRetries}:readback=" +
            (if (f.readbackPresent) "on" else "off"),
        suppressAsk = true,
        requireAsk = false,
        degrade = true,
        hintOnce = false,
    )

    private fun rpeProposal(f: ReadingFacts) = Proposal(
        rule = Rule.RPE_NEG_SAME,
        reasonCode = REASON_RPE,
        source = "756:rpe-neg=${f.rpeNegativeStreak}:cause=same",
        suppressAsk = true,
        requireAsk = false,
        degrade = false,
        hintOnce = true,
    )

    private fun crossProposal() = Proposal(
        rule = Rule.CROSS_NO_EVIDENCE,
        reasonCode = REASON_CROSS,
        source = "747:crossed=true:evidence=none",
        suppressAsk = false,
        requireAsk = true,
        degrade = false,
        hintOnce = false,
    )
}
