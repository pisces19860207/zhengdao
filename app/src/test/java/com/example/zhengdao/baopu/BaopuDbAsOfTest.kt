// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 451 批判据①／②／④／⑤：`memory.as_of` 列、v21 迁移、按 asOf 判「是否该复核」、
 * 以及「90 天＝内部复核周期」的口径门禁。
 *
 * 本仓无 Robolectric ⇒ SQLiteOpenHelper 进不了 JVM（[BaopuDbTest] J9 同款纪律）：
 * 这里钉**语句层**（列形状、幂等、绑定参数、迁移链两处）；「旧库真升级、旧行 NULL
 * 真可读」的①级证据由 `tools/check-memory-asof-migration.py` 用 Python sqlite3 真跑补上。
 */
class BaopuDbAsOfTest {

    private companion object {
        const val NOW = 1_730_000_000_000L
        const val DAY = MemoryQuality.DAY_MILLIS
        const val DB = "src/main/java/com/example/zhengdao/baopu/BaopuDb.kt"
        const val TIERS = "src/main/java/com/example/zhengdao/baopu/MemoryTiers.kt"
        const val ASSEMBLER = "src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt"
        const val MEMORY = "src/main/java/com/example/zhengdao/baopu/Memory.kt"
        const val STORE = "src/main/java/com/example/zhengdao/baopu/MemoryStore.kt"
        const val QUALITY = "src/main/java/com/example/zhengdao/baopu/MemoryQuality.kt"
    }

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
        assertTrue("找不到源码：$rel", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun fact(asOf: Long) = MemoryEntry(
        id = "f-" + asOf,
        scope = MemoryScope.PROJECT,
        kind = MemoryKind.FACT,
        title = "模型X支持长文",
        body = "正文",
        sourceSession = "s1",
        sourceFile = "资料库/原始/设定.md",
        status = MemoryStatus.CONFIRMED,
        asOf = asOf,
        createdAt = asOf,
    )

    // ── 判据①：加列语句与旧行 NULL ────────────────────────────────────────

    @Test
    fun `判据1_加列可空无默认_旧行才是NULL`() {
        val stmts = MemoryAsOfSql.columnStmt(listOf("id", "scope", "title"))
        assertEquals("缺列 ⇒ 一条 ALTER", 1, stmts.size)
        assertEquals("ALTER TABLE memory ADD COLUMN as_of INTEGER", stmts[0].sql)
        assertFalse("不许 NOT NULL：老行填不进去", stmts[0].sql.contains("NOT NULL"))
        assertFalse("不许 DEFAULT 0：那会把老行伪造成「0 时刻核实过」", stmts[0].sql.contains("DEFAULT"))
        assertEquals(0, stmts[0].args.size)
    }

    @Test
    fun `判据1_看列再动_幂等且大小写不敏感`() {
        assertTrue(MemoryAsOfSql.columnStmt(listOf("id", "as_of")).isEmpty())
        assertTrue(MemoryAsOfSql.columnStmt(listOf("ID", "AS_OF")).isEmpty())
        assertEquals(1, MemoryAsOfSql.columnStmt(listOf("id", "score", "scored_at")).size)
        assertEquals(
            "连跑两次拿到同一串语句（没有第二次才生效的隐藏步骤）",
            MemoryAsOfSql.columnStmt(listOf("id")),
            MemoryAsOfSql.columnStmt(listOf("id")),
        )
    }

    @Test
    fun `判据1_v21迁移挂onCreate与onUpgrade两处_VERSION23`() {
        val db = src(DB)
        assertEquals(23, BaopuDb.VERSION)
        assertTrue("新装路径", db.contains("applyV22Migration(db)"))
        assertTrue("升级路径", db.contains("if (oldVersion < 22) applyV22Migration(db)"))
        assertTrue("迁移体定义", db.contains("private fun applyV22Migration(db: SQLiteDatabase)"))
        assertTrue("迁移体看列再动", db.contains("val cols = tableColumns(db, \"memory\")"))
        // 负例：漏挂一边就红（新装／老库必有一边缺列）
        assertFalse(
            "漏挂升级路径 ⇒ 门禁必须红",
            wired(db.replace("if (oldVersion < 22) applyV22Migration(db)", "/* 忘了 */")),
        )
        assertTrue(wired(db))
    }

    private fun wired(db: String): Boolean =
        db.contains("applyV22Migration(db)") && db.contains("if (oldVersion < 22) applyV22Migration(db)") &&
            db.contains("private fun applyV22Migration(db: SQLiteDatabase)")

    @Test
    fun `判据1_读法要带as_of_排序口径不变`() {
        val ranked = BaopuSql.allMemoriesRanked()
        assertTrue("按质量序的读法也要读得到 as_of：${ranked.sql}", ranked.sql.contains("as_of"))
        assertTrue(ranked.sql.contains("ORDER BY score DESC, created_at ASC, id ASC"))
        assertEquals(0, ranked.args.size)
    }

    // ── 判据④：不许编造 asOf ──────────────────────────────────────────────

    @Test
    fun `判据4_upsert有asOf写值_缺失绑NULL不许编造`() {
        val withAsOf = BaopuSql.upsertMemory(fact(NOW))
        assertEquals(18, withAsOf.args.size)
        assertTrue(withAsOf.sql.contains("as_of)"))
        assertEquals("18 个占位符", 18, withAsOf.sql.substringAfter("VALUES (").count { it == '?' })
        assertEquals(NOW.toString(), withAsOf.args[17])
        val missing = BaopuSql.upsertMemory(fact(0L))
        assertEquals(18, missing.args.size)
        assertNull("缺 asOf 必须绑 NULL，不是 0 也不是当前时刻", missing.args[17])
    }

    // ── 判据②：按 asOf 判「是否该复核」 ────────────────────────────────────

    @Test
    fun `判据2_asOf超90天标待复核_边界满90天整`() {
        val period = MemoryTiers.REVIEW_PERIOD_MILLIS
        assertFalse("第 89 天不到线", MemoryTiers.reviewDue(fact(NOW), NOW + 89 * DAY))
        assertTrue("满 90 天整到线", MemoryTiers.reviewDue(fact(NOW), NOW + period))
        val due = MemoryTiers.lifecycleOf(fact(NOW), NOW + period)
        assertEquals(MemoryLifecycle.REVIEW_DUE, due)
        assertTrue("标签要说「待复核」：${MemoryTiers.lifecycleLabel(due!!)}",
            MemoryTiers.lifecycleLabel(due).contains("待复核"))
        assertEquals("复核到期时刻＝哪天查的 ＋ 90 天", NOW + period, MemoryTiers.reviewDueAt(fact(NOW)))
    }

    @Test
    fun `判据2_查询按asOf判_cutoff与90天同源`() {
        val q = MemoryAsOfSql.needingReview(NOW)
        assertTrue("只查标了时刻的行", q.sql.contains("as_of IS NOT NULL"))
        assertTrue("不认 0 这种编造值", q.sql.contains("as_of > 0"))
        assertTrue("按 asOf 比较", q.sql.contains("as_of <= ?"))
        assertTrue(q.sql.contains("ORDER BY as_of ASC, id ASC"))
        assertEquals("90 天只有一个常量（这里由它算 cutoff）",
            (NOW - MemoryTiers.REVIEW_PERIOD_MILLIS).toString(), q.args[0])
        // SQL 不等式与纯判据是同一条：asOf <= now − 周期
        val cutoff = q.args[0]!!.toLong()
        assertTrue("cutoff 上的事实该复核", MemoryTiers.reviewDue(fact(cutoff), NOW))
        assertFalse("cutoff 之后一天的事实不该复核", MemoryTiers.reviewDue(fact(cutoff + DAY), NOW))
    }

    @Test
    fun `判据2_缺asOf单独列出_不混进待复核`() {
        val q = MemoryAsOfSql.missingAsOf()
        assertTrue(q.sql.contains("as_of IS NULL"))
        assertTrue(q.sql.contains("ORDER BY id ASC"))
        assertFalse("缺时刻的行不许被当成待复核", q.sql.contains("<= ?"))
        assertEquals(0, q.args.size)
    }

    // ── 判据⑤ / 口径门禁 ─────────────────────────────────────────────────

    @Test
    fun `口径_90天常量注释原话_且单点可改`() {
        val tiers = src(TIERS)
        assertTrue("常量注释要写裁定原话",
            tiers.contains("90 天是本项目的复核周期（2026-11-04 裁定），非业界标准"))
        assertTrue("裁定出处要在", tiers.contains("裁定记录-2026-11.md"))
        assertTrue("不许写进事实有效期语义", tiers.contains("不写进任何"))
        assertEquals("90 这个数在判据层只出现一次", 1, Regex("=\\s*90L").findAll(tiers).count())
        assertEquals(90L * DAY, MemoryTiers.REVIEW_PERIOD_MILLIS)
        assertTrue("查询层复用同一个常量", src(DB).contains("now - MemoryTiers.REVIEW_PERIOD_MILLIS"))
    }

    @Test
    fun `口径_事实记忆域禁用保质期过期expire_带变异负例`() {
        val files = listOf(TIERS, MEMORY, STORE, ASSEMBLER, QUALITY)
        val banned = Regex("保质期|过期|expire|EXPIRED|isExpired|expiresAt|FACT_TTL")
        for (rel in files) {
            val s = src(rel)
            assertFalse("$rel 里还有事实有效期的说法：${banned.findAll(s).map { it.value }.toList()}",
                banned.containsMatchIn(s))
        }
        // 负例：往任意一份里塞回「保质期」⇒ 门禁必须红
        val dirty = src(TIERS) + "\n// 改回 90 天保质期\n"
        assertTrue("塞回禁用词后门禁必须红", banned.containsMatchIn(dirty))
    }

}
