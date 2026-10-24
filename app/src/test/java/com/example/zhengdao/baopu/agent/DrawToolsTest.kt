// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：三个工具的**落盘/预览/截图编排**（JVM 直测，不建 WebView）。
//
// 这里守的负例：
// - 截图器失败（含「空白」）⇒ 整次调用 Failed，且话术说清「HTML 已经落盘」，不冒充成功；
// - 截图器没接线 ⇒ 明说，不静默产出一张不存在的 PNG；
// - 预览没推出去 ⇒ 文件仍算写成（不把「玄览没开」升级成「文件没写」）；
// - 落点越界 ⇒ Denied；同名 ⇒ 新文件（-2），绝不覆盖。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DrawToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private class FakePreview(private val reason: String? = null) : PreviewSink {
        val seen = mutableListOf<String>()
        override fun show(relPath: String, text: String): String? {
            seen += relPath
            return reason
        }
    }

    private class FakeRaster(
        private val fail: String? = null,
        private val clipped: Boolean = false,
    ) : ArtifactRasterizer {
        var calls = 0
        var lastHtml: String? = null
        override fun capture(html: String, target: File): RasterResult {
            calls++
            lastHtml = html
            if (fail != null) throw RasterException(fail)
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            return RasterResult(4, 1080, 600, clipped)
        }
    }

    private var oldSink: PreviewSink? = null
    private var oldRaster: ArtifactRasterizer? = null

    @Before
    fun setUp() {
        root = tmp.newFolder("ws")
        oldSink = PreviewTool.sink
        oldRaster = DrawPublish.rasterizer
    }

    @After
    fun tearDown() {
        PreviewTool.sink = oldSink
        DrawPublish.rasterizer = oldRaster
    }

    private fun files(dir: File, ext: String): List<File> =
        dir.listFiles { f -> f.isFile && f.extension == ext }?.sortedBy { it.name }.orEmpty()

    // ── render_artifact ─────────────────────────────────────────────────

    @Test
    fun `画板_落图片目录_出预览与PNG`() {
        val preview = FakePreview()
        val raster = FakeRaster()
        PreviewTool.sink = preview
        DrawPublish.rasterizer = raster

        val out = RenderArtifactTool.run(
            root,
            JSONObject()
                .put("content", "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"6\" height=\"6\"/></svg>")
                .put("title", "流程"),
        )
        assertTrue("应当成功：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue(text, text.contains("画板做好了"))
        assertTrue(text, text.contains("图片/"))
        assertTrue(text, text.contains("PNG"))
        assertTrue(text, text.contains("本地沙箱渲染"))
        assertEquals(1, files(DrawPaths.imagesDir(root), "html").size)
        assertEquals(1, files(DrawPaths.imagesDir(root), "png").size)
        assertEquals(1, preview.seen.size)
        assertTrue(preview.seen[0].startsWith("图片/"))
        assertEquals("PNG 截的必须是同一份 HTML", 1, raster.calls)
        assertTrue(raster.lastHtml!!.contains("<rect"))
    }

    @Test
    fun `画板_png=false_只落HTML`() {
        PreviewTool.sink = FakePreview()
        val raster = FakeRaster()
        DrawPublish.rasterizer = raster

        val out = RenderArtifactTool.run(
            root,
            JSONObject().put("content", "<div>正文</div>").put("png", false),
        )
        assertTrue(out is ToolOutcome.Ok)
        assertEquals(1, files(DrawPaths.imagesDir(root), "html").size)
        assertEquals(0, files(DrawPaths.imagesDir(root), "png").size)
        assertEquals(0, raster.calls)
    }

    @Test
    fun `画板_截图器报空白_整次失败且说清HTML已落盘`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = FakeRaster(
            fail = "渲染结果是空白（页面没有画出任何内容）：已拒绝产出空白 PNG。",
        )

        val out = RenderArtifactTool.run(root, JSONObject().put("content", "<div>正文</div>"))
        assertTrue("空白不能冒充成功：$out", out is ToolOutcome.Failed)
        val text = (out as ToolOutcome.Failed).error
        assertTrue(text, text.contains("HTML 已经落盘"))
        assertTrue(text, text.contains("空白"))
        assertTrue(text, text.contains("不要重复写 HTML"))
        assertTrue("HTML 仍要在盘上", files(DrawPaths.imagesDir(root), "html").size == 1)
        assertEquals(0, files(DrawPaths.imagesDir(root), "png").size)
    }

    @Test
    fun `画板_截图器没接线_明说失败`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = null

        val out = RenderArtifactTool.run(root, JSONObject().put("content", "<div>正文</div>"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("截图器没接线"))
        assertEquals(1, files(DrawPaths.imagesDir(root), "html").size)
    }

    @Test
    fun `画板_空内容_失败且不落盘`() {
        PreviewTool.sink = FakePreview()
        val out = RenderArtifactTool.run(root, JSONObject().put("content", "  "))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue(files(DrawPaths.imagesDir(root), "html").isEmpty())
    }

    @Test
    fun `画板_外链与脚本在落盘前已剥`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = FakeRaster()
        val out = RenderArtifactTool.run(
            root,
            JSONObject().put(
                "content",
                "<html><body><script>alert(1)</script>" +
                    "<div onclick=\"x()\">正文</div><img src=\"https://evil/a.png\"></body></html>",
            ).put("png", false),
        )
        assertTrue(out is ToolOutcome.Ok)
        val html = files(DrawPaths.imagesDir(root), "html").single().readText()
        assertFalse(html.contains("script", ignoreCase = true))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("evil"))
        assertTrue(html.contains("正文"))
        assertTrue((out as ToolOutcome.Ok).text.contains("已剥掉"))
    }

    // ── draw_table ──────────────────────────────────────────────────────

    @Test
    fun `表格_落产物表格目录_出预览`() {
        val preview = FakePreview()
        PreviewTool.sink = preview
        DrawPublish.rasterizer = FakeRaster()

        val out = DrawTableTool.run(
            root,
            JSONObject().put("source", "| 姓名 | 城市 |\n|---|---|\n| 甲 | 北京 |").put("title", "名单"),
        )
        assertTrue("应当成功：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue(text, text.contains("表格做好了"))
        assertTrue(text, text.contains("产物/表格/"))
        assertEquals(1, files(DrawPaths.tablesDir(root), "html").size)
        assertEquals(0, files(DrawPaths.tablesDir(root), "png").size)
        assertTrue(preview.seen.single().startsWith("产物/表格/"))
        val html = files(DrawPaths.tablesDir(root), "html").single().readText()
        assertTrue(html.contains("北京"))
    }

    @Test
    fun `表格_png=true_截同一份HTML`() {
        PreviewTool.sink = FakePreview()
        val raster = FakeRaster()
        DrawPublish.rasterizer = raster

        val out = DrawTableTool.run(
            root,
            JSONObject().put("source", "| a |\n|---|\n| 1 |").put("png", true),
        )
        assertTrue(out is ToolOutcome.Ok)
        assertEquals(1, files(DrawPaths.tablesDir(root), "png").size)
        assertEquals(1, raster.calls)
    }

    @Test
    fun `表格_截图失败_整次失败但HTML保留`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = FakeRaster(fail = "渲染结果是空白（页面没有画出任何内容）：已拒绝产出空白 PNG。")
        val out = DrawTableTool.run(
            root,
            JSONObject().put("source", "| a |\n|---|\n| 1 |").put("png", true),
        )
        assertTrue(out is ToolOutcome.Failed)
        val text = (out as ToolOutcome.Failed).error
        assertTrue(text.contains("表格 HTML 已经落盘"))
        assertTrue(text.contains("空白"))
        assertEquals(1, files(DrawPaths.tablesDir(root), "html").size)
        assertEquals(0, files(DrawPaths.tablesDir(root), "png").size)
    }

    // ── draw_chart ──────────────────────────────────────────────────────

    @Test
    fun `图表_落图片目录SVG_不出PNG默认`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = FakeRaster()
        val data = JSONObject()
            .put("categories", JSONArray(listOf("一月", "二月")))
            .put(
                "series",
                JSONArray(
                    listOf(
                        JSONObject().put("name", "销量").put("values", JSONArray(listOf(3, 5))),
                    ),
                ),
            )
        val out = DrawChartTool.run(root, JSONObject().put("kind", "bar").put("data", data))
        assertTrue("应当成功：$out", out is ToolOutcome.Ok)
        val svg = files(DrawPaths.imagesDir(root), "svg").single().readText()
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("销量"))
        assertEquals(0, files(DrawPaths.imagesDir(root), "png").size)
    }

    @Test
    fun `图表_png=true_走同一份SVG包页`() {
        PreviewTool.sink = FakePreview()
        val raster = FakeRaster()
        DrawPublish.rasterizer = raster
        val data = JSONObject()
            .put("categories", JSONArray(listOf("甲")))
            .put("series", JSONArray(listOf(JSONObject().put("name", "s").put("values", JSONArray(listOf(1))))))
        val out = DrawChartTool.run(
            root,
            JSONObject().put("kind", "line").put("data", data).put("png", true),
        )
        assertTrue(out is ToolOutcome.Ok)
        assertEquals(1, files(DrawPaths.imagesDir(root), "png").size)
        assertTrue(raster.lastHtml!!.contains("<svg"))
        assertTrue(raster.lastHtml!!.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `图表_截图失败_整次失败但SVG保留`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = FakeRaster(fail = "渲染结果是空白（页面没有画出任何内容）：已拒绝产出空白 PNG。")
        val data = JSONObject()
            .put("categories", JSONArray(listOf("甲")))
            .put("series", JSONArray(listOf(JSONObject().put("name", "s").put("values", JSONArray(listOf(1))))))
        val out = DrawChartTool.run(
            root,
            JSONObject().put("kind", "bar").put("data", data).put("png", true),
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("SVG 已经落盘"))
        assertEquals(1, files(DrawPaths.imagesDir(root), "svg").size)
        assertEquals(0, files(DrawPaths.imagesDir(root), "png").size)
    }

    // ── 落盘判据 ────────────────────────────────────────────────────────

    @Test
    fun `同名不覆盖_第二次是新文件`() {
        PreviewTool.sink = FakePreview()
        DrawPublish.rasterizer = FakeRaster()
        val args = JSONObject().put("content", "<div>正文</div>").put("title", "同名").put("png", false)
        assertTrue(RenderArtifactTool.run(root, args) is ToolOutcome.Ok)
        assertTrue(RenderArtifactTool.run(root, args) is ToolOutcome.Ok)
        assertEquals(2, files(DrawPaths.imagesDir(root), "html").size)
    }

    @Test
    fun `落点越界_拒绝`() {
        val outside = tmp.newFolder("outside")
        val r = DrawPublish.write(root, outside, "x", "txt", "body", 1024)
        assertTrue("越界必须是 Denied：$r", r is DrawResult.Denied)
        assertTrue((r as DrawResult.Denied).message.contains("越界"))
        assertTrue(outside.listFiles().isNullOrEmpty())
    }

    @Test
    fun `超过字节上限_可读错误_不落盘`() {
        val r = DrawPublish.write(root, DrawPaths.imagesDir(root), "x", "html", "abcdef", 3)
        assertTrue(r is DrawResult.Err)
        assertTrue((r as DrawResult.Err).message.contains("上限"))
        assertTrue(files(DrawPaths.imagesDir(root), "html").isEmpty())
    }

    @Test
    fun `预览没推出去_文件仍算写成`() {
        PreviewTool.sink = FakePreview(reason = "玄览页签没打开")
        DrawPublish.rasterizer = FakeRaster()
        val out = RenderArtifactTool.run(
            root,
            JSONObject().put("content", "<div>正文</div>").put("png", false),
        )
        assertTrue("预览失败不该升级成整次失败：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue(text.contains("预览没推出去"))
        assertTrue(text.contains("文件已写好"))
        assertEquals(1, files(DrawPaths.imagesDir(root), "html").size)
    }
}
