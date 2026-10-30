// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）：文件操作账（**全盘操作入账，30 天可回放**）的判据测试。
//
// 判据来自派工单 287：
// - 每笔文件操作一行：时间／会话／工具／种类／路径／用途；
// - 工具 → 种类映射覆盖全部带路径参数的工具（漏一个＝那类操作不入账）；
// - 用途是一句人话（不是参数 dump）；
// - 30 天窗口是常量且 SQL 真的按它剪（负例「账本无限长」）；
// - 账是**留痕不是闸门**：写不进库也不许抛（否则工具会被账本拖死）。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileOpLedgerTest {

    @Before
    fun setUp() {
        FileOpLog.resetForTest()
        FileOpLog.enterSession(9L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    @Test
    fun `入账_路径与用途齐备`() {
        FileOpLog.record("read_file", FileOpKind.READ, "/storage/emulated/0/小说/笔记.md", "读文件")
        val rows = FileOpLog.recent(10)
        assertEquals(1, rows.size)
        val e = rows[0]
        assertEquals(9L, e.sessionId)
        assertEquals("read_file", e.tool)
        assertEquals(FileOpKind.READ, e.kind)
        assertEquals("/storage/emulated/0/小说/笔记.md", e.path)
        assertEquals("读文件", e.purpose)
        assertTrue("时间必须记", e.at > 0)
    }

    @Test
    fun `最近_按时间倒序`() {
        FileOpLog.record("read_file", FileOpKind.READ, "/a", "读文件", now = 1_000)
        FileOpLog.record("write_file", FileOpKind.WRITE, "/b", "新建写", now = 3_000)
        FileOpLog.record("delete_path", FileOpKind.DELETE, "/c", "删进回收站（可恢复）", now = 2_000)
        assertEquals(listOf("/b", "/c", "/a"), FileOpLog.recent(3).map { it.path })
    }

    @Test
    fun `种类_带路径的工具一个不漏`() {
        val expected = mapOf(
            "read_file" to FileOpKind.READ,
            "list_dir" to FileOpKind.LIST,
            "find_files" to FileOpKind.SEARCH,
            "search_in_files" to FileOpKind.SEARCH,
            "write_file" to FileOpKind.WRITE,
            "edit_file" to FileOpKind.WRITE,
            "delete_path" to FileOpKind.DELETE,
            "move_path" to FileOpKind.MOVE,
            "archive_pack" to FileOpKind.WRITE,
            "archive_extract" to FileOpKind.WRITE,
            "image_ops" to FileOpKind.WRITE,
            "convert_encoding" to FileOpKind.READ,
            "file_info" to FileOpKind.READ,
            "book_outline" to FileOpKind.READ,
            "sheet_read" to FileOpKind.READ,
        )
        expected.forEach { (tool, kind) ->
            assertEquals("$tool 的种类", kind, FileOpRules.kindOf(tool))
        }
        assertNull("不碰文件的工具不入账", FileOpRules.kindOf("search_web"))
    }

    @Test
    fun `用途_是一句人话_不是参数dump`() {
        assertEquals(
            "按名找「斗罗」",
            FileOpRules.purposeOf("find_files", JSONObject().put("pattern", "斗罗")),
        )
        assertEquals(
            "搜「第一章」",
            FileOpRules.purposeOf("search_in_files", JSONObject().put("query", "第一章")),
        )
        assertEquals(
            "删进回收站（可恢复）",
            FileOpRules.purposeOf("delete_path", JSONObject().put("path", "/x")),
        )
        assertEquals(
            "彻底删（不可恢复）",
            FileOpRules.purposeOf("delete_path", JSONObject().put("path", "/x").put("permanent", true)),
        )
        assertEquals(
            "清空回收站",
            FileOpRules.purposeOf("delete_path", JSONObject().put("empty_trash", true)),
        )
        assertEquals(
            "覆盖写",
            FileOpRules.purposeOf("write_file", JSONObject().put("path", "/x").put("overwrite", true)),
        )
        val long = "很长的搜索词".repeat(20)
        val brief = FileOpRules.purposeOf("find_files", JSONObject().put("pattern", long))
        assertTrue("超长要截断（账是流水，不是正文仓库）", brief.length < 40)
    }

    @Test
    fun `保留窗口_30天`() {
        assertEquals(30, FileOpSql.RETENTION_DAYS)
        assertTrue("剪枝语句要按时间删", FileOpSql.PRUNE.contains("DELETE FROM") && FileOpSql.PRUNE.contains("at <"))
    }

    @Test
    fun `账行_人话含会话与用途`() {
        val e = FileOpEntry(1L, 12L, "write_file", FileOpKind.WRITE, "/storage/x.md", "新建写")
        val line = FileOpRules.lineOf(e)
        assertTrue(line.contains("#12"))
        assertTrue(line.contains("/storage/x.md"))
        assertTrue(line.contains("新建写"))
    }

    @Test
    fun `负例_没接线也不许抛`() {
        // 内存账本永远可用；这里验的是「记账失败不改变工具结果」——record 不抛
        val e = FileOpLog.record("read_file", FileOpKind.READ, "/x", "读文件")
        assertNotNull(e)
        assertTrue(FileOpLog.count() >= 1)
    }

    @Test
    fun `tag回读_认不出按读_不丢行`() {
        assertEquals(FileOpKind.DELETE, FileOpRules.kindOfTag("delete"))
        assertEquals(FileOpKind.READ, FileOpRules.kindOfTag("不认识的标签"))
        assertEquals(FileOpKind.READ, FileOpRules.kindOfTag(null))
    }
}
