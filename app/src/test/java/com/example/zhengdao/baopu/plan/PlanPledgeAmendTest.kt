// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-01-第三百四十三批-军令状范围变更 §判据 ①②③④⑤ ＋ §负例（各红）。
//
// 本文件打的是**判定层**（`PlanPledgeAmend.kt`）与**卡面**（`PlanPledgeAmendCards.kt`）——
// 两者都是零 Android、零 IO、零时钟的纯函数。状态与两本账（军令状账＋变更账）打在
// `PlanPledgeAmendBoardTest`；312 的判据一条不动，仍在 `PlanPledgeTest`。
//
// 房型照 `PlanPledgeTest`（312）与 `PlanContractTest`（242）：「负例」不是注释，是一条
// 断言——把错的做法写回代码，这里必红。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendBy
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendContext
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendDecision
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendKind
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendOp
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendParse
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.DropRecord
import com.example.zhengdao.baopu.plan.PlanPledgeRules.AbandonReason
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndKind
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndingDeclaration
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot
import com.example.zhengdao.baopu.plan.PlanPledgeRules.ResignChannel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPledgeAmendTest {

    // ── 造数 ────────────────────────────────────────────────────────────────

    private fun items(vararg texts: String): List<PlanItem> =
        texts.map { PlanItem(it, PlanRules.STATUS_TODO) }

    private fun snap(vararg texts: String, version: Int = 1): PledgeSnapshot {
        val accepts = texts.map { "交付「$it」并核对" }
        return PlanPledgeRules.snapshotOf(items(*texts), accepts, version, "把事办成", 1_000L, 7L)
    }

    private fun add(text: String, accept: String = "") = AmendOp(AmendKind.ADD, text, accept = accept)

    private fun revise(text: String, to: String = "", accept: String = "", reason: String = "") =
        AmendOp(AmendKind.REVISE, text, to = to, accept = accept, reason = reason)

    private fun drop(text: String, reason: String = "") = AmendOp(AmendKind.DROP, text, reason = reason)

    private fun declDone(text: String, evidence: String = "out:1") = EndingDeclaration(
        text,
        EndKind.DONE,
        evidence = evidence,
    )

    private fun declAbandoned(text: String, reason: AbandonReason = AbandonReason.BLOCKED) =
        EndingDeclaration(text, EndKind.ABANDONED, abandon = reason, detail = "实情如此")

    private fun judge(
        s: PledgeSnapshot,
        ops: List<AmendOp>,
        channel: ResignChannel? = null,
        incoming: List<PlanItem>? = null,
        endings: Map<String, EndingDeclaration> = emptyMap(),
        dropped: Set<String> = emptySet(),
        superseded: Set<String> = emptySet(),
        owed: Set<String> = emptySet(),
        asksUsed: Int = 0,
        by: AmendBy = AmendBy.USER,
        reason: String = "路上想清楚了",
        atMs: Long = 2_000L,
    ): AmendDecision = PlanPledgeAmendRules.decide(
        AmendContext(s, s.entries.size, asksUsed, endings, dropped, superseded, owed),
        ops, by, reason, incoming, channel, atMs,
    )

    private fun applied(d: AmendDecision): AmendDecision.Apply {
        assertTrue("预期落地，实际 $d", d is AmendDecision.Apply)
        return d as AmendDecision.Apply
    }

    private fun needUser(d: AmendDecision): AmendDecision.NeedUser {
        assertTrue("预期要她点头，实际 $d", d is AmendDecision.NeedUser)
        return d as AmendDecision.NeedUser
    }

    private fun rejected(d: AmendDecision): String {
        assertTrue("预期被拒，实际 $d", d is AmendDecision.Reject)
        return (d as AmendDecision.Reject).message
    }

    private fun opJson(kind: String, text: String, vararg kv: Pair<String, String>): JSONObject =
        JSONObject().put("kind", kind).put("text", text).also { o -> kv.forEach { (k, v) -> o.put(k, v) } }

    private fun amendArgs(
        ops: List<JSONObject> = listOf(opJson("add", "己")),
        by: String = "user",
        reason: String = "路上想清楚了",
    ): JSONObject = JSONObject()
        .put(
            PlanPledgeAmendRules.ARG_AMEND,
            JSONObject().put("by", by).put("reason", reason).put("ops", JSONArray(ops)),
        )

    // ── 判据①：三条正路，各走各的门 ─────────────────────────────────────────

    @Test
    fun `判据1_追加要确认_新项进表_版本加1`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val ask = needUser(judge(s, listOf(add("己"))))
        assertTrue("问人要说得清是追加：${ask.why}", ask.why.contains("追加"))
        assertTrue("预算内的追加不挂超限：${ask.change.overBudget}", ask.change.overBudget.isEmpty())

        val ok = applied(judge(s, listOf(add("己")), channel = ResignChannel.HUMAN))
        val next = ok.outcome.snapshot
        assertEquals("版本 +1", 2, next.version)
        assertEquals("新项排末尾、老项顺序不动", listOf("甲", "乙", "丙", "丁", "戊", "己"), next.texts)
        assertEquals("没写验收方式时以条目原文兜底（不猜）", "己", next.entries.last().accept)
        assertEquals("账上记谁提的", AmendBy.USER, ok.change.by)
        assertEquals("这一轮只动了追加", listOf(AmendKind.ADD), ok.change.kinds)
        assertFalse("纯追加不必重签（改判据/删项才要）", ok.change.needsResign)
        assertTrue("未变项的结局不动", ok.outcome.state.keepEndings.isEmpty())
        assertTrue("没删项 ⇒ 没有 DROPPED", ok.outcome.state.dropped.isEmpty())
    }

    @Test
    fun `判据1_修改判据必须重签_旧判据留档`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val ask = needUser(judge(s, listOf(revise("乙", to = "乙改", accept = "乙改要交付两版"))))
        assertTrue("判据变了＝重新立约：${ask.why}", ask.change.needsResign)

        val self = judge(s, listOf(revise("乙", accept = "验收改了")), channel = ResignChannel.SELF)
        assertTrue("自决单无权改标准：$self", self is AmendDecision.Reject)

        val ok = applied(
            judge(s, listOf(revise("乙", to = "乙改", accept = "乙改要交付两版")), channel = ResignChannel.HUMAN),
        )
        assertEquals(listOf("甲", "乙改", "丙", "丁", "戊"), ok.outcome.snapshot.texts)
        assertEquals("原位换文字，不重排", 1, ok.outcome.snapshot.texts.indexOf("乙改"))
        assertEquals("未变项的判据一个字不动", s.entries[0].accept, ok.outcome.snapshot.entries[0].accept)

        val row = PlanPledgeAmendRules.encodeChange(ok.change, ok.outcome, s, ResignChannel.HUMAN, 2_000L)
        val op = row.optJSONArray("ops")!!.optJSONObject(0)!!
        assertEquals("旧判据留在变更账上（规矩①）", "交付「乙」并核对", op.optString("prev_accept"))
        assertEquals("新判据也在这行", "乙改要交付两版", op.optString("accept"))
    }

    @Test
    fun `判据1_删除记DROPPED_与ABANDONED分列`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val self = judge(s, listOf(drop("丙", "这件事不做了")), channel = ResignChannel.SELF)
        assertTrue("删项是她的权利，自决单无权：$self", self is AmendDecision.Reject)

        val ok = applied(judge(s, listOf(drop("丙", "这件事不做了")), channel = ResignChannel.HUMAN))
        assertEquals("删掉的那项离开新版", listOf("甲", "乙", "丁", "戊"), ok.outcome.snapshot.texts)
        val d = ok.outcome.state.dropped.single()
        assertEquals("记原文", "丙", d.text)
        assertEquals("原因进记录", "这件事不做了", d.reason)
        assertEquals("从第几版起不再有它", 2, d.version)
        assertEquals("谁提的、谁批的都在记录上", AmendBy.USER, d.by)
        assertEquals(ResignChannel.HUMAN, d.channel)
        assertEquals("状态词是 dropped", PlanPledgeAmendRules.STATE_DROPPED, d.wire)
        assertTrue(
            "负例②的根：dropped 不在 abandoned 的三值里（两套词不许混用）",
            AbandonReason.entries.none { it.wire == d.wire },
        )
        assertTrue("DROPPED 不进结局表（它不是 abandoned）", ok.outcome.state.keepEndings.keys.none { it == "丙" })
    }

    @Test
    fun `判据1_删项不写原由时用本轮理由兜底`() {
        val s = snap("甲", "乙")
        val ok = applied(judge(s, listOf(drop("乙")), channel = ResignChannel.HUMAN, reason = "她说不做了"))
        assertEquals("单条没写就用整轮的理由", "她说不做了", ok.outcome.state.dropped.single().reason)
    }

    @Test
    fun `判据1_非法组合被拒`() {
        // 解析层：写法门是闭集
        assertTrue(
            "没带 amend ⇒ Absent（不是错误）",
            PlanPledgeAmendRules.parseAmend(JSONObject()) is AmendParse.Absent,
        )
        assertTrue(
            "amend 不是对象 ⇒ Error",
            PlanPledgeAmendRules.parseAmend(JSONObject().put(PlanPledgeAmendRules.ARG_AMEND, "改一下")) is AmendParse.Error,
        )
        assertTrue(
            "缺原因 ⇒ Error（变更账要回答当初为什么改）",
            PlanPledgeAmendRules.parseAmend(amendArgs(reason = "   ")) is AmendParse.Error,
        )
        assertTrue(
            "ops 为空 ⇒ Error",
            PlanPledgeAmendRules.parseAmend(amendArgs(ops = emptyList())) is AmendParse.Error,
        )
        assertTrue(
            "kind 不在闭集 ⇒ Error",
            PlanPledgeAmendRules.parseAmend(amendArgs(ops = listOf(opJson("append", "己")))) is AmendParse.Error,
        )
        assertTrue(
            "revise 不说改成什么 ⇒ Error",
            PlanPledgeAmendRules.parseAmend(amendArgs(ops = listOf(opJson("revise", "甲")))) is AmendParse.Error,
        )
        assertTrue(
            "text 空白 ⇒ Error",
            PlanPledgeAmendRules.parseAmend(amendArgs(ops = listOf(opJson("add", "  ")))) is AmendParse.Error,
        )
        val byDirty = PlanPledgeAmendRules.parseAmend(amendArgs(by = "robot"))
        assertTrue(byDirty is AmendParse.Ok)
        assertEquals("不认识的 by ⇒ 按模型自己提的记", AmendBy.MODEL, (byDirty as AmendParse.Ok).by)
        assertEquals("要留一句交代", 1, byDirty.notices.size)
        val mixed = PlanPledgeAmendRules.parseAmend(
            amendArgs(ops = listOf(opJson("add", "己"), opJson("drop", "丙", "reason" to "不做了"))),
        )
        assertEquals("混了两种 ⇒ 整轮按最严走", 1, (mixed as AmendParse.Ok).notices.size)

        // 判定层：ops 对不上现役那一版
        val s = snap("甲", "乙", "丙")
        assertTrue("加一项已在表上的", judge(s, listOf(add("甲"))) is AmendDecision.Reject)
        assertTrue("改一项不在表上的", judge(s, listOf(revise("丁", to = "丁改"))) is AmendDecision.Reject)
        assertTrue("删一项不在表上的", judge(s, listOf(drop("丁")), channel = ResignChannel.HUMAN) is AmendDecision.Reject)
        assertTrue(
            "删一项已经 DROPPED 过的",
            judge(s, listOf(drop("乙")), channel = ResignChannel.HUMAN, dropped = setOf("乙")) is AmendDecision.Reject,
        )
        assertTrue(
            "同一项又改又删",
            judge(s, listOf(revise("乙", to = "乙改"), drop("乙")), channel = ResignChannel.HUMAN) is AmendDecision.Reject,
        )
        assertTrue(
            "同一条动两次（重复申报）",
            judge(s, listOf(add("己"), add("己")), channel = ResignChannel.HUMAN) is AmendDecision.Reject,
        )
        assertTrue(
            "改完之后两项同名（结项按原文对账会串）",
            judge(s, listOf(revise("甲", to = "丙")), channel = ResignChannel.HUMAN) is AmendDecision.Reject,
        )
    }

    // ── 判据②：被改项回待做、未变项保留 ────────────────────────────────────

    @Test
    fun `判据2_修改项回待做_未变项保留结局`() {
        val s = snap("甲", "乙")
        val endings = mapOf("甲" to declDone("甲"), "乙" to declAbandoned("乙"))
        val ok = applied(
            judge(s, listOf(revise("乙", to = "乙改")), channel = ResignChannel.HUMAN, endings = endings),
        )
        assertEquals("被改的那项重置为待做", listOf("乙改"), ok.outcome.state.resetToTodo)
        assertEquals("未变项的结局照旧", setOf("甲"), ok.outcome.state.keepEndings.keys)

        val verdict = PlanPledgeRules.settle(ok.outcome.snapshot, ok.outcome.state.keepEndings.values.toList())
        assertFalse("负例③：旧结论不许带到新判据上", verdict.complete)
        assertEquals(listOf("乙改"), verdict.gaps.map { it.text })
    }

    @Test
    fun `判据2_只改验收_同名项也回待做`() {
        val s = snap("甲", "乙")
        val ok = applied(
            judge(s, listOf(revise("甲", accept = "改成核对三处")), channel = ResignChannel.HUMAN, endings = mapOf("甲" to declDone("甲"))),
        )
        assertEquals("文字没变、判据变了，同样回待做", listOf("甲"), ok.outcome.state.resetToTodo)
        assertTrue("旧结论被清掉", ok.outcome.state.keepEndings.isEmpty())
        assertEquals("验收换成新的", "改成核对三处", ok.outcome.snapshot.entries[0].accept)
        assertEquals("文字还是那一项", "甲", ok.outcome.snapshot.entries[0].text)
    }

    // ── 判据③：删项没有 DROPPED ⇒ 门禁红（负例①）───────────────────────────

    @Test
    fun `负例1_悄悄删项没有DROPPED_收口报红`() {
        val v1 = snap("甲", "乙", "丙")
        // 312 的 applyResign 路径：把没做的「乙」摘掉，不留任何 DROPPED 记录
        val v2 = snap("甲", "丙", version = 2)
        val gaps = PlanPledgeAmendRules.shrinkGaps(listOf(v1, v2), v2, emptyMap(), emptySet(), emptyList())
        assertEquals("摘掉的项照样欠着", listOf("乙"), gaps.map { it.text })
        assertEquals("缺什么说得准", PlanPledgeAmendRules.MISSING_SHRINK, gaps.single().missing)

        val base = PlanPledgeRules.settle(v2, listOf(declDone("甲"), declDone("丙")))
        val verdict = base.copy(gaps = base.gaps + gaps)
        assertFalse("静默缩表 ⇒ 任务判未完成", verdict.complete)

        val rec = DropRecord("乙", "这件事不做了", 2, AmendBy.USER, ResignChannel.HUMAN, 2_000L)
        assertTrue(
            "记了 DROPPED 就结清（她说了不要了）",
            PlanPledgeAmendRules.shrinkGaps(listOf(v1, v2), v2, mapOf("乙" to rec), emptySet(), emptyList()).isEmpty(),
        )
        assertTrue(
            "被 revise 顶替（改名）也算有下落",
            PlanPledgeAmendRules.shrinkGaps(
                listOf(v1),
                snap("甲", "乙改", "丙", version = 2),
                emptyMap(),
                setOf("乙"),
                emptyList(),
            ).isEmpty(),
        )
    }

    @Test
    fun `判据3_补记DROPPED能结清旧账`() {
        val v1 = snap("甲", "乙", "丙")
        val v2 = snap("甲", "丙", version = 2) // 312 的老路摘的：没有 DROPPED ⇒ 收口欠着
        assertEquals(
            listOf("乙"),
            PlanPledgeAmendRules.shrinkGaps(listOf(v1, v2), v2, emptyMap(), emptySet(), emptyList()).map { it.text },
        )

        val ok = applied(
            judge(v2, listOf(drop("乙", "当时说好不做了")), channel = ResignChannel.HUMAN, owed = setOf("乙")),
        )
        assertEquals("补记账不改表", listOf("甲", "丙"), ok.outcome.snapshot.texts)
        assertEquals("凭据记在第 3 版上", 3, ok.outcome.snapshot.version)
        assertEquals("DROPPED 补上了", listOf("乙"), ok.outcome.state.dropped.map { it.text })
        assertTrue(
            "旧账结清",
            PlanPledgeAmendRules.shrinkGaps(
                listOf(v1, v2, ok.outcome.snapshot),
                ok.outcome.snapshot,
                mapOf("乙" to ok.outcome.state.dropped.single()),
                emptySet(),
                emptyList(),
            ).isEmpty(),
        )
        assertTrue(
            "没约过的事删不了（不在现役、也不在历版）",
            judge(v2, listOf(drop("丁")), channel = ResignChannel.HUMAN, owed = setOf("乙")) is AmendDecision.Reject,
        )
    }

    @Test
    fun `负例1_口头说改一处_表里偷偷删另一处`() {
        val s = snap("甲", "乙", "丙")
        val shrunk = rejected(judge(s, listOf(revise("甲", to = "甲改")), incoming = items("甲改", "丙")))
        assertTrue("要指名是静默缩表：$shrunk", shrunk.contains("静默缩表"))
        val snuck = rejected(judge(s, listOf(revise("甲", to = "甲改")), incoming = items("甲改", "乙", "丙", "丁")))
        assertTrue("顺手多塞一条也不行：$snuck", snuck.contains("不许顺手加"))
    }

    @Test
    fun `判据3_摘掉的项再挂回去就结清`() {
        val v1 = snap("甲", "乙", "丙")
        val v2 = snap("甲", "丙", version = 2)
        val back = snap("甲", "乙", "丙", version = 3)
        assertTrue(
            "现役里又有它 ⇒ 不欠",
            PlanPledgeAmendRules.shrinkGaps(listOf(v1, v2, back), back, emptyMap(), emptySet(), emptyList()).isEmpty(),
        )
        assertEquals(
            "历版并集只数一次",
            listOf("乙"),
            PlanPledgeAmendRules.owedTexts(listOf(v1, v1, v2), v2, emptySet(), emptySet()),
        )
    }

    // ── 判据④＋负例⑤：预算 ─────────────────────────────────────────────────

    @Test
    fun `判据4_追加超预算必须问用户`() {
        val s = snap("甲", "乙", "丙", "丁", "戊") // 立状 5 项 ⇒ 批准 7 项
        val ask = needUser(judge(s, listOf(add("己"), add("庚"), add("辛"))))
        assertEquals("超的是时间预算：${ask.change.overBudget}", 1, ask.change.overBudget.size)
        assertTrue(ask.change.overBudget.single().contains("时间预算"))
        assertTrue("问人的理由要把超预算挂上：${ask.why}", ask.why.contains("超预算"))
    }

    @Test
    fun `负例5_超预算的追加不许自己认`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val msg = rejected(judge(s, listOf(add("己"), add("庚"), add("辛")), channel = ResignChannel.SELF))
        assertTrue("超预算的字样要有：$msg", msg.contains("不许自己认下来"))
        assertTrue("要指明走她的确认卡：$msg", msg.contains("计划变更确认卡"))
        val inside = applied(judge(s, listOf(add("己")), channel = ResignChannel.SELF))
        assertEquals("预算内的追加自决可过", 2, inside.outcome.snapshot.version)
    }

    @Test
    fun `判据4_打扰预算用满也不再自决`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val ask = needUser(judge(s, listOf(add("己")), asksUsed = 3))
        assertTrue("打扰轴也进超预算读数：${ask.change.overBudget}", ask.change.overBudget.any { it.contains("打扰预算") })
        assertTrue(
            "用满之后再动计划只能当面问",
            judge(s, listOf(add("己")), asksUsed = 3, channel = ResignChannel.SELF) is AmendDecision.Reject,
        )
    }

    @Test
    fun `判据4_封顶十二项_立状时就顶格再加一项也超`() {
        assertEquals("预算不超过计划卡本身的封顶", PlanRules.MAX_ITEMS, PlanPledgeAmendRules.budgetOf(12, 0).maxItems)
        assertTrue(PlanPledgeAmendRules.budgetOf(12, 0).overByAdd(1, 13).isNotEmpty())
        assertEquals("余量两项：5 项立状 ⇒ 最多 7 项", 7, PlanPledgeAmendRules.budgetOf(5, 0).maxItems)
    }

    @Test
    fun `追加3_自决单在312重签路上也要过预算闸`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        assertEquals(
            "预算内（共 6 项 ≤ 批准 7 项）放行",
            PlanPledgeRules.EditGuard.Pass,
            PlanPledgeAmendRules.guardSelfAddBudget(s, 5, 0, 1, ResignChannel.SELF),
        )
        val over = PlanPledgeAmendRules.guardSelfAddBudget(s, 5, 0, 3, ResignChannel.SELF)
        assertTrue("超预算的加项不放过：$over", over is PlanPledgeRules.EditGuard.Reject)
        assertTrue("拒的话要把预算说清", (over as PlanPledgeRules.EditGuard.Reject).message.contains("时间预算"))
        assertEquals(
            "人批的加项本闸不问（她已经点头了）",
            PlanPledgeRules.EditGuard.Pass,
            PlanPledgeAmendRules.guardSelfAddBudget(s, 5, 0, 3, ResignChannel.HUMAN),
        )
        assertEquals(
            "没批准不是本闸的事（312 的闸先拦）",
            PlanPledgeRules.EditGuard.Pass,
            PlanPledgeAmendRules.guardSelfAddBudget(s, 5, 0, 3, null),
        )
    }

    // ── 规矩⑤／负例④：已完成项不因变更作废 ────────────────────────────────

    @Test
    fun `负例4_已完成项不因变更作废`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val endings = mapOf("甲" to declDone("甲", "file:a.md"), "乙" to declAbandoned("乙"))
        val ok = applied(
            judge(
                s, listOf(add("己"), drop("丙", "不做了")),
                channel = ResignChannel.HUMAN, endings = endings,
            ),
        )
        assertEquals("没动过的两项结局原样保留", setOf("甲", "乙"), ok.outcome.state.keepEndings.keys)
        assertEquals("已完成的那项还是 done", EndKind.DONE, ok.outcome.state.keepEndings["甲"]!!.end)
        val verdict = PlanPledgeRules.settle(ok.outcome.snapshot, ok.outcome.state.keepEndings.values.toList())
        assertEquals("已完成的项不在缺口里", listOf("丁", "戊", "己"), verdict.gaps.map { it.text })
    }

    // ── 判据⑤：版本化（旧版原样可读）──────────────────────────────────────

    @Test
    fun `判据5_变更只增版_旧版对象一个字节不动`() {
        val s = snap("甲", "乙", "丙", version = 1)
        val ok = applied(judge(s, listOf(add("丁"), drop("乙", "不做了")), channel = ResignChannel.HUMAN))
        assertEquals("旧版还是第 1 版、三项", 1, s.version)
        assertEquals(listOf("甲", "乙", "丙"), s.texts)
        assertEquals("新版是第 2 版", 2, ok.outcome.snapshot.version)
        assertNotEquals("版本化按内容＋版次哈希：两版 id 不同", s.id, ok.outcome.snapshot.id)
    }

    // ── 变更账的行（判据③的机器侧＋§三「分表」的读回）─────────────────────

    @Test
    fun `账行_变更编码读回_谁批的改了什么预算多少`() {
        val s = snap("甲", "乙", "丙")
        val ok = applied(
            judge(
                s, listOf(revise("甲", to = "甲改", accept = "新验收"), drop("丙", "不做了")),
                channel = ResignChannel.HUMAN, by = AmendBy.MODEL, reason = "路上想清楚了",
            ),
        )
        val row = PlanPledgeAmendRules.encodeChange(ok.change, ok.outcome, s, ResignChannel.HUMAN, 2_000L)
        assertEquals(PlanPledgeAmendRules.EVENT_AMEND, row.optString("event"))
        assertEquals("变更前那一版", 1, row.optInt("from"))
        assertEquals("变更后那一版", 2, row.optInt("version"))
        assertEquals("谁提的", "model", row.optString("by"))
        assertEquals("谁批的", "human", row.optString("channel"))
        assertTrue("人批过的算一次打扰", row.optBoolean("asked"))
        assertEquals("为什么改", "路上想清楚了", row.optString("reason"))
        assertEquals(listOf("revise", "drop"), row.optJSONArray("kinds")!!.strings())
        assertEquals("返回待做的项在账上", listOf("甲改"), row.optJSONArray("reset")!!.strings())
        val d = row.optJSONArray("dropped")!!.optJSONObject(0)!!
        assertEquals("删项记 dropped，不是 abandoned", "dropped", d.optString("state"))
        assertEquals("从第几版起没有它", 2, d.optInt("version"))
        assertEquals("原因是她的理由", "不做了", d.optString("reason"))
        assertEquals("预算读数在账上", 3, row.optJSONObject("budget")!!.optInt("items_at_freeze"))
        assertEquals("未变项的结局数在账上", 0, row.optInt("kept"))
    }

    @Test
    fun `账行_种子读回_跨会话接得上`() {
        val s = snap("甲", "乙", "丙")
        val ok = applied(judge(s, listOf(revise("甲", to = "甲改"), drop("丙", "不做了")), channel = ResignChannel.HUMAN))
        val row = PlanPledgeAmendRules.encodeChange(ok.change, ok.outcome, s, ResignChannel.HUMAN, 2_000L)
        val seed = PlanPledgeAmendRules.seedFrom(listOf(row, JSONObject().put("event", "freeze")))
        assertEquals("DROPPED 接得回来", listOf("丙"), seed.dropped.map { it.text })
        assertEquals("顶替关系接得回来", mapOf("甲" to "甲改"), seed.superseded)
        assertEquals("已问次数接得回来", 1, seed.asksUsed)
        assertTrue("坏行不崩", PlanPledgeAmendRules.seedFrom(listOf(JSONObject())).dropped.isEmpty())
    }

    // ── 卡面（固定三行＋fail-closed）────────────────────────────────────────

    @Test
    fun `卡面_固定三行且只认那一个键`() {
        val s = snap("甲", "乙", "丙")
        val ask = needUser(judge(s, listOf(add("丁"), revise("乙", to = "乙改", accept = "新验收"))))
        val lines = PlanPledgeAmendCards.cardLines(ask.change)
        assertEquals("卡面固定三行（派工单 §三「≤3 行」）", PlanPledgeAmendCards.MAX_CARD_LINES, lines.size)
        assertTrue("第一行：加了什么——${lines[0]}", lines[0].startsWith("加：「丁」"))
        assertTrue("第二行：改了什么——${lines[1]}", lines[1].contains("改这一项 「乙改」") && lines[1].contains("新验收"))
        assertTrue("第三行：要不要重签——${lines[2]}", lines[2].startsWith("要——判据变了等于重新立约"))

        val onlyAdd = needUser(judge(s, listOf(add("丁"))))
        assertEquals("没改没删就写「无」，不留空行", "改：无", PlanPledgeAmendCards.cardLines(onlyAdd.change)[1])

        val req = PlanPledgeAmendCards.amendRequest(ask.change)
        assertTrue("题头要写谁提的、为什么", req.question.startsWith("军令状第 1 版要变更（用户提出：路上想清楚了）"))
        assertEquals(2, req.options.size)
        assertTrue("变更卡不替她做主：两个键一个推荐也不标", req.options.none { it.recommended })
        assertTrue(PlanPledgeAmendCards.approved(PlanPledgeAmendCards.OPT_APPLY))
        assertFalse("沉默＝不改（军令状要她签名）", PlanPledgeAmendCards.approved(null))
        assertFalse(PlanPledgeAmendCards.approved(PlanPledgeAmendCards.OPT_KEEP))
        assertFalse("不认识的 id 不算批", PlanPledgeAmendCards.approved("plan-pledge-amend-yes"))
        assertFalse("卡面不带价值判断词（尺子先量自己）", PlanPledgeRules.hasValueJudgment(req.question))
    }

    @Test
    fun `卡面_超预算挂在第三行`() {
        val s = snap("甲", "乙", "丙", "丁", "戊")
        val ask = needUser(judge(s, listOf(add("己"), add("庚"), add("辛"))))
        val last = PlanPledgeAmendCards.cardLines(ask.change).last()
        assertTrue("超预算要让她一眼看见：$last", last.contains("时间预算"))
    }

    @Test
    fun `卡面_三种回灌各说各的话`() {
        val s = snap("甲", "乙", "丙")
        val ok = applied(judge(s, listOf(revise("甲", to = "甲改"), drop("丙", "不做了")), channel = ResignChannel.HUMAN))
        val ack = PlanPledgeAmendCards.appliedAck(ok.change, ok.outcome)
        assertTrue("说清新版号", ack.contains("第 2 版已生效"))
        assertTrue("说清哪项回待做", ack.contains("「甲改」"))
        assertTrue("说清 DROPPED 不是 abandoned", ack.contains("不是 abandoned（做不完）"))
        assertFalse("回灌不带价值判断词", PlanPledgeRules.hasValueJudgment(ack))

        assertTrue("她点了不改 ⇒ 现役那一版一个字不动", PlanPledgeAmendCards.keptAck(s).contains("第 1 版"))
        assertTrue("没立状 ⇒ 照 140 的卡走", PlanPledgeAmendCards.ignoredAck().contains("未立状"))
        assertTrue("被拒 ⇒ 原文回灌", PlanPledgeAmendCards.rejectedAck("写法不对").contains("写法不对"))
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
}
