// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十五批 · 容量与分配的判定口径（741 补丁）：判定本体。
// 三态：矛盾／支持／未知。硬规则——缺任一要素⇒未知；判矛盾必给「每项带来源锚」的排除清单，
// 清单空或含无锚项⇒降未知；以房间数反推容量⇒红/拒。
// 全是字符串/集合的确定性运算，不读文件、不读时钟、不用随机；同输入必得同结论。
package com.example.zhengdao.baopu.state.capacity

/** 容量口径判定器。纯函数：只吃结构化输入，不碰 IO。 */
object CapacityRule {

    /** 房间数反推容量的拒绝理由：口径钉死，便于报告与测试逐字对齐。 */
    const val ROOM_COUNT_REJECT: String =
        "不得以房间数反推容量：房间数不是容量，须写明人数、分配、范围、时段并逐项排除替代安排"

    /**
     * 主判定：先守房间数红线，再查四要素，最后按证据分三态。
     * @throws IllegalArgumentException 仅当拿房间数反推容量（判据④）。
     */
    fun judge(claim: CapacityClaim): CapacityResult {
        require(claim.countingBasis != CountingBasis.ROOM_COUNT_DERIVED) { ROOM_COUNT_REJECT }
        val missing = missingOf(claim)
        if (missing.isNotEmpty()) return unknown(missing, "四要素缺：${missing.joinToString("、") { it.label }}")
        return judgeComplete(claim)
    }

    /** 四要素齐备后的分支：排除清单优先（矛盾），其次支持证据，都没有则未知。 */
    private fun judgeComplete(claim: CapacityClaim): CapacityResult {
        val exclusions = claim.alternatives
        if (exclusions.isNotEmpty()) {
            return if (exclusions.all { it.valid }) contradiction(exclusions)
            else unknown(emptyList(), "排除清单含无来源锚的项，整张清单作废")
        }
        val confirmations = claim.confirmations
        if (confirmations.isNotEmpty() && confirmations.all { it.valid }) return support(confirmations)
        return unknown(emptyList(), "四要素齐但无有效证据，判不了")
    }

    /** 判矛盾：所有替代安排都被带锚排除，清单逐字进结论（判据③的证据留存）。 */
    private fun contradiction(exclusions: List<ExcludedAlternative>): CapacityResult = CapacityResult(
        verdict = CapacityVerdict.CONTRADICTION,
        missing = emptyList(),
        excludedAlternatives = exclusions.map { it.arrangement },
        reason = "已逐项排除 ${exclusions.size} 种替代安排且每项带来源锚",
    )

    /** 判支持：原文有带锚的正面陈述。 */
    private fun support(confirmations: List<SourceAnchor>): CapacityResult = CapacityResult(
        verdict = CapacityVerdict.SUPPORT,
        missing = emptyList(),
        excludedAlternatives = emptyList(),
        reason = "有 ${confirmations.size} 条带来源锚的正面陈述",
    )

    private fun unknown(missing: List<CapacityFactor>, reason: String): CapacityResult = CapacityResult(
        verdict = CapacityVerdict.UNKNOWN,
        missing = missing,
        excludedAlternatives = emptyList(),
        reason = reason,
    )

    /** 缺哪几个要素：按枚举固定次序返回，空串/空集视同缺。 */
    private fun missingOf(claim: CapacityClaim): List<CapacityFactor> {
        val out = ArrayList<CapacityFactor>(4)
        if (claim.quantity.isNullOrBlank()) out += CapacityFactor.QUANTITY
        if (claim.allocation.isNullOrBlank()) out += CapacityFactor.ALLOCATION
        if (claim.scope.isNullOrEmpty()) out += CapacityFactor.SCOPE
        if (claim.period.isNullOrBlank()) out += CapacityFactor.PERIOD
        return out
    }
}
