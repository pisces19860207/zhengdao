// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百四十五批 · 往返夹具的构造与导出工具。
// 夹具一律走生产 Writer 真生成（不手写一份「假」OOXML），这样读回不一致就是真回归。
package com.example.zhengdao.baopu.office.officebaseline

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 造一个块：数组型键按 List 递归转 JSONArray。 */
private fun block(type: String, vararg pairs: Pair<String, Any>): JSONObject {
    val obj = JSONObject().put("type", type)
    for ((k, v) in pairs) obj.put(k, toJson(v))
    return obj
}

private fun toJson(v: Any): Any = when (v) {
    is List<*> -> JSONArray().also { a -> v.forEach { item -> if (item != null) a.put(toJson(item)) else a.put(JSONObject.NULL) } }
    else -> v
}

/** docx 夹具：3 级标题 + 加粗行内 + 两个有序列表 + 一个无序列表 + 一张 3 列表格 + 占位符。 */
internal fun docxArgs(): JSONObject = JSONObject()
    .put("name", "季度报告")
    .put("header", "内部资料")
    .put(
        "blocks",
        JSONArray()
            .put(block("heading", "level" to 1, "text" to "季度报告"))
            .put(block("heading", "level" to 2, "text" to "一、概览"))
            .put(block("heading", "level" to 3, "text" to "1.1 成本"))
            .put(block("paragraph", "text" to "本季**营收**{{Amount}}亿元。"))
            .put(block("list", "ordered" to true, "items" to listOf("第一项", "第二项", "第三项")))
            .put(block("list", "ordered" to true, "items" to listOf("甲", "乙")))
            .put(block("list", "ordered" to false, "items" to listOf("要点A", "要点B")))
            .put(
                block(
                    "table",
                    "columns" to listOf("项目", "数值", "备注"),
                    "rows" to listOf(listOf("营收", "100", "增长"), listOf("成本", "60", "持平")),
                ),
            ),
    )

private fun rich(v: Any, fmt: String? = null): JSONObject {
    val o = JSONObject().put("v", v)
    if (fmt != null) o.put("format", fmt)
    return o
}

private fun sheetObj(name: String, rows: JSONArray, merges: JSONArray? = null): JSONObject {
    val o = JSONObject().put("name", name).put("rows", rows)
    if (merges != null) o.put("merges", merges)
    return o
}

/**
 * xlsx 夹具：两张表。
 *  · 明细：文本/整数/两位小数/日期四列 + 合并区；数量列 B2..B4 = 3,2,5
 *  · 汇总：项目/值两列
 * 明细第 5 行先写数量 0，随后由 [injectFormula] 改成 SUM(B2:B4) 并放缓存 10。
 */
internal fun xlsxArgs(): JSONObject {
    val detail = JSONArray()
        .put(JSONArray().put("品名").put("数量").put("金额").put("日期"))
        .put(JSONArray().put("甲").put(3).put(rich(9.5, "0.00")).put(rich("2026-11-13", "yyyy-mm-dd")))
        .put(JSONArray().put("乙").put(2).put(rich(4.25, "0.00")).put(rich("2026-11-14", "yyyy-mm-dd")))
        .put(JSONArray().put("丙").put(5).put(rich(12.0, "0.00")).put(rich("2026-11-15", "yyyy-mm-dd")))
        .put(JSONArray().put("合计").put(0))
    val summary = JSONArray()
        .put(JSONArray().put("项目").put("值"))
        .put(JSONArray().put("总数量").put(10))
    return JSONObject().put("name", "账目").put(
        "sheets",
        JSONArray()
            .put(sheetObj("明细", detail, JSONArray().put("A7:B7")))
            .put(sheetObj("汇总", summary)),
    )
}

/** 数字写回文本：整数不带 .0（与生产 XlsxWriter.numberText 同口径，但这里是独立重算）。 */
internal fun numText(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val whole = value.toLong()
    return if (value == whole.toDouble() && kotlin.math.abs(value) < 1e15) whole.toString() else value.toString()
}

/** 把明细 B5 改成公式格：`<f>SUM(B2:B4)</f>` + 缓存值 10（模拟 Excel 保存后的样子）。 */
internal fun injectFormula(xlsx: ByteArray): ByteArray {
    val entries = LinkedHashMap(unzip(xlsx))
    val part = "xl/worksheets/sheet1.xml"
    val xml = String(entries[part] ?: throw AssertionError("没有 sheet1.xml"), Charsets.UTF_8)
    val changed = xml.replaceFirst(
        Regex("<c r=\"B5\"[^>]*>.*?</c>"),
        "<c r=\"B5\" s=\"0\"><f>SUM(B2:B4)</f><v>10</v></c>",
    )
    if (changed == xml) throw AssertionError("没在明细表里找到 B5 单元格，无法注入公式夹具")
    entries[part] = changed.toByteArray(Charsets.UTF_8)
    return repack(entries)
}

private fun repack(entries: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        entries.forEach { (name, data) ->
            z.putNextEntry(ZipEntry(name))
            z.write(data)
            z.closeEntry()
        }
    }
    return out.toByteArray()
}

/** 把生产 Writer 的真产物导出到 app/build/baseline-artifacts，供外部 check_office.py 复核。 */
internal fun exportForCheck(src: File, name: String): File {
    val dir = File(System.getProperty("user.dir"), "build/baseline-artifacts")
    dir.mkdirs()
    val dst = File(dir, name)
    src.copyTo(dst, overwrite = true)
    return dst
}
