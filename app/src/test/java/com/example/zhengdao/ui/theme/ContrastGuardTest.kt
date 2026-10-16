// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-25（2026-10-16）：对比度守卫 —— **从源码里取值、当场按 WCAG 公式算**，不抄注释里的数字。
 *
 * 台账原文（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * - **B-补-25**（R2 剩余对比度）`#18A018` 白底 3.45:1（`res/values/colors.xml` 的 `term_key_active`）、
 *   `IOSBlueText #0058B0` 只落在 text 语义、选中 chip `#E1ECFF` 上 3.38:1。
 *
 * 复核实测（2026-10-16，本测试的公式与 `#F5F5F7` 键栏底色都取自源码）：
 * - `term_key_active #18A018` 在**键栏底 `#F5F5F7`** 上只有 **3.17:1** ⇒ 真缺陷（已改 `#0F7A0F`，5.06:1）；
 * - 台账说 `#0058B0` 在选中 chip 底上 3.38:1 —— **算错了**：实测 `#E1ECFF` 底 5.84:1、白底 6.95:1，
 *   本来就达 AA ⇒ 本条按「数字纠正」收口，不动色值。
 *
 * 为什么写成测试而不是只改注释：这三个数字过去在 KDoc 里被抄来抄去（`Theme.kt` 还写着 5.1:1），
 * 谁也没复算过。守卫把「小字必须 ≥ 4.5:1（WCAG AA 正文）」钉成可执行判据，配色一改就会红。
 * 本模块没有 UI 测试基建，因此走源码级取值 ＋ 纯函数计算，与 [RouteRestoreGuardTest] 同惯例。
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

    /** 从布局文件里取 `@+id/<viewId>` 那个 View 的 `android:background="#RRGGBB"`。 */
    private fun layoutBackground(layoutRel: String, viewId: String): String {
        val xml = repoFile(layoutRel).readText()
        val start = xml.indexOf("@+id/$viewId")
        assertTrue("$layoutRel 里找不到 @+id/$viewId", start >= 0)
        val tail = xml.substring(start, (start + 1200).coerceAtMost(xml.length))
        val m = Regex("""android:background="#(?:[0-9A-Fa-f]{2})?([0-9A-Fa-f]{6})"""").find(tail)
            ?: throw AssertionError("$viewId 上没有硬编码 android:background（若改成主题色，本测需同步改判据）")
        return "#" + m.groupValues[1].uppercase()
    }

    /** 从 `Color.kt` 取 `val NAME = Color(0xAARRGGBB)`。 */
    private fun composeColor(valName: String): String {
        val kt = repoFile("app/src/main/java/com/example/zhengdao/ui/theme/Color.kt").readText()
        val m = Regex("""val $valName = Color\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\)""").find(kt)
            ?: throw AssertionError("Color.kt 里找不到 val $valName = Color(0xAARRGGBB)")
        return "#" + m.groupValues[1].uppercase()
    }

    /** 从 `Theme.kt` 取浅色 colorScheme 里的某个槽位（取第一次出现，即浅色方案）。 */
    private fun themeSlot(slot: String): String {
        val kt = repoFile("app/src/main/java/com/example/zhengdao/ui/theme/Theme.kt").readText()
        val m = Regex("""$slot = Color\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\)""").find(kt)
            ?: throw AssertionError("Theme.kt 浅色方案里找不到 $slot = Color(0xAARRGGBB)")
        return "#" + m.groupValues[1].uppercase()
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

    private fun assertAA(label: String, fg: String, bg: String) {
        val r = contrast(fg, bg)
        assertTrue(
            "%s：%s 在 %s 上只有 %.2f:1，低于 WCAG AA 正文线 4.5:1".format(label, fg, bg, r),
            r >= 4.5,
        )
    }

    @Test
    fun `门禁_B补25_终端粘滞键的活性格在键栏底上达AA`() {
        val active = xmlColor("app/src/main/res/values/colors.xml", "term_key_active")
        val bar = layoutBackground("app/src/main/res/layout/term_keys.xml", "key_bar")
        // 真缺陷本体：CTRL/SHIFT 按下后变这个绿色，字就贴在键栏底上。
        assertAA("B-补-25 终端键活性格（键栏底）", active, bar)
        // 顺手钉住白底：真机上键栏可能被主题换成白卡片。
        assertAA("B-补-25 终端键活性格（白底）", active, "#FFFFFF")
    }

    @Test
    fun `门禁_B补25_蓝色小字在白底与选中chip底都达AA`() {
        val blueText = composeColor("IOSBlueText")
        val tinted = composeColor("IOSTintedBlue")
        assertAA("蓝色小字（白底）", blueText, "#FFFFFF")
        assertAA("蓝色小字（选中 chip 底）", blueText, tinted)
    }

    @Test
    fun `门禁_B补25_选中chip上的深蓝槽位达AA`() {
        assertAA("onPrimaryContainer（选中 chip 底）", themeSlot("onPrimaryContainer"), composeColor("IOSTintedBlue"))
    }

    @Test
    fun `门禁_B补25_其余浅色小字色都达AA`() {
        assertAA("次要文字 IOSSecondaryLabel（白底）", composeColor("IOSSecondaryLabel"), "#FFFFFF")
        assertAA("就绪绿 IOSReadyGreen（白底）", composeColor("IOSReadyGreen"), "#FFFFFF")
        assertAA("错误正文 IOSErrorText（白底）", composeColor("IOSErrorText"), "#FFFFFF")
    }

    @Test
    fun `门禁_B补25_深色主题的小字色对卡片底都达AA`() {
        val card = composeColor("IOSDarkCard")
        assertAA("深色次要文字（卡片底）", composeColor("IOSDarkSecondaryLabel"), card)
        assertAA("深色蓝色小字（卡片底）", composeColor("IOSDarkBlueText"), card)
        assertAA("深色错误正文（卡片底）", composeColor("IOSDarkErrorText"), card)
        assertAA("深色就绪绿（卡片底）", composeColor("IOSDarkReadyGreen"), card)
        assertAA("深色 chip 上的蓝（卡片底）", composeColor("IOSDarkOnTintedBlue"), card)
        assertAA("深色 chip 上的绿（卡片底）", composeColor("IOSDarkOnTintedGreen"), card)
        assertAA("深色 chip 上的红（卡片底）", composeColor("IOSDarkOnTintedRed"), card)
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
