// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第三十二批 §4 判据 T1–T6（技能试用期界面：草稿列出来＋用一次＋转正＋模板预填）。
// 两层打法（与 BaopuImageGridTest / SkillLifecycleAcceptanceTest 同一惯例）：
//   1. **行为层**：真临时目录里造出草稿文件，跑 draftSkillRows / commitSkillDraft，
//      断言盘上到底变成什么样——T1/T2/T3 的主证据在这儿，不靠读注释相信它；
//   2. **源码层**：读剥掉注释后的两个 UI 文件，钉「不许出现 X」那几条（T4/T5/T6 与 C 段）。
// 每条「不许出现 X」都配了判别器正例（同一段源码里必须出现新写法），防止门禁空跑。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第三十二批 · 技能试用期界面（草稿·已用N/3·转正＋平台模板预填）。
 *
 * ## 为什么行为层能跑而不必上真机
 *
 * 本批改的只有「界面消费点」：读盘列表、按钮出现条件、写回哪个文件。这些都在
 * `draftSkillRows` / `commitSkillDraft` 里，输入输出是纯数据＋临时目录，JVM 能真跑。
 * 真机要验的是 Compose 排版出来的手感（草稿区好不好找、两个按钮点得动否），列在交付报告 §7。
 */
class BaopuSkillTrialUiTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** A/B 段本体的两个文件（白名单里的两个）。 */
    private fun card() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCard.kt")

    private fun settings() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun zh() = repoFile("src/main/res/values/strings.xml").readText()

    private fun en() = repoFile("src/main/res/values-en/strings.xml").readText()

    // ── 盘上现场（真临时目录）──────────────────────────────────────────────

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd32-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd32-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    /** 在两层目录里造一个技能正文文件，返回它的路径（测试夹具，允许直接写盘）。 */
    private fun makeSkill(scope: SkillScope, name: String, body: String): File {
        val dir = File(
            when (scope) {
                SkillScope.PROJECT -> SkillRules.projectSkillDir(projectRoot)
                SkillScope.GLOBAL -> SkillRules.globalSkillDir(globalRoot)
            },
            name,
        )
        dir.mkdirs()
        val file = File(dir, SkillRules.SKILL_FILE)
        file.writeText(body, Charsets.UTF_8)
        return file
    }

    private fun draft(uses: Int, name: String = "trial-a", scope: SkillScope = SkillScope.PROJECT): File =
        makeSkill(scope, name, SkillStore.draftHeader("draft", uses) + "\n# 摸索中的流程\n还没验完。")

    /** 本批新加的十个键（T6 的靶子；增删键时这里同步）。 */
    private val newKeys = listOf(
        "skill_draft_group", "skill_draft_none", "skill_draft_uses", "skill_draft_use_once",
        "skill_draft_promote", "skill_draft_used", "skill_draft_promoted", "skill_draft_index_full",
        "skill_use_platform_template", "skill_use_platform_template_hint",
    )

    /** 取某个键里的占位符（`%1$d`／`%1$s`…），中英必须一一对上。 */
    private fun placeholders(text: String, key: String): List<String> {
        val line = Regex("""name="$key">(.*?)</string>""").find(text)?.groupValues?.get(1).orEmpty()
        // 这里不用原始字符串：`\$` 在正则里是字面量美元符号，写成普通串最不容易看错
        return Regex("%\\d+\\$[a-z]").findAll(line).map { it.value }.toList()
    }

    // ── T1：草稿区只列草稿 ──────────────────────────────────────────────────

    @Test
    fun `T1_草稿区只列draft_正式技能一个都不进`() {
        draft(uses = 1, name = "trial-a", scope = SkillScope.PROJECT)
        draft(uses = 3, name = "trial-g", scope = SkillScope.GLOBAL)
        makeSkill(SkillScope.PROJECT, "official-b", "# 正式流程\n已经转正的写法。") // 无头 ⇒ active
        makeSkill(SkillScope.PROJECT, "official-c", SkillStore.draftHeader("active", 9) + "\n# 显式 active")

        val rows = draftSkillRows(projectRoot, globalRoot)

        assertEquals("两层各一条草稿，共两条", 2, rows.size)
        assertEquals(listOf("trial-a", "trial-g"), rows.map { it.name })
        assertEquals(listOf(SkillScope.PROJECT, SkillScope.GLOBAL), rows.map { it.scope })
        assertTrue("正式技能不许出现在草稿区", rows.none { it.name.startsWith("official") })
    }

    @Test
    fun `T1_没有SKILL_md与归档文件都不算草稿`() {
        File(SkillRules.projectSkillDir(projectRoot), "empty-dir").mkdirs() // 光秃秃的目录，没有正文
        draft(uses = 0, name = "trial-a")
        val archived = makeSkill(SkillScope.PROJECT, "archive-me", SkillStore.draftHeader("draft", 1) + "\n正文")
        File(archived.parentFile, "${archived.name}.deleted-20261018")
            .writeText(archived.readText(), Charsets.UTF_8)
        archived.delete() // 归档态：SKILL.md 不在了，只剩 .deleted-*

        val rows = draftSkillRows(projectRoot, globalRoot)

        assertEquals("归档件（.deleted-*）不是正文，不许列进草稿区", listOf("trial-a"), rows.map { it.name })
    }

    @Test
    fun `T1_两层目录都没有时返回空列表_不抛`() {
        assertEquals(emptyList<SkillDraftRow>(), draftSkillRows(projectRoot, globalRoot))
    }

    @Test
    fun `T1_列表真从目录扫_不是写死的名字`() {
        val src = card()
        assertTrue("判别器正例：草稿区靠列目录得出", src.contains("dir.listFiles()"))
        assertTrue("落点仍用 SkillRules 那两个唯一真源", src.contains("SkillRules.projectSkillDir(projectRoot)"))
        assertTrue("全局层同样", src.contains("SkillRules.globalSkillDir(globalRoot)"))
        assertTrue("只认 SKILL.md 这个文件名", src.contains("File(sub, SkillRules.SKILL_FILE)"))
    }

    // ── T2：已用次数来自 usesOf ─────────────────────────────────────────────

    @Test
    fun `T2_行上的次数就是SkillStore_usesOf的读数`() {
        val file = draft(uses = 2)
        val body = file.readText()
        val row = draftSkillRows(projectRoot, globalRoot).single()

        assertEquals("N 必须由 SkillStore.usesOf 给", SkillStore.usesOf(body), row.uses)
        assertEquals(2, row.uses)
        assertEquals(body, row.body)
    }

    @Test
    fun `T2_缺uses时算零_分母用PROMOTE_USES`() {
        val file = makeSkill(SkillScope.PROJECT, "no-uses", SkillStore.draftHeader("draft", 0) + "\n正文")
        val row = draftSkillRows(projectRoot, globalRoot).single()
        assertEquals(0, row.uses)
        assertEquals(0, SkillStore.usesOf(file.readText()))

        val src = settings()
        assertTrue("界面那行读的是 row.uses 与 SkillStore.PROMOTE_USES",
            src.contains("stringResource(R.string.skill_draft_uses, row.uses, SkillStore.PROMOTE_USES)"))
        assertFalse("UI 不许自己解析头里的数字（本批一条正则都用不上）", card().contains("Regex("))
    }

    // ── T3：转正按钮的出现条件与转正后的盘上结果 ────────────────────────────

    @Test
    fun `T3_canPromote的次数线跟着数据层`() {
        draft(uses = SkillStore.PROMOTE_USES - 1, name = "not-yet")
        draft(uses = SkillStore.PROMOTE_USES, name = "ready")

        val rows = draftSkillRows(projectRoot, globalRoot).associateBy { it.name }
        assertFalse("少一次不许能转正", rows.getValue("not-yet").canPromote)
        assertTrue("用满才允许", rows.getValue("ready").canPromote)
    }

    @Test
    fun `T3_转正后正文头变active_且索引多一行`() {
        val file = draft(uses = SkillStore.PROMOTE_USES)
        val row = draftSkillRows(projectRoot, globalRoot).single()
        assertEquals("转正前索引区是空的", null, pf().read(PersonaLayer.C_PROJECT))

        val result = commitSkillDraft(
            row = row,
            newBody = promoteBody(row.body),
            stillDraft = false,
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            personaFiles = pf(),
            backupStamp = "20261018-230000",
        )

        assertTrue("转正要写成：$result", result is SkillWriteResult.Written)
        val written = result as SkillWriteResult.Written
        val head = file.readText().lineSequence().first()
        assertTrue("正文头变 active：$head", head.contains("status=active"))
        assertEquals("uses 原样保留", SkillStore.PROMOTE_USES, SkillStore.usesOf(file.readText()))
        assertEquals("statusOf 也认它已正式", "active", SkillStore.statusOf(file.readText()))
        assertTrue("索引多一行", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains(SkillRules.INDEX_MARK))
        assertTrue("索引里有它的名字", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("trial-a"))
        // 数据层 132 行：`draftMark = if (proposal.draft) … else ""`——转正走 draft=false 的提案，
        // 所以索引行不该再挂「未验证」。这条把「移到索引区」说圆：不只是多一行，还是**正式**的一行。
        assertTrue(
            "转正后的索引行不带未验证标（草稿期才有的那个提醒要摘掉）",
            !pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("未验证"),
        )
        assertTrue("索引写成功要如实回报", written.indexUpdated)
        assertEquals("转正后它从草稿区消失（移到上面的索引区）", emptyList<SkillDraftRow>(), draftSkillRows(projectRoot, globalRoot))
    }

    @Test
    fun `T3_用一次之后uses真加一且仍是草稿_索引还标未验证`() {
        // 这条走完整来路：先用 SkillStore 建草稿（索引里带未验证标），再点「用一次」
        val proposal = com.example.zhengdao.baopu.SkillProposal(
            scope = SkillScope.PROJECT, name = "trial-b", body = "# 流程\n先填必填项",
            reason = "验收", draft = true,
        )
        val created = SkillStore.apply(
            SkillStore.plan(proposal, projectRoot, globalRoot, "20261018-220000"), pf(), "",
        ) as SkillWriteResult.Written
        assertTrue("草稿也进索引", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("草稿·未验证"))

        val row = draftSkillRows(projectRoot, globalRoot).single()
        val r = commitSkillDraft(
            row = row, newBody = useOnceBody(row.body), stillDraft = true,
            projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = pf(),
            backupStamp = "20261018-223000",
        )
        assertTrue("写回成功：$r", r is SkillWriteResult.Written)

        val text = created.file.readText()
        assertEquals("uses+1，不是重置成 0", 1, SkillStore.usesOf(text))
        assertEquals("status 仍是 draft", "draft", SkillStore.statusOf(text))
        assertTrue("转正按钮这时还不该出现", draftSkillRows(projectRoot, globalRoot).single().canPromote.not())
        assertTrue("索引行继续带未验证标（C 段口径）", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("草稿·未验证"))
        assertTrue("写前备份照常留一份", SkillStore.backupsOf(created.file).isNotEmpty())
    }

    @Test
    fun `T3_转正按钮只在canPromote为真时渲染`() {
        val src = settings()
        val guard = src.indexOf("if (row.canPromote) {")
        val button = src.indexOf("onClick = { promote(row) }")
        assertTrue("转正按钮得在界面上", button >= 0)
        assertTrue("而且外面必须包着数据层的判据（T3：不许无条件出现）", guard in 0 until button)
        assertEquals("promote 只有那一条入口，别处不许再调", 1, src.split("onClick = { promote(row) }").size - 1)
        assertTrue("用一次按钮不受次数限制（随时能记）", src.contains("onClick = { useOnce(row) }"))
    }

    // ── T4：写盘只经 SkillStore ─────────────────────────────────────────────

    @Test
    fun `T4_UI里没有一处自己写SKILL_md`() {
        val both = card() + "\n" + settings()
        assertFalse("UI 不许出现 writeText（正文只能经 SkillStore.apply）", both.contains(".writeText("))
        assertFalse("同样不许 outputStream", both.contains("outputStream("))
        assertFalse("同样不许 copyTo/copyRecursively 造正文", both.contains("copyRecursively("))
        // 判别器正例：同一段源码里必须有那条唯一合法的写口
        assertTrue("commitSkillDraft 走 SkillStore.apply", card().contains("return SkillStore.apply("))
        assertEquals("SkillEditorDialog 那处＋commitSkillDraft 这处，写口共两处", 2, card().split("SkillStore.apply(").size - 1)
    }

    @Test
    fun `T4_两个按钮都只调同一个写口`() {
        val src = settings()
        assertEquals("用一次／转正各一处，共两处", 2, src.split("commitSkillDraft(").size - 1)
        assertTrue("草稿区列表只有一个来源", src.contains("draftSkillRows(projectRoot, globalRoot)"))
    }

    @Test
    fun `T4_目录名与技能名不一致时拒绝写_不另建一份`() {
        val weird = makeSkill(SkillScope.PROJECT, "bad name!", SkillStore.draftHeader("draft", 3) + "\n正文")
        val row = draftSkillRows(projectRoot, globalRoot).single()
        val r = commitSkillDraft(
            row = row, newBody = promoteBody(row.body), stillDraft = false,
            projectRoot = projectRoot, globalRoot = globalRoot, personaFiles = pf(), backupStamp = "s",
        )
        assertTrue("脏名字要拒绝而不是在旁边另建：$r", r is SkillWriteResult.Rejected)
        assertTrue("原件一个字没动", weird.readText().contains("正文"))
        assertFalse("没被安全化成第二个目录", File(SkillRules.projectSkillDir(projectRoot), "badname").exists())
    }

    // ── T5：模板预填不落盘 ──────────────────────────────────────────────────

    @Test
    fun `T5_模板预填只是文本_点之前点之后盘上什么都没有`() {
        val before = projectRoot.walkTopDown().filter { it.isFile }.count()

        val filled = platformTemplateBody()

        assertEquals("灌进正文框的就是那份模板", SkillStore.PLATFORM_SKILL_TEMPLATE, filled)
        assertTrue("模板四段齐全", filled.contains("① 平台 / 页面") && filled.contains("④ 已知坑"))
        assertEquals("预填之后盘上一个文件都没多", before, projectRoot.walkTopDown().filter { it.isFile }.count())
        assertEquals("全局层也没被碰", 0, globalRoot.walkTopDown().filter { it.isFile }.count())
        assertFalse("连技能目录都不许建出来", SkillRules.projectSkillDir(projectRoot).exists())
    }

    @Test
    fun `T5_预填只改正文框_名字留空且写盘仍要过确认卡`() {
        val src = card()
        assertTrue("判别器正例：按钮只赋给 body", src.contains("onClick = { body = platformTemplateBody() }"))
        assertFalse("预填不许顺手把名字也写了", src.contains("onClick = { body = platformTemplateBody(); name"))
        assertTrue("确认屏那条保存路还在（预填绕不过它）", src.contains("enabled = plan.blocked == null"))
        assertTrue("界面上要写明预填不写盘", src.contains("R.string.skill_use_platform_template_hint"))
    }

    // ── T6：中英对称 ────────────────────────────────────────────────────────

    @Test
    fun `T6_本批新键中英都有`() {
        newKeys.forEach { key ->
            assertTrue("缺中文键 $key", zh().contains("name=\"$key\""))
            assertTrue("缺英文键 $key", en().contains("name=\"$key\""))
        }
    }

    @Test
    fun `T6_同一个键的占位符中英一致`() {
        newKeys.forEach { key ->
            val z = placeholders(zh(), key)
            val e = placeholders(en(), key)
            assertEquals("$key 的中英占位符要一致（中文 $z / 英文 $e）", z, e)
        }
    }

    @Test
    fun `T6_已用N分M那条实义非空`() {
        Regex("""name="skill_draft_uses">(.*?)</string>""").find(zh())?.groupValues?.get(1).let {
            assertTrue("中文文案不许是空串", !it.isNullOrBlank())
        }
        Regex("""name="skill_draft_uses">(.*?)</string>""").find(en())?.groupValues?.get(1).let {
            assertTrue("英文文案不许是空串", !it.isNullOrBlank())
        }
    }

    // ── C 段：文案对齐现状（派工单明令别再改回去）───────────────────────────

    @Test
    fun `C_草稿勾选说明仍是会进索引但标未验证`() {
        val line = Regex("""name="skill_new_draft">(.*?)</string>""").find(zh())?.groupValues?.get(1).orEmpty()
        assertTrue("23A 之后草稿确实进索引，说明不许回改：$line", line.contains("草稿会进索引"))
        assertTrue(line.contains("未验证"))
        assertTrue("编辑屏仍在渲染这条说明", card().contains("R.string.skill_new_draft"))
    }

    @Test
    fun `C_删除归档成deleted那句说明保持`() {
        val line = Regex("""name="skill_review_desc">(.*?)</string>""").find(zh())?.groupValues?.get(1).orEmpty()
        assertTrue("归档口径不许被改回硬删：$line", line.contains(".deleted-"))
        assertTrue("不硬删", line.contains("不硬删"))
    }

    // ── 红线自查：本批不许把数据层的事搬到 UI 里重算 ─────────────────────────

    @Test
    fun `红线_转正次数只在数据层定义`() {
        val both = card() + "\n" + settings()
        assertFalse("UI 不许自己立 PROMOTE_USES 常量", both.contains("const val PROMOTE_USES"))
        assertFalse("UI 不许自己判草稿状态（那是列表纯函数的事）", settings().contains("== \"draft\""))
        assertTrue("判别器正例：能不能转正问的是 SkillStore.canPromote", card().contains("SkillStore.canPromote(body)"))
    }
}
