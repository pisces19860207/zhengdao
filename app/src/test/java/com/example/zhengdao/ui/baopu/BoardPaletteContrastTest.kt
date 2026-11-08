// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十二批（2026-11-08）· 小黑板板面色的**门禁**（A 案：深绿底 #1F3B2F / 白字 #F2F1E6）。
//
// 与 `ContrastGuardTest` 同一房型、同一把尺：**取值一律从源码读**，对比度**当场按 WCAG 相对亮度
// 重算**，本文件不写"已验"结论（色值一改，这里就重新算）。
//
// 三条判据：
//   ① 三档色值必须逐字等于 A 案（`FF1F3B2F` / `FFF2F1E6` / `FFC9C9BE`）——用户原话钉的值，
//      谁把它调成"更好看的绿"都要在这里交代；
//   ② 板面上的两档字都达 WCAG AA（4.5:1，正文与小字同门槛：13sp 的小字也是字）；
//   ③ 门禁不是空转：同一套算式喂一对"已知不达标的"色（次墨 #57544C 落在板面上）必须算到
//      < 4.5 —— 否则"全部达标"这句结论可能来自一个恒真的函数（本仓 428 N2 那一课的同类风险）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPaletteContrastTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("app/$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }

    private fun paletteSource(): String =
        repoFile("src/main/java/com/example/zhengdao/ui/baopu/BoardPalette.kt").readText()

    /** 从源码取 `val NAME: Color = Color(0xAARRGGBB)` 的那一段 hex（去掉 `0x`，含 alpha）。 */
    private fun hexOf(token: String): String {
        val m = Regex("""val\s+$token\s*:\s*Color\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
            .find(paletteSource())
            ?: throw AssertionError("BoardPalette.kt 里找不到 val $token: Color = Color(0xAARRGGBB)")
        return m.groupValues[1].uppercase()
    }

    /** 只取 RGB 三位（对比度算法不看 alpha；板面三档全是不透明色）。 */
    private fun rgbOf(token: String): String = hexOf(token).takeLast(6)

    // ── WCAG 相对亮度（与 ContrastGuardTest 同源：sRGB 反伽马 → 加权和）──────────

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    private fun luminance(hex: String): Double {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun contrast(fg: String, bg: String): Double {
        val a = luminance(fg)
        val b = luminance(bg)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** AA 正文门槛（WCAG 1.4.3）。 */
    private val aa = 4.5

    // ── ① 色值就是 A 案那几个 ─────────────────────────────────────────────────

    @Test
    fun `三档色值逐字等于A案`() {
        assertEquals("板面底必须是 A 案的 #1F3B2F", "1F3B2F", rgbOf("ground"))
        assertEquals("板面字必须是 A 案的 #F2F1E6", "F2F1E6", rgbOf("chalk"))
        assertEquals("板面小字必须是 A 案的 #C9C9BE", "C9C9BE", rgbOf("chalkDim"))
        // 三档都必须不透明：半透明的板子会把底下的纸色透上来，A 案就不是这个绿了
        listOf("ground", "chalk", "chalkDim").forEach {
            assertEquals("$it 必须是不透明色（AA＝FF）", "FF", hexOf(it).take(2))
        }
    }

    // ── ② 两档字都达 AA ─────────────────────────────────────────────────────

    @Test
    fun `板面正文与A案底色达AA`() {
        val c = contrast(rgbOf("chalk"), rgbOf("ground"))
        assertTrue("板面正文 / 板面底 只有 %.2f:1，低于 AA 4.5:1".format(c), c >= aa)
    }

    @Test
    fun `板面小字与A案底色达AA`() {
        val c = contrast(rgbOf("chalkDim"), rgbOf("ground"))
        assertTrue("板面小字 / 板面底 只有 %.2f:1，低于 AA 4.5:1".format(c), c >= aa)
    }

    // ── ③ 负例：这套算式不会把不达标的色算成达标 ──────────────────────────────

    @Test
    fun `负例_次墨落在板面上不达AA_证明这把尺不是恒真`() {
        val c = contrast("57544C", rgbOf("ground"))
        assertTrue("次墨 #57544C 在板面上应当明显不够（实测 %.2f:1）——若这里也 ≥4.5，说明算式坏了".format(c), c < aa)
    }
}
