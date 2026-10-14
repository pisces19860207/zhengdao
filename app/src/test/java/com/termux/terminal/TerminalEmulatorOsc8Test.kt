// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，上游 v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalEmulator`；本测试钉的正是 2026-10-14 第 ② 刀补的 OSC 8 超链接解析
// （台账 §B-12：上游整段丢弃 OSC 8 ⇒ agent 打出的链接只能手抄）与第 ⑨ 刀 R-10 修
// （审计：未闭合的 OSC 8 会把 [起点行, 光标行] 整段 linkify 成同一个 URI）。
package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `TerminalEmulator` OSC 8 超链接解析的单测。
 *
 * 派工单（2026-10-14，第 ② 刀）指定五种输入各一条：有参数 / 无参数 / ST 收尾 /
 * 折行的长 URI / 非法 URI。第 ⑨ 刀（R-10）追加四条：未闭合不产链接 / 忘闭后新开
 * 不落账 / 跨度达回滚容量不登记 / 绕回区间跨行都登记。
 *
 * 零依赖：`TerminalEmulator` 只用到 `android.util.Base64`（本测试的序列不碰它），
 * 会话出口用 [RecordingOutput] 顶掉 ⇒ 纯 JVM 可跑，不需要 Robolectric。
 */
class TerminalEmulatorOsc8Test {

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

    private fun emulator(columns: Int = 80, rows: Int = 24, transcriptRows: Int = 1000): TerminalEmulator =
        TerminalEmulator(RecordingOutput(), columns, rows, 8, 16, transcriptRows, null)

    private fun TerminalEmulator.feed(seq: String) {
        val bytes = seq.toByteArray(Charsets.US_ASCII)
        append(bytes, bytes.size)
    }

    private fun TerminalEmulator.linkAt(row: Int, col: Int): String? =
        getScreen().getLinkAt(row, col)

    // ── ① 有参数（params 非空，BEL 收尾）──

    @Test
    fun `有参数的 OSC8 开闭后 链接区间命中`() {
        val em = emulator()
        // ESC ] 8 ; id=x ; https://example.com/a BEL → 在 (0,0) 开链接
        em.feed("\u001b]8;id=x;https://example.com/a\u0007")
        em.feed("LINK")
        em.feed("\u001b]8;;\u0007") // 闭链接：区间 [0,4) 落在第 0 行
        assertEquals("https://example.com/a", em.linkAt(0, 0))
        assertEquals("https://example.com/a", em.linkAt(0, 3))
        assertNull(em.linkAt(0, 4)) // 区间外不中
        assertNull(em.linkAt(1, 0)) // 别的行不中
    }

    // ── ② 无参数（params 为空，ST 收尾）──

    @Test
    fun `无参数的 OSC8 开闭后 链接区间命中`() {
        val em = emulator()
        em.feed("\u001b]8;;https://example.com/b\u001b\\") // ESC \ = ST
        em.feed("GO")
        em.feed("\u001b]8;;\u001b\\")
        assertEquals("https://example.com/b", em.linkAt(0, 0))
        assertEquals("https://example.com/b", em.linkAt(0, 1))
        assertNull(em.linkAt(0, 2))
    }

    // ── ③ ST 收尾 + 区间边界精确性（闭链接处的光标列即区间终点，不含）──

    @Test
    fun `ST 收尾的 OSC8 区间边界正确`() {
        val em = emulator()
        em.feed("PRE") // 先写 3 个字符，光标到第 3 列
        em.feed("\u001b]8;;https://example.com/c\u001b\\") // 从第 3 列开链接
        em.feed("ABCD") // 区间 [3,7)
        em.feed("\u001b]8;;\u001b\\")
        assertNull(em.linkAt(0, 2)) // 链接前的字符不中
        assertEquals("https://example.com/c", em.linkAt(0, 3))
        assertEquals("https://example.com/c", em.linkAt(0, 6))
        assertNull(em.linkAt(0, 7)) // 闭链接处的列不在区间内
    }

    // ── ④ 折行的长 URI（显示文字超过行宽 ⇒ 区间跨行）──

    @Test
    fun `折行的长 URI 跨行都有链接`() {
        val em = emulator(columns = 20, rows = 5)
        em.feed("\u001b]8;;https://example.com/very/long/path/that/wraps\u001b\\")
        em.feed("X".repeat(35)) // 20 + 15 ⇒ 第 0 行满、第 1 行 15 列
        em.feed("\u001b]8;;\u001b\\")
        // 第 0 行整行 [0,20) 是链接
        assertEquals("https://example.com/very/long/path/that/wraps", em.linkAt(0, 0))
        assertEquals("https://example.com/very/long/path/that/wraps", em.linkAt(0, 19))
        // 第 1 行 [0,15) 也是同一条链接（折行continuation）
        assertEquals("https://example.com/very/long/path/that/wraps", em.linkAt(1, 0))
        assertEquals("https://example.com/very/long/path/that/wraps", em.linkAt(1, 14))
        assertNull(em.linkAt(1, 15))
    }

    // ── ⑤ 非法 URI / 异常序列：不崩、不产链接 ──

    @Test
    fun `非法 OSC8 序列不抛异常且不产链接`() {
        val em = emulator()
        // a) 从未开链接就发关闭序列（空操作）
        em.feed("\u001b]8;;\u001b\\")
        assertNull(em.linkAt(0, 0))
        // b) 只有分号没有 URI（等价于关闭，无链接可落）
        em.feed("\u001b]8;\u001b\\")
        assertNull(em.linkAt(0, 0))
        // c) 开链接后紧接着又开一条（前一条忘闭）——第⑨刀 R-10 起**不再防御性落账**：
        //    旧行为把前一条在当前光标处落成 [0,2)，正是审计判的「未闭合把整段 linkify」病灶；
        //    新行为直接丢弃旧 pending，只有显式闭序列才登记区间。
        em.feed("\u001b]8;;https://example.com/d\u001b\\")
        em.feed("AB")
        em.feed("\u001b]8;;https://example.com/e\u001b\\") // 前一条 d 被丢弃，不落账
        em.feed("CD")
        em.feed("\u001b]8;;\u001b\\") // 后一条 e 落 [2,4)
        assertNull(em.linkAt(0, 0)) // d 不中（忘闭 ⇒ 不登记）
        assertNull(em.linkAt(0, 1))
        assertEquals("https://example.com/e", em.linkAt(0, 2))
        assertEquals("https://example.com/e", em.linkAt(0, 3))
        assertNull(em.linkAt(0, 4))
    }

    // ── ⑥ 第⑨刀 R-10：未闭合的 OSC 8 不得把整段文字变成可点链接 ──

    /** 任务书指定用例：未闭合 OSC 8 的文本，getLinkAt(任意列) 必须为 null。 */
    @Test
    fun `未闭合的 OSC8 不产任何链接`() {
        val em = emulator()
        em.feed("\u001b]8;;https://example.com/unclosed\u001b\\") // 开，从此不再闭
        em.feed("LINK")
        em.feed("\r\n") // 换行继续写，链接一直没闭
        em.feed("MORE TEXT AFTER THE LINK")
        // 任意行、任意列都不中
        for (row in 0 until em.mRows) {
            for (col in 0 until em.mColumns) {
                assertNull("($row,$col) 不该有链接", em.linkAt(row, col))
            }
        }
    }

    /** R-10 主病灶：忘闭后新开一条，旧链接不许被"防御性"落成 [起点, 新开点) 整段区间。 */
    @Test
    fun `忘闭后新开链接 旧链接不落账`() {
        val em = emulator()
        em.feed("\u001b]8;;https://example.com/forgot-to-close\u001b\\")
        em.feed("first line of text")
        em.feed("\r\nsecond line\r\nthird line\r\n") // 旧链接一直没闭
        em.feed("\u001b]8;;https://example.com/properly-closed\u001b\\")
        em.feed("OK")
        em.feed("\u001b]8;;\u001b\\") // 只有这条显式闭合
        for (row in 0 until em.mRows) {
            for (col in 0 until em.mColumns) {
                val hit = em.linkAt(row, col)
                if (hit != null) assertEquals("https://example.com/properly-closed", hit)
            }
        }
        // 显式闭合的那一小段确实中
        assertEquals("https://example.com/properly-closed", em.linkAt(3, 0))
        assertEquals("https://example.com/properly-closed", em.linkAt(3, 1))
        assertNull(em.linkAt(3, 2))
    }

    /** R-10 超长：跨度达到回滚容量 ⇒ 起点行已被卷出回收，整段不登记。 */
    @Test
    fun `跨度达到回滚容量的 OSC8 不登记`() {
        // 回滚设 100 行（构造器下限），屏 5 行 ⇒ 内部索引必然绕回
        val em = emulator(columns = 20, rows = 5, transcriptRows = 100)
        em.feed("\u001b]8;;https://example.com/way-too-long\u001b\\")
        em.feed("\n".repeat(110)) // 卷出 106 行 > 容量 100 ⇒ 起点行已回收
        em.feed("\u001b]8;;\u001b\\") // 此时才闭：跨度 110 ≥ 100 ⇒ 丢弃
        for (row in 0 until em.mRows) {
            for (col in 0 until em.mColumns) {
                assertNull("($row,$col) 不该有链接", em.linkAt(row, col))
            }
        }
    }

    /** R-10 绕回：跨度未超容量但内部索引绕回（起点行号 > 终点行号）时区间仍要正确登记。 */
    @Test
    fun `绕回的 OSC8 区间跨行都登记`() {
        val em = emulator(columns = 20, rows = 5, transcriptRows = 100)
        em.feed("\n".repeat(99)) // 卷 95 行 ⇒ 光标内部行 = (95+4)%100 = 99（贴近绕回点）
        em.feed("\u001b]8;;https://example.com/wrapped\u001b\\") // 起点内部行 99
        em.feed("AB")
        em.feed("\n".repeat(7)) // 再卷 7 行 ⇒ 终点内部行 = (102+4)%100 = 6（已绕回）
        em.feed("\u001b]8;;\u001b\\") // 跨度 7 < 100 ⇒ 登记，且必须覆盖绕回后的行
        // 屏上 5 行 = 内部行 2..6：前 4 行整行是链接，末行 [0,2)
        assertEquals("https://example.com/wrapped", em.linkAt(0, 0))
        assertEquals("https://example.com/wrapped", em.linkAt(0, 19))
        assertEquals("https://example.com/wrapped", em.linkAt(3, 10))
        assertEquals("https://example.com/wrapped", em.linkAt(4, 0))
        assertEquals("https://example.com/wrapped", em.linkAt(4, 1))
        assertNull(em.linkAt(4, 2))
        assertNull(em.linkAt(4, 19))
    }

    /** reset 后打开中的链接态必须清掉（否则 reset 后的关闭序列会标错位置）。 */
    @Test
    fun `reset 清掉打开中的链接态`() {
        val em = emulator()
        em.feed("\u001b]8;;https://example.com/f\u001b\\") // 开链接但不闭
        em.reset()
        em.feed("XY")
        em.feed("\u001b]8;;\u001b\\") // reset 后的关闭序列不应产出任何链接
        assertNull(em.linkAt(0, 0))
        assertNull(em.linkAt(0, 1))
    }
}
