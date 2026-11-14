// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批 · docx 写出的判据（派工单 ①③④）。
//
// 判据①「能被解析库打开」怎么算数：不拿我们自己的模型自证。两条独立读数——
//   · 项目里**既有的** docx 读取器（`core` 的 `DocxTextExtractor`，知识库那条路）能抽出正文；
//   · `word/document.xml` 能被 JDK 的 XML 解析器整篇解析（格式良好），再按标签数结构。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.DocxWriteTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.core.doc.DocxTextExtractor
import org.json.JSONArray
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
import javax.xml.parsers.DocumentBuilderFactory

class DocxWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ws: File

    @Before
    fun setUp() {
        ws = tmp.newFolder("ws")
        FileOpLog.resetForTest()
        FileOpLog.enterSession(632L)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    // ── ① 结构齐全：标题层级 / 段落 / 列表 / 表格列数 / 图片数 / 页眉 ──────────

    @Test
    fun `docx_写出后能被既有解析器打开_结构逐项对得上`() {
        val png = pngFile("图/a.png", 40, 30)
        val pngPath = "图/" + png.name
        val args = docArgs(
            "季度小结",
            "内部资料 · 2026 秋",
            blocks(
                block("heading", "level" to 1, "text" to "季度小结"),
                block("heading", "level" to 2, "text" to "一、结果"),
                block("heading", "level" to 3, "text" to "1.1 细节"),
                block("paragraph", "text" to "正文第一段，**加粗**与*斜体*都在这里。"),
                block("paragraph", "text" to "第二段：2*3 这种星号要原样保留。"),
                block("list", "ordered" to false, "items" to listOf("无序一", "无序二")),
                block("list", "ordered" to true, "items" to listOf("有序一", "有序二")),
                block("table", "columns" to listOf("项目", "数量", "备注"),
                    "rows" to listOf(listOf("A", "1", "说明"), listOf("B", "2", ""))),
                block("image", "path" to pngPath),
            ),
        )
        val out = DocxWriteTool.run(args, ws)
        assertTrue("应当写成功：$out", out is ToolOutcome.Ok)
        val file = onlyProduct("docx")
        val xml = entryText(file, "word/document.xml")

        assertEquals("一级标题一个", 1, count(xml, "<w:pStyle w:val=\"Heading1\"/>"))
        assertEquals("二级标题一个", 1, count(xml, "<w:pStyle w:val=\"Heading2\"/>"))
        assertEquals("三级标题一个", 1, count(xml, "<w:pStyle w:val=\"Heading3\"/>"))
        assertEquals("两组列表共四条 numPr", 4, count(xml, "<w:numPr>"))
        assertEquals("表格列数", 3, count(xml, "<w:gridCol "))
        assertEquals("图片数", 1, count(xml, "<w:drawing>"))
        assertTrue("加粗 run 与表头都要 b", count(xml, "<w:b/>") >= 2)
        assertEquals("斜体 run 存在", 1, count(xml, "<w:i/>"))
        assertTrue("星号要原样留下", xml.contains("2*3"))
        assertTrue("页眉引用在 sectPr 里", xml.contains("<w:headerReference w:type=\"default\" r:id="))
        assertTrue("页眉部件要带那行字", entryText(file, "word/header1.xml").contains("内部资料 · 2026 秋"))
        assertTrue("图片进了包", entryNames(file).any { it.startsWith("word/media/image1.") })

        // 判据①第一半：项目既有 docx 读取器能抽出正文
        val text = DocxTextExtractor.extract(file.readBytes())
        assertTrue("既有解析器要读得到标题：$text", text.contains("季度小结"))
        assertTrue("既有解析器要读得到段落", text.contains("正文第一段"))
        assertTrue("既有解析器要读得到表格文字", text.contains("说明"))
        // 判据①第二半：document.xml 整篇是格式良好的 XML
        parseXml(entryText(file, "word/document.xml"))
        // 包清单里必须有主文档那一行（缺了 Office 直接判"文件损坏"）
        assertTrue(
            "Content_Types 缺主文档声明",
            entryText(file, "[Content_Types].xml").contains("/word/document.xml"),
        )
    }

    // ── ④ 超上限：明确报错、不静默截断、不落盘 ─────────────────────────────

    @Test
    fun `docx_表格11列_明确报错且不留半份文件`() {
        val wide = (1..11).map { "列$it" }
        val args = docArgs("超宽", "", blocks(block("table", "columns" to wide, "rows" to listOf(wide))))
        val out = DocxWriteTool.run(args, ws)
        assertTrue("必须失败而不是截断：$out", out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue("要点名上限：$reason", reason.contains("11 列") && reason.contains("10 列"))
        assertEquals("失败时一个文件都不许落", 0, products().size)
    }

    @Test
    fun `docx_图片3张_明确报错`() {
        val a = pngFile("a.png", 10, 10)
        val path = "图/" + a.name
        val args = docArgs(
            "多图", "",
            blocks(
                block("image", "path" to path),
                block("image", "path" to path),
                block("image", "path" to path),
            ),
        )
        val out = DocxWriteTool.run(args, ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue("要说到 2 张上限：${(out as ToolOutcome.Failed).error}", out.error.contains("2 张"))
        assertEquals("失败不落盘", 0, products().size)
    }

    @Test
    fun `docx_标题4级与空段落_明确报错`() {
        val level4 = DocxWriteTool.run(docArgs("x", "", blocks(block("heading", "level" to 4, "text" to "四"))), ws)
        assertTrue(level4 is ToolOutcome.Failed)
        assertTrue("要说到 1–3 级", (level4 as ToolOutcome.Failed).error.contains("1–3"))
        val empty = DocxWriteTool.run(docArgs("y", "", blocks(block("paragraph", "text" to "  "))), ws)
        assertTrue(empty is ToolOutcome.Failed)
        assertEquals("两次都失败 ⇒ 产物目录仍空", 0, products().size)
    }

    @Test
    fun `docx_图片文件不存在_明确失败不产缺图文档`() {
        val args = docArgs("缺图", "", blocks(block("image", "path" to "图/没有这张.png")))
        val out = DocxWriteTool.run(args, ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue("要说读不到：${(out as ToolOutcome.Failed).error}", out.error.contains("读不到"))
        assertEquals(0, products().size)
    }

    // ── ⑥ 落账：路径 / 大小 / 来源 ──────────────────────────────────────────

    @Test
    fun `docx_写成功落一行账_带路径大小与来源`() {
        val out = DocxWriteTool.run(docArgs("记账", "", blocks(block("paragraph", "text" to "一句话"))), ws)
        assertTrue(out is ToolOutcome.Ok)
        val rows = FileOpLog.recent(5)
        assertEquals("每次写出一行", 1, rows.size)
        val row = rows.first()
        assertEquals("docx_write", row.tool)
        assertEquals(FileOpKind.WRITE, row.kind)
        assertTrue("路径要是落盘那份：${row.path}", row.path.endsWith(".docx"))
        assertTrue("用途要带大小：${row.purpose}", row.purpose.contains("B") || row.purpose.contains("KB"))
        assertTrue("用途要带来源：${row.purpose}", row.purpose.contains("来源"))
    }

    // ── ③ 同名不覆盖（连写两次 ⇒ 两份）────────────────────────────────────

    @Test
    fun `docx_连写两次同名_磁盘上是两份`() {
        val args = docArgs("同一份", "", blocks(block("paragraph", "text" to "第一版")))
        assertTrue(DocxWriteTool.run(args, ws) is ToolOutcome.Ok)
        assertTrue(DocxWriteTool.run(args, ws) is ToolOutcome.Ok)
        assertEquals("两次写 ⇒ 两份文件", 2, products().size)
        assertEquals("两份都要能打开", 2, products().count { it.length() > 0 })
    }

    // ── 行内标注：成对才算（裸星号原样） ─────────────────────────────────────

    @Test
    fun `行内标注_成对才算_裸星号不改样式`() {
        val runs = DocxInline.runs("a**粗**b*c*2*3")
        assertEquals("粗体一段", true, runs.any { it.bold && it.text == "粗" })
        assertEquals("斜体一段", true, runs.any { it.italic && it.text == "c" })
        assertEquals("裸星号原样", true, runs.any { it.text.contains("2*3") })
        assertEquals(
            "没有星号就一段",
            1,
            DocxInline.runs("纯文本").size,
        )
        assertEquals("只有一段纯文本", false, DocxInline.runs("纯文本").first().bold)
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private fun docArgs(name: String, header: String, blocks: JSONArray): JSONObject =
        JSONObject().put("name", name).put("header", header).put("blocks", blocks)

    private fun blocks(vararg items: JSONObject): JSONArray = JSONArray().also { arr -> items.forEach { arr.put(it) } }

    /** 按 `键 to 值` 造一个块：数组型键（items/columns/rows）按 Kotlin List 传。 */
    private fun block(type: String, vararg pairs: Pair<String, Any>): JSONObject {
        val obj = JSONObject().put("type", type)
        for ((k, v) in pairs) {
            when (v) {
                is List<*> -> obj.put(k, toJsonArray(v))
                else -> obj.put(k, v)
            }
        }
        return obj
    }

    private fun toJsonArray(values: List<*>): JSONArray = JSONArray().also { arr ->
        for (v in values) {
            if (v is List<*>) arr.put(toJsonArray(v)) else arr.put(v)
        }
    }

    /** 造一张真 PNG（1×1 透明，67 字节；探针只读 IHDR，但整份是合法 PNG）。 */
    private fun pngFile(rel: String, width: Int, height: Int): File {
        val f = File(ws, rel)
        f.parentFile?.mkdirs()
        val bytes = PNG_1X1.copyOf()
        // 改写 IHDR 的宽高（大端），让"按比例缩放"那条有数可对
        writeInt(bytes, 16, width)
        writeInt(bytes, 20, height)
        f.writeBytes(bytes)
        return f
    }

    private fun writeInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun products(): List<File> = File(ws, "产物").listFiles().orEmpty().filter { it.isFile }

    private fun onlyProduct(ext: String): File {
        val all = products().filter { it.name.endsWith(".$ext") }
        assertEquals("产物里应当恰好一份 .$ext：${all.map { it.name }}", 1, all.size)
        return all.first()
    }

    private fun entryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                zip.closeEntry()
            }
        }
        return names
    }

    private fun entryText(file: File, name: String): String {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
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
        /** 标准 1×1 透明 PNG（67 字节；宽度/高度会被 [pngFile] 改写成夹具需要的尺寸）。 */
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
