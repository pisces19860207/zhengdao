// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 对比度守卫 —— **从源码里取值、当场按 WCAG 公式算**，不抄注释里的数字。
 *
 * 历史：B-补-25（2026-10-16）先钉了终端的绿地/蓝字两组；第一百三十七批（2026-10-22）
 * 换 Token 时**先改判据、再换色**（派工单红线：反了必红，红了不许放宽判据）。
 * 现值覆盖《抱朴UI设计语言方案-纸墨黛青》§2.1 定稿全量表 ＋ §16 浅/深 28 槽配对
 * ＋ §15.2 终端内联色四处 ＋ 「IOS 零残留」「绿色零残留」两条文本判据。
 *
 * 三条纪律：
 * 1. 取值一律走源码（`Color.kt` 常量 / `Theme.kt` 槽位 / 布局内联色），不写字面量；
 * 2. 门槛写死在断言里：正文 4.5:1、非文字边界 3:1；
 * 3. **不达标就是设计错**——不许把门槛改小换绿（§2.1 末尾原文）。
 */
class ContrastGuardTest {

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    /** 从 `res/values/colors.xml` 取色值，返回 `#RRGGBB`。 */
    private fun xmlColor(xmlRel: String, name: String): String {
        val xml = repoFile(xmlRel).readText()
        val m = Regex("""<color name="$name">#(?:[0-9A-Fa-f]{2})?([0-9A-Fa-f]{6})</color>""").find(xml)
            ?: throw AssertionError("$xmlRel 里找不到 <color name=\"$name\">")
        return "#" + m.groupValues[1].uppercase()
    }

    /** 从 `Color.kt` 取 `val NAME = Color(0xAARRGGBB)`。 */
    private fun composeColor(valName: String): String {
        val kt = repoFile(COLOR_KT).readText()
        val m = Regex("""val $valName = Color\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\)""").find(kt)
            ?: throw AssertionError("Color.kt 里找不到 val $valName = Color(0xAARRGGBB)")
        return "#" + m.groupValues[1].uppercase()
    }

    /** 布局里 `@+id/<viewId>` 那个 View 的 `android:<attr>="#RRGGBB"`。 */
    private fun layoutAttr(layoutRel: String, viewId: String, attr: String): String {
        val xml = repoFile(layoutRel).readText()
        val start = xml.indexOf("@+id/$viewId")
        assertTrue("$layoutRel 里找不到 @+id/$viewId", start >= 0)
        val tail = xml.substring(start, (start + 1200).coerceAtMost(xml.length))
        val m = Regex("""android:$attr="#(?:[0-9A-Fa-f]{2})?([0-9A-Fa-f]{6})"""").find(tail)
            ?: throw AssertionError("$viewId 上没有硬编码 android:$attr（改成主题色时本测需同步改判据）")
        return "#" + m.groupValues[1].uppercase()
    }

    /** `Theme.kt` 里某个 colorScheme 定义的正文（从 `(` 到方案末尾的 `\n)`）。 */
    private fun schemeBody(schemeName: String): String {
        val kt = repoFile(THEME_KT).readText()
        val start = kt.indexOf("val $schemeName = ")
        if (start < 0) throw AssertionError("Theme.kt 里找不到 val $schemeName")
        val open = kt.indexOf('(', start)
        val close = kt.indexOf("\n)", open)
        if (open < 0 || close < 0) throw AssertionError("$schemeName 的括号不完整，无法解析槽位")
        return kt.substring(open, close)
    }

    /** 槽位值可能是 `Color(0xAARRGGBB)`、`Color.White` 或常量名——三种都解到 `#RRGGBB`。 */
    private fun rawColor(token: String): String = when {
        token == "Color.White" -> "#FFFFFF"
        token == "Color.Black" -> "#000000"
        token.startsWith("Color(0x") -> "#" + token.substring(8, 16).uppercase()
        else -> composeColor(token)
    }

    /** 从 `Theme.kt` 取某方案的某个槽位，解析到最终色值。 */
    private fun slotColor(schemeName: String, slot: String): String {
        val body = schemeBody(schemeName)
        val m = Regex(
            """(?m)^\s*$slot\s*=\s*(Color\.White|Color\.Black|Color\(0x[0-9A-Fa-f]{8}\)|[A-Za-z][A-Za-z0-9_]*)\s*,""",
        ).find(body) ?: throw AssertionError("$schemeName 里找不到槽位 $slot")
        return rawColor(m.groupValues[1])
    }

    // ── WCAG 2.x 相对亮度与对比度（纯函数，判据的主体）──────────────
    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun luminance(hex: String): Double {
        val h = hex.trimStart('#')
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(fg: String, bg: String): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAt(label: String, fg: String, bg: String, need: Double, kind: String) {
        val r = contrast(fg, bg)
        assertTrue(
            "%s：%s 在 %s 上只有 %.2f:1，低于%s门槛 %.1f:1".format(label, fg, bg, r, kind, need),
            r >= need,
        )
    }

    /** 正文/小字门槛 4.5:1（WCAG AA）。 */
    private fun assertAA(label: String, fg: String, bg: String) =
        assertAt(label, fg, bg, 4.5, " WCAG AA 正文")

    /** 非文字边界门槛 3:1（WCAG 1.4.11）。 */
    private fun assertEdge(label: String, fg: String, bg: String) =
        assertAt(label, fg, bg, 3.0, " 非文字边界")

    private companion object {
        const val COLOR_KT = "app/src/main/java/com/example/zhengdao/ui/theme/Color.kt"
        const val THEME_KT = "app/src/main/java/com/example/zhengdao/ui/theme/Theme.kt"
        const val MAIN_JAVA = "app/src/main/java"
        const val LIGHT = "ZhengdaoLightScheme"
        const val DARK = "ZhengdaoDarkScheme"
        const val TERMINAL_XML = "app/src/main/res/layout/activity_main.xml"
    }

    // ── 一、§2.1 定稿全量表：每一种「文字 × 容器底」组合 ──────────────────

    @Test
    fun `门禁_纸墨_浅色每种文字对每种容器底都达AA`() {
        val paper = composeColor("Paper")
        val card = composeColor("CardSurface")
        val deep = composeColor("PaperDeep")
        val tint = composeColor("QingTint")
        val ink1 = composeColor("Ink1")
        val ink2 = composeColor("Ink2")
        val dai = composeColor("DaiQing")
        val zhu = composeColor("ZhuSha")
        val hu = composeColor("HuPo")

        assertAA("主文本（纸）", ink1, paper)
        assertAA("主文本（白卡）", ink1, card)
        assertAA("次要文本（纸）", ink2, paper)
        assertAA("次要文本（白卡）", ink2, card)
        assertAA("次要文本（纸深）", ink2, deep)
        assertAA("次要文本（浅青）", ink2, tint)
        assertAA("白字 on 黛青（主按钮）", card, dai)
        assertAA("白字 on 朱砂（停止）", card, zhu)
        assertAA("链接字（纸）", dai, paper)
        assertAA("链接字（白卡）", dai, card)
        assertAA("链接字（浅青）", dai, tint)
        assertAA("错误正文（纸）", zhu, paper)
        assertAA("警示字（纸）", hu, paper)
        assertAA("警示字（纸深）", hu, deep)
        assertAA("警示字（白卡）", hu, card)
        assertAA("朱砂 on 朱浅（errorContainer）", zhu, composeColor("ZhuTint"))
    }

    @Test
    fun `门禁_纸墨_非文字边界达三比一`() {
        val ink3 = composeColor("Ink3")
        assertEdge("线灰 on 纸（承担识别功能的边界）", ink3, composeColor("Paper"))
        assertEdge("线灰 on 白卡", ink3, composeColor("CardSurface"))
        val darkInk3 = composeColor("DarkInk3")
        assertEdge("深线灰 on 卡面", darkInk3, composeColor("DarkSurface"))
    }

    @Test
    fun `门禁_纸墨_装饰性发丝线允许低对比`() {
        // 反向钉口径：发丝线只做纯装饰，1.31:1 是**允许**的；谁把它当文字/边界用才是错。
        // 这条断言的用意是防后人"顺手把发丝线也加深到达 AA"，那会把纸面切成一格一格。
        val r = contrast(composeColor("Hairline"), composeColor("Paper"))
        assertTrue("发丝线对纸应保持装饰级低对比（实测 %.2f:1）".format(r), r < 3.0)
    }

    // ── 二、§16.1 浅色 28 槽：每对前景/容器必须达标 ──────────────────────

    @Test
    fun `门禁_纸墨_浅色主题每个槽位配对都达AA`() {
        fun pair(fore: String, back: String, label: String) =
            assertAA(label, slotColor(LIGHT, fore), slotColor(LIGHT, back))

        pair("onPrimary", "primary", "onPrimary / primary")
        pair("onPrimaryContainer", "primaryContainer", "onPrimaryContainer / primaryContainer")
        pair("onSecondary", "secondary", "onSecondary / secondary")
        pair("onSecondaryContainer", "secondaryContainer", "onSecondaryContainer / secondaryContainer")
        pair("onTertiary", "tertiary", "onTertiary / tertiary")
        pair("onTertiaryContainer", "tertiaryContainer", "onTertiaryContainer / tertiaryContainer")
        pair("onError", "error", "onError / error")
        pair("onErrorContainer", "errorContainer", "onErrorContainer / errorContainer")
        pair("onBackground", "background", "onBackground / background")
        pair("onSurface", "surface", "onSurface / surface")
        pair("onSurfaceVariant", "surfaceVariant", "onSurfaceVariant / surfaceVariant（110 处次要文字）")
    }

    @Test
    fun `门禁_纸墨_浅色槽位与设计表逐值一致`() {
        // 不是"看起来差不多"：§16.1 的每一格都钉成字面量，改色必须同步改这里（改这里＝改设计）。
        val expect = mapOf(
            "primary" to "#2C5F54",
            "onPrimary" to "#FFFFFF",
            "primaryContainer" to "#E3EDE7",
            "onPrimaryContainer" to "#2C5F54",
            "secondary" to "#57544C",
            "onSecondary" to "#FFFFFF",
            "secondaryContainer" to "#EDE8DC",
            "onSecondaryContainer" to "#1F1E1B",
            "tertiary" to "#856018",
            "onTertiary" to "#FFFFFF",
            "tertiaryContainer" to "#EDE8DC",
            "onTertiaryContainer" to "#856018",
            "error" to "#A8402B",
            "onError" to "#FFFFFF",
            "errorContainer" to "#F7E5E0",
            "onErrorContainer" to "#A8402B",
            "background" to "#F7F4ED",
            "onBackground" to "#1F1E1B",
            "surface" to "#FFFFFF",
            "onSurface" to "#1F1E1B",
            "surfaceVariant" to "#EDE8DC",
            "onSurfaceVariant" to "#57544C",
            "surfaceContainerLowest" to "#FFFFFF",
            "surfaceContainerLow" to "#F7F4ED",
            "surfaceContainer" to "#FFFFFF",
            "surfaceContainerHigh" to "#EDE8DC",
            "surfaceContainerHighest" to "#EDE8DC",
            "outline" to "#8A857A",
            "outlineVariant" to "#DCD7C9",
        )
        expect.forEach { (slot, hex) ->
            assertTrue("浅色槽位 $slot 应为 $hex，实际 ${slotColor(LIGHT, slot)}", slotColor(LIGHT, slot) == hex)
        }
    }

    // ── 三、§16.2 深色 28 槽 ────────────────────────────────────────────

    @Test
    fun `门禁_纸墨_深色主题每个槽位配对都达AA`() {
        fun pair(fore: String, back: String, label: String) =
            assertAA(label, slotColor(DARK, fore), slotColor(DARK, back))

        pair("onPrimary", "primary", "深 onPrimary / primary")
        pair("onPrimaryContainer", "primaryContainer", "深 onPrimaryContainer / primaryContainer")
        pair("onSecondary", "secondary", "深 onSecondary / secondary")
        pair("onSecondaryContainer", "secondaryContainer", "深 onSecondaryContainer / secondaryContainer")
        pair("onTertiary", "tertiary", "深 onTertiary / tertiary")
        pair("onTertiaryContainer", "tertiaryContainer", "深 onTertiaryContainer / tertiaryContainer")
        pair("onError", "error", "深 onError / error")
        pair("onErrorContainer", "errorContainer", "深 onErrorContainer / errorContainer")
        pair("onBackground", "background", "深 onBackground / background")
        pair("onSurface", "surface", "深 onSurface / surface")
        pair("onSurfaceVariant", "surfaceVariant", "深 onSurfaceVariant / surfaceVariant")
    }

    @Test
    fun `门禁_纸墨_深色槽位与设计表逐值一致`() {
        val expect = mapOf(
            "primary" to "#7FA89B",
            "onPrimary" to "#1C1B18",
            "primaryContainer" to "#2C5F54",
            "onPrimaryContainer" to "#E3EDE7",
            "secondary" to "#A9A399",
            "onSecondary" to "#1C1B18",
            "secondaryContainer" to "#2E2C26",
            "onSecondaryContainer" to "#EDE9DF",
            "tertiary" to "#C4903F",
            "onTertiary" to "#1C1B18",
            "tertiaryContainer" to "#2E2C26",
            "onTertiaryContainer" to "#C4903F",
            "error" to "#D0705A",
            "onError" to "#1C1B18",
            "errorContainer" to "#4A2A22",
            "onErrorContainer" to "#F0BBAE",
            "background" to "#1C1B18",
            "onBackground" to "#EDE9DF",
            "surface" to "#24231F",
            "onSurface" to "#EDE9DF",
            "surfaceVariant" to "#2E2C26",
            "onSurfaceVariant" to "#A9A399",
            "surfaceContainerLowest" to "#1C1B18",
            "surfaceContainerLow" to "#24231F",
            "surfaceContainer" to "#24231F",
            "surfaceContainerHigh" to "#2E2C26",
            "surfaceContainerHighest" to "#2E2C26",
            "outline" to "#807B70",
            "outlineVariant" to "#3A372F",
        )
        expect.forEach { (slot, hex) ->
            assertTrue("深色槽位 $slot 应为 $hex，实际 ${slotColor(DARK, slot)}", slotColor(DARK, slot) == hex)
        }
    }

    @Test
    fun `门禁_纸墨_深色错误正文用浅朱而不是朱提亮块`() {
        // 深色 error(#D0705A) 对卡面只有 4.60:1——刚过线，长段正文不留余量。
        // 语义函数给的是 #F0BBAE（对卡面 9.31:1），这条钉住"别把两者搞混"。
        val theme = repoFile(THEME_KT).readText()
        assertTrue(
            "zhengdaoErrorText() 深色分支必须用 DarkOnZhuTint（浅朱），不许直接用 error 色块",
            theme.contains("isZhengdaoDark()) DarkOnZhuTint else ZhuSha"),
        )
        assertAA("深色错误正文 / 卡面", composeColor("DarkOnZhuTint"), composeColor("DarkSurface"))
        assertAA("深色错误正文 / 墨底", composeColor("DarkOnZhuTint"), composeColor("DarkGround"))
    }

    // ── 四、§15.2 终端内联色（交通灯三件不动，其余四组文字达 AA）────────

    @Test
    fun `门禁_纸墨_终端顶栏与状态条文字达AA`() {
        val bar = layoutAttr(TERMINAL_XML, "toolbar", "background")
        assertAA("终端标题字 / 顶栏底", layoutAttr(TERMINAL_XML, "toolbar_title", "textColor"), bar)
        assertAA("终端「复制」/ 顶栏底", layoutAttr(TERMINAL_XML, "btn_copy_top", "textColor"), bar)
        assertAA("终端「粘贴」/ 顶栏底", layoutAttr(TERMINAL_XML, "btn_paste_top", "textColor"), bar)
        val banner = layoutAttr(TERMINAL_XML, "status_banner", "background")
        assertAA("终端状态条字 / 状态条底", layoutAttr(TERMINAL_XML, "status_banner", "textColor"), banner)
    }

    @Test
    fun `门禁_纸墨_终端顶栏底与按钮字是纸与黛青`() {
        assertTrue(
            "终端顶栏底应为纸 #F7F4ED，实际 ${layoutAttr(TERMINAL_XML, "toolbar", "background")}",
            layoutAttr(TERMINAL_XML, "toolbar", "background") == "#F7F4ED",
        )
        assertTrue(
            "终端按钮字应为黛青 #2C5F54（原 #007AFF 是本批主目标）",
            layoutAttr(TERMINAL_XML, "btn_copy_top", "textColor") == "#2C5F54",
        )
        assertTrue(
            "终端状态条字应为琥珀 #856018（#8F6A1F 在纸深底只有 4.04:1）",
            layoutAttr(TERMINAL_XML, "status_banner", "textColor") == "#856018",
        )
    }

    // ── 五、文本判据：IOS 零残留 / 绿色零残留 ──────────────────────────

    @Test
    fun `门禁_纸墨_主源码不再引用 IOS 色板`() {
        val offenders = mutableListOf<String>()
        val root = File(MAIN_JAVA)
        val base = if (root.isDirectory) root else File("../$MAIN_JAVA")
        base.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                val s = line.trimStart()
                // 注释里允许保留历史说明（"这是从 iOS 色板换过来的"），代码行里一个都不许。
                val isComment = s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")
                if (!isComment && Regex("""\bIOS""").containsMatchIn(line)) {
                    offenders += "${f.path}:${i + 1} => ${line.trim()}"
                }
            }
        }
        assertTrue(
            "以下位置仍在代码里引用 IOS 色板（§15.4：旧名不保留，留别名＝留后门）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁_纸墨_色板里没有绿色通道`() {
        // §2.1「色数收敛」：本语言只有纸/墨/青/朱四类，绿色删除。
        // 就绪＝青、待处理＝琥珀、错误＝朱。常量名里再有 Green 就是通道回来了。
        val kt = repoFile(COLOR_KT).readText()
        val bad = Regex("""val\s+\w*Green\w*\s*=""").findAll(kt).map { it.value }.toList()
        assertTrue("Color.kt 里不许再有绿色常量：$bad", bad.isEmpty())
    }

    @Test
    fun `门禁_对比度门槛不许放宽`() {
        // §2.1 末尾原文：「任何一条不达标＝设计错，不许在测试里放宽判据」。
        // 这句话本身也要能被执行——否则"红了就改门槛"是最省事的作弊方式。
        //
        // ⚠️ 写法演进（负例实测留下的教训）：第一版用 `contains("…4.5…")` 检查本文件，
        // 而那条断言自己的字符串里就有 `4.5` ⇒ 变异函数体会话依然命中，**永远为真**。
        // 现在改成**解析 assertAA / assertEdge 的定义体取默认门槛**，从结构上取值。
        val me = repoFile("app/src/test/java/com/example/zhengdao/ui/theme/ContrastGuardTest.kt").readText()
        fun thresholdOf(funName: String): Double? =
            Regex("""private fun $funName\([^)]*\)\s*=\s*assertAt\([^,]*,[^,]*,[^,]*,\s*([0-9.]+),""")
                .find(me)?.groupValues?.get(1)?.toDoubleOrNull()

        assertTrue("正文门槛必须是 4.5（WCAG AA），实际 ${thresholdOf("assertAA")}", thresholdOf("assertAA") == 4.5)
        assertTrue("非文字边界门槛必须是 3.0（WCAG 1.4.11），实际 ${thresholdOf("assertEdge")}", thresholdOf("assertEdge") == 3.0)
    }

    // ── 六、历史遗留（B-补-25）：终端粘滞键的活性格 ──────────────────────

    @Test
    fun `门禁_B补25_终端粘滞键的活性格在键栏底上达AA`() {
        val active = xmlColor("app/src/main/res/values/colors.xml", "term_key_active")
        // 134 批（Overdraw）：键栏底色从 term_keys.xml 根布局移到 activity_main 的
        // key_bar_container（少画一层），判据跟着取新的承载处。
        val bar = layoutAttr("app/src/main/res/layout/activity_main.xml", "key_bar_container", "background")
        // 真缺陷本体：CTRL/SHIFT 按下后变这个绿色，字就贴在键栏底上。
        assertAA("B-补-25 终端键活性格（键栏底）", active, bar)
        // 顺手钉住白底：真机上键栏可能被主题换成白卡片。
        assertAA("B-补-25 终端键活性格（白底）", active, "#FFFFFF")
    }

    @Test
    fun `门禁_B补25_旧绿值不得回潮`() {
        val active = xmlColor("app/src/main/res/values/colors.xml", "term_key_active")
        assertTrue(
            "B-补-25：#18A018 在键栏底上只有 3.17:1，不能改回来（现值 $active）",
            active != "#18A018",
        )
    }
}
