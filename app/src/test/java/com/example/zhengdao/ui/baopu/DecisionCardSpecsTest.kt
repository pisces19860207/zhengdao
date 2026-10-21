// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零四批：决策卡四变体的**回填测试**（派工单 §2「四变体回填/按钮/状态各至少一例」）。
//
// 房型：规格工厂（DecisionSpecs）是纯映射＋文案解析器注入 ⇒ 这里用「读
// strings.xml 的假解析器」直测——顺带把「键不存在/键值文不对题」也钉住
// （R 键在但 strings.xml 缺行 ⇒ 解析器直接抛，测试红）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.EffectSnapshot
import com.example.zhengdao.baopu.EvolutionRecommendation
import com.example.zhengdao.baopu.EvolutionReason
import com.example.zhengdao.baopu.EvolutionReasonCode
import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.PostAdoptionChannel
import com.example.zhengdao.baopu.PostAdoptionNotice
import com.example.zhengdao.baopu.PostAdoptionReason
import com.example.zhengdao.baopu.PostAdoptionVerdict
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.agent.AskKind
import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecisionCardSpecsTest {

    // ── 假解析器：R.int → strings.xml 真值（键缺失即抛） ─────────────────────

    private val idToName: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val zhXml: String by lazy {
        File("src/main/res/values/strings.xml")
            .let { if (it.isFile) it else File("app/$it") }
            .readText()
    }

    private val res = DecisionStrRes { id, args ->
        val name = idToName[id] ?: error("R.string id=$id 没有对应字段")
        val m = Regex("<string name=\"$name\">([^<]*)</string>").find(zhXml)
            ?: error("strings.xml 缺键 $name（决策卡用到了它）")
        val raw = m.groupValues[1]
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        if (args.isEmpty()) raw else String.format(raw, *args)
    }

    private fun buttonMap(spec: DecisionCardSpec): Map<String, DecisionButton> =
        spec.buttons.associateBy { it.id }

    // ── 授权卡（97） ────────────────────────────────────────────────────────

    private fun authReq(subject: String, skill: Boolean = false) = AskUserRequest(
        question = "启用「$subject」组？",
        options = listOf(
            AskUserOption("session", "本次会话", recommended = true),
            AskUserOption("always", "一直"),
            AskUserOption("cancel", "取消"),
        ),
        kind = AskKind.GROUP_AUTHORIZATION,
        subject = subject,
    )

    @Test
    fun `授权卡_普通组_三钮次序_否左确认中缓冲右`() {
        val spec = DecisionSpecs.authorization(authReq("browser"), res)
        val byId = buttonMap(spec)
        assertEquals(3, spec.buttons.size)
        assertEquals(DecisionButtonRole.NEGATIVE, byId["cancel"]!!.role)
        assertEquals(DecisionButtonRole.PRIMARY, byId["session"]!!.role)
        assertEquals(DecisionButtonRole.BUFFER, byId["always"]!!.role)
        // 次序：取消在最左（拇指区口径）
        assertEquals("cancel", spec.buttons.first().id)
        // 标题带组显示名；副标题是一句话用途
        assertTrue("标题要带组名", spec.title.contains("网页操作"))
        assertTrue("副标题要有一句话用途", spec.subtitle.contains("点击"))
        // 非 SKILL 组没有警示带
        assertNull(spec.warning)
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    @Test
    fun `授权卡_SKILL组_贴顶警示带`() {
        val spec = DecisionSpecs.authorization(authReq("skill"), res)
        assertNotNull("SKILL 组必须带高风险警示带", spec.warning)
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    // ── 提案卡（96） ────────────────────────────────────────────────────────

    private fun personaReq(subject: String, withGlobal: Boolean) = AskUserRequest(
        question = "把这段记进人设？「…」（理由）",
        options = buildList {
            add(AskUserOption("workspace", "本工作区", recommended = true))
            if (withGlobal) add(AskUserOption("global", "全局（仅 B）"))
            add(AskUserOption("cancel", "拒绝"))
        },
        kind = AskKind.PERSONA_PROPOSAL,
        subject = subject,
        detailText = "第一条条款\n第二条条款",
    )

    @Test
    fun `提案卡_C类没有全局钮`() {
        val spec = DecisionSpecs.persona(personaReq("C", withGlobal = false), res)
        assertNull("C 类不许出现全局钮", buttonMap(spec)["global"])
        assertTrue("C 类副标题要写本工作区", spec.subtitle.contains("本工作区"))
        // 条款全文进正文（默认 3 行＋展开）
        assertTrue(spec.body.contains("第一条条款"))
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    @Test
    fun `提案卡_B类有全局钮且居缓冲位`() {
        val spec = DecisionSpecs.persona(personaReq("B", withGlobal = true), res)
        val g = buttonMap(spec)["global"]
        assertNotNull("B 类要有全局钮", g)
        assertEquals(DecisionButtonRole.BUFFER, g!!.role)
        assertTrue(spec.subtitle.contains("所有工作区"))
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    // ── 建议卡（99） ────────────────────────────────────────────────────────

    private fun suggestion(
        kind: ProposalKind = ProposalKind.SKILL_BODY,
        risk: EvolutionRisk = EvolutionRisk.LOW,
        rec: EvolutionRecommendation = EvolutionRecommendation.ADOPT,
        target: String = "长文摘要",
    ) = EvolutionSuggestion(
        candidateId = "cand-1",
        kind = kind,
        target = target,
        payload = "技能正文建议：先看结构再动笔。",
        createdAt = 1_000L,
        recommendation = rec,
        confidence = 0.8,
        riskClass = risk,
        requiresApproval = true,
        reasons = listOf(
            EvolutionReason(EvolutionReasonCode.CANDIDATE_EVIDENCE, listOf("run_log:1")),
            EvolutionReason(EvolutionReasonCode.EVAL_PASSED),
        ),
    )

    @Test
    fun `建议卡_三钮_采纳可用_证据chip过滤流程码`() {
        val spec = DecisionSpecs.suggestion(suggestion(), res)
        val byId = buttonMap(spec)
        assertEquals(DecisionButtonRole.NEGATIVE, byId["reject"]!!.role)
        assertEquals(DecisionButtonRole.BUFFER, byId["defer"]!!.role)
        assertTrue("低风险可采纳", byId["adopt"]!!.enabled)
        // 风险徽标＋判断行
        assertEquals("低", spec.badge)
        assertTrue(spec.subtitle.contains("建议采纳"))
        // chips：eval 过线在；CANDIDATE_EVIDENCE 这种流程码被过滤
        assertEquals(listOf("eval 过线"), spec.chips)
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    @Test
    fun `建议卡_工具约束_采纳置灰并带原因`() {
        val spec = DecisionSpecs.suggestion(suggestion(kind = ProposalKind.TOOL_RULE), res)
        val adopt = buttonMap(spec)["adopt"]!!
        assertFalse("TOOL_RULE 没有落盘通道，采纳必须置灰", adopt.enabled)
        assertNotNull("置灰要带原因", adopt.note)
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    @Test
    fun `建议卡_高风险_警示带加危险徽标`() {
        val spec = DecisionSpecs.suggestion(suggestion(risk = EvolutionRisk.HIGH), res)
        assertNotNull("高风险要警示带", spec.warning)
        assertEquals(DecisionBadgeTone.DANGER, spec.badgeTone)
        assertEquals("高", spec.badge)
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    @Test
    fun `建议卡_人设类与工具类标题各走各的`() {
        val persona = DecisionSpecs.suggestion(suggestion(kind = ProposalKind.PERSONA_APPEND), res)
        assertTrue(persona.title.contains("人设"))
        val tool = DecisionSpecs.suggestion(suggestion(kind = ProposalKind.TOOL_RULE, target = "delete_path"), res)
        assertTrue(tool.title.contains("delete_path"))
    }

    // ── 效果卡（100） ───────────────────────────────────────────────────────

    private fun notice(verdict: PostAdoptionVerdict, backups: List<String>) = PostAdoptionNotice(
        eventId = "adopt-x-1",
        channel = PostAdoptionChannel.LEARN_CARD,
        skillName = "长文摘要",
        verdict = verdict,
        reason = PostAdoptionReason.EFFECT_NEGATIVE_ROLLBACKABLE,
        before = EffectSnapshot(4, 1, 0, 0),
        delta = EffectSnapshot(0, 2, 1, 0),
        backupIds = backups,
        appliedAtMs = 2_000L,
    )

    @Test
    fun `效果卡_ROLLBACK_回滚钮是危险主钮且带备份chip`() {
        val spec = DecisionSpecs.effect(notice(PostAdoptionVerdict.ROLLBACK, listOf("b1")), res)
        val rollback = buttonMap(spec)["rollback"]
        assertNotNull("ROLLBACK 要给了回滚钮", rollback)
        assertEquals(DecisionButtonRole.DANGER, rollback!!.role)
        assertTrue(spec.chips.contains("可回滚"))
        // 一行前后对比（正文里同时有采纳前与采纳后）
        assertTrue(spec.body.contains("采纳前"))
        assertTrue(spec.body.contains("采纳后"))
        assertEquals(DecisionBadgeTone.DANGER, spec.badgeTone)
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    @Test
    fun `效果卡_RETIRE_主钮只指路设置页`() {
        val spec = DecisionSpecs.effect(notice(PostAdoptionVerdict.RETIRE, emptyList()), res)
        val byId = buttonMap(spec)
        assertNull("RETIRE 不许给回滚钮（没有备份）", byId["rollback"])
        assertNotNull("RETIRE 主钮要指路", byId["tosettings"])
        assertEquals(DecisionButtonRole.PRIMARY, byId["tosettings"]!!.role)
        assertTrue("没有备份 ⇒ 不带可回滚 chip", spec.chips.isEmpty())
        assertTrue(DecisionSpecRules.isValid(spec))
    }

    // ── 待办投影（哪类事进槽） ──────────────────────────────────────────────

    @Test
    fun `待办投影_普通提问不进决策槽`() {
        val items = decisionPendingItems(
            choiceKind = AskKind.QUESTION,
            choiceSubject = "",
            effectIds = emptyList(),
            suggestionIds = emptyList(),
        )
        assertTrue("ask_user 普通提问是对话不是决策，不进槽", items.isEmpty())
    }

    @Test
    fun `待办投影_授权效果建议各就各位`() {
        val items = decisionPendingItems(
            choiceKind = AskKind.GROUP_AUTHORIZATION,
            choiceSubject = "browser",
            effectIds = listOf("e1", "e2"),
            suggestionIds = listOf("c1"),
        )
        assertEquals(4, items.size)
        assertEquals(DecisionVariant.AUTHORIZATION, items.first().kind)
        assertEquals("auth:browser", items.first().id)
        assertEquals(DecisionVariant.POST_ADOPTION, items[1].kind)
        // 授权让位给效果（授权处理后）——槽位判据复用
        val afterAuth = decisionSlotCurrent(items.first(), items.drop(1))
        assertEquals(DecisionVariant.POST_ADOPTION, afterAuth!!.kind)
    }

    @Test
    fun `待办投影_人设提案占一格`() {
        val items = decisionPendingItems(
            choiceKind = AskKind.PERSONA_PROPOSAL,
            choiceSubject = "C",
            effectIds = emptyList(),
            suggestionIds = emptyList(),
        )
        assertEquals(listOf(DecisionItem(DecisionVariant.PERSONA, "persona:C")), items)
    }

    // ── 按钮排序（否左/缓冲中/确认右） ──────────────────────────────────────

    @Test
    fun `按钮排序_引擎推荐项第一也被排到确认位`() {
        val ordered = orderedButtons(
            listOf(
                DecisionButton("session", "本次会话", DecisionButtonRole.PRIMARY),
                DecisionButton("always", "一直", DecisionButtonRole.BUFFER),
                DecisionButton("cancel", "取消", DecisionButtonRole.NEGATIVE),
            ),
        )
        assertEquals(listOf("cancel", "always", "session"), ordered.map { it.id })
    }
}
