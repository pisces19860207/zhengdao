// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第八百一十九批 · 核心判据：台词互换测试。
// 同场 ≥2 人互换台词须 ≥2 处明显不合；读不出差异⇒红。
package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSwapTest {

    @Test
    fun `判据_两人互换台词两方向都至少两处不合`() {
        val a = PersonaFixture.voiceA()
        val b = PersonaFixture.voiceB()
        val (ab, ba) = VoiceSwap.swapTest(a, b)
        assertTrue("A→B 须 ≥2 处不合：$ab", ab.passed)
        assertTrue("B→A 须 ≥2 处不合：$ba", ba.passed)
        assertTrue("两人可区分", VoiceSwap.distinguishable(a, b))
        // 四维全不同 ⇒ 每方向恰 4 处。
        assertEquals(4, ab.distinctCount)
        assertEquals(4, ba.distinctCount)
    }

    @Test
    fun `判据_只读四维句长用词称呼口头禅`() {
        assertEquals(listOf("句长", "用词", "称呼", "口头禅"), VoiceSwap.dimensions())
        val a = PersonaFixture.voiceA()
        val b = PersonaFixture.voiceB()
        val dims = VoiceSwap.swapTest(a, b).first.mismatches.map { it.dimension }.toSet()
        assertEquals("语气词/停顿/打断不参与互换判据", setOf("句长", "用词", "称呼", "口头禅"), dims)
    }

    @Test
    fun `判据_读不出差异时判红`() {
        // A 与"只差一维"的角色对换 ⇒ 仅 1 处不合 < 2 ⇒ 红。
        val a = PersonaFixture.voiceA()
        val twin = PersonaFixture.voiceAOnlyLengthDiff()
        val (ab, ba) = VoiceSwap.swapTest(a, twin)
        assertEquals("仅 1 处不合", 1, ab.distinctCount)
        assertFalse("读不出差异 ⇒ 红", ab.passed)
        assertFalse("任一方向读不出即红", VoiceSwap.distinguishable(a, twin))
    }

    @Test
    fun `判据_完全一人两张嘴判红`() {
        val a = PersonaFixture.voiceA()
        val (ab, ba) = VoiceSwap.swapTest(a, a)
        assertEquals(0, ab.distinctCount)
        assertEquals(0, ba.distinctCount)
        assertFalse(VoiceSwap.distinguishable(a, a))
    }

    @Test
    fun `判据_口头禅一方为空则跳过该维不硬凑`() {
        val line = PersonaFixture.voiceA().lines.first()
        val noCatch = PersonaFixture.voiceB().dimensions.copy(catchphrase = null)
        val res = VoiceSwap.wear(line, "甲", "乙", noCatch)
        assertFalse("口头禅维不参与，不得硬凑成第 4 处", res.mismatches.any { it.dimension == "口头禅" })
        assertEquals(3, res.distinctCount) // 句长/用词/称呼仍不合
    }

    @Test
    fun `判据_不合处写明台词值与档案值可核对`() {
        val res = VoiceSwap.swapTest(PersonaFixture.voiceA(), PersonaFixture.voiceB()).first
        assertTrue(res.mismatches.all { it.line().contains("≠") })
        assertTrue(res.mismatches.all { it.lineValue.isNotBlank() && it.profileValue.isNotBlank() })
        assertTrue(res.line().contains("甲→乙"))
    }
}
