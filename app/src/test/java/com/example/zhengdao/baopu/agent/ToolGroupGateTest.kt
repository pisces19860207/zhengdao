// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第九十七批 §2（启用触发与御批）/ §2.5（口径变更）/ §4（测试）。
// 这一份钉的是**闸门本体**（纯 JVM）：弹卡时机、被拒后的 N 次限流、同类合并、
// 按会话分格、审计行格式、卡片三选一的形状。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGroupGateTest {

    // ── 弹卡时机 ─────────────────────────────────────────────────────────────

    @Test
    fun `N1_首次申请就弹卡`() {
        val gate = ToolGroupGate()
        val d = gate.decide(1L, ToolGroup.BROWSER)
        assertTrue("第一次申请必须给卡：$d", d is GroupGateDecision.Ask)
        assertEquals(1, (d as GroupGateDecision.Ask).attempt)
    }

    @Test
    fun `N2_被拒后攒够N次才再弹`() {
        val gate = ToolGroupGate(threshold = 3)
        assertTrue(gate.decide(1L, ToolGroup.BROWSER) is GroupGateDecision.Ask)
        gate.noteDeclined(1L, ToolGroup.BROWSER) // 第 1 次被拒 ⇒ 下次要等到第 4 次
        // 第 2、3 次：压住
        repeat(2) { i ->
            val d = gate.decide(1L, ToolGroup.BROWSER)
            assertTrue("拒绝后第 ${i + 2} 次申请不该弹卡：$d", d is GroupGateDecision.Suppress)
            assertTrue("要说清为什么压住：${(d as GroupGateDecision.Suppress).reason}",
                d.reason.contains("没有同意"))
        }
        // 第 4 次：再弹
        assertTrue("攒够 3 次必须再弹", gate.decide(1L, ToolGroup.BROWSER) is GroupGateDecision.Ask)
        assertEquals(4, gate.attemptCount(1L, ToolGroup.BROWSER))
    }

    @Test
    fun `N3_已经有一张卡在等时合并_不叠卡`() {
        val gate = ToolGroupGate()
        assertTrue(gate.decide(1L, ToolGroup.LIBRARY) is GroupGateDecision.Ask)
        val second = gate.decide(1L, ToolGroup.LIBRARY)
        assertTrue("同一组不许叠第二张卡：$second", second is GroupGateDecision.Suppress)
        assertTrue((second as GroupGateDecision.Suppress).reason.contains("合并"))
        // 点完之后下一次又能弹（不是永久锁死）
        gate.noteDeclined(1L, ToolGroup.LIBRARY)
        gate.reset()
        assertTrue(gate.decide(1L, ToolGroup.LIBRARY) is GroupGateDecision.Ask)
    }

    @Test
    fun `N4_同意后清态_下次重新从第一次算`() {
        val gate = ToolGroupGate(threshold = 3)
        gate.decide(1L, ToolGroup.FILES)
        gate.noteDeclined(1L, ToolGroup.FILES)
        gate.decide(1L, ToolGroup.FILES) // 被压住
        gate.noteApproved(1L, ToolGroup.FILES)
        assertEquals(0, gate.attemptCount(1L, ToolGroup.FILES))
        assertTrue("同意后计数清零、下个会话/下次申请重新弹", gate.decide(1L, ToolGroup.FILES) is GroupGateDecision.Ask)
    }

    @Test
    fun `N5_按会话分格_一个会话被拒不牵连另一个`() {
        val gate = ToolGroupGate()
        gate.decide(11L, ToolGroup.CARD)
        gate.noteDeclined(11L, ToolGroup.CARD)
        assertTrue("另一个会话第一次申请必须弹卡", gate.decide(22L, ToolGroup.CARD) is GroupGateDecision.Ask)
        assertEquals(1, gate.attemptCount(22L, ToolGroup.CARD))
    }

    @Test
    fun `N6_阈值可配_设为1就每次都弹`() {
        val gate = ToolGroupGate(threshold = 1)
        repeat(3) {
            assertTrue("threshold=1 ⇒ 每次都要卡", gate.decide(3L, ToolGroup.PREVIEW) is GroupGateDecision.Ask)
            gate.noteDeclined(3L, ToolGroup.PREVIEW)
        }
    }

    // ── 卡片形状（复用 ask_user 那张卡）────────────────────────────────────

    @Test
    fun `C1_三选一_本次会话排第一_取消在最后`() {
        val req = GroupApprovalCard.request(ToolGroup.BROWSER)
        assertEquals("只三选一，不许多塞", 3, req.options.size)
        assertEquals(listOf("session", "always", "cancel"), req.options.map { it.id })
        assertTrue("推荐项要排第一（ask_user 的既有纪律）", req.options.first().recommended)
        assertEquals("本次会话", req.options.first().label)
        assertTrue("问题要点名是哪一组：${req.question}", req.question.contains("网页"))
    }

    @Test
    fun `C2_SKILL组带风险提示_普通组不带`() {
        assertTrue(
            "SKILL 必须在卡面写清风险：${GroupApprovalCard.request(ToolGroup.SKILL).question}",
            GroupApprovalCard.request(ToolGroup.SKILL).question.contains("风险较高"),
        )
        assertFalse(GroupApprovalCard.request(ToolGroup.LIBRARY).question.contains("风险较高"))
        // 每一组的标题都得能从 oneLine 取出来（冒号前那一段），不许有组是空标题
        ToolGroup.values().filter { it != ToolGroup.COMPAT }.forEach {
            assertNotEquals("${it.key} 组标题不许为空", "", GroupApprovalCard.titleOf(it))
        }
    }

    // ── 审计 ───────────────────────────────────────────────────────────────

    @Test
    fun `A1_三种动作各一行_带group锚与来源`() {
        val lines = mutableListOf<String>()
        val old = ToolGroupAudit.sink
        ToolGroupAudit.sink = { lines += it }
        try {
            ToolGroupAudit.grant(ToolGroup.BROWSER, "本次会话")
            ToolGroupAudit.grant(ToolGroup.CARD, "长期")
            ToolGroupAudit.deny(ToolGroup.LIBRARY)
            ToolGroupAudit.manual(ToolGroup.SKILL, on = true)
            assertEquals(4, lines.size)
            assertTrue(lines.all { it.startsWith("[group]") })
            assertTrue("批准要带范围：${lines[0]}", lines[0].contains("批准 browser（本次会话）"))
            assertTrue(lines[0].contains("来源=卡片"))
            assertTrue(lines[1].contains("批准 card（长期）"))
            assertTrue("拒绝要有自己的一行：${lines[2]}", lines[2].contains("未批准 library"))
            assertTrue("手动开启要与卡片来源分开：${lines[3]}", lines[3].contains("来源=设置页"))
            assertTrue(lines[3].contains("手动开启 skill"))
        } finally {
            ToolGroupAudit.sink = old
        }
    }

    @Test
    fun `A2_没有落盘点也能调_只返回文本不炸`() {
        val old = ToolGroupAudit.sink
        ToolGroupAudit.sink = null
        try {
            val line = ToolGroupAudit.grant(ToolGroup.FILES, "本次会话")
            assertTrue(line.startsWith("[group] 批准 files"))
        } finally {
            ToolGroupAudit.sink = old
        }
    }
}
