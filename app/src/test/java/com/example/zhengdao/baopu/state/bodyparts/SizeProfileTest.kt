// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十批 · 记录本身的规矩：来源三选一、默认中性必须全中性、依据不许提体格。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeProfileTest {

    @Test
    fun `默认中性_八件全落中性档`() {
        val p = SizeProfile.neutral()
        assertTrue("默认中性必须自认为中性", p.neutral)
        assertEquals(SizeSource.DEFAULT_NEUTRAL, p.source)
        assertEquals(LengthTier.MID, p.length)
        assertEquals(Girth.MEDIUM, p.girth)
        assertEquals(GlansTier.MID, p.glans)
        assertEquals(BuriedLook.NONE, p.buriedLook)
        assertEquals(DevelopmentTiming.TYPICAL, p.timing)
        assertEquals(DevelopmentLook.NONE, p.look)
        assertEquals(FlowShift.NONE, p.colorShift)
        assertEquals(ColorRef.SAME, p.colorTier)
        assertEquals(null, p.basis)
    }

    @Test
    fun `默认中性_写成一行`() {
        assertEquals(
            "尺寸=中／中等／相对中；埋没=无埋没；时相=常；发育外观=无可见标记；色变档=无变化；色档=与肤色相同（默认中性）",
            SizeProfile.neutral().line(),
        )
    }

    @Test
    fun `默认中性_偏一档即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SizeProfile(
                LengthTier.LONG, Girth.THICK, GlansTier.LARGE, BuriedLook.NONE,
                DevelopmentTiming.TYPICAL, DevelopmentLook.NONE, FlowShift.NONE, ColorRef.SAME,
                SizeSource.DEFAULT_NEUTRAL,
            )
        }
        assertTrue(e.message!!.startsWith(SizeProfile.NEUTRAL_REJECT))
    }

    @Test
    fun `观测与档案_必须给依据`() {
        listOf(SizeSource.OBSERVED, SizeSource.ARCHIVE).forEach { src ->
            val e = assertThrows(IllegalArgumentException::class.java) {
                SizeProfile(
                    LengthTier.LONG, Girth.THICK, GlansTier.LARGE, BuriedLook.NONE,
                    DevelopmentTiming.TYPICAL, DevelopmentLook.NONE, FlowShift.NONE, ColorRef.SAME,
                    src, null,
                )
            }
            assertTrue("$src 缺依据须拒", e.message!!.startsWith(SizeProfile.BASIS_REJECT))
        }
    }

    @Test
    fun `依据里提体格属性即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SizeFixtures.profile(LengthTier.LONG, Girth.THICK, GlansTier.LARGE, basis = "按身高推的")
        }
        assertTrue(e.message!!.startsWith(SizeGuard.DERIVED_BASIS_REJECT))
    }

    @Test
    fun `依据写成因果句即拒`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SizeFixtures.profile(LengthTier.SHORT, Girth.THIN, GlansTier.SMALL, basis = "因为偏胖所以记小")
        }
        assertTrue(e.message!!.startsWith(SizeGuard.CAUSATION_REJECT))
    }

    @Test
    fun `来源只有三种`() {
        assertEquals(3, SizeSource.entries.size)
        assertEquals(3, SizeSource.COUNT)
    }

    @Test
    fun `颜色两层分两段写`() {
        val line = SizeFixtures.profile(LengthTier.LONG, Girth.THICK, GlansTier.LARGE).line()
        assertTrue("色变档要单独一段", line.contains("；色变档="))
        assertTrue("色档要单独一段", line.contains("；色档="))
        assertFalse("不许出现合体标签", line.contains("颜色档"))
    }

    @Test
    fun `一行里没有长度百分比与判定词`() {
        val line = SizeFixtures.profile(LengthTier.LONG, Girth.THICK, GlansTier.VERY_LARGE).line()
        assertEquals(null, BodyPartGuard.measureHit(line))
        assertTrue(BodyPartGuard.diagnosisHits(line).isEmpty())
    }

    @Test
    fun `档序越界钳到两端`() {
        assertEquals(LengthTier.SHORT, LengthTier.of(-3))
        assertEquals(LengthTier.LONG, LengthTier.of(9))
        assertEquals(BuriedLook.DEEP, BuriedLook.of(4))
        assertEquals(FlowShift.STRONG, FlowShift.of(7))
        assertEquals(DevelopmentTiming.LATE, DevelopmentTiming.of(5))
    }
}
