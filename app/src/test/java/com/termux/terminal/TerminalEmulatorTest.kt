// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalEmulator`；本测试钉的正是 2026-10-12 那处补丁（🔴1 光标列越界崩进程）。
package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TerminalEmulator` 光标列边界的单测。
 *
 * 为什么必须用单测钉住（而不是真机手测）：这条缺陷的**触发路径极窄**
 * （先开左右边距模式、再设边距、再退格、再插入字符），但后果是
 * "站内异常从 pty 读线程冒顶 ⇒ 整页 FATAL"。真机手测很容易"碰巧没碰到"，
 * 而这里四行字符串就能稳定复现。
 *
 * 零依赖：`TerminalEmulator` 只用到 `android.util.Base64`（本测试的序列不碰它），
 * 会话出口用 [RecordingOutput] 顶掉 ⇒ 纯 JVM 可跑，不需要 Robolectric。
 */
class TerminalEmulatorTest {

    /** 最小终端出口：把写回 guest 的内容记下来，其余回调空实现。 */
    private class RecordingOutput : TerminalOutput() {
        val written = StringBuilder()
        override fun write(data: ByteArray, offset: Int, count: Int) {
            written.append(String(data, offset, count, Charsets.UTF_8))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    /** `client` 传 null 是安全的：本文件用到的路径里只有 `setCursorStyle()` 读它，而它自带判空。 */
    private fun emulator(columns: Int = 80, rows: Int = 24): TerminalEmulator =
        TerminalEmulator(RecordingOutput(), columns, rows, 8, 16, 1000, null)

    private fun TerminalEmulator.feed(seq: String) {
        val bytes = seq.toByteArray(Charsets.US_ASCII)
        append(bytes, bytes.size)
    }

    // ── 🔴1：DECSLRM + BS 把光标列写成 -1 → ICH 撞 TerminalBuffer 断言 → 崩进程 ──

    @Test
    fun `开边距后立刻退格 再插入字符 不该抛异常`() {
        val em = emulator()
        // 逐步拆开这条复现串（旧代码在第二步就把光标列写成了 -1）：
        //   ESC[?69h  —— 开左右边距模式（DECLRMM）
        //   ESC[10;70s —— 设左边距 10、右边距 70（零基 9 / 70），并把光标送到 (0,0)。
        //                注意这一步之后光标在第 0 列，却在左边距左侧 —— 上游没防的状态。
        //   \b        —— 退格（BS）：旧判据 `mLeftMargin == mCursorCol` 不成立 ⇒ setCursorCol(-1)
        //   ESC[@     —— ICH 插入字符，会拿 mCursorCol 去 blockCopy ⇒ 负列 ⇒ IllegalArgumentException
        em.feed("\u001b[?69h\u001b[10;70s\b\u001b[@")
        assertEquals(0, em.cursorCol)
        assertEquals(0, em.cursorRow)
    }

    @Test
    fun `退格不会把光标推到左边距左侧`() {
        val em = emulator()
        em.feed("\u001b[?69h\u001b[10;70s\r")
        assertEquals("CR 之后光标应停在左边距上", 9, em.cursorCol)
        em.feed("\b".repeat(20))
        assertEquals("左边距之外怎么退都不动", 9, em.cursorCol)
        assertEquals("退格不该换行", 0, em.cursorRow)
    }

    @Test
    fun `没有水平边距时 退格退到第 0 列就停住`() {
        val em = emulator()
        em.feed("abc")
        assertEquals(3, em.cursorCol)
        em.feed("\b".repeat(10))
        assertEquals(0, em.cursorCol)
    }

    @Test
    fun `一批定位序列之后 光标列始终落在缓冲区范围内`() {
        val columns = 40
        val em = emulator(columns = columns, rows = 10)
        em.feed("\u001b[?69h\u001b[5;30s")
        val sequences = listOf(
            "\b",                    // BS：本次修的入口
            "\u001b[0G",             // CHA（列号钳到 1 ⇒ 第 0 列）
            "\u001b[999G",           // CHA 越右界 ⇒ 应是最后一列
            "\u001b[5D",             // CUB
            "\u001b[1;1H",           // CUP
            "\u001b[?6h\u001b[1;1H", // 开 origin mode 再 CUP（此时受左边距约束）
            "\u001b[?6l\r",          // 关 origin mode 再 CR
            "\b\u001b[@",            // 复现串的最小版本
        )
        for (seq in sequences) {
            em.feed(seq)
            assertTrue(
                "列越界：${em.cursorCol}（序列 \u001b → ESC：${seq.replace("\u001b", "ESC")}）",
                em.cursorCol in 0 until columns
            )
        }
    }
}
