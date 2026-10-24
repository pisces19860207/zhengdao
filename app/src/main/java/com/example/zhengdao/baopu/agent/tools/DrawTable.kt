// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：表格的**解析＋静态 HTML**（纯函数、零 IO、零 Android）。
//
// 为什么表格单独一个工具：模型写 Markdown 表格几乎是免费的（不用记坐标），用户要的
// 也是「能读、能复制」；HTML 又正好能在玄览的本地预览页签里看（断网、禁 JS 的那一侧）。
// PNG 只是附赠的一眼预览，由沙箱截同一份 HTML 得到——不另写一套栅格排版。
//
// 单元格里的 HTML 一律当**文字**处理：先 cleanText 剥标记，再转义落进 `<td>`。
// 本批不执行 JS、不联网，所以「单元格里放 <script>」只可能变成一行可见的普通文字
// 或者被剥掉并计数，不可能成为可执行内容。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

internal object DrawTable {

    val FORMATS = listOf("markdown", "csv", "json", "auto")

    private const val FONT = 13
    private const val PAD_X = 12
    private const val MIN_COL = 64
    private const val MAX_COL = 320

    data class Rendered(
        val html: String,
        val notes: List<String>,
        val summary: String,
    )

    private data class Parsed(
        val headers: List<String>,
        val rows: List<List<String>>,
        val align: List<String>,
    )

    private data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val align: List<String>,
        val title: String?,
    )

    fun render(sourceRaw: String, formatRaw: String?, titleRaw: String?): DrawResult<Rendered> {
        val source = sourceRaw.replace("\r\n", "\n").replace('\r', '\n')
        if (source.isBlank()) {
            return drawErr("table 是空的：给一段 Markdown 表格、CSV 或 JSON 行。")
        }
        if (source.length > DrawLimits.MAX_INPUT_CHARS) {
            return drawErr("table 输入太长：${source.length} 字（上限 ${DrawLimits.MAX_INPUT_CHARS}）。")
        }
        val fmt = formatRaw?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
        if (fmt !in FORMATS) {
            return drawErr("不认识的 format「$formatRaw」：只认 ${FORMATS.joinToString(" / ")}。")
        }
        return try {
            val cleaner = DrawCleaner()
            val title = titleRaw
                ?.let { cleaner.text(it, "标题", DrawLimits.MAX_TITLE_CHARS) }
                ?.ifBlank { null }
            val parsed = when (resolveFormat(fmt, source)) {
                "markdown" -> parseMarkdown(source)
                "csv" -> parseCsv(source)
                else -> parseJson(source)
            }
            val table = normalize(parsed, title, cleaner)
            val html = buildHtml(table)
            val sanitized = MarkupSanitizer.sanitizeHtml(html)
            if (!sanitized.ok) {
                return drawErr("表格 HTML 消毒失败：${sanitized.problems.joinToString("；")}")
            }
            if (sanitized.removed > 0) {
                return drawErr("表格 HTML 里出现了 ${sanitized.removed} 处白名单外的标记：已拒绝落盘（这是工具自身的缺陷）。")
            }
            drawOk(
                Rendered(
                    html = sanitized.text,
                    notes = cleaner.notes(),
                    summary = "表格：${table.headers.size} 列 × ${table.rows.size} 行数据",
                ),
            )
        } catch (b: DrawBad) {
            drawErr(b.message ?: "表格参数不对")
        } catch (t: Throwable) {
            drawErr("表格解析失败：${t.javaClass.simpleName}（检查 source 的结构）")
        }
    }

    private fun resolveFormat(fmt: String, source: String): String {
        if (fmt != "auto") return fmt
        val firstLine = source.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return when {
            firstLine.startsWith("[") || firstLine.startsWith("{") -> "json"
            firstLine.startsWith("|") -> "markdown"
            else -> "csv"
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────

    private fun parseMarkdown(source: String): Parsed {
        val lines = source.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw DrawBad("Markdown 表格是空的。")
        if (!lines.first().startsWith("|")) {
            throw DrawBad("Markdown 表格每行要以 | 开头（第一行是表头，第二行是 |---| 分隔行）。")
        }
        val rows = lines.map { splitMarkdownRow(it) }
        val separatorAt = rows.indexOfFirst { cells ->
            cells.isNotEmpty() && cells.all { it.matches(Regex(""":?-{1,}:?""")) }
        }
        if (separatorAt == 0) throw DrawBad("Markdown 表格第一行不能是 |---| 分隔行：先给表头。")
        val hasSeparator = separatorAt > 0
        val headerRow = rows.first()
        val body = if (hasSeparator) rows.drop(separatorAt + 1) else rows.drop(1)
        if (body.isEmpty()) throw DrawBad("Markdown 表格只有表头，没有数据行。")
        val align = if (hasSeparator) rows[separatorAt].map { markdownAlign(it) } else headerRow.map { "left" }
        return Parsed(headerRow, body, align)
    }

    private fun splitMarkdownRow(line: String): List<String> {
        var s = line
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        return s.split('|').map { it.trim() }
    }

    private fun markdownAlign(cell: String): String {
        val t = cell.trim()
        return when {
            t.startsWith(":") && t.endsWith(":") -> "center"
            t.endsWith(":") -> "right"
            else -> "left"
        }
    }

    private fun parseCsv(source: String): Parsed {
        val rows = ArrayList<MutableList<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < source.length && source[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                    }
                    '\n' -> {
                        row.add(cell.toString())
                        cell.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (inQuotes) throw DrawBad("CSV 里有没闭合的双引号：把引号补上，或改用 JSON 的字符串转义。")
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row)
        }
        val kept = rows.filter { r -> r.any { it.isNotBlank() } }
        if (kept.isEmpty()) throw DrawBad("CSV 里没有可读的行。")
        val header = kept.first()
        val body = kept.drop(1)
        if (body.isEmpty()) throw DrawBad("CSV 只有表头，没有数据行。")
        return Parsed(header, body, header.map { "left" })
    }

    private fun parseJson(source: String): Parsed {
        val text = source.trim()
        if (text.startsWith("[")) {
            val arr = JSONArray(text)
            if (arr.length() == 0) throw DrawBad("JSON 数组是空的。")
            val first = arr.opt(0)
            return when (first) {
                is JSONObject -> jsonObjects(arr)
                is JSONArray -> jsonRows(arr)
                else -> throw DrawBad("JSON 数组里要放对象（表头取字段名）或数组行（第一行是表头）。")
            }
        }
        if (!text.startsWith("{")) {
            throw DrawBad("JSON 要么是对象数组，要么是 {\"headers\":[…],\"rows\":[[…]]}。")
        }
        val obj = JSONObject(text)
        val headerArr = obj.optJSONArray("headers")
            ?: throw DrawBad("JSON 对象缺 headers 数组。")
        val rowArr = obj.optJSONArray("rows") ?: throw DrawBad("JSON 对象缺 rows 数组。")
        val headers = (0 until headerArr.length()).map { headerArr.optString(it) }
        val rows = ArrayList<List<String>>(rowArr.length())
        for (i in 0 until rowArr.length()) {
            val item = rowArr.opt(i)
            when (item) {
                is JSONArray -> rows.add((0 until item.length()).map { cellText(item.opt(it)) })
                is JSONObject -> rows.add(headers.map { cellText(item.opt(it)) })
                else -> throw DrawBad("rows[$i] 既不是数组也不是对象。")
            }
        }
        val alignArr = obj.optJSONArray("align")
        val align = if (alignArr != null) {
            (0 until alignArr.length()).map { alignArr.optString(it) }
        } else {
            headers.map { "left" }
        }
        return Parsed(headers, rows, align)
    }

    private fun jsonObjects(arr: JSONArray): Parsed {
        val keys = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw DrawBad("JSON 数组第 ${i + 1} 项不是对象。")
            keys.addAll(o.keys().asSequence().toList())
        }
        val headers = keys.toList()
        if (headers.isEmpty()) throw DrawBad("JSON 对象里没有字段。")
        val rows = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            headers.map { cellText(o.opt(it)) }
        }
        return Parsed(headers, rows, headers.map { "left" })
    }

    private fun jsonRows(arr: JSONArray): Parsed {
        val matrix = (0 until arr.length()).map { i ->
            val row = arr.optJSONArray(i)
                ?: throw DrawBad("JSON 数组第 ${i + 1} 项不是数组（要么全是对象，要么全是数组行）。")
            (0 until row.length()).map { cellText(row.opt(it)) }
        }
        if (matrix.size < 2) throw DrawBad("JSON 行数组至少要两行：第一行表头 + 一行数据。")
        return Parsed(matrix.first(), matrix.drop(1), matrix.first().map { "left" })
    }

    private fun cellText(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until v.length()).joinToString("，") { cellText(v.opt(it)) }
        is JSONObject -> v.keys().asSequence().joinToString("，") { "$it=${cellText(v.opt(it))}" }
        else -> v.toString()
    }

    // ── 规整 + 限额 ──────────────────────────────────────────────────────

    private fun normalize(parsed: Parsed, title: String?, cleaner: DrawCleaner): Table {
        val cols = parsed.headers.size
        if (cols == 0) throw DrawBad("表格一列都没有。")
        if (cols > DrawLimits.MAX_TABLE_COLS) {
            throw DrawBad("列太多：$cols 列（上限 ${DrawLimits.MAX_TABLE_COLS}）。")
        }
        if (parsed.rows.size > DrawLimits.MAX_TABLE_ROWS) {
            throw DrawBad("行太多：${parsed.rows.size} 行（上限 ${DrawLimits.MAX_TABLE_ROWS}）。")
        }
        val headers = parsed.headers.mapIndexed { i, h ->
            val v = cleaner.textStrict(h, "表头第 ${i + 1} 列")
            v.ifBlank { "列 ${i + 1}" }
        }
        val rows = parsed.rows.mapIndexed { r, cells ->
            if (cells.size != cols) {
                throw DrawBad("第 ${r + 1} 行有 ${cells.size} 列，表头有 $cols 列：每行列数必须一致。")
            }
            cells.mapIndexed { c, v -> cleaner.textStrict(v, "第 ${r + 1} 行第 ${c + 1} 列") }
        }
        val align = (0 until cols).map { i ->
            val a = parsed.align.getOrNull(i)?.trim()?.lowercase().orEmpty().ifBlank { "left" }
            if (a !in setOf("left", "center", "right")) {
                throw DrawBad("第 ${i + 1} 列的对齐方式「$a」不认识：只认 left / center / right。")
            }
            a
        }
        return Table(headers, rows, align, title)
    }

    // ── HTML ────────────────────────────────────────────────────────────

    private fun buildHtml(t: Table): String {
        val sb = StringBuilder(1024)
        sb.append("<html><head><meta charset=\"utf-8\"/>")
        sb.append("<title>").append(DrawText.escapeXml(t.title ?: "表格")).append("</title>")
        sb.append("</head><body style=\"margin:0;padding:16px;background:#ffffff;color:#111827\">")
        sb.append("<table style=\"border-collapse:collapse;font-size:13px;")
        sb.append("font-family:sans-serif;table-layout:auto\">")
        if (t.title != null) {
            sb.append("<caption style=\"caption-side:top;text-align:left;font-size:15px;")
            sb.append("font-weight:bold;padding:0 0 10px 0\">")
                .append(DrawText.escapeXml(t.title)).append("</caption>")
        }
        val natural = (0 until t.headers.size).map { col ->
            naturalWidth(t, col)
        }
        val total = max(1, natural.sum())
        sb.append("<colgroup>")
        natural.forEach { w ->
            val pct = (w * 100.0 / total).roundToInt().coerceAtLeast(4)
            sb.append("<col style=\"width:").append(pct).append("%\"/>")
        }
        sb.append("</colgroup><thead><tr>")
        t.headers.forEachIndexed { i, h ->
            sb.append("<th style=\"").append(thStyle(t.align[i])).append("\">")
                .append(DrawText.escapeXml(h)).append("</th>")
        }
        sb.append("</tr></thead><tbody>")
        for (row in t.rows) {
            sb.append("<tr>")
            row.forEachIndexed { i, v ->
                sb.append("<td style=\"").append(tdStyle(t.align[i])).append("\">")
                    .append(DrawText.escapeXml(v)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</tbody></table></body></html>")
        return sb.toString()
    }

    private fun thStyle(align: String): String =
        "border:1px solid #9ca3af;padding:8px 12px;background:#f3f4f6;" +
            "font-weight:bold;vertical-align:top;text-align:$align;"

    private fun tdStyle(align: String): String =
        "border:1px solid #9ca3af;padding:8px 12px;vertical-align:top;text-align:$align;"

    private fun naturalWidth(t: Table, col: Int): Int {
        var w = DrawText.width(t.headers[col], FONT)
        for (row in t.rows) w = max(w, DrawText.width(row[col], FONT))
        return (w + 2 * PAD_X).coerceIn(MIN_COL, MAX_COL)
    }
}
