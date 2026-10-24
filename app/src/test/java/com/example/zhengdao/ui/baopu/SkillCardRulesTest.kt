// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百八十四批 §2 判据。入口判据、卡片内容与「确认后才落盘」全在纯函数里，
// 所以这些用例不启动 Compose、不需要设备：真临时目录 + `PersonaFiles` 就能把
// 「说一句 → 出卡 → 确认 → 文件＋索引都在 → 下一轮读得到」整条链走完。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SkillCardRulesTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd-skillcard-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd-skillcard-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun draft(
        name: String = "整理书稿",
        body: String = "# 整理书稿\n遇到拆章先备份，再按章节转编码。",
        source: SkillCardSource = SkillCardSource.USER_SAID,
    ) = SkillCardDraft(name = name, body = body, reason = "用户说记住这个流程", source = source)

    private fun build(d: SkillCardDraft = draft(), stamp: String = "20261024-170000") =
        SkillCardRules.build(d, projectRoot, globalRoot, stamp)

    private fun view(d: SkillCardDraft = draft()) = (build(d) as SkillCardBuild.Ready).view

    private fun host() = SkillCardHost(
        projectRoot = { projectRoot },
        globalRoot = { globalRoot },
        personaFiles = { pf() },
        stamp = { "20261024-170000" },
    )

    private fun skillFile(name: String = "整理书稿") =
        File(projectRoot, "${SkillRules.SKILL_DIR}/$name/${SkillRules.SKILL_FILE}")

    // ── 1. 入口：哪句话算「要记技能」 ─────────────────────────────────────

    @Test
    fun `入口_派工单三句原话都识别`() {
        assertTrue(SkillCardRules.isRememberRequest("记住这个技能"))
        assertTrue(SkillCardRules.isRememberRequest("以后都这么做"))
        assertTrue(SkillCardRules.isRememberRequest("把这个流程记下来"))
        assertTrue("嵌在一句话里也算", SkillCardRules.isRememberRequest("刚才那套挺好，以后都这么做吧"))
    }

    @Test
    fun `入口_闲聊与空串不出卡`() {
        assertFalse(SkillCardRules.isRememberRequest("今天天气怎么样"))
        assertFalse(SkillCardRules.isRememberRequest(""))
        assertFalse(SkillCardRules.isRememberRequest("   "))
    }

    @Test
    fun `入口_说了全局就直拒_提示去设置`() {
        assertTrue(SkillCardRules.asksGlobal("记住这个技能，全局的"))
        val refused = build(draft().copy(scope = SkillScope.GLOBAL))
        assertTrue("全局必须拒：$refused", refused is SkillCardBuild.Refused)
        assertEquals(SkillCardRules.GLOBAL_REFUSAL, (refused as SkillCardBuild.Refused).reason)
        assertTrue("拒绝理由要让用户知道去哪改", refused.reason.contains("设置"))
        assertFalse("全局目录一个字节都不许写", SkillRules.globalSkillDir(globalRoot).exists())
    }

    // ── 2. 未确认：一个字节都不写 ─────────────────────────────────────────

    @Test
    fun `未确认_不出卡不落盘_技能目录和人设都不存在`() {
        val v = view()
        assertEquals("技能目录不许被建出来", false, File(projectRoot, SkillRules.SKILL_DIR).exists())
        assertFalse("人设也不许写", pf().projectFile().exists())
        assertNull("首次保存没有备份名", v.backupName)
        assertTrue("新技能先按草稿走", v.isDraft)
        assertTrue("卡上必须能点记住它", v.canRemember)

        val h = host()
        h.offer(draft())
        assertNotNull("出卡", h.view)
        h.dismiss()
        assertNull("收起后没卡", h.view)
        assertFalse("点「先不记」也不许落盘", File(projectRoot, SkillRules.SKILL_DIR).exists())
        assertFalse(pf().projectFile().exists())
    }

    @Test
    fun `卡上五项都来自数据层_用途_预览_字数_落点_备份`() {
        val long = "# 整理书稿\n" + "把每一章拆开再转编码。".repeat(40)
        val v = view(draft(body = long))
        assertEquals("整理书稿", v.purpose)
        assertEquals(SkillStore.PREVIEW_CHARS, v.preview.length)
        assertEquals(long.length, v.charCount)
        assertEquals("技能/整理书稿/SKILL.md", v.target)
        assertNull(v.blocked)
    }

    @Test
    fun `空正文和非法名字都禁用记住它`() {
        val empty = view(draft(body = "   \n  "))
        assertNotNull("空正文要挡", empty.blocked)
        assertFalse(empty.canRemember)

        val badName = view(draft(name = "../.."))
        assertNotNull("非法名字要挡", badName.blocked)
        assertFalse(badName.canRemember)

        val r1 = SkillCardWriter.save(draft(body = ""), projectRoot, globalRoot, pf(), "s")
        assertTrue(r1 is SkillWriteResult.Rejected)
        assertFalse("被挡时连目录都不建", File(projectRoot, SkillRules.SKILL_DIR).exists())
    }

    // ── 3. 确认后：正文＋索引都在，下一轮模型看得见 ───────────────────────

    @Test
    fun `确认后_文件与索引都在_草稿标未验证`() {
        val h = host()
        h.offer(draft())
        val r = h.remember()
        assertTrue("应当写成：$r", r is SkillWriteResult.Written)
        val w = r as SkillWriteResult.Written
        assertTrue("正文落盘", skillFile().isFile)
        assertTrue("索引一起写", w.indexUpdated)
        assertTrue("草稿头在第一行", skillFile().readText().startsWith("<!-- status=draft uses=0 -->"))

        val index = pf().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("索引里要有这一条：$index", index.contains("【技能】整理书稿"))
        assertTrue("草稿要标未验证：$index", index.contains("草稿·未验证"))
        assertTrue("索引要指到正文路径", index.contains("技能/整理书稿/SKILL.md"))
        assertNull("成功就收卡", h.view)
    }

    @Test
    fun `编辑名字和正文都生效_保存后是编辑后的那份`() {
        val v = view()
        val edited = v.draft.copy(name = "拆章流程", body = "# 拆章流程\n先备份，再拆。")
        val r = SkillCardWriter.save(edited, projectRoot, globalRoot, pf(), "20261024-180000")
        assertTrue("应当写成：$r", r is SkillWriteResult.Written)
        val f = skillFile("拆章流程")
        assertTrue("按新名字落盘", f.isFile)
        assertTrue("正文是编辑后的", f.readText().contains("先备份，再拆"))
        assertTrue("索引跟着新名字", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("【技能】拆章流程"))
    }

    @Test
    fun `同名再记_写前备份旧版_次数不丢`() {
        SkillCardWriter.save(draft(body = "# 整理书稿\n第一版流程"), projectRoot, globalRoot, pf(), "s1")
        // 模拟已经用过两次：头里 uses=2，正文不变。
        val human = SkillStore.contentWithoutHeader(skillFile().readText())
        skillFile().writeText(SkillStore.draftHeader("draft", 2) + "\n" + human, Charsets.UTF_8)

        val v = view(draft(body = "# 整理书稿\n第二版流程（平台加字段了）"))
        assertTrue("同名已有 ⇒ 卡上要给备份名", v.backupName?.contains(SkillStore.BACKUP_MARK) == true)
        assertTrue("同名时告诉用户会覆盖", v.backupName!!.startsWith("整理书稿"))

        val r = SkillCardWriter.save(v.draft, projectRoot, globalRoot, pf(), "s2")
        assertTrue("应当写成：$r", r is SkillWriteResult.Written)
        val w = r as SkillWriteResult.Written
        val backup = File(skillFile().parentFile, w.backup!!)
        assertTrue("备份真在盘上", backup.isFile)
        assertTrue("备份里是旧版", backup.readText().contains("第一版流程"))
        val body = skillFile().readText()
        assertTrue("正文换成新版", body.contains("第二版流程"))
        assertEquals("次数不能被一次编辑清零", 2, SkillStore.usesOf(body))
        assertEquals("还是草稿", "draft", SkillStore.statusOf(body))
        val idx = pf().read(PersonaLayer.C_PROJECT).orEmpty()
        assertEquals(
            "同名只留一条索引（重复写不许长出第二份）：$idx",
            1,
            idx.lines().count { it.trim().startsWith(SkillRules.INDEX_MARK) && it.contains("整理书稿") },
        )
    }

    @Test
    fun `正式技能再记一次_不打回草稿_索引不掉未验证标`() {
        val reg = SkillStore.plan(
            SkillProposal(
                scope = SkillScope.PROJECT,
                name = "正式流程",
                body = "# 正式流程\n老正文",
                reason = "t",
                draft = false,
            ),
            projectRoot,
            globalRoot,
            "s0",
        )
        assertTrue(SkillStore.apply(reg, pf(), "") is SkillWriteResult.Written)

        val v = view(draft(name = "正式流程", body = "# 正式流程\n新正文"))
        assertFalse("正式技能编辑时保持正式", v.isDraft)
        val r = SkillCardWriter.save(v.draft, projectRoot, globalRoot, pf(), "s1")
        assertTrue(r is SkillWriteResult.Written)
        assertEquals("正式", "active", SkillStore.statusOf(skillFile("正式流程").readText()))
        assertFalse(
            "正式技能索引里不该出现未验证标",
            pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("草稿·未验证"),
        )
    }

    @Test
    fun `索引满20条时拒写_正文也不留半条`() {
        pf().projectFile().writeText(
            (1..SkillRules.INDEX_CAP).joinToString("\n") { i ->
                SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "旧技能$i", "旧场景$i"))
            } + "\n",
            Charsets.UTF_8,
        )
        val r = SkillCardWriter.save(draft(), projectRoot, globalRoot, pf(), "s")
        assertTrue("第 21 条要拒：$r", r is SkillWriteResult.Rejected)
        assertTrue((r as SkillWriteResult.Rejected).reason.contains("索引已满"))
        assertFalse("索引没写成时正文不许留半条", skillFile().exists())
    }

    // ── 4. 转正：用满 3 次自动／手动也行 ────────────────────────────────

    @Test
    fun `用满三次自动转正_不到三次还是草稿`() {
        val once = SkillStore.afterUse(SkillStore.draftHeader("draft", 0) + "\n# 流程\n正文")
        assertEquals("第 1 次还早", false, once.promoted)
        assertEquals(1, SkillStore.usesOf(once.body))
        assertEquals("still draft", "draft", SkillStore.statusOf(once.body))

        val third = SkillStore.afterUse(SkillStore.draftHeader("draft", 2) + "\n# 流程\n正文")
        assertTrue("第 3 次当次转正", third.promoted)
        assertEquals("active", SkillStore.statusOf(third.body))
        assertEquals("次数保留", 3, SkillStore.usesOf(third.body))
        assertTrue("转正后不再是草稿", !SkillStore.canPromote(third.body))
    }

    @Test
    fun `手动转正判据仍在_用满才给转`() {
        val two = SkillStore.draftHeader("draft", 2) + "\n# 流程\n正文"
        assertFalse("两次不够", SkillStore.canPromote(two))
        val three = SkillStore.draftHeader("draft", 3) + "\n# 流程\n正文"
        assertTrue("三次够了", SkillStore.canPromote(three))
        assertEquals("active", SkillStore.statusOf(SkillStore.promoteBody(three)))
    }

    // ── 5. 重复成功才主动提一张 ──────────────────────────────────────────

    @Test
    fun `重复成功_两次才提_提过不再提`() {
        val sig = SkillCardRules.flowSignature(listOf("write_file", "bash"))
        assertEquals("write_file>bash", sig)
        assertFalse("一次不主动提", SkillCardRules.repeatedSuccessDue(listOf(sig), sig, alreadyOffered = false))
        assertTrue("两次才提", SkillCardRules.repeatedSuccessDue(listOf(sig, sig), sig, alreadyOffered = false))
        assertFalse("提过不再提", SkillCardRules.repeatedSuccessDue(listOf(sig, sig), sig, alreadyOffered = true))
        assertFalse("空签名不猜", SkillCardRules.repeatedSuccessDue(listOf("", ""), "", alreadyOffered = false))
    }

    @Test
    fun `流程签名只认成功工具_顺序稳定_空白剔除`() {
        assertEquals("bash>write_file", SkillCardRules.flowSignature(listOf("bash", " write_file ", "")))
        assertEquals("", SkillCardRules.flowSignature(listOf("", "  ")))
    }

    // ── 6. 源码级判据：写口唯一、没有删除、文案中英齐 ────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    @Test
    fun `卡片源码_落盘只走SkillStore_不自己写文件`() {
        val card = raw("src/main/java/com/example/zhengdao/ui/baopu/SkillCard.kt")
        assertTrue("必须经 SkillStore.plan", card.contains("SkillStore.plan("))
        assertTrue("必须经 SkillStore.apply", card.contains("SkillStore.apply("))
        assertFalse("卡片不许自己写 SKILL.md", card.contains("writeText(Charsets.UTF_8)"))
        assertFalse("本单没有删除入口", card.contains("fun delete("))
        val store = raw("src/main/java/com/example/zhengdao/baopu/SkillStore.kt")
        assertFalse("SkillStore 仍然故意没有 delete", store.contains("fun delete("))
    }

    @Test
    fun `设置页_用一次走afterUse_满次提示自动转正`() {
        val settings = raw("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("用一次必须走同一份纯函数", settings.contains("SkillStore.afterUse(row.body)"))
        assertTrue("按转正结果决定写草稿还是正式", settings.contains("stillDraft = !after.promoted"))
        assertTrue("要提示自动转正", settings.contains("R.string.skill_draft_auto_promoted"))
        assertTrue("草稿行要标未验证", settings.contains("R.string.skill_draft_uses"))
    }

    @Test
    fun `技能卡文案中英一份不差`() {
        val zh = raw("src/main/res/values/strings.xml")
        val en = raw("src/main/res/values-en/strings.xml")
        fun keys(src: String) = Regex("""<string name="(skill_card_[a-z_]+)"""")
            .findAll(src).map { it.groupValues[1] }.toSet()
        val kz = keys(zh)
        val ke = keys(en)
        assertTrue("中文这份要有关键文案：$kz", kz.contains("skill_card_remember") && kz.contains("skill_card_dismiss"))
        assertEquals("中英技能卡文案必须一一对应：中=$kz 英=$ke", kz, ke)
    }
}
