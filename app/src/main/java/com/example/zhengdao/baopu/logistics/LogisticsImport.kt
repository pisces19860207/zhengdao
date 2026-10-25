// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 一份导出表 → 当日表里的行（编排层）。
//
// ## 分成两半是判据要求，不是洁癖
// - [plan]：**纯函数**（进文件名＋字节＋时间戳，出「准备记什么」或「拒读的一句话」）。
//   派工单 §5 的负例 1a/1b/2/4/5/6 全打在这一层，本机没有 SQLite 也照样能钉红；
// - [runInto]：把 [plan] 的结论交给 [LogisticsStore]，只有这一格碰库。
//   真机上 `importFile` ＝ 读文件字节 + plan + runInto，三步之外没有别的路径。
//
// ## 「一天」按数据时间归，不按今天归
// `day` 取的是**导出时间那天**，不是手机日历的今天。她 00:30 补导昨天的配货单，
// 那张表就该待昨天，不该混进「今天还有哪些没发」——把昨天的单子报成今天的待发，
// 会让人白催一次司机，这比少报一单更坏。取不到任何可用时间戳时**拒读**（不猜一天）。
package com.example.zhengdao.baopu.logistics

import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.security.MessageDigest
import java.util.TimeZone

/** [plan] 通过的产物：这批行准备记进哪一天、带着哪些要告诉用户的话。 */
data class ImportPlan(
    val day: String,
    val fileLabel: String,
    val exportedAt: ExportedAt,
    val rows: List<StoredRow>,
    /** 这份表缺掉的可降级列（答案第 3 行的脚注与面板都要念它）。 */
    val absent: Set<LogisticsField>,
    /** 这份表里没被契约认领的列名（照读其余，但如实报出来）。 */
    val unclaimed: List<String>,
    val notes: List<String>,
)

/** 一次导入的结局。 */
sealed interface ImportReport {
    /** 拒读：[reason] 就是给用户的那句话（点名缺哪列／为什么认不出／怎么补救）。 */
    data class Refused(val fileLabel: String, val reason: String) : ImportReport

    /**
     * 读成了。[tally] 里 `failed > 0` 时**不许**报「导入成功」——
     * 内存判定永远全绿，只有落库那条路知道有没有写成。
     *
     * 「按车牌还是按单号数车」是**查询时**的口径（[LogisticsBasis]），不属于导入结论：
     * 同一份表换口径不该产生第二次导入，也不该让台账里存下一个口径数。
     */
    data class Done(val plan: ImportPlan, val tally: ImportTally) : ImportReport
}

/** 导入编排。 */
object LogisticsImport {

    /** 单个文件字节上限（比 xlsx 自己的上限略松一点点都不行：读之前就要能拦住）。 */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /**
     * 纯函数：文件名 + 字节 → 准备记什么，或者一句拒读的话。
     *
     * @param mtimeMillis 文件的 lastModified（第三档时间来源；测试里传固定数，不靠时钟）
     */
    fun parse(
        fileName: String,
        bytes: ByteArray,
        mtimeMillis: Long,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): PlanOutcome {
        if (bytes.isEmpty()) return PlanOutcome.Refused(fileName, "这个文件是空的（0 字节），没有可读的表。")
        if (bytes.size.toLong() > MAX_FILE_BYTES) {
            return PlanOutcome.Refused(fileName, "这个文件有 ${bytes.size / 1024 / 1024} MB，超过 $MAX_FILE_BYTES 的上限，先按天拆开再导。")
        }
        val grid = when {
            LogisticsPaths.isCsv(fileName) -> when (val r = LogisticsCsv.parseBytes(bytes)) {
                is ReadOutcome.Ok -> r.grid
                is ReadOutcome.Failed -> return PlanOutcome.Refused(fileName, r.reason)
            }
            LogisticsPaths.isXlsx(fileName) -> when (val r = LogisticsXlsx.parseBytes(bytes)) {
                is ReadOutcome.Ok -> r.grid
                is ReadOutcome.Failed -> return PlanOutcome.Refused(fileName, r.reason)
            }
            else -> return PlanOutcome.Refused(
                fileName,
                "只认 .csv 与 .xlsx（派工单 §1）。这个文件后缀是「${fileName.substringAfterLast('.', "")}」，没读。",
            )
        }
        val header = when (val h = LogisticsContract.resolve(grid.header)) {
            is HeaderOutcome.Rejected -> return PlanOutcome.Refused(fileName, h.reason)
            is HeaderOutcome.Ok -> h
        }
        val built = when (val b = LogisticsRows.build(grid, header)) {
            is RowBuild.Failed -> return PlanOutcome.Refused(fileName, b.reason)
            is RowBuild.Ok -> b
        }
        val at = LogisticsExportedAt.resolve(fileName, grid, mtimeMillis, nowMillis, zone)
        val millis = at.millis ?: return PlanOutcome.Refused(
            fileName,
            "这份表取不到可用时间：文件名里没有日期、表里没有标「导出时间」、文件时间也不可信。" +
                "不知道它算哪一天，所以一行都没记——请在文件名里写上日期，例如 配货单20261025.csv。",
        )
        val day = LogisticsExportedAt.dayKey(millis, zone)
        val missing = header.absentSoft.joinToString("、") { it.label }
        val rows = built.rows.map { r ->
            StoredRow(
                day = day,
                waybill = r.waybill,
                orderNo = r.orderNo,
                direction = r.direction,
                unload = r.unload,
                goods = r.goods,
                pieces = r.pieces,
                piecesRaw = r.piecesRaw,
                weight = r.weight,
                volume = r.volume,
                driver = r.driver,
                phone = r.phone,
                plate = r.plates.joinToString("/"),
                status = r.status.name,
                statusRaw = r.statusRaw,
                statusUser = "",
                missing = missing,
                exportedAt = millis,
                exportedClock = at.hasClock,
                exportedSource = at.source.name,
                srcFile = fileName,
                srcHash = sha256(bytes),
                lineNo = r.lineNo,
            )
        }
        val notes = ArrayList<String>()
        notes += grid.notes
        notes += built.notes
        if (header.absentSoft.isNotEmpty()) notes += degradeNote(header.absentSoft)
        if (header.unclaimed.isNotEmpty()) {
            notes += "这几列本批不认识，没读进表：${header.unclaimed.joinToString("、")}——" +
                "要收进来请把它加进别名表（[LogisticsContract.ALIASES]），不改判据。"
        }
        if (at.source != ExportedAtSource.FILENAME) {
            notes += "数据时间取自${at.source.label}。"
        }
        return PlanOutcome.Ready(
            ImportPlan(day, fileName, at, rows, header.absentSoft, header.unclaimed, notes.distinct()),
        )
    }

    /**
     * 缺列的降级说法（派工单 2026-10-25 改单后的 B 档：缺了少报一维、必须明说）。
     *
     * 一句话里把「不显示 / 不参与计算 / 不参与告警」三件都写全：只说「缺件数列」
     * 会让人以为下面的总数是 0，而真实情况是「这一维根本没说」。
     */
    fun degradeNote(absent: Set<LogisticsField>): String {
        if (absent.isEmpty()) return ""
        val names = absent.joinToString("、") { "「${it.label}」" }
        return "这份表没有${names}列：这几维不显示、不参与任何合计，也不进告警名单——" +
            "答案里出现的数字只来自表里真有的列，绝不补 0。"
    }

    /** 落库（唯一碰库的一步）。 */
    fun runInto(store: LogisticsStore, plan: ImportPlan): ImportReport {
        val tally = store.importRows(plan.day, plan.rows)
        return ImportReport.Done(plan, tally)
    }

    /** 真机入口：读文件 → 判 → 落库。文件读不到（权限、被删）也走 [ImportReport.Refused]。 */
    fun importFile(
        store: LogisticsStore,
        file: File,
        nowMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): ImportReport {
        if (file.length() > MAX_FILE_BYTES) {
            return ImportReport.Refused(file.name, "这个文件有 ${file.length() / 1024 / 1024} MB，超过上限，先按天拆开再导。")
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            RunLog.log("物流表读取失败（$it）；文件名不入日志")
            return ImportReport.Refused(file.name, "这个文件打不开（${file.name}）：可能被别的程序占着，或者刚被移走。")
        }
        val outcome = parse(file.name, bytes, file.lastModified(), nowMillis, zone)
        return when (outcome) {
            is PlanOutcome.Refused -> ImportReport.Refused(outcome.fileLabel, outcome.reason)
            is PlanOutcome.Ready -> runInto(store, outcome.plan)
        }
    }

    /**
     * 把 inbox 里可读的表全读一遍，并给**被写过的每一天**补一份台账（派工单 §4：每天一份）。
     *
     * 一次调用只产出一句话：面板上她只看一行，看不了五段日志。所以每份表各一句、
     * 台账各一句，用换行拼起来——顺序就是文件名字序（[LogisticsPaths.readableInbox] 钉过），
     * 两次点同一个 inbox，出来的字必须逐行一样。
     *
     * 台账失败**不**改导入的结论：库已经写进去了，那是既成事实；台账没写成只是那份 md 旧了一版。
     */
    fun importInbox(store: LogisticsStore, workspace: File, nowMillis: Long, zone: TimeZone): String {
        val inbox = LogisticsPaths.inboxDirUnder(workspace)
        val files = LogisticsPaths.readableInbox(inbox)
        if (files.isEmpty()) {
            return "inbox 里没有可读的表：把导出的 .csv 或 .xlsx 放进 ${LogisticsPaths.displayPath(workspace, inbox)}"
        }
        val lines = ArrayList<String>(files.size + 2)
        val touched = LinkedHashSet<String>()
        files.forEach { f ->
            val r = importFile(store, f, nowMillis, zone)
            if (r is ImportReport.Done) touched += r.plan.day
            lines += summaryOf(r, zone, nowMillis)
        }
        touched.forEach { d ->
            val w = LogisticsLedger.writeForDay(workspace, store, d, zone)
            lines += LogisticsLedger.report(w)
        }
        return lines.joinToString("\n")
    }

    /** 一句导入结果（面板与台账共用；[ImportReport] 的两支各自成句）。 */
    fun summaryOf(report: ImportReport, zone: TimeZone, nowMillis: Long): String = when (report) {
        is ImportReport.Refused -> "没读入：${report.reason}"
        is ImportReport.Done -> {
            val t = report.tally
            val p = report.plan
            val head = "${p.rows.size} 单进了 ${p.day} 那天的表：新增 ${t.inserted}、没变 ${t.unchanged}、" +
                "被这份表更新 ${t.overwritten}"
            val tail = when {
                t.failed > 0 -> "；有 ${t.failed} 单没写进库（库打不开），先重开抱朴再导一次"
                t.kept > 0 -> "；${t.kept} 单库里那份更新，没被这份旧表覆盖"
                t.tied > 0 -> "；${t.tied} 单与库里的时间相同但值不同，按这次导入的记（两份表至少有一份没导全）"
                else -> ""
            }
            "$head$tail。${LogisticsExportedAt.lineOf(p.exportedAt, p.fileLabel, zone, nowMillis)}"
        }
    }

    /** 内容指纹（幂等判定用；sha-256 前 32 位十六进制）。 */
    fun sha256(bytes: ByteArray): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.substring(0, 32)
    }.getOrDefault("")
}

/** [LogisticsImport.parse] 的结局（与 [ImportReport] 分开：这支还没碰库，负例全打在这里）。 */
sealed interface PlanOutcome {
    data class Ready(val plan: ImportPlan) : PlanOutcome
    data class Refused(val fileLabel: String, val reason: String) : PlanOutcome
}
