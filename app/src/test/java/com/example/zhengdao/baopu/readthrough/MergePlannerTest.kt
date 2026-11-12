// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分层归并规划器单测（610 判据②：3000 章夹具验证层数；**不许一次合并 1700 片**）。
 *
 * 钉住的判据：① 任何一次归并调用的输入 ≤ 100（结构性，不是靠自觉）；
 * ② 3000 章 ⇒ 4 层（片→章→块→书），块层 30 次调用、书层 1 次；
 * ③ 单片章不出章层调用；④ 超限就地再分层（250 片的章 / 150 块的书）。
 */
class MergePlannerTest {

    // ── 判据②：3000 章夹具 ──────────────────────────────────────────────────

    @Test
    fun `三千章夹具_层数为4_任何一次归并不超100输入`() {
        // 3000 章：其中 100 章各 3 片（章层真的要干活），其余 1 片
        val chapters = (0 until 3000).map { if (it % 30 == 0) it to 3 else it to 1 }
        val plan = MergePlanner.plan(chapters)
        assertEquals("3000 章应为 4 层（片→章→块→书）", 4, plan.layers)
        assertTrue(
            "单次归并最大输入必须 ≤100（不许一次合并 1700 片）：${plan.maxInputs}",
            plan.maxInputs <= 100,
        )
        val blocks = plan.calls.filter { it.kind == MergeCall.KIND_BLOCK }
        assertEquals("块层应 30 次调用（每 100 章）", 30, blocks.size)
        blocks.forEach { assertEquals(100, it.inputIds.size) }
        val book = plan.calls.filter { it.kind == MergeCall.KIND_BOOK }
        assertEquals("书层 1 次调用", 1, book.size)
        assertEquals(30, book[0].inputIds.size)
        // 章层调用只出现在 3 片的章
        val chapterCalls = plan.calls.filter { it.kind == MergeCall.KIND_CHAPTER }
        assertEquals(100, chapterCalls.size)
        chapterCalls.forEach { assertEquals(3, it.inputIds.size) }
        // 片 id 与 ReadSlice.id 同口径（章号用真实章号）
        val anyChapterCall = chapterCalls.first()
        val ci = anyChapterCall.outputId.removePrefix("c").toInt()
        assertEquals((0 until 3).map { "c${ci}t$it" }, anyChapterCall.inputIds)
    }

    // ── ③ 单片章不出章层调用 ────────────────────────────────────────────────

    @Test
    fun `五章各一片_只有块层与书层各一次`() {
        val plan = MergePlanner.plan((0 until 5).map { it to 1 })
        assertEquals(listOf(MergeCall.KIND_BLOCK, MergeCall.KIND_BOOK), plan.calls.map { it.kind })
        assertEquals(5, plan.calls[0].inputIds.size)
        // 片→(章)→块→书：章层空转也算层位，仍是 4 层
        assertEquals(4, plan.layers)
        assertEquals(5, plan.maxInputs)
    }

    // ── ④ 章内 250 片：章层就地分层，不超 100 ────────────────────────────────

    @Test
    fun `一章二百五十片_章层递归分组_不超一百输入`() {
        val plan = MergePlanner.plan(listOf(0 to 250))
        val chapterCalls = plan.calls.filter { it.kind == MergeCall.KIND_CHAPTER }
        // L1: 3 次（100+100+50）→ L2: 1 次（3 个组产物 → 1 章产物）
        assertEquals(4, chapterCalls.size)
        assertEquals(3, chapterCalls.count { it.layer == 1 })
        assertEquals(1, chapterCalls.count { it.layer == 2 })
        chapterCalls.forEach { assertTrue(it.inputIds.size <= 100) }
        // 块层接在章层之后
        val block = plan.calls.filter { it.kind == MergeCall.KIND_BLOCK }
        assertEquals(1, block.size)
        assertEquals(3, block[0].layer)
        assertTrue("层数 = 1 + 最深层", plan.layers >= 4)
    }

    // ── ④b 书层 150 块：就地再分层 ──────────────────────────────────────────

    @Test
    fun `一万五千章_书层递归分组_不超一百输入`() {
        val plan = MergePlanner.plan((0 until 15000).map { it to 1 })
        val blocks = plan.calls.filter { it.kind == MergeCall.KIND_BLOCK }
        assertEquals(150, blocks.size)
        val bookCalls = plan.calls.filter { it.kind == MergeCall.KIND_BOOK }
        // 150 块 → L3: 2 次（100+50）→ L4: 1 次（2 个组产物 → book）
        assertEquals(3, bookCalls.size)
        assertTrue(bookCalls.all { it.inputIds.size <= 100 })
        assertEquals("书层最后一次合拢 2 个组产物", 2, bookCalls.last().inputIds.size)
        assertEquals("book 产物落在最深层", bookCalls.last().layer, plan.layers - 1)
        assertTrue(plan.maxInputs <= 100)
    }

    // ── 边界：空书 / 100 章整 ───────────────────────────────────────────────

    @Test
    fun `空书_无调用`() {
        val plan = MergePlanner.plan(emptyList())
        assertTrue(plan.calls.isEmpty())
        assertEquals(1, plan.layers)
    }

    @Test
    fun `恰好一百章_一个块_书层把块摘要整理成全书`() {
        val plan = MergePlanner.plan((0 until 100).map { it to 1 })
        // 100 章 → 1 个块；书层照常出调用（book.jsonl 是最终交付物，1 个块也合）
        assertEquals(listOf(MergeCall.KIND_BLOCK, MergeCall.KIND_BOOK), plan.calls.map { it.kind })
        assertEquals(100, plan.calls[0].inputIds.size)
        assertEquals(listOf("b0"), plan.calls[1].inputIds)
        assertEquals(100, plan.maxInputs)
        assertEquals(4, plan.layers)
    }
}
