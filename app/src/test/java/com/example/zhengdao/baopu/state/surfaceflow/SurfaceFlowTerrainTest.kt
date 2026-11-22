// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 地形与滴落的定向判据：②③④⑤⑥ + 积水出口 + 四要素正例。
package com.example.zhengdao.baopu.state.surfaceflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFlowTerrainTest {

    @Test
    fun `正例_四要素齐备必过`() {
        val verdict = SurfaceFlowRule.judge(SurfaceFixtures.good())
        assertTrue(verdict.ok)
        assertNotNull(verdict.value)
        assertTrue(verdict.issues.isEmpty())
    }

    @Test
    fun `判据二_胸沟被流经却不积水必红`() {
        val verdict = SurfaceFlowRule.judge(SurfaceFixtures.good().copy(pools = emptyList()))
        assertFalse(verdict.ok)
        assertTrue(verdict.hasCode(SurfaceCodes.POOL_MISSING_STERNAL))
    }

    @Test
    fun `判据二_积水档写无也算没积水`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(pools = listOf(PoolRecord(Site.STERNAL_GROOVE, PoolLevel.NONE, Site.NAVEL)))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.POOL_MISSING_STERNAL))
    }

    @Test
    fun `判据二_积水没有出口必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(pools = listOf(PoolRecord(Site.STERNAL_GROOVE, PoolLevel.OBVIOUS, null)))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.POOL_NO_OUTLET))
    }

    @Test
    fun `判据三_滴落点未标最低点必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(drips = listOf(DripRecord(Site.GLANS_TIP, null)))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.DRIP_NO_LOWEST))
        assertFalse(verdict.ok)
    }

    @Test
    fun `判据三_标了最低点就过`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(drips = listOf(DripRecord(Site.GLANS_TIP, true)))
        )
        assertTrue(verdict.ok)
    }

    @Test
    fun `判据四_包皮口缘滴落无组织与长度依据必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(drips = listOf(DripRecord(Site.PREPUCE_RIM, true)))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.PREPUCE_NO_BASIS))
    }

    @Test
    fun `判据四_只给组织不给长度仍红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(
                drips = listOf(
                    DripRecord(
                        site = Site.PREPUCE_RIM,
                        isLowest = true,
                        prepuceTissue = PrepuceTissue.MIDDLE,
                        prepuceLength = null,
                    ),
                ),
            )
        )
        assertTrue(verdict.hasCode(SurfaceCodes.PREPUCE_NO_BASIS))
    }

    @Test
    fun `判据四_两档都给就过`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(
                drips = listOf(
                    DripRecord(
                        site = Site.PREPUCE_RIM,
                        isLowest = true,
                        prepuceTissue = PrepuceTissue.MIDDLE,
                        prepuceLength = PrepuceLength.SHORT,
                    ),
                ),
            )
        )
        assertTrue(verdict.ok)
    }

    @Test
    fun `判据五_喉结横穿不下淌必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(
                paths = listOf(
                    FlowPath(PathKind.MIDLINE, Site.LARYNX, FlowBehavior.HANG, Site.PECTORAL),
                    FlowPath(PathKind.ALONG_GROOVE, Site.STERNAL_GROOVE, FlowBehavior.POOL),
                ),
                note = "横穿不下淌",
            )
        )
        assertTrue(verdict.hasCode(SurfaceCodes.LARYNX_NO_SPLIT))
    }

    @Test
    fun `判据五_喉结分流下淌就过`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(
                paths = listOf(
                    FlowPath(PathKind.LATERAL, Site.LARYNX, FlowBehavior.SPLIT, Site.PECTORAL),
                    FlowPath(PathKind.ALONG_GROOVE, Site.STERNAL_GROOVE, FlowBehavior.POOL),
                ),
            )
        )
        assertTrue(verdict.ok)
    }

    @Test
    fun `判据六_毛不沾水必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(hairWicks = listOf(HairWick(Site.AXILLA, false)))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.HAIR_DRY))
    }

    @Test
    fun `判据六_沿毛束却没有毛束必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(
                paths = listOf(
                    FlowPath(PathKind.MIDLINE, Site.LARYNX, FlowBehavior.SPLIT, Site.PECTORAL),
                    FlowPath(PathKind.ALONG_HAIR, Site.KNEE, FlowBehavior.SPLIT),
                ),
                hairWicks = emptyList(),
            )
        )
        assertTrue(verdict.hasCode(SurfaceCodes.HAIR_NO_WICK))
    }

    @Test
    fun `地形四要素与三行为的计数固定`() {
        assertEquals(4, TerrainKind.COUNT)
        assertEquals(3, FlowBehavior.COUNT)
        assertEquals(4, PathKind.COUNT)
        assertEquals(15, Site.COUNT)
        assertEquals(4, Site.ofKind(TerrainKind.CONVEX).size)
        assertEquals(5, Site.ofKind(TerrainKind.CONCAVE).size)
        assertEquals(3, Site.ofKind(TerrainKind.APEX).size)
        assertEquals(3, Site.ofKind(TerrainKind.RIM).size)
    }
}
