// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十批 · 八个组合夹具：身高、体重、骨架、年龄按极端铺开，尺寸与埋没故意反着配。
// 八条都必须过；其中两条是票面点名的（185 配偏小、胖配粗大），单独具名。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SizeCombosTest {

    private val cases = SizeFixtures.eight()

    @Test
    fun `八组合_全部出档且判据为空`() {
        assertEquals("组合数必须八个", 8, cases.size)
        assertEquals("八个名字不许重", 8, cases.map { it.name }.toSet().size)
        cases.forEach { c ->
            val layer = SizeGate.resolve(c.profile, c.ctx)
            assertTrue(c.name + " 应出档", layer is SizedLayer.Present)
            assertSame(c.name + " 记录不许被改写", c.profile, (layer as SizedLayer.Present).profile)
            assertTrue(c.name + " 判据须为空", SizeRules.inspect(c.profile).isEmpty())
            assertTrue(c.name + " 汇总须红 0", SizeRules.summary(SizeRules.inspect(c.profile)).startsWith("红 0"))
        }
    }

    @Test
    fun `八组合_逐条读数对上`() {
        cases.forEach { c ->
            assertTrue(c.name + " 读数不符：" + c.profile.line(), c.profile.line().startsWith(c.expectSize))
        }
    }

    @Test
    fun `八组合_报告行带来源`() {
        cases.forEach { c ->
            val report = SizeRules.report(c.profile, c.ctx)
            assertTrue(c.name + " 报告须带来源", report.contains("（" + c.profile.source.label + "）"))
            assertTrue(c.name + " 报告须带矩阵读数", report.contains("独立矩阵 48 格全过"))
        }
    }

    @Test
    fun `特别一_185配偏小必须过`() {
        val c = cases.first { it.name.startsWith("②") }
        assertEquals(185, c.ctx.stature)
        assertEquals(LengthTier.SHORT, c.profile.length)
        assertEquals(Girth.THIN, c.profile.girth)
        assertTrue("185 × 偏小 不许判红", SizeRules.inspect(c.profile).isEmpty())
        assertTrue("185 × 偏小 要出档", SizeGate.resolve(c.profile, c.ctx) is SizedLayer.Present)
    }

    @Test
    fun `特别二_胖配粗大必须过`() {
        val c = cases.first { it.name.startsWith("⑤") }
        assertEquals(170, c.ctx.mass)
        assertEquals(Girth.THICK, c.profile.girth)
        assertEquals(BuriedLook.OBVIOUS, c.profile.buriedLook)
        assertTrue("胖 × 粗大 × 埋没明显 不许判红", SizeRules.inspect(c.profile).isEmpty())
        assertTrue("胖 × 粗大 要出档", SizeGate.resolve(c.profile, c.ctx) is SizedLayer.Present)
    }

    @Test
    fun `特别三_矮配大与瘦配小也过`() {
        listOf("③", "⑧").forEach { prefix ->
            val c = cases.first { it.name.startsWith(prefix) }
            assertTrue(c.name + " 不许判红", SizeRules.inspect(c.profile).isEmpty())
        }
    }
}
