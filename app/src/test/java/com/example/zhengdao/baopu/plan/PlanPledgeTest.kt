// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-31-第三百一十二批-计划军令状 §判据 ①–⑦ ＋ §负例（各红）。
// 本文件打的是**判定层**（`PlanPledge.kt`）；状态与两本账（军令状账＋未办成单）
// 打在 `PlanPledgeBoardTest`。
//
// 房型照 `PlanContractTest`（242）：判定层零 Android、零 IO，所以判据与负例每一条
// 都能在 JVM 里红——「负例」不是注释，是一条断言：把错的做法写回代码，这里必红。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPledgeTest {

    // ── 造数 ────────────────────────────────────────────────────────────────

    private fun items(vararg texts: String): List<PlanItem> =
        texts.map { PlanItem(it, PlanRules.STATUS_TODO) }

    private fun snap(vararg texts: String, version: Int = 1): PlanPledgeRules.PledgeSnapshot {
        val accepts = texts.map { "交付「$it」并核对" }
        return PlanPledgeRules.snapshotOf(items(*texts), accepts, version, "把事办成", 1_000L, 7L)
    }

    private fun pledgeArgs(vararg accepts: String): JSONObject = JSONObject()
        .put(PlanPledgeRules.ARG_PLEDGE, JSONObject().put("acceptance", JSONArray(accepts.toList())))

    private fun resignArgs(reason: String): JSONObject = JSONObject()
        .put(PlanPledgeRules.ARG_RESIGN, JSONObject().put("reason", reason))

    private fun declDone(text: String, evidence: String = "out:1") = PlanPledgeRules.EndingDeclaration(
        text,
        PlanPledgeRules.EndKind.DONE,
        evidence = evidence,
    )

    private fun declAbandoned(
        text: String,
        reason: PlanPledgeRules.AbandonReason,
        detail: String = "实情如此",
    ) = PlanPledgeRules.EndingDeclaration(
        text,
        PlanPledgeRules.EndKind.ABANDONED,
        abandon = reason,
        detail = detail,
    )

    /** 判据①①负例共用的闸壳：相对快照算增删后过闸。 */
    private fun guard(
        snapshot: PlanPledgeRules.PledgeSnapshot?,
        incoming: List<String>,
        resign: PlanPledgeRules.ResignParse,
        channel: PlanPledgeRules.ResignChannel? = null,
        openFeedback: Set<String> = emptySet(),
    ): PlanPledgeRules.EditGuard {
        val old = snapshot?.texts.orEmpty()
        val added = incoming.filter { it !in old.toSet() }
        val removed = old.filter { it !in incoming.toSet() }
        return PlanPledgeRules.guardFrozenEdit(snapshot, added, removed, resign, channel, openFeedback)
    }

    private fun closeoutArg(vararg rows: JSONObject): JSONObject = JSONObject()
        .put(PlanPledgeRules.ARG_CLOSEOUT, JSONObject().put("items", JSONArray(rows.toList())))

    private fun doneRow(text: String, evidence: String = "out:1") = JSONObject()
        .put("text", text).put("end", "done").put("evidence", evidence)

    private fun abandonedRow(text: String, reason: String) = JSONObject()
        .put("text", text).put("end", "abandoned").put("reason", reason)

    // ── 立状（判据④）────────────────────────────────────────────────────────

    @Test
    fun `判据4_短任务不立状`() {
        assertFalse("4 项同会话不算长程", PlanPledgeRules.isLongTask(4, false))
        assertTrue("5 项算长程", PlanPledgeRules.isLongTask(5, false))
        assertTrue("跨会话算长程（步数不看）", PlanPledgeRules.isLongTask(2, true))

        val bare = PlanPledgeRules.parsePledge(JSONObject(), 4, false)
        assertTrue("短任务不带参数 ⇒ 不问：$bare", bare is PlanPledgeRules.PledgeParse.Skip)
        val carried = PlanPledgeRules.parsePledge(pledgeArgs("一", "二", "三", "四"), 4, false)
        assertTrue("短任务带了 pledge 也只忽略，不弹卡：$carried", carried is PlanPledgeRules.PledgeParse.Skip)
        assertEquals("忽略要有交代（notice）", 1, (carried as PlanPledgeRules.PledgeParse.Skip).notices.size)
    }

    @Test
    fun `立状_长程任务缺验收方式就拒`() {
        val bare = PlanPledgeRules.parsePledge(JSONObject(), 5, false)
        assertTrue("长程不带 pledge ⇒ 拒（静默当没立状＝绕开结项门）：$bare", bare is PlanPledgeRules.PledgeParse.Error)
        val fewer = PlanPledgeRules.parsePledge(pledgeArgs("一", "二", "三", "四"), 5, false)
        assertTrue("少一条验收方式也立不起来：$fewer", fewer is PlanPledgeRules.PledgeParse.Error)
        val ok = PlanPledgeRules.parsePledge(pledgeArgs("一", "二", "三", "四", "五"), 5, false)
        assertTrue(ok is PlanPledgeRules.PledgeParse.Need)
        assertEquals(5, (ok as PlanPledgeRules.PledgeParse.Need).accepts.size)
    }

    @Test
    fun `立状_问清过就不再问`() {
        val again = PlanPledgeRules.parsePledge(JSONObject(), 5, false, pledged = true)
        assertTrue("已立状的进度更新不重复弹卡：$again", again is PlanPledgeRules.PledgeParse.Skip)
    }

    // ── 冻结后改计划（判据①＋负例1）─────────────────────────────────────────

    @Test
    fun `判据1_冻结后改计划拒`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val incoming = listOf("甲", "乙改", "丙", "丁", "戊")

        val noResign = guard(s, incoming, PlanPledgeRules.ResignParse.Absent)
        assertTrue("没带重签 ⇒ 拒：$noResign", noResign is PlanPledgeRules.EditGuard.Reject)

        val unauthorized = guard(
            s, incoming,
            PlanPledgeRules.ResignParse.Ok("乙这件事改名了"),
            channel = null,
        )
        assertTrue("理由写了但没获批 ⇒ 同样不生效：$unauthorized", unauthorized is PlanPledgeRules.EditGuard.Reject)

        val progress = guard(
            s, listOf("甲", "乙", "丙", "丁", "戊"),
            PlanPledgeRules.ResignParse.Absent,
        )
        assertEquals("只打勾/换顺序不拦（结项门另管）", PlanPledgeRules.EditGuard.Pass, progress)

        assertEquals("没立状 ⇒ 本层沉默（今天的路径逐字节不变）", PlanPledgeRules.EditGuard.Pass, guard(null, incoming, PlanPledgeRules.ResignParse.Absent))
    }

    @Test
    fun `负例1_悄悄删项后宣布完成`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        // 第一步：把没做的「乙」从计划里删掉 ⇒ 拒（不许就地改计划）
        val withoutB = listOf("甲", "丙", "丁", "戊")
        val guard = guard(s, withoutB, PlanPledgeRules.ResignParse.Absent)
        assertTrue("删项必须走重签：$guard", guard is PlanPledgeRules.EditGuard.Reject)
        // 第二步：即便表换成 4 项，快照还是 5 项——收口只认快照，「乙」仍是没有结局的那项
        val others = listOf("甲", "丙", "丁", "戊").map { declDone(it) }
        val verdict = PlanPledgeRules.settle(s, others)
        assertFalse("删项删不掉快照里的项，任务判未完成", verdict.complete)
        assertEquals(listOf("乙"), verdict.gaps.map { it.text })
    }

    // ── 结项与收口（判据②⑤＋负例2）─────────────────────────────────────────

    @Test
    fun `判据2_漏一项结局就判未完成`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val four = listOf("甲", "乙", "丙", "丁").map { declDone(it) }
        val verdict = PlanPledgeRules.settle(s, four)
        assertFalse("漏了「戊」，任务不能算完成", verdict.complete)
        assertEquals(listOf("戊"), verdict.gaps.map { it.text })
        val all = four + declDone("戊", evidence = "账行:208/abc")
        assertTrue("补齐最后一条（带证据）⇒ 完成", PlanPledgeRules.settle(s, all).complete)
    }

    @Test
    fun `负例2_DONE缺证据不算结局`() {
        val s = snap("甲", "乙")
        val noEvidence = listOf(
            declDone("甲", evidence = "   "),
            declDone("乙", evidence = "out:2"),
        )
        val verdict = PlanPledgeRules.settle(s, noEvidence)
        assertFalse("说做完但拿不出证据，就是还没做完", verdict.complete)
        assertEquals("缺证据", verdict.gaps.single().missing)
    }

    @Test
    fun `判据5_ABANDONED空原因拒`() {
        val empty = PlanPledgeRules.parseCloseout(closeoutArg(abandonedRow("甲", "  ")))
        assertTrue("空原因的弃单不收：$empty", empty is PlanPledgeRules.CloseoutParse.Error)
        val unknown = PlanPledgeRules.parseCloseout(closeoutArg(abandonedRow("甲", "懒得做")))
        assertTrue("三值之外不收：$unknown", unknown is PlanPledgeRules.CloseoutParse.Error)
        val ok = PlanPledgeRules.parseCloseout(closeoutArg(abandonedRow("甲", "BLOCKED")))
        assertTrue("三值大小写不限：$ok", ok is PlanPledgeRules.CloseoutParse.Ok)
        val absent = PlanPledgeRules.parseCloseout(JSONObject())
        assertTrue("没申报不是错误（收口按没有结局记）", absent is PlanPledgeRules.CloseoutParse.Absent)
    }

    @Test
    fun `结项_abandoned带原因也算结局`() {
        val s = snap("甲", "乙")
        val verdict = PlanPledgeRules.settle(
            s,
            listOf(
                declDone("甲"),
                declAbandoned("乙", PlanPledgeRules.AbandonReason.NEEDS_HUMAN),
            ),
        )
        assertTrue("两项都有结局 ⇒ 完成（弃单是诚实结局，不是失败）", verdict.complete)
    }

    @Test
    fun `结项_打了勾不构成结局`() {
        val s = snap("甲", "乙")
        val verdict = PlanPledgeRules.settle(s, emptyList(), doneTexts = setOf("甲"))
        assertFalse("打勾只是进度，不是结项申报", verdict.complete)
        assertEquals("打了勾但没申报结项", verdict.gaps.first { it.text == "甲" }.missing)
        assertEquals("没有结局", verdict.gaps.first { it.text == "乙" }.missing)
    }

    // ── 右相回灌（追加①②＋判据⑥）──────────────────────────────────────────

    @Test
    fun `判据6_同一项第3次未过转交人`() {
        val s = snap("甲", "乙")
        val first = PlanPledgeRules.settle(s, emptyList())
        assertEquals(1, first.gaps[0].failures)
        assertFalse("第 1 次还在回灌", first.gaps[0].escalated)

        val second = PlanPledgeRules.settle(s, emptyList(), failures = mapOf("甲" to 1, "乙" to 1))
        assertFalse("第 2 次仍在回灌（同一项最多回灌 2 次）", second.gaps[0].escalated)

        val third = PlanPledgeRules.settle(s, emptyList(), failures = mapOf("甲" to 2, "乙" to 2))
        assertTrue("第 3 次未过 ⇒ 交人，不再回灌", third.gaps.all { it.escalated })
        val feedback = PlanPledgeRules.gapFeedback(third)
        assertNotNull(feedback)
        assertTrue("交人的行要出现在清单里", feedback!!.contains("交人"))
        assertFalse("交人之后不再报最小满足（不教做法，等人收场）", feedback.contains("最小满足"))
    }

    @Test
    fun `回灌_差距清单三件齐`() {
        val s = snap("甲", "乙", "丙")
        val partial = PlanPledgeRules.settle(s, listOf(declDone("甲")))
        val feedback = PlanPledgeRules.gapFeedback(partial)
        assertNotNull(feedback)
        assertTrue("哪项：${feedback!!}", feedback.contains("「乙」"))
        assertTrue("缺什么", feedback.contains("缺"))
        assertTrue("最小满足条件", feedback.contains("最小满足"))
        assertNull("完结了就不回灌，不多说一个字", PlanPledgeRules.gapFeedback(PlanPledgeRules.settle(s, listOf(declDone("甲"), declAbandoned("乙", PlanPledgeRules.AbandonReason.BLOCKED), declDone("丙")))))
    }

    // ── 追加判据⑦：价值判断词的门 ───────────────────────────────────────────

    @Test
    fun `判据7_回灌文案不带价值判断词`() {
        val verdict = PlanPledgeRules.settle(snap("甲", "乙"), emptyList())
        val feedback = PlanPledgeRules.gapFeedback(verdict)!!
        assertFalse("回灌只报事实与标准：$feedback", PlanPledgeRules.hasValueJudgment(feedback))
        // 尺子要认得出脏东西（负例自检：尺子失灵＝门禁是摆设）
        listOf("建议你", "应该做", "最好再", "更好些", "不妨试", "值得一做").forEach {
            assertTrue("尺子必须认得出：$it", PlanPledgeRules.hasValueJudgment(it))
        }
    }

    @Test
    fun `判据7_源级门禁_本包源码不出现价值判断词`() {
        val dir = File("src/main/java/com/example/zhengdao/baopu/plan")
        val files = listOf("PlanPledge.kt", "PlanPledgeCards.kt", "PlanPledgeBoard.kt")
            .associateWith { stripComments(File(dir, it).readText()) }
        assertTrue("被扫的源文件得在", files.values.all { it.isNotBlank() })
        files.forEach { (name, code) ->
            PlanPledgeRules.VALUE_JUDGMENT_WORDS.forEach { word ->
                assertFalse(
                    "$name 的代码里出现了价值判断词「$word」（回灌文案的门禁，尺子先量自己）",
                    code.contains(word),
                )
            }
        }
    }

    /** 与 ScoutReadOnlyGateTest 同法：剥掉注释再扫（字符串一个不留）。 */
    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .replace(Regex("(?m)//.*$"), " ")

    // ── 重签（负例3＋追加③）─────────────────────────────────────────────────

    @Test
    fun `负例3_重签不写原因`() {
        val parse = PlanPledgeRules.parseResign(resignArgs("   "))
        assertTrue("空白理由＝没写：$parse", parse is PlanPledgeRules.ResignParse.Error)
        val absent = PlanPledgeRules.parseResign(JSONObject())
        assertTrue(absent is PlanPledgeRules.ResignParse.Absent)
        val guard = guard(
            snap("甲", "乙", "丙", "丁", "戊"),
            listOf("甲", "乙", "丙", "丁", "戊", "己"),
            parse,
            channel = PlanPledgeRules.ResignChannel.HUMAN,
        )
        assertTrue("理由空 ⇒ 即便人批了也拒（显式性的下半张脸）：$guard", guard is PlanPledgeRules.EditGuard.Reject)
    }

    @Test
    fun `追加3_回灌期自决单不许删缺口项`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val open = setOf("乙")

        val self = guard(
            s, listOf("甲", "丙", "丁", "戊"),
            PlanPledgeRules.ResignParse.Ok("乙做不完"),
            channel = PlanPledgeRules.ResignChannel.SELF,
            openFeedback = open,
        )
        assertTrue("自决单删缺口项＝把缺口变完成 ⇒ 拒：$self", self is PlanPledgeRules.EditGuard.Reject)

        val human = guard(
            s, listOf("甲", "丙", "丁", "戊"),
            PlanPledgeRules.ResignParse.Ok("乙做不完，我同意取消"),
            channel = PlanPledgeRules.ResignChannel.HUMAN,
            openFeedback = open,
        )
        assertEquals("人批的删项是人的权利（卡面上删了什么她看得见）", PlanPledgeRules.EditGuard.Pass, human)

        val addOnly = guard(
            s, listOf("甲", "乙", "丙", "丁", "戊", "己"),
            PlanPledgeRules.ResignParse.Ok("发现还要补一件事"),
            channel = PlanPledgeRules.ResignChannel.SELF,
            openFeedback = open,
        )
        assertEquals("加项不缩目标，自决单可过", PlanPledgeRules.EditGuard.Pass, addOnly)
    }

    // ── 账行（判据③的机器侧）────────────────────────────────────────────────

    @Test
    fun `账行_快照编码读回逐字一致`() {
        val s = snap("甲", "乙", version = 2)
        val back = PlanPledgeRules.decodeSnapshot(
            PlanPledgeRules.encodeSnapshot(s, PlanPledgeRules.EVENT_FREEZE),
        )
        assertEquals("冻结行 round-trip", s, back)
    }

    @Test
    fun `账行_重签行带删项加项原因通道与计划变更标记`() {
        val s = snap("甲", "乙", version = 2)
        val line = PlanPledgeRules.encodeSnapshot(
            s,
            PlanPledgeRules.EVENT_RESIGN,
            added = listOf("己"),
            removed = listOf("甲"),
            why = "发现还要补一件事",
            channel = PlanPledgeRules.ResignChannel.SELF,
        )
        assertEquals("计划变更", line.optString("change"))
        assertEquals("发现还要补一件事", line.optString("why"))
        assertEquals("self", line.optString("channel"))
        assertEquals(listOf("己"), line.optJSONArray("added")!!.let { a -> (0 until a.length()).map { a.optString(it) } })
        assertEquals(listOf("甲"), line.optJSONArray("removed")!!.let { a -> (0 until a.length()).map { a.optString(it) } })
        assertEquals("重签行也读得回快照（旧版本可查的另一半）", s, PlanPledgeRules.decodeSnapshot(line))
    }

    @Test
    fun `账行_收口行留结局与证据`() {
        val s = snap("甲", "乙")
        val verdict = PlanPledgeRules.settle(s, listOf(declDone("甲", evidence = "file:a.md"), declAbandoned("乙", PlanPledgeRules.AbandonReason.BLOCKED)))
        assertTrue("两项都有结局 ⇒ 完成", verdict.complete)
        val line = PlanPledgeRules.encodeSettle(verdict, s, 5_000L)
        assertTrue(line.optBoolean("complete"))
        val items = line.optJSONArray("items")!!
        assertEquals("done", items.optJSONObject(0)!!.optString("end"))
        assertEquals("file:a.md", items.optJSONObject(0)!!.optString("evidence"))
        assertEquals("abandoned", items.optJSONObject(1)!!.optString("end"))
        assertEquals("blocked", items.optJSONObject(1)!!.optString("reason"))
    }

    // ── 未办成单的行（负例⑤的机器侧）────────────────────────────────────────

    @Test
    fun `未办成单_弃单与缺口都成行_DONE不成行`() {
        val s = snap("甲", "乙", "丙")
        val verdict = PlanPledgeRules.settle(
            s,
            listOf(
                declDone("甲"),
                declAbandoned("乙", PlanPledgeRules.AbandonReason.UNSUPPORTED),
            ),
        )
        val rows = PlanPledgeRules.unfinishedRows(verdict, 5_000L)
        assertEquals("弃单＋缺口各一行；done 的不成行", 2, rows.size)
        val abandoned = rows.first { it.intent == "乙" }
        assertEquals(UnfinishedOutcome.NOT_DONE, abandoned.outcome)
        assertEquals("点得回原状", "pledge:${s.id}", abandoned.reasonRef)
        assertFalse("UNSUPPORTED 做不了，不给「再试一次就行」的假入口", abandoned.fixable)
        val gap = rows.first { it.intent == "丙" }
        assertTrue("缺口能补", gap.fixable)
        assertTrue("能补就要说怎么补（入口，不是做法）", gap.fixHint.isNotBlank())
    }
}
