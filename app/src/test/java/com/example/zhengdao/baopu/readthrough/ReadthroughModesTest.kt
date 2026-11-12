// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选章与预算单测（610 判据③⑤）。
 *
 * 钉住的判据：③ 略读只抽相关章且**必须列出漏掉的章**（全集−选中，一个不少）；
 * ⑤ 预算开跑前算 {片数, 调用数, token}，任何一项超限 ⇒ 拒绝且原因逐条。
 */
class ReadthroughModesTest {

    private val briefs = listOf(
        ChapterBrief(0, "第一章 初入宗门", "林惊羽拜入青云门，遇张小凡"),
        ChapterBrief(1, "第二章 后山禁地", "张小凡夜探后山，遇神秘老者"),
        ChapterBrief(2, "第三章 魔教来袭", "魔教夜袭青云，张小凡初显身手"),
        ChapterBrief(3, "第四章 师门日常", "张小凡练功做饭，田灵儿相伴"),
    )

    // ── 略读选章 ────────────────────────────────────────────────────────────

    @Test
    fun `略读_命中标题或首句_漏章逐章列出`() {
        // 只查「魔教」：命中第 2 章（标题）；第 1 章首句虽提张小凡但不查这个词
        val s = SkimPlanner.select("魔教", briefs)
        assertEquals(listOf(2), s.selected)
        assertEquals("漏章 = 全集−选中，不许只报个数", listOf(0, 1, 3), s.missed)
        // 关键词打到首句：第 1 章的 preview 有「神秘老者」
        val s2 = SkimPlanner.select("魔教 神秘老者", briefs)
        assertEquals(listOf(1, 2), s2.selected)
        assertEquals(listOf(0, 3), s2.missed)
    }

    @Test
    fun `略读_空查询_全部列为漏章`() {
        val s = SkimPlanner.select("   ", briefs)
        assertTrue(s.selected.isEmpty())
        assertEquals(listOf(0, 1, 2, 3), s.missed)
    }

    @Test
    fun `略读_一个不中_漏章是全集`() {
        val s = SkimPlanner.select("灵潮复苏", briefs)
        assertTrue(s.selected.isEmpty())
        assertEquals(4, s.missed.size)
    }

    @Test
    fun `漏章渲染_压成区间串但不丢章`() {
        assertEquals("3–50、52、55–99", SkimPlanner.renderMissed((3..50).toList() + listOf(52) + (55..99).toList()))
        assertEquals("（无）", SkimPlanner.renderMissed(emptyList()))
        assertEquals("7", SkimPlanner.renderMissed(listOf(7)))
    }

    // ── 预算估算与判定 ──────────────────────────────────────────────────────

    @Test
    fun `预算估算_调用数=抽取+归并_token=输入+输出`() {
        // 10 片共 60000 字 + 12 次归并
        val e = BudgetCheck.estimate(10, 60_000, 12)
        assertEquals(10, e.sliceCount)
        assertEquals(22, e.totalCalls)
        assertEquals(60_000L + 300L * 22, e.inputTokens)
        assertEquals(1_500L * 22, e.outputTokens)
        assertEquals(e.inputTokens + e.outputTokens, e.totalTokens)
    }

    @Test
    fun `预算_都在限内_允许`() {
        val v = BudgetCheck.check(BudgetCheck.estimate(10, 60_000, 12), BudgetLimits(50, 100_000L))
        assertTrue(v is BudgetVerdict.Allow)
    }

    @Test
    fun `预算_恰好压线_允许_含边界`() {
        val e = BudgetCheck.estimate(10, 60_000, 12)
        val v = BudgetCheck.check(e, BudgetLimits(e.totalCalls, e.totalTokens))
        assertTrue("等于上限不算超额", v is BudgetVerdict.Allow)
    }

    @Test
    fun `预算_超限_拒绝且原因逐条`() {
        val v = BudgetCheck.check(BudgetCheck.estimate(10, 60_000, 12), BudgetLimits(5, 1_000L))
        val deny = v as BudgetVerdict.Deny
        assertEquals(2, deny.reasons.size)
        assertTrue(deny.reasons[0].contains("调用数"))
        assertTrue(deny.reasons[0].contains("22"))
        assertTrue(deny.reasons[1].contains("token"))
    }
}
