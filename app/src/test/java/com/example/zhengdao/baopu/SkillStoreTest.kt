// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
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
 * 4. 索引与正文是一件事（**正式与草稿都两边都写**，草稿那行带「草稿·未验证」标——第一百七十四批）；
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
        name: String = "网络平台填卡",
        body: String = "# 网络平台 填卡\n先看字段清单，再按必填顺序填。",
        scope: SkillScope = SkillScope.PROJECT,
        draft: Boolean = false,
    ) = SkillProposal(scope = scope, name = name, body = body, reason = "学到一条", draft = draft)

    // ── 1. 技能名安全化 ─────────────────────────────────────────────────────

    @Test
    fun `S1_技能名剥掉路径与控制字符_空则拒绝`() {
        assertEquals("网络平台填卡", SkillStore.sanitizeName("网络平台填卡"))
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
        assertEquals("网络平台填卡", plan.proposal.name)
        assertEquals("网络平台填卡", plan.indexEntry.name)
    }

    @Test
    fun `S3_同名已存在时给出备份文件名`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "网络平台填卡").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("旧版")
        val plan = SkillStore.plan(proposal(), projectRoot, globalRoot, backupStamp = "20261018-214500")
        assertEquals("网络平台填卡.SKILL.md.bak-20261018-214500", plan.backupName)
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
        // 第一百七十四批：草稿**也占索引位**，索引满了草稿同样挡（原来这条豁免是绕过上限的口子）
        val draftWhenFull = SkillStore.plan(
            proposal(draft = true),
            projectRoot, globalRoot, "s",
            indexCountAfterAdd = SkillRules.INDEX_CAP + 1,
        )
        assertNotNull("草稿也占索引位，满了照样挡", draftWhenFull.blocked)
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
        assertTrue("索引行进项目人设：$layer", layer.contains("网络平台填卡"))
    }

    @Test
    fun `S6_草稿也进索引_并带未验证标`() {
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
        assertTrue("索引里要能看见它", idx.contains("网络平台填卡"))
        assertTrue("索引要标明未验证", idx.contains("草稿·未验证"))
        assertTrue("草稿头要在第一行", r.file.readText().startsWith("<!-- status=draft uses=0 -->"))
    }

    @Test
    fun `S7_覆盖写之前先备份_旧内容还在备份里`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "网络平台填卡").apply { mkdirs() }
        val target = File(dir, "SKILL.md")
        target.writeText("旧版正文")
        val r = SkillStore.apply(
            SkillStore.plan(proposal(body = "新版正文"), projectRoot, globalRoot, "20261018-214500"),
            files(),
            currentIndexText = "",
        )
        assertTrue(r is SkillWriteResult.Written)
        val backupName = (r as SkillWriteResult.Written).backup
        assertEquals("网络平台填卡.SKILL.md.bak-20261018-214500", backupName)
        assertEquals("旧版正文", File(dir, backupName!!).readText())
        assertEquals("新版正文", target.readText())
    }

    @Test
    fun `S8_回滚到上一版_且回滚本身也留备份`() {
        val dir = File(SkillRules.projectSkillDir(projectRoot), "网络平台填卡").apply { mkdirs() }
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
        // 判据不点名：拆开拼接，避免源码里出现平台名
            assertFalse("不许点名具体平台", preset.contains("风" + "月"))
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

    // ── 6. 第一百七十四批 · 索引接线（死分支／两层合计／同名一行／不留半条）──

    /** SkillStore.kt 的源码文本（源码级判据用；跑测试的工作目录可能是仓库根或 app/）。 */
    private fun skillStoreSrc(): String = File("src/main/java/com/example/zhengdao/baopu/SkillStore.kt")
        .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/SkillStore.kt") }
        .readText()

    /** 往一层人设灌 [count] 条索引（夹具；走合法写口，不手写路径）。 */
    private fun seedIndex(files: PersonaFiles, layer: PersonaLayer, count: Int) {
        val scope = if (layer == PersonaLayer.B_GLOBAL) MemoryScope.GLOBAL else MemoryScope.PROJECT
        val text = (1..count).joinToString("\n") {
            SkillRules.renderIndexLine(SkillRules.IndexLine(scope, "占位技能$it", "占位场景第 $it 条")) + "\n"
        }
        val p = PersonaRules.propose(layer, files.read(layer).orEmpty(), text, "测试夹具：灌索引")
        assertNotNull("夹具要能写人设", p)
        assertTrue("夹具写盘要成", files.applyProposal(p!!))
    }

    /** 人设层写不下去的夹具：projectRoot 做成**普通文件** ⇒ 它的子路径写不进（applyProposal 返回 false）。 */
    private fun brokenPersonaFiles(): PersonaFiles {
        val blocked = File(projectRoot, "persona-blocked")
        blocked.writeText("我不是目录", Charsets.UTF_8)
        return PersonaFiles(projectRoot = blocked, globalRoot = globalRoot)
    }

    private fun indexLinesOf(text: String): List<String> =
        text.lines().filter { it.trim().startsWith(SkillRules.INDEX_MARK) }

    @Test
    fun `S17_索引写入是直线_不许死分支把它关死`() {
        // 派工单 §2 负例「if (false) 复活」。来历：2026-10-18 那次改口径把
        // `if (plan.proposal.draft) { false } else { 写索引 }` 的条件直接改成 `if (false)`——
        // 运行时通（走 else），但判据没人读得出来，注释与代码自相矛盾。
        val src = skillStoreSrc()
        assertFalse("不许再用 if (false) 死分支", src.contains("if (false)"))
        assertTrue("索引必须真的算 addIndexLine", src.contains("SkillRules.addIndexLine("))
        assertTrue("索引写盘只许走人设层唯一写口", src.contains("personaFiles.applyProposal("))
        assertFalse("草稿条件不许再包住索引写入（旧的那道豁免）", src.contains("if (plan.proposal.draft)"))
    }

    @Test
    fun `S18_同名再写_索引只有一行_那一行换成新场景`() {
        val pf = files()
        val first = SkillStore.apply(
            SkillStore.plan(proposal(body = "先看字段清单再按顺序填。"), projectRoot, globalRoot, "s"),
            pf, pf.read(PersonaLayer.C_PROJECT).orEmpty(),
        )
        assertTrue("首写要成：$first", first is SkillWriteResult.Written)
        val second = SkillStore.apply(
            SkillStore.plan(proposal(body = "先填必填项再存草稿。"), projectRoot, globalRoot, "s2"),
            pf, pf.read(PersonaLayer.C_PROJECT).orEmpty(),
        )
        assertTrue("再写要成：$second", second is SkillWriteResult.Written)
        val rows = indexLinesOf(pf.read(PersonaLayer.C_PROJECT).orEmpty())
        assertEquals("同名只许一行，不许重复追加：$rows", 1, rows.size)
        assertTrue("那一行要更新成新版场景：$rows", rows.first().contains("先填必填项"))
    }

    @Test
    fun `S19_草稿转正后未验证标消失_仍然只有一行`() {
        val pf = files()
        val draft = SkillStore.apply(
            SkillStore.plan(proposal(body = "先按字段清单填一遍。", draft = true), projectRoot, globalRoot, "s"),
            pf, "",
        )
        assertTrue("草稿要写成：$draft", draft is SkillWriteResult.Written)
        val duringDraft = pf.read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("草稿期要带未验证标：$duringDraft", duringDraft.contains("草稿·未验证"))
        val promoted = SkillStore.apply(
            SkillStore.plan(proposal(body = "先按字段清单填一遍。", draft = false), projectRoot, globalRoot, "s2"),
            pf, pf.read(PersonaLayer.C_PROJECT).orEmpty(),
        )
        assertTrue("转正要写成：$promoted", promoted is SkillWriteResult.Written)
        val after = pf.read(PersonaLayer.C_PROJECT).orEmpty()
        assertFalse("转正后标记要消失：$after", after.contains("草稿·未验证"))
        assertEquals("转正不许多出一行", 1, indexLinesOf(after).size)
    }

    @Test
    fun `S20_两层合计满20条_第21条拒_盘上一个字节都不写`() {
        // 上限是 B＋C **两层合计**（SkillRules.INDEX_CAP 的口径）：全局层占满 20 条时，
        // 项目层哪怕一行都没有，也不许再写第 21 条。原来的 apply 没传 otherLayerCount ⇒ 各层各算各的。
        val pf = files()
        seedIndex(pf, PersonaLayer.B_GLOBAL, SkillRules.INDEX_CAP)
        val globalBefore = pf.read(PersonaLayer.B_GLOBAL).orEmpty()
        val plan = SkillStore.plan(proposal(), projectRoot, globalRoot, "s")
        val r = SkillStore.apply(plan, pf, pf.read(PersonaLayer.C_PROJECT).orEmpty())
        val rej = r as SkillWriteResult.Rejected
        assertTrue("两层合计满了要拒：${rej.reason}", rej.reason.contains("索引已满"))
        assertFalse("被拒不许留下正文（不留半条）", plan.file.exists())
        assertFalse("被拒不许建技能目录", File(projectRoot, SkillRules.SKILL_DIR).exists())
        assertEquals("另一层原样不动", globalBefore, pf.read(PersonaLayer.B_GLOBAL).orEmpty())
    }

    @Test
    fun `S21_草稿同样占索引位_合计满时一样拒`() {
        val pf = files()
        seedIndex(pf, PersonaLayer.B_GLOBAL, SkillRules.INDEX_CAP)
        val plan = SkillStore.plan(proposal(draft = true), projectRoot, globalRoot, "s")
        val r = SkillStore.apply(plan, pf, "")
        assertTrue("草稿满了照样拒：$r", r is SkillWriteResult.Rejected)
        assertFalse("被拒不许留下正文", plan.file.exists())
    }

    @Test
    fun `S22_索引落不成_覆盖写把正文回滚成上一版`() {
        val pf = files()
        val first = SkillStore.apply(
            SkillStore.plan(proposal(body = "第一版流程。"), projectRoot, globalRoot, "20261024-100000"),
            pf, "",
        )
        assertTrue("前置：第一次要写成：$first", first is SkillWriteResult.Written)
        val target = (first as SkillWriteResult.Written).file
        val plan2 = SkillStore.plan(proposal(body = "第二版流程。"), projectRoot, globalRoot, "20261024-100100")
        assertNotNull("前置：同名已在 ⇒ 要有备份名", plan2.backupName)
        val r = SkillStore.apply(plan2, brokenPersonaFiles(), pf.read(PersonaLayer.C_PROJECT).orEmpty())
        assertTrue("索引写不成就得报错：$r", r is SkillWriteResult.Rejected)
        assertEquals("正文回到上一版（不留改坏了却没索引的半条）", "第一版流程。", target.readText())
    }

    @Test
    fun `S23_索引落不成_首次新建软归档不留孤儿正文`() {
        val plan = SkillStore.plan(proposal(body = "还没落地的流程。"), projectRoot, globalRoot, "20261024-100200")
        assertNull("前置：这是首次新建", plan.backupName)
        val r = SkillStore.apply(plan, brokenPersonaFiles(), "")
        assertTrue("索引写不成要报错：$r", r is SkillWriteResult.Rejected)
        assertFalse("不许留下有正文没索引的孤儿技能", plan.file.isFile)
        val archived = plan.file.parentFile?.listFiles { f -> f.name.contains(".deleted-") }.orEmpty()
        assertTrue("软归档（红线不硬删）要看得见这份原件", archived.isNotEmpty())
    }
}
