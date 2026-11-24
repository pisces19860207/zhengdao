// 第八百九十七批 · 五件档位表：全维覆盖、档序连续、蒸汽与遮挡两句不许相同。
package com.example.zhengdao.baopu.state.wet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WetGradesTest {

    @Test
    fun `表驱动_湿身四档从半干到滴水档序连续`() {
        assertEquals(listOf(0, 1, 2, 3), SkinWet.entries.map { it.level })
        assertEquals(listOf("半干", "挂水", "湿亮", "滴水"), SkinWet.entries.map { it.label })
        SkinWet.entries.forEach { assertEquals(it, SkinWet.of(it.level)) }
        assertEquals(SkinWet.HALF_DRY, SkinWet.of(-9))
        assertEquals(SkinWet.DRIPPING, SkinWet.of(99))
    }

    @Test
    fun `表驱动_五处滴源重力次序连续且腿为最低`() {
        assertEquals(listOf(0, 1, 2, 3, 4), DripSource.entries.map { it.order })
        assertEquals(DripSource.LEG, DripSource.entries.maxBy { it.order })
        DripSource.entries.forEach { assertEquals(it, DripSource.of(it.order)) }
    }

    @Test
    fun `表驱动_浴巾五处置与围腰三档`() {
        assertEquals(5, TowelState.entries.size)
        assertEquals(listOf(0, 1, 2), WaistTier.entries.map { it.level })
        assertEquals(1, WaistTier.entries.count { it.canSlip })
        assertTrue(WaistTier.LOOSE.canSlip)
        assertTrue(!WaistTier.MIDDLE.canSlip && !WaistTier.SECURE.canSlip)
    }

    @Test
    fun `表驱动_蒸汽三档与偏温三档只到二档`() {
        assertEquals(listOf(0, 1, 2), SteamFog.entries.map { it.level })
        assertEquals(listOf(0, 1, 2), WarmLevel.entries.map { it.level })
        assertEquals(WarmLevel.SETTLED, WarmLevel.of(-3))
        assertEquals(WarmLevel.CLEAR, WarmLevel.of(9))
    }

    @Test
    fun `口径_蒸汽看不清与遮挡被挡住是两句`() {
        assertTrue(STEAM_MEANS.contains("看不清"))
        assertTrue(OCCLUSION_MEANS.contains("被挡住"))
        assertNotEquals(STEAM_MEANS, OCCLUSION_MEANS)
    }

    @Test
    fun `口径_本包只陈述档位与拍数不含点值`() {
        assertTrue(WET_DISCLAIMER.contains("档位"))
        assertTrue(WET_DISCLAIMER.contains("拍数"))
    }
}
