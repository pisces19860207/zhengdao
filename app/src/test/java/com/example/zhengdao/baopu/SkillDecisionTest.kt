// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十七批 §1.3：采纳/丢弃留痕 ＋ 同候选去重（名称＋正文摘要 hash）。
//
// 边界（测试照它钉）：留痕**只记录**——丢弃候选之后是「下轮不再提」，
// 不是「删技能」；hash 提不出的候选不瞎猜（宁缺勿错）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.SkillRefine.LearnProposal
import com.example.zhengdao.baopu.SkillRefine.LearnSignal
import com.example.zhengdao.baopu.SkillRefine.SignalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillDecisionTest {

    // ── D1：去重键稳定（派工单 §2：同内容同 key，不同内容不同 key）─────────────

    @Test
    fun `D1_同内容同key_跨轮认得出是同一个候选`() {
        val a = SkillRefine.dedupeKey("做卡流程", "遇到做卡任务先看名册再动手")
        val b = SkillRefine.dedupeKey("做卡流程", "遇到做卡任务先看名册再动手")
        assertEquals(a, b)
        assertEquals("同内容不同次调用也稳定", a, SkillRefine.dedupeKey("做卡流程", "遇到做卡任务先看名册再动手"))
    }

    @Test
    fun `D1_不同内容不同key_两条候选不许被误并`() {
        val a = SkillRefine.dedupeKey("做卡流程", "先看名册")
        val b = SkillRefine.dedupeKey("做卡流程", "先看底稿")
        assertNotEquals(a, b)
        // 名字也是键的一部分：同名不同文、同文不同名都该分得开
        assertNotEquals(a, SkillRefine.dedupeKey("查资料流程", "先看名册"))
    }

    @Test
    fun `D1_key是16位十六进制_长度钉死`() {
        val k = SkillRefine.dedupeKey("n", "c")
        assertEquals("sha256 前 16 位", 16, k.length)
        assertTrue("必须是 hex：$k", k.all { it.isDigit() || it in 'a'..'f' })
    }

    // ── D2：提炼跳过被丢候选（丢弃留痕的另一半）────────────────────────────────

    private fun signal(detail: String) = listOf(LearnSignal(SignalKind.USER_SAID, detail))

    @Test
    fun `D2_封卷提炼_被丢过的候选下轮不再出`() {
        val facts = MemoryRefine.SessionFacts(
            sessionId = 1L,
            userTexts = listOf("记住这个流程：做卡先看名册"),
            now = 0L,
        )
        val first = SkillRefine.refine(facts, emptyList())
        assertEquals("首轮要出一条", 1, first.candidates.size)
        val cand = first.candidates[0]
        val key = SkillRefine.dedupeKey(cand.name, SkillRefine.normalizeKeyText(cand.scenario))
        val second = SkillRefine.refine(facts, emptyList(), rejectedKeys = setOf(key))
        assertTrue("丢过 ⇒ 不再提", second.candidates.isEmpty())
        val other = SkillRefine.refine(facts, emptyList(), rejectedKeys = setOf("deadbeef"))
        assertEquals("没丢过的照常提", 1, other.candidates.size)
    }

    @Test
    fun `D2_学习卡_被丢过的候选下轮不再出`() {
        val signals = signal("记住：做卡先看名册再动手，避免漏步骤")
        val first = SkillRefine.learnProposals(signals)
        assertTrue("首轮要出卡", first.isNotEmpty())
        val rejected = first.map { SkillRefine.dedupeKeyOf(it) }.toSet()
        assertTrue("学习卡候选的 hash 必须非空（有 content 才有键）", rejected.all { it.length == 16 })
        val second = SkillRefine.learnProposals(signals, rejectedKeys = rejected)
        assertTrue("整卡都丢过 ⇒ 下轮不出", second.isEmpty())
    }

    // ── D3：封卷确认卡的名字提取（留痕的口径）──────────────────────────────────

    private fun proposalOf(name: String, scenario: String): PersonaProposal {
        val current = ""
        val line = SkillRules.IndexLine(MemoryScope.PROJECT, name, scenario)
        // 照壳层的落点走同一套渲染/解析（refine 候选 → renderIndexLine → 确认卡 → parse）
        val newText = (SkillRules.addIndexLine(current, line) as SkillRules.EditResult.Ok).newText
        return PersonaProposal(
            layer = PersonaLayer.C_PROJECT,
            current = current,
            proposed = newText,
            reason = "测试",
        )
    }

    @Test
    fun `D3_提案名字从新增行提取_提不出返回null不瞎猜`() {
        val p = proposalOf("做卡流程", "遇到做卡任务先看名册")
        assertEquals("做卡流程", SkillRefine.proposalNameOf(p))
        val key = SkillRefine.dedupeKeyOfProposal(p)
        assertTrue("key 要有（16 位 hex）：$key", key != null && key.length == 16)
        // 提不出的形状：proposed 没有新增行
        val empty = PersonaProposal(PersonaLayer.C_PROJECT, p.proposed, p.proposed, "没新增")
        assertNull("没有新增行 ⇒ null（宁缺勿错，不把没丢过的拉黑）", SkillRefine.proposalNameOf(empty))
    }

    @Test
    fun `D3_提炼候选与确认卡候选同一把尺_key能对上`() {
        // refine 产候选 →（壳层渲染成索引行提案）→ 用户丢弃 ⇒ 两边的 key 必须相等
        val facts = MemoryRefine.SessionFacts(
            sessionId = 1L,
            userTexts = listOf("记住这个流程：做卡先看名册"),
            now = 0L,
        )
        val cand = SkillRefine.refine(facts, emptyList()).candidates.single()
        val line = SkillRules.IndexLine(cand.scope, cand.name, cand.scenario)
        val proposed = (SkillRules.addIndexLine("", line) as SkillRules.EditResult.Ok).newText
        val card = PersonaProposal(PersonaLayer.C_PROJECT, "", proposed, "封卷")
        assertEquals(
            "丢弃侧的 key 必须与提炼侧一致（对不上＝去重失效）",
            SkillRefine.dedupeKey(cand.name, cand.scenario.trim().trimEnd('，', ',')),
            SkillRefine.dedupeKeyOfProposal(card),
        )
    }

    // ── D4：源码门禁（留痕四接线点都在；谁也不许借留痕通道改技能/删技能）──────

    private fun source(rel: String): String =
        File(rel).let { if (it.isFile) it else File("../$rel") }
            .readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun `D4_四个接线点都接了留痕`() {
        val src = source("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        assertTrue("adoptLearn 要记采纳", Regex("""adopted = true""").containsMatchIn(src))
        assertTrue("dismissLearn 要记丢弃", Regex("""adopted = false""").containsMatchIn(src))
        assertEquals(
            "采纳＋丢弃两处留痕都必须带 dedupeKeyOf（hash 丢了＝去重失效）",
            2,
            Regex("hash = com\\.example\\.zhengdao\\.baopu\\.SkillRefine\\.dedupeKeyOf\\(p\\),")
                .findAll(src).count(),
        )

        assertTrue("封卷确认卡采纳要走 recordSkillDecisionFor", src.contains("recordSkillDecisionFor(p, adopted = true"))
        assertTrue("封卷确认卡丢弃要走 recordSkillDecisionFor（reason 可空）", src.contains("recordSkillDecisionFor(p, adopted = false, detail = reason"))
        assertTrue("提炼要带 rejectedKeys（丢过的不再提）", src.contains("facts, globalLines + projectLines, rejectedKeys,"))
    }

    @Test
    fun `D4_引擎出卡也跳过被丢候选_主循环语义没动`() {
        val src = source("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("emitLearnCardIfDue 要传 rejected", src.contains("SkillRefine.learnProposals(learnSignals, names, rejected)"))
        // 主循环语义没动：runLoop/stopRequested 的判据不能被这批碰
        assertFalse("不许在本批改 stopRequested 判据", src.substringAfter("emitLearnCardIfDue()").substringBefore("private fun existingSkillNames").contains("stopRequested ="))
    }

    @Test
    fun `D4_decision表只增不改不删_留痕不许被洗掉`() {
        val src = source("app/src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
        val decision = src.substringAfter("fun insertSkillDecision")
            .substringBefore("/**")
        assertTrue("INSERT 才是留痕", decision.contains("INSERT INTO skill_event"))
        assertFalse("不许 UPDATE", decision.contains("UPDATE "))
        assertFalse("不许 DELETE", decision.contains("DELETE"))
    }

    @Test
    fun `D4_丢弃理由可空不强制_有理由就截到120字`() {
        val s = BaopuSql.insertSkillDecision("skill", "做卡流程", adopted = false, hash = "abc", detail = "不".repeat(300), nowMs = 1L)
        assertEquals("detail 原样绑定（截断在壳层做，SQL 层不偷改）", "不".repeat(300), s.args[4])
        val noReason = BaopuSql.insertSkillDecision("skill", "做卡流程", adopted = false, hash = null, detail = null, nowMs = 1L)
        assertNull("不填理由也立得住", noReason.args[4])
        assertTrue("采纳 ok=1 / 丢弃 ok=0", BaopuSql.insertSkillDecision("skill", "n", true, null, null, 1L).args[2] == "1")
    }
}
