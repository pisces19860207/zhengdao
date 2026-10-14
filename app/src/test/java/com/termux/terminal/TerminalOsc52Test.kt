// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-emulator
// （Apache-2.0，上游 v0.119.0-beta.3，聚合分发见 PROVENANCE.md）经本项目打过补丁的
// `TerminalEmulator`；本测试钉的正是 2026-10-14 第 ③ 刀补的 🟢2（OSC 52 死判据移除后
// 防护仍由 collectOSCArgs 承担）。
package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OSC 52（剪贴板写入）超长防护的单测（台账 §B-补-49 🟢2）。
 *
 * 背景：doOscSetTextParameters 的 case 52 里原有「textParameter.length() > 100KB+10」
 * 判断是**死判据**（collectOSCArgs 已在收集层把整条夹在 MAX_OSC52_STRING_LENGTH 内），
 * 2026-10-14 删除。本测试钉住：**删除判断后防护仍然有效**——超长 OSC 52 被整条丢弃、
 * 绝不把超长 base64 送进解码/剪贴板。
 *
 * 零依赖：JVM 可跑（RecordingOutput 记录剪贴板回调）。
 * 注意：**只测"超长整条丢弃"这条路径**——它不碰 `android.util.Base64`（JVM 无实现，
 * 同 TerminalEmulatorTest 的规避说明）；正常长度解码路径依赖 Android Base64，
 * 由真机走查覆盖。
 */
class TerminalOsc52Test {

    private class RecordingOutput : TerminalOutput() {
        val written = StringBuilder()
        var lastClipboard: String? = null
        override fun write(data: ByteArray, offset: Int, count: Int) {
            written.append(String(data, offset, count, Charsets.UTF_8))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) { lastClipboard = text }
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator(out: RecordingOutput, columns: Int = 80, rows: Int = 24): TerminalEmulator =
        TerminalEmulator(out, columns, rows, 8, 16, 1000, null)

    private fun TerminalEmulator.feed(seq: String) {
        val bytes = seq.toByteArray(Charsets.US_ASCII)
        append(bytes, bytes.size)
    }

    @Test
    fun `超长 OSC52 被整条丢弃 不进剪贴板`() {
        val out = RecordingOutput()
        val em = emulator(out)
        // base64 载荷 ~150KB（远超 100KB+10 上限），ST 收尾
        val bigPayload = "A".repeat(150 * 1024)
        em.feed("\u001b]52;c;$bigPayload\u001b\\")
        assertEquals("超长 OSC 52 不该送进剪贴板（防护由 collectOSCArgs 承担）", null, out.lastClipboard)
    }
}
