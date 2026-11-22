// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 输出与确定性：四件输出固定次序 + 判据⑧同输入同结论。
package com.example.zhengdao.baopu.state.surfaceflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFlowRendererTest {

    @Test
    fun `输出四件_paths_poolAt_dripAt_posture`() {
        val claim = SurfaceFixtures.good()
        val value = requireNotNull(SurfaceFlowRule.judge(claim).value)
        val out = SurfaceFlowRenderer.render(value)
        assertEquals(3, out.paths.size)
        assertEquals(listOf("胸沟=明显·出口脐"), out.poolAt)
        assertEquals(listOf("龟头顶=最低点是"), out.dripAt)
        assertEquals("站立", out.posture)
        assertTrue(out.line().startsWith("paths["))
        assertTrue(out.line().contains("|poolAt["))
        assertTrue(out.line().contains("|dripAt["))
        assertTrue(out.line().endsWith("|posture=站立"))
    }

    @Test
    fun `判据八_同输入同结论逐字一致`() {
        val claim = SurfaceFixtures.good()
        val a = SurfaceFlowRenderer.render(requireNotNull(SurfaceFlowRule.judge(claim).value)).line()
        val b = SurfaceFlowRenderer.render(requireNotNull(SurfaceFlowRule.judge(claim.copy()).value)).line()
        assertEquals(a, b)
        assertEquals(SurfaceFixtures.goodLine(), a)
    }

    @Test
    fun `判据八_换体位结论必须不同`() {
        val claim = SurfaceFixtures.good()
        val a = SurfaceFlowRenderer.render(requireNotNull(SurfaceFlowRule.judge(claim).value)).line()
        val b = SurfaceFlowRenderer.render(
            requireNotNull(SurfaceFlowRule.judge(claim.copy(posture = FlowPosture.SUPINE)).value)
        ).line()
        assertNotEquals(a, b)
    }

    @Test
    fun `无积水无滴落如实记无`() {
        val claim = SurfaceFlowClaim(
            source = WaterSource.EXTERNAL,
            posture = FlowPosture.SITTING,
        )
        val verdict = SurfaceFlowRule.judge(claim)
        assertTrue(verdict.ok)
        val out = SurfaceFlowRenderer.render(requireNotNull(verdict.value))
        assertEquals(listOf("无"), out.poolAt)
        assertEquals(listOf("无"), out.dripAt)
        assertTrue(out.paths.isEmpty())
    }

    @Test
    fun `判据八_红时不出值`() {
        val claim = SurfaceFixtures.good().copy(drips = listOf(DripRecord(Site.GLANS_TIP, null)))
        val verdict = SurfaceFlowRule.judge(claim)
        assertEquals(null, verdict.value)
        assertTrue(verdict.ok.not())
    }
}
