// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · ③ 重力垂坠（必须带体位）④ 织物吸收/渗透（三态、四材质、不许跳层）
// ⑤ 量-面积对应（量档定上限、全覆盖须载体）。
package com.example.zhengdao.baopu.state.fluid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidSurfaceTest {

    // —— ③ 垂坠倾向 ——

    @Test
    fun `判据三_站立多量稀薄垂坠到顶档`() {
        val r = FluidDroop.judge(DroopRequest(Posture.STANDING, VolumeGrade.COPIOUS, ViscosityGrade.RUNNY))
        assertEquals(DroopTendency.HEAVY, r.value?.tendency)
        assertEquals("体位随结果一起带出去", Posture.STANDING, r.value?.posture)
    }

    @Test
    fun `判据三_仰卧微量黏稠不垂`() {
        val r = FluidDroop.judge(DroopRequest(Posture.SUPINE, VolumeGrade.TRACE, ViscosityGrade.GLUTINOUS))
        assertEquals(DroopTendency.SLIGHT, r.value?.tendency)
        assertEquals(Posture.SUPINE, r.value?.posture)
    }

    @Test
    fun `判据三_坐姿中量稠也垂`() {
        val r = FluidDroop.judge(DroopRequest(Posture.SITTING, VolumeGrade.MODERATE, ViscosityGrade.CREAMY))
        assertEquals(DroopTendency.HEAVY, r.value?.tendency)
    }

    @Test
    fun `判据三_缺体位判红`() {
        val r = FluidDroop.judge(DroopRequest(null, VolumeGrade.COPIOUS, ViscosityGrade.RUNNY))
        assertNull("没体位就不出倾向档", r.value)
        assertEquals(FluidCodes.DROOP_NO_POSTURE, r.reds[0].code)
    }

    @Test
    fun `渲染_垂坠必带体位`() {
        val text = FluidRenderer.renderDroop(
            requireNotNull(FluidDroop.judge(DroopRequest(Posture.LATERAL, VolumeGrade.MODERATE, ViscosityGrade.THIN)).value)
        )
        assertTrue("体位要在文本里：$text", text.contains("垂坠=") && text.contains("体位=侧卧"))
    }

    // —— ④ 织物 ——

    @Test
    fun `判据四_四类材质三态各异`() {
        assertEquals(LayerOutcome.BEADED, FluidFabric.outcomeOf(FabricMaterial.SYNTHETIC, VolumeGrade.MODERATE, ViscosityGrade.THIN))
        assertEquals(LayerOutcome.BEADED, FluidFabric.outcomeOf(FabricMaterial.WOOL, VolumeGrade.MODERATE, ViscosityGrade.CREAMY))
        assertEquals(LayerOutcome.ABSORBED, FluidFabric.outcomeOf(FabricMaterial.WOOL, VolumeGrade.MODERATE, ViscosityGrade.THIN))
        assertEquals(LayerOutcome.PASSED_THROUGH, FluidFabric.outcomeOf(FabricMaterial.BLEND, VolumeGrade.MODERATE, ViscosityGrade.THIN))
        assertEquals(LayerOutcome.ABSORBED, FluidFabric.outcomeOf(FabricMaterial.COTTON, VolumeGrade.MODERATE, ViscosityGrade.THIN))
        assertEquals(LayerOutcome.PASSED_THROUGH, FluidFabric.outcomeOf(FabricMaterial.COTTON, VolumeGrade.COPIOUS, ViscosityGrade.THIN))
        FabricMaterial.values().forEach {
            assertEquals("无量就不触及：" + it.label, LayerOutcome.DRY, FluidFabric.outcomeOf(it, VolumeGrade.NONE, ViscosityGrade.THIN))
        }
        assertEquals("材质恰四类", 4, FabricMaterial.COUNT)
    }

    @Test
    fun `判据四_逐层推进不许跳层`() {
        val stack = FluidFabric.propagate(
            listOf(FabricMaterial.COTTON, FabricMaterial.WOOL, FabricMaterial.BLEND),
            VolumeGrade.COPIOUS,
            ViscosityGrade.RUNNY,
        )
        assertEquals(3, stack.layers.size)
        assertEquals(LayerOutcome.PASSED_THROUGH, stack.layers[0].outcome)
        assertEquals(LayerOutcome.ABSORBED, stack.layers[1].outcome)
        assertEquals("上一层吸收住了，第三层碰不到", LayerOutcome.DRY, stack.layers[2].outcome)
        assertTrue("自建栈不许跳层", FluidFabric.judge(stack).value != null)
    }

    @Test
    fun `判据四_化纤珠滚则内层全干`() {
        val stack = FluidFabric.propagate(
            listOf(FabricMaterial.SYNTHETIC, FabricMaterial.COTTON),
            VolumeGrade.COPIOUS,
            ViscosityGrade.THIN,
        )
        assertEquals(LayerOutcome.BEADED, stack.layers[0].outcome)
        assertEquals(LayerOutcome.DRY, stack.layers[1].outcome)
    }

    @Test
    fun `判据四_手工造跳层判红`() {
        val skipped = FabricStack(
            listOf(
                FabricLayer(FabricMaterial.COTTON, LayerOutcome.ABSORBED),
                FabricLayer(FabricMaterial.COTTON, LayerOutcome.ABSORBED),
            )
        )
        val r = FluidFabric.judge(skipped)
        assertNull(r.value)
        assertEquals(FluidCodes.FABRIC_SKIP, r.reds[0].code)
        assertTrue("要点名第几层：" + r.reds[0].detail, r.reds[0].detail.contains("第 2 层"))
    }

    @Test
    fun `判据四_空栈判红且推进拒绝`() {
        assertEquals(FluidCodes.FABRIC_EMPTY, FluidFabric.judge(FabricStack(emptyList())).reds[0].code)
        val thrown = runCatching {
            FluidFabric.propagate(emptyList(), VolumeGrade.TRACE, ViscosityGrade.THIN)
        }.exceptionOrNull()
        assertTrue("空栈推进要当场拒：$thrown", thrown is IllegalArgumentException)
    }

    // —— ⑤ 量-面积 ——

    @Test
    fun `判据五_量档定覆盖上限`() {
        assertEquals(Coverage.NONE, FluidCoverage.maxOf(VolumeGrade.NONE))
        assertEquals(Coverage.LOCAL, FluidCoverage.maxOf(VolumeGrade.TRACE))
        assertEquals(Coverage.HALF, FluidCoverage.maxOf(VolumeGrade.MODERATE))
        assertEquals(Coverage.FULL, FluidCoverage.maxOf(VolumeGrade.COPIOUS))
    }

    @Test
    fun `判据五_量微写全覆盖判红`() {
        val r = FluidCoverage.judge(CoverageClaim(VolumeGrade.TRACE, Coverage.FULL, CoverageCarrier.BODY))
        assertNull(r.value)
        assertEquals(FluidCodes.COVERAGE_OVER, r.reds[0].code)
    }

    @Test
    fun `判据五_量中只能半覆盖`() {
        assertNotNull(FluidCoverage.judge(CoverageClaim(VolumeGrade.MODERATE, Coverage.HALF, null)).value)
        val over = FluidCoverage.judge(CoverageClaim(VolumeGrade.MODERATE, Coverage.FULL, CoverageCarrier.FABRIC))
        assertEquals(FluidCodes.COVERAGE_OVER, over.reds[0].code)
    }

    @Test
    fun `判据五_全覆盖缺载体只提示`() {
        val r = FluidCoverage.judge(CoverageClaim(VolumeGrade.COPIOUS, Coverage.FULL, null))
        assertNotNull("提示不是红，值照样出", r.value)
        assertTrue(r.reds.isEmpty())
        assertEquals(FluidCodes.COVERAGE_NO_CARRIER, r.prompts[0].code)
        assertTrue("载体缺省要如实留空", r.value?.carrier == null)
    }

    @Test
    fun `判据五_全覆盖给了载体就干净`() {
        val ok = FluidCoverage.judge(CoverageClaim(VolumeGrade.COPIOUS, Coverage.FULL, CoverageCarrier.BODY))
        assertTrue(ok.issues.isEmpty())
        assertEquals(Coverage.FULL, ok.value?.coverage)
        assertTrue("渲染要带载体", FluidRenderer.renderCoverage(requireNotNull(ok.value)).contains("载体=体表"))
    }

    @Test
    fun `判据四_量微文案写淌满判红`() {
        val red = FluidChecks.overstatedCoverage(VolumeGrade.TRACE, "顺着腿淌满了")
        assertNotNull(red)
        assertEquals(FluidCodes.COVERAGE_TEXT_OVER, red?.code)
        assertNull("量微写点状没问题", FluidChecks.overstatedCoverage(VolumeGrade.TRACE, "只在体表点状"))
        assertNull("量多写满手没问题", FluidChecks.overstatedCoverage(VolumeGrade.COPIOUS, "满手"))
        assertFalse("提示与红是两档", red?.severity == IssueSeverity.PROMPT)
    }
}
