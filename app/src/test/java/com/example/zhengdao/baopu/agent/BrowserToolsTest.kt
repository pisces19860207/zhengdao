// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：刀 2 收尾后用户口径「浏览器工具你也别忘了」。本测试只验**工具层语义**：
// 权限级、scheme 拦截、截断明说、拿不到网关时的话术、参数错误话术、截图图片通道。
// 不碰真实 WebView（那要真机；见交付报告「未验」栏）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserToolsTest {

    /** 假网关：记录调用、返回固定文本、可注入异常。 */
    private class FakeGateway(
        private val answer: String = "ok",
        private val boom: Throwable? = null,
        private val shotRel: String? = "截屏/bridge-1.png",
        private val page: PageInfo? = null,
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
        override fun screenshot(fullPage: Boolean): Shot {
            calls += "shot:$fullPage"
            boom?.let { throw it }
            return Shot(answer, shotRel)
        }
        override fun tap(cssX: Int, cssY: Int): String {
            calls += "tap:$cssX,$cssY"
            boom?.let { throw it }
            return answer
        }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
            calls += "swipe:$x1,$y1->$x2,$y2:$durationMs"
            boom?.let { throw it }
            return answer
        }
        override fun longPress(cssX: Int, cssY: Int, durationMs: Int): String {
            calls += "press:$cssX,$cssY:$durationMs"
            boom?.let { throw it }
            return answer
        }
        override fun key(key: String): String {
            calls += "key:$key"
            boom?.let { throw it }
            return answer
        }
        override fun waitFor(ms: Int, selector: String?): String {
            calls += "wait:$ms:$selector"
            boom?.let { throw it }
            return answer
        }
        override fun pageInfo(): PageInfo? = page
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
        assertEquals(ToolPermission.FREE, spec("browser_wait").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_open").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_click").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_type").permission)
        // 第一百七十七批：坐标四件都是「会改页面状态」的写类 ⇒ 每次一张卡
        assertEquals(ToolPermission.CONFIRM, spec("browser_tap").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_swipe").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_long_press").permission)
        assertEquals(ToolPermission.CONFIRM, spec("browser_key").permission)
    }

    @Test
    fun 十一个工具_名字与超时() {
        val names = BrowserTools.all().map { it.name }
        assertEquals(
            listOf(
                "browser_open", "browser_snapshot", "browser_click",
                "browser_type", "browser_scroll", "browser_screenshot",
                "browser_tap", "browser_swipe", "browser_long_press",
                "browser_key", "browser_wait",
            ),
            names,
        )
        assertTrue(BrowserTools.all().all { it.timeoutMs == ToolTimeout.BROWSER_MS })
    }

    @Test
    fun 浏览器工具都不许并行_只读也不许() {
        // 抢的是同一个 WebView：并行两个读也是踩同一个页面
        assertTrue(BrowserTools.all().none { it.parallelSafe })
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
    fun 网关没开_十一工具都回同一句人话() {
        BaopuBrowser.gateway = null
        val args = mapOf(
            "browser_open" to JSONObject().put("url", "https://a.com"),
            "browser_snapshot" to JSONObject(),
            "browser_click" to JSONObject().put("target", "#a"),
            "browser_type" to JSONObject().put("target", "#a").put("text", "x"),
            "browser_scroll" to JSONObject().put("direction", "down"),
            "browser_screenshot" to JSONObject(),
            "browser_tap" to JSONObject().put("x", 10).put("y", 20),
            "browser_swipe" to JSONObject().put("x1", 10).put("y1", 20).put("x2", 30).put("y2", 40),
            "browser_long_press" to JSONObject().put("x", 10).put("y", 20),
            "browser_key" to JSONObject().put("key", "enter"),
            "browser_wait" to JSONObject().put("ms", 100),
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
    fun 截图_带图路径进图片通道_文本仍含路径() {
        BaopuBrowser.gateway = FakeGateway(answer = "已截图：截屏/bridge-1.png")
        val outcome = spec("browser_screenshot").executor(JSONObject())
        val ok = outcome as ToolOutcome.Ok
        assertTrue(ok.text.contains("截屏/bridge-1.png"))
        // S1：路径进图片通道 ⇒ 引擎会把它读成像素给模型
        assertEquals(listOf("截屏/bridge-1.png"), ok.imageRelPaths)
    }

    @Test
    fun 截图_拿不到路径_明说模型看不到像素() {
        BaopuBrowser.gateway = FakeGateway(answer = "截了但没路径", shotRel = null)
        val outcome = spec("browser_screenshot").executor(JSONObject())
        val ok = outcome as ToolOutcome.Ok
        assertTrue(ok.imageRelPaths.isEmpty())
        assertTrue("必须明说看不到像素", ok.text.contains("看不到像素"))
    }

    @Test
    fun 截图_敏感页_回执带警告() {
        BaopuBrowser.gateway = FakeGateway(
            answer = "已截图",
            page = PageInfo("https://login.example.com/", "登录"),
        )
        val outcome = spec("browser_screenshot").executor(JSONObject())
        assertTrue((outcome as ToolOutcome.Ok).text.startsWith("⚠ 敏感页截图"))
    }

    // ── 第一百七十七批：坐标四件的参数边界 ────────────────────────────────────

    @Test
    fun tap_负坐标_失败且不碰网关() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        val outcome = spec("browser_tap").executor(JSONObject().put("x", -1).put("y", 5))
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun tap_正常_坐标原样传下去() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        spec("browser_tap").executor(JSONObject().put("x", 12).put("y", 34))
        assertEquals(listOf("tap:12,34"), fake.calls)
    }

    @Test
    fun swipe_时长夹到上下限() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        val args = JSONObject().put("x1", 0).put("y1", 0).put("x2", 10).put("y2", 10)
            .put("duration_ms", 99_999)
        spec("browser_swipe").executor(args)
        assertEquals(listOf("swipe:0,0->10,10:${BrowserTools.MAX_PRESS_MS}"), fake.calls)
    }

    @Test
    fun long_press_默认500毫秒() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        spec("browser_long_press").executor(JSONObject().put("x", 1).put("y", 2))
        assertEquals(listOf("press:1,2:500"), fake.calls)
    }

    @Test
    fun key_非法键_失败且不碰网关() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        val outcome = spec("browser_key").executor(JSONObject().put("key", "f5"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun wait_毫秒夹到上限_选择器可为空() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        spec("browser_wait").executor(JSONObject().put("ms", 99_999))
        assertEquals(listOf("wait:${BrowserTools.MAX_WAIT_MS}:null"), fake.calls)
    }

    @Test
    fun wait_没给参数_默认2000纯等() {
        val fake = FakeGateway()
        BaopuBrowser.gateway = fake
        spec("browser_wait").executor(JSONObject())
        assertEquals(listOf("wait:2000:null"), fake.calls)
    }

    @Test
    fun 没有网关时_截图不会假装成功() {
        BaopuBrowser.gateway = null
        val outcome = spec("browser_screenshot").executor(JSONObject())
        assertTrue(outcome is ToolOutcome.Failed)
        assertNull(BaopuBrowser.gateway)
    }
}
