// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import com.termux.terminal.WcWidth
import com.termux.view.textselection.DisplayColumnIndex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「列 → 合法游标列」判据的单测（B-补-47②，2026-10-11）。
 *
 * 背景：这个判据原先内联在 `TextSelectionCursorController.getValidCurX()` 里，与
 * `TerminalRow#findStartOfColumn` 的整行 walker 是**两份实现**，必须同源同判据；
 * 抽成第一方类 `DisplayColumnIndex` 之后，用它把语义钉死：
 * 边界列原样、宽字符内部贴右侧、零宽字符只认"确实停在其起始列"、越界原样返回。
 *
 * 其中最后一条是**修掉**旧内联实现的毛病：旧代码碰到零宽字符就无条件 `return col`，
 * 于是行内只要有组合字符/ZWJ/变体选择符，手柄列就会被夹回去（用例里显式钉住新取值）。
 */
class DisplayColumnIndexTest {

    @Test
    fun `半宽字符：边界列原样返回，越界列也原样`() {
        val line = "abc"
        assertEquals(0, DisplayColumnIndex.validCursorColumn(line, 0))
        assertEquals(1, DisplayColumnIndex.validCursorColumn(line, 1))
        assertEquals(3, DisplayColumnIndex.validCursorColumn(line, 3))
        assertEquals(9, DisplayColumnIndex.validCursorColumn(line, 9))
    }

    @Test
    fun `宽字符：起始列停住，内部列贴到右侧`() {
        val line = "中" // 占 0..2 两列
        assertEquals(0, DisplayColumnIndex.validCursorColumn(line, 0))
        assertEquals(2, DisplayColumnIndex.validCursorColumn(line, 1))
        assertEquals(2, DisplayColumnIndex.validCursorColumn(line, 2))
    }

    @Test
    fun `混排中a文b：每个位置都对得上`() {
        val line = "中a文b" // 中 0..2 / a 2..3 / 文 3..5 / b 5..6
        assertEquals(0, DisplayColumnIndex.validCursorColumn(line, 0))
        assertEquals(2, DisplayColumnIndex.validCursorColumn(line, 1)) // 中内部
        assertEquals(2, DisplayColumnIndex.validCursorColumn(line, 2)) // a 起始
        assertEquals(3, DisplayColumnIndex.validCursorColumn(line, 3)) // 文 起始
        assertEquals(5, DisplayColumnIndex.validCursorColumn(line, 4)) // 文内部
        assertEquals(5, DisplayColumnIndex.validCursorColumn(line, 5)) // b 起始
        assertEquals(6, DisplayColumnIndex.validCursorColumn(line, 6)) // 行尾
    }

    @Test
    fun `零宽组合字符：停在其起始列，但不再把后面的列夹回去`() {
        val line = "a\u0301b" // a + 组合尖音符（零宽）+ b：a 占 0..1，b 占 1..2
        assertEquals(0, DisplayColumnIndex.validCursorColumn(line, 0))
        assertEquals(1, DisplayColumnIndex.validCursorColumn(line, 1)) // 组合字符所在列 ⇒ 起始列 1
        // 旧内联实现到这里会被夹回 1（碰到零宽字符就 return col）；现在是修正后的取值：
        assertEquals(2, DisplayColumnIndex.validCursorColumn(line, 2))
    }

    @Test
    fun `零宽字符开头的行：不会把任何列都算成第 0 列`() {
        val line = "\u200db" // ZWJ（零宽）+ b
        // 旧实现：碰到 ZWJ 无条件 return 0 ⇒ 第 1 列也被算成 0。
        assertEquals(0, DisplayColumnIndex.validCursorColumn(line, 0))
        assertEquals(1, DisplayColumnIndex.validCursorColumn(line, 1))
    }

    @Test
    fun `代理对（emoji）：内部列贴到它右侧`() {
        val line = "\uD83D\uDE00" // 😀，宽度由 WcWidth 给（当前 Unicode 表里是 2）
        val width = WcWidth.width(0x1F600)
        assertEquals(0, DisplayColumnIndex.validCursorColumn(line, 0))
        if (width > 1) {
            assertEquals(width, DisplayColumnIndex.validCursorColumn(line, 1))
        }
        assertEquals(width, DisplayColumnIndex.validCursorColumn(line, width))
    }

    @Test
    fun `空行与 null：原样返回，不抛异常`() {
        assertEquals(4, DisplayColumnIndex.validCursorColumn("", 4))
        assertEquals(4, DisplayColumnIndex.validCursorColumn(null, 4))
    }

    @Test
    fun `行内 NUL：按上游语义在此截断，列原样返回`() {
        val line = "a\u0000中"
        assertEquals(1, DisplayColumnIndex.validCursorColumn(line, 1))
        assertEquals(3, DisplayColumnIndex.validCursorColumn(line, 3)) // NUL 之后不再参与换算
    }
}
