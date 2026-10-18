// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第二十三批 A 段 · 技能可写（`SkillStore`）。
 *
 * ## 钉的是那五条铁律
 *
 * 1. 写入口唯一（源码级：别处不许再 fopen `SKILL.md`）；
 * 2. 写前必备份；
 * 3. 确认卡需要的五项都在计划里（名／落点／前 200 字／字数／备份名）；
 * 4. 索引与正文是一件事（正式技能：两边都写；草稿：只写正文、**不进索引**）；
 * 5. **没有删除**（源码级：SkillStore 里不许有 delete 逻辑）。
 *
 * 用真实临时目录 ＋ 真实 `PersonaFiles`（技能写盘这事儿没法 mock：要验的就是「盘上真的有什么」）。
 */
class SkillStoreTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd-skill-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd-skill-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun files() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun proposal(
        name: String = "风月AI填卡",
        body: String = "# 风月AI 填卡\n先看字段清单，再按必填顺序填。",
        scope: SkillScope = SkillScope.PROJECT,
        draft: Boolean = false,
    ) = SkillProposal(scope = scope, name = name, body = body, reason = "学到一条", draft = draft)

    // ── 1. 技能名安全化 ─────────────────────────────────────────────────────

    @Test
    fun `S1_技能名剥掉路径与控制字符_空则拒绝`() {
        assertEquals("风月AI填卡", SkillStore.sanitizeName("风月AI填卡"))
        assertEquals("ab_c-1", SkillStore.sanitizeName("ab_c-1"))
        assertEquals("攻略", SkillStore.sanitizeName("../攻略"))
        assertEquals("攻略", SkillStore.sanitizeName("攻略\u0000"))
        assertNull("全是非法字符 ⇒ 拒绝（不许用「未命名」兜底写盘）", SkillStore.sanitizeName("../.."))
        assertNull(SkillStore.sanitizeName("   "))
        assertEquals(32, SkillStore.sanitizeName("技".repeat(50))!!.length)
    }

    // ── 2. 计划（确认卡要的五项） ───────────────────────────────────────────

    @Test
    fun `S2_计划里有确认卡要的五项_首次写没有备份名`() {
        val plan = SkillStore.plan(proposal(), projectRoot, globalRoot, backupStamp = "20261018-214500")
        assertTrue("落点必须是项目的技能目录", plan.file.path.contains("技能"))
        assertEquals("SKILL.md", plan.file.name)
        assertNull("同名文件不存在 ⇒ 没有备份名", plan.backupName)
        assertEquals(200, SkillStore.PREVIEW_CHARS)
        assertTrue("预览不超过 200 字", plan.preview.length <= 200)
        assertEquals(plan.proposal.body.length, plan.charCount)
        assertEquals("风月AI填卡", plan.proposal.name)
        assertEquals("风月AI填卡", plan.indexEntry.name)
    }

    @Test
    fun `S3_同名已存在时给出备份文件名`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "风月AI填卡").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("旧版")
        val plan = SkillStore.plan(proposal(), projectRoot, globalRoot, backupStamp = "20261018-214500")
        assertEquals("风月AI填卡.SKILL.md.bak-20261018-214500", plan.backupName)
    }

    @Test
    fun `S4_四类阻断_名字非法_正文空_正文超长_索引满`() {
        val illegal = SkillStore.plan(proposal(name = "../.."), projectRoot, globalRoot, "s")
        assertNotNull("名字非法要挡", illegal.blocked)
        val empty = SkillStore.plan(proposal(body = "  "), projectRoot, globalRoot, "s")
        assertNotNull("空正文要挡", empty.blocked)
        val huge = SkillStore.plan(proposal(body = "x".repeat(SkillStore.BODY_MAX_CHARS + 1)), projectRoot, globalRoot, "s")
        assertNotNull("超长要挡", huge.blocked)
        val fullIndex = SkillStore.plan(
            proposal(),
            projectRoot, globalRoot, "s",
            indexCountAfterAdd = SkillRules.INDEX_CAP + 1,
        )
        assertNotNull("索引满了要挡", fullIndex.blocked)
        // 草稿不进索引 ⇒ 索引满也挡不住草稿（它本来就不占索引位）
        val draftWhenFull = SkillStore.plan(
            proposal(draft = true),
            projectRoot, globalRoot, "s",
            indexCountAfterAdd = SkillRules.INDEX_CAP + 1,
        )
        assertNull("草稿不占索引位", draftWhenFull.blocked)
    }

    // ── 3. 真落盘：备份 → 正文 → 索引 ───────────────────────────────────────

    @Test
    fun `S5_正式技能_正文写下去且索引一起写`() {
        val r = SkillStore.apply(
            SkillStore.plan(proposal(), projectRoot, globalRoot, "s"),
            files(),
            currentIndexText = "",
        )
        assertTrue("要写成：$r", r is SkillWriteResult.Written)
        r as SkillWriteResult.Written
        assertTrue("正文在盘上", r.file.isFile)
        assertTrue("索引要一起加", r.indexUpdated)
        val layer = files().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("索引行进项目人设：$layer", layer.contains("风月AI填卡"))
    }

    @Test
    fun `S6_草稿只写正文_不进索引`() {
        val r = SkillStore.apply(
            SkillStore.plan(proposal(draft = true), projectRoot, globalRoot, "s"),
            files(),
            currentIndexText = "",
        )
        assertTrue(r is SkillWriteResult.Written)
        // 2026-10-18 改口径：草稿**也要进索引**（否则下次看不见，只能重新摸索＝白烧 token），
        // 但索引行要标明「草稿·未验证」，让人和模型都知道它还没验过。
        assertTrue("草稿也要写索引", (r as SkillWriteResult.Written).indexUpdated)
        val idx = files().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("索引里要能看见它", idx.contains("风月AI填卡"))
        assertTrue("索引要标明未验证", idx.contains("草稿·未验证"))
        assertTrue("草稿头要在第一行", r.file.readText().startsWith("<!-- status=draft uses=0 -->"))
    }

    @Test
    fun `S7_覆盖写之前先备份_旧内容还在备份里`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "风月AI填卡").apply { mkdirs() }
        val target = File(dir, "SKILL.md")
        target.writeText("旧版正文")
        val r = SkillStore.apply(
            SkillStore.plan(proposal(body = "新版正文"), projectRoot, globalRoot, "20261018-214500"),
            files(),
            currentIndexText = "",
        )
        assertTrue(r is SkillWriteResult.Written)
        val backupName = (r as SkillWriteResult.Written).backup
        assertEquals("风月AI填卡.SKILL.md.bak-20261018-214500", backupName)
        assertEquals("旧版正文", File(dir, backupName!!).readText())
        assertEquals("新版正文", target.readText())
    }

    @Test
    fun `S8_回滚到上一版_且回滚本身也留备份`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "风月AI填卡").apply { mkdirs() }
        val target = File(dir, "SKILL.md")
        target.writeText("v1")
        SkillStore.apply(
            SkillStore.plan(proposal(body = "v2"), projectRoot, globalRoot, "20261018-220000"),
            files(),
            currentIndexText = "",
        )
        assertEquals("v2", target.readText())
        val r = SkillStore.restore(target, backupStamp = "20261018-221000")
        assertTrue("要能恢复：$r", r is SkillWriteResult.Written)
        assertEquals("回到 v1", "v1", target.readText())
        assertTrue("回滚前的那版也留了一份", SkillStore.backupsOf(target).isNotEmpty())
    }

    @Test
    fun `S9_没有备份时恢复要明说`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "只有正文").apply { mkdirs() }
        val target = File(dir, "SKILL.md").apply { writeText("仅此一版") }
        val r = SkillStore.restore(target, "s")
        assertTrue(r is SkillWriteResult.Rejected)
        assertTrue((r as SkillWriteResult.Rejected).reason.contains("还没有备份"))
        assertEquals("仅此一版", target.readText())
    }

    // ── 4. 试用期（草稿头） ─────────────────────────────────────────────────

    @Test
    fun `S10_草稿头解析与累加_用满三次才可转正`() {
        val body = SkillStore.draftHeader() + "\n正文"
        assertEquals("draft", SkillStore.statusOf(body))
        assertEquals(0, SkillStore.usesOf(body))
        var cur = body
        repeat(SkillStore.PROMOTE_USES) { cur = SkillStore.bumpUses(cur) }
        assertEquals(SkillStore.PROMOTE_USES, SkillStore.usesOf(cur))
        assertTrue("用满 ${SkillStore.PROMOTE_USES} 次才可转正", SkillStore.canPromote(cur))
        assertFalse("少一次不行", SkillStore.canPromote(SkillStore.bumpUses(body).let { it }))
    }

    @Test
    fun `S11_没有头的正文视为正式技能`() {
        assertEquals("active", SkillStore.statusOf("纯正文"))
        assertEquals(0, SkillStore.usesOf("纯正文"))
        assertFalse(SkillStore.canPromote("纯正文"))
    }

    // ── 5. 源码级铁律 ───────────────────────────────────────────────────────

    @Test
    fun `S12_技能正文的写入口只有SkillStore一处`() {
        val writers = mutableListOf<String>()
        File("src/main/java/com/example/zhengdao").let { root ->
            val base = if (root.exists()) root else File("app/src/main/java/com/example/zhengdao")
            base.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
                if (f.name == "SkillStore.kt") return@forEach
                f.readLines().forEachIndexed { i, line ->
                    val code = line.substringBefore("//")
                    if (code.contains("SKILL.md") && (code.contains("writeText(") || code.contains("createNewFile("))) {
                        writers += "${f.name}:${i + 1}"
                    }
                }
            }
        }
        assertTrue("技能正文只许 SkillStore 写，别处发现：$writers", writers.isEmpty())
    }

    @Test
    fun `S13_没有删除技能的能力`() {
        val src = File("src/main/java/com/example/zhengdao/baopu/SkillStore.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/SkillStore.kt") }
            .readText()
        // 2026-10-18 改口径：技能要能删，但**不是硬删** —— 改名成 `.deleted-<stamp>` 留在原地，
        // 原件一个字节不动，用户在文件管理器里自己清。硬红线只剩「资料库/原始/ 不许碰」。
        assertTrue("要有归档式删除", src.contains("fun archive(file: File"))
        assertFalse("不许直接 hard delete", src.contains("file.delete()"))
    }
    @Test
    fun `S14_设置页真接上了新建与回滚_不是只有底座`() {
        val settings = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt") }
            .readText()
        assertTrue("要有「新建技能」入口", settings.contains("R.string.skill_new_button"))
        assertTrue("要弹编辑器", settings.contains("SkillEditorDialog("))
        // 对话框在 BaopuSkillCard.kt 里（设置页只负责弹它）——两处合起来看
        val uiText = settings + "\n" + File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCard.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCard.kt") }
            .readText()
        assertTrue("保存走 SkillStore.apply（不是自己写文件）", uiText.contains("SkillStore.apply(plan, personaFiles, indexText)"))
        assertTrue("每行要有「恢复到上一版」", settings.contains("R.string.skill_restore_last"))
        assertTrue("回滚要真调 SkillStore.restore", settings.contains("SkillStore.restore(file,"))
        assertTrue("没有备份时不出按钮", settings.contains("SkillStore.backupsOf(skillFileOf("))
        // 确认卡五项都在文案里
        listOf(
            "skill_confirm_name", "skill_confirm_scope", "skill_confirm_chars", "skill_confirm_backup",
        ).forEach { assertTrue("确认卡缺 $it", settings.contains(it) || File("src/main/res/values/strings.xml").let { s -> val ff = if (s.exists()) s else File("app/src/main/res/values/strings.xml"); ff.readText().contains("name=\"$it\"") }) }
    }
    // ── C 段：抱朴得知道自己会做卡 ─────────────────────────────────────────

    @Test
    fun `S15_人设里写明它会做卡_且点明三套格式`() {
        // 直接断言**生成出来的人设文本**（源码注释不算数）
        val preset = BaopuPreset.personaText("/storage/emulated/0/Download/证道")
        // 用户实测的痛点：跟抱朴聊天，它没说自己会做酒馆/Tavo/平台的卡
        assertTrue("要有能力自述段", preset.contains("你会做什么"))
        // 2026-10-18 用户口径：不点名具体平台，只写「网络平台」——
        // 抱朴有浏览器工具、能看网站的真实情况，钉死一个平台名反而会让它照记忆编。
        assertFalse("不许点名具体平台", preset.contains("风月AI"))
        assertTrue("就写「网络平台」", preset.contains("网络平台"))
        assertTrue("要强调它能看真实页面", preset.contains("能看网站的真实情况"))
        assertTrue("要说明它会做角色卡与叙事游戏", preset.contains("角色卡"))
        assertTrue("要提到世界书格式由平台定", preset.contains("世界书"))
        assertTrue("要讲先认脸", preset.contains("先认脸"))
        assertTrue("要提醒字段以实测为准", preset.contains("实测"))
        assertTrue("指向技能目录", preset.contains("技能/"))
    }

    @Test
    fun `S16_平台机制技能模板是固定四段`() {
        val t = SkillStore.PLATFORM_SKILL_TEMPLATE
        listOf("① 平台 / 页面", "② 字段清单", "③ 格式认脸与差异", "④ 已知坑").forEach {
            assertTrue("模板缺段：$it", t.contains(it))
        }
        assertTrue("未实测要写明", t.contains("未实测"))
        assertTrue("三套格式都要提", t.contains("world_book") && t.contains("SillyTavern") && t.contains("Tavo"))
        assertTrue("ST 的 disable 是反义，这条最容易错", t.contains("反义"))
    }
}
