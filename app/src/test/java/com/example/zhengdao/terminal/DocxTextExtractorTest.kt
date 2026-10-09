// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P3a `.docx` 提取的单测（**零依赖**：zip 用 JDK 自带，测试数据在测试里现造）。
 *
 * 用"现造一个最小 docx"而不是塞一个二进制样本进仓库：
 * ① 不引入二进制资产；② 测的是**我们的解析逻辑**，不是某个 Word 版本的兼容性。
 */
class DocxTextExtractorTest {

    /** 造一个最小 `.docx`：zip 里放 `word/document.xml`，可选再塞点干扰条目。 */
    private fun fakeDocx(docXml: String, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            // 干扰条目：体积大且不该被解压（验证"只取 document.xml"）
            for ((name, data) in extra) {
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(docXml.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun docxXml(body: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
           <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
             <w:body>$body</w:body>
           </w:document>"""

    // ── 基本提取 ────────────────────────────────────────────────────────────

    @Test
    fun `段落的文字能提出来 且按段落断行`() {
        val xml = docxXml(
            "<w:p><w:r><w:t>第一段</w:t></w:r></w:p><w:p><w:r><w:t>第二段</w:t></w:r></w:p>"
        )
        // 段落之间隔一个空行（开/闭标签各断一次，多出来的空行由 normalize 收敛成一个）
        // —— 这是**有意**的：Word 里段落本来就该分得开，挤成一坨 AI 会读错结构。
        assertEquals("第一段\n\n第二段", DocxTextExtractor.extract(fakeDocx(xml)))
    }

    @Test
    fun `一个段落被拆成多个 run 时要拼起来`() {
        // Word 会把一句话按格式切成多个 <w:r>，不拼接就会一个字一行
        val xml = docxXml(
            "<w:p><w:r><w:t>这是</w:t></w:r><w:r><w:t>一句话</w:t></w:r></w:p>"
        )
        assertEquals("这是一句话", DocxTextExtractor.extract(fakeDocx(xml)))
    }

    @Test
    fun `制表变成空格 强制换行能断行`() {
        val xml = docxXml(
            "<w:p><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r></w:p>"
        )
        assertEquals("A B\nC", DocxTextExtractor.extract(fakeDocx(xml)))
    }

    @Test
    fun `XML 实体要还原成原字符`() {
        val xml = docxXml("<w:p><w:r><w:t>a &amp; b &lt;c&gt; &quot;d&quot;</w:t></w:r></w:p>")
        assertEquals("a & b <c> \"d\"", DocxTextExtractor.extract(fakeDocx(xml)))
    }

    @Test
    fun `数字实体也要还原 认不出的实体原样保留`() {
        assertEquals("中", DocxTextExtractor.unescapeXml("&#20013;"))
        assertEquals("A", DocxTextExtractor.unescapeXml("&#x41;"))
        // 认不出的（比如裸 & 或未定义实体）不能吞掉，否则会丢文字
        assertEquals("a&unknown;b", DocxTextExtractor.unescapeXml("a&unknown;b"))
        assertEquals("裸&符号", DocxTextExtractor.unescapeXml("裸&符号"))
    }

    // ── ★ 回归：不能把表格/标签名当成正文 ────────────────────────────────────

    @Test
    fun `表格结构不能被当成正文取出来`() {
        // ⚠️ 回归：`w:tbl` / `w:tc` / `w:tblPr` 全以 `w:t` 开头。
        //    用 startsWith("w:t") 匹配会把它们当文字节点 ⇒ 正文里混进一堆标签。
        val xml = docxXml(
            "<w:tbl><w:tblPr><w:tblW w:w=\"0\"/></w:tblPr>" +
                "<w:tr><w:tc><w:p><w:r><w:t>单元格文字</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        )
        assertEquals("单元格文字", DocxTextExtractor.extract(fakeDocx(xml)))
    }

    @Test
    fun `带属性的文本标签能正确取到文字`() {
        val xml = docxXml("<w:p><w:r><w:t xml:space=\"preserve\"> 保留了空格 </w:t></w:r></w:p>")
        assertEquals("保留了空格", DocxTextExtractor.extract(fakeDocx(xml)).trim())
    }

    // ── 空白收敛 ────────────────────────────────────────────────────────────

    @Test
    fun `多余空格与连续空行会被收敛`() {
        val xml = docxXml(
            "<w:p><w:r><w:t>上面</w:t></w:r></w:p>" +
                "<w:p/>" + "<w:p/>" + "<w:p/>" +
                "<w:p><w:r><w:t>下面</w:t></w:r></w:p>"
        )
        assertEquals("上面\n\n下面", DocxTextExtractor.extract(fakeDocx(xml)))
    }

    // ── 各种"不是 docx"的输入都不能抛 ────────────────────────────────────────

    @Test
    fun `空输入返回空串 不抛异常`() {
        assertEquals("", DocxTextExtractor.extract(ByteArray(0)))
    }

    @Test
    fun `不是 zip 的字节返回空串 不抛异常`() {
        // 真实场景：用户把 .doc 改名成 .docx、或文件下了半截
        val garbage = "这其实不是 docx，是一个改了扩展名的纯文本".toByteArray(Charsets.UTF_8)
        assertEquals("", DocxTextExtractor.extract(garbage))
    }

    @Test
    fun `zip 里没有 document xml 时返回空串`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("xl/workbook.xml")); z.write("<x/>".toByteArray()); z.closeEntry()
        }
        assertEquals("", DocxTextExtractor.extract(bos.toByteArray()))
    }

    @Test
    fun `正文为空的 docx 返回空串`() {
        assertEquals("", DocxTextExtractor.extract(fakeDocx(docxXml("<w:p/>"))))
    }

    // ── ★ 只解压 document xml（性能相关）────────────────────────────────────

    @Test
    fun `只解压 document xml 不碰体积很大的图片条目`() {
        // 塞一个 8 MB 的**不可压缩**假图片（随机字节，压缩后仍是 8 MB）：
        // 如果实现把整个 zip 都解了，这里会明显慢/占内存。
        // ⚠️ 别用重复字节（0x41 重复）——deflate 会把它压到几 KB，那就测不出东西了。
        val rnd = java.util.Random(42)
        val big = ByteArray(8 * 1024 * 1024).also { rnd.nextBytes(it) }
        val xml = docxXml("<w:p><w:r><w:t>正文</w:t></w:r></w:p>")
        val bytes = fakeDocx(xml, mapOf("word/media/image1.png" to big))
        assertEquals("正文", DocxTextExtractor.extract(bytes))
        assertTrue("图片条目确实在里面（证明是干扰项）", bytes.size > 8 * 1024 * 1024)
    }

    // ── 扩展名判定 ──────────────────────────────────────────────────────────

    @Test
    fun `按扩展名判定 大小写都认`() {
        assertTrue(DocxTextExtractor.canHandle("合同.docx"))
        assertTrue(DocxTextExtractor.canHandle("A.DOCX"))
        assertFalse("旧版 doc 不是 zip，明确不支持", DocxTextExtractor.canHandle("旧文档.doc"))
        assertFalse(DocxTextExtractor.canHandle("说明.txt"))
        assertFalse(DocxTextExtractor.canHandle("报告.pdf"))
    }
}
