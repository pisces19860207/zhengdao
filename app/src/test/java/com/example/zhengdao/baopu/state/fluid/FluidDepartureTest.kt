// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · ⑥ 离体三型与并存：喷射（盆底驱动＋落点）/ 甩出（无盆底、切向、方向）
// / 重力流（贴体无初速）；判据⑤三型互换判红、判据⑥缺项与「到处都是」判红、判据⑦点值判红。
package com.example.zhengdao.baopu.state.fluid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidDepartureTest {

    private val landing = Landing("小腹", SurfaceKind.SKIN)

    private fun ejection(): Ejection = requireNotNull(
        FluidDeparture.ejection(EjectionRequest(PelvicDrive.STRONG, landing)).value
    )

    private fun shed(): Shed = requireNotNull(
        FluidDeparture.shed(ShedRequest(SwingIntensity.MID, DetachDir.TANGENTIAL, SwingDir.SIDE)).value
    )

    private fun seepage(): Seepage = requireNotNull(FluidDeparture.seepage().value)

    // —— ⑥ 三型本体 ——

    @Test
    fun `喷射_初速沿开口轴向且其后只受重力`() {
        val e = ejection()
        assertEquals(DepartureKind.EJECTION, e.kind)
        assertEquals(SpeedAxis.MEATUS_AXIS, e.axis)
        assertEquals("初速之后只有重力", true, e.gravityOnly)
        assertEquals("落点必须留着", "小腹", e.landing.target)
    }

    @Test
    fun `喷射_缺盆底驱动判红`() {
        val r = FluidDeparture.ejection(EjectionRequest(null, landing))
        assertNull(r.value)
        assertEquals(FluidCodes.EJECT_NO_DRIVE, r.reds[0].code)
    }

    @Test
    fun `喷射_缺落点判红`() {
        val r = FluidDeparture.ejection(EjectionRequest(PelvicDrive.WEAK, null))
        assertNull(r.value)
        assertEquals(FluidCodes.EJECT_NO_LANDING, r.reds[0].code)
    }

    @Test
    fun `甩出_无盆底参与且初速是切向`() {
        val s = shed()
        assertEquals(DepartureKind.SHED_BY_MOTION, s.kind)
        assertEquals("甩出与盆底无关", false, s.pelvicInvolved)
        assertEquals(DetachDir.TANGENTIAL, s.detach)
    }

    @Test
    fun `甩出_缺强度缺方向各判红`() {
        assertEquals(
            FluidCodes.SHED_NO_INTENSITY,
            FluidDeparture.shed(ShedRequest(null, DetachDir.TANGENTIAL, SwingDir.UP)).reds[0].code,
        )
        assertEquals(
            FluidCodes.SHED_NO_DETACH,
            FluidDeparture.shed(ShedRequest(SwingIntensity.MID, null, SwingDir.UP)).reds[0].code,
        )
        assertEquals(
            FluidCodes.SHED_NO_DIR,
            FluidDeparture.shed(ShedRequest(SwingIntensity.MID, DetachDir.TANGENTIAL, null)).reds[0].code,
        )
    }

    @Test
    fun `甩出_脱离方向给法向判红`() {
        val r = FluidDeparture.shed(ShedRequest(SwingIntensity.MID, DetachDir.NORMAL, SwingDir.UP))
        assertNull(r.value)
        assertEquals(FluidCodes.SHED_NOT_TANGENTIAL, r.reds[0].code)
    }

    @Test
    fun `重力流_贴体无初速且没有抛物线这条路`() {
        val s = seepage()
        assertEquals(DepartureKind.SEEPAGE, s.kind)
        assertEquals("重力流只能贴体", true, s.attachedToBody)
    }

    // —— 并存 ——

    @Test
    fun `并存_两段分别记且标mixed`() {
        val set = requireNotNull(FluidDeparture.set(ejection(), seepage()).value)
        assertEquals(DepartureKind.MIXED, set.kind)
        assertEquals(listOf(DepartureKind.EJECTION, DepartureKind.SEEPAGE), set.kinds)
        assertEquals("两段都在", 2, set.segments.size)
        val text = FluidRenderer.renderDeparture(set)
        assertTrue("并存要逐段列：$text", text.startsWith("并存[喷射(") && text.contains("/重力流("))
    }

    @Test
    fun `并存_同一型记两段判红_且空集判红`() {
        assertEquals(FluidCodes.DEPART_DUP, FluidDeparture.set(ejection(), ejection()).reds[0].code)
        assertEquals(FluidCodes.DEPART_EMPTY, FluidDeparture.set().reds[0].code)
        val thrown = runCatching { DepartureSet(emptyList()) }.exceptionOrNull()
        assertTrue("空离体集构造即拒：$thrown", thrown is IllegalArgumentException)
    }

    // —— 判据⑤ 三型互换 ——

    @Test
    fun `判据五_三型各自的正例文案都过闸`() {
        assertTrue(FluidChecks.kindSwap(DepartureKind.EJECTION, FluidRenderer.renderSegment(ejection())).isEmpty())
        assertTrue(FluidChecks.kindSwap(DepartureKind.SHED_BY_MOTION, FluidRenderer.renderSegment(shed())).isEmpty())
        assertTrue(FluidChecks.kindSwap(DepartureKind.SEEPAGE, FluidRenderer.renderSegment(seepage())).isEmpty())
    }

    @Test
    fun `判据五_甩出写成喷射判红`() {
        // 除「抛物线」外样样都写对：切向有了、无盆底有了、没写初速——只错这一处。
        val issues = FluidChecks.kindSwap(
            DepartureKind.SHED_BY_MOTION,
            "甩出(脱离=切向/无盆底参与)，抛物线落在床头",
        )
        assertEquals("只该咬抛物线这一处", 1, issues.size)
        assertEquals(FluidCodes.DEPART_SWAP, issues[0].code)
        assertTrue(issues[0].detail.contains("抛物线"))
    }

    @Test
    fun `判据五_甩出写成有盆底参与判红`() {
        val issues = FluidChecks.kindSwap(DepartureKind.SHED_BY_MOTION, "甩出，盆底发力")
        assertTrue("盆底参与不是甩出：" + issues.map { it.detail }, issues.isNotEmpty())
    }

    @Test
    fun `判据五_喷射写成贴体流判红`() {
        val issues = FluidChecks.kindSwap(DepartureKind.EJECTION, "喷射，贴体往下淌")
        assertTrue(issues.isNotEmpty())
        assertEquals(FluidCodes.DEPART_SWAP, issues[0].code)
    }

    @Test
    fun `判据五_重力流写成抛物线判红`() {
        val a = FluidChecks.kindSwap(DepartureKind.SEEPAGE, "重力流，抛物线落下")
        assertTrue("抛物线是喷射的写法", a.isNotEmpty())
        val b = FluidChecks.kindSwap(DepartureKind.SEEPAGE, "重力流，初速沿开口轴向")
        assertTrue("无初速才是重力流", b.isNotEmpty())
    }

    @Test
    fun `判据五_重力流写无初速不算写初速`() {
        assertTrue(FluidChecks.kindSwap(DepartureKind.SEEPAGE, "重力流(贴体/无初速)").isEmpty())
    }

    // —— 判据⑥ 到处都是 ——

    @Test
    fun `判据六_到处都是却没有落点判红`() {
        val red = FluidChecks.vagueLanding("到处都是", null)
        assertNotNull(red)
        assertEquals(FluidCodes.VAGUE_LANDING, red?.code)
        assertNull("有落点就随便怎么说", FluidChecks.vagueLanding("到处都是", landing))
    }

    // —— 判据⑦ 点值 ——

    @Test
    fun `判据七_点值单位逐个人咬住`() {
        listOf("5毫升", "12cm", "3mL", "10ml", "40厘米", "两秒", "pH7", "成分不明", "1.5米")
            .forEach { token ->
                assertTrue("必须咬住 $token", FluidChecks.pointValueUnits(token).isNotEmpty())
            }
    }

    @Test
    fun `判据七_六段渲染全文没有点值`() {
        val body = FluidBody(
            GradeRead(VolumeGrade.MODERATE, listOf(ChangeDriver.EVENT)),
            GradeRead(ViscosityGrade.CREAMY, listOf(ChangeDriver.TIME)),
            GradeRead(FluidTempGrade.WARM, listOf(ChangeDriver.TEMP_HUMIDITY)),
            GradeRead(DrynessGrade.TACKY, listOf(ChangeDriver.EXPOSURE)),
        )
        val texts = listOf(
            FluidRenderer.renderChange(body),
            FluidRenderer.renderMix(
                requireNotNull(FluidMixer.mix(MixRequest(FluidKind.PRE_SEMINAL, FluidKind.SEMEN)).value)
            ),
            FluidRenderer.renderDroop(
                requireNotNull(FluidDroop.judge(DroopRequest(Posture.PRONE, VolumeGrade.TRACE, ViscosityGrade.THIN)).value)
            ),
            FluidRenderer.renderFabric(
                FluidFabric.propagate(listOf(FabricMaterial.COTTON), VolumeGrade.TRACE, ViscosityGrade.THIN)
            ),
            FluidRenderer.renderCoverage(
                requireNotNull(FluidCoverage.judge(CoverageClaim(VolumeGrade.TRACE, Coverage.LOCAL, null)).value)
            ),
            FluidRenderer.renderDeparture(requireNotNull(FluidDeparture.set(ejection(), shed(), seepage()).value)),
        )
        assertEquals("六节各一段", 6, texts.size)
        texts.forEach { assertTrue("不得含点值：$it", FluidChecks.pointValueUnits(it).isEmpty()) }
        assertTrue("每条都要带非诊断附言", texts.all { it.endsWith(FLUID_DISCLAIMER) })
    }
}
