// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.readthrough

import com.example.zhengdao.baopu.library.ChapterSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通读切片器单测（610 判据①⑧）。
 *
 * 钉住的判据：① 超长章按段切到 ≤6k 字；② 切点不跨对话段（对话连段是原子）；
 * ③ 切不开的（整场超长对话/超长单段）块内下刀且异常**逐条报**；④ 空章出一片
 * （覆盖率分母不缺章）＋异常；⑤ 行号 0 起含端点、与 read_file 同口径。
 */
class ReadthroughSlicerTest {

    private fun split(text: String) = ChapterSplitter.split(text)
    private fun slice(text: String, maxChars: Int = ReadthroughSlicer.MAX_SLICE_CHARS) =
        ReadthroughSlicer.sliceBook(text.lines(), split(text), maxChars)

    // ── ① 短章整章一片，不下刀 ──────────────────────────────────────────────

    @Test
    fun `短章_整章一片_行号含标题行`() {
        val book = ReadthroughFixtures.standardBook(3)
        val r = slice(book)
        assertEquals(3, r.slices.size)
        val ch0 = r.slices.first { it.chapterIndex == 0 }
        assertEquals(1, ch0.sliceCount)
        assertEquals(0, ch0.startLine)
        assertEquals(2, ch0.endLine)
        assertTrue(r.anomalies.isEmpty())
        // 行号与 read_file 同口径：textOf 拼回 = 原文那几行
        assertEquals(book.lines().subList(0, 3).joinToString("\n"), ch0.textOf(book.lines()))
    }

    // ── ② 超长章按段收片，每片 ≤6k ──────────────────────────────────────────

    @Test
    fun `超长章_按段收片_每片不超上限`() {
        // 一章 = 30 段 × 500 字 ≈ 15k+ ⇒ 3 片
        val book = "第一章 长章\n" + (1..30).joinToString("") {
            "段${it}，" + "文".repeat(496) + '\n'
        }
        val r = slice(book)
        val chapterSlices = r.slices.filter { it.chapterIndex == 0 }
        assertTrue("应切成 ≥2 片：${chapterSlices.size}", chapterSlices.size >= 2)
        chapterSlices.forEach { s ->
            val chars = s.textOf(book.lines()).length
            assertTrue("片 ${s.id} 超上限：$chars", chars <= ReadthroughSlicer.MAX_SLICE_CHARS)
        }
        // 片拼回 = 整章（不丢行，含末尾空行）
        val joined = chapterSlices.joinToString("\n") { it.textOf(book.lines()) }
        assertEquals(book.lines().joinToString("\n"), joined)
        assertEquals(chapterSlices.size, chapterSlices.first().sliceCount)
        // sliceIndex 连续
        assertEquals(chapterSlices.indices.toList(), chapterSlices.map { it.sliceIndex })
    }

    // ── ③ 切点不跨对话段（对话连段是原子） ──────────────────────────────────

    @Test
    fun `切点不落在对话与对话之间`() {
        // 叙述 5×800 + 对话 4×300（连段 ≈1.2k 原子）+ 叙述 5×800 ≈ 9.2k ⇒ 必切；
        // 切点只许落在叙述段边界，不许落在对话连段内部
        val book = ReadthroughFixtures.mixedChapter(
            narr1 = 5, dialogue = 4, dialogueChars = 296, narr2 = 5, narrChars = 800,
        )
        val r = slice(book)
        val chapterSlices = r.slices.filter { it.chapterIndex == 0 }
        assertTrue("应切成 ≥2 片：${chapterSlices.size}", chapterSlices.size >= 2)
        chapterSlices.zipWithNext().forEach { (a, b) ->
            val lastA = a.textOf(book.lines()).lineSequence().last { it.isNotBlank() }
            val firstB = b.textOf(book.lines()).lineSequence().first { it.isNotBlank() }
            val splitInsideDialogue =
                ReadthroughSlicer.isDialogue(lastA) && ReadthroughSlicer.isDialogue(firstB)
            assertTrue(
                "切点落在对话与对话之间（片 ${a.id}|${b.id}）：「$lastA」|「$firstB」",
                !splitInsideDialogue,
            )
        }
        // 对话连段必须完整地待在同一片里（4 段引号行同片）
        val dialogueSlices = chapterSlices.filter { s ->
            s.textOf(book.lines()).lineSequence().any { ReadthroughSlicer.isDialogue(it) }
        }
        assertTrue("对话连段不该散到多片：$dialogueSlices", dialogueSlices.size == 1)
        assertTrue("对话连段是原子的 ⇒ 不应有对话切分异常：${r.anomalies}", r.anomalies.isEmpty())
    }

    @Test
    fun `引号未闭合的段接住下一段_不在中间切`() {
        // 前段引号开了没关（300 字），后段叙述形状（300 字）⇒ 同一场话，合成原子块
        val book = buildString {
            append("第一章 续引号\n")
            append("「引号开到一半没关，" + "续".repeat(296) + '\n')
            append("下一段接着说的事，" + "续".repeat(296) + '\n')
            (1..6).forEach { append("叙述垫高，" + "述".repeat(296) + '\n') }
        }
        assertTrue(ReadthroughSlicer.quoteBalanced("「关了」"))
        assertTrue(!ReadthroughSlicer.quoteBalanced("「没关"))
        val r = slice(book, maxChars = 1200) // 压上限逼出切点
        val lines = book.lines()
        val unclosedIdx = 1 // 「引号开到一半没关… 所在行
        val slicesWithUnclosed = r.slices.filter { s ->
            (s.startLine..s.endLine).contains(unclosedIdx)
        }
        assertEquals("未闭合引号行只会在一片里", 1, slicesWithUnclosed.size)
        assertTrue(
            "接话的下一段要跟未闭合行同片（切点不落中间）",
            slicesWithUnclosed[0].endLine >= 2,
        )
        // 该片没被拦腰截（块 ≤ 上限 ⇒ 不需要块内下刀）
        assertTrue(!slicesWithUnclosed[0].midLine)
        assertTrue(lines[unclosedIdx].startsWith("「"))
    }

    // ── ④ 切不开的块：块内下刀＋异常逐条报 ──────────────────────────────────

    @Test
    fun `整场超长对话_块内下刀并报异常`() {
        // 12 段对话 × 600 字 = 7.2k > 6k ⇒ 对话连段原子但超限 ⇒ 块内下刀 + DIALOGUE_RUN_CUT
        val book = ReadthroughFixtures.mixedChapter(narr1 = 0, dialogue = 12, dialogueChars = 596, narr2 = 0)
        val r = slice(book)
        val anomaly = r.anomalies.firstOrNull { it.kind == ReadthroughSlicer.ANOMALY_DIALOGUE_RUN_CUT }
        assertNotNull("超长对话块必须报异常：${r.anomalies.map { it.render() }}", anomaly)
        val slices = r.slices.filter { it.chapterIndex == 0 }
        assertTrue(slices.size >= 2)
        slices.forEach { s ->
            assertTrue(
                "块内下刀也要 ≤上限：${s.textOf(book.lines()).length}",
                s.textOf(book.lines()).length <= ReadthroughSlicer.MAX_SLICE_CHARS,
            )
        }
        assertTrue("异常要能追到章与行：${anomaly!!.render()}", anomaly.render().contains("第0章"))
    }

    @Test
    fun `超长单段_句边界切并报异常_中间片如实标 midLine`() {
        val book = ReadthroughFixtures.giantLineChapter(15000)
        val r = slice(book)
        val anomaly = r.anomalies.firstOrNull { it.kind == ReadthroughSlicer.ANOMALY_MID_PARAGRAPH_CUT }
        assertNotNull("超长单段必须报异常", anomaly)
        val slices = r.slices.filter { it.chapterIndex == 0 }
        assertTrue(slices.size >= 3)
        // 单行被行内切成句边界的片：恰好 2 片标 midLine（行内截断），每片 ≤上限
        assertEquals(2, slices.count { it.midLine })
        slices.forEach { s ->
            assertTrue(
                "句边界切也要 ≤上限：${s.textOf(book.lines()).length}",
                s.textOf(book.lines()).length <= ReadthroughSlicer.MAX_SLICE_CHARS,
            )
        }
        // midLine 片是同一行（行号口径不变），且以句号收尾（句边界）
        val mid = slices.filter { it.midLine }
        assertTrue(mid.all { it.startLine == mid.first().startLine && it.endLine == mid.first().endLine })
        mid.forEach { s -> assertTrue("切点应是句边界", s.textOf(book.lines()).endsWith("。")) }
        // 不丢内容：midLine 片的字符区间拼回 = 原行
        val joined = mid.joinToString("") { it.textOf(book.lines()) }
        assertEquals(12000, joined.length)
    }

    // ── ⑤ 空章：出一片 + 异常 ───────────────────────────────────────────────

    @Test
    fun `空章_仍出一片并报异常_覆盖率分母不缺章`() {
        val book = "第一章 空章\n\n\n\n第二章 有货\n正文\n"
        val r = slice(book)
        assertEquals(2, r.slices.size)
        val empty = r.slices.first { it.chapterIndex == 0 }
        assertEquals(0, empty.startLine)
        assertEquals(3, empty.endLine)
        assertTrue(
            r.anomalies.any { it.kind == ReadthroughSlicer.ANOMALY_EMPTY_CHAPTER && it.chapterIndex == 0 },
        )
    }

    // ── ⑥ 全书切片总量与章数对账 ────────────────────────────────────────────

    @Test
    fun `标准书_每章一片_总量对账`() {
        val book = ReadthroughFixtures.bigBook(50)
        val r = slice(book)
        assertEquals(50, r.slices.size)
        assertEquals((0..49).toList(), r.slices.map { it.chapterIndex }.distinct().sorted())
        assertTrue(r.anomalies.isEmpty())
    }
}
