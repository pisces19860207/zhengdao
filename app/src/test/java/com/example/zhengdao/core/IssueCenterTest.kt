// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「最近问题」中枢的单测（#4 全 App 错误提示走查，2026-10-09）。
 *
 * 这里锁死的是**列表语义**，不是文案：同 id 覆盖、最新在前、上限丢最旧、resolve 撤下、
 * clear 清空。这几条都是被真机形态逼出来的——例如 serve 每次拉起都会超时，
 * 不去重的话主页那张卡会变成一屏一模一样的"启动超时"。
 *
 * ⚠️ [IssueCenter] 是 object（跨用例共享状态），所以每个用例前后都要 [IssueCenter.clear]。
 */
class IssueCenterTest {

    @Before
    fun setUp() = IssueCenter.clear()

    @After
    fun tearDown() = IssueCenter.clear()

    @Test
    fun `上报的问题带时间戳进快照`() {
        IssueCenter.report("a", "标题A", "说明A")
        val all = IssueCenter.snapshot()
        assertEquals(1, all.size)
        assertEquals("a", all[0].id)
        assertEquals("标题A", all[0].title)
        assertEquals("说明A", all[0].detail)
        assertTrue("at 应由 report 打上（0 = 没打）", all[0].at > 0L)
        assertNull("没给去路就是没去路", all[0].actionLabel)
        assertNull(all[0].actionId)
    }

    @Test
    fun `便捷重载能把去路一起带上`() {
        IssueCenter.report(
            id = "oc-serve-timeout",
            title = "太极启动超时",
            detail = "serve 未在 15 秒内就绪",
            actionLabel = "重试",
            actionId = IssueCenter.ACTION_RETRY_SERVE,
        )
        val one = IssueCenter.snapshot().single()
        assertEquals("重试", one.actionLabel)
        assertEquals(IssueCenter.ACTION_RETRY_SERVE, one.actionId)
    }

    @Test
    fun `同一个 id 只留最新一条并保留去路`() {
        IssueCenter.report("oc-serve-timeout", "超时", "第一秒", "重试", IssueCenter.ACTION_RETRY_SERVE)
        IssueCenter.report("oc-serve-timeout", "超时", "第二秒")
        val all = IssueCenter.snapshot()
        assertEquals("同 id 不能追加成两条", 1, all.size)
        assertEquals("第二秒", all[0].detail)
        // 覆盖是整条替换：新报的那条没给去路，就不能继承旧那条的按钮（否则会点到一个
        // 跟当前失败未必对应的动作）
        assertNull(all[0].actionLabel)
    }

    @Test
    fun `最新上报的排在最前`() {
        IssueCenter.report("a", "A", "1")
        IssueCenter.report("b", "B", "2")
        assertEquals(listOf("b", "a"), IssueCenter.snapshot().map { it.id })
    }

    @Test
    fun `超过上限时丢掉最旧的一条`() {
        // 报 25 条（1..25），上限 20 ⇒ 只剩 6..25，且 25 在最前
        for (i in 1..25) IssueCenter.report("id$i", "标题$i", "说明$i")
        val all = IssueCenter.snapshot()
        assertEquals(IssueCenter.LIMIT, all.size)
        assertEquals("id25", all.first().id)
        assertEquals("id6", all.last().id)
        assertTrue("最旧的应该被丢掉", all.none { it.id == "id5" })
    }

    @Test
    fun `resolve 只撤下指定的一条`() {
        IssueCenter.report("a", "A", "1")
        IssueCenter.report("b", "B", "2")
        IssueCenter.resolve("a")
        assertEquals(listOf("b"), IssueCenter.snapshot().map { it.id })
        // 撤下不存在的不该报错，也不该动列表
        IssueCenter.resolve("不存在")
        assertEquals(listOf("b"), IssueCenter.snapshot().map { it.id })
    }

    @Test
    fun `clear 清空列表`() {
        IssueCenter.report("a", "A", "1")
        IssueCenter.report("b", "B", "2")
        IssueCenter.clear()
        assertTrue(IssueCenter.snapshot().isEmpty())
    }

    @Test
    fun `issues 这个 State 与快照同源`() {
        // 主页读的是 issues（Compose State），单测读的是 snapshot —— 两者必须是同一份数据，
        // 否则"界面看不到但日志里有"这个 #4 的病根会以另一种形式复现。
        IssueCenter.report("a", "A", "1")
        assertNotNull(IssueCenter.issues.value.singleOrNull())
        assertEquals(IssueCenter.snapshot().map { it.id }, IssueCenter.issues.value.map { it.id })
        IssueCenter.resolve("a")
        assertTrue(IssueCenter.issues.value.isEmpty())
    }
}
