// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.mermaid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 637 · 页面协议（[MermaidPage]）的门禁。
 *
 * 这里只有一件事值得反复钉：**图源码必须以「JS 字符串字面量」进页面**，
 * 不许以任何形式拼进 HTML / innerHTML。判据不是"看代码像不像"，
 * 而是把脚本里那个字面量**解回来**，要求与原文逐字相同——注入负例
 * （`</script>`、`<img onerror>`、反斜杠、换行）全程只当字符。
 */
class MermaidPageTest {

    /** 从探针脚本里取出 `ZD.render(<literal>)` 的那个字面量并解码回原文。 */
    private fun roundTrip(code: String): String {
        val script = MermaidPage.probeScript(code)
        val start = script.indexOf("window.ZD.render(") + "window.ZD.render(".length
        val end = script.indexOf(");return 'wait'", start)
        assertTrue("探针脚本里找不到 render 调用", start > 0 && end > start)
        val literal = script.substring(start, end)
        // org.json 的真实实现在 test classpath 上（见 build.gradle.kts 第 793 行注释）
        return JSONObject("{\"v\":$literal}").getString("v")
    }

    @Test
    fun `普通源码逐字往返`() {
        val code = "flowchart TD\n  A[开始] --> B{判断}\n  B -->|是| C[结束]"
        assertEquals(code, roundTrip(code))
    }

    @Test
    fun `注入负例只当字符 不许落地成 HTML`() {
        val payloads = listOf(
            "flowchart TD\n  A[\"</script><script>window.__pwned=1</script>\"] --> B",
            "flowchart TD\n  A[\"<img src=x onerror=alert(1)>\"] --> B",
            "flowchart TD\n  A[\"\\\"; window.__pwned=1; //\"] --> B",
            "flowchart TD\n  A[\"</title><svg onload=alert(1)>\"] --> B",
        )
        for (p in payloads) {
            val script = MermaidPage.probeScript(p)
            // ① 原文必须逐字进得去（没有偷偷丢字符、没有提前截断）
            assertEquals(p, roundTrip(p))
            // ② 关掉字符串之前，`</script>` 这类闭合标记不许以裸形式出现在脚本里：
            //    它只可能待在引号内。判据：把字面量挖掉后，剩余脚本不含 "</script"。
            val literalStart = script.indexOf("window.ZD.render(") + "window.ZD.render(".length
            val literalEnd = script.indexOf(");return 'wait'", literalStart)
            val shell = script.substring(0, literalStart) + script.substring(literalEnd)
            assertFalse("注入的闭合标记漏到了字符串外", shell.contains("</script"))
            assertFalse(shell.contains("<script"))
        }
    }

    @Test
    fun `换行与反斜杠被转义 不会打断脚本`() {
        val code = "flowchart TD\n  A[\"a\\\\b\"] --> B"
        val script = MermaidPage.probeScript(code)
        // 脚本本身必须是单行（多行会把 IIFE 拆断）
        assertFalse(script.contains("\n"))
        assertEquals(code, roundTrip(code))
    }

    @Test
    fun `页面地址指向随包 asset 且带上高度上限`() {
        val url = MermaidPage.pageUrl()
        assertEquals("file:///android_asset/mermaid/mermaid.html?maxh=6000", url)
        assertTrue(url.startsWith("file:///android_asset/"))
        assertFalse("绝不许联网", url.startsWith("http"))
        // 越界值被夹住，页面不会收到 0 或负数
        assertTrue(MermaidPage.pageUrl(0).endsWith("maxh=320"))
        assertTrue(MermaidPage.pageUrl(999_999).endsWith("maxh=20000"))
    }

    @Test
    fun `探针协议三种回话都能被认出来`() {
        val s = MermaidPage.probeScript("flowchart TD\n A-->B")
        // 第一次执行：没有 ZD ⇒ 返回 wait（页面还没加载完时不许误判成失败）
        assertTrue(s.contains("if(!window.ZD){return 'wait';}"))
        // 成功与失败两条分支的格式必须与 OffscreenShotter 的解析一致
        assertTrue(s.contains("return 'ok:'+z.h;"))
        assertTrue(s.contains("return 'err:'+(z.msg||'渲染失败');"))
    }
}
