// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十三批 · MCP v1 负例：派工单 §红线逐条对应，任何一条被改坏都要先红。
// 1) 自动连接；2) 自动调用；3) stdio/本地进程；4) 密钥进日志/文案；5) 绕过 Guard。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

class McpNegativeTest {

    private val config = McpServerConfig(
        id = "srv",
        name = "演示 server",
        url = "http://127.0.0.1:1/mcp",
        callTimeoutMs = 2_000,
    )

    private class FakeSession(
        private val tools: List<McpToolDescriptor> = emptyList(),
    ) : McpSession {
        var connectCalls = 0
        var listCalls = 0
        var callCalls = 0
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL

        override fun connect() {
            connectCalls++
        }

        override fun listTools(): List<McpToolDescriptor> {
            listCalls++
            return tools
        }

        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult {
            callCalls++
            return McpCallResult("远程结果", false)
        }

        override fun close() = Unit
    }

    private fun descriptor(name: String, readOnly: Boolean = false): McpToolDescriptor {
        val raw = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject())
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

    private fun pipeline(reg: ToolRegistry): GuardPipeline = GuardPipeline(
        registry = reg,
        workspaceRoot = { File(".") },
        rawDir = { null },
        runner = ToolRunner(Executors.newSingleThreadExecutor()),
        log = {},
    )

    @Test
    fun `负例_自动连接_登记与换表都不建客户端`() {
        var created = 0
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { created++; FakeSession() })

        source.addServer(config)
        source.attach(ToolRegistry())
        reg.visibleSpecs()

        assertEquals("没点「连接」就绝不建客户端", 0, created)
        assertEquals(McpServerStatus.OFFLINE, source.status("srv"))
        assertEquals(0, source.toolCount("srv"))
    }

    @Test
    fun `负例_自动调用_连接只列表不调用`() {
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)

        source.connect("srv")
        source.attach(ToolRegistry())
        reg.visibleSpecs()

        assertEquals(1, fake.listCalls)
        assertEquals("连接不是调用：一次 tools/call 都不许发", 0, fake.callCalls)
    }

    @Test
    fun `负例_stdio_不是可配置传输`() {
        for (url in listOf(
            "stdio:///bin/sh",
            "stdio:/bin/sh",
            "cmd://127.0.0.1/mcp",
            "file:///tmp/server.sock",
        )) {
            assertFalse("必须拒：$url", McpUrlRules.isPermitted(url))
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpServerConfig(id = "srv", name = "stdio", url = "stdio:///bin/sh")
        }
    }

    @Test
    fun `负例_MCP代码不启动本地进程`() {
        val root = File("src/main/java/com/example/zhengdao")
            .takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/example/zhengdao")
        val files = File(root, "baopu/mcp").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList() + File(root, "baopu/BaopuRuntime.kt")
        assertTrue("扫描目标必须存在（否则这条负例是空转的）", files.size >= 5)

        val banned = listOf(
            Regex("""ProcessBuilder"""),
            Regex("""Runtime\s*\.\s*getRuntime\s*\("""),
            Regex("""\.exec\s*\("""),
            Regex("""ProcessHandle"""),
        )
        val offenders = files.filter { f ->
            val text = f.readText()
            banned.any { it.containsMatchIn(text) }
        }.map { it.name }
        assertEquals("MCP 客户端不许拉起任何子进程：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `负例_密钥与 query_不进日志文案`() {
        val logs = mutableListOf<String>()
        val cfg = McpServerConfig(
            id = "srv",
            name = "带密钥",
            url = "https://example.com/mcp?token=SECRET",
            headers = mapOf("Authorization" to "Bearer SECRET"),
        )
        logs.add(cfg.toString())

        assertFalse("配置文案不带头部值", logs.any { it.contains("Bearer SECRET") })
        assertFalse("配置文案不带 query", logs.any { it.contains("token=") })
        assertFalse("配置文案不带密钥片段", logs.any { it.contains("SECRET") })
    }

    @Test
    fun `负例_绕过 Guard_直调与断连后都拒`() {
        val fake = FakeSession(listOf(descriptor("read_thing", readOnly = true)))
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { fake })
        source.addServer(config)
        source.connect("srv")

        val spec: ToolSpec = reg.find("mcp_srv_read_thing")!!
        assertEquals(ToolPermission.CONFIRM, spec.permission)
        assertEquals(ToolGroup.MCP, spec.group)

        val direct = spec.executor(JSONObject())
        assertTrue("绕过确认直调：拒", direct is ToolOutcome.Rejected)
        assertEquals("拒的时候一个网络请求都不发", 0, fake.callCalls)

        val pipe = pipeline(reg)
        val approved = pipe.executeApproved(ToolCall("c-1", spec.name, JSONObject()), { _, _ -> null })
        assertTrue(approved is GuardPipeline.Result.Done)
        assertEquals(1, fake.callCalls)

        source.disconnect("srv")
        val stale = spec.executor(JSONObject())
        assertTrue("断连后旧 spec 也拒", stale is ToolOutcome.Rejected)
        assertEquals("断连后不再发请求", 1, fake.callCalls)
    }
}
