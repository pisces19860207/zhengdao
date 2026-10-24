// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：render_artifact 的判据层（认格式/包装/限额/可读错误）。
// 负例重点：空、纯文本、超长、显式 svg 却没有 <svg> —— 一律可读 Err，绝不返回空白当成功。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawArtifactTest {

    private fun ok(content: String, format: String? = null, title: String? = null): DrawArtifact.Rendered {
        val r = DrawArtifact.render(content, format, title)
        assertTrue("应当成功，实际：$r", r is DrawResult.Ok)
        return (r as DrawResult.Ok).value
    }

    private fun err(content: String, format: String? = null, title: String? = null): String {
        val r = DrawArtifact.render(content, format, title)
        assertTrue("应当失败，实际：$r", r is DrawResult.Err)
        return (r as DrawResult.Err).message
    }

    @Test
    fun `auto认SVG_包成一页HTML`() {
        val r = ok("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"4\" height=\"4\"/></svg>", title = "方块")
        assertEquals("svg", r.format)
        assertTrue(r.html.contains("<!DOCTYPE html>"))
        assertTrue(r.html.contains("<svg"))
        assertTrue(r.html.contains("<rect"))
        assertTrue("标题要进 <title>", r.html.contains("<title>方块</title>"))
        assertEquals(0, r.removed)
    }

    @Test
    fun `auto认HTML_原样保留静态排版`() {
        val r = ok("<!DOCTYPE html><html><head><style>.a{color:#111827}</style></head><body><div class=\"a\">正文</div></body></html>")
        assertEquals("html", r.format)
        assertTrue(r.html.contains("正文"))
        assertTrue(r.html.contains(".a{color:#111827}"))
    }

    @Test
    fun `显式format覆盖认脸`() {
        val asHtml = ok("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>", format = "html")
        assertEquals("html", asHtml.format)
        val asSvg = ok("<html><body><svg><rect/></svg></body></html>", format = "svg")
        assertEquals("svg", asSvg.format)
        assertTrue("format=svg 要抽出 SVG 并包页", asSvg.html.contains("<svg"))
    }

    @Test
    fun `空内容_可读错误`() {
        assertTrue(err("   ").contains("空"))
        assertTrue(err("\uFEFF\n\t").contains("空"))
    }

    @Test
    fun `纯文本_说清不是HTML或SVG`() {
        val m = err("这是一段普通文字，没有任何标记")
        assertTrue(m.contains("不是 HTML/SVG"))
    }

    @Test
    fun `不认识format_列出可用值`() {
        val m = err("<div>x</div>", format = "pdf")
        assertTrue(m.contains("pdf"))
        assertTrue(m.contains("auto"))
        assertTrue(m.contains("html"))
        assertTrue(m.contains("svg"))
    }

    @Test
    fun `显式svg却没有svg标签_报错`() {
        val m = err("<div>x</div>", format = "svg")
        assertTrue(m.contains("<svg>"))
    }

    @Test
    fun `超长artifact_报错并给上限`() {
        val huge = "<div>" + "a".repeat(DrawLimits.MAX_ARTIFACT_CHARS + 10) + "</div>"
        val m = err(huge)
        assertTrue(m.contains("太长"))
        assertTrue(m.contains(DrawLimits.MAX_ARTIFACT_CHARS.toString()))
    }

    @Test
    fun `注释里的svg不能把HTML误判成SVG`() {
        val r = ok("<!-- <svg><rect/></svg> --><div>正文</div>")
        assertEquals("html", r.format)
        assertFalse("注释应当被剥掉", r.html.contains("<!--"))
    }

    @Test
    fun `XSS在落盘前就剥干净_并计数`() {
        val r = ok("<p>正文</p><script>alert(1)</script><div onclick=\"x()\">尾</div>")
        assertFalse(r.html.contains("script", ignoreCase = true))
        assertFalse(r.html.contains("onclick", ignoreCase = true))
        assertFalse(r.html.contains("alert"))
        assertTrue(r.html.contains("正文") && r.html.contains("尾"))
        assertTrue("剥掉几处要能报给用户", r.removed >= 2)
    }

    @Test
    fun `外链在落盘前就断掉`() {
        val r = ok(
            "<html><head><link rel=\"stylesheet\" href=\"https://evil/a.css\"></head>" +
                "<body><div style=\"background:url(https://evil/b.png)\">框</div></body></html>",
        )
        assertFalse(r.html.contains("evil"))
        assertFalse(r.html.contains("<link"))
        assertTrue(r.html.contains("框"))
        assertTrue(r.removed >= 2)
    }

    @Test
    fun `SVG里的危险属性剥掉_图形保留`() {
        val r = ok(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"evil()\">" +
                "<a xlink:href=\"https://evil\"><rect width=\"4\" height=\"4\"/></a></svg>",
        )
        assertFalse(r.html.contains("onload"))
        assertFalse(r.html.contains("evil"))
        assertTrue(r.html.contains("<rect"))
        assertTrue(r.removed >= 2)
    }

    @Test
    fun `标题里的标记当文字处理`() {
        val r = ok("<div>正文</div>", title = "<script>alert(1)</script>标题")
        assertEquals("标题", r.title)
        assertFalse(r.html.contains("alert"))
        assertTrue(r.removed >= 1)
    }
}
