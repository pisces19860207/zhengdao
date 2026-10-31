// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-31-第三百一十二批-计划军令状 §判据 ②③④ ＋ §负例⑤
// （ABANDONED 不落未办成单）＋ §做什么 1（写账）。
//
// 打的是**状态与账**（`PlanPledgeBoard.kt`）：军令状账（只追加，旧版本原样在账上）
// 与未办成单都用真临时目录落盘——判据③「两个版本都在账上」量的是盘上的账，
// 不是内存里的列表。判据层那批（①⑤⑥⑦与负例1/2/3）在 `PlanPledgeTest`。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
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

class PlanPledgeBoardTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        PlanPledgeBoard.resetForTest()
        root = Files.createTempDirectory("pledge-board").toFile()
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

    private fun declDone(text: String, evidence: String = "out:1") = PlanPledgeRules.EndingDeclaration(
        text,
        PlanPledgeRules.EndKind.DONE,
        evidence = evidence,
    )

    private fun declAbandoned(text: String, reason: PlanPledgeRules.AbandonReason) =
        PlanPledgeRules.EndingDeclaration(
            text,
            PlanPledgeRules.EndKind.ABANDONED,
            abandon = reason,
            detail = "实情如此",
        )

    private fun resignArgs(reason: String): JSONObject = JSONObject()
        .put(PlanPledgeRules.ARG_RESIGN, JSONObject().put("reason", reason))

    private fun ledgerRows(): List<JSONObject> {
        val f = PlanPledgeBoard.ledgerFile() ?: return emptyList()
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    private fun events(): List<String> = ledgerRows().map { it.optString("event") }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

    // ── 立状写账 ────────────────────────────────────────────────────────────

    @Test
    fun `立状_写账_读回一致`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        PlanPledgeBoard.freeze(s)
        assertEquals("冻结一行", listOf(PlanPledgeRules.EVENT_FREEZE), events())
        assertEquals(
            "账行解码即快照（写后读回逐字一致）",
            s,
            PlanPledgeRules.decodeSnapshot(ledgerRows().first()),
        )
        assertEquals(s, PlanPledgeBoard.activeSnapshot())
        assertEquals(listOf(s), PlanPledgeBoard.history())
    }

    @Test
    fun `人拒绝立状_同版不再问_军令状不生效`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        PlanPledgeBoard.noteDeclined(s)
        assertTrue(PlanPledgeBoard.isDeclined(s))
        assertFalse("别的候选照问（一次问清，不是一次就不问）", PlanPledgeBoard.isDeclined(snap("甲", "乙")))
        assertNull("没立状 ⇒ 没有现役快照", PlanPledgeBoard.activeSnapshot())
        assertTrue("账上不落行——没点头的状不入账", ledgerRows().isEmpty())
    }

    // ── 重签（判据③）────────────────────────────────────────────────────────

    @Test
    fun `判据3_重签后旧快照仍可查`() {
        val first = snap("甲", "乙", "丙", "丁", "戊")
        PlanPledgeBoard.freeze(first)
        val incoming = items("甲", "乙改", "丙", "丁", "戊", "己")
        val accepts = incoming.map { "交付「$it」并核对" }
        val next = PlanPledgeBoard.applyResign(
            incoming,
            accepts,
            "乙改名，另发现要补一件事",
            PlanPledgeRules.ResignChannel.HUMAN,
            atMs = 2_000L,
        )
        assertEquals("新版次＋1", 2, next!!.version)
        assertEquals(listOf(PlanPledgeRules.EVENT_FREEZE, PlanPledgeRules.EVENT_RESIGN), events())
        val rows = ledgerRows()
        val oldRow = PlanPledgeRules.decodeSnapshot(rows[0])!!
        assertEquals("旧快照原样在账上（第 1 行）", first, oldRow)
        assertEquals("旧版快照的项一个不少", listOf("甲", "乙", "丙", "丁", "戊"), oldRow.texts)
        val newRow = PlanPledgeRules.decodeSnapshot(rows[1])!!
        assertEquals("新版本在账上（第 2 行）", next, newRow)
        assertEquals("显式标记「计划变更」", "计划变更", rows[1].optString("change"))
        assertEquals("原因在账上", "乙改名，另发现要补一件事", rows[1].optString("why"))
        assertEquals("通道在账上", "human", rows[1].optString("channel"))
        assertEquals("删哪项", listOf("乙"), rows[1].optJSONArray("removed")!!.strings())
        assertEquals("加哪项", listOf("乙改", "己"), rows[1].optJSONArray("added")!!.strings())
        assertEquals(2, PlanPledgeBoard.history().size)
        assertEquals(next, PlanPledgeBoard.activeSnapshot())
    }

    // ── 收口（判据②＋负例⑤）────────────────────────────────────────────────

    @Test
    fun `判据2_收口漏项判未完成_缺口进未办成单`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲", evidence = "file:a.md")))
        val verdict = PlanPledgeBoard.settle(doneTexts = setOf("甲"), atMs = 3_000L)!!
        assertFalse("两项没结局 ⇒ 任务判未完成", verdict.complete)
        assertEquals(listOf("乙", "丙"), verdict.gaps.map { it.text })

        val unfinished = UnfinishedLedger(listOf(root)).load()
        assertEquals("缺口逐行进未办成单", setOf("乙", "丙"), unfinished.map { it.intent }.toSet())
        assertEquals("结项缺口过得了账行取证（settle 行在军令状账上）", false, ledgerRows().last { it.optString("event") == PlanPledgeRules.EVENT_SETTLE }.optBoolean("complete"))
    }

    @Test
    fun `负例5_ABANDONED也落未办成单`() {
        PlanPledgeBoard.freeze(snap("甲", "乙"))
        PlanPledgeBoard.noteCloseout(
            listOf(
                declDone("甲", evidence = "file:a.md"),
                declAbandoned("乙", PlanPledgeRules.AbandonReason.BLOCKED),
            ),
        )
        val verdict = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)!!
        assertTrue("两项都有结局 ⇒ 完成（弃单是诚实结局）", verdict.complete)

        val unfinished = UnfinishedLedger(listOf(root)).load()
        val row = unfinished.firstOrNull { it.intent == "乙" }
        assertNotNull("弃单也要落单（负例⑤：不能只留在军令状账里）", row)
        assertEquals(UnfinishedOutcome.NOT_DONE, row!!.outcome)
        assertTrue("点得回原状", row.reasonRef.startsWith("pledge:"))
        assertTrue("被阻塞的事解除阻塞后还能续（能补 ≠ 要补）", row.fixable)
        assertFalse("done 的不成行", unfinished.any { it.intent == "甲" })
        assertEquals(
            "证据随收口行落账（file:a.md 就是「文件」那一种证据）",
            "file:a.md",
            ledgerRows().last { it.optString("event") == PlanPledgeRules.EVENT_SETTLE }
                .optJSONArray("items")!!.optJSONObject(0)!!.optString("evidence"),
        )    }

    @Test
    fun `收口_一轮只结一次_新申报重开`() {
        PlanPledgeBoard.freeze(snap("甲", "乙"))
        val first = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)!!
        val again = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)
        assertEquals("同一版重复收口 ⇒ 返回上次读数", first, again)
        assertEquals("账上只落一行 settle", 1, events().count { it == PlanPledgeRules.EVENT_SETTLE })

        PlanPledgeBoard.noteCloseout(
            listOf(
                declDone("甲", evidence = "file:a.md"),
                declAbandoned("乙", PlanPledgeRules.AbandonReason.BLOCKED),
            ),
        )
        val reopened = PlanPledgeBoard.settle(doneTexts = setOf("甲"), atMs = 4_000L)
        assertTrue("新申报 ⇒ 重新收口（两项都有结局了）", reopened!!.complete)
        assertEquals(2, events().count { it == PlanPledgeRules.EVENT_SETTLE })
    }

    @Test
    fun `判据4_没立状就什么门都没有`() {
        assertNull("没立状 ⇒ 收口不编读数", PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L))
        assertEquals(
            "没立状 ⇒ 改动闸沉默（今天的路径逐字节不变）",
            PlanPledgeRules.EditGuard.Pass,
            PlanPledgeBoard.guardEdit(items("甲", "乙"), JSONObject(), channel = null),
        )
        assertTrue(PlanPledgeBoard.history().isEmpty())
        assertFalse(File(root, PlanPledgeBoard.LEDGER_FILE).exists())
    }

    // ── 回灌期的闸（追加③，经板走一遍）─────────────────────────────────────

    @Test
    fun `追加3_经板_回灌期自决单删缺口项被拒`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙", "丁", "戊"))
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲")))
        val verdict = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)!!
        assertEquals(1, verdict.gaps.first { it.text == "乙" }.failures)
        assertTrue("乙在回灌期里", PlanPledgeBoard.openFeedback().contains("乙"))

        val self = PlanPledgeBoard.guardEdit(
            items("甲", "丙", "丁", "戊"),
            resignArgs("乙做不完"),
            channel = PlanPledgeRules.ResignChannel.SELF,
        )
        assertTrue("自决单无权删：$self", self is PlanPledgeRules.EditGuard.Reject)

        val human = PlanPledgeBoard.guardEdit(
            items("甲", "丙", "丁", "戊"),
            resignArgs("乙做不完，我同意取消"),
            channel = PlanPledgeRules.ResignChannel.HUMAN,
        )
        assertEquals("人批可过", PlanPledgeRules.EditGuard.Pass, human)
    }

    // ── 跨会话（seed）───────────────────────────────────────────────────────

    @Test
    fun `seed_跨会话接回_轮数跟人走`() {
        val s = snap("甲", "乙", "丙", "丁", "戊", version = 3)
        PlanPledgeBoard.seed(s, priorFailures = mapOf("甲" to 2, "乙" to 2))
        assertEquals(s, PlanPledgeBoard.activeSnapshot())
        assertEquals(listOf(s), PlanPledgeBoard.history())
        val verdict = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)!!
        assertEquals("上一场已经回灌 2 次 ⇒ 这次就是第 3 次", 3, verdict.gaps.first { it.text == "甲" }.failures)
        assertTrue("第 3 次未过 ⇒ 交人", verdict.gaps.first { it.text == "甲" }.escalated)
        assertEquals(
            "接回不重写冻结行（一版两行会让「两个版本」数出第三个）",
            listOf(PlanPledgeRules.EVENT_SETTLE),
            events(),
        )
    }

    @Test
    fun `clear_新请求不带旧状`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙", "丁", "戊"))
        PlanPledgeBoard.clear()
        assertNull(PlanPledgeBoard.activeSnapshot())
        assertTrue(PlanPledgeBoard.history().isEmpty())
        assertNull("上次的收口读数也一并清", PlanPledgeBoard.lastVerdict())
    }

    @Test
    fun `重签_新版从头结_幸存项轮数保留`() {
        PlanPledgeBoard.freeze(snap("甲", "乙", "丙"))
        PlanPledgeBoard.noteCloseout(listOf(declDone("甲", evidence = "out:1")))
        PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 3_000L)
        val next = PlanPledgeBoard.applyResign(
            items("甲", "乙", "丙", "丁"),
            listOf("交付「甲」并核对", "交付「乙」并核对", "交付「丙」并核对", "交付「丁」并核对"),
            "发现还要补一件事",
            PlanPledgeRules.ResignChannel.SELF,
            atMs = 4_000L,
        )!!
        assertEquals(2, next.version)
        val verdict = PlanPledgeBoard.settle(doneTexts = emptySet(), atMs = 5_000L)!!
        assertEquals("旧申报不带到新版本（第 2 版从头结）", listOf("甲", "乙", "丙", "丁"), verdict.gaps.map { it.text })
        assertEquals(
            "乙上一场没过 1 次，幸存下来轮数跟着走",
            2,
            verdict.gaps.first { it.text == "乙" }.failures,
        )
    }
}
