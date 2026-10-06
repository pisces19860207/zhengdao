// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// v1.1 第一阶段「会话完整化」的纯逻辑验证。
//
// 为什么只测这一层：太极的会话新建/恢复要连真机上的 opencode serve，无法在 JVM 单测里跑；
// 但「历史列表按 今天/昨天/更早 分组」是**纯函数**（只依赖系统时钟），可以脱离真机自动验证。
// 其余（新建 / 切换 / 拉取）以真机验收为准，见《v1.1 计划》第一阶段。
package com.example.zhengdao.ui.taiji

import com.example.zhengdao.oc.OcSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TaijiSessionGroupingTest {

    /** 以「现在」为锚，构造相对时间戳，保证用例在任何时刻运行都稳定。 */
    private val now = System.currentTimeMillis()

    @Test
    fun `刚刚更新 - 归入今天`() {
        assertEquals("今天", dayLabel(now))
    }

    @Test
    fun `24 小时前 - 归入昨天`() {
        // now-24h 必 < 今天 00:00 且 >= 昨天 00:00（无论此刻是几点）
        assertEquals("昨天", dayLabel(now - DAY))
    }

    @Test
    fun `3 天前 - 归入更早`() {
        assertEquals("更早", dayLabel(now - 3 * DAY))
    }

    @Test
    fun `时间缺失 - 归入更早（不臆造时间）`() {
        assertEquals("更早", dayLabel(null))
    }

    @Test
    fun `今天零点整 - 归入今天而非昨天（边界）`() {
        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("今天", dayLabel(startOfToday))
        assertEquals("昨天", dayLabel(startOfToday - 1))   // 再早 1ms 即昨天
    }

    @Test
    fun `分组保持服务端倒序且切段正确`() {
        val list = listOf(
            OcSessionSummary("s1", "最新", now, 3),
            OcSessionSummary("s2", "昨天的", now - DAY, 1),
            OcSessionSummary("s3", "很久前", now - 5 * DAY, 7),
        )
        val groups = groupSessionsByDay(list)
        assertEquals(listOf("今天", "昨天", "更早"), groups.map { it.first })
        assertEquals(listOf("s1"), groups[0].second.map { it.id })
        assertEquals(listOf("s2"), groups[1].second.map { it.id })
        assertEquals(listOf("s3"), groups[2].second.map { it.id })
    }

    @Test
    fun `同一天多条会话合并进同一组且保持顺序`() {
        val list = listOf(
            OcSessionSummary("a", null, now, 0),
            OcSessionSummary("b", null, now - 60_000L, 0),
        )
        val groups = groupSessionsByDay(list)
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups[0].second.map { it.id })
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
