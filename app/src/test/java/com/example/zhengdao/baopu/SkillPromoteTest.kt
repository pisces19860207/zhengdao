// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零七批 §1.5 · 转正双路与回退的判据（种子与所有草稿技能通用）：
//   手动 ⇒ 索引标记变化＋决策中心一条「手动」；
//   满 3 次 ⇒ 无弹卡、索引自动变正式＋决策中心一条「满3次」；
//   决策中心回退 ⇒ 回草稿、索引标记复原、再留一条回退记录；再回退要被拒。
// 负例（各红）：满 3 次转正但决策中心没记录／转正后回退不了／回退后索引没复原。
package com.example.zhengdao.baopu

import com.example.zhengdao.ui.baopu.SkillDraftRow
import com.example.zhengdao.ui.baopu.commitSkillDraft
import com.example.zhengdao.ui.baopu.draftSkillRows
import com.example.zhengdao.ui.baopu.promoteBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SkillPromoteTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd207p-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd207p-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun makeGlobalDraft(name: String, uses: Int): File {
        val f = File(File(SkillRules.globalSkillDir(globalRoot), name), SkillRules.SKILL_FILE)
        f.parentFile?.mkdirs()
        f.writeText(
            SkillStore.draftHeader("draft", uses) + "\n# $name 的流程\n1. 第一步。",
            Charsets.UTF_8,
        )
        return f
    }

    private fun globalAudit(): String {
        val f = SkillAudit.fileIn(SkillRules.globalSkillDir(globalRoot))
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    private fun bIndexHasDraftMark(name: String): Boolean =
        SkillRules.parseIndexLines(pf().read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL)
            .firstOrNull { it.name == name }
            ?.scenario?.contains("草稿·未验证") == true

    // ── 纯判据 ───────────────────────────────────────────────────────────

    @Test
    fun `P1_转正归因_次数没动是手动_次数加了是满3次_其余不算转正`() {
        val d2 = SkillStore.draftHeader("draft", 2)
        assertEquals("手动", SkillPromote.promoteReasonOf("$d2\n正文", SkillStore.draftHeader("active", 2) + "\n正文"))
        assertEquals("满3次", SkillPromote.promoteReasonOf("$d2\n正文", SkillStore.draftHeader("active", 3) + "\n正文"))
        assertNull("草稿→草稿不算", SkillPromote.promoteReasonOf("$d2\n正文", "$d2\n正文"))
        assertNull("正式→草稿（回退）不算", SkillPromote.promoteReasonOf(SkillStore.draftHeader("active", 3) + "\n正文", "$d2\n正文"))
        assertNull("正式→正式不算", SkillPromote.promoteReasonOf(SkillStore.draftHeader("active", 1) + "\n正文", SkillStore.draftHeader("active", 1) + "\n正文"))
    }

    @Test
    fun `P2_回退正文_头回draft_uses保留_非正式返回null`() {
        val active = SkillStore.draftHeader("active", 3) + "\n# 标题\n1. 第一步。"
        val back = SkillPromote.revertBody(active)!!
        assertEquals("draft", SkillStore.statusOf(back))
        assertEquals(3, SkillStore.usesOf(back))
        assertTrue("正文一字不动", back.contains("# 标题\n1. 第一步。"))
        assertNull("草稿没有可回退的", SkillPromote.revertBody(SkillStore.draftHeader("draft", 3) + "\nx"))
    }

    @Test
    fun `P3_账本回放_解析与最新一条_乱序也按ts取最大`() {
        val f = makeGlobalDraft("微头条流程", 3)
        assertTrue(SkillPromote.auditPromote(f, "global", "微头条流程", SkillPromote.REASON_MANUAL, nowMs = 100))
        assertTrue(SkillPromote.auditRevert(f, "global", "微头条流程", nowMs = 200))
        assertTrue(SkillPromote.auditPromote(f, "global", "微头条流程", SkillPromote.REASON_FULL_USES, nowMs = 300))

        val lines = SkillPromote.parseLines(globalAudit())
        assertEquals(3, lines.size)
        assertEquals("手动", lines[0].reason)
        // 种子名＋全局 ⇒ 记录带 source=seed（决策中心「来源」那一栏）
        assertEquals("seed", lines[0].source)
        assertEquals(SkillPromote.ACTION_REVERT, lines[1].action)

        // 乱序喂入也认得出最新（ts 最大）是第二条转正 ⇒ 它才是可回退的那条
        val shuffled = listOf(lines[1], lines[2], lines[0])
        val latest = SkillPromote.latestPerName(shuffled)["微头条流程"]!!
        assertEquals(300L, latest.ts)
        assertEquals(SkillPromote.REASON_FULL_USES, latest.reason)

        // 非转正/回退的审计行不进记录（write/update/seed 等杂行）
        assertEquals(emptyList<SkillPromote.PromoteLine>(), SkillPromote.parseLines("""{"action":"write","name":"x","ts":1}"""))
    }

    // ── 端到端：手动转正 → 决策中心有记录 → 一键回退 → 索引复原 ───────────

    @Test
    fun `P4_手动转正留痕_回退复原索引标记_再回退被拒`() {
        val f = makeGlobalDraft("T9取数", 3)
        val row = draftSkillRows(projectRoot, globalRoot).single()
        assertEquals("T9取数", row.name)

        // ① 手动转正（设置页「转正」按钮那条路＝commitSkillDraft）
        val r = commitSkillDraft(
            row = row, newBody = promoteBody(row.body), stillDraft = false,
            projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = pf(), backupStamp = "s1",
        )
        assertTrue("转正写盘要成：$r", r is SkillWriteResult.Written)
        assertEquals("active", SkillStore.statusOf(f.readText(Charsets.UTF_8)))
        assertFalse("索引标记要变（未验证标消失）", bIndexHasDraftMark("T9取数"))
        assertTrue(
            "决策中心要有一条「手动」（负例：转正无记录）",
            SkillPromote.parseLines(globalAudit()).any { it.name == "T9取数" && it.reason == SkillPromote.REASON_MANUAL },
        )

        // ② 决策中心一键回退：正式 → 草稿，索引标记复原，再留一条回退记录
        val body = f.readText(Charsets.UTF_8)
        val back = SkillPromote.revertBody(body)!!
        val r2 = commitSkillDraft(
            row = SkillDraftRow("T9取数", SkillScope.GLOBAL, f, body, SkillStore.usesOf(body), canPromote = false),
            newBody = back, stillDraft = true,
            projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = pf(), backupStamp = "s2",
            reason = "决策中心回退为草稿",
        )
        assertTrue("回退写盘要成：$r2", r2 is SkillWriteResult.Written)
        // 控制器在回退写盘成功后补的回退记录（决策中心 ⑤ 数据源）
        assertTrue(SkillPromote.auditRevert(f, "global", "T9取数", System.currentTimeMillis()))
        val after = f.readText(Charsets.UTF_8)
        assertEquals("回到草稿（负例：转正后回退不了）", "draft", SkillStore.statusOf(after))
        assertEquals("次数保留", 3, SkillStore.usesOf(after))
        assertTrue("索引标记要复原（负例：回退后索引没复原）", bIndexHasDraftMark("T9取数"))
        val lines = SkillPromote.parseLines(globalAudit())
        assertTrue("要再留一条回退记录", lines.any { it.name == "T9取数" && it.action == SkillPromote.ACTION_REVERT })
        val latest = SkillPromote.latestPerName(lines)["T9取数"]!!
        assertEquals("最新一条是回退 ⇒ 决策中心那条不再可撤销", SkillPromote.ACTION_REVERT, latest.action)

        // ③ 已是草稿 ⇒ 没有可回退的（不许把回退当开关来回拨正文之外的状态）
        assertNull("二次回退被拒", SkillPromote.revertBody(after))
    }

    @Test
    fun `P5_用满3次自动转正_不弹卡_决策中心留满3次`() {
        val f = makeGlobalDraft("产物清理", 0)
        repeat(3) { i ->
            val row = draftSkillRows(projectRoot, globalRoot).single()
            // 镜像设置页「用一次」：数次数与转正出自同一份 afterUse；这里不构造任何确认卡
            val after = SkillStore.afterUse(row.body)
            val r = commitSkillDraft(
                row = row, newBody = after.body, stillDraft = !after.promoted,
                projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = pf(), backupStamp = "u$i",
            )
            assertTrue("第 ${i + 1} 次使用要写成功：$r", r is SkillWriteResult.Written)
            if (i < 2) {
                assertTrue("前两次还是草稿", SkillStore.statusOf(f.readText(Charsets.UTF_8)) == "draft")
                assertTrue("索引仍带未验证标", bIndexHasDraftMark("产物清理"))
            }
        }
        assertEquals("第 3 次当场转正（无弹卡，同一个写口）", "active", SkillStore.statusOf(f.readText(Charsets.UTF_8)))
        assertFalse("索引自动变正式", bIndexHasDraftMark("产物清理"))
        val line = SkillPromote.parseLines(globalAudit()).single()
        assertEquals("决策中心留的是「满3次」（负例：满 3 次转正但没记录）", SkillPromote.REASON_FULL_USES, line.reason)
        assertEquals("seed", line.source)
    }

    // ── 模型侧（write_skill）同口径：use 第 3 次自动转正＋留痕 ────────────

    @Test
    fun `P6_write_skill用满3次当场转正_审计留满3次`() {
        makeProjectDraft("tool-flow", 2)
        val spec = com.example.zhengdao.baopu.agent.SkillTools.spec(
            workspaceRoot = { projectRoot },
            personaFiles = { pf() },
        )
        val out = spec.executor(org.json.JSONObject("""{"name":"tool-flow","action":"use"}"""))
        assertTrue("第 3 次使用要成功：$out", out is com.example.zhengdao.baopu.agent.ToolOutcome.Ok)
        assertTrue("话术要说明自动转正", (out as com.example.zhengdao.baopu.agent.ToolOutcome.Ok).text.contains("自动转正"))

        val f = File(SkillRules.projectSkillDir(projectRoot), "tool-flow/${SkillRules.SKILL_FILE}")
        assertEquals("当场转正", "active", SkillStore.statusOf(f.readText(Charsets.UTF_8)))
        assertEquals(3, SkillStore.usesOf(f.readText(Charsets.UTF_8)))
        val audit = SkillAudit.fileIn(SkillRules.projectSkillDir(projectRoot)).readText(Charsets.UTF_8)
        assertTrue(
            "审计要有一条满3次的转正行",
            SkillPromote.parseLines(audit).any { it.name == "tool-flow" && it.reason == SkillPromote.REASON_FULL_USES },
        )
    }

    private fun makeProjectDraft(name: String, uses: Int) {
        val f = File(File(SkillRules.projectSkillDir(projectRoot), name), SkillRules.SKILL_FILE)
        f.parentFile?.mkdirs()
        f.writeText(SkillStore.draftHeader("draft", uses) + "\n# $name\n1. 步骤。", Charsets.UTF_8)
    }
}
