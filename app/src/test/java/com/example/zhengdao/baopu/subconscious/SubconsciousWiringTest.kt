// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerState
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 676 装配判据的 JVM 直测（临时沙盒自清：TempSandbox use{}）。
 *
 * Context 一格不在 JVM 测（本仓无 Robolectric）：装配入口的 Context 那一行是薄壳，
 * 可测的判据全在 [echoCandidatesOf]／[suggestedAtByKey]／状态文件与模型解析这几件。
 */
class SubconsciousWiringTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.systemDefault()
        val DAY0: LocalDate = LocalDate.of(2026, 11, 16)
        const val HOUR_MS: Long = 60L * 60 * 1000
        const val DAY_MS: Long = 24L * HOUR_MS
    }

    private fun at(dayOffset: Long, hour: Long): Long =
        DAY0.plusDays(dayOffset).atStartOfDay(ZONE).toInstant().toEpochMilli() + hour * HOUR_MS

    private fun item(
        id: String,
        status: LedgerState,
        createdAt: Long,
        verdict: String = "",
        verifiedAt: Long = 0L,
        sourceMsgId: String = "",
        archivedAtMs: Long = 0L,
    ): LedgerItem = LedgerItem(
        id = id,
        title = "账行 $id 的标题",
        sourceMsgId = sourceMsgId,
        createdAtMs = createdAt,
        status = status,
        verdict = verdict,
        verifiedAtMs = verifiedAt,
        archivedAtMs = archivedAtMs,
    )

    // ── 候选挑选（625 §一①：最近失败 / 未完成步骤 / 用户提过没做） ─────────────

    @Test
    fun `最近失败_7天内的FAIL行出候选_标下意识`() {
        val now = at(0, 10)
        val items = listOf(item("L2", LedgerState.PENDING, at(0, 0), verdict = "FAIL", verifiedAt = now - DAY_MS))
        val candidates = echoCandidatesOf(items, now)
        assertEquals(1, candidates.size)
        assertEquals(EchoKind.FAIL_RECENT, candidates[0].kind)
        assertEquals("echo:L2", candidates[0].id)
        assertEquals("L2", candidates[0].ledgerId)
    }

    @Test
    fun `失败太老不出候选`() {
        val now = at(0, 10)
        val items = listOf(item("L2", LedgerState.PENDING, at(0, 0), verdict = "FAIL", verifiedAt = now - 8 * DAY_MS))
        assertTrue(echoCandidatesOf(items, now).isEmpty())
    }

    @Test
    fun `未完成步骤_在飞搁置超3天出候选_标想起`() {
        val now = at(0, 10)
        val items = listOf(item("L5", LedgerState.FLYING, now - 5 * DAY_MS))
        val candidates = echoCandidatesOf(items, now)
        assertEquals(1, candidates.size)
        assertEquals(EchoKind.STEP_UNFINISHED, candidates[0].kind)
    }

    @Test
    fun `刚立的在飞行不出候选_搁置太久的也不出`() {
        val now = at(0, 10)
        val items = listOf(
            item("L1", LedgerState.FLYING, now - DAY_MS / 2),
            item("L6", LedgerState.FLYING, now - 40 * DAY_MS),
        )
        assertTrue(echoCandidatesOf(items, now).isEmpty())
    }

    @Test
    fun `用户提过没做_带来源的冻结或撤行出候选_标想起`() {
        val now = at(0, 10)
        val items = listOf(
            item("L7", LedgerState.FROZEN, now - 2 * DAY_MS, sourceMsgId = "m42"),
            item("L8", LedgerState.WITHDRAWN, now - 3 * DAY_MS, sourceMsgId = "m43"),
        )
        val candidates = echoCandidatesOf(items, now)
        assertEquals(2, candidates.size)
        assertTrue(candidates.all { it.kind == EchoKind.MENTIONED_NOT_DONE })
    }

    @Test
    fun `没来源的冻结行不算提过`() {
        val now = at(0, 10)
        val items = listOf(item("L7", LedgerState.FROZEN, now - 2 * DAY_MS))
        assertTrue(echoCandidatesOf(items, now).isEmpty())
    }

    @Test
    fun `已归档的行不回响`() {
        val now = at(0, 10)
        val items = listOf(
            item("L2", LedgerState.PENDING, at(0, 0), verdict = "FAIL", verifiedAt = now - DAY_MS, archivedAtMs = now),
        )
        assertTrue(echoCandidatesOf(items, now).isEmpty())
    }

    @Test
    fun `同一行只出一类候选_id稳定`() {
        val now = at(0, 10)
        val items = listOf(
            item("L2", LedgerState.PENDING, at(0, 0), verdict = "FAIL", verifiedAt = now - DAY_MS),
            item("L5", LedgerState.FLYING, now - 5 * DAY_MS),
        )
        val candidates = echoCandidatesOf(items, now)
        assertEquals(listOf("echo:L2", "echo:L5"), candidates.map { it.id })
    }

    // ── 自己那本账：起居注同形 ACTION 行读回（冷却表） ────────────────────────

    @Test
    fun `回响账读回冷却表_同一账行取最新一次`() {
        TempSandbox.use("zd676-echo-ledger") { dir ->
            val f = echoLedgerFile(dir)
            f.appendText(DiaryCodec.encode(DiaryEvent(DiaryKind.ACTION, at(0, 9), "676 回响 · echo:L2", "标题（依据账行 L2）", "下意识·右相可建议")) + "\n")
            f.appendText(DiaryCodec.encode(DiaryEvent(DiaryKind.ACTION, at(0, 15), "676 回响 · echo:L2", "标题（依据账行 L2）", "下意识·右相可建议")) + "\n")
            f.appendText(DiaryCodec.encode(DiaryEvent(DiaryKind.ACTION, at(0, 10), "别的账写的 ACTION", "与回响无关", "无关")) + "\n")
            f.appendText("坏行不算\n")
            val key = suggestedAtByKey(dir)
            assertEquals(at(0, 15), key["L2"])
            assertTrue(key.size == 1)
        }
    }

    @Test
    fun `没有回响账_冷却表为空`() {
        TempSandbox.use("zd676-echo-empty") { dir ->
            assertTrue(suggestedAtByKey(dir).isEmpty())
        }
    }

    // ── 天闸状态文件（last-run 桶的落与读） ──────────────────────────────────

    @Test
    fun `状态文件_写读一圈_lastRunDayKey取回`() {
        TempSandbox.use("zd676-echo-state") { dir ->
            assertNull(readEchoState(dir))
            val outcome = SubconsciousRunner.run(
                candidates = emptyList(),
                nowMs = at(0, 10),
                grant = null,
                shape = com.example.zhengdao.baopu.agent.SessionShape(hourOfDay = 10),
                lastRunDayKey = null,
                suggestedAtByKey = emptyMap(),
                picker = null,
            )
            writeEchoState(dir, outcome, at(0, 10))
            val state = readEchoState(dir)!!
            assertEquals(outcome.dayKey, state.optLong("lastRunDayKey", -1L))
        }
    }

    @Test
    fun `状态文件坏了_当从没跑过`() {
        TempSandbox.use("zd676-echo-badstate") { dir ->
            echoStateFile(dir).writeText("不是JSON", Charsets.UTF_8)
            assertNull(readEchoState(dir))
        }
    }

    // ── 模型挑选的解析（选不造：只认候选集里的 id） ───────────────────────────

    @Test
    fun `解析_带前缀与裸id都认_候选集外扔`() {
        val now = at(0, 10)
        val pool = listOf(
            SubconsciousCandidate(id = "echo:L2", kind = EchoKind.FAIL_RECENT, title = "a", ledgerId = "L2", atMs = now - DAY_MS),
            SubconsciousCandidate(id = "echo:L3", kind = EchoKind.STEP_UNFINISHED, title = "b", ledgerId = "L3", atMs = now - 5 * DAY_MS),
        )
        assertEquals(listOf("echo:L2", "echo:L3"), parseModelPicks("echo:L2\nL3\necho:L9", pool)!!)
        assertNull(parseModelPicks("echo:L9", pool))
        assertNull(parseModelPicks("", pool))
    }
}
