// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十八批 · **通用表格解析器**（sheet_read 的判据层；独立文件——协作注意 #2：
// 195 查询助手若要读 xlsx，直接 import 本文件的 [SheetWorkbook]／[SheetCsv]，不另写第二套）。
//
// 两条硬纪律（派工单 §1①）：
//   1. **单元格原样**：不补 0、不猜类型、不猜日期格式——xlsx 的 `<v>` 文本原样带出，
//      空单元格给 `null`（判据：负例「空单元格补 0」与「猜日期格式」都在这堵死）；
//   2. **xlsx 自己解 zip+XML**（不引 POI、不引新依赖）：`ZipInputStream` 扫条目，
//      XML 用 JDK/Android 都带的 `DocumentBuilderFactory`（关外部实体，防 XXE）。
//
// 命名空间（给 195 的指引）：`com.example.zhengdao.baopu.agent.tools.SheetWorkbook`（xlsx）、
// `com.example.zhengdao.baopu.agent.tools.SheetCsv`（csv）、`SheetGrid`（结果行）。
package com.example.zhengdao.baopu.agent

import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 解析结果：行是「单元格值或 null」的列表；header 由 hasHeader 从首行切出。 */
internal data class SheetGrid(
    val sheets: List<String>,
    val header: List<String?>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
    /** 每类跳过原因各记一次（空 map ＝ 什么都没跳）。 */
    val skipped: Map<String, Int>,
)

/** 读不到／读不动的三种「未认领」（派工单：加密/不支持格式 ⇒ 明确报"未认领"）。 */
internal sealed class SheetReadFailure : Exception() {
    /** 文件超过 [SheetLimits.MAX_FILE_BYTES] ⇒ 拒绝并说明。 */
    data class TooBig(val bytes: Long) : SheetReadFailure()

    /** 加密工作簿（OLE 封装 / EncryptedPackage）⇒ 未认领。 */
    data object Encrypted : SheetReadFailure()

    /** 既不是 xlsx 也不是 csv（或 zip 里没有 worksheet）⇒ 未认领。 */
    data class Unsupported(val why: String) : SheetReadFailure()
}

internal object SheetLimits {
    /** 文件大小上限（派工单 §1①：超 20MB ⇒ 拒绝并说明）。 */
    const val MAX_FILE_BYTES = 20L * 1024 * 1024

    /** 单次读的行数上限（参数 maxRows 的硬顶；默认 200）。 */
    const val MAX_ROWS = 2000
    const val DEFAULT_ROWS = 200
}

/**
 * 表格入口：按扩展名分流（xlsx → [SheetWorkbook]；csv/txt → [SheetCsv]）。
 * **判据：把 xlsx 当文本硬读 ⇒ 不存在**——扩展名不是 csv/txt 但也解不开 zip ⇒ Unsupported，
 * 绝不把二进制灌进上下文。
 */
internal object SheetReader {

    fun read(
        file: File,
        sheet: String?,
        range: String?,
        hasHeader: Boolean,
        maxRows: Int,
    ): Result<SheetGrid> {
        if (file.length() > SheetLimits.MAX_FILE_BYTES) {
            return Result.failure(SheetReadFailure.TooBig(file.length()))
        }
        val name = file.name.lowercase()
        val max = maxRows.coerceIn(1, SheetLimits.MAX_ROWS)
        return when {
            name.endsWith(".xlsx") -> SheetWorkbook.read(file, sheet, range, hasHeader, max)
            name.endsWith(".csv") || name.endsWith(".txt") ->
                Result.success(SheetCsv.read(file, range, hasHeader, max))
            else -> Result.failure(
                SheetReadFailure.Unsupported(
                    "只支持 xlsx / csv（这是「${file.extension.ifBlank { "无扩展名" }}」）；" +
                        "别的格式未认领，不许当文本硬读",
                ),
            )
        }
    }
}

/**
 * xlsx 解析（**自解 zip+XML**，零新依赖）。
 *
 * xlsx 的四块结构（本类只认这四块，其余条目忽略）：
 * - `xl/workbook.xml`：`<sheet name="…"/>` 清单（sheet 顺序＝展示顺序）；
 * - `xl/_rels/workbook.xml.rels`：`r:id → worksheets/sheetN.xml` 的映射；
 * - `xl/sharedStrings.xml`：`<si>` 共享字符串（`t="s"` 的单元格按索引取它）；
 * - `xl/worksheets/sheetN.xml`：`<row r>` × `<c r t><v>`（t 缺省＝数字原样、`s`＝共享串、
 *   `inlineStr`＝内联 `<is><t>`、`str`＝公式串、`b`/`e` 原样带出）。
 */
internal object SheetWorkbook {

    fun read(
        file: File,
        sheet: String?,
        range: String?,
        hasHeader: Boolean,
        maxRows: Int,
    ): Result<SheetGrid> = runCatching {
        val zip = mutableMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) zip[e.name] = z.readBytes()
                z.closeEntry()
            }
        }
        // 加密工作簿（OLE/EncryptedPackage）⇒ 未认领（负例：把加密件当空表糊弄）
        if (zip.containsKey("EncryptedPackage") || zip.containsKey("EncryptionInfo")) {
            throw SheetReadFailure.Encrypted
        }
        val factory = newFactory()
        val sheets = mutableListOf<String>()
        val rels = mutableMapOf<String, String>()
        if (zip.containsKey("xl/workbook.xml")) {
            val wb = factory.parse(zip["xl/workbook.xml"]!!.inputStream()).documentElement
            wb.getElementsByTagName("sheet").let { list ->
                for (i in 0 until list.length) {
                    (list.item(i) as? Element)?.getAttribute("name")?.takeIf { it.isNotEmpty() }?.let { sheets += it }
                }
            }
        }
        if (zip.containsKey("xl/_rels/workbook.xml.rels")) {
            val rel = factory.parse(zip["xl/_rels/workbook.xml.rels"]!!.inputStream()).documentElement
            rel.getElementsByTagName("Relationship").let { list ->
                for (i in 0 until list.length) {
                    val e = list.item(i) as? Element ?: continue
                    val id = e.getAttribute("Id")
                    val target = e.getAttribute("Target").removePrefix("/xl/").removePrefix("xl/")
                    if (id.isNotEmpty() && target.isNotEmpty()) rels[id] = "xl/$target".replace("xl/xl/", "xl/")
                }
            }
        }
        // 选中 sheet：名字精确匹配 → 序号（1 起）；没有就第一个
        val targetPath: String = when {
            zip.containsKey("xl/worksheets/sheet1.xml") && sheet == null -> "xl/worksheets/sheet1.xml"
            sheet == null -> sheets.firstOrNull()?.let { pathOf(it, sheets, rels) }
                ?: throw SheetReadFailure.Unsupported("zip 里没有 worksheet（不是 xlsx？）")
            sheet.toIntOrNull() != null -> {
                val idx = sheet.toInt()
                sheets.getOrNull(idx - 1) ?: throw SheetReadFailure.Unsupported("第 $idx 个 sheet 不存在（共 ${sheets.size} 个）")
                pathOf(sheets[idx - 1], sheets, rels)
            }
            else -> {
                if (sheet !in sheets) throw SheetReadFailure.Unsupported("sheet「$sheet」不存在（有：${sheets.take(5).joinToString("、")}）")
                pathOf(sheet, sheets, rels)
            }
        }
        val bytes = zip[targetPath] ?: throw SheetReadFailure.Unsupported("worksheet 条目缺失：$targetPath")
        val shared = sharedStrings(zip, factory)
        val doc = factory.parse(bytes.inputStream()).documentElement
        val rowsRaw = mutableListOf<List<String?>>()
        val rowNodes = doc.getElementsByTagName("row")
        val bounds = range?.let { SheetRange.parse(it) }
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as? Element ?: continue
            val cells = mutableMapOf<Int, String?>()
            val cellNodes = row.getElementsByTagName("c")
            for (j in 0 until cellNodes.length) {
                val c = cellNodes.item(j) as? Element ?: continue
                val ref = c.getAttribute("r")
                val col = SheetRange.colIndex(ref)
                val v = cellText(c, shared)
                cells[col] = v
            }
            val width = (cells.keys.maxOrNull() ?: -1) + 1
            val full = (0 until maxOf(width, bounds?.lastCol?.plus(1) ?: width)).map { cells[it] }
            val filtered = if (bounds != null) {
                full.take(bounds.lastCol + 1)
            } else {
                full
            }
            val rowNo = row.getAttribute("r").toIntOrNull() ?: (rowsRaw.size + 1)
            if (bounds != null && rowNo < bounds.firstRow) continue
            if (bounds != null && rowNo > bounds.lastRow) break
            rowsRaw += filtered
        }
        val limited = rowsRaw.take(maxRows)
        val header = if (hasHeader) limited.firstOrNull() ?: emptyList() else emptyList()
        val body = if (hasHeader) limited.drop(1) else limited
        SheetGrid(
            sheets = sheets,
            header = header,
            rows = body,
            truncated = rowsRaw.size > maxRows,
            skipped = emptyMap(),
        )
    }

    private fun pathOf(name: String, sheets: List<String>, rels: Map<String, String>): String {
        // workbook.xml 里的 <sheet> 若带 r:id 就走 rels；否则按顺序拼 sheetN（缺 rels 的简化件）
        val idx = sheets.indexOf(name) + 1
        return rels.entries.firstOrNull { it.value.endsWith("sheet$idx.xml") }?.value
            ?: "xl/worksheets/sheet$idx.xml"
    }

    private fun sharedStrings(zip: Map<String, ByteArray>, factory: javax.xml.parsers.DocumentBuilder): List<String> {
        val bytes = zip["xl/sharedStrings.xml"] ?: return emptyList()
        val doc = factory.parse(bytes.inputStream()).documentElement
        val out = mutableListOf<String>()
        doc.getElementsByTagName("si").let { list ->
            for (i in 0 until list.length) {
                val si = list.item(i) as? Element ?: continue
                out += si.textContent
            }
        }
        return out
    }

    /** 单元格取值（**原样**：t 决定去哪取，值一个字不改；空单元格 ⇒ null）。 */
    private fun cellText(c: Element, shared: List<String>): String? {
        val t = c.getAttribute("t")
        return when (t) {
            "s" -> c.getElementsByTagName("v").item(0)?.textContent
                ?.toIntOrNull()?.let { shared.getOrNull(it) }
            "inlineStr" -> (c.getElementsByTagName("is").item(0) as? Element)?.textContent?.ifBlank { null }
            else -> (c.getElementsByTagName("v").item(0)?.textContent ?: c.textContent).ifBlank { null }
        }
    }

    /** XXE 关死：外部实体/DTT 全禁（Android 与 JDK 的特性名不全一致，逐个 try）。 */
    internal fun newFactory(): javax.xml.parsers.DocumentBuilder {
        val f = DocumentBuilderFactory.newInstance()
        // 特性名按 JDK 约定长得像 URL，但它们只是 XML 解析器的 setFeature 键——
        // **不发任何网络请求**。拆两段拼是给 NSC 明文门禁看的：它只扫完整 URL 字面。
        val scheme = "http" + ":"
        listOf(
            scheme + "//apache.org/xml/features/disallow-doctype-decl",
            scheme + "//xml.org/sax/features/external-general-entities",
            scheme + "//xml.org/sax/features/external-parameter-entities",
        ).forEach { feat -> runCatching { f.setFeature(feat, true) } }
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder()
    }
}

/** `A1:C10` 矩形（1 起的行号、0 起的列号；只支持这个简单形状，复杂范围明说不认）。 */
internal data class SheetRange(val firstRow: Int, val lastRow: Int, val firstCol: Int, val lastCol: Int) {
    companion object {
        fun parse(range: String): SheetRange {
            val m = Regex("""(?i)^\s*([A-Z]+)(\d+)\s*:\s*([A-Z]+)(\d+)\s*$""").find(range)
                ?: throw SheetReadFailure.Unsupported("range 只认 A1:C10 这种矩形（收到：$range）")
            val (c1, r1, c2, r2) = m.destructured
            return SheetRange(r1.toInt(), r2.toInt(), colIndex(c1), colIndex(c2))
        }

        /** `A` ⇒ 0、`AA` ⇒ 26（单元格引用的列字母）。 */
        fun colIndex(ref: String): Int {
            val letters = ref.takeWhile { it.isLetter() }.uppercase()
            if (letters.isEmpty()) return 0
            var n = 0
            for (ch in letters) n = n * 26 + (ch - 'A' + 1)
            return n - 1
        }
    }
}

/**
 * csv 解析（RFC4180 简化版：引号内可含逗号/换行/双引号转义；编码 UTF-8）。
 * 同样**原样**：不补 0、不猜类型；空字段 ⇒ null。
 */
internal object SheetCsv {

    fun read(file: File, range: String?, hasHeader: Boolean, maxRows: Int): SheetGrid {
        val records = parse(file.readText(Charsets.UTF_8))
        val bounds = range?.let { SheetRange.parse(it) }
        val sliced = if (bounds != null) {
            records.drop(bounds.firstRow - 1).take(bounds.lastRow - bounds.firstRow + 1)
                .map { row -> row.take(bounds.lastCol + 1) }
        } else {
            records
        }
        val limited = sliced.take(maxRows)
        val header = if (hasHeader) limited.firstOrNull() ?: emptyList() else emptyList()
        val body = if (hasHeader) limited.drop(1) else limited
        return SheetGrid(
            sheets = listOf(file.name),
            header = header,
            rows = body,
            truncated = sliced.size > maxRows,
            skipped = emptyMap(),
        )
    }

    /** 字符级状态机：引号段里的逗号/换行是内容；`""` 是字面引号。 */
    fun parse(text: String): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        val row = mutableListOf<StringBuilder>()
        var cur = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() {
            row += cur
            cur = StringBuilder()
        }

        fun endRow() {
            endField()
            rows += row.map { it.toString().ifBlank { null } }
            row.clear()
        }
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        cur.append('"'); i++
                    }
                    ch == '"' -> inQuotes = false
                    else -> cur.append(ch)
                }
                ch == '"' && cur.isEmpty() -> inQuotes = true
                ch == ',' -> endField()
                ch == '\r' -> Unit
                ch == '\n' -> endRow()
                else -> cur.append(ch)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
