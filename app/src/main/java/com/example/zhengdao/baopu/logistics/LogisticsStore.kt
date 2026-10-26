// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 当本地表的读写面（接口 + SQLite 实现）。
//
// 接口存在只为一个理由：**查询与答话的判据必须在 JVM 里能跑**。
// 本机跑不了 `SQLiteOpenHelper`（`android.jar` 是 stub、离线缓存里没有 sqlite-jdbc），
// 所以「一句话查」和「固定三行」那两套判据对着 [LogisticsStore] 打，
// 落库那层只测形状（列名、幂等处置、fail-closed），不测 SQL 执行——真机执行留给验收。
//
// ## fail closed 的方向
// 库里出错一律吞成「没有行」，不吞成「有行但值为空」：
// 前者会得到一句「这份表里没有北京的记录」（诚实，用户知道要重导），
// 后者会得到一个 0 件（假数，负例 1b 钉的就是这条）。错误经 `RunLog` 让人看见，
// 但**只报条数与异常，绝不报单号/车牌/货名，也绝不报司机姓名与手机号**（§1.8 冻结那条：
// 「显示真实」与「日志脱敏」是两件事）——那些是她的真实单据与真人号码，日志文件会被外传。
package com.example.zhengdao.baopu.logistics

import android.content.Context
import android.database.Cursor
import com.example.zhengdao.core.RunLog

/** 某一天有表（面板的「数据时间」列表与「今天没表」提示都读它）。 */
data class DaySummary(val day: String, val rows: Int, val exportedAt: Long)

/** 当日表读写面。 */
interface LogisticsStore {

    /** 某一天的全部行（按来源行号序）。库打不开/没有这天 ⇒ 空列表，调用方按「没有表」处理。 */
    fun rowsFor(day: String): List<StoredRow>

    /** 哪些天有表（新的在前）。 */
    fun daySummaries(): List<DaySummary>

    /** 一批行落库（处置判据见 [LogisticsSql.decide]）。返回的 [ImportTally.failed] 非 0 ⇒ 不许报成功。 */
    fun importRows(day: String, incoming: List<StoredRow>): ImportTally

    /** 用户勾选状态（人记的盖过表里的）。`null` ＝ 取消勾选，回到表里的状态。 */
    fun setStatusUser(day: String, waybill: String, status: LogisticsStatus?): Boolean
}

/** 落库用的形状映射（列名 → 值）。与 [LogisticsSql.columnNames] 必须一一对应，由单测钉住。 */
internal fun storedRowValues(r: StoredRow): Map<String, Any?> = mapOf(
    LogisticsSql.COL_DAY to r.day,
    LogisticsSql.COL_WAYBILL to r.waybill,
    LogisticsSql.COL_ORDER_NO to r.orderNo,
    "direction" to r.direction,
    "unload_point" to r.unload,
    "goods" to r.goods,
    "pieces" to r.pieces,
    "pieces_raw" to r.piecesRaw,
    "weight" to r.weight,
    "volume" to r.volume,
    "driver" to r.driver,
    "phone" to r.phone,
    "plate" to r.plate,
    "status" to r.status,
    "status_raw" to r.statusRaw,
    "status_user" to r.statusUser,
    "missing" to r.missing,
    "exported_at" to r.exportedAt,
    "exported_clock" to (if (r.exportedClock) 1 else 0),
    "exported_src" to r.exportedSource,
    "src_file" to r.srcFile,
    "src_hash" to r.srcHash,
    "line_no" to r.lineNo,
)

/** `logistics.db` 的实现（派工单 §2：独立库、不动 `BaopuDb`）。 */
internal class SqlLogisticsStore(context: Context) : LogisticsStore {

    private val db = LogisticsDb(context.applicationContext)

    override fun rowsFor(day: String): List<StoredRow> = runCatching {
        db.readable().rawQuery(LogisticsSql.SELECT_DAY_SQL, arrayOf(day)).use { c ->
            val out = ArrayList<StoredRow>(c.count)
            while (c.moveToNext()) out += c.toStoredRow()
            out
        }
    }.getOrElse {
        RunLog.log("logistics.db 读当天失败（$it）；按「没有表」处理")
        emptyList()
    }

    override fun daySummaries(): List<DaySummary> = runCatching {
        db.readable().rawQuery(LogisticsSql.SELECT_DAYS_SQL, null).use { c ->
            val out = ArrayList<DaySummary>(c.count)
            while (c.moveToNext()) {
                out += DaySummary(
                    day = c.getString(0) ?: "",
                    rows = c.getInt(1),
                    exportedAt = c.getLong(2),
                )
            }
            out.filter { it.day.isNotEmpty() }
        }
    }.getOrElse {
        RunLog.log("logistics.db 读日期列表失败（$it）")
        emptyList()
    }

    /**
     * 一批行落库。
     *
     * 一次读全天的现有行进内存判定（而不是每行一次 `SELECT`）：一份 200 行的导出
     * 在手机上就是 200 次查询 vs 1 次，而「幂等」这句话要求判定次序稳定，读一次反而更稳。
     */
    override fun importRows(day: String, incoming: List<StoredRow>): ImportTally {
        val tally = ImportTally()
        if (incoming.isEmpty()) return tally
        val existing = rowsFor(day).associateBy { it.waybill }
        return runCatching {
            val writable = db.writable()
            writable.beginTransaction()
            try {
                incoming.forEach { row ->
                    val before = existing[row.waybill]
                    when (LogisticsSql.decide(before, row)) {
                        RowAction.INSERT -> if (exec(writable, LogisticsSql.INSERT_SQL, insertArgs(row))) tally.inserted++ else tally.failed++
                        RowAction.UPDATE -> {
                            if (exec(writable, LogisticsSql.UPDATE_SQL, updateArgs(row))) {
                                // 「时间相等但值不同」单列计数：这句话要报给用户，见 [LogisticsSql.decide] 第 3 条
                                if (before != null && row.exportedAt == before.exportedAt) tally.tied++ else tally.overwritten++
                            } else tally.failed++
                        }
                        RowAction.SKIP -> tally.unchanged++
                        RowAction.KEEP -> tally.kept++
                    }
                }
                writable.setTransactionSuccessful()
            } finally {
                runCatching { writable.endTransaction() }
            }
            tally
        }.getOrElse {
            RunLog.log("logistics.db 导入失败（$it）；已判定的 $tally 不落库")
            tally.also { it.failed += incoming.size - it.total }
        }
    }

    override fun setStatusUser(day: String, waybill: String, status: LogisticsStatus?): Boolean = runCatching {
        db.writable().execSQL(
            "UPDATE ${LogisticsSql.TABLE} SET status_user = ? WHERE ${LogisticsSql.COL_DAY} = ? AND ${LogisticsSql.COL_WAYBILL} = ?",
            arrayOf(status?.name ?: "", day, waybill),
        )
        true
    }.getOrDefault(false)

    private fun exec(writable: android.database.sqlite.SQLiteDatabase, sql: String, args: Array<Any?>): Boolean =
        runCatching { writable.execSQL(sql, args) }.isSuccess

    private fun insertArgs(row: StoredRow): Array<Any?> {
        val values = storedRowValues(row)
        return LogisticsSql.columnNames.map { values[it] }.toTypedArray()
    }

    /**
     * UPDATE 的参数：SET 的列 + WHERE 的两列，次序与 [LogisticsSql.UPDATE_SQL] 一致。
     *
     * 用 ArrayList 逐格追加而不用 `a + b`：`Array<Any?>` 遇 `Array<Any?>` 时 `plus` 有两个
     * 候选重载（`T.plus` 与 `Array<T>.plus`），编译器判不了歧义。
     */
    private fun updateArgs(row: StoredRow): Array<Any?> {
        val values = storedRowValues(row)
        val skip = setOf(LogisticsSql.COL_DAY, LogisticsSql.COL_WAYBILL, "status_user")
        val out = ArrayList<Any?>(LogisticsSql.columnNames.size)
        LogisticsSql.columnNames.filterNot { it in skip }.forEach { out += values[it] }
        out += row.day
        out += row.waybill
        return out.toTypedArray()
    }

    private companion object {
        /** 游标 → 行：列名全部按名字取，位置变了也不至于错位填成另一个字段。 */
        fun Cursor.toStoredRow(): StoredRow {
            fun text(col: String): String = getString(index(col)) ?: ""
            fun intOrNull(col: String): Int? = if (isNull(index(col))) null else getInt(index(col))
            return StoredRow(
                day = text(LogisticsSql.COL_DAY),
                waybill = text(LogisticsSql.COL_WAYBILL),
                orderNo = text(LogisticsSql.COL_ORDER_NO),
                direction = text("direction"),
                unload = text("unload_point"),
                goods = text("goods"),
                pieces = intOrNull("pieces"),
                piecesRaw = text("pieces_raw"),
                weight = text("weight"),
                volume = text("volume"),
                driver = text("driver"),
                phone = text("phone"),
                plate = text("plate"),
                status = text("status"),
                statusRaw = text("status_raw"),
                statusUser = text("status_user"),
                missing = text("missing"),
                exportedAt = if (isNull(index("exported_at"))) 0L else getLong(index("exported_at")),
                exportedClock = getInt(index("exported_clock")) != 0,
                exportedSource = text("exported_src"),
                srcFile = text("src_file"),
                srcHash = text("src_hash"),
                lineNo = if (isNull(index("line_no"))) -1 else getInt(index("line_no")),
            )
        }

        private fun Cursor.index(col: String): Int = getColumnIndexOrThrow(col)
    }
}
