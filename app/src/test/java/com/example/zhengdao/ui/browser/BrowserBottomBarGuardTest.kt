// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 底栏改版接线/几何守卫（2026-10-18 定稿 · 方案二甲「五件全可见」）。
 *
 * 本文件是 `BrowserNavCapsuleGuardTest`（第⑨刀 C）的**继任**：那条「106dp 定稿」（地址栏带
 * 50dp ＋ 浮动胶囊栏 56dp）被用户 2026-10-18 拍板作废，两条带子合成一条 56dp ⇒ 判据必须跟着
 * 重拍。定稿与账：`证道设计报告/玄览-底栏改版-定稿与施工单-2026-10-18.md`。
 *
 * 与 [XuanLanPaletteContrastTest] 的分工不变：那个钉**色值对比度**，这个钉**接线与几何**。
 *
 * 判据七组：
 * ① 键位五件一个不缺（后退常驻、前进按需、地址胶囊、刷新↔停止同位、⋯ 菜单）＋ contentDescription 全在；
 * ② 新几何（栏 56 = 48 键 ＋ 上下各 4dp、水平内缩 12、件间距 4、胶囊 40/圆角 20、weight 自适应）；
 * ③ 旧几何不许回潮（`.height(50.dp)`、`top = 6.dp, bottom = 6.dp`、`browser_go`、簇与分隔线）；
 * ④ Column 顺序 = 标签条 → 页面区 → 提示条 → 底栏（提示条搬家是定稿第 4 点）；
 * ⑤ zh/en 的 `browser_*` 键集合对称，`browser_menu` 双侧都在；
 * ⑥ 动作本体仍住 BrowserScreen（主页 = `HOME_URL`、刷新 = `reload`、停止 = `stopLoading`、
 *    提交仍只有一条路径）—— 底栏只发回调，不许在底栏里解析 URL；
 * ⑦ 触感三数（scale .94 / 焦点 2px / 禁用描边 1.4）不许借改版顺手改。
 *
 * 文本门禁的已知局限与对策（与旧版同款教训）：**所有源码断言一律先剥注释**——本刀 KDoc 里就写着
 * `height(50.dp)` / 106dp 这些反例词，不剥注释就是自己拦自己（正例负例同此）。
 * 真机读数（按下触感、菜单弹层、大字号不撑破、深浅色实拍）不归本文件，见交付报告「未验清单」。
 */
class BrowserBottomBarGuardTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 剥注释后的源码（多行注释 ＋ 整行单行注释都剥）。 */
    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun bar() = code("src/main/java/com/example/zhengdao/ui/browser/BrowserBottomBar.kt")

    private fun screen() = code("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt")

    // ── ① 键位与 contentDescription ──

    @Test
    fun `五件键位与描述一个不缺`() {
        val src = bar()
        assertTrue("后退键", src.contains("stringResource(R.string.browser_back)"))
        assertTrue("前进键", src.contains("stringResource(R.string.browser_forward)"))
        assertTrue("刷新键（常态）", src.contains("R.string.browser_reload"))
        assertTrue("停止键（加载中）", src.contains("R.string.browser_stop"))
        assertTrue("⋯ 菜单键", src.contains("stringResource(R.string.browser_menu)"))
        assertTrue("地址胶囊（空 URL 时的提示）", src.contains("stringResource(R.string.browser_url_hint)"))
        // 菜单三项 = 定稿第 5 点：只放代码里已有的动作
        assertTrue("菜单项 主页", src.contains("stringResource(R.string.browser_home)"))
        assertTrue("菜单项 新标签", src.contains("stringResource(R.string.browser_new_tab)"))
        assertTrue("菜单项 关闭标签", src.contains("stringResource(R.string.browser_close_tab)"))
    }

    @Test
    fun `前进键按需出现_主页收进菜单不再常驻`() {
        val src = bar()
        assertTrue("前进键必须包在 if (canForward) 里（定稿：没有前进历史不摆灰死的键）", src.contains("if (canForward) {"))
        assertFalse("主页不许再做常驻键（已收进 ⋯ 菜单）", src.contains("fillColor = palette.homeFill"))
    }

    // ── ② 新几何 ──

    @Test
    fun `新底栏几何_56_48_12_4_40_20`() {
        val src = bar()
        assertTrue("栏高必须 56dp（48 键上下各 4dp，布局承载不靠 padding 撑）", src.contains(".height(56.dp)"))
        assertTrue("键命中区必须 48×48dp", src.contains(".size(48.dp)"))
        assertTrue("键圆角必须 12dp", src.contains("RoundedCornerShape(12.dp)"))
        assertTrue("件间距必须 4dp", src.contains("Arrangement.spacedBy(4.dp)"))
        assertTrue("水平内缩必须 12dp", src.contains(".padding(horizontal = 12.dp)"))
        assertTrue("地址胶囊必须高 40dp", src.contains(".height(40.dp)"))
        assertTrue("地址胶囊必须全圆（圆角 20dp）", src.contains("RoundedCornerShape(20.dp)"))
        assertTrue("胶囊宽度必须自适应（weight），不许写死 195dp/143dp", src.contains(".weight(1f)"))
    }

    @Test
    fun `定稿算式_壳子108_页面604_胶囊195与143`() {
        // 机器数：374.9×813.7dp（1312×2848px ÷ dpr 3.5），取整 375 算宽度分配
        assertEquals("壳子必须 108dp（标签条 52 ＋ 底栏 56）", 108, 52 + 56)
        assertEquals("页面可视必须 604dp（814 − 34 状态 − 108 壳 − 56 App栏 − 12 手势）", 604, 814 - 34 - 108 - 56 - 12)
        val inner = 375 - 12 * 2
        assertEquals("常态胶囊 195dp（3 枚键 ＋ 3 个间距）", 195, inner - (48 * 3 + 4 * 3))
        assertEquals("有前进历史时胶囊 143dp（4 枚键 ＋ 4 个间距）", 143, inner - (48 * 4 + 4 * 4))
        // 旧 106dp 定稿作废：两条带子的账不再成立（防止后人以为 50dp 带还在）
        assertEquals("旧底栏合计是 106dp，本刀作废", 106, 50 + 56)
    }

    // ── ③ 旧几何不许回潮 ──

    @Test
    fun `旧两条带子不回潮`() {
        val src = screen()
        assertFalse("地址栏带 50dp 回来了（定稿：并进底栏）", src.contains(".height(50.dp)"))
        assertFalse("地址栏带的 6dp 上下 padding 回来了", src.contains("top = 6.dp, bottom = 6.dp"))
        assertFalse("「前往」按钮回来了（与键盘 Go 同义，已删）", src.contains("R.string.browser_go"))
        assertFalse("旧胶囊栏组件不许复活", src.contains("BrowserNavCapsule"))
        assertFalse("旧胶囊文件不许复活", repoFile("src/main/java/com/example/zhengdao/ui/browser/BrowserNavCapsule.kt").isFile)
        // 簇与分隔线是两簇布局的产物，合成一条带子后没有它们
        val bar = bar()
        assertFalse("左簇底色不许回来", bar.contains("clusterSecondaryBg"))
        assertFalse("1×20dp 分隔线不许回来", bar.contains(".height(20.dp)"))
        assertFalse("簇圆角 14dp 不许回来", bar.contains("RoundedCornerShape(14.dp)"))
    }

    // ── ④ Column 顺序 ──

    @Test
    fun `列顺序_标签条到页面区到提示条到底栏`() {
        val src = screen()
        val stripAt = src.indexOf("BrowserTabStrip(")
        val pageAt = src.indexOf("AndroidView(")
        val toastAt = src.indexOf("WarningGlyph(tint = MaterialTheme.colorScheme.error")
        val barAt = src.indexOf("BrowserBottomBar(")
        listOf(
            "BrowserTabStrip(" to stripAt,
            "AndroidView(（页面区）" to pageAt,
            "WarningGlyph(（提示条）" to toastAt,
            "BrowserBottomBar(" to barAt,
        ).forEach { (name, at) -> assertTrue("BrowserScreen 里找不到 $name", at >= 0) }
        assertTrue("页面区必须紧跟标签条（50dp 地址带与胶囊栏都撤了）", stripAt < pageAt)
        assertTrue("提示条必须搬到页面区之后（定稿第 4 点：离出错那一页更近）", pageAt < toastAt)
        assertTrue("底栏必须是最后一件（拇指区）", toastAt < barAt)
    }

    // ── ⑤ zh/en 对称 ──

    @Test
    fun `browser 字符串 zh_en 对称`() {
        val zh = code("src/main/res/values/strings.xml")
        val en = code("src/main/res/values-en/strings.xml")
        val keyRegex = Regex("<string name=\"(browser_[a-z_]+)\"")
        val zhKeys = keyRegex.findAll(zh).map { it.groupValues[1] }.toSet()
        val enKeys = keyRegex.findAll(en).map { it.groupValues[1] }.toSet()
        assertEquals("zh/en 的 browser_* 键集合必须一致", zhKeys, enKeys)
        listOf(
            "browser_back", "browser_forward", "browser_reload", "browser_home", "browser_stop",
            "browser_menu", "browser_new_tab", "browser_close_tab", "browser_tabs_full",
            "browser_js_dialog_title", "browser_url_hint",
        ).forEach { key ->
            assertTrue("$key 必须双侧都在", key in zhKeys && key in enKeys)
        }
        // 定稿删除件：browser_go 双侧都不许留（留一侧就破对称，留两侧就是死文案）
        assertFalse("browser_go 必须双侧一起删", "browser_go" in zhKeys || "browser_go" in enKeys)
        // 任务书定值
        assertTrue("browser_back 的 zh 值必须是「后退」", zh.contains("<string name=\"browser_back\">后退</string>"))
        assertTrue("browser_home 的 zh 值必须是「主页」", zh.contains("<string name=\"browser_home\">主页</string>"))
        assertTrue("browser_stop 的 zh 值必须是「停止」", zh.contains("<string name=\"browser_stop\">停止</string>"))
        assertTrue("browser_menu 的 zh 值必须是「更多」", zh.contains("<string name=\"browser_menu\">更多</string>"))
    }

    // ── ⑥ 动作本体仍住 BrowserScreen ＋ 提交仍一条路 ──

    @Test
    fun `动作仍住_主屏_底栏只发回调`() {
        val src = screen()
        assertTrue("刷新键加载中必须走 stopLoading", src.contains("engine.stopLoading()"))
        assertTrue("常态刷新必须走 reload", src.contains("engine.reload()"))
        assertTrue("主页必须走 HOME_URL（菜单第一项）", src.contains("engine.loadUrl(HOME_URL)"))
        assertTrue("HOME_URL 必须是必应国内版（设计稿起始页）", src.contains("https://cn.bing.com"))
        assertTrue("前进判据仍取引擎", src.contains("canForward = engine.canGoForward()"))
        assertTrue("底栏提交必须接到唯一入口 navigate", src.contains("onSubmit = navigate"))
        // 底栏自己不许解析/导航（与起始页同一口径：纯 UI ＋ 回调）
        val bar = bar()
        listOf("Uri.parse", "loadUrl(", "normalizeInput", "BrowserTabManager", "engine.").forEach {
            assertFalse("底栏不该出现 $it（动作本体归 BrowserScreen）", bar.contains(it))
        }
        assertTrue("编辑态关掉必须回 BrowserScreen 管（共 2 处：切 Tab ＋ 提交）", Regex("addressEditing = false").findAll(src).count() == 2)
    }

    @Test
    fun `胶囊两态读页面真实URL`() {
        val src = screen()
        assertTrue(
            "展示态必须读活跃标签 state.url（不是 urlInput，否则提交搜索词后胶囊还显示搜索词）",
            src.contains("displayUrl = active?.state?.url ?: \"\""),
        )
        assertTrue("进编辑态要先把真实 URL 灌进输入框", src.contains("onEditStart = {"))
        val bar = bar()
        assertTrue("常态走 displayHost", bar.contains("BrowserUrlPolicy.displayHost(displayUrl)"))
        assertTrue("常态走 displayPath", bar.contains("BrowserUrlPolicy.displayPath(displayUrl)"))
        assertTrue("锁形走 isSecure", bar.contains("BrowserUrlPolicy.isSecure(displayUrl)"))
        assertTrue("键盘 Go 是唯一提交手势（前往按钮已删）", bar.contains("ImeAction.Go"))
    }

    // ── ⑦ 触感三数 ──

    @Test
    fun `按下反馈_scale94_焦点框_2px_禁用描边_1_4`() {
        val src = bar()
        assertTrue("按下必须 scale(.94)", src.contains("0.94f"))
        assertTrue("焦点态必须 2px 外框", src.contains("if (focused) 2.dp else 0.dp"))
        assertTrue("禁用描边必须降到 1.4", src.contains("val strokeWidth = if (enabled) 1.9.dp else 1.4.dp"))
    }

    @Test
    fun `加载中刷新键原位变停止红`() {
        val src = bar()
        assertTrue(
            "加载中必须走 stop 分支（StopGlyph + stopRed）",
            src.contains("if (loading) {") && src.contains("StopGlyph(") && src.contains("palette.stopRed"),
        )
    }
}
