// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第五百一十批 · 判据①：不写验收的许愿也能出候选判据。
package com.example.zhengdao.baopu.wish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishSpecBuilderTest {

    @Test
    fun 判据1_许愿出规格含候选验收并等确认() {
        val spec = WishSpecBuilder.build("把工作区里的 .md 合并成一本", WishProduct.SKILL)
        assertTrue("候选判据 2–3 条", spec.candidates.size in 2..3)
        assertFalse("未确认", spec.confirmed)
        assertEquals("把工作区里的 .md 合并成一本", spec.goal)
        assertTrue("风险非空", spec.risk.isNotBlank())
    }

    @Test
    fun 四类产物各有候选判据() {
        WishProduct.entries.forEach { p ->
            val c = WishSpecBuilder.candidatesFor(p)
            assertTrue("$p 候选 2–3 条", c.size in 2..3)
            assertTrue("$p 每条有机械描述", c.all { it.mechanical.isNotBlank() })
        }
    }

    @Test
    fun 用户挑选后确认() {
        val spec = WishSpecBuilder.build("写一个微头条", WishProduct.CONTENT)
        val ids = spec.candidates.take(2).map { it.id }
        val confirmed = WishSpecBuilder.confirm(spec, ids)
        assertTrue(confirmed.confirmed)
        assertEquals(2, confirmed.acceptance.size)
    }

    @Test
    fun 无效id不算确认() {
        val spec = WishSpecBuilder.build("做一张卡", WishProduct.CARD)
        val confirmed = WishSpecBuilder.confirm(spec, listOf("BOGUS"))
        assertFalse(confirmed.confirmed)
    }

    @Test
    fun 候选id不重复() {
        WishProduct.entries.forEach { p ->
            val ids = WishSpecBuilder.candidatesFor(p).map { it.id }
            assertEquals("$p id 无重复", ids.size, ids.toSet().size)
        }
    }
}