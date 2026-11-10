// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 能力清单（.mcp-peers.json）与指纹对比的单元测试。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.testkit.TempSandboxPool
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpPeersManifestTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = TempSandboxPool.create("peers548")
    }

    @After
    fun tearDown() {
        TempSandboxPool.clean()
    }

    private fun entry(
        agentId: String = "ag-a",
        tools: List<PeerToolFp> = listOf(PeerToolFp("t1", "工具一", "1".repeat(64))),
        caps: List<String> = listOf("tools"),
        probedAt: Long = 1_000L,
    ) = PeerManifestEntry(
        agentId = agentId,
        serverId = "s-$agentId",
        name = "台$agentId",
        endpoint = "http://127.0.0.1:9000/mcp",
        protocolVersion = "2026-07-28",
        mode = "streamable-http",
        capabilities = caps,
        tools = tools,
        probedAtMs = probedAt,
        probeResult = "ok",
    )

    @Test
    fun `落盘读回_一行不差`() {
        val e = entry()
        assertTrue(McpPeersManifest.upsert(root, e))
        assertEquals(mapOf("ag-a" to e), McpPeersManifest.load(root))
        // 文件在 产物/ 下、点号开头。
        assertEquals(File(File(root, "产物"), ".mcp-peers.json").path, McpPeersManifest.fileIn(root).path)
        assertTrue(McpPeersManifest.fileIn(root).isFile)
    }

    @Test
    fun `upsert_保留别的行_替换同id行`() {
        McpPeersManifest.upsert(root, entry())
        McpPeersManifest.upsert(root, entry(agentId = "ag-b"))
        val newer = entry(tools = listOf(PeerToolFp("t1", "工具一改", "2".repeat(64))))
        McpPeersManifest.upsert(root, newer)
        val all = McpPeersManifest.load(root)
        assertEquals(2, all.size)
        assertEquals(newer, all["ag-a"])
        assertEquals("工具一", all["ag-b"]!!.tools.single().description)
    }

    @Test
    fun `TTL_24h_过期标陈旧_不自动沿用`() {
        val e = entry(probedAt = 0L)
        assertFalse("刚探完不算陈旧", e.isStale(nowMs = PeerManifestEntry.DEFAULT_TTL_MS - 1))
        assertTrue("满 24h 即陈旧", e.isStale(nowMs = PeerManifestEntry.DEFAULT_TTL_MS))
        assertTrue(e.isStale(nowMs = PeerManifestEntry.DEFAULT_TTL_MS + 1))
        // 自定义窗也走同一把尺。
        assertTrue(e.isStale(nowMs = 100L, ttlMs = 100L))
    }

    @Test
    fun `坏文件_空表不抛`() {
        McpPeersManifest.fileIn(root).parentFile!!.mkdirs()
        McpPeersManifest.fileIn(root).writeText("这不是json{{{", Charsets.UTF_8)
        assertTrue(McpPeersManifest.load(root).isEmpty())
        assertTrue("坏文件之后照样能写", McpPeersManifest.upsert(root, entry()))
        assertEquals(1, McpPeersManifest.load(root).size)
    }

    @Test
    fun `没有文件_load是空表`() {
        assertTrue(McpPeersManifest.load(root).isEmpty())
    }

    // ── 指纹对比（rug-pull 防线的纯件） ─────────────────────────────────

    @Test
    fun `对比_无旧清单_不算变化`() {
        assertFalse(diffCapabilities(null, entry()).changed)
        assertEquals(CapabilityChangeReport.NONE, diffCapabilities(null, entry()))
    }

    @Test
    fun `对比_schema指纹变_报在案且点名工具`() {
        val old = entry(tools = listOf(PeerToolFp("t1", "工具一", "1".repeat(64))))
        val new = entry(tools = listOf(PeerToolFp("t1", "工具一", "9".repeat(64))))
        val r = diffCapabilities(old, new)
        assertTrue(r.changed)
        assertEquals(listOf("t1"), r.schemaChanged)
        assertTrue("人话要点名哪个工具改了", r.human().contains("「t1」的入参约定变了"))
        assertTrue(r.human().contains("能力变了"))
        assertTrue("不许静默：要写明未自动启用", r.human().contains("没有自动启用"))
    }

    @Test
    fun `对比_加工具_减工具_能力键变_各自入账`() {
        val old = entry(
            tools = listOf(PeerToolFp("t1", "d", "1"), PeerToolFp("t2", "d", "2")),
            caps = listOf("tools"),
        )
        val new = entry(
            tools = listOf(PeerToolFp("t1", "d", "1"), PeerToolFp("t3", "d", "3")),
            caps = listOf("tools", "resources"),
        )
        val r = diffCapabilities(old, new)
        assertTrue(r.changed)
        assertEquals(listOf("t3"), r.addedTools)
        assertEquals(listOf("t2"), r.removedTools)
        assertTrue(r.capabilityKeysChanged)
        assertEquals("schema 没动的工具不许误报", emptyList<String>(), r.schemaChanged)
    }

    @Test
    fun `对比_一模一样_零变化`() {
        val old = entry()
        val new = entry()
        assertFalse(diffCapabilities(old, new).changed)
        assertEquals("", diffCapabilities(old, new).human())
    }

    @Test
    fun `schema指纹_内容变才变_键序不变`() {
        val a = JSONObject("""{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""")
        val reordered = JSONObject("""{"required":["q"],"properties":{"q":{"type":"string"}},"type":"object"}""")
        assertEquals("只调键序不许翻脸", PeerFingerprints.ofToolSchema(a), PeerFingerprints.ofToolSchema(reordered))
        val contentChanged = JSONObject("""{"type":"object","properties":{"q":{"type":"integer"}},"required":["q"]}""")
        assertNotEquals(PeerFingerprints.ofToolSchema(a), PeerFingerprints.ofToolSchema(contentChanged))
        // 嵌套对象同样规范化。
        val nested = JSONObject("""{"b":{"y":1,"x":2},"a":[3,{"z":true,"w":null}]}""")
        val nestedReordered = JSONObject("""{"a":[3,{"w":null,"z":true}],"b":{"x":2,"y":1}}""")
        assertEquals(PeerFingerprints.ofToolSchema(nested), PeerFingerprints.ofToolSchema(nestedReordered))
    }

    @Test
    fun `能力清单指纹_随工具增删与schema变而变_不随排序变`() {
        val t1 = PeerToolFp("t1", "d", "1")
        val t2 = PeerToolFp("t2", "d", "2")
        val base = PeerFingerprints.ofCapabilitySet(listOf("tools", "logging"), listOf(t1, t2))
        assertEquals("工具顺序不影响", base, PeerFingerprints.ofCapabilitySet(listOf("logging", "tools"), listOf(t2, t1)))
        assertNotEquals(base, PeerFingerprints.ofCapabilitySet(listOf("tools", "logging"), listOf(t1)))
        assertNotEquals(base, PeerFingerprints.ofCapabilitySet(listOf("tools"), listOf(t1, t2)))
        assertNotEquals(base, PeerFingerprints.ofCapabilitySet(listOf("tools", "logging"), listOf(t1, t2.copy(schemaSha256 = "9"))))
    }

    @Test
    fun `PeerToolFp_坏行读不出_不抛`() {
        assertNull(PeerToolFp.fromJson(JSONObject("""{"description":"没有名字"}""")))
        val t = PeerToolFp("t", "d", "f")
        assertEquals(t, PeerToolFp.fromJson(t.toJson()))
    }
}
