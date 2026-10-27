// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百五十二批 · 旧版 Excel（`.xls` / BIFF）→ UTF-8 CSV —— **只加一条读取分支**。
//
// 起因：F9 物流系统的导出只肯出 `.xls`（BIFF），而抱朴的表格通道只认 `.xlsx / .csv / .tsv`
// ⇒ 导出来的表在抱朴里**根本看不见**。派工单定的做法：**不许硬读** —— 用 jxl 读第一个工作表、
// 写成 UTF-8 CSV 落在工作区的缓存目录，再**走已经存在的那条 CSV 通道**。
// 红线：xlsx / csv / tsv 的既有路径一个字都不改（本文件甚至不 import `SheetReader`）。
//
// ## 四条硬口径（派工单 §1 / §2）
//
// 1. **只转第一张**：多工作表默认取第一张，提示里写「另有 K 个工作表未读」；
// 2. **日期**：DateCell 一律格成 `yyyy-MM-dd HH:mm`，整点只写 `yyyy-MM-dd`。
//    ⚠️ 时区是这里唯一的坑，实测（真表 `到货库存.xls` r2c6）：
//      `cell.getContents()` = `2026-10-24 09:36:00`
//      `((DateCell) cell).getDate()` 按**本地时区**格式化 = `2026-10-24 17:36`（本机 Asia/Hong_Kong）
//      `((DateCell) cell).getDate()` 按 **UTC** 格式化      = `2026-10-24 09:36` ✔
//    ⇒ 必须按 **UTC** 格式化，才是 Excel 里那个墙上时间。按本地时区格式化比漏出 `46319.4`
//    更糟：它看起来是个正常日期，只是悄悄偏了 8 小时，没人会发现。
// 3. **上限**：单文件 ≤ 50MB、≤ 20 万行、转换后 CSV ≤ 50MB；超了**拒绝并给一句能动手的话**，
//    不许 OOM、不许卡主线程；
// 4. **缓存**：源 `.xls` 的 mtime + size 一变就重转；没变就直接复用上一份 CSV。
//
// ## 负例在这里怎么堵
//
// - 「把 .xls 当已支持格式直接读 ⇒ 乱码」：`.xls` 只在**本文件**被认领；`SheetReader` 那条
//   链子永远收不到它（扩展名不是 xlsx/csv/txt ⇒ Unsupported，不会被当文本灌进上下文）；
// - 「转换后列错位」：每一行都按 **`sheet.columns` 这个统一宽度**补齐。jxl 的 `getRow()` 只
//   给到该行最后一个非空格，谁按行长度写谁就把中间空列挤掉了 —— 那才是错位；
// - 「日期仍是序列号」：见上面第 2 条；
// - 「失败静默」：四种失败各自一句能动手的话，见 [XlsFailure]；
// - 「大文件 OOM」：转换是**流式写**（一行一行进 `BufferedWriter`，不拼一个大 String），
//   且**先看大小/行数再打开**，写的过程中还盯着已写字节数；
// - 「改了既有通道」：本文件的产出只是「一个 UTF-8 的 .csv 文件」，下游照旧。
package com.example.zhengdao.baopu.table

import jxl.Cell
import jxl.CellType
import jxl.DateCell
import jxl.Workbook
import jxl.WorkbookSettings
import jxl.read.biff.BiffException
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 一张 `.xls` 转出来的那一段说明（给用户看的那句话由 [sentence] 产出）。 */
data class XlsNotice(
    /** 源 `.xls` 在登记簿里的相对路径（**不念绝对路径**——绝对路径是隐私面）。 */
    val source: String,
    /** 转成 CSV 的行数（就是源表第一张工作表那几行，没裁剪）。 */
    val rows: Int,
    /** 列数（源表第一张工作表的宽度）。 */
    val columns: Int,
    /** 还有几张工作表没读（多表只取第一张）。 */
    val extraSheets: Int,
) {
    // 刻意**不存**缓存 CSV 的路径：它由 `cacheFile(cacheDir, src)` 算得出来，
    // 存一份绝对路径进 registry.json 等于把用户的目录名写进一份可能被备份的文件里
    // （出处只念相对路径是 232 就定的口径）。

    /** 「已把「物流.xls」自动转成 CSV（94 行 × 28 列）；另有 2 个工作表未读」。 */
    fun sentence(): String = buildString {
        append("已把「").append(source).append("」自动转成 CSV（")
            .append(rows).append(" 行 × ").append(columns).append(" 列）")
        if (extraSheets > 0) append("；另有 ").append(extraSheets).append(" 个工作表未读")
    }
}

/** 一次转换的结果（成功才给得出）。 */
data class XlsConverted(
    val source: File,
    /** 落好的 UTF-8 CSV。 */
    val csv: File,
    /** 第一张工作表的名字（当 sheet 名用，出处要念它）。 */
    val sheet: String,
    /** 全部工作表名单（多表时用来报「另有 K 张未读」）。 */
    val sheets: List<String>,
    val rows: Int,
    val columns: Int,
    /** true ＝ 这次没真转，命中的缓存。 */
    val cached: Boolean,
)

/** 「MB」这件事只有一处口径（报错里的数字与上限必须是同一把尺子）。 */
private const val MB_BYTES = 1024L * 1024L

/**
 * 读不动的四种（派工单：失败必须**可操作**，不许只抛一个 `Exception`，更不许跳过）。
 *
 * 每种 [action] 都是一句「用户下一步该干什么」的话，不是错误码。
 */
sealed class XlsFailure(val action: String) : Exception(action) {

    /** 源文件超过 [XlsCompat.MAX_FILE_BYTES]。 */
    class TooBig(bytes: Long) : XlsFailure(
        "这个文件有 ${bytes / MB_BYTES}MB，超过 ${XlsCompat.MAX_FILE_BYTES / MB_BYTES}MB 上限" +
            "——请按日期拆成几份再放进工作区",
    )

    /** 第一张工作表超过 [limit] 行（生产上是 [XlsCompat.MAX_ROWS]）。 */
    class TooManyRows(rows: Int, limit: Int) : XlsFailure(
        "第一张表有 $rows 行，超过 $limit 行上限——请拆成几份再放进工作区",
    )

    /** 转换后 CSV 超过 [XlsCompat.MAX_CSV_BYTES]（行数够、内容太肥 ⇒ 下游读它会撑爆内存）。 */
    class TooBigAfter(bytes: Long) : XlsFailure(
        "转成 CSV 后有 ${bytes / MB_BYTES}MB，超过 ${XlsCompat.MAX_CSV_BYTES / MB_BYTES}MB 上限" +
            "——请按行拆成几份再放进工作区",
    )

    /** 加密工作簿（BIFF 里的 Password/Encrypted 记录）。 */
    data object Encrypted : XlsFailure(
        "这个文件加了密，我读不了——请在电脑上另存成「不加密」的 .xls，或者直接存成 .csv",
    )

    /** 截断／损坏／压根不是 BIFF。 */
    class Broken(why: String) : XlsFailure(
        "读不动（$why）——请重新导出一份完整的 .xls；" +
            "若它是 .xlsx 改名的，请把后缀改回 .xlsx",
    )

}

/**
 * 252：转换的**可视状态**（派工单 §2「期间给『正在转换』的可视状态」）。
 *
 * 转换本身跑在调用方线程上，而登记簿刷新是由 `table_query` 工具发起的 —— 工具在
 * `zd-baopu-run` 那个守护线程里跑（`RunEngine`），**不是主线程**；这里只负责把「正在转」
 * 这件事**说出去**，让面板/日志能读到，不负责调度。
 */
object XlsProgress {

    @Volatile
    var active: Boolean = false
        private set

    /** 正在转的那个文件名（[active] 为 false 时是空串）。 */
    @Volatile
    var name: String = ""
        private set

    internal fun begin(what: String) {
        name = what
        active = true
    }

    internal fun end() {
        active = false
        name = ""
    }
}

/**
 * 旧版 Excel（`.xls` / BIFF）→ UTF-8 CSV。
 *
 * 单一职责：**认格式、转、管缓存**。转完之后它只是一个 `.csv` 文件，后面全交给既有通道。
 */
object XlsCompat {

    /** 单文件上限（派工单 §2）。 */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /** 行数上限（派工单 §2）。 */
    const val MAX_ROWS = 200_000

    /** 转换后 CSV 的上限（**派工单没写，是本文件自己加的闸门**：见负例「大文件 OOM」）。 */
    const val MAX_CSV_BYTES = 50L * 1024 * 1024

    /** 缓存目录名（`<工作区>/抱朴记忆/表格/缓存/`）。 */
    const val DIR_CACHE = "缓存"

    /** 缓存文件的后缀（`<原名>-<hash8>.csv`）与它的元信息后缀。 */
    private const val META_SUFFIX = ".meta"

    /**
     * 日期两把格式（**UTC**：见文件头第 2 条）。
     *
     * 用 `java.time.DateTimeFormatter` 而不是 `SimpleDateFormat`：① 后者不是线程安全的，
     * 转表这种「一次几万格」的活儿不能每格新建、也不能共享一个可变实例；
     * ② 主源码里出现 `SimpleDateFormat(` 会被 `TimeFormatsTest` 的源码级门禁判红
     * （B-补-24：时间戳格式化的唯一真源是 `:core` 的 `TimeFormats`——它用的是系统默认时区，
     * 而这里必须是 UTC，所以本文件自己持两个 **不可变** 的 formatter，不新增 `TimeFormats` 的条目）。
     */
    private val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US).withZone(ZoneOffset.UTC)

    private val DATE_ONLY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(ZoneOffset.UTC)

    /** 本次进程**真转**了几次（测试拿它钉「重跑命中缓存不重复转」）。 */
    @Volatile
    var convertCount: Int = 0
        private set

    /** 测试用：把计数清零（每个用例一份干净账）。 */
    fun resetCountForTest() {
        convertCount = 0
    }

    /** 认 `.xls`（BIFF；**不认** `.xlsx`——那是另一个通道，本批一行都不碰）。 */
    fun isXls(name: String): Boolean = name.lowercase().endsWith(".xls")

    /** 缓存 CSV 的落点：`缓存/<原名>-<hash8>.csv`（hash8 只认**路径身份**，不掺 mtime）。 */
    fun cacheFile(cacheDir: File, src: File): File =
        File(cacheDir, "${src.nameWithoutExtension}-${hash8(src.absolutePath)}.csv")

    /**
     * 缓存是不是还新鲜：**源 mtime + size 都没变**才算数（派工单 §2）。
     *
     * 少一个都不行：只比 size 会漏掉「同长度改内容」，只比 mtime 会漏掉「拷贝过来 mtime 被重置」。
     */
    fun isFresh(csv: File, src: File): Boolean {
        if (!csv.isFile) return false
        val m = readMeta(csv) ?: return false
        return m.mtime == src.lastModified() && m.size == src.length()
    }

    /**
     * 转一张 `.xls`。
     *
     * @param src 源 `.xls`
     * @param cacheDir 缓存目录（`<工作区>/抱朴记忆/表格/缓存/`）
     * @param maxRows 行数上限（默认 [MAX_ROWS]；**只为让那条判据能被单测到**——
     *   BIFF 一张表最多 65536 行，jxl 连 20 万行的 `.xls` 都写不出来，真造不出超限的夹具）
     * @return 成功给 [XlsConverted]；失败给 [XlsFailure]（**每一种都有一句能动手的话**）
     */
    fun convert(src: File, cacheDir: File, maxRows: Int = MAX_ROWS): Result<XlsConverted> = runCatching {
        val size = src.length()
        if (size > MAX_FILE_BYTES) throw XlsFailure.TooBig(size)

        val out = cacheFile(cacheDir, src)
        if (isFresh(out, src)) {
            val m = readMeta(out) ?: throw XlsFailure.Broken("缓存的元信息读不出来")
            return@runCatching XlsConverted(
                source = src,
                csv = out,
                sheet = m.sheet,
                sheets = m.sheets,
                rows = m.rows,
                columns = m.cols,
                cached = true,
            )
        }

        XlsProgress.begin(src.name)
        try {
            convertNow(src, out, maxRows)
        } finally {
            XlsProgress.end()
        }
    }

    // ── 转换本体 ────────────────────────────────────────────────────────────

    /**
     * jxl 的英文报错 → 中文。
     *
     * **只译它自己说过的那几种，不认得的照原样带出**——把「corrupt」翻成「文件是坏的」
     * 是翻译，把它翻成「网络不好」就是猜。用户拿到的那句话里永远看得到 jxl 的原文。
     */
    private fun biffWhy(e: BiffException): String {
        val m = e.message ?: return "不是能认的 .xls"
        return when {
            m.contains("corrupt", ignoreCase = true) -> "文件是坏的：导出中断或落盘不全；jxl 原话=$m"
            m.contains("biff", ignoreCase = true) -> "不是 Excel 97–2003 那种 .xls；jxl 原话=$m"
            m.contains("OLE", ignoreCase = true) -> "不是 OLE 复合文档，多半压根不是 .xls；jxl 原话=$m"
            else -> m.take(80)
        }
    }

    private fun convertNow(src: File, out: File, maxRows: Int): XlsConverted {
        val settings = WorkbookSettings().apply {
            // jxl 内存吃紧时会自己调 System.gc()：在 Android 上那一下会让整条线程停住。
            // 我们宁可让它多吃点堆，也不要一次不可预测的停顿（真 OOM 由上面的上限挡住）。
            setGCDisabled(true)
            // jxl 自带的 SimpleLogger 默认往 System.out 写警告；转表这种高频动作不该刷屏。
            setSuppressWarnings(true)
        }
        val wb = try {
            Workbook.getWorkbook(src, settings)
        } catch (e: jxl.read.biff.PasswordException) {
            throw XlsFailure.Encrypted
        } catch (e: BiffException) {
            throw XlsFailure.Broken(biffWhy(e))
        } catch (e: java.io.IOException) {
            throw XlsFailure.Broken("读不出来：" + (e.message?.take(80) ?: "IO 失败"))
        } catch (e: RuntimeException) {
            // 解析**别人给过来的二进制**：jxl 对截断/畸形文件不保证都抛受检异常，
            // 越界与负长度这类 RuntimeException 在真机上一样会发生。吞掉它才是真静默，
            // 这里把它翻译成一句用户能动手的话（负例：「失败只抛 Exception」）。
            throw XlsFailure.Broken("文件结构不对：" + (e.message?.take(80) ?: e.javaClass.simpleName))
        }
        try {
            val names = wb.sheetNames.toList()
            if (names.isEmpty()) throw XlsFailure.Broken("这个文件里没有工作表")
            val first = wb.getSheet(0)
            val rows = first.rows
            val cols = first.columns
            if (rows > maxRows) throw XlsFailure.TooManyRows(rows, maxRows)

            writeCsv(first, out)
            writeMeta(out, Meta(src.lastModified(), src.length(), rows, cols, first.name, names))
            convertCount++
            return XlsConverted(
                source = src,
                csv = out,
                sheet = first.name,
                sheets = names,
                rows = rows,
                columns = cols,
                cached = false,
            )
        } finally {
            wb.close() // jxl 会开着 RandomAccessFile / 临时文件句柄，漏 close 就是漏 fd
        }
    }

    /**
     * 流式写 CSV（**不拼一个大 String**：20 万行 × 43 列拼起来就是几百 MB，那是负例 6 本人）。
     *
     * 先写 `.tmp` 再改名：转一半崩了不会留下一个「看着新鲜、其实是半截」的 CSV。
     *
     * @return 写出去的字节数
     */
    private fun writeCsv(sheet: jxl.Sheet, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        var bytes = 0L
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            for (r in 0 until sheet.rows) {
                val line = StringBuilder()
                for (c in 0 until sheet.columns) {
                    if (c > 0) line.append(',')
                    line.append(escape(textOf(sheet.getCell(c, r))))
                }
                w.write(line.toString())
                w.write("\r\n") // RFC 4180 §2.1：行结束是 CRLF
                bytes += line.length + 2
                if (bytes > MAX_CSV_BYTES) throw XlsFailure.TooBigAfter(bytes)
            }
        }
        if (out.isFile && !out.delete()) throw XlsFailure.Broken("旧的缓存删不掉")
        if (!tmp.renameTo(out)) {
            tmp.delete()
            throw XlsFailure.Broken("缓存写不进去")
        }
    }

    /** 一个单元格 → 一段文本（**日期在这里落定**，别处不许再动它）。 */
    private fun textOf(cell: Cell): String = when {
        // 用 `is DateCell` 而不是比对 CellType.DATE：DATE_FORMULA（公式算出来的日期）
        // 也实现 DateCell，按类型比会漏掉它 —— 漏掉就是「日期仍是序列号」。
        cell is DateCell -> {
            val t = cell.date.toInstant()
            val s = DATE_TIME.format(t)
            if (s.endsWith(" 00:00")) DATE_ONLY.format(t) else s
        }
        cell.type == CellType.EMPTY -> ""
        // #DIV/0! 这类错误值：它不是数，写进 CSV 会被当成字符串， downstream 算不动
        cell.type == CellType.ERROR || cell.type == CellType.FORMULA_ERROR -> ""
        else -> cell.contents.orEmpty()
    }

    /** RFC 4180 §2：含逗号/引号/CR/LF 的字段加引号，内部引号翻倍。 */
    internal fun escape(v: String): String =
        if (v.indexOfAny(charArrayOf(',', '"', '\r', '\n')) < 0) {
            v
        } else {
            "\"" + v.replace("\"", "\"\"") + "\""
        }

    // ── 缓存元信息 ──────────────────────────────────────────────────────────

    private data class Meta(
        val mtime: Long,
        val size: Long,
        val rows: Int,
        val cols: Int,
        val sheet: String,
        val sheets: List<String>,
    )

    private fun metaOf(csv: File): File = File(csv.parentFile, csv.name + META_SUFFIX)

    private fun readMeta(csv: File): Meta? {
        val raw = runCatching { metaOf(csv).readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val kv = raw.lineSequence().associate {
            val i = it.indexOf('=')
            if (i <= 0) "" to "" else it.substring(0, i) to it.substring(i + 1)
        }
        val mtime = kv["mtime"]?.toLongOrNull() ?: return null
        val size = kv["size"]?.toLongOrNull() ?: return null
        val rows = kv["rows"]?.toIntOrNull() ?: return null
        val cols = kv["cols"]?.toIntOrNull() ?: return null
        val sheet = kv["sheet"].orEmpty().ifEmpty { return null }
        val sheets = kv["sheets"].orEmpty().split('\u0001').filter { it.isNotEmpty() }
        return Meta(mtime, size, rows, cols, sheet, sheets.ifEmpty { listOf(sheet) })
    }

    private fun writeMeta(csv: File, m: Meta) {
        runCatching {
            metaOf(csv).writeText(
                buildString {
                    appendLine("mtime=${m.mtime}")
                    appendLine("size=${m.size}")
                    appendLine("rows=${m.rows}")
                    appendLine("cols=${m.cols}")
                    appendLine("sheet=${m.sheet}")
                    appendLine("sheets=${m.sheets.joinToString("\u0001")}")
                },
                Charsets.UTF_8,
            )
        }
    }

    /** 路径 → 8 位十六进制（只做**身份**指纹，不是安全用途 ⇒ 用 SHA-256 不用 MD5）。 */
    private fun hash8(path: String): String =
        MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
}
