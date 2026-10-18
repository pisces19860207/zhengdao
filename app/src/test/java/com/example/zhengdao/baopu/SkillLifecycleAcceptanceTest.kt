// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第二十三批 A 段 · **技能全生命周期验收**（一次性把盘上真发生的事钉死）。
 *
 * 为什么用真临时目录跑而不是真机点：真机上「在 Compose 两个输入框之间切焦点」靠 adb 输入法切不动，
 * 而这一批真正要保证的恰恰是**盘上留下了什么**——文件名、内容、备份、归档名。
 * 真机只负责验「看得见的那层」（按钮在、确认卡五项、阻断红字），那些已经验过了。
 *
 * 一条链走完：新建 → 再写（改自己那份）→ 回滚 → 归档删除；外加草稿头与索引标记。
 */
class SkillLifecycleAcceptanceTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd-life-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd-life-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun p(body: String, draft: Boolean = false, name: String = "platform-flow") = SkillProposal(
        scope = SkillScope.PROJECT,
        name = name,
        body = body,
        reason = "验收",
        draft = draft,
    )

    @Test
    fun `L1_新建正式技能_盘上有SKILL_md_索引也有它`() {
        val plan = SkillStore.plan(p("# 平台流程\n先填必填项。"), projectRoot, globalRoot, "20261018-220000")
        val r = SkillStore.apply(plan, pf(), currentIndexText = "")
        assertTrue(r is SkillWriteResult.Written)
        val file = (r as SkillWriteResult.Written).file
        assertEquals("SKILL.md", file.name)
        assertEquals("platform-flow", file.parentFile!!.name)
        assertTrue("正文落盘", file.readText().contains("先填必填项"))
        assertTrue("索引要一起写", r.indexUpdated)
        assertTrue(pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("platform-flow"))
    }

    @Test
    fun `L2_再写同名等于改自己那份_旧版进备份`() {
        val v1 = "第一版流程"
        SkillStore.apply(SkillStore.plan(p(v1), projectRoot, globalRoot, "20261018-220000"), pf(), "")
        val plan2 = SkillStore.plan(p("第二版流程（平台加字段了）"), projectRoot, globalRoot, "20261018-230000")
        assertEquals("platform-flow.SKILL.md.bak-20261018-230000", plan2.backupName)
        val r = SkillStore.apply(plan2, pf(), pf().read(PersonaLayer.C_PROJECT).orEmpty())
        val written = r as SkillWriteResult.Written
        assertTrue("正文换成新版", written.file.readText().contains("第二版"))
        val backup = File(written.file.parentFile, written.backup!!)
        assertTrue("备份里必须是旧版", backup.readText().contains(v1))
        assertNotNull("备份文件真在盘上", backup.takeIf { it.isFile })
    }

    @Test
    fun `L3_回滚到上一版_内容真回来`() {
        val file = (SkillStore.apply(
            SkillStore.plan(p("v1"), projectRoot, globalRoot, "20261018-220000"), pf(), "",
        ) as SkillWriteResult.Written).file
        SkillStore.apply(
            SkillStore.plan(p("v2"), projectRoot, globalRoot, "20261018-230000"),
            pf(), pf().read(PersonaLayer.C_PROJECT).orEmpty(),
        )
        assertTrue("改成 v2", file.readText().contains("v2"))
        val r = SkillStore.restore(file, "20261018-240000")
        assertTrue(r is SkillWriteResult.Written)
        assertTrue("回到 v1", file.readText().contains("v1"))
        assertFalse("v2 不再是当前", file.readText().contains("v2"))
        assertTrue("回滚本身也留了一份", SkillStore.backupsOf(file).size >= 2)
    }

    @Test
    fun `L4_删除是归档_不硬删_原件还在盘上`() {
        val file = (SkillStore.apply(
            SkillStore.plan(p("要删的技能正文"), projectRoot, globalRoot, "20261018-220000"), pf(), "",
        ) as SkillWriteResult.Written).file
        val r = SkillStore.archive(file, "20261018-250000")
        assertTrue(r is SkillWriteResult.Written)
        val gone = (r as SkillWriteResult.Written).file
        assertFalse("原名不再可用", file.exists())
        assertTrue("归档文件在", gone.isFile)
        assertEquals("SKILL.md.deleted-20261018-250000", gone.name)
        assertTrue("内容原样保留", gone.readText().contains("要删的技能正文"))
    }

    @Test
    fun `L5_草稿带status头_且索引行标未验证`() {
        val plan = SkillStore.plan(p("# 摸索中\n还没验完", draft = true), projectRoot, globalRoot, "s")
        val r = SkillStore.apply(plan, pf(), "") as SkillWriteResult.Written
        assertTrue("草稿头在第一行", r.file.readText().startsWith("<!-- status=draft uses=0 -->"))
        assertTrue("草稿也要进索引（否则下次抓瞎）", r.indexUpdated)
        assertTrue("索引里要标明未验证", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("草稿·未验证"))
    }

    @Test
    fun `L6_名字非法时连盘都不碰`() {
        val plan = SkillStore.plan(p("x", name = "../.."), projectRoot, globalRoot, "s")
        assertNotNull("名字非法要挡住", plan.blocked)
        val r = SkillStore.apply(plan, pf(), "")
        assertTrue(r is SkillWriteResult.Rejected)
        assertFalse("技能目录一个都不许建", File(projectRoot, SkillRules.SKILL_DIR).exists())
    }
    // ── write_skill 工具的门禁（第二十三批追加） ─────────────────────────────

    @Test
    fun `L7_write_skill必须是确认制_且默认启用`() {
        val spec = com.example.zhengdao.baopu.agent.SkillTools.spec(
            workspaceRoot = { projectRoot },
            personaFiles = { pf() },
        )
        assertEquals("write_skill", spec.name)
        assertEquals(
            "写技能会改用户文件，必须是 CONFIRM（不许 FREE 一键落盘）",
            com.example.zhengdao.baopu.agent.ToolPermission.CONFIRM,
            spec.permission,
        )
        val reg = File("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt") }
            .readText()
        assertTrue("要进默认启用集", reg.contains("\"write_skill\""))
        val rt = File("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt") }
            .readText()
        assertTrue("要真的注册进注册表", rt.contains("SkillTools.spec("))
    }

    @Test
    fun `L8_名字为空或正文为空时工具拒绝_不落盘`() {
        val spec = com.example.zhengdao.baopu.agent.SkillTools.spec(
            workspaceRoot = { projectRoot },
            personaFiles = { pf() },
        )
        val noName = spec.executor(org.json.JSONObject("""{"name":"","body":"x"}"""))
        assertTrue("空名字要拒：$noName", noName is com.example.zhengdao.baopu.agent.ToolOutcome.Failed)
        val noBody = spec.executor(org.json.JSONObject("""{"name":"ok-name","body":"   "}"""))
        assertTrue("空正文要拒：$noBody", noBody is com.example.zhengdao.baopu.agent.ToolOutcome.Failed)
        assertFalse("被拒时不许建目录", File(projectRoot, SkillRules.SKILL_DIR).exists())
    }

    @Test
    fun `L9_全局技能不许抱朴自己写`() {
        // 2026-10-19 用户逐字：「全局技能不能让他随便改」。
        // 项目技能仍可自写（草稿＋确认卡＋写前备份）；全局是共用底座，只有用户在本机设置页能改。
        val spec = com.example.zhengdao.baopu.agent.SkillTools.spec(
            workspaceRoot = { projectRoot },
            personaFiles = { pf() },
        )
        val out = spec.executor(
            org.json.JSONObject("""{"name":"global-thing","body":"正文","scope":"global"}"""),
        )
        assertTrue("写全局必须被拒：$out", out is com.example.zhengdao.baopu.agent.ToolOutcome.Rejected)
        val reason = (out as com.example.zhengdao.baopu.agent.ToolOutcome.Rejected).reason
        assertTrue("拒绝理由要点明全局是共用底座：$reason", reason.contains("全局技能"))
        val globalSkillDir = SkillRules.globalSkillDir(File(pf().globalFile().parentFile ?: projectRoot, ""))
        assertFalse("被拒时全局目录一个字节都不许写：${globalSkillDir.absolutePath}", globalSkillDir.exists())
    }
    // ── write_skill 的生命周期两步（action=use / promote，2026-10-19「养孩子」口径）──

    private fun specForTest() = com.example.zhengdao.baopu.agent.SkillTools.spec(
        workspaceRoot = { projectRoot },
        personaFiles = { pf() },
    )

    private fun putDraftSkill(name: String, uses: Int, status: String = "draft") {
        val dir = File(projectRoot, "${SkillRules.SKILL_DIR}/$name")
        dir.mkdirs()
        File(dir, SkillRules.SKILL_FILE).writeText(
            "<!-- status=$status uses=$uses -->" + System.lineSeparator() + "# $name" + System.lineSeparator() + "正文",
            Charsets.UTF_8,
        )
    }

    @Test
    fun `L10_use记一次次数_仍草稿且正文不动`() {
        putDraftSkill("probe-flow", uses = 0)
        val out = specForTest().executor(org.json.JSONObject("""{"name":"probe-flow","action":"use"}"""))
        assertTrue("记次数应当成功：$out", out is com.example.zhengdao.baopu.agent.ToolOutcome.Ok)
        val body = File(projectRoot, "${SkillRules.SKILL_DIR}/probe-flow/${SkillRules.SKILL_FILE}").readText()
        assertEquals("次数要加一", 1, SkillStore.usesOf(body))
        assertEquals("还是草稿", "draft", SkillStore.statusOf(body))
        assertTrue("正文内容不能丢", body.contains("正文"))
    }

    @Test
    fun `L11_promote只在用满3次时放行_转正后头变active`() {
        putDraftSkill("young-flow", uses = 2)
        val tooEarly = specForTest().executor(org.json.JSONObject("""{"name":"young-flow","action":"promote"}"""))
        assertTrue("没满 3 次不许转正：$tooEarly", tooEarly is com.example.zhengdao.baopu.agent.ToolOutcome.Failed)
        putDraftSkill("young-flow", uses = 3)
        val ok = specForTest().executor(org.json.JSONObject("""{"name":"young-flow","action":"promote"}"""))
        assertTrue("用满了应当能转正：$ok", ok is com.example.zhengdao.baopu.agent.ToolOutcome.Ok)
        val body = File(projectRoot, "${SkillRules.SKILL_DIR}/young-flow/${SkillRules.SKILL_FILE}").readText()
        assertEquals("转正后头是 active", "active", SkillStore.statusOf(body))
        assertEquals("次数保留", 3, SkillStore.usesOf(body))
    }

    @Test
    fun `L12_use碰不到不存在的技能_也不越界`() {
        val out = specForTest().executor(org.json.JSONObject("""{"name":"nope","action":"use"}"""))
        assertTrue("不存在的技能要拒：$out", out is com.example.zhengdao.baopu.agent.ToolOutcome.Failed)
        assertFalse("不该凭空建目录", File(projectRoot, "${SkillRules.SKILL_DIR}/nope").exists())
    }
}