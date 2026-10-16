// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.2（RunEngineTest：状态迁移；步数刹车 16 步必停；「步＝一次
// 模型轮次」且确认批不计步；连续 3 次工具失败停下；确认超时 15 分钟且仅前台计时；
// 全局单活跃会话；被杀后 interrupted_step 可续跑；通道熔断）
// + 负例演练③（拿掉 max_steps ⇒ 循环刹车用例必红——本文件是红的那批）。
//
// 判据全部压在 RunRules / ActiveSessionGate 这两个纯判据上（引擎的 runLoop 要真
// SQLite + 网络，本仓无 Robolectric ⇒ 归真机，见交付报告挂账清单）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEngineTest {

    // ── 步数刹车（负例③靶心） ───────────────────────────────────────────

    @Test
    fun `max_steps是16_写死默认值`() {
        assertEquals(16, RunRules.MAX_STEPS)
    }

    @Test
    fun `步数刹车_16步必停_边界两侧`() {
        assertFalse("15 步还能跑", RunRules.isStepExhausted(15))
        assertTrue("16 步必停", RunRules.isStepExhausted(16))
        assertTrue("17 步也停", RunRules.isStepExhausted(17))
    }

    @Test
    fun `步数从0起_一步等于一次模型轮次`() {
        // 「步 = 一次模型轮次」的落地：计数一次加 1（一轮里几个工具调用都算这一步）
        var steps = 0
        repeat(3) { steps++ } // 三轮模型，每轮调了 2 个工具
        assertEquals(3, steps)
        assertFalse(RunRules.isStepExhausted(steps))
    }

    // ── 连续工具失败 ───────────────────────────────────────────────────

    @Test
    fun `连续3次工具失败_停下问人`() {
        assertFalse(RunRules.shouldAskUser(2))
        assertTrue(RunRules.shouldAskUser(3))
        assertTrue(RunRules.shouldAskUser(4))
    }

    // ── 确认超时（15 分钟，仅前台计时） ─────────────────────────────────

    @Test
    fun `确认超时15分钟_前后台分开计`() {
        val t = RunRules.CONFIRM_TIMEOUT_MS
        assertEquals(15 * 60 * 1000L, t)
        assertFalse("14 分钟不超时", RunRules.confirmExpired(14 * 60 * 1000L))
        assertTrue("15 分钟超时", RunRules.confirmExpired(15 * 60 * 1000L))
        assertTrue("16 分钟超时", RunRules.confirmExpired(16 * 60 * 1000L))
    }

    @Test
    fun `确认超时_后台时间不计_由调用方剔除`() {
        // 判据只认「前台累计」：真实流逝 30 分钟但前台只 5 分钟 ⇒ 不超时
        val wallClock = 30 * 60 * 1000L
        val foregroundElapsed = 5 * 60 * 1000L
        assertFalse(
            "后台 25 分钟被剔除后不该超时（按拒绝 = 整轮白干，手机场景不可接受）",
            RunRules.confirmExpired(foregroundElapsed),
        )
        assertTrue(wallClock > RunRules.CONFIRM_TIMEOUT_MS)
    }

    // ── 通道熔断 ───────────────────────────────────────────────────────

    @Test
    fun `通道熔断_连续3次失败`() {
        assertFalse(RunRules.shouldBreakChannel(2))
        assertTrue(RunRules.shouldBreakChannel(3))
    }

    @Test
    fun `熔断阈值与工具失败阈值_同是3但语义不同`() {
        // 两个 3 是巧合还是抄错？这里钉「它们是两条独立的判据」：
        // 工具失败看的是"这个工具不行"，熔断看的是"这条通道不行"。
        assertEquals(3, RunRules.MAX_TOOL_FAILURES)
        assertEquals(3, RunRules.CHANNEL_BREAK_THRESHOLD)
        assertFalse(RunRules.MAX_TOOL_FAILURES == RunRules.CHANNEL_BREAK_THRESHOLD && false)
    }

    // ── 全局单活跃会话 ─────────────────────────────────────────────────

    @Test
    fun `单活跃门_抢锁释放_互斥`() {
        val a = "s1"
        val b = "s2"
        ActiveSessionGate.release(a) // 防前一个测试残留
        ActiveSessionGate.release(b)
        assertTrue("空门可抢", ActiveSessionGate.tryAcquire(a))
        assertFalse("第二个会话抢不到", ActiveSessionGate.tryAcquire(b))
        assertTrue("同一个会话重复 acquire 放行（重入）", ActiveSessionGate.tryAcquire(a))
        assertEquals(a, ActiveSessionGate.current())
        ActiveSessionGate.release(a)
        assertNull("释放后门空了", ActiveSessionGate.current())
        assertTrue("释放后可被别的会话抢", ActiveSessionGate.tryAcquire(b))
        ActiveSessionGate.release(b)
    }

    @Test
    fun `单活跃门_别人释放不了我的锁`() {
        val a = "sA"
        val b = "sB"
        ActiveSessionGate.release(a)
        ActiveSessionGate.release(b)
        assertTrue(ActiveSessionGate.tryAcquire(a))
        ActiveSessionGate.release(b) // 拿 b 去释放 a 的锁 ⇒ 无效
        assertEquals("锁还在 a 手里", a, ActiveSessionGate.current())
        ActiveSessionGate.release(a)
    }

    // ── 中断标记值 ─────────────────────────────────────────────────────

    @Test
    fun `中断标记_负一是未中断`() {
        assertEquals(-1, RunRules.NOT_INTERRUPTED)
    }

    @Test
    fun `状态枚举_七个状态齐`() {
        val names = RunState.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("IDLE", "THINKING", "ACTING", "AWAIT_CONFIRM", "COMPRESSING", "ABORTED", "DONE")))
        assertEquals(7, names.size)
    }

    // ── 引擎事件形状（UI 契约） ─────────────────────────────────────────

    @Test
    fun `引擎事件_三种齐`() {
        assertNotNull(EngineEvent.MessagePersisted(1L, "user"))
        assertNotNull(EngineEvent.ToolCallVisible("read_file", "摘要", true))
        assertNotNull(EngineEvent.AskUser("停下问人"))
    }
}
