// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零四批：决策卡家族与全局单卡槽的判据测试（派工单 §2 测试/门禁）。
// 靶子全部对着 `DecisionCardRules.kt` 的纯函数；Composable 层不测（无 Robolectric，
// 视觉/热区走源码门禁与真机走查，见同批 `DecisionCardGateTest`）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.EvolutionReasonCode
import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.ProposalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionCardRulesTest {

    private fun item(kind: DecisionVariant, id: String) = DecisionItem(kind, id)

    // ── 单卡槽：优先级 ────────────────────────────────────────────────────────

    @Test
    fun `空槽没有卡`() {
        assertNull(decisionSlotCurrent(null, emptyList()))
    }

    @Test
    fun `四类同时待处理_授权占槽`() {
        val pending = listOf(
            item(DecisionVariant.SUGGESTION, "suggestion:c1"),
            item(DecisionVariant.PERSONA, "persona:C"),
            item(DecisionVariant.POST_ADOPTION, "effect:e1"),
            item(DecisionVariant.AUTHORIZATION, "auth:browser"),
        )
        assertEquals(item(DecisionVariant.AUTHORIZATION, "auth:browser"), decisionSlotCurrent(null, pending))
    }

    @Test
    fun `授权处理后_效果卡补位`() {
        val pending = listOf(
            item(DecisionVariant.SUGGESTION, "suggestion:c1"),
            item(DecisionVariant.POST_ADOPTION, "effect:e1"),
        )
        assertEquals(item(DecisionVariant.POST_ADOPTION, "effect:e1"), decisionSlotCurrent(null, pending))
    }

    @Test
    fun `效果也没有时_提案先于建议`() {
        val pending = listOf(
            item(DecisionVariant.SUGGESTION, "suggestion:c1"),
            item(DecisionVariant.PERSONA, "persona:C"),
        )
        assertEquals(item(DecisionVariant.PERSONA, "persona:C"), decisionSlotCurrent(null, pending))
    }

    // ── 单卡槽：不抢占（§2.3 第 2 条）────────────────────────────────────────

    @Test
    fun `新卡到来不抢走正在展示的卡`() {
        val current = item(DecisionVariant.SUGGESTION, "suggestion:c1")
        val pending = listOf(
            current,
            item(DecisionVariant.AUTHORIZATION, "auth:browser"), // 更高优先级，但后到
        )
        assertEquals(current, decisionSlotCurrent(current, pending))
    }

    @Test
    fun `当前卡被处理后_槽释放并按优先级补位`() {
        val current = item(DecisionVariant.SUGGESTION, "suggestion:c1")
        // c1 已处理 ⇒ 不在 pending
        val pending = listOf(item(DecisionVariant.AUTHORIZATION, "auth:browser"))
        assertEquals(item(DecisionVariant.AUTHORIZATION, "auth:browser"), decisionSlotCurrent(current, pending))
    }

    @Test
    fun `当前卡从pending消失_不残留幽灵槽`() {
        val current = item(DecisionVariant.PERSONA, "persona:C")
        assertNull(decisionSlotCurrent(current, emptyList()))
    }

    // ── 排队数 ───────────────────────────────────────────────────────────────

    @Test
    fun `排队数不含展示中的那一件`() {
        val pending = listOf(
            item(DecisionVariant.AUTHORIZATION, "auth:browser"),
            item(DecisionVariant.POST_ADOPTION, "effect:e1"),
            item(DecisionVariant.SUGGESTION, "suggestion:c1"),
        )
        val shown = decisionSlotCurrent(null, pending)
        assertEquals(2, decisionQueueCount(shown, pending))
    }

    @Test
    fun `一件待办时排队为零_它就在槽里`() {
        val pending = listOf(item(DecisionVariant.SUGGESTION, "suggestion:c1"))
        // 没登记过「正在展示」也不要紧：这一件下一刻就进槽（屏幕层 SideEffect 登记）
        assertEquals(0, decisionQueueCount(null, pending))
    }

    @Test
    fun `没有待办时排队为零`() {
        assertEquals(0, decisionQueueCount(null, emptyList()))
        assertEquals(0, decisionQueueCount(item(DecisionVariant.PERSONA, "persona:C"), emptyList()))
    }

    // ── 徽标（§3.6b：前三页签，记录页不计）────────────────────────────────────

    @Test
    fun `徽标等于前三页签待处理总数`() {
        assertEquals(0, decisionBadgeCount(0, 0, 0))
        assertEquals(6, decisionBadgeCount(2, 3, 1)) // W1 线框：授权2+建议/效果3+提案1
        assertFalse(badgeVisible(0))
        assertTrue(badgeVisible(1))
    }

    // ── 建议页签：双类型不并串、效果排前 ──────────────────────────────────────

    @Test
    fun `建议页签效果行排前且组内保序`() {
        val rows = listOf(
            DecisionCenterRow("s1", DecisionVariant.SUGGESTION, "建议一", typeLabel = "建议"),
            DecisionCenterRow("e2", DecisionVariant.POST_ADOPTION, "效果二", typeLabel = "效果"),
            DecisionCenterRow("s2", DecisionVariant.SUGGESTION, "建议二", typeLabel = "建议"),
            DecisionCenterRow("e1", DecisionVariant.POST_ADOPTION, "效果一", typeLabel = "效果"),
        )
        val out = suggestionTabRows(rows)
        assertEquals(listOf("e2", "e1", "s1", "s2"), out.map { it.id })
        // 类型签区分（不并串）：每一行都带自己的签
        assertEquals(listOf("效果", "效果", "建议", "建议"), out.map { it.typeLabel })
    }

    // ── 卡片规格自检 ─────────────────────────────────────────────────────────

    private fun spec(
        buttons: List<DecisionButton> = listOf(
            DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE),
            DecisionButton("ok", "一直", DecisionButtonRole.PRIMARY),
        ),
        title: String = "允许使用「网页操作」工具组？",
    ) = DecisionCardSpec(
        variant = DecisionVariant.AUTHORIZATION,
        key = "auth:browser",
        title = title,
        buttons = buttons,
    )

    @Test
    fun `规格自检_合法卡过`() {
        assertTrue(DecisionSpecRules.isValid(spec()))
    }

    @Test
    fun `规格自检_空标题红`() {
        assertFalse(DecisionSpecRules.isValid(spec(title = "  ")))
    }

    @Test
    fun `规格自检_没有强调位红`() {
        val only = listOf(DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE))
        assertFalse(DecisionSpecRules.isValid(spec(buttons = only)))
    }

    @Test
    fun `规格自检_两个强调位红_一张卡最多一个强调色`() {
        val two = listOf(
            DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE),
            DecisionButton("a", "甲", DecisionButtonRole.PRIMARY),
            DecisionButton("b", "乙", DecisionButtonRole.DANGER),
        )
        assertFalse(DecisionSpecRules.isValid(spec(buttons = two)))
    }

    @Test
    fun `规格自检_否定位不在最左红`() {
        val swapped = listOf(
            DecisionButton("ok", "一直", DecisionButtonRole.PRIMARY),
            DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE),
        )
        assertFalse(DecisionSpecRules.isValid(spec(buttons = swapped)))
    }

    @Test
    fun `规格自检_按钮id重复红`() {
        val dup = listOf(
            DecisionButton("x", "取消", DecisionButtonRole.NEGATIVE),
            DecisionButton("x", "一直", DecisionButtonRole.PRIMARY),
        )
        assertFalse(DecisionSpecRules.isValid(spec(buttons = dup)))
    }

    // ── 建议卡内容换算 ───────────────────────────────────────────────────────

    @Test
    fun `风险徽标三档`() {
        assertEquals(R.string.decision_badge_high, riskBadgeRes(EvolutionRisk.HIGH))
        assertEquals(R.string.decision_badge_medium, riskBadgeRes(EvolutionRisk.MEDIUM))
        assertEquals(R.string.decision_badge_low, riskBadgeRes(EvolutionRisk.LOW))
    }

    @Test
    fun `证据chip只映射用户看得懂的码`() {
        assertEquals(R.string.decision_chip_eval_passed, reasonChipRes(EvolutionReasonCode.EVAL_PASSED))
        assertEquals(R.string.decision_chip_effect_negative, reasonChipRes(EvolutionReasonCode.EFFECT_NEGATIVE))
        assertEquals(R.string.decision_chip_rollback_available, reasonChipRes(EvolutionReasonCode.ROLLBACK_AVAILABLE))
        assertNull(reasonChipRes(EvolutionReasonCode.CANDIDATE_EVIDENCE))
        assertNull(reasonChipRes(EvolutionReasonCode.HIGH_RISK_ALWAYS_APPROVAL))
    }

    @Test
    fun `高风险采纳必须二次确认`() {
        assertTrue(adoptNeedsConfirm(EvolutionRisk.HIGH))
        assertFalse(adoptNeedsConfirm(EvolutionRisk.MEDIUM))
        assertFalse(adoptNeedsConfirm(EvolutionRisk.LOW))
    }

    @Test
    fun `工具约束建议没有落盘通道_采纳置灰`() {
        assertTrue(suggestionAdoptEnabled(ProposalKind.SKILL_BODY))
        assertTrue(suggestionAdoptEnabled(ProposalKind.PERSONA_APPEND))
        // 99 批报告 §6：TOOL_RULE 一键采纳「不新开写盘路径」⇒ 后续单
        assertFalse(suggestionAdoptEnabled(ProposalKind.TOOL_RULE))
    }

    @Test
    fun `建议三值都有文案`() {
        assertEquals(R.string.decision_rec_adopt, recommendationRes(EvolutionRecommendation.ADOPT))
        assertEquals(R.string.decision_rec_reject, recommendationRes(EvolutionRecommendation.REJECT))
        assertEquals(R.string.decision_rec_defer, recommendationRes(EvolutionRecommendation.DEFER))
    }
}
