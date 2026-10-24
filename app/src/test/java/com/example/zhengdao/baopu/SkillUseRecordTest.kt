// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五十七批 §1.1：技能被用 ⇒ 记一条 `skill_event(kind='use')`。
//
// 边界（本批最重要的一条，测试照它钉）：**只记录，不据此自动改技能**——
// 记录失败只 log、绝不影响这次工具调用的结果（红线「只记录、不阻塞」）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.SkillTools
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SkillUseRecordTest {

    // ── U1：表形状与 SQL 文本层（JVM 无 Android 运行时，照 BaopuArtifactLedgerTest 房型）──

    private val ddl: String get() = BaopuSql.SKILL_EVENT_DDL.joinToString("\n")

    private fun columns(): List<String> =
        BaopuSql.SKILL_EVENT_DDL[0]
            .substringAfter("skill_event (")
            .substringBeforeLast(")")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(" ").first().trimEnd(',') }

    @Test
    fun `U1_表十列照派工单_use要记名字_会话_步_成败_时刻`() {
        assertTrue("表名要是 skill_event", ddl.contains("CREATE TABLE IF NOT EXISTS skill_event ("))
        assertEquals(
            "十列照第五十七批 §1.1（use 五项）＋ §1.3（decision 的 hash/detail）共用一张表：${columns()}",
            listOf(
                "id", "kind", "target", "name", "session_id", "step", "ok", "hash", "detail", "at",
            ),
            columns(),
        )
        assertTrue("ok 要有默认（decision 侧不传也立得住）", ddl.contains("ok INTEGER NOT NULL DEFAULT 1"))
        assertTrue("时间必须 NOT NULL（过期判据靠它排序）", ddl.contains("at INTEGER NOT NULL"))
    }

    @Test
    fun `U1_两条DDL都幂等_重跑迁移不炸`() {
        BaopuSql.SKILL_EVENT_DDL.forEach { s ->
            assertTrue("每条都必须 IF NOT EXISTS：$s", s.contains("IF NOT EXISTS"))
        }
        assertEquals("建表一条＋索引一条，不多不少", 2, BaopuSql.SKILL_EVENT_DDL.size)
    }

    @Test
    fun `U1_账本只增不改不删_没有UPDATE没有DELETE`() {
        // 红线：留痕是账本。谁往这张表上加 UPDATE/DELETE 谁就是把审计口径拆了。
        val all = ddl + "\n" + BaopuSql.insertSkillUse("n", 1L, 2, true, 0L).sql
        assertFalse("不许 UPDATE", all.contains("UPDATE "))
        assertFalse("不许 DELETE", all.contains("DELETE FROM skill_event"))
    }

    @Test
    fun `U1_insertSkillUse绑定顺序_名字_会话_步_成败_时刻`() {
        val s = BaopuSql.insertSkillUse("做卡流程", 7L, 3, false, 1_700_000_000_000L)
        assertEquals(
            "五个 ? 对五个值，顺序错了就是静默串列",
            listOf("做卡流程", "7", "3", "0", "1700000000000"),
            s.args.toList(),
        )
        assertEquals("失败要记 0 不是 1", "0", s.args[3])
        val okStmt = BaopuSql.insertSkillUse("做卡流程", null, null, true, 1L)
        assertEquals("成功记 1", "1", okStmt.args[3])
        assertEquals("无会话时 session_id 落空（后台记录不硬凑）", null, okStmt.args[1])
        val cols = s.sql.substringAfter("INSERT INTO skill_event (").substringBefore(")")
            .split(", ").map { it.trim() }
        assertEquals(listOf("kind", "target", "name", "session_id", "step", "ok", "at"), cols)
        assertEquals("占位符个数＝绑定值个数", cols.size - 2, Regex("\\?").findAll(s.sql).count())
        assertTrue("kind 写死 use", s.sql.contains("VALUES ('use'"))
        assertTrue("target 写死 skill", s.sql.contains("'use', 'skill'"))
    }

    @Test
    fun `U1_库版本升到11_迁移两条入口同一份`() {
        assertEquals("v11 = 第五十七批 skill_event（v12 … v18 是后续批次的账，与本批的 use 行无关；v17 = 第一百四十七批 diary_index 日记索引，v18 = 157 补充 workspace_grant/workspace_audit）", 19, BaopuDb.VERSION)
        val src = repoFile("app/src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
        val body = src.readText(Charsets.UTF_8).replace("\r\n", "\n")
            .substringAfter("class BaopuDb")
        assertEquals(
            "onCreate 与 onUpgrade 都要调 applyV11Migration（新装与升级同一条路径）",
            2,
            Regex("applyV11Migration\\(db\\)").findAll(body).count(),
        )
    }

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("../$rel") }

    // ── U2/U3：行为级（真临时目录，走 spec 的 executor）────────────────────────

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    /** runUseOnce 各自新建的目录（tearDown 一起删）。 */
    private val tempRoots = mutableListOf<File>()

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd-use-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd-use-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        (tempRoots + projectRoot + globalRoot).forEach { it.deleteRecursively() }
    }

    /** 盘上先有一条草稿技能（use 只认已存在的那条）。 */
    private fun seedDraft(name: String = "做卡流程") {
        val pf = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT, name = name, body = "# $name\n先填必填项。",
            reason = "测试", draft = true,
        )
        val plan = SkillStore.plan(proposal, projectRoot, globalRoot, "20261020-000000")
        val r = SkillStore.apply(plan, pf, "")
        assertTrue("前置：草稿要写成功", r is SkillWriteResult.Written)
    }

    private fun specOf(recorder: SkillTools.UseRecorder?) = SkillTools.spec(
        workspaceRoot = { projectRoot },
        personaFiles = { PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot) },
        useRecorder = recorder,
    )

    private fun useArgs(name: String) = JSONObject().put("action", "use").put("name", name).put("body", "")

    @Test
    fun `U2_use成功_记录器收到名字与ok`() {
        seedDraft()
        val got = mutableListOf<Pair<String, Boolean>>()
        val out = specOf(SkillTools.UseRecorder { n, ok -> got += n to ok; null })
            .executor(useArgs("做卡流程"))
        assertTrue("use 本身要成功：$out", out is ToolOutcome.Ok)
        assertEquals(listOf("做卡流程" to true), got)
    }

    @Test
    fun `U2_use失败也记录_失败也是用了没成的证据`() {
        val got = mutableListOf<Pair<String, Boolean>>()
        val out = specOf(SkillTools.UseRecorder { n, ok -> got += n to ok; null })
            .executor(useArgs("不存在的技能"))
        assertTrue("不存在的技能要 Failed：$out", out is ToolOutcome.Failed)
        assertEquals("失败也要记（派工单 §1.1：ok=该步最终成功/失败）", listOf("不存在的技能" to false), got)
    }

    @Test
    fun `U3_记录器炸了_工具结果一字不动`() {
        // 两个**独立** fixture 各跑一次 use（同一目录跑两次会把 uses 数两遍，文本没法对齐）
        val withRecorder = runUseOnce(SkillTools.UseRecorder { _, _ -> throw RuntimeException("账本坏了") })
        val without = runUseOnce(null)
        assertEquals(
            "记录失败只 log，结果必须与没有记录器时完全一致",
            without,
            withRecorder,
        )
        assertTrue(without.contains("已用 1/3"))
    }

    /** 独立临时目录 + 盘上一条草稿 + 跑一次 use ⇒ 返回工具答复文本。 */
    private fun runUseOnce(recorder: SkillTools.UseRecorder?): String {
        val proj = File.createTempFile("zd-use-once", "").let { it.delete(); it.mkdirs(); it }
        val glob = File.createTempFile("zd-use-once-g", "").let { it.delete(); it.mkdirs(); it }
        tempRoots += proj; tempRoots += glob
        val pf = PersonaFiles(projectRoot = proj, globalRoot = glob)
        val seed = SkillStore.plan(
            SkillProposal(SkillScope.PROJECT, "做卡流程", "# 做卡流程\n先填必填项。", "测试", draft = true),
            proj, glob, "20261020-000000",
        )
        assertTrue("前置：草稿要写成功", SkillStore.apply(seed, pf, "") is SkillWriteResult.Written)
        val out = SkillTools.spec(
            workspaceRoot = { proj },
            personaFiles = { pf },
            useRecorder = recorder,
        ).executor(useArgs("做卡流程"))
        return (out as ToolOutcome.Ok).text
    }

    // ── U4：源码门禁（记录路径不许被顺手摘掉）────────────────────────────────────

    /** 源码门禁通用的取源码（CRLF 归一）。 */
    private fun source(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun `U4_use收口必调记录器且runCatching包裹`() {
        val src = source("app/src/main/java/com/example/zhengdao/baopu/agent/tools/SkillTools.kt")
        val gate = src.substringAfter("if (action == \"use\" && useRecorder != null)")
        assertTrue("记录调用必须包在 runCatching 里（记录炸了不许带走工具结果）", gate.contains("runCatching { useRecorder.onUse(name, out is ToolOutcome.Ok) }"))
        // 记录器返回的提醒只在成功时拼进答复；失败/无提醒原样返回 out
        assertTrue(gate.contains("ToolOutcome.Ok(out.text + \"\\n\" + tip)"))
        assertTrue("记录器抛异常时兜底为 null（不影响 out）", gate.contains("}.getOrNull()"))
    }

    @Test
    fun `U4_装配层必须注入记录器_且写失败只log`() {
        val src = source("app/src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("spec 注入处要传 useRecorder", src.contains("useRecorder = com.example.zhengdao.baopu.agent.SkillTools.UseRecorder"))
        val fn = src.substringAfter("private fun recordSkillUse")
        assertTrue("记录体要 runCatching 兜底（红线：写失败只 log、不阻塞）", fn.contains("runCatching {"))
        assertTrue("失败要落 RunLog（不静默）", fn.contains("RunLog.log("))
        assertTrue("写要等提交（get 带超时，不无限等）", fn.contains(".get(3, java.util.concurrent.TimeUnit.SECONDS)"))
    }
}
