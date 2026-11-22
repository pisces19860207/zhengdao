// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 汗的三点差异与点值闸：就地渗出／分区不均／会蒸发 + 判据①⑦。
package com.example.zhengdao.baopu.state.surfaceflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceFlowSweatTest {

    @Test
    fun `判据一_显式全身均匀流下必红`() {
        val sweat = requireNotNull(SurfaceFixtures.good().sweat).copy(uniformRunoff = true)
        val verdict = SurfaceFlowRule.judge(SurfaceFixtures.good().copy(sweat = sweat))
        assertTrue(verdict.hasCode(SurfaceCodes.UNIFORM_RUNOFF))
        assertFalse(verdict.ok)
    }

    @Test
    fun `判据一_各汗区同档同阶段必红`() {
        val zones = listOf(
            SweatZoneRead(SweatZone.CHEST, FlowVolume.MODERATE, SeepStage.RUNNING, EvapTier.MIDDLE, true),
            SweatZoneRead(SweatZone.BELLY, FlowVolume.MODERATE, SeepStage.RUNNING, EvapTier.SLIGHT, true),
        )
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(sweat = SweatProfile(zones))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.UNIFORM_RUNOFF))
    }

    @Test
    fun `汗_source为汗却没有分区必红`() {
        val verdict = SurfaceFlowRule.judge(SurfaceFixtures.good().copy(sweat = null))
        assertTrue(verdict.hasCode(SurfaceCodes.SWEAT_MISSING))
    }

    @Test
    fun `汗_分区不足两区必红`() {
        val zones = listOf(
            SweatZoneRead(SweatZone.CHEST, FlowVolume.MODERATE, SeepStage.RUNNING, EvapTier.MIDDLE, true)
        )
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(sweat = SweatProfile(zones))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.SWEAT_NO_ZONES))
    }

    @Test
    fun `汗_分区重复必红`() {
        val zones = listOf(
            SweatZoneRead(SweatZone.CHEST, FlowVolume.MODERATE, SeepStage.RUNNING, EvapTier.MIDDLE, true),
            SweatZoneRead(SweatZone.CHEST, FlowVolume.TRACE, SeepStage.BEADING, EvapTier.SLIGHT, true),
        )
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(sweat = SweatProfile(zones))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.SWEAT_DUP_ZONE))
    }

    @Test
    fun `汗_缺蒸发档必红`() {
        val zones = listOf(
            SweatZoneRead(SweatZone.CHEST, FlowVolume.MODERATE, SeepStage.RUNNING, null, true),
            SweatZoneRead(SweatZone.BELLY, FlowVolume.TRACE, SeepStage.BEADING, EvapTier.SLIGHT, true),
        )
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(sweat = SweatProfile(zones))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.SWEAT_NO_EVAP))
    }

    @Test
    fun `汗_跳过起珠成片直接汇流必红`() {
        val zones = listOf(
            SweatZoneRead(SweatZone.CHEST, FlowVolume.MODERATE, SeepStage.RUNNING, EvapTier.MIDDLE, false),
            SweatZoneRead(SweatZone.BELLY, FlowVolume.TRACE, SeepStage.BEADING, EvapTier.SLIGHT, true),
        )
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(sweat = SweatProfile(zones))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.SWEAT_SKIP_SEEP))
    }

    @Test
    fun `汗_量不够就汇流必红`() {
        val zones = listOf(
            SweatZoneRead(SweatZone.CHEST, FlowVolume.TRACE, SeepStage.RUNNING, EvapTier.MIDDLE, true),
            SweatZoneRead(SweatZone.BELLY, FlowVolume.MODERATE, SeepStage.BEADING, EvapTier.SLIGHT, true),
        )
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(sweat = SweatProfile(zones))
        )
        assertTrue(verdict.hasCode(SurfaceCodes.SWEAT_EARLY_RUN))
    }

    @Test
    fun `判据七_给流速频率数值必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(note = "流速每秒三滴，频率 3 次")
        )
        assertTrue(verdict.hasCode(SurfaceCodes.NUMERIC_VALUE))
    }

    @Test
    fun `判据七_只给档与部位就过`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(note = "胸沟积水明显，喉结分流下淌")
        )
        assertTrue(verdict.ok)
    }

    @Test
    fun `边界_体液专名越界必红`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(note = "此处混入精液")
        )
        assertTrue(verdict.hasCode(SurfaceCodes.CROSS_DOMAIN))
    }

    @Test
    fun `外部水不必给汗分区`() {
        val verdict = SurfaceFlowRule.judge(
            SurfaceFixtures.good().copy(source = WaterSource.EXTERNAL, sweat = null)
        )
        assertTrue(verdict.ok)
    }
}
