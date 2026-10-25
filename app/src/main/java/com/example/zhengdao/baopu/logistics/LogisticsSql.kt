// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 当日本地表的**形状与判据**（纯文本 SQL ＋ 纯函数，零 Android）。
//
// 为什么形状要单独一个文件：JVM 里跑不了 `SQLiteOpenHelper`（`android.jar` 是 stub，离线缓存里
// 也没有 sqlite-jdbc），所以「表长什么样」「同一单该不该覆盖」必须能脱离数据库断言——
// 这条房型在 197 批的 `SearchSql` 与主库迁移测试里已经立着。[LogisticsDb] 只是把这里的语句执行一遍。
//
// ## 主键为什么是 (day, waybill)，不是 (day, waybill, src_hash)
// 派工单 §2 原话是「主键 day＋waybill＋src_file 哈希去重」。两种读法差别很大：
// - 主键带哈希 ⇒ 同一个单号从**两份不同导出**（上午一版、下午改了状态再导一版）会落进行两遍，
//   「北京 3 单」当场变 6 单，件数翻倍——这是把去重键选成了「文件身份」而不是「单据身份」；
// - 主键 (day, waybill) ＋ 哈希作为列 ⇒ 重复导入**天然不产生重复行**（负例③要的正是这条），
//   而「后一份表覆盖前一份」由 [decide] 按数据时间判，哈希只用来判「完全一样、什么都不必动」。
// 所以主键取后者，`src_hash` 留在列里参加判定——派工单要的三样（day、waybill、文件哈希）一个没少，
// 只是哈希换了岗位。这一条在交付报告里点名给蓝总管核对。
//
// ## 为什么行上还要背 `missing`
// 缺列是**文件级**的事，但一天的表可能来自两份文件（一份有件数、一份没有）。
// 把「这一行的来源表缺哪些可降级列」抄在行上，聚合时才能说出
// 「41 件里有 2 单来自缺件数列的表，没算进来」——不加这一列就只能报一个偏小的总数，那是静默错。
package com.example.zhengdao.baopu.logistics

/** 一行的库存形态（与 [ShipmentRow] 的差别：加了 day/src/exported_at 与用户勾选的覆盖）。 */
data class StoredRow(
    val day: String,
    val waybill: String,
    /** 配货单号（§1.8.1：车数按它去重；空串＝这一单没归到任何一张车上）。 */
    val orderNo: String,
    val direction: String,
    val unload: String,
    val goods: String,
    /** `null` ＝ 库里就是 NULL（未填或读不出），SQL 聚合天然不计（NULL 不进 SUM）。 */
    val pieces: Int?,
    val piecesRaw: String,
    val weight: String,
    /** 体积原文（§1.8 加的一维）：只存只展示，本批不做任何合计与换算。 */
    val volume: String,
    val driver: String,
    /** 司机手机号（§1.8 冻结：界面与台账原样显示、不许打码；日志与审计一律不写）。 */
    val phone: String,
    /** 并列车牌用 `/` 存回一列（读回时按同一分隔符拆，见 [platesOf]）。 */
    val plate: String,
    /** 表里的状态（枚举名）。 */
    val status: String,
    val statusRaw: String,
    /** 用户勾选的状态（枚举名，`""` ＝ 没勾过）。人记的盖过表里的。 */
    val statusUser: String,
    /** 这一行的来源表缺掉的可降级列名（`、` 分隔，可能为空）。 */
    val missing: String,
    val exportedAt: Long,
    /** 时间戳里到底有没有钟点（只有日期时答案写日期，不写 00:00）。 */
    val exportedClock: Boolean,
    /** 这个时间是从哪条路取来的（[ExportedAtSource] 的名字；界面明示用）。 */
    val exportedSource: String,
    val srcFile: String,
    val srcHash: String,
    val lineNo: Int,
) {
    /** 这一行的数据时间（同一份表的每行都相同，取任意一行即得）。 */
    fun exported(): ExportedAt = ExportedAt(
        exportedAt.takeIf { it != 0L },
        runCatching { ExportedAtSource.valueOf(exportedSource) }.getOrDefault(ExportedAtSource.NONE),
        exportedClock,
    )

    /** 生效状态：人勾过的优先（派工单 §2「由用户勾选或播报时按单子比对得出」）。 */
    fun effectiveStatus(): LogisticsStatus =
        runCatching { LogisticsStatus.valueOf(statusUser) }.getOrNull()
            ?: runCatching { LogisticsStatus.valueOf(status) }.getOrNull()
            ?: LogisticsStatus.UNKNOWN

    fun plates(): List<String> = LogisticsRows.platesOf(plate)

    /** 来源表缺的列（[LogisticsField] 名，认不出的写法原样留着报）。 */
    fun missingFields(): Set<LogisticsField> = missing.split('、')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { n -> LogisticsField.entries.firstOrNull { it.label == n } }
        .toSet()
}

/** 一条单子这次导入该怎么处置。 */
enum class RowAction {
    /** 库里没有 ⇒ 记一行。 */
    INSERT,

    /** 库里已有、而且每个字段都一样 ⇒ 什么都不动（负例③「导入两次不重复」走这条）。 */
    SKIP,

    /** 数据时间不比库里新、值又不同 ⇒ 保住库里的旧行，但这**必须报出来**（不许静默不覆盖）。 */
    KEEP,

    /** 这份表更新 ⇒ 覆盖库里那行（用户勾选 [StoredRow.statusUser] 不动）。 */
    UPDATE,
}

/**
 * 一次导入的处置统计（给报告、面板与台账用的「这次到底发生了什么」）。
 *
 * [kept] 与 [overwritten] 是同一件事的两个方向，分开计数是为了能把话说明白：
 * 「12 单里 9 单没变、2 单被这份表更新、1 单库里那份更新所以没被覆盖」。
 */
data class ImportTally(
    var inserted: Int = 0,
    var unchanged: Int = 0,
    var overwritten: Int = 0,
    var kept: Int = 0,
    var tied: Int = 0,

    /**
     * 判据要写、但**库里没写成**的行数（打不开库、SQL 失败）。
     *
     * 单列一条是因为「导入成功」这句话最坏的用法是数出来的：内存里的判定永远全绿，
     * 只有落库那条路才知道有没有写成。这一格非 0 ⇒ 面板必须写「N 单没进表」，不许报成功。
     */
    var failed: Int = 0,
) {
    val total: Int get() = inserted + unchanged + overwritten + kept + tied
}

/** 表形状、SQL 语句与处置判据。 */
object LogisticsSql {

    const val TABLE = "shipment"
    const val COL_DAY = "day"
    const val COL_WAYBILL = "waybill"
    const val COL_ORDER_NO = "order_no"

    /** 建库时用的版本（**只对 logistics.db 自己**；派工单红线是不动 `BaopuDb` 的版本号）。 */
    const val VERSION = 1

    /** 列名 → 声明（顺序就是 DDL 顺序，也是 `INSERT`/`UPDATE` 的绑定顺序）。 */
    val COLUMNS: List<Pair<String, String>> = listOf(
        COL_DAY to "TEXT NOT NULL",
        COL_WAYBILL to "TEXT NOT NULL",
        // §1.8.1 冻结：车数按这一列去重。它和 `waybill` 是两个身份——主键是运单身份，
        // 车数口径是装车身份，谁也不许替谁（替了就把 40 行压成 9 行）。
        COL_ORDER_NO to "TEXT NOT NULL DEFAULT ''",
        "direction" to "TEXT NOT NULL DEFAULT ''",
        "unload_point" to "TEXT NOT NULL DEFAULT ''",
        "goods" to "TEXT NOT NULL DEFAULT ''",
        "pieces" to "INTEGER",
        "pieces_raw" to "TEXT NOT NULL DEFAULT ''",
        "weight" to "TEXT NOT NULL DEFAULT ''",
        // §1.8 加的一维：只存原文、只展示，本批不做任何合计与换算（单位没法从格子本身证明）。
        "volume" to "TEXT NOT NULL DEFAULT ''",
        "driver" to "TEXT NOT NULL DEFAULT ''",
        // §1.8（2026-10-25 冻结）加的一维；派工单 §2 那份列名清单还没跟着改，
        // 差异写在交付报告里，不在这儿偷偷按清单办事。
        "phone" to "TEXT NOT NULL DEFAULT ''",
        "plate" to "TEXT NOT NULL DEFAULT ''",
        "status" to "TEXT NOT NULL",
        "status_raw" to "TEXT NOT NULL DEFAULT ''",
        "status_user" to "TEXT NOT NULL DEFAULT ''",
        "missing" to "TEXT NOT NULL DEFAULT ''",
        "exported_at" to "INTEGER NOT NULL DEFAULT 0",
        // 数据时间的**来历**也跟着存：只有日期时不许写「00:00 导出的」（那是把
        // 「只知道哪天」伪装成「知道几点」）；「取自文件名还是文件时间」是界面必须明示的一句话。
        "exported_clock" to "INTEGER NOT NULL DEFAULT 1",
        "exported_src" to "TEXT NOT NULL DEFAULT ''",
        "src_file" to "TEXT NOT NULL DEFAULT ''",
        "src_hash" to "TEXT NOT NULL DEFAULT ''",
        "line_no" to "INTEGER NOT NULL DEFAULT 0",
    )

    val columnNames: List<String> = COLUMNS.map { it.first }

    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            ${COLUMNS.joinToString(",\n            ") { "${it.first} ${it.second}" }},
            PRIMARY KEY ($COL_DAY, $COL_WAYBILL)
        )
        """.trimIndent(),
        // 「今天还有哪些没发」与「北京方向」都按天筛，这一条索引是那张面板的毫秒级前提
        "CREATE INDEX IF NOT EXISTS idx_shipment_day ON $TABLE ($COL_DAY)",
    )

    /** 整包重建（结构不认识时用；这张库可丢弃重建是它的立库前提，同 `SearchDb` 的处置）。 */
    val DROP_ALL: List<String> = listOf("DROP TABLE IF EXISTS $TABLE")

    private val PLACEHOLDERS = columnNames.indices.joinToString(", ") { "?" }

    val INSERT_SQL: String = "INSERT INTO $TABLE (${columnNames.joinToString(", ")}) VALUES ($PLACEHOLDERS)"

    /** 主键两列留在 WHERE，其余全部更新（`status_user` 不在 SET 里：人勾过的不被表覆盖）。 */
    val UPDATE_SQL: String = "UPDATE $TABLE SET " +
        columnNames.filterNot { it == COL_DAY || it == COL_WAYBILL || it == "status_user" }
            .joinToString(", ") { "$it = ?" } +
        " WHERE $COL_DAY = ? AND $COL_WAYBILL = ?"

    /** 读一行（判处置用）。 */
    val SELECT_ONE_SQL: String = "SELECT ${columnNames.joinToString(", ")} FROM $TABLE WHERE $COL_DAY = ? AND $COL_WAYBILL = ?"

    /** 读一整天（查询与播报都用它，一天几十到几百行，一次取全在内存里算）。 */
    val SELECT_DAY_SQL: String = "SELECT ${columnNames.joinToString(", ")} FROM $TABLE WHERE $COL_DAY = ? ORDER BY line_no"

    /** 有哪些天有表（面板的「数据时间」下拉与「今天没表」提示用）。 */
    val SELECT_DAYS_SQL: String = "SELECT $COL_DAY, COUNT(*), MAX(exported_at) FROM $TABLE GROUP BY $COL_DAY ORDER BY $COL_DAY DESC"

    /**
     * 处置判据（纯函数，可 JVM 直测）。
     *
     * 次序是判据本体：
     * 1. 库里没有 ⇒ [RowAction.INSERT]；
     * 2. 看得见的字**逐个相同**（[samePayload]）⇒ [RowAction.SKIP]——负例③「同一文件导入两次不重复」走这条；
     * 3. 这份表的数据时间**不比库里旧**（`>=`）⇒ [RowAction.UPDATE]。
     *    相等（`==`）的那一部分由调用方计入 [ImportTally.tied] 单独报出去：
     *    两份表同一时刻导出却写着不同的值，说明至少有一份没导全，取后到的那份是
     *    「以最后一次导入为准」这条唯一说得清的规矩（保住旧值会把已改对的表判成没改）；
     * 4. 这份表更旧 ⇒ [RowAction.KEEP]，并且**必须**在导入报告里点名：
     *    静默不覆盖会让人以为「重导过了就是新的」。
     *
     * 用户勾选（[StoredRow.statusUser]）永远不参与比较、也永远不被写回：人是最新事实的来源。
     */
    fun decide(existing: StoredRow?, incoming: StoredRow): RowAction {
        if (existing == null) return RowAction.INSERT
        if (samePayload(existing, incoming)) {
            // 完全一样：同一份文件重复导入 ⇒ 不动。字段一样但文件不同也走这条（等价内容，不动就是对的）。
            return RowAction.SKIP
        }
        if (incoming.exportedAt >= existing.exportedAt) return RowAction.UPDATE
        return RowAction.KEEP
    }

    /**
     * 逐字段比**看得见的字**（不比 day/waybill 身份，也不比 exported_at/src_file/line_no，更不含 status_user）。
     *
     * 不比身份三列是有意的：两份不同文件导出同样的内容，用户看得见的字一个都没变，
     * 报「3 单里 3 单没变」比报「3 单被更新」诚实。代价是证据仍停在**第一次记下**的那份表上
     * （行号与文件名不刷新）——这一点在面板的「来源」里写明，不装作指向最新那份。
     */
    fun samePayload(a: StoredRow, b: StoredRow): Boolean =
        a.orderNo == b.orderNo &&
            a.direction == b.direction && a.unload == b.unload && a.goods == b.goods &&
            a.pieces == b.pieces && a.piecesRaw == b.piecesRaw && a.weight == b.weight &&
            a.volume == b.volume &&
            a.driver == b.driver && a.phone == b.phone && a.plate == b.plate && a.status == b.status &&
            a.statusRaw == b.statusRaw && a.missing == b.missing

    /**
     * 缺列就补、在就不动（`CREATE TABLE IF NOT EXISTS` 对**已存在但少列**的旧库是不加列的，
     * 所以老用户的库要靠这一条把新列补上，而不是把库删了——里面有她勾过的状态）。
     *
     * 大小写不敏感（SQLite 的列名不区分大小写）；重复调用返回空 ⇒ 幂等。
     */
    fun alterFor(existingColumns: Set<String>): List<String> {
        val have = existingColumns.map { it.lowercase() }.toSet()
        return columnNames.filterNot { it.lowercase() in have }
            .map { "ALTER TABLE $TABLE ADD COLUMN $it ${COLUMNS.first { p -> p.first == it }.second}" }
    }

    /** 一天有多少行（播报「打开抱朴时提示过一次就不再提示」的比对基准）。 */
    val COUNT_DAY_SQL: String = "SELECT COUNT(*) FROM $TABLE WHERE $COL_DAY = ?"
}
