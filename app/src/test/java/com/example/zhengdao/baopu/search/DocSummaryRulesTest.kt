// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 B①「标题/前 N 行/结构块名/文件:行」的折叠判据（[DocSummaryRules]）——
// 纯函数 JVM 直测；「前 N 行」的 N、块数与行数上限、编码/解码无歧义，都在这里钉死。
package com.example.zhengdao.baopu.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocSummaryRulesTest {

    // ── 前 N 行 ─────────────────────────────────────────────────────────────

    @Test
    fun `前N行_取满N行_尾空行丢弃`() {
        val text = (1..20).joinToString("\n") { "第$it 行" }
        val s = DocSummaryRules.of(text)
        val lines = DocSummaryRules.firstLineList(s.firstLines)
        assertEquals(DocSummaryRules.FIRST_LINES, lines.size)
        assertEquals("第1 行", lines.first())
        assertEquals("第${DocSummaryRules.FIRST_LINES} 行", lines.last())
    }

    @Test
    fun `前N行_超长行截断_不把摘要撑爆`() {
        val longLine = "长".repeat(1000)
        val s = DocSummaryRules.of(longLine)
        val lines = DocSummaryRules.firstLineList(s.firstLines)
        assertEquals(DocSummaryRules.MAX_LINE_CHARS, lines[0].length)
    }

    @Test
    fun `前N行_空正文_给空表不是一行空行`() {
        val s = DocSummaryRules.of("")
        assertTrue(DocSummaryRules.firstLineList(s.firstLines).isEmpty())
    }

    // ── 结构块 ──────────────────────────────────────────────────────────────

    @Test
    fun `结构块_ATX标题带1起行号_非标题不进`() {
        val text = listOf(
            "前言一段", // 1
            "## 世界观", // 2
            "正文", // 3
            "###节气", // 4 —— # 后没空格：不是 ATX 标题
            "####### 七个井号", // 5 —— 超过 6 级：不是
            "#### 灵气潮汐", // 6
        ).joinToString("\n")
        val s = DocSummaryRules.of(text)
        val blocks = DocSummaryRules.blockList(s.blocks)
        assertEquals(listOf(2 to "## 世界观", 6 to "#### 灵气潮汐"), blocks)
    }

    @Test
    fun `结构块_块名里的制表符被替换_解码无歧义`() {
        val s = DocSummaryRules.of("## 带\t制表符的标题")
        val blocks = DocSummaryRules.blockList(s.blocks)
        assertEquals(1, blocks.size)
        assertEquals("## 带 制表符的标题", blocks[0].second)
    }

    @Test
    fun `结构块_超过上限截断_行号不丢`() {
        val many = (1..(DocSummaryRules.MAX_BLOCKS + 5)).joinToString("\n") { "## 标题$it" }
        val s = DocSummaryRules.of(many)
        val blocks = DocSummaryRules.blockList(s.blocks)
        assertEquals(DocSummaryRules.MAX_BLOCKS, blocks.size)
        assertEquals(1, blocks.first().first)
    }

    // ── 标题不抄第二份 / 行数上限写死 ────────────────────────────────────────

    @Test
    fun `摘要层只有两段文本_标题归doc列`() {
        // SummaryText 形状照 doc_summary 两列：first_lines / blocks。标题在 doc.title，
        // 这里多一个字段就是「同一份事实抄两处」，搜索与浏览就会漂。
        assertEquals(
            setOf("firstLines", "blocks"),
            DocSummaryRules.SummaryText::class.java.declaredFields.map { it.name }.filter { !it.contains("$") }.toSet(),
        )
    }

    @Test
    fun `isHeading_边界`() {
        assertTrue(DocSummaryRules.isHeading("# 一级"))
        assertTrue(DocSummaryRules.isHeading("###### 六级"))
        assertTrue(DocSummaryRules.isHeading("   ## 缩进的也算"))
        assertTrue("光井号也是合法空标题", DocSummaryRules.isHeading("##"))
        assertFalse(DocSummaryRules.isHeading("####### 七个"))
        assertFalse(DocSummaryRules.isHeading("#标签 无空格"))
        assertFalse(DocSummaryRules.isHeading("正文"))
    }

    @Test
    fun `规模上限写死_两万行不是开关`() {
        assertEquals(2000, DocSummaryRules.MAX_ROWS)
        assertEquals(12, DocSummaryRules.FIRST_LINES)
    }
}
