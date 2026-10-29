// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十八批 §1–§3 的整条流水线（意见 → 拟稿 → ③b 核验 → 卡面自检 → 御案列表）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.ClaimCheck
import com.example.zhengdao.baopu.agent.ClaimVerdict
import com.example.zhengdao.baopu.agent.ClaimVerifier
import com.example.zhengdao.baopu.agent.OpinionDecision
import com.example.zhengdao.baopu.agent.OpinionWhy
import com.example.zhengdao.baopu.agent.Ruling
import com.example.zhengdao.baopu.agent.StubVerifier
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalQueueRulesTest {

    private val s = DecisionStrRes { _, _ -> "短句" }

    private fun ruling(id: String = "op:1", law: LawRef = LawRef.L7) = Ruling(
        id = id,
        opinion = Opinion(
            Source.CLASSIFIER, Severity.MAJOR, "未分类", ActionCode.REVIEW, law,
            listOf("risk:unclassified_tool"),
        ),
        decision = OpinionDecision.DEFER,
        why = OpinionWhy.ROUND_TAKEN,
        detail = "本轮已弹过一张：进队列",
        round = "process",
        royalToday = 1,
        atMs = 1_700_000_000_000L,
        tool = "write_file",
    )

    @Test
    fun `判据_一条意见能走完整条流水线变成一张可渲染的卡`() {
        val row = RoyalQueueRules.prepare(ruling(), s, StubVerifier)
        assertNotNull("正常意见必须拟出卡", row)
        assertTrue(RoyalCardGate.renderable(row!!.card))
        assertEquals(RoyalTruth.UNVERIFIED, row.card.mark)
        assertEquals(3, row.card.options.size)
    }

    @Test
    fun `负例_有假主张的意见打回_不进御案列表`() {
        val liar = ClaimVerifier { list -> list.map { ClaimCheck(it.id, ClaimVerdict.FALSE, "第 41 行对不上") } }
        assertNull("假条必须打回", RoyalQueueRules.prepare(ruling(), s, liar))
        val rows = RoyalQueueRules.pending(listOf(ruling()), emptySet(), s, liar)
        assertTrue("打回的不许出现在御案里", rows.isEmpty())
        val why = RoyalQueueRules.reasonLine(ruling(), s, liar)
        assertTrue("要能说清为什么没上：$why", why!!.contains("打回"))
    }

    @Test
    fun `判据_批过驳过的会被suppressed划掉`() {
        val rows = RoyalQueueRules.pending(listOf(ruling("op:1"), ruling("op:2")), setOf("op:1"), s, StubVerifier)
        assertEquals(listOf("op:2"), rows.map { it.id })
    }

    @Test
    fun `判据_无法判定的卡照样上御案_但标未核实`() {
        val unsure = ClaimVerifier { list -> list.map { ClaimCheck(it.id, ClaimVerdict.UNVERIFIABLE) } }
        val row = RoyalQueueRules.prepare(ruling(), s, unsure)!!
        assertEquals(RoyalTruth.UNVERIFIED, row.card.mark)
        assertTrue("未核实不许挡住上御案（它只是要写明）", RoyalCardGate.renderable(row.card))
    }

    @Test
    fun `判据_全核过才标真`() {
        val ok = ClaimVerifier { list -> list.map { ClaimCheck(it.id, ClaimVerdict.TRUE, "对上账") } }
        val row = RoyalQueueRules.prepare(ruling(), s, ok)!!
        assertEquals(RoyalTruth.TRUE, row.card.mark)
    }

    @Test
    fun `判据_排序按时间新到旧`() {
        val older = ruling("op:1").copy(atMs = 1_000L)
        val newer = ruling("op:2").copy(atMs = 9_000L)
        val rows = RoyalQueueRules.pending(listOf(older, newer), emptySet(), s, StubVerifier)
        assertEquals(listOf("op:2", "op:1"), rows.map { it.id })
    }
}
