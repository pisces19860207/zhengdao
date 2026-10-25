// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 可参考的公开资料：ECMA-376（Spreadsheet ML，.xlsx 的包结构）、ZIP（deflate，.xlsx 就是个 zip）。
// 实现只用 JDK 自带的 java.util.zip 与字符串扫描，**不引 POI**（派工单 §1 点名十几 MB 的依赖不要）。
//
// 第一百九十五批 · `.xlsx` 读法（只读第一张表的单元格文本）。
//
// ## 只读四样东西
// `xl/workbook.xml`（第几张表、表名、有没有多 sheet）
// ＋ `xl/_rels/workbook.xml.rels`（表名 → 包内路径）
// ＋ `xl/sharedStrings.xml` ＋ 那一张 sheet 的 XML。
// styles、media、docProps 一律不解压——理由照 `terminal/DocxTextExtractor.kt:74` 那句
// 「zip 里还有别的东西，解它们纯属浪费」，这里浪费的还是一台手机。
//
// ## 三件刻意不做的事
// 1. **不计算公式**：`<f>` 只用来判「这一格是公式」；有缓存值 `<v>` 就取缓存值，
//    没有缓存值就当**未填**并计数报出去。算公式＝自带一个电子表格引擎，那是猜的另一种形态。
// 2. **不换算日期序列号**：xlsx 的日期是一个浮点序列号，格式藏在 style 里。读成「2026-10-25」
//    要引 style 表＋历法换算，错一位就是把数据时间报错——派工单 §1 要的「导出时间」走
//    文件名/内容时间戳/mtime 那条路（见 [LogisticsExportedAt]），不在这儿猜。
//    单元格里的日期若本来就是文本（T9 导出常见），照文本收。
// 3. **不多 sheet 合并**：只读第一张，其余表名进注记（负例⑥钉的就是这句话有没有出现）。
package com.example.zhengdao.baopu.logistics

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * xlsx → [Grid]。
 *
 * 全部是**纯函数**（进 [ByteArray]、出 [ReadOutcome]）：不碰 Context、不碰文件、不碰网络
 * ⇒ 七负例里那条「多 sheet 必须提示」能在 JVM 里直接打。
 */
object LogisticsXlsx {

    /** 单个 XML 条目解压上限（防 zip 炸弹；200 行配货单远不到）。 */
    private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024

    /** sharedStrings 上限：超了说明这张表全是长文本，不像是当天单子。 */
    private const val MAX_SHARED_BYTES = 16L * 1024 * 1024

    /** 整个 xlsx 文件大小上限（超过直接不试，不白白解压）。 */
    const val MAX_XLSX_BYTES = 30L * 1024 * 1024

    fun canHandle(name: String): Boolean = name.lowercase().endsWith(".xlsx")

    /** workbook 里声明的一张表：页签名 ＋ 它在 rels 里的引用号。 */
    data class SheetRef(val name: String, val relId: String)

    private class Parts {
        var workbookXml: String? = null
        var relsXml: String? = null
        var sharedXml: String? = null
        val worksheetEntries = ArrayList<String>()
    }

    fun parseBytes(bytes: ByteArray): ReadOutcome {
        if (bytes.isEmpty()) return ReadOutcome.Failed("这个 xlsx 是空的（0 字节）。")
        if (bytes.size > MAX_XLSX_BYTES) {
            return ReadOutcome.Failed("这个 xlsx 有 ${bytes.size / 1024 / 1024} MB，不像当天的配货单，先确认拿的是哪个文件。")
        }
        // 先看包标记，再看内容：`ZipInputStream` 对一串不是 zip 的字节**不抛异常**，它直接读到
        // 「没有条目」为止，于是错误会变成那句半对的「没有 xl/workbook.xml」。
        // 改了后缀名的 CSV / 旧版 .xls 是最常见的拿错文件，要给的是能照着做的那句话。
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return ReadOutcome.Failed(
                "这个 .xlsx 不是 xlsx 包（开头没有包标记），多半是把 CSV 改了个后缀或者是旧版 .xls。" +
                    "请在 Excel 里另存为 .xlsx，或直接「另存为 CSV」再放进 inbox。",
            )
        }
        val parts = Parts()
        val pass1 = runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "xl/workbook.xml" -> parts.workbookXml = readLimited(zin, MAX_ENTRY_BYTES)
                        "xl/_rels/workbook.xml.rels" -> parts.relsXml = readLimited(zin, MAX_ENTRY_BYTES)
                        "xl/sharedStrings.xml" -> parts.sharedXml = readLimited(zin, MAX_SHARED_BYTES)
                        else -> if (entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml")) {
                            parts.worksheetEntries += entry.name
                        }
                    }
                    entry = zin.nextEntry
                }
            }
        }
        if (pass1.isFailure) {
            return ReadOutcome.Failed(
                "这个文件打不开（不是合法的 xlsx 包）：${pass1.exceptionOrNull()?.javaClass?.simpleName}。" +
                    "请用 Excel「另存为 CSV」导一份。",
            )
        }
        val workbook = parts.workbookXml
            ?: return ReadOutcome.Failed("这个 xlsx 里没有 xl/workbook.xml，认不出哪张表是第一张，整份不读。")
        val sheets = sheetRefs(workbook)
        if (sheets.isEmpty()) return ReadOutcome.Failed("这个 xlsx 的 workbook 里没有列任何表。")
        val target = firstSheetTarget(sheets, parts.relsXml, parts.worksheetEntries)
            ?: return ReadOutcome.Failed(
                "认不出第一张表「${sheets.first().name}」的数据在包里的哪个位置（workbook 与 rels 对不上），" +
                    "整份不读，避免读到错的那一张。",
            )
        var sheetXml: String? = null
        var oversize = false
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name == target) {
                        val text = readLimited(zin, MAX_ENTRY_BYTES)
                        if (text == null) oversize = true else sheetXml = text
                        break
                    }
                    entry = zin.nextEntry
                }
            }
        }
        if (oversize) {
            return ReadOutcome.Failed("第一张表解压后超过 ${MAX_ENTRY_BYTES / 1024 / 1024} MB 上限，先按天拆开再导。")
        }
        val xml = sheetXml
            ?: return ReadOutcome.Failed("第一张表的数据（$target）在包里找不到，整份不读。")
        val shared = parts.sharedXml?.let { sharedStrings(it) }.orEmpty()
        return readSheet(xml, shared, sheets)
    }

    /**
     * `xl/workbook.xml` 里 `<sheets>` 的**声明顺序**＝用户看到的「第一张」。
     *
     * 不是取 `sheet1.xml` 这个文件名：OOXML 不约束文件名与页签顺序的关系，
     * 按文件名取会读到错的那一张（把第二张表当第一张，比拒读坏得多）。
     */
    internal fun sheetRefs(workbookXml: String): List<SheetRef> {
        val out = ArrayList<SheetRef>()
        forEachTag(workbookXml) { tag ->
            if (tagName(tag) == "sheet") {
                // `<sheets>` 的开标签也叫 sheets（复数），tagName 精确相等比过才不会混进来
                out += SheetRef(attr(tag, "name") ?: "", attrNs(tag, "id") ?: attr(tag, "sheetId") ?: "")
            }
        }
        return out
    }

    /** rels 把 `rId2` 映射到 `worksheets/sheet1.xml`；对不上时只在**唯一一张** worksheet 时兜底。 */
    private fun firstSheetTarget(sheets: List<SheetRef>, relsXml: String?, present: List<String>): String? {
        val first = sheets.first()
        val rels = relsXml
        if (rels != null && first.relId.isNotEmpty()) {
            var hit: String? = null
            forEachTag(rels) { tag ->
                // rels 里的 Id 是**不带命名空间前缀**的（`Id="rId1"`）：先按原样取，
                // 再兜 `:Id` 那种带前缀的写法。只写 attrNs 会一条都对不上 ⇒ 所有 rels 都走兜底，
                // 两张表的簿子就直接报「认不出第一张表」（2026-10-25 写 xlsx 测试时抓到的）。
                if (tagName(tag) == "Relationship" && (attr(tag, "Id") ?: attrNs(tag, "Id")) == first.relId) {
                    hit = attr(tag, "Target")
                }
            }
            hit?.let { return normalizeTarget(it) }
        }
        // 兜底只有一种情形：包里就一张 worksheet，没有可选错的空间。两张以上不兜＝不猜。
        return present.singleOrNull()
    }

    /** `worksheets/sheet1.xml` / `/xl/worksheets/sheet1.xml` → 包内路径。 */
    private fun normalizeTarget(target: String): String {
        val t = target.trim().removePrefix("/")
        return if (t.startsWith("xl/")) t else "xl/$t"
    }

    /**
     * sharedStrings：一个 `<si>` 里所有 `<t>` 拼起来（富文本一段段放在 `<r>` 里）。
     *
     * ⚠️ 按**出现顺序**建索引：`<c t="s"><v>7</v></c>` 里的 7 就是这里的下标。
     * 少收一个 `<si>` 会让后面整列文字前移一位——字都对、全在错的行上，是最安静的一种错。
     */
    internal fun sharedStrings(xml: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inSi = false
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            val name = tagName(tag)
            val closing = tag.startsWith("/")
            when {
                // `</si>`：tagName 已经把开头的 `/` 剥掉了，所以收尾只能看 closing 这个标志。
                // （早先这里写的是 `name == "/si"`——它永远不成立，于是一整条共享串表只留下
                // **最后一个**字符串，所有下标全落空：文字静默变空白。2026-10-25 xlsx 测试抓到。）
                name == "si" && closing -> { out += sb.toString(); sb.setLength(0); inSi = false }
                name == "si" -> if (tag.endsWith("/")) out += "" else { inSi = true; sb.setLength(0) }
                inSi && name == "t" && !closing -> {
                    // `<t/>` 是「这条共享串就是空文本」，不是「没有这一段」——
                    // 把它当成标签跳过的话，后面的 </t> 会被下一个 <t> 认走，
                    // 整条共享串表前移一位：字都对，全落在错的格上。
                    val closer = nextCloser(xml, gt, "t")
                    if (closer >= 0) {
                        sb.append(unescapeOf(xml.substring(gt + 1, closer)))
                        i = closer + "</t>".length
                        continue
                    }
                }
            }
            i = gt + 1
        }
        if (inSi) out += sb.toString()
        return out
    }

    /**
     * 从 [gt] 往后找 `</name>`，**只有它紧跟的下一个标签就是这个闭合标签**才返回位置。
     *
     * 中间夹着别的标签（结构不认识）时返回 -1：调用方放弃这段文本，
     * 也要保住「一个标签 = 一格 / 一条共享串」的对齐——少一行字能报，错位没人报。
     */
    private fun nextCloser(xml: String, gt: Int, name: String): Int {
        val close = xml.indexOf("</$name>", gt)
        if (close < 0) return -1
        val nextLt = xml.indexOf('<', gt + 1)
        return if (nextLt == close) close else -1
    }

    /** sheet 的 `<sheetData>` → 方格（第一行表头，其余数据）。 */
    private class SheetCells {
        val rows = LinkedHashMap<Int, MutableMap<Int, String>>()
        var formulaWithoutValue = 0
        var unresolvedShared = 0
    }

    private fun readSheet(xml: String, shared: List<String>, sheets: List<SheetRef>): ReadOutcome {
        val cells = SheetCells()
        var rowIndex = 0
        var lastCol = -1
        var inCell = false
        var cellType = ""
        var cellCol = 0
        var cellHasValue = false
        var cellHasFormula = false

        /** 一格收尾：公式只在**没有缓存值**时才算「读不出」（`<f>` 先出现、`<v>` 后出现，不能当场判）。 */
        fun settleCell() {
            if (cellHasFormula && !cellHasValue) cells.formulaWithoutValue++
            inCell = false
            cellHasValue = false
            cellHasFormula = false
        }

        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            val gt = xml.indexOf('>', lt)
            if (gt < 0) break
            val tag = xml.substring(lt + 1, gt)
            val name = tagName(tag)
            val closing = tag.startsWith("/")
            val selfClosing = tag.endsWith("/")
            when {
                name == "row" && !closing -> {
                    rowIndex = attr(tag, "r")?.toIntOrNull() ?: (rowIndex + 1).coerceAtLeast(1)
                    lastCol = -1
                    cells.rows.getOrPut(rowIndex) { LinkedHashMap() }
                }
                name == "row" && closing -> if (inCell) settleCell()
                name == "c" && !closing -> {
                    settleCell()
                    inCell = true
                    cellType = attr(tag, "t") ?: ""
                    cellCol = columnIndexOf(attr(tag, "r"), lastCol)
                    lastCol = cellCol
                    if (selfClosing) settleCell() // `<c r="A1"/>`：空格，不写值（空 ≠ 0）
                }
                inCell && !closing && !selfClosing && (name == "v" || name == "t") -> {
                    val closer = nextCloser(xml, gt, name)
                    if (closer >= 0) {
                        val text = xml.substring(gt + 1, closer)
                        val raw = unescapeOf(text)
                        val fromInline = name == "t" && cellType == "inlineStr"
                        if (name == "v" || fromInline) {
                            if (!setValue(cells, shared, cellType, cellCol, rowIndex, raw)) {
                                cells.unresolvedShared++
                            }
                            cellHasValue = true
                        }
                        i = closer + "</$name>".length
                        continue
                    }
                }
                inCell && !closing && name == "f" -> cellHasFormula = true
                name == "c" && closing -> settleCell()
            }
            i = gt + 1
        }
        val nonEmpty = cells.rows.entries
            .filter { (_, cols) -> cols.values.any { it.isNotBlank() } }
            .sortedBy { it.key }
        if (nonEmpty.isEmpty()) return ReadOutcome.Failed("这张 xlsx 的第一张表里没有可读的单元格。")
        val widest = nonEmpty.maxOf { it.value.keys.maxOrNull() ?: 0 } + 1
        if (widest > LogisticsGrid.MAX_COLS) {
            return ReadOutcome.Failed("这张表有 $widest 列，超过 ${LogisticsGrid.MAX_COLS} 列上限，不是当天配货单的形状。")
        }
        fun line(cols: Map<Int, String>): List<String> = (0 until widest).map { LogisticsGrid.cell(cols[it] ?: "") }
        val header = line(nonEmpty.first().value)
        if (header.none { it.isNotEmpty() }) return ReadOutcome.Failed("这张 xlsx 的第一行没有列名，认不出表头。")
        val body = nonEmpty.drop(1)
        if (body.isEmpty()) return ReadOutcome.Failed("这张 xlsx 只有表头、没有数据行——当天这张单子是空的？")
        if (body.size > LogisticsGrid.MAX_ROWS) {
            return ReadOutcome.Failed("这张表有 ${body.size} 行，超过单表 ${LogisticsGrid.MAX_ROWS} 行上限，先按天拆开再导。")
        }
        val bodyLines = body.map { it.key }
        val (aligned, notes) = LogisticsGrid.align(header, body.map { line(it.value) }, bodyLines)
        val all = ArrayList(notes)
        if (sheets.size > 1) {
            all += "这个工作簿有 ${sheets.size} 张表，只读了第一张「${sheets.first().name}」；" +
                "另外几张：${sheets.drop(1).joinToString("、") { it.name }}——要它们的内容请各自另存成单独的表再导。"
        }
        if (cells.formulaWithoutValue > 0) {
            all += "有 ${cells.formulaWithoutValue} 格是公式且没有缓存值，本批不计算公式，这些格按「未填」记、不参与任何计算。"
        }
        if (cells.unresolvedShared > 0) {
            all += "有 ${cells.unresolvedShared} 格指向共享文字表里不存在的下标（文件可能被截断），这些格按「未填」记。"
        }
        return ReadOutcome.Ok(Grid(header, aligned, all, bodyLines))
    }

    /**
     * 一格的值落库（`t="s"` 走共享串表，其余取原文）。
     *
     * 返回 `false` ＝ 声明了共享串下标却查不到（下标越界／不是数字）。这时写空串，
     * 但**必须让调用方计数并报出去**：静默把一格变成空白，正是「把空当 0」的源头。
     */
    private fun setValue(
        cells: SheetCells,
        shared: List<String>,
        type: String,
        col: Int,
        row: Int,
        raw: String,
    ): Boolean {
        var resolved = true
        val value = when (type) {
            "s" -> {
                val idx = raw.trim().toIntOrNull()
                if (idx != null && idx in shared.indices) shared[idx] else {
                    resolved = false
                    ""
                }
            }
            "inlineStr", "str", "n", "b", "" -> raw
            // e（错误值 #N/A/#VALUE!）与其它未知类型：原样收进 raw，字段解析层会当成读不出
            else -> raw
        }
        cells.rows.getOrPut(row) { LinkedHashMap() }[col] = value
        return resolved
    }

    /**
     * `A1 → 0`、`B1 → 1`、`AA1 → 26`；没有 `r` 属性时按「上一列 +1」补。
     *
     * 漏 `r` 在实际导出里不出现（Excel 一律写），WPS 与部分 SaaS 的导出会漏。
     * 顺序补不是猜：XML 里 `<c>` 的书写顺序就是列顺序，跳格必带 `r`。
     */
    internal fun columnIndexOf(ref: String?, lastCol: Int): Int {
        if (ref.isNullOrEmpty()) return lastCol + 1
        var acc = 0
        var seen = false
        for (c in ref) {
            val upper = c.uppercaseChar()
            if (upper in 'A'..'Z') {
                acc = acc * 26 + (upper - 'A' + 1)
                seen = true
            } else {
                break
            }
        }
        return if (seen) acc - 1 else lastCol + 1
    }

    // ── XML 扫描的小刀（与 DocxTextExtractor 同一族，独立实现避免跨包耦合）──────

    /** 逐个标签走一遍（只给标签原文；要吞内容的地方自己写循环）。 */
    private inline fun forEachTag(xml: String, onTag: (tag: String) -> Unit) {
        var rest = xml
        while (true) {
            val lt = rest.indexOf('<')
            if (lt < 0) return
            val gt = rest.indexOf('>', lt)
            if (gt < 0) return
            onTag(rest.substring(lt + 1, gt))
            rest = rest.substring(gt + 1)
        }
    }

    /**
     * 取标签名（去 `/` 前缀、去属性、去自闭合斜杠）。
     *
     * ⚠️ 必须**精确相等**地比：`<t>` 与 `<tab>`、`<c>` 与 `<cellPolicy>` 同前缀，
     * 用 startsWith 会把结构标签当内容吃（这条坑记在 `DocxTextExtractor.kt:151`）。
     */
    private fun tagName(tag: String): String {
        val t = if (tag.startsWith("/")) tag.substring(1) else tag
        var end = t.length
        for (k in t.indices) {
            val c = t[k]
            if (c.isWhitespace() || c == '/' || c == '!') { end = k; break }
        }
        return t.substring(0, end)
    }

    /** 属性值（`name="value"`），带命名空间前缀的（`r:id`）走 [attrNs]。 */
    private fun attr(tag: String, name: String): String? = attrAt(tag, name, ns = false)

    /** 按 `:名字` 尾段匹配（`r:id`、`x:id` 都算命中）。 */
    private fun attrNs(tag: String, name: String): String? = attrAt(tag, name, ns = true)

    private fun attrAt(tag: String, name: String, ns: Boolean): String? {
        var i = 0
        while (i < tag.length) {
            val c = tag[i]
            if (c != '"' && c != '\'') {
                i++
                continue
            }
            val close = tag.indexOf(c, i + 1)
            if (close < 0) return null
            keyOf(tag, i)?.let { key ->
                if (ns && key.endsWith(":$name")) return unescapeOf(tag.substring(i + 1, close))
                if (!ns && key == name) return unescapeOf(tag.substring(i + 1, close))
            }
            i = close + 1
        }
        return null
    }

    /** 引号左边那段属性名（允许 `=` 两侧有空格）。 */
    private fun keyOf(tag: String, quoteAt: Int): String? {
        var k = quoteAt - 1
        while (k >= 0 && tag[k].isWhitespace()) k--
        if (k < 0 || tag[k] != '=') return null
        k--
        while (k >= 0 && tag[k].isWhitespace()) k--
        val end = k
        while (k >= 0) {
            val c = tag[k]
            if (!(c.isLetterOrDigit() || c == '_' || c == ':' || c == '-')) break
            k--
        }
        if (end <= k) return null
        return tag.substring(k + 1, end + 1)
    }

    private fun unescapeOf(s: String): String = LogisticsGrid.unescapeXml(s)

    private fun readLimited(zin: ZipInputStream, limit: Long): String? {
        val buf = ByteArray(8192)
        val out = StringBuilder()
        var total = 0L
        while (true) {
            val n = zin.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) return null
            out.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return out.toString()
    }
}
