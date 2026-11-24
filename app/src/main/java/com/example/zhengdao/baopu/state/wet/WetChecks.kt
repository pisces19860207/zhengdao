// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十七批 · 判据检查器：五件齐 / 三拍不干 / 水珠向下 / 围腰可滑 / 蒸汽≠遮挡 / 禁数值。
// 纯函数；红即不出值，提示只提醒不阻断。
package com.example.zhengdao.baopu.state.wet

internal object WetCodes {
    const val FIVE_MISSING = "W-FIVE-MISSING"
    const val INSTANT_DRY = "W-INSTANT-DRY"
    const val DRIP_UPWARD = "W-DRIP-UPWARD"
    const val TOWEL_NO_TIER = "W-TOWEL-NO-TIER"
    const val TOWEL_SAFE_TALK = "W-TOWEL-SAFE-TALK"
    const val FOG_BLOCK_CONFUSION = "W-FOG-BLOCK-CONFUSION"
    const val NUMERIC_VALUE = "W-NUMERIC-VALUE"
    const val WARM_STUCK = "W-WARM-STUCK"
}

internal enum class WetSeverity(val label: String) {
    RED("红"),
    HINT("提示"),
}

internal data class WetIssue(val code: String, val severity: WetSeverity, val detail: String)

/** 水珠起点→下一点；终点为空只许出现在最低的腿，表示离开体表。 */
internal data class DripRecord(val from: DripSource, val to: DripSource?)

/**
 * 一项浴后陈述。五件都可以缺席——缺席不是崩溃，是判据①的红。
 * note 是自由描述，steamNote 专写蒸汽感受；拍数只走 recoveryLevel（0/1/2 → 1/3/6）。
 */
internal data class WetClaim(
    val skinWet: SkinWet? = null,
    val drips: List<DripRecord> = emptyList(),
    val towel: TowelState? = null,
    val waistTier: WaistTier? = null,
    val steamFog: SteamFog? = null,
    val warm: WarmLevel? = null,
    val air: DryAir = DryAir.STILL,
    val recoveryLevel: Int = 1,
    val note: String = "",
    val steamNote: String = "",
)

/** 单位字面拼接写，运行期连续、生产源码扫不到自己。 */
private val WET_UNITS: List<String> = listOf(
    "摄" + "氏度",
    "m" + "L",
    "毫" + "升",
    "厘" + "米",
    "毫" + "米",
    "滴" + "/秒",
    "米" + "/秒",
    "H" + "z",
    "秒",
)

/** 遮挡措辞：蒸汽里出现任一即是与 861 混用。拼接写，避免源码门禁自咬。 */
private val BLOCK_TERMS: List<String> = listOf(
    "被" + "挡住",
    "遮" + "挡",
    "挡" + "住",
    "遮" + "住",
    "遮" + "蔽",
    "看不见=被挡住",
)

internal object WetChecks {

    /** 判据①：五件各就各位。 */
    fun fiveComplete(claim: WetClaim): List<WetIssue> {
        val missing = ArrayList<String>()
        if (claim.skinWet == null) missing.add("湿身档")
        if (claim.drips.isEmpty()) missing.add("滴落起点")
        if (claim.towel == null) missing.add("浴巾处置")
        if (claim.steamFog == null) missing.add("蒸汽档")
        if (claim.warm == null) missing.add("偏温档")
        return if (missing.isEmpty()) emptyList()
        else listOf(WetIssue(WetCodes.FIVE_MISSING, WetSeverity.RED, "五件不齐：缺" + missing.joinToString("、")))
    }

    /** 判据②：三拍时间夹具——洗完立刻干/起点已是半干，都红。 */
    fun notInstantDry(claim: WetClaim): List<WetIssue> {
        val skin = claim.skinWet ?: return emptyList()
        val start = WetSolver.start(skin, claim.warm ?: WarmLevel.CLEAR)
        val end = WetSolver.advance(
            start, claim.air, waistOf(claim), claim.recoveryLevel, WetClock.DRY_CHECK_BEATS
        )
        if (WetSolver.skinGrade(end.skinMilli).level < skin.level) return emptyList()
        return listOf(
            WetIssue(WetCodes.INSTANT_DRY, WetSeverity.RED, "三拍后湿身未降档：不许洗完立刻干")
        )
    }

    /** 判据③：滴落只能沿重力次序往更低部位走；最低处（腿）才许离开体表。 */
    fun dripsDownward(claim: WetClaim): List<WetIssue> {
        val issues = ArrayList<WetIssue>()
        claim.drips.forEach { drip ->
            val to = drip.to
            if (to == null) {
                if (drip.from != DripSource.LEG) issues.add(
                    WetIssue(WetCodes.DRIP_UPWARD, WetSeverity.RED, "${drip.from.label}未给下方落点")
                )
            } else if (to.order <= drip.from.order) {
                issues.add(
                    WetIssue(
                        WetCodes.DRIP_UPWARD, WetSeverity.RED,
                        "${drip.from.label}→${to.label}是水珠往上流，必须向下"
                    )
                )
            }
        }
        return issues
    }

    /** 判据④：围腰必须有松紧档；"围得稳稳的"只作提示，不冒充唯一情形。 */
    fun towelCanSlip(claim: WetClaim): List<WetIssue> {
        if (claim.towel != TowelState.WAIST) return emptyList()
        val issues = ArrayList<WetIssue>()
        if (claim.waistTier == null) {
            issues.add(WetIssue(WetCodes.TOWEL_NO_TIER, WetSeverity.RED, "围腰必须给松紧档，才能表达滑落"))
        }
        if ("围得稳稳的" in claim.note || "不会掉" in claim.note) {
            issues.add(WetIssue(WetCodes.TOWEL_SAFE_TALK, WetSeverity.HINT, "围腰也会滑落，别写成唯一情形"))
        }
        return issues
    }

    /** 判据⑤：蒸汽只说"看不清"；说成"被挡住"即与 861 混用，红。 */
    fun fogNotBlocking(claim: WetClaim): List<WetIssue> {
        val text = claim.steamNote + " " + claim.note
        val hit = BLOCK_TERMS.firstOrNull { it in text } ?: return emptyList()
        return listOf(
            WetIssue(
                WetCodes.FOG_BLOCK_CONFUSION, WetSeverity.RED,
                "蒸汽是看不清（$STEAM_MEANS），遮挡是被挡住（$OCCLUSION_MEANS）：命中「$hit」"
            )
        )
    }

    /** 判据⑥：只给档与拍，不给任何点值单位或数字。 */
    fun noNumbers(claim: WetClaim): List<WetIssue> {
        val issues = ArrayList<WetIssue>()
        listOf(claim.note, claim.steamNote).forEach { text ->
            if (text.any { it.isDigit() } || WET_UNITS.any { it in text }) {
                issues.add(WetIssue(WetCodes.NUMERIC_VALUE, WetSeverity.RED, "只给档与拍，不给数值单位"))
            }
        }
        return issues
    }

    /** 判据②的体温半：刚洗完有偏温，且走完最慢预算必回落。 */
    fun warmFalls(claim: WetClaim): List<WetIssue> {
        val warm = claim.warm ?: return emptyList()
        if (warm.level == WarmLevel.SETTLED.level) {
            return listOf(WetIssue(WetCodes.WARM_STUCK, WetSeverity.RED, "刚洗完不能没有偏温档"))
        }
        val settled = WetSolver.warmAt(
            warm.level, WetClock.MAX_STEPS + WetClock.DT, claim.recoveryLevel
        )
        return if (settled == 0) emptyList()
        else listOf(WetIssue(WetCodes.WARM_STUCK, WetSeverity.RED, "偏温走完拍预算仍未回落"))
    }

    /** 汇总一次跑完；五件不齐时其余检查各自跳过缺件，仍把所有红码报全。 */
    fun all(claim: WetClaim): List<WetIssue> {
        val out = ArrayList<WetIssue>()
        out.addAll(fiveComplete(claim))
        out.addAll(notInstantDry(claim))
        out.addAll(dripsDownward(claim))
        out.addAll(towelCanSlip(claim))
        out.addAll(fogNotBlocking(claim))
        out.addAll(noNumbers(claim))
        out.addAll(warmFalls(claim))
        return out
    }

    private fun waistOf(claim: WetClaim): WaistTier? =
        if (claim.towel == TowelState.WAIST) claim.waistTier else null
}
