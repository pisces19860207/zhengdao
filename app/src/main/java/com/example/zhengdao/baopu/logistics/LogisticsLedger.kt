// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 每日发货台账（派工单 §4：每天一份落 `<工作区>/产物/物流/YYYYMMDD-发货台账.md`，
// 含数据时间与来源文件）。
//
// ## 台账为什么是 Markdown 表格而不是散文
// 她拿这份文件是对账用的：哪个单号、往哪去、几件、哪台车、装没装、在源表的第几行。
// 散文读不出这些，图片更对不了账。所以形状固定 = 一列一件事，列名与契约 [LogisticsField.label] 同源，
// **不再另造一份字符串**（造了就会有「台账写重量、面板写计费重量」这种两套话）。
//
// ## 三行纪律只管答案，不管台账
// §3.5 的「固定三行」约束的是**问答**；台账是文件，长什么样由对账需要决定。
// 但同一条诚实规矩照样生效：字段空写「未填」不给 0、件数没读出来就不进合计、
// 数据时间取不到就写「取不到」而不是拿 `System.currentTimeMillis()` 顶。
//
// ## 手勾的状态要标出来
// 用户在面板勾过「已装」的单，台账里写成 `已装（手勾）`。
// 不标就会出现一件假话：表里明明写着「待发」，台账却说是「已装」——
// 而她第二天拿着台账去核对 T9，找不到「已装」这两个字，就会以为台账在编。
//
// ## 原子写
// 照仓库既有写法（`BaopuLongformStore` 的临时文件 + rename）：台账是**覆盖式**每天一份，
// 写一半被杀掉会留下半份表，那比没这份文件更坏（半份表里的合计是真数出来的错数）。
package com.example.zhengdao.baopu.logistics

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.util.TimeZone

/** 台账写的结局——没写成必须有一句能转述的话，不许静默。 */
sealed interface LedgerWrite {
    /** @param displayPath 给用户看的相对路径（[LogisticsPaths.displayPath]） */
    data class Written(val displayPath: String, val rows: Int, val overwritten: Boolean) : LedgerWrite

    data class Failed(val reason: String) : LedgerWrite
}

/**
 * 台账的形状与内容（[render] 是纯函数，JVM 直测；[write] 才碰文件与库）。
 */
object LogisticsLedger {

    /** 表格里固定出现的列（顺序即列序；台账只认这一种形状，改形状要连测试一起改）。 */
    private val COLUMNS: List<LogisticsField> = listOf(
        LogisticsField.WAYBILL,
        // §1.8（2026-10-25 冻结）：「配货单号、车牌、司机姓名、司机手机号这四列必须原样显示，
        // 不许打码」，台账属于「显示」这一面 ⇒ 四列全部进表，且照实写（文件里也不许出现手机号之外的
        // 加工：不脱敏、不改写）。红线是另一件事——日志与审计里不写姓名与手机号，台账不是日志。
        LogisticsField.ORDER_NO,
        LogisticsField.DIRECTION,
        LogisticsField.UNLOAD,
        LogisticsField.GOODS,
        LogisticsField.PIECES,
        LogisticsField.PLATE,
        LogisticsField.DRIVER,
        LogisticsField.PHONE,
        LogisticsField.STATUS,
    )

    /** `20261025` → `2026-10-25`（标题用；键本身仍是不带横杠的文件名形状）。 */
    fun dayTitle(dayKey: String): String =
        if (dayKey.length == 8) "${dayKey.substring(0, 4)}-${dayKey.substring(4, 6)}-${dayKey.substring(6, 8)}" else dayKey

    /**
     * 台账正文（纯函数）。
     *
     * @param rows 这一天库里的全部行（调用方按 `line_no` 次序取出，这里不重排——
     *   重排一次，「来源行」就和用户在 Excel 里数出来的行号对不上了）
     * @param absent 这些行的来源表**共同缺掉**的可降级列（进「本表没有的列」一行）
     * @param at 这一天的数据时间（最晚那份表的时间与来历）
     * @param sources 来源文件名（证据要指到文件，不能只说「表里」）
     */
    fun render(
        dayKey: String,
        rows: List<StoredRow>,
        absent: Set<LogisticsField>,
        at: ExportedAt,
        sources: List<String>,
        zone: TimeZone,
    ): String {
        val out = ArrayList<String>(rows.size + 14)
        out += "# ${dayTitle(dayKey)} 发货台账"
        out += ""
        out += "- 数据时间：${timeCell(at, zone)}"
        out += "- 来源文件：${if (sources.isEmpty()) "未填" else sources.joinToString("、")}"
        out += "- 共 ${rows.size} 单（已装 ${countOf(rows, LogisticsStatus.LOADED)}、待发 ${countOf(rows, LogisticsStatus.PENDING)}、" +
            "缺件 ${countOf(rows, LogisticsStatus.SHORT)}、状态未填 ${countOf(rows, LogisticsStatus.UNKNOWN)}）"
        out += "- 件数合计：${piecesCell(rows)}"
        if (absent.isNotEmpty()) {
            out += "- 本表没有的列：${absent.joinToString("、") { "「${it.label}」" }}——" +
                "这些列在下面的表里一律写「未填」，那是「这一维没说」，不是「有人没填」。"
        }
        out += "- 本文件由抱朴按导出的表生成，只在这一天的数据时间之前成立；之后的变化不在这份表里。"
        out += ""
        out += "| 来源 | ${COLUMNS.joinToString(" | ") { it.label }} |"
        out += "| --- | ${COLUMNS.joinToString(" | ") { "---" }} |"
        rows.forEach { r -> out += "| ${sourceCell(r)} | ${cellsOf(r, absent)} |" }
        out += ""
        return out.joinToString("\n")
    }

    /** 「来源」那一格：文件名 + 源表行号，两个都要（只有行号时，隔天两份表就分不清是谁的第 5 行）。 */
    private fun sourceCell(r: StoredRow): String {
        val file = r.srcFile.ifEmpty { "未填" }
        return if (r.lineNo > 0) "$file 第 ${r.lineNo} 行" else "$file 行号未填"
    }

    /** 一行的各列文本（缺列 / 空值都写「未填」，件数没读出来时把原文带上）。 */
    private fun cellsOf(r: StoredRow, absent: Set<LogisticsField>): String =
        COLUMNS.joinToString(" | ") { f -> cellOf(r, f, absent) }

    private fun cellOf(r: StoredRow, f: LogisticsField, absent: Set<LogisticsField>): String {
        if (f in absent) return "未填"
        return when (f) {
            LogisticsField.WAYBILL -> or(r.waybill)
            LogisticsField.ORDER_NO -> or(r.orderNo)
            LogisticsField.DIRECTION -> or(r.direction)
            LogisticsField.UNLOAD -> or(r.unload)
            LogisticsField.GOODS -> or(r.goods)
            LogisticsField.PIECES -> when {
                r.pieces != null -> r.pieces.toString()
                r.piecesRaw.isNotEmpty() -> "未填（原写「${esc(r.piecesRaw)}」）"
                else -> "未填"
            }
            LogisticsField.PLATE -> r.plates().joinToString("/").ifEmpty { "未填" }
            LogisticsField.DRIVER -> or(r.driver)
            LogisticsField.PHONE -> or(r.phone)
            LogisticsField.STATUS -> statusCell(r)
            // 重量与体积是「只显示、不参与任何计算」那两维（单位没法从格子里证明），所以不进台账表格；
            // 留在枚举里会撞这条分支，宁可让它编译期就要求补齐。
            LogisticsField.WEIGHT, LogisticsField.VOLUME -> "未填"
        }
    }

    private fun statusCell(r: StoredRow): String {
        val eff = r.effectiveStatus().label
        val byUser = r.statusUser.isNotEmpty() && runCatching { LogisticsStatus.valueOf(r.statusUser) }.isSuccess
        return if (byUser) "$eff（手勾）" else eff
    }

    private fun countOf(rows: List<StoredRow>, s: LogisticsStatus): Int =
        rows.count { it.effectiveStatus() == s }

    /**
     * 件数合计：一个数字都没有 ⇒ 「没有一件有数」，不写 0。
     *
     * 有的行有数、有的没数 ⇒ 「X 件（另有 N 单件数未填，没算进来）」。
     * 这句是把合计的**口径**一起交出去，否则对账的人会拿这个数去和 T9 的「全部件数」比，一比就差。
     */
    fun piecesCell(rows: List<StoredRow>): String {
        val total = LogisticsAggregate.piecesTotal(rows) ?: return "没有一件有数（件数这一维没读出来，不写 0）"
        val unknown = rows.count { it.pieces == null }
        return if (unknown == 0) "$total 件" else "$total 件（另有 $unknown 单件数未填，没算进来）"
    }

    private fun timeCell(at: ExportedAt, zone: TimeZone): String =
        if (at.millis == null) {
            "取不到（来源：${at.source.label}）——这份表的新旧没法判断"
        } else {
            val shown = if (at.hasClock) LogisticsExportedAt.display(at.millis, zone) else dayTitle(LogisticsExportedAt.dayKey(at.millis, zone))
            "$shown（取自${at.source.label}）"
        }

    private fun or(text: String): String = if (text.isEmpty()) "未填" else esc(text)

    /** 竖线与换行会把表格撑破：撑破了「这一列是件数」就不成立了，所以原样转义而不是删掉。 */
    private fun esc(text: String): String = text.replace("|", "\\|").replace("\n", "／")

    /**
     * 落盘（唯一碰文件的一步）。
     *
     * 覆盖式：一天一份，重新导入后再记一次就是最新的那份真相。
     * 目录建不出来／盘写不进去都只报一句，不重试、不删旧文件——旧台账至少是**上一版真相**。
     *
     * @param rows 行数由调用方给（写成功的条数要和库里对上，不能靠数文本）
     */
    fun write(workspace: File, dayKey: String, text: String, rows: Int): LedgerWrite {
        val file = LogisticsPaths.ledgerFileUnder(workspace, dayKey)
        val existed = file.isFile
        val tmp = File(file.parentFile, file.name + ".tmp")
        val out = runCatching {
            file.parentFile?.mkdirs()
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) error("rename 失败")
        }.exceptionOrNull()
        return if (out != null) {
            runCatching { if (tmp.isFile) tmp.delete() }
            LedgerWrite.Failed("台账没写成：${out.message}")
        } else {
            LedgerWrite.Written(LogisticsPaths.displayPath(workspace, file), rows, overwritten = existed)
        }
    }

    /**
     * 现读现写（**不碰 Context**：导入那一侧只有 `workspace` 这个 File）。
     *
     * 库里这一天没单子 ⇒ 不生成（写一份只有标题的空台账，看上去像「今天没发货」，
     * 而真实情况是「今天没导表」——这两件事必须区分，所以宁可不写）。
     */
    fun writeForDay(workspace: File, store: LogisticsStore, dayKey: String, zone: TimeZone): LedgerWrite {
        val rows = store.rowsFor(dayKey)
        if (rows.isEmpty()) return LedgerWrite.Failed("这一天库里没有单子，台账不生成——先导入导出的表。")
        val text = render(dayKey, rows, LogisticsAsk.absentOf(rows), LogisticsAsk.exportedAtOf(rows), LogisticsAsk.sourcesOf(rows), zone)
        return write(workspace, dayKey, text, rows.size)
    }

    /** 带 Context 的那一层（面板上的「重记台账」用）。 */
    fun writeForDay(context: Context, store: LogisticsStore, dayKey: String, zone: TimeZone): LedgerWrite {
        val workspace = runCatching { BaopuRuntime.workspaceRoot(context) }.getOrNull()
            ?: return LedgerWrite.Failed("工作区还没就绪（先让抱朴把目录建起来），台账没处落。")
        return writeForDay(workspace, store, dayKey, zone)
    }

    /**
     * 写完留痕（成功与否都要有字：静默失败是这条链上最难查的一种）。
     *
     * 返回的就是给用户看的那一句——面板与导入两侧共用，措辞只有这一处。
     * `[LedgerWrite.Failed].reason` 本身已是完整句子（「先导入导出的表」这种），所以这里不再套前缀，
     * 否则用户读到「台账没写成：台账没写成：」。
     */
    fun report(w: LedgerWrite): String {
        val line = when (w) {
            is LedgerWrite.Written -> "台账已记：${w.displayPath}（${w.rows} 单${if (w.overwritten) "，覆盖旧的一份" else ""}）"
            is LedgerWrite.Failed -> w.reason
        }
        RunLog.log("物流台账：${if (w is LedgerWrite.Failed) "没写成 " else ""}$line")
        return line
    }
}
