// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 B（阶段二）· 草案结构校验的判据层测试：判据①（定位全空拒收，负例）
// ＋判据②（无证据拒收，负例）＋冲突坏形＋param 声明一致性＋JSON 闭集回环。
package com.example.zhengdao.baopu.learn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SkillDraftRulesTest {

    private fun faces(
        tag: String = "BUTTON",
        name: String = "查询",
        xp: String = "/html/body/button[1]",
    ) = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun step(
        index: Int,
        locator: ObserveFaces = faces(),
        evidence: List<String> = listOf("观察/会话-1.jsonl#seq1"),
        param: String? = null,
        candidates: List<ObserveFaces> = emptyList(),
        kind: String = "click",
    ) = DraftStep(
        index = index, intent = "查询", kind = kind, url = "https://example.test/list",
        locator = locator, param = param, optional = false, candidates = candidates,
        expect = listOf("urlChanged"), evidence = evidence,
    )

    private fun draft(vararg steps: DraftStep, goal: String = "查装车", params: List<DraftParam> = emptyList()) =
        SkillDraft(
            id = "d1", goal = goal, targetSite = "example.test",
            params = params, skeleton = steps.toList(), variableSteps = emptyList(),
            mergedFrom = 1, sourceSessions = listOf("1"),
        )

    // ── 判据①：定位全空 ⇒ 拒收（负例） ──────────────────────────────────────────

    private fun blankFaces() = ObserveFaces(
        tag = "", role = "", name = "", aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = "", cls = emptyList(),
    )

    @Test
    fun `判据1_定位全空的草案被拒`() {
        val blank = blankFaces()
        val d = draft(step(1, locator = blank))
        val check = SkillDraftRules.validate(d)
        assertTrue("负例必须被拒", check is DraftCheck.Invalid)
        assertTrue(
            "拒收理由必须点出 定位全空（判据①断言词）",
            (check as DraftCheck.Invalid).problems.any { it.contains(SkillDraftRules.BLANK_LOCATOR) },
        )
    }

    @Test
    fun `定位全空但有两套候选_不判死_冲突由人裁`() {
        val blank = blankFaces()
        val d = draft(step(1, locator = blank, candidates = listOf(faces(name = "A"), faces(name = "B"))))
        assertTrue(SkillDraftRules.validate(d) is DraftCheck.Ok)
    }

    // ── 判据②：无证据 ⇒ 拒收（负例） ────────────────────────────────────────────

    @Test
    fun `判据2_没有证据的草案被拒`() {
        val d = draft(step(1, evidence = emptyList()))
        val check = SkillDraftRules.validate(d)
        assertTrue("负例必须被拒", check is DraftCheck.Invalid)
        assertTrue(
            (check as DraftCheck.Invalid).problems.any { it.contains(SkillDraftRules.NO_EVIDENCE) },
        )
    }

    @Test
    fun `正常草案_校验通过`() {
        assertTrue(SkillDraftRules.validate(draft(step(1), step(2, locator = faces(xp = "input[1]")))) is DraftCheck.Ok)
    }

    // ── 一致性与坏形 ─────────────────────────────────────────────────────────────

    @Test
    fun `参数步声明了 params 里没有的参数名_拒`() {
        val d = draft(step(1, param = "单号", kind = "type"))
        val check = SkillDraftRules.validate(d)
        assertTrue(check is DraftCheck.Invalid)
    }

    @Test
    fun `冲突候选只有一条_坏形拒`() {
        val d = draft(step(1, candidates = listOf(faces(name = "A"))))
        assertTrue(SkillDraftRules.validate(d) is DraftCheck.Invalid)
    }

    @Test
    fun `goal为空_拒`() {
        assertTrue(SkillDraftRules.validate(draft(step(1), goal = "  ")) is DraftCheck.Invalid)
    }

    // ── JSON 回环（键闭集由形状保证；draft JSON 里没有值通道——判据④的结构面） ────

    @Test
    fun `草案JSON回环一致_且没有值字面量通道`() {
        val d = draft(
            step(1, param = "单号", kind = "type"),
            step(2),
            params = listOf(DraftParam("单号", "step1.input")),
        )
        val raw = d.toJson().toString()
        val back = SkillDraft.fromJson(JSONObject(raw))!!
        assertEquals(back, d)
        assertTrue("schema 必须是 zl-draft/1", raw.contains("zl-draft/1"))
        assertFalseNoValueChannel(raw)
    }

    private fun assertFalseNoValueChannel(raw: String) {
        // 判据④的结构面：草案 JSON 里没有任何 value 键（观察值/字面量本来就不出页）
        org.junit.Assert.assertFalse(Regex(""""value"\s*:""").containsMatchIn(raw))
    }
}
