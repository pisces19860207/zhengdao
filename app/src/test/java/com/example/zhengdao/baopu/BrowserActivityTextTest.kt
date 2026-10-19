// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserActivityTextTest {

    @Test
    fun open_shows_host_only() {
        val line = browserActivityLine(
            "browser_open",
            """browser_open（{"url":"https://ai-xan.xyz/zh/app/abc/configuration"}）→ {"ok":true}""",
        )
        assertEquals("正在读 ai-xan.xyz", line)
    }

    @Test
    fun snapshot_and_click_use_same_host() {
        val summary = """browser_click（{"ref":"e12","url":"https://example.com/a/b"}）→ ok"""
        assertEquals("正在点 example.com", browserActivityLine("browser_click", summary))
        assertEquals("正在读 example.com", browserActivityLine("browser_snapshot", summary))
    }

    @Test
    fun no_url_does_not_invent_one() {
        assertEquals("正在读页面", browserActivityLine("browser_snapshot", "browser_snapshot（{}）→ ok"))
        assertEquals("正在点页面", browserActivityLine("browser_click", "失败：元素没找到"))
    }

    @Test
    fun non_browser_tool_is_ignored() {
        assertNull(browserActivityLine("read_file", """read_file（{"url":"https://x"}）"""))
    }

    @Test
    fun host_parsing_edges() {
        assertEquals("example.com", hostOf("https://example.com"))
        assertEquals("localhost:8080", hostOf("http://localhost:8080/x"))
        assertNull(hostOf(""))
        assertEquals("example.com", hostOf("example.com/y"))
    }

    @Test
    fun extract_handles_spaces_after_colon() {
        assertEquals("https://a.b/c", extractUrl("""{"url" : "https://a.b/c"}"""))
        assertNull(extractUrl("""{"url": 123}"""))
        assertNull(extractUrl("没有 url"))
    }
}
