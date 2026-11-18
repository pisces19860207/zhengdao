// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百零七单 · 探测接线（G-705-2）＋ 档位文案（G-705-1 裁定）的判据测试。
//
// 覆盖（派工单 707 判据①③④⑤；判据②在 McpToolSource 侧本文件钉死负例）：
// ① 三态纯函数逐态：可用 / 陈旧（探测失败）/ 陈旧（清单过期）/ 未探测；
//    **负例**：探测失败 ⇒ 绝不读成「可用」（夹具停了还显示可用＝红）；
// ② 陈旧态调工具 ⇒ 明确「不发请求」且一个字节都不发（与 705 负例③同口径）；
// ③ 接线真跑：面板装配授租约→探成功→清单落盘＋两本账各有行；夹具停掉再探
//    ⇒ 失败如实回、**登记不删**（原清单行原样留着，就是要给人看最后成功时间）；
// ④ 档位文案断言字符串在场＋**放行判定零改动**（源码断言：AskTierRules 放权分支原文逐字）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.mcp.McpCallResult
import com.example.zhengdao.baopu.mcp.McpJsonRpc
import com.example.zhengdao.baopu.mcp.McpSchema
import com.example.zhengdao.baopu.mcp.McpServerConfig
import com.example.zhengdao.baopu.mcp.McpSession
import com.example.zhengdao.baopu.mcp.McpToolDescriptor
import com.example.zhengdao.baopu.mcp.McpToolSource
import com.example.zhengdao.testkit.Peer570Fixture
import com.example.zhengdao.testkit.TempSandboxPool
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class McpPeerProbeWireTest {

    private lateinit var fx: Peer570Fixture
    private lateinit var root: File

    @Before
    fun setUp() {
        fx = Peer570Fixture()
        fx.start()
        root = TempSandboxPool.create("peer707")
        // 与 548 同一房型：落点装上、收场清干净（不污染别的测试）。
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

    private fun config(url: String = fx.mcpUrl()): McpServerConfig = McpServerConfig(
        id = "fx707",
        name = "707台",
        url = url,
        connectTimeoutMs = 5_000L,
        callTimeoutMs = 5_000L,
    )

    // ── 判据①：三态纯函数逐态 ────────────────────────────────────────

    @Test
    fun `判据①_未探测_三态是未知`() {
        val r = peerPanelReading(entry = null, lastProbeOk = null, nowMs = NOW)
        assertEquals(PeerPanelState.UNKNOWN, r.state)
        assertNull(r.lastOkAtMs)
    }

    @Test
    fun `判据①_探过且清单新鲜_三态是可用`() {
        val entry = entryOk(probedAtMs = NOW)
        val r = peerPanelReading(entry, lastProbeOk = true, nowMs = NOW + 1_000)
        assertEquals(PeerPanelState.OK, r.state)
        assertEquals(NOW, r.lastOkAtMs)
        assertEquals(2, r.toolCount)
    }

    @Test
    fun `判据①负例_探测失败_当场陈旧_带最后成功时间_绝不显示可用`() {
        // 清单里还留着一条新鲜 OK 行（登记不删），但刚刚那次探测失败 ⇒ 必须陈旧。
        val entry = entryOk(probedAtMs = NOW)
        val r = peerPanelReading(entry, lastProbeOk = false, nowMs = NOW + 1_000)
        assertEquals(PeerPanelState.STALE, r.state)
        assertEquals("陈旧时亮出来的必须是最后一次成功时间", NOW, r.lastOkAtMs)
        assertFalseLike(r)
        assertEquals("探测失败不算 TTL 过期", false, r.staleByTtl)
    }

    @Test
    fun `判据①_清单超过24小时没刷新_也标陈旧`() {
        val entry = entryOk(probedAtMs = NOW)
        val r = peerPanelReading(entry, lastProbeOk = true, nowMs = NOW + PeerManifestEntry.DEFAULT_TTL_MS)
        assertEquals(PeerPanelState.STALE, r.state)
        assertEquals(NOW, r.lastOkAtMs)
        assertEquals(true, r.staleByTtl)
    }

    private fun assertFalseLike(r: PeerPanelReading) {
        assertTrue("陈旧不许读成可用（判据①负例：仍显示可用＝红）", r.state != PeerPanelState.OK)
    }

    private fun entryOk(probedAtMs: Long): PeerManifestEntry = PeerManifestEntry(
        agentId = "ag-x",
        serverId = "fx707",
        name = "707台",
        endpoint = fx.mcpUrl(),
        protocolVersion = "2025-06-18",
        mode = "streamable-http",
        capabilities = listOf("tools"),
        tools = listOf(
            PeerToolFp("read_note", "读", "a".repeat(64)),
            PeerToolFp("write_note", "写", "b".repeat(64)),
        ),
        probedAtMs = probedAtMs,
        probeResult = PeerFailureCode.OK.wire,
    )

    // ── 判据②：陈旧态调工具 ⇒ 明确不可用且不发请求 ────────────────────

    @Test
    fun `判据②_探测失败后调工具_明确拒_零请求_探测成功后恢复`() {
        val fake = FakeSession(listOf(descriptor("read_thing")))
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config(url = "http://127.0.0.1:1/mcp"))
        source.connect("fx707")
        val spec: ToolSpec = reg.find("mcp_fx707_read_thing")!!
        // 先批一次（走 Guard 的批准口），调用是通的。
        val pipe = pipeline(reg)
        assertTrue(pipe.executeApproved(ToolCall("c-1", spec.name, JSONObject()), { _, _ -> null }) is GuardPipeline.Result.Done)
        assertEquals(1, fake.callCalls)

        // 面板探测失败 ⇒ 陈旧回灌：之后调用明确拒、一个字节都不发。
        source.notePeerProbe("fx707", ok = false)
        val stale = spec.executor(JSONObject())
        assertTrue("陈旧态必须拒（不许假装可用）", stale is ToolOutcome.Rejected)
        assertTrue(
            "拒的话里要明说「不发请求」：${(stale as ToolOutcome.Rejected).reason}",
            stale.reason.contains("不发请求"),
        )
        assertEquals("陈旧态零请求（705 负例③同口径）", 1, fake.callCalls)

        // 探测成功（或重连成功）⇒ 恢复。
        source.notePeerProbe("fx707", ok = true)
        assertTrue(spec.executor(JSONObject()) is ToolOutcome.Ok)
        assertEquals(2, fake.callCalls)
    }

    @Test
    fun `判据②_重连成功同样清掉陈旧`() {
        val fake = FakeSession(listOf(descriptor("read_thing")))
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config(url = "http://127.0.0.1:1/mcp"))
        source.connect("fx707")
        // 先批一次（不然拒的是「没授权」，测不到陈旧分支）。
        val pipe = pipeline(reg)
        val spec = reg.find("mcp_fx707_read_thing")!!
        assertTrue(pipe.executeApproved(ToolCall("c-1", spec.name, JSONObject()), { _, _ -> null }) is GuardPipeline.Result.Done)
        source.notePeerProbe("fx707", ok = false)
        assertTrue(spec.executor(JSONObject()) is ToolOutcome.Rejected)
        assertEquals(1, fake.callCalls)
        // 连接成功 ⇒ 陈旧标记清掉（面板点「连接」是「回来了」的当下证据）。
        source.connect("fx707")
        assertTrue(spec.executor(JSONObject()) is ToolOutcome.Ok)
        assertEquals(2, fake.callCalls)
    }

    private fun descriptor(name: String): McpToolDescriptor {
        val raw = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false)
        return McpToolDescriptor(
            serverId = "fx707",
            serverName = "707台",
            name = name,
            description = "工具 $name",
            inputSchema = McpSchema.normalizeSchema(name, raw),
            readOnly = true,
        )
    }

    private fun pipeline(reg: ToolRegistry): GuardPipeline = GuardPipeline(
        registry = reg,
        workspaceRoot = { File(".") },
        rawDir = { null },
        runner = ToolRunner(java.util.concurrent.Executors.newSingleThreadExecutor()),
        log = {},
    )

    private class FakeSession(
        private val tools: List<McpToolDescriptor>,
    ) : McpSession {
        var callCalls = 0
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL
        override fun connect() = Unit
        override fun listTools(): List<McpToolDescriptor> = tools
        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult {
            callCalls++
            return McpCallResult("远程结果", false)
        }
        override fun close() = Unit
    }

    // ── 判据③：接线真跑（授租约 → 探测 → 落盘 → 两本账）＋ 夹具停掉重探 ──

    @Test
    fun `判据③_面板探测授租约_探成功_清单落盘_两本账都有行`() {
        val cfg = config()
        val p = McpPeerProbeWiring.probeFromPanel(cfg, root, tokenProvider = null, nowMs = NOW)
        assertTrue("探测应成功：${p.human}", p.ok)
        val entry = McpPeersManifest.load(root)[AgentIds.stableId(cfg.url)]
        assertNotNull("清单必须落盘（.mcp-peers.json）", entry)
        assertEquals(PeerFailureCode.OK.wire, entry!!.probeResult)
        assertEquals(NOW, entry.probedAtMs)
        assertTrue(
            "外发即租约：租约账要有 peer/probe 的 grant 行",
            LeaseLedger.replay().any { it.optString("kind") == "grant" && it.optString("cls") == "peer/probe" },
        )
        assertTrue(
            "事件账要有 probe 行",
            PeerEventsLedger.replay().any { it.optString("kind") == "probe" && it.optString("result") == "ok" },
        )
    }

    @Test
    fun `判据①真跑_夹具停掉再探_失败如实回_登记不删`() {
        val cfg = config()
        assertTrue(McpPeerProbeWiring.probeFromPanel(cfg, root, null, NOW).ok)
        val before = McpPeersManifest.load(root)[AgentIds.stableId(cfg.url)]!!
        fx.close() // 把夹具服务停掉

        val p2 = McpPeerProbeWiring.probeFromPanel(cfg, root, null, NOW + 60_000)
        assertTrue("停掉后探测必须失败（负例：仍成功＝红）", !p2.ok)
        val after = McpPeersManifest.load(root)[AgentIds.stableId(cfg.url)]
        assertNotNull("探测失败不删登记", after)
        assertEquals("登记行原样（probedAt 不动＝最后成功时间可读）", before.toJson().toString(), after!!.toJson().toString())
        // 事件账上失败一行也要看得见（不静默）。
        assertTrue(
            PeerEventsLedger.replay().any {
                it.optString("kind") == "probe" && it.optString("result") != "ok" && it.optLong("at") == NOW + 60_000
            },
        )
    }

    // ── 判据③④：源码断言（生产调用方在场；放行判定零改动；文案锚点在场） ──

    @Test
    fun `判据③源码断言_McpPeerProbe有非测试生产调用点`() {
        val wiring = repoFile("src/main/java/com/example/zhengdao/baopu/peer/McpPeerProbeWiring.kt").readText()
        assertTrue("接线要真调 McpPeerProbe", wiring.contains("McpPeerProbe("))
        assertTrue("面板探测要先授探针租约（外发即租约）", wiring.contains("grantProbeLease"))
        val source = repoFile("src/main/java/com/example/zhengdao/baopu/mcp/McpToolSource.kt").readText()
        assertTrue("探测收场要回灌 McpToolSource（陈旧 ⇒ 调用拒）", source.contains("notePeerProbe"))
        val screen = repoFile("src/main/java/com/example/zhengdao/ui/baopu/McpServerScreen.kt").readText()
        assertTrue("面板要经装配真调 548 探针（生产调用方）", screen.contains("McpPeerProbeWiring.probeFromPanel"))
        assertTrue("面板要有「探测」动作入口", screen.contains("probePeerServer"))
    }

    @Test
    fun `判据④_档位文案含放权档不弹卡与无租约_且放行判定零改动`() {
        val zh = repoFile("src/main/res/values/strings_health.xml").readText()
        assertTrue("设置页档位说明要有「放权档不弹卡」", zh.contains("放权档不弹卡"))
        assertTrue("设置页档位说明要有「无租约」", zh.contains("无租约"))
        assertTrue("要说清「不是租约坏了」", zh.contains("不是租约坏了"))
        val en = repoFile("src/main/res/values-en/strings_health.xml").readText()
        assertTrue("英文侧同句在场", en.contains("Delegated tier never shows a card") && en.contains("not a broken lease"))
        // 租约读数区（治理中心）同标注。
        val zone = repoFile("src/main/res/values/strings_baopu_governance_zone.xml").readText()
        assertTrue(zone.contains("gc_lease_delegated_note") && zone.contains("不是租约坏了"))
        val screen = repoFile("src/main/java/com/example/zhengdao/ui/baopu/GovernanceCenterScreen.kt").readText()
        assertTrue("治理中心要在放权档下显示这句说明", screen.contains("gc_lease_delegated_note"))
        // **放行判定零改动**：AskTierRules 的放权分支原文逐字在场（机制一个字没碰）。
        val gov = repoFile("src/main/java/com/example/zhengdao/baopu/agent/AskGovernance.kt").readText()
        assertTrue(
            "放权档放行分支原文必须逐字在场（707 只许改文案）",
            gov.contains("AskTierDecision(true, \"放权档：根内可回滚 R1/R2 不问（回收站/备份可回滚）\")"),
        )
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
