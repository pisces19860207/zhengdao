// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零五批 · 追加件③（草稿自动治理）的判据——四条规则＋四条负例逐条红：
//   负例：自动删了正式技能／真删文件／退役无记录／设置页新增了技能管理入口。
// 执行壳 `governDrafts`/`retireDraft` 在 `ui.baopu`（写盘走既有口），这里用真临时目录整跑。
package com.example.zhengdao.baopu

import com.example.zhengdao.ui.baopu.governDrafts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class SkillDraftGovernorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File
    private lateinit var global: File

    private val now: Long = ts(2026, 10, 25, 21)
    private val day = 24L * 60 * 60 * 1000

    private fun ts(y: Int, m: Int, d: Int, h: Int = 12): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun setUp() {
        ws = tmp.newFolder("ws")
        global = tmp.newFolder("global")
    }

    private fun persona(): PersonaFiles = PersonaFiles(globalRoot = global, projectRoot = ws)

    /** 落一条草稿/正式＋（可选）它的索引行与变更账行（lastActivity 由账行 ts 决定）。 */
    private fun writeSkill(
        name: String,
        status: String,
        uses: Int,
        lastAuditMs: Long? = null,
        scenario: String? = null,
        bodyText: String = "正文一字不动",
    ): File {
        val dir = File(File(ws, "技能"), name)
        dir.mkdirs()
        val f = File(dir, SkillRules.SKILL_FILE)
        f.writeText(SkillStore.draftHeader(status = status, uses = uses) + "\n# $name\n$bodyText", Charsets.UTF_8)
        if (scenario != null) {
            val index = persona().projectFile()
            index.parentFile?.mkdirs()
            index.appendText(
                SkillRules.renderIndexLine(SkillRules.IndexLine(MemoryScope.PROJECT, name, scenario)) + "\n",
                Charsets.UTF_8,
            )
        }
        if (lastAuditMs != null) {
            val ledger = File(File(ws, "技能"), SkillAudit.FILE_NAME)
            ledger.appendText(
                JSONObject()
                    .put("ts", lastAuditMs).put("scope", "project").put("name", name)
                    .put("action", "write").put("reason", "写")
                    .put("beforeSha", "-").put("afterSha", "abc123def456")
                    .put("indexUpdated", true).toString() + "\n",
                Charsets.UTF_8,
            )
        }
        return f
    }

    private fun ledgerText(): String =
        File(File(ws, "技能"), SkillAudit.FILE_NAME).takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty()

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    // ── 规则①：30 天未用 ⇒ 软退役进「已退役」───────────────────────────────

    @Test
    fun `规则1_三十天未用草稿软退役_原件字节保留_决策中心留痕`() {
        setUp()
        val f = writeSkill("闲置草稿", "draft", 0, lastAuditMs = now - 31 * day, scenario = "场景")
        val body = f.readText(Charsets.UTF_8)

        val report = governDrafts(ws, global, persona(), lastUseMs = emptyMap(), nowMs = now)

        assertEquals(listOf("闲置草稿"), report.retiredIdle)
        // 软退役：本名不占、.deleted-* 字节全在（负例②「真删文件」的反证）
        assertFalse(f.isFile)
        val gone = f.parentFile!!.listFiles()!!.single { it.name.startsWith("SKILL.md.deleted-") }
        assertEquals(body, gone.readText(Charsets.UTF_8))
        // 索引行同步去掉
        assertFalse(persona().read(PersonaLayer.C_PROJECT).orEmpty().contains("闲置草稿"))
        // 决策中心可查（负例③「退役无记录」的反证）
        val lines = SkillPromote.parseLines(ledgerText())
        assertTrue(lines.any { it.name == "闲置草稿" && it.action == SkillPromote.ACTION_DRAFT_RETIRE && it.reason == "30天未用" })
    }

    @Test
    fun `规则1_边界_29天不退_刚用过不退`() {
        setUp()
        writeSkill("二十九天", "draft", 0, lastAuditMs = now - 29 * day)
        writeSkill("昨天用过", "draft", 1, lastAuditMs = now - 1 * day)
        val report = governDrafts(ws, global, persona(), emptyMap(), now)
        assertTrue(report.retiredIdle.isEmpty())
    }

    // ── 规则②：>20 条 ⇒ 最久未用的降到 20 ─────────────────────────────────

    @Test
    fun `规则2_二十一条草稿自动降到二十_挑最久未用`() {
        setUp()
        for (i in 1..21) {
            writeSkill("草稿%02d".format(i), "draft", 0, lastAuditMs = now - i * day)
        }
        val report = governDrafts(ws, global, persona(), emptyMap(), now)
        assertEquals(21 - SkillDraftGovernor.MAX_DRAFTS, report.retiredOverflow.size)
        assertEquals("最久未用的先退", listOf("草稿21"), report.retiredOverflow)
        val draftsLeft = File(ws, "技能").listFiles()!!.count { sub ->
            sub.isDirectory && File(sub, "SKILL.md").isFile &&
                SkillStore.statusOf(File(sub, "SKILL.md").readText()) == "draft"
        }
        assertEquals(SkillDraftGovernor.MAX_DRAFTS, draftsLeft)
    }

    @Test
    fun `规则2_恰好二十条不动`() {
        setUp()
        for (i in 1..20) {
            writeSkill("草稿%02d".format(i), "draft", 0, lastAuditMs = now - i * day)
        }
        val report = governDrafts(ws, global, persona(), emptyMap(), now)
        assertTrue("恰好 20 条不触发②（30 天未用的走①，那是另一条规则）", report.retiredOverflow.isEmpty())
    }

    // ── 规则③：满 3 次 ⇒ 自动转正（进决策中心，reason=满3次）─────────────────

    @Test
    fun `规则3_满三次自动转正_账本说满3次不说手动`() {
        setUp()
        writeSkill("熟草稿", "draft", SkillStore.PROMOTE_USES, lastAuditMs = now - 1 * day)
        val report = governDrafts(ws, global, persona(), emptyMap(), now)
        assertEquals(listOf("熟草稿"), report.promoted)
        val body = File(File(File(ws, "技能"), "熟草稿"), "SKILL.md").readText(Charsets.UTF_8)
        assertTrue("头转 active，uses 保留", SkillStore.statusOf(body) == "active" && SkillStore.usesOf(body) == SkillStore.PROMOTE_USES)
        val lines = SkillPromote.parseLines(ledgerText())
        assertTrue(lines.any { it.name == "熟草稿" && it.action == SkillPromote.ACTION_PROMOTE && it.reason == SkillPromote.REASON_FULL_USES })
    }

    // ── 规则④：疑似重复只标不删 ────────────────────────────────────────────

    @Test
    fun `规则4_场景归一相同的两条草稿_只标不删_一键合并退役对方`() {
        setUp()
        val a = writeSkill("重复A", "draft", 0, lastAuditMs = now - 1 * day, scenario = "回复末尾，带简短总结！")
        val b = writeSkill("重复B", "draft", 0, lastAuditMs = now - 2 * day, scenario = "回复末尾 带简短总结")
        val report = governDrafts(ws, global, persona(), emptyMap(), now)

        assertEquals(listOf("重复A" to "重复B"), report.duplicates)
        assertTrue("只标不删：两条正文都还在", a.isFile && b.isFile)
        assertTrue(report.retiredIdle.isEmpty() && report.retiredOverflow.isEmpty())
    }

    // ── 负例①：自动规则不许碰正式组 ────────────────────────────────────────

    @Test
    fun `负例1_正式技能三十天未用_自动规则一个不碰`() {
        setUp()
        val active = writeSkill("正式老技能", "active", 0, lastAuditMs = now - 90 * day, scenario = "场景")
        val draft = writeSkill("闲置草稿", "draft", 0, lastAuditMs = now - 31 * day, scenario = "场景2")

        val report = governDrafts(ws, global, persona(), emptyMap(), now)

        assertTrue("正式技能不在任何自动名单里", report.retiredIdle.none { it == "正式老技能" })
        assertTrue("正式正文原位未动", active.isFile && SkillStore.statusOf(active.readText()) == "active")
        assertTrue("旁边的草稿照常治理", report.retiredIdle.contains("闲置草稿"))
        assertFalse(draft.isFile)
    }

    // ── 负例②：永不真删（源码门禁＋行为双保险）──────────────────────────────

    @Test
    fun `负例2_治理链路没有真删的口_源码门禁`() {
        for (rel in listOf(
            "src/main/java/com/example/zhengdao/baopu/SkillDraftGovernor.kt",
            "src/main/java/com/example/zhengdao/ui/baopu/SkillLibraryScreen.kt",
        )) {
            val src = repoFile(rel).readText()
            assertFalse("治理链路不许出现 .delete(（$rel）", src.contains(".delete("))
            assertFalse("治理链路不许 deleteRecursively（$rel）", src.contains("deleteRecursively"))
        }
        // 行为半边：规则①里 .deleted-* 字节与原正文逐字一致
    }

    // ── 负例③：退役无记录 ⇒ 红（审计行必须能被决策中心解析出来）──────────────

    @Test
    fun `负例3_每次自动退役都有账_解析可查`() {
        setUp()
        writeSkill("闲置甲", "draft", 0, lastAuditMs = now - 31 * day)
        writeSkill("闲置乙", "draft", 0, lastAuditMs = now - 40 * day)
        val report = governDrafts(ws, global, persona(), emptyMap(), now)
        assertEquals(2, report.retiredIdle.size)
        val parsed = SkillPromote.parseLines(ledgerText())
        assertEquals(
            "两条退役都得在账上（缺账即红）",
            report.retiredIdle.size,
            parsed.count { it.action == SkillPromote.ACTION_DRAFT_RETIRE },
        )
    }

    // ── 负例④：设置页一个入口都不加（技能管理只在技能页）────────────────────

    @Test
    fun `负例4_设置页没有新增任何技能治理入口_源码门禁`() {
        val settings = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt").readText()
        listOf("SkillDraftGovernor", "governDrafts", "skill_library", "runSkillRowAction", "SkillLibraryDialog").forEach { token ->
            assertFalse("设置页不许出现 $token（技能管理只在技能页）", settings.contains(token))
        }
    }
}
