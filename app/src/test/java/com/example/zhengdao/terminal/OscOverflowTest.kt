package com.example.zhengdao.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 🟡-7 的回归用例：OSC 载荷超过上限后，上游 v0.119 走 `unknownSequence()` → `finishSequence()`，
 * 于是余下载荷被当普通正文打印到屏幕上（还可能拿截断的前缀去解参数）。
 *
 * 证道的本地补丁改为「整条作废，但继续吃到真正的终结符（BEL / ST）」，并给"丢弃"本身加了绝对上限
 * （`TerminalEmulator.MAX_OSC_DISCARD_LENGTH`）：既没有终结符、又超过上限时才放弃这条序列。
 * 补丁与依据写在 `docs/证道-P0渲染层测量-2026-10-10.md` §3「P1-d」。
 */
class OscOverflowTest {

    private class CapturingOutput : TerminalOutput() {
        var title: String? = null
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {
            title = newTitle
        }

        override fun onCopyTextToClipboard(text: String) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private fun newEmulator(output: TerminalOutput = CapturingOutput()): TerminalEmulator =
        TerminalEmulator(output, 80, 24, 1, 1, 100, null)

    private fun feed(emulator: TerminalEmulator, sequence: String) {
        for (ch in sequence) {
            emulator.processCodePoint(ch.code)
        }
    }

    private fun screenText(emulator: TerminalEmulator): String =
        emulator.screen.getSelectedText(0, 0, 79, 23)

    /** 读取私有累积缓冲 mOSCOrDeviceControlArgs 的长度（序列终结后不会被清空）。 */
    private fun oscBufferLength(emulator: TerminalEmulator): Int {
        val field = TerminalEmulator::class.java.getDeclaredField("mOSCOrDeviceControlArgs")
        field.isAccessible = true
        return (field.get(emulator) as StringBuilder).length
    }

    @Test
    fun oversizedOscPayloadIsDiscardedInsteadOfPrinted() {
        val emulator = newEmulator()
        feed(emulator, "\u001b]0;" + "A".repeat(20_000) + "\u0007")

        val screen = screenText(emulator)
        assertFalse("超限 OSC 的余下载荷不该被当正文打印，实际屏幕=$screen", screen.contains("A"))
        assertTrue(
            "累积缓冲仍应停在上限内，实际 ${oscBufferLength(emulator)}",
            oscBufferLength(emulator) <= 8192,
        )
    }

    @Test
    fun emulatorKeepsWorkingAfterAnOversizedOsc() {
        val emulator = newEmulator()
        feed(emulator, "\u001b]0;" + "A".repeat(20_000) + "\u0007")
        feed(emulator, "hello")

        assertTrue("超限序列按终结符结束后，终端应正常回显", screenText(emulator).contains("hello"))
    }

    @Test
    fun normalOscStillSetsTheTitle() {
        val output = CapturingOutput()
        val emulator = newEmulator(output)
        feed(emulator, "\u001b]0;标题\u0007")

        assertEquals("标题", output.title)
    }

    @Test
    fun oversizedOscWithoutTerminatorGivesUpAfterTheAbsoluteLimit() {
        val emulator = newEmulator()
        // 超限之后既不给终结符也不停：吃掉绝对上限（1 MiB）之后应放弃这条序列，后续正文照常显示。
        feed(emulator, "\u001b]0;" + "A".repeat(9_000) + "B".repeat(1024 * 1024 + 16))
        feed(emulator, "after")

        assertTrue("放弃这条序列后，正文应照常显示", screenText(emulator).contains("after"))
    }
}
