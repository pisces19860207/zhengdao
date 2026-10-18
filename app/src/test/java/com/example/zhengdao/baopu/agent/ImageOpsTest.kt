// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// `image_ops` 的判据层（2026-10-18 用户逐字「落」）：全部打在**纯函数**上——
// 缩放/裁剪的边界、输出命名、缺参报缺哪个、主色统计口径，一条都不靠真解码。
//
// 另有三条源码级门禁：注册表里有它、权限是 CONFIRM、没有递归删除之类越界能力。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageOpsTest {

    // ── 缩放 ────────────────────────────────────────────────────────────────

    @Test
    fun `P1_等比缩到长边_不拉伸`() {
        assertEquals(512 to 384, ImageOps.fitSize(4000, 3000, 512))
        assertEquals(384 to 512, ImageOps.fitSize(3000, 4000, 512))
        assertEquals(4000 to 3000, ImageOps.fitSize(4000, 3000, 4096))  // 已经够小，原样
        assertEquals(100 to 100, ImageOps.fitSize(100, 100, 0))          // 非法 maxEdge 不崩
    }

    // ── 裁剪 ────────────────────────────────────────────────────────────────

    @Test
    fun `P2_裁剪矩形正常通过`() {
        val r = ImageOps.clampCrop(1000, 800, PixelRect(100, 50, 600, 450))
        assertEquals(PixelRect(100, 50, 600, 450), r)
        assertEquals(500, r!!.width)
        assertEquals(400, r.height)
    }

    @Test
    fun `P3_越界的裁剪矩形被夹到图内`() {
        assertEquals(PixelRect(0, 0, 1000, 800), ImageOps.clampCrop(1000, 800, PixelRect(-10, -10, 5000, 5000)))
        assertEquals(PixelRect(900, 700, 1000, 800), ImageOps.clampCrop(1000, 800, PixelRect(900, 700, 2000, 2000)))
    }

    @Test
    fun `P4_反向矩形与完全在图外都判非法`() {
        assertNull("left>right 是写反了，不替模型猜", ImageOps.clampCrop(1000, 800, PixelRect(600, 50, 100, 450)))
        assertNull("整块在图外，夹出来是空", ImageOps.clampCrop(1000, 800, PixelRect(2000, 2000, 3000, 3000)))
        assertNull(ImageOps.clampCrop(0, 0, PixelRect(0, 0, 10, 10)))
    }

    // ── 命名与格式 ──────────────────────────────────────────────────────────

    @Test
    fun `P5_输出名默认带动作_不碰原图`() {
        assertEquals("photo-resize.png", ImageOps.outName("photo.png", "resize", "png"))
        assertEquals("photo-convert.jpg", ImageOps.outName("photo.png", "convert", "jpg"))
        assertEquals("photo-crop.webp", ImageOps.outName("photo.jpg", "crop", "webp"))
        assertEquals("photo-resize.jpg", ImageOps.outName("photo.jpeg", "resize", "jpeg"))
    }

    @Test
    fun `P6_图标五档命名固定`() {
        assertEquals(5, ImageOps.ICON_SIZES.size)
        assertEquals(listOf(48, 72, 96, 144, 192), ImageOps.ICON_SIZES)
        assertEquals("icon-48.png", ImageOps.iconName("icon.png", 48))
        assertEquals("app-192.png", ImageOps.iconName("app.jpg", 192))
    }

    @Test
    fun `P7_只认三种格式且jpg无透明通道`() {
        assertEquals("png", ImageOps.formatOf("a.png"))
        assertEquals("jpg", ImageOps.formatOf("a.JPG"))
        assertEquals("jpg", ImageOps.formatOf("a.jpeg"))
        assertEquals("webp", ImageOps.formatOf("a.webp"))
        assertNull("gif 我们不认（编不出来）", ImageOps.formatOf("a.gif"))
        assertNull(ImageOps.formatOf("a.txt"))
        assertTrue(ImageOps.supportsAlpha("png"))
        assertTrue(ImageOps.supportsAlpha("webp"))
        assertTrue("jpg 必须压白底而不是静默丢 alpha", !ImageOps.supportsAlpha("jpg"))
    }

    // ── 缺参：报缺哪个，不猜默认值 ──────────────────────────────────────────

    @Test
    fun `P8_缺参要说清缺哪个`() {
        assertTrue(ImageOps.missingParam("resize", null, null, null, false)!!.contains("max_edge"))
        assertTrue(ImageOps.missingParam("crop", null, null, null, false)!!.contains("left"))
        assertTrue(ImageOps.missingParam("convert", null, null, null, false)!!.contains("format"))
        assertTrue(ImageOps.missingParam("放烟花", null, null, null, false)!!.contains("不认识"))
        assertNull("palette 不需要额外参数", ImageOps.missingParam("palette", null, null, null, false))
        assertNull("icon 不需要额外参数", ImageOps.missingParam("icon", null, null, null, false))
    }

    // ── 主色 ────────────────────────────────────────────────────────────────

    @Test
    fun `P9_主色按面积排序_透明像素不参与`() {
        val px = IntArray(100) { i ->
            when {
                i < 90 -> 0xFFFF0000.toInt()          // 90 个纯红
                i < 100 -> 0xFF0000FF.toInt()         // 10 个纯蓝
                else -> 0
            }
        }
        val list = ImageOps.palette(px, 2)
        assertEquals(2, list.size)
        assertEquals("#FF0000", list[0].hex)
        assertEquals(0.9, list[0].share, 0.01)
        assertEquals("#0000FF", list[1].hex)

        val withTransparent = IntArray(100) { if (it < 50) 0x00000000 else 0xFF00FF00.toInt() }
        val l2 = ImageOps.palette(withTransparent, 3)
        assertEquals("全透明不算颜色", 1, l2.size)
        assertEquals("#00FF00", l2[0].hex)
        assertEquals(1.0, l2[0].share, 0.001)
    }

    @Test
    fun `P10_全透明图与空数组都返回空_不崩`() {
        assertTrue(ImageOps.palette(IntArray(0), 3).isEmpty())
        assertTrue(ImageOps.palette(IntArray(10) { 0 }, 3).isEmpty())
        assertTrue("count<=0 也别炸", ImageOps.palette(IntArray(10) { 0xFF123456.toInt() }, 0).isEmpty())
    }

    // ── 源码级：接上了才算数 ────────────────────────────────────────────────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `G1_注册表里有image_ops且默认启用`() {
        val reg = code("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt")
        assertTrue("ENTRIES 里没注册 image_ops", reg.contains("Entry(\"image_ops\")"))
        assertTrue("DEFAULT_ENABLED 里没有 image_ops", reg.contains("\"image_ops\","))
    }

    @Test
    fun `G2_权限是CONFIRM且源码写死`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/tools/ImageOpsTool.kt")
        assertTrue("改图必须过确认卡", src.contains("permission = ToolPermission.CONFIRM"))
        assertTrue("不许出现 FREE 权限", !src.contains("permission = ToolPermission.FREE"))
    }

    @Test
    fun `G3_永不覆盖原图_也没有越界能力`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/tools/ImageOpsTool.kt")
        assertTrue("输出路径必须过 PathGuard", src.contains("PathGuard.isInside"))
        assertTrue("必须拒绝覆盖已存在的目标", src.contains("目标已存在，不做覆盖"))
        assertTrue("必须拒绝和原图同路径", src.contains("image_ops 永不覆盖原图"))
        assertTrue("不存在递归删除", !src.contains("deleteRecursively"))
    }

    @Test
    fun `G4_设置页两个语言都有标签与副标题`() {
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("baopuToolLabel 漏了 image_ops", screen.contains("\"image_ops\" -> R.string.baopu_tool_image_ops"))
        assertTrue("baopuToolDesc 漏了 image_ops", screen.contains("\"image_ops\" -> R.string.baopu_tool_image_ops_desc"))
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        listOf("baopu_tool_image_ops", "baopu_tool_image_ops_desc").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
}
