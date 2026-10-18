// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第三十四批 §4 判据 U1–U6（技能**能看见**（详情）·**能找回**（已归档）·**能改**（编辑））。
//
// 两层打法沿用 32 批（BaopuSkillTrialUiTest）的惯例，因为本批改的只有「界面消费点」：
//   1. **行为层**：真临时目录里造出正文／备份／归档件，跑 skillDetailRow／archivedSkillRows／
//      restoreArchived／deleteArchived，断言**盘上到底变成什么样**——U1/U2/U3/U4 的主证据在这儿；
//   2. **源码层**：读剥掉注释后的两个 UI 文件，钉「按钮什么时候才许出现」「写口只有那几条」
//      「三处读不到都得说话」这几条（U2 的出现条件、U4 的名字锁定、U5、U6）。
// 每条「不许出现 X」都配判别器正例（同一段源码里必须有那句新写法），防门禁空跑。
//
// 负例（派工单 §5 点名的三条）由 tools 外的临时脚本按字节造红，读数记在交付报告 §3：
//   N1 剥状态头 ⇒ U1 红；N2 恢复按钮无条件出现 ⇒ U2 红；N3 归档恢复改成静默覆盖 ⇒ U3 红。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaRules
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

/**
 * 第三十四批 · 技能详情／已归档／编辑（只动 UI）。
 *
 * ## 为什么行为层能跑而不必上真机
 *
 * 「详情显示的到底是不是盘上那份」「恢复到底搬了哪个文件」「没确认时删没删」——
 * 这些都不依赖 Compose 起没起来，输入输出是纯数据＋临时目录，JVM 能真跑。
 * 真机要验的是排版手感（360dp 那栏正文滚得顺否、整行点得到否、剪贴板落不落得下），列在交付报告 §6。
 */
class BaopuSkillInspectUiTest {

    // ── 源码读取（工作目录 = app/；判据一律读剥掉注释后的文本）──────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun card() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCard.kt")

    private fun settings() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun zh() = repoFile("src/main/res/values/strings.xml").readText()

    private fun en() = repoFile("src/main/res/values-en/strings.xml").readText()

    // ── 盘上现场（真临时目录）──────────────────────────────────────────────

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd34-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd34-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun skillDir(scope: SkillScope): File = when (scope) {
        SkillScope.PROJECT -> SkillRules.projectSkillDir(projectRoot)
        SkillScope.GLOBAL -> SkillRules.globalSkillDir(globalRoot)
    }

    /** 造一份技能正文（测试夹具允许直接写盘；产品代码那条路都没了）。 */
    private fun makeSkill(scope: SkillScope, name: String, body: String): File {
        val dir = File(skillDir(scope), name).apply { mkdirs() }
        val file = File(dir, SkillRules.SKILL_FILE)
        file.writeText(body, Charsets.UTF_8)
        return file
    }

    /** 造一份**备份**文件（形状照 SkillStore：`<名>.SKILL.md.bak-<时间戳>`）。 */
    private fun makeBackup(scope: SkillScope, skillName: String, stamp: String): File {
        val dir = File(skillDir(scope), skillName).apply { mkdirs() }
        return File(dir, "$skillName${SkillStore.BACKUP_MARK}$stamp").apply { writeText("旧版正文", Charsets.UTF_8) }
    }

    /** 造一份**归档件**（形状照 SkillStore.archive：`SKILL.md.deleted-<时间戳>`，正文不在原位）。 */
    private fun makeArchived(
        scope: SkillScope,
        name: String,
        body: String,
        stamp: String,
        alsoMakeLive: Boolean = false,
    ): File {
        val dir = File(skillDir(scope), name).apply { mkdirs() }
        if (alsoMakeLive) File(dir, SkillRules.SKILL_FILE).writeText("占位正文，一个字都不许被动", Charsets.UTF_8)
        return File(dir, "${SkillRules.SKILL_FILE}$ARCHIVED_MARK$stamp")
            .apply { writeText(body, Charsets.UTF_8) }
    }

    /** 把人设某层填到 count 条索引（超上限那条判据的夹具；仍然走合法写口）。 */
    private fun seedIndex(layer: PersonaLayer, count: Int) {
        val scope = if (layer == PersonaLayer.B_GLOBAL) MemoryScope.GLOBAL else MemoryScope.PROJECT
        val text = (1..count).joinToString("\n") {
            SkillRules.renderIndexLine(
                SkillRules.IndexLine(scope, "seed$it", "占位场景第 $it 条"),
            ) + "\n"
        }
        val p = PersonaRules.propose(layer, pf().read(layer).orEmpty(), text, "测试夹具：灌索引")
        assertNotNull("夹具要能写人设", p)
        assertTrue("夹具写盘要成", pf().applyProposal(p!!))
    }

    /** 本批新加的二十八个键（U6 的靶子；增删键时这里同步）。 */
    private val newKeys = listOf(
        "skill_detail_title", "skill_detail_scope", "skill_detail_status",
        "skill_detail_status_draft", "skill_detail_status_active", "skill_detail_uses",
        "skill_detail_file", "skill_missing_file", "skill_copy_all", "skill_copied_chars",
        "skill_close", "skill_detail_edit", "skill_restore_backup_at",
        "skill_archived_group", "skill_archived_none", "skill_archived_at",
        "skill_archived_restore", "skill_archived_delete", "skill_archived_restored",
        "skill_archived_occupied", "skill_archived_io_failed", "skill_archived_index_full",
        "skill_archived_index_skipped", "skill_archived_confirm", "skill_archived_deleted",
        "skill_edit_title", "skill_name_locked_hint", "skill_edit_saved",
    )

    /** 取某个键里的占位符（`%1$d`／`%1$s`…），中英必须一一对上。 */
    private fun placeholders(text: String, key: String): List<String> {
        val line = Regex("""name="$key">(.*?)</string>""").find(text)?.groupValues?.get(1).orEmpty()
        return Regex("%\\d+\\$[a-z]").findAll(line).map { it.value }.toList()
    }

    /** 取某个键的文案原文（不许是空串）。 */
    private fun value(text: String, key: String): String =
        Regex("""name="$key">(.*?)</string>""").find(text)?.groupValues?.get(1).orEmpty()

    // ── U1：详情显示的是盘上原文（含状态头，一字不剥）───────────────────────

    @Test
    fun `U1_详情正文与盘上那份逐字相同_状态头一个字不剥`() {
        val body = "<!-- status=draft uses=2 -->\n# 摸索中的流程\n\n中段也有个注释 <!-- note: 别看漏 -->\n还没验完。"
        val file = makeSkill(SkillScope.PROJECT, "trial-a", body)
        val raw = file.readText(Charsets.UTF_8)

        val row = skillDetailRow("trial-a", SkillScope.PROJECT, projectRoot, globalRoot)

        assertTrue("文件在 ⇒ 读得出来", row.readable)
        assertEquals("正文必须逐字＝盘上那份", raw, row.body)
        assertEquals("显示的那屏也是同一份（U1 的靶子）", raw, skillDetailDisplayBody(row))
        assertTrue("状态头还在第一行：${skillDetailDisplayBody(row).take(40)}", skillDetailDisplayBody(row).startsWith("<!--"))
        assertTrue("uses=2 要看得见，不许替用户藏起来", skillDetailDisplayBody(row).contains("uses=2"))
        assertTrue("正文中段的注释更不许被吃掉", skillDetailDisplayBody(row).contains("note: 别看漏"))
    }

    @Test
    fun `U1_顶部四行读的是数据层的数_不自己数`() {
        makeSkill(SkillScope.GLOBAL, "g-a", "<!-- status=draft uses=5 -->\n# 全局草稿")

        val row = skillDetailRow("g-a", SkillScope.GLOBAL, projectRoot, globalRoot)

        assertEquals("status 由 SkillStore.statusOf 给", "draft", row.status)
        assertTrue("草稿与否由同一个读数派生", row.isDraft)
        assertEquals("uses 由 SkillStore.usesOf 给", 5, row.uses)
        assertEquals("分母是数据层的 PROMOTE_USES", SkillStore.PROMOTE_USES, row.promoteAt)
        assertEquals("作用域如实是全局", SkillScope.GLOBAL, row.scope)
        assertEquals("给人看的落点是相对路径", "技能/g-a/SKILL.md", row.relPath)
    }

    @Test
    fun `U1_无状态头的正式技能状态读成active`() {
        makeSkill(SkillScope.PROJECT, "official", "# 正式流程\n没有头。")

        val row = skillDetailRow("official", SkillScope.PROJECT, projectRoot, globalRoot)

        assertEquals("缺头 ⇒ 数据层认 active", "active", row.status)
        assertFalse(row.isDraft)
        assertEquals(0, row.uses)
        assertEquals("正文一个字不加不减", "# 正式流程\n没有头。", skillDetailDisplayBody(row))
    }

    @Test
    fun `U1_空文件也是原文_不许当读不到`() {
        val file = makeSkill(SkillScope.PROJECT, "empty", "")

        val row = skillDetailRow("empty", SkillScope.PROJECT, projectRoot, globalRoot)

        assertTrue("空正文也是盘上真实存在的一份", row.readable)
        assertEquals("", row.body)
        assertEquals("", skillDetailDisplayBody(row))
        assertEquals(file.readText(), skillDetailDisplayBody(row))
    }

    // ── U2：「恢复到上一版」的条件出现＋显示备份自己的时间───────────────────

    @Test
    fun `U2_没有备份时恢复条件是假_时间也是null`() {
        makeSkill(SkillScope.PROJECT, "no-bak", "# 新写的\n还没备份过。")

        val row = skillDetailRow("no-bak", SkillScope.PROJECT, projectRoot, globalRoot)

        assertNull("没有备份文件", row.backupName)
        assertNull("那就没有时间可显示", row.backupAt)
        assertFalse("恢复按钮这时不许出现", row.canRestore)
    }

    @Test
    fun `U2_有备份时显示的是那份文件名里的真实时间`() {
        makeSkill(SkillScope.PROJECT, "has-bak", "# 现版")
        val backup = makeBackup(SkillScope.PROJECT, "has-bak", "20260101-093000")

        val row = skillDetailRow("has-bak", SkillScope.PROJECT, projectRoot, globalRoot)

        assertTrue("有备份才出按钮", row.canRestore)
        assertEquals(backup.name, row.backupName)
        assertEquals("显示的必须是这份备份自己那串时间", "20260101-093000", row.backupAt)
        assertEquals("切时间戳只用字符串切，不现取 now", "20260101-093000", backupStampOf(backup.name))
    }

    @Test
    fun `U2_切不出时间时返回null_不许兜底成今天`() {
        assertNull("没有备份 ⇒ null", backupStampOf(null))
        assertNull("形状不对 ⇒ null，不猜", backupStampOf("SKILL.md.bak-20260101"))
        assertNull("空尾巴 ⇒ null", backupStampOf("x${SkillStore.BACKUP_MARK}"))
        assertFalse("界面文件里根本不许出现取 now 的那两个入口", card().contains("TimeFormats"))
        assertFalse("也不许 System.currentTimeMillis", card().contains("System.currentTimeMillis"))
    }

    @Test
    fun `U2_多份备份取最近那份的时间`() {
        makeSkill(SkillScope.PROJECT, "two-bak", "# 现版")
        makeBackup(SkillScope.PROJECT, "two-bak", "20260101-080000")
        makeBackup(SkillScope.PROJECT, "two-bak", "20260102-120000")

        val row = skillDetailRow("two-bak", SkillScope.PROJECT, projectRoot, globalRoot)

        assertEquals("新→旧由数据层 backupsOf 定", "20260102-120000", row.backupAt)
    }

    @Test
    fun `U2_恢复按钮只在canRestore为真那一支里渲染`() {
        val src = card()
        val guard = src.indexOf("if (detail.canRestore) {")
        val button = src.indexOf("R.string.skill_restore_backup_at")
        assertTrue("判别器正例：界面上得有这条按钮", button >= 0)
        assertTrue("而且外面必须包着 canRestore 那道闸（负例 N2 打的就是这里）", guard in 0 until button)
        assertEquals("这条按钮文案全文件只许出现一次", 1, src.split("skill_restore_backup_at").size - 1)
        assertEquals("出现条件也只写一次", 1, src.split("if (detail.canRestore) {").size - 1)
    }

    // ── U3：归档列表只收 .deleted- ／恢复撞名拒绝／彻底删除要二次确认─────────

    @Test
    fun `U3_归档列表只收deleted形状_正文与备份都不算`() {
        makeSkill(SkillScope.PROJECT, "live", "# 还在用的")
        makeBackup(SkillScope.PROJECT, "live", "20260101-080000")
        val gone = makeArchived(SkillScope.PROJECT, "gone", "<!-- status=draft uses=1 -->\n# 删掉的", "20260103-101500")

        val rows = archivedSkillRows(projectRoot, globalRoot)

        assertEquals("一份归档就是一行，别的形状一个不进", 1, rows.size)
        assertEquals("gone", rows.single().name)
        assertEquals(gone, rows.single().file)
        assertEquals("技能/gone/SKILL.md", rows.single().targetRelPath)
        assertEquals("技能/gone/SKILL.md.deleted-20260103-101500", rows.single().relPath)
        assertEquals("归档时间取自文件名", "20260103-101500", rows.single().archivedAt)
        assertEquals("恢复目标就是同目录那份 SKILL.md", File(gone.parentFile, SkillRules.SKILL_FILE), rows.single().target)
    }

    @Test
    fun `U3_两层各扫各的_同一名字的几份按新到旧排`() {
        makeArchived(SkillScope.PROJECT, "same", "项目那份", "20260101-000000")
        makeArchived(SkillScope.GLOBAL, "same", "全局那份", "20260102-000000")
        val older = makeArchived(SkillScope.PROJECT, "same", "更早的项目那份", "20251231-000000")

        val rows = archivedSkillRows(projectRoot, globalRoot)

        assertEquals("两层同名各算各的（查找优先级是数据层的事，这里只做列表）", 3, rows.size)
        assertEquals(listOf(SkillScope.PROJECT, SkillScope.PROJECT, SkillScope.GLOBAL), rows.map { it.scope })
        assertEquals("同一目录里新→旧", "20260102-000000", rows.first { it.scope == SkillScope.GLOBAL }.archivedAt)
        assertEquals(older.name, rows[1].file.name)
    }

    @Test
    fun `U3_扫描不递归_深一层的deleted件不算`() {
        val deep = File(File(File(skillDir(SkillScope.PROJECT), "outer"), "inner"), "deeper")
        deep.mkdirs()
        File(deep, "${SkillRules.SKILL_FILE}$ARCHIVED_MARK" + "20260101-000000")
            .writeText("埋得很深的一份", Charsets.UTF_8)

        assertEquals("只扫 技能/ 本身那一层，埋两层的进不了列表", emptyList<ArchivedSkillRow>(), archivedSkillRows(projectRoot, globalRoot))
        assertFalse("判别器正例：列表靠列目录，不用 walkTopDown 之类的递归", card().contains("walkTopDown"))
        assertTrue("软链要跳过（跟 ListDirTool 同一纪律）", card().contains("BaopuGuardCore.isSymlink(f)"))
        assertTrue("归档前缀取自数据层那两段常量", card().contains("SkillRules.SKILL_FILE + ARCHIVED_MARK"))
    }

    @Test
    fun `U3_恢复撞名必须拒绝并指名占位者_占位内容一字不动`() {
        val live = makeSkill(SkillScope.PROJECT, "occupied", "占位正文，一个字都不许被动")
        val before = live.readText(Charsets.UTF_8)
        val gone = makeArchived(SkillScope.PROJECT, "occupied", "旧版那份", "20260101-000000", alsoMakeLive = true)

        val outcome = restoreArchived(
            archivedSkillRows(projectRoot, globalRoot).single(),
            projectRoot, globalRoot, pf(),
        )

        assertTrue("目标已存在 ⇒ 必须是 Occupied，不是静默覆盖：$outcome", outcome is ArchivedOutcome.Occupied)
        assertEquals("要指名占位者是谁", "技能/occupied/SKILL.md", (outcome as ArchivedOutcome.Occupied).target)
        assertEquals("占位那份正文一个字节没被换", before, live.readText(Charsets.UTF_8))
        assertTrue("归档件继续留在原地，等用户自己处置", gone.isFile)
        assertEquals("索引也没被偷偷动", null, pf().read(PersonaLayer.C_PROJECT))
    }

    @Test
    fun `U3_恢复成功后正文回位且只搬文件不重写`() {
        val gone = makeArchived(SkillScope.PROJECT, "back", "<!-- status=active uses=7 -->\n# 找回的流程", "20260105-200000")
        val saved = gone.readText(Charsets.UTF_8)

        val outcome = restoreArchived(
            archivedSkillRows(projectRoot, globalRoot).single(),
            projectRoot, globalRoot, pf(),
        )

        assertTrue("该成的要如实报成 Restored：$outcome", outcome is ArchivedOutcome.Restored)
        assertEquals("索引那一半也补上了", RestoredIndex.Added, (outcome as ArchivedOutcome.Restored).index)
        val target = File(File(skillDir(SkillScope.PROJECT), "back"), SkillRules.SKILL_FILE)
        assertTrue("正文回到原位", target.isFile)
        assertEquals("搬文件就是搬文件：内容与归档那份逐字相同（没被 UI 重排过）", saved, target.readText(Charsets.UTF_8))
        assertFalse("原处的 .deleted 不再存在（不是复制一份）", gone.isFile)
        assertEquals("归档列表少一行", emptyList<ArchivedSkillRow>(), archivedSkillRows(projectRoot, globalRoot))
        val index = pf().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("索引行有了：$index", index.contains(SkillRules.INDEX_MARK) && index.contains("back"))
    }

    @Test
    fun `U3_草稿件恢复后索引继续带未验证提醒`() {
        makeArchived(SkillScope.PROJECT, "draft-back", "<!-- status=draft uses=1 -->\n# 还没验完", "20260105-210000")

        restoreArchived(archivedSkillRows(projectRoot, globalRoot).single(), projectRoot, globalRoot, pf())

        val index = pf().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("草稿恢复后仍是草稿口径：$index", index.contains("草稿·未验证"))
        assertEquals("状态头跟着原文一起回来", "draft", SkillStore.statusOf(File(File(skillDir(SkillScope.PROJECT), "draft-back"), SkillRules.SKILL_FILE).readText()))
    }

    @Test
    fun `U3_索引已满时如实报OverCap_不假装找回做完了`() {
        seedIndex(PersonaLayer.C_PROJECT, SkillRules.INDEX_CAP)
        makeArchived(SkillScope.PROJECT, "late-back", "# 来得太晚", "20260105-220000")

        val outcome = restoreArchived(
            archivedSkillRows(projectRoot, globalRoot).single(),
            projectRoot, globalRoot, pf(),
        )

        assertEquals("正文能搬回来", true, File(File(skillDir(SkillScope.PROJECT), "late-back"), SkillRules.SKILL_FILE).isFile)
        assertTrue("但索引没加上要说实话：$outcome", outcome is ArchivedOutcome.Restored)
        assertEquals(RestoredIndex.OverCap, (outcome as ArchivedOutcome.Restored).index)
        assertFalse("索引里不许有它", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("late-back"))
    }

    @Test
    fun `U3_目录名不合法时不写假索引_BadName`() {
        makeArchived(SkillScope.PROJECT, "bad name!", "# 脏名字", "20260105-230000")

        val outcome = restoreArchived(
            archivedSkillRows(projectRoot, globalRoot).single(),
            projectRoot, globalRoot, pf(),
        )

        assertTrue("$outcome", outcome is ArchivedOutcome.Restored)
        assertEquals("索引行会指向一个不存在的目录 ⇒ 宁可不写", RestoredIndex.BadName, (outcome as ArchivedOutcome.Restored).index)
        assertEquals("正文照样搬回来了", true, File(File(skillDir(SkillScope.PROJECT), "bad name!"), SkillRules.SKILL_FILE).isFile)
        assertEquals("人设一个字没动", null, pf().read(PersonaLayer.C_PROJECT))
    }

    @Test
    fun `U3_彻底删除没确认时一个字节都不动`() {
        val gone = makeArchived(SkillScope.PROJECT, "keep", "还没确认就删等于误删", "20260106-000000")

        val outcome = deleteArchived(archivedSkillRows(projectRoot, globalRoot).single(), confirmed = false)

        assertTrue("第一击只许拿到 NeedsConfirm：$outcome", outcome is ArchivedOutcome.NeedsConfirm)
        assertEquals("要指名是哪一份在等确认", "技能/keep/SKILL.md.deleted-20260106-000000", (outcome as ArchivedOutcome.NeedsConfirm).target)
        assertTrue("文件还在原地", gone.isFile)
        assertEquals("内容也没变", "还没确认就删等于误删", gone.readText(Charsets.UTF_8))
    }

    @Test
    fun `U3_确认后真删且只删归档那一份`() {
        val gone = makeArchived(SkillScope.PROJECT, "doomed", "这一份要没了", "20260106-010000")
        val row = archivedSkillRows(projectRoot, globalRoot).single()

        val outcome = deleteArchived(row, confirmed = true)

        assertTrue("$outcome", outcome is ArchivedOutcome.Deleted)
        assertFalse("归档件真被清掉", gone.isFile)
        assertEquals("报的也是它的路径", row.relPath, (outcome as ArchivedOutcome.Deleted).target)
        assertEquals("列表空了", emptyList<ArchivedSkillRow>(), archivedSkillRows(projectRoot, globalRoot))
    }

    @Test
    fun `U3_界面只有一条删除入口且第二击才带confirmed`() {
        val src = settings()
        assertEquals("deleteArchived 只有一个调用点", 1, src.split("deleteArchived(").size - 1)
        assertTrue("判别器正例：确认位由 confirmingDelete 比对得出，不是写死 true", src.contains("confirmingDelete?.absolutePath == row.file.absolutePath"))
        assertFalse("不许出现无条件删除的写法", src.contains("deleteArchived(row, true)"))
        assertTrue("关掉面板要清掉确认态", src.contains("confirmingDelete = null"))
    }

    // ── U4：写盘唯一走 SkillStore.apply；名字锁定────────────────────────────

    @Test
    fun `U4_两个UI文件里没有一处自己写正文`() {
        val both = card() + "\n" + settings()
        assertFalse("正文只能经 SkillStore.apply 落地", both.contains(".writeText("))
        assertFalse("也不许绕道流", both.contains("outputStream("))
        assertFalse("归档恢复不许复制", both.contains("copyRecursively("))
        assertTrue("判别器正例：编辑保存回到 32 批那条唯一写口", card().contains("newBody = editedBody(originalBody, body)"))
        assertEquals("写口总数仍然是 2（SkillEditorDialog 那处＋commitSkillDraft 那处）", 2, card().split("SkillStore.apply(").size - 1)
    }

    @Test
    fun `U4_恢复与彻底删除只搬文件_不碰人设以外正文的写法`() {
        makeArchived(SkillScope.PROJECT, "moved", "# 搬回来的那份", "20260106-020000")
        val row = archivedSkillRows(projectRoot, globalRoot).single()
        val saved = row.file.readText(Charsets.UTF_8)

        restoreArchived(row, projectRoot, globalRoot, pf())

        assertEquals("搬回来的内容与归档逐字相同", saved, row.target.readText(Charsets.UTF_8))
        assertTrue("本批新增的两个动作都只改名／删除", card().contains("row.file.renameTo(row.target)"))
        assertTrue(card().contains("row.file.delete()"))
    }

    @Test
    fun `U4_编辑保住状态头_试用次数不许被清零`() {
        val edited = editedBody("<!-- status=draft uses=2 -->\n旧正文", "改过的正文")

        assertEquals("<!-- status=draft uses=2 -->\n改过的正文", edited)
        assertEquals("uses 还是 2", 2, SkillStore.usesOf(edited))
        assertEquals("status 还是 draft", "draft", SkillStore.statusOf(edited))
        assertEquals("正式技能没头就别加：原样交回", "改过的正文", editedBody("# 旧版\n没有状态头", "改过的正文"))
    }

    @Test
    fun `U4_编辑后盘上正文与索引都跟着变_且写前留了备份`() {
        val file = makeSkill(SkillScope.PROJECT, "edit-me", "<!-- status=draft uses=2 -->\n# 旧标题\n旧正文")
        val d = skillDetailRow("edit-me", SkillScope.PROJECT, projectRoot, globalRoot)

        val result = commitSkillDraft(
            row = SkillDraftRow(d.name, d.scope, d.file, d.body, d.uses, canPromote = false),
            newBody = editedBody(d.body, "# 新标题\n改过的正文"),
            stillDraft = d.isDraft,
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            personaFiles = pf(),
            backupStamp = "20260106-030000",
        )

        assertTrue("写要写成：$result", result is SkillWriteResult.Written)
        assertEquals("正文换了", "# 新标题\n改过的正文", stripStatusHeader(file.readText(Charsets.UTF_8)))
        assertEquals("头还在，uses 没被清", 2, SkillStore.usesOf(file.readText(Charsets.UTF_8)))
        assertTrue("旧版留了一份备份", SkillStore.backupsOf(file).any { it.name.endsWith("20260106-030000") })
        assertTrue("索引行是草稿口径（未验证标还在）", pf().read(PersonaLayer.C_PROJECT).orEmpty().contains("未验证"))
    }

    @Test
    fun `U4_盘上目录名不合法时编辑被拒绝_不在旁边另建一份`() {
        val weird = makeSkill(SkillScope.PROJECT, "bad name!", "<!-- status=draft uses=3 -->\n原件")
        val d = skillDetailRow("bad name!", SkillScope.PROJECT, projectRoot, globalRoot)

        val r = commitSkillDraft(
            row = SkillDraftRow(d.name, d.scope, d.file, d.body, d.uses, canPromote = false),
            newBody = editedBody(d.body, "改过的"),
            stillDraft = true,
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            personaFiles = pf(),
            backupStamp = "s",
        )

        assertTrue("脏名字要拒绝：$r", r is SkillWriteResult.Rejected)
        assertEquals("原件一个字没动", "<!-- status=draft uses=3 -->\n原件", weird.readText(Charsets.UTF_8))
        assertFalse("没被安全化成第二个目录", File(skillDir(SkillScope.PROJECT), "badname").exists())
    }

    @Test
    fun `U4_名字与作用域在编辑态是锁死的`() {
        val src = card()
        assertTrue("初值就是盘上那个名字（负例 N4 打的是这条 enabled）", src.contains("enabled = lockedName == null"))
        assertTrue("输入框改不动它", src.contains("onValueChange = { if (lockedName == null) name = it }"))
        assertTrue("初值来自 lockedName", src.contains("mutableStateOf(lockedName.orEmpty())"))
        assertTrue("比较落点用的是盘上真实路径（不是安全化后的）", src.contains("file = skillBodyFile(lockedName, scope, projectRoot, globalRoot)"))
        assertTrue("界面上要说明为什么锁着", src.contains("R.string.skill_name_locked_hint"))
        assertTrue("锁定的判据在正文里至少出现四次（名字框＋两个作用域钮＋草稿勾）", src.split("lockedName == null").size - 1 >= 4)
    }

    // ── U5：读不到时三处都要说话（详情／备份恢复／归档列表）─────────────────

    @Test
    fun `U5_详情那处_文件不在时给的是带路径的一句话`() {
        val row = skillDetailRow("gone", SkillScope.PROJECT, projectRoot, globalRoot)

        assertFalse(row.readable)
        assertEquals("空正文但路径要说得出", "", row.body)
        assertEquals("技能/gone/SKILL.md", row.relPath)

        val src = card()
        assertTrue("判别器正例：读得到才显示正文", src.contains("if (detail.readable) {"))
        val missing = src.indexOf("R.string.skill_missing_file")
        assertTrue("读不到时必须换成那句话", missing >= 0)
        assertTrue("而且它在 readable 那支的后面（else 那一支）", src.indexOf("if (detail.readable) {") < missing)
        assertEquals("这条只许出现一次", 1, src.split("skill_missing_file").size - 1)
    }

    @Test
    fun `U5_备份恢复那处_拒绝也要带理由`() {
        val file = skillBodyFile("no-backup", SkillScope.PROJECT, projectRoot, globalRoot)
        file.parentFile?.mkdirs()
        file.writeText("# 没备份", Charsets.UTF_8)

        val r = SkillStore.restore(file, "20260106-040000")

        assertTrue("没备份要拒绝：$r", r is SkillWriteResult.Rejected)
        assertTrue("理由不是空串", (r as SkillWriteResult.Rejected).reason.isNotBlank())
        val src = settings()
        assertTrue("界面上这条拒绝也要把理由念出来", src.contains("ctx.getString(R.string.skill_restore_done)"))
        assertTrue("至少三处把 Rejected 的 reason 直接给用户", src.split("-> r.reason").size - 1 >= 3)
    }

    @Test
    fun `U5_归档那处_文件不见了与改名失败都要回报`() {
        val gone = makeArchived(SkillScope.PROJECT, "vanish", "要被别人删掉的一份", "20260106-050000")
        val row = archivedSkillRows(projectRoot, globalRoot).single()
        gone.delete() // 面板打开后别的进程动了盘

        val restore = restoreArchived(row, projectRoot, globalRoot, pf())
        val delete = deleteArchived(row, confirmed = true)

        assertTrue("恢复：$restore", restore is ArchivedOutcome.IoFailed)
        assertEquals("说的是盘上那个路径", row.relPath, (restore as ArchivedOutcome.IoFailed).target)
        assertTrue("原因不是空串", (restore).why.isNotBlank())
        assertTrue("删除：$delete", delete is ArchivedOutcome.IoFailed)
        assertFalse("目标位没被凭空造出来", row.target.isFile)

        val src = settings()
        listOf(
            "skill_archived_restored", "skill_archived_occupied", "skill_archived_io_failed",
            "skill_archived_confirm", "skill_archived_deleted", "skill_archived_index_full",
            "skill_archived_index_skipped",
        ).forEach { key ->
            assertTrue("归档七种下场每种都得有一句话：$key", src.contains("R.string.$key"))
        }
    }

    @Test
    fun `U5_列表空了也不许空白`() {
        val src = card()
        assertTrue("归档面板开着时列表变空要说「没有已归档的技能」", src.contains("R.string.skill_archived_none"))
        assertTrue("入口常驻：只把 enabled 关掉，不许整条消失", settings().contains("TextButton(enabled = archivedRows.isNotEmpty(), onClick = { archivedOpen = true })"))
    }

    // ── U6：中英对称，界面不硬编码中文───────────────────────────────────────

    @Test
    fun `U6_本批新键中英都有`() {
        newKeys.forEach { key ->
            assertTrue("缺中文键 $key", zh().contains("name=\"$key\""))
            assertTrue("缺英文键 $key", en().contains("name=\"$key\""))
        }
        assertEquals("键数对得上（漏加一边就红）", newKeys.size, newKeys.count { zh().contains("name=\"$it\"") })
    }

    @Test
    fun `U6_同一个键的占位符中英一致`() {
        newKeys.forEach { key ->
            assertEquals("$key 的中英占位符要一致", placeholders(zh(), key), placeholders(en(), key))
        }
        assertTrue("详情那条计数带分母", placeholders(zh(), "skill_detail_uses") == listOf("%1\$d", "%2\$d"))
        assertTrue("归档入口的计数不能少分母", placeholders(zh(), "skill_archived_group").contains("%1\$d"))
    }

    @Test
    fun `U6_本批新键的文案都不是空串`() {
        newKeys.forEach { key ->
            assertTrue("$key 中文不许空", value(zh(), key).isNotBlank())
            assertTrue("$key 英文不许空", value(en(), key).isNotBlank())
        }
    }

    @Test
    fun `U6_界面新增的文案全从strings取_没有硬编码中文`() {
        val both = card() + "\n" + settings()
        assertTrue("判别器正例：详情顶部走 stringResource", card().contains("stringResource(R.string.skill_detail_uses"))
        assertTrue("归档一句话也走 stringResource", card().contains("stringResource(R.string.skill_archived_at"))
        assertFalse("Toast 那几处取的是资源串与数据层理由，本文件不新增中文串字面量当界面文案", both.contains("\"文件不在了："))
        assertFalse("「彻底删除」这类按钮文案不许写在代码里", both.contains("\"彻底删除\""))
        assertFalse("「已归档（」同理", both.contains("\"已归档（"))
    }

    // ── 红线自查：本批不许把数据层的事搬到 UI 里重算─────────────────────────

    @Test
    fun `红线_状态次数上限全部问数据层要`() {
        val both = card() + "\n" + settings()
        assertFalse("UI 不许自己立 PROMOTE_USES", both.contains("const val PROMOTE_USES"))
        assertFalse("UI 不许自己立 INDEX_CAP", both.contains("const val INDEX_CAP"))
        assertFalse("分母硬编码成 3 就红了", card().contains("skill_detail_uses, detail.uses, 3"))
        assertTrue("判别器正例：分母取自 SkillStore", card().contains("promoteAt = SkillStore.PROMOTE_USES"))
        assertTrue("上限那句用 SkillRules.INDEX_CAP 现取", settings().contains("R.string.skill_archived_index_full, SkillRules.INDEX_CAP"))
        assertFalse("归档扫描不许另起目录", card().contains("File(root, \"skills\")"))
    }

    @Test
    fun `红线_详情与归档面板本身不写盘`() {
        val src = card()
        makeSkill(SkillScope.PROJECT, "quiet", "<!-- status=draft uses=1 -->\n# 只看不动")
        val before = projectRoot.walkTopDown().filter { it.isFile }.count()

        val row = skillDetailRow("quiet", SkillScope.PROJECT, projectRoot, globalRoot)

        assertEquals("读一遍详情，盘上文件数不变", before, projectRoot.walkTopDown().filter { it.isFile }.count())
        assertEquals("正文也没被重写", "<!-- status=draft uses=1 -->\n# 只看不动", row.body)
        assertTrue("详情对话框里没有写口", !src.substring(src.indexOf("internal fun SkillDetailDialog")).contains("SkillStore.apply("))
    }
}
