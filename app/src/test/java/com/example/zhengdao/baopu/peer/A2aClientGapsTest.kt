// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十批 · A2A 收口的判据测试（①版本区间 ②4xx按序分 ③cancel幂等 ④非ASCII凭据归CONFIG）。
//
// 判据（派工单 590 七条，这里落前五条＋第六条的请求计数证据；第七条是 detekt/lint 门禁读数）：
// · 超区间不请求——卡上 protocolVersion 越界，message/send 一个 RPC 不发（拉卡那一发不算：不拉卡不知道版本）；
// · 400+错误体判版本——错误体点名 version（spec §6.4 的形状）才归 VERSION_MISMATCH，没点名归 PROTOCOL；
// · 401·404·500·其余4xx 四类不混——AUTH／ENDPOINT／NETWORK／PROTOCOL 各是各的码、各是各的话；
// · cancel 幂等——首取消回终态；重复取消被拒（spec TaskNotCancelableError -32002）就回读一次收终态；
//   回读也没终态就把原错照抛，**不许把「取消失败」谎报成「已取消」**；
// · 中文 token 归 CONFIG——本机就拦下，一个字节不发，账行落 "config"；
// · 不新增网络行为——上面的「零请求／恰好多一发」计数就是证据：预检只减请求，cancel 只按调用数发。
//
// 靶子：570 床（[Peer570A2aBed]，①③用）＋裸 [MockWebServer]（②④用按序投喂）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.testkit.A2aBedFault
import com.example.zhengdao.testkit.Peer570A2aBed
import com.example.zhengdao.testkit.TempSandboxPool
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class A2aClientGapsTest {

    private lateinit var bed: Peer570A2aBed

    @Before
    fun setUp() {
        bed = Peer570A2aBed()
        bed.start()
        val root = TempSandboxPool.create("a2a590")
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        bed.close()
        LeaseStore.resetForTest()
        AgentRegistry.resetForTest()
        PeerEventsLedger.resetForTest()
        TempSandboxPool.clean()
    }

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun bedClient() = A2aClient(bed.base(), http())

    private fun failureOf(block: () -> Unit): A2aException {
        val e = runCatching(block).exceptionOrNull()
        return e as? A2aException ?: throw AssertionError("该是 A2aException，来的是：$e", e)
    }

    /** 裸靶：按序投喂。拉卡永远给第一张卡（protocolVersion 可指定/可缺）。 */
    private fun rawServer(): MockWebServer = MockWebServer().also { it.start() }

    private fun closeQuietly(vararg servers: MockWebServer) {
        servers.forEach { runCatching { it.close() } }
    }

    private fun cardJson(base: String, protocolVersion: String?): String {
        val o = JSONObject()
            .put("name", "590靶")
            .put("description", "590 收口用的靶卡")
            .put("url", "$base/")
            .put("version", "1.0")
            .put("capabilities", JSONObject())
            .put("skills", JSONArray())
        if (protocolVersion != null) o.put("protocolVersion", protocolVersion)
        return o.toString()
    }

    private fun taskJson(state: String): String = JSONObject()
        .put("id", "task-590")
        .put("contextId", "ctx-590")
        .put("kind", "task")
        .put("status", JSONObject().put("state", state))
        .toString()

    private fun rpcEnvelope(result: JSONObject): String =
        JSONObject().put("jsonrpc", "2.0").put("id", 0).put("result", result).toString()

    private fun rpcError(reqBody: JSONObject, code: Int, message: String): MockResponse =
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
            .body(
                JSONObject().put("jsonrpc", "2.0").put("id", reqBody.opt("id"))
                    .put("error", JSONObject().put("code", code).put("message", message)).toString(),
            ).build()

    // ── 判据① 超区间不请求 ───────────────────────────────────────────────

    @Test
    fun `收口一_卡上版本低于区间_messageSend一个RPC不发_归VERSION_MISMATCH`() {
        bed.fault = A2aBedFault.BAD_PROTOCOL // 卡上公布 0.2.5（低于支持区间）
        val c = bedClient()
        val card = c.fetchCard()
        assertEquals("卡上如实读到旧版", Peer570A2aBed.UNSUPPORTED_PROTOCOL, card.protocolVersion)
        val e = failureOf { c.messageSend(card, "发一条") }
        assertEquals(PeerFailureCode.VERSION_MISMATCH, e.failureCode)
        assertTrue("人话要提版本：${e.message}", e.message!!.contains("版本"))
        assertTrue("不许混成网络错", e.message!!.contains("版本对不上"))
        assertEquals("一个 RPC 都没发（床没收到任何 method）", 0, bed.rpcMethods.size)
        assertEquals("只有拉卡那一发", 1, bed.cardPaths.size)
    }

    @Test
    fun `收口一b_版本高于区间_或卡上没写_同样不发_归VERSION_MISMATCH`() {
        // 超上界：2.0
        val high = rawServer()
        val missing = rawServer()
        try {
            high.enqueue(MockResponse.Builder().code(200).body(cardJson("http://127.0.0.1:${high.port}", "2.0")).build())
            val c1 = A2aClient("http://127.0.0.1:${high.port}", http())
            val card1 = c1.fetchCard()
            val e1 = failureOf { c1.messageSend(card1, "发一条") }
            assertEquals(PeerFailureCode.VERSION_MISMATCH, e1.failureCode)
            assertEquals("超上界也只许有拉卡那一发", 1, high.requestCount)

            // 卡上没写 protocolVersion：认不出＝不敢连（A2A 卡必带该字段，缺了就是卡不合规）
            missing.enqueue(MockResponse.Builder().code(200).body(cardJson("http://127.0.0.1:${missing.port}", null)).build())
            val c2 = A2aClient("http://127.0.0.1:${missing.port}", http())
            val card2 = c2.fetchCard()
            assertTrue("缺字段读成空", card2.protocolVersion.isEmpty())
            val e2 = failureOf { c2.messageSend(card2, "发一条") }
            assertEquals(PeerFailureCode.VERSION_MISMATCH, e2.failureCode)
            assertTrue("人话要点破「没写」：${e2.message}", e2.message!!.contains("没写"))
            assertEquals("缺版本同样只许有拉卡那一发", 1, missing.requestCount)
        } finally {
            closeQuietly(high, missing)
        }
    }

    // ── 判据② 400+错误体判版本；401·404·500·其余4xx 四类不混 ─────────────

    @Test
    fun `收口二_400错误体点名version_归VERSION_MISMATCH_没点名归PROTOCOL`() {
        val server = rawServer()
        try {
            // spec §6.4 的版本拒绝形状：400 + problem+json，体里点名 version。
            val versionBody = JSONObject()
                .put("type", "https://a2a-protocol.org/errors/version-not-supported")
                .put("title", "Protocol Version Not Supported")
                .put("status", 400)
                .put("detail", "The requested A2A protocol version 0.5 is not supported by this agent")
                .put("supportedVersions", JSONArray().put("0.3"))
                .toString()
            server.enqueue(MockResponse.Builder().code(200).body(cardJson("http://127.0.0.1:${server.port}", "1.0")).build())
            server.enqueue(MockResponse.Builder().code(400).body(versionBody).build())
            val c1 = A2aClient("http://127.0.0.1:${server.port}", http())
            val e1 = failureOf { c1.messageSend(c1.fetchCard(), "发一条") }
            assertEquals("400＋体里点名 version ⇒ 版本不符", PeerFailureCode.VERSION_MISMATCH, e1.failureCode)
            assertTrue(e1.message!!.contains("HTTP 400"))

            // 同码不同体：400 但没点 version ⇒ 还是 PROTOCOL（不许把一切 400 都说成版本问题）。
            server.enqueue(MockResponse.Builder().code(200).body(cardJson("http://127.0.0.1:${server.port}", "1.0")).build())
            server.enqueue(MockResponse.Builder().code(400).body("""{"status":400,"detail":"Invalid parameters"}""").build())
            val c2 = A2aClient("http://127.0.0.1:${server.port}", http())
            val e2 = failureOf { c2.messageSend(c2.fetchCard(), "发一条") }
            assertEquals("400 没点名 version ⇒ 协议不符", PeerFailureCode.PROTOCOL, e2.failureCode)
        } finally {
            closeQuietly(server)
        }
    }

    @Test
    fun `收口二b_401_404_500_其余4xx_四类不混_人话各是各`() {
        val server = rawServer()
        try {
            listOf(401, 404, 500, 418).forEach { code ->
                server.enqueue(MockResponse.Builder().code(code).build())
            }
            val c = A2aClient("http://127.0.0.1:${server.port}", http())
            val results = listOf(401, 404, 500, 418).map { httpCode ->
                httpCode to failureOf { c.fetchCard() }
            }
            val codes = results.map { it.second.failureCode }
            assertEquals(
                "四类四个码：401→AUTH 404→ENDPOINT 500→NETWORK 418→PROTOCOL",
                listOf(PeerFailureCode.AUTH, PeerFailureCode.ENDPOINT, PeerFailureCode.NETWORK, PeerFailureCode.PROTOCOL),
                codes,
            )
            assertEquals("四个码互不相同", 4, codes.toSet().size)
            val humans = results.map { it.second.message.orEmpty() }
            assertEquals("四句话互不相同", 4, humans.toSet().size)
            assertTrue("401 得指授权：${humans[0]}", humans[0].contains("先验明身份"))
            assertTrue("404 得指端点：${humans[1]}", humans[1].contains("没有这条接口"))
        } finally {
            closeQuietly(server)
        }
    }

    // ── 判据④ cancel 幂等 ───────────────────────────────────────────────

    @Test
    fun `收口三_cancel回终态canceled_再取消幂等_一次恰好多一发`() {
        val c = bedClient()
        val card = c.fetchCard()
        val sent = c.messageSend(card, "先开一个任务")

        val first = c.taskCancel(card, sent.id)
        assertEquals("首取消回终态", A2aTaskState.CANCELED, first.state)
        assertTrue("canceled 是终态", first.state.terminal)
        assertEquals("发送＋取消恰好两发", listOf("message/send", "tasks/cancel"), bed.rpcMethods.toList())

        val second = c.taskCancel(card, sent.id)
        assertEquals("再取消不炸、照样 canceled", A2aTaskState.CANCELED, second.state)
        assertEquals("幂等＝不重演：再取消也只恰好多一发（床不拒 ⇒ 无回读）",
            listOf("message/send", "tasks/cancel", "tasks/cancel"), bed.rpcMethods.toList())
    }

    @Test
    fun `收口三b_重复取消被拒_回读终态幂等收`() {
        val server = rawServer()
        try {
            // spec §3.1.5/§5.4：对已取消的任务再取消，服务器可拒 TaskNotCancelableError(-32002)。
            val base = "http://127.0.0.1:${server.port}"
            server.enqueue(MockResponse.Builder().code(200).body(cardJson(base, "1.0")).build())
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(rpcEnvelope(JSONObject(taskJson("canceled")))).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(
                        JSONObject().put("jsonrpc", "2.0").put("id", 2)
                            .put("error", JSONObject().put("code", -32002).put("message", "Task not cancelable")).toString(),
                    ).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(rpcEnvelope(JSONObject(taskJson("canceled")))).build(),
            )

            val c = A2aClient(base, http())
            val card = c.fetchCard()
            val first = c.taskCancel(card, "task-590")
            assertEquals("首取消回 canceled", A2aTaskState.CANCELED, first.state)
            val second = c.taskCancel(card, "task-590")
            assertEquals("被拒后回读到终态 ⇒ 幂等收，不抛", A2aTaskState.CANCELED, second.state)
            assertEquals("拉卡＋取消＋取消（被拒）＋回读，恰好四发", 4, server.requestCount)
        } finally {
            closeQuietly(server)
        }
    }

    @Test
    fun `收口三c_任务已完成的取消被拒_回读照实收completed_不谎报canceled`() {
        val server = rawServer()
        try {
            val base = "http://127.0.0.1:${server.port}"
            server.enqueue(MockResponse.Builder().code(200).body(cardJson(base, "1.0")).build())
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(
                        JSONObject().put("jsonrpc", "2.0").put("id", 1)
                            .put("error", JSONObject().put("code", -32002).put("message", "Task not cancelable")).toString(),
                    ).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(rpcEnvelope(JSONObject(taskJson("completed")))).build(),
            )

            val c = A2aClient(base, http())
            val card = c.fetchCard()
            val got = c.taskCancel(card, "task-590")
            assertEquals("已完成任务的取消：照实回 completed", A2aTaskState.COMPLETED, got.state)
            assertTrue(got.state.terminal)
        } finally {
            closeQuietly(server)
        }
    }

    @Test
    fun `收口三d_取消失败回读也无终态_原错照抛_不谎报已取消`() {
        val server = rawServer()
        try {
            val base = "http://127.0.0.1:${server.port}"
            server.enqueue(MockResponse.Builder().code(200).body(cardJson(base, "1.0")).build())
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(
                        JSONObject().put("jsonrpc", "2.0").put("id", 1)
                            .put("error", JSONObject().put("code", -32602).put("message", "本靶没有这个任务")).toString(),
                    ).build(),
            )
            server.enqueue(
                MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                    .body(
                        JSONObject().put("jsonrpc", "2.0").put("id", 2)
                            .put("error", JSONObject().put("code", -32602).put("message", "本靶没有这个任务")).toString(),
                    ).build(),
            )

            val c = A2aClient(base, http())
            val card = c.fetchCard()
            val e = failureOf { c.taskCancel(card, "task-ghost") }
            assertEquals(PeerFailureCode.PROTOCOL, e.failureCode)
            assertTrue("抛的是 tasks/cancel 的原错：${e.message}", e.message!!.contains("tasks/cancel 被拒"))
            assertEquals("拉卡＋取消＋回读尝试，恰好三发", 3, server.requestCount)
        } finally {
            closeQuietly(server)
        }
    }

    // ── 判据⑤ 中文 token 归 CONFIG（零请求）＋探测账行 ───────────────────

    @Test
    fun `收口四_中文token_本机拦下归CONFIG_零请求_人话指路`() {
        val server = rawServer() // 不投喂：一个请求都不该来
        try {
            val bad = A2aCredential(Peer570A2aBed.TOKEN_HEADER, "中文token-不合法")
            val c = A2aClient("http://127.0.0.1:${server.port}", http(), credential = bad)
            val e = failureOf { c.fetchCard() }
            assertEquals(PeerFailureCode.CONFIG, e.failureCode)
            assertTrue("人话指路换 token：${e.message}", e.message!!.contains("ASCII"))
            assertTrue("人话指路自定头：${e.message}", e.message!!.contains("自定头"))
            assertEquals("本机拦下：一个字节都不发", 0, server.requestCount)

            // 负例：同一台靶，换成 ASCII token 照常通——拦的是非 ASCII，不是一刀切。
            server.enqueue(MockResponse.Builder().code(200).body(cardJson("http://127.0.0.1:${server.port}", "1.0")).build())
            val ok = A2aClient(
                "http://127.0.0.1:${server.port}", http(),
                credential = A2aCredential(Peer570A2aBed.TOKEN_HEADER, "sk-ok-590"),
            )
            assertEquals("ASCII token 照常拉卡", "590靶", ok.fetchCard().name)
            assertEquals("负例恰好发出拉卡那一发", 1, server.requestCount)
        } finally {
            closeQuietly(server)
        }
    }

    @Test
    fun `收口四b_探测路中文token_账行落config_人话指路`() {
        val server = rawServer()
        try {
            val base = "http://127.0.0.1:${server.port}"
            LeaseOutboundGate.grantProbeLease(base, McpPeerProbe.SESSION_MANUAL, "test-590", NOW)
            val out = A2aPeerProbe(http()).probeCard(
                base,
                credential = A2aCredential(Peer570A2aBed.TOKEN_HEADER, "中文token-不合法"),
                nowMs = NOW,
            )
            assertEquals(PeerFailureCode.CONFIG, out.code)
            assertTrue("人话指路：${out.human}", out.human.contains("ASCII"))
            assertEquals("账行按键对账：result=config", "config",
                PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.PROBE }.optString("result"))
            assertEquals("探测也没发出一个字节", 0, server.requestCount)
        } finally {
            closeQuietly(server)
        }
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
