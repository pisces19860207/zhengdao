// 第五百八十四单 · 服务端核心单测：五闸一条链（停机→鉴权→路由→白名单→租约→执行件）。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.authz.LeaseOffer
import com.example.zhengdao.baopu.authz.LeaseRules
import com.example.zhengdao.baopu.authz.LeaseScope
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.mcp.McpJsonRpc
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpServerCoreTest {

    companion object {
        const val NOW = 1_760_000_000_000L
        lateinit var TOKEN: String
    }

    private lateinit var vault: InMemoryMcpVault
    private val runnerCalls = mutableListOf<String>()
    private var halted = false

    /** 假白名单：一件 R0 只读（有必填参数）＋ 一件 R0 只读（无参数）。 */
    private fun whitelist(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "read_file",
            description = "读工作区文件（假件）",
            inputSchema = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put("path", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray(listOf("path")))
                .put("additionalProperties", false),
            permission = ToolPermission.FREE,
            group = ToolGroup.CORE,
            riskTier = "R0",
            readOnly = true,
            executor = { ToolOutcome.Ok("stub") },
        ),
        ToolSpec(
            name = "governance_state",
            description = "读治理账（假件）",
            inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()),
            permission = ToolPermission.FREE,
            group = ToolGroup.FILES,
            riskTier = "R0",
            readOnly = true,
            executor = { ToolOutcome.Ok("stub") },
        ),
    )

    @Before
    fun wire() {
        LeaseStore.resetForTest()
        McpAuditLedger.resetForTest()
        halted = false
        runnerCalls.clear()
        vault = InMemoryMcpVault()
        TOKEN = vault.ensureToken()
    }

    @After
    fun clean() {
        LeaseStore.resetForTest()
        McpAuditLedger.resetForTest()
    }

    private fun core(): McpServerCore = McpServerCore(
        vault = vault,
        runner = { spec, _, opId ->
            runnerCalls.add("${spec.name}@$opId")
            McpToolRunner.Outcome("ran:${spec.name}", isError = false)
        },
        haltProbe = { halted },
        serverVersion = "test-1",
        whitelist = { whitelist() },
        nowMs = { NOW },
    )

    private fun req(
        method: String = "POST",
        path: String = "/mcp",
        auth: String? = "Bearer $TOKEN",
        body: String = "",
    ): McpServerTransport.Request {
        val headers = LinkedHashMap<String, String>()
        auth?.let { headers["authorization"] = it }
        headers["content-length"] = body.toByteArray(StandardCharsets.UTF_8).size.toString()
        return McpServerTransport.Request(method, path, headers, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun rpcBody(id: Any?, method: String, params: JSONObject? = null): String {
        val o = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("method", method)
        params?.let { o.put("params", it) }
        return o.toString()
    }

    private fun callTool(name: String, args: JSONObject = JSONObject(), token: String = TOKEN): Pair<Int, JSONObject> {
        val resp = core().handle(
            req(
                body = rpcBody(7, "tools/call", JSONObject().put("name", name).put("arguments", args)),
                auth = "Bearer $token",
            ),
        )
        return resp.status to JSONObject(resp.body)
    }

    // ── 判据②：错 token ⇒ 401 ＋ 拒绝账 ──────────────────────────────────

    @Test
    fun `错token 401并落拒绝账`() {
        val (status, body) = callTool("read_file", token = TOKEN.dropLast(1) + "X")
        assertEquals(401, status)
        assertEquals("unauthorized", body.optString("error"))
        val rows = McpAuditLedger.replay()
        assertTrue("必须有 auth_fail 行", rows.any { it.optString("ev") == McpAuditLedger.EV_AUTH_FAIL && it.optString("reason") == "bad_token" })
        rows.forEach { row -> assertFalse("账行不许有 token：$row", row.toString().contains(TOKEN)) }
    }

    @Test
    fun `缺token 401并落拒绝账`() {
        val resp = core().handle(req(auth = null, body = rpcBody(1, "initialize")))
        assertEquals(401, resp.status)
        assertTrue(
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_AUTH_FAIL && it.optString("reason") == "missing_token" },
        )
    }

    @Test
    fun `鉴权先于路由：错token的GET也401`() {
        val resp = core().handle(req(method = "GET", auth = "Bearer wrong", body = ""))
        assertEquals(401, resp.status)
    }

    // ── 判据④的兜底：停机期 503（端口关闭是主判据，这格防"慢一拍"）────────

    @Test
    fun `停机期一切请求503`() {
        halted = true
        val resp = core().handle(req(body = rpcBody(1, "initialize")))
        assertEquals(503, resp.status)
        assertTrue(resp.body.contains("server_halted"))
    }

    // ── 判据①：initialize + tools/list ─────────────────────────────────

    @Test
    fun `initialize 报协议版本与serverInfo`() {
        val resp = core().handle(req(body = rpcBody(1, "initialize")))
        assertEquals(200, resp.status)
        val result = JSONObject(resp.body).optJSONObject("result")!!
        assertEquals(McpJsonRpc.PROTOCOL, result.optString("protocolVersion"))
        assertEquals(McpServerCore.SERVER_NAME, result.optJSONObject("serverInfo")!!.optString("name"))
        assertEquals("test-1", result.optJSONObject("serverInfo")!!.optString("version"))
        assertFalse("不做 listChanged 推送（无会话长连）", result.optJSONObject("capabilities")!!.optJSONObject("tools")!!.optBoolean("listChanged"))
    }

    @Test
    fun `tools_list 只出白名单并标 readOnlyHint`() {
        val resp = core().handle(req(body = rpcBody(2, "tools/list")))
        assertEquals(200, resp.status)
        val tools = JSONObject(resp.body).optJSONObject("result")!!.optJSONArray("tools")!!
        val names = (0 until tools.length()).map { tools.optJSONObject(it)!!.optString("name") }
        assertEquals(listOf("read_file", "governance_state"), names)
        (0 until tools.length()).forEach { i ->
            assertTrue(tools.optJSONObject(i)!!.optJSONObject("annotations")!!.optBoolean("readOnlyHint"))
        }
    }

    // ── 判据③：非白名单 ⇒ 拒并记账 ──────────────────────────────────────

    @Test
    fun `非白名单工具被拒并落账`() {
        val (status, body) = callTool("write_file", args = JSONObject().put("path", "x"))
        assertEquals(200, status) // 协议层 200，拒绝走 JSON-RPC error
        val err = body.optJSONObject("error")!!
        assertEquals(McpServerRpc.Codes.SERVER_DENIED, err.optInt("code"))
        assertTrue(err.optString("message").contains("白名单"))
        assertFalse(err.optString("message").contains("path=x")) // 不回显参数
        assertTrue(
            McpAuditLedger.replay().any {
                it.optString("ev") == McpAuditLedger.EV_TOOL_REJECT && it.optString("tool") == "write_file"
            },
        )
        assertTrue("执行件不该被调", runnerCalls.isEmpty())
    }

    @Test
    fun `未知工具同样拒并落账`() {
        callTool("no_such_tool")
        assertTrue(
            McpAuditLedger.replay().any {
                it.optString("ev") == McpAuditLedger.EV_TOOL_REJECT && it.optString("tool") == "no_such_tool"
            },
        )
    }

    // ── 租约：每次调用必过；命中扣次；免卡类放行；账分得清 ────────────────

    @Test
    fun `无租约的R0只读走免卡放行并落账`() {
        val (status, body) = callTool("read_file", args = JSONObject().put("path", "a.txt"))
        assertEquals(200, status)
        assertFalse(body.optJSONObject("result")!!.optBoolean("isError"))
        assertEquals("ran:read_file", body.optJSONObject("result")!!.optJSONArray("content")!!.optJSONObject(0)!!.optString("text"))
        assertTrue(
            "调用账要标 lease=free（免卡类放行看得见）",
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_CALL && it.optString("lease") == "free" },
        )
        assertEquals("执行件被调一次", 1, runnerCalls.size)
        assertTrue(runnerCalls[0].startsWith("read_file@mcp-"))
    }

    @Test
    fun `租约命中真扣次：两发用尽第三发回免卡`() {
        val spec = whitelist()[0]
        val cls = LeaseRules.classOf(spec)
        LeaseStore.grant(
            LeaseOffer(cls = cls, scope = LeaseScope(LeaseScope.Kind.WORKSPACE, ""), cap = 2, ttlMs = 3_600_000),
            sessionId = McpCallLease.MCP_SESSION_ID,
            cardId = "card-test",
            nowMs = NOW,
        )
        val outcomes = (1..3).map {
            val (status, body) = callTool("read_file", args = JSONObject().put("path", "a.txt"))
            assertEquals(200, status)
            body.optJSONObject("result")!!.optBoolean("isError") to body
        }
        assertFalse(outcomes[0].first)
        assertFalse(outcomes[1].first)
        assertFalse(outcomes[2].first)
        val rows = McpAuditLedger.replay().filter { it.optString("ev") == McpAuditLedger.EV_CALL && it.optString("tool") == "read_file" }
        assertEquals("三次调用三行账（一次调用只落一行）", 3, rows.size)
        assertEquals("前两发租约命中、第三发回免卡", listOf(false, false, true), rows.map { it.optString("lease") == "free" })
        assertEquals("前两发用的是同一张租约", rows[0].optString("lease"), rows[1].optString("lease"))
        assertTrue("账上的租约 id 要真（ls- 前缀）", rows[0].optString("lease").startsWith("ls-"))
        assertEquals("全部 outcome=ok", rows.map { "ok" }, rows.map { it.optString("outcome") })
        assertEquals("执行件被调三次", 3, runnerCalls.size)
    }

    // ── 参数形状：schema 校验与注册表同一把尺 ────────────────────────────

    @Test
    fun `缺必填参数回 INVALID_PARAMS 不进租约账`() {
        val (status, body) = callTool("read_file", args = JSONObject())
        assertEquals(200, status)
        assertEquals(McpServerRpc.Codes.INVALID_PARAMS, body.optJSONObject("error")!!.optInt("code"))
        assertTrue(
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_CALL && it.optString("outcome") == "invalid_params" },
        )
        assertTrue("租约未扣（校验在租约前）", runnerCalls.isEmpty())
    }

    // ── 信封层：通知/坏包/未知方法/路径 ─────────────────────────────────

    @Test
    fun `notifications-initialized 202空体`() {
        val resp = core().handle(req(body = rpcBody(null, "notifications/initialized")))
        assertEquals(202, resp.status)
        assertEquals("", resp.body)
    }

    @Test
    fun `坏JSON 400`() {
        val resp = core().handle(req(body = "this is not json"))
        assertEquals(400, resp.status)
    }

    @Test
    fun `未知方法 METHOD_NOT_FOUND 并落账`() {
        val resp = core().handle(req(body = rpcBody(3, "resources/list")))
        assertEquals(200, resp.status)
        assertEquals(McpServerRpc.Codes.METHOD_NOT_FOUND, JSONObject(resp.body).optJSONObject("error")!!.optInt("code"))
        assertTrue(
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_METHOD && it.optString("method") == "resources/list" },
        )
    }

    @Test
    fun `只有 _mcp 一个端点 且 GET 405`() {
        assertEquals(404, core().handle(req(path = "/other", body = rpcBody(1, "ping"))).status)
        assertEquals(405, core().handle(req(method = "GET", body = "")).status)
    }

    @Test
    fun `ping 200空结果`() {
        val resp = core().handle(req(body = rpcBody(9, "ping")))
        assertEquals(200, resp.status)
        assertTrue(JSONObject(resp.body).has("result"))
    }
}
