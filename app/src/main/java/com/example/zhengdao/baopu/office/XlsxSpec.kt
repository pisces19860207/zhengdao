// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开标准：ECMA-376 Part 1（SpreadsheetML：workbook/worksheet/styles/sharedStrings）、
//   OPC（[Content_Types].xml 与 .rels 关系）、Excel 1900 日期系统（序列值 1899-12-30 起算）。
//
// 第六百三十二批 · xlsx 写出的**表格模型**。
//
// 与 `sheet_read` 的分工（同 232 的老口径）：那边把表读成行（单元格原样、不猜类型）；
// 这边把「已经决定好类型与格式的数据」写成表——**仍然不猜**：
// 字符串就是文本，要写成数字/日期就必须由调用方显式给 `format`。
// 这样「读回来逐格一致」才有意义（判据②）：写进去什么，读出来就是什么。
package com.example.zhengdao.baopu.office

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 单元格格式（**闭集**：632 只做「数字/日期」这几档，不收任意 formatCode）。
 *
 * 为什么不开放任意格式串：格式串写错时 Excel 打开是「一堆 ####」而不是报错，
 * 用户只会觉得"文件坏了"；闭集里的每一档都由我们给出确定的面板样式。
 */
internal enum class XlsxFormat(val code: String) {

    /** 整数。 */
    INT("0"),

    /** 两位小数。 */
    DEC2("0.00"),

    /** 千分位两位小数（金额）。 */
    DEC2_GROUPED("#,##0.00"),

    /** 百分比（整数位）。 */
    PERCENT("0%"),

    /** 百分比（两位小数）。 */
    PERCENT2("0.00%"),

    /** 日期 `2026-11-13`。 */
    DATE("yyyy-mm-dd"),

    /** 日期时间 `2026-11-13 09:30`。 */
    DATETIME("yyyy-mm-dd hh:mm"),

    /** 时刻 `09:30`。 */
    TIME("hh:mm"),
    ;

    companion object {

        /** 用户/模型写的格式名 → 枚举（认常见别名；认不出 ⇒ null，由调用方报错）。 */
        fun of(raw: String): XlsxFormat? {
            val key = raw.trim().lowercase()
            return entries.firstOrNull { it.code.lowercase() == key || it.name.lowercase() == key }
                ?: ALIASES[key]
        }

        private val ALIASES: Map<String, XlsxFormat> = mapOf(
            "number" to INT,
            "integer" to INT,
            "int" to INT,
            "decimal" to DEC2,
            "money" to DEC2_GROUPED,
            "currency" to DEC2_GROUPED,
            "percent" to PERCENT,
            "percentage" to PERCENT,
            "date" to DATE,
            "day" to DATE,
            "datetime" to DATETIME,
            "date_time" to DATETIME,
            "time" to TIME,
        )
    }
}

/** 一个格子：空 / 文本 / 数字（日期与时刻都是带格式的数字——Excel 本来就这么存）。 */
internal sealed interface XlsxCell {

    /** 空格子（不写 `<c>`，读回来是 null——与 `sheet_read` 的口径一致）。 */
    data object Blank : XlsxCell

    /** 文本（走 sharedStrings）。 */
    data class Text(val value: String) : XlsxCell

    /**
     * 数字（`fmt=null` ⇒ 常规）。
     *
     * 日期/时刻**也是** [Num]：`v` 是 Excel 序列值，`fmt` 决定它显示成什么——
     * 这正是判据②「值与日期格式逐格一致」要钉的东西。
     */
    data class Num(val value: Double, val fmt: XlsxFormat? = null) : XlsxCell
}

/** 一个 sheet。 */
internal data class XlsxSheet(
    val name: String,
    val rows: List<List<XlsxCell>>,
    val header: Boolean,
    val merges: List<String>,
)

/** 一份 xlsx（≤ [OfficeLimits.XLSX_MAX_SHEETS] 个 sheet）。 */
internal data class XlsxSpec(val fileBase: String, val sheets: List<XlsxSheet>)

/**
 * Excel 日期换算（**纯函数**）。
 *
 * 1900 日期系统的「第 0 天」是 1899-12-30（Excel 为了把 1900 当闰年而留下的历史偏移，
 * 所有实现都照这个数算）；时刻是当天的小数部分。
 */
internal object ExcelDate {

    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 文本 → 序列值；解析不出 ⇒ null（由调用方报「格式不对」）。 */
    fun serialOf(text: String, fmt: XlsxFormat): Double? {
        val raw = text.trim()
        return when (fmt) {
            XlsxFormat.DATE -> dateOf(raw)?.toDouble()
            XlsxFormat.DATETIME -> dateTimeOf(raw)
            XlsxFormat.TIME -> timeOf(raw)
            else -> raw.toDoubleOrNull()
        }
    }

    private fun dateOf(raw: String): Long? = runCatching {
        ChronoUnit.DAYS.between(EPOCH, LocalDate.parse(raw, DATE_FMT))
    }.getOrNull()

    private fun dateTimeOf(raw: String): Double? {
        val text = raw.replace('T', ' ').trim()
        val parsed = runCatching { LocalDateTime.parse(text, DATE_TIME_FMT) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, DATE_FMT).atStartOfDay() }.getOrNull()
            ?: return null
        val days = ChronoUnit.DAYS.between(EPOCH, parsed.toLocalDate()).toDouble()
        val minutes = parsed.hour * 60.0 + parsed.minute
        return days + minutes / 1440.0
    }

    private fun timeOf(raw: String): Double? {
        val text = raw.trim()
        val hm = runCatching { LocalTime.parse(text) }.getOrNull() ?: return null
        return (hm.hour * 60.0 + hm.minute) / 1440.0
    }
}

/** `sheets` 的解析与校验（**纯函数**，零 IO）。 */
internal object XlsxSpecParser {

    const val ARG_NAME = "name"
    const val ARG_SHEETS = "sheets"

    private val MERGE_REF = Regex("""^[A-Z]+\d+:[A-Z]+\d+$""", RegexOption.IGNORE_CASE)

    /** sheet 名里 Excel 不允许的字符（`[ ] : * ? / \`）。 */
    private val BAD_NAME_CHARS = charArrayOf('[', ']', ':', '*', '?', '/', '\\')

    fun parse(args: JSONObject): Step<XlsxSpec> {
        val array = args.optJSONArray(ARG_SHEETS)
            ?: return Step.Bad("缺少必填参数「$ARG_SHEETS」：把每个 sheet 的 rows 列出来")
        if (array.length() == 0) return Step.Bad("「$ARG_SHEETS」是空的——没有表可写")
        if (array.length() > OfficeLimits.XLSX_MAX_SHEETS) {
            return Step.Bad(
                "sheet 有 ${array.length()} 个，超过上限 ${OfficeLimits.XLSX_MAX_SHEETS} 个——" +
                    "一个文件最多 ${OfficeLimits.XLSX_MAX_SHEETS} 个工作表，请拆成多个文件（没有静默截断）",
            )
        }
        val sheets = mutableListOf<XlsxSheet>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return Step.Bad("第 ${i + 1} 个 sheet 不是对象（要写成 {\"name\":\"汇总\",\"rows\":[…] }）")
            when (val one = sheetOf(obj, i, sheets.map { it.name })) {
                is Step.Bad -> return one
                is Step.Ok -> sheets += one.value
            }
        }
        return Step.Ok(XlsxSpec(fileBase = args.optString(ARG_NAME).trim().ifEmpty { sheets.first().name }, sheets = sheets))
    }

    private fun sheetOf(obj: JSONObject, index: Int, taken: List<String>): Step<XlsxSheet> {
        val at = "第 ${index + 1} 个 sheet"
        val name = nameOf(obj, index)
        if (name.isEmpty()) return Step.Bad("$at 的 name 是空的")
        if (name.length > OfficeLimits.XLSX_MAX_SHEET_NAME) {
            return Step.Bad("$at 的 name「$name」超过 ${OfficeLimits.XLSX_MAX_SHEET_NAME} 个字（Excel 硬上限），请改短")
        }
        if (name.any { it in BAD_NAME_CHARS }) {
            return Step.Bad("$at 的 name「$name」里有 Excel 不允许的字符（[ ] : * ? / \\ 都不行）")
        }
        if (name in taken) return Step.Bad("$at 的 name「$name」与前面重复了（sheet 名必须唯一）")
        val rowsArr = obj.optJSONArray("rows") ?: return Step.Bad("$at 缺 rows（二维数组）")
        if (rowsArr.length() == 0) return Step.Bad("$at 的 rows 是空的")
        if (rowsArr.length() > OfficeLimits.XLSX_MAX_ROWS) {
            return Step.Bad("$at 有 ${rowsArr.length()} 行，超过上限 ${OfficeLimits.XLSX_MAX_ROWS} 行；请拆成多个 sheet")
        }
        return when (val rows = rowsOf(rowsArr, at)) {
            is Step.Bad -> rows
            is Step.Ok -> {
                val merges = mergesOf(obj, at)
                if (merges is Step.Bad) {
                    merges
                } else {
                    Step.Ok(
                        XlsxSheet(
                            name = name,
                            rows = rows.value,
                            header = obj.optBoolean("header", true),
                            merges = (merges as Step.Ok).value,
                        ),
                    )
                }
            }
        }
    }

    private fun nameOf(obj: JSONObject, index: Int): String {
        val raw = obj.optString("name").trim()
        return raw.ifEmpty { "Sheet${index + 1}" }
    }

    private fun rowsOf(array: JSONArray, at: String): Step<List<List<XlsxCell>>> {
        val out = mutableListOf<List<XlsxCell>>()
        for (r in 0 until array.length()) {
            val row = array.optJSONArray(r) ?: return Step.Bad("$at 的第 ${r + 1} 行不是数组")
            val cells = mutableListOf<XlsxCell>()
            for (c in 0 until row.length()) {
                when (val cell = cellOf(row.opt(c), at, r + 1)) {
                    is Step.Bad -> return cell
                    is Step.Ok -> cells += cell.value
                }
            }
            out += cells
        }
        return Step.Ok(out)
    }

    private fun cellOf(raw: Any?, at: String, rowNo: Int): Step<XlsxCell> = when (raw) {
        null, JSONObject.NULL -> Step.Ok(XlsxCell.Blank)
        is Number -> Step.Ok(XlsxCell.Num(raw.toDouble()))
        is Boolean -> Step.Ok(XlsxCell.Text(if (raw) "TRUE" else "FALSE"))
        is String -> textCell(raw, at, rowNo)
        is JSONObject -> richCell(raw, at, rowNo)
        else -> Step.Bad("$at 第 $rowNo 行有认不出的格子（只收 文本/数字/空/{\"v\":…,\"format\":…}）")
    }

    private fun textCell(raw: String, at: String, rowNo: Int): Step<XlsxCell> {
        if (raw.length > OfficeLimits.XLSX_MAX_CELL_CHARS) {
            return Step.Bad("$at 第 $rowNo 行有一格 ${raw.length} 字，超过上限 ${OfficeLimits.XLSX_MAX_CELL_CHARS} 字；请截短或另存")
        }
        return Step.Ok(if (raw.isEmpty()) XlsxCell.Blank else XlsxCell.Text(raw))
    }

    private fun richCell(obj: JSONObject, at: String, rowNo: Int): Step<XlsxCell> {
        val rawFormat = obj.optString("format").trim()
        if (rawFormat.isEmpty()) {
            val v = obj.opt("v")
            return if (v is Number) Step.Ok(XlsxCell.Num(v.toDouble()))
            else textCell(obj.optString("v"), at, rowNo)
        }
        val fmt = XlsxFormat.of(rawFormat)
            ?: return Step.Bad("$at 第 $rowNo 行的 format「$rawFormat」不认得（可用：数字 0 / 0.00 / #,##0.00 / 0% / 0.00% /yyyy-mm-dd / yyyy-mm-dd hh:mm / hh:mm）")
        val v = obj.opt("v")
        if (v is Number) return Step.Ok(XlsxCell.Num(v.toDouble(), fmt))
        val text = obj.optString("v").trim()
        val serial = ExcelDate.serialOf(text, fmt)
            ?: return Step.Bad("$at 第 $rowNo 行的值「$text」不是合法的 ${fmt.code}（日期写 2026-11-13；时刻写 09:30）")
        return Step.Ok(XlsxCell.Num(serial, fmt))
    }

    private fun mergesOf(obj: JSONObject, at: String): Step<List<String>> {
        val array = obj.optJSONArray("merges") ?: return Step.Ok(emptyList())
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val ref = array.optString(i).trim().uppercase()
            if (!MERGE_REF.matches(ref)) {
                return Step.Bad("$at 的 merges 第 ${i + 1} 项「$ref」不是 A1:C1 这种形状")
            }
            out += ref
        }
        return Step.Ok(out)
    }
}
