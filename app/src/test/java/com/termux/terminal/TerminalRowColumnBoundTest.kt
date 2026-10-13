// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，上游 v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalRow`；本测试钉的正是 2026-10-14 第 ③ 刀补的 🟡9（列号越界读）。
package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `TerminalRow.findStartOfColumn` 列号边界的单测（台账 §B-补-49 🟡9）。
 *
 * 缺陷链路（蓝总管核到行）：唯一安全出口只拦 `column == mColumns`，而
 * `TerminalBuffer.getSelectedText()` 的 `x2Index == x1Index` 分支传 `x2 + 1`
 （x2 已被夹到 mColumns）⇒ 越界列号进主循环，扩容区 `\0` 的 wcwidth=0 让列号
 * 不推进，读到 mText 数组尾 ⇒ ArrayIndexOutOfBoundsException 从主线程 Handler 冒顶。
 * 零依赖：纯 Java 逻辑，JVM 可跑。
 */
class TerminalRowColumnBoundTest {

    @Test
    fun `列号等于列数 返回行尾`() {
        val row = TerminalRow(10, TextStyle.NORMAL)
        assertEquals(row.spaceUsed, row.findStartOfColumn(10))
    }

    @Test
    fun `列号超过列数 不抛异常且返回行尾`() {
        val row = TerminalRow(10, TextStyle.NORMAL)
        // 回归：旧代码 `== mColumns` 拦不住 mColumns+1（调用方真实可达）
        assertEquals(row.spaceUsed, row.findStartOfColumn(11))
        assertEquals(row.spaceUsed, row.findStartOfColumn(50))
    }

    @Test
    fun `正常列号仍精确`() {
        val row = TerminalRow(10, TextStyle.NORMAL)
        row.setChar(0, 'a'.code, TextStyle.NORMAL)
        row.setChar(1, 'b'.code, TextStyle.NORMAL)
        assertEquals(0, row.findStartOfColumn(0))
        assertEquals(1, row.findStartOfColumn(1))
    }
}
