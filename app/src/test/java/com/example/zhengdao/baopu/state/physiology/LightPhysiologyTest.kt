// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十八批 · 判据①③⑥：四档在域内可并存且互不覆盖、非零档必带驱动、确定性。
package com.example.zhengdao.baopu.state.physiology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightPhysiologyTest {

    /** 一组覆盖两端的输入，用于驱动/确定性遍历。 */
    private val samples = listOf(
        PhysioInputs.BASELINE,
        PhysioInputs(
            AmbientTempBand.HOT, HumidityBand.SATURATED, ActivityBand.INTENSE,
            DurationBand.PROLONGED, EmotionBand.INTENSE, IndividualBand.SENSITIVE,
        ),
        PhysioInputs(
            AmbientTempBand.COOL, HumidityBand.DRY, ActivityBand.MODERATE,
            DurationBand.LONG, EmotionBand.AROUSED, IndividualBand.TYPICAL,
        ),
        PhysioInputs(
            AmbientTempBand.WARM, HumidityBand.HUMID, ActivityBand.LIGHT,
            DurationBand.MEDIUM, EmotionBand.MILD, IndividualBand.HIGH,
        ),
    )

    // ── 判据① 四状态 ──────────────────────────────────────────────────────

    @Test
    fun `判据一_四状态各四档在域内`() {
        assertEquals(4, BodyTempShift.COUNT)
        assertEquals(4, HeartRateBand.COUNT)
        assertEquals(4, SweatLevel.COUNT)
        assertEquals(4, Fatigue.COUNT)
        listOf(
            BodyTempShift.values(), HeartRateBand.values(), SweatLevel.values(), Fatigue.values(),
        ).forEach { grades ->
            assertEquals("档数须为四", 4, grades.size)
            assertEquals("档序须为 0..3", listOf(0, 1, 2, 3), grades.map { it.level })
            assertEquals("档名不得重复", grades.map { it.label }.toSet().size, grades.size)
        }
        // 越界钳到端点，不产生域外档
        assertEquals(BodyTempShift.FEVER, BodyTempShift.of(99))
        assertEquals(HeartRateBand.REST, HeartRateBand.of(-7))
        assertEquals(SweatLevel.SOAKED, SweatLevel.of(99))
        assertEquals(Fatigue.AWAKE, Fatigue.of(-7))
    }

    @Test
    fun `判据一_四状态可同时取值互不覆盖`() {
        val s = LightPhysiology(
            bodyTempShift = GradeReading(BodyTempShift.HIGH, listOf(PhysioDriver.ENV_TEMP_HUMIDITY)),
            heartRateBand = GradeReading(HeartRateBand.MODERATE, listOf(PhysioDriver.ACTIVITY_INTENSITY)),
            sweatLevel = GradeReading(SweatLevel.OBVIOUS, listOf(PhysioDriver.DURATION)),
            fatigue = GradeReading(Fatigue.MID, listOf(PhysioDriver.ACTIVITY_INTENSITY)),
            fluids = emptyFluids(),
        )
        // 「中疲 + 明显出汗」同一时刻并存，互不顶替
        assertEquals(Fatigue.MID, s.fatigue.grade)
        assertEquals(SweatLevel.OBVIOUS, s.sweatLevel.grade)
        val changed = s.copy(fatigue = GradeReading(Fatigue.EXHAUSTED, listOf(PhysioDriver.DURATION)))
        assertEquals("改疲劳不得动出汗", s.sweatLevel, changed.sweatLevel)
        assertEquals("改疲劳不得动体温", s.bodyTempShift, changed.bodyTempShift)
        assertEquals("改疲劳不得动心率", s.heartRateBand, changed.heartRateBand)
        assertNotEquals(s.fatigue, changed.fatigue)
    }

    @Test
    fun `判据一_出汗表现档与汗液分道是两个独立字段`() {
        // 整体出汗表现档 = 明显；汗液分道单独记一条独立读数，两者不互相覆盖。
        val s = LightPhysiology(
            bodyTempShift = GradeReading(BodyTempShift.NORMAL, emptyList()),
            heartRateBand = GradeReading(HeartRateBand.REST, emptyList()),
            sweatLevel = GradeReading(SweatLevel.OBVIOUS, listOf(PhysioDriver.ACTIVITY_INTENSITY)),
            fatigue = GradeReading(Fatigue.AWAKE, emptyList()),
            fluids = emptyFluids(),
        )
        assertEquals(SweatLevel.OBVIOUS, s.sweatLevel.grade)
        assertEquals("三液的汗分道须独立可读", FluidAmount.NONE, s.fluids.sweat.amount)
        val onlyFluid = s.copy(fluids = s.fluids.copy(
            sweat = FluidTrack(true, FluidAmount.MODERATE, listOf(PhysioDriver.ENV_TEMP_HUMIDITY)),
        ))
        assertEquals("改汗液分道不得动出汗表现档", s.sweatLevel, onlyFluid.sweatLevel)
        assertNotEquals(s.fluids.sweat, onlyFluid.fluids.sweat)
    }

    @Test
    fun `判据一_求解器四态可各取不同档互不顶替`() {
        val s = LightPhysiologySolver.solve(samples[3])
        // 这组输入下出汗到顶档、疲劳仍是轻档：两者各自算，不互相覆盖。
        assertEquals(SweatLevel.SOAKED, s.sweatLevel.grade)
        assertEquals(Fatigue.LIGHT, s.fatigue.grade)
    }

    @Test
    fun `判据一_求解器三液分道各自独立`() {
        val s = LightPhysiologySolver.solve(samples[1])
        // 同一情绪输入，前液因个体差异档额外抬级，唾液不跟着抬——两条账互不覆盖。
        assertNotEquals(s.fluids.saliva.amount, s.fluids.preSeminal.amount)
        assertNotEquals(s.fluids.sweat.amount, s.fluids.saliva.amount)
    }

    // ── 判据③ 驱动 ────────────────────────────────────────────────────────

    @Test
    fun `判据三_求解器每个非零档都带至少一条驱动`() {
        samples.forEach { input ->
            val s = LightPhysiologySolver.solve(input)
            assertTrue("输入 $input 产出存在无驱动非零档：${PhysiologyChecks.missingDriverGrades(s)}",
                PhysiologyChecks.missingDriverGrades(s).isEmpty())
        }
    }

    @Test
    fun `判据三_清空驱动被检查器判红_负例夹具`() {
        val s = LightPhysiologySolver.solve(samples[2])
        val broken = s.copy(
            sweatLevel = GradeReading(SweatLevel.OBVIOUS, emptyList()),
        )
        val problems = PhysiologyChecks.missingDriverGrades(broken)
        assertTrue("必须咬住无驱动出汗档", problems.any { it.startsWith("sweatLevel:") })
        assertTrue("负例前提：原产出本来是干净的", PhysiologyChecks.missingDriverGrades(s).isEmpty())
    }

    @Test
    fun `判据三_三液非零档也须带驱动`() {
        val s = LightPhysiologySolver.solve(samples[1])
        val broken = s.copy(fluids = s.fluids.copy(
            saliva = s.fluids.saliva.copy(drivers = emptyList()),
        ))
        assertTrue("必须咬住无驱动唾液分道",
            PhysiologyChecks.missingDriverGrades(broken).any { it.startsWith("saliva:") })
    }

    // ── 判据⑥ 确定性 ──────────────────────────────────────────────────────

    @Test
    fun `判据六_同输入两次逐字节一致`() {
        samples.forEach { input ->
            val a = LightPhysiologySolver.solve(input)
            val b = LightPhysiologySolver.solve(input)
            assertEquals("产出对象须相等", a, b)
            assertEquals("渲染文本须逐字节一致", PhysioRenderer.render(a), PhysioRenderer.render(b))
        }
    }

    @Test
    fun `判据六_全部档位组合确定性`() {
        val temp = AmbientTempBand.values()
        val hum = HumidityBand.values()
        val act = ActivityBand.values()
        val dur = DurationBand.values()
        val emo = EmotionBand.values()
        val ind = IndividualBand.values()
        var n = 0
        for (t in temp) for (h in hum) for (a in act) for (d in dur) for (e in emo) for (g in ind) {
            val input = PhysioInputs(t, h, a, d, e, g)
            assertEquals("组合 $input 渲染须确定",
                PhysioRenderer.render(LightPhysiologySolver.solve(input)),
                PhysioRenderer.render(LightPhysiologySolver.solve(input)))
            n++
        }
        assertEquals("应覆盖 4 的 6 次方组合", 4096, n)
    }

    // ── 求解边界 ──────────────────────────────────────────────────────────

    @Test
    fun `基线输入产出全零档且三液皆无`() {
        val s = LightPhysiologySolver.solve(PhysioInputs.BASELINE)
        assertTrue(s.bodyTempShift.grade.zero)
        assertTrue(s.heartRateBand.grade.zero)
        assertTrue(s.sweatLevel.grade.zero)
        assertTrue(s.fatigue.grade.zero)
        assertTrue(!s.fluids.sweat.present)
        assertTrue(!s.fluids.saliva.present)
        assertTrue(!s.fluids.preSeminal.present)
        assertTrue(PhysiologyChecks.inconsistentFluidTracks(s).isEmpty())
    }

    @Test
    fun `极端输入四档到顶且三液有量档`() {
        val s = LightPhysiologySolver.solve(samples[1])
        assertEquals(BodyTempShift.FEVER, s.bodyTempShift.grade)
        assertEquals(HeartRateBand.INTENSE, s.heartRateBand.grade)
        assertEquals(SweatLevel.SOAKED, s.sweatLevel.grade)
        assertEquals(Fatigue.EXHAUSTED, s.fatigue.grade)
        assertTrue(s.fluids.sweat.present)
        assertTrue(s.fluids.saliva.present)
        assertTrue(s.fluids.preSeminal.present)
        assertTrue(PhysiologyChecks.inconsistentFluidTracks(s).isEmpty())
    }

    private fun emptyFluids(): FluidChannels = FluidChannels(
        sweat = FluidTrack(false, FluidAmount.NONE, emptyList()),
        saliva = FluidTrack(false, FluidAmount.NONE, emptyList()),
        preSeminal = FluidTrack(false, FluidAmount.NONE, emptyList()),
    )
}
