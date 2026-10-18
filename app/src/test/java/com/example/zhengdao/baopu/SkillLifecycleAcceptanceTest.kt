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
}
