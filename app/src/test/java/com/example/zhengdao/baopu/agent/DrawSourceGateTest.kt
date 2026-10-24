// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：**源码级负例**（派工单 §3 的「各红」里，有几条只有读源码才验得到）。
//
// 这里不测行为，而是守边界本身：
// - 不调生图模型：artifact 三件不许出现 GenerateImage / generate_image；
// - 不联网：不许 okhttp / HttpURLConnection / 裸 URL / loadUrl；
// - 不跑 JS：不许 evaluateJavascript，沙箱必须 javaScriptEnabled=false；
// - 空白冒充成功：沙箱必须有空白检测与拒绝文案；
// - 不手搓私有 DSL：只认 bar/line，没有 flowchart/sequence 实现文件。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawSourceGateTest {

    private val toolsDir = File("src/main/java/com/example/zhengdao/baopu/agent/tools")

    private val drawFiles = listOf(
        "Draw.kt", "DrawArtifact.kt", "DrawChart.kt", "DrawChartTool.kt",
        "DrawTable.kt", "DrawTableTool.kt", "DrawPublish.kt",
        "MarkupSanitizer.kt", "RenderArtifactTool.kt", "ArtifactRaster.kt",
    )

    private fun source(name: String): String {
        val f = File(toolsDir, name)
        assertTrue("找不到 $f（单测工作目录应在 app/ 下）", f.exists())
        return f.readText()
    }

    /** 去掉整行注释后再看：KDoc 里可以写「不调 generate_image」，代码里不行。 */
    private fun code(name: String): String =
        source(name).lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
            }
            .joinToString("\n")

    @Test
    fun `不调生图模型`() {
        for (name in drawFiles) {
            val c = code(name)
            assertFalse("$name 不许引用 GenerateImage", c.contains("GenerateImage"))
            assertFalse("$name 不许调用 generate_image", c.contains("\"generate_image\""))
        }
    }

    @Test
    fun `不联网_没有网络客户端与裸URL`() {
        val banned = listOf(
            "okhttp3", "HttpURLConnection", "java.net.URL", "openConnection(",
            "java.net.Socket", "loadUrl(",
        )
        for (name in drawFiles) {
            val c = code(name)
            for (b in banned) {
                assertFalse("$name 不许出现 $b", c.contains(b))
            }
        }
    }

    @Test
    fun `不执行JS_沙箱关脚本且没有求值入口`() {
        for (name in drawFiles) {
            val c = code(name)
            assertFalse("$name 不许调 evaluateJavascript", c.contains("evaluateJavascript"))
            assertFalse("$name 不许打开 JS", c.contains("javaScriptEnabled = true"))
        }
        val raster = code("ArtifactRaster.kt")
        assertTrue(raster.contains("javaScriptEnabled = false"))
        assertTrue("还要禁止弹窗", raster.contains("javaScriptCanOpenWindowsAutomatically = false"))
    }

    @Test
    fun `沙箱断网_双保险`() {
        val raster = code("ArtifactRaster.kt")
        assertTrue("WebSettings 断网", raster.contains("blockNetworkLoads = true"))
        assertTrue("请求级拦截", raster.contains("shouldInterceptRequest"))
        assertTrue("外部 URL 一律拦", raster.contains("shouldOverrideUrlLoading"))
        assertTrue("只放 about 与 data", raster.contains("about:") && raster.contains("data:"))
        assertTrue("不能用 View.post（未 attach 死锁）", raster.contains("Handler(Looper.getMainLooper())"))
    }

    @Test
    fun `空白不许冒充成功`() {
        val raster = source("ArtifactRaster.kt")
        assertTrue("必须有空白像素检测", raster.contains("isBlank("))
        assertTrue("必须拒绝空白 PNG", raster.contains("已拒绝产出空白 PNG"))
        // 工具层也一样：截图失败要说清「HTML 已落盘」，而不是回成功
        assertTrue(code("RenderArtifactTool.kt").contains("HTML 已经落盘"))
        assertTrue(code("DrawChartTool.kt").contains("SVG 已经落盘"))
        assertTrue(code("DrawTableTool.kt").contains("表格 HTML 已经落盘"))
    }

    @Test
    fun `只做bar与line_没有私有DSL`() {
        assertEquals(listOf("bar", "line"), DrawChart.KINDS)
        assertFalse(File(toolsDir, "DrawDiagram.kt").exists())
        assertFalse(File(toolsDir, "DrawDiagramTool.kt").exists())
        assertFalse(File(toolsDir, "DrawRaster.kt").exists())
        assertFalse(code("DrawChart.kt").contains("\"flowchart\""))
        assertFalse(code("DrawChart.kt").contains("\"sequence\""))
    }

    @Test
    fun `落点只有图片与产物表格`() {
        assertEquals("图片", DrawPaths.IMAGES)
        assertEquals("产物/表格", DrawPaths.TABLES)
        for (name in drawFiles) {
            val c = source(name)
            assertFalse("$name 不许写系统相册/外部路径", c.contains("MediaStore"))
        }
    }

    @Test
    fun `三件都必须先消毒再落盘`() {
        assertTrue(code("DrawArtifact.kt").contains("MarkupSanitizer.sanitize"))
        assertTrue(code("DrawTable.kt").contains("MarkupSanitizer.sanitizeHtml"))
        assertTrue(code("DrawChartTool.kt").contains("MarkupSanitizer.sanitizeSvg"))
        assertTrue(code("RenderArtifactTool.kt").contains("DrawArtifact.render"))
    }

    @Test
    fun `工具声明_确认制与本地渲染口径`() {
        val root = File(".")
        val specs = listOf(
            RenderArtifactTool.spec { root },
            DrawChartTool.spec { root },
            DrawTableTool.spec { root },
        )
        for (s in specs) {
            assertEquals("${s.name} 必须每次弹确认卡", ToolPermission.CONFIRM, s.permission)
            assertEquals("${s.name} 超时用 DRAW_MS", ToolTimeout.DRAW_MS, s.timeoutMs)
            assertTrue("${s.name} 描述超过上限", s.description.length <= ToolSpec.MAX_DESC)
        }
        assertTrue(RenderArtifactTool.DESCRIPTION.contains("不联网"))
        assertTrue(RenderArtifactTool.DESCRIPTION.contains("不执行 JS"))
        assertTrue(DrawChartTool.DESCRIPTION.contains("本地渲染"))
        assertTrue(DrawTableTool.DESCRIPTION.contains("产物/表格"))
    }
}
