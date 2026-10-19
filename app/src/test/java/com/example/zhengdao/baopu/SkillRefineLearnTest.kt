// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第三十七批 B 段（用户 2026-10-19 口径：收尾才出卡、只出卡不落盘、
// 点采纳才写、拒绝一字不动、同一次收尾不重复出卡、被保护闸拦下的不算学到）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「这次学到」提案卡的判据层单测（第三十七批 B 段，L3/L4）。
 *
 * 钉五件事：
 * 1. **没信号不出卡**（不许每轮都弹）；
 * 2. 每条都有**内容／来源／建议**三件套，最多 3 条；
 * 3. 用户明说的排最前；已有同名技能 ⇒ 建议「转正式」而不是再建一份草稿；
 * 4. **同一次收尾只出一次**（`shouldEmit(alreadyShown=true)` ⇒ false）；
 * 5. **落盘只发生在采纳路径上**：`SkillStore.plan` → `apply` 有备份、进索引；不调就没有文件。
 */
class SkillRefineLearnTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sig(kind: SkillRefine.SignalKind, detail: String) = SkillRefine.LearnSignal(kind, detail)

    // ── ① 出不出卡 ────────────────────────────────────────────────────

    @Test
    fun `没信号_不出卡`() {
        assertFalse(SkillRefine.shouldEmit(emptyList(), alreadyShown = false))
        assertTrue(SkillRefine.learnProposals(emptyList()).isEmpty())
    }

    @Test
    fun `有信号_出卡且每条三件套齐全`() {
        val out = SkillRefine.learnProposals(listOf(sig(SkillRefine.SignalKind.FILE, "notes/随手记.md")))
        assertEquals(1, out.size)
        val p = out.first()
        assertTrue("内容不能为空", p.content.isNotBlank())
        assertTrue("来源不能为空", p.source.isNotBlank())
        assertTrue("id 要稳定（采纳时按 id 找回）", p.id.isNotBlank())
    }

    @Test
    fun `同一次收尾_只出一次`() {
        val signals = listOf(sig(SkillRefine.SignalKind.FILE, "a.md"))
        assertTrue(SkillRefine.shouldEmit(signals, alreadyShown = false))
        assertFalse("已经出过一次就不许再出", SkillRefine.shouldEmit(signals, alreadyShown = true))
    }

    // ── ② 排序、条数与建议 ─────────────────────────────────────────────

    @Test
    fun `最多三条_多出来的不进卡`() {
        val signals = (1..5).map { sig(SkillRefine.SignalKind.FILE, "f$it.md") }
        assertEquals(3, SkillRefine.learnProposals(signals).size)
    }

    @Test
    fun `用户明说的_排最前`() {
        val signals = listOf(
            sig(SkillRefine.SignalKind.FILE, "a.md"),
            sig(SkillRefine.SignalKind.USER_SAID, "记下来这个流程"),
        )
        val out = SkillRefine.learnProposals(signals)
        assertTrue("你说的原话排第一", out.first().fromUser)
        assertTrue(out.first().source.contains("你说的原话"))
    }

    @Test
    fun `已有同名技能_建议转正式而不是再建草稿`() {
        val signals = listOf(sig(SkillRefine.SignalKind.FILE, "流程.md"))
        val fresh = SkillRefine.learnProposals(signals, existingNames = emptySet())
        val existing = SkillRefine.learnProposals(signals, existingNames = setOf(fresh.first().id.substringAfter('-')))
        assertEquals(SkillRefine.Suggest.DRAFT, fresh.first().suggest)
        assertEquals(SkillRefine.Suggest.FORMAL, existing.first().suggest)
    }

    @Test
    fun `你的原话_能提出USER_SAID信号`() {
        assertTrue(SkillRefine.learnMarkersIn("这个流程记住").isNotEmpty())
        assertTrue(SkillRefine.learnMarkersIn("以后都这样做").isNotEmpty())
        assertTrue("没说就不该有", SkillRefine.learnMarkersIn("今天天气不错").isEmpty())
    }

    // ── ③ 工具名 ⇒ 信号（被闸拦下的走 Rejected 分支，根本不会调这里）──────

    @Test
    fun `工具名映射_写类与浏览器类才算学到`() {
        assertEquals(SkillRefine.SignalKind.FILE, SkillRefine.signalKindFor("write_file"))
        assertEquals(SkillRefine.SignalKind.FILE, SkillRefine.signalKindFor("edit_file"))
        assertEquals(SkillRefine.SignalKind.FILE, SkillRefine.signalKindFor("move_path"))
        assertEquals(SkillRefine.SignalKind.FILE, SkillRefine.signalKindFor("delete_path"))
        assertEquals(SkillRefine.SignalKind.BROWSER, SkillRefine.signalKindFor("browser_snapshot"))
        assertEquals(SkillRefine.SignalKind.SKILL, SkillRefine.signalKindFor("write_skill"))
        assertEquals(null, SkillRefine.signalKindFor("read_file"))
        assertEquals(null, SkillRefine.signalKindFor("list_dir"))
    }

    // ── ④ 落盘只发生在采纳路径上 ────────────────────────────────────────

    private fun roots(): Pair<File, File> {
        val project = tmp.newFolder("workspace")
        val global = tmp.newFolder("global")
        return project to global
    }

    private fun writeSkill(project: File, global: File, name: String, stamp: String): SkillWriteResult {
        val pf = PersonaFiles(global, project)
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = name,
            body = "# 技能：$name\n\n## 场景\n随手记\n",
            reason = "「这次学到」卡片采纳",
            draft = true,
        )
        val plan = SkillStore.plan(proposal, project, global, stamp)
        plan.blocked?.let { return SkillWriteResult.Rejected(it) }
        return SkillStore.apply(plan, pf, pf.read(PersonaLayer.C_PROJECT).orEmpty())
    }

    @Test
    fun `采纳_走plan与apply落盘且索引更新`() {
        val (project, global) = roots()
        val r = writeSkill(project, global, "随手记", "20261019-010000")
        assertTrue("落盘应当成功：$r", r is SkillWriteResult.Written)
        val written = r as SkillWriteResult.Written
        assertTrue(written.file.isFile)
        assertTrue("要进索引", written.indexUpdated)
    }

    @Test
    fun `第二次写同名_有备份`() {
        val (project, global) = roots()
        writeSkill(project, global, "随手记", "20261019-010000")
        val r2 = writeSkill(project, global, "随手记", "20261019-020000")
        assertTrue("第二次也该成功：$r2", r2 is SkillWriteResult.Written)
        assertTrue("改旧版必须先备份：${(r2 as SkillWriteResult.Written).backup}", r2.backup != null)
    }
}
