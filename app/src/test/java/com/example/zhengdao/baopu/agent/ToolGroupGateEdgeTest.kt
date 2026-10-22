// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十四批 · 纯函数边界测试第二批（第 3 组）。
//
// `ToolGroupGateTest` 已钉住「首次弹卡 / 拒后攒 N 次 / 合并 / 分格」；
// 本组只补边界：N＝0、N 为负、未申请先被拒/被同意、放弃与拒绝同口径、
// 等待中的卡不重复计数、会话 id 取极值。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGroupGateEdgeTest {

    @Test
    fun `阈值0_被拒后下一次立刻再弹`() {
        val gate = ToolGroupGate(threshold = 0)
        val first = gate.decide(1L, ToolGroup.BROWSER)
        assertTrue(first is GroupGateDecision.Ask)
        gate.noteDeclined(1L, ToolGroup.BROWSER)
        val second = gate.decide(1L, ToolGroup.BROWSER)
        assertTrue("N＝0 ⇒ 不压，立刻再问：$second", second is GroupGateDecision.Ask)
        assertEquals(2, (second as GroupGateDecision.Ask).attempt)
    }

    @Test
    fun `阈值负_被拒后也立刻再弹_行为与0一致`() {
        val gate = ToolGroupGate(threshold = -1)
        assertTrue(gate.decide(1L, ToolGroup.BROWSER) is GroupGateDecision.Ask)
        gate.noteDeclined(1L, ToolGroup.BROWSER)
        assertTrue("负阈值不许把申请永久压死", gate.decide(1L, ToolGroup.BROWSER) is GroupGateDecision.Ask)
    }

    @Test
    fun `没申请先被拒_只定阈值不记账_仍要攒够N次`() {
        val gate = ToolGroupGate(threshold = 3)
        gate.noteDeclined(1L, ToolGroup.CARD)
        assertEquals("没申请过时不写计数，只定下次阈值", 0, gate.attemptCount(1L, ToolGroup.CARD))
        repeat(3) {
            assertTrue("攒到第 4 次前都压住", gate.decide(1L, ToolGroup.CARD) is GroupGateDecision.Suppress)
        }
        val fourth = gate.decide(1L, ToolGroup.CARD)
        assertTrue("第 4 次得弹（1 次记账 + 3 次攒）", fourth is GroupGateDecision.Ask)
        assertEquals(4, (fourth as GroupGateDecision.Ask).attempt)
    }

    @Test
    fun `没人应答与拒绝同一条限流`() {
        val gate = ToolGroupGate(threshold = 3)
        assertTrue(gate.decide(7L, ToolGroup.LIBRARY) is GroupGateDecision.Ask)
        gate.noteAbandoned(7L, ToolGroup.LIBRARY)
        assertTrue(gate.decide(7L, ToolGroup.LIBRARY) is GroupGateDecision.Suppress)
        assertTrue(gate.decide(7L, ToolGroup.LIBRARY) is GroupGateDecision.Suppress)
        assertTrue(gate.decide(7L, ToolGroup.LIBRARY) is GroupGateDecision.Ask)
    }

    @Test
    fun `没申请先同意_不炸且计数为零`() {
        val gate = ToolGroupGate()
        gate.noteApproved(9L, ToolGroup.FILES)
        assertEquals(0, gate.attemptCount(9L, ToolGroup.FILES))
        val d = gate.decide(9L, ToolGroup.FILES)
        assertTrue(d is GroupGateDecision.Ask)
        assertEquals(1, (d as GroupGateDecision.Ask).attempt)
    }

    @Test
    fun `卡在等时的合并请求不重复计数`() {
        val gate = ToolGroupGate(threshold = 3)
        assertTrue(gate.decide(1L, ToolGroup.CARD) is GroupGateDecision.Ask)
        assertTrue(gate.decide(1L, ToolGroup.CARD) is GroupGateDecision.Suppress)
        assertEquals("合并的请求不算一次申请", 1, gate.attemptCount(1L, ToolGroup.CARD))
        gate.noteDeclined(1L, ToolGroup.CARD)
        assertTrue("从第 1 次起算 ⇒ 第 2 次仍压住", gate.decide(1L, ToolGroup.CARD) is GroupGateDecision.Suppress)
        assertTrue(gate.decide(1L, ToolGroup.CARD) is GroupGateDecision.Suppress)
        assertTrue("第 4 次才再弹", gate.decide(1L, ToolGroup.CARD) is GroupGateDecision.Ask)
        assertEquals(4, gate.attemptCount(1L, ToolGroup.CARD))
    }

    @Test
    fun `不同组互不牵连_同一会话内各算各的`() {
        val gate = ToolGroupGate(threshold = 3)
        assertTrue(gate.decide(5L, ToolGroup.BROWSER) is GroupGateDecision.Ask)
        gate.noteDeclined(5L, ToolGroup.BROWSER)
        val other = gate.decide(5L, ToolGroup.FILES)
        assertTrue("另一组第一次申请照弹", other is GroupGateDecision.Ask)
        assertEquals(1, gate.attemptCount(5L, ToolGroup.FILES))
    }

    @Test
    fun `会话id取极值_仍是独立格子`() {
        val gate = ToolGroupGate(threshold = 3)
        assertTrue(gate.decide(0L, ToolGroup.SKILL) is GroupGateDecision.Ask)
        gate.noteDeclined(0L, ToolGroup.SKILL)
        assertTrue(gate.decide(Long.MIN_VALUE, ToolGroup.SKILL) is GroupGateDecision.Ask)
        assertTrue(gate.decide(Long.MAX_VALUE, ToolGroup.SKILL) is GroupGateDecision.Ask)
        assertEquals(1, gate.attemptCount(Long.MIN_VALUE, ToolGroup.SKILL))
    }

    @Test
    fun `reset_连等待中的卡一起清掉`() {
        val gate = ToolGroupGate(threshold = 3)
        assertTrue(gate.decide(1L, ToolGroup.PREVIEW) is GroupGateDecision.Ask)
        gate.noteDeclined(1L, ToolGroup.PREVIEW)
        assertTrue(gate.decide(1L, ToolGroup.PREVIEW) is GroupGateDecision.Suppress)
        gate.reset()
        assertEquals(0, gate.attemptCount(1L, ToolGroup.PREVIEW))
        val after = gate.decide(1L, ToolGroup.PREVIEW)
        assertTrue("清空后重新从第一次算", after is GroupGateDecision.Ask)
        assertEquals(1, (after as GroupGateDecision.Ask).attempt)
    }
}
