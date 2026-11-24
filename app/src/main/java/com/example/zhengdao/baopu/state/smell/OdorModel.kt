// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 只读数据模型：字段是档位、枚举、集合与来源引用。
package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.fluid.DrynessGrade

internal enum class IssueSeverity(val label: String) {
    RED("红"),
    PROMPT("提示"),
}

internal data class OdorIssue(
    val code: String,
    val severity: IssueSeverity,
    val detail: String,
) {
    fun line(): String = "[" + severity.label + "]" + code + "：" + detail
}

internal data class OdorVerdict<out T>(
    val value: T?,
    val issues: List<OdorIssue>,
) {
    val reds: List<OdorIssue> get() = issues.filter { it.severity == IssueSeverity.RED }
    val prompts: List<OdorIssue> get() = issues.filter { it.severity == IssueSeverity.PROMPT }
    val ok: Boolean get() = value != null && reds.isEmpty()

    companion object {
        fun <T> of(value: T): OdorVerdict<T> = OdorVerdict(value, emptyList())

        fun <T> red(code: String, detail: String): OdorVerdict<T> =
            OdorVerdict(null, listOf(OdorIssue(code, IssueSeverity.RED, detail)))

        fun <T> prompt(value: T?, code: String, detail: String): OdorVerdict<T> =
            OdorVerdict(value, listOf(OdorIssue(code, IssueSeverity.PROMPT, detail)))
    }
}

/** 入参允许缺项，缺项由判定层给码；同输入必得同结果。 */
internal data class OdorClaim(
    val odorPresent: OdorPresence?,
    val odorSource: Set<OdorSource> = emptySet(),
    val odorWord: Set<OdorWord> = emptySet(),
    val odorRegion: Set<OdorRegion> = emptySet(),
    val odorTemporal: OdorTemporal? = null,
    val odorSeasonal: OdorSeason? = null,
    val climateEvidence: ClimateEvidence? = null,
    val odorClimateTrend: Trend? = null,
    val smellIntensity: SmellIntensityRef? = null,
    val lowerBodyOrigins: Set<LowerBodySource> = emptySet(),
    val residueDryness: DrynessGrade? = null,
    val freshSweat: Boolean = false,
    val baseline: OdorPresence? = null,
    val changeDriver: OdorChangeDriver? = null,
    val note: String = "",
)

/** 气候只引用 657/730 的档；sweatGrade 是上游原档，不是本批算出的强度。 */
internal data class ClimateReading(
    val source: String,
    val sweatGrade: Int,
    val trend: Trend,
    val why: String,
)

/** 一档季节方向；四档方向全部固定产出，缺一不可。 */
internal data class SeasonalDirection(
    val season: OdorSeason,
    val sweat: Trend,
    val humidity: Trend,
    val bodyOdor: Trend,
    val clothingResidue: Trend,
    val genitalOdor: Trend,
    val why: String,
)

/** 通过判定后的读数；强度可以缺省，但一旦出现必带 883 来源。 */
internal data class OdorReading(
    val odorPresent: OdorPresence,
    val odorSource: Set<OdorSource>,
    val odorWord: Set<OdorWord>,
    val odorRegion: Set<OdorRegion>,
    val odorTemporal: OdorTemporal,
    val currentSeason: OdorSeason,
    val odorSeasonal: List<SeasonalDirection>,
    val climate: ClimateReading,
    val smellIntensity: SmellIntensityRef?,
    val lowerBodyOrigins: Set<LowerBodySource>,
    val note: String,
)
