// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：派工单-2026-10-21-第一百二十八批 §3（建索引/检索/引用/预算）的切块侧：
// md 按标题分块、txt 按段落、行号正确、超长硬切、html 剥标签、docx 走 121 批提取器。
package com.example.zhengdao.baopu.kb

import com.example.zhengdao.baopu.agent.TextDecode
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KbChunkerTest {

    // ── txt：按空行分段，行号 1 起且连续 ──────────────────────────────────

    @Test
    fun `txt_按空行分段_行号正确`() {
        val text = "第一段第一行\n第一段第二行\n\n第二段第一行\n\n第三段"
        val chunks = KbChunker.chunkText("a.txt", text, KbChunker.ChunkStyle.PLAIN)
        assertEquals(3, chunks.size)
        assertEquals(1, chunks[0].lineStart)
        assertEquals(2, chunks[0].lineEnd)
        assertEquals("第一段第一行\n第一段第二行", chunks[0].text)
        assertEquals(4, chunks[1].lineStart)
        assertEquals(4, chunks[1].lineEnd)
        assertEquals(6, chunks[2].lineStart)
        // 纯文本 ⇒ 行号就是原文件行号（不是提取文本）
        assertEquals(KbChunk.SOURCE_FILE, chunks[0].source)
    }

    @Test
    fun `空文本与空路径不产块`() {
        assertTrue(KbChunker.chunkText("a.txt", "", KbChunker.ChunkStyle.PLAIN).isEmpty())
        assertTrue(KbChunker.chunkText("a.txt", "   \n\n  \n", KbChunker.ChunkStyle.PLAIN).isEmpty())
        assertTrue(KbChunker.chunkText("", "有内容", KbChunker.ChunkStyle.PLAIN).isEmpty())
    }

    // ── md：标题行开新块，标题行进块首且记进 heading ───────────────────────

    @Test
    fun `md_标题开新块_标题行进块首并记heading`() {
        val text = "# 第一章\n他是谁\n\n# 第二章\n她去哪儿"
        val chunks = KbChunker.chunkText("书.md", text, KbChunker.ChunkStyle.MARKDOWN)
        assertEquals(2, chunks.size)
        assertEquals("第一章", chunks[0].heading)
        assertEquals(1, chunks[0].lineStart)
        // 标题行本身是内容：块文本含标题行（引用区间才对得上原文）。
        // 块区间不含空行分隔线——内容就在第 1-2 行
        assertEquals("# 第一章\n他是谁", chunks[0].text)
        assertEquals(2, chunks[0].lineEnd)
        assertEquals("第二章", chunks[1].heading)
        assertEquals(4, chunks[1].lineStart)
        assertEquals("# 第二章\n她去哪儿", chunks[1].text)
        assertEquals(5, chunks[1].lineEnd)
    }

    @Test
    fun `md_没有标题时heading为空`() {
        val chunks = KbChunker.chunkText("书.md", "正文一\n\n正文二", KbChunker.ChunkStyle.MARKDOWN)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.heading.isEmpty() })
    }

    // ── 超长块硬切：行号连续、标记续块靠行号自明 ──────────────────────────

    @Test
    fun `超长内容按行硬切_行号连续不重叠`() {
        val lines = (1..30).map { "第${it}行内容填充填充填充" }
        val chunks = KbChunker.chunkText(
            "长.txt", lines.joinToString("\n"), KbChunker.ChunkStyle.PLAIN, targetChars = 50,
        )
        assertTrue("应切成多块", chunks.size >= 2)
        // 相邻块行号不重叠（引用区间才能对回原文）
        for (i in 1 until chunks.size) {
            assertTrue(
                "块$i 起点 ${chunks[i].lineStart} 应大于前块终点 ${chunks[i - 1].lineEnd}",
                chunks[i].lineStart > chunks[i - 1].lineEnd,
            )
        }
        // 行号区间覆盖全文（首块从 1 开始，末块到 30 行结束）
        assertEquals(1, chunks.first().lineStart)
        assertEquals(30, chunks.last().lineEnd)
    }

    // ── html：剥标签、丢 script/style、块级转行、实体还原 ──────────────────

    @Test
    fun `html_剥标签丢脚本实体还原`() {
        val html = "<html><head><style>p{color:red}</style><script>alert(1)</script></head>" +
            "<body><h1>标题</h1><p>第一&nbsp;段&amp;更多</p><p>第二段</p></body></html>"
        val text = KbChunker.htmlToText(html)
        assertTrue("style 内容不许进正文：$text", !text.contains("color"))
        assertTrue("script 内容不许进正文：$text", !text.contains("alert"))
        assertTrue("实体要还原：$text", text.contains("第一 段&更多"))
        assertTrue("标题要在：$text", text.contains("标题"))
        val chunks = KbChunker.chunkText(
            "页.html", text, KbChunker.ChunkStyle.PLAIN, KbChunk.SOURCE_EXTRACTED,
        )
        assertTrue(chunks.isNotEmpty())
        // 提取文本 ⇒ 引用必须标明，不冒充原文件行号
        assertEquals(KbChunk.SOURCE_EXTRACTED, chunks[0].source)
    }

    @Test
    fun `html_半个标签与坏结构不抛异常`() {
        val text = KbChunker.htmlToText("<p>半截标签 <b 加<p>更多</p")
        assertTrue("坏结构也要给出能看的结果：$text", text.contains("半截标签"))
    }

    // ── docx：走 121 批提取器（zip + word/document.xml） ──────────────────

    @Test
    fun `docx_走提取器抽正文并切块`() {
        val docx = minimalDocx(
            "<w:p><w:r><w:t>抱朴知识库</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>第二段文字</w:t></w:r></w:p>",
        )
        val text = com.example.zhengdao.terminal.DocxTextExtractor.extract(docx)
        assertTrue("提取器应抽出正文：$text", text.contains("抱朴知识库"))
        val chunks = KbChunker.chunkText("书.docx", text, KbChunker.ChunkStyle.PLAIN, KbChunk.SOURCE_EXTRACTED)
        assertEquals(2, chunks.size)
        assertEquals(KbChunk.SOURCE_EXTRACTED, chunks[0].source)
        assertEquals("抱朴知识库", chunks[0].text)
    }

    // ── GBK：解码走 TextDecode 唯一真源，切块正常 ─────────────────────────

    @Test
    fun `GBK字节_经TextDecode解码后切块`() {
        val gbkBytes = "老书第一段\n\n老书第二段".toByteArray(charset("GBK"))
        val decoded = TextDecode.decode(java.io.File("老书.txt"), gbkBytes)
        assertEquals("GBK", decoded.encoding)
        val chunks = KbChunker.chunkText("老书.txt", decoded.text!!, KbChunker.ChunkStyle.PLAIN)
        assertEquals(2, chunks.size)
        assertEquals("老书第一段", chunks[0].text)
    }

    /** 最小合法 docx（一个 zip，里面只有 word/document.xml）。 */
    private fun minimalDocx(body: String): ByteArray {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"x\">$body</w:document>"
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(xml.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return bos.toByteArray()
    }
}
