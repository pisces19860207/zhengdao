// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · MCP 探针的判据测试（夹具＝570 自测床的 JVM 起步版）。
//
// 覆盖（派工单 L3-Z1 判据①②③④）：
// ① 570 夹具握手成功、清单落盘字段齐（含 schema sha256 指纹与名册行）；
// ② 协议不符／超时／需鉴权各有不同人话与账行（另有 闸拒/DNS/端口/证书 的分类纯测）；
// ③ schema 指纹变化 ⇒ 提示「能力变了」且不发业务调用；
// ④ 探测只发 initialize / notifications/initialized / tools/list（wire 层逐请求断言，
//    notifications/initialized 是握手序的组成部分——派工单 ① 明文的顺序）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.peer.LeaseOutboundGate
import com.example.zhengdao.baopu.mcp.McpJsonRpc
import com.example.zhengdao.baopu.mcp.McpServerConfig
import com.example.zhengdao.testkit.Peer570Fixture
import com.example.zhengdao.testkit.TempSandboxPool
import java.io.File
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpPeerProbeTest {

    private lateinit var fx: Peer570Fixture
    private lateinit var root: File

    @Before
    fun setUp() {
        fx = Peer570Fixture()
        fx.start()
        root = TempSandboxPool.create("peer548")
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        fx.close()
        LeaseStore.resetForTest()
        AgentRegistry.resetForTest()
        PeerEventsLedger.resetForTest()
        TempSandboxPool.clean()
    }

    // OkHttp 基座：不跟重定向（与生产 defaultHttp 同两条），超时由 config 的时限在 call 上给。
    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun config(
        url: String = fx.mcpUrl(),
        connectTimeoutMs: Long = 5_000L,
        name: String = "570台",
    ): McpServerConfig = McpServerConfig(
        id = "fx570",
        name = name,
        url = url,
        connectTimeoutMs = connectTimeoutMs,
        callTimeoutMs = 5_000L,
    )

    private fun grant(url: String = fx.mcpUrl(), nowMs: Long = NOW): String =
        LeaseOutboundGate.grantProbeLease(url, McpPeerProbe.SESSION_MANUAL, "test-card", nowMs)

    private fun probe(config: McpServerConfig, nowMs: Long = NOW): PeerProbeOutcome =
        McpPeerProbe(http()).probe(config, root = root, nowMs = nowMs)

    // ── 判据①：握手成功、清单落盘字段齐 ────────────────────────────────

    @Test
    fun `判据①_570夹具握手成功_清单落盘字段齐_身份进名册_两本账都有行`() {
        grant()
        val out = probe(config())
        assertEquals(PeerFailureCode.OK, out.code)
        val entry = out.entry ?: throw AssertionError("成功收场必须带清单行")
        assertEquals("ag-", entry.agentId.take(3))
        assertEquals("fx570", entry.serverId)
        assertEquals(fx.mcpUrl(), entry.endpoint)
        assertEquals(Peer570Fixture.MCP_PROTOCOL, entry.protocolVersion)
        assertEquals("streamable-http", entry.mode)
        assertTrue("capabilities 要含 tools：${entry.capabilities}", entry.capabilities.contains("tools"))
        assertEquals(listOf("read_note", "write_note"), entry.tools.map { it.name })
        assertTrue(entry.tools.all { it.description.isNotEmpty() })
        assertTrue(entry.tools.all { it.schemaSha256.length == 64 })
        assertEquals(PeerFailureCode.OK.wire, entry.probeResult)
        assertEquals(NOW, entry.probedAtMs)

        // 指纹与「服务端声明 → 本端归一 → 规范化 sha256」这条管线对上（同一把尺重算）。
        val normalized = com.example.zhengdao.baopu.mcp.McpSchema.normalizeSchema("read_note", org.json.JSONObject(fx.toolASchema))
        assertEquals(PeerFingerprints.ofToolSchema(normalized), entry.tools.first { it.name == "read_note" }.schemaSha256)

        // 落盘读回一致（判据①「清单正确落盘，字段齐全」）。
        assertEquals(entry, McpPeersManifest.load(root)[entry.agentId])
        assertTrue(McpPeersManifest.fileIn(root).isFile)

        // 名册：form=mcp、凭据指纹为空串（这台没配凭据）、能力指纹 64 位。
        val rec = AgentRegistry.active().single()
        assertEquals(entry.agentId, rec.id)
        assertEquals(AgentForm.MCP, rec.form)
        assertEquals("", rec.credentialFingerprint)
        assertEquals(64, rec.capabilityFingerprint.length)

        // 事件账：一行 probe/ok；租约账：grant（peer/probe）＋use＋outcome 三行。
        val probeRows = PeerEventsLedger.replay().filter { it.optString("kind") == PeerEventKinds.PROBE }
        assertEquals(1, probeRows.size)
        assertEquals("ok", probeRows.single().optString("result"))
        val leaseRows = LeaseLedger.replay()
        assertEquals("peer/probe", leaseRows.first { it.optString("kind") == "grant" }.optString("cls"))
        val use = leaseRows.first { it.optString("kind") == "use" }
        assertEquals(McpPeerProbe.TOOL_NAME, use.optString("tool"))
        assertEquals("ok", use.optString("result"))
        assertEquals("ok", leaseRows.first { it.optString("kind") == "outcome" }.optString("result"))
    }

    // ── 判据④：探测只发握手与列清单，不调业务工具 ──────────────────────

    @Test
    fun `判据④_探测只发握手与列清单_绝不发业务调用`() {
        grant()
        probe(config())
        val methods = fx.mcpMethods.toList()
        assertEquals(
            "探测的请求面必须恰是握手三件（notifications/initialized 是握手序组成部分）：$methods",
            listOf("initialize", "notifications/initialized", "tools/list"),
            methods,
        )
        assertFalse("探测不许调业务工具", methods.contains("tools/call"))
        assertFalse("探测不许碰 resources", methods.any { it.startsWith("resources/") })
        assertFalse("探测不许碰 prompts", methods.any { it.startsWith("prompts/") })
    }

    // ── 判据③：schema 指纹变化 ⇒ 「能力变了」，且不自动启用 ─────────────

    @Test
    fun `判据③_schema指纹变化_提示能力变了_且不发业务调用`() {
        grant()
        val first = probe(config())
        assertFalse(first.capabilityChanged)
        val oldFp = first.entry!!.tools.first { it.name == "read_note" }.schemaSha256

        // 上游悄悄改了 read_note 的入参约定（rug-pull 样子）。
        fx.toolASchema = """{"type":"object","properties":{"q":{"type":"string"},"mustNew":{"type":"integer"}},"required":["q","mustNew"]}"""
        LeaseStore.resetForTest() // 清上一轮的租约内存（账文件保留，断言只看本轮）
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        grant() // 外发即租约：第二次探测照样要新批（cap=1，一次一发）

        val second = probe(config())
        assertEquals(PeerFailureCode.OK, second.code)
        assertTrue("指纹变了必须报「能力变了」：${second.human}", second.human.contains("能力变了"))
        assertTrue(second.change!!.schemaChanged.contains("read_note"))
        val newFp = second.entry!!.tools.first { it.name == "read_note" }.schemaSha256
        assertNotEquals(oldFp, newFp)

        // 指纹变了也不许冒出业务调用（本轮依旧只有握手三件）。
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list"),
            fx.mcpMethods.toList().takeLast(3),
        )
        assertTrue(fx.mcpMethods.none { it == "tools/call" })
        // 名册的能力指纹跟着变（下一轮探测的老账可比）。
        assertNotEquals(
            "能力指纹必须变化",
            AgentRegistry.active().single().capabilityFingerprint,
            "",
        )
        // 事件账这轮落 changed=true 的一行。
        val changedRow = PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.PROBE }
        assertEquals("true", changedRow.optString("detail").substringAfter("changed=", "").substringBefore(" "))
    }

    @Test
    fun `无变化的重探_不报能力变了_清单原样更新`() {
        grant()
        probe(config())
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        grant()
        val second = probe(config(), nowMs = NOW + 1_000)
        assertFalse(second.capabilityChanged)
        assertEquals(NOW + 1_000, second.entry!!.probedAtMs)
    }

    // ── 判据②：三类失败各有不同人话与账行 ──────────────────────────────

    @Test
    fun `判据②_回声版本不符_人话带版本对不上_账行version_且说明期望与实际`() {
        // 606①：版本对不上是自己的键（version），不再借宿 protocol（602 行 12 收口）。
        fx.protocolEcho = "2025-03-26"
        grant()
        val out = probe(config())
        assertEquals(PeerFailureCode.VERSION_MISMATCH, out.code)
        assertTrue("人话要说「版本对不上」：${out.human}", out.human.contains("版本对不上"))
        val row = PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.PROBE }
        assertEquals("version", row.optString("result"))
        assertTrue("账行要说明期望与实际：${row.optString("detail")}",
            row.optString("detail").contains(Peer570Fixture.MCP_PROTOCOL) && row.optString("detail").contains("2025-03-26"))
        val outcomeRow = LeaseLedger.replay().first { it.optString("kind") == "outcome" }
        assertEquals("version", outcomeRow.optString("result"))
    }

    @Test
    fun `判据②_超时_人话带超时_账行timeout`() {
        fx.initDelayMs = 3_000L
        grant()
        val out = probe(config(connectTimeoutMs = 500L))
        assertEquals(PeerFailureCode.TIMEOUT, out.code)
        assertTrue("人话要说「超时」：${out.human}", out.human.contains("超时"))
        assertEquals("timeout", PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.PROBE }.optString("result"))
    }

    @Test
    fun `判据②_需鉴权_人话带先授权_账行auth`() {
        fx.mcpNeedsAuth = true
        grant()
        val out = probe(config())
        assertEquals(PeerFailureCode.AUTH, out.code)
        assertTrue("人话要说「先授权」：${out.human}", out.human.contains("先验明身份"))
        assertEquals("auth", PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.PROBE }.optString("result"))
    }

    @Test
    fun `三类失败的人话互不相同`() {
        val a = PeerFailureText.human(PeerFailureCode.PROTOCOL, "X")
        val b = PeerFailureText.human(PeerFailureCode.TIMEOUT, "X")
        val c = PeerFailureText.human(PeerFailureCode.AUTH, "X")
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
    }

    // ── 判据⑤（外发即租约）：未批准 ⇒ 不发起 ───────────────────────────

    @Test
    fun `未批准_一个请求都不发_闸头落一行refused`() {
        val out = probe(config())
        assertEquals(PeerFailureCode.REFUSED, out.code)
        assertEquals("服务器必须一个请求都没收到", 0, fx.mcpMethods.size)
        assertEquals(0, fx.mcp.requestCount)
        val gateRow = PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.GATE }
        assertEquals("refused", gateRow.optString("result"))
        assertTrue(gateRow.optString("detail").isNotEmpty())
    }

    @Test
    fun `范围按host圈定_同机换端口换路径_批准仍生效`() {
        // 467 的 HOST 粒度：批准的是「这台机器」，不是某条路径/某个端口——换 path 不换 host 仍命中。
        grant(fx.mcpUrl(path = "/granted-path"))
        val out = probe(config(url = fx.mcpUrl(path = "/somewhere-else")))
        assertEquals(PeerFailureCode.OK, out.code)
        assertEquals(3, fx.mcpMethods.size)
    }

    @Test
    fun `范围host不匹配_即使有租约也OUT_OF_SCOPE拒不发起`() {
        // 给「别的机器」批的租约，探本机 ⇒ 范围这一维不命中（467 的范围维原样生效）。
        LeaseOutboundGate.grantProbeLease("http://other-machine.example:9/svc", McpPeerProbe.SESSION_MANUAL, "test-card", NOW)
        val out = probe(config(url = fx.mcpUrl()))
        assertEquals(PeerFailureCode.REFUSED, out.code)
        assertEquals(0, fx.mcpMethods.size)
        assertTrue(out.human.contains("范围") || out.human.contains("超出"))
    }

    @Test
    fun `同一张批准只能探一次_第二次不发起`() {
        val url = fx.mcpUrl()
        grant(url)
        assertEquals(PeerFailureCode.OK, probe(config()).code)
        assertEquals(PeerFailureCode.REFUSED, probe(config(), nowMs = NOW + 1).code)
        assertEquals(3, fx.mcpMethods.size) // 第一次的三件；第二次零请求
    }

    // ── 失败分类的纯件（不占网络） ──────────────────────────────────────

    @Test
    fun `四类失败的分类尺_DNS_端口_证书_协议各归各类`() {
        assertEquals(PeerFailureCode.DNS, PeerFailureClassify.ofThrowable(UnknownHostException("nx.example")))
        assertEquals(PeerFailureCode.PORT, PeerFailureClassify.ofThrowable(ConnectException("refused")))
        assertEquals(PeerFailureCode.TLS, PeerFailureClassify.ofThrowable(SSLHandshakeException("cert")))
        val mcpProtocol = com.example.zhengdao.baopu.mcp.McpException(
            com.example.zhengdao.baopu.mcp.McpFailure.PROTOCOL, "版本不符",
        )
        assertEquals(PeerFailureCode.PROTOCOL, PeerFailureClassify.ofThrowable(mcpProtocol))
        // 四句话互不相同（判据「不许统一网络错误」的钉子）。
        val four = listOf(PeerFailureCode.DNS, PeerFailureCode.PORT, PeerFailureCode.TLS, PeerFailureCode.PROTOCOL)
            .map { PeerFailureText.human(it, "X") }
        assertEquals(4, four.toSet().size)
    }

    @Test
    fun `协议版本常量与夹具一致_不认错版本`() {
        assertEquals(McpJsonRpc.PROTOCOL, Peer570Fixture.MCP_PROTOCOL)
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
