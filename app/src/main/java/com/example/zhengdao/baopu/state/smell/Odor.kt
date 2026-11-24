// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 对外门面：齐判据、译气候、出读数；零装配、零落库。
package com.example.zhengdao.baopu.state.smell

internal object Odor {

    fun judge(claim: OdorClaim): OdorVerdict<OdorReading> {
        val climateVerdict = claim.climateEvidence?.let {
            OdorRules.climateReading(it, claim.odorClimateTrend, OdorRules.hasPositiveSweatSource(claim))
        }
        val issues = OdorChecks.claimIssues(claim).toMutableList()
        climateVerdict?.let { issues += it.issues }
        if (issues.any { it.severity == IssueSeverity.RED }) return OdorVerdict(null, issues)

        val climate = requireNotNull(climateVerdict?.value)
        val reading = OdorReading(
            odorPresent = requireNotNull(claim.odorPresent),
            odorSource = claim.odorSource,
            odorWord = claim.odorWord,
            odorRegion = claim.odorRegion,
            odorTemporal = requireNotNull(claim.odorTemporal),
            currentSeason = requireNotNull(claim.odorSeasonal),
            odorSeasonal = OdorRules.seasonDirections(),
            climate = climate,
            smellIntensity = claim.smellIntensity,
            lowerBodyOrigins = claim.lowerBodyOrigins,
            note = claim.note,
        )
        return OdorVerdict(reading, issues)
    }

    fun render(reading: OdorReading): String = OdorRenderer.render(reading)
}
