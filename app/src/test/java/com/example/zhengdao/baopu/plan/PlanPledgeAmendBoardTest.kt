// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-01-第三百四十三批-军令状范围变更 §判据 ③④⑤ ＋ §三（与账本：
// 变更落一行「变更账」，与业务账分表）＋ §二 规矩①③⑤。
//
// 打的是**状态与两本账**（`PlanPledgeBoard.kt`）：军令状账（状态：哪一版约了什么、
// 收口什么读数）、变更账（谁提的、谁批的、改了什么、超没超预算）、未办成单（业务账）
// 都用真临时目录落盘——判据⑤「旧版本可读」量的是盘上的账，不是内存里的列表。
// 判定层与卡面那批（①②③ 与五负例）在 `PlanPledgeAmendTest`。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendDecision
import com.example.zhengdao.baopu.plan.PlanPledgeRules.AbandonReason
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndKind
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndingDeclaration
import com.example.zhengdao.baopu.plan.PlanPledgeRules.ResignChannel
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlanPledgeAmendBoardTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        PlanPledgeBoard.resetForTest()
        root = Files.createTempDirectory("pledge-amend").toFile()
        PlanPledgeBoard.attach(listOf(root))
    }

    @After
    fun tearDown() {
        PlanPledgeBoard.resetForTest()
        root.deleteRecursively()
    }

    // ── 造数 ────────────────────────────────────────────────────────────────

    private fun items(vararg texts: String): List<PlanItem> =
        texts.map { PlanItem(it, PlanRules.STATUS_TODO) }

    private fun snap(vararg texts: String, version: Int = 1): PlanPledgeRules.PledgeSnapshot {
        val accepts = texts.map { "交付「$it」并核对" }
        return PlanPledgeRules.snapshotOf(items(*texts), accepts, version, "把事办成", 1_000L, 7L)
    }

    private fun opJson(kind: String, text: String, vararg kv: Pair<String, String>): JSONObject =
        JSONObject().put("kind", kind).put("text", text).also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

    private fun amendArgs(
        ops: List<JSONObject>,
        by: String = "user",
        reason: String = "路上想清楚了",
    ): JSONObject = JSONObject()
        .put(
            PlanPledgeAmendRules.ARG_AMEND,
            JSONObject().put("by", by).put("reason", reason).put("ops", JSONArray(ops)),
        )

    private fun amend(
        ops: List<JSONObject>,
        channel: ResignChannel?,
        incoming: List<PlanItem>? = null,
        atMs: Long = 2_000L,
        reason: String = "路上想清楚了",
    ): AmendDecision = PlanPledgeBoard.amend(incoming, amendArgs(ops, reason = reason), channel, atMs)

    private fun add(text: String, accept: String = ""): JSONObject =
        if (accept.isEmpty()) opJson("add", text) else opJson("add", text, "accept" to accept)

    private fun revise(text: String, to: String = "", accept: String = ""): JSONObject =
        when {
            to.isEmpty() && accept.isEmpty() -> opJson("revise", text)
            to.isEmpty() -> opJson("revise", text, "accept" to accept)
            accept.isEmpty() -> opJson("revise", text, "to" to to)
            else -> opJson("revise", text, "to" to to, "accept" to accept)
        }

    private fun drop(text: String, reason: String = ""): JSONObject =
        if (reason.isEmpty()) opJson("drop", text) else opJson("drop", text, "reason" to reason)

    private fun declDone(text: String, evidence: String = "out:1") = EndingDeclaration(
        text,
        EndKind.DONE,
        evidence = evidence,
    )

    private fun declAbandoned(text: String, reason: AbandonReason = AbandonReason.BLOCKED) =
        EndingDeclaration(text, EndKind.ABANDONED, abandon = reason, detail = "实情如此")

    private fun applied(d: AmendDecision): AmendDecision.Apply {
        assertTrue("预期落地，实际 $d", d is AmendDecision.Apply)
        return d as AmendDecision.Apply
    }

    private fun rows(f: File?): List<JSONObject> =
        if (f == null || !f.isFile) emptyList() else f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    private fun stateRows(): List<JSONObject> = rows(PlanPledgeBoard.ledgerFile())

    private fun changeRows(): List<JSONObject> = rows(PlanPledgeBoard.changeLedgerFile())

    private fun events(): List<String> = stateRows().map { it.optString("event") }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

    // ── 追加落地（判据①⑤＋§三「谁批的」）──────────────────────────────────

    @Test
    fun `追加_人批后落板_版本加1_两本账各一行`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙", "丁", "戊"))
        val ask = amend(listOf(add("己")), channel = null)
        assertTrue("没批准 ⇒ 只出卡：$ask", ask is AmendDecision.NeedUser)
        assertEquals("板上一个字节不改", 1, PlanPledgeBoard.activeSnapshot()!!.version)
        assertEquals(1, PlanPledgeBoard.cardsHandedOut())
        assertTrue("出卡时变更账不落行", changeRows().isEmpty())

        val ok = applied(amend(listOf(add("己")), channel = ResignChannel.HUMAN))
        assertEquals(2, ok.outcome.snapshot.version)
        assertEquals(listOf("甲", "乙", "丙", "丁", "戊", "己"), PlanPledgeBoard.activeSnapshot()!!.texts)
        assertEquals("旧版还在内存里可查", 2, PlanPledgeBoard.history().size)
        assertEquals("状态账：冻结一行＋重签一行", listOf("freeze", "resign"), events())

        val c = changeRows().single()
        assertEquals("变更账一行，事件是 amend", "amend", c.optString("event"))
        assertEquals(1, c.optInt("from"))
        assertEquals(2, c.optInt("version"))
        assertEquals("人批", "human", c.optString("channel"))
        assertTrue(c.optBoolean("asked"))
        assertEquals("追加的那一项在 ops 里", "己", c.optJSONArray("ops")!!.optJSONObject(0)!!.optString("text"))
        assertNull("落板之后没有挂着的卡", PlanPledgeBoard.pendingAmend())
    }

    @Test
    fun `判据4_同一份申报只递一张卡_换了申报才第二张`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        amend(listOf(add("丁")), channel = null)
        amend(listOf(add("丁")), channel = null)
        assertEquals("同一件事问两遍就是打扰：只递一张", 1, PlanPledgeBoard.cardsHandedOut())
        assertNotNull(PlanPledgeBoard.pendingAmend())
        amend(listOf(add("戊")), channel = null)
        assertEquals("换了申报才递第二张", 2, PlanPledgeBoard.cardsHandedOut())
    }

    @Test
    fun `预算内的自决单加项_不出卡也不计打扰`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙", "丁", "戊"))
        val ok = applied(amend(listOf(add("己")), channel = ResignChannel.SELF))
        assertEquals(2, ok.outcome.snapshot.version)
        assertEquals("自决不弹卡", 0, PlanPledgeBoard.cardsHandedOut())
        assertEquals("自决不算打扰预算", 0, PlanPledgeBoard.asksUsed())
        val c = changeRows().single()
        assertEquals("self", c.optString("channel"))
        assertFalse("asked 只在人批时为真", c.optBoolean("asked"))
    }

    @Test
    fun `超预算的自决单被拒_板不动`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙", "丁", "戊"))
        val d = amend(listOf(add("己"), add("庚"), add("辛")), channel = ResignChannel.SELF)
        assertTrue("$d", d is AmendDecision.Reject)
        assertTrue((d as AmendDecision.Reject).message.contains("不许自己认下来"))
        assertEquals("板保持第 1 版", 1, PlanPledgeBoard.activeSnapshot()!!.version)
        assertEquals("账上只有冻结那一行", listOf("freeze"), events())
        assertTrue(changeRows().isEmpty())

        val guard = PlanPledgeBoard.guardEdit(
            items("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛"),
            amendArgs(listOf(add("己"), add("庚"), add("辛"))),
            channel = ResignChannel.SELF,
        )
        assertTrue("312 的重签老路也要过预算闸：$guard", guard is PlanPledgeRules.EditGuard.Reject)
        assertTrue((guard as PlanPledgeRules.EditGuard.Reject).message.contains("时间预算"))
    }

    // ── 判据③／负例①：悄悄摘项 ⇒ 门禁红（在板上走一遍）────────────────────

    @Test
    fun `负例1_旧账里静默摘掉的项_收口照样报红并进未办成单`() {
        // 343 之前的账：第 1 版五项、第 2 版三项（312 的重签摘了「丁」「戊」，
        // 那本账里没有 DROPPED 这回事）——跨会话接回来时，这笔旧账照样要还
        PlanPledgeBoard.seed(
            snap("甲", "乙", "丙", version = 2),
            priorVersions = listOf(snap("甲", "乙", "丙", "丁", "戊", version = 1)),
        )
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲"), declDone("乙"), declDone("丙")))
        val verdict = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)!!
        assertFalse("摘掉的项照样欠着", verdict.complete)
        val shrink = verdict.gaps.first { it.text == "丁" }
        assertEquals("缺的是 DROPPED 那一张凭据", PlanPledgeAmendRules.MISSING_SHRINK, shrink.missing)
        assertEquals(listOf("丁", "戊"), verdict.gaps.map { it.text })
        assertTrue(
            "缺口逐行进未办成单",
            UnfinishedLedger(listOf(root)).load().any { it.intent == "丁" },
        )

        // 补记账（amend drop 旧项，人批）⇒ 有了 DROPPED ⇒ 结清；表不动
        applied(amend(listOf(drop("丁", "这件事不做了"), drop("戊", "这件事也不做了")), channel = ResignChannel.HUMAN))
        val after = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 4_000L)!!
        assertTrue("补了凭据 ⇒ 收口通过：${after.gaps}", after.complete)
        assertEquals("现役还是三项（补记账不改表）", 3, PlanPledgeBoard.activeSnapshot()!!.entries.size)
        assertEquals("DROPPED 进变更账", "dropped", changeRows().single().optJSONArray("dropped")!!.optJSONObject(0)!!.optString("state"))
    }

    // ── 负例②（机器侧）：DROPPED 与 ABANDONED 分两本账 ─────────────────────

    @Test
    fun `负例2_DROPPED进变更账_ABANDONED进业务账_互不冒充`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        PlanPledgeBoard.noteCloseout(listOf(declAbandoned("乙", AbandonReason.BLOCKED)))
        applied(amend(listOf(drop("丙", "这件事不做了")), channel = ResignChannel.HUMAN))
        val verdict = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)!!

        val unfinished = UnfinishedLedger(listOf(root)).load()
        assertTrue("做不完的乙进未办成单", unfinished.any { it.intent == "乙" })
        assertFalse("不要了的丙不进未办成单（负例②：不能把它当 abandoned）", unfinished.any { it.intent == "丙" })

        val dropped = PlanPledgeBoard.droppedRecords().single()
        assertEquals("丙", dropped.text)
        assertEquals("不做了的原因在记录上", "这件事不做了", dropped.reason)
        assertTrue(
            "dropped 与 abandoned 是两套词（没有 one 装作另一个）",
            AbandonReason.entries.none { it.wire == dropped.wire },
        )
        assertFalse("丙不在收口缺口里（它已有下落）", verdict.gaps.any { it.text == "丙" })
        val settleRow = stateRows().last { it.optString("event") == PlanPledgeRules.EVENT_SETTLE }
        val settledTexts = settleRow.optJSONArray("items")!!.let { a ->
            (0 until a.length()).map { a.optJSONObject(it)!!.optString("text") }
        }
        assertFalse("状态账的收口行里没有丙", settledTexts.contains("丙"))
    }

    // ── 规矩⑤／负例④：已完成项不因变更作废 ────────────────────────────────

    @Test
    fun `负例4_变更不作废已完成项`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙", "丁", "戊"))
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲", "file:a.md")))
        PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 2_000L)
        applied(amend(listOf(add("己")), channel = ResignChannel.HUMAN, atMs = 3_000L))
        val after = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 4_000L)!!
        assertFalse("甲不该因为加了别的项又被讨一次", after.gaps.any { it.text == "甲" })
        assertEquals(listOf("乙", "丙", "丁", "戊", "己"), after.gaps.map { it.text })
        assertTrue("甲的 done 还在收口行上", after.ended.any { it.text == "甲" && it.ending is PlanPledgeRules.Ending.Done })
    }

    @Test
    fun `判据2_修改项回待做_板上也作数`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲", "file:a.md")))
        PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 2_000L)
        applied(amend(listOf(revise("甲", accept = "改成核对三处")), channel = ResignChannel.HUMAN, atMs = 3_000L))
        val after = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 4_000L)!!
        assertTrue("判据变了，旧结论不算：甲回到缺口", after.gaps.any { it.text == "甲" })
        assertEquals("变更账记下哪项回待做", listOf("甲"), changeRows().single().optJSONArray("reset")!!.strings())
    }

    // ── 判据⑤：旧版本可读＋跨会话接回 ──────────────────────────────────────

    @Test
    fun `判据5_两版都在账上_跨会话接得回`() {
        val v1 = snap("甲", "乙", "丙")
        PlanPledgeBoard.freeze(v1)
        applied(amend(listOf(revise("乙", to = "乙改", accept = "新验收"), drop("丙", "不做了")), channel = ResignChannel.HUMAN))
        val rows = stateRows()
        assertEquals("状态账两行：冻结＋重签", 2, rows.size)
        assertEquals("旧快照原样在第 1 行（判据⑤）", v1, PlanPledgeRules.decodeSnapshot(rows[0])!!)

        val versions = rows.mapNotNull { PlanPledgeRules.decodeSnapshot(it) }
        val changes = changeRows()
        PlanPledgeBoard.clear()
        PlanPledgeBoard.seed(versions.last(), priorVersions = versions, priorChanges = changes)

        assertEquals("接回后两版都查得到", 2, PlanPledgeBoard.history().size)
        assertEquals(mapOf("乙" to "乙改"), PlanPledgeBoard.supersededFrom())
        assertEquals(listOf("丙"), PlanPledgeBoard.droppedRecords().map { it.text })
        assertEquals("打扰预算跟人走", 1, PlanPledgeBoard.asksUsed())
        assertEquals("接回不重写冻结行", 2, stateRows().size)
    }

    // ── 没立状：两本账都不写（今天的路径逐字节不变）─────────────────────────

    @Test
    fun `没立状_amend沉默_两本账都不写`() {
        val d = PlanPledgeBoard.amend(items("甲", "乙"), amendArgs(listOf(add("丙"))), null, 2_000L)
        assertTrue("没立状 ⇒ 本层不回话：$d", d is AmendDecision.Ignored)
        assertEquals(
            "改动闸沉默（照 140 的确认卡走）",
            PlanPledgeRules.EditGuard.Pass,
            PlanPledgeBoard.guardEdit(items("甲", "乙", "丙"), amendArgs(listOf(add("丙"))), channel = null),
        )
        assertFalse(PlanPledgeBoard.ledgerFile()!!.exists())
        assertFalse(PlanPledgeBoard.changeLedgerFile()!!.exists())
        assertTrue(PlanPledgeBoard.history().isEmpty())
    }

    @Test
    fun `判据1_写法不合格的申报被拒且不落板`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        val bad = PlanPledgeBoard.amend(null, amendArgs(listOf(add("丁")), reason = "  "), null, 2_000L)
        assertTrue("缺原因 ⇒ 拒：$bad", bad is AmendDecision.Reject)
        assertEquals("板不动", 1, PlanPledgeBoard.activeSnapshot()!!.version)
        assertEquals("账上只有冻结那一行", listOf("freeze"), events())
        assertTrue("变更账不落行", changeRows().isEmpty())
        assertEquals("拒了不递卡", 0, PlanPledgeBoard.cardsHandedOut())
    }

    // ── 判据⑥：三本账分表 ──────────────────────────────────────────────────

    // ── 判据③的另一半：312 的重签老路也不能静默缩表 ──────────────────────────

    @Test
    fun `判据3_重签老路删项也记DROPPED_收口不再报静默缩表`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        val next = PlanPledgeBoard.applyResign(
            items("甲", "乙"),
            listOf("交付「甲」并核对", "交付「乙」并核对"),
            "丙不要了",
            ResignChannel.HUMAN,
            atMs = 2_000L,
        )!!
        assertEquals(2, next.version)
        assertEquals("删掉的项有 DROPPED 凭据", listOf("丙"), PlanPledgeBoard.droppedRecords().map { it.text })
        assertEquals("变更账补了这一行（不是只在内存）", 1, changeRows().size)
        val row = changeRows().single()
        assertEquals(PlanPledgeAmendRules.EVENT_AMEND, row.optString("event"))
        assertEquals("human", row.optString("channel"))
        assertEquals(
            "丙",
            row.optJSONArray("dropped")!!.optJSONObject(0)!!.optString("text"),
        )
        assertEquals(
            "跨会话接得回同一份凭据",
            listOf("丙"),
            PlanPledgeAmendRules.seedFrom(changeRows()).dropped.map { it.text },
        )

        PlanPledgeBoard.noteCloseout(listOf(declDone("甲"), declDone("乙")))
        val verdict = PlanPledgeBoard.settle(doneTexts = setOf("甲", "乙"), atMs = 3_000L)!!
        assertTrue("有凭据就不算静默缩表：${verdict.gaps.map { it.text }}", verdict.complete)
        assertEquals(2, verdict.total)
    }

    @Test
    fun `判据6_三本账三个文件_各记各的`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲"), declAbandoned("乙")))
        applied(amend(listOf(drop("丙", "不做了")), channel = ResignChannel.HUMAN))
        PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)

        val state = PlanPledgeBoard.ledgerFile()!!
        val change = PlanPledgeBoard.changeLedgerFile()!!
        val business = UnfinishedLedger(listOf(root)).file()
        assertTrue(state.isFile)
        assertTrue(change.isFile)
        assertTrue(business.isFile)
        assertEquals(
            "三个不同文件（判据⑥：一本只管一件事）",
            3,
            setOf(state.name, change.name, business.name).size,
        )
        assertFalse("状态账里没有变更行", events().any { it == PlanPledgeAmendRules.EVENT_AMEND })
        assertTrue("变更账里只有变更行", changeRows().all { it.optString("event") == PlanPledgeAmendRules.EVENT_AMEND })
        assertTrue("状态账记过冻结与收口", events().containsAll(listOf("freeze", "settle")))
        assertEquals("DROPPED 只在变更账上", 1, PlanPledgeBoard.droppedRecords().size)
    }
}
