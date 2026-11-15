// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段二）· 多次归并的判据层测试：判据③（两次观察合并成一条并标 N）、
// 判据④（变化字段成为参数——断言字面量已拔掉）、判据⑤（冲突两套并存；负例：自动选 ⇒ 红）。
package com.example.zhengdao.baopu.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionMergerTest {

    private fun faces(
        tag: String = "BUTTON",
        name: String = "查询",
        xp: String = "/html/body/button[1]",
        placeholder: String = "",
    ) = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = placeholder, inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun step(
        index: Int,
        kind: String = "click",
        locator: ObserveFaces = faces(),
        param: String? = null,
        optional: Boolean = false,
        evidence: String = "观察/会话-1.jsonl#seq$index",
    ) = DraftStep(
        index = index, intent = "查询", kind = kind, url = "https://example.test/list",
        locator = locator, param = param, optional = optional, candidates = emptyList(),
        expect = listOf("urlChanged"), evidence = listOf(evidence),
    )

    private fun draft(steps: List<DraftStep>, goal: String = "查装车", sessions: Int = 1) = SkillDraft(
        id = "d$sessions", goal = goal, targetSite = "example.test",
        params = steps.filter { it.param != null }.map { DraftParam(it.param!!, "step${it.index}.input") },
        skeleton = steps, variableSteps = steps.filter { it.param != null }.map { it.index },
        mergedFrom = sessions, sourceSessions = (1..sessions).map { "s$it" },
    )

    // ── 判据③：两次观察合并成一条并标 N ──────────────────────────────────────────

    @Test
    fun `判据3_两次观察合并成一条_标N`() {
        val a = draft(listOf(step(1), step(2, kind = "type", locator = faces(tag = "INPUT", name = "单号", xp = "input[1]"), param = "单号")), sessions = 1)
        val b = draft(listOf(step(1), step(2, kind = "type", locator = faces(tag = "INPUT", name = "单号", xp = "input[1]"), param = "单号")), sessions = 1)
        val m = SessionMerger.merge(a, b, "m1")
        assertTrue(m is MergeOutcome.Merged)
        val merged = (m as MergeOutcome.Merged).draft
        assertEquals("标 N 次观察归并（两次）", 2, merged.mergedFrom)
        assertEquals("来源会话并集", 2, merged.sourceSessions.size)
    }

    @Test
    fun `固定步合并_骨架不重复`() {
        val a = draft(listOf(step(1, locator = faces(name = "打开列表", xp = "nav[1]"))))
        val b = draft(listOf(step(1, locator = faces(name = "打开列表", xp = "nav[1]"))))
        val merged = (SessionMerger.merge(a, b, "m1") as MergeOutcome.Merged).draft
        assertEquals("同位固定步只留一条", 1, merged.skeleton.size)
        assertEquals(1, merged.skeleton.count { it.locator.name == "打开列表" })
    }

    @Test
    fun `偶发步降级为可选`() {
        val a = draft(listOf(step(1), step(2, locator = faces(name = "筛选", xp = "filter[1]"))))
        val b = draft(listOf(step(1)))
        val merged = (SessionMerger.merge(a, b, "m1") as MergeOutcome.Merged).draft
        val occasional = merged.skeleton.filter { it.locator.name == "筛选" }
        assertEquals(1, occasional.size)
        assertTrue("只在一侧出现的步降级为可选", occasional.single().optional)
    }

    // ── 判据④：变化字段成为参数（字面量拔掉） ────────────────────────────────────

    @Test
    fun `判据4_输入步归并后成为参数_字面量已拔掉`() {
        // 第二次换了单号：值形状不同（digits→date）——但值本身从未出页，草案里更没有
        val a = draft(listOf(step(1, kind = "type", locator = faces(tag = "INPUT", name = "单号", xp = "input[1]"), param = "单号")))
        val b = draft(listOf(step(1, kind = "type", locator = faces(tag = "INPUT", name = "单号", xp = "input[1]"), param = "单号")))
        val merged = (SessionMerger.merge(a, b, "m1") as MergeOutcome.Merged).draft
        val inputStep = merged.skeleton.single()
        assertTrue("变化字段成为参数", inputStep.param != null)
        assertEquals(listOf("单号"), merged.params.map { it.name })
        assertTrue("variableSteps 含参数步", inputStep.index in merged.variableSteps)
        // 断言字面量已拔掉：草案 JSON 没有 value 键（判据④的结构面）
        val raw = merged.toJson().toString()
        assertFalse(Regex(""""value"\s*:""").containsMatchIn(raw))
    }

    @Test
    fun `没有参数名的输入步_归并时按身份面自动命名`() {
        val a = draft(listOf(step(1, kind = "type", locator = faces(tag = "INPUT", placeholder = "请输入单号", xp = "input[1]"))))
        val b = draft(listOf(step(1, kind = "type", locator = faces(tag = "INPUT", placeholder = "请输入单号", xp = "input[1]"))))
        val merged = (SessionMerger.merge(a, b, "m1") as MergeOutcome.Merged).draft
        assertTrue(merged.skeleton.single().param != null)
        assertTrue(merged.params.isNotEmpty())
    }

    // ── 判据⑤：冲突两套并存（负例：自动选 ⇒ 红） ─────────────────────────────────

    @Test
    fun `判据5_两次同类操作定位不同_两套候选并存`() {
        val a = draft(listOf(step(1, locator = faces(name = "查货", xp = "button[1]"))))
        val b = draft(listOf(step(1, locator = faces(name = "查询", xp = "button[2]"))))
        val merged = (SessionMerger.merge(a, b, "m1") as MergeOutcome.Merged).draft
        val conflicted = merged.skeleton.single()
        assertEquals("两套候选并存", 2, conflicted.candidates.size)
        assertNull("主位必须留空＝没选定（自动选就是负例）", conflicted.locator.name.takeIf { it.isNotBlank() })
        assertNull(conflicted.locator.xp.takeIf { it.isNotBlank() })
        assertTrue("草案整体带冲突标", merged.hasConflict())
    }

    @Test
    fun `负例_归并结果若擅自选了一套走法_本判据必红`() {
        // 变异验证：把 conflictStep 改成「直接采用第一条」⇒ 本判据的判别函数必须抓住
        fun discriminator(merged: SkillDraft): Boolean = merged.skeleton
            .all { it.candidates.isEmpty() && it.locator.name.isNotBlank() }
        val a = draft(listOf(step(1, locator = faces(name = "查货", xp = "button[1]"))))
        val b = draft(listOf(step(1, locator = faces(name = "查询", xp = "button[2]"))))
        val merged = (SessionMerger.merge(a, b, "m1") as MergeOutcome.Merged).draft
        assertFalse("真实现不许通过判别（两套并存）", discriminator(merged))
        assertTrue("变异（擅自选 A）被抓住", discriminator(merged.copy(skeleton = listOf(step(1, locator = faces(name = "查货", xp = "button[1]"))))))
    }

    // ── 同意图判定 ───────────────────────────────────────────────────────────────

    @Test
    fun `goal不同_不合（把两次当两条技能或硬合都是错）`() {
        val a = draft(listOf(step(1)), goal = "查装车")
        val b = draft(listOf(step(1)), goal = "查对账")
        val m = SessionMerger.merge(a, b, "m1")
        assertTrue(m is MergeOutcome.NotSameIntent)
    }

    @Test
    fun `goal空白差异归一化后相等_照合`() {
        val a = draft(listOf(step(1)), goal = "查 装车")
        val b = draft(listOf(step(1)), goal = "查装车")
        assertTrue(SessionMerger.merge(a, b, "m1") is MergeOutcome.Merged)
    }
}
