// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，上游 v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalEmulator`；本测试钉的正是 2026-10-14 第 ③ 刀补的 🟡8（DCS 超限）。
package com.termux.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TerminalEmulator` DCS 载荷超限处理的单测（台账 §B-补-49 🟡8）。
 *
 * 缺陷链路（蓝总管核到行；台账原记 :1059-1067 已漂移，现行号 = doDeviceControl 的
 * default 分支，收集阶段）：上游超限时 setLength(0) + finishSequence ⇒ 后续载荷
 * 字节走 ESC_NONE 被当正文打印，载荷里的 ESC[ / ESC] 被当真转义序列执行。
 * OSC 侧 2026-10-10 已有等价补丁（mOscArgsDropped），DCS 一直没覆盖。
 *
 * 零依赖：TerminalEmulator 只用到 android.util.Base64（本测试序列不碰它），
 * 会话出口用 [RecordingOutput] 顶掉 ⇒ 纯 JVM 可跑。
 */
class TerminalDcsOverflowTest {

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

    private fun emulator(columns: Int = 80, rows: Int = 24): TerminalEmulator =
        TerminalEmulator(RecordingOutput(), columns, rows, 8, 16, 1000, null)

    private fun TerminalEmulator.feed(seq: String) {
        val bytes = seq.toByteArray(Charsets.US_ASCII)
        append(bytes, bytes.size)
    }

    @Test
    fun `DCS 超限 余下载荷不泄漏到屏幕`() {
        val em = emulator()
        // ESC P + 9000 字节载荷（超 MAX_OSC_STRING_LENGTH=8192）+ LEAK + ST。
        // 旧代码在收集到第 8193 字节时就 setLength(0)+finishSequence ⇒ LEAK 被当正文打印。
        em.feed("\u001bP" + "A".repeat(9000) + "LEAK" + "\u001b\\")
        em.feed("AFTER") // 终结符之后的正常输入
        val text = em.getScreen().getTranscriptText()
        assertFalse("超限后的载荷不该被当正文打印", text.contains("LEAK"))
        assertFalse("整条作废——超限前的载荷也不该出现", text.contains("AAAA"))
        assertTrue("终结符之后的正常输入不受影响", text.contains("AFTER"))
    }

    @Test
    fun `短 DCS 不泄漏`() {
        val em = emulator()
        em.feed("\u001bP" + "A".repeat(100) + "LEAK" + "\u001b\\")
        em.feed("AFTER")
        val text = em.getScreen().getTranscriptText()
        assertFalse("未超限的载荷同样不该被当正文打印", text.contains("LEAK"))
        assertFalse("未超限的载荷同样不该出现", text.contains("AAAA"))
    }

    @Test
    fun `正常长度 DCS 行为不变`() {
        val em = emulator()
        // DECRQSS 形态的短 DCS：\$q"p —— 不该被误伤，响应照常写回
        em.feed("\u001bP\$q\"p\u001b\\")
        em.feed("OK")
        assertTrue(em.getScreen().getTranscriptText().contains("OK"))
    }
}
