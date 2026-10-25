// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零五批 · 追加件（技能列表页）的判据。四条负例（追加件原话）逐条红：
//   ①新造第二套技能卡组件；②绕过 184 写盘口；③删除真删文件；④满 3 次转正不进决策中心。
// 红的定义与主单一致：这些行为被闸挡住，用例断言「挡住了」——源码门禁钉写法，功能用例钉行为。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.ReflectionChangeKind
import com.example.zhengdao.baopu.ReflectionCandidate
import com.example.zhengdao.baopu.ReflectionShape
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var global: File

    private fun setUp() {
        ws = tmp.newFolder("ws")
        global = tmp.newFolder("global")
    }

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun screen(): String =
        repoFile("src/main/java/com/example/zhengdao/ui/baopu/SkillLibraryScreen.kt").readText()

    private fun controller(): String =
        repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()

    private fun writeSkill(
        root: File,
        name: String,
        body: String,
    ): File {
        val dir = File(File(root, "技能"), name)
        dir.mkdirs()
        val f = File(dir, SkillRules.SKILL_FILE)
        f.writeText(body, Charsets.UTF_8)
        return f
    }

    private fun appendLedger(root: File, vararg fields: Pair<String, Any>) {
        val dir = File(root, "技能")
        dir.mkdirs()
        val o = org.json.JSONObject()
        for ((k, v) in fields) o.put(k, v)
        File(dir, "_变更.jsonl").appendText(o.toString() + "\n", Charsets.UTF_8)
    }

    private fun personaFiles(): PersonaFiles = PersonaFiles(globalRoot = global, projectRoot = ws)

    // ── 三组读数 ────────────────────────────────────────────────────────────

    @Test
    fun `三组读数_草稿正式退役各归各位`() {
        setUp()
        writeSkill(ws, "草稿A", SkillStore.draftHeader(status = "draft", uses = 1) + "\n# 草稿A\n正文")
        writeSkill(ws, "正式B", SkillStore.draftHeader(status = "active", uses = 5) + "\n# 正式B\n正文")
        writeSkill(global, "种子C", SkillStore.draftHeader(status = "draft", uses = 0) + "\n# 种子C\n正文")
        // 退役：正文软删成 .deleted-*（口径照 SkillStore.archive）
        val f = writeSkill(ws, "退役D", "# 退役D\n正文")
        assertTrue(SkillStore.archive(f, "20261025-120000") is SkillWriteResult.Written)

        val rows = skillLibraryRows(ws, global, lastUseMs = mapOf("正式B" to 123L))
        assertEquals(4, rows.size)
        val byName = rows.associateBy { it.name }
        assertEquals(SkillLibraryStatus.DRAFT, byName.getValue("草稿A").status)
        assertEquals(1, byName.getValue("草稿A").uses)
        assertEquals(SkillLibraryStatus.ACTIVE, byName.getValue("正式B").status)
        assertEquals(5, byName.getValue("正式B").uses)
        assertEquals(123L, byName.getValue("正式B").lastUseMs)
        assertEquals(SkillLibraryStatus.RETIRED, byName.getValue("退役D").status)
        assertEquals("20261025-120000", byName.getValue("退役D").archivedAt)
        assertTrue(byName.getValue("退役D").file.name.startsWith("SKILL.md.deleted-"))
        assertEquals(SkillScope.GLOBAL, byName.getValue("种子C").scope)
    }

    @Test
    fun `来源徽标_seed按安装账_reflect按反思器理由_其余manual`() {
        setUp()
        appendLedger(
            global, "name" to "种子C", "source" to "seed",
            "ts" to 1L, "action" to "write", "reason" to "首启安装",
        )
        appendLedger(
            ws, "name" to "反思E", "ts" to 2L,
            "action" to "write", "reason" to "反思器提议：把微头条流程记成技能",
        )
        appendLedger(
            ws, "name" to "手记F", "ts" to 3L,
            "action" to "write", "reason" to "你说的",
        )
        // 徽标取最新一条：手记F 后面又有一条 seed 行 ⇒ 翻成 SEED
        appendLedger(
            ws, "name" to "手记F", "source" to "seed", "ts" to 4L,
            "action" to "promote", "reason" to "满3次",
        )
        val badges = skillSourceBadges(ws, global)
        assertEquals(SkillLibrarySource.SEED, badges["种子C"])
        assertEquals(SkillLibrarySource.REFLECT, badges["反思E"])
        assertEquals(SkillLibrarySource.SEED, badges["手记F"])
        // 没账的技能不进徽标表——行读取时落 MANUAL（不给缺账的编故事）
        assertNull(badges["没账的G"])
        writeSkill(ws, "没账的G", "# 没账的G\n正文")
        assertEquals(SkillLibrarySource.MANUAL, skillLibraryRows(ws, global).single { it.name == "没账的G" }.source)
    }

    // ── 软删（追加件负例③：不真删）──────────────────────────────────────────

    @Test
    fun `删除是软删_原件字节保留_索引行去掉`() {
        setUp()
        val body = SkillStore.draftHeader(status = "draft", uses = 2) + "\n# 软删X\n正文一字不改"
        val f = writeSkill(ws, "软删X", body)
        val pf = personaFiles()
        val index = pf.projectFile()
        index.parentFile?.mkdirs()
        index.writeText(
            SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, "软删X", "场景")) + "\n",
            Charsets.UTF_8,
        )
        val row = skillLibraryRows(ws, global).single()

        val r = softDeleteSkill(row, pf, "20261025-130000")

        assertTrue(r is SkillWriteResult.Written && r.indexUpdated)
        // 原件字节全在 .deleted-* 里（一个字节不丢——「不真删」的硬判据）
        val gone = File(f.parentFile, "SKILL.md.deleted-20261025-130000")
        assertTrue(gone.isFile)
        assertEquals(body, gone.readText(Charsets.UTF_8))
        assertFalse("本名不许再占着（模型读不到＝删除生效）", f.isFile)
        // 索引行同步去掉
        assertFalse(pf.read(PersonaLayer.C_PROJECT).orEmpty().contains("软删X"))
    }

    // ── 候选 ⇒ 184 卡草稿 ──────────────────────────────────────────────────

    @Test
    fun `候选成技能形状给草稿_落点非技能给null`() {
        setUp()
        val skillCandidate = ReflectionCandidate(
            shape = ReflectionShape.REPEAT_LABOR,
            target = "微头条", changeKind = ReflectionChangeKind.NEW_SKILL,
            claim = "记成技能", evidences = emptyList(), benefit = "b", risk = "r", landing = "项目技能",
            suggestedName = "微头条流程", suggestedBody = "# 微头条流程\n骨架",
            createdAt = 0L,
        )
        val draft = candidateDraft(skillCandidate)
        assertNotNull(draft)
        assertEquals("微头条流程", draft!!.name)
        assertEquals(SkillCardSource.REFLECTION, draft.source)
        assertTrue(draft.reason.contains("反思器"))
        val flowCandidate = skillCandidate.copy(
            changeKind = ReflectionChangeKind.EDIT_FLOW,
            target = "技能索引", suggestedName = null, suggestedBody = null,
        )
        assertNull(candidateDraft(flowCandidate))
    }

    // ── 追加件四条负例（源码门禁：行为被钉在既有口上）────────────────────────

    @Test
    fun `负例1_不造第二套技能卡组件`() {
        val src = screen()
        assertFalse("不许自造行动卡容器（行动卡调用点白名单之外的都算第二套）", src.contains("BaopuActionContainer("))
        assertTrue("每行必须复用 184 的 BaopuSkillCard", src.contains("BaopuSkillCard("))
    }

    @Test
    fun `负例2_不绕过184写盘口`() {
        val src = screen()
        assertFalse("技能正文不许在页面里直写（唯一写口是 184 的 SkillCardWriter／32 批 commitSkillDraft）", src.contains(".writeText("))
        assertFalse("页面不许自己 rename/delete 技能文件", src.contains(".renameTo("))
        assertTrue("候选保存必须走 184 卡宿主（host.remember ⇒ SkillCardWriter.save）", src.contains("host.remember()"))
        val c = controller()
        assertTrue("行内转正必须走 commitSkillDraft（32 批唯一写口）", c.contains("commitSkillDraft("))
        assertTrue("删除必须走 softDeleteSkill（SkillStore.archive＋索引去行）", c.contains("softDeleteSkill("))
        assertTrue("恢复必须走 restoreArchived（34 批 B）", c.contains("restoreArchived("))
    }

    @Test
    fun `负例3_删除不真删文件`() {
        assertFalse("页面源码不许出现 delete()（彻底删只属于设置页的二次确认路径）", screen().contains(".delete()"))
        // 行为半边在上面 `删除是软删_原件字节保留_索引行去掉`
    }

    @Test
    fun `负例4_满3次转正必进决策中心_功能钉`() {
        setUp()
        // 走本页转正的同一条口（commitSkillDraft stillDraft=false）：
        // 写成了 ⇒ _变更.jsonl 里必有 promote 行（决策中心第⑤数据源读的就是它＝207）。
        val body = SkillStore.draftHeader(status = "draft", uses = SkillStore.PROMOTE_USES) + "\n# 转正Y\n正文"
        val f = writeSkill(ws, "转正Y", body)
        val pf = personaFiles()
        val row = skillLibraryRows(ws, global).single().toDraftRow()!!
        val r = commitSkillDraft(
            row = row,
            newBody = promoteBody(row.body),
            stillDraft = false,
            projectRoot = ws,
            globalRoot = global,
            personaFiles = pf,
            backupStamp = "20261025-140000",
        )
        assertTrue(r is SkillWriteResult.Written)
        val ledgerText = File(File(ws, "技能"), "_变更.jsonl").readText(Charsets.UTF_8)
        val lines = SkillPromote.parseLines(ledgerText)
        assertTrue("转正必须留 promote 行（决策中心靠它展示与回退）", lines.any { it.name == "转正Y" && it.action == SkillPromote.ACTION_PROMOTE })
        // 满三次那一条自动转正也留痕（184 的 afterUse＋commitSkillDraft 同一口）
        val thirdUse = SkillStore.afterUse(body).let { it.body }
        assertTrue(SkillStore.statusOf(thirdUse) == "active")
        assertEquals(SkillPromote.REASON_FULL_USES, SkillPromote.promoteReasonOf(body, thirdUse))
        assertTrue("转正不动正文文件本身（只改头）", f.isFile)
    }
}
