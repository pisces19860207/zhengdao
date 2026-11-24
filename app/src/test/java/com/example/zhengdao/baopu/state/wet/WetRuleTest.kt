// 第八百九十七批 · 六条判据逐条正反用例 + 唯一出口。
package com.example.zhengdao.baopu.state.wet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WetRuleTest {

    private fun valid(): WetClaim = WetClaim(
        skinWet = SkinWet.DRIPPING,
        drips = listOf(
            DripRecord(DripSource.HAIR_TIP, DripSource.JAW),
            DripRecord(DripSource.CHEST_BELLY, DripSource.LEG),
        ),
        towel = TowelState.WAIST,
        waistTier = WaistTier.LOOSE,
        steamFog = SteamFog.DENSE,
        warm = WarmLevel.CLEAR,
        note = "刚出浴室，发梢挂水，围腰松了些",
        steamNote = "雾气重，眼前看不清",
    )

    private fun codes(claim: WetClaim): List<String> = WetRule.judge(claim).issues.map { it.code }

    @Test
    fun `基础_合法陈述五件齐且出读数`() {
        val verdict = WetRule.judge(valid())
        assertTrue(verdict.issues.toString(), !verdict.hasRed && !verdict.hasHint)
        assertNotNull(verdict.reading)
        assertEquals(SkinWet.DRIPPING, verdict.reading?.skinWet)
        assertEquals(SteamFog.DENSE, verdict.reading?.steamFog)
    }

    @Test
    fun `判据一_五件逐个拿掉都红且不出值`() {
        val blanks = listOf(
            valid().copy(skinWet = null),
            valid().copy(drips = emptyList()),
            valid().copy(towel = null),
            valid().copy(steamFog = null),
            valid().copy(warm = null),
        )
        assertEquals(5, blanks.size)
        blanks.forEach { claim ->
            assertTrue(codes(claim).contains(WetCodes.FIVE_MISSING))
            assertNull(WetRule.judge(claim).reading)
        }
    }

    @Test
    fun `判据二_半干起点与三拍不降档都红`() {
        assertTrue(codes(valid().copy(skinWet = SkinWet.HALF_DRY)).contains(WetCodes.INSTANT_DRY))
        assertFalse(codes(valid()).contains(WetCodes.INSTANT_DRY))
    }

    @Test
    fun `判据三_水珠往上流与同级横走都红`() {
        val upward = listOf(
            DripRecord(DripSource.JAW, DripSource.HAIR_TIP),
            DripRecord(DripSource.LEG, DripSource.BACK),
            DripRecord(DripSource.CHEST_BELLY, DripSource.CHEST_BELLY),
            DripRecord(DripSource.BACK, null),
        )
        upward.forEach { drip ->
            assertTrue("$drip 未被咬住", codes(valid().copy(drips = listOf(drip))).contains(WetCodes.DRIP_UPWARD))
        }
        assertFalse(codes(valid()).contains(WetCodes.DRIP_UPWARD))
    }

    @Test
    fun `判据三_腿为最低处才许离开体表`() {
        val verdict = WetRule.judge(valid().copy(drips = listOf(DripRecord(DripSource.LEG, null))))
        assertTrue(!verdict.hasRed)
        assertEquals("腿→离体", WetRenderer.line(verdict.reading!!).substringAfter("水珠=").substringBefore(" ·"))
    }

    @Test
    fun `判据四_围腰有松紧档且松档可滑落`() {
        val loose = WetRule.judge(valid()).reading!!
        assertTrue(loose.canSlip)
        assertEquals(WaistTier.LOOSE, loose.waistTier)
        val secure = WetRule.judge(valid().copy(waistTier = WaistTier.SECURE)).reading!!
        assertFalse(secure.canSlip)
        assertTrue(codes(valid().copy(waistTier = null)).contains(WetCodes.TOWEL_NO_TIER))
    }

    @Test
    fun `判据四_围得稳稳的只作提示不冒充唯一情形`() {
        val verdict = WetRule.judge(valid().copy(note = "围得稳稳的"))
        assertTrue(verdict.hasHint)
        assertTrue(verdict.issues.any { it.code == WetCodes.TOWEL_SAFE_TALK && it.severity == WetSeverity.HINT })
        assertFalse(verdict.hasRed)
    }

    @Test
    fun `判据五_蒸汽说成遮挡即红`() {
        listOf("被挡住", "看不见=被挡住", "前面有遮挡", "被遮住")
            .map { valid().copy(steamNote = it) }
            .forEach { claim ->
                assertTrue(claim.steamNote, codes(claim).contains(WetCodes.FOG_BLOCK_CONFUSION))
            }
        assertFalse(codes(valid()).contains(WetCodes.FOG_BLOCK_CONFUSION))
    }

    @Test
    fun `判据六_自由描述里的点值一律红`() {
        listOf(
            valid().copy(note = "体表三十摄氏度"),
            valid().copy(note = "残留 5 毫升"),
            valid().copy(note = "三秒后干"),
            valid().copy(steamNote = "能见 2 米"),
        ).forEach { claim -> assertTrue(claim.note + claim.steamNote, codes(claim).contains(WetCodes.NUMERIC_VALUE)) }
        assertFalse(codes(valid()).contains(WetCodes.NUMERIC_VALUE))
    }

    @Test
    fun `体温_刚洗完有偏温且走完预算必回落`() {
        assertTrue(codes(valid().copy(warm = WarmLevel.SETTLED)).contains(WetCodes.WARM_STUCK))
        listOf(0, 1, 2).forEach { level ->
            assertFalse("$level 档预算不该红", codes(valid().copy(recoveryLevel = level)).contains(WetCodes.WARM_STUCK))
        }
    }

    @Test
    fun `出口_一行五件齐且蒸汽不写成遮挡`() {
        val line = WetRenderer.line(WetRule.judge(valid()).reading!!)
        assertTrue(line.contains("湿身=滴水"))
        assertTrue(line.contains("水珠=发梢→下颌"))
        assertTrue(line.contains("浴巾=围腰/松·将滑/可滑落"))
        assertTrue(line.contains("蒸汽=浓（" + STEAM_MEANS + "）"))
        assertTrue(line.contains("偏温=偏温二档"))
        assertFalse(line.contains("被挡住"))
        assertEquals(line, WetRenderer.line(WetRule.judge(valid()).reading!!))
    }

    @Test
    fun `红时不出值_判据红则渲染无读数`() {
        val verdict = WetRule.judge(valid().copy(drips = listOf(DripRecord(DripSource.LEG, DripSource.JAW))))
        assertTrue(verdict.hasRed)
        assertNull(verdict.reading)
    }
}
