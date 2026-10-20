// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十六批 §4：生成（三类来源各≥1 例、证据不足不出）、去重（已有技能/已拒绝/PENDING）。
// 全部打纯判据 [ProposalRules]——不起 App、不摸库。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.ProposalRules.SkillEvent
import com.example.zhengdao.baopu.ProposalRules.ToolRun
import com.example.zhengdao.baopu.SkillRefine.LearnProposal
import com.example.zhengdao.baopu.SkillRefine.Suggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalRulesTest {

    private val now = 1_700_000_000_000L

    private fun rejected(id: Long, name: String, target: String = "skill") = SkillEvent(
        id = id,
        name = name,
        ok = false,
        atMs = id * 1_000L,
        target = target,
    )

    private fun used(id: Long, name: String, ok: Boolean = true) = SkillEvent(
        id = id,
        name = name,
        ok = ok,
        atMs = id * 1_000L,
    )

    private fun tool(id: Long, name: String, ok: Boolean) = ToolRun(
        id = id,
        tool = name,
        ok = ok,
        atMs = id * 1_000L,
    )

    private fun generate(
        learn: List<LearnProposal> = emptyList(),
        uses: List<SkillEvent> = emptyList(),
        decisions: List<SkillEvent> = emptyList(),
        tools: List<ToolRun> = emptyList(),
        existing: Map<String, String> = emptyMap(),
        persona: String = "",
        rejectedIds: Set<String> = emptySet(),
        pendingIds: Set<String> = emptySet(),
    ) = ProposalRules.generate(
        ProposalRules.Input(
            sessionId = 1L,
            nowMs = now,
            learn = learn,
            skillUses = uses,
            decisions = decisions,
            toolRuns = tools,
            existingSkillBodies = existing,
            personaText = persona,
            rejectedIds = rejectedIds,
            pendingIds = pendingIds,
        ),
    )

    private fun candidate(list: List<ProposalCandidate>, kind: ProposalKind) =
        list.firstOrNull { it.kind == kind }

    // ── 来源①：用户纠正 ──────────────────────────────────────────────────────

    @Test
    fun `用户纠正_同会话同一技能被否决两次_生成技能规则候选`() {
        val r = generate(decisions = listOf(rejected(10, "做卡流程"), rejected(11, "做卡流程")))
        val c = candidate(r.candidates, ProposalKind.SKILL_BODY)
        assertTrue("要生成一条技能候选：${r.skipped}", c != null)
        c!!
        assertEquals("做卡流程", c.target)
        assertEquals(ProposalRules.correctionPayload("做卡流程"), c.payload)
        assertEquals("id 要和 payload 同一把尺", ProposalRules.idOf(c.target, c.payload), c.id)
        assertEquals(listOf(10L, 11L), c.evidence.skillEventIds)
        assertEquals(11_000L, c.evidence.atMs)
        assertEquals(ProposalStatus.PENDING, c.status)
        assertEquals(now, c.createdAt)
        assertTrue("正文不许写次数（次数进证据）", !c.payload.contains("2"))
    }

    @Test
    fun `用户纠正_同一目标出现第三次_仍是同一条候选不换hash`() {
        val two = generate(decisions = listOf(rejected(10, "做卡流程"), rejected(11, "做卡流程")))
        val three = generate(
            decisions = listOf(rejected(10, "做卡流程"), rejected(11, "做卡流程"), rejected(12, "做卡流程")),
        )
        assertEquals(
            "阈值之后再来一次否决，不许变成第二条候选",
            two.candidates.single().id,
            three.candidates.single().id,
        )
        assertEquals(3, three.candidates.single().evidence.skillEventIds.size)
    }

    @Test
    fun `用户纠正_两个对象各被否决两次_追加一条人设候选`() {
        val r = generate(
            decisions = listOf(
                rejected(1, "做卡流程"),
                rejected(2, "做卡流程"),
                rejected(3, "查资料流程"),
                rejected(4, "查资料流程"),
            ),
        )
        assertEquals(2, r.candidates.count { it.kind == ProposalKind.SKILL_BODY })
        val persona = candidate(r.candidates, ProposalKind.PERSONA_APPEND)
        assertTrue("跨对象重复否决要出人设追加：${r.skipped}", persona != null)
        assertEquals(ProposalRules.PERSONA_TARGET, persona!!.target)
        assertEquals(4, persona.evidence.skillEventIds.size)

        val one = generate(decisions = listOf(rejected(1, "做卡流程"), rejected(2, "做卡流程")))
        assertFalse(
            "只否决一个对象、只够技能规则，不该升级成人设",
            one.candidates.any { it.kind == ProposalKind.PERSONA_APPEND },
        )
    }

    @Test
    fun `用户纠正_只被否决一次_不生成`() {
        val r = generate(decisions = listOf(rejected(1, "做卡流程")))
        assertTrue(r.candidates.isEmpty())
        assertEquals("没到阈值不算跳过，只是没到", 0, r.skipped.size)
    }

    // ── 来源②：失败模式 ──────────────────────────────────────────────────────

    @Test
    fun `失败模式_同一工具重复失败两次_生成工具约束`() {
        val r = generate(tools = listOf(tool(1, "write_file", ok = false), tool(2, "write_file", ok = false)))
        val c = candidate(r.candidates, ProposalKind.TOOL_RULE)
        assertTrue("要生成工具约束：${r.skipped}", c != null)
        assertEquals("write_file", c!!.target)
        assertEquals(ProposalRules.failurePayload("write_file"), c.payload)
        assertEquals(listOf(1L, 2L), c.evidence.runLogIds)
        assertFalse("正文不许带次数", c.payload.contains("2"))
    }

    @Test
    fun `失败模式_工具只失败一次_不生成`() {
        val r = generate(tools = listOf(tool(1, "write_file", ok = false), tool(2, "read_file", ok = false)))
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun `失败模式_压缩原因码不算工具`() {
        val r = generate(
            tools = listOf(
                tool(1, "compression_failed", ok = false),
                tool(2, "compression_failed", ok = false),
                tool(3, "compression_skipped_degraded", ok = false),
            ),
        )
        assertTrue("compression_* 是内部原因码，不该生成工具约束", r.candidates.isEmpty())
    }

    @Test
    fun `失败模式_证据最多引用8条`() {
        val rows = (1L..10L).map { tool(it, "write_file", ok = false) }
        val c = generate(tools = rows).candidates.single()
        assertEquals(ProposalRules.MAX_EVIDENCE_IDS, c.evidence.runLogIds.size)
        assertEquals("取最近 8 条", 3L, c.evidence.runLogIds.first())
    }

    // ── 来源③：成功模式 ──────────────────────────────────────────────────────

    @Test
    fun `成功模式_同一技能连续成功且工具链成功_生成正文补充`() {
        val r = generate(
            uses = listOf(used(5, "做卡流程"), used(6, "做卡流程")),
            tools = listOf(tool(7, "write_file", ok = true), tool(8, "read_file", ok = true)),
        )
        val c = candidate(r.candidates, ProposalKind.SKILL_BODY)
        assertTrue("要生成成功模式候选：${r.skipped}", c != null)
        assertEquals("做卡流程", c!!.target)
        assertEquals(ProposalRules.successPayload("做卡流程"), c.payload)
        assertEquals(listOf(5L, 6L), c.evidence.skillEventIds)
        assertEquals(listOf(7L, 8L), c.evidence.runLogIds)
        assertEquals(6_000L, c.evidence.atMs)
    }

    @Test
    fun `成功模式_中间夹一次失败_连续段断了不生成`() {
        val r = generate(
            uses = listOf(used(1, "做卡流程"), used(2, "做卡流程", ok = false), used(3, "做卡流程")),
            tools = listOf(tool(4, "write_file", ok = true), tool(5, "read_file", ok = true)),
        )
        assertTrue("只有一次连续成功，不够阈值", r.candidates.isEmpty())
    }

    @Test
    fun `成功模式_工具链证据不足_不生成且说明原因`() {
        val r = generate(
            uses = listOf(used(1, "做卡流程"), used(2, "做卡流程")),
            tools = listOf(tool(3, "write_file", ok = true)),
        )
        assertTrue(r.candidates.isEmpty())
        assertTrue(
            "要说明为什么不出：${r.skipped}",
            r.skipped.any { it.target == "做卡流程" && it.reason.contains("工具链") },
        )
    }

    // ── 学习卡 → 候选（引擎已出的记录）────────────────────────────────────────

    private fun learnProposal(fromUser: Boolean = false, content: String = "遇到做卡任务先看名册") =
        LearnProposal(
            id = "file-做卡流程",
            content = content,
            source = "文件",
            suggest = Suggest.DRAFT,
            fromUser = fromUser,
        )

    @Test
    fun `学习卡_有事件证据_生成候选且id与丢弃留痕同一把尺`() {
        val p = learnProposal()
        val r = generate(learn = listOf(p), tools = listOf(tool(9, "write_file", ok = true)))
        val c = r.candidates.single()
        assertEquals(ProposalKind.SKILL_BODY, c.kind)
        assertEquals("做卡流程", c.target)
        assertEquals(p.content, c.payload)
        assertEquals("id 必须等于卡片口径的 dedupeKeyOf", SkillRefine.dedupeKeyOf(p), c.id)
        assertEquals(listOf(9L), c.evidence.runLogIds)
        assertEquals(now, c.createdAt)
    }

    @Test
    fun `学习卡_没有事件证据也不是用户明说_不生成`() {
        val r = generate(learn = listOf(learnProposal(fromUser = false)))
        assertTrue(r.candidates.isEmpty())
        assertTrue(r.skipped.any { it.reason.contains("证据") })
    }

    @Test
    fun `学习卡_用户明说本身就是已有证据_没有别的记录也生成`() {
        val p = learnProposal(fromUser = true)
        val c = generate(learn = listOf(p)).candidates.single()
        assertEquals(SkillRefine.dedupeKeyOf(p), c.id)
    }

    @Test
    fun `学习卡_同hash已被丢弃_不重复生成`() {
        val p = learnProposal(fromUser = true)
        val r = generate(
            learn = listOf(p),
            rejectedIds = setOf(SkillRefine.dedupeKeyOf(p)),
        )
        assertTrue(r.candidates.isEmpty())
        assertTrue(r.skipped.any { it.reason.contains("拒绝") })
    }

    // ── 去重：已有技能 / PENDING ──────────────────────────────────────────────

    @Test
    fun `去重_已有技能正文已包含这条内容_不生成`() {
        val p = learnProposal(fromUser = true)
        val r = generate(
            learn = listOf(p),
            existing = mapOf("做卡流程" to "# 技能：做卡流程\n遇到做卡任务先看名册\n"),
        )
        assertTrue(r.candidates.isEmpty())
        assertTrue(r.skipped.any { it.reason.contains("已有技能") })
    }

    @Test
    fun `去重_同hash已在PENDING_不重复生成`() {
        val correction = generate(decisions = listOf(rejected(1, "做卡流程"), rejected(2, "做卡流程")))
            .candidates.single()
        val again = generate(
            decisions = listOf(rejected(3, "做卡流程"), rejected(4, "做卡流程")),
            pendingIds = setOf(correction.id),
        )
        assertTrue(again.candidates.isEmpty())
        assertTrue(again.skipped.any { it.reason.contains("PENDING") })
    }

    @Test
    fun `去重_人设追加已在人设文本里_不生成`() {
        val persona = ProposalRules.personaCorrectionPayload()
        val r = generate(
            decisions = listOf(
                rejected(1, "做卡流程"),
                rejected(2, "做卡流程"),
                rejected(3, "查资料流程"),
                rejected(4, "查资料流程"),
            ),
            persona = "# 人设\n$persona\n",
        )
        assertFalse(
            "人设已包含正文 ⇒ 不再生成人设追加",
            r.candidates.any { it.kind == ProposalKind.PERSONA_APPEND },
        )
    }

    // ── 上限与不静默 ─────────────────────────────────────────────────────────

    @Test
    fun `上限_一次最多五条_超出计入dropped不静默`() {
        val tools = (1..6).flatMap { n ->
            listOf(tool(n * 10L, "tool_$n", ok = false), tool(n * 10L + 1, "tool_$n", ok = false))
        }
        val r = generate(tools = tools)
        assertEquals(ProposalRules.MAX_CANDIDATES_PER_RUN, r.candidates.size)
        assertEquals("超出的那条不许悄悄消失", 1, r.dropped)
    }

    @Test
    fun `证据不足_全部来源都只有一次_一条候选也不生成`() {
        val r = generate(
            uses = listOf(used(1, "做卡流程")),
            decisions = listOf(rejected(2, "查资料流程")),
            tools = listOf(tool(3, "write_file", ok = false)),
        )
        assertTrue(r.candidates.isEmpty())
    }
}
