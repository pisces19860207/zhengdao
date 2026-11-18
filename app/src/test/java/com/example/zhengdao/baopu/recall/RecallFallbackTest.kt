// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 540 索引→直读兜底的组合判据（纯函数，假 Outcome 直测）。
 *
 * 靶心：索引零命中时才允许走直读（找到就不许多读一遍主库）；兜底用的是**同一批线索**
 * ——它不新增检索组，所以「多线索检索 ≤3 次」仍成立。
 */
class RecallFallbackTest {

    private fun outcome(found: Boolean, calls: Int = 2, point: String = "要点"): RecallEngine.Outcome =
        RecallEngine.Outcome(
            found = found,
            points = if (found) listOf(RecallEngine.Point(point, 7L, 42L)) else emptyList(),
            searchCalls = calls,
            clueGroupCount = calls,
        )

    @Test
    fun `索引找到_不走兜底`() {
        var fallbackCalls = 0
        val result = RecallFallback.run(outcome(true)) {
            fallbackCalls++
            outcome(true, point = "兜底要点")
        }
        assertFalse(result.usedFallback)
        assertEquals("找到就不许再读一遍主库", 0, fallbackCalls)
        assertEquals("要点", result.outcome.points.single().text)
    }

    @Test
    fun `索引空_同线索直读兜底一次`() {
        var fallbackCalls = 0
        val result = RecallFallback.run(outcome(false)) {
            fallbackCalls++
            outcome(true, point = "直读要点")
        }
        assertTrue(result.usedFallback)
        assertEquals("兜底只跑一次", 1, fallbackCalls)
        assertEquals("直读要点", result.outcome.points.single().text)
        assertTrue("兜底也不许超过 3 组线索", result.outcome.searchCalls <= RecallEngine.MAX_SEARCH_CALLS)
    }

    @Test
    fun `两条路都空_才叫没找到`() {
        val result = RecallFallback.run(outcome(false, calls = 3)) { outcome(false, calls = 3) }
        assertTrue(result.usedFallback)
        assertFalse(result.outcome.found)
        assertEquals("没找到相关的旧消息", result.outcome.notFoundText)
        assertEquals("兜底不新增线索组", 3, result.outcome.searchCalls)
    }
}
