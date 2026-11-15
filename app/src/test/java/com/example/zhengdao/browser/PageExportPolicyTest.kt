// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设备尺寸预设与导出的**纯判据**（651）：三档预设、自定义夹紧、只缩不放、
 * 「未加载完不许导出」七种拒绝、整页截断、PDF 分页。
 *
 * 这些数都是用户能看见的行为（"桌面档看起来是多大""PDF 几页"），所以逐格钉，
 * 不用"大概对"的近似断言。
 */
class PageExportPolicyTest {

    // ── 预设 ────────────────────────────────────────────────────────────────

    @Test
    fun `手机档跟随屏幕_零改动`() {
        val vp = PageExportPolicy.viewportOf(PageExportPolicy.Preset.PHONE)
        assertTrue("手机档必须是「跟随屏幕」（不写死宽高）", vp.followsScreen)
        assertEquals(null, vp.widthDp)
        assertEquals(null, vp.heightDp)
    }

    @Test
    fun `平板与桌面是写死的两档`() {
        val tablet = PageExportPolicy.viewportOf(PageExportPolicy.Preset.TABLET)
        assertEquals(768, tablet.widthDp)
        assertEquals(1024, tablet.heightDp)
        val desktop = PageExportPolicy.viewportOf(PageExportPolicy.Preset.DESKTOP)
        assertEquals(1280, desktop.widthDp)
        assertEquals(800, desktop.heightDp)
    }

    @Test
    fun `自定义_宽夹到区间内_高按 4比3`() {
        assertEquals(768, PageExportPolicy.customViewport(768).widthDp)
        assertEquals(576, PageExportPolicy.customViewport(768).heightDp)
        // 三个边界：太窄 / 太宽 / 正好
        assertEquals(PageExportPolicy.MIN_CUSTOM_WIDTH_DP, PageExportPolicy.customViewport(10).widthDp)
        assertEquals(PageExportPolicy.MAX_CUSTOM_WIDTH_DP, PageExportPolicy.customViewport(99999).widthDp)
        assertEquals(600, PageExportPolicy.customViewport(600).widthDp)
        assertEquals(450, PageExportPolicy.customViewport(600).heightDp)
    }

    // ── 导出目标（预设只作用于导出）────────────────────────────────────────

    @Test
    fun `跟随屏幕就用屏幕像素_与旧取图一模一样`() {
        val t = PageExportPolicy.captureTarget(PageExportPolicy.FOLLOW_SCREEN, 3.5f, 1312, 2848)
        assertEquals(1312, t.w)
        assertEquals(2848, t.h)
    }

    @Test
    fun `桌面档按 density 换成设备像素`() {
        val desktop = PageExportPolicy.viewportOf(PageExportPolicy.Preset.DESKTOP)
        val t = PageExportPolicy.captureTarget(desktop, 3.5f, 1312, 2848)
        assertEquals(4480, t.w)
        assertEquals(2800, t.h)
        val tablet = PageExportPolicy.viewportOf(PageExportPolicy.Preset.TABLET)
        val t2 = PageExportPolicy.captureTarget(tablet, 2f, 800, 1200)
        assertEquals(1536, t2.w)
        assertEquals(2048, t2.h)
    }

    // ── 判据：未加载完 ⇒ 拒绝导出 ────────────────────────────────────────────

    @Test
    fun `七种情形逐格判_只有全绿才放行`() {
        data class Case(
            val name: String,
            val hasTab: Boolean,
            val loading: Boolean,
            val everLoaded: Boolean,
            val rendered: Boolean,
            val restored: Boolean,
            val recycled: Boolean,
            val want: PageExportPolicy.Refusal,
        )

        val cases = listOf(
            Case("全绿 ⇒ 可以导", true, false, true, true, false, false, PageExportPolicy.Refusal.NONE),
            Case("没有标签", false, false, false, false, false, false, PageExportPolicy.Refusal.NO_TAB),
            Case("恢复出来还没打开", true, false, false, false, true, false, PageExportPolicy.Refusal.RESTORED),
            Case("被系统回收", true, false, true, true, false, true, PageExportPolicy.Refusal.RECYCLED),
            Case("还在加载", true, true, true, true, false, false, PageExportPolicy.Refusal.LOADING),
            Case("从没加载过", true, false, false, false, false, false, PageExportPolicy.Refusal.NOT_LOADED),
            Case("载入过但没在前台画出来", true, false, true, false, false, false, PageExportPolicy.Refusal.NOT_RENDERED),
        )
        for (c in cases) {
            assertEquals(
                c.name,
                c.want,
                PageExportPolicy.refusalOf(
                    hasTab = c.hasTab,
                    loading = c.loading,
                    everLoaded = c.everLoaded,
                    renderedWhileForeground = c.rendered,
                    restored = c.restored,
                    recycled = c.recycled,
                ),
            )
        }
    }

    @Test
    fun `加载中优先于没渲染_理由说得更准`() {
        // 加载中的页同时"没画过"，但用户该看到的是"还在加载"而不是"还没画出来"
        assertEquals(
            PageExportPolicy.Refusal.LOADING,
            PageExportPolicy.refusalOf(
                hasTab = true, loading = true, everLoaded = true,
                renderedWhileForeground = false, restored = false, recycled = false,
            ),
        )
    }

    @Test
    fun `只有 NONE 是放行`() {
        val all = PageExportPolicy.Refusal.entries
        assertEquals("拒绝档必须正好七种", 7, all.size)
        assertEquals(1, all.count { it == PageExportPolicy.Refusal.NONE })
    }

    // ── 整页截断 ────────────────────────────────────────────────────────────

    @Test
    fun `整页高度夹在 8 到 6000 之间且说得清截断`() {
        assertEquals(8, PageExportPolicy.captureHeightPx(0))
        assertEquals(1234, PageExportPolicy.captureHeightPx(1234))
        assertEquals(6000, PageExportPolicy.captureHeightPx(20000))
        assertFalse("没超上限就不叫截断", PageExportPolicy.truncated(6000))
        assertTrue("超上限必须标截断", PageExportPolicy.truncated(6001))
    }

    // ── PDF 分页 ────────────────────────────────────────────────────────────

    @Test
    fun `桌面档整页要按像素预算降高_否则 107MB 必 OOM`() {
        // 手机档 1312px 宽：预算 16M/1312 = 12195 ⇒ 仍由 6000 高度上限决定（老行为不变）
        assertEquals(6000, PageExportPolicy.captureHeightPx(20000, 1312))
        // 桌面档 4480px 宽：16M/4480 = 3571 ⇒ 高度降到 3571（再乘 4B ≈ 64MB）
        assertEquals(3571, PageExportPolicy.captureHeightPx(20000, 4480))
        assertEquals("没超预算就不算截断", false, PageExportPolicy.truncated(3000, 4480))
        assertEquals("超预算必须标截断", true, PageExportPolicy.truncated(5000, 4480))
    }

    @Test
    fun `A4 一页装得下的源图高度_按可用宽算比例`() {
        // 可用宽 595-36=559、可用高 842-36=806；源宽 559 ⇒ 比例 1 ⇒ 一页装 806px
        assertEquals(806, PageExportPolicy.sourceSliceHeightPx(559, 5000))
        // 源宽 1118（两倍）⇒ 比例 1/2 ⇒ 一页装 1612 源像素
        assertEquals(1612, PageExportPolicy.sourceSliceHeightPx(1118, 5000))
    }

    @Test
    fun `PDF 页数按切片向上取整_空的也至少一页`() {
        val slice = PageExportPolicy.sourceSliceHeightPx(559, 3000)
        assertEquals(1, PageExportPolicy.pdfPageCount(559, slice))
        assertEquals(2, PageExportPolicy.pdfPageCount(559, slice + 1))
        assertEquals(4, PageExportPolicy.pdfPageCount(559, slice * 4))
        assertEquals("空图也出一页，不留空文档", 1, PageExportPolicy.pdfPageCount(559, 0))
    }

    // ── 三条「不许」的源码级护栏（行为在这层，接线在 PageExportGateTest） ─────────

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun `导出链路绝不联网_不 reload 不新建 WebView`() {
        val s = src("src/main/java/com/example/zhengdao/browser/PageExporter.kt")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
        for (banned in listOf("loadUrl(", "reload(", "WebView(", "HttpURLConnection", "okhttp")) {
            assertFalse(
                "导出链里不许出现 $banned（导出＝把眼前这一页拿出来，不是再去要一次）",
                s.contains(banned),
            )
        }
    }
}
