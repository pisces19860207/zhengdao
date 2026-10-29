// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十八批 §1（拟稿：≤30 字现状／≤3 选项各带后果／一个默认项；
// 技术字段禁止出现在卡面，只进证据区）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.agent.ClaimCheck
import com.example.zhengdao.baopu.agent.ClaimVerdict
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

class RoyalDraftsTest {

    /** 假文案解析器：不给真文案（真文案由 `RoyalCardStringsTest` 单独体检），只测结构。 */
    private val s = DecisionStrRes { _, _ -> "短句" }

    private fun ruling(law: LawRef = LawRef.L7, gist: String = "未分类") = Ruling(
        id = "op:1700000000000:1:1",
        opinion = Opinion(
            Source.CLASSIFIER, Severity.MAJOR, gist, ActionCode.REVIEW, law,
            listOf("risk:unclassified_tool", "tool:write_file"),
        ),
        decision = OpinionDecision.DEFER,
        why = OpinionWhy.ROUND_TAKEN,
        detail = "本轮已弹过一张：进队列",
        round = "process",
        royalToday = 1,
        atMs = 1_700_000_000_000L,
        tool = "write_file",
    )

    private fun checks(verdict: ClaimVerdict) =
        RoyalDrafts.claimsOf(ruling()).map { ClaimCheck(it.id, verdict, "对上账") }

    @Test
    fun `判据_三行齐全且都过卡面自检`() {
        val card = RoyalDrafts.draft(ruling(), StubVerifier.verify(RoyalDrafts.claimsOf(ruling())), s)
        assertNotNull("拟稿必须出得来", card)
        assertEquals(3, card!!.lines.size)
        assertEquals(
            listOf(RoyalLineKind.WHAT, RoyalLineKind.WHY, RoyalLineKind.IMPACT),
            card.lines.map { it.kind },
        )
        assertTrue("三行＋选项必须过卡面自检：${RoyalCardGate.check(card)}", RoyalCardGate.renderable(card))
    }

    @Test
    fun `判据_三个选项各带后果且默认是搁置`() {
        val card = RoyalDrafts.draft(ruling(), checks(ClaimVerdict.TRUE), s)!!
        assertEquals(3, card.options.size)
        assertTrue("每个选项都要写清后果", card.options.all { it.consequence.isNotBlank() })
        assertEquals("默认项必须恰好一个", 1, card.options.count { it.isDefault })
        assertEquals(RoyalDrafts.ID_HOLD, card.options.first { it.isDefault }.id)
        assertEquals(
            "三选项＝批/驳/搁",
            listOf(RoyalDrafts.ID_APPROVE, RoyalDrafts.ID_REJECT, RoyalDrafts.ID_HOLD),
            card.options.map { it.id },
        )
    }

    @Test
    fun `负例_技术字段只进证据区_卡面一个字都不带`() {
        val card = RoyalDrafts.draft(ruling(), StubVerifier.verify(RoyalDrafts.claimsOf(ruling())), s)!!
        // 证据区有机器键（本来就该有）
        assertTrue(
            "证据区必须保留账本行与证据键（否则核验无从查）",
            card.evidence.any { it.startsWith("ledger:") } && card.evidence.any { it.startsWith("risk:") },
        )
        // 卡面一个都不许有
        assertTrue(
            "卡面不许出现技术字段：${card.faceTexts}",
            card.faceTexts.none { RoyalCardGate.looksTechnical(it) },
        )
    }

    @Test
    fun `负例_桩核验下卡面必须标未核实_不许标真`() {
        val card = RoyalDrafts.draft(ruling(), StubVerifier.verify(RoyalDrafts.claimsOf(ruling())), s)!!
        assertEquals("没人核过就只能标未核实", RoyalTruth.UNVERIFIED, card.mark)
    }

    @Test
    fun `判据_全核过才标真`() {
        val card = RoyalDrafts.draft(ruling(), checks(ClaimVerdict.TRUE), s)!!
        assertEquals(RoyalTruth.TRUE, card.mark)
    }

    @Test
    fun `负例_有假主张就拟不出卡_这是第二道闸`() {
        assertNull("有假还拟出卡＝绕过了③b", RoyalDrafts.draft(ruling(), checks(ClaimVerdict.FALSE), s))
        assertNull(RoyalDrafts.truthOf(checks(ClaimVerdict.FALSE)))
    }

    @Test
    fun `判据_一条都没核过也算未核实`() {
        assertEquals(RoyalTruth.UNVERIFIED, RoyalDrafts.truthOf(emptyList()))
    }

    @Test
    fun `判据_三行人话按法条分版_三种法条各出各的`() {
        // 真文案由 RoyalCardStringsTest 体检；这里只钉"不同法条走不同资源"，防止全落一版
        val l7 = RoyalDrafts.claimsOf(ruling(LawRef.L7)).first().text
        val l8 = RoyalDrafts.claimsOf(ruling(LawRef.L8, "硬拒")).first().text
        val l9 = RoyalDrafts.claimsOf(ruling(LawRef.L9, "越界访问")).first().text
        assertTrue(l7.startsWith("L7"))
        assertTrue(l8.startsWith("L8"))
        assertTrue(l9.startsWith("L9"))
    }
}
