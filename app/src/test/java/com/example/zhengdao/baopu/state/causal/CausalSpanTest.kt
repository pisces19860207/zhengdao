// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 判据②：滞后后果只用 825 的四档，不造时间单位。
// 这一条查三样：档就是四档、档名/边界与派工单逐字一致、跨度只能比较不能换算。
package com.example.zhengdao.baopu.state.causal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalSpanTest {

    @Test
    fun `判据二_跨度恰四档且档序连续`() {
        assertEquals("825 四档", 4, Span825.COUNT)
        assertEquals("档序 0–3", listOf(0, 1, 2, 3), Span825.values().map { it.rank })
        assertEquals("档名", listOf("分钟级", "小时级", "过夜", "跨日"), Span825.values().map { it.label })
    }

    @Test
    fun `判据二_边界文案与派工单逐字一致`() {
        Span825.values().forEach { s ->
            assertEquals("${s.label} 的边界文案", Span825Source.QUOTE[s], s.bound)
        }
        assertEquals("四档都要有边界文案", 4, Span825Source.QUOTE.size)
        assertTrue("溯源要指向 825", Span825Source.SOURCE.contains("第八百二十五批"))
        assertTrue("要写明 825 未落地", Span825Source.PENDING.contains("未落地"))
    }

    @Test
    fun `判据二_到期判定是档序比较不是时间换算`() {
        assertTrue("分钟级在分钟级到期", Span825.MINUTES.reachedBy(Span825.MINUTES))
        assertFalse("跨日不在分钟级到期", Span825.CROSS_DAY.reachedBy(Span825.MINUTES))
        assertFalse("过夜不在小时级到期", Span825.OVERNIGHT.reachedBy(Span825.HOURS))
        assertTrue("过夜在跨日已到期", Span825.OVERNIGHT.reachedBy(Span825.CROSS_DAY))
    }

    @Test
    fun `判据二_到期表是单调的`() {
        Span825.values().forEach { s ->
            var seenTrue = false
            Span825.values().forEach { e ->
                val r = s.reachedBy(e)
                if (r) seenTrue = true
                if (seenTrue) assertTrue("${s.label} 在 ${e.label} 之后不许再变 false", r)
            }
        }
    }

    @Test
    fun `判据二_后一档封顶在最长档`() {
        assertSame(Span825.HOURS, Span825.later(Span825.MINUTES))
        assertSame(Span825.CROSS_DAY, Span825.later(Span825.OVERNIGHT))
        assertSame("最长档原地不动", Span825.CROSS_DAY, Span825.later(Span825.CROSS_DAY))
    }

    @Test
    fun `判据二_越界档序钳到端点`() {
        assertSame(Span825.MINUTES, Span825.of(-5))
        assertSame(Span825.CROSS_DAY, Span825.of(99))
    }

    @Test
    fun `判据二_滞后项只能是这四档之一`() {
        val log = CausalScenarios.fatigueChain()
        val spans = log.events.flatMap { e -> e.delayed.map { it.span } }
        assertTrue("链③有滞后项", spans.isNotEmpty())
        spans.forEach { assertTrue("$it 必须来自 825 四档", it in Span825.values()) }
    }
}
