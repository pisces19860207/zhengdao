// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 方向规则：气候只译上游档，季节四向固定，时间与基线各守一条。
package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.fluid.FluidKind

internal object OdorCodes {
    const val CORE_PRESENT = "ODOR_CORE_PRESENT"
    const val CORE_SOURCE = "ODOR_CORE_SOURCE"
    const val CORE_WORD = "ODOR_CORE_WORD"
    const val CORE_REGION = "ODOR_CORE_REGION"
    const val CORE_TEMPORAL = "ODOR_CORE_TEMPORAL"
    const val CORE_SEASONAL = "ODOR_CORE_SEASONAL"
    const val CORE_CLIMATE = "ODOR_CORE_CLIMATE"
    const val CORE_CLIMATE_TREND = "ODOR_CORE_CLIMATE_TREND"
    const val ZERO_WITH_DETAIL = "ODOR_ZERO_WITH_DETAIL"
    const val ZERO_WITH_INTENSITY = "ODOR_ZERO_WITH_INTENSITY"
    const val LOWER_NO_SOURCE = "ODOR_LOWER_NO_SOURCE"
    const val REGION_REDLINE = "ODOR_REGION_REDLINE"
    const val CLIMATE_CONFLICT = "ODOR_CLIMATE_CONFLICT"
    const val CLIMATE_REVERSED = "ODOR_CLIMATE_REVERSED"
    const val TEMPORAL_WASH = "ODOR_TEMPORAL_WASH"
    const val TEMPORAL_TIER = "ODOR_TEMPORAL_TIER"
    const val TEMPORAL_SOURCE = "ODOR_TEMPORAL_SOURCE"
    const val INTENSITY_UNSOURCED = "ODOR_INTENSITY_UNSOURCED"
    const val SEASON_CLIMATE_CONFLICT = "ODOR_SEASON_CLIMATE_CONFLICT"
    const val FRESH_SWEAT_SOUR = "ODOR_FRESH_SWEAT_SOUR"
    const val BASELINE_NO_DRIVER = "ODOR_BASELINE_NO_DRIVER"
    const val DIAGNOSIS = "ODOR_DIAGNOSIS"
    const val VALUE_JUDGMENT = "ODOR_VALUE_JUDGMENT"
    const val BEHAVIOR_GUIDANCE = "ODOR_BEHAVIOR_GUIDANCE"
    const val OCCUPATION_INFERENCE = "ODOR_OCCUPATION_INFERENCE"
    const val POINT_VALUE = "ODOR_POINT_VALUE"
    const val WASHED_STILL_ODOR = "ODOR_WASHED_STILL_ODOR"
    const val DRY_MARK_SILENT = "ODOR_DRY_MARK_SILENT"
}

internal enum class ClimateBias(val trend: Trend) {
    HOT_HUMID(Trend.UP),
    DRY_COLD(Trend.DOWN),
    NEUTRAL(Trend.FLAT),
    CONFLICT(Trend.FLAT),
}

internal object OdorRules {

    fun climateReading(
        evidence: ClimateEvidence,
        claimed: Trend?,
        sweatRelated: Boolean,
    ): OdorVerdict<ClimateReading> {
        val bias = biasOf(evidence)
        if (bias == ClimateBias.CONFLICT) {
            return OdorVerdict.red(OdorCodes.CLIMATE_CONFLICT, "657 与 730 的冷热湿方向不一致：先对齐上游档")
        }
        val trend = if (bias == ClimateBias.HOT_HUMID && !sweatRelated) Trend.FLAT else bias.trend
        if (claimed != null && claimed != trend) {
            return OdorVerdict.red(
                OdorCodes.CLIMATE_REVERSED,
                "气候方向应为「" + trend.label + "」，实为「" + claimed.label + "」",
            )
        }
        val sweatGrade = evidence.env657?.sweat?.grade ?: requireNotNull(evidence.thermo730).sweatGrade
        return OdorVerdict.of(
            ClimateReading(
                source = sourcesOf(evidence),
                sweatGrade = sweatGrade,
                trend = trend,
                why = climateWhy(bias, sweatRelated),
            )
        )
    }

    private fun biasOf(evidence: ClimateEvidence): ClimateBias {
        val from657 = evidence.env657?.let(::biasOf657)
        val from730 = evidence.thermo730?.let(::biasOf730)
        val directional = listOfNotNull(from657, from730).filter { it != ClimateBias.NEUTRAL }
        return when {
            directional.distinct().size > 1 -> ClimateBias.CONFLICT
            directional.isNotEmpty() -> directional.first()
            else -> ClimateBias.NEUTRAL
        }
    }

    private fun biasOf657(env: EnvironmentState): ClimateBias {
        val felt = env.sweat.effects.firstOrNull { it.kind == "felt_temp" } ?: return ClimateBias.NEUTRAL
        val humid = env.humidity.evaporation == "低"
        return if (felt.grade >= 2 && humid) ClimateBias.HOT_HUMID else ClimateBias.NEUTRAL
    }

    private fun biasOf730(ref: ThermoTierRef): ClimateBias = when {
        ref.thermalLevel >= 2 && ref.humidityLevel >= 2 -> ClimateBias.HOT_HUMID
        ref.thermalLevel == 0 && ref.humidityLevel == 0 -> ClimateBias.DRY_COLD
        else -> ClimateBias.NEUTRAL
    }

    private fun sourcesOf(evidence: ClimateEvidence): String = buildList {
        evidence.env657?.let { add("657") }
        evidence.thermo730?.let { add(it.source) }
    }.joinToString("+")

    private fun climateWhy(bias: ClimateBias, sweatRelated: Boolean): String = when (bias) {
        ClimateBias.HOT_HUMID ->
            if (sweatRelated) "热湿档使汗味更易被注意到" else "热湿档只影响汗味；本读数无汗源，方向持平"
        ClimateBias.DRY_COLD -> "干冷档使出汗与气味减弱"
        ClimateBias.NEUTRAL -> "上游档未落在热湿或干冷两端，方向持平"
        ClimateBias.CONFLICT -> "上游方向冲突，不出气候读数"
    }

    fun hasPositiveSweatSource(claim: OdorClaim): Boolean =
        OdorSource.BODY_SWEAT in claim.odorSource || claim.lowerBodyOrigins.any { origin ->
            when (origin) {
                is SweatSource -> origin.volume.level > 0
                is FluidResidueSource -> origin.kind == FluidKind.SWEAT && origin.volume.level > 0
                is SmegmaSource -> false
            }
        }

    /** 四档方向全部产出；秋冬与室内干燥的「体味淡、衣物残留升」必须同时保留。 */
    fun seasonDirections(): List<SeasonalDirection> = listOf(
        SeasonalDirection(
            season = OdorSeason.SUMMER_HOT,
            sweat = Trend.UP,
            humidity = Trend.UP,
            bodyOdor = Trend.UP,
            clothingResidue = Trend.FLAT,
            genitalOdor = Trend.UP,
            why = "闷热多汗、湿度高，下体气味最易被注意到",
        ),
        SeasonalDirection(
            season = OdorSeason.RAINY_DAMP,
            sweat = Trend.FLAT,
            humidity = Trend.UP,
            bodyOdor = Trend.FLAT,
            clothingResidue = Trend.UP,
            genitalOdor = Trend.FLAT,
            why = "衣物难干，气味滞留，残留感上升",
        ),
        SeasonalDirection(
            season = OdorSeason.AUTUMN_WINTER_COLD,
            sweat = Trend.DOWN,
            humidity = Trend.DOWN,
            bodyOdor = Trend.DOWN,
            clothingResidue = Trend.UP,
            genitalOdor = Trend.DOWN,
            why = "干冷少汗、体味淡；厚衣物换洗间隔长，残留味反升",
        ),
        SeasonalDirection(
            season = OdorSeason.INDOOR_DRY,
            sweat = Trend.FLAT,
            humidity = Trend.DOWN,
            bodyOdor = Trend.DOWN,
            clothingResidue = Trend.UP,
            genitalOdor = Trend.DOWN,
            why = "室内干燥使气味淡；静电吸尘使衣物尘土残留感升",
        ),
    )

    fun seasonIssue(claim: OdorClaim): OdorIssue? {
        val season = claim.odorSeasonal ?: return null
        val evidence = claim.climateEvidence ?: return null
        val mismatch = when (season) {
            OdorSeason.SUMMER_HOT, OdorSeason.RAINY_DAMP -> biasOf(evidence) == ClimateBias.DRY_COLD
            OdorSeason.AUTUMN_WINTER_COLD, OdorSeason.INDOOR_DRY -> biasOf(evidence) == ClimateBias.HOT_HUMID
        }
        return if (mismatch) {
            OdorIssue(
                OdorCodes.SEASON_CLIMATE_CONFLICT,
                IssueSeverity.RED,
                "季节微气候与上游冷热湿档相反：先对齐季节或气候依据",
            )
        } else {
            null
        }
    }

    fun hasLowerBody(regions: Set<OdorRegion>): Boolean = regions.any { it.lowerBody }

    fun hasValidLowerBodyOrigin(origins: Set<LowerBodySource>): Boolean = origins.any(::validOrigin)

    private fun validOrigin(origin: LowerBodySource): Boolean = when (origin) {
        // 827 只问来源是否存在；883 的非零强度门槛仍由 883 自己把。
        is SmegmaSource -> origin.smegmaTier >= 1
        is FluidResidueSource -> origin.volume.level > 0
        is SweatSource -> origin.volume.level > 0
    }

    private val washExceptionNegated =
        Regex("""(?:并非|并不是|不是|没有)\s*(?:未洗净|没洗净)""")
    private val washExceptionUncertain = Regex(
        "(?:若|如果|假如|倘|一旦|除非|要是|是否|有没有|可能|也许|或许|疑似|怀疑|据说|听说|" +
            "确认是否|判断是否|不排除|不能排除|无法排除|待核实|尚需确认|仍需确认|尚未确认|有待确认)" +
            "[^。！？；;\\n]{0,16}(?:未洗净|没洗净)",
    )

    fun incompleteWashStated(note: String): Boolean {
        if (note.contains("？") || note.contains("?")) return false
        val affirmative = note.replace(washExceptionNegated, "")
        return affirmative.split(Regex("""[。！？；;，,\n]""")).any { clause ->
            val stated = clause.contains("未洗净") || clause.contains("没洗净")
            val quoted = clause.contains("“") || clause.contains("”") || clause.contains("『") ||
                clause.contains("』") || clause.contains("「") || clause.contains("」") || clause.contains("\"")
            stated && !quoted && !washExceptionUncertain.containsMatchIn(clause)
        }
    }

    fun temporalIssue(claim: OdorClaim): OdorIssue? {
        val present = claim.odorPresent ?: return null
        val washed = claim.odorTemporal == OdorTemporal.JUST_WASHED
        if (washed && present.level > OdorPresence.FAINT.level && !incompleteWashStated(claim.note)) {
            return OdorIssue(OdorCodes.TEMPORAL_WASH, IssueSeverity.RED, "刚清洗后仍写明显气味：须明写未洗净")
        }
        return null
    }

    fun temporalTierIssue(claim: OdorClaim): OdorIssue? {
        val present = claim.odorPresent ?: return null
        if (present == OdorPresence.NONE) return null
        val mismatch = when (claim.odorTemporal) {
            OdorTemporal.SAME_DAY -> present.level > OdorPresence.FAINT.level
            OdorTemporal.DAYS_UNWASHED -> present.level < OdorPresence.NOTICEABLE.level
            else -> false
        }
        return if (mismatch) {
            OdorIssue(
                OdorCodes.TEMPORAL_TIER,
                IssueSeverity.RED,
                "时间档与存在档不符：当日只能淡，数日未洗须至少明显",
            )
        } else {
            null
        }
    }

    fun temporalSourceIssue(claim: OdorClaim): OdorIssue? {
        if (claim.odorPresent == null || claim.odorPresent == OdorPresence.NONE) return null
        val mismatch = when (claim.odorTemporal) {
            OdorTemporal.AFTER_EXERCISE -> !hasPositiveSweatSource(claim)
            OdorTemporal.AFTER_SEX -> !hasPostSexSource(claim)
            else -> false
        }
        return if (mismatch) {
            OdorIssue(
                OdorCodes.TEMPORAL_SOURCE,
                IssueSeverity.RED,
                "时间档解释与来源不符：运动后须有正汗源，性活动后须有正汗/前液/精液源",
            )
        } else {
            null
        }
    }

    private fun hasPostSexSource(claim: OdorClaim): Boolean {
        if (!hasLowerBody(claim.odorRegion)) return hasPositiveSweatSource(claim)
        return claim.lowerBodyOrigins.any { origin ->
            when (origin) {
                is SweatSource -> origin.volume.level > 0
                is FluidResidueSource -> origin.volume.level > 0 &&
                    origin.kind in setOf(FluidKind.SWEAT, FluidKind.PRE_SEMINAL, FluidKind.SEMEN)
                is SmegmaSource -> false
            }
        }
    }

    fun freshSweatIssue(claim: OdorClaim): OdorIssue? {
        val sour = OdorWord.SOUR in claim.odorWord && hasPositiveSweatSource(claim)
        return if (claim.freshSweat && sour) {
            OdorIssue(OdorCodes.FRESH_SWEAT_SOUR, IssueSeverity.RED, "刚出的汗几乎无味：不能立刻写成酸臭")
        } else {
            null
        }
    }

    fun baselineIssue(claim: OdorClaim): OdorIssue? {
        val before = claim.baseline ?: return null
        val changed = before != claim.odorPresent
        val driven = claim.changeDriver != null && claim.changeDriver != OdorChangeDriver.NONE
        return if (changed && !driven) {
            OdorIssue(OdorCodes.BASELINE_NO_DRIVER, IssueSeverity.RED, "跨场气味基线变化没有驱动：补清洗/运动/换衣等来源")
        } else {
            null
        }
    }
}
