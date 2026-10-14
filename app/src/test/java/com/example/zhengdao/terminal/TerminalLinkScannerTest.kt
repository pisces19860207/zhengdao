package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TerminalLinkScanner（B 路径：裸 URL 识别）单测。
 * 纯 JVM，无 Android 依赖。派工单第 ② 刀（2026-10-14）与 OSC 8 合刀落地。
 */
class TerminalLinkScannerTest {

    @Test
    fun `裸 URL 命中`() {
        assertEquals(
            "https://example.com/a",
            TerminalLinkScanner.findLinkCovering("see https://example.com/a ok", 5),
        )
    }

    @Test
    fun `URL 外的列不命中`() {
        assertNull(TerminalLinkScanner.findLinkCovering("see https://a.com ok", 0))
        assertNull(TerminalLinkScanner.findLinkCovering("see https://a.com ok", 21)) // 空格后
    }

    @Test
    fun `行内多条取覆盖那一条`() {
        val line = "https://a.com 和 https://b.com"
        assertEquals("https://a.com", TerminalLinkScanner.findLinkCovering(line, 3))
        assertEquals("https://b.com", TerminalLinkScanner.findLinkCovering(line, 17))
    }

    @Test
    fun `中文标点结尾不被吞进 URL`() {
        val links = TerminalLinkScanner.findLinks("打开 https://a.com。好的")
        assertEquals(1, links.size)
        assertEquals("https://a.com", links[0].url)
    }

    @Test
    fun `空行与负索引安全`() {
        assertNull(TerminalLinkScanner.findLinkCovering("", 0))
        assertNull(TerminalLinkScanner.findLinkCovering("https://a.com", -1))
    }

    @Test
    fun `非 http 链接不识别`() {
        // file:// 与 intent:// 不走 B 路径（策略层拒绝；见 TerminalLinkPolicy）
        assertEquals(0, TerminalLinkScanner.findLinks("file:///etc/passwd intent://x").size)
    }
}
