// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十批 B · MCP 真入口：管理器（McpToolSource）与本地账（McpServerStore）的协作判据。
//
// 这一份钉的是**入口链路**，不是网络协议（协议在 McpClientTest / McpProtocolTest）：
// 1. 登记即写盘；重启后 restoreFromStore 能把地址认回来，且**一个网络请求都不发**；
// 2. 断开只改状态不删盘（可重连）；删除连盘一起删；
// 3. 落盘失败不阻断本次会话（内存是这一次的真相）；
// 4. 面板看到的 snapshot 里，地址是脱敏的。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.agent.ToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpEntryTest {

    private class FakeStorage(var text: String? = null) : McpServerStorage {
        var failOnWrite = false

        override fun read(): String? = text

        override fun write(json: String) {
            if (failOnWrite) throw IllegalStateException("模拟写入失败")
            text = json
        }
    }

    private class FakeSession(
        private val tools: List<McpToolDescriptor> = emptyList(),
    ) : McpSession {
        var connectCalls = 0
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL

        override fun connect() {
            connectCalls++
        }

        override fun listTools(): List<McpToolDescriptor> = tools

        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult =
            McpCallResult("ok", false)

        override fun close() = Unit
    }

    private fun descriptor(name: String) = McpToolDescriptor(
        serverId = "srv",
        serverName = "演示 server",
        name = name,
        description = "工具 $name",
        inputSchema = McpSchema.normalizeSchema(
            name,
            JSONObject().put("type", "object").put("properties", JSONObject()),
        ),
        readOnly = true,
    )

    private val config = McpServerConfig(
        id = "srv",
        name = "演示 server",
        url = "https://example.com/mcp",
    )

    @Test
    fun `登记即写盘_重启后恢复成未连接_且不联网`() {
        val storage = FakeStorage()
        var created = 0
        val first = McpToolSource(
            ToolRegistry(),
            clientFactory = { created++; FakeSession() },
            store = McpServerStore(storage),
        )
        assertTrue(first.addServer(config))
        assertNotNull("登记就要落盘（否则重启丢地址）", storage.text)
        assertEquals("登记不联网", 0, created)

        // 模拟进程重启：新注册表、新管理器，同一份存储。
        val second = McpToolSource(
            ToolRegistry(),
            clientFactory = { created++; FakeSession() },
            store = McpServerStore(storage),
        )
        assertEquals("认回 1 条", 1, second.restoreFromStore())
        assertEquals("恢复不能联网", 0, created)

        val row = second.snapshot().single()
        assertEquals("srv", row.id)
        assertEquals("演示 server", row.name)
        assertEquals("恢复后一律未连接", McpServerStatus.OFFLINE, row.status)
        assertEquals(0, row.toolCount)
    }

    @Test
    fun `restoreFromStore 幂等`() {
        val storage = FakeStorage()
        // 盘上先有一条（等价于上次退出时的样子），管理器还是空的。
        McpServerStore(storage).save(listOf(config))
        val source = McpToolSource(ToolRegistry(), clientFactory = { FakeSession() }, store = McpServerStore(storage))

        assertEquals(1, source.restoreFromStore())
        assertEquals("第二遍不该再加一条", 0, source.restoreFromStore())
        assertEquals(1, source.snapshot().size)
    }

    @Test
    fun `断开_配置仍留着可重连`() {
        val storage = FakeStorage()
        val fake = FakeSession(listOf(descriptor("read_thing")))
        val source = McpToolSource(
            ToolRegistry(),
            clientFactory = { fake },
            store = McpServerStore(storage),
        )
        source.addServer(config)
        assertTrue(source.connect("srv") is McpConnectOutcome.Ok)
        assertEquals(1, source.snapshot().single().toolCount)

        source.disconnect("srv")
        assertEquals(McpServerStatus.OFFLINE, source.snapshot().single().status)

        val reopened = McpToolSource(
            ToolRegistry(),
            clientFactory = { FakeSession() },
            store = McpServerStore(storage),
        )
        assertEquals("断开不许删盘（否则每次重启都要重填地址）", 1, reopened.restoreFromStore())
    }

    @Test
    fun `删除_盘上也删干净`() {
        val storage = FakeStorage()
        val source = McpToolSource(ToolRegistry(), clientFactory = { FakeSession() }, store = McpServerStore(storage))
        source.addServer(config)
        assertTrue(source.removeServer("srv"))

        assertTrue("内存里没了", source.serverIds().isEmpty())
        assertFalse("盘上也不许留", storage.text.orEmpty().contains("\"srv\""))

        val reopened = McpToolSource(ToolRegistry(), clientFactory = { FakeSession() }, store = McpServerStore(storage))
        assertEquals(0, reopened.restoreFromStore())
    }

    @Test
    fun `负例_落盘失败不阻断本次会话`() {
        val storage = FakeStorage().also { it.failOnWrite = true }
        val fake = FakeSession(listOf(descriptor("read_thing")))
        val source = McpToolSource(
            ToolRegistry(),
            clientFactory = { fake },
            store = McpServerStore(storage),
        )
        assertTrue("内存登记成功（写盘失败只记账）", source.addServer(config))
        assertTrue("连接照常", source.connect("srv") is McpConnectOutcome.Ok)
        assertEquals(1, source.snapshot().single().toolCount)
        assertNull("盘上确实没写进去", storage.text)
    }

    @Test
    fun `snapshot 地址脱敏_不露 query`() {
        val storage = FakeStorage()
        val source = McpToolSource(ToolRegistry(), clientFactory = { FakeSession() }, store = McpServerStore(storage))
        source.addServer(config.copy(url = "https://example.com/mcp?token=secret-xyz"))

        val row = source.snapshot().single()
        assertFalse("query 里的凭据不许显示", row.displayUrl.contains("secret-xyz"))
        assertTrue(row.displayUrl.contains("example.com"))
    }

    @Test
    fun `负例_未连接时断连与删除不崩`() {
        val source = McpToolSource(ToolRegistry(), clientFactory = { FakeSession() }, store = McpServerStore(FakeStorage()))
        source.disconnect("不存在")
        assertFalse(source.removeServer("不存在"))
        assertTrue(source.snapshot().isEmpty())
    }
}
