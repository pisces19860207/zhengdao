// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：HTML/SVG 消毒的判据与负例（纯 JVM）。
//
// 这一层守的是「XSS 残留 = 红」与「外链 = 剥」两条底线：模型写什么都不能让预览/PNG
// 里出现可执行标记或出网引用；同时不能把正常排版（样式、SVG 图形、锚点）一并洗掉。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupSanitizerTest {

    // ── 负例：可执行标记 ────────────────────────────────────────────────

    @Test
    fun `script_连内容一起剥_并计数`() {
        val r = MarkupSanitizer.sanitizeHtml("<p>前</p><script>alert(1)</script><p>后</p>")
        assertTrue(r.ok)
        assertFalse(r.text.contains("script", ignoreCase = true))
        assertFalse(r.text.contains("alert"))
        assertTrue(r.text.contains("前") && r.text.contains("后"))
        assertTrue("必须报告剥掉了几处", r.removed >= 1)
    }

    @Test
    fun `大写SCRIPT与带属性也剥`() {
        val r = MarkupSanitizer.sanitizeHtml("<SCRIPT type=\"text/javascript\">alert(1)</SCRIPT><b>ok</b>")
        assertFalse(r.text.contains("alert"))
        assertTrue(r.text.contains("ok"))
    }

    @Test
    fun `未闭合的script_从开标签丢到文末_不把后半页当成脚本内容`() {
        val r = MarkupSanitizer.sanitizeHtml("<p>保留</p><script>alert(1)<p>后面的正文</p>")
        assertFalse(r.text.contains("script", ignoreCase = true))
        assertFalse(r.text.contains("alert"))
        assertTrue(r.text.contains("保留"))
        assertFalse("未闭合危险块之后的内容一律不进预览：${r.text}", r.text.contains("后面的正文"))
        assertTrue(r.removed >= 1)
    }

    @Test
    fun `iframe_object_foreignObject_整块剥`() {
        val raw = "<iframe src=\"https://evil\">x</iframe>" +
            "<object data=\"a.swf\"></object>" +
            "<svg><foreignObject><div>危险</div></foreignObject><rect/></svg>"
        val r = MarkupSanitizer.sanitizeHtml(raw)
        assertFalse(r.text.contains("iframe", ignoreCase = true))
        assertFalse(r.text.contains("object", ignoreCase = true))
        assertFalse(r.text.contains("危险"))
        assertTrue("正常 SVG 图形要留下", r.text.contains("<rect"))
        assertTrue(r.removed >= 3)
    }

    @Test
    fun `on事件属性一律剥`() {
        val r = MarkupSanitizer.sanitizeHtml("<div onclick=\"alert(1)\" onmouseover='x()'>正文</div>")
        assertFalse(r.text.contains("onclick", ignoreCase = true))
        assertFalse(r.text.contains("onmouseover", ignoreCase = true))
        assertFalse(r.text.contains("alert"))
        assertTrue(r.text.contains("正文"))
        assertEquals(2, r.removed)
    }

    @Test
    fun `javascript协议与外链href都剥`() {
        val r = MarkupSanitizer.sanitizeHtml(
            "<a href=\"javascript:alert(1)\">甲</a><a href=\"https://evil/x\">乙</a>",
        )
        assertFalse(r.text.contains("javascript", ignoreCase = true))
        assertFalse(r.text.contains("https://evil"))
        assertTrue(r.text.contains("甲") && r.text.contains("乙"))
        assertEquals(2, r.removed)
    }

    @Test
    fun `外链图片_CSS_字体全剥`() {
        val raw = "<link rel=\"stylesheet\" href=\"https://evil/a.css\">" +
            "<img src=\"https://evil/a.png\">" +
            "<div style=\"background:url(https://evil/bg.png)\">框</div>" +
            "<style>@import url(\"https://evil/x.css\"); .a{background:url(https://evil/y.png)}</style>"
        val r = MarkupSanitizer.sanitizeHtml(raw)
        assertFalse(r.text.contains("<link", ignoreCase = true))
        assertFalse(r.text.contains("<img", ignoreCase = true))
        assertFalse(r.text.contains("evil"))
        assertFalse(r.text.contains("@import", ignoreCase = true))
        assertTrue("正文要留下", r.text.contains("框"))
        assertTrue(r.removed >= 4)
    }

    @Test
    fun `meta_refresh_与base_剥`() {
        val r = MarkupSanitizer.sanitizeHtml(
            "<head><meta http-equiv=\"refresh\" content=\"0;url=https://evil\"><base href=\"https://evil/\"></head>" +
                "<body>正文</body>",
        )
        assertFalse(r.text.contains("refresh", ignoreCase = true))
        assertFalse(r.text.contains("<base", ignoreCase = true))
        assertTrue(r.text.contains("正文"))
    }

    @Test
    fun `全是危险标记_消毒后为空必须报问题_而不是返回空串当成功`() {
        val r = MarkupSanitizer.sanitizeHtml("<script>alert(1)</script>")
        assertFalse("空结果必须是 not ok", r.ok)
        assertTrue(r.problems.isNotEmpty())
    }

    // ── 正例：静态内容保留 ──────────────────────────────────────────────

    @Test
    fun `静态HTML与样式保留_viewBox大小写不变`() {
        val raw = "<html><head><style>.t{color:#111827;text-align:center}</style></head>" +
            "<body><div class=\"t\">标题</div><svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\" fill=\"#2563eb\"/></svg></body></html>"
        val r = MarkupSanitizer.sanitizeHtml(raw)
        assertTrue(r.ok)
        assertEquals(0, r.removed)
        assertTrue(r.text.contains("<style>"))
        assertTrue(r.text.contains("color:#111827"))
        assertTrue(r.text.contains("viewBox"))
        assertTrue(r.text.contains("<rect"))
    }

    @Test
    fun `锚点与data内联图放行`() {
        val html = MarkupSanitizer.sanitizeHtml("<a href=\"#sec\">跳</a>")
        assertTrue(html.text.contains("href=\"#sec\""))
        assertEquals(0, html.removed)

        val svg = MarkupSanitizer.sanitizeSvg(
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><style>.a{background:url(#g)}</style></svg>",
        )
        assertTrue("本页锚点不是外链：${svg.text}", svg.text.contains("url(#g)"))
        assertTrue(svg.ok)
    }

    @Test
    fun `data内联图不被当成外链`() {
        val r = MarkupSanitizer.sanitizeSvg(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">" +
                "<style>.a{background:url(data:image/png;base64,AAAA)}</style></svg>",
        )
        assertTrue(r.ok)
        assertTrue("内联 data 图不出网：${r.text}", r.text.contains("data:image/png"))
    }

    @Test
    fun `注释与XML声明先清掉_不参与认标签`() {
        val r = MarkupSanitizer.sanitizeHtml("<?xml version=\"1.0\"?><!-- <svg> --><div>正文</div>")
        assertEquals(0, r.removed)
        assertTrue(r.text.contains("正文"))
    }

    @Test
    fun `文本里的少于号不算标签`() {
        val r = MarkupSanitizer.sanitizeHtml("<p>1 < 2 且 3 > 2</p>")
        assertTrue(r.text.contains("1 < 2"))
        assertEquals(0, r.removed)
    }

    // ── cleanText（表格单元格/图表标签）────────────────────────────────

    @Test
    fun `cleanText_剥标记与on属性_保留文字`() {
        val c = MarkupSanitizer.cleanText("<b>粗</b><script>alert(1)</script><span onclick=\"x()\">细</span>")
        assertEquals("粗细", c.text)
        assertTrue(c.removed >= 3)
    }

    @Test
    fun `sanitizeSvg_只认SVG标签_HTML块剥掉`() {
        val r = MarkupSanitizer.sanitizeSvg("<svg><div>不是SVG</div><circle cx=\"5\" cy=\"5\" r=\"4\"/></svg>")
        assertFalse(r.text.contains("<div"))
        assertTrue(r.text.contains("<circle"))
        assertTrue(r.removed >= 1)
    }
}
