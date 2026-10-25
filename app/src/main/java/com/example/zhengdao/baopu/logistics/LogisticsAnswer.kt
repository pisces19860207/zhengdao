// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 答话纪律（派工单 §3.5，用户 2026-10-24 原话：「这种回答就行，
// 要精准和准确，不能废话多」）。
//
// ## 固定三行，一行一职能
// ```
// <方向/条件>：<N> 车／<M> 件                        ← 汇总行：数量必须带单位
// 已装 <a> 车（<点名…>），缺 <b> 车 <c> 件（<点名…>）  ← 明细行：缺口必须点名
// 数据：<HH:mm> 导出的<来源>；之后的变化不在这份表里    ← 时间行：必须明示新鲜度
// ```
// 点名的那一格里放什么**跟着口径走**（§1.8 冻结默认 ORDER＝按配货单去重之后，「2 车」后面
// 不能再列 5 个运单号）：能数车时报该口径那一列的值（默认档＝配货单号，车牌档＝车牌），
// 数不了车（缺列降级）时才改报单号——§3.5 原话「缺 <b> 车（<单号…>）」是后一种写法。
// 外加两条裁定：**口径脚注合并进第 3 行**（2026-10-25 裁定 3）、**缺哪列就地明说**
// （2026-10-25 改单后的负例 1b：「只报『12 单（缺件数列）』」）。
//
// ## 三行是上限，不是凑数目标
// 无表 ⇒ 一行（而且这一行**不许有任何数字**）；拒答与歧义 ⇒ 两行（结论 + 数据时间）。
// 判据是「随机抽 10 次提问，回答全部落在三行内」，所以这里把行数与禁用词都做成
// 可断言的函数（[lineCount]／[containsForbidden]），测试直接钉。
//
// ## 禁的三类话
// - **推断词**：可能／大概／应该／估计／看起来——数据里没有就是没有，写「表里没有」；
// - **铺垫与总结**：「我帮你查一下」「根据导出数据」「总体来看」——一行都不许有；
// - **建议**：除非是异常清单（未发播报），否则不主动给建议（用户没问）。
package com.example.zhengdao.baopu.logistics

import java.util.TimeZone

/** 三行答话的组装（纯函数：条件 + 聚合 + 数据时间 → 给用户的那段话）。 */
object LogisticsAnswer {

    /** 明细点名超过这么多条就折叠（§3.5「明细超过 3 条一律折叠」）。 */
    const val MAX_DETAIL = 3

    /** 推断词＋铺垫词（测试随机抽问法时逐条扫这句话）。 */
    val FORBIDDEN = listOf(
        "可能", "大概", "应该", "估计", "看起来", "应该差不多", "我帮你", "我为你", "根据导出数据", "根据这份表",
        "总体来看", "看来", "也许", "应该是", "别忘了", "建议您", "建议你", "最好",
    )

    /**
     * 一句话的完整回答。
     *
     * @param at 这一天的数据时间（从当日表里取来的，不是墙上时钟）
     * @param fileLabel 来源表名（进时间行，作为「数据」的那个名词）
     */
    fun of(
        outcome: QueryOutcome,
        at: ExportedAt,
        fileLabel: String,
        zone: TimeZone,
        nowMillis: Long,
    ): String = when (outcome) {
        is QueryOutcome.NoTable -> noTable()
        is QueryOutcome.Ambiguous -> {
            val options = outcome.options.take(MAX_DETAIL).joinToString("、") +
                if (outcome.options.size > MAX_DETAIL) " 等 ${outcome.options.size} 个" else ""
            listOf("${orToday(outcome.term)}有 $options，问的是哪个？", timeLine(at, fileLabel, zone, nowMillis, emptyList()))
                .joinToString("\n")
        }
        is QueryOutcome.NoMatch -> listOf(refusal(outcome.spec), timeLine(at, fileLabel, zone, nowMillis, emptyList())).joinToString("\n")
        is QueryOutcome.Found -> found(outcome.spec, outcome.agg, at, fileLabel, zone, nowMillis)
    }

    /** 无表那一句：**一个数字都不许出现**（判据「无表 10 句」钉这条），所以不写产品代号、只写去处。 */
    fun noTable(): String = "今天还没有表：把导出的配货单放进 产物/物流/inbox/ 再问"

    /** 拒答句（§3：查不到就说查不到，不许推「可能在路上」）。 */
    fun refusal(spec: QuerySpec): String {
        // 「货主是谁」这类问的是**配货单上根本没有的一列**：说「没有记录」她会回去翻表，
        // 点名「没有这一列」才是这张表的真实情况（§1.8.5：配货单没有客户信息）。
        spec.unmatchedColumn.takeIf { it.isNotEmpty() }?.let { return "这份表里没有「$it」这一列" }
        val term = spec.unmatchedTerm.ifEmpty {
            spec.scopes.firstOrNull() ?: spec.plate ?: spec.waybill ?: spec.goods ?: "这个条件"
        }
        return "这份表里没有「$term」的记录"
    }

    private fun orToday(term: String): String = if (term.isEmpty()) "今天" else "「$term」"

    private fun found(
        spec: QuerySpec,
        agg: Aggregate,
        at: ExportedAt,
        fileLabel: String,
        zone: TimeZone,
        nowMillis: Long,
    ): String {
        val lines = ArrayList<String>(3)
        lines += summaryLine(spec, agg)
        lines += detailLine(spec, agg)
        lines += timeLine(at, fileLabel, zone, nowMillis, footnotes(agg))
        return lines.joinToString("\n")
    }

    /**
     * 第 1 行：汇总。
     *
     * 缺列时就地换成能报的那一维并点名缺什么（负例 1b/1c 的判据形态），
     * 车数 > 单数 时按裁定标「口径可疑」而不是取一个好看的数。
     */
    internal fun summaryLine(spec: QuerySpec, agg: Aggregate): String {
        val subject = subjectOf(spec)
        val piecesAbsent = LogisticsField.PIECES in agg.absent
        // 「缺哪一列所以数不了车」跟着口径变：默认档缺的是配货单号，切到车牌档缺的才是车牌。
        // 写死「缺车牌列」会让人去补一列本来不需要的车牌。
        val vehicleAbsentNote = if (agg.canCountVehicles) {
            ""
        } else {
            "（缺${LogisticsAggregate.vehicleField(agg.basis).label}列，车数不报）"
        }
        val pieces = agg.piecesTotal
        // 「没数」有两种，话不能说反：列整个不在 ⇒「缺件数列」；列在但一格数字都没读出 ⇒「件数没一个有数」。
        // 后者要是也写成「缺件数列」，她会去 T9 补列，而真正该补的是那几格的件数。
        val noPieces = when {
            piecesAbsent -> "（缺件数列）"
            pieces == null -> "（件数没一个有数）"
            else -> ""
        }
        val canCountVehicles = agg.canCountVehicles
        val core = when {
            canCountVehicles && pieces != null -> "${agg.vehicles} 车／$pieces 件"
            canCountVehicles -> "${agg.vehicles} 车／${agg.orders} 单$noPieces"
            pieces != null -> "${agg.orders} 单／$pieces 件$vehicleAbsentNote"
            else -> "${agg.orders} 单$vehicleAbsentNote$noPieces"
        }
        val suspicious = if (agg.suspicious) "口径可疑（车数>单数）" else ""
        val tail = if (suspicious.isEmpty()) "" else "，$suspicious"
        return "$subject：$core$tail"
    }

    /** 条件怎么说（范围/车牌/单号/货名/全天，四种各一句）。 */
    internal fun subjectOf(spec: QuerySpec): String {
        val waybill = spec.waybill
        val plate = spec.plate
        val goods = spec.goods
        return when {
            waybill != null -> "单号 $waybill"
            plate != null -> plate
            spec.scopes.size == 1 -> "${spec.scopes.first()}方向"
            spec.scopes.size > 1 -> spec.scopes.joinToString("、")
            goods != null -> "「$goods」"
            else -> "今天"
        }
    }

    /**
     * 第 2 行：明细，缺口必须点名。
     *
     * 「缺」这里只算**待发**档；状态写着「缺件」的单子既不算已装也不算待发，
     * 单独一句点名（把它们并进任何一边都是替她做判断）。
     * 表里没有状态列 ⇒ 这一行直接答「无法判断」，不许按「都还没装」算（裁定 1c）。
     */
    internal fun detailLine(spec: QuerySpec, agg: Aggregate): String {
        if (spec.intent == LogisticsIntent.GOODS) return goodsLine(agg)
        if (spec.intent == LogisticsIntent.WHO) return whoLine(agg)
        if (LogisticsField.STATUS in agg.absent) return "表里没有状态列，无法判断装没装"
        // 第 1 行说了「车数不报」，第 2 行就不许接着报「已装 0 车」——那等于换个地方把没这一维
        // 说成一辆车都没有。数不了车时（缺口径那一列）改按**单**点单号。
        val canVehicles = agg.canCountVehicles
        val unit = if (canVehicles) "车" else "单"
        val loadedCount = if (canVehicles) agg.loadedVehicles else agg.loaded.size
        val pendingCount = if (canVehicles) agg.pendingVehicles else agg.pending.size
        val loadedNames = if (canVehicles) agg.loadedNames() else agg.loaded.map { it.waybill }
        val pendingNames = if (canVehicles) agg.pendingNames() else agg.pending.map { it.waybill }
        val loaded = namedPart("已装", loadedCount, loadedNames, unit, null)
        val pieces = agg.pendingPieces
        val pendingCore = "$pendingCount $unit" + if (LogisticsField.PIECES in agg.absent || pieces == null) {
            ""
        } else {
            " ${pieces} 件"
        }
        val pending = namedPart("缺", pendingCount, pendingNames, unit, pendingCore)
        val extra = ArrayList<String>(3)
        if (agg.shortShip.isNotEmpty()) extra += "另 ${agg.shortShip.size} 单标了缺件"
        if (agg.statusUnknown.isNotEmpty()) extra += "另 ${agg.statusUnknown.size} 单状态未填，没法判断"
        val folded = if (agg.piecesUnknown.isNotEmpty() && LogisticsField.PIECES !in agg.absent) {
            "（${agg.piecesUnknown.size} 单件数未填，没算进来）"
        } else {
            ""
        }
        val tail = if (extra.isEmpty()) "" else "；" + extra.joinToString("；")
        return "$loaded，$pending$folded$tail"
    }

    /** 「已装 2 车（冀A12345、冀A67890）」；超过 [MAX_DETAIL] 条折成「另有 N 条，展开看」。 */
    private fun namedPart(
        label: String,
        count: Int,
        names: List<String>,
        unit: String,
        override: String?,
    ): String {
        val shown = override ?: "$count $unit"
        if (names.isEmpty()) return "$label $shown"
        val head = names.take(MAX_DETAIL).joinToString("、")
        val tail = if (names.size > MAX_DETAIL) "，另有 ${names.size - MAX_DETAIL} 条，展开看" else ""
        return "$label $shown（$head$tail）"
    }

    /** 「什么货」那一问：按货名分布报，缺货名列就直说没有这维。 */
    private fun goodsLine(agg: Aggregate): String {
        if (LogisticsField.GOODS in agg.absent) return "表里没有货名列，无法回答是什么货"
        val groups = agg.rows.groupBy { if (it.goods.isEmpty()) "" else it.goods }
        val named = groups.keys.filter { it.isNotEmpty() }.sortedBy { -(groups.getValue(it).size) }
        if (named.isEmpty()) return "${agg.rows.size} 单的货名都没填"
        val parts = named.take(MAX_DETAIL).map { g ->
            val rows = groups.getValue(g)
            val p = LogisticsAggregate.piecesTotal(rows)
            if (p == null) "$g ${rows.size} 单" else "$g $p 件"
        }
        val blank = groups[""]?.size ?: 0
        val more = named.size - MAX_DETAIL
        val tail = when {
            more > 0 && blank > 0 -> "，另有 $more 种；$blank 单货名未填"
            more > 0 -> "，另有 $more 种"
            blank > 0 -> "；$blank 单货名未填"
            else -> ""
        }
        return "货名：" + parts.joinToString("、") + tail
    }

    /**
     * 「这车谁的」「老张电话」那一问（2026-10-25 她补的口径）。
     *
     * 名字与号码**原样**（§1.8 冻结：这四列不打码），号码这维整列缺席时就地降级——
     * 「表里没有手机号列」后面只列名字，**不许**从单号/车牌那边凑一个像号码的东西出来。
     * 每个人名后面跟的号码交给面板变成可点的拨号链接（`LogisticsPhone` 判形，壳里只接线）。
     */
    internal fun whoLine(agg: Aggregate): String {
        if (LogisticsField.DRIVER in agg.absent) return "表里没有司机列，回答不了是谁的"
        val groups = agg.rows.groupBy { it.driver }
        val named = groups.keys.filter { it.isNotEmpty() }.sortedBy { -groups.getValue(it).size }
        if (named.isEmpty()) return "司机一栏没填"
        val blank = groups[""]?.size ?: 0
        val head = if (LogisticsField.PHONE in agg.absent) "表里没有手机号列：" else "司机："
        val parts = named.take(MAX_DETAIL).map { name ->
            if (LogisticsField.PHONE in agg.absent) {
                name
            } else {
                val phones = groups.getValue(name).flatMap { LogisticsPhone.forRow(it) }
                    .map { it.shown }.distinct()
                if (phones.isEmpty()) "$name 未填" else "$name ${phones.joinToString("/")}"
            }
        }
        val more = named.size - MAX_DETAIL
        val tail = when {
            more > 0 && blank > 0 -> "，另有 $more 人；$blank 单司机未填"
            more > 0 -> "，另有 $more 人，展开看"
            blank > 0 -> "；$blank 单司机未填"
            else -> ""
        }
        return head + parts.joinToString("、") + tail
    }

    /** 第 3 行：数据时间 ＋ 口径脚注（裁定「每答必带脚注，收在三行内，第 3 行合并」）。 */
    internal fun timeLine(
        at: ExportedAt,
        fileLabel: String,
        zone: TimeZone,
        nowMillis: Long,
        footnotes: List<String>,
    ): String {
        val base = LogisticsExportedAt.lineOf(at, fileLabel, zone, nowMillis)
        if (footnotes.isEmpty()) return base
        // 脚注插在「之后的变化不在这份表里」之前，仍然是同一行（不许为脚注多开一行）
        val marker = "；之后的变化不在这份表里"
        return if (base.endsWith(marker)) {
            base.removeSuffix(marker) + "；" + footnotes.joinToString("；") + marker
        } else {
            "$base；" + footnotes.joinToString("；")
        }
    }

    /** 第 3 行要挂的脚注：口径定义 ＋（有单没算进来时的）说明。 */
    private fun footnotes(agg: Aggregate): List<String> {
        val out = ArrayList<String>(2)
        out += agg.basis.footnote
        if (agg.canCountVehicles) {
            val field = LogisticsAggregate.vehicleField(agg.basis)
            val n = if (agg.basis == LogisticsBasis.ORDER) {
                LogisticsAggregate.orderUnknown(agg.rows).size
            } else {
                LogisticsAggregate.plateUnknown(agg.rows).size
            }
            if (n > 0) out += "$n 单${field.label}未填，没算进车数"
        }
        return out
    }

    /** 行数（判据：随机抽 10 次提问，回答全部落在三行内）。 */
    fun lineCount(text: String): Int = text.split('\n').count { it.isNotBlank() }

    /** 这句话里出现了哪些禁用词（空 = 干净）。 */
    fun containsForbidden(text: String): List<String> = FORBIDDEN.filter { text.contains(it) }

    /** 这句话里所有数字串（判据用它来要求「无表时不许有数字」）。 */
    fun digitsOf(text: String): List<String> = Regex("""\d+""").findAll(text).map { it.value }.toList()
}
