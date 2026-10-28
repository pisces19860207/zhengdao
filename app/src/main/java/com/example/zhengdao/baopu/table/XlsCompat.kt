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
// 3. **上限**：单文件 ≤ 50MB、第一张表 ≤ 65536 行（**BIFF 自己的格式上限**，不是我们另设的阀门）、
//    转换后 CSV ≤ 50MB；超了**拒绝并给一句能动手的话**，不许 OOM、不许卡主线程。
//    ⚠️ 这里曾经写过「20 万行上限」——那是**永远碰不到的假判据**：BIFF8 一张表最多 65536 行，
//    jxl 连写都写不出来（`RowsExceededException` 当场证明）。假判据比没有判据更坏：它看着很严格，
//    真出事时什么也拦不住，还会让人以为「20 万行以下都安全」。现在这条的真身份是**格式上限守卫**，
//    真话是「这文件的行数超过了 .xls 能装下的行数 ⇒ 它不是一张正常的 .xls，另存为 .xlsx 再导」。
// 4. **缓存**：源 `.xls` 的 mtime + size 一变就重转；没变就直接复用上一份 CSV。
//
// ## 缓存档位：**R1**（2026-10-28 蓝总管裁定，三条硬约束）
//
// R1 ＝ 可逆、只写用户自己的工作区、无外发。落点是固定的 `<工作区>/抱朴记忆/表格/缓存/`。
// 三条约束**是规格，不是注释**，改动它们等于改档位，要重新裁定：
//
// a) **路径固定，且不许进资料库/知识库索引**。资料库那边的东西是会被检索、被引用的；
//    缓存是派生物，进了索引就变成了「一份谁都能搜到的原始数据副本」。
//    本文件只认 [XlsCompat.DIR_CACHE] 这一个目录名，不提供任何改路径的入口；
//    `TableRegistry.walk()` 另有一道按路径身份的硬跳过（见那里的注释），不靠「恰好」不被扫到。
// b) **整表绝不进模型**：缓存 CSV 一个字都不发。进上下文的只有**查询结果**（几行几列、
//    聚合值、出处行号），这条路一直是 [TableQuery] 的既有口径，本批没有为 `.xls` 开任何例外。
// c) **生命周期**：源 `.xls` 被删、或 mtime/size 变了 ⇒ **扫描时顺手清理**旧缓存（[XlsCompat.sweep]，
//    惰性，不另起定时任务、不注册任何后台工作）。缓存是派生物：删掉不丢用户任何东西，下次问就重转。
//
// ⚠️ 另记一条（**本批不实现**，留给蓝总管定）：这是用户自己的物流数据（含姓名手机号），
//    缓存目录**不进备份、不进云同步**。当前代码里没有任何备份/同步逻辑会碰它，
//    但如果哪天加了「整个工作区打包上传」，这里就是那条必须先想清楚的边界。
//
// ## 负例在这里怎么堵
//
// - 「把 .xls 当已支持格式直接读 ⇒ 乱码」：`.xls` 只在**本文件**被认领；`SheetReader` 那条
//   链子永远收不到它（扩展名不是 xlsx/csv/txt ⇒ Unsupported，不会被当文本灌进上下文）；
// - 「转换后列错位」：每一行都按 **`sheet.columns` 这个统一宽度**补齐。jxl 的 `getRow()` 只
//   给到该行最后一个非空格，谁按行长度写谁就把中间空列挤掉了 —— 那才是错位；
// - 「日期仍是序列号」：见上面第 2 条；
// - 「失败静默」：**五种**失败各自一句能动手的话，见 [XlsFailure]；
// - 「大文件 OOM」：转换是**流式写**（一行一行进 `BufferedWriter`，不拼一个大 String），
//   且**先看大小/行数再打开**，写的过程中还盯着已写字节数；
// - 「缓存变成第二份原始数据」：路径固定进 [XlsCompat.DIR_CACHE]、按路径身份硬跳过、
//   源一变就清（见上面 R1 那三条）。
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
 * 读不动的五种（派工单：失败必须**可操作**，不许只抛一个 `Exception`，更不许跳过）。
 *
 * 每种 [action] 都是一句「用户下一步该干什么」的话，不是错误码。
 */
sealed class XlsFailure(val action: String) : Exception(action) {

    /** 源文件超过 [XlsCompat.MAX_FILE_BYTES]。 */
    class TooBig(bytes: Long) : XlsFailure(
        "这个文件有 ${bytes / MB_BYTES}MB，超过 ${XlsCompat.MAX_FILE_BYTES / MB_BYTES}MB 上限" +
            "——请按日期拆成几份再放进工作区",
    )

    /**
     * 第一张表超过 .xls 能装下的行数 ⇒ **这是文件本身的问题，不是我们设的阀门**。
     *
     * 措辞有意不提「上限」二字：用户看到的必须是「.xls 这个格式就到这儿了，另存为 .xlsx」，
     * 而不是「你的表太大，请拆开」——拆成两份 `.xls` 是白费力气，真正该做的是换格式。
     */
    class AboveBiffLimit(rows: Int, limit: Int) : XlsFailure(
        "这张表有 $rows 行，超过 .xls 的格式上限 $limit 行——这是 .xls 自己的限制，拆成几份也没用；" +
            "请在 Excel 里另存为 .xlsx 再放进工作区（.xlsx 没有这个限制）",
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

    /** 单文件上限（派工单 §2；这条是真闸门——50MB 的 BIFF 真能把堆吃穿）。 */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /**
     * **BIFF 自己的行数上限**（.xls 一张工作表最多 65536 行）——不是我们另设的阀门。
     *
     * 这里曾经是 `MAX_ROWS = 200_000`。删掉它的理由：BIFF8 装不下 200k 行，
     * 那条判据永远触发不了 ⇒ 是假判据。现在这个数只用来「认出一个装不下的文件」，
     * 报的话也是「另存为 .xlsx」而不是「拆成几份」。
     */
    const val BIFF_MAX_ROWS = 65_536

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

    // ── 缓存的生命周期（R1 约束 c：扫描时顺手清，不另起定时任务） ─────────────

    /** 我们自己的缓存产物长这样：`<原名>-<hash8>.csv`（外加它的 `.meta` / 写完前的 `.tmp`）。 */
    private val CACHE_CSV = Regex(""".+-[0-9a-f]{8}\.csv""")

    /** 是不是本文件写的缓存产物——**只有认得的才敢删**，`缓存/` 里用户自己放的文件一律不碰。 */
    private fun isCacheArtifact(name: String): Boolean {
        val base = when {
            name.endsWith(".csv$META_SUFFIX") -> name.removeSuffix(META_SUFFIX)
            name.endsWith(".csv.tmp") -> name.removeSuffix(".tmp")
            name.endsWith(".csv") -> name
            else -> return false
        }
        return CACHE_CSV.matches(base)
    }

    /**
     * 惰性清缓存（R1 约束 c）：**刷新时顺手做**，不注册后台任务、不占主线程之外的东西。
     *
     * 清两种：
     * 1. **源没了** —— `alive` 是本次扫描看到的 `.xls`。缓存文件名是「原名 + 源绝对路径的 hash8」，
     *    hash 不可逆，所以这里按**名字集合**判：不在集合里的产物就是这个工作区里已经没有源了；
     * 2. **源变了** —— 源还在但 mtime/size 与 `.meta` 对不上 ⇒ 旧 CSV 已经是过期数据，
     *    留着只会诱惑别的代码去读它（下次问会重转）。
     *
     * ⚠️ 只删 [isCacheArtifact] 认得的名字。缓存是派生物：删掉不丢用户任何东西。
     *
     * @return 删掉的文件数（测试拿它钉「删干净了」）
     */
    fun sweep(cacheDir: File, alive: Collection<File>): Int {
        val files = cacheDir.listFiles() ?: return 0
        // 名字 → 源（hash8 不可逆，只能正着算一遍再按名字对）
        val byName = HashMap<String, File>(alive.size * 2)
        alive.forEach { byName[cacheFile(cacheDir, it).name] = it }
        var gone = 0
        for (f in files) {
            if (!f.isFile || !isCacheArtifact(f.name)) continue
            // `.tmp` 是「写完才 rename」留下的残余（写和 rename 在同一个调用里完成）⇒ 扫描时一定是垃圾
            if (f.name.endsWith(".tmp")) {
                if (f.delete()) gone++
                continue
            }
            val csvName = f.name.removeSuffix(META_SUFFIX)
            val src = byName[csvName]
            // 源还在且没变 ⇒ 留着（这是唯一该留的情形）
            if (src != null && isFresh(File(cacheDir, csvName), src)) continue
            if (f.delete()) gone++
        }
        return gone
    }

    /**
     * 转一张 `.xls`。
     *
     * @param src 源 `.xls`
     * @param cacheDir 缓存目录（**固定** `<工作区>/抱朴记忆/表格/缓存/`；参数只为单测换临时目录，
     *   生产调用方 [TableRegistry.cacheDir] 只算这一个路径，不提供别的入口）
     * @param rowLimit 行数上限，默认 [BIFF_MAX_ROWS]（＝格式上限）。**这个参数只为让那条判据
     *   能被单测到**——真 `.xls` 装不下 65537 行，jxl 写都写不出来，造不出超限夹具
     * @return 成功给 [XlsConverted]；失败给 [XlsFailure]（**每一种都有一句能动手的话**）
     */
    fun convert(src: File, cacheDir: File, rowLimit: Int = BIFF_MAX_ROWS): Result<XlsConverted> = runCatching {
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
            convertNow(src, out, rowLimit)
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

    private fun convertNow(src: File, out: File, rowLimit: Int): XlsConverted {
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
            if (rows > rowLimit) throw XlsFailure.AboveBiffLimit(rows, rowLimit)

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
     * 流式写 CSV（**不拼一个大 String**：65536 行 × 43 列拼起来就是几十上百 MB，那是负例 6 本人）。
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
