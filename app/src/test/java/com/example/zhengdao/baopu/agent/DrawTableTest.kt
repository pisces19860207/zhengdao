// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：draw_table 的判据层（Markdown/CSV/JSON → 静态 HTML 表格）。
// 负例重点：列数不齐、引号未闭合、空表、超行/超列/超长单元格 —— 全部可读 Err；
// 单元格里的 HTML 只当文字（不执行、不联网）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawTableTest {

    private fun ok(source: String, format: String? = null, title: String? = null): DrawTable.Rendered {
        val r = DrawTable.render(source, format, title)
        assertTrue("应当成功，实际：$r", r is DrawResult.Ok)
        return (r as DrawResult.Ok).value
    }

    private fun err(source: String, format: String? = null, title: String? = null): String {
        val r = DrawTable.render(source, format, title)
        assertTrue("应当失败，实际：$r", r is DrawResult.Err)
        return (r as DrawResult.Err).message
    }

    @Test
    fun `Markdown三乘三_表头与单元格正确`() {
        val r = ok(
            "| 姓名 | 城市 | 年龄 |\n|---|:---:|---:|\n| 甲 | 北京 | 30 |\n| 乙 | 上海 | 25 |",
            title = "名单",
        )
        assertEquals("表格：3 列 × 2 行数据", r.summary)
        val html = r.html
        assertTrue(html.contains("姓名") && html.contains("北京") && html.contains("25"))
        assertTrue("标题进 caption", html.contains("<caption") && html.contains("名单"))
        assertTrue("居中对齐要落进样式", html.contains("text-align:center"))
        assertTrue("右对齐要落进样式", html.contains("text-align:right"))
        assertTrue("零残留", MarkupSanitizer.sanitizeHtml(html).removed == 0)
    }

    @Test
    fun `CSV带引号逗号与转义引号`() {
        val r = ok("名称,备注\n\"甲,乙\",\"引\"\"号\"", format = "csv")
        assertEquals("表格：2 列 × 1 行数据", r.summary)
        assertTrue(r.html.contains("甲,乙"))
        assertTrue(r.html.contains("引&quot;号"))
    }

    @Test
    fun `CSV引号未闭合_报错`() {
        val m = err("a,b\n\"1,2", format = "csv")
        assertTrue(m.contains("没闭合"))
    }

    @Test
    fun `CSV只有表头_报错`() {
        val m = err("a,b\n", format = "csv")
        assertTrue(m.contains("只有表头"))
    }

    @Test
    fun `JSON对象数组_字段名当表头`() {
        val r = ok("[{\"书名\":\"甲\",\"页数\":10},{\"书名\":\"乙\",\"页数\":20}]")
        assertEquals("表格：2 列 × 2 行数据", r.summary)
        assertTrue(r.html.contains("书名"))
        assertTrue(r.html.contains("乙"))
    }

    @Test
    fun `JSON_headers_rows_与align`() {
        val r = ok("{\"headers\":[\"甲\",\"乙\"],\"rows\":[[1,2],[3,4]],\"align\":[\"left\",\"right\"]}")
        assertTrue(r.html.contains(">1<") && r.html.contains(">4<"))
        assertTrue(r.html.contains("text-align:right"))
    }

    @Test
    fun `JSON数组行_第一行是表头`() {
        val r = ok("[[\"甲\",\"乙\"],[1,2]]")
        assertEquals("表格：2 列 × 1 行数据", r.summary)
        assertTrue(r.html.contains("甲") && r.html.contains(">2<"))
    }

    @Test
    fun `单元格里的script只当文字_不落进HTML`() {
        val r = ok("| 名 |\n|---|\n| <script>alert(1)</script>值 |")
        assertFalse(r.html.contains("script", ignoreCase = true))
        assertFalse(r.html.contains("alert"))
        assertTrue("剥掉后剩下的文字要留下", r.html.contains("值"))
        assertTrue(r.notes.isNotEmpty())
    }

    @Test
    fun `单元格里的标签当文字_不执行`() {
        val r = ok("| 名 |\n|---|\n| <img src=x onerror=evil()>粗 |")
        assertFalse(r.html.contains("<img"))
        assertFalse(r.html.contains("onerror"))
        assertTrue(r.html.contains("粗"))
    }

    @Test
    fun `行列数不齐_报错`() {
        val m = err("| a | b |\n|---|---|\n| 1 |")
        assertTrue(m.contains("列数必须一致"))
    }

    @Test
    fun `空输入_可读错误`() {
        assertTrue(err("   ").contains("空"))
    }

    @Test
    fun `不认识的format_列可用值`() {
        val m = err("a,b", format = "tsv")
        assertTrue(m.contains("tsv"))
        assertTrue(m.contains("markdown"))
        assertTrue(m.contains("csv"))
        assertTrue(m.contains("json"))
    }

    @Test
    fun `超过行上限_报错不静默截断`() {
        val body = buildString {
            append("| a |\n|---|\n")
            repeat(DrawLimits.MAX_TABLE_ROWS + 1) { append("| 1 |\n") }
        }
        val m = err(body)
        assertTrue(m.contains("行太多"))
    }

    @Test
    fun `超过列上限_报错`() {
        val cols = DrawLimits.MAX_TABLE_COLS + 1
        fun row() = "|" + (1..cols).joinToString("|") { "c$it" } + "|"
        val src = row() + "\n|" + (1..cols).joinToString("|") { "---" } + "|\n" + row()
        val m = err(src)
        assertTrue(m.contains("列太多"))
    }

    @Test
    fun `单元格超长_报错让模型拆列`() {
        val src = "| a |\n|---|\n| " + "x".repeat(DrawLimits.MAX_CELL_CHARS + 1) + " |"
        val m = err(src)
        assertTrue(m.contains("太长"))
    }

    @Test
    fun `markdown分隔行不在第二行_也能认`() {
        // 表头 + 空行 + 分隔行：空行会被过滤，仍按「第一行表头」处理
        val r = ok("| a | b |\n\n|--- | ---|\n| 1 | 2 |")
        assertEquals("表格：2 列 × 1 行数据", r.summary)
    }
}
