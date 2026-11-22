// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 判据①（四维各带驱动）③（干涸复湿须有新来源）⑧（确定性）。
package com.example.zhengdao.baopu.state.fluid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidChangeTest {

    /** 基线体：量微/偏稀，各挂一条事件驱动；温与干都在零档，不挂驱动。 */
    private fun baseline(
        volume: VolumeGrade = VolumeGrade.TRACE,
        viscosity: ViscosityGrade = ViscosityGrade.THIN,
        dryness: DrynessGrade = DrynessGrade.WET,
    ): FluidBody = FluidBody(
        volume = GradeRead(volume, listOf(ChangeDriver.EVENT)),
        viscosity = GradeRead(viscosity, listOf(ChangeDriver.EVENT)),
        temperature = GradeRead(FluidTempGrade.MILD, emptyList()),
        dryness = GradeRead(dryness, if (dryness.zero) emptyList() else listOf(ChangeDriver.TIME)),
    )

    /** 许久 + 迎风 + 温和 + 无事件：时间与暴露一起把水带走。 */
    private val blowing = FluidStep(ElapsedBand.LONG, ExposureBand.AIRFLOW, EnvBand.MILD, EventKind.NONE)

    // —— ① 四维变化 ——

    @Test
    fun `判据一_迎风久置四维各走各的`() {
        val after = FluidChange.step(baseline(), blowing).value
        assertNotNull("这一步应当算出结果", after)
        requireNotNull(after)
        assertEquals("干涸档：时间+裸露+迎风三档推力", DrynessGrade.DRIED, after.dryness.grade)
        assertEquals("黏稠档随干涸升一档", ViscosityGrade.CREAMY, after.viscosity.grade)
        assertEquals("迎风久了量收一档", VolumeGrade.NONE, after.volume.grade)
        assertEquals("温和环境不动温度档", FluidTempGrade.MILD, after.temperature.grade)
    }

    @Test
    fun `判据一_四维驱动分别挂对`() {
        val after = FluidChange.step(baseline(), blowing).value
        requireNotNull(after)
        assertEquals(
            "干涸档的驱动恰为时间+暴露",
            listOf(ChangeDriver.TIME, ChangeDriver.EXPOSURE),
            after.dryness.drivers,
        )
        assertTrue(
            "黏稠档要同时留住历史驱动与本步驱动",
            after.viscosity.drivers.containsAll(listOf(ChangeDriver.EVENT, ChangeDriver.TIME)),
        )
        assertEquals("零档温度不挂驱动", emptyList<ChangeDriver>(), after.temperature.drivers)
    }

    @Test
    fun `判据一_新液让量档升档并挂事件驱动`() {
        val step = FluidStep(ElapsedBand.BRIEF, ExposureBand.COVERED, EnvBand.MILD, EventKind.FRESH_FLUID)
        val after = FluidChange.step(baseline(), step).value
        requireNotNull(after)
        assertEquals("新液加一档", VolumeGrade.MODERATE, after.volume.grade)
        assertEquals(listOf(ChangeDriver.EVENT), after.volume.drivers)
    }

    @Test
    fun `判据一_全零步什么都不变`() {
        val before = baseline()
        val after = FluidChange.step(before, FluidStep.STILL).value
        requireNotNull(after)
        assertEquals(before.dryness.grade, after.dryness.grade)
        assertEquals(before.viscosity.grade, after.viscosity.grade)
        assertEquals(before.volume.grade, after.volume.grade)
        assertEquals(FluidTempGrade.MILD, after.temperature.grade)
    }

    @Test
    fun `判据一_非零档无驱动判红_负例夹具`() {
        val bad = FluidBody(
            volume = GradeRead(VolumeGrade.TRACE, emptyList()),
            viscosity = GradeRead(ViscosityGrade.CREAMY, emptyList()),
            temperature = GradeRead(FluidTempGrade.MILD, emptyList()),
            dryness = GradeRead(DrynessGrade.TACKY, emptyList()),
        )
        assertEquals(listOf("量档", "黏稠档", "干涸档"), FluidChecks.missingDrivers(bad))
        val issues = FluidChecks.driverIssues(bad)
        assertEquals("三维各一条红", 3, issues.size)
        assertTrue("驱动缺失全是红", issues.all { it.severity == IssueSeverity.RED })
        assertTrue("码要指对", issues.all { it.code == FluidCodes.CHANGE_NO_DRIVER })
    }

    @Test
    fun `判据一_正例前提_求解器产出每维都有驱动`() {
        val after = FluidChange.step(baseline(), blowing).value
        requireNotNull(after)
        assertEquals(emptyList<String>(), FluidChecks.missingDrivers(after))
    }

    // —— ③ 干涸后复湿 ——

    @Test
    fun `判据三_干涸无新来源不许变湿_求解器不给这条路`() {
        val dried = baseline(dryness = DrynessGrade.DRIED)
        val after = FluidChange.step(dried, FluidStep.STILL).value
        requireNotNull(after) { "温和无事件的一步不该判红" }
        assertEquals("干涸原地不动", DrynessGrade.DRIED, after.dryness.grade)
        assertTrue("求解器不产生无来源的复湿", after.dryness.grade.level >= DrynessGrade.DRIED_LEVEL)
    }

    @Test
    fun `判据三_新液一步拉回湿润`() {
        val dried = baseline(dryness = DrynessGrade.DRIED)
        val step = FluidStep(ElapsedBand.BRIEF, ExposureBand.COVERED, EnvBand.MILD, EventKind.FRESH_FLUID)
        val after = FluidChange.step(dried, step).value
        requireNotNull(after)
        assertEquals("新来源把干涸拉回湿润", DrynessGrade.WET, after.dryness.grade)
        assertTrue("新液要挂事件驱动", after.volume.drivers.contains(ChangeDriver.EVENT))
    }

    @Test
    fun `判据三_干涸复湿无新来源判红_负例夹具`() {
        val red = FluidChecks.rewet(DrynessGrade.DRIED, DrynessGrade.WET, EventKind.NONE)
        assertNotNull("干涸→湿润且无来源必须红", red)
        assertEquals(FluidCodes.CHANGE_REWET_NO_SOURCE, red?.code)
        assertEquals(IssueSeverity.RED, red?.severity)

        val prompt = FluidChecks.rewet(DrynessGrade.DRY_MARK, DrynessGrade.TACKY, EventKind.CONTACT)
        assertEquals("干痕→初干只提示", IssueSeverity.PROMPT, prompt?.severity)

        assertNull("有新来源就不红", FluidChecks.rewet(DrynessGrade.DRIED, DrynessGrade.WET, EventKind.FRESH_FLUID))
        assertNull("变干不是复湿", FluidChecks.rewet(DrynessGrade.DRIED, DrynessGrade.DRIED, EventKind.NONE))
    }

    // —— ⑧ 确定性 ——

    @Test
    fun `判据八_同输入两次逐字节相同`() {
        val a = FluidRenderer.renderChange(requireNotNull(FluidChange.step(baseline(), blowing).value))
        val b = FluidRenderer.renderChange(requireNotNull(FluidChange.step(baseline(), blowing).value))
        assertEquals(a, b)
        assertTrue("渲染要真出四条维名", a.startsWith("四维[量=") && a.contains("黏=") &&
            a.contains("温=") && a.contains("干="))
    }
}
