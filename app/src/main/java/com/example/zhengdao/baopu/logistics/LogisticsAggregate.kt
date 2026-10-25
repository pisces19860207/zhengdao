// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 「几车几件」怎么算（用户 2026-10-25 裁定的口径层，纯函数）。
//
// ## 裁定原文落成的代码
// 「车数**按可配置口径**算，不做硬编码」「每答必带脚注，仍然收在三行内」
// 「字段不支持就降级，不许凑」「**一致性自检**：车数 ≤ 单数 必须成立；违反 ⇒ 标『口径可疑（车数>单数）』并写日志」
//
// 2026-10-25 §1.8 冻结了**默认档**（覆盖早前聊天里的「默认 = PLATE」）：
// 「车数口径＝ORDER（按配货单去重）——每一张配货单对应一辆车、一个司机和司机手机号」，
// 「PLATE/TRIP 仍保留为可切换口径，但默认 ORDER；脚注写『车＝按配货单去重 N 张』」。
//
// 三个口径的定义（定义写死，切换时**数字与脚注一起变**，这是判据要求的可观测差）：
// - [LogisticsBasis.ORDER]：按配货单去重＝**几张单子**。默认——一张配货单就是一辆车。
// - [LogisticsBasis.PLATE]：按车牌去重＝**几台车**。同一台车拉三家的货，这里算 1。
// - [LogisticsBasis.TRIP]：按「车牌＋范围」去重＝**几趟车**。同一台车往北京、天津各装一次算两趟。
//
// 「范围」取 `方向`，方向那列没有时取 `卸点`（表头契约保证两列至少有一列）。
// 没有第三口径可用的字段（导出表里根本没有车次号这一列），所以 TRIP 只能用这个定义——
// 定义本身进了脚注，用户看得见它是怎么数出来的，这才是「不许凑」。
//
// ## 降级而不是补数
// 一维缺了就整维不报：缺件数列 ⇒ [Aggregate.piecesTotal] 是 `null`（答案写「缺件数列」），
// 有件数列但某行空着 ⇒ 那一行进 [Aggregate.piecesUnknown]（答案写「N 单件数未填，没算进来」）。
// 两种都**绝不**当成 0 加进合计——把它们加进合计得到的那个数看着最正常，也最坏。
package com.example.zhengdao.baopu.logistics

/** 车数的口径（可配置三档，[ORDER] 是默认——派工单 §1.8 于 2026-10-25 冻结）。 */
enum class LogisticsBasis(val label: String, val footnote: String) {
    ORDER("配货单", "车＝按配货单去重（张）"),
    PLATE("车牌", "车＝按车牌去重（台）"),
    TRIP("车次", "车＝按车牌＋方向去重（趟）"),
    ;

    companion object {
        /** 面板上的「口径」切换与工具入参都走这里；认不出来的写法回默认，不猜。 */
        fun of(name: String?): LogisticsBasis =
            entries.firstOrNull { it.name.equals(name?.trim()?.uppercase(), ignoreCase = true) } ?: ORDER
    }
}

/** 一个口径算出来的数与它的证据（列表都是原始行，答案层负责折叠与点名）。 */
data class Aggregate(
    /** 参与统计的单子（已按查询条件筛过）。 */
    val rows: List<StoredRow>,

    /** 单数（= [rows] 行数；主键保证不重复）。 */
    val orders: Int,

    /** 车数（按 [basis] 数出来的；见各口径定义）。 */
    val vehicles: Int,

    /** 用的哪个口径——脚注要跟着它变（判据：切换口径 ⇒ 数与脚注同时变）。 */
    val basis: LogisticsBasis,

    /** 件数合计；`null` ＝ 这一维**整维都没说**（缺件数列，或所有行的件数都空/读不出）。 */
    val piecesTotal: Int?,

    /** 已装（含已发：发出去的车必然装过）。 */
    val loaded: List<StoredRow>,

    /** 待发（还没装车的）——「今天还有哪些没发」点名的就是这些。 */
    val pending: List<StoredRow>,

    /** 状态写着「缺件」的：既不算已装也不算待发，单独一档报出去，不塞进任何一边。 */
    val shortShip: List<StoredRow>,

    /** 状态空着或那句我不认识的：装没装**答不出**，也不进待发名单（宁可少列）。 */
    val statusUnknown: List<StoredRow>,

    /** 件数没算进来的单（空着或读不出数字），含「来源表缺件数列」的那些。 */
    val piecesUnknown: List<StoredRow>,

    /** 这些行的来源表缺哪些可降级列（脚注用；跨文件导入时是并集）。 */
    val absent: Set<LogisticsField>,

    /** 一致性自检：车数 > 单数 ⇒ 口径可疑（多半是一格并列表牌拆出了比单子还多的车）。 */
    val suspicious: Boolean,
) {
    val loadedVehicles: Int get() = vehiclesOf(loaded)
    val pendingVehicles: Int get() = vehiclesOf(pending)
    val pendingPieces: Int? get() = piecesOf(pending)
    val loadedPieces: Int? get() = piecesOf(loaded)
    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * 这一档口径到底能不能数车：看它靠的那一列在不在来源表里。
     *
     * 默认档（[LogisticsBasis.ORDER]）靠 `配货单号`，`PLATE`／`TRIP` 靠 `车牌`——
     * 「缺车牌列所以车数不报」早先是写死的，换成默认 ORDER 以后再照抄就会说错列名，
     * 她会去补一列本来就不需要的车牌。
     */
    val canCountVehicles: Boolean get() = LogisticsAggregate.vehicleField(basis) !in absent

    /** 按同一口径重算一部分行的车数（明细行「已装 a 车」用，保证两行的口径是同一个）。 */
    fun vehiclesOf(subset: List<StoredRow>): Int = LogisticsAggregate.vehicleCount(subset, basis)

    fun piecesOf(subset: List<StoredRow>): Int? = LogisticsAggregate.piecesTotal(subset)

    /** 已装那部分的车点名（明细行要用；`PLATE`／`TRIP` 报车牌，默认 `ORDER` 口径报配货单号）。 */
    fun loadedNames(): List<String> = LogisticsAggregate.namesOf(loaded, basis)

    fun pendingNames(): List<String> = LogisticsAggregate.namesOf(pending, basis)
}

/** 聚合判据（纯函数，JVM 直测：三档口径、降级、可疑自检都在这）。 */
object LogisticsAggregate {

    /** 一行算不算「范围」命中：方向优先，没有方向列时用卸点（契约保证至少有一列）。 */
    fun scopeOf(row: StoredRow): String = row.direction.ifEmpty { row.unload }

    /** 口径下的车数。 */
    fun vehicleCount(rows: List<StoredRow>, basis: LogisticsBasis): Int = when (basis) {
        // 每台车都能被数到才算车：那一格没填的行不发明一辆「未知车」，它们进脚注（[plateUnknown]/[orderUnknown]）
        LogisticsBasis.PLATE -> rows.flatMap { it.plates() }.distinct().size
        // §1.8.1 冻结：「车」是**配货单**那一列，不是运单号。按运单号数会把 9 张单子报成 40 车
        LogisticsBasis.ORDER -> rows.map { it.orderNo }.filter { it.isNotEmpty() }.distinct().size
        LogisticsBasis.TRIP -> rows.flatMap { r -> r.plates().map { p -> "$p|${scopeOf(r)}" } }.distinct().size
    }

    /** 车数里「车牌没填」的单（`PLATE`／`TRIP` 口径的脚注：这几单没法归到任何一台车上）。 */
    fun plateUnknown(rows: List<StoredRow>): List<StoredRow> = rows.filter { it.plates().isEmpty() }

    /** 车数里「配货单号没填」的单（默认 `ORDER` 口径的脚注）。 */
    fun orderUnknown(rows: List<StoredRow>): List<StoredRow> = rows.filter { it.orderNo.isEmpty() }

    /** 这一档口径数车靠的是哪一列（缺了这列就不能数车——降级判断的唯一定义处）。 */
    fun vehicleField(basis: LogisticsBasis): LogisticsField =
        if (basis == LogisticsBasis.ORDER) LogisticsField.ORDER_NO else LogisticsField.PLATE

    /**
     * 件数合计：只有**有数字的行**参与，一行都没有 ⇒ `null`（这一维没说），不是 0。
     *
     * SQL 里用 `SUM(pieces)` 会得到 NULL（SQLite 对全 NULL 组的规矩），这里在内存里算，
     * 保持同一个「全空就是没说」的语义（主库那边 NULL 不进聚合的写法是 93 批立的例）。
     */
    fun piecesTotal(rows: List<StoredRow>): Int? {
        val known = rows.mapNotNull { it.pieces }
        return if (known.isEmpty()) null else known.sum()
    }

    /**
     * 点名用的标识：点名必须与 [vehicleCount] 用**同一列**，否则「2 车」后面会跟着 5 个号。
     *
     * `PLATE`／`TRIP` 报车牌，默认 [LogisticsBasis.ORDER] 报配货单号（§1.8 点名的四列原样显示列之一）。
     * 单号的点名另有出处：那一档不能数车时（缺列降级），答案层直接取 [StoredRow.waybill]。
     */
    fun namesOf(rows: List<StoredRow>, basis: LogisticsBasis): List<String> = when (basis) {
        LogisticsBasis.ORDER -> rows.map { it.orderNo }.filter { it.isNotEmpty() }.distinct()
        LogisticsBasis.PLATE -> rows.flatMap { it.plates() }.distinct()
        LogisticsBasis.TRIP -> rows.flatMap { r -> r.plates().map { p -> "$p·${scopeOf(r)}" } }.distinct()
    }

    /**
     * 全量聚合（查询层与播报层共用这一条路径，两处的数必须是同一个算法）。
     *
     * 状态分档只看**生效状态**（人勾过的盖过表里的，见 [StoredRow.effectiveStatus]）：
     * 她手动勾了「已装」而表里还写「待发」，那是人对现实的最新陈述，不是数据不一致。
     */
    fun aggregate(rows: List<StoredRow>, basis: LogisticsBasis): Aggregate {
        val loaded = ArrayList<StoredRow>()
        val pending = ArrayList<StoredRow>()
        val shortShip = ArrayList<StoredRow>()
        val statusUnknown = ArrayList<StoredRow>()
        rows.forEach { r ->
            when (r.effectiveStatus()) {
                LogisticsStatus.LOADED, LogisticsStatus.SENT -> loaded += r
                LogisticsStatus.PENDING -> pending += r
                LogisticsStatus.SHORT -> shortShip += r
                LogisticsStatus.UNKNOWN -> statusUnknown += r
            }
        }
        val absent = HashSet<LogisticsField>()
        rows.forEach { absent += it.missingFields() }
        val vehicles = vehicleCount(rows, basis)
        return Aggregate(
            rows = rows,
            orders = rows.size,
            vehicles = vehicles,
            basis = basis,
            piecesTotal = piecesTotal(rows),
            loaded = loaded,
            pending = pending,
            shortShip = shortShip,
            statusUnknown = statusUnknown,
            piecesUnknown = rows.filter { it.pieces == null },
            absent = absent,
            suspicious = vehicles > rows.size,
        )
    }
}
