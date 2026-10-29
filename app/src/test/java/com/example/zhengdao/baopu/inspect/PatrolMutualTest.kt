// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十九批 §3／§4／§5 的判据靶：③冲突并列、⑩误报率分母、⑪额度调整
// 入账／有上限／可回滚／可复核，外加「冲突不进御案」。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatrolMutualTest {

    private val srcRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")
    private val day = 24L * 3_600_000L

    private fun side(
        who: MutualSide,
        verdict: InspectVerdict,
        evidence: List<String> = listOf("decision:1"),
        check: InspectCheck = InspectCheck.AUDIT_GAP,
    ) = MutualFinding(who, check, verdict, evidence)

    private fun round(id: String, atMs: Long, hits: Int, noData: Int = 0) = NightRound(
        id = id,
        atMs = atMs,
        bucket = 1L,
        inspected = hits + noData,
        hits = hits,
        noData = noData,
        emergency = false,
    )

    private fun change(
        atMs: Long = 10L,
        basis: String = "patrol:1",
        before: AllowanceBand = AllowanceBand.HELD,
        after: AllowanceBand = AllowanceBand.RAISED,
        samples: Int = 30,
        pct: Double = 96.0,
        actor: String = DecisionActor.AUTO.wire,
    ) = AllowanceLadder.AllowanceChange(
        id = "allow:$atMs",
        atMs = atMs,
        actor = actor,
        basisPatrolId = basis,
        passRatePct = pct,
        samples = samples,
        before = before,
        after = after,
    )

    // ── 判据③：冲突并列，一条不丢 ──────────────────────────────────────────

    @Test
    fun `判据③_冲突两条并列各带双边证据`() {
        val conflicts = PatrolMutual.conflicts(
            shadow = listOf(side(MutualSide.SHADOW, InspectVerdict.HIT, listOf("decision:1", "decision:2"))),
            guard = listOf(side(MutualSide.GUARD, InspectVerdict.CLEAR, listOf("decision:1"))),
            nowMs = 5L,
        )
        assertEquals("一条冲突必须落成两行", 2, conflicts.size)
        assertEquals(listOf(MutualSide.SHADOW, MutualSide.GUARD), conflicts.map { it.side })
        conflicts.forEach { row ->
            assertTrue("每行都要带自己的证据", row.evidence.isNotEmpty())
            assertTrue("每行都要带对侧证据 id", row.peerEvidence.isNotEmpty())
            assertTrue("277 落地前一律待回放", row.pendingReplay)
            assertEquals("decision:1", row.target)
        }
        // 双向：暗卫那一行看到的是金吾卫的证据，反之亦然
        assertTrue(conflicts[0].peerEvidence.containsAll(conflicts[1].evidence))
        assertTrue(conflicts[1].peerEvidence.containsAll(conflicts[0].evidence))
    }

    @Test
    fun `负例_认定相同或证据不共享都不算冲突`() {
        val same = PatrolMutual.conflicts(
            shadow = listOf(side(MutualSide.SHADOW, InspectVerdict.HIT)),
            guard = listOf(side(MutualSide.GUARD, InspectVerdict.HIT)),
            nowMs = 5L,
        )
        assertTrue("都 HIT 不叫冲突", same.isEmpty())
        val apart = PatrolMutual.conflicts(
            shadow = listOf(side(MutualSide.SHADOW, InspectVerdict.HIT, listOf("decision:1"))),
            guard = listOf(side(MutualSide.GUARD, InspectVerdict.CLEAR, listOf("decision:9"))),
            nowMs = 5L,
        )
        assertTrue("没有共同证据就不配对", apart.isEmpty())
        val otherCheck = PatrolMutual.conflicts(
            shadow = listOf(side(MutualSide.SHADOW, InspectVerdict.HIT)),
            guard = listOf(side(MutualSide.GUARD, InspectVerdict.CLEAR, check = InspectCheck.SHADOW_MISMATCH)),
            nowMs = 5L,
        )
        assertTrue("检查项不同不是同一件事", otherCheck.isEmpty())
    }

    @Test
    fun `负例_同一对冲突不重复入账`() {
        val conflicts = PatrolMutual.conflicts(
            shadow = listOf(
                side(MutualSide.SHADOW, InspectVerdict.HIT, listOf("decision:1")),
                side(MutualSide.SHADOW, InspectVerdict.HIT, listOf("decision:1", "decision:2")),
            ),
            guard = listOf(side(MutualSide.GUARD, InspectVerdict.CLEAR, listOf("decision:1"))),
            nowMs = 5L,
        )
        assertEquals("同一检查项＋同一条证据只落一对", 2, conflicts.size)
    }

    @Test
    fun `冲突行的账能原样读回来且带待回放标记`() {
        val row = PatrolMutual.conflicts(
            shadow = listOf(side(MutualSide.SHADOW, InspectVerdict.HIT)),
            guard = listOf(side(MutualSide.GUARD, InspectVerdict.CLEAR)),
            nowMs = 5L,
        ).first()
        val json = MutualLedger.conflictJson(row)
        assertEquals(MutualLedger.KIND_CONFLICT, json.optString("kind"))
        assertTrue(json.optBoolean("pending_replay"))
        assertEquals("decision:1", json.optString("target"))
        assertEquals(MutualSide.SHADOW.wire, json.optString("side"))
        assertEquals(MutualSide.GUARD.wire, json.optString("peer_side"))
        assertTrue(json.optJSONArray("peer_evidence")!!.length() > 0)
    }

    // ── 判据⑩：误报率分母不含 NO_DATA ──────────────────────────────────────

    @Test
    fun `判据⑩_误报率分母不含查不了的`() {
        val rounds = listOf(round("night:1", 0L, hits = 5, noData = 4))
        val reviews = listOf(DigestReview("night:1", 0L, ReviewVerdict.FALSE_POSITIVE))
        val rate = PatrolMutual.falsePositiveRate(rounds, reviews, nowMs = 1L)
        assertEquals("分母只认报出来的条数（查不了的不算）", 5, rate.reported)
        assertEquals(1, rate.falsePositives)
        assertEquals(20.0, rate.pct, 0.001)
        assertTrue(rate.known)
    }

    @Test
    fun `判据⑩_查不了的复核两边都不进`() {
        val rounds = listOf(round("night:1", 0L, hits = 4))
        val reviews = listOf(
            DigestReview("night:1", 0L, ReviewVerdict.NO_DATA),
            DigestReview("night:1", 1L, ReviewVerdict.CONFIRMED),
        )
        val rate = PatrolMutual.falsePositiveRate(rounds, reviews, nowMs = 2L)
        assertEquals(0, rate.falsePositives)
        assertEquals(4, rate.reported)
        assertEquals(0.0, rate.pct, 0.001)
    }

    @Test
    fun `误报率_窗口与空账`() {
        val rounds = listOf(round("night:old", 0L, hits = 5), round("night:new", 40 * day, hits = 10))
        val reviews = listOf(
            DigestReview("night:old", 0L, ReviewVerdict.FALSE_POSITIVE),
            DigestReview("night:new", 40 * day, ReviewVerdict.FALSE_POSITIVE),
        )
        val rate = PatrolMutual.falsePositiveRate(rounds, reviews, nowMs = 40 * day)
        assertEquals("30 天窗：老那一轮不进", 10, rate.reported)
        assertEquals(1, rate.falsePositives)
        // 一条都没报 ⇒ 算不出（不是 0%）
        val empty = PatrolMutual.falsePositiveRate(emptyList(), reviews, nowMs = 40 * day)
        assertFalse(empty.known)
        assertEquals(PassRate.UNKNOWN_PCT, empty.pct, 0.001)
    }

    // ── 判据⑪：额度调整入账／有上限／可回滚／可复核 ─────────────────────────

    @Test
    fun `判据⑪_正常调整入账且可回滚`() {
        val row = PatrolMutual.allowanceAudit(change())
        assertNull(row.refused)
        assertEquals(AllowanceBand.HELD.wire, row.before)
        assertEquals(AllowanceBand.RAISED.wire, row.after)
        val draft = PatrolMutual.rollbackOf(row)
        assertNotNull("每笔入账的调整都要能回滚", draft)
        assertEquals(DecisionKind.TOOL_GRANT, draft!!.kind)
        assertEquals(AllowanceBand.RAISED.wire, draft.before)
        assertEquals(AllowanceBand.HELD.wire, draft.after)
        assertEquals(row.id, draft.rollbackOf)
        assertTrue(draft.reversible!!)
    }

    @Test
    fun `判据⑪_同一勘验依据只许调一次`() {
        val first = PatrolMutual.allowanceAudit(change(atMs = 10L))
        val again = PatrolMutual.allowanceAudit(change(atMs = 20L), earlier = listOf(first))
        assertNotNull("一笔依据反复引用＝反复升降", again.refused)
        assertTrue(again.refused!!.contains("只许调一次"))
        assertNull("被拒的没有可回滚的东西", PatrolMutual.rollbackOf(again))
    }

    @Test
    fun `判据⑪_跨两档要人确认`() {
        val jump = PatrolMutual.allowanceAudit(
            change(before = AllowanceBand.REVOKED, after = AllowanceBand.RAISED),
        )
        assertNotNull("一次读盘就把额度从收回放到放开＝无上限", jump.refused)
        assertTrue(jump.refused!!.contains("跨两档"))
        val down = PatrolMutual.allowanceAudit(change(before = AllowanceBand.RAISED, after = AllowanceBand.HELD))
        assertNull("相邻档不许被这条误伤", down.refused)
    }

    @Test
    fun `负例_模型不许自己调额度`() {
        val row = PatrolMutual.allowanceAudit(change(actor = DecisionActor.MODEL.wire))
        assertNotNull(row.refused)
        assertTrue(row.refused!!.contains("model"))
        assertNull(PatrolMutual.rollbackOf(row))
    }

    @Test
    fun `拒了也要留痕`() {
        val row = PatrolMutual.allowanceAudit(change(actor = DecisionActor.MODEL.wire))
        val json = MutualLedger.allowanceJson(row)
        assertEquals(MutualLedger.KIND_ALLOWANCE, json.optString("kind"))
        assertTrue("拒的理由要进账", json.optString("refused").isNotEmpty())
        assertEquals("patrol:1", json.optString("basis_patrol_id"))
    }

    @Test
    fun `判据⑪_暗卫能复核一笔调整`() {
        val row = PatrolMutual.allowanceAudit(change(pct = 96.0, samples = 30))
        val rate = PassRate(96.0, 30, 29, 1, InspectLimits.PASS_RATE_WINDOW_DAYS)
        assertTrue("通过率对得上就该属实", PatrolMutual.recheck(row, rate).startsWith("属实"))
        val off = PassRate(80.0, 30, 24, 6, InspectLimits.PASS_RATE_WINDOW_DAYS)
        assertTrue("读数对不上就是不符", PatrolMutual.recheck(row, off).startsWith("不符"))
        val none = PassRate(PassRate.UNKNOWN_PCT, 0, 0, 0, InspectLimits.PASS_RATE_WINDOW_DAYS)
        assertTrue("算不出时不许说属实", PatrolMutual.recheck(row, none).contains("复核不了"))
    }

    // ── 冲突不进御案 ────────────────────────────────────────────────────────

    @Test
    fun `冲突只在报告里列不进御案`() {
        val text = File(srcRoot, "PatrolMutual.kt").readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
        listOf("OpinionWiring", "Budget(", "Opinion(", "AlertDialog", "import com.example.zhengdao.ui.")
            .forEach { word -> assertFalse("互查层出现了 $word（冲突只进报告）", text.contains(word)) }
    }

    @Test
    fun `负例_不许自建第二本延后队列`() {
        val text = File(srcRoot, "PatrolMutual.kt").readText(Charsets.UTF_8)
        assertTrue("待回放标记要在", text.contains("pendingReplay"))
        listOf("延后队列.jsonl", "deferred.jsonl", "PendingQueue").forEach { word ->
            assertFalse("第二本队列会与 277 漂移：$word", text.contains(word))
        }
    }
}
