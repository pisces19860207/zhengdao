// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批 · 模板替换的判据（派工单判据④）。
//
// 判据④要两件事：
//   · `{{name}}` 替换成功（且**只有字变了**——排版标签原样）；
//   · 未提供的占位符 ⇒ 明确报「缺少字段 X」，**不静默留空、不落盘半份**。
// 另有两条负例：宏（.docm / vbaProject.bin）与"占位符被 Word 拆成几段"。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.DocxTemplateTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.core.doc.DocxTextExtractor
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream

class DocxTemplateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        FileOpLog.resetForTest()
        FileOpLog.enterSession(642L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    // ── ① 替换成功：字变了、版没动 ──────────────────────────────────────────

    @Test
    fun `占位符替换成功_其余标签原样`() {
        val tpl = template(
            "通知书",
            header = "{{单位}}人事部",
            footer = "出具：{{单位}}",
            pages = true,
            paragraphs = listOf("姓名：{{姓名}}", "日期：{{ 日期 }}"),
        )
        val before = entryText(tpl, "word/document.xml")

        val out = DocxTemplateTool.run(
            args(tpl.name, mapOf("姓名" to "张三", "日期" to "2026-11-14", "单位" to "青灯科技")),
            ws,
        )
        assertTrue("应当写成功：$out", out is ToolOutcome.Ok)
        val docx = onlyDocx()
        val text = DocxTextExtractor.extract(docx.readBytes())
        assertTrue("正文要换成值：$text", text.contains("姓名：张三") && text.contains("日期：2026-11-14"))
        assertFalse("不许留下任何占位符：$text", text.contains("{{"))

        // 页眉/页脚里的占位符也要换（模板的每一处都算）
        assertTrue("页眉要换成值", entryText(docx, "word/header1.xml").contains("青灯科技人事部"))
        assertTrue("页脚要换成值", entryText(docx, "word/footer1.xml").contains("出具：青灯科技"))

        // 版式：部件数与关键标签一个不少（只改字＝这些必须原样在）
        val names = entryNames(docx)
        assertEquals("部件清单要与模板一致：$names", entryNames(tpl), names)
        val after = entryText(docx, "word/document.xml")
        assertEquals("段落数不许变", count(before, "<w:p>") + count(before, "<w:p "), count(after, "<w:p>") + count(after, "<w:p "))
        assertTrue("页脚的域必须还在（模板里的页码不许被抹掉）", entryText(docx, "word/footer1.xml").contains("NUMPAGES"))
        assertTrue("样式部件原样搬过来", entryText(docx, "word/styles.xml") == entryText(tpl, "word/styles.xml"))

        // 落账一行（谁生成的、多长）
        val row = FileOpLog.recent(5).first()
        assertEquals("docx_template", row.tool)
        assertEquals(FileOpKind.WRITE, row.kind)
        assertTrue("用途要说清是按模板生成的：${row.purpose}", row.purpose.contains("模板"))
    }

    @Test
    fun `值里有XML特殊字符_转义后仍是纯文本`() {
        val tpl = template("特殊字符", paragraphs = listOf("公司：{{公司}}"))
        val out = DocxTemplateTool.run(args(tpl.name, mapOf("公司" to "A&B <分公司>")), ws)
        assertTrue(out is ToolOutcome.Ok)
        val xml = entryText(onlyDocx(), "word/document.xml")
        assertTrue("要转义，不能把 XML 结构戳破：$xml", xml.contains("A&amp;B &lt;分公司&gt;"))
        assertTrue("既有解析器能读回原文", DocxTextExtractor.extract(onlyDocx().readBytes()).contains("A&B <分公司>"))
    }

    // ── ② 缺字段：点名，不留空，不落盘 ──────────────────────────────────────

    @Test
    fun `缺一个字段_明确报缺少字段且不落盘`() {
        val tpl = template("请假条", paragraphs = listOf("姓名：{{姓名}}", "日期：{{日期}}"))
        val out = DocxTemplateTool.run(args(tpl.name, mapOf("姓名" to "张三")), ws)
        assertTrue("必须失败：$out", out is ToolOutcome.Failed)
        val err = (out as ToolOutcome.Failed).error
        assertTrue("要点名字段名：$err", err.contains("缺少字段") && err.contains("日期"))
        assertFalse("已给的字段不该被点名：$err", err.contains("缺少字段 姓名"))
        assertEquals("失败时产物目录必须空", 0, products().size)
    }

    @Test
    fun `字段给了空串_算给了_不报缺字段`() {
        val tpl = template("空值", paragraphs = listOf("备注：{{备注}}"))
        val out = DocxTemplateTool.run(args(tpl.name, mapOf("备注" to "")), ws)
        assertTrue("空串是用户的选择，不算缺：$out", out is ToolOutcome.Ok)
        assertFalse(entryText(onlyDocx(), "word/document.xml").contains("{{"))
    }

    @Test
    fun `多给字段_不影响_模板里没有就不管`() {
        val tpl = template("多余字段", paragraphs = listOf("姓名：{{姓名}}"))
        val out = DocxTemplateTool.run(args(tpl.name, mapOf("姓名" to "李四", "职务" to "经理")), ws)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text, "1 个字段" in out.text)
    }

    @Test
    fun `模板里没有占位符_照样出文档并说清`() {
        val tpl = template("无占位符", paragraphs = listOf("这是一份固定正文。"))
        val out = DocxTemplateTool.run(args(tpl.name, emptyMap()), ws)
        assertTrue(out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text, "没有 {{占位符}}" in out.text)
        assertTrue(DocxTextExtractor.extract(onlyDocx().readBytes()).contains("这是一份固定正文。"))
    }

    // ── ③ 负例：占位符被拆成几段 ────────────────────────────────────────────

    @Test
    fun `占位符被Word拆开_明确说做不到而不是猜`() {
        val split = rawDocx(
            "<w:p><w:r><w:t>姓名：{{姓</w:t></w:r><w:r><w:t>名}}</w:t></w:r></w:p>",
        )
        val f = File(ws, "拆开的模板.docx").apply { writeBytes(split) }
        val out = DocxTemplateTool.run(args(f.name, mapOf("姓名" to "张三")), ws)
        assertTrue("必须失败：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "拆成" in out.error)
        assertEquals(0, products().size)
    }

    // ── ④ 负例：宏 ────────────────────────────────────────────────────────

    @Test
    fun `带宏的模板_当场拒`() {
        val tpl = rawDocx("<w:p><w:r><w:t>{{姓名}}</w:t></w:r></w:p>", withMacro = true)
        val f = File(ws, "带宏的模板.docx").apply { writeBytes(tpl) }
        val out = DocxTemplateTool.run(args(f.name, mapOf("姓名" to "张三")), ws)
        assertTrue("必须失败：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "宏" in out.error)
        assertEquals(0, products().size)
    }

    @Test
    fun `docm后缀_连读都不读就拒`() {
        File(ws, "带宏.docm").writeBytes(ByteArray(16))
        val out = DocxTemplateTool.run(args("带宏.docm", mapOf("姓名" to "张三")), ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "宏" in out.error)
    }

    // ── ⑤ 负例：不是 docx / 路径不对 ────────────────────────────────────────

    @Test
    fun `改名的假docx_明确只支持docx模板`() {
        File(ws, "假的.docx").writeText("我其实是个文本文件")
        val out = DocxTemplateTool.run(args("假的.docx", mapOf()), ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "只支持 .docx" in out.error)
    }

    @Test
    fun `模板不存在_说清不存在`() {
        val out = DocxTemplateTool.run(args("没有这份.docx", mapOf()), ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error, "不存在" in out.error)
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 用**项目自己的** docx 写出件造模板（模板就是一份普通 docx，这也顺带验了往返）。 */
    private fun template(
        base: String,
        header: String = "",
        footer: String = "",
        pages: Boolean = false,
        paragraphs: List<String>,
    ): File {
        val spec = DocxSpec(
            fileBase = base,
            header = header,
            blocks = paragraphs.map { DocxBlock.Paragraph(it) },
            footer = if (footer.isBlank() && !pages) null else DocxFooter(footer, pages),
        )
        val f = File(ws, "$base.docx")
        f.writeBytes(DocxWriter.build(spec, emptyMap()))
        return f
    }

    /** 手搓一份最小 docx（用来造"占位符被拆开""带宏"这些畸形模板）。 */
    private fun rawDocx(documentBody: String, withMacro: Boolean = false): ByteArray {
        val pkg = OoxmlPackage()
        pkg.xml(
            OoxmlPackage.CONTENT_TYPES,
            XML_HEAD + "<Types xmlns=\"" + OoxmlNs.CONTENT_TYPES + "\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/word/document.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                "</Types>",
        )
        pkg.xml(
            OoxmlPackage.ROOT_RELS,
            XML_HEAD + "<Relationships xmlns=\"" + OoxmlNs.PACKAGE_REL + "\">" +
                "<Relationship Id=\"rId1\" Type=\"" + OoxmlNs.OFFICE_REL + "/officeDocument\" " +
                "Target=\"word/document.xml\"/></Relationships>",
        )
        pkg.xml(
            "word/document.xml",
            XML_HEAD + "<w:document xmlns:w=\"" + OoxmlNs.WORD + "\"><w:body>" +
                documentBody + "</w:body></w:document>",
        )
        if (withMacro) pkg.binary("word/vbaProject.bin", ByteArray(32))
        return pkg.build()
    }

    private fun args(template: String, fields: Map<String, String>): JSONObject = JSONObject()
        .put("template", template)
        .put("fields", JSONObject(fields as Map<*, *>))

    private fun products(): List<File> = File(ws, "产物").listFiles().orEmpty().filter { it.isFile }

    private fun onlyDocx(): File {
        val all = products().filter { it.name.endsWith(".docx") }
        assertEquals("产物里应当恰好一份 docx：${all.map { it.name }}", 1, all.size)
        return all.first()
    }

    private fun entryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
            }
        }
        return names
    }

    private fun entryText(file: File, name: String): String {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == name) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError("包里没有 $name（有：${entryNames(file)}）")
    }

    private fun count(text: String, needle: String): Int {
        var n = 0
        var at = text.indexOf(needle)
        while (at >= 0) {
            n++
            at = text.indexOf(needle, at + needle.length)
        }
        return n
    }
}
