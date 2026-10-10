package com.example.zhengdao.terminal

import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalColors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catppuccin Mocha 配色回归用例（2026-10-10）。
 *
 * 锁三件事：
 * 1. 色值表与规格逐色一致——包括「Bright 与 Normal 多数同色」这条**设计特点**，
 *    免得后来者看到重复值以为是复制粘贴错误而「顺手修正」；
 * 2. 出厂默认是 catppuccin_mocha，认不出的 id 也回落到它；
 * 3. 换配色时 16 个 ANSI 色会跟着切换：Catppuccin 写进去的颜色，切回经典必须还原成
 *    出厂 xterm 色。调色板是**全局静态单例**，`writePalette()` 开头那句 reset 就是防串色。
 */
class CatppuccinPaletteTest {

    private val mocha = TerminalPrefs.Scheme.CATPPUCCIN_MOCHA

    /** 规格表：0–7 normal、8–15 bright（用户 2026-10-10 给定）。 */
    private val expectedAnsi = intArrayOf(
        0xFF45475A.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
        0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFBAC2DE.toInt(),
        0xFF585B70.toInt(), 0xFFF38BA8.toInt(), 0xFFA6E3A1.toInt(), 0xFFF9E2AF.toInt(),
        0xFF89B4FA.toInt(), 0xFFF5C2E7.toInt(), 0xFF94E2D5.toInt(), 0xFFA6ADC8.toInt(),
    )

    @Test
    fun `16 个 ANSI 色与规格逐色一致`() {
        val ansi = mocha.ansi
        assertTrue("Catppuccin Mocha 必须自带 16 个 ANSI 色", ansi != null)
        assertEquals(16, ansi!!.size)
        assertArrayEquals(expectedAnsi, ansi)
    }

    @Test
    fun `basic colors match spec`() {
        assertEquals(0xFF1E1E2E.toInt(), mocha.bg)
        assertEquals(0xFFCDD6F4.toInt(), mocha.fg)
        assertEquals(0xFFF5E0DC.toInt(), mocha.cursor)
        assertEquals(0xFF585B70.toInt(), mocha.selection)
    }

    @Test
    fun `bright and normal share most colors by design`() {
        // 6 对同色（red/green/yellow/blue/magenta/cyan），只有 black/white 分深浅。
        val samePairs = (0 until 8).count { mocha.ansi!![it] == mocha.ansi!![it + 8] }
        assertEquals("Catppuccin 的设计就只有 black/white 两档分深浅", 6, samePairs)
    }

    @Test
    fun `default scheme is catppuccin mocha`() {
        assertEquals("catppuccin_mocha", TerminalPrefs.Scheme.default().id)
        assertEquals(mocha, TerminalPrefs.Scheme.default())
    }

    @Test
    fun `unknown scheme id falls back to default`() {
        assertEquals(mocha, TerminalPrefs.Scheme.of("no_such_scheme"))
        assertEquals(mocha, TerminalPrefs.Scheme.of(null))
        // 老用户存过的 id 仍然认得出（不能被默认值改掉）
        assertEquals(TerminalPrefs.Scheme.CLASSIC, TerminalPrefs.Scheme.of("classic"))
    }

    @Test
    fun `only catppuccin defines a selection color`() {
        TerminalPrefs.Scheme.values().forEach { scheme ->
            if (scheme == mocha) assertEquals(0xFF585B70.toInt(), scheme.selection)
            else assertEquals("${scheme.id} 应保持反色选区（老行为）", 0, scheme.selection)
        }
    }

    @Test
    fun `writing palette puts ansi and base colors in place`() {
        TerminalPrefs.writePalette(mocha)
        val palette = TerminalColors.COLOR_SCHEME.mDefaultColors
        assertArrayEquals(expectedAnsi, palette.copyOfRange(0, 16))
        assertEquals(mocha.bg, palette[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(mocha.fg, palette[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(mocha.cursor, palette[TextStyle.COLOR_INDEX_CURSOR])
        assertEquals(mocha.selection, palette[TextStyle.COLOR_INDEX_SELECTION])
    }

    @Test
    fun `switching back to classic restores factory ansi colors`() {
        val factory = TerminalColorScheme().mDefaultColors.copyOf()
        TerminalPrefs.writePalette(TerminalPrefs.Scheme.CLASSIC)
        val palette = TerminalColors.COLOR_SCHEME.mDefaultColors
        assertArrayEquals(
            "切回经典后 16 个 ANSI 色不能残留 Catppuccin 的值",
            factory.copyOfRange(0, 16),
            palette.copyOfRange(0, 16),
        )
        assertEquals(0, palette[TextStyle.COLOR_INDEX_SELECTION])
    }

    @Test
    fun `scheme list still exposes all previous schemes`() {
        val ids = TerminalPrefs.Scheme.values().map { it.id }
        assertTrue(ids.containsAll(listOf("classic", "amber", "green", "paper", "catppuccin_mocha")))
    }
}
