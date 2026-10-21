// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第一百一十七批派工单《抱朴多任务并发 v1》§实现/§测试。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务级并发判据的纯函数测试（JVM，不碰库、不碰线程）。
 *
 * 覆盖派工单点名的四类事实：
 * 1. **同会话绝不并发两个 run**：门空才 STARTED，同会话只能 QUEUED；
 * 2. **队列上限明确拒绝**：第 9 条不许悄悄收；
 * 3. **取消后不再自动接力**：只有 DONE 且未 stop 才抽下一条；
 * 4. **多会话 v1 不并行**：跨会话发送直接拒绝，接口常量可测。
 */
class RunQueueTest {

    // ── 受理矩阵 ────────────────────────────────────────────────────────

    @Test
    fun `门空_非空文本_STARTED`() {
        assertEquals(SendAcceptance.STARTED, RunQueueRules.acceptance(GateOwner.NONE, 0, false))
        assertTrue(SendAcceptance.STARTED.accepted)
    }

    @Test
    fun `同会话在跑_QUEUED_不并起第二个run`() {
        assertEquals(SendAcceptance.QUEUED, RunQueueRules.acceptance(GateOwner.SAME_SESSION, 0, false))
        assertEquals(SendAcceptance.QUEUED, RunQueueRules.acceptance(GateOwner.SAME_SESSION, 7, false))
        assertTrue(SendAcceptance.QUEUED.accepted)
    }

    @Test
    fun `别的会话在跑_明确拒绝_v1不跨会话排队`() {
        assertEquals(
            SendAcceptance.REFUSED_BUSY_OTHER_SESSION,
            RunQueueRules.acceptance(GateOwner.OTHER_SESSION, 0, false),
        )
        assertFalse(SendAcceptance.REFUSED_BUSY_OTHER_SESSION.accepted)
        // 别的会话 + 本会话队列也满 ⇒ 仍然先报「别的会话在跑」（门比队列更外层）
        assertEquals(
            SendAcceptance.REFUSED_BUSY_OTHER_SESSION,
            RunQueueRules.acceptance(GateOwner.OTHER_SESSION, RunQueueRules.MAX_QUEUED, false),
        )
    }

    @Test
    fun `空文本拒绝_不占队列`() {
        assertEquals(SendAcceptance.REFUSED_EMPTY, RunQueueRules.acceptance(GateOwner.NONE, 0, true))
        assertEquals(SendAcceptance.REFUSED_EMPTY, RunQueueRules.acceptance(GateOwner.SAME_SESSION, 0, true))
        assertFalse(SendAcceptance.REFUSED_EMPTY.accepted)
    }

    @Test
    fun `队列上限8_第9条明确拒绝`() {
        assertEquals(8, RunQueueRules.MAX_QUEUED)
        assertEquals(
            SendAcceptance.QUEUED,
            RunQueueRules.acceptance(GateOwner.SAME_SESSION, RunQueueRules.MAX_QUEUED - 1, false),
        )
        assertEquals(
            SendAcceptance.REFUSED_QUEUE_FULL,
            RunQueueRules.acceptance(GateOwner.SAME_SESSION, RunQueueRules.MAX_QUEUED, false),
        )
        assertFalse(SendAcceptance.REFUSED_QUEUE_FULL.accepted)
        // 门空但队列非空（异常残留）：照样不许开第二条，先报满
        assertEquals(
            SendAcceptance.REFUSED_QUEUE_FULL,
            RunQueueRules.acceptance(GateOwner.NONE, RunQueueRules.MAX_QUEUED, false),
        )
    }

    // ── 接力条件 ────────────────────────────────────────────────────────

    @Test
    fun `只有DONE且未stop才自动抽下一条`() {
        assertTrue(RunQueueRules.canAutoAdvance(RunState.DONE, stopRequested = false, queued = 1))
        assertFalse("停止后不接力", RunQueueRules.canAutoAdvance(RunState.DONE, stopRequested = true, queued = 1))
        assertFalse("空队列不接力", RunQueueRules.canAutoAdvance(RunState.DONE, stopRequested = false, queued = 0))
        assertFalse("失败暂停队列（第八十七批不静默）", RunQueueRules.canAutoAdvance(RunState.ABORTED, false, 3))
        assertFalse("跑着当然不抽下一条", RunQueueRules.canAutoAdvance(RunState.THINKING, false, 3))
    }

    // ── 多会话 v1 ───────────────────────────────────────────────────────

    @Test
    fun `多会话v1_最多一个活跃_跨会话不接`() {
        assertEquals(1, RunConcurrency.maxParallelSessions)
        assertTrue(RunConcurrency.canStartAnother(activeSessions = 0))
        assertFalse(RunConcurrency.canStartAnother(activeSessions = 1))
        assertFalse("v1 跨会话发送一律不接", RunConcurrency.crossSessionSendAllowed())
    }

    // ── 内存队列 ────────────────────────────────────────────────────────

    private fun turn(sid: Long, text: String, images: List<String> = emptyList()) =
        QueuedTurn(sessionId = sid, text = text, imageRelPaths = images)

    @Test
    fun `每会话FIFO_互不串队`() {
        val q = SessionRunQueue()
        q.enqueue(turn(1L, "a"))
        q.enqueue(turn(2L, "x"))
        q.enqueue(turn(1L, "b"))
        assertEquals(2, q.size(1L))
        assertEquals(1, q.size(2L))
        assertEquals(listOf("a", "b"), q.snapshot(1L).map { it.text })
        assertEquals("x", q.poll(2L)?.text)
        assertEquals(0, q.size(2L))
        assertEquals("a", q.poll(1L)?.text)
        assertEquals("b", q.poll(1L)?.text)
        assertEquals(null, q.poll(1L))
        assertFalse(q.hasItems(1L))
    }

    @Test
    fun `图片路径随条目走_两条带图消息不串`() {
        val q = SessionRunQueue()
        q.enqueue(turn(1L, "一", listOf("附件/1.png")))
        q.enqueue(turn(1L, "二", listOf("附件/2.png")))
        assertEquals(
            listOf(listOf("附件/1.png"), listOf("附件/2.png")),
            q.snapshot(1L).map { it.imageRelPaths },
        )
        assertEquals("一", q.poll(1L)?.text)
        assertEquals(listOf("附件/2.png"), q.snapshot(1L).single().imageRelPaths)
    }

    @Test
    fun `清空只清目标会话_其余队列不动`() {
        val q = SessionRunQueue()
        q.enqueue(turn(1L, "a"))
        q.enqueue(turn(1L, "b"))
        q.enqueue(turn(2L, "x"))
        val removed = q.clear(1L)
        assertEquals(listOf("a", "b"), removed.map { it.text })
        assertEquals(0, q.size(1L))
        assertEquals(1, q.size(2L))
        assertTrue(q.clear(9L).isEmpty())
    }

    @Test
    fun `恢复只在空队列时接管_不覆盖刚入队的镜像`() {
        val q = SessionRunQueue()
        q.restore(1L, listOf(turn(1L, "旧1"), turn(1L, "旧2")))
        assertEquals(listOf("旧1", "旧2"), q.snapshot(1L).map { it.text })
        // 已有内存镜像（本进程刚入队）⇒ 恢复不覆盖，避免重复
        q.restore(1L, listOf(turn(1L, "旧1")))
        assertEquals(2, q.size(1L))
        // 别的会话照常接管
        q.restore(2L, listOf(turn(2L, "x")))
        assertEquals(1, q.size(2L))
    }

    @Test
    fun `撤销用对象身份_不误删同文条目`() {
        val q = SessionRunQueue()
        val a = turn(1L, "同一句")
        val b = turn(1L, "同一句")
        q.enqueue(a)
        q.enqueue(b)
        assertTrue(q.remove(1L, b))
        assertEquals(1, q.size(1L))
        assertSame(a, q.poll(1L))
    }

    @Test
    fun `会话集合与计数是诊断读数`() {
        val q = SessionRunQueue()
        q.enqueue(turn(3L, "a"))
        q.enqueue(turn(5L, "b"))
        assertEquals(setOf(3L, 5L), q.sessions())
        assertEquals(1, q.size(3L))
    }
}
