// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第一百五十二批派工单《F 时序竞态运行期用例（可注入 SessionRunQueue 替身）》§1–§2。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 「poll 后 stop 到达」的**运行期**用例（152批 F）。
 *
 * 为什么现在能在 JVM 里跑：152 批把队列本体抽成 [SessionRunQueue] 接口（替身可注入）、
 * 把「取条 vs 停止清队」的同锁临界区收进 [QueueStopGate]——RunEngine 要真 SQLite/网络
 * 起不来，但这两个件是纯 JVM，本文件直接驱动**生产协议代码**，不再只钉源码形状。
 *
 * 存活不变量（派工单 §1.1）：每条 turn 在 **队列里（被清且计入 N）／已进 runLoop
 * （在飞账面）／已落库结算** 三者中至少占一个，不得「两者皆无」——150 批审计的
 * 「已 poll 出、还没开跑就被 clearQueue 永久丢掉」一旦回来，[alive] 必须打红。
 *
 * 确定性：时序全靠**顺序编排＋闩（CountDownLatch）**，不靠 sleep、不靠线程抖动；
 * 「poll 先赢门」与「stop 先赢门」两个分支分别由时序 1／时序 2 钉死，时序 4 用
 * 两个真线程把「runLoop 仍在飞时 stop 落地」的窗口按生产形状复现。
 */
class SessionRunQueueRaceTest {

    private fun turn(sid: Long = 7L, text: String) = QueuedTurn(sessionId = sid, text = text)

    /** 停止标志替身：引擎的 `stopRequested` 字段在用例里由 AtomicBoolean 顶替（缝的注入形状）。 */
    private fun newStoppedFlag() = AtomicBoolean(false)

    /**
     * 存活不变量：队列栏（被清且计入 N）∨ 在飞栏（已进 runLoop 的账面）∨ 结算栏
     * （消息已落库、轮次已收尾）——三者至少占一。
     */
    private fun alive(inCleared: Boolean, inFlight: Int, settled: Boolean): Boolean =
        inCleared || inFlight > 0 || settled

    // ── 时序 1：poll 后 stop 立即到（poll 赢了门）────────────────────────

    @Test
    fun `时序1_poll后stop立即到_过闸条在飞不丢_剩余条清掉`() {
        val q = DefaultSessionRunQueue()
        val stopped = newStoppedFlag()
        val g = QueueStopGate(q, stopped::get, { stopped.set(true) })
        val t1 = turn(text = "已过闸这条")
        val t2 = turn(text = "还在排队这条")
        q.enqueue(t1)
        q.enqueue(t2)

        // drain 侧：先 poll 出一条（过闸 ⇒ 在飞计 1）。此刻引擎正要 ensureQueueRow/进 runLoop。
        val polled = g.pollForRun(7L)
        assertSame(t1, polled)
        assertEquals(1, g.polledInFlight.get())

        // stop 在「poll 之后、收尾之前」到达：门内 置位→中断→清队→读在飞。
        var cleared: List<QueuedTurn> = emptyList()
        var notice: String? = null
        g.onStopArrived(interrupt = {}) {
            cleared = q.clear(7L)
            val running = g.polledInFlight.get()
            notice = stopNoticeText(cleared.size, running)
            // 存活不变量（停止落地那一刻）：t1 过闸在飞（1）、t2 被清——没有一条三栏皆无
            assertTrue(alive(inCleared = cleared.contains(t1), inFlight = running, settled = false))
            assertTrue(alive(inCleared = cleared.contains(t2), inFlight = running, settled = false))
        }
        assertFalse("已过闸的 t1 不得被 clearQueue 二次清掉", cleared.contains(t1))
        assertEquals(listOf(t2), cleared)
        assertEquals(
            "「清掉 N 条」与「另有 1 条在飞」必须分开说全",
            "已停止：清掉 1 条排队；另有 1 条已开始处理的消息会保留，可续跑",
            notice,
        )
        // 引擎 finally 的同一笔账：收尾后在飞归零；停止标志经门置位
        g.polledInFlight.decrementAndGet()
        assertEquals(0, g.polledInFlight.get())
        assertTrue(stopped.get())
    }

    // ── 时序 2：stop 先到（stop 赢了门）─────────────────────────────────

    @Test
    fun `时序2_stop先到_清掉N条与实际清理数一致`() {
        val q = DefaultSessionRunQueue()
        val stopped = newStoppedFlag()
        val g = QueueStopGate(q, stopped::get, { stopped.set(true) })
        val ts = (1..3).map { turn(text = "排队$it") }
        ts.forEach { q.enqueue(it) }

        var actualRemoved: List<QueuedTurn> = emptyList()
        var notice: String? = null
        // stop 先拿门：没有任何 poll 发生 ⇒ 在飞为 0，队列里 3 条全数被清
        g.onStopArrived(interrupt = {}) {
            actualRemoved = q.clear(7L)
            notice = stopNoticeText(actualRemoved.size, g.polledInFlight.get())
        }
        assertEquals("清掉的条目必须是队列里那 3 条、一字不差", ts, actualRemoved)
        assertEquals(0, q.size(7L))
        assertEquals(
            "通知里的 N 必须与实际清理数一致（文案点名 3）",
            "已停止，排队中的 3 条也清掉了",
            notice,
        )
        assertEquals(
            "从文案里抠出的 N 与实清条数对账",
            actualRemoved.size,
            Regex("排队中的 (\\d+) 条").find(notice!!)!!.groupValues[1].toInt(),
        )
        assertTrue("置位必须经门发生", stopped.get())
        // 每一条都落在「被清」栏 ⇒ 存活
        actualRemoved.forEach { assertTrue(alive(inCleared = true, inFlight = 0, settled = false)) }
    }

    // ── 时序 3：正常收尾（stop 后到）⇒ 不许误判丢失 ─────────────────────

    @Test
    fun `时序3_正常收尾_不弹假通知也不误判丢失`() {
        val q = DefaultSessionRunQueue()
        val stopped = newStoppedFlag()
        val g = QueueStopGate(q, stopped::get, { stopped.set(true) })
        val t = turn(text = "正常跑完这条")
        q.enqueue(t)

        // drain：取条 → runLoop → 落库/结算（引擎 finally 的同一笔减账）
        val polled = g.pollForRun(7L)
        assertSame(t, polled)
        val settled = mutableListOf<QueuedTurn>()
        settled += polled!!
        g.polledInFlight.decrementAndGet()

        // 之后的 stop：既没清到东西、也没有在飞 ⇒ 文案必须闭嘴（null，不弹假通知）
        var cleared = -1
        var notice: String? = "未执行"
        g.onStopArrived(interrupt = {}) {
            cleared = q.clear(7L).size
            notice = stopNoticeText(cleared, g.polledInFlight.get())
        }
        assertEquals(0, cleared)
        assertNull("正常收尾不许弹「清掉/在飞」的假通知", notice)
        assertTrue("置位必须经门发生", stopped.get())
        // 这条已结算——三栏里占「结算」栏，不是丢
        assertTrue(alive(inCleared = false, inFlight = 0, settled = settled.isNotEmpty()))
    }

    // ── 时序 4：两个真线程按生产形状复现「runLoop 在飞时 stop 落地」的窗口 ──

    @Test
    fun `时序4_真并发_drain停在runLoop时stop落地_不变量恒成立`() {
        val q = DefaultSessionRunQueue()
        val stopped = newStoppedFlag()
        val g = QueueStopGate(q, stopped::get, { stopped.set(true) })
        val t1 = turn(text = "竞速这条")
        val t2 = turn(text = "看谁被清这条")
        q.enqueue(t1)
        q.enqueue(t2)

        val polledTurn = AtomicReference<QueuedTurn?>()
        val drainedSignal = CountDownLatch(1)
        val stopDone = CountDownLatch(1)

        val drainer = Thread {
            // drain 线程：过闸拿条 ⇒ 通知 stop 可以来了 ⇒ 停在「runLoop 在飞」里等 stop 落地
            polledTurn.set(g.pollForRun(7L))
            drainedSignal.countDown()
            stopDone.await()
            g.polledInFlight.decrementAndGet() // 引擎 finally 的同一笔账
        }
        drainer.name = "zd-test-drain"
        drainer.isDaemon = true
        drainer.start()
        assertTrue("drain 侧 5s 内没完成过闸取条（测试编排坏了）", drainedSignal.await(5, TimeUnit.SECONDS))

        // stop 侧：此刻 t1 已过闸在飞、t2 还在队列——恰是 150 批审计的窗口形状
        var runningAtStop = -1
        var cleared: List<QueuedTurn> = emptyList()
        var notice: String? = null
        g.onStopArrived(interrupt = {}) {
            runningAtStop = g.polledInFlight.get()
            cleared = q.clear(7L)
            notice = stopNoticeText(cleared.size, runningAtStop)
        }
        stopDone.countDown()
        drainer.join(5_000)
        assertFalse("drain 线程卡死（收尾减账没跑）", drainer.isAlive)

        // 确定性断言：闩编排下 poll 必先完成 ⇒ t1 过闸、t2 被清、文案说全
        assertSame(t1, polledTurn.get())
        assertEquals(1, runningAtStop)
        assertEquals(listOf(t2), cleared)
        assertEquals(
            "已停止：清掉 1 条排队；另有 1 条已开始处理的消息会保留，可续跑",
            notice,
        )
        // 存活不变量在窗口内成立：t1 在飞、t2 被清，没有一条三栏皆无
        assertTrue(alive(inCleared = cleared.contains(t1), inFlight = runningAtStop, settled = false))
        assertTrue(alive(inCleared = cleared.contains(t2), inFlight = runningAtStop, settled = false))
        // 收尾后账面归零（不许漏减）；停止标志经门置位
        assertEquals(0, g.polledInFlight.get())
        assertTrue(stopped.get())
    }

    // ── 缝的可注入性：替身队列顶上来，协议照常 ───────────────────────────

    /** 替身队列：poll 吐预置条目并记录操作序——证明缝可注入、协议不依赖具体实现。 */
    private class ScriptedQueue(private val pollScript: ArrayDeque<QueuedTurn>) : SessionRunQueue {
        val ops = mutableListOf<String>()

        override fun enqueue(turn: QueuedTurn): Int = error("替身脚本用例不入队")
        override fun size(sessionId: Long): Int = 0
        override fun hasItems(sessionId: Long): Boolean = false
        override fun snapshot(sessionId: Long): List<QueuedTurn> = emptyList()

        override fun poll(sessionId: Long): QueuedTurn? {
            ops += "poll"
            return pollScript.removeFirstOrNull()
        }

        override fun clear(sessionId: Long): List<QueuedTurn> {
            ops += "clear"
            return emptyList()
        }

        override fun restore(sessionId: Long, turns: List<QueuedTurn>) = error("替身脚本用例不恢复")
        override fun remove(sessionId: Long, turn: QueuedTurn): Boolean = error("替身脚本用例不撤销")
        override fun sessions(): Set<Long> = emptySet()
    }

    @Test
    fun `替身缝_注入ScriptedQueue_同锁协议照常`() {
        val t = turn(text = "替身出的这条")
        val queue = ScriptedQueue(ArrayDeque(listOf(t)))
        val stopped = AtomicBoolean(false)
        val gate = QueueStopGate(queue, stopped::get, { stopped.set(true) })

        val polled = gate.pollForRun(7L)
        assertSame("替身的 poll 返回值必须原样过门", t, polled)
        assertEquals("取条计数不依赖具体队列实现", 1, gate.polledInFlight.get())

        gate.onStopArrived(interrupt = {}) {
            queue.clear(7L)
        }
        assertTrue("置位必须经门发生", stopped.get())
        assertEquals("门必须恰调一次 poll、一次 clear", listOf("poll", "clear"), queue.ops)
    }

    // ── 负例：旧行为替身（150批 F 之前的形状）⇒ 存活检测器必须打红 ─────────

    /**
     * 旧行为替身：取条**不查停、不记账**（poll 与 stop 不同锁、无在飞账）；
     * 清队与置位各干各的——已 poll 出的那条没有任何账，就此失踪。
     * 用同样的时序驱动它，[alive] 必须打红：红不了＝用例是摆设。
     */
    private class LegacyUncoordinated(turns: List<QueuedTurn>) {
        private val inner = DefaultSessionRunQueue()
        private var stopFlag = false

        init {
            turns.forEach { inner.enqueue(it) }
        }

        fun poll(sessionId: Long): QueuedTurn? = inner.poll(sessionId)

        fun stopAndClear(sessionId: Long): List<QueuedTurn> {
            stopFlag = true
            return inner.clear(sessionId)
        }
    }

    @Test
    fun `负例_旧行为替身_poll后清队_存活检测器必须打红`() {
        val t1 = turn(text = "必丢这条")
        val t2 = turn(text = "排队这条")
        val legacy = LegacyUncoordinated(listOf(t1, t2))

        // 同样的时序 1：先 poll 出一条，stop 随后到达
        val polled = legacy.poll(7L)
        assertSame(t1, polled)
        val cleared = legacy.stopAndClear(7L)

        // 旧行为没有在飞账（poll 不计数）、没有结算账（直接失踪）⇒ 检测器三输入 (false, 0, false)
        assertFalse(
            "150批审计的「取出后永久丢失」必须被打红——红不了＝用例是摆设",
            alive(inCleared = cleared.contains(t1), inFlight = 0, settled = false),
        )
        // t2 还在队列里 ⇒ 被 stop 清掉、存活；t1 确实失踪（旧行为的实锤形状）
        assertTrue(cleared.contains(t2))
        assertFalse("旧行为下 t1 连「被清」栏都不在", cleared.contains(t1))
        // 顺带钉 151 批补的另一半：旧行为文案不报在飞条，用户被误导
        val oldNotice = "已停止，排队中的 ${cleared.size} 条也清掉了"
        assertFalse("旧行为文案不报在飞条", oldNotice.contains("另有 1 条已开始处理"))
        assertTrue("新文案必须把在飞那条说全", stopNoticeText(cleared.size, 1)!!.contains("另有 1 条已开始处理"))
    }

    // ── 文案矩阵：四分支直测，「清掉 N」与实清数对账 ─────────────────────

    @Test
    fun `停止文案四分支_N与实清数对账`() {
        // (n>0, running>0)：两条分开说；N 可从文案里抠出来对上实清数
        val both = stopNoticeText(n = 1, running = 1)!!
        assertEquals(
            "已停止：清掉 1 条排队；另有 1 条已开始处理的消息会保留，可续跑",
            both,
        )
        assertEquals(1, Regex("清掉 (\\d+) 条").find(both)!!.groupValues[1].toInt())
        // (n>0, running=0)：旧文案保留；N 同样可对上
        val queuedOnly = stopNoticeText(n = 3, running = 0)!!
        assertEquals("已停止，排队中的 3 条也清掉了", queuedOnly)
        assertEquals(3, Regex("排队中的 (\\d+) 条").find(queuedOnly)!!.groupValues[1].toInt())
        // (n=0, running>0)：只说在飞那条；(0,0)：闭嘴
        assertEquals(
            "已停止：正在处理的那条会停在当前步，消息保留可续跑",
            stopNoticeText(n = 0, running = 1),
        )
        assertNull(stopNoticeText(n = 0, running = 0))
    }
}
