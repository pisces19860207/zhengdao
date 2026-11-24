package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.environment.EnvironmentSolver
import com.example.zhengdao.baopu.state.fluid.DrynessGrade
import com.example.zhengdao.baopu.state.fluid.FluidKind
import com.example.zhengdao.baopu.state.fluid.VolumeGrade
import com.example.zhengdao.baopu.state.surfaceflow.FlowVolume
import com.example.zhengdao.baopu.state.surfaceflow.SweatZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdorJudgeTest {

    private fun hotEnv() = EnvironmentSolver.solve(31.0, 200, 12.0, 0, 0.0, 32.0, 85.0, 3, 0)

    private fun coldEnv() = EnvironmentSolver.solve(31.0, 200, 12.0, 0, 0.0, 5.0, 30.0, 0, 0)

    private fun coldHumidEnv() = EnvironmentSolver.solve(31.0, 200, 12.0, 0, 0.0, 5.0, 90.0, 0, 0)

    private fun mildEnv() = EnvironmentSolver.solve(31.0, 200, 12.0, 0, 0.0, 22.0, 50.0, 0, 0)

    private fun base() = OdorClaim(
        odorPresent = OdorPresence.NOTICEABLE,
        odorSource = setOf(OdorSource.BODY_SWEAT),
        odorWord = setOf(OdorWord.SOUR),
        odorRegion = setOf(OdorRegion.AXILLA),
        odorTemporal = OdorTemporal.DAYS_UNWASHED,
        odorSeasonal = OdorSeason.SUMMER_HOT,
        climateEvidence = ClimateEvidence(env657 = hotEnv()),
        odorClimateTrend = Trend.UP,
    )

    private fun reading(claim: OdorClaim = base()): OdorReading = requireNotNull(Odor.judge(claim).value)

    @Test
    fun `完整八件_通过且字段原样带出`() {
        val claim = base().copy(smellIntensity = SmellIntensityRef(2))
        val verdict = Odor.judge(claim)
        assertTrue(verdict.ok)
        assertEquals(OdorPresence.NOTICEABLE, verdict.value?.odorPresent)
        assertEquals(setOf(OdorSource.BODY_SWEAT), verdict.value?.odorSource)
        assertEquals(setOf(OdorWord.SOUR), verdict.value?.odorWord)
        assertEquals(setOf(OdorRegion.AXILLA), verdict.value?.odorRegion)
        assertEquals("883", verdict.value?.smellIntensity?.source)
    }

    @Test
    fun `四核心与三追加_各缺一件即红`() {
        val cases = mapOf(
            OdorCodes.CORE_PRESENT to base().copy(odorPresent = null),
            OdorCodes.CORE_SOURCE to base().copy(odorSource = emptySet()),
            OdorCodes.CORE_WORD to base().copy(odorWord = emptySet()),
            OdorCodes.CORE_REGION to base().copy(odorRegion = emptySet()),
            OdorCodes.CORE_TEMPORAL to base().copy(odorTemporal = null),
            OdorCodes.CORE_SEASONAL to base().copy(odorSeasonal = null),
            OdorCodes.CORE_CLIMATE to base().copy(climateEvidence = null),
            OdorCodes.CORE_CLIMATE_TREND to base().copy(odorClimateTrend = null),
        )
        cases.forEach { (code, claim) ->
            val verdict = Odor.judge(claim)
            assertNull(code, verdict.value)
            assertTrue(code, verdict.reds.any { it.code == code })
        }
    }

    @Test
    fun `无气味_来源与词必须空_分区时间季节仍必填`() {
        val claim = base().copy(
            odorPresent = OdorPresence.NONE,
            odorSource = emptySet(),
            odorWord = emptySet(),
            climateEvidence = ClimateEvidence(env657 = mildEnv()),
            odorClimateTrend = Trend.FLAT,
        )
        assertTrue(Odor.judge(claim).ok)
        val bad = claim.copy(odorWord = setOf(OdorWord.SWEAT))
        assertTrue(Odor.judge(bad).reds.any { it.code == OdorCodes.ZERO_WITH_DETAIL })
    }

    @Test
    fun `无气味_883非零强度即红但零档可一致`() {
        val claim = base().copy(
            odorPresent = OdorPresence.NONE,
            odorSource = emptySet(),
            odorWord = emptySet(),
            climateEvidence = ClimateEvidence(env657 = mildEnv()),
            odorClimateTrend = Trend.FLAT,
        )
        val bad = claim.copy(smellIntensity = SmellIntensityRef(3))
        assertTrue(Odor.judge(bad).reds.any { it.code == OdorCodes.ZERO_WITH_INTENSITY })
        assertTrue(Odor.judge(claim.copy(smellIntensity = SmellIntensityRef(0))).ok)
    }

    @Test
    fun `下体有味_无三选一来源必红`() {
        val claim = base().copy(odorRegion = setOf(OdorRegion.SCROTUM))
        val verdict = Odor.judge(claim)
        assertNull(verdict.value)
        assertTrue(verdict.reds.any { it.code == OdorCodes.LOWER_NO_SOURCE })
    }

    @Test
    fun `下体来源_883垢780三液806汗三路都放行`() {
        val origins = listOf(
            SmegmaSource(1),
            FluidResidueSource(FluidKind.PRE_SEMINAL, VolumeGrade.TRACE, DrynessGrade.WET),
            FluidResidueSource(FluidKind.SEMEN, VolumeGrade.MODERATE, DrynessGrade.TACKY),
            FluidResidueSource(FluidKind.SWEAT, VolumeGrade.TRACE, DrynessGrade.WET),
            SweatSource(SweatZone.GROIN, FlowVolume.TRACE),
        )
        origins.forEach { origin ->
            val claim = base().copy(
                odorSource = emptySet(),
                odorRegion = setOf(OdorRegion.CORONAL_SULCUS),
                climateEvidence = ClimateEvidence(env657 = mildEnv()),
                odorClimateTrend = Trend.FLAT,
                lowerBodyOrigins = setOf(origin),
            )
            assertTrue(origin.label, Odor.judge(claim).ok)
        }
    }

    @Test
    fun `混合分区_下体来源不能替代腋下来源`() {
        val claim = base().copy(
            odorSource = emptySet(),
            odorRegion = setOf(OdorRegion.AXILLA, OdorRegion.SCROTUM),
            lowerBodyOrigins = setOf(SmegmaSource(1)),
        )
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.CORE_SOURCE })
        assertTrue(Odor.judge(claim.copy(odorSource = setOf(OdorSource.BODY_SWEAT))).ok)
    }

    @Test
    fun `下体来源_零档不算来源`() {
        val origins = listOf(
            SmegmaSource(0),
            FluidResidueSource(FluidKind.SEMEN, VolumeGrade.NONE, DrynessGrade.WET),
            SweatSource(SweatZone.GROIN, FlowVolume.NONE),
        )
        origins.forEach { origin ->
            val claim = base().copy(
                odorRegion = setOf(OdorRegion.SCROTUM),
                lowerBodyOrigins = setOf(origin),
            )
            assertTrue(origin.label, Odor.judge(claim).reds.any { it.code == OdorCodes.LOWER_NO_SOURCE })
        }
    }

    @Test
    fun `肛周_默认不写即红`() {
        val claim = base().copy(odorRegion = setOf(OdorRegion.PERIANAL))
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.REGION_REDLINE })
    }

    @Test
    fun `刚清洗后明显气味_未明写未洗净即红`() {
        val claim = base().copy(odorTemporal = OdorTemporal.JUST_WASHED)
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.TEMPORAL_WASH })
        assertTrue(Odor.judge(claim.copy(note = "未洗净")).ok)
        assertTrue(Odor.judge(claim.copy(note = "没洗净")).ok)
        assertTrue(Odor.judge(claim.copy(note = "检查发现没洗净")).ok)
        assertTrue(Odor.judge(claim.copy(note = "不排除天气原因，他明确没洗净")).ok)
        listOf(
            "并非未洗净", "并不是未洗净", "不是没洗净", "没有没洗净",
            "若没洗净，洗过仍有味", "可能没洗净", "他说“没洗净”", "他说『没洗净』", "没洗净？",
            "不排除没洗净", "待核实没洗净", "尚需确认没洗净",
        ).forEach { note ->
            assertTrue(note, Odor.judge(claim.copy(note = note)).reds.any {
                it.code == OdorCodes.TEMPORAL_WASH
            })
        }
    }

    @Test
    fun `刚出汗就酸臭_结构化与文案双红`() {
        val claim = base().copy(freshSweat = true)
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.FRESH_SWEAT_SOUR })
        val text = base().copy(note = "刚出汗就酸臭")
        assertTrue(Odor.judge(text).reds.any { it.code == OdorCodes.FRESH_SWEAT_SOUR })
    }

    @Test
    fun `新汗酸臭_下体正汗源也红_零量汗源不算`() {
        val lower = base().copy(
            odorTemporal = OdorTemporal.AFTER_EXERCISE,
            odorSource = emptySet(),
            odorRegion = setOf(OdorRegion.SCROTUM),
            lowerBodyOrigins = setOf(SweatSource(SweatZone.GROIN, FlowVolume.TRACE)),
        )
        assertTrue(Odor.judge(lower.copy(freshSweat = true)).reds.any {
            it.code == OdorCodes.FRESH_SWEAT_SOUR
        })
        val zeroSweat = lower.copy(
            odorPresent = OdorPresence.FAINT,
            odorTemporal = OdorTemporal.SAME_DAY,
            odorClimateTrend = Trend.FLAT,
            lowerBodyOrigins = setOf(
                SmegmaSource(2),
                SweatSource(SweatZone.GROIN, FlowVolume.NONE),
            ),
            freshSweat = true,
        )
        assertTrue(Odor.judge(zeroSweat).ok)
    }

    @Test
    fun `时间档_当日只能淡_夜间不额外限档`() {
        val sameDay = base().copy(odorTemporal = OdorTemporal.SAME_DAY)
        assertTrue(Odor.judge(sameDay).reds.any { it.code == OdorCodes.TEMPORAL_TIER })
        assertTrue(Odor.judge(sameDay.copy(odorPresent = OdorPresence.FAINT)).ok)
        assertTrue(Odor.judge(base().copy(odorTemporal = OdorTemporal.NIGHT)).ok)
    }

    @Test
    fun `时间档_数日未洗须至少明显`() {
        val claim = base().copy(odorTemporal = OdorTemporal.DAYS_UNWASHED)
        assertTrue(Odor.judge(claim.copy(odorPresent = OdorPresence.FAINT)).reds.any {
            it.code == OdorCodes.TEMPORAL_TIER
        })
        assertTrue(Odor.judge(claim).ok)
    }

    @Test
    fun `时间档_运动后无正汗源即红`() {
        val claim = base().copy(
            odorTemporal = OdorTemporal.AFTER_EXERCISE,
            odorSource = setOf(OdorSource.CLOTHING_SMOKE),
            odorWord = setOf(OdorWord.SMOKE),
            odorClimateTrend = Trend.FLAT,
        )
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.TEMPORAL_SOURCE })
        assertTrue(Odor.judge(base()).ok)
    }

    @Test
    fun `时间档_性活动后须有正汗前液或精液源`() {
        val noSource = base().copy(
            odorSource = setOf(OdorSource.ENV_PERFUME),
            odorWord = setOf(OdorWord.SOAP),
            odorTemporal = OdorTemporal.AFTER_SEX,
            odorClimateTrend = Trend.FLAT,
        )
        assertTrue(Odor.judge(noSource).reds.any { it.code == OdorCodes.TEMPORAL_SOURCE })
        val withFluid = noSource.copy(
            odorSource = emptySet(),
            odorWord = setOf(OdorWord.SOUR),
            odorRegion = setOf(OdorRegion.SCROTUM),
            lowerBodyOrigins = setOf(
                FluidResidueSource(FluidKind.PRE_SEMINAL, VolumeGrade.TRACE, DrynessGrade.WET)
            ),
        )
        assertTrue(Odor.judge(withFluid).ok)
    }

    @Test
    fun `跨场基线变化_必须有驱动`() {
        val claim = base().copy(baseline = OdorPresence.FAINT)
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.BASELINE_NO_DRIVER })
        assertTrue(Odor.judge(claim.copy(changeDriver = OdorChangeDriver.EXERCISE)).ok)
        assertTrue(Odor.judge(base().copy(baseline = OdorPresence.NOTICEABLE)).ok)
    }

    @Test
    fun `热湿_657与730都推汗味向上`() {
        assertEquals(Trend.UP, reading().climate.trend)
        val from730 = base().copy(climateEvidence = ClimateEvidence(thermo730 = ThermoTierRef(3, 3, 3)))
        assertEquals("730", reading(from730).climate.source)
        assertEquals(Trend.UP, reading(from730).climate.trend)
    }

    @Test
    fun `热湿_无汗源的衣物气味不推升`() {
        val claim = base().copy(
            odorSource = setOf(OdorSource.CLOTHING_SMOKE),
            odorWord = setOf(OdorWord.SMOKE),
            odorClimateTrend = Trend.FLAT,
        )
        assertEquals(Trend.FLAT, reading(claim).climate.trend)
        assertTrue(Odor.judge(claim.copy(odorClimateTrend = Trend.UP)).reds.any {
            it.code == OdorCodes.CLIMATE_REVERSED
        })
    }

    @Test
    fun `热湿_下体汗源也推升`() {
        val origins = listOf(
            SweatSource(SweatZone.GROIN, FlowVolume.TRACE),
            FluidResidueSource(FluidKind.SWEAT, VolumeGrade.TRACE, DrynessGrade.WET),
        )
        origins.forEach { origin ->
            val claim = base().copy(
                odorSource = emptySet(),
                odorRegion = setOf(OdorRegion.SCROTUM),
                lowerBodyOrigins = setOf(origin),
            )
            assertEquals(origin.label, Trend.UP, reading(claim).climate.trend)
        }
    }

    @Test
    fun `热湿_零量下体汗源不推升`() {
        val claim = base().copy(
            odorPresent = OdorPresence.FAINT,
            odorSource = emptySet(),
            odorRegion = setOf(OdorRegion.SCROTUM),
            odorTemporal = OdorTemporal.SAME_DAY,
            odorClimateTrend = Trend.FLAT,
            lowerBodyOrigins = setOf(
                SmegmaSource(2),
                SweatSource(SweatZone.GROIN, FlowVolume.NONE),
            ),
        )
        assertEquals(Trend.FLAT, reading(claim).climate.trend)
    }

    @Test
    fun `干冷_730即使汗档高仍推气候向下`() {
        val claim = base().copy(
            odorSeasonal = OdorSeason.AUTUMN_WINTER_COLD,
            climateEvidence = ClimateEvidence(thermo730 = ThermoTierRef(0, 0, 3)),
            odorClimateTrend = Trend.DOWN,
        )
        assertEquals(Trend.DOWN, reading(claim).climate.trend)
    }

    @Test
    fun `干冷_730湿度零档作证且657不越权`() {
        val only657 = base().copy(
            odorSeasonal = OdorSeason.AUTUMN_WINTER_COLD,
            climateEvidence = ClimateEvidence(env657 = coldEnv()),
            odorClimateTrend = Trend.FLAT,
        )
        assertEquals(Trend.FLAT, reading(only657).climate.trend)
        val claim = only657.copy(
            climateEvidence = ClimateEvidence(env657 = coldEnv(), thermo730 = ThermoTierRef(0, 0, 0)),
            odorClimateTrend = Trend.DOWN,
        )
        assertEquals(Trend.DOWN, reading(claim).climate.trend)
        val from730 = claim.copy(climateEvidence = ClimateEvidence(thermo730 = ThermoTierRef(0, 0, 0)))
        assertEquals("730", reading(from730).climate.source)
        assertEquals(Trend.DOWN, reading(from730).climate.trend)
    }

    @Test
    fun `冷湿_657单证或冷湿730都不推干冷`() {
        val claim = base().copy(
            climateEvidence = ClimateEvidence(env657 = coldHumidEnv()),
            odorClimateTrend = Trend.FLAT,
        )
        assertEquals(Trend.FLAT, reading(claim).climate.trend)
        val humid730 = claim.copy(
            climateEvidence = ClimateEvidence(env657 = coldHumidEnv(), thermo730 = ThermoTierRef(0, 3, 0)),
        )
        assertEquals(Trend.FLAT, reading(humid730).climate.trend)
    }

    @Test
    fun `热湿反写向下_干冷反写向上都红`() {
        val hotReversed = base().copy(odorClimateTrend = Trend.DOWN)
        assertTrue(Odor.judge(hotReversed).reds.any { it.code == OdorCodes.CLIMATE_REVERSED })
        val coldReversed = base().copy(
            odorSeasonal = OdorSeason.AUTUMN_WINTER_COLD,
            climateEvidence = ClimateEvidence(env657 = coldEnv(), thermo730 = ThermoTierRef(0, 0, 0)),
            odorClimateTrend = Trend.UP,
        )
        assertTrue(Odor.judge(coldReversed).reds.any { it.code == OdorCodes.CLIMATE_REVERSED })
    }

    @Test
    fun `657与730方向打架_红不出值`() {
        val claim = base().copy(
            climateEvidence = ClimateEvidence(env657 = hotEnv(), thermo730 = ThermoTierRef(0, 0, 0)),
        )
        assertTrue(Odor.judge(claim).reds.any { it.code == OdorCodes.CLIMATE_CONFLICT })
    }

    @Test
    fun `季节微气候与上游相反_红_中性档不越权`() {
        val coldInSummer = base().copy(
            climateEvidence = ClimateEvidence(thermo730 = ThermoTierRef(0, 0, 0)),
            odorClimateTrend = Trend.DOWN,
        )
        assertTrue(Odor.judge(coldInSummer).reds.any {
            it.code == OdorCodes.SEASON_CLIMATE_CONFLICT
        })
        val hotInWinter = base().copy(odorSeasonal = OdorSeason.AUTUMN_WINTER_COLD)
        assertTrue(Odor.judge(hotInWinter).reds.any {
            it.code == OdorCodes.SEASON_CLIMATE_CONFLICT
        })
        val neutral = base().copy(
            climateEvidence = ClimateEvidence(env657 = mildEnv()),
            odorClimateTrend = Trend.FLAT,
        )
        assertTrue(Odor.judge(neutral).ok)
    }

    @Test
    fun `四季方向_四档齐且秋冬与暖气保留相反方向`() {
        val directions = reading().odorSeasonal
        assertEquals(OdorSeason.entries.map { it }, directions.map { it.season })
        val cold = directions.single { it.season == OdorSeason.AUTUMN_WINTER_COLD }
        assertEquals(Trend.DOWN, cold.bodyOdor)
        assertEquals(Trend.UP, cold.clothingResidue)
        val indoor = directions.single { it.season == OdorSeason.INDOOR_DRY }
        assertEquals(Trend.DOWN, indoor.bodyOdor)
        assertEquals(Trend.UP, indoor.clothingResidue)
        val summer = directions.single { it.season == OdorSeason.SUMMER_HOT }
        assertEquals(Trend.UP, summer.genitalOdor)
    }

    @Test
    fun `干痕或干涸_写完全无味只提示不判红`() {
        val claim = base().copy(
            odorPresent = OdorPresence.NONE,
            odorSource = emptySet(),
            odorWord = emptySet(),
            climateEvidence = ClimateEvidence(env657 = mildEnv()),
            odorClimateTrend = Trend.FLAT,
            residueDryness = DrynessGrade.DRY_MARK,
        )
        val verdict = Odor.judge(claim)
        assertTrue(verdict.ok)
        assertTrue(verdict.prompts.any { it.code == OdorCodes.DRY_MARK_SILENT })
    }

    @Test
    fun `同输入_读数与渲染逐字确定`() {
        val claim = base().copy(
            odorSource = linkedSetOf(
                OdorSource.BODY_SWEAT,
                OdorSource.CLOTHING_SMOKE,
                OdorSource.ORAL,
            ),
            odorWord = linkedSetOf(OdorWord.SOUR, OdorWord.SMOKE),
            odorRegion = linkedSetOf(
                OdorRegion.AXILLA,
                OdorRegion.SCALP,
                OdorRegion.ORAL,
                OdorRegion.SCROTUM,
            ),
            lowerBodyOrigins = linkedSetOf(
                SmegmaSource(1),
                SweatSource(SweatZone.GROIN, FlowVolume.TRACE),
            ),
        )
        val a = Odor.judge(claim)
        val b = Odor.judge(claim)
        assertEquals(a, b)
        val value = requireNotNull(a.value)
        assertEquals(Odor.render(value), Odor.render(requireNotNull(b.value)))
        val reordered = value.copy(
            odorSource = linkedSetOf(*value.odorSource.reversed().toTypedArray()),
            odorWord = linkedSetOf(*value.odorWord.reversed().toTypedArray()),
            odorRegion = linkedSetOf(*value.odorRegion.reversed().toTypedArray()),
            lowerBodyOrigins = linkedSetOf(*value.lowerBodyOrigins.reversed().toTypedArray()),
        )
        assertEquals(value, reordered)
        assertEquals(Odor.render(value), Odor.render(reordered))
    }

    @Test
    fun `渲染_含四季来源883与免责声明_不含点值`() {
        val value = reading(base().copy(smellIntensity = SmellIntensityRef(1)))
        val text = Odor.render(value)
        assertTrue(text.contains("四季方向["))
        assertTrue(text.contains("引用883=档1"))
        assertTrue(text.contains(ODOR_DISCLAIMER))
        assertFalse(text.contains("pH"))
        assertNotNull(value.climate)
    }
}
