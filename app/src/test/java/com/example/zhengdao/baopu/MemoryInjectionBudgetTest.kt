// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十一批 §3/§4：注入排序（分数/新鲜度/相关性）、超预算截断、不删除、稳定排序。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryInjectionBudgetTest {

    private val now = 1_800_000_000_000L

    private fun mem(
        id: String,
        title: String = "标题$id",
        body: String = "正文$id",
        updatedAt: Long = now,
        createdAt: Long = updatedAt,
        useCount: Int = 0,
        tags: List<String> = emptyList(),
    ) = MemoryEntry(
        id = id,
        scope = MemoryScope.GLOBAL,
        kind = MemoryKind.FACT,
        title = title,
        body = body,
        tags = tags,
        sourceSession = "test",
        status = MemoryStatus.CONFIRMED,
        createdAt = createdAt,
        updatedAt = updatedAt,
        useCount = useCount,
    )

    private fun ids(list: List<MemoryEntry>): List<String> = list.map { it.id }

    // ── 排序 ─────────────────────────────────────────────────────────────

    @Test
    fun `稳定排序_更新时间_创建时间_id逐级平手`() {
        val a = mem("a", updatedAt = now)
        val b = mem("b", updatedAt = now + 10)
        val c = mem("c", updatedAt = now + 10)
        val d = mem("d", updatedAt = now + 10, createdAt = now - 10)
        val ranked = MemoryInjectionBudget.rank(listOf(c, a, d, b), now).map { it.entry.id }
        assertEquals("同分先看更新时间新的，再按 id —— 与输入顺序无关", listOf("b", "c", "d", "a"), ranked)
    }

    @Test
    fun `新鲜度_无查询时新记忆优先`() {
        val old = mem("old", updatedAt = now - 30L * 24 * 60 * 60 * 1000)
        val fresh = mem("fresh", updatedAt = now)
        assertEquals(listOf("fresh", "old"), ids(MemoryInjectionBudget.plan(listOf(old, fresh), now).injected))
    }

    @Test
    fun `相关性_查询命中标签的旧记忆排到最新之前`() {
        val oldHit = mem("hit", updatedAt = now - 90L * 24 * 60 * 60 * 1000, tags = listOf("三国"))
        val freshMiss = mem("miss", updatedAt = now)
        val plan = MemoryInjectionBudget.plan(listOf(freshMiss, oldHit), now, query = "三国")
        assertEquals(listOf("hit", "miss"), ids(plan.injected))
    }

    @Test
    fun `使用分_同时间戳时用得多排前`() {
        val often = mem("often", useCount = 9)
        val never = mem("never", useCount = 0)
        assertEquals(listOf("often", "never"), ids(MemoryInjectionBudget.plan(listOf(never, often), now).injected))
    }

    // ── 条数预算 ─────────────────────────────────────────────────────────

    @Test
    fun `超条数_只注入上限_其余一条不少`() {
        val all = (1..5).map { mem("m$it") }
        val plan = MemoryInjectionBudget.plan(
            all,
            now,
            config = MemoryInjectionBudget.Config(maxItems = 2, maxChars = 100_000),
        )
        assertEquals(2, plan.injected.size)
        assertEquals(3, plan.omitted.size)
        assertEquals("注入 + 未注入 = 全部候选（没有删除）", ids(all).toSet(), (ids(plan.injected) + ids(plan.omitted)).toSet())
        assertTrue(plan.truncated)
        assertTrue(plan.note.contains("没有删除"))
    }

    // ── 字符预算 ─────────────────────────────────────────────────────────

    @Test
    fun `超字符预算_跳过装不下的继续找短的`() {
        val big = mem("big", title = "大", body = "正".repeat(400), updatedAt = now + 20)
        val small1 = mem("s1", title = "小一", body = "正文", updatedAt = now + 10)
        val small2 = mem("s2", title = "小二", body = "正文", updatedAt = now)
        val smallCost = MemoryRules.renderForContext(small1).length
        assertEquals(MemoryRules.renderForContext(small2).length, smallCost)
        val plan = MemoryInjectionBudget.plan(
            listOf(big, small1, small2),
            now,
            config = MemoryInjectionBudget.Config(maxItems = 10, maxChars = smallCost * 2),
        )
        assertEquals("大条装不下被跳过，后两条短的仍装进来", listOf("s1", "s2"), ids(plan.injected))
        assertEquals(listOf("big"), ids(plan.omitted))
        assertEquals(smallCost * 2, plan.usedChars)
        assertTrue(plan.totalChars > plan.usedChars)
    }

    @Test
    fun `字符预算_刚好等于也能装下`() {
        val only = mem("only", title = "标题", body = "正文")
        val cost = MemoryRules.renderForContext(only).length
        val plan = MemoryInjectionBudget.plan(
            listOf(only),
            now,
            config = MemoryInjectionBudget.Config(maxItems = 10, maxChars = cost),
        )
        assertEquals(listOf("only"), ids(plan.injected))
        assertEquals(cost, plan.usedChars)
        assertFalse(plan.truncated)
    }

    @Test
    fun `空候选_空计划`() {
        val plan = MemoryInjectionBudget.plan(emptyList(), now)
        assertTrue(plan.injected.isEmpty())
        assertTrue(plan.omitted.isEmpty())
        assertEquals(0, plan.usedChars)
        assertEquals("", plan.note)
        assertFalse(plan.truncated)
    }

    // ── 默认值 ───────────────────────────────────────────────────────────

    @Test
    fun `默认配置_条数与字符预算固定`() {
        val c = MemoryInjectionBudget.Config()
        assertEquals(MemoryInjectionBudget.DEFAULT_MAX_ITEMS, c.maxItems)
        assertEquals(MemoryInjectionBudget.DEFAULT_MAX_CHARS, c.maxChars)
        assertEquals(MemoryInjectionBudget.FRESHNESS_HALF_LIFE_MS, c.freshHalfLifeMs)
    }
}
