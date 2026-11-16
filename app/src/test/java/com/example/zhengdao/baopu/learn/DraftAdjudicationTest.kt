// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十二批 · 冲突步人裁的判据层：①有候选未裁 ⇒ 转正被拒且理由含「有冲突未裁」
// （负例：放行即红）；②裁 A ⇒ 可转正／裁 none ⇒ 回「重新教这一步」；④裁定前后
// DraftCheck 结论可断言变化；②的落盘三字段（resolved／时间／用户）与坏盘降级。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class DraftAdjudicationTest {

    private fun faces(name: String = "查询", xp: String = "/html/body/button[1]") = ObserveFaces(
        tag = "BUTTON", role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun plainStep(index: Int, name: String = "查询") = DraftStep(
        index = index, intent = name, kind = "click", url = "https://example.test/list",
        locator = faces(name = name, xp = "button[$index]"), param = null, optional = false,
        candidates = emptyList(), expect = listOf("urlChanged"),
        evidence = listOf("观察/会话-1.jsonl#seq$index"),
    )

    /** 冲突步：主位空、两套候选（SessionMerger.conflictStep 的形状）。 */
    private fun conflictStep(index: Int = 1) = DraftStep(
        index = index, intent = "查询", kind = "click", url = "https://example.test/list",
        locator = ObserveFaces(
            tag = "", role = "", name = "", aria = "", testid = "", id = "", inputName = "",
            placeholder = "", inputType = "", auto = "", formLabel = "", xp = "", cls = emptyList(),
        ),
        param = null, optional = false,
        candidates = listOf(faces(name = "查询", xp = "button[1]"), faces(name = "查货", xp = "button[2]")),
        expect = listOf("urlChanged"),
        evidence = listOf("观察/会话-1.jsonl#seq1"),
    )

    private fun draft(vararg steps: DraftStep) = SkillDraft(
        id = "d1", goal = "查装车", targetSite = "example.test", params = emptyList(),
        skeleton = steps.toList(), variableSteps = emptyList(), mergedFrom = 1,
        sourceSessions = listOf("1"),
    )

    private fun adj(step: Int, choice: DraftChoice, atMs: Long = 1_700_000_000_000L, by: String = DraftAdjudications.USER) =
        DraftAdjudication(step, choice, atMs, by)

    // ── 判据①：有候选未裁 ⇒ 转正被拒（负例：放行即红） ────────────────────────────

    @Test
    fun `判据1_有候选未裁_转正门判有冲突未裁_转换器同样拒`() {
        val d = draft(conflictStep())
        val check = SkillDraftRules.checkForPromotion(d, emptyList())
        assertTrue("负例：未裁必须被拒", check is DraftCheck.Invalid)
        val problems = (check as DraftCheck.Invalid).problems
        assertTrue(
            "理由必须点出「${SkillDraftRules.CONFLICT_UNRESOLVED}」：$problems",
            problems.any { it.contains(SkillDraftRules.CONFLICT_UNRESOLVED) },
        )
        val out = DraftConverter.toSkill(d, "查装车", emptyMap(), 1_000L)
        assertTrue("放行就是红：转换器不许产出技能", out is DraftConverter.Outcome.Refused)
        assertTrue(
            "拒收理由照转正门原话：${(out as DraftConverter.Outcome.Refused).reason}",
            out.reason.contains(SkillDraftRules.CONFLICT_UNRESOLVED),
        )
    }

    @Test
    fun `判据1_没有冲突的草案_转正门照常放行`() {
        val d = draft(plainStep(1), plainStep(2, name = "导出"))
        val check = SkillDraftRules.checkForPromotion(d, emptyList())
        assertTrue("无候选 ⇒ 无需裁定", check is DraftCheck.Ok)
        assertTrue(DraftConverter.toSkill(d, "查装车", emptyMap(), 1_000L) is DraftConverter.Outcome.Skill)
    }

    // ── 判据②：裁 A ⇒ 可转正；裁 none ⇒ 回「重新教这一步」 ──────────────────────────

    @Test
    fun `判据2_裁A可转正_且用的是第一套定位`() {
        val d = draft(conflictStep())
        val outcome = DraftConverter.toSkill(d, "查装车", emptyMap(), 1_000L, listOf(adj(1, DraftChoice.A)))
        val skill = (outcome as DraftConverter.Outcome.Skill).skill
        assertEquals("用的是候选[0]：查询", "查询", skill.steps.single().snapshot?.name)
        assertEquals("裁完不该再把候选带进技能", 1, skill.steps.size)
    }

    @Test
    fun `判据2_裁B可转正_且用的是第二套定位`() {
        val d = draft(conflictStep())
        val outcome = DraftConverter.toSkill(d, "查装车", emptyMap(), 1_000L, listOf(adj(1, DraftChoice.B)))
        val skill = (outcome as DraftConverter.Outcome.Skill).skill
        assertEquals("用的是候选[1]：查货", "查货", skill.steps.single().snapshot?.name)
    }

    @Test
    fun `判据2_裁none_回重新教这一步_不许转正`() {
        val d = draft(conflictStep())
        val check = SkillDraftRules.checkForPromotion(d, listOf(adj(1, DraftChoice.NONE)))
        assertTrue("裁 none 也拦", check is DraftCheck.Invalid)
        assertTrue(
            "理由必须点出「${SkillDraftRules.RETEACH}」",
            (check as DraftCheck.Invalid).problems.any { it.contains(SkillDraftRules.RETEACH) },
        )
        val out = DraftConverter.toSkill(d, "查装车", emptyMap(), 1_000L, listOf(adj(1, DraftChoice.NONE)))
        assertTrue(out is DraftConverter.Outcome.Refused)
        assertTrue((out as DraftConverter.Outcome.Refused).reason.contains(SkillDraftRules.RETEACH))
    }

    @Test
    fun `裁了别的步不算裁这一步`() {
        val d = draft(conflictStep(1), conflictStep(3))
        val check = SkillDraftRules.checkForPromotion(d, listOf(adj(1, DraftChoice.A)))
        assertTrue("第 3 步仍未裁", check is DraftCheck.Invalid)
        assertTrue((check as DraftCheck.Invalid).problems.any { it.contains("第 3 步") })
    }

    // ── 判据④：裁定前后结论可断言变化（同一个函数） ────────────────────────────────

    @Test
    fun `判据4_裁定前后_DraftCheck结论变化`() {
        val d = draft(conflictStep())
        val before = SkillDraftRules.checkForPromotion(d, emptyList())
        assertTrue("裁定前：有冲突未裁", before is DraftCheck.Invalid)
        val after = SkillDraftRules.checkForPromotion(d, listOf(adj(1, DraftChoice.A)))
        assertTrue("裁定后：放行", after is DraftCheck.Ok)
        assertNotEquals("两次结论必须不同（判据④的本体）", before::class, after::class)
    }

    // ── 判据②的落盘：resolved＋时间＋用户；坏盘按未裁算 ────────────────────────────

    @Test
    fun `落盘三字段齐_重读一致_同一步后裁的胜`() {
        TempSandbox.use("zd672-adj-store") { tmp ->
            val store = DraftAdjudicationStore(File(tmp, SkillDraftStore.DIR_NAME))
            assertTrue(store.put("d1", adj(3, DraftChoice.B, atMs = 1_700_000_123_456L, by = "用户")))
            assertTrue("同一步再裁：覆盖不新增", store.put("d1", adj(3, DraftChoice.A, atMs = 1_700_000_999_000L)))
            val loaded = store.load("d1")
            assertEquals("同一步只留一条", 1, loaded.size)
            assertEquals("resolved 落盘可读", DraftChoice.A, loaded.single().resolved)
            assertEquals("时间落盘可读", 1_700_000_999_000L, loaded.single().atMs)
            assertEquals("用户落盘可读", "用户", loaded.single().by)
            val text = File(tmp, SkillDraftStore.DIR_NAME + "/" + DraftAdjudicationStore.fileNameFor("d1")).readText()
            assertTrue("盘上字面量就是 resolved:A：$text", text.contains("\"resolved\":\"A\""))
            assertEquals("all() 按草案 id 归档", 1, store.all().size)
            store.clear("d1")
            assertEquals("弃草案时裁决一起清", emptyList<DraftAdjudication>(), store.load("d1"))
        }
    }

    @Test
    fun `坏盘与坏字段_按未裁算而不按已裁算`() {
        TempSandbox.use("zd672-adj-broken") { tmp ->
            val dir = File(tmp, SkillDraftStore.DIR_NAME)
            dir.mkdirs()
            File(dir, DraftAdjudicationStore.fileNameFor("bad")).writeText("{不是 json", Charsets.UTF_8)
            File(dir, DraftAdjudicationStore.fileNameFor("old")).writeText(
                "{\"schema\":\"zl-adj/0\",\"steps\":[{\"i\":1,\"resolved\":\"A\",\"t\":1,\"by\":\"用户\"}]}",
                Charsets.UTF_8,
            )
            val store = DraftAdjudicationStore(dir)
            assertEquals("坏 json ⇒ 空（未裁）", emptyList<DraftAdjudication>(), store.load("bad"))
            assertEquals("旧 schema ⇒ 空（未裁）", emptyList<DraftAdjudication>(), store.load("old"))
            val d = draft(conflictStep())
            assertTrue("按未裁算 ⇒ 照拦", SkillDraftRules.checkForPromotion(d, store.load("bad")) is DraftCheck.Invalid)
            assertNull("没有主体的裁定整条不认", DraftAdjudication.fromJson(
                org.json.JSONObject().put("i", 1).put("resolved", "A").put("t", 1).put("by", ""),
            ))
        }
    }

    // ── 坏形：候选不足两套 ──────────────────────────────────────────────────────

    @Test
    fun `候选只有一套_拦住并点出坏形_不崩`() {
        val broken = conflictStep().copy(candidates = listOf(faces(name = "查询")))
        val d = draft(broken)
        val applied = DraftAdjudications.apply(d, listOf(adj(1, DraftChoice.B)))
        assertTrue(applied is DraftAdjudications.Applied.Blocked)
        assertTrue(
            "点出坏形与步号：${(applied as DraftAdjudications.Applied.Blocked).problems}",
            applied.problems.any { it.contains("第 1 步") && it.contains("坏形") },
        )
        assertTrue(DraftConverter.toSkill(d, "查装车", emptyMap(), 1_000L, listOf(adj(1, DraftChoice.B))) is DraftConverter.Outcome.Refused)
    }

    @Test
    fun `待裁清单_裁完即空`() {
        val d = draft(conflictStep())
        assertEquals("未裁 ⇒ 一步待人裁", 1, DraftAdjudications.pending(d, emptyList()).size)
        assertTrue("裁完 ⇒ 空", DraftAdjudications.pending(d, listOf(adj(1, DraftChoice.B))).isEmpty())
        assertEquals("草案本体一个字不动（仍留着两套）", 1, d.unresolvedSteps(emptyList()).size)
        assertFalse("裁不改草案：candidates 还在", d.skeleton.single().candidates.isEmpty())
    }
}
