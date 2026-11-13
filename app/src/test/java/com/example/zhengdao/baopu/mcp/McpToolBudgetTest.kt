// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · 预算读数与账本（McpToolBudget / McpBudgetStore）。
// 纯 JVM：零临时目录、零 Android——水位按 607 口径本文件零残留。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.TokenEstimator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolBudgetTest {

    // ── 预算数学 ─────────────────────────────────────────────────────────

    @Test
    fun `预算_可用上下文乘比例_默认8percent`() {
        assertEquals(8_000, McpToolBudget.budgetTokens(100_000, McpToolBudget.DEFAULT_RATIO))
        assertEquals(0, McpToolBudget.budgetTokens(0, 0.08))
        assertEquals(0, McpToolBudget.budgetTokens(-5, 0.08))
    }

    @Test
    fun `比例夹取_过小过大都兜住_坏值回默认`() {
        assertEquals(McpToolBudget.MIN_RATIO, McpToolBudget.clampRatio(0.001), 0.0)
        assertEquals(McpToolBudget.MAX_RATIO, McpToolBudget.clampRatio(0.9), 0.0)
        assertEquals(McpToolBudget.DEFAULT_RATIO, McpToolBudget.clampRatio(Double.NaN), 0.0)
        assertEquals(0.08, McpToolBudget.clampRatio(0.08), 0.0)
    }

    @Test
    fun `MCP额度_全局预算扣内置_下限0`() {
        assertEquals(2_000, McpToolBudget.mcpAllowanceTokens(4_000, 2_000))
        assertEquals(0, McpToolBudget.mcpAllowanceTokens(1_000, 2_000))
    }

    @Test
    fun `schema度量_与全仓唯一尺子同源`() {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
        val expected = TokenEstimator.estimate(schema.toString())
        assertEquals(expected, McpToolBudget.schemaTokens(schema))
        assertTrue("UTF-8 字节量必须是正数", McpToolBudget.schemaBytes(schema) > 0)
        assertEquals(schema.toString().toByteArray(Charsets.UTF_8).size.toLong(), McpToolBudget.schemaBytes(schema))
    }

    // ── 意图打分 ─────────────────────────────────────────────────────────

    @Test
    fun `意图打分_无意图文本_恒0`() {
        val scorer = McpToolBudget.intentScorer(null)
        assertEquals(0, scorer("search_file", "搜索文件"))
        val emptyScorer = McpToolBudget.intentScorer("")
        assertEquals(0, emptyScorer("a", "b"))
    }

    @Test
    fun `意图打分_CJK二元组命中`() {
        val scorer = McpToolBudget.intentScorer("帮我搜索一下文件")
        assertTrue("工具名命中意图词", scorer("search_file", "搜索文件相关的工具") > 0)
        assertEquals(0, scorer("play_music", "播放音乐"))
    }

    @Test
    fun `意图打分_ASCII整词命中_大小写不敏感`() {
        val scorer = McpToolBudget.intentScorer("Search the files")
        assertTrue(scorer("search_file", "Search files quickly") > 0)
        assertEquals("词没对上就是 0", 0, scorer("draw_chart", "画图"))
    }

    // ── 账本 ─────────────────────────────────────────────────────────────

    /** 只在内存里记写入（编解码断言的物证）。 */
    private class FakeStorage : McpBudgetStorage {
        val writes = mutableListOf<String>()
        var next: String? = null
        override fun read(): String? = next
        override fun write(json: String) {
            writes.add(json)
            next = json
        }
    }

    @Test
    fun `账本_pin与取消_每次变更落盘`() {
        val storage = FakeStorage()
        val store = McpBudgetStore(storage)
        assertTrue(store.pin("mcp_a_search"))
        assertTrue("重复 pin 不算变更", !store.pin("mcp_a_search"))
        assertEquals(1, store.pins().size)
        assertTrue(storage.writes.isNotEmpty())
        assertTrue(store.unpin("mcp_a_search"))
        assertEquals(0, store.pins().size)
    }

    @Test
    fun `账本_重开进程_pin_LRU_比例都接回来`() {
        val storage = FakeStorage()
        val first = McpBudgetStore(storage)
        first.pin("mcp_a_search")
        first.recordUsed("mcp_a_search", 1_234L)
        first.setRatio(0.2)
        val second = McpBudgetStore(storage)
        second.load()
        assertEquals(setOf("mcp_a_search"), second.pins())
        assertEquals(1_234L, second.lastUsedAtMs()["mcp_a_search"])
        assertEquals(0.2, second.ratio()!!, 0.0)
    }

    @Test
    fun `账本_坏JSON按空处理_不整份丢的意思是不崩`() {
        val storage = FakeStorage()
        storage.next = "不是 JSON"
        val store = McpBudgetStore(storage)
        store.load()
        assertEquals(0, store.pins().size)
        assertNull(store.ratio())
    }

    @Test
    fun `账本_比例越界夹取_null回默认`() {
        val storage = FakeStorage()
        val store = McpBudgetStore(storage)
        assertTrue(store.setRatio(0.9))
        assertEquals(McpToolBudget.MAX_RATIO, store.ratio()!!, 0.0)
        assertTrue(store.setRatio(null))
        assertNull("null = 回默认比例", store.ratio())
    }

    @Test
    fun `账本_LRU封顶_最旧的被挤掉`() {
        val store = McpBudgetStore.inMemory()
        repeat(McpBudgetStore_LRU_MAX_FOR_TEST + 8) { i -> store.recordUsed("t$i", (i + 1).toLong()) }
        val snapshot = store.lastUsedAtMs()
        assertEquals("LRU 表按上限封顶", McpBudgetStore_LRU_MAX_FOR_TEST, snapshot.size)
        assertFalse("最旧的必须被挤掉", snapshot.containsKey("t0"))
        assertTrue("最新的必须还在", snapshot.containsKey("t${McpBudgetStore_LRU_MAX_FOR_TEST + 7}"))
    }

    private companion object {
        // 与 McpBudgetStore.LRU_MAX 同值（常量是 private，这里钉一份防漂移）。
        const val McpBudgetStore_LRU_MAX_FOR_TEST = 512
    }

    @Test
    fun `账本_纯内存模式_一个字节都不写盘`() {
        val store = McpBudgetStore.inMemory()
        store.pin("mcp_a_search")
        store.recordUsed("mcp_a_search", 1L)
        store.setRatio(0.3)
        // 没有 storage ⇒ 不崩；账在内存里仍可读。
        assertEquals(setOf("mcp_a_search"), store.pins())
        assertEquals(0.3, store.ratio()!!, 0.0)
    }
}
