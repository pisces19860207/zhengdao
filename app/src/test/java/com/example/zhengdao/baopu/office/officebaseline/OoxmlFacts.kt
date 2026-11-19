// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百四十五批 · 轻办公「往返保真」深测夹具的**结构化读回引擎**。
//
// 为什么不复用 DocxTextExtractor / SheetReader：前者把标题/编号/表格/加粗全丢成纯文字，
// 后者只回读单元格值、不读公式与格式——两者都是判据⑤禁止的「静默降级」。
// 本文件用 JDK 自带 java.util.zip + DOM 解包 OOXML，把版式事实读成可断言的数据类，
// 任何一项读不出来就是失败（不猜、不降级、不返回半个结果）。
package com.example.zhengdao.baopu.office.officebaseline

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

// ── docx 结构快照（判据①③）────────────────────────────────────────────────

/** 一份 docx 的版式事实。字段与派工单判据①一一对应。 */
internal data class DocxFacts(
    val bodyParagraphs: Int,          // w:body 直属 w:p 数（表格里的 p 不算正文段落）
    val headingLevels: List<Int>,      // 标题级别序列，如 [1,2,3]
    val usedNumIds: List<Int>,         // 正文段落用到的 numId（含无序 1、有序 2/3；去重保序）
    val listItemCount: Int,            // 带 numPr 的段落数（所有列表条目）
    val tableRows: List<Int>,          // 每张表的数据行数（不含表头）
    val tableCols: List<Int>,          // 每张表的列数（gridCol 数）
    val boldRuns: Int,                 // 正文里带 <w:b/> 的 run 数
    val styleIds: List<String>,        // 正文用到的段落样式 id
    val fontHalfPts: Set<String>,      // styles.xml docDefaults 的字号（半磅）
    val lineValue: String?,            // 正文默认行距 w:line
    val lineRule: String?,             // 行距规则 w:lineRule
    val margins: Map<String, String>,  // sectPr w:pgMar 四边（twips）
    val numberingIds: List<Int>,       // numbering.xml 里定义的 w:numId（判③编号连续）
)

// ── xlsx 结构快照（判据②）─────────────────────────────────────────────────

/** 一个单元格的读回事实：值 + 数字格式 + 公式（有则带缓存结果）。 */
internal data class CellFacts(
    val ref: String,
    val value: String?,            // 原始值（数字＝序列/数值文本，文本＝解出 sharedStrings 后）
    val numFmtCode: String?,       // 该格 formatCode（styles.xml 的 numFmt）
    val formula: String?,          // <f> 里的公式文本（无公式则 null）
    val cachedValue: String?,      // <v> 缓存结果（公式格＝上次算出的值）
    val bold: Boolean,             // 该格字体是否加粗
)

/** 一份 xlsx 的读回事实。 */
internal data class XlsxFacts(
    val sheetNames: List<String>,
    val cells: Map<String, Map<String, CellFacts>>, // sheet → 引用 → 事实
    val merges: Map<String, List<String>>,          // sheet → mergeCell ref 列表
    val frozenFirstRow: Map<String, Boolean>,       // sheet → 是否冻结首行
)

// ── OOXML 解包与 DOM 工具 ──────────────────────────────────────────────────

/** 解 zip：条目名 → 字节。目录跳过；重名以先到为准，与写出侧唯一路径一致。 */
internal fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
        while (true) {
            val e = z.nextEntry ?: break
            if (!e.isDirectory && e.name !in out) out[e.name] = z.readBytes()
            z.closeEntry()
        }
    }
    return out
}

/** XXE 关死（与 SheetRead.newFactory 同口径），只解析我们自己的包。 */
internal fun newXml(): DocumentBuilder {
    val f = DocumentBuilderFactory.newInstance()
    val scheme = "http" + ":"
    listOf(
        scheme + "//apache.org/xml/features/disallow-doctype-decl",
        scheme + "//xml.org/sax/features/external-general-entities",
        scheme + "//xml.org/sax/features/external-parameter-entities",
    ).forEach { feat -> runCatching { f.setFeature(feat, true) } }
    runCatching { f.isXIncludeAware = false }
    runCatching { f.isExpandEntityReferences = false }
    val db = f.newDocumentBuilder()
    db.setErrorHandler(object : org.xml.sax.ErrorHandler {
        override fun warning(e: org.xml.sax.SAXParseException?) = throw AssertionError("XML warning: ${e?.message}")
        override fun error(e: org.xml.sax.SAXParseException?) = throw AssertionError("XML error: ${e?.message}")
        override fun fatalError(e: org.xml.sax.SAXParseException?) = throw AssertionError("XML fatal: ${e?.message}")
    })
    return db
}

private fun bytesOf(entries: Map<String, ByteArray>, name: String): ByteArray =
    entries[name] ?: throw AssertionError("OOXML 包里缺关键部件 $name（有：${entries.keys.sorted()}）")

/** 直接子节点里名为 tag 的元素（不带命名空间前缀，DOM 非 namespace-aware）。 */
private fun children(parent: Element, tag: String): List<Element> {
    val out = ArrayList<Element>()
    val nodes = parent.childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName == tag) out += n
    }
    return out
}

private fun first(parent: Element, tag: String): Element? = children(parent, tag).firstOrNull()

private fun attr(el: Element?, name: String): String? = el?.getAttribute(name)?.takeIf { it.isNotEmpty() }

/** DOM 非 namespace-aware：OOXML 用 w:/r: 前缀字面量，getElementsByTagName 直接按前缀取。 */
private fun all(parent: Element, tag: String): List<Element> {
    val out = ArrayList<Element>()
    val list = parent.getElementsByTagName(tag)
    for (i in 0 until list.length) out += list.item(i) as Element
    return out
}

// ── docx 结构化读回 ────────────────────────────────────────────────────────

internal fun readDocxFacts(bytes: ByteArray): DocxFacts {
    val entries = unzip(bytes)
    val db = newXml()
    val doc = db.parse(ByteArrayInputStream(bytesOf(entries, "word/document.xml"))).documentElement
    val body = first(doc, "w:body") ?: throw AssertionError("document.xml 没有 w:body")

    // 正文段落只数 body 直属 w:p；表格单元格里的 w:p 归表格，不混进来
    val paragraphs = children(body, "w:p")
    val tables = children(body, "w:tbl")
    val headings = mutableListOf<Int>()
    val styleIds = LinkedHashSet<String>()
    val orderedIds = LinkedHashSet<Int>()
    var listItems = 0
    for (p in paragraphs) {
        val ppr = first(p, "w:pPr")
        val style = attr(first(ppr ?: p, "w:pStyle"), "w:val")
        if (style != null) {
            styleIds += style
            if (style.startsWith("Heading")) headings += style.removePrefix("Heading").toIntOrNull() ?: 0
        }
        val numPr = first(ppr ?: p, "w:numPr")
        if (numPr != null) {
            listItems++
            attr(first(numPr, "w:numId"), "w:val")?.toIntOrNull()?.let { orderedIds += it }
        }
    }
    // 有序列表的 numId 是 numbering.xml 里 abstractNumId=1（decimal）那几条，另算
    val gridCols = tables.map { all(it, "w:gridCol").size }
    val tableRows = tables.map { children(it, "w:tr").size - 1 } // 减表头行

    // styles.xml：docDefaults 的字号与行距
    val styles = db.parse(ByteArrayInputStream(bytesOf(entries, "word/styles.xml"))).documentElement
    val defaults = first(styles, "w:docDefaults") ?: throw AssertionError("styles.xml 没有 w:docDefaults")
    val fontHalfPts = all(defaults, "w:sz").mapNotNull { attr(it, "w:val") }.toSet()
    val pprDefault = first(first(defaults, "w:pPrDefault") ?: defaults, "w:pPr")
    val spacing = first(pprDefault ?: defaults, "w:spacing")

    // sectPr 页边距
    val sect = first(body, "w:sectPr") ?: throw AssertionError("document.xml 没有 w:sectPr")
    val pmar = first(sect, "w:pgMar") ?: throw AssertionError("sectPr 没有 w:pgMar")
    val margins = linkedMapOf<String, String>()
    for (side in listOf("top", "right", "bottom", "left")) {
        margins[side] = attr(pmar, "w:$side") ?: throw AssertionError("pgMar 缺 $side")
    }

    // numbering.xml：定义的 w:numId（判③编号连续 1..N）
    val numbering = db.parse(ByteArrayInputStream(bytesOf(entries, "word/numbering.xml"))).documentElement
    val numberingIds = all(numbering, "w:num").mapNotNull { attr(it, "w:numId")?.toIntOrNull() }.sorted()

    return DocxFacts(
        bodyParagraphs = paragraphs.size,
        headingLevels = headings,
        usedNumIds = orderedIds.sorted(),
        listItemCount = listItems,
        tableRows = tableRows,
        tableCols = gridCols,
        boldRuns = all(doc, "w:b").size,
        styleIds = styleIds.toList(),
        fontHalfPts = fontHalfPts,
        lineValue = attr(spacing, "w:line"),
        lineRule = attr(spacing, "w:lineRule"),
        margins = margins,
        numberingIds = numberingIds,
    )
}

// ── xlsx 结构化读回 ────────────────────────────────────────────────────────

internal fun readXlsxFacts(bytes: ByteArray): XlsxFacts {
    val entries = unzip(bytes)
    val db = newXml()

    // sharedStrings：<si><t>…</t></si>（一个 si 可能被拆成多个 t，拼接）
    val shared = mutableListOf<String>()
    if ("xl/sharedStrings.xml" in entries) {
        val sst = db.parse(ByteArrayInputStream(entries.getValue("xl/sharedStrings.xml"))).documentElement
        for (si in all(sst, "si")) shared += all(si, "t").joinToString("") { it.textContent }
    }

    // styles：numFmtId → formatCode；cellXfs[s] → (numFmtId, fontId)；fonts[f] → 是否加粗
    val styles = db.parse(ByteArrayInputStream(bytesOf(entries, "xl/styles.xml"))).documentElement
    val fmtCodes = HashMap<String, String>()
    for (nf in all(styles, "numFmt")) {
        val id = attr(nf, "numFmtId")
        val code = attr(nf, "formatCode")
        if (id != null && code != null) fmtCodes[id] = code
    }
    val cellXfs = first(styles, "cellXfs") ?: throw AssertionError("styles.xml 没有 cellXfs")
    val xfs = children(cellXfs, "xf")
    val fonts = first(styles, "fonts")?.let { children(it, "font") }.orEmpty()
    val effFmtOf = ArrayList<String>()   // 生效格式串（内置 0 → "General"）
    val boldOf = ArrayList<Boolean>()
    for (xf in xfs) {
        val numFmtId = attr(xf, "numFmtId") ?: "0"
        effFmtOf += if (numFmtId == "0") "General" else (fmtCodes[numFmtId] ?: "UNKNOWN:$numFmtId")
        val fontId = attr(xf, "fontId")?.toIntOrNull() ?: 0
        boldOf += fonts.getOrNull(fontId)?.let { it.getElementsByTagName("b").length > 0 } ?: false
    }

    // workbook：sheet 名（顺序＝展示顺序）
    val wb = db.parse(ByteArrayInputStream(bytesOf(entries, "xl/workbook.xml"))).documentElement
    val sheetNames = all(wb, "sheet").mapNotNull { attr(it, "name") }

    val cells = LinkedHashMap<String, Map<String, CellFacts>>()
    val merges = LinkedHashMap<String, List<String>>()
    val frozen = LinkedHashMap<String, Boolean>()
    sheetNames.forEachIndexed { idx, name ->
        val part = "xl/worksheets/sheet${idx + 1}.xml"
        val sheet = db.parse(ByteArrayInputStream(bytesOf(entries, part))).documentElement
        val one = LinkedHashMap<String, CellFacts>()
        for (c in all(sheet, "c")) {
            val ref = attr(c, "r") ?: continue
            val s = attr(c, "s")?.toIntOrNull() ?: 0
            val vNode = first(c, "v")
            val raw = vNode?.textContent
            val type = attr(c, "t")
            val value = when (type) {
                "s" -> raw?.toIntOrNull()?.let { shared.getOrNull(it) }
                "str", "inlineStr" -> raw
                else -> raw
            }
            one[ref] = CellFacts(
                ref = ref,
                value = value,
                numFmtCode = effFmtOf.getOrNull(s) ?: "UNKNOWN-STYLE:$s",
                formula = first(c, "f")?.textContent,
                cachedValue = raw,
                bold = boldOf.getOrNull(s) ?: false,
            )
        }
        cells[name] = one
        merges[name] = all(sheet, "mergeCell").mapNotNull { attr(it, "ref") }
        frozen[name] = all(sheet, "pane").any { attr(it, "state") == "frozen" && attr(it, "ySplit") == "1" }
    }
    return XlsxFacts(sheetNames, cells, merges, frozen)
}

/**
 * 判据⑤的失败兜底：只认 docx/xlsx 两种能结构化往返的格式。
 * 其余（.doc/.xls/.csv/.pdf…）一律抛，消息里点名「不支持往返」，
 * 绝不返回一份纯文本快照冒充成功。
 */
internal fun requireRoundTrippable(fileName: String) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext != "docx" && ext != "xlsx") {
        throw UnsupportedRoundTrip("格式 .$ext 不支持往返（只支持 .docx / .xlsx）；不做静默纯文本降级")
    }
}

internal class UnsupportedRoundTrip(message: String) : IllegalArgumentException(message)
