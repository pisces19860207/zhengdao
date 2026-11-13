// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · 装配计划（McpToolAssembly）——判据①②③⑤在这里逐一落地：
// ① 夹具 3 server × 64 工具 ⇒ 装配后 ≤ 预算、挤出数如实；
// ② pin 必装（超预算也装）；
// ③ LRU 顺序断言（最近用过的先装，没有记录的按连接序兜底）；
// ⑤ 不截断 schema：装配器只收 token 数、摸不到 schema 本体，计划里只有「整具」。
// 纯 JVM、零临时目录。
package com.example.zhengdao.baopu.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolAssemblyTest {

    /** 一台 server 的候选（名字/描述/近似的 schema token 都可控）。 */
    private fun candidate(
        serverSeq: Int,
        toolSeq: Int,
        wireName: String = "mcp_s${serverSeq}_t$toolSeq",
        description: String = "工具 $wireName",
        schemaTokens: Int = 100,
    ): McpAssemblyCandidate = McpAssemblyCandidate(
        wireName = wireName,
        remoteName = wireName,
        description = description,
        schemaTokens = schemaTokens,
        serverSeq = serverSeq,
        toolSeq = toolSeq,
    )

    /** 判据①的夹具：3 台 × 64 具（总 192，每具 100 token ⇒ 总 19_200）。 */
    private fun fixture(): List<McpAssemblyCandidate> =
        (0 until 3).flatMap { s -> (0 until 64).map { t -> candidate(s, t) } }

    private val fixtureTotal = 192 * 100

    // ── 判据①：夹具 3×64 ⇒ 装配后 ≤ 预算；挤出数如实 ─────────────────────

    @Test
    fun `夹具3x64_装得下_全装_零挤出_无说明`() {
        val plan = McpToolAssembly.plan(fixture(), fixtureTotal, emptySet(), emptyMap())
        assertEquals(192, plan.loadedCount)
        assertEquals(0, plan.evictedCount)
        assertEquals(fixtureTotal, plan.usedTokens)
        assertTrue("装配后总 token ≤ 预算", plan.usedTokens <= plan.budgetTokens)
        assertNull("零挤出不给模型添一句话", plan.notice)
        assertTrue(plan.loadsAll)
        assertTrue("夹具读数如实", plan.totalCandidates == 192)
    }

    @Test
    fun `夹具3x64_预算半档_装一半挤出如实报`() {
        val allowance = fixtureTotal / 2
        val plan = McpToolAssembly.plan(fixture(), allowance, emptySet(), emptyMap())
        assertEquals("装到预算为止（100 token/具 ⇒ 恰 96 具）", 96, plan.loadedCount)
        assertEquals("被挤出数如实", 96, plan.evictedCount)
        assertTrue("已用 ≤ 额度", plan.usedTokens <= allowance)
        assertTrue(
            "挤出说明是那一句（不给 schema）",
            plan.notice!!.contains("96 个外部工具未装载") && plan.notice!!.contains("没有截断"),
        )
        assertFalse(plan.loadsAll)
    }

    @Test
    fun `预算为0_无pin_一具都不装_挤出数如实`() {
        val plan = McpToolAssembly.plan(fixture(), 0, emptySet(), emptyMap())
        assertEquals(0, plan.loadedCount)
        assertEquals(192, plan.evictedCount)
        assertEquals(0, plan.usedTokens)
        assertTrue(plan.notice!!.contains("192 个外部工具未装载"))
    }

    @Test
    fun `空候选_零读数`() {
        val plan = McpToolAssembly.plan(emptyList(), 1_000, setOf("mcp_x"), emptyMap())
        assertEquals(0, plan.loadedCount)
        assertNull(plan.notice)
        assertTrue(plan.loadsAll)
    }

    @Test
    fun `重名候选_去重_只装一份`() {
        val plan = McpToolAssembly.plan(
            listOf(candidate(0, 0, "mcp_dup"), candidate(1, 0, "mcp_dup")),
            1_000,
            emptySet(),
            emptyMap(),
        )
        assertEquals(1, plan.loadedCount)
        assertEquals(1, plan.totalCandidates)
    }

    // ── 判据②：pin 必装，哪怕超预算 ─────────────────────────────────────

    @Test
    fun `pin超预算也必装_挤出数不含pin`() {
        // 额度只够 1 具（100），pin 了 3 具（300）⇒ pin 全装、额度爆掉、其余全挤。
        val pins = setOf("mcp_s0_t0", "mcp_s1_t0", "mcp_s2_t0")
        val plan = McpToolAssembly.plan(fixture(), 100, pins, emptyMap())
        assertEquals("pin 三具全在", 3, plan.pinnedLoadedNames.size)
        assertTrue(plan.loadedNames.containsAll(pins))
        assertTrue("pin 装完后已用必然超额度（如实记，不许抹平）", plan.usedTokens > plan.budgetTokens)
        assertEquals("其余 189 具全挤出（pin 不算挤出）", 189, plan.evictedCount)
        assertTrue("说明里报的是 189（不含 pin）", plan.notice!!.contains("189 个外部工具未装载"))
        assertTrue(plan.evictedNames.none { it in pins })
    }

    @Test
    fun `pin不存在的名字_忽略不装`() {
        val plan = McpToolAssembly.plan(fixture(), fixtureTotal, setOf("mcp_ghost"), emptyMap())
        assertTrue(plan.pinnedLoadedNames.isEmpty())
        assertEquals(192, plan.loadedCount)
        assertNull(plan.notice)
    }

    @Test
    fun `pin挤不挤_只看候选里有没有它_预算足够时照装`() {
        val pins = setOf("mcp_s0_t0")
        val plan = McpToolAssembly.plan(fixture(), fixtureTotal, pins, emptyMap())
        assertEquals(listOf("mcp_s0_t0"), plan.pinnedLoadedNames)
        assertEquals(192, plan.loadedCount)
        assertNull("零挤出", plan.notice)
    }

    // ── 判据③：LRU 顺序 ─────────────────────────────────────────────────

    @Test
    fun `LRU_最近用过的先装_久未用的先挤`() {
        val candidates = listOf(
            candidate(0, 0, "mcp_a"),
            candidate(0, 1, "mcp_b"),
            candidate(0, 2, "mcp_c"),
            candidate(0, 3, "mcp_d"),
        )
        // 额度只够 2 具（200）；LRU：a(最新) > b > c > d(无记录=最旧)。
        val plan = McpToolAssembly.plan(
            candidates,
            200,
            emptySet(),
            lru = mapOf("mcp_a" to 3_000L, "mcp_b" to 2_000L, "mcp_c" to 1_000L),
        )
        assertEquals(listOf("mcp_a", "mcp_b"), plan.loadedNames)
        assertEquals(listOf("mcp_c", "mcp_d"), plan.evictedNames)
    }

    @Test
    fun `LRU_没有记录的按连接序兜底`() {
        val candidates = listOf(
            candidate(1, 0, "mcp_b1"),
            candidate(0, 5, "mcp_a5"),
        )
        // 无 LRU 记录 ⇒ 连接序：server 0 在前。
        val plan = McpToolAssembly.plan(candidates, 100, emptySet(), emptyMap())
        assertEquals(listOf("mcp_a5"), plan.loadedNames)
        assertEquals(listOf("mcp_b1"), plan.evictedNames)
    }

    @Test
    fun `LRU_同server内按注册序兜底`() {
        val candidates = listOf(candidate(0, 1, "mcp_t1"), candidate(0, 0, "mcp_t0"))
        val plan = McpToolAssembly.plan(candidates, 100, emptySet(), emptyMap())
        assertEquals("注册序 0 的先装", listOf("mcp_t0"), plan.loadedNames)
    }

    // ── 判据⑤：意图预筛（X/Y 措辞）──────────────────────────────────────

    @Test
    fun `预筛_意图命中者优先装_说明用XY措辞`() {
        val candidates = listOf(
            candidate(0, 0, "mcp_play_music", description = "播放音乐"),
            candidate(0, 1, "mcp_chat", description = "闲聊陪聊"),
            candidate(1, 0, "mcp_search_file", description = "搜索文件内容"),
            candidate(1, 1, "mcp_find_notes", description = "找文件与笔记"),
        )
        // 额度只够 2 具；LRU 全空、连接序固定 ⇒ 不预筛时装 play_music+chat；
        // 意图「搜索文件」 ⇒ 命中的两具抢到额度。
        val plan = McpToolAssembly.plan(
            candidates,
            200,
            emptySet(),
            emptyMap(),
            intentText = "帮我搜索一下文件",
        )
        assertTrue(plan.prefiltered)
        assertTrue(
            "命中意图的工具必须装上",
            plan.loadedNames.containsAll(listOf("mcp_search_file", "mcp_find_notes")),
        )
        assertTrue(
            "X/Y 措辞",
            plan.notice!!.contains("本轮只装了 2/4 个外部工具"),
        )
        assertTrue("挤出不含已装的", plan.evictedNames.none { it in plan.loadedNames })
    }

    @Test
    fun `预筛_装得下时不启用_全装无说明`() {
        val candidates = listOf(candidate(0, 0, "mcp_a", description = "搜索文件"))
        val plan = McpToolAssembly.plan(
            candidates,
            1_000,
            emptySet(),
            emptyMap(),
            intentText = "搜索文件",
        )
        assertTrue("装得下 ⇒ 预筛不开", !plan.prefiltered)
        assertNull(plan.notice)
        assertEquals(1, plan.loadedCount)
    }

    @Test
    fun `预筛_意图一个都没命中_回落LRU连接序_用预算措辞`() {
        val candidates = listOf(
            candidate(0, 0, "mcp_a"),
            candidate(0, 1, "mcp_b"),
        )
        val plan = McpToolAssembly.plan(
            candidates,
            100,
            emptySet(),
            emptyMap(),
            intentText = "完全无关的意图词",
        )
        assertTrue("没有命中 ⇒ 预筛未生效", !plan.prefiltered)
        assertEquals(listOf("mcp_a"), plan.loadedNames)
        assertTrue("回落到预算措辞", plan.notice!!.contains("1 个外部工具未装载"))
    }

    @Test
    fun `预筛_意图命中的LRU也参与排序_更近用过者在同分里在前`() {
        val candidates = listOf(
            candidate(0, 0, "mcp_search_a", description = "搜索东西"),
            candidate(0, 1, "mcp_search_b", description = "搜索东西"),
        )
        // 同分（都命中）⇒ 同分内按 LRU：b 更近 ⇒ b 先。
        val plan = McpToolAssembly.plan(
            candidates,
            100,
            emptySet(),
            lru = mapOf("mcp_search_a" to 1L, "mcp_search_b" to 2L),
            intentText = "搜索",
        )
        assertEquals(listOf("mcp_search_b"), plan.loadedNames)
    }
}
