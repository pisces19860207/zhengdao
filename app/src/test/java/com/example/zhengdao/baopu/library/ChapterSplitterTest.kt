// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ChapterSplitter 纯函数单测（刀 2 追加包；判据追加②）。
 *
 * 三条规则各至少一条（派工单原话级）：中文章节标记（第…章/节/回）、
 * 英文 `Chapter N`、固定字数兜底；外加空文件与「卷首」章。
 */
class ChapterSplitterTest {

    private val opts = SplitOptions(fallbackChars = 200) // 200 是 splitByChars 的防呆下限

    private fun split(text: String) = ChapterSplitter.split(text, opts)

    /** 按行切片（textOf 收 List<String>；与 split 内部的 text.lines() 同一把尺子）。 */
    private fun textOf(text: String, c: Chapter): String = c.textOf(text.lines())

    @Test
    fun `空文件_空结果`() {
        assertTrue(split("").isEmpty())
        assertTrue(split("   \n\n  ").isEmpty())
    }

    @Test
    fun `中文章节标记_按章切且行号连续`() {
        val text = """
            第一章 起点
            第一行内容
            第二行内容
            第二章 发展
            第三行内容
            第三章 结局
            第四行内容
        """.trimIndent()

        val chapters = split(text)

        assertEquals(3, chapters.size)
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals("第一章 起点", chapters[0].title)
        assertEquals("第二章 发展", chapters[1].title)
        assertEquals("第三章 结局", chapters[2].title)
        // 行号：第一章 = 0-2 行，第二章 = 3-4，第三章 = 5-6（章末行 = 下一标记行 − 1）
        assertEquals(0, chapters[0].startLine)
        assertEquals(2, chapters[0].endLine)
        assertEquals(3, chapters[1].startLine)
        assertEquals(4, chapters[1].endLine)
        assertEquals(5, chapters[2].startLine)
        assertEquals(6, chapters[2].endLine)
    }

    @Test
    fun `中文节与回也算标记`() {
        val text = "第一节 甲\n内容\n第二节 乙\n内容\n第三回 丙\n内容"
        val chapters = split(text)
        assertEquals(3, chapters.size)
        assertTrue(chapters[0].title.startsWith("第一节"))
        assertTrue(chapters[2].title.startsWith("第三回"))
    }

    @Test
    fun `标记只认行首_正文句中的第三章不误切`() {
        val text = "他翻到了第三章，然后继续读。\n后面还有内容。"
        val chapters = split(text)
        // 没有行首标记 ⇒ 走兜底（不按正文里的「第三章」切）
        assertTrue(chapters.all { !it.title.contains("第三章") })
        assertTrue(chapters.isNotEmpty())
    }

    @Test
    fun `首个标记前的非空白内容独立成卷首章`() {
        val text = "前言：这本书讲的是甲。\n\n第一章 起点\n内容"
        val chapters = split(text)

        assertEquals(2, chapters.size)
        assertEquals("（卷首）", chapters[0].title)
        assertTrue(textOf(text, chapters[0]).contains("前言"))
        assertEquals("第一章 起点", chapters[1].title)
    }

    @Test
    fun `英文ChapterN_按章切`() {
        val text = "Chapter 1 Begin\nline one\nline two\nChapter 2 Middle\nline three"
        val chapters = split(text)

        assertEquals(2, chapters.size)
        assertTrue(chapters[0].title.lowercase().startsWith("chapter 1"))
        assertTrue(chapters[1].title.lowercase().startsWith("chapter 2"))
        assertEquals(0, chapters[0].startLine)
        assertEquals(2, chapters[0].endLine)
    }

    @Test
    fun `英文chapter大小写不敏感`() {
        val chapters = split("chapter 1 A\nx\nCHAPTER 2 B\ny")
        assertEquals(2, chapters.size)
    }

    @Test
    fun `无标记_按固定字数兜底切且不丢内容`() {
        // 每行 6 字；预算 200 ⇒ 每章约 28 行。100 行 ⇒ 4 章左右
        val lines = (1..100).map { "第%02d行内容".format(it) }
        val text = lines.joinToString("\n")
        val chapters = split(text)

        assertTrue("预期 3 章以上，实际 ${chapters.size}", chapters.size >= 3)
        // 不丢内容：所有章拼回去等于原文（章区间连续覆盖全部行）
        val rebuilt = chapters.joinToString("\n") { textOf(text, it) }
        assertEquals(text, rebuilt)
        // 标题是「第 N 节」形态
        assertTrue(chapters.first().title.startsWith("第 1 节"))
    }

    @Test
    fun `兜底_单行超预算不切断`() {
        val long = "x".repeat(500)
        val chapters = split("$long\n短行")
        // 那一长行独占一章（宁可超标也不把一行劈开）
        assertTrue(chapters.any { textOf("$long\n短行", it) == long })
    }

    @Test
    fun `兜底_空白行不单独成章`() {
        val text = "\n\n\n内容一\n\n\n内容二\n\n\n"
        val chapters = split(text)
        assertTrue(chapters.isNotEmpty())
        chapters.forEach { c ->
            assertFalse("空白章不许出现：${c.title}", textOf(text, c).isBlank())
        }
        // 内容不丢
        assertTrue(chapters.joinToString("") { textOf(text, it) }.contains("内容一"))
        assertTrue(chapters.joinToString("") { textOf(text, it) }.contains("内容二"))
    }

    @Test
    fun `标题超长截断`() {
        val title = "第" + "一".repeat(200) + "章"
        val chapters = split("$title\n内容")
        assertTrue(chapters.single().title.length <= 60)
    }

    @Test
    fun `textOf_按行号切片`() {
        val text = "a\nb\nc\nd"
        val chapter = Chapter(index = 0, title = "t", startLine = 1, endLine = 2)
        assertEquals("b\nc", chapter.textOf(text.lines()))
    }
}
