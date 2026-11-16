// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.agent.QuietMode
import com.example.zhengdao.baopu.agent.QuietTrigger
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.safety.DiaryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 676 编排层的 JVM 直测：一轮回响的闸次序、退回、行数判据（时间注入夹具）。
 */
class SubconsciousRunnerTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DAY0: LocalDate = LocalDate.of(2026, 11, 16)
        const val HOUR_MS: Long = 60L * 60 * 1000
        const val DAY_MS: Long = 24L * HOUR_MS
    }

    private fun at(dayOffset: Long, hour: Long): Long =
        DAY0.plusDays(dayOffset).atStartOfDay(ZONE).toInstant().toEpochMilli() + hour * HOUR_MS

    private fun cand(ledgerId: String, atMs: Long, kind: EchoKind = EchoKind.FAIL_RECENT): SubconsciousCandidate =
        SubconsciousCandidate(
            id = "echo:$ledgerId",
            kind = kind,
            title = "账行 $ledgerId",
            ledgerId = ledgerId,
            atMs = atMs,
        )

    private fun shape(hour: Long? = 10L, busy: Boolean = false): SessionShape =
        SessionShape(hourOfDay = hour?.toInt(), busy = busy)

    // ── 判据①：默认开；一天 ≤1 次（时间注入） ───────────────────────────────

    @Test
    fun `一天只浮一次_同天第二趟什么都不做`() {
        val morning = at(0, 9)
        val first = SubconsciousRunner.run(
            candidates = listOf(cand("L2", morning - DAY_MS)),
            nowMs = morning,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(first.ran)
        assertEquals(1, first.signals.size)
        // 同一天晚些时候再触发：天闸拦下，不调模型、不出信号
        val second = SubconsciousRunner.run(
            candidates = listOf(cand("L3", morning)),
            nowMs = at(0, 20),
            grant = null,
            shape = shape(),
            lastRunDayKey = first.dayKey,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertFalse(second.ran)
        assertTrue(second.signals.isEmpty())
        assertTrue(second.actionRows.isEmpty())
        assertTrue(second.skipWhy.contains("一天"))
    }

    @Test
    fun `跨天后天闸放行`() {
        val today = at(0, 9)
        val yesterdayRan = SubconsciousRunner.run(
            candidates = emptyList(),
            nowMs = today,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        val nextDay = SubconsciousRunner.run(
            candidates = listOf(cand("L2", today - DAY_MS)),
            nowMs = at(1, 9),
            grant = null,
            shape = shape(),
            lastRunDayKey = yesterdayRan.dayKey,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(nextDay.ran)
    }

    // ── 判据③：免打扰窗口内不打扰（负例：弹了 ⇒ 红） ─────────────────────────

    @Test
    fun `负例_夜间时段内不出信号`() {
        val now = at(0, 10) // nowMs 本身不给形状；形状由调用方注入（23 点＝深夜）
        val outcome = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(hour = 23),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(outcome.deferred)
        assertTrue(outcome.signals.isEmpty())
        assertTrue(outcome.actionRows.isEmpty())
        assertFalse(outcome.ran)
    }

    @Test
    fun `负例_免打扰授权内不出信号`() {
        val now = at(0, 10)
        val grant = QuietMode.grant(taskId = "t1", nowMs = now, trigger = QuietTrigger.NO_CARDS)
        val outcome = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = grant,
            shape = shape(hour = 10),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(outcome.deferred)
        assertTrue(outcome.signals.isEmpty())
    }

    @Test
    fun `延后不耗当日额度_同天免打扰结束后还能跑`() {
        val now = at(0, 10)
        // 先在免打扰里延后一轮（不落 last-run）
        val deferred = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = QuietMode.grant(taskId = "t1", nowMs = now),
            shape = shape(hour = 10),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(deferred.deferred)
        // 授权撤了（grant=null）同一时刻再触发 ⇒ 照跑（延后不是丢弃，也不扣额度）
        val after = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(hour = 10),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(after.ran)
        assertEquals(1, after.signals.size)
    }

    // ── 判据④：模型不可用 ⇒ 退回规则版（不崩、不空转） ───────────────────────

    @Test
    fun `模型不可用_退回规则版照常出信号`() {
        val now = at(0, 10)
        val outcome = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = { _ -> throw RuntimeException("模型炸了") },
        )
        assertTrue(outcome.ran)
        assertFalse(outcome.modelPicked)
        assertEquals(1, outcome.signals.size)
        assertFalse(outcome.signals[0].modelPicked)
    }

    @Test
    fun `模型挑非法id_也退回规则版`() {
        val now = at(0, 10)
        val outcome = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = { _ -> listOf("echo:凭空造的") },
        )
        assertTrue(outcome.ran)
        assertFalse(outcome.modelPicked)
    }

    @Test
    fun `模型正常挑_如实标注模型版`() {
        val now = at(0, 10)
        val pool = listOf(cand("L2", now - DAY_MS), cand("L3", now - 2 * DAY_MS))
        val outcome = SubconsciousRunner.run(
            candidates = pool,
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = { candidates -> listOf(candidates.last().id) },
        )
        assertTrue(outcome.modelPicked)
        assertEquals(1, outcome.signals.size)
        assertEquals("L3", outcome.signals[0].ledgerId)
    }

    // ── 判据⑤：起居注 ACTION 行数与该次建议数一致 ───────────────────────────

    @Test
    fun `每枚信号一行ACTION_0信号0行`() {
        val now = at(0, 10)
        val withSignal = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS), cand("L3", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertEquals(withSignal.signals.size, withSignal.actionRows.size)
        withSignal.actionRows.forEach { row ->
            assertEquals(DiaryKind.ACTION, row.kind)
            assertTrue(row.why.startsWith(SubconsciousRunner.ACTION_WHY))
            assertTrue(row.what.contains("账行"))
            // 依据哪条账：行里带得到信号指的账行 id
            assertTrue(withSignal.signals.any { row.what.contains(it.ledgerId) })
        }
        val emptyRound = SubconsciousRunner.run(
            candidates = listOf(cand("L9", now - 90 * DAY_MS)), // 超窗 ⇒ 右相不建议 ⇒ 0 信号
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertTrue(emptyRound.ran)
        assertTrue(emptyRound.signals.isEmpty())
        assertTrue(emptyRound.actionRows.isEmpty())
    }

    @Test
    fun `候选上限8_喂给模型前截断`() {
        val now = at(0, 10)
        val pool = (1L..12L).map { cand("L$it", atMs = now - DAY_MS) }
        var given: List<SubconsciousCandidate>? = null
        val outcome = SubconsciousRunner.run(
            candidates = pool,
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = { candidates ->
                given = candidates
                null // 模型这趟不挑 ⇒ 规则版；但候选集先过一道 ≤8
            },
        )
        assertEquals(EchoLimits.CANDIDATES_CAP, given!!.size)
        assertEquals(EchoLimits.ROWS_MAX, outcome.signals.size)
    }

    @Test
    fun `右相冷却用装配层喂的账_同一账行三天内不再浮`() {
        val now = at(0, 10)
        val outcome = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = mapOf("L2" to now - DAY_MS),
            picker = null,
        )
        assertTrue(outcome.ran)
        assertTrue(outcome.signals.isEmpty())
    }

    @Test
    fun `三态结论闭集_浮出的恒为可建议`() {
        val now = at(0, 10)
        val outcome = SubconsciousRunner.run(
            candidates = listOf(cand("L2", now - DAY_MS)),
            nowMs = now,
            grant = null,
            shape = shape(),
            lastRunDayKey = null,
            suggestedAtByKey = emptyMap(),
            picker = null,
        )
        assertEquals(EchoVerdict.RECOMMEND, outcome.signals[0].verdict)
        assertEquals(listOf("可建议", "不建议", "不确定"), EchoVerdict.entries.map { it.cn })
    }
}
