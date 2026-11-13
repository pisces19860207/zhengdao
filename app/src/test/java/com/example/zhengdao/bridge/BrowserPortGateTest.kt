// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 622：执行端口（BrowserPort）的五道闸判据——**闸的行为在这里验，不在调用方验**。
 *
 * 五组判据（对应 622 工单 A①/A③）：
 * 1. 急停第一道：停机中一个动作都不发（READ 也拦）；
 * 2. 三档闸：EXTERNAL 回闸（EXEC_JS / 拒点词升档）、LOCAL 执行并记账、READ 直执行；
 * 3. 参数闸：缺 URL / 坏 selector / 坏 mode 在端口就拒（不到执行层）；
 * 4. URL/域名策略（判据②负例）：越白名单 ⇒ 拒**并记账**；
 * 5. 单域限速：窗口内超限 ⇒ 熔断并记账。
 *
 * JVM 约束说明：执行层（BrowserCommands→BrowserTabManager）在没有标签时如实回
 * 「没有活跃标签」错误 JSON——本文件的"放行"用例都断言到这层为止（闸放行＋执行层
 * 如实报无标签），不造 WebView。
 */
class BrowserPortGateTest {

    private val events = mutableListOf<BrowserPort.LedgerEvent>()
    private var now = 1_000_000L
    private var halted = false

    @Before
    fun install() {
        events.clear()
        now = 1_000_000L
        halted = false
        BrowserPort.install(
            halt = { halted },
            ledger = { events += it },
            nowMs = { now },
            rateMax = 5,
            rateWindowMs = 60_000L,
        )
    }

    @After
    fun restore() {
        BrowserPort.resetForTest()
    }

    // ── ① 急停第一道 ─────────────────────────────────────────────────────────

    @Test
    fun `急停中_一个动作都不发_READ也拦_并记账`() {
        halted = true
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.SNAPSHOT,
            ),
        )
        assertTrue(o is BrowserPort.PortOutcome.Stopped)
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("halt", stop.wire)
        assertTrue(stop.reason.contains("急停"))
        // 记账：拦下的事实要找得到
        assertEquals(1, events.size)
        assertEquals("halt", events[0].kind)
    }

    // ── ② 三档闸 ─────────────────────────────────────────────────────────────

    @Test
    fun `自由JS属EXTERNAL_端口回闸不执行`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.EXEC_JS,
                script = "return 1+1",
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("escalate", stop.wire)
        assertTrue(stop.reason.contains("回抱朴走闸"))
        assertEquals("escalate", events.single().kind)
    }

    @Test
    fun `点击语义面命中拒点词_升档回闸_词表与示教层同源`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.TEACH_REPLAY,
                action = BrowserPort.PortAction.CLICK,
                selector = "#submit-btn",
                subject = listOf("提交订单"),
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("escalate", stop.wire)
        assertTrue(stop.reason.contains("拒点"))
        // 词表同源：示教层 TeachSafety 与端口判同一个函数（拿同一句面再判一遍，结果一致）
        assertEquals(
            stop.reason,
            com.example.zhengdao.baopu.TeachSafety.refuseClickReason(
                com.example.zhengdao.baopu.TeachSafety.ClickSurface("提交订单", null, null, null),
            ),
        )
    }

    @Test
    fun `英文拒点词同样升档`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.TEACH_REPLAY,
                action = BrowserPort.PortAction.CLICK,
                selector = "#x",
                subject = listOf("Submit order"),
            ),
        )
        assertEquals("escalate", (o as BrowserPort.PortOutcome.Stopped).stop.wire)
    }

    @Test
    fun `无语义面的点击属LOCAL_闸放行_执行层如实报无标签_并记账`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.CLICK,
                selector = "#btn",
            ),
        )
        // 放行到了执行层（JVM 无标签 ⇒ 执行层自己的「没有活跃标签」JSON，不是端口闸停）
        val json = (o as BrowserPort.PortOutcome.Done).json
        assertTrue(json.contains("\"ok\":false"))
        assertTrue(json.contains("没有活跃标签"))
        assertFalse(json.contains("\"stop\""))
        // LOCAL 动作执行后记账
        assertEquals("local", events.single().kind)
    }

    @Test
    fun `READ动作不放LOCAL账`() {
        BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.SNAPSHOT,
                mode = "text",
            ),
        )
        assertTrue("只读动作不记账（执行层自己有日志）", events.isEmpty())
    }

    // ── ③ 参数闸 ─────────────────────────────────────────────────────────────

    @Test
    fun `navigate缺URL_参数闸就拒_不到执行层`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.NAVIGATE,
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("deny", stop.wire)
        assertTrue(stop.reason.contains("缺 url"))
    }

    @Test
    fun `file scheme_参数闸拒`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.NAVIGATE,
                url = "file:///sdcard/index.html",
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertTrue(stop.reason.contains("只允许 http/https"))
    }

    @Test
    fun `selector带引号_参数闸拒_防JS注入逃逸`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.TYPE,
                selector = "x'}alert(1)//",
                text = "hi",
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertTrue(stop.reason.contains("引号或反斜杠"))
    }

    @Test
    fun `type缺text_参数闸拒`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.TYPE,
                selector = "#q",
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertTrue(stop.reason.contains("缺 text"))
    }

    @Test
    fun `probe缺固定脚本_参数闸拒`() {
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.TEACH_REPLAY,
                action = BrowserPort.PortAction.PROBE,
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertTrue(stop.reason.contains("缺固定脚本"))
    }

    // ── ④ URL/域名策略（判据②负例：越白名单 ⇒ 拒并记账） ──────────────────────

    @Test
    fun `越白名单导航_拒并记账_判据②负例`() {
        val policy = BrowserPort.PortPolicy(listOf("demo.example.com"))
        val json = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://evil.example.net/page",
            null,
            policy,
        )
        assertTrue(json.contains("\"stop\":\"deny\""))
        assertTrue(json.contains("不在授权域"))
        assertTrue(json.contains("evil.example.net"))
        // 记账：deny 一条，带动作与目标
        val e = events.single()
        assertEquals("deny", e.kind)
        assertEquals(BrowserPort.PortAction.NAVIGATE, e.action)
        assertEquals("evil.example.net", e.target)
    }

    @Test
    fun `白名单内的导航_放行`() {
        val policy = BrowserPort.PortPolicy(listOf("demo.example.com"))
        val json = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://demo.example.com/list",
            null,
            policy,
        )
        // 放行到了执行层（JVM 无标签/onMain 失败 ⇒ 执行层报错，不是端口闸停）
        assertFalse("不得带端口闸停键：$json", json.contains("\"stop\""))
    }

    @Test
    fun `子域蹭白名单_点前缀匹配防住`() {
        val policy = BrowserPort.PortPolicy(listOf("demo.example.com"))
        val json = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://demo.example.com.evil.io/",
            null,
            policy,
        )
        assertTrue("子域蹭白名单必须被拒：$json", json.contains("不在授权域"))
    }

    @Test
    fun `空授权域全拒_fail-closed`() {
        val json = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://demo.example.com/",
            null,
            BrowserPort.PortPolicy(emptyList()),
        )
        assertTrue("空授权域全拒：$json", json.contains("\"stop\":\"deny\""))
    }

    @Test
    fun `策略OPEN的抱朴路径_scheme闸仍生效`() {
        val json = BrowserPort.open(
            BrowserPort.PortCaller.BAOPU_AGENT,
            "javascript:alert(1)",
            newTab = false,
            context = null,
            tabId = null,
            policy = BrowserPort.PortPolicy.OPEN,
        )
        assertTrue("scheme 闸在端口生效：$json", json.contains("只允许 http/https"))
    }

    // ── ⑤ 单域限速 ───────────────────────────────────────────────────────────

    @Test
    fun `窗口内超限_熔断并记账_过了窗口恢复`() {
        val policy = BrowserPort.PortPolicy.OPEN
        // rateMax=5：前 5 次导航放行（执行层报错，但闸放行）；限速键＝目标 URL 的 host
        repeat(5) { i ->
            val json = BrowserPort.navigate(
                BrowserPort.PortCaller.TEACH_REPLAY,
                "https://demo.example.com/p$i",
                null,
                BrowserPort.PortPolicy(listOf("demo.example.com")),
            )
            assertFalse("第 ${i + 1} 次不该熔断：$json", json.contains("\"stop\""))
        }
        // 第 6 次：同窗口同 host 熔断
        val json = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://demo.example.com/p6",
            null,
            BrowserPort.PortPolicy(listOf("demo.example.com")),
        )
        assertTrue(json.contains("\"stop\":\"throttle\""))
        assertTrue(json.contains("单域限速"))
        assertEquals("throttle", events.last().kind)
        // 时钟走过窗口 ⇒ 恢复放行
        now += 61_000L
        val after = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://demo.example.com/p7",
            null,
            BrowserPort.PortPolicy(listOf("demo.example.com")),
        )
        assertFalse("过窗后应恢复：$after", after.contains("\"stop\""))
    }

    @Test
    fun `不同域不互相熔断`() {
        repeat(5) { i ->
            BrowserPort.navigate(
                BrowserPort.PortCaller.TEACH_REPLAY,
                "https://demo.example.com/p$i",
                null,
                BrowserPort.PortPolicy(listOf("demo.example.com")),
            )
        }
        val other = BrowserPort.navigate(
            BrowserPort.PortCaller.TEACH_REPLAY,
            "https://other.example.org/",
            null,
            BrowserPort.PortPolicy(listOf("other.example.org")),
        )
        assertFalse("不同域不互相熔断：$other", other.contains("\"stop\""))
    }
}
