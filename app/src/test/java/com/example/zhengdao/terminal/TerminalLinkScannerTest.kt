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

    // ── §B 顺序 30（2026-10-14 真机）：尾部 ASCII 标点 ──────────────────────

    @Test
    fun `尾部逗号_后面是行尾_裁掉且区间跟着收窄`() {
        val links = TerminalLinkScanner.findLinks("https://example.com/trailing-test,")
        assertEquals(1, links.size)
        assertEquals("https://example.com/trailing-test", links[0].url)
        assertEquals("逗号不该算进链接区间", 33, links[0].end)
    }

    @Test
    fun `尾部句号_后面是空白_裁掉`() {
        val links = TerminalLinkScanner.findLinks("装一下 https://example.com/a. 然后重启")
        assertEquals(1, links.size)
        assertEquals("https://example.com/a", links[0].url)
    }

    @Test
    fun `尾部连续多标点整段裁掉`() {
        assertEquals(
            "https://example.com/x",
            TerminalLinkScanner.findLinks("见 https://example.com/x.., 完").single().url,
        )
    }

    @Test
    fun `句中的点与冒号不裁_是 URL 合法字符`() {
        assertEquals(
            "https://example.com:8080/v1.2/api",
            TerminalLinkScanner.findLinks("https://example.com:8080/v1.2/api").single().url,
        )
    }

    @Test
    fun `句中逗号不裁_后随非空白`() {
        assertEquals(
            "https://example.com/a,b",
            TerminalLinkScanner.findLinks("https://example.com/a,b").single().url,
        )
    }

    @Test
    fun `查询串与问号不被误伤`() {
        assertEquals(
            "https://example.com/search?q=test&p=1",
            TerminalLinkScanner.findLinks("https://example.com/search?q=test&p=1").single().url,
        )
    }

    @Test
    fun `闭合符之后的标点不裁_按后随非空白口径`() {
        // `)` 在排除集里，URL 到它之前就止；`(url,)` 的逗号后随 `)`（非空白）⇒ 按
        // 「后一字符是空白或行尾才裁」的口径原样保留。有意的最小改动，边界写进交付说明。
        assertEquals(
            "https://example.com/a,",
            TerminalLinkScanner.findLinks("(https://example.com/a,)").single().url,
        )
    }

    @Test
    fun `点按命中继承裁剪_尾标点那一位不再算链接`() {
        val line = "https://example.com/trailing-test,"
        assertEquals("https://example.com/trailing-test", TerminalLinkScanner.findLinkCovering(line, 5))
        assertNull("逗号处（索引 33）不该再命中链接", TerminalLinkScanner.findLinkCovering(line, 33))
    }
}
