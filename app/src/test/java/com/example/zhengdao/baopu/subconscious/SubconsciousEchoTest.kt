// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.subconscious

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 676 判据层的 JVM 直测（纯判据：右相三态／规则版挑选／模型选不造／天闸尺）。
 *
 * 每条判据都带负例：判据能被绕过它就是假的。
 */
class SubconsciousEchoTest {

    private companion object {
        /** 任意一个固定锚点；「同一天/第二天」用本地时区现算，任何时区跑都不 flaky。 */
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DAY0: LocalDate = LocalDate.of(2026, 11, 16)
        const val HOUR_MS: Long = 60L * 60 * 1000
        const val DAY_MS: Long = 24L * HOUR_MS
    }

    /** 本地日 0 点起的第 [hour] 小时的时刻（同一天内的时间注入夹具）。 */
    private fun at(dayOffset: Long, hour: Long): Long =
        DAY0.plusDays(dayOffset).atStartOfDay(ZONE).toInstant().toEpochMilli() + hour * HOUR_MS

    private fun cand(
        ledgerId: String = "L2",
        kind: EchoKind = EchoKind.FAIL_RECENT,
        atMs: Long,
        verdict: String = "",
    ): SubconsciousCandidate = SubconsciousCandidate(
        id = "echo:$ledgerId",
        kind = kind,
        title = "账行 $ledgerId 的一句标题",
        ledgerId = ledgerId,
        atMs = atMs,
        verdictOfLedger = verdict,
    )

    // ── 判据②：候选必须指回账行（缺引用 ＝ 活不到出卡） ─────────────────────

    @Test
    fun `候选缺账行引用在构造期就拒`() {
        try {
            SubconsciousCandidate(id = "echo:X", kind = EchoKind.FAIL_RECENT, title = "没引用", ledgerId = " ", atMs = 0L)
            fail("缺引用的候选不该被构造出来")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("账行"))
        }
    }

    @Test
    fun `候选标题必须一行`() {
        try {
            SubconsciousCandidate(id = "echo:L1", kind = EchoKind.FAIL_RECENT, title = "两\n行", ledgerId = "L1", atMs = 0L)
            fail("带换行的标题不该被构造出来")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("一行"))
        }
    }

    // ── 右相三态（判据③） ──────────────────────────────────────────────────

    @Test
    fun `右相_新鲜失败判可建议`() {
        val now = at(0, 10)
        assertEquals(EchoVerdict.RECOMMEND, rightMinisterJudge(cand(atMs = now - DAY_MS), now, null))
    }

    @Test
    fun `右相_账龄过半判不确定`() {
        val now = at(0, 10)
        // 失败窗 7 天，4 天前 ＝ 过半 ⇒ 不确定
        assertEquals(EchoVerdict.UNCERTAIN, rightMinisterJudge(cand(atMs = now - 4 * DAY_MS), now, null))
    }

    @Test
    fun `右相_超窗判不建议`() {
        val now = at(0, 10)
        // 8 天前的失败超出 7 天窗 ⇒ 不建议
        assertEquals(EchoVerdict.NOT_RECOMMEND, rightMinisterJudge(cand(atMs = now - 8 * DAY_MS), now, null))
    }

    @Test
    fun `右相_冷却期内提过判不建议`() {
        val now = at(0, 10)
        assertEquals(
            EchoVerdict.NOT_RECOMMEND,
            rightMinisterJudge(cand(atMs = now - DAY_MS), now, suggestedAtMs = now - DAY_MS),
        )
    }

    @Test
    fun `右相_冷却过期后可再提`() {
        val now = at(0, 10)
        assertEquals(
            EchoVerdict.RECOMMEND,
            rightMinisterJudge(cand(atMs = now - DAY_MS), now, suggestedAtMs = now - 4 * DAY_MS),
        )
    }

    // ── 规则版挑选（判据④的退回路；确定性排序） ─────────────────────────────

    @Test
    fun `规则版_失败最先_上限三枚`() {
        val now = at(0, 10)
        val pool = listOf(
            cand("L1", EchoKind.MENTIONED_NOT_DONE, atMs = now - DAY_MS),
            cand("L2", EchoKind.FAIL_RECENT, atMs = now - 2 * DAY_MS),
            cand("L3", EchoKind.STEP_UNFINISHED, atMs = now - 5 * DAY_MS),
            cand("L4", EchoKind.FAIL_RECENT, atMs = now - DAY_MS),
            cand("L5", EchoKind.FAIL_RECENT, atMs = now - 3 * DAY_MS),
        )
        val picked = rulesPick(pool, emptyMap(), now)
        assertEquals(listOf("L4", "L2", "L5"), picked.map { it.ledgerId })
    }

    @Test
    fun `规则版_未完成步骤最久搁置的先`() {
        val now = at(0, 10)
        val pool = listOf(
            cand("L3", EchoKind.STEP_UNFINISHED, atMs = now - 4 * DAY_MS),
            cand("L1", EchoKind.STEP_UNFINISHED, atMs = now - 10 * DAY_MS),
        )
        assertEquals(listOf("L1", "L3"), rulesPick(pool, emptyMap(), now).map { it.ledgerId })
    }

    @Test
    fun `规则版_冷却内的不再挑`() {
        val now = at(0, 10)
        val pool = listOf(cand("L2", EchoKind.FAIL_RECENT, atMs = now - DAY_MS))
        val picked = rulesPick(pool, mapOf("L2" to now - DAY_MS), now)
        assertTrue(picked.isEmpty())
    }

    // ── 模型选不造（判据②⑤：选出的 id 必须在候选集里） ──────────────────────

    @Test
    fun `模型选不造_候选集外的id一律扔`() {
        val now = at(0, 10)
        val pool = listOf(cand("L2", atMs = now - DAY_MS), cand("L3", atMs = now - 2 * DAY_MS))
        val picked = sanitizeModelPick(listOf("echo:L2", "echo:凭空造的L9"), pool, emptyMap(), now)
        assertEquals(listOf("L2"), picked!!.map { it.ledgerId })
    }

    @Test
    fun `模型全挑非法id_回null退规则`() {
        val now = at(0, 10)
        val pool = listOf(cand("L2", atMs = now - DAY_MS))
        assertNull(sanitizeModelPick(listOf("echo:L9", "echo:L8"), pool, emptyMap(), now))
    }

    @Test
    fun `模型空手_回null退规则`() {
        val now = at(0, 10)
        assertNull(sanitizeModelPick(emptyList(), listOf(cand("L2", atMs = now - DAY_MS)), emptyMap(), now))
    }

    @Test
    fun `模型挑的也过右相_不建议的进不来`() {
        val now = at(0, 10)
        val pool = listOf(
            cand("L2", atMs = now - DAY_MS),
            cand("L8", atMs = now - 9 * DAY_MS),
        )
        val picked = sanitizeModelPick(listOf("echo:L2", "echo:L8"), pool, emptyMap(), now)
        assertEquals(listOf("L2"), picked!!.map { it.ledgerId })
    }

    @Test
    fun `模型挑的也卡上限`() {
        val now = at(0, 10)
        val pool = (1L..5L).map { cand("L$it", atMs = now - it * DAY_MS) }
        val picked = sanitizeModelPick(pool.map { it.id }, pool, emptyMap(), now)
        assertEquals(EchoLimits.ROWS_MAX, picked!!.size)
    }

    // ── 天闸的尺（判据①的时间注入夹具） ─────────────────────────────────────

    @Test
    fun `同一天的桶相同_跨天不同`() {
        val morning = at(0, 9)
        val night = at(0, 21)
        val nextDay = at(1, 9)
        assertEquals(echoDayKey(morning), echoDayKey(night))
        assertTrue(echoDayKey(morning) != echoDayKey(nextDay))
    }

    @Test
    fun `回响三类来源的typeLabel_灵感不在本批`() {
        // 派工单：typeLabel 区分灵感/想起/下意识；「灵感」归 677，本批三类只占两签。
        assertEquals("下意识", EchoKind.FAIL_RECENT.label)
        assertEquals("想起", EchoKind.STEP_UNFINISHED.label)
        assertEquals("想起", EchoKind.MENTIONED_NOT_DONE.label)
        assertTrue(EchoKind.entries.none { it.label == "灵感" })
    }
}
