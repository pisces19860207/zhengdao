// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零二批 · MCP 自配五件的门禁。
//
// 钉的判据（派工单 §判据）：
// · 给 URL 能连上并列工具（runAdd 全流程：登记 → 连接 → 新增 N 个工具）；
// · 401 自动授权**只弹一次**（台账 SHOWN/DENIED 两个状态都钉）；
// · `mcp_set_token` 后**任何输出无明文**（回执 + 全部日志 grep 断言）；
// · 启动自动连一台坏的**只影响它自己**（逐台隔离 + 停排）；
// · 工具清单刷新（重连报新增/移除差集）。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import okhttp3.OkHttpClient
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Collections

class McpSelfServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val popupUrls = Collections.synchronizedList(mutableListOf<String>())

    /** FakeSession 的工具清单（刷新用例改它）；serverId 由 FakeSession 按真实 cfg.id 重打。 */
    private val remoteTools = Collections.synchronizedList(
        mutableListOf(descriptor("read_thing", "srv", readOnly = true), descriptor("write_thing", "srv")),
    )

    /** 让 clientFactory 对指定 id 抛（"一台坏的"用例）。 */
    private val brokenIds = Collections.synchronizedSet(mutableSetOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        base = "http://127.0.0.1:${server.port}"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = handle(req)
        }
        remoteTools.clear()
        // serverId 占位：FakeSession 在 clientFactory 里按真实 cfg.id 重打（wire 名用它的 id）
        remoteTools += descriptor("read_thing", "srv", readOnly = true)
        remoteTools += descriptor("write_thing", "srv")
        brokenIds.clear()
        McpAutoConnect.resetForTest()
    }

    private fun config(id: String) = McpServerConfig(
        id = id,
        name = "假服务器",
        url = "$base/mcp",
        connectTimeoutMs = 5_000,
        callTimeoutMs = 5_000,
    )

    @After
    fun tearDown() {
        McpSelfService.env = null
        McpSelfService.popupLedger.forgetAllForTest()
        McpAutoConnect.resetForTest()
        runCatching { server.close() }
    }

    // ── 假 OAuth 元数据（needsAuth 用例的 begin() 要发现+注册） ─────────────

    private fun handle(req: RecordedRequest): MockResponse {
        val path = req.target.orEmpty().substringBefore('?')
        return when (path) {
            "/.well-known/oauth-protected-resource" -> json(
                JSONObject()
                    .put("resource", "$base/mcp")
                    .put("authorization_servers", JSONArray().put(base)),
            )
            "/.well-known/oauth-authorization-server" -> json(
                JSONObject()
                    .put("issuer", base)
                    .put("authorization_endpoint", "$base/authorize")
                    .put("token_endpoint", "$base/token")
                    .put("registration_endpoint", "$base/register")
                    .put("code_challenge_methods_supported", JSONArray().put("S256"))
                    .put("token_endpoint_auth_methods_supported", JSONArray().put("none")),
            )
            "/register" -> json(
                JSONObject()
                    .put("client_id", "client-abc")
                    .put("token_endpoint_auth_method", "none"),
            )
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun json(body: JSONObject): MockResponse = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body.toString()).build()

    // ── 被测装配 ─────────────────────────────────────────────────────────

    private class FakeSession(
        private val serverId: String,
        private val tools: () -> List<McpToolDescriptor>,
    ) : McpSession {
        var connectCalls = 0
        var listCalls = 0
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL

        override fun connect() {
            connectCalls++
        }

        override fun listTools(): List<McpToolDescriptor> {
            listCalls++
            return tools()
        }

        override fun callTool(remoteName: String, arguments: JSONObject) =
            McpCallResult("远程结果", false)

        override fun close() = Unit
    }

    private fun descriptor(name: String, serverId: String, readOnly: Boolean = false) = McpToolDescriptor(
        serverId = serverId,
        serverName = "假服务器",
        name = name,
        description = "工具 $name",
        inputSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
            .put("additionalProperties", false),
        readOnly = readOnly,
    )

    /** 造好五件套（source + flow + env）；返回 (source, flow, vault)。 */
    private fun wire(): Triple<McpToolSource, McpAuthFlow, McpTokenVault> {
        val vault = McpTokenVault(InMemoryMcpSecretStore())
        val flow = McpAuthFlow(
            vault = vault,
            oauth = McpOAuthClient(OkHttpClient(), log = { logs += it }),
            now = { 1_000_000L },
            log = { logs += it },
        )
        val reg = ToolRegistry()
        val source = McpToolSource(
            reg,
            auth = flow,
            clientFactory = { cfg ->
                if (cfg.id in brokenIds) error("connection refused")
                FakeSession(cfg.id) {
                    remoteTools.toList().map {
                        it.copy(serverId = cfg.id)
                    }
                }
            },
            log = { logs += it },
            now = { 1_000_000L },
        )
        McpSelfService.env = McpSelfService.Env(
            source = { source },
            auth = { flow },
            browser = { url -> popupUrls.add(url); true },
            log = { logs += it },
            now = { 1_000_000L },
        )
        return Triple(source, flow, vault)
    }

    private fun okText(outcome: ToolOutcome): String =
        (outcome as? ToolOutcome.Ok)?.text ?: error("期待 Ok，实到 $outcome")

    private fun failText(outcome: ToolOutcome): String =
        (outcome as? ToolOutcome.Failed)?.error ?: error("期待 Failed，实到 $outcome")

    private fun assertNull(actual: Any?) = org.junit.Assert.assertNull(actual)

    private fun allLogs(): String = logs.joinToString("\n")

    // ── 硬边界：mcp_set_token 无明文 ─────────────────────────────────────

    @Test
    fun `set_token_写保险柜_任何输出无明文`() {
        val (source, flow, vault) = wire()
        source.addServer(config("srv"))
        val token = "sekret-pat-value-9f2"
        val outcome = McpSelfService.runSetToken("srv", token)
        assertTrue(okText(outcome).contains("保险柜"))
        // 判据 grep 断言：回执 + 全部日志（含 OAuth/ vault 的行）都不出现明文
        assertFalse(okText(outcome).contains(token))
        assertFalse(allLogs().contains(token))
        // 真的进了保险柜（受众绑定 resource），且模型能连（有 token 就不再走授权）
        assertNotNull(vault.load("srv", "$base/mcp"))
        val connect = source.connect("srv")
        assertTrue("有令牌后连接应直接成功：$connect", connect is McpConnectOutcome.Ok)
        assertEquals("set_token 之后 flow 不该去开授权页", 0, popupUrls.size)
    }

    @Test
    fun `set_token_形状不合法_拒绝且不写`() {
        val (source, flow, vault) = wire()
        source.addServer(config("srv"))
        val outcome = McpSelfService.runSetToken("srv", "  ")
        assertTrue(failText(outcome).contains("不合法"))
        assertNull(vault.load("srv", "$base/mcp"))
        assertFalse("没写就不该有「已导入手动令牌」的账", allLogs().contains("已导入手动令牌"))
    }

    @Test
    fun `set_token_没有这台服务器_点名报错`() {
        wire()
        val outcome = McpSelfService.runSetToken("nope", "x")
        assertTrue(failText(outcome).contains("没有这个 MCP server"))
    }

    // ── 判据：给 URL 能连上并列工具 ─────────────────────────────────────

    @Test
    fun `add_server_登记即进自动连名单_需授权时自动弹页`() {
        val (source, flow, _) = wire()
        val outcome = McpSelfService.runAdd("$base/mcp", "")
        // 假服务器带 OAuth 元数据、无 token ⇒ runAdd 转 NeedsAuth（自动 B 弹一次）
        assertTrue(okText(outcome).contains("已自动打开授权页"))
        assertEquals(1, popupUrls.size)
        val id = source.serverIds().single()
        assertTrue(source.config(id)!!.autoConnect)
        // 补上令牌再连 ⇒ 这才走完整连接并列出工具（"给 URL 能连上并列工具"的闭环）
        flow.importToken(source.config(id)!!, "at-add")
        val connect = McpSelfService.runConnect(id)
        val text = okText(connect)
        assertTrue("回执要有新增数：$text", text.contains("新增 2 个工具"))
        assertTrue("回执要有传输：$text", text.contains("fake"))
        assertTrue(source.registeredNames(id).contains("mcp_${id}_read_thing"))
    }

    @Test
    fun `add_server_重复地址_拒绝不覆盖`() {
        val (source, _) = wire()
        McpSelfService.runAdd("$base/mcp", "")
        val again = McpSelfService.runAdd("$base/mcp", "")
        assertTrue(failText(again).contains("已经登记过"))
        assertEquals(1, source.serverIds().size)
    }

    @Test
    fun `add_server_地址不许可_拒绝`() {
        wire()
        val outcome = McpSelfService.runAdd("http://example.com/mcp", "")
        assertTrue(failText(outcome).contains("不许可") || failText(outcome).contains("明文"))
    }

    // ── 判据：401 自动授权只弹一次 ──────────────────────────────────────

    @Test
    fun `needsAuth_自动弹页_只弹一次_被拒后不再弹`() {
        val (source, _) = wire()
        // 无 token ⇒ connect 转 NeedsAuth（授权页由工具自动开）
        val first = McpSelfService.runAdd("$base/mcp", "")
        assertTrue(okText(first).contains("已自动打开授权页"))
        assertEquals("第一次弹", 1, popupUrls.size)
        // 第二次连接：SHOWN ⇒ 只报话不弹
        val id = source.serverIds().single()
        val second = McpSelfService.runConnect(id)
        assertTrue(okText(second).contains("不重复弹"))
        assertEquals("第二次不许再弹", 1, popupUrls.size)
        // 被拒 ⇒ DENIED ⇒ 明确失败，永不弹
        McpSelfService.popupLedger.markDenied(id)
        val third = McpSelfService.runConnect(id)
        assertTrue(failText(third).contains("被拒绝过"))
        assertEquals("被拒后不许弹", 1, popupUrls.size)
    }

    @Test
    fun `授权闭环后_台账清零_过期重授还能再弹`() {
        val (source, _) = wire()
        McpSelfService.runAdd("$base/mcp", "")
        val id = source.serverIds().single()
        assertEquals(1, popupUrls.size)
        // episode 闭环（续连成功/后台换到 token 都会走 forget）⇒ 下次 NeedsAuth 是新 episode
        McpSelfService.popupLedger.forget(id)
        val again = McpSelfService.runConnect(id)
        assertTrue(okText(again).contains("已自动打开授权页"))
        assertEquals(2, popupUrls.size)
    }

    // ── 判据：启动自动连一台坏的只影响它自己 ─────────────────────────────

    @Test
    fun `自动连_坏的那台只影响自己_且按退避停排`() {
        val (source, flow, _) = wire()
        val good = source.addServer(config("good").copy(autoConnect = true))
        assertTrue(good)
        source.addServer(config("bad").copy(autoConnect = true))
        brokenIds.add("bad")
        // 两台都用手动令牌免授权（否则 bad 会先撞授权分支而不是真炸）
        flow.importToken(source.config("good")!!, "at-good")
        flow.importToken(source.config("bad")!!, "at-bad")

        McpAutoConnect.pass("启动")
        assertEquals("好的连上", McpServerStatus.CONNECTED, source.status("good"))
        assertEquals("坏的保持离线", McpServerStatus.ERROR, source.status("bad"))
        val badLines = logs.filter { it.contains("自动连(") && it.contains("server=bad") }
        assertEquals("坏的一台自动连只落一行", 1, badLines.size)
        assertTrue(allLogs().contains("server=good"))
        // 退避：立刻再 pass 不会重试 bad（没到点）
        val before = logs.size
        McpAutoConnect.pass("回前台")
        assertTrue("到点前不许重复尝试", logs.drop(before).none { it.contains("server=bad") })
    }

    @Test
    fun `自动连_需要授权_后台不弹页_停排等授权`() {
        val (source, _) = wire()
        source.addServer(config("srv").copy(autoConnect = true))
        McpAutoConnect.pass("启动")
        assertEquals("后台绝不弹授权页", 0, popupUrls.size)
        assertTrue(
            "要有诊断行",
            allLogs().contains("需要授权"),
        )
        val before = logs.size
        McpAutoConnect.pass("回前台")
        assertTrue("停排后不再尝试", logs.drop(before).isEmpty())
    }

    @Test
    fun `自动连_空名单_零日志`() {
        wire()
        val before = logs.size
        McpAutoConnect.pass("启动")
        assertEquals(before, logs.size)
    }

    // ── 判据：工具清单刷新（重连差集） ───────────────────────────────────

    @Test
    fun `connect_已连接时刷新_报新增与移除`() {
        val (source, flow, _) = wire()
        source.addServer(config("srv"))
        flow.importToken(source.config("srv")!!, "at-r")
        source.connect("srv")
        assertEquals(2, source.toolCount("srv"))
        // 服务器端：加一个、去掉一个
        remoteTools.clear()
        remoteTools += descriptor("read_thing", "srv", readOnly = true)
        remoteTools += descriptor("write_thing", "srv")
        remoteTools += descriptor("new_thing", "srv", readOnly = true)
        remoteTools.removeIf { it.name == "write_thing" }

        val outcome = McpSelfService.runConnect("srv")
        val text = okText(outcome)
        assertTrue("回执要报新增：$text", text.contains("新增 1 个工具"))
        assertTrue("回执要报移除：$text", text.contains("移除 1 个工具"))
        val id = source.serverIds().single()
        val names = source.registeredNames(id)
        assertTrue(names.contains("mcp_${id}_new_thing"))
        assertFalse(names.contains("mcp_${id}_write_thing"))
    }

    // ── 断开：清令牌＋关自动连＋注销工具 ─────────────────────────────────

    @Test
    fun `disconnect_清令牌_关自动连_注销工具`() {
        val (source, flow, vault) = wire()
        source.addServer(config("srv").copy(autoConnect = true))
        source.connect("srv")
        val id = source.serverIds().single()
        flow.importToken(source.config(id)!!, "at-x")
        val outcome = McpSelfService.runDisconnect(id)
        assertTrue(okText(outcome).contains("令牌已从保险柜删除"))
        assertFalse(source.config(id)!!.autoConnect)
        assertTrue("令牌应被清", vault.load(id, "$base/mcp") == null)
        assertTrue("工具应被注销", source.registeredNames(id).isEmpty())
    }

    // ── mcp_list：只报状态，永不报值 ─────────────────────────────────────

    @Test
    fun `mcp_list_状态列齐_无令牌值_空态人话`() {
        val (source, flow, _) = wire()
        val empty = McpSelfService.renderList(source)
        assertTrue(empty.contains("还没有登记任何 MCP 服务器"))

        source.addServer(config("srv"))
        flow.importToken(source.config("srv")!!, "sekret-list-token")
        source.connect("srv")
        val text = McpSelfService.renderList(source)
        assertTrue(text.contains("有效（有令牌）"))
        assertTrue(text.contains("自动连=关"))
        assertTrue(text.contains(McpUrlRules.redact("$base/mcp")))
        assertFalse("清单永不出现令牌明文", text.contains("sekret-list-token"))
    }

    // ── 粘贴即解析（自动 C） ────────────────────────────────────────────

    @Test
    fun `paste_单URL解析_名称与传输候选`() {
        val p = McpPasteRule.singleUrl("  $base/mcp 。")
        assertNotNull(p)
        assertEquals("$base/mcp", p!!.url)
        assertEquals("127.0.0.1", p.name)
        assertTrue(p.transportHint.contains("Streamable HTTP"))
        assertTrue(p.invalid == null)
    }

    @Test
    fun `paste_sse路径_候选传输给SSE`() {
        val p = McpPasteRule.singleUrl("https://example.com/api/sse")
        assertNotNull(p)
        assertTrue(p!!.transportHint.contains("旧式 SSE"))
    }

    @Test
    fun `paste_非单URL_不解析`() {
        assertNull(McpPasteRule.singleUrl("看这个 $base/mcp"))
        assertNull(McpPasteRule.singleUrl("$base/mcp\n$base/mcp2"))
        assertNull(McpPasteRule.singleUrl("ftp://example.com"))
        assertNull(McpPasteRule.singleUrl(""))
    }

    @Test
    fun `paste_形似URL但不许可_给原因`() {
        val p = McpPasteRule.singleUrl("http://example.com/mcp")
        assertNotNull(p)
        assertNotNull(p!!.invalid)
    }

    // ── 配置落盘：autoConnect 进白名单字段 ───────────────────────────────

    @Test
    fun `store_autoConnect_往返`() {
        val memory = object : McpServerStorage {
            var raw: String? = null
            override fun read(): String? = raw
            override fun write(json: String) {
                raw = json
            }
        }
        val store = McpServerStore(memory)
        val cfg = config("srv").copy(autoConnect = true)
        assertTrue(store.save(listOf(cfg)))
        val loaded = store.load().single()
        assertTrue(loaded.autoConnect)
        assertEquals(cfg.id, loaded.id)
    }

    // ── 装配 ─────────────────────────────────────────────────────────────
}
