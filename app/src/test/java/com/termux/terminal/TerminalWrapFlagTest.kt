// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalEmulator` / `TerminalRow`；本测试钉的正是 2026-10-12 那两处补丁
// （§B-补-49 🟡6 行复用不清换行标志、🟡7 IL 漏清自动换行待写标志）。
package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换行标志（`TerminalRow.mLineWrap`）与自动换行待写标志（`TerminalEmulator.mAboutToAutoWrap`）
 * 的单测。
 *
 * 为什么必须用单测钉住：这两条都是**静默**缺陷 —— 不崩不报错，只是
 * ① 复制出来的文本「两行粘成一行」/「凭空多一行空格」，或
 * ② 某条控制序列之后敲字符会**凭空跳行**。真机手测既要撞对序列、又要盯住字符位置，
 * 很容易「看着没错」；这两条在 JVM 上几行就能稳定复现。
 *
 * 零依赖：只用到 `TerminalEmulator` 的纯逻辑路径（本文件的序列不碰 `android.util.Base64`），
 * 会话出口用 [RecordingOutput] 顶掉 ⇒ 纯 JVM 可跑，不需要 Robolectric。
 */
class TerminalWrapFlagTest {

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

    // ── 🟡6：行复用必须清掉 mLineWrap（否则复制内容被静默篡改）──

    @Test
    fun `clear 必须把换行标志一起复位`() {
        // 直接钉住被改的那一处：行复用（滚动 / IL / DL）都走 TerminalRow.clear()，
        // 上一行留下的 mLineWrap 必须在这里归零，否则复用后的行会被
        // TerminalBuffer.getSelectedText() 当成"折行接续"⇒ 两行粘成一行。
        val row = TerminalRow(10, 0L)
        row.mLineWrap = true
        row.clear(0L)
        assertFalse("clear() 之后 mLineWrap 必须为 false", row.mLineWrap)
    }

    @Test
    fun `滚动本身不该被误解为复用 已折行的行仍带标志`() {
        val em = emulator(columns = 5, rows = 2)
        em.feed("AAAAA")
        em.feed("BBBBB")
        assertTrue("打满并被折走的第 0 行应当带换行标志", em.screen.getLineWrap(0))
        em.feed("C")
        assertTrue("滚动搬上来的那一行保留它自己的标志", em.screen.getLineWrap(0))
    }

    // ── 🟡7：IL（CSI n L）必须清 mAboutToAutoWrap（否则打满一行后 IL 再敲字符会跳行）──

    @Test
    fun `打满一行后插入行 再敲字符 不该凭空跳行`() {
        val em = emulator(columns = 10, rows = 3)
        em.feed("0123456789")           // 打满一行：光标停在第 9 列，自动换行标志置位
        assertEquals(9, em.cursorCol)
        assertEquals(0, em.cursorRow)
        em.feed("\u001b[2L")            // IL 插入 2 行（兄弟分支 EL/ICH/DL/DCH 都清标志，旧代码这里漏）
        em.feed("x")                    // 旧代码：标志还在 ⇒ 折到下一行；修复后：原地覆盖第 9 列
        assertEquals("IL 之后敲字符不该跳到下一行", 0, em.cursorRow)
        assertEquals(9, em.cursorCol)
    }

    @Test
    fun `没有 IL 时 打满一行再敲字符仍然照常折行`() {
        // 反向用例：确认上面那条不是因为"折行本身坏了"才通过
        val em = emulator(columns = 10, rows = 3)
        em.feed("0123456789")
        em.feed("x")
        assertEquals("打满一行后继续输出应当折到下一行", 1, em.cursorRow)
    }
}
