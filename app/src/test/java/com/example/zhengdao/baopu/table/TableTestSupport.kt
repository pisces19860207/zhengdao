// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 表格问答的测试夹具。
//
// **刻意不复用 `logistics/LogisticsTestSupport.kt`**：那份夹具的 sheet 名写死成「配货单」、
// 表头写死成物流十二列——用它来测「通用表格」等于拿配货单冒充学生成绩表，
// 正是派工单 §2.0 点名不许做的事。这里另起一份中立的：sheet 名随便给、列随便给。
package com.example.zhengdao.baopu.table

import com.example.zhengdao.baopu.agent.SheetGrid
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 一张表 → [TableModel]（不落盘：模型层的用例要快、要能一行一行读）。 */
internal fun gridOf(rows: List<List<String?>>, rowNumbers: List<Int>? = null): SheetGrid = SheetGrid(
    sheets = listOf("S"),
    header = emptyList(),
    rows = rows,
    truncated = false,
    skipped = emptyMap(),
    rowNumbers = rowNumbers ?: rows.indices.map { it + 1 },
)

internal fun modelOf(vararg rows: List<String?>): TableModel =
    TableModelBuilder.build("t.xlsx", "S", gridOf(rows.toList()))

internal fun modelOf(rows: List<List<String?>>, headerRow: Int? = null): TableModel =
    TableModelBuilder.build("t.xlsx", "S", gridOf(rows), headerRow)

// ── 验收用的那张非物流表（学生成绩表：姓名/科目/分数） ────────────────────────

/** 表头（**第 1 行**就是表头的那种普通表）。 */
internal val SCORE_HEADER = listOf<String?>("姓名", "科目", "分数")

/**
 * 5 个人 × 2 科 ＝ 10 行。
 *
 * 数字是按「能手算」摆的，验收报告里每一个数都能被蓝总管当场核：
 * 语文 88+55+90+59+100 = 392 → 平均 78.4；数学 92+48+76+61+95 = 372 → 平均 74.4；
 * 不及格（<60）：李四 55/48、赵六 59 ⇒ **3 行 / 2 个人**；最高 100（孙七·语文）。
 */
internal val SCORE_ROWS: List<List<String?>> = listOf(
    listOf("张三", "语文", "88"),
    listOf("张三", "数学", "92"),
    listOf("李四", "语文", "55"),
    listOf("李四", "数学", "48"),
    listOf("王五", "语文", "90"),
    listOf("王五", "数学", "76"),
    listOf("赵六", "语文", "59"),
    listOf("赵六", "数学", "61"),
    listOf("孙七", "语文", "100"),
    listOf("孙七", "数学", "95"),
)

internal fun scoreModel(): TableModel = modelOf(listOf(SCORE_HEADER) + SCORE_ROWS)

/** 带标题行的成绩表（表头在**第 3 行**：前两行是标题与副标题）。 */
internal fun titledScoreRows(): List<List<String?>> = listOf(
    listOf<String?>("2026 年秋季学期期末成绩表"),
    listOf<String?>("班级：三班", "制表：李老师"),
    SCORE_HEADER,
) + SCORE_ROWS

// ── 落盘夹具（登记簿与增量用例要真文件） ────────────────────────────────────

/** 最小的 xlsx 打包器：行 → `<row r>` / `<c r t>`，字符串进 sharedStrings，纯数字走 `<v>`。 */
internal class Xlsx(val sheetName: String = "Sheet1") {

    private val rows = sortedMapOf<Int, MutableMap<Int, String>>()

    fun row(no: Int, vararg values: String): Xlsx {
        values.forEachIndexed { i, v -> if (v.isNotEmpty()) rows.getOrPut(no) { sortedMapOf() }[i] = v }
        return this
    }

    fun bytes(): ByteArray {
        val shared = ArrayList<String>()
        fun idx(text: String): Int {
            val at = shared.indexOf(text)
            return if (at >= 0) at else shared.size.also { shared += text }
        }
        val numeric = Regex("""^-?\d+(\.\d+)?$""")
        val sheet = StringBuilder("<?xml version=\"1.0\"?><worksheet><sheetData>")
        rows.forEach { (no, cols) ->
            sheet.append("<row r=\"$no\">")
            cols.forEach { (col, v) ->
                val ref = "${colRef(col)}$no"
                if (numeric.matches(v)) {
                    sheet.append("<c r=\"$ref\"><v>$v</v></c>")
                } else {
                    sheet.append("<c r=\"$ref\" t=\"s\"><v>${idx(v)}</v></c>")
                }
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")
        val sst = StringBuilder("<?xml version=\"1.0\"?><sst>")
        shared.forEach { sst.append("<si><t xml:space=\"preserve\">").append(esc(it)).append("</t></si>") }
        sst.append("</sst>")
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add(
                "[Content_Types].xml",
                "<?xml version=\"1.0\"?><Types>" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" " +
                    "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>",
            )
            add("_rels/.rels", "<?xml version=\"1.0\"?><Relationships/>")
            add(
                "xl/workbook.xml",
                "<?xml version=\"1.0\"?><workbook " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"${esc(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            )
            add(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\"?><Relationships>" +
                    "<Relationship Id=\"rId1\" Type=\"sheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            )
            add("xl/sharedStrings.xml", sst.toString())
            add("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return out.toByteArray()
    }

    private fun colRef(col: Int): String {
        var n = col
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** 在 [dir] 下落一张成绩表 xlsx（可选带标题行）。 */
internal fun writeScoreXlsx(dir: File, name: String = "成绩表.xlsx", titled: Boolean = false): File {
    val x = Xlsx("Sheet1")
    var no = 1
    if (titled) {
        x.row(no++, "2026 年秋季学期期末成绩表")
        x.row(no++, "班级：三班", "制表：李老师")
    }
    x.row(no++, *SCORE_HEADER.map { it!! }.toTypedArray())
    SCORE_ROWS.forEach { x.row(no++, *it.map { v -> v!! }.toTypedArray()) }
    val f = File(dir, name)
    f.parentFile?.mkdirs()
    f.writeBytes(x.bytes())
    return f
}

/** 在 [dir] 下落一张 csv。 */
internal fun writeCsv(dir: File, name: String, text: String): File {
    val f = File(dir, name)
    f.parentFile?.mkdirs()
    f.writeText(text, Charsets.UTF_8)
    return f
}

/** 成绩表的 csv 版（同数据，测「两条读取路走同一个模型」）。 */
internal fun scoreCsvText(): String =
    (listOf(SCORE_HEADER) + SCORE_ROWS).joinToString("\n") { r -> r.joinToString(",") { it ?: "" } }

/** 一张带手机号与姓名的表（负例 8：这些值不许进日志/登记簿）。 */
internal val PHONE_ROWS: List<List<String?>> = listOf(
    listOf<String?>("姓名", "手机号", "班级"),
    listOf("张三", "13800138000", "三班"),
    listOf("李四", "13900139000", "三班"),
)

// ── 252：旧版 `.xls`（BIFF）落盘夹具 ────────────────────────────────────────
//
// **用 jxl 自己写，不手搓字节**：手搓出来的「xls」只能骗过自己写的断言，骗不过真机——
// 派工单点名「不许造假数据」，夹具本身也得是真的 BIFF（`Workbook.createWorkbook` 的产物）。

/** 一张工作表的填法（列、行都 0 起）。 */
internal class XlsSheet(val name: String) {

    internal val cells = ArrayList<jxl.write.WritableCell>()

    fun text(col: Int, row: Int, value: String): XlsSheet {
        cells += jxl.write.Label(col, row, value)
        return this
    }

    fun number(col: Int, row: Int, value: Double): XlsSheet {
        cells += jxl.write.Number(col, row, value)
        return this
    }

    /** 一个真日期格（**不是**写着日期样子的字符串）。 */
    fun date(col: Int, row: Int, value: java.util.Date): XlsSheet {
        cells += jxl.write.DateTime(col, row, value)
        return this
    }
}

internal fun xlsSheet(name: String): XlsSheet = XlsSheet(name)

/** 在 [dir] 下落一张真 `.xls`（[sheets] 里的第一张就是「第一张工作表」）。 */
internal fun writeXls(dir: File, name: String, vararg sheets: XlsSheet): File {
    val f = File(dir, name)
    f.parentFile?.mkdirs()
    val wb = jxl.Workbook.createWorkbook(f)
    try {
        sheets.forEachIndexed { i, spec ->
            val s = wb.createSheet(spec.name, i)
            spec.cells.forEach { s.addCell(it) }
        }
        wb.write()
    } finally {
        wb.close()
    }
    return f
}

/** `yyyy-MM-dd HH:mm` 的 UTC 时刻（转表前后的日期口径就是 UTC，见 `XlsCompat` 文件头）。 */
internal fun utcMoment(text: String): java.util.Date =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.parse(text)!!

/**
 * 252：真表夹具目录（`..\_zd-private\样本\f9\`）。
 *
 * 单测的工作目录是 `app/`，所以仓库根在 `../`、**私有侧还得再上一级**；
 * 两个候选都试一遍（主工作树与新工作树都该能对上）。**一个都对不上就跳过真表那几条，
 * 绝不造假数据顶替**——这是派工单的红线。
 */
internal val REAL_XLS_DIR: File = listOf(
    File("../../_zd-private/样本/f9"),
    File("../_zd-private/样本/f9"),
).firstOrNull { it.isDirectory } ?: File("../../_zd-private/样本/f9")

/** 临时工作区（用完删）。 */
internal fun tempWorkspace(tag: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "zd232-$tag-${System.nanoTime()}")
    dir.mkdirs()
    return dir
}

/** 递归删掉（测试自己建的目录，不碰用户目录）。 */
internal fun wipe(dir: File) {
    dir.walkBottomUp().forEach { runCatching { it.delete() } }
}
