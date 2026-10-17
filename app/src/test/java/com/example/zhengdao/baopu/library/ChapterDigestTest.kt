// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChapterDigest 单测（刀 2 追加包；判据追加②）。
 *
 * 钉死：单次上限 20 章（超了停下并报 remaining）、同章重复跑**同一份 entries**
 * （⇒ 配 `INSERT OR IGNORE` 后零新行）、摘要确定性、只要索引模式的占位、
 * 精读模式的 skipped 计数。
 */
class ChapterDigestTest {

    private fun lines(text: String) = text.lines()

    /** 造 N 章的文本与切章结果（中文标记，稳定可预期）。 */
    private fun book(chapterCount: Int): Pair<String, List<Chapter>> {
        val text = (1..chapterCount).joinToString("\n") { "第${cn(it)}章 标题$it\n这是第 $it 章的开头内容。" }
        return text to ChapterSplitter.split(text)
    }

    private fun cn(n: Int): String = when (n) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
        6 -> "六"; 7 -> "七"; 8 -> "八"; 9 -> "九"; 10 -> "十"
        else -> n.toString()
    }

    @Test
    fun `空书_空结果`() {
        val r = ChapterDigest.build("空书", emptyList(), emptyList(), now = 1L)
        assertEquals(0, r.entries.size)
        assertEquals(0, r.total)
        assertEquals(0, r.remaining)
        assertEquals(0, r.skipped)
    }

    @Test
    fun `逐章摘要_每章一条且确定性`() {
        val (text, chapters) = book(3)
        val r1 = ChapterDigest.build("书甲", chapters, lines(text), now = 100L)
        val r2 = ChapterDigest.build("书甲", chapters, lines(text), now = 100L)

        assertEquals(3, r1.entries.size)
        assertEquals(3, r1.total)
        assertEquals(0, r1.remaining)
        assertEquals("同输入必须同输出", r1.entries, r2.entries)
        r1.entries.forEachIndexed { i, e ->
            assertEquals(i, e.chapterNo)
            assertEquals("书甲", e.book)
            assertTrue(e.digest.startsWith(e.title + "｜"))
            assertTrue(e.digest.contains("字｜开头："))
            assertTrue(e.chars > 0)
            assertEquals(100L, e.createdAt)
        }
    }

    @Test
    fun `摘要_首句超80字截断加省略号`() {
        val long = "内".repeat(200)
        val text = "第一章 甲\n$long"
        val chapters = ChapterSplitter.split(text)
        val e = ChapterDigest.build("书", chapters, lines(text), now = 1L).entries.single()
        assertTrue(e.digest.endsWith("…"))
        assertTrue(e.digest.length < 200)
    }

    @Test
    fun `摘要_空章有占位`() {
        val text = "第一章 甲\n   \n第二章 乙\n内容"
        val chapters = ChapterSplitter.split(text)
        val r = ChapterDigest.build("书", chapters, lines(text), now = 1L)
        assertEquals("（空章）", r.entries.single { it.chapterNo == 0 }.digest)
    }

    @Test
    fun `只要索引_摘要占位但行仍在`() {
        val (text, chapters) = book(2)
        val r = ChapterDigest.build("书", chapters, lines(text), now = 1L, withDigest = false)
        assertEquals(2, r.entries.size)
        r.entries.forEach { assertEquals(ChapterDigest.INDEX_ONLY_DIGEST, it.digest) }
    }

    @Test
    fun `单次上限20章_超了停下并报剩余`() {
        val (text, chapters) = book(25)
        assertEquals(25, chapters.size)
        val r = ChapterDigest.build("大书", chapters, lines(text), now = 1L)

        assertEquals(ChapterDigest.MAX_CHAPTERS_PER_RUN, r.entries.size)
        assertEquals(5, r.remaining)
        assertEquals(25, r.total)
        assertEquals(0, r.skipped)
        // 触顶后从第一章顺序取（不跳章）
        assertEquals((0 until 20).toList(), r.entries.map { it.chapterNo })
    }

    @Test
    fun `正好20章不报剩余`() {
        val (text, chapters) = book(20)
        val r = ChapterDigest.build("书", chapters, lines(text), now = 1L)
        assertEquals(20, r.entries.size)
        assertEquals(0, r.remaining)
    }

    @Test
    fun `精读某几章_没选的记skipped不算remaining`() {
        val (text, chapters) = book(5)
        val r = ChapterDigest.build("书", chapters, lines(text), now = 1L, selected = setOf(1, 3))

        assertEquals(2, r.entries.size)
        assertEquals(listOf(1, 3), r.entries.map { it.chapterNo })
        assertEquals(3, r.skipped)
        assertEquals(0, r.remaining)
        assertEquals(5, r.total)
    }

    @Test
    fun `精读选超过20章_同样触顶`() {
        val (text, chapters) = book(25)
        val r = ChapterDigest.build("书", chapters, lines(text), now = 1L, selected = (0 until 25).toSet())
        assertEquals(20, r.entries.size)
        assertEquals(5, r.remaining)
    }

    @Test
    fun `精读选空集_一条都不处理`() {
        val (text, chapters) = book(3)
        val r = ChapterDigest.build("书", chapters, lines(text), now = 1L, selected = emptySet())
        assertTrue(r.entries.isEmpty())
        assertEquals(3, r.skipped)
        assertEquals(3, r.total)
    }

    @Test
    fun `同章重复跑_entries逐字节相同_配ORIGNORE即零新行`() {
        val (text, chapters) = book(2)
        val r1 = ChapterDigest.build("书", chapters, lines(text), now = 111L)
        val r2 = ChapterDigest.build("书", chapters, lines(text), now = 111L)
        // 追加包判据②的可测部分：两轮产出逐字段相同 ⇒ INSERT OR IGNORE 后零新行
        assertEquals(r1.entries, r2.entries)
        // sha 由章文本决定（同章同内容 ⇒ 同 sha ⇒ 三元组撞上）
        assertEquals(r1.entries.map { it.sha256 }, r2.entries.map { it.sha256 })
        assertTrue(r1.entries.isNotEmpty())
    }

    // ── SQL 文本契约 ──────────────────────────────────────────────────────

    @Test
    fun `落库语句_ORIGNORE加七参`() {
        val (text, chapters) = book(1)
        val e = ChapterDigest.build("书", chapters, lines(text), now = 5L).entries.single()
        val s = ChapterDigest.Sql.insertOrIgnore(e)

        assertTrue(s.sql.startsWith("INSERT OR IGNORE INTO chapter_index"))
        assertEquals(7, s.args.size)
        assertEquals("书", s.args[0])
        assertEquals("0", s.args[1])
        assertEquals("5", s.args[6])
    }

    @Test
    fun `取最新_按章分组MAXid`() {
        val s = ChapterDigest.Sql.latestChapters("书")
        assertTrue(s.sql.contains("MAX(id)"))
        assertTrue(s.sql.contains("GROUP BY chapter_no"))
        assertTrue(s.sql.contains("ORDER BY c.chapter_no"))
        assertEquals(arrayOf("书", "书"), s.args)
    }

    @Test
    fun `已索引章数_去重计数`() {
        val s = ChapterDigest.Sql.indexedChapterCount("书")
        assertTrue(s.sql.contains("COUNT(DISTINCT chapter_no)"))
        assertEquals(arrayOf("书"), s.args)
    }

    @Test
    fun `建表_唯一三元组`() {
        val ddl = com.example.zhengdao.baopu.BaopuSql.CHAPTER_INDEX_DDL.joinToString("\n")
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS chapter_index"))
        assertTrue(ddl.contains("UNIQUE(book, chapter_no, sha256)"))
        assertTrue(ddl.contains("CREATE INDEX IF NOT EXISTS idx_chapter_index_book"))
    }
}
