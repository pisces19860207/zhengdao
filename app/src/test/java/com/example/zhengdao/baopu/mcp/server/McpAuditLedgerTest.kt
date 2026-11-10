// 第五百八十四单 · 审计账单测：内存/盘两路、拒绝账读得到、token 永不进账。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.testkit.TempSandboxPool
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpAuditLedgerTest {

    private lateinit var dir: File

    @Before
    fun wire() {
        McpAuditLedger.resetForTest()
        dir = TempSandboxPool.create("zd-mcp-audit-")
        McpAuditLedger.install(listOf(dir))
    }

    @After
    fun clean() {
        McpAuditLedger.resetForTest()
        TempSandboxPool.clean()
    }

    @Test
    fun `追加即落盘，replay盘上读得到`() {
        McpAuditLedger.append(McpAuditLedger.EV_AUTH_FAIL, mapOf("outcome" to "401", "reason" to "bad_token"))
        val f = File(dir, McpAuditLedger.FILE_NAME)
        assertTrue("账文件必须落上", f.isFile)
        val rows = McpAuditLedger.replay()
        assertEquals(1, rows.size)
        assertEquals(McpAuditLedger.EV_AUTH_FAIL, rows[0].optString("ev"))
        assertEquals("bad_token", rows[0].optString("reason"))
        assertEquals(401, rows[0].optInt("outcome", 401))
    }

    @Test
    fun `没装配落点时内存兜底，replay照样读得到`() {
        McpAuditLedger.resetForTest()
        McpAuditLedger.append(McpAuditLedger.EV_TOOL_REJECT, mapOf("tool" to "write_file"))
        val rows = McpAuditLedger.replay()
        assertEquals(1, rows.size)
        assertEquals("write_file", rows[0].optString("tool"))
    }

    @Test
    fun `拒绝账三件套都能落（判据②③的读数口）`() {
        McpAuditLedger.append(McpAuditLedger.EV_AUTH_FAIL, mapOf("reason" to "bad_token"))
        McpAuditLedger.append(McpAuditLedger.EV_TOOL_REJECT, mapOf("tool" to "delete_path", "outcome" to "denied"))
        McpAuditLedger.append(McpAuditLedger.EV_LEASE_REJECT, mapOf("tool" to "schedule_task", "reason" to "not_granted"))
        val evs = McpAuditLedger.replay().map { it.optString("ev") }
        assertTrue(McpAuditLedger.EV_AUTH_FAIL in evs)
        assertTrue(McpAuditLedger.EV_TOOL_REJECT in evs)
        assertTrue(McpAuditLedger.EV_LEASE_REJECT in evs)
    }

    @Test
    fun `常规调用链的账行没有任何 token 形状的键（216 批红线在账本上的钉子）`() {
        McpAuditLedger.append(McpAuditLedger.EV_AUTH_FAIL, mapOf("outcome" to "401", "reason" to "bad_token"))
        McpAuditLedger.append(McpAuditLedger.EV_CALL, mapOf("tool" to "read_file", "outcome" to "lease_free"))
        McpAuditLedger.append(McpAuditLedger.EV_SERVER, mapOf("detail" to "started", "bind" to "127.0.0.1"))
        val dump = McpAuditLedger.replay().joinToString("\n") { it.toString() } +
            File(dir, McpAuditLedger.FILE_NAME).readText()
        listOf("\"token\"", "authorization", "Bearer").forEach { banned ->
            assertFalse("账行不许出现 $banned 键/字样", dump.contains(banned))
        }
    }

    @Test
    fun `内存有界（200行封顶）`() {
        McpAuditLedger.resetForTest()
        repeat(300) { McpAuditLedger.append(McpAuditLedger.EV_METHOD, mapOf("method" to "ping", "n" to it.toString())) }
        assertTrue(McpAuditLedger.replay().size <= 200)
    }
}
