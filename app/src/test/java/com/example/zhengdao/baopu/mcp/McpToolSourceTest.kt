// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP v1：授权/注册/执行器闸/组可见性/断连/换表。
// 用假 McpSession 数请求次数：证明「未授权 0 请求」「连接不自动调用」。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonObjectOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class McpToolSourceTest {

    private val config = McpServerConfig(
        id = "srv",
        name = "演示 server",
        url = "http://127.0.0.1:1/mcp",
        callTimeoutMs = 2_000,
    )

    private class FakeSession(
        private val tools: List<McpToolDescriptor> = emptyList(),
        private val connectError: McpException? = null,
        private val listError: McpException? = null,
        private val callError: McpException? = null,
        private val callResult: McpCallResult = McpCallResult("远程结果", false),
    ) : McpSession {
        var connectCalls = 0
        var listCalls = 0
        var callCalls = 0
        var closed = false
        var lastArgs: JSONObject? = null
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL

        override fun connect() {
            connectCalls++
            connectError?.let { throw it }
        }

        override fun listTools(): List<McpToolDescriptor> {
            listCalls++
            listError?.let { throw it }
            return tools
        }

        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult {
            callCalls++
            lastArgs = JSONObject(arguments.toString())
            callError?.let { throw it }
            return callResult
        }

        override fun close() {
            closed = true
        }
    }

    private fun descriptor(
        name: String,
        readOnly: Boolean = false,
        pathParam: Boolean = false,
    ): McpToolDescriptor {
        val props = JSONObject()
        if (pathParam) props.put("path", JSONObject().put("type", "string"))
        val raw = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("additionalProperties", false)
        return McpToolDescriptor(
            serverId = "srv",
            serverName = "演示 server",
            name = name,
            description = "工具 $name",
            inputSchema = McpSchema.normalizeSchema(name, raw),
            readOnly = readOnly,
        )
    }

    private fun tmp(): File = Files.createTempDirectory("mcp-source").toFile()

    private fun pipeline(reg: ToolRegistry, root: File = tmp()): GuardPipeline =
        GuardPipeline(
            registry = reg,
            workspaceRoot = { root },
            rawDir = { null },
            runner = ToolRunner(Executors.newSingleThreadExecutor()),
            log = {},
        )

    private fun call(name: String, args: JSONObject = JSONObject()): ToolCall =
        ToolCall("c-1", name, args)

    @Test
    fun `登记_不连接_不建客户端`() {
        var created = 0
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { created++; FakeSession() })
        assertTrue(source.addServer(config))
        assertFalse("同 id 不覆盖", source.addServer(config))
        assertEquals(0, created)
        assertEquals(0, source.toolCount("srv"))
        assertEquals(listOf("srv"), source.serverIds())
    }

    @Test
    fun `连接_注册两个工具_CONFIRM_组可见`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true), descriptor("write_thing")))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)

        val out = source.connect("srv")
        assertTrue(out is McpConnectOutcome.Ok)
        assertEquals(2, (out as McpConnectOutcome.Ok).toolCount)
        assertEquals(1, fake.connectCalls)
        assertEquals(1, fake.listCalls)
        assertEquals("连接不自动调用", 0, fake.callCalls)

        val read = reg.find("mcp_srv_read_thing")!!
        assertEquals(ToolPermission.CONFIRM, read.permission)
        assertEquals(ToolGroup.MCP, read.group)
        assertEquals(7000L, read.timeoutMs)
        assertTrue(reg.visibleSpecs().map { it.name }.containsAll(listOf("mcp_srv_read_thing", "mcp_srv_write_thing")))
        assertTrue(reg.approvedGroups().contains(ToolGroup.MCP))
        assertTrue(source.grantSnapshot().isEmpty())
    }

    @Test
    fun `未授权_直调执行器_0 请求`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")

        val spec = reg.find("mcp_srv_read_thing")!!
        val direct = spec.executor(JSONObject())
        assertTrue(direct is ToolOutcome.Rejected)
        assertEquals(0, fake.callCalls)

        val r = pipeline(reg).execute(call(spec.name), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.NeedsConfirm)
        assertEquals("确认卡没点之前 0 请求", 0, fake.callCalls)
    }

    @Test
    fun `批准后_只读转 FREE_危险仍每次确认`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true), descriptor("write_thing")))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")
        val pipe = pipeline(reg)

        val readName = "mcp_srv_read_thing"
        assertEquals(ToolPermission.CONFIRM, reg.find(readName)!!.permission)
        val first = pipe.executeApproved(call(readName), { _, _ -> null })
        assertTrue(first is GuardPipeline.Result.Done)
        assertEquals(1, fake.callCalls)
        assertEquals("只读工具批准后免确认", ToolPermission.FREE, reg.find(readName)!!.permission)

        val again = pipe.execute(call(readName), { _, _ -> null })
        assertTrue("免确认直行", again is GuardPipeline.Result.Done)
        assertEquals(2, fake.callCalls)

        val writeName = "mcp_srv_write_thing"
        assertTrue(pipe.execute(call(writeName), { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        assertTrue(pipe.executeApproved(call(writeName), { _, _ -> null }) is GuardPipeline.Result.Done)
        assertEquals(3, fake.callCalls)
        assertEquals("危险工具批准后仍是 CONFIRM", ToolPermission.CONFIRM, reg.find(writeName)!!.permission)
        assertTrue("危险工具每次再确认", pipe.execute(call(writeName), { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        assertEquals(3, fake.callCalls)
    }

    @Test
    fun `外部 path 参数_不被本地工作区改写`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_path", readOnly = true, pathParam = true)))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")
        val pipe = pipeline(reg)
        val args = JSONObject().put("path", "remote/rel.txt")
        assertTrue(pipe.executeApproved(call("mcp_srv_read_path", args), { _, _ -> null }) is GuardPipeline.Result.Done)
        assertEquals("remote/rel.txt", fake.lastArgs!!.getString("path"))
        assertFalse("不能被改写成绝对路径", fake.lastArgs!!.getString("path").startsWith("/"))
    }

    @Test
    fun `参数不合 schema_守卫先拒_0 请求`() {
        val reg = ToolRegistry()
        val schema = McpSchema.normalizeSchema(
            "need_text",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("text", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("text")),
        )
        val fake = FakeSession(
            listOf(
                McpToolDescriptor(
                    serverId = "srv",
                    serverName = "演示 server",
                    name = "need_text",
                    description = "必须带 text",
                    inputSchema = schema,
                    readOnly = false,
                ),
            ),
        )
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")

        val r = pipeline(reg).executeApproved(
            ToolCall("c-1", "mcp_srv_need_text", JSONObject()),
            { _, _ -> null },
        )
        assertTrue(r is GuardPipeline.Result.Failed)
        assertTrue(
            "复用现有 SchemaCheck 的原文：${(r as GuardPipeline.Result.Failed).error}",
            r.error.contains("缺少必填参数") && r.error.contains("text"),
        )
        assertEquals("Schema 不过：一个网络请求都不发", 0, fake.callCalls)
        assertTrue("Schema 不过：不记授权", source.grantSnapshot().isEmpty())
    }

    @Test
    fun `断连_注销_清授权`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")
        val pipe = pipeline(reg)
        pipe.executeApproved(call("mcp_srv_read_thing"), { _, _ -> null })
        assertEquals(1, source.grantSnapshot().values.sumOf { it.size })

        source.disconnect("srv")
        assertNull(reg.find("mcp_srv_read_thing"))
        assertFalse(reg.names().contains("mcp_srv_read_thing"))
        assertTrue("授权清空", source.grantSnapshot().isEmpty())
        assertTrue(fake.closed)
        assertEquals(McpServerStatus.OFFLINE, source.status("srv"))
        assertEquals(0, source.toolCount("srv"))
    }

    @Test
    fun `连接失败_不注册_状态 ERROR`() {
        val reg = ToolRegistry()
        val fake = FakeSession(connectError = McpException(McpFailure.CONNECT, "假 server 断连了"))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)

        val out = source.connect("srv")
        assertTrue(out is McpConnectOutcome.Failed)
        assertTrue((out as McpConnectOutcome.Failed).message.contains("断连"))
        assertTrue(reg.names().none { it.startsWith("mcp_") })
        assertEquals(McpServerStatus.ERROR, source.status("srv"))
        assertTrue(fake.closed)
    }

    @Test
    fun `列表失败_不注册部分工具_整 server 拒收`() {
        val reg = ToolRegistry()
        val fake = FakeSession(
            listError = McpException(McpFailure.SCHEMA, "工具「bad」的 schema 不受支持"),
        )
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        val out = source.connect("srv")
        assertTrue(out is McpConnectOutcome.Failed)
        assertTrue(reg.names().none { it.startsWith("mcp_") })
        assertTrue(fake.closed)
    }

    @Test
    fun `撞本地工具名_拒收_不覆盖本地`() {
        val reg = ToolRegistry()
        reg.register(
            ToolSpec(
                name = "mcp_srv_echo",
                description = "本地工具（故意占名）",
                inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
            ) { ToolOutcome.Ok("local") },
        )
        val fake = FakeSession(listOf(descriptor("echo")))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        val out = source.connect("srv")
        assertTrue(out is McpConnectOutcome.Failed)
        assertTrue((out as McpConnectOutcome.Failed).message.contains("本地工具"))
        assertEquals("本地工具原样保留", "本地工具（故意占名）", reg.find("mcp_srv_echo")!!.description)
        assertTrue(fake.closed)
    }

    @Test
    fun `MCP 组被用户关闭_连接不覆盖_工具不可见`() {
        val reg = ToolRegistry()
        reg.setGroupDisabled(ToolGroup.MCP, true)
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        val out = source.connect("srv")
        assertTrue(out is McpConnectOutcome.Ok)
        assertTrue((out as McpConnectOutcome.Ok).warnings.any { it.contains("关闭") })
        assertTrue(reg.disabledGroups().contains(ToolGroup.MCP))
        assertFalse(reg.visibleSpecs().any { it.name.startsWith("mcp_") })
    }

    @Test
    fun `换注册表_只搬工具_不重连`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")
        val fresh = ToolRegistry()
        source.attach(fresh)
        assertNotNull(fresh.find("mcp_srv_read_thing"))
        assertEquals(1, fake.connectCalls)
        assertEquals(1, fake.listCalls)
        assertTrue(fresh.approvedGroups().contains(ToolGroup.MCP))
    }

    @Test
    fun `远程错误_可读失败_注册表不污染`() {
        val reg = ToolRegistry()
        val fake = FakeSession(
            listOf(descriptor("write_thing")),
            callError = McpException(McpFailure.TIMEOUT, "外部工具调用超时（2s）"),
        )
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")
        val before = reg.find("mcp_srv_write_thing")!!
        val r = pipeline(reg).executeApproved(call(before.name), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Failed)
        assertTrue((r as GuardPipeline.Result.Failed).error.contains("超时"))
        assertEquals("注册表不变", before.description, reg.find(before.name)!!.description)
        assertEquals("权限不变", ToolPermission.CONFIRM, reg.find(before.name)!!.permission)
        assertEquals(1, fake.callCalls)
    }

    @Test
    fun `removeServer_断连并移除配置`() {
        val reg = ToolRegistry()
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")
        assertTrue(source.removeServer("srv"))
        assertTrue(source.serverIds().isEmpty())
        assertNull(reg.find("mcp_srv_read_thing"))
        assertFalse(source.removeServer("srv"))
    }
}
