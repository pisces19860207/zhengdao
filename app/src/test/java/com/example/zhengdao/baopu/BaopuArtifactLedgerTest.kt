package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第三十八批 §9.1 · 产物台账（artifact 表）· 判据 C1／C3／C4／C6 的数据侧。
 *
 * 为什么这一批的判据只能钉 SQL 文本＋绑定顺序：本仓单测跑在 JVM 上，没有 Robolectric 也没有
 * sqlite-jdbc（`android.jar` 里 `SQLiteDatabase` 是桩），**建不出真库**（37 批同一口径）。
 * 所以「一份文件只有一行」这件事在这里钉的是三条真源头：
 *   1. 唯一索引 `(workspace, rel_path)` —— 数据库层面的唯一真源；
 *   2. `ON CONFLICT (…) DO UPDATE` 的冲突键与更新列 —— 谁被刷、谁不许动；
 *   3. `?` 绑定顺序 —— 参数贴错列是最常见的假成功。
 * 真机（证据等级③）要验的是「跑一轮真写过文件的活，抽屉里 N 对不对」，列在交付报告。
 *
 * 负例（派工单 §9.3）由脚本按字节造红：把 `ON CONFLICT … DO UPDATE` 摘掉 ⇒
 * 本文件 `C1_同一路径再写是更新不是第二行` 必须红，读数记在报告。
 */
class BaopuArtifactLedgerTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun dbSource(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")

    private val ddl: String get() = BaopuSql.ARTIFACT_DDL.joinToString("\n")

    /** 建表那一条里的列名（按行取第一个词；只对 `ARTIFACT_DDL[0]` 解析，不掺索引那条）。 */
    private fun columns(): List<String> =
        BaopuSql.ARTIFACT_DDL[0]
            .substringAfter("artifact (")
            .substringBeforeLast(")")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(" ").first().trimEnd(',') }

    // ── C1：表形状＝派工单那一段 SQL（多一列少一列都不行）────────────────────

    @Test
    fun `C1_建表七列一个不多_绝对路径与文件内容都不许进库`() {
        assertTrue("表名要是 artifact", ddl.contains("CREATE TABLE IF NOT EXISTS artifact ("))
        assertEquals(
            "七列照派工单 §9.1，一列不加一列不减：${columns()}",
            listOf("id", "session_id", "workspace", "rel_path", "bytes", "created_at", "updated_at"),
            columns(),
        )
        // §9.4 红线：只存相对路径，不存绝对路径、不存文件内容
        assertFalse("不许有绝对路径列", columns().any { it.contains("abs") })
        assertFalse("不许有正文/内容列", columns().any { it == "body" || it == "content" || it == "text" })
        assertTrue("rel_path 必须 NOT NULL", ddl.contains("rel_path TEXT NOT NULL"))
        assertTrue("workspace 必须 NOT NULL（隔离靠它）", ddl.contains("workspace TEXT NOT NULL"))
        assertTrue("bytes 有默认 0", ddl.contains("bytes INTEGER NOT NULL DEFAULT 0"))
    }

    @Test
    fun `C1_会话删了台账跟着走_靠DDL级联不靠第二份删除语句`() {
        assertTrue(
            "session_id 必须 REFERENCES session(id) ON DELETE CASCADE（E-158 那类漏表事故不能再来一次）",
            ddl.contains("session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE"),
        )
        // 判别器自证：漏了级联的那种写法必须被同一条断言抓到
        val noCascade = "session_id INTEGER NOT NULL REFERENCES session(id)"
        assertFalse("判别器：没写 CASCADE 就该判红", noCascade.contains("ON DELETE CASCADE"))
        val deleted = BaopuSql.deleteSessionStatements(7L).joinToString("\n") { it.sql }
        assertFalse(
            "有级联就不要再显式删 artifact（删两遍是重复劳动，还会把「谁负责这条边」说不清）",
            deleted.contains("DELETE FROM artifact"),
        )
    }

    @Test
    fun `C1_唯一索引是工作区加相对路径_一份文件只有一行`() {
        assertTrue(
            "唯一索引照派工单：(workspace, rel_path)",
            ddl.contains("CREATE UNIQUE INDEX IF NOT EXISTS idx_artifact_ws_path ON artifact (workspace, rel_path)"),
        )
        // 判别器自证：只按 rel_path 建唯一索引的那种写法（跨工作区会被并成一行）必须被抓到
        val wrong = "CREATE UNIQUE INDEX IF NOT EXISTS idx_artifact_ws_path ON artifact (rel_path)"
        assertFalse("判别器：漏了 workspace 就该判红", wrong.contains("ON artifact (workspace, rel_path)"))
    }

    @Test
    fun `口径_两条DDL都幂等_重跑迁移不炸`() {
        // SQLite 没有 CREATE TABLE IF NOT EXISTS 之外的等价物，这里钉的就是「重复执行安全」：
        // onUpgrade 在已经建过表的老库上再跑一次是常态（37 批同一纪律）。
        BaopuSql.ARTIFACT_DDL.forEach { s ->
            assertTrue("每条都必须 IF NOT EXISTS：$s", s.contains("IF NOT EXISTS"))
        }
        assertEquals("建表一条＋索引一条，不多不少", 2, BaopuSql.ARTIFACT_DDL.size)
    }

    // ── C1：再写是更新，不是第二行 ────────────────────────────────────────────

    @Test
    fun `C1_同一路径再写是更新不是第二行`() {
        val s = BaopuSql.upsertArtifact(11L, "/ws/A", "报告/甲.md", 1234L, 1_700_000_000_000L)
        assertTrue("必须带冲突更新子句", s.sql.contains("ON CONFLICT (workspace, rel_path) DO UPDATE SET"))
        val after = s.sql.substringAfter("DO UPDATE SET")
        assertTrue("再写只刷 bytes：$after", after.contains("bytes = excluded.bytes"))
        assertTrue("再写只刷 updated_at：$after", after.contains("updated_at = excluded.updated_at"))
        assertFalse("再写不许动 created_at（第一次被记下来的时刻是历史）", after.contains("created_at"))
        assertFalse("再写不许动 session_id（首次来源要认得出）", after.contains("session_id"))
        assertFalse("再写不许动 workspace/rel_path（那是冲突键本身）", after.contains("rel_path ="))
    }

    @Test
    fun `C1_绑定顺序是会话_工作区_路径_字节_两个时刻`() {
        val s = BaopuSql.upsertArtifact(11L, "/ws/A", "报告/甲.md", 1234L, 1_700_000_000_000L)
        assertEquals(
            "六个 ? 对六个值，顺序错了就是静默串列",
            listOf("11", "/ws/A", "报告/甲.md", "1234", "1700000000000", "1700000000000"),
            s.args.toList(),
        )
        // 插入列清单与占位符个数必须对齐（这条是「参数贴错列」最常见的形状）
        val cols = s.sql.substringAfter("INSERT INTO artifact (").substringBefore(")").split(", ").map { it.trim() }
        assertEquals(listOf("session_id", "workspace", "rel_path", "bytes", "created_at", "updated_at"), cols)
        assertEquals("占位符个数＝列个数", cols.size, Regex("\\?").findAll(s.sql).count())
        assertEquals("created_at 与 updated_at 首次都取这一刻", s.args[4], s.args[5])
    }

    @Test
    fun `C1_换工作区写同名文件是另一行`() {
        // 唯一真源是索引 (workspace, rel_path)：workspace 不同 ⇒ 冲突不成立 ⇒ 新行。
        // 这里钉两条：冲突键里必须带 workspace；工作区是各传各的参（不是写死一个）。
        val a = BaopuSql.upsertArtifact(1L, "/ws/B", "a.txt", 3L, 9L)
        val b = BaopuSql.upsertArtifact(1L, "/ws/C", "a.txt", 3L, 9L)
        assertTrue("冲突键含 workspace", a.sql.contains("ON CONFLICT (workspace, rel_path)"))
        assertEquals(
            "同 rel_path、不同 workspace ⇒ 绑进去的是两个不同的值",
            listOf("/ws/B", "/ws/C"),
            listOf(a.args[1], b.args[1]),
        )
    }

    // ── C3／C4／C6：读与删都按工作区隔离 ─────────────────────────────────────

    @Test
    fun `C3_数与列都按当前工作区过滤`() {
        val c = BaopuSql.countArtifacts("/ws/A")
        assertTrue(c.sql.contains("COUNT(*)"))
        assertTrue(c.sql.contains("WHERE workspace = ?"))
        assertEquals(listOf("/ws/A"), c.args.toList())
        val l = BaopuSql.listArtifacts("/ws/B")
        assertTrue("列也要隔离（不然 A 的产物会出现在 B 的面板上）", l.sql.contains("WHERE a.workspace = ?"))
        assertEquals(listOf("/ws/B"), l.args.toList())
        // 判别器自证：没有工作区条件的那种 SQL 必须被同一条断言抓到
        assertFalse("判别器：漏了 workspace 过滤就该判红", "SELECT COUNT(*) FROM artifact".contains("WHERE workspace = ?"))
    }

    @Test
    fun `C4_列表按更新时间倒序_最多200行_带来源会话标题`() {
        val sql = BaopuSql.listArtifacts("/ws").sql
        assertTrue("updated_at 倒序（最新在最上）", sql.contains("ORDER BY a.updated_at DESC"))
        assertEquals("上限常量就是 200（派工单 §9.2 写死）", 200, BaopuSql.ARTIFACT_LIST_MAX)
        // LIMIT 在 SQLite 里要的是整数字面量（本仓既有先例：`LIMIT 1` 也是拼进去的），
        // 所以钉「SQL 里那个数 == 常量」，两处必须同一个口径。
        assertTrue("SQL 里的上限与常量同源：$sql", sql.contains("LIMIT ${BaopuSql.ARTIFACT_LIST_MAX}"))
        assertTrue("会话标题走 LEFT JOIN（会话没了也不能整行消失）", sql.contains("LEFT JOIN session s ON s.id = a.session_id"))
        assertTrue("标题取不到给空串", sql.contains("COALESCE(s.title, '')"))
        assertFalse("查询不许把文件内容拉出来（只报路径与体积）", sql.contains("content") || sql.contains("body"))
    }

    @Test
    fun `C6_删一行要同时按工作区与路径`() {
        val s = BaopuSql.deleteArtifact("/ws/A", "报告/甲.md")
        assertEquals("DELETE FROM artifact WHERE workspace = ? AND rel_path = ?", s.sql)
        assertEquals(listOf("/ws/A", "报告/甲.md"), s.args.toList())
        // 判别器：只按路径删（会把别的工作区的同名文件一起带走）必须被抓到
        val wrongShape = "DELETE FROM artifact WHERE rel_path = ?"
        assertFalse("判别器：漏 workspace 就该判红", wrongShape == s.sql)
    }

    // ── 迁移接线（v9）──────────────────────────────────────────────────────

    @Test
    fun `v9_迁移挂在onCreate与onUpgrade两条路上`() {
        val src = dbSource()
        assertTrue(
            "版本号必须 ≥ 10（v9 归本批 artifact；v11 起归第五十七批 skill_event）",
            Regex("const val VERSION = (1[0-9]|[2-9][0-9])").containsMatchIn(src),
        )
        assertTrue("onCreate 里要建表", src.contains("applyV9Migration(db)"))
        assertTrue("老库升级也要建表", src.contains("if (oldVersion < 9) applyV9Migration(db)"))
        val body = src.substringAfter("private fun applyV9Migration(db: SQLiteDatabase)")
        assertTrue("迁移体就是逐条 execDDL：" + body.take(160), body.contains("BaopuSql.ARTIFACT_DDL.forEach { db.execSQL(it) }"))
        assertEquals("applyV9Migration 只定义一次", 1, Regex("private fun applyV9Migration").findAll(src).count())
    }
}
