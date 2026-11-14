// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十二批 · xlsx 写出的判据（派工单 ②③④⑥）。
//
// 判据②「生成后 `sheet_read` 读回，值与格式逐格一致」怎么算数：
//   · 值：直接调 `SheetReader`（就是 `sheet_read` 用的那一套解析，作者不是本文件的写出代码）；
//   · 格式：读回 `xl/styles.xml` 的 numFmt 表，再按格子的 `s` 下标解析出格式串，逐格比对——
//     这条是"文件里真的写着这个格式"，不是"我们的模型以为写了"。
package com.example.zhengdao.baopu.office

import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.OfficeSink
import com.example.zhengdao.baopu.agent.SheetReader
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.XlsxWriteTool
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

class XlsxWriteTest {

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

    // ── ② 往返：值 + 数字/日期格式逐格一致 ─────────────────────────────────

    @Test
    fun `xlsx_读回逐格一致_数字与日期格式都在文件里`() {
        val file = writeSample()
        val grid = SheetReader.read(file, "汇总", null, hasHeader = true, maxRows = 100).getOrThrow()
        assertEquals(listOf("姓名", "分数", "金额", "占比", "日期", "时刻"), grid.header)
        assertEquals(2, grid.rows.size)
        // 前五格逐字比对（文本/整数/小数/百分比值的原始序列值/日期序列值）
        assertEquals(listOf("张三", "90", "1234.5", "0.125", "46339"), grid.rows[0].take(5))
        // 时刻 09:30 = 当天 0.3958333…（Excel 用小数存时刻；这里按数值比对，不咬 Double 的打印位数）
        assertEquals(0.3958333333333333, grid.rows[0][5]!!.toDouble(), 1e-9)
        assertEquals(listOf("李四", "0", null, null, null, null), padded(grid.rows[1], 6))

        // 格式：按格子的 s 下标去 styles.xml 里查它到底写着什么格式
        val styles = entryText(file, "xl/styles.xml")
        assertEquals("金额格式", "#,##0.00", formatOf(styles, cellStyleOf(file, "C2")))
        assertEquals("百分比格式", "0.00%", formatOf(styles, cellStyleOf(file, "D2")))
        assertEquals("日期格式", "yyyy-mm-dd", formatOf(styles, cellStyleOf(file, "E2")))
        assertEquals("时刻格式", "hh:mm", formatOf(styles, cellStyleOf(file, "F2")))
        assertEquals("分数不挂自定义格式", "0", formatOf(styles, cellStyleOf(file, "B2")))
    }

    @Test
    fun `xlsx_表头加粗与冻结首行_列宽与合并都在`() {
        val file = writeSample()
        val sheet = entryText(file, "xl/worksheets/sheet1.xml")
        assertTrue("冻结首行", sheet.contains("<pane ySplit=\"1\"") && sheet.contains("state=\"frozen\""))
        assertTrue("列宽自适应", sheet.contains("<col min=\"1\" max=\"1\""))
        assertTrue("合并单元格", sheet.contains("<mergeCell ref=\"A3:B3\"/>"))
        assertFalse("只写值不算公式", sheet.contains("<f>"))
        val styles = entryText(file, "xl/styles.xml")
        assertEquals("表头走加粗字体", 1, fontOf(styles, cellStyleOf(file, "A1")))
        assertEquals("数据行走普通字体", 0, fontOf(styles, cellStyleOf(file, "A2")))
    }

    @Test
    fun `xlsx_多sheet_按名字都能读回`() {
        val args = JSONObject().put("name", "多表").put(
            "sheets",
            JSONArray()
                .put(sheet("汇总", listOf(listOf("项目", "值"), listOf("甲", 1))))
                .put(sheet("明细", listOf(listOf("序号"), listOf("1")))),
        )
        assertTrue(XlsxWriteTool.run(args, ws) is ToolOutcome.Ok)
        val file = onlyProduct("xlsx")
        val first = SheetReader.read(file, "汇总", null, true, 20).getOrThrow()
        assertEquals(listOf("项目", "值"), first.header)
        val second = SheetReader.read(file, "明细", null, true, 20).getOrThrow()
        assertEquals(listOf("序号"), second.header)
        val names = Regex("<sheet name=\"([^\"]+)\"").findAll(entryText(file, "xl/workbook.xml"))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("汇总", "明细"), names)
    }

    // ── ④ 超上限：明确报错、不静默截断 ─────────────────────────────────────

    @Test
    fun `xlsx_6个sheet_明确报错且不落盘`() {
        val sheets = JSONArray()
        repeat(6) { i -> sheets.put(sheet("S${i + 1}", listOf(listOf("a")))) }
        val out = XlsxWriteTool.run(JSONObject().put("sheets", sheets), ws)
        assertTrue(out is ToolOutcome.Failed)
        val reason = (out as ToolOutcome.Failed).error
        assertTrue("要点名 6 与 5：$reason", reason.contains("6 个") && reason.contains("5 个"))
        assertEquals(0, products().size)
    }

    @Test
    fun `xlsx_sheet名重复或过长_明确报错`() {
        val dup = JSONObject().put(
            "sheets",
            JSONArray()
                .put(sheet("同名", listOf(listOf("a"))))
                .put(sheet("同名", listOf(listOf("b")))),
        )
        val out = XlsxWriteTool.run(dup, ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue("要说到重复", (out as ToolOutcome.Failed).error.contains("重复"))

        val longName = "很".repeat(32)
        val tooLong = XlsxWriteTool.run(
            JSONObject().put("sheets", JSONArray().put(sheet(longName, listOf(listOf("a"))))),
            ws,
        )
        assertTrue(tooLong is ToolOutcome.Failed)
        assertTrue("要说到 31", (tooLong as ToolOutcome.Failed).error.contains("31"))
        assertEquals("失败不落盘", 0, products().size)
    }

    @Test
    fun `xlsx_日期写错_明确报错`() {
        val bad = JSONObject().put(
            "sheets",
            JSONArray().put(
                sheet(
                    "日期",
                    listOf(
                        listOf("日期"),
                        listOf(JSONObject().put("v", "不是日期").put("format", "yyyy-mm-dd")),
                    ),
                ),
            ),
        )
        val out = XlsxWriteTool.run(bad, ws)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue("要说哪一格不对：${(out as ToolOutcome.Failed).error}", out.error.contains("不是日期"))
        assertEquals(0, products().size)
    }

    // ── ③ 同名不覆盖 ────────────────────────────────────────────────────

    @Test
    fun `xlsx_同一分钟连写两次_第二份带序号`() {
        val sink = OfficeSink
        val a = sink.write(ws, "账", "xlsx", byteArrayOf(1, 2, 3), "20261113-2105")
        val b = sink.write(ws, "账", "xlsx", byteArrayOf(4, 5, 6), "20261113-2105")
        val first = a.valueOrNull()
        val second = b.valueOrNull()
        assertTrue("两次都应成功：$a / $b", first != null && second != null)
        assertEquals("账-20261113-2105.xlsx", first!!.name)
        assertEquals("账-20261113-2105-2.xlsx", second!!.name)
        assertTrue("第一份内容没被覆盖", first.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue("第二份是新文件", second.readBytes().contentEquals(byteArrayOf(4, 5, 6)))
    }

    // ── ⑥ 落账 ────────────────────────────────────────────────────────────

    @Test
    fun `xlsx_写成功落一行账`() {
        assertTrue(writeRaw() is ToolOutcome.Ok)
        val rows = FileOpLog.recent(5)
        assertEquals(1, rows.size)
        assertEquals("xlsx_write", rows.first().tool)
        assertTrue("路径是落盘那份", rows.first().path.endsWith(".xlsx"))
        assertTrue("用途带大小与来源", rows.first().purpose.contains("来源"))
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private fun writeSample(): File {
        assertTrue(writeRaw() is ToolOutcome.Ok)
        return onlyProduct("xlsx")
    }

    private fun writeRaw(): ToolOutcome {
        val rows = JSONArray()
            .put(row("姓名", "分数", "金额", "占比", "日期", "时刻"))
            .put(
                row(
                    "张三",
                    90,
                    JSONObject().put("v", 1234.5).put("format", "#,##0.00"),
                    JSONObject().put("v", 0.125).put("format", "0.00%"),
                    JSONObject().put("v", "2026-11-13").put("format", "yyyy-mm-dd"),
                    JSONObject().put("v", "09:30").put("format", "hh:mm"),
                ),
            )
            .put(row("李四", 0, "", "", "", ""))
        val first = sheet("汇总", null)
        first.put("rows", rows)
        first.put("merges", JSONArray().put("A3:B3"))
        val args = JSONObject().put("name", "样表").put("sheets", JSONArray().put(first))
        return XlsxWriteTool.run(args, ws)
    }

    private fun sheet(name: String, rows: List<List<Any?>>?): JSONObject {
        val obj = JSONObject().put("name", name)
        if (rows != null) obj.put("rows", rowsOf(rows))
        return obj
    }

    private fun rowsOf(rows: List<List<Any?>>): JSONArray = JSONArray().also { arr ->
        rows.forEach { row -> arr.put(toArray(row)) }
    }

    private fun row(vararg cells: Any?): JSONArray = toArray(cells.toList())

    private fun toArray(cells: List<Any?>): JSONArray = JSONArray().also { arr ->
        cells.forEach { cell ->
            when (cell) {
                null -> arr.put(JSONObject.NULL)
                else -> arr.put(cell)
            }
        }
    }

    private fun padded(row: List<String?>, width: Int): List<String?> =
        (0 until width).map { row.getOrNull(it) }

    private fun products(): List<File> = File(ws, "产物").listFiles().orEmpty().filter { it.isFile }

    private fun onlyProduct(ext: String): File {
        val all = products().filter { it.name.endsWith(".$ext") }
        assertEquals("产物里应当恰好一份 .$ext：${all.map { it.name }}", 1, all.size)
        return all.first()
    }

    private fun entryText(file: File, name: String): String {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == name) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        throw AssertionError("包里没有 $name")
    }

    /** 单元格引用的 `s`（样式下标）：`<c r="C2" s="3"`。 */
    private fun cellStyleOf(file: File, ref: String): Int {
        val xml = entryText(file, "xl/worksheets/sheet1.xml")
        val m = Regex("<c r=\"$ref\" s=\"(\\d+)\"").find(xml)
            ?: throw AssertionError("找不到单元格 $ref（$xml）")
        return m.groupValues[1].toInt()
    }

    /** `s` → numFmtId → 格式串（内置 0 视为「常规」，本仓只写自定义号段）。 */
    private fun formatOf(styles: String, s: Int): String {
        val numFmtId = cellXf(styles, s).groupValues[1]
        if (numFmtId == "0") return "0"
        return numFmtCodes(styles)[numFmtId] ?: throw AssertionError("numFmtId=$numFmtId 在 styles.xml 里没有定义")
    }

    private fun fontOf(styles: String, s: Int): Int = cellXf(styles, s).groupValues[2].toInt()

    /** 第 s 条 `<xf …>`（cellXfs 段内）。 */
    private fun cellXf(styles: String, s: Int): MatchResult {
        val section = styles.substringAfter("<cellXfs").substringBefore("</cellXfs>")
        val xfs = Regex("<xf numFmtId=\"(\\d+)\" fontId=\"(\\d+)\"[^>]*/>").findAll(section).toList()
        assertTrue("cellXfs 里没有第 $s 条（共 ${xfs.size} 条）", s < xfs.size)
        return xfs[s]
    }

    private fun numFmtCodes(styles: String): Map<String, String> =
        Regex("<numFmt numFmtId=\"(\\d+)\" formatCode=\"([^\"]+)\"/>").findAll(styles)
            .associate { it.groupValues[1] to it.groupValues[2] }
}
