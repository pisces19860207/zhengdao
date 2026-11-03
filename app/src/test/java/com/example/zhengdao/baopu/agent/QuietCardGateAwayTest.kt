// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 388 派工单："锁屏期间不弹卡（进队列）"。弹卡闸多了一个静默源（离场值守＋锁屏），
// 本套只钉**新源**的行为；330/379 的老判据在 [QuietCardGateTest]，一个字没改、原样绿。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietCardGateAwayTest {

    private val t0 = 1_700_000_000_000L

    /** 日间不忙（14 点）＝330 的三格静默全不命中：压不压只看离场锁屏这一格。 */
    private fun dayShape() = SessionShape(hourOfDay = 14, busy = false)

    private fun req(q: String) = AskUserRequest(
        question = q,
        options = listOf(AskUserOption("keep", "都留着"), AskUserOption("drop", "删掉")),
    )

    // ── 判据：锁屏期间不弹卡，进队列 ────────────────────────────────────

    @Test
    fun `离场加锁屏_不弹_进队列_凭据写离场值守锁屏`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(null, dayShape(), t0, req("删哪些旧稿？"), awayLocked = true)
        assertEquals(QuietCardVerdict.DEFER, outcome.verdict)
        assertTrue("凭据要点名新源：${outcome.line}", outcome.line.contains("离场值守·锁屏"))
        assertEquals(1, gate.size)
    }

    @Test
    fun `没锁屏_同样的卡_照常弹_330行为一行不改`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(null, dayShape(), t0, req("删哪些旧稿？"), awayLocked = false)
        assertEquals(QuietCardVerdict.ASK, outcome.verdict)
        assertEquals(0, gate.size)
    }

    @Test
    fun `值守没锁屏的时段_卡不进队列_不误伤`() {
        // awayLocked 是"开值守**且**锁着屏"的合取，装配层算好喂进来；false 就是普通档。
        val gate = QuietCardGate()
        gate.onAsk(null, dayShape(), t0, req("第一件"), awayLocked = false)
        assertEquals(0, gate.size)
    }

    // ── 补问与放行（锁一开按序补问，不丢） ──────────────────────────────

    @Test
    fun `还锁着_补问口不放行_队列一件不丢`() {
        val gate = QuietCardGate()
        gate.onAsk(null, dayShape(), t0, req("第一件"), awayLocked = true)
        gate.onAsk(null, dayShape(), t0 + 1, req("第二件"), awayLocked = true)
        assertNull("锁还没开，补问口不许放行", gate.takeNext(null, dayShape(), t0 + 2, awayLocked = true))
        assertEquals(2, gate.size)
    }

    @Test
    fun `解锁之后_按入队序原样补问`() {
        val gate = QuietCardGate()
        gate.onAsk(null, dayShape(), t0, req("第一件"), awayLocked = true)
        gate.onAsk(null, dayShape(), t0 + 1, req("第二件"), awayLocked = true)
        val first = gate.takeNext(null, dayShape(), t0 + 2, awayLocked = false)!!
        assertEquals("第一件", first.headline)
        assertEquals("凭据是压下那一刻写死的，解锁不篡改账", true, first.why.contains("离场值守·锁屏"))
        val second = gate.takeNext(null, dayShape(), t0 + 3, awayLocked = false)!!
        assertEquals("第二件", second.headline)
    }

    @Test
    fun `满员转检查点等待_离场锁屏也不许永久不问`() {
        val gate = QuietCardGate()
        repeat(QuietGateLimits.MAX_DEFERRED) { index ->
            gate.onAsk(null, dayShape(), t0 + index, req("第${index + 1}件"), awayLocked = true)
        }
        val outcome = gate.onAsk(null, dayShape(), t0 + 99, req("第四件"), awayLocked = true)
        assertEquals(QuietCardVerdict.CHECKPOINT_WAIT, outcome.verdict)
        assertTrue("满员的账行要点名检查点：${outcome.line}", outcome.line.contains("检查点"))
    }

    @Test
    fun `静默上限到期_哪怕还锁着也必现_纪律兜底`() {
        // dueMs＝首延后＋4h：离场锁屏没有天然终点，到期尺是"不许永久不问"的唯一兜底。
        val gate = QuietCardGate()
        gate.onAsk(null, dayShape(), t0, req("第一件"), awayLocked = true)
        val due = gate.takeNext(null, dayShape(), t0 + QuietLimits.MAX_MS, awayLocked = true)
        assertEquals("第一件", due?.headline)
    }

    @Test
    fun `离场锁屏与免打扰同时命中_凭据两行连写`() {
        val gate = QuietCardGate()
        val grant = QuietMode.grant("task-quiet", QuietLimits.MAX_MINUTES, t0)
        val outcome = gate.onAsk(grant, dayShape(), t0 + QuietLimits.MINUTE_MS, req("删稿？"), awayLocked = true)
        assertTrue("两个源都要在账上：${outcome.line}", outcome.line.contains("离场值守·锁屏"))
        assertTrue(outcome.line.contains("免打扰授权中"))
    }
}
