// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 B②「index_browse(路径)」的渲染与分拣判据（[IndexBrowse] 的纯函数半边）：
// LIKE 转义（目录名带 %/_ 不串台）、目录分拣（直接子文件/子目录/更深处）、
// 「部分索引 N/M」状态行是唯一写法、未入索引要说实话。
package com.example.zhengdao.baopu.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexBrowsePureTest {

    // ── LIKE 模式 ───────────────────────────────────────────────────────────

    @Test
    fun `likePattern_根目录给全匹配`() {
        assertEquals("%", IndexBrowse.likePattern(""))
        assertEquals("%", IndexBrowse.likePattern("/"))
    }

    @Test
    fun `likePattern_子目录带转义_通配符不串台`() {
        assertEquals("设定/%", IndexBrowse.likePattern("设定"))
        assertEquals("50\\%甲/%", IndexBrowse.likePattern("50%甲"))
        assertEquals("下\\_划/%", IndexBrowse.likePattern("下_划"))
        assertEquals("反\\\\斜/%", IndexBrowse.likePattern("反\\斜"))
    }

    // ── 目录分拣 ────────────────────────────────────────────────────────────

    private fun row(rel: String, title: String = "", status: String = "OK") = DirDocRow(rel, title, status)

    @Test
    fun `partition_根目录_直接子文件与子目录分开`() {
        val rows = listOf(row("a.md"), row("sub/b.md"), row("sub/deep/c.md"), row("d.md"))
        val p = IndexBrowse.partition("", rows)
        assertEquals(listOf("a.md", "d.md"), p.directFiles.map { it.relPath })
        assertEquals(listOf("sub"), p.dirNames)
        assertEquals(2, p.deeperFiles)
    }

    @Test
    fun `partition_子目录_直接子文件整行保留_子目录只留名`() {
        val rows = listOf(row("设定/世界观.md"), row("设定/人物/主角.md"))
        val p = IndexBrowse.partition("设定", rows)
        // directFiles 是整行（渲染层自己取文件名）；dirNames 只留目录名
        assertEquals(listOf("设定/世界观.md"), p.directFiles.map { it.relPath })
        assertEquals(listOf("人物"), p.dirNames)
        assertEquals(1, p.deeperFiles)
    }

    @Test
    fun `partition_空目录_三样全空`() {
        val p = IndexBrowse.partition("空", emptyList())
        assertTrue(p.directFiles.isEmpty() && p.dirNames.isEmpty() && p.deeperFiles == 0)
    }

    // ── 状态行（「部分索引 N/M」的唯一写法） ──────────────────────────────────

    @Test
    fun `status_库不在_索引未建立`() {
        val line = IndexBrowse.Status(false, 0, 0).line()
        assertTrue(line.contains("索引未建立"))
    }

    @Test
    fun `status_超限_标部分索引`() {
        val line = IndexBrowse.Status(true, 2000, 2010).line()
        assertTrue("工单 B 判据：超限标「部分索引 N/M」", line.contains("部分索引 2000/2010"))
    }

    @Test
    fun `status_齐平_不标部分`() {
        val line = IndexBrowse.Status(true, 50, 50).line()
        assertTrue(line.contains("索引摘要 50/50"))
        assertFalse(IndexBrowse.Status(true, 50, 50).partial)
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    @Test
    fun `renderFile_未入索引_说实话并指路`() {
        val card = IndexBrowse.FileCard("/x/不在.md", "", null, null)
        val out = IndexBrowse.renderFile(card, "部分索引 1/2")
        assertTrue(out.contains("不在索引"))
        assertTrue(out.contains("read_file"))
        assertTrue(out.contains("部分索引 1/2"))
    }

    @Test
    fun `renderFile_有账无摘要_说明三种原因之一`() {
        val card = IndexBrowse.FileCard(
            "/x/a.md", "a.md",
            doc = DocCardRow(docId = 1, title = "t", mtime = 1L, status = "UNCLAIMED", indexedAt = 1L),
            summary = null,
        )
        val out = IndexBrowse.renderFile(card, "索引摘要 5/5")
        assertTrue(out.contains("没有摘要"))
        assertTrue(out.contains("UNCLAIMED"))
    }

    @Test
    fun `renderFile_完整卡_标题前行块行号齐备`() {
        val summary = DocSummaryRules.of("首行\n## 世界观\n第三行")
        val card = IndexBrowse.FileCard(
            "/x/设定.md", "设定/设定.md",
            doc = DocCardRow(docId = 1, title = "设定集", mtime = 1L, status = "OK", indexedAt = 1L),
            summary = summary,
        )
        val out = IndexBrowse.renderFile(card, "索引摘要 5/5")
        assertTrue(out.contains("[索引摘要卡] 设定/设定.md"))
        assertTrue(out.contains("标题：设定集"))
        assertTrue(out.contains("1| 首行"))
        assertTrue("结构块要带 文件:行", out.contains("设定/设定.md:2  ## 世界观"))
        assertTrue(out.contains("索引摘要 5/5"))
    }

    @Test
    fun `renderDir_子目录在前_触顶与更深处明说`() {
        val browse = IndexBrowse.DirBrowse(
            absDir = "/x/设定", relDir = "设定",
            directFiles = listOf(row("设定/世界观.md", "世界观"), row("设定/坏.md", "", "TOO_BIG")),
            dirNames = listOf("人物"),
            deeperFiles = 3,
            truncated = true,
        )
        val out = IndexBrowse.renderDir(browse, "索引摘要 9/9", 2)
        assertTrue(out.contains("人物/"))
        assertTrue(out.contains("世界观.md —— 世界观"))
        assertTrue("非 OK 状态要标出来", out.contains("TOO_BIG"))
        assertTrue(out.contains("更深处还有 3 篇"))
        assertTrue(out.contains("已到 2 行上限"))
    }

    @Test
    fun `renderDir_空目录_给指路不冒充空结论`() {
        val browse = IndexBrowse.DirBrowse("/x/空", "空", emptyList(), emptyList(), 0, truncated = false)
        val out = IndexBrowse.renderDir(browse, "索引摘要 0/0", 100)
        assertTrue(out.contains("空的"))
        assertTrue(out.contains("扫描本机"))
    }
}
