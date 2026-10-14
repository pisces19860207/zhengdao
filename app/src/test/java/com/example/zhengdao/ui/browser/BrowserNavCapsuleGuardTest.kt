// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底栏改版接线/几何守卫（第⑨刀 C · v1.1 主稿 + 探索稿 B 收窄定稿）。
 *
 * 与 [XuanLanPaletteContrastTest] 分工：那个钉**色值对比度**，这个钉**接线与几何**——
 * ① 四键 contentDescription 一个不少（后退/前进/刷新/主页，加载中→停止）；
 * ② 收窄几何（栏高 56 = 48 键 + 上下各 4dp、地址栏带 50dp、合计 106dp）；
 * ③ zh/en 双语对称（759+6 条不许破对称）；
 * ④ 刷新/停止同位、主页键唯一填充。
 *
 * 文本门禁的已知局限（与 ColorsXmlGuardTest 同类）：注释里的同名字面量能满足断言——
 * 所以断言尽量挑**不会被写进注释**的形态（如 `stringResource(R.string.browser_home)` 整串）。
 * 真机读数（按下反馈/焦点框/实际触感）归蓝总管采。
 */
class BrowserNavCapsuleGuardTest {

    private fun source(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private fun capsuleSource() = source("src/main/java/com/example/zhengdao/ui/browser/BrowserNavCapsule.kt")

    private fun screenSource() = source("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")

    // ── ① 四键 contentDescription 一个不少 ──

    @Test
    fun `四键 contentDescription 全部接线`() {
        val src = capsuleSource()
        // 后退/前进/刷新（加载中切停止）/主页 —— 整串匹配，注释里不容易误触
        assertTrue("后退键 contentDescription", src.contains("stringResource(R.string.browser_back)"))
        assertTrue("前进键 contentDescription", src.contains("stringResource(R.string.browser_forward)"))
        assertTrue("刷新键 contentDescription（常态）", src.contains("R.string.browser_reload"))
        assertTrue("停止键 contentDescription（加载中）", src.contains("R.string.browser_stop"))
        assertTrue("主页键 contentDescription", src.contains("stringResource(R.string.browser_home)"))
    }

    // ── ② 收窄几何 ──

    @Test
    fun `胶囊栏高 56dp_键 48dp`() {
        val src = capsuleSource()
        assertTrue(
            "胶囊栏高必须 56dp（48 键上下各 4dp；布局承载，不许用 padding 撑高）",
            src.contains(".height(56.dp)")
        )
        assertTrue("键命中区必须 48×48dp", src.contains(".size(48.dp)"))
        assertTrue("键圆角必须 12dp", src.contains("RoundedCornerShape(12.dp)"))
        assertTrue("簇圆角必须 14dp（与键成内嵌层次）", src.contains("RoundedCornerShape(14.dp)"))
        assertTrue("分隔线必须 1×20dp", src.contains(".width(1.dp)") && src.contains(".height(20.dp)"))
    }

    @Test
    fun `地址栏带 50dp_与胶囊间距 6dp_合计 106dp`() {
        val src = screenSource()
        assertTrue("地址栏带必须高 50dp", src.contains(".height(50.dp)"))
        assertTrue(
            "地址栏带上下 padding 6dp（下 padding 即与胶囊的 6px 间距）",
            src.contains("top = 6.dp, bottom = 6.dp")
        )
        // 50 + 56 = 106 ≤ 106（任务书上限）；几何等式在此显式钉住
        assertEquals("底栏合计必须 ≤106dp", 106, 50 + 56)
    }

    // ── ③ zh/en 双语对称 ──

    @Test
    fun `browser 字符串 zh_en 对称`() {
        val zh = source("src/main/res/values/strings.xml")
        val en = source("src/main/res/values-en/strings.xml")
        val keyRegex = Regex("<string name=\"(browser_[a-z_]+)\"")
        val zhKeys = keyRegex.findAll(zh).map { it.groupValues[1] }.toSet()
        val enKeys = keyRegex.findAll(en).map { it.groupValues[1] }.toSet()
        assertEquals("zh/en 的 browser_* 键集合必须一致", zhKeys, enKeys)
        // 本刀新增/改值的键必须都在
        listOf(
            "browser_back", "browser_forward", "browser_reload", "browser_home", "browser_stop",
            "browser_new_tab", "browser_close_tab", "browser_tabs_full", "browser_js_dialog_title",
        ).forEach { key ->
            assertTrue("$key 必须双侧都在", key in zhKeys && key in enKeys)
        }
        // 任务书定值：后退（不是"返回"）
        assertTrue("browser_back 的 zh 值必须是「后退」", zh.contains("<string name=\"browser_back\">后退</string>"))
        assertTrue("browser_home 的 zh 值必须是「主页」", zh.contains("<string name=\"browser_home\">主页</string>"))
        assertTrue("browser_stop 的 zh 值必须是「停止」", zh.contains("<string name=\"browser_stop\">停止</string>"))
    }

    // ── ④ 刷新/停止同位 + 主页键唯一填充 ──

    @Test
    fun `刷新键加载中变停止_主页键唯一填充`() {
        val src = capsuleSource()
        assertTrue(
            "加载中刷新键必须走 stopLoading 分支（stopRed + StopGlyph）",
            src.contains("if (loading) {") && src.contains("StopGlyph(") && src.contains("palette.stopRed")
        )
        assertTrue(
            "主页键必须是唯一 fillColor 非空的键（玄青雾，整栏唯一填充）",
            src.contains("fillColor = palette.homeFill")
        )
        // 只有主页键传 fillColor（数一下出现次数）
        val fillCount = Regex("fillColor = palette\\.homeFill").findAll(src).count()
        assertEquals("fillColor 只允许主页键一处用", 1, fillCount)
    }

    @Test
    fun `按下反馈 scale94_焦点框 2px`() {
        val src = capsuleSource()
        assertTrue("按下必须 scale(.94)", src.contains("0.94f"))
        assertTrue("焦点态必须 2px 外框", src.contains("if (focused) 2.dp else 0.dp"))
        assertTrue("禁用描边必须降到 1.4", src.contains("val strokeWidth = if (enabled) 1.9.dp else 1.4.dp"))
    }

    @Test
    fun `BrowserScreen 接停止与主页语义`() {
        val src = screenSource()
        assertTrue("刷新键加载中必须走 stopLoading", src.contains("engine.stopLoading()"))
        assertTrue("常态刷新必须走 reload", src.contains("engine.reload()"))
        assertTrue("主页键必须走 HOME_URL", src.contains("engine.loadUrl(HOME_URL)"))
        assertTrue("HOME_URL 必须是必应国内版（设计稿起始页）", src.contains("https://cn.bing.com"))
    }
}
