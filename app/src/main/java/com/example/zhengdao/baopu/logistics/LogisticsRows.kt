// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 方格 → 单据行（纯函数，零 Android）。
//
// 这一层负责把「一格一格的文字」变成「能算、能报的单子」，纪律有三条：
//
// ## 1. 空 ≠ 0，读不出 ≠ 空
// 件数那一格有三种下场：空白 ⇒ [LogisticsField.PIECES]「未填」；有字但不是数 ⇒「读不出」；
// 是数才进合计。后两种**都不进合计**，而且**分别计数报出去**。
// 「静默当 0」是派工单 §5 负例 1b 钉的那条：缺件数列不许用 0 凑，一格读不出也不许。
//
// ## 2. 状态只认表里的字，不认「差不多」
// 状态机按派工单 §2 就四档：[LogisticsStatus.PENDING] 待发 / [LogisticsStatus.LOADED] 已装 /
// [LogisticsStatus.SENT] 已发 / [LogisticsStatus.SHORT] 缺件，外加一个「表里有这列但这句话我不认识」
// 的 [LogisticsStatus.UNKNOWN]。认识的写法在 [LogisticsStatus.keywords] 里，**整段相等**才算命中：
// 「已装车 12:30」这种带时间的尾巴不认，因为把尾巴剥掉就是猜——它也可能写成「未装车 12:30 前」。
// 不认识的行按未填处理：**不判装没装、也不进「今天还有哪些没发」的名单**（宁可少列一单，
// 不能把「作废」这种单子当成待发催一遍）。
//
// ## 3. 重量与体积不参与任何计算
// 表头写「重量」还是「计费重量」不知道是吨还是公斤，单位错的相加比不加坏得多（派工单 §1
// 「不在别名表里才叫表头变了」管的是列名，管不到单位）。所以 [ShipmentRow.weight] 存原文、只展示，
// 答案里出现的数字只有「车／件／单」，没有吨。
// §1.8 之后加的 [ShipmentRow.volume]（体积/方数）走同一条纪律：194 批要拿它判重泡，
// 但那是 194 的事——本批只读进表、只原样展示、只照实写台账。
package com.example.zhengdao.baopu.logistics

/** 派工单 §2 的状态机（[UNKNOWN] 不是第五档状态，是「这句话我不认识」的如实登记）。 */
enum class LogisticsStatus(val label: String, val keywords: Set<String>) {
    PENDING("待发", setOf("待发", "未发", "待发货", "未发货", "待装车", "未装车", "待配", "未配", "待提", "未提")),
    LOADED("已装", setOf("已装", "已装车", "已装载", "装车完成", "装完", "已配载", "配载完成", "已配", "已提", "已提车")),
    SENT("已发", setOf("已发", "已发货", "已发出", "发出", "发走", "已走", "在途", "已运出")),
    SHORT("缺件", setOf("缺件", "少件", "缺货", "短少", "件数不符")),

    /** 表里有状态列、但这行是空白或写着不认识的话 ⇒ 装没装**答不出**，不许蒙。 */
    UNKNOWN("未填", emptySet()),
    ;

    companion object {

        /**
         * 归一后的关键词 → 档位，一次性建好。
         *
         * 建索引而不是每格遍历：导入 5000 行时「每格现归一关键词表」会把同一句话归一万遍，
         * 更要紧的是——索引建好之后，**两个档位的关键词撞车**（比如「已提」同时进 已装 和 已发）
         * 会在表驱动测试里当场红，而不是变成「哪档先遍历到算哪档」。
         */
        private val INDEX: Map<String, LogisticsStatus> =
            entries.flatMap { s -> s.keywords.map { LogisticsContract.normalize(it) to s } }.toMap()

        /** 表驱动测试钉这条：一个写法只许归一个档。 */
        fun keywordCount(): Int = INDEX.size

        /**
         * 状态文字 → 档位（整段归一后相等才命中）。
         *
         * @return 命中档位；空格/横杠/不认识的话都是 [UNKNOWN]
         */
        fun of(cell: String): LogisticsStatus {
            val n = LogisticsContract.normalize(cell)
            if (n.isEmpty()) return UNKNOWN
            return INDEX[n] ?: UNKNOWN
        }
    }
}

/** 一张单子（一行导出）。 */
data class ShipmentRow(
    /** 归一后的单号（去空白、大写、剥 Excel 文本标记 `'`）——库里存这个，重复导入按它去重。 */
    val waybill: String,
    /**
     * 配货单号（同样按单号规则归一）：§1.8.1 的「车」就是按它去重，一张配货单＝一辆车。
     * 空串 ＝ 这份表没有这一列或这格没填 ⇒ 这一单**不归到任何一张车上**，脚注要点名有几单没算进车数。
     */
    val orderNo: String,
    val direction: String,
    val unload: String,
    val goods: String,
    /** 件数：`null` ＝ 未填或读不出，**绝不是 0**。 */
    val pieces: Int?,
    /** 件数那格的原文（`null` 时答案里要念出「写着 ××，读不出数字」）。 */
    val piecesRaw: String,
    /** 重量原文：只展示，本批不参与任何计算（单位没法从格子本身判定）。 */
    val weight: String,
    /** 体积原文（方数）：同重量——只读、只展示，重泡判断归 194，本批不参与任何计算。 */
    val volume: String,
    val driver: String,
    /** 司机手机号：§1.8 点名的「原样显示、不许打码」四列之一，但同样**不进日志与审计**。 */
    val phone: String,
    /** 车牌：一格可能并列两台车（`冀A1/冀B2`），拆开存；不并列时就是长度 1 或 0。 */
    val plates: List<String>,
    val status: LogisticsStatus,
    /** 状态原文（UNKNOWN 时答案要点名原文，让人知道是哪句话没认出来）。 */
    val statusRaw: String,
    /** 来源文件里的真实行号（派工单 §3「每个数字带证据：来源文件名＋行号」）。 */
    val lineNo: Int,
) {
    /** 有件数才进合计。 */
    val hasPieces: Boolean get() = pieces != null
}

/** 一行的下场：收进来，或者**因为认不出而被拒**（拒掉的行号要报给用户，不能静默丢）。 */
sealed interface RowBuild {
    data class Ok(val rows: List<ShipmentRow>, val notes: List<String>) : RowBuild

    /** 整张表一行都没收成 ⇒ 一句给用户的话（不是空表，是「这张表我一行都没记」）。 */
    data class Failed(val reason: String) : RowBuild
}

/**
 * [Grid] + [HeaderOutcome.Ok] → 单据行。
 *
 * 列的位置完全由 [LogisticsContract] 给的 [HeaderOutcome.Ok.columnOf] 决定：这里**只看列号**，
 * 绝不再按「长得像不像车牌」去猜哪列是车牌。
 */
object LogisticsRows {

    /** 一格里并列多个车牌的分隔符（写完不再加：`冀A 冀B` 这种用空格并列的也收，因为空格不是车牌字符）。 */
    private val PLATE_SPLIT = charArrayOf('/', '\\', ',', '，', '、', ';', '；', ' ', '　', '\n', '\r')

    fun build(grid: Grid, ok: HeaderOutcome.Ok): RowBuild {
        val col = ok.columnOf
        val noWaybill = ArrayList<Int>()
        val badPieces = ArrayList<String>()
        val oddStatus = ArrayList<String>()
        val out = ArrayList<ShipmentRow>(grid.rows.size)
        grid.rows.forEachIndexed { i, row ->
            val lineNo = grid.rowNumbers.getOrElse(i) { -1 }
            val waybill = waybillOf(at(row, col, LogisticsField.WAYBILL))
            if (waybill.isEmpty()) {
                noWaybill += lineNo
                return@forEachIndexed
            }
            val piecesRaw = at(row, col, LogisticsField.PIECES)
            val pieces = piecesOf(piecesRaw)
            if (pieces == null && piecesRaw.isNotEmpty()) badPieces += "$piecesRaw（第 $lineNo 行）"
            val statusRaw = at(row, col, LogisticsField.STATUS)
            val status = LogisticsStatus.of(statusRaw)
            if (status == LogisticsStatus.UNKNOWN && statusRaw.isNotEmpty()) {
                oddStatus += "$statusRaw（第 $lineNo 行）"
            }
            out += ShipmentRow(
                waybill = waybill,
                orderNo = waybillOf(at(row, col, LogisticsField.ORDER_NO)),
                direction = at(row, col, LogisticsField.DIRECTION),
                unload = at(row, col, LogisticsField.UNLOAD),
                goods = at(row, col, LogisticsField.GOODS),
                pieces = pieces,
                piecesRaw = piecesRaw,
                weight = at(row, col, LogisticsField.WEIGHT),
                volume = at(row, col, LogisticsField.VOLUME),
                driver = at(row, col, LogisticsField.DRIVER),
                phone = at(row, col, LogisticsField.PHONE),
                plates = platesOf(at(row, col, LogisticsField.PLATE)),
                status = status,
                statusRaw = statusRaw,
                lineNo = lineNo,
            )
        }
        if (out.isEmpty()) {
            val why = if (noWaybill.isNotEmpty()) {
                "有 ${noWaybill.size} 行「单号」是空的（第 ${LogisticsGrid.previewRows(noWaybill)} 附近）没收进表，一行都没记。"
            } else {
                "这张表没有一行能认成单子。"
            }
            return RowBuild.Failed(
                "$why 单号是核对与「同一份表导入两次不重复」的唯一凭据，空单号没法保证不重复记，" +
                    "所以这些行不读、也不往任何字段里塞。",
            )
        }
        val notes = ArrayList<String>()
        if (noWaybill.isNotEmpty()) {
            notes += "有 ${noWaybill.size} 行没有单号（第 ${LogisticsGrid.previewRows(noWaybill)}）没收进表：" +
                "单号空就没法去重，也没法让你点开核。"
        }
        if (badPieces.isNotEmpty()) {
            notes += "有 ${badPieces.size} 行的「件数」不是数字（${LogisticsGrid.previewOf(badPieces, "处")}）：" +
                "这些行不进任何合计，绝不按 0 计。"
        }
        if (oddStatus.isNotEmpty()) {
            notes += "有 ${oddStatus.size} 行的状态这句话不认识（${LogisticsGrid.previewOf(oddStatus, "处")}）：" +
                "按「未填」记，不判装没装、也不进「还没发」的名单。要收进状态机请把它加进关键词表。"
        }
        return RowBuild.Ok(out, notes)
    }

    /** 取一格；这一维的列不存在时返回空串（缺列的降级在 [HeaderOutcome.Ok.absentSoft] 里统一报）。 */
    private fun at(row: List<String>, col: Map<LogisticsField, Int>, f: LogisticsField): String {
        val idx = col[f] ?: return ""
        return row.getOrElse(idx) { "" }
    }

    /**
     * 单号归一：去空白、拉丁字母大写、剥掉 Excel 导出的文本标记 `'`。
     *
     * 只做**表示法**的归一，不改一个字的内容：`a0123` 与 `A0123` 是同一个单号（大小写是录入习惯），
     * 但 `0123` 与 `123` 不是（少一位是另一个号，补零就是猜）。
     */
    fun waybillOf(raw: String): String {
        val t = raw.trim().trimStart('\'', '‘', '“').trimEnd('\'', '’', '”')
        if (t.any { it.isWhitespace() }) return t.filterNot { it.isWhitespace() }.uppercase()
        return t.uppercase()
    }

    /**
     * 件数：`12`／`12件`／`12.0` ⇒ 12；空白、`12.5`、`12/8`、`三件` ⇒ `null`（不是 0）。
     *
     * 尾巴只认「件/个/票/包」这四个量词，认它们是**表示法**不是内容：导出里 `12件` 与 `12` 同义。
     * 小数只在「.0」这种浮点尾巴上收成整数（xlsx 的数字格常写成 `12`，WPS 有的版本写 `12.0`）。
     */
    fun piecesOf(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val m = PIECES_RE.matchEntire(t) ?: return null
        val whole = m.groupValues[1]
        val frac = m.groupValues[2]
        if (frac.isNotEmpty() && frac.trimEnd('0') != "") return null
        return whole.toIntOrNull()?.takeIf { it in 0..999_999 }
    }

    private val PIECES_RE = Regex("""^(\d{1,6})(?:\.(\d{1,4}))?\s*(?:件|个|票|包)?$""")

    /**
     * 车牌：按分隔符拆开、逐个归一（去空白、大写）。
     *
     * 拆并列车牌不是为了算得准，是为了**不并错**：`冀A12345/冀B67890` 整串当成一个车牌，
     * 那台车换任何一个字都不再相等，去重会得到「两台车」的错误结论（这里拆完是 2 个值）。
     * 并列导致车数超过单数时，[LogisticsAggregate] 的一致性自检会把它标成「口径可疑」，
     * 而不是悄悄取一个。
     */
    fun platesOf(raw: String): List<String> = raw
        .split(*PLATE_SPLIT)
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() && it != "-" && it != "—" }
        .distinct()
}
