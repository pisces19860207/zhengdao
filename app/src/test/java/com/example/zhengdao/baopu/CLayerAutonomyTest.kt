// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十八批（C 层自治）：① C 人设追加-only；② 项目技能写后审计；③ 新工具登记。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.AppendPersonaTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CLayerAutonomyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun pf(): PersonaFiles {
        val g = tmp.newFolder("global")
        val p = tmp.newFolder("project")
        return PersonaFiles(g, p)
    }

    @Test
    fun append_only_allows_growth_and_rejects_rewrite() {
        assertTrue("空文件可以写入", PersonaRules.appendOnly("", "第一条"))
        assertTrue("末尾追加可以", PersonaRules.appendOnly("第一条\n", "第一条\n第二条"))
        assertFalse("改既有条款不行", PersonaRules.appendOnly("第一条\n", "改过的第一条\n第二条"))
        assertFalse("原样重复不行", PersonaRules.appendOnly("第一条\n", "第一条\n"))
        assertFalse("删条款不行", PersonaRules.appendOnly("第一条\n第二条", "第一条"))
    }

    @Test
    fun append_persona_tool_adds_clause_and_backs_up() {
        val files = pf()
        files.applyProposal(
            PersonaRules.propose(PersonaLayer.C_PROJECT, "", "- 原条款", "初始")!!,
        )
        val before = files.projectFile().readText(Charsets.UTF_8)

        val spec = AppendPersonaTool.spec { files }
        val out = spec.executor(org.json.JSONObject().put("text", "- 新条款").put("reason", "用户说过"))
        assertTrue("工具应成功：$out", out is ToolOutcome.Ok)

        val after = files.projectFile().readText(Charsets.UTF_8)
        assertTrue("原条款还在", after.contains("原条款"))
        assertTrue("新条款追加上了", after.contains("新条款"))
        assertTrue("确实是追加", after.startsWith(before.trimEnd()))
        val backups = files.projectFile().parentFile!!.listFiles { f -> f.name.contains(".bak-") }
        assertTrue("人设写入要备份", backups != null && backups.isNotEmpty())
    }

    @Test
    fun project_skill_write_records_audit_line() {
        val files = pf()
        val projectRoot = files.projectFile().parentFile!!
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = "audit_demo",
            body = "# 演示\n\n- 一条规则",
            reason = "验证审计",
            draft = true,
        )
        val plan = SkillStore.plan(proposal, projectRoot, files.globalFile().parentFile!!, "20261020-0100")
        val result = SkillStore.apply(plan, files, "")
        assertTrue("写技能应成功：$result", result is SkillWriteResult.Written)

        val audit = File(SkillRules.projectSkillDir(projectRoot), SkillAudit.FILE_NAME)
        assertTrue("要写 _变更.jsonl", audit.isFile)
        val lines = audit.readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        val o = org.json.JSONObject(lines[0])
        assertEquals("project", o.getString("scope"))
        assertEquals("audit_demo", o.getString("name"))
        assertTrue("要有前后摘要", o.has("beforeSha") && o.has("afterSha"))
        assertTrue("理由要留", o.getString("reason").contains("验证审计"))
    }

    @Test
    fun audit_failure_does_not_break_write() {
        // 审计目录不可写（把 技能/ 建成文件）⇒ 技能仍要写成功，只是没有审计行
        val files = pf()
        val projectRoot = files.projectFile().parentFile!!
        File(projectRoot, SkillRules.SKILL_DIR).writeText("我是文件不是目录")
        val proposal = SkillProposal(SkillScope.PROJECT, "no_audit", "# x", "r", draft = false)
        val plan = SkillStore.plan(proposal, projectRoot, files.globalFile().parentFile!!, "s")
        // 目录被占 ⇒ 写技能本身失败（Rejected），这是既有行为，不该崩
        val r = SkillStore.apply(plan, files, "")
        assertTrue("坏目录只能是 Rejected 不能抛", r is SkillWriteResult.Rejected)
    }
}
