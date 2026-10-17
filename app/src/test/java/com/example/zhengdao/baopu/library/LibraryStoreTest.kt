// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.baopu.BaopuSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LibrarySql 纯 SQL 契约单测（刀 2 A 段）。
 *
 * 本仓无 Robolectric ⇒ 不真跑 SQLite，只钉**语句契约**（谁把 `isolated`/
 * `exported_at` 加进 UPDATE 的 SET，或把认脸幂等改成"后到覆盖"，这里必须红）。
 * 行为层归真机读数（交付报告挂账）。
 */
class LibraryStoreTest {

    private fun sql(f: BaopuSql.Stmt): String = f.sql

    @Test
    fun `插入_同sha不覆盖先见条目`() {
        val s = LibrarySql.insertItemIfAbsent(
            sha256 = "sha1", name = "a.txt", size = 10, mtime = 1,
            source = LibrarySource.DELIVER, isolated = true,
            kind = LibraryKind.TEXT, privatePath = "a.txt", createdAt = 1,
        )
        assertTrue(sql(s).contains("INSERT INTO library_item"))
        assertTrue(sql(s).contains("ON CONFLICT(sha256) DO NOTHING"))
        // 参数顺序与列清单一一对应（9 个占位符 9 个参数）
        assertEquals(9, s.args.size)
    }

    @Test
    fun `刷新元数据_绝不碰隔离位与导出账`() {
        val s = LibrarySql.updateItemMeta(
            sha256 = "sha1", name = "b.txt", size = 20, mtime = 2,
            source = LibrarySource.IMPORT, kind = LibraryKind.TEXT, privatePath = "b.txt",
        )
        val set = sql(s).substringAfter("SET ").substringBefore(" WHERE ")
        assertTrue("不许碰 isolated：$set", !set.contains("isolated"))
        assertTrue("不许碰 exported_at：$set", !set.contains("exported_at"))
        assertTrue(set.contains("name = ?"))
        assertTrue(set.contains("private_path = ?"))
    }

    @Test
    fun `隔离作废_只许单向`() {
        val s = LibrarySql.markNotIsolated("sha1")
        assertTrue(sql(s).contains("SET isolated = 0"))
        assertTrue("WHERE 必须带 isolated = 1（只 1→0，幂等且不复位）", sql(s).contains("isolated = 1"))
    }

    @Test
    fun `导出账_只写exported_at`() {
        val s = LibrarySql.markExported("sha1", 12345L)
        assertTrue(sql(s).contains("UPDATE library_item SET exported_at = ?"))
        assertEquals(arrayOf("12345", "sha1"), s.args)
    }

    @Test
    fun `汇总查询_三个口径`() {
        assertTrue(sql(LibrarySql.totalCount()).contains("COUNT(*)"))
        val last = sql(LibrarySql.lastExportAt())
        assertTrue(last.contains("COALESCE(MAX(exported_at), 0)"))
        val un = sql(LibrarySql.unexportedCount())
        assertTrue(un.contains("exported_at = 0"))
    }

    @Test
    fun `读全部_显式稳定排序`() {
        // 索引是缓存前缀的一部分：排序必须显式（name, sha256），不许依赖遍历顺序
        assertTrue(sql(LibrarySql.allItems()).contains("ORDER BY name, sha256"))
    }

    @Test
    fun `流水账与一次性提示`() {
        val log = LibrarySql.insertMoveLog("move", "a.txt", "sha1", "ok", 1L)
        assertTrue(sql(log).contains("INSERT INTO library_move_log"))
        assertEquals(arrayOf("move", "a.txt", "sha1", "ok", "1"), log.args)

        val has = LibrarySql.hasNotice("first-move")
        assertTrue(sql(has).contains("op = ?"))
        assertEquals(arrayOf(LibrarySql.NOTICE_PREFIX + "first-move"), has.args)
    }

    @Test
    fun `找一条_按sha`() {
        val s = LibrarySql.findItem("sha1")
        assertTrue(sql(s).contains("WHERE sha256 = ?"))
        assertEquals(arrayOf("sha1"), s.args)
    }

    @Test
    fun `来源与分类字面值`() {
        assertEquals("deliver", LibrarySource.DELIVER)
        assertEquals("import", LibrarySource.IMPORT)
        assertEquals("投递", LibrarySource.label(LibrarySource.DELIVER))
        assertEquals("导入", LibrarySource.label(LibrarySource.IMPORT))
        assertEquals("text", LibraryKind.TEXT)
        assertEquals("unclaimed", LibraryKind.UNCLAIMED)
    }

    @Test
    fun `建表DDL_两表各有唯一约束与索引`() {
        val item = BaopuSql.LIBRARY_DDL.joinToString("\n")
        assertTrue(item.contains("CREATE TABLE IF NOT EXISTS library_item"))
        assertTrue(item.contains("sha256 TEXT NOT NULL UNIQUE"))
        assertTrue(item.contains("CREATE INDEX IF NOT EXISTS idx_library_item_isolated"))
        val log = item
        assertTrue(log.contains("CREATE TABLE IF NOT EXISTS library_move_log"))
        assertTrue(log.contains("CREATE INDEX IF NOT EXISTS idx_library_move_log_sha"))
        // 流水账无外键（行数只增不减，不级联删）
        assertFalse(item.contains("FOREIGN KEY"))
    }
}
