// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ④：三套映射的档位断言（判据④：只许 0–3 的既有档）。
// 口径：标称读数取自本网络四档输入的实测稳态值（见 MapToTier 头注），
// 断言的是"这四个值各落一档"，**不是**"和代理档一致"——后者是段 C 的事。
package com.example.zhengdao.baopu.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapToTierTest {

    @Test
    fun `四档标称放电率各落一档`() {
        // 实测区间：档 0＝0；档 1＝0.013–0.048；档 2＝0.053–0.079；档 3＝0.102–0.106
        assertEquals(0, MapToTier.turgor(0.0))
        assertEquals(1, MapToTier.turgor(0.030))
        assertEquals(2, MapToTier.turgor(0.060))
        assertEquals(3, MapToTier.turgor(0.100))
    }

    @Test
    fun `放电率切点落在空隙里`() {
        // 切点两侧各一格（负例：切点写错一格，这里立刻红）
        assertEquals(0, MapToTier.turgor(0.0049))
        assertEquals(1, MapToTier.turgor(0.005))
        assertEquals(1, MapToTier.turgor(0.0499))
        assertEquals(2, MapToTier.turgor(0.050))
        assertEquals(2, MapToTier.turgor(0.0899))
        assertEquals(3, MapToTier.turgor(0.090))
    }

    @Test
    fun `适应档按衰减量分三截`() {
        // 实测：0 / 0.00040–0.00044 / 0.00064–0.00067 / 0.00073–0.00074
        assertEquals("不衰减", 0, MapToTier.adaptation(0.0))
        assertEquals("轻微衰减", 1, MapToTier.adaptation(-0.00042))
        assertEquals("明显衰减", 2, MapToTier.adaptation(-0.00065))
        assertEquals("强衰减", 3, MapToTier.adaptation(-0.00074))
    }

    @Test
    fun `适应档对正向斜率不越界`() {
        // 斜率正（越跑越快）在模型里不该出现；真出现了也只许落 0 档，不许借位成 3
        assertEquals(0, MapToTier.adaptation(0.0009))
    }

    @Test
    fun `兴奋度档静息落零档_被驱动落高档`() {
        assertEquals("静息（平均电位 0）必须 0 档", 0, MapToTier.arousal(0.0))
        assertEquals("被驱动起来（≈1.0）落 3 档", 3, MapToTier.arousal(1.0))
        assertTrue("中间值必须落在 0..3", MapToTier.arousal(0.5) in 1..2)
    }

    @Test
    fun `非有限值一律零档`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("$bad 不许编一档出来", 0, MapToTier.turgor(bad))
            assertEquals(0, MapToTier.arousal(bad))
            assertEquals(0, MapToTier.adaptation(bad))
        }
    }

    @Test
    fun `三套映射的域恒为 0到3`() {
        for (x in listOf(-1.0, -0.001, 0.0, 0.02, 0.06, 0.2, 1.0, 10.0)) {
            for (tier in listOf(MapToTier.turgor(x), MapToTier.adaptation(x), MapToTier.arousal(x))) {
                assertTrue("$x 映射出 $tier，越出既有档域", tier in 0..MapToTier.TIER_MAX)
            }
        }
        assertEquals(3, MapToTier.TIER_MAX)
    }
}
