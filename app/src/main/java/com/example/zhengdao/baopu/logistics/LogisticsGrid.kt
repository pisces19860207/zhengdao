// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 解析层的公共形状：一张「有表头的方格」。
//
// CSV 与 xlsx 两条读法都收口到 [Grid]，再由 [LogisticsContract] 认表头、[LogisticsRows] 填行。
// 收口成一个形状是有意的：**「表头即契约」只许有一条判定路径**，两条读法各自判一次表头
// 迟早会长出两种口径（157 批的 资料库/ 与 175 批的 产物/ 就是这么撞上的）。
package com.example.zhengdao.baopu.logistics

/**
 * 一张读出来的方格。
 *
 * @param header 第一行（列名原文，**未归一**——归一是 [LogisticsContract] 的事，
 *   这里保留原文是为了拒读时能把用户写的原话念回去）
 * @param rows 数据行（已经过 [align]，每行长度等于表头长度；缺的格是空串）
 * @param rowNumbers 每个数据行在**原文里的行号**（xlsx 用 `<row r>`、CSV 数真实换行）——
 *   派工单 §3 要的「每个数字带证据：来源文件名＋行号」钉的就是这个数，
 *   它在文件里跨过空行/被引号包住的换行也不能错
 * @param notes 读这张表时**必须告诉用户**的话（多 sheet、溢出格、没缓存值的公式…）；
 *   空列表＝这张表读得干干净净，没有要交代的
 */
data class Grid(
    val header: List<String>,
    val rows: List<List<String>>,
    val notes: List<String> = emptyList(),
    val rowNumbers: List<Int> = emptyList(),
)

/**
 * 方格层的公共判据。
 */
object LogisticsGrid {

    /** 一张表最多收多少数据行（超了如实报，不静默截断——见 [align] 的溢出注记）。 */
    const val MAX_ROWS = 5_000

    /** 一行最多多少格（防一张脏表撑爆内存）。 */
    const val MAX_COLS = 200

    /** 注记最多留几条（再多就只报总数：面板一行放不下十句话）。 */
    const val MAX_NOTES = 5

    /**
     * 把每行补齐/裁切成表头宽度，并把**裁掉的部分如实记进注记**。
     *
     * 派工单 §1 的原话是「不许猜列、不许错位填充」。这里对错位做的处理是：
     * - 行比表头**长**：多出来的格子没有列名，读进任何字段都是错位 ⇒ 丢掉并在注记里点名行号；
     * - 行比表头**短**：尾部补空串。空串在下游一律按「未填」处理（**不给 0**、不参与计算），
     *   这是 §3.5「字段空写未填」的同一条纪律，不是猜。
     *
     * 行号一律取 [rowNumbers]（来源文件的真实行号），不在这里 `i + 2` 现推：CSV 会丢掉全空行、
     * xlsx 的行号由 `<row r>` 给出，「注记里点名的行号」和「证据行号」两套并存时，
     * 用户按注记去核数会核到错的行——「每个数字带证据」就变成「每个数字带假证据」。
     *
     * 溢出与欠缺的行号各汇总成一条注记（超过 [MAX_NOTES] 只报总数），
     * 因为「这份表有 37 行比表头多一格」这句话本身就是「表没读全」的证据。
     */
    fun align(
        header: List<String>,
        rows: List<List<String>>,
        rowNumbers: List<Int>,
    ): Pair<List<List<String>>, List<String>> {
        val width = header.size
        val out = ArrayList<List<String>>(rows.size)
        val overflow = ArrayList<Int>()
        val short = ArrayList<Int>()
        rows.forEachIndexed { i, row ->
            val no = rowNumbers.getOrElse(i) { -1 }
            when {
                row.size > width -> {
                    overflow += no
                    out += row.take(width)
                }
                row.size < width -> {
                    short += no
                    out += row + List(width - row.size) { "" }
                }
                else -> out += row
            }
        }
        val notes = ArrayList<String>(2)
        if (overflow.isNotEmpty()) {
            notes += "有 ${overflow.size} 行比表头多格（第 ${previewRows(overflow)}），" +
                "多出的格子没有列名，按没读到处理，不往任何字段里塞。"
        }
        if (short.isNotEmpty()) {
            notes += "有 ${short.size} 行比表头少格（第 ${previewRows(short)}），缺的格按「未填」记，不给 0。"
        }
        return out to notes
    }

    /** 注记里点**行号**：最多列 [MAX_NOTES] 个，其余折成「等 N 行」；`-1` 是行号没拿到，写 `?` 不写 0。 */
    fun previewRows(rows: List<Int>): String = previewOf(rows.map { if (it < 0) "?" else it.toString() }, "行")

    /**
     * 注记里点**原文**（读不出的件数、不认的状态…）：同样最多 [MAX_NOTES] 项。
     *
     * 面板一行放不下十句话，但「只报总数不报例子」等于让人没法核——所以取前几项点名＋给总数。
     */
    fun previewOf(items: List<String>, unit: String = "项"): String =
        items.take(MAX_NOTES).joinToString("、") +
            if (items.size > MAX_NOTES) " 等 ${items.size} $unit" else ""

    /**
     * 一格是不是「空的」（含只填了横杠/顿号这类占位符）。
     *
     * 空格的判定放在**读入**这一层而不是查询那一层，是为了让「未填」在库里就有唯一表示；
     * 下游再各自 trim 会得到两种「空」。
     */
    fun isBlankish(cell: String): Boolean {
        val t = cell.trim()
        return t.isEmpty() || t.all { it == '-' || it == '—' || it == '/' || it == '·' || it == '.' }
    }

    /** 读入用的取值：空占位符统一成空串（下游据此写「未填」）。 */
    fun cell(cell: String): String = if (isBlankish(cell)) "" else cell.trim()

    /**
     * 只处理这五个 XML 预定义实体与数字实体（`&#…;`）。
     *
     * 手写而不是引 XML 解析器的理由与 `terminal/DocxTextExtractor.kt:107` 同一份：
     * OOXML 的 sheet XML 动辄几 MB，DOM 会把整棵树建出来（手机内存吃不消），
     * 扫描是 O(n) 且几乎不额外占内存。
     */
    fun unescapeXml(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 12) { // 裸 &（Excel 会转义，走到这里说明文件不规矩）——原样保留
                sb.append(c)
                i++
                continue
            }
            val ent = s.substring(i + 1, semi)
            val decoded = when {
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") ->
                    ent.substring(2).toIntOrNull(16)?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) }
                ent.startsWith("#") ->
                    ent.substring(1).toIntOrNull()?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) }
                else -> null
            }
            if (decoded == null) {
                sb.append(c)
                i++
            } else {
                sb.append(decoded)
                i = semi + 1
            }
        }
        return sb.toString()
    }
}
