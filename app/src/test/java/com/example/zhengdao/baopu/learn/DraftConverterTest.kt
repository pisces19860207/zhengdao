// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 ⑥（阶段二）· 判据⑥：阶段二产物能进 643 的候选入口——
// 草案经 DraftConverter 落成 zl-teach/2 后，643 的 ScriptEvoRules 原样能吃：
// 版本可算（sha256 前 12）／校验点可数／评估能走到 Ready（五字段齐：id/版本/成功率/步数/校验点）。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.baopu.AcceptanceSample
import com.example.zhengdao.baopu.evolution.script.ScriptCandidacy
import com.example.zhengdao.baopu.evolution.script.ScriptEvoRules
import com.example.zhengdao.baopu.TeachSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class DraftConverterTest {

    private fun faces(name: String = "查询", xp: String = "/html/body/button[1]", tag: String = "BUTTON") = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun step(
        index: Int,
        name: String = "查询",
        kind: String = "click",
        param: String? = null,
        xp: String = "button[$index]",
        expect: List<String> = listOf("urlChanged"),
    ) = DraftStep(
        index = index, intent = name, kind = kind, url = "https://example.test/list",
        locator = faces(name = name, xp = xp, tag = if (kind == "type") "INPUT" else "BUTTON"),
        param = param, optional = false, candidates = emptyList(),
        expect = expect, evidence = listOf("观察/会话-1.jsonl#seq$index"),
    )

    private fun draft(steps: List<DraftStep>) = SkillDraft(
        id = "d1", goal = "查装车", targetSite = "example.test",
        params = steps.filter { it.param != null }.map { DraftParam(it.param!!, "step${it.index}.input") },
        skeleton = steps, variableSteps = steps.filter { it.param != null }.map { it.index },
        mergedFrom = 2, sourceSessions = listOf("s1", "s2"),
    )

    /** 判据⑥的主证据链：草案 → 技能 → 643 评估走到 Ready，五字段齐。 */
    @Test
    fun `判据6_草案落成技能后_643候选入口五字段齐`() {
        val converted = DraftConverter.toSkill(
            draft(listOf(step(1), step(2, kind = "type", param = "单号", name = "单号", xp = "input[1]"))),
            name = "查装车",
            paramDefaults = mapOf("单号" to "T9-0921"),
            nowMs = 1_000L,
        )
        val skill = (converted as DraftConverter.Outcome.Skill).skill
        assertEquals("zl-teach/2 形状", TeachSkill.SCHEMA, "zl-teach/2")
        val version = ScriptEvoRules.versionOf(skill)
        assertTrue("版本＝sha256 前 12", version.length == 12)
        val checkpoints = ScriptEvoRules.checkpointsOf(skill)
        assertEquals("校验点数＝各步 expect 之和", skill.steps.sumOf { it.expect.size }, checkpoints)
        // 643 评估：3 次同版本成功 ⇒ Ready（五字段都在 ScriptCandidate 上）
        val samples = (1..3).map {
            AcceptanceSample(opId = "op$it", atMs = it.toLong(), ok = true, fingerprintOk = true, driftCount = 0, site = "example.test", version = version)
        }
        val verdict = ScriptEvoRules.evaluate(skill, samples, version)
        assertTrue("643 入口接受阶段二产物", verdict is ScriptCandidacy.Ready)
        val c = (verdict as ScriptCandidacy.Ready).candidate
        assertEquals("script:查装车@$version", c.id)
        assertEquals(3, c.okReplays)
        assertEquals(3, c.totalReplays)
        assertEquals(2, c.steps)
        assertEquals(checkpoints, c.checkpoints)
        assertTrue(c.line().contains("2 步"))
    }

    @Test
    fun `判据4_参数步只有占位符_真值只在参数默认值里`() {
        val converted = DraftConverter.toSkill(
            draft(listOf(step(1, kind = "type", param = "单号", name = "单号", xp = "input[1]"))),
            name = "查装车",
            paramDefaults = mapOf("单号" to "T9-0921"),
            nowMs = 1_000L,
        )
        val skill = (converted as DraftConverter.Outcome.Skill).skill
        val typeStep = skill.steps.single()
        assertEquals("步骤里只有 \${name} 占位（v2 口径）", "\${单号}", typeStep.value)
        assertEquals("参数默认值＝人批确认时填的（不在草案里）", "T9-0921", skill.params.single().default)
    }

    @Test
    fun `冲突草案不转_不擅自选`() {
        val conflicted = draft(listOf(step(1, name = "").copy(candidates = listOf(faces(name = "查货", xp = "button[1]"), faces(name = "查询", xp = "button[2]")))))
        val outcome = DraftConverter.toSkill(conflicted, name = "查装车", paramDefaults = emptyMap(), nowMs = 1_000L)
        assertTrue(outcome is DraftConverter.Outcome.Refused)
        assertTrue((outcome as DraftConverter.Outcome.Refused).reason.contains("冲突"))
    }

    @Test
    fun `定位不足两条不转_如实说缺几档`() {
        val thin = faces(name = "", xp = "", tag = "BUTTON").copy(role = "button") // 只有 role 一档 ⇒ 0 条定位器
        val outcome = DraftConverter.toSkill(draft(listOf(step(1, name = "").copy(locator = thin))), name = "查装车", paramDefaults = emptyMap(), nowMs = 1_000L)
        assertTrue(outcome is DraftConverter.Outcome.Refused)
        assertTrue((outcome as DraftConverter.Outcome.Refused).reason.contains("定位不足两条"))
    }

    @Test
    fun `两套候选之外的正常草案_能转出合法技能`() {
        val converted = DraftConverter.toSkill(draft(listOf(step(1), step(2, kind = "type", param = "单号", name = "单号", xp = "input[1]"))), name = "查装车", paramDefaults = emptyMap(), nowMs = 1_000L)
        val skill = (converted as DraftConverter.Outcome.Skill).skill
        assertEquals(2, skill.steps.size)
        assertEquals("example.test", skill.targetSite)
        assertTrue(skill.steps.all { it.locators.size >= 2 })
    }
}
