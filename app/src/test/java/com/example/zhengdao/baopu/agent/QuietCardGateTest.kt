// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 三百三十批接线 · 免打扰弹卡闸的单测（纯 JVM，零 Android、零时钟——时刻全由用例递进来）。
//
// 判据对应（派工单 2026-11-02）：
//   ① 静默期内造一件本该问的事 ⇒ 不弹、记延后；
//   ② 静默结束按序补问（不丢）；
//   ③ 一句话进静默可查；
//   ④ 超上限转等待（队容满 ／ 静默时长超 4 小时，两条都到得了检查点）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietCardGateTest {

    private val t0 = 1_700_000_000_000L

    /** 日间不忙（14 点、队列空）＝三格静默全不命中。 */
    private fun dayShape(busy: Boolean = false) = SessionShape(hourOfDay = 14, busy = busy)

    private fun nightShape(busy: Boolean = false) = SessionShape(hourOfDay = 23, busy = busy)

    private fun req(q: String) = AskUserRequest(
        question = q,
        options = listOf(AskUserOption("keep", "都留着"), AskUserOption("drop", "删掉")),
    )

    /** 授权在 t0 开、满 240 分钟（判据④的超上限要用"过了 due 还在静默"的形状）。 */
    private fun activeGrant(atMs: Long = t0, minutes: Long = QuietLimits.MAX_MINUTES) =
        QuietMode.grant("task-quiet", minutes, atMs)

    // ── 判据①：静默期内不弹、记延后 ─────────────────────────────────────

    @Test
    fun `静默期内造一件本该问的事_不弹_记因免打扰延后`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(activeGrant(), dayShape(), t0 + QuietLimits.MINUTE_MS, req("删哪些旧稿？"))
        assertEquals(QuietCardVerdict.DEFER, outcome.verdict)
        assertTrue("账行要点名这件事：${outcome.line}", outcome.line.contains("因免打扰延后"))
        assertTrue("账行要凭据可查：${outcome.line}", outcome.line.contains("免打扰授权中"))
        assertTrue("账行要有静默上限（不许永久不问）：${outcome.line}", outcome.line.contains("静默上限 ms"))
        assertEquals("不弹＝进队列等着，不是消失", 1, gate.size)
        assertEquals(
            "静默上限就是授权那把尺（4 小时），不新造第二个数",
            t0 + QuietLimits.MINUTE_MS + QuietLimits.MAX_MS,
            outcome.deferred!!.dueMs,
        )
    }

    @Test
    fun `夜间时段同样延后_凭要写夜间`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(null, nightShape(), t0, req("要不要合并这两段？"))
        assertEquals(QuietCardVerdict.DEFER, outcome.verdict)
        assertTrue(outcome.line.contains("夜间时段"))
    }

    @Test
    fun `专注期连发指令中同样延后`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(null, dayShape(busy = true), t0, req("要继续吗？"))
        assertEquals(QuietCardVerdict.DEFER, outcome.verdict)
        assertTrue(outcome.line.contains("专注期"))
    }

    @Test
    fun `三个静默源都命中_凭一格全写出来`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(activeGrant(), nightShape(busy = true), t0, req("继续吗？"))
        assertTrue(outcome.line.contains("免打扰授权中＋夜间时段＋专注期"))
    }

    @Test
    fun `不静默就照常弹_不留账不进队`() {
        val gate = QuietCardGate()
        val outcome = gate.onAsk(null, dayShape(), t0, req("删哪些旧稿？"))
        assertEquals(QuietCardVerdict.ASK, outcome.verdict)
        assertEquals("照常弹没有要记的账", "", outcome.line)
        assertNull(outcome.deferred)
        assertEquals(0, gate.size)
    }

    // ── 判据②：静默结束按序补问，一件不丢 ───────────────────────────────

    @Test
    fun `静默结束按序补问_先压的先现身_原卡原样`() {
        val gate = QuietCardGate()
        val hushed = t0 + QuietLimits.MINUTE_MS
        gate.onAsk(activeGrant(), dayShape(), hushed, req("第一件：删稿？"))
        gate.onAsk(activeGrant(), dayShape(), hushed, req("第二件：合并？"))
        val after = hushed + QuietLimits.MAX_MS + QuietLimits.MINUTE_MS // 授权也过了，静默彻底结束
        val first = gate.takeNext(activeGrant(), dayShape(), after)
        assertEquals("按入队序：先压的先出来", "第一件：删稿？", first!!.headline)
        assertEquals("补问走原卡，一字不改（选项都在）", 2, first.request.options.size)
        assertEquals("第二件紧跟着", "第二件：合并？", gate.takeNext(activeGrant(), dayShape(), after)!!.headline)
        assertNull("补问完队列就空了，不剩第四件", gate.takeNext(activeGrant(), dayShape(), after))
    }

    @Test
    fun `还在静默期且没到上限_一件都不现身`() {
        val gate = QuietCardGate()
        gate.onAsk(activeGrant(), dayShape(), t0, req("删稿？"))
        assertNull("免打扰期内零卡（判据①的另一半：不许提前弹）", gate.takeNext(activeGrant(), dayShape(), t0 + QuietLimits.MINUTE_MS))
        assertEquals(1, gate.size)
    }

    @Test
    fun `队首不能现身就停_后件不许越序`() {
        val gate = QuietCardGate()
        gate.onAsk(activeGrant(), dayShape(), t0, req("第一件"))
        gate.onAsk(activeGrant(), dayShape(), t0, req("第二件"))
        gate.onAsk(activeGrant(), dayShape(), t0, req("第三件"))
        // 第 4 件满员 ⇒ 转检查点等待（可现身的那格）；但队首三件还没到静默上限，
        // 按序优先：检查点那件不许越过队首先冒出来。
        val fourth = gate.onAsk(activeGrant(), dayShape(), t0, req("第四件"))
        assertEquals(QuietCardVerdict.CHECKPOINT_WAIT, fourth.verdict)
        assertNull(gate.takeNext(activeGrant(), dayShape(), t0 + QuietLimits.MINUTE_MS))
        assertEquals("一件都没少", 4, gate.size)
    }

    // ── 判据③：一句话进静默，可查 ──────────────────────────────────────

    @Test
    fun `一句话开授权_状态一行可查`() {
        val gate = QuietCardGate()
        val grant = QuietMode.grantFromText("别弹了，直接干吧", "task-quiet", t0)
        assertTrue("触发词认不出就没有授权", grant != null)
        val line = gate.stateLine(grant, t0 + 60 * QuietLimits.MINUTE_MS)
        assertFalse("可查＝一行（换行就是第二条账）", line.contains('\n'))
        assertTrue("授权态要看得见：$line", line.contains("免打扰中"))
        assertTrue("还剩多久要看得见：$line", line.contains("还剩 180 分钟"))
        assertTrue(line.contains("延后 0 件"))
    }

    @Test
    fun `授权落账那笔_五样都在`() {
        val grant = QuietMode.grantFromText("别弹了", "task-quiet", t0)!!
        val record = QuietMode.authRecord(grant, t0)
        assertTrue(record.contains("别弹了"))
        assertTrue(record.contains("task-quiet"))
        assertTrue(record.contains("240 分钟"))
        assertTrue("外发一律不做也要在账上", record.contains("外发一律不做"))
    }

    @Test
    fun `延后之后状态行带队列读数_最老一件等多久`() {
        val gate = QuietCardGate()
        val grant = activeGrant()
        gate.onAsk(grant, dayShape(), t0, req("删稿？"))
        gate.onAsk(grant, dayShape(), t0 + QuietLimits.MINUTE_MS, req("合并？"))
        val line = gate.stateLine(grant, t0 + 60 * QuietLimits.MINUTE_MS)
        assertTrue(line.contains("延后 2 件"))
        assertTrue("最老一件从 t0 等起：$line", line.contains("最老一件已等 60/${QuietLimits.MAX_MINUTES} 分钟"))
    }

    @Test
    fun `认不出触发词的句子不开授权_也不冒充静默`() {
        val gate = QuietCardGate()
        assertNull("普通句子没有免问权", QuietMode.grantFromText("把这份稿子删了", "task-quiet", t0))
        assertEquals("没授权不冒充静默：照常弹", QuietCardVerdict.ASK, gate.onAsk(null, dayShape(), t0, req("删稿？")).verdict)
    }

    // ── 判据④：超上限转等待（队容与静默时长两条路） ──────────────────────

    @Test
    fun `延后满三件_第四件转检查点等待_照样进队不丢`() {
        val gate = QuietCardGate()
        val grant = activeGrant()
        repeat(3) { i ->
            assertEquals("前三件都记延后", QuietCardVerdict.DEFER, gate.onAsk(grant, dayShape(), t0 + i, req("第${i + 1}件")).verdict)
        }
        val fourth = gate.onAsk(grant, dayShape(), t0 + 3, req("第四件"))
        assertEquals(QuietCardVerdict.CHECKPOINT_WAIT, fourth.verdict)
        assertTrue("账要换成更响的那行：${fourth.line}", fourth.line.contains("转检查点等待"))
        assertTrue(fourth.line.contains("不许永久不问"))
        assertTrue(fourth.deferred!!.checkpoint)
        assertEquals("满员不拒：照样进队（拒了就是丢）", 4, gate.size)
    }

    @Test
    fun `检查点等待的那件_轮到它时哪怕还在静默期也现身`() {
        val gate = QuietCardGate()
        val firstGrant = activeGrant()
        repeat(3) { gate.onAsk(firstGrant, dayShape(), t0, req("早期第${gate.size + 1}件")) }
        // 她中途又续了一张授权（t0+2h 起 4 小时）⇒ 到 t0+4h+1 前三件到期时**静默还在**。
        val secondGrant = QuietMode.grant("task-quiet", QuietLimits.MAX_MINUTES, t0 + 2 * 60 * QuietLimits.MINUTE_MS)
        val fourthAt = t0 + 3 * 60 * QuietLimits.MINUTE_MS
        gate.onAsk(secondGrant, dayShape(), fourthAt, req("第四件"))
        val due = t0 + QuietLimits.MAX_MS + QuietLimits.MINUTE_MS
        repeat(3) { assertTrue(gate.takeNext(secondGrant, dayShape(), due) != null) }
        val last = gate.takeNext(secondGrant, dayShape(), due)
        assertEquals("第四件", last!!.headline)
        assertTrue("转检查点等待＝必现，不要求静默先结束", last.checkpoint)
    }

    @Test
    fun `静默超上限_天没亮授权没到也到点现身_账行说超上限`() {
        val gate = QuietCardGate()
        gate.onAsk(null, nightShape(), t0, req("深夜要合并吗？"))
        // 一小时后她开了授权（4 小时）⇒ 到期尺到点时静默还在（夜间 ＋ 授权）。
        val grant = QuietMode.grant("task-quiet", QuietLimits.MAX_MINUTES, t0 + 60 * QuietLimits.MINUTE_MS)
        val due = t0 + QuietLimits.MAX_MS + QuietLimits.MINUTE_MS
        val item = gate.takeNext(grant, nightShape(), due)
        assertEquals("深夜要合并吗？", item!!.headline)
        val line = gate.comebackLine(item, grant, nightShape(), due)
        assertTrue("补问账要点破是超上限兜的底：$line", line.contains("静默超上限"))
    }

    @Test
    fun `静默结束的补问账_说按序`() {
        val gate = QuietCardGate()
        gate.onAsk(activeGrant(), dayShape(), t0, req("删稿？"))
        val after = t0 + QuietLimits.MAX_MS + QuietLimits.MINUTE_MS
        val item = gate.takeNext(null, dayShape(), after)
        assertTrue(gate.comebackLine(item!!, null, dayShape(), after).contains("静默结束按序补问"))
    }

    @Test
    fun `未授权也照印状态行_没有静默本身是可查的答案`() {
        val line = QuietCardGate().stateLine(null, t0)
        assertTrue(line.contains("未授权"))
        assertTrue(line.contains("延后 0 件"))
    }
}
