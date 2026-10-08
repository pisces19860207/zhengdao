package com.example.zhengdao.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OSC 52（剪贴板）载荷是 base64，动辄远超默认的 8 KiB 累积上限。上游 d8d6b02 把 OSC 52 的上限提到
 * 100 KiB + 10，本仓库没有那次解析器重构，只 inline 了一个长度检查（死代码），>8 KiB 的载荷仍被整条
 * 丢弃。这里用反射直接看累积缓冲：52 号 OSC 必须能越过 8 KiB，其它 OSC 号仍按下限截断。
 */
class Osc52LimitTest {

    private class CapturingOutput : TerminalOutput() {
        var clipboard: String? = null
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String, newTitle: String) {}
        override fun onCopyTextToClipboard(text: String) {
            clipboard = text
        }
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private fun newEmulator(): TerminalEmulator =
        TerminalEmulator(CapturingOutput(), 80, 24, 1, 1, 100, null)

    private fun feed(emulator: TerminalEmulator, sequence: String) {
        for (ch in sequence) {
            emulator.processCodePoint(ch.code)
        }
    }

    /** 读取私有累积缓冲 mOSCOrDeviceControlArgs 的长度（序列未终结时不会被清空）。 */
    private fun oscBufferLength(emulator: TerminalEmulator): Int {
        val field = TerminalEmulator::class.java.getDeclaredField("mOSCOrDeviceControlArgs")
        field.isAccessible = true
        return (field.get(emulator) as StringBuilder).length
    }

    @Test
    fun osc52AccumulatesPastTheDefaultEightKibLimit() {
        val emulator = newEmulator()
        val payload = "A".repeat(20_000)
        // 只喂到载荷结束、不喂终结符（BEL/ST），这样累积缓冲还留在 emulator 里可供检查。
        feed(emulator, "\u001b]52;c;$payload")

        assertEquals(
            "OSC 52 的累积缓冲应能装下 \"52;c;\" + 20000 字节载荷",
            "52;c;".length + payload.length,
            oscBufferLength(emulator),
        )
    }

    @Test
    fun otherOscNumbersKeepTheDefaultLimit() {
        val emulator = newEmulator()
        feed(emulator, "\u001b]0;" + "A".repeat(20_000))

        assertTrue(
            "非 52 号 OSC 仍应在 8192 处截断，实际 ${oscBufferLength(emulator)}",
            oscBufferLength(emulator) <= 8192,
        )
    }
}
