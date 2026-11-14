// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十二批 · 页脚页码的判据（派工单判据①）。
//
// 判据①要的是"在 Word/WPS 里显示第 X 页 / 共 Y 页"。本文件验的是**结构**：
//   · 页脚部件存在、被 sectPr 引用、content-types 与 rels 都登记了；
//   · 页码是**域**（`fldChar begin/separate/end` ＋ `instrText PAGE`／`NUMPAGES`），
//     不是写死的数字 —— 写死的话第二页就错。
// 「打开真文档看它算出来是几」在报告里另附一条（Word 真渲染）。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxFooterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
    }

    // ── ① 页脚 + 页码：结构逐项 ─────────────────────────────────────────────

    @Test
    fun `页脚页码_部件与域结构齐全`() {
        val out = DocxWriteTool.run(
            args("带页码", footer = "内部资料", pages = true),
            ws,
        )
        assertTrue("应当写成功：$out", out is ToolOutcome.Ok)
        val file = onlyDocx()

        assertTrue("要有页脚部件：${entryNames(file)}", "word/footer1.xml" in entryNames(file))
        val footer = entryText(file, "word/footer1.xml")
        // 域的五颗 run（begin → instrText → separate → 结果 → end），两个域各一套
        assertEquals("两个域 ⇒ 两个 begin", 2, count(footer, "<w:fldChar w:fldCharType=\"begin\"/>"))
        assertEquals("两个域 ⇒ 两个 separate", 2, count(footer, "<w:fldChar w:fldCharType=\"separate\"/>"))
        assertEquals("两个域 ⇒ 两个 end", 2, count(footer, "<w:fldChar w:fldCharType=\"end\"/>"))
        val instr = Regex("<w:instrText[^>]*>([^<]*)</w:instrText>")
            .findAll(footer).map { it.groupValues[1].trim() }.toList()
        assertEquals("域指令必须是 PAGE 与 NUMPAGES", listOf("PAGE", "NUMPAGES"), instr)
        assertTrue("页脚文字要在：$footer", footer.contains("内部资料"))
        assertTrue("要是「第 X 页 / 共 Y 页」的形状：$footer", footer.contains("第 ") && footer.contains(" 页 / 共 ") && footer.contains(" 页"))

        // 部件登记与引用（缺一条 Word 就判"内容有问题"）
        assertTrue(
            "content-types 要登记页脚",
            entryText(file, "[Content_Types].xml").contains("/word/footer1.xml"),
        )
        val rels = entryText(file, "word/_rels/document.xml.rels")
        val footerRid = Regex("<Relationship Id=\"(rId\\d+)\" Type=\"[^\"]*/footer\" Target=\"footer1.xml\"/>")
            .find(rels)?.groupValues?.get(1)
        assertTrue("rels 里要有页脚关系：$rels", footerRid != null)
        val doc = entryText(file, "word/document.xml")
        assertTrue(
            "sectPr 要引用页脚（$footerRid）：$doc",
            doc.contains("<w:footerReference w:type=\"default\" r:id=\"$footerRid\"/>"),
        )
        // 顺序：引用必须在 pgSz 之前（CT_SectPr 是有序序列）
        assertTrue(
            "页脚引用要排在 pgSz 前面",
            doc.indexOf("footerReference") < doc.indexOf("<w:pgSz"),
        )
        // 整篇 XML 格式良好（Word 打不开最常见的根因就是这里）
        parseXml(footer)
        parseXml(doc)
    }

    // ── ② 只要文字、不要页码 ──────────────────────────────────────────────

    @Test
    fun `页脚只有文字_不带任何域`() {
        val out = DocxWriteTool.run(args("纯页脚", footer = "第 1 页（手写）", pages = false), ws)
        assertTrue(out is ToolOutcome.Ok)
        val footer = entryText(onlyDocx(), "word/footer1.xml")
        assertTrue("文字要在", footer.contains("第 1 页（手写）"))
        assertFalse("没要页码就不许有域：$footer", footer.contains("fldChar"))
        assertFalse("也不许有域指令", footer.contains("instrText"))
    }

    // ── ③ 什么都不给 ⇒ 与 632 完全一致（不建页脚部件）──────────────────────

    @Test
    fun `不给页脚_包里没有页脚部件`() {
        val out = DocxWriteTool.run(args("无页脚", footer = "", pages = false), ws)
        assertTrue(out is ToolOutcome.Ok)
        val names = entryNames(onlyDocx())
        assertFalse("不该凭空多一个部件：$names", names.any { it.startsWith("word/footer") })
        assertFalse(
            "content-types 也不该登记页脚",
            entryText(onlyDocx(), "[Content_Types].xml").contains("/word/footer"),
        )
    }

    // ── ④ 只给页码、不给文字 ───────────────────────────────────────────────

    @Test
    fun `只给页码_页脚里就是第几页共几页`() {
        val out = DocxWriteTool.run(args("只页码", footer = "", pages = true), ws)
        assertTrue(out is ToolOutcome.Ok)
        val footer = entryText(onlyDocx(), "word/footer1.xml")
        assertTrue(footer.contains("第 ") && footer.contains(" 页 / 共 ") && footer.contains(" 页"))
        assertEquals(2, count(footer, "fldChar w:fldCharType=\"begin\""))
    }

    // ── ⑤ 页眉 + 页脚 + 图片同时存在：rId 不许串 ───────────────────────────

    @Test
    fun `页眉页脚图片共存_rId 各对各的`() {
        val png = pngFile("图/a.png")
        val args = args("三件齐", footer = "机密", pages = true).apply {
            put("header", "单位名")
            put(
                "blocks",
                JSONArray().put(JSONObject().put("type", "paragraph").put("text", "正文一句话"))
                    .put(JSONObject().put("type", "image").put("path", "图/" + png.name)),
            )
        }
        val out = DocxWriteTool.run(args, ws)
        assertTrue("应当写成功：$out", out is ToolOutcome.Ok)
        val file = onlyDocx()
        val rels = entryText(file, "word/_rels/document.xml.rels")
        val ids = Regex("<Relationship Id=\"(rId\\d+)\"").findAll(rels).map { it.groupValues[1] }.toList()
        assertEquals("四份关系 id 不许重复：$rels", ids.size, ids.toSet().size)
        val mediaTarget = Regex("<Relationship Id=\"(rId\\d+)\" Type=\"[^\"]*/image\" Target=\"(media/image1\\.png)\"/>")
            .find(rels)?.groupValues?.get(1)
        assertTrue("图片关系要在：$rels", mediaTarget != null)
        val doc = entryText(file, "word/document.xml")
        assertTrue(
            "document.xml 里嵌的 rId 必须就是图片那条（$mediaTarget）：$doc",
            doc.contains("<a:blip r:embed=\"$mediaTarget\"/>"),
        )
        // 页眉页脚各自也要能解析
        parseXml(entryText(file, "word/header1.xml"))
        parseXml(entryText(file, "word/footer1.xml"))
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private fun args(name: String, footer: String, pages: Boolean): JSONObject =
        JSONObject()
            .put("name", name)
            .put("footer", footer)
            .put("footer_pages", pages)
            .put(
                "blocks",
                JSONArray().put(JSONObject().put("type", "paragraph").put("text", "正文一句话")),
            )

    private fun pngFile(rel: String): File {
        val f = File(ws, rel)
        f.parentFile?.mkdirs()
        f.writeBytes(PNG_1X1.copyOf())
        return f
    }

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

    private fun parseXml(xml: String) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    private companion object {
        /** 标准 1×1 透明 PNG（探针只读 IHDR，但整份是合法 PNG）。 */
        val PNG_1X1: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
