// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 身份名册（.agents.jsonl）的判据测试：
// · 同 url 重注册不新增行、id 稳定（判据①）；
// · 断连/删除标 removed 不硬删（工作单判据⑤）；
// · 行里只有指纹没有凭据本体（凭据只进 McpVault——grep 断言）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.testkit.TempSandboxPool
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentRegistryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = TempSandboxPool.create("agents548")
        AgentRegistry.installRoot { root }
    }

    @After
    fun tearDown() {
        AgentRegistry.resetForTest()
        TempSandboxPool.clean()
    }

    private fun register(
        endpoint: String = "http://127.0.0.1:8765/mcp",
        name: String = "台A",
        form: AgentForm = AgentForm.MCP,
        credFp: String = "",
        capFp: String = "aa".repeat(32),
        atMs: Long = 1_000L,
    ) = AgentRegistry.register(name, form, endpoint, credFp, capFp, atMs = atMs)

    @Test
    fun `注册_行齐且字段是约定那几样`() {
        val r = register(credFp = "1234567890abcdef", capFp = "bb".repeat(32))
        assertTrue(r.appended)
        assertEquals("ag-", r.record.id.take(3))
        val line = AgentRegistry.fileIn(root).readText()
        val row = org.json.JSONObject(line.trim())
        assertEquals(r.record.id, row.optString("id"))
        assertEquals("台A", row.optString("name"))
        assertEquals("mcp", row.optString("form"))
        assertEquals("http://127.0.0.1:8765/mcp", row.optString("endpoint"))
        assertEquals("1234567890abcdef", row.optString("credFp"))
        assertEquals("bb".repeat(32), row.optString("capFp"))
        assertEquals("register", row.optString("kind"))
    }

    @Test
    fun `判据①_同url重注册_不新增行_id不变`() {
        val first = register()
        val second = register()
        assertFalse("幂等命中不许加行", second.appended)
        assertEquals(first.record.id, second.record.id)
        assertEquals("整本名册仍只有一行", 1, AgentRegistry.fileIn(root).readLines().count { it.isNotBlank() })
    }

    @Test
    fun `指纹变了_加一行register_还是同一个id`() {
        val first = register()
        val second = register(capFp = "cc".repeat(32))
        assertTrue("能力指纹变了是实质变化，要留一行账", second.appended)
        assertEquals(first.record.id, second.record.id)
        assertEquals("cc".repeat(32), AgentRegistry.active().single().capabilityFingerprint)
        assertEquals(2, AgentRegistry.fileIn(root).readLines().count { it.isNotBlank() })
    }

    @Test
    fun `规范化_大小写尾斜杠默认端口不影响id`() {
        val a = AgentIds.stableId("HTTP://127.0.0.1:8765/mcp/")
        val b = AgentIds.stableId("http://127.0.0.1:8765/mcp")
        assertEquals(a, b)
        assertNotEquals(a, AgentIds.stableId("http://127.0.0.1:8766/mcp"))
        assertNotEquals(a, AgentIds.stableId("http://127.0.0.1:8765/other"))
    }

    @Test
    fun `判据⑤_删服务器_标removed不硬删_可按endpoint再来`() {
        register()
        // 已删的再删：无事发生（这条要在恢复之前验）。
        assertTrue(AgentRegistry.markRemoved("http://127.0.0.1:8765/mcp", AgentForm.MCP, "面板删除", atMs = 2_000L))
        assertFalse(AgentRegistry.markRemoved("http://127.0.0.1:8765/mcp", AgentForm.MCP, "again", atMs = 2_100L))
        assertTrue("删了就是空册", AgentRegistry.active().isEmpty())
        // 行还在（留痕）：最后一行是 removed。
        val lines = AgentRegistry.fileIn(root).readLines().filter { it.isNotBlank() }
        assertEquals("removed", org.json.JSONObject(lines.last()).optString("kind"))
        // 再注册 = 恢复在册，id 不变，addedAt 保留最初那次。
        val again = register(atMs = 3_000L)
        assertTrue(again.appended)
        assertEquals(3_000L, again.record.updatedAtMs)
        assertEquals(1_000L, again.record.addedAtMs)
        assertEquals(1, AgentRegistry.active().size)
    }

    @Test
    fun `没注册过的endpoint_删不动`() {
        assertFalse(AgentRegistry.markRemoved("http://127.0.0.1:1/x", AgentForm.MCP, "ghost"))
    }

    @Test
    fun `凭据本体不进名册_只有指纹`() {
        val secret = "sk-super-secret-token-548"
        val fp = PeerFingerprints.ofCredential(secret)
        assertEquals("指纹是 16 位十六进制", 16, fp.length)
        register(credFp = fp)
        val text = AgentRegistry.fileIn(root).readText()
        assertFalse("名册里不许出现凭据明文", text.contains(secret))
        assertTrue("名册里只有指纹", text.contains(fp))
        // 指纹稳定：同料同纹。
        assertEquals(fp, PeerFingerprints.ofCredential(secret))
        // 没凭据 = 空串（「没有」与「有」不许混）。
        assertEquals("", PeerFingerprints.ofCredential(null))
        assertEquals("", PeerFingerprints.ofCredential(""))
    }

    @Test
    fun `坏行不毒死整本名册`() {
        register()
        val f = AgentRegistry.fileIn(root)
        f.appendText("{\"kind\":\"register\",\"id\":\"ag-broken\"}\n") // 缺 name/form/endpoint ⇒ 读不出
        register(name = "台B", endpoint = "http://127.0.0.1:9999/x")
        assertEquals(2, AgentRegistry.active().size)
    }

    @Test
    fun `没装落点_注册只返回记录不落盘不抛`() {
        AgentRegistry.resetForTest()
        val r = AgentRegistry.register("台C", AgentForm.A2A, "http://127.0.0.1:1", "", "", atMs = 5L)
        assertTrue("不落点也要把记录算出来（幂等口径不受影响）", r.appended)
        assertTrue(r.record.id.startsWith("ag-"))
        assertEquals(AgentForm.A2A, r.record.form)
    }
}
