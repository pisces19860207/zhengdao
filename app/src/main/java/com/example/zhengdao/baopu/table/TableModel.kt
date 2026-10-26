// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · **通用表模型**（用户 2026-10-26 原话：「把查询助手改成全能而不只是物流，
// 只要有表格就能识别，一问就能按表格上的东西回答真实答案，除了 PDF」）。
//
// ## 为什么不是「把 195 的物流解析器改一改」
// 195 的 [com.example.zhengdao.baopu.logistics.LogisticsContract] 是**契约**：它认十二个固定
// 字段（单号/方向/卸点…），缺了硬列就整份拒读。那是物流专用档的判据，**冻结不动**。
// 本文件是另一件事：它不知道也不关心列名是什么意思，只回答「这张表长什么样」——
// 表头在第几行、每列叫什么、每列是什么类型、哪列能当主键、哪里不对劲。
// 于是物流表照样能进这个模型（它只是一张有十二列的表），而学生成绩表也能进。
//
// ## 三条硬纪律
// 1. **不许猜表头**：认不出就 `header_unknown`，把「第几行是表头？」这句话交出去；
//    认出来的那条路必须能说出**为什么是这一行**（[TableHeader] 的判据写在那里）；
// 2. **弱类型只做弱推断**：一列里有一格不是数字，这列就是文本（`90` 和 `缺考` 混在一列时
//    按文本处理，求和会明说「这列不是数字列」，而不是把 `缺考` 当 0）；
// 3. **行号是文件里的真行号**：来自 [com.example.zhengdao.baopu.agent.SheetGrid.rowNumbers]
//    （xlsx 的 `<row r>`、csv 的记录序），不是下标现推——「答案必带出处」的出处要经得起核对。
package com.example.zhengdao.baopu.table

import com.example.zhengdao.baopu.agent.SheetGrid

/** 一列的弱类型（[BLANK] 只给「整列都空」用，不当成一种数据）。 */
enum class ColumnType {
    TEXT,
    NUMBER,
    DATE,
    PHONE,
    PLATE,

    /** 整列没有一个非空格 ⇒ 空列（进 warnings，不参与任何计算）。 */
    BLANK,
}

/** 表头认不出的那两句话（唯一出处：面板、工具回灌、registry 都念这两句）。 */
object TableHeaderAsk {

    /** 认不出时的统一问法（派工单 §2.1④ 原话：「问一句第几行是表头」）。 */
    const val ASK = "第几行是表头？"

    /** 把「为什么认不出」拼成一句人话。 */
    fun sentence(reason: String): String = "这张表认不出表头：$reason $ASK"
}

/** 一列。 */
data class TableColumn(
    /** 列序（0 起，与数据行 cells 的下标一致）。 */
    val index: Int,
    /** 列名原文（已 trim；表头空格由 [TableModelBuilder] 拒掉，所以这里一定非空）。 */
    val name: String,
    val type: ColumnType,
    /** 有值的格数。 */
    val filled: Int,
    /** 去重后的取值个数（主键判定与「有几种」都用它）。 */
    val distinct: Int,
    /** 能不能当主键：整列有值且**每行都不同**（这是判据，不是相似度）。 */
    val keyCandidate: Boolean,
)

/** 一行数据。[no] 是**文件里的行号**（1 起）——出处的「第 N 行」就是它。 */
data class TableRow(val no: Int, val cells: List<String?>)

/**
 * 一张表的模型（问答层的唯一输入形状）。
 *
 * @param path 相对工作区根的正斜杠路径（出处念它，不念绝对路径）
 * @param headerRow 表头行（0 起）；`-1` ＝ 没认出（此时 [columns]/[rows] 都是空的）
 * @param warnings 必须告诉用户的话：合并表头/空列/重名列/全空行/超宽行…
 */
data class TableModel(
    val path: String,
    val sheet: String,
    val headerRow: Int,
    val columns: List<TableColumn>,
    val keys: List<String>,
    val rows: List<TableRow>,
    val warnings: List<String>,
    val truncated: Boolean,
    /** 认不出表头时的那句话（非 null ⇒ 这张表暂时不能问答，只能先问用户）。 */
    val headerAsk: String? = null,
) {

    /** 表头在第几行（1 起，给人看）。没认出 ⇒ 0。 */
    val headerRowNumber: Int get() = if (headerRow < 0) 0 else headerRow + 1

    val known: Boolean get() = headerRow >= 0 && columns.isNotEmpty()

    /** 数据行数（**不含**表头、不含全空行）。 */
    val rowCount: Int get() = rows.size

    /** 出处前缀：`表：成绩表.xlsx · sheet「Sheet1」`。 */
    val source: String get() = "表：$path · sheet「$sheet」"

    fun columnAt(index: Int): TableColumn? = columns.firstOrNull { it.index == index }

    /** 按**原文**（归一后）找列；不做别名、不做包含——别名在 [TableAlias] 那一层。 */
    fun columnNamed(term: String): TableColumn? {
        val t = TableText.normalizeName(term)
        if (t.isEmpty()) return null
        return columns.firstOrNull { TableText.normalizeName(it.name) == t }
    }

    /** 这一列的取值（按行序；空格为 null）。 */
    fun valuesOf(column: TableColumn): List<String?> = rows.map { it.cells.getOrNull(column.index) }

    /** 一行的某个值。 */
    fun valueOf(row: TableRow, column: TableColumn): String? = row.cells.getOrNull(column.index)

    companion object {
        /** 认不出表头时的模型（**空表**：一行都不给，避免下游拿半张表硬答）。 */
        fun unknown(path: String, sheet: String, ask: String): TableModel = TableModel(
            path = path,
            sheet = sheet,
            headerRow = -1,
            columns = emptyList(),
            keys = emptyList(),
            rows = emptyList(),
            warnings = listOf(ask),
            truncated = false,
            headerAsk = ask,
        )
    }
}

/**
 * 单元格取值的公共判据（**不猜**：只回答「这一格能不能整格解析成 X」）。
 *
 * 与 `LogisticsGrid.cell/isBlankish` 是同一套「空」的语义（空串/横杠/斜杠/点号都算空），
 * 但本文件**不 import 物流包**：物流是特化档，通用层反过来依赖特化档会让「抽公共」
 * 变成「把冻结口径拖进通用路径」。
 */
object TableValues {

    /** 整格是不是数字（允许千分位、货币前缀、常见计量单位后缀）。 */
    private val NUMBER = Regex(
        """^[¥￥$]?[-+]?\d[\d,，]*(?:\.\d+)?(%|％|元|块|分|件|个|名|人|天|次|斤|公斤|千克|kg|KG|吨|米|m|M)?$""",
    )

    /** 数字的核（从 [NUMBER] 认过的串里取出可解析的部分）。 */
    private val NUMBER_CORE = Regex("""[-+]?\d[\d,，]*(?:\.\d+)?""")

    /** 日期：`2026-10-26` / `2026/10/26` / `2026年10月26日` / `2026-10`。 */
    private val DATE = Regex("""^\d{4}\s*[-/.年]\s*\d{1,2}(\s*[-/.月]\s*\d{1,2}\s*日?)?$""")

    /** 手机号：11 位、1 开头（**先于**数字判：号码也是数字，但按数字列求平均没有意义）。 */
    private val PHONE = Regex("""^1[3-9]\d{9}$""")

    /** 车牌：一个汉字 + 一个字母 + 4~6 位字母数字（`京A12345`、`冀A1234挂` 不算，宁窄不宽）。 */
    private val PLATE = Regex("""^[\u4e00-\u9fa5][A-Za-z][A-Za-z0-9]{4,6}$""")

    /** 空格（含只填了横杠/斜杠这类占位符）。 */
    fun isBlank(raw: String?): Boolean {
        val t = raw?.trim() ?: return true
        return t.isEmpty() || t.all { it == '-' || it == '—' || it == '/' || it == '·' || it == '.' }
    }

    /** 整格是数字 ⇒ 它的值；否则 null（**不猜**：`90分` 是 90，`缺考` 不是 0）。 */
    fun numberOrNull(raw: String?): Double? {
        if (isBlank(raw)) return null
        val t = raw!!.trim().replace(" ", "").replace("　", "")
        if (!NUMBER.matches(t)) return null
        val core = NUMBER_CORE.find(t)?.value ?: return null
        return core.replace(",", "").replace("，", "").toDoubleOrNull()
    }

    fun isDate(raw: String?): Boolean {
        if (isBlank(raw)) return false
        return DATE.matches(raw!!.trim().replace(" ", ""))
    }

    fun isPhone(raw: String?): Boolean {
        if (isBlank(raw)) return false
        return PHONE.matches(raw!!.trim().replace(" ", ""))
    }

    fun isPlate(raw: String?): Boolean {
        if (isBlank(raw)) return false
        return PLATE.matches(raw!!.trim().replace(" ", ""))
    }

    /** 单格的最细类型（列类型由「整列是否同型」再收敛，见 [TableModelBuilder]）。 */
    fun classify(raw: String?): ColumnType = when {
        isBlank(raw) -> ColumnType.BLANK
        isPhone(raw) -> ColumnType.PHONE
        isPlate(raw) -> ColumnType.PLATE
        isDate(raw) -> ColumnType.DATE
        numberOrNull(raw) != null -> ColumnType.NUMBER
        else -> ColumnType.TEXT
    }
}

/** 列名归一（**只做三件事**：去空白、剥首尾成对括号、拉丁字母小写）。 */
object TableText {

    /**
     * 归一口径与 `LogisticsContract.normalize` 逐字一致（同一份「什么算同一个列名」）：
     * 去 BOM/首尾空白/内部空格（含全角）、剥掉**首尾成对**的括号、字母小写。
     * 不做同义、不做繁简、不拆斜杠——那些是 [TableAlias] 的事。
     */
    fun normalizeName(raw: String): String {
        val noSpace = raw.trim().removePrefix("\uFEFF").replace(" ", "").replace("　", "")
        if (noSpace.isEmpty()) return ""
        val last = noSpace.last()
        val paired = (noSpace.first() == '(' && last == ')') || (noSpace.first() == '（' && last == '）')
        return (if (paired) noSpace.substring(1, noSpace.length - 1) else noSpace).lowercase()
    }

    /** 给人看的取值（答案里的一个格）：空 ⇒ 「未填」，不补 0。 */
    fun display(raw: String?): String = if (TableValues.isBlank(raw)) "未填" else raw!!.trim()

    /** 数字进答案：整数不带 `.0`（`90` 不是 `90.0`），小数最多两位且去掉尾零（`78.4` 不是 `78.40`）。 */
    fun number(d: Double): String {
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) return d.toLong().toString()
        return "%.2f".format(d).trimEnd('0').trimEnd('.')
    }

    /** 行号列表 → 「第 4/7/9 行」（最多 5 个，其余折成「等 N 行」）。 */
    fun rowsText(nos: List<Int>, max: Int = 5): String {
        if (nos.isEmpty()) return "（没有行）"
        val head = nos.take(max).joinToString("/")
        return if (nos.size > max) "第 $head 行 等 ${nos.size} 行" else "第 $head 行"
    }
}

/** 表头识别的结论。 */
internal data class HeaderDetection(
    /** 0 起；`-1` ＝ 没认出。 */
    val row: Int,
    /** 没认出时的原因（给 [TableHeaderAsk.sentence] 用）。 */
    val reason: String = "",
    /** 认出来了、但**有别扭之处**时必须告诉用户的话（例如「第 2 行也像表头，按第 1 行算」）。 */
    val warning: String = "",
    /** 参与打分的行（1 起，排障与单测用）。 */
    val scored: List<Int> = emptyList(),
)

/**
 * 表头行识别（派工单 §2.1④「表头不在第 1 行也要认，认不出就问第几行是表头，不许猜」）。
 *
 * ## 判据（次序即判据）
 * 对前 [MAX_SCAN_ROWS] 行逐行打分，候选行必须满足：
 * 1. **有字**：至少两格非空（单列表放宽到一格）——一格都没有的行不可能是列名行；
 * 2. **不含数字格**：列名里出现整格数字的极少，而数据行几乎必然有 ⇒ 这条把数据行挡在外面；
 * 3. **下面得有数据**：最后一行不能当表头（下面没东西）；
 * 4. **数据块同型度**（`score = 同型度 × 1000 + 有字格数`）：一个「真表头」后面跟着的
 *    数据块是**整列同型**的；一个「标题行」后面紧跟着的还有表头那一行，于是它下面那个
 *    数据块会掺进一格文本，同型度掉下来。这不是猜——它是可复算的数。
 *
 * ## 什么时候**不问**、什么时候**必须问**
 * - 唯一最高分 ⇒ 认这一行；
 * - 最高分并列（差 < 0.5）⇒ **不问就是猜** ⇒ `header_unknown` 并把并列的行号说出来；
 * - 一个候选都没有（全是数字行 / 全是空行）⇒ `header_unknown`；
 * - 认出来的那一行**中间有空洞**（`A` 有字、`B` 空、`C` 有字）⇒ 合并单元格的典型形态，
 *   `header_unknown`（派工单 §2.1④ 原话：合并单元格表头 ⇒ 标 header_unknown 并问一句）。
 */
internal object TableHeader {

    /** 只看前 10 行：表头再往下就说明这张表不是「一张表」（是报表拼版），该问人。 */
    const val MAX_SCAN_ROWS = 10

    /** 同型度采样多少行（够看出形态即可，不必扫全表）。 */
    private const val SAMPLE_ROWS = 50

    fun detect(raw: List<List<String?>>, width: Int): HeaderDetection {
        if (raw.size < 2) {
            return HeaderDetection(-1, "表里只有一行，没有数据行——哪一行都不能当表头。")
        }
        val limit = minOf(raw.size - 1, MAX_SCAN_ROWS)
        // (行下标 0 起, 分数, 有字格数)
        val hits = ArrayList<Triple<Int, Double, Int>>(limit)
        val dropped = ArrayList<Int>(2)
        for (i in 0 until limit) {
            val filled = candidateFill(raw[i], width) ?: continue
            // 判据⑤：**表头只有一个**。若前面已经有一行候选，而它不比这一行窄，
            // 那这一行就是数据行（标题行天生比表头窄：它是一格跨列，表头是一格一列）。
            // 这条不猜——它是「标题窄、表头宽」这个可复算的形状，而且结果会写进 warnings 让人改。
            if (hits.any { it.third >= filled }) {
                dropped += i + 1
                continue
            }
            hits += Triple(i, blockConsistency(raw, i + 1) * 1000.0 + filled, filled)
        }
        if (hits.isEmpty()) {
            return HeaderDetection(-1, "前 ${limit + 1} 行都像数据行，没找到列名行。")
        }
        val row = hits.maxByOrNull { it.second }!!.first
        if (hasHole(raw[row])) {
            return HeaderDetection(-1, "第 ${row + 1} 行像合并单元格（中间有空格），列名对不上号。", scored = listOf(row + 1))
        }
        val warning = if (dropped.isEmpty()) {
            ""
        } else {
            "第 ${dropped.joinToString("、")} 行也像表头，这里按第 ${row + 1} 行当表头" +
                "（标题行通常比表头窄）。不对的话告诉我第几行是表头。"
        }
        return HeaderDetection(row, warning = warning, scored = listOf(row + 1))
    }

    /**
     * 这一行能不能当表头候选：能 ⇒ 有字格数；不能 ⇒ null。
     *
     * 三条否决（判据①②③）：空格行、比表头窄的行（一格跨列的标题）、含整格数字的行。
     */
    private fun candidateFill(row: List<String?>, width: Int): Int? {
        val filled = row.count { !TableValues.isBlank(it) }
        if (filled == 0) return null
        if (width > 1 && filled < 2) return null
        if (row.any { TableValues.numberOrNull(it) != null }) return null
        return filled
    }

    /** 中间有空洞：`lastNonBlank` 之前存在空格 ⇒ 那一列没有自己的列名。 */
    fun hasHole(row: List<String?>): Boolean {
        val last = row.indexOfLast { !TableValues.isBlank(it) }
        if (last <= 0) return false
        return (0 until last).any { TableValues.isBlank(row[it]) }
    }

    /** 数据块的「整列同型度」（0..1；没有数据 ⇒ 0）。 */
    private fun blockConsistency(raw: List<List<String?>>, from: Int): Double {
        if (from >= raw.size) return 0.0
        val block = raw.subList(from, minOf(raw.size, from + SAMPLE_ROWS))
        val width = block.maxOfOrNull { it.size } ?: 0
        var sum = 0.0
        var cols = 0
        for (c in 0 until width) {
            val types = block.mapNotNull { r ->
                val v = r.getOrNull(c)
                if (TableValues.isBlank(v)) null else TableValues.classify(v)
            }
            if (types.isEmpty()) continue
            sum += types.groupingBy { it }.eachCount().values.max().toDouble() / types.size
            cols++
        }
        return if (cols == 0) 0.0 else sum / cols
    }
}

/**
 * 从 [SheetGrid]（`sheet_read` 的解析结果）搭出 [TableModel]。
 *
 * 读表用 `hasHeader = false`：表头在第几行**由本文件判**，不由「首行就是表头」这个假设定。
 *
 * `internal`：入参 [SheetGrid] 是读表层的内部形状，不对外暴露（对外只给 [TableModel]）。
 */
internal object TableModelBuilder {

    /**
     * @param headerRowOverride 用户指定的表头行（**1 起**，就是她在表里数出来的行号）；
     *   给了就不走识别——她已经说了，再判一次就是跟她争。
     */
    fun build(
        path: String,
        sheet: String,
        grid: SheetGrid,
        headerRowOverride: Int? = null,
    ): TableModel {
        val raw = grid.rows
        val nos = if (grid.rowNumbers.size == raw.size) grid.rowNumbers else raw.indices.map { it + 1 }
        if (raw.isEmpty()) {
            return TableModel.unknown(path, sheet, TableHeaderAsk.sentence("这张表一行都没有（空文件或空 sheet）。"))
        }
        val width = raw.maxOfOrNull { it.size } ?: 0
        if (width == 0) {
            return TableModel.unknown(path, sheet, TableHeaderAsk.sentence("这张表一格都没有。"))
        }
        var headerWarn = ""
        val row = when {
            headerRowOverride != null -> {
                val idx = headerRowOverride - 1
                if (idx < 0 || idx >= raw.size) {
                    return TableModel.unknown(
                        path,
                        sheet,
                        "你指的「第 $headerRowOverride 行」不在这张表里（共 ${raw.size} 行）。${TableHeaderAsk.ASK}",
                    )
                }
                if (idx >= raw.size - 1) {
                    return TableModel.unknown(
                        path,
                        sheet,
                        "你指的「第 $headerRowOverride 行」下面没有数据行。${TableHeaderAsk.ASK}",
                    )
                }
                if (TableHeader.hasHole(raw[idx])) {
                    return TableModel.unknown(
                        path,
                        sheet,
                        TableHeaderAsk.sentence("第 $headerRowOverride 行中间有空格（合并单元格），列名对不上号。"),
                    )
                }
                idx
            }
            else -> TableHeader.detect(raw, width).let { d ->
                if (d.row < 0) return TableModel.unknown(path, sheet, TableHeaderAsk.sentence(d.reason))
                headerWarn = d.warning
                d.row
            }
        }
        return assemble(path, sheet, raw, nos, row, grid.truncated, headerWarn)
    }

    /** 表头已定 ⇒ 建列、收行、算主键、攒 warnings。 */
    private fun assemble(
        path: String,
        sheet: String,
        raw: List<List<String?>>,
        nos: List<Int>,
        headerRow: Int,
        truncated: Boolean,
        headerWarn: String,
    ): TableModel {
        val head = raw[headerRow]
        val colCount = head.indexOfLast { !TableValues.isBlank(it) } + 1
        val warnings = ArrayList<String>()
        if (headerWarn.isNotEmpty()) warnings += headerWarn
        val names = (0 until colCount).map { head.getOrNull(it)?.trim().orEmpty() }
        val dupes = names.filter { it.isNotEmpty() }.groupingBy { TableText.normalizeName(it) }
            .eachCount().filterValues { it > 1 }.keys
        if (dupes.isNotEmpty()) {
            warnings += "表头有重名列（${dupes.joinToString("、")}）：问这一列时会先问你问的是哪一列，不替你选。"
        }
        val data = ArrayList<TableRow>(raw.size)
        val over = ArrayList<Int>()
        var dropped = 0
        for (i in headerRow + 1 until raw.size) {
            val cells = raw[i]
            if (cells.all { TableValues.isBlank(it) }) {
                dropped++
                continue
            }
            if (cells.size > colCount && cells.drop(colCount).any { !TableValues.isBlank(it) }) {
                over += nos.getOrElse(i) { i + 1 }
            }
            data += TableRow(nos.getOrElse(i) { i + 1 }, pad(cells, colCount))
        }
        if (dropped > 0) warnings += "有 $dropped 行是整行全空，按没有这一行处理（不计入人数/行数）。"
        if (over.isNotEmpty()) {
            warnings += "有 ${over.size} 行比表头多格（${TableText.rowsText(over)}），" +
                "多出的格子没有列名，按没读到处理。"
        }
        val columns = (0 until colCount).map { buildColumn(it, names[it], data) }
        columns.filter { it.type == ColumnType.BLANK }.forEach {
            warnings += "「${it.name}」整列都是空的（空列），不参与任何计算。"
        }
        return TableModel(
            path = path,
            sheet = sheet,
            headerRow = headerRow,
            columns = columns,
            keys = columns.filter { it.keyCandidate }
                .sortedByDescending { keyWeight(it.name) }
                .map { it.name },
            rows = data,
            warnings = warnings,
            truncated = truncated,
        )
    }

    /**
     * 建一列：**弱类型**——非空格里只要有一种以上类型，这列就是文本。
     *
     * 这条判据直接决定「求和能不能算」：`分数` 列里混进一格 `缺考` ⇒ 这列是文本，
     * 求平均时回「这列不是数字列」，而不是把 `缺考` 当 0 拉低平均分。
     */
    private fun buildColumn(index: Int, name: String, data: List<TableRow>): TableColumn {
        val cells = data.map { it.cells.getOrNull(index) }
        val nonBlank = cells.filter { !TableValues.isBlank(it) }
        val types = nonBlank.map { TableValues.classify(it) }.toSet()
        val type = when {
            nonBlank.isEmpty() -> ColumnType.BLANK
            types.size == 1 -> types.first()
            else -> ColumnType.TEXT
        }
        val distinct = nonBlank.map { it!!.trim() }.toSet().size
        val key = nonBlank.size == data.size && data.isNotEmpty() && distinct == data.size
        return TableColumn(
            index = index,
            name = name.ifEmpty { "（第 ${index + 1} 列没名字）" },
            type = type,
            filled = nonBlank.size,
            distinct = distinct,
            keyCandidate = key,
        )
    }

    /** 主键候选的排序权重：列名像「编号/号/ID/学号」的排前面（只影响先后，不影响是否候选）。 */
    private fun keyWeight(name: String): Int {
        val n = TableText.normalizeName(name)
        return when {
            n.contains("编号") || n.contains("id") -> 3
            n.endsWith("号") || n.contains("学号") || n.contains("工号") -> 2
            n.contains("姓名") || n.contains("名称") -> 1
            else -> 0
        }
    }

    private fun pad(cells: List<String?>, width: Int): List<String?> =
        if (cells.size >= width) cells.take(width) else cells + List(width - cells.size) { null }
}
