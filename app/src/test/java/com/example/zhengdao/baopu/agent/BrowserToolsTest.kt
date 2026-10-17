// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：刀 2 收尾后用户口径「浏览器工具你也别忘了」。本测试只验**工具层语义**：
// 权限级、scheme 拦截、截断明说、拿不到网关时的话术、参数错误话术。
// 不碰真实 WebView（那要真机；见交付报告「未验」栏）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolsTest {

    /** 假网关：记录调用、返回固定文本、可注入异常。 */
    private class FakeGateway(
        private val answer: String = "ok",
        private val boom: Throwable? = null,
    ) : BrowserGateway {
        val calls = mutableListOf<String>()
        override fun open(url: String, newTab: Boolean): String {
            calls += "open:$url:$newTab"
            boom?.let { throw it }
            return answer
        }
        override fun snapshot(selector: String?): String {
            calls += "snapshot:$selector"
            boom?.let { throw it }
            return answer
        }
        override fun click(target: String): String {
            calls += "click:$target"
            boom?.let { throw it }
            return answer
        }
        override fun typeInto(target: String, text: String): String {
            calls += "type:$target=$text"
            boom?.let { throw it }
            return answer
        }
        override fun scroll(direction: String): String {
            calls += "scroll:$direction"
            boom?.let { throw it }
            return answer
        }
        override fun screenshot(fullPage: Boolean): String {
            calls += "shot:$fullPage"
            boom?.let { throw it }
            return answer
        }
    }

    @After
    fun tearDown() {
        BaopuBrowser.gateway = null
    }

    private fun spec(name: String): ToolSpec =
        BrowserTools.all().first { it.name == name }

    @Test
    fun 权限级_读类免确认_写类需确认() {
        assertEquals(ToolPermission.FREE, spec("browser_snapshot").permission)
        assertEquals(ToolPermission.FREE, spec("browser_screenshot").permission)
        assertEquals(ToolPermission.FREE, spec("browser_scroll").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_open").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_click").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_type").permission)
    }

    @Test
    fun 六个工具_名字与超时() {
        val names = BrowserTools.all().map { it.name }
        assertEquals(
            listOf(
                "browser_open", "browser_snapshot", "browser_click",
                "browser_type", "browser_scroll", "browser_screenshot",
            ),
            names,
        )
        assertTrue(BrowserTools.all().all { it.timeoutMs == ToolTimeout.BROWSER_MS })
    }

    @Test
    fun open_非http_scheme_直接拒() {
        BaopuBrowser.gateway = FakeGateway()
        val outcome = spec("browser_open").executor(JSONObject().put("url", "javascript:alert(1)"))
        assertTrue(outcome is ToolOutcome.Rejected)
        assertTrue((outcome as ToolOutcome.Rejected).reason.contains("http"))
        assertEquals(0, (BaopuBrowser.gateway as FakeGateway).calls.size)
    }

    @Test
    fun open_正常_传下去并提示异步() {
        BaopuBrowser.gateway = FakeGateway(answer = "navigating")
        val outcome = spec("browser_open").executor(JSONObject().put("url", "https://m.bing.com"))
        assertTrue(outcome is ToolOutcome.Ok)
        val text = (outcome as ToolOutcome.Ok).text
        assertTrue(text.contains("m.bing.com"))
        assertTrue(text.contains("异步"))
    }

    @Test
    fun snapshot_超长_截断并明说() {
        BaopuBrowser.gateway = FakeGateway(answer = "x".repeat(BrowserTools.MAX_SNAPSHOT_CHARS + 500))
        val outcome = spec("browser_snapshot").executor(JSONObject())
        val text = (outcome as ToolOutcome.Ok).text
        assertTrue(text.contains("已截断"))
        assertTrue(text.contains("还有约 500 字符"))
    }

    @Test
    fun snapshot_未超长_原样返回() {
        BaopuBrowser.gateway = FakeGateway(answer = "短")
        val outcome = spec("browser_snapshot").executor(JSONObject())
        assertEquals("短", (outcome as ToolOutcome.Ok).text)
    }

    @Test
    fun snapshot_只截断到上限_不吞中间() {
        val capped = BrowserTools.cap("abcdef", 3)
        assertTrue(capped.startsWith("abc"))
        assertTrue(capped.contains("还有约 3 字符"))
    }

    @Test
    fun click_空目标_失败话术() {
        BaopuBrowser.gateway = FakeGateway()
        val outcome = spec("browser_click").executor(JSONObject().put("target", "  "))
        assertTrue(outcome is ToolOutcome.Failed)
    }

    @Test
    fun click_at句柄_明确说不支持() {
        BaopuBrowser.gateway = FakeGateway()
        val outcome = spec("browser_click").executor(JSONObject().put("target", "@e3"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).error.contains("CSS"))
    }

    @Test
    fun type_空文本_允许清空() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        val outcome = spec("browser_type")
            .executor(JSONObject().put("target", "#q").put("text", ""))
        assertTrue(outcome is ToolOutcome.Ok)
        assertEquals(listOf("type:#q="), fake.calls)
    }

    @Test
    fun scroll_非法方向_失败() {
        BaopuBrowser.gateway = FakeGateway()
        val outcome = spec("browser_scroll").executor(JSONObject().put("direction", "diagonal"))
        assertTrue(outcome is ToolOutcome.Failed)
    }

    @Test
    fun 网关没开_六工具都回同一句人话() {
        BaopuBrowser.gateway = null
        val args = mapOf(
            "browser_open" to JSONObject().put("url", "https://a.com"),
            "browser_snapshot" to JSONObject(),
            "browser_click" to JSONObject().put("target", "#a"),
            "browser_type" to JSONObject().put("target", "#a").put("text", "x"),
            "browser_scroll" to JSONObject().put("direction", "down"),
            "browser_screenshot" to JSONObject(),
        )
        for ((name, json) in args) {
            val outcome = spec(name).executor(json)
            assertTrue("$name 应回 Failed", outcome is ToolOutcome.Failed)
            assertTrue(
                "$name 话术应含玄览",
                (outcome as ToolOutcome.Failed).error.contains("玄览"),
            )
        }
    }

    @Test
    fun 网关抛异常_变Failed不崩() {
        BaopuBrowser.gateway = FakeGateway(boom = IllegalStateException("boom"))
        val outcome = spec("browser_snapshot").executor(JSONObject())
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).error.contains("boom"))
    }

    @Test
    fun 截图_只回路径不塞像素() {
        BaopuBrowser.gateway = FakeGateway(answer = "/data/user/0/x/files/shot.png")
        val outcome = spec("browser_screenshot").executor(JSONObject())
        assertTrue((outcome as ToolOutcome.Ok).text.contains(".png"))
    }
}
