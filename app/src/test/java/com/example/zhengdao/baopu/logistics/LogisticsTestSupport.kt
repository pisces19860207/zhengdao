// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批测试的**共用地基**：假库、行构造器、真 CSV 字节、真 xlsx（zip）字节。
//
// ## 为什么假库要照抄处置判据而不是自己编一套
// [FakeLogisticsStore.importRows] 走的仍是 [LogisticsSql.decide] 那一条判据链，只是把
// SQLite 换成内存 Map。这样「同一份表导两次不重复」（负例③）打的是**真判据**；
// 假库里自创一套去重逻辑会让这条测试变成在给测试代码打分。
//
// ## 数据全是编的
// 单号 `10231…`、方向 `北京朝阳`、车牌 `冀A12345` 都是脱敏假数据（派工单 §7：真实单据不进仓库、不进测试）。
package com.example.zhengdao.baopu.logistics

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 固定区时：机器时区一改「今天算哪天」就飘，所以测试全用这一把。 */
internal val TZ: TimeZone = TimeZone.getTimeZone("GMT+08:00")

/** 2026-10-25 09:30（+08）——本批所有「今天」都指这一天。 */
internal fun millisOf(day: Int, hour: Int = 9, minute: Int = 30, month: Int = 10, year: Int = 2026): Long =
    Calendar.getInstance(TZ).apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

internal const val TODAY_KEY = "20261025"
internal val NOW: Long = millisOf(25)

/**
 * 十二列齐全时 T9 导出会长这样（顺序即表头顺序）。
 *
 * `配货单号`/`手机号`/`体积` 是 §1.8（2026-10-25 冻结）加上的三维。`配货单号` 必须**独立成列**：
 * 194 批的真实表里 40 行运单挂在 9 张配货单上，把它当成 `单号` 的别名会把 40 行压成 9 行。
 */
internal val FULL_HEADER =
    listOf("单号", "配货单号", "方向", "卸点", "货名", "件数", "重量", "体积", "司机", "手机号", "车牌", "状态")

/**
 * 脱敏假单子：三单两方向、**两张配货单**。
 *
 * 10231 与 10232 同属 PH101，而且同车同司机——§1.8 那句「每一张配货单对应一辆车、一个司机和
 * 司机手机号」就是这个形状。这是「按配货单去重」唯一能在数据上看出差别的地方：
 * 按运单数得到 3 单，按配货单数得到 2 车（194 批真实表是 40 单挂 9 张单子，同一个缩影）。
 * 其中一单没车牌、一单件数写的是「一批」。号码是编的号段。
 */
internal fun sampleCsv(): String = listOf(
    FULL_HEADER.joinToString(","),
    "10231,PH101,北京朝阳,三间房,钢材,12,3.5,4.2,王师傅,13800000001,冀A12345,已装",
    "10232,PH101,北京朝阳,四惠,家具,8,,1.5,王师傅,13800000001,冀A12345,待发",
    "10233,PH102,上海青浦,华新,钢材,一批,2.1,,张师傅,,,未填",
).joinToString("\r\n") + "\r\n"

/** CSV → 字节（默认 UTF-8；负例里的 GBK 走 [gbkBytes]）。 */
internal fun csvBytes(text: String, charset: Charset = Charsets.UTF_8): ByteArray = text.toByteArray(charset)

/** 中文 Windows 的 Excel「另存为 CSV」默认那把编码（GB18030 是 GBK 超集，字节形状一样）。 */
internal fun gbkBytes(text: String): ByteArray = text.toByteArray(Charset.forName("GB18030"))

/** 造一行库里读出来的单子（除单号外全部可省，省掉＝「表里没填」）。 */
internal fun storedRow(
    waybill: String,
    orderNo: String = "",
    direction: String = "",
    unload: String = "",
    goods: String = "",
    pieces: Int? = null,
    weight: String = "",
    volume: String = "",
    driver: String = "",
    phone: String = "",
    plate: String = "",
    status: LogisticsStatus = LogisticsStatus.UNKNOWN,
    statusUser: String = "",
    day: String = TODAY_KEY,
    exportedAt: Long = NOW,
    exportedClock: Boolean = true,
    exportedSource: String = ExportedAtSource.FILENAME.name,
    srcFile: String = "配货单20261025.csv",
    srcHash: String = "h-$waybill",
    lineNo: Int = 2,
    missing: String = "",
): StoredRow = StoredRow(
    day = day,
    waybill = waybill,
    orderNo = orderNo,
    direction = direction,
    unload = unload,
    goods = goods,
    pieces = pieces,
    piecesRaw = pieces?.toString() ?: "",
    weight = weight,
    volume = volume,
    driver = driver,
    phone = phone,
    plate = plate,
    status = status.name,
    statusRaw = status.label,
    statusUser = statusUser,
    missing = missing,
    exportedAt = exportedAt,
    exportedClock = exportedClock,
    exportedSource = exportedSource,
    srcFile = srcFile,
    srcHash = srcHash,
    lineNo = lineNo,
)

/**
 * 内存版 [LogisticsStore]（本机没有 Robolectric ⇒ `SQLiteOpenHelper` 在这套测试里起不来）。
 *
 * @param failWrites 模拟「库打不开」：处置统计要落进 [ImportTally.failed]，导入不许报成功
 */
internal class FakeLogisticsStore(private val failWrites: Boolean = false) : LogisticsStore {

    /** day → waybill → 行（顺序即写入顺序，[rowsFor] 再按来源行号排，与库里的 `ORDER BY line_no` 同形）。 */
    private val days = LinkedHashMap<String, MutableMap<String, StoredRow>>()

    /** 每次落库的调用次数（幂等那条判据要看「第二次有没有真写」）。 */
    var writeCount: Int = 0
        private set

    override fun rowsFor(day: String): List<StoredRow> =
        days[day]?.values?.sortedBy { it.lineNo } ?: emptyList()

    override fun daySummaries(): List<DaySummary> = days.entries
        .map { (day, rows) -> DaySummary(day, rows.size, rows.values.maxOf { it.exportedAt }) }
        .filter { it.day.isNotEmpty() }
        .sortedByDescending { it.day }

    override fun importRows(day: String, incoming: List<StoredRow>): ImportTally {
        val tally = ImportTally()
        if (incoming.isEmpty()) return tally
        if (failWrites) {
            tally.failed = incoming.size
            return tally
        }
        val bucket = days.getOrPut(day) { LinkedHashMap() }
        incoming.forEach { row ->
            val before = bucket[row.waybill]
            when (LogisticsSql.decide(before, row)) {
                RowAction.INSERT -> {
                    bucket[row.waybill] = row
                    writeCount++
                    tally.inserted++
                }
                RowAction.UPDATE -> {
                    // 人勾过的状态不被表覆盖（与 SqlLogisticsStore 的 UPDATE_SQL 不在 SET 里同一处置）
                    bucket[row.waybill] = row.copy(statusUser = before?.statusUser ?: "")
                    writeCount++
                    if (before != null && row.exportedAt == before.exportedAt) tally.tied++ else tally.overwritten++
                }
                RowAction.SKIP -> tally.unchanged++
                RowAction.KEEP -> tally.kept++
            }
        }
        return tally
    }

    override fun setStatusUser(day: String, waybill: String, status: LogisticsStatus?): Boolean {
        val bucket = days[day] ?: return false
        val row = bucket[waybill] ?: return false
        bucket[waybill] = row.copy(statusUser = status?.name ?: "")
        return true
    }

    /** 测试内部用：直接塞行，不走导入链（查询/答话那几条只想摆数据）。 */
    fun put(vararg rows: StoredRow) {
        rows.groupBy { it.day }.forEach { (day, list) ->
            val bucket = days.getOrPut(day) { LinkedHashMap() }
            list.forEach { bucket[it.waybill] = it }
        }
    }
}

/** 文件名里带今天日期的那份导出，直接落进假库（parse→runInto 全链，与面板点「读 inbox」同一条路）。 */
internal fun importCsvText(
    store: FakeLogisticsStore,
    text: String,
    fileName: String = "配货单$TODAY_KEY.csv",
    mtime: Long = NOW,
    now: Long = NOW,
): ImportReport {
    val bytes = csvBytes(text)
    return when (val outcome = LogisticsImport.parse(fileName, bytes, mtime, now, TZ)) {
        is PlanOutcome.Refused -> ImportReport.Refused(outcome.fileLabel, outcome.reason)
        is PlanOutcome.Ready -> LogisticsImport.runInto(store, outcome.plan)
    }
}

/** 同上，但喂 xlsx 字节（两条读取路共用一套断言）。 */
internal fun importXlsxBytes(
    store: FakeLogisticsStore,
    bytes: ByteArray,
    fileName: String = "配货单$TODAY_KEY.xlsx",
    mtime: Long = NOW,
    now: Long = NOW,
): ImportReport = when (val outcome = LogisticsImport.parse(fileName, bytes, mtime, now, TZ)) {
    is PlanOutcome.Refused -> ImportReport.Refused(outcome.fileLabel, outcome.reason)
    is PlanOutcome.Ready -> LogisticsImport.runInto(store, outcome.plan)
}

/**
 * 真 xlsx 字节（zip + OOXML），不是「假装是 xlsx 的字符串」。
 *
 * 只按 [LogisticsXlsx] 实际会读的四个部件生成：workbook 的 `<sheets>` 声明顺序、
 * workbook.xml.rels 的 Id→Target、sharedStrings、第一张 worksheet。
 * 字符串走 `t="s"` 下标（真实导出的样子），数字走裸 `<v>`。
 */
internal class XlsxSheet {
    /** row（1 起）→ col（0 起）→ 原文。 */
    val cells = LinkedHashMap<Int, LinkedHashMap<Int, String>>()

    fun put(row: Int, col: Int, text: String): XlsxSheet {
        cells.getOrPut(row) { LinkedHashMap() }[col] = text
        return this
    }

    fun putRow(row: Int, values: List<String>): XlsxSheet {
        values.forEachIndexed { i, v -> if (v.isNotEmpty()) put(row, i, v) }
        return this
    }
}

/** 一张表 → 一个 xlsx 包；[extraSheets] 里的页签只在 workbook 里声明（数据可以不给，测「只读第一张」用）。 */
internal fun xlsxBytes(main: XlsxSheet, extraSheetNames: List<String> = emptyList()): ByteArray {
    val shared = ArrayList<String>()
    fun sharedIndexOf(text: String): Int {
        val at = shared.indexOf(text)
        return if (at >= 0) at else shared.size.also { shared += text }
    }
    val numeric = Regex("""^-?\d+(\.\d+)?$""")
    val sheetXml = StringBuilder("<?xml version=\"1.0\"?><worksheet><sheetData>")
    main.cells.toSortedMap().forEach { (rowNo, cols) ->
        sheetXml.append("<row r=\"$rowNo\">")
        cols.toSortedMap().forEach { (col, value) ->
            val ref = "${colRef(col)}$rowNo"
            if (numeric.matches(value)) {
                sheetXml.append("<c r=\"$ref\"><v>$value</v></c>")
            } else {
                sheetXml.append("<c r=\"$ref\" t=\"s\"><v>${sharedIndexOf(value)}</v></c>")
            }
        }
        sheetXml.append("</row>")
    }
    sheetXml.append("</sheetData></worksheet>")

    val sst = StringBuilder("<?xml version=\"1.0\"?><sst>")
    shared.forEach { sst.append("<si><t xml:space=\"preserve\">").append(xmlText(it)).append("</t></si>") }
    sst.append("</sst>")

    val names = listOf("配货单") + extraSheetNames
    val sheetTags = names.mapIndexed { i, n -> "<sheet name=\"${xmlText(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }
        .joinToString("")
    val workbook = "<?xml version=\"1.0\"?><workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets>$sheetTags</sheets></workbook>"
    val rels = "<?xml version=\"1.0\"?><Relationships>" +
        names.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"sheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
        "</Relationships>"
    val contentTypes = "<?xml version=\"1.0\"?><Types>" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        names.indices.joinToString("") {
            "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        } +
        "</Types>"

    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun add(path: String, text: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        add("[Content_Types].xml", contentTypes)
        add("_rels/.rels", "<?xml version=\"1.0\"?><Relationships/>")
        add("xl/workbook.xml", workbook)
        add("xl/_rels/workbook.xml.rels", rels)
        add("xl/sharedStrings.xml", sst.toString())
        add("xl/worksheets/sheet1.xml", sheetXml.toString())
    }
    return out.toByteArray()
}

/** 十二列齐全的表 + 三行数据 → xlsx 字节（与 [sampleCsv] 同一份数据，两条读取路对照用）。 */
internal fun sampleXlsxBytes(): ByteArray {
    val sheet = XlsxSheet()
    sheet.putRow(1, FULL_HEADER)
    sheet.putRow(2, listOf("10231", "PH101", "北京朝阳", "三间房", "钢材", "12", "3.5", "4.2", "王师傅", "13800000001", "冀A12345", "已装"))
    sheet.putRow(3, listOf("10232", "PH101", "北京朝阳", "四惠", "家具", "8", "", "1.5", "王师傅", "13800000001", "冀A12345", "待发"))
    sheet.putRow(4, listOf("10233", "PH102", "上海青浦", "华新", "钢材", "一批", "2.1", "", "张师傅", "", "", "未填"))
    return xlsxBytes(sheet)
}

/** 手工摆一个 zip 包（测「缺 workbook」「rels 对不上」这类坏包，用不着真 Excel）。 */
internal fun zipOf(vararg parts: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        parts.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun colRef(col: Int): String {
    var n = col
    val sb = StringBuilder()
    do {
        sb.insert(0, ('A' + (n % 26)))
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

private fun xmlText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
