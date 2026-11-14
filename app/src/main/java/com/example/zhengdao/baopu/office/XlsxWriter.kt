// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开标准：ECMA-376 Part 1（SpreadsheetML：workbook/worksheet/styles/sharedStrings）、
//   OPC 关系与内容类型。实现只用 StringBuilder / java.util.zip —— 不引 poi-ooxml、不引 xmlbeans。
//
// 第六百三十二批 · xlsx 写出（**自研 OOXML 拼装**，判据⑤：零新增依赖）。
//
// 四件事按派工单 §一② 落地：
//   · 多 sheet（≤5，超出由解析层明确报错）；
//   · 表头加粗（font 1）＋ 冻结首行（pane ySplit=1）；
//   · 列宽按内容自适应（CJK 按 2 个字宽算）；
//   · 数字/日期格式（闭集 [XlsxFormat]）＋ 合并单元格（mergeCells）。
package com.example.zhengdao.baopu.office

/** sharedStrings 登记簿（同一个字符串只写一次；Excel 的常规做法）。 */
private class SharedStrings {

    private val index = LinkedHashMap<String, Int>()

    /** 引用总次数（`count` 属性：出现几次，不是去重几条）。 */
    var refs: Int = 0
        private set

    fun idOf(text: String): Int {
        refs++
        return index.getOrPut(text) { index.size }
    }

    fun xml(): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<sst xmlns=\"").append(OoxmlNs.SHEET).append("\"")
            .append(" count=\"").append(refs).append("\" uniqueCount=\"").append(index.size).append("\">")
        for (text in index.keys) {
            sb.append("<si><t xml:space=\"preserve\">").append(Xml.text(text)).append("</t></si>")
        }
        return sb.append("</sst>").toString()
    }
}

/**
 * 样式簿：把「加粗 × 数字格式」的每种组合编成一条 `cellXfs`。
 *
 * 为什么懒分配：只有真用到的格式才进 styles.xml——写一份全是文本的表，
 * 样式表里不该冒出十条没人用的日期格式（那会让 filesize 与"这份表用了什么格式"两件事都失真）。
 */
private class StyleBook {

    /** 组合键（加粗｜格式名）→ cellXfs 下标。 */
    private val xfs = LinkedHashMap<String, Int>()

    /** 格式 → numFmtId（自定义从 164 起，与 Excel 的内置号段不冲突）。 */
    private val numFmts = LinkedHashMap<XlsxFormat, Int>()

    init {
        xfs["false|"] = 0 // 常规
        xfs["true|"] = 1  // 表头加粗
    }

    fun idOf(bold: Boolean, fmt: XlsxFormat?): Int {
        val key = "$bold|${fmt?.code.orEmpty()}"
        xfs[key]?.let { return it }
        if (fmt != null && fmt !in numFmts) numFmts[fmt] = 164 + numFmts.size
        val next = xfs.size
        xfs[key] = next
        return next
    }

    /** 该格式的 numFmtId（只有登记过的才返回；用于写 cellXfs）。 */
    private fun fmtIdOf(fmt: XlsxFormat?): Int =
        if (fmt == null) 0 else numFmts[fmt] ?: 0

    fun xml(): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<styleSheet xmlns=\"").append(OoxmlNs.SHEET).append("\">")
        appendNumFmts(sb)
        sb.append("<fonts count=\"2\">")
            .append("<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            .append("<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>")
            .append("</fonts>")
        sb.append("<fills count=\"2\">")
            .append("<fill><patternFill patternType=\"none\"/></fill>")
            .append("<fill><patternFill patternType=\"gray125\"/></fill>")
            .append("</fills>")
        sb.append("<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>")
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        appendCellXfs(sb)
        sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
        return sb.append("</styleSheet>").toString()
    }

    private fun appendNumFmts(sb: StringBuilder) {
        if (numFmts.isEmpty()) return
        sb.append("<numFmts count=\"").append(numFmts.size).append("\">")
        for ((fmt, id) in numFmts) {
            sb.append("<numFmt numFmtId=\"").append(id).append("\" formatCode=\"")
                .append(Xml.attr(fmt.code)).append("\"/>")
        }
        sb.append("</numFmts>")
    }

    private fun appendCellXfs(sb: StringBuilder) {
        sb.append("<cellXfs count=\"").append(xfs.size).append("\">")
        for ((key, _) in xfs) {
            val bold = key.startsWith("true")
            val code = key.substringAfter('|')
            val fmt = XlsxFormat.entries.firstOrNull { it.code == code }
            val fontId = if (bold) 1 else 0
            sb.append("<xf numFmtId=\"").append(fmtIdOf(fmt)).append("\" fontId=\"").append(fontId)
                .append("\" fillId=\"0\" borderId=\"0\" xfId=\"0\"")
            if (bold) sb.append(" applyFont=\"1\"")
            if (fmt != null) sb.append(" applyNumberFormat=\"1\"")
            sb.append("/>")
        }
        sb.append("</cellXfs>")
    }
}

/**
 * xlsx 装配器：模型（[XlsxSpec]）→ 一个能打开的 `.xlsx` 字节包。
 *
 * 不做的事（派工单 §三）：不算公式（只写值）、不写图表、不做条件格式、不设打印区域。
 */
internal object XlsxWriter {

    private const val NS_MAIN = OoxmlNs.SHEET
    private const val REL = OoxmlNs.OFFICE_REL
    private const val DOC_REL = OoxmlNs.OFFICE_REL

    fun build(spec: XlsxSpec): ByteArray {
        val strings = SharedStrings()
        val styles = StyleBook()
        // 先把每个 sheet 渲染出来（渲染过程会登记共享字符串与样式），再写公共部件
        val sheets = spec.sheets.map { sheet -> renderSheet(sheet, strings, styles) }
        val pkg = OoxmlPackage()
        pkg.xml(OoxmlPackage.CONTENT_TYPES, contentTypes(spec.sheets.size))
        pkg.xml(OoxmlPackage.ROOT_RELS, rootRels())
        pkg.xml("xl/workbook.xml", workbookXml(spec))
        pkg.xml("xl/_rels/workbook.xml.rels", workbookRels(spec.sheets.size))
        pkg.xml("xl/styles.xml", styles.xml())
        pkg.xml("xl/sharedStrings.xml", strings.xml())
        spec.sheets.forEachIndexed { i, _ -> pkg.xml("xl/worksheets/sheet${i + 1}.xml", sheets[i]) }
        return pkg.build()
    }

    // ── 包清单与关系 ──────────────────────────────────────────────────────

    private fun contentTypes(count: Int): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<Types xmlns=\"").append(OoxmlNs.CONTENT_TYPES).append("\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        for (i in 1..count) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet$i.xml\"")
                .append(" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        }
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        sb.append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>")
        return sb.append("</Types>").toString()
    }

    private fun rootRels(): String = XML_HEAD +
        "<Relationships xmlns=\"" + OoxmlNs.PACKAGE_REL + "\">" +
        "<Relationship Id=\"rId1\" Type=\"$DOC_REL/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private fun workbookXml(spec: XlsxSpec): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(REL).append("\">")
        sb.append("<bookViews><workbookView/></bookViews><sheets>")
        spec.sheets.forEachIndexed { i, sheet ->
            sb.append("<sheet name=\"").append(Xml.attr(sheet.name)).append("\" sheetId=\"")
                .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>")
        }
        return sb.append("</sheets></workbook>").toString()
    }

    private fun workbookRels(count: Int): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<Relationships xmlns=\"").append(OoxmlNs.PACKAGE_REL).append("\">")
        for (i in 1..count) {
            sb.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(DOC_REL)
                .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>")
        }
        sb.append("<Relationship Id=\"rId").append(count + 1).append("\" Type=\"").append(DOC_REL)
            .append("/styles\" Target=\"styles.xml\"/>")
        sb.append("<Relationship Id=\"rId").append(count + 2).append("\" Type=\"").append(DOC_REL)
            .append("/sharedStrings\" Target=\"sharedStrings.xml\"/>")
        return sb.append("</Relationships>").toString()
    }

    // ── 工作表 ────────────────────────────────────────────────────────────

    private fun renderSheet(sheet: XlsxSheet, strings: SharedStrings, styles: StyleBook): String {
        val sb = StringBuilder(XML_HEAD)
        sb.append("<worksheet xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(REL).append("\">")
        appendViews(sb, sheet.header)
        appendCols(sb, sheet)
        appendRows(sb, sheet, strings, styles)
        appendMerges(sb, sheet.merges)
        sb.append("</worksheet>")
        return sb.toString()
    }

    /** 冻结首行（判据②：表头加粗＋冻结首行）。 */
    private fun appendViews(sb: StringBuilder, header: Boolean) {
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        if (header) {
            sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            sb.append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>")
        }
        sb.append("</sheetView></sheetViews>")
    }

    /** 列宽自适应：按该列最长内容的显示宽度算（CJK 2 个字宽，上下限 8–60）。 */
    private fun appendCols(sb: StringBuilder, sheet: XlsxSheet) {
        val widths = columnWidths(sheet)
        if (widths.isEmpty()) return
        sb.append("<cols>")
        widths.forEachIndexed { i, w ->
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                .append("\" width=\"").append(w).append("\" customWidth=\"1\"/>")
        }
        sb.append("</cols>")
    }

    private fun columnWidths(sheet: XlsxSheet): List<Double> {
        val widest = sheet.rows.maxOfOrNull { it.size } ?: 0
        return (0 until widest).map { col ->
            val chars = sheet.rows.maxOfOrNull { row -> displayWidth(row.getOrNull(col)) } ?: 0
            (chars * 1.15 + 2).coerceIn(8.0, 60.0)
        }
    }

    private fun displayWidth(cell: XlsxCell?): Int = when (cell) {
        null, XlsxCell.Blank -> 0
        is XlsxCell.Text -> cell.value.sumOf { if (it.code > 0x2E80) 2 else 1 }
        is XlsxCell.Num -> numberText(cell.value).length + (if (cell.fmt == XlsxFormat.PERCENT || cell.fmt == XlsxFormat.PERCENT2) 1 else 0)
    }

    private fun appendRows(sb: StringBuilder, sheet: XlsxSheet, strings: SharedStrings, styles: StyleBook) {
        sb.append("<sheetData>")
        sheet.rows.forEachIndexed { r, row ->
            if (row.all { it is XlsxCell.Blank }) return@forEachIndexed
            val bold = sheet.header && r == 0
            sb.append("<row r=\"").append(r + 1).append("\">")
            row.forEachIndexed { c, cell ->
                appendCell(sb, r + 1, c, cell, bold, strings, styles)
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
    }

    private fun appendCell(
        sb: StringBuilder,
        rowNo: Int,
        col: Int,
        cell: XlsxCell,
        bold: Boolean,
        strings: SharedStrings,
        styles: StyleBook,
    ) {
        when (cell) {
            is XlsxCell.Blank -> Unit
            is XlsxCell.Text -> {
                val style = styles.idOf(bold, null)
                sb.append("<c r=\"").append(colName(col)).append(rowNo).append("\" s=\"").append(style)
                    .append("\" t=\"s\"><v>").append(strings.idOf(cell.value)).append("</v></c>")
            }
            is XlsxCell.Num -> {
                val style = styles.idOf(bold, cell.fmt)
                sb.append("<c r=\"").append(colName(col)).append(rowNo).append("\" s=\"").append(style)
                    .append("\"><v>").append(numberText(cell.value)).append("</v></c>")
            }
        }
    }

    private fun appendMerges(sb: StringBuilder, merges: List<String>) {
        if (merges.isEmpty()) return
        sb.append("<mergeCells count=\"").append(merges.size).append("\">")
        for (ref in merges) sb.append("<mergeCell ref=\"").append(Xml.attr(ref)).append("\"/>")
        sb.append("</mergeCells>")
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /** 数字的 XML 文本：整数不带 `.0`（`46313` 而不是 `46313.0`，与 Excel 自己写的一致）。 */
    private fun numberText(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val whole = value.toLong()
        return if (value == whole.toDouble() && kotlin.math.abs(value) < 1e15) whole.toString() else value.toString()
    }

    /** 0 → A、25 → Z、26 → AA（Excel 的列字母，与 `sheet_read` 的 [SheetRange.colIndex] 互逆）。 */
    internal fun colName(index: Int): String {
        var n = index
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }
}
