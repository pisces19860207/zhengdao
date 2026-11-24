// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十七批 · 判定本体：只吃结构化陈述，红即不出读数。
package com.example.zhengdao.baopu.state.wet

/** 对外读数：五件齐全才有；围腰的松紧与"能不能滑"单列。 */
internal data class WetReading(
    val skinWet: SkinWet,
    val drips: List<DripRecord>,
    val towel: TowelState,
    val waistTier: WaistTier?,
    val canSlip: Boolean,
    val steamFog: SteamFog,
    val warm: WarmLevel,
)

internal data class WetVerdict(val issues: List<WetIssue>, val reading: WetReading?) {
    val hasRed: Boolean get() = issues.any { it.severity == WetSeverity.RED }
    val hasHint: Boolean get() = issues.any { it.severity == WetSeverity.HINT }
}

internal object WetRule {

    fun judge(claim: WetClaim): WetVerdict {
        val issues = WetChecks.all(claim)
        if (issues.any { it.severity == WetSeverity.RED }) return WetVerdict(issues, null)
        return WetVerdict(issues, readingOf(claim))
    }

    private fun readingOf(claim: WetClaim): WetReading {
        val tier = claim.waistTier
        return WetReading(
            skinWet = requireNotNull(claim.skinWet),
            drips = claim.drips.toList(),
            towel = requireNotNull(claim.towel),
            waistTier = tier,
            canSlip = claim.towel == TowelState.WAIST && tier?.canSlip == true,
            steamFog = requireNotNull(claim.steamFog),
            warm = requireNotNull(claim.warm),
        )
    }
}
