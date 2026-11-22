// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十批 · 判据①②：混合必须主液主导（不给主液提示、平均式判红）、
// 精液出现后黏度必须跳一档；三条规则逐条对拍。
package com.example.zhengdao.baopu.state.fluid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FluidMixTest {

    private fun mix(
        primary: FluidKind?,
        secondary: FluidKind?,
        viscosity: ViscosityGrade = ViscosityGrade.THIN,
        onSkin: Boolean = true,
        mode: MixMode = MixMode.DOMINANT_CORRECTION,
    ): FluidVerdict<Mixture> = FluidMixer.mix(
        MixRequest(primary, secondary, viscosity, ViscosityGrade.RUNNY, onSkin, mode)
    )

    private fun mustMix(primary: FluidKind, secondary: FluidKind?, viscosity: ViscosityGrade = ViscosityGrade.THIN): Mixture =
        requireNotNull(mix(primary, secondary, viscosity).value)

    // —— ② 三条混合规则 ——

    @Test
    fun `规则一_汗加前液体表是水光且黏度不升`() {
        val m = mustMix(FluidKind.SWEAT, FluidKind.PRE_SEMINAL, ViscosityGrade.THIN)
        assertEquals(MixRule.SWEAT_PRE_SEMINAL, m.rule)
        assertEquals("水光", Appearance.SHEEN, m.appearance)
        assertEquals("下滑", FlowKind.SLIDE, m.flow)
        assertEquals("黏度不升", ViscosityGrade.THIN, m.viscosity)
        assertEquals("主液是汗", FluidKind.SWEAT, m.dominant)
        assertTrue("这条规则黏度干净", FluidChecks.viscosityIssues(m).isEmpty())
    }

    @Test
    fun `规则一_离体表就不算这条规则`() {
        val offSkin = FluidMixer.mix(
            MixRequest(FluidKind.SWEAT, FluidKind.PRE_SEMINAL, ViscosityGrade.THIN, ViscosityGrade.RUNNY, false)
        )
        assertEquals("不在体表：退回单液规则", MixRule.PLAIN, offSkin.value?.rule)
        assertEquals("单液外观只看黏度档", Appearance.SHEEN, offSkin.value?.appearance)
    }

    @Test
    fun `规则二_唾液加前液更滑且干得慢`() {
        val m = mustMix(FluidKind.SALIVA, FluidKind.PRE_SEMINAL, ViscosityGrade.CREAMY)
        assertEquals(MixRule.SALIVA_PRE_SEMINAL, m.rule)
        assertEquals("挂丝", Appearance.STRINGY, m.appearance)
        assertEquals("拉丝", FlowKind.STRETCH, m.flow)
        assertEquals("干得慢", DryHint.SLOW, m.dryHint)
        assertEquals("更滑不加稠", ViscosityGrade.CREAMY, m.viscosity)
    }

    @Test
    fun `规则三_前液加精液黏度跳一档`() {
        val m = mustMix(FluidKind.PRE_SEMINAL, FluidKind.SEMEN, ViscosityGrade.THIN)
        assertEquals(MixRule.PRE_SEMINAL_SEMEN, m.rule)
        assertEquals("偏稀跳一档＝稠", ViscosityGrade.CREAMY, m.viscosity)
        assertEquals("凝块", Appearance.CLOT, m.appearance)
        assertEquals("挂住", FlowKind.HOLD, m.flow)
        assertTrue("跳档正确", FluidChecks.viscosityIssues(m).isEmpty())
    }

    @Test
    fun `规则三_已在顶档时停顶档不算未升档`() {
        val m = mustMix(FluidKind.PRE_SEMINAL, FluidKind.SEMEN, ViscosityGrade.GLUTINOUS)
        assertEquals("顶档无处可跳：停在顶档", ViscosityGrade.GLUTINOUS, m.viscosity)
        assertTrue(FluidChecks.viscosityIssues(m).isEmpty())
    }

    // —— 判据① 主液必须给、不许平均 ——

    @Test
    fun `判据一_未给主液只提示不判红`() {
        val v = mix(null, FluidKind.PRE_SEMINAL)
        assertNull("没主液就不出混合结果", v.value)
        assertTrue("不判红", v.reds.isEmpty())
        assertEquals("给一条提示", 1, v.prompts.size)
        assertEquals(FluidCodes.MIX_NO_PRIMARY, v.prompts[0].code)
        assertFalse("没值就不算可出", v.ok)
    }

    @Test
    fun `判据一_平均式混合判红`() {
        val v = mix(FluidKind.SWEAT, FluidKind.PRE_SEMINAL, mode = MixMode.AVERAGED)
        assertNull(v.value)
        assertEquals(1, v.reds.size)
        assertEquals(FluidCodes.MIX_AVERAGED, v.reds[0].code)
    }

    @Test
    fun `判据一_不做平均_取平均的手工结果被判红`() {
        val real = mustMix(FluidKind.SWEAT, FluidKind.PRE_SEMINAL, ViscosityGrade.GLUTINOUS)
        assertEquals("主液主导：保持主液黏度", ViscosityGrade.GLUTINOUS, real.viscosity)

        // 手工造一个「取平均」的结果：黏稠 3 与稀薄 0 平均成偏稀 1。
        val averaged = real.copy(viscosity = ViscosityGrade.THIN)
        val issues = FluidChecks.viscosityIssues(averaged)
        assertEquals(1, issues.size)
        assertEquals(FluidCodes.MIX_VISCOSITY_DRIFT, issues[0].code)
        assertEquals(IssueSeverity.RED, issues[0].severity)
    }

    // —— 判据② 精液出现后必须升档 ——

    @Test
    fun `判据二_精液出现后没升档判红_负例夹具`() {
        val m = mustMix(FluidKind.PRE_SEMINAL, FluidKind.SEMEN, ViscosityGrade.THIN)
        val flat = m.copy(viscosity = ViscosityGrade.THIN)
        val issues = FluidChecks.viscosityIssues(flat)
        assertEquals(1, issues.size)
        assertEquals(FluidCodes.MIX_VISCOSITY_JUMP, issues[0].code)
        assertTrue(issues[0].detail.contains("没跳档"))
    }

    @Test
    fun `判据二_精液作次液也认`() {
        val m = mustMix(FluidKind.SEMEN, FluidKind.PRE_SEMINAL, ViscosityGrade.THIN)
        assertEquals(MixRule.PRE_SEMINAL_SEMEN, m.rule)
        assertEquals("以主液为基准跳一档", ViscosityGrade.CREAMY, m.viscosity)
        assertTrue(FluidChecks.viscosityIssues(m).isEmpty())
    }

    // —— 渲染 ——

    @Test
    fun `渲染_主液写在最前且逐字节确定`() {
        val a = FluidRenderer.renderMix(mustMix(FluidKind.SALIVA, FluidKind.PRE_SEMINAL))
        val b = FluidRenderer.renderMix(mustMix(FluidKind.SALIVA, FluidKind.PRE_SEMINAL))
        assertEquals(a, b)
        assertTrue("主液在最前：$a", a.startsWith("混合[主=唾液/次=前液"))
        assertTrue("点值干净", FluidChecks.pointValueUnits(a).isEmpty())
    }
}
