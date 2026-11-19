// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百四十五批 · 判据①（docx 往返）＋ 判据③的 docx 部分（格式 XML 断言）。
//
// 往返定义：DocxWriter 真生成 → readDocxFacts 解包读回 → 字段逐项对拍 →
// DocxTemplate 改写占位符 → 再读回 → 结构字段不因改写而漂移。
// 一致性用「字段集合完全相等」判定（100%），不是「文件含某字面量」的恒真断言。
package com.example.zhengdao.baopu.office.officebaseline

import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.office.DocxTemplate
import com.example.zhengdao.baopu.office.Step
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocxRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        FileOpLog.resetForTest()
        FileOpLog.enterSession(745L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    private fun produce(): File {
        val out = DocxWriteTool.run(docxArgs(), ws)
        assertTrue("生成应成功：$out", out is ToolOutcome.Ok)
        val all = File(ws, "产物").listFiles().orEmpty().filter { it.name.endsWith(".docx") }
        assertEquals("恰好一份 docx", 1, all.size)
        return all.first()
    }

    // ── 判据①：生成 → 读回 → 结构字段 100% 一致 ───────────────────────────

    @Test
    fun `判据1_docx生成读回_五类结构字段逐项对得上`() {
        val facts = readDocxFacts(produce().readBytes())

        assertEquals("段落数（表格后空段也算 body 直属）", 12, facts.bodyParagraphs)
        assertEquals("标题层级 1/2/3", listOf(1, 2, 3), facts.headingLevels)
        assertEquals("列表用到 numId 1(无序)/2/3(有序)", listOf(1, 2, 3), facts.usedNumIds)
        assertEquals("列表条目 3+2+2=7", 7, facts.listItemCount)
        assertEquals("一张表的数据行（不含表头）", listOf(2), facts.tableRows)
        assertEquals("表格 3 列", listOf(3), facts.tableCols)
        // document.xml 里的 w:b：表头 3 格 + 正文「营收」1 个 = 4（标题样式粗体在 styles.xml，不在此数）
        assertEquals("加粗 run=表头3+行内1", 4, facts.boldRuns)
        assertTrue("用到了 Heading 样式", facts.styleIds.any { it.startsWith("Heading") })
        assertTrue("用到了 ListParagraph 样式", "ListParagraph" in facts.styleIds)
    }

    @Test
    fun `判据1_改写后再读回_结构与字段不漂移`() {
        val first = readDocxFacts(produce().readBytes())

        // 改写：用项目已有的 DocxTemplate 真替换占位符（不是重写一份）
        val bytes = File(ws, "产物").listFiles()!!.first { it.name.endsWith(".docx") }.readBytes()
        val applied = DocxTemplate.apply(bytes, mapOf("Amount" to "12.5"))
        assertTrue("改写应成功：$applied", applied is Step.Ok)
        val changed = applied.valueOrNull() ?: throw AssertionError("改写失败：$applied")
        assertEquals("点名被替换的占位符", listOf("Amount"), changed.fields)
        assertEquals("替换一处", 1, changed.replaced)

        val second = readDocxFacts(changed.bytes)
        // 结构字段一个都不许因改写而变
        assertEquals("段落数", first.bodyParagraphs, second.bodyParagraphs)
        assertEquals("标题层级", first.headingLevels, second.headingLevels)
        assertEquals("列表编号", first.usedNumIds, second.usedNumIds)
        assertEquals("列表条目", first.listItemCount, second.listItemCount)
        assertEquals("表格行", first.tableRows, second.tableRows)
        assertEquals("表格列", first.tableCols, second.tableCols)
        assertEquals("加粗段数", first.boldRuns, second.boldRuns)
        // 数字格式事实（字号/行距/页边距/编号定义）同样不漂
        assertEquals("字号", first.fontHalfPts, second.fontHalfPts)
        assertEquals("行距", first.lineValue, second.lineValue)
        assertEquals("页边距", first.margins, second.margins)
        assertEquals("编号定义", first.numberingIds, second.numberingIds)
        assertFalse("占位符确实被换掉", com.example.zhengdao.core.doc.DocxTextExtractor.extract(changed.bytes).contains("{{Amount}}"))
        assertTrue("换成新值", com.example.zhengdao.core.doc.DocxTextExtractor.extract(changed.bytes).contains("12.5"))

        // 顺手把生产真产物导出供外部结构校验
        val exported = File(tmp.root, "745-docx-roundtrip.docx")
        exported.writeBytes(changed.bytes)
        exportForCheck(exported, "745-docx-roundtrip.docx")
    }

    // ── 判据③：字号 / 行距 / 页边距 / 编号连续 —— 解包 XML 断言 ────────────

    @Test
    fun `判据3_格式XML断言_字号行距页边距与编号连续`() {
        val facts = readDocxFacts(produce().readBytes())

        // 字号：正文默认 15pt（half-point 30）
        assertTrue("默认字号应为 30 半磅（15pt）：${facts.fontHalfPts}", "30" in facts.fontHalfPts)
        // 行距：line=360 auto（1.5 倍）
        assertEquals("行距 line", "360", facts.lineValue)
        assertEquals("行距规则", "auto", facts.lineRule)
        // 页边距：四边皆 1440 twips（1 英寸）
        assertEquals("四边页边距", mapOf("top" to "1440", "right" to "1440", "bottom" to "1440", "left" to "1440"), facts.margins)
        // 编号连续：numbering.xml 里的 numId = 1..N 无缺号（1=无序，2/3=两个有序列表）
        assertEquals("编号定义应连续 1,2,3", listOf(1, 2, 3), facts.numberingIds)
        val expected = (facts.numberingIds.first()..facts.numberingIds.last()).toList()
        assertEquals("编号 1..N 无缺号（负例：改缺一个即失败）", expected, facts.numberingIds)
    }
}
