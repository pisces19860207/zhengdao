// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 判据②③④：关联只用既有索引 + 每天≤1不点不进上下文 + 与625共用去重。
package com.example.zhengdao.baopu.insight

import com.example.zhengdao.baopu.agent.QuietGrant
import com.example.zhengdao.baopu.agent.QuietTrigger
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.recall.IndexHit
import com.example.zhengdao.baopu.recall.IndexPoint
import com.example.zhengdao.baopu.recall.IndexStatus
import com.example.zhengdao.baopu.recall.RecallIndexPort
import com.example.zhengdao.baopu.subconscious.SubconsciousRunner
import com.example.zhengdao.baopu.subconscious.suggestedAtByKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 假索引：只实现 search/digest/status（证明关联不需要第四种能力）。 */
private class FakeIndex(
    private val hits: List<IndexHit>,
    private val points: List<IndexPoint> = emptyList(),
) : RecallIndexPort {
    var searchCalls = 0
    var digestCalls = 0
    var statusCalls = 0
    override fun search(query: String, limit: Int): List<IndexHit> {
        searchCalls += 1
        return hits.take(limit)
    }
    override fun digest(sessionId: Long, max: Int): List<IndexPoint> {
        digestCalls += 1
        return points.take(max)
    }
    override fun status(): IndexStatus {
        statusCalls += 1
        return IndexStatus(hits.size.toLong(), 10L, 1_000L)
    }
}

private fun thought(msgId: Long, body: String, atMs: Long = 1_000L) = CapturedThought(
    "insight-$msgId", body,
    InsightAnchor.Message(msgId, 1L, body), atMs, "ws",
)

class InsightAssociateWakeTest {

    // ── 判据②：关联只用既有索引 ─────────────────────────────────────────────

    @Test
    fun `判据2_线索经既有索引search命中同一句话_关联成立`() {
        val t = thought(7L, "我在想也许做一个灵感捕获的功能")
        val index = FakeIndex(listOf(IndexHit(7L, 1L, "我在想也许做一个灵感捕获的功能")))
        val result = InsightAssociate.associate("灵感 捕获", listOf(t), index)
        assertEquals(1, result.size)
        assertEquals(t.id, result.single().thought.id)
        assertEquals(1, index.searchCalls)
        assertTrue(result.single().why.contains("同一句话"))
    }

    @Test
    fun `判据2_负例_只有1个词重合_不关联_防噪音`() {
        val t = thought(7L, "我在想也许做一个灵感捕获的功能")
        val index = FakeIndex(listOf(IndexHit(99L, 1L, "灵感"))) // 只重合 1 个词
        assertTrue(InsightAssociate.associate("灵感", listOf(t), index).isEmpty())
    }

    @Test
    fun `判据2_空线索不发查询_不关联`() {
        val index = FakeIndex(listOf(IndexHit(7L, 1L, "随便")))
        assertTrue(InsightAssociate.associate("  ，。！  ", listOf(thought(7L, "想做事")), index).isEmpty())
        assertEquals(0, index.searchCalls)
    }

    @Test
    fun `判据2_digest与status也走既有索引_不自建摘要`() {
        val index = FakeIndex(emptyList(), listOf(IndexPoint(3L, "要点一")))
        assertEquals(listOf("要点一"), InsightAssociate.digestClue(1L, 5, index))
        assertTrue(InsightAssociate.statusLine(index).contains("索引"))
        assertEquals(1, index.digestCalls)
        assertEquals(1, index.statusCalls)
    }

    // ── 判据③：每天≤1 + 不点不进上下文 ──────────────────────────────────────

    private fun assoc(msgId: Long, atMs: Long = 1_000L) = InsightAssociation(
        thought(msgId, "我在想也许做一个灵感捕获的功能", atMs),
        IndexHit(msgId, 1L, "我在想也许做一个灵感捕获的功能"),
        "同一句话",
    )

    @Test
    fun `判据3_当天已浮过_这轮不浮`() {
        val day = SubconsciousRunner.ACTION_WHY // 只为拿常量存在性；天闸用 dayBucket 由 runner 内部判
        assertTrue(day.isNotBlank())
        val round = runInsightWake(
            listOf(assoc(7L)), nowMs = 5_000L, grant = null, shape = SessionShape(),
            lastRunDayKey = com.example.zhengdao.baopu.inspect.PatrolScheduler.dayBucket(5_000L),
            suggestedAtByKey = emptyMap(),
        )
        assertFalse(round.ran)
        assertTrue(round.signals.isEmpty())
        assertTrue(round.note.contains("一天"))
    }

    @Test
    fun `判据3_多枚候选_一天也只浮一行`() {
        val round = runInsightWake(
            listOf(assoc(7L), assoc(8L), assoc(9L)), nowMs = 5_000L,
            grant = null, shape = SessionShape(), lastRunDayKey = null, suggestedAtByKey = emptyMap(),
        )
        assertTrue(round.ran)
        assertEquals(1, round.signals.size)
        assertEquals(1, round.actionRows.size)
    }

    @Test
    fun `判据3_不点_信箱空_增量0_逐字节一致`() {
        InsightMailbox.clear()
        val before = "system-prompt-prefix"
        val withNote = before + InsightMailbox.drain().joinToString("")
        assertEquals(before, withNote) // 不点 ⇒ drain 空 ⇒ 提示词一个字节不变
        assertEquals(emptyList<String>(), InsightMailbox.drain())
    }

    @Test
    fun `判据3_点开一次_drain一次且取走即清`() {
        InsightMailbox.clear()
        onInsightOpened(roundSignal())
        assertEquals(1, InsightMailbox.drain().size)
        assertEquals(emptyList<String>(), InsightMailbox.drain()) // 不重放
    }

    private fun roundSignal() = runInsightWake(
        listOf(assoc(7L)), nowMs = 5_000L, grant = null, shape = SessionShape(),
        lastRunDayKey = null, suggestedAtByKey = emptyMap(),
    ).signals.single()

    // ── 判据④：与 625 共用 runner + 去重表 ──────────────────────────────────

    @Test
    fun `判据4_候选id用anchor_ref_账行能被625去重表读回`() {
        val a = assoc(7L)
        val candidate = candidatesOf(listOf(a)).single()
        assertEquals("echo:msg:7", candidate.id)
        assertEquals("msg:7", candidate.ledgerId) // 625 去重键 = ledgerId
    }

    @Test
    fun `判据4_同一念头24小时内已浮_再关联网不浮`() {
        // 用 625 的真实账落一行，再用 625 的 suggestedAtByKey 读回 ⇒ 证明共用同一张表。
        val dir = File(System.getProperty("java.io.tmpdir"), "insight-echo-${System.nanoTime()}")
        dir.mkdirs()
        val round1 = runInsightWake(
            listOf(assoc(7L)), nowMs = 5_000L, grant = null, shape = SessionShape(),
            lastRunDayKey = null, suggestedAtByKey = emptyMap(),
        )
        // 把账行写到 625 认得的路径形状（why 前缀＝676 回响，why 尾＝候选 id）
        val ledger = File(dir, "回响.jsonl")
        round1.actionRows.forEach { ledger.appendText(whyLine(it.why, it.atMs, it.what, it.result) + "\n") }
        val table = suggestedAtByKey(dir)
        assertTrue("625 的去重表必须读得到 534 的念头账行", table.containsKey("msg:7"))

        val round2 = runInsightWake(
            listOf(assoc(7L)), nowMs = 5_000L + 3_600_000L, grant = null,
            shape = SessionShape(), lastRunDayKey = null, suggestedAtByKey = table,
        )
        assertTrue("同一念头冷却期内 ⇒ 不浮", round2.signals.isEmpty())
    }

    /** DiaryCodec 同形五列（kind｜at｜why｜what｜result），与 625 回响账同格式。 */
    private fun whyLine(why: String, at: Long, what: String, result: String) =
        "action\t$at\t$why\t$what\t$result"

    @Test
    fun `判据3_关掉开关_只记不唤`() {
        val f = File(System.getProperty("java.io.tmpdir"), "insight-cycle-${System.nanoTime()}.jsonl")
        val store = InsightStore(f)
        val index = FakeIndex(listOf(IndexHit(7L, 1L, "我在想也许做一个灵感捕获的功能")))
        val cycle = runInsightCycle(
            messages = listOf(TurnMessage(7L, 1L, "user", "我在想也许做一个灵感捕获的功能。", 1_000L)),
            clue = "灵感", nowMs = 5_000L, workspace = "ws", enabled = false,
            store = store, index = index, grant = null, shape = SessionShape(),
            lastRunDayKey = null, suggestedAtByKey = emptyMap(),
        )
        assertEquals(1, cycle.captured.thoughts.size) // 记保留
        assertFalse(cycle.wake.ran)                   // 唤停掉
        assertEquals(0, index.searchCalls)            // 关时连索引都不查
    }

    @Test
    fun `开关默认开_resolve纯函数`() {
        assertTrue(InsightSwitch.resolve(null))
        assertTrue(InsightSwitch.resolve(true))
        assertFalse(InsightSwitch.resolve(false))
    }

    @Test
    fun `免打扰窗口内_整轮延后不浮`() {
        val grant = QuietGrant("t1", 5_000L, 10L, QuietTrigger.NO_CARDS)
        val round = runInsightWake(
            listOf(assoc(7L)), nowMs = 5_000L, grant = grant, shape = SessionShape(),
            lastRunDayKey = null, suggestedAtByKey = emptyMap(),
        )
        assertFalse(round.ran)
        assertTrue(round.signals.isEmpty())
    }
}
