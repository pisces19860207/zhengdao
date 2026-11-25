// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十批 · 解耦的读数台：身高、体重、骨架换遍，档一个字都不许动；年龄只在成年闸上起作用。
//
// 这些用例是「四向全禁」的**运行时**一半；另一半在 SizeSourceAssertTest（源码数着 context. 的用法）。
// 两边合起来才是判据②：谁把 f(身高)／f(体重)／f(骨架)／f(年龄) 加进来，这里立刻红。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeIndependenceTest {

    private val sample = SizeFixtures.profile(
        LengthTier.LONG, Girth.THICK, GlansTier.VERY_LARGE,
        BuriedLook.OBVIOUS, DevelopmentTiming.EARLY, DevelopmentLook.VISIBLE,
        FlowShift.OBVIOUS, ColorRef.DARKER,
    )

    /** 中档样本：变异若往「大」或「深」推，极端样本看不出来，中档样本一定看得出来。 */
    private val mid = SizeFixtures.profile(
        LengthTier.MID, Girth.MEDIUM, GlansTier.MID,
        BuriedLook.SLIGHT, DevelopmentTiming.TYPICAL, DevelopmentLook.FAINT,
        FlowShift.NONE, ColorRef.SAME,
    )

    private val samples = listOf(sample, mid)

    private fun line(ctx: BodyContext): String = samples.joinToString("｜") { SizeGate.line(it, ctx) }

    @Test
    fun `身高换遍_档与行逐字不变`() {
        val base = line(SizeFixtures.context(170, 70, 1, 30))
        (140..200 step 5).forEach { h ->
            assertEquals("身高 $h 改变了读数", base, line(SizeFixtures.context(h, 70, 1, 30)))
        }
    }

    @Test
    fun `体重换遍_档与行逐字不变`() {
        val base = line(SizeFixtures.context(170, 70, 1, 30))
        (40..180 step 5).forEach { m ->
            assertEquals("体重 $m 改变了读数", base, line(SizeFixtures.context(170, m, 1, 30)))
        }
    }

    @Test
    fun `骨架换遍_档与行逐字不变`() {
        val base = line(SizeFixtures.context(170, 70, 1, 30))
        (0..3).forEach { s ->
            assertEquals("骨架 $s 改变了读数", base, line(SizeFixtures.context(170, 70, s, 30)))
        }
    }

    @Test
    fun `成年区间内_年龄换遍档不变`() {
        val base = line(SizeFixtures.context(170, 70, 1, 30))
        (BodyContext.ADULT_MIN..90 step 2).forEach { age ->
            assertEquals("年龄 $age 改变了读数", base, line(SizeFixtures.context(170, 70, 1, age)))
        }
    }

    @Test
    fun `未成年_整层不出且不带档名`() {
        (0 until BodyContext.ADULT_MIN).forEach { age ->
            val ctx = SizeFixtures.context(170, 70, 1, age)
            samples.forEach { p ->
                val layer = SizeGate.resolve(p, ctx)
                assertTrue("年龄 $age 必须整层不出", layer is SizedLayer.Absent)
                assertEquals(SizedLayer.ABSENT_PREFIX + SizeGate.MINOR_ABSENT, layer.line())
                assertTrue("不出档的行里不许有档名", !layer.line().contains("尺寸="))
            }
        }
    }

    @Test
    fun `成年边界_十八岁出档`() {
        assertTrue(SizeGate.resolve(sample, SizeFixtures.context(170, 70, 1, 17)) is SizedLayer.Absent)
        assertTrue(SizeGate.resolve(sample, SizeFixtures.context(170, 70, 1, 18)) is SizedLayer.Present)
    }

    @Test
    fun `埋没与尺寸_四乘四乘四全过`() {
        var n = 0
        BuriedLook.entries.forEach { buried ->
            LengthTier.entries.forEach { l ->
                Girth.entries.forEach { g ->
                    GlansTier.entries.forEach { gl ->
                        val p = SizeFixtures.profile(l, g, gl, buried)
                        assertEquals("埋没" + buried.label + "×" + l.label + "/" + g.label + "/" + gl.label,
                            emptyList<SizeFinding>(), SizeRules.inspect(p))
                        n++
                    }
                }
            }
        }
        assertEquals(256, n)
    }

    @Test
    fun `独立矩阵_四十八格全过`() {
        var n = 0
        BuriedLook.entries.forEach { buried ->
            (0..3).forEach { tier ->
                assertEquals(Verdict.PASS, IndependenceMatrix.verdict(buried, tier))
                n++
            }
        }
        assertEquals(16, n)
        assertEquals(48, IndependenceMatrix.CELLS)
    }

    @Test
    fun `发育外观不联动_年龄体重换遍档不变`() {
        val look = SizeFixtures.profile(
            LengthTier.MID, Girth.MEDIUM, GlansTier.MID, look = DevelopmentLook.DISTINCT,
        )
        listOf(19, 35, 58, 80).forEach { age ->
            listOf(45, 90, 170).forEach { mass ->
                val out = SizeGate.resolve(look, SizeFixtures.context(170, mass, 2, age))
                assertTrue("必须出档", out is SizedLayer.Present)
                val present = out as SizedLayer.Present
                assertEquals("外观档被年龄体重改写", look, present.profile)
                assertEquals(DevelopmentLook.DISTINCT.level, present.profile.look.level)
            }
        }
    }

    @Test
    fun `时相是背景_与尺寸无推导边`() {
        DevelopmentTiming.entries.forEach { t ->
            val p = SizeFixtures.profile(
                LengthTier.SHORT, Girth.THIN, GlansTier.SMALL, timing = t,
            )
            assertTrue(t.label + " 不该产生判据", SizeRules.inspect(p).isEmpty())
            assertEquals(LengthTier.SHORT, p.length)
        }
    }
}
