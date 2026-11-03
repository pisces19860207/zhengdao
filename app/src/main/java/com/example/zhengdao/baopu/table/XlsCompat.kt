// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百五十二批 · 旧版 Excel（`.xls` / BIFF）→ UTF-8 CSV —— **只加一条读取分支**。
// 第四百二十二批 · 读库由 **jxl（LGPL-2.1）换成 Apache POI 的 HSSF（Apache-2.0）**。
//
// 起因：F9 物流系统的导出只肯出 `.xls`（BIFF），而抱朴的表格通道只认 `.xlsx / .csv / .tsv`
// ⇒ 导出来的表在抱朴里**根本看不见**。派工单定的做法：**不许硬读** —— 用读库读第一个工作表、
// 写成 UTF-8 CSV 落在工作区的缓存目录，再**走已经存在的那条 CSV 通道**。
// 红线：xlsx / csv / tsv 的既有路径一个字都不改（本文件甚至不 import `SheetReader`）。
//
// ## 为什么 422 批换库（换的是许可，不是功能）
//
// jxl 是随包依赖里**唯一的 LGPL-2.1**（弱传染：动态链接即可用，但随附正文 + 源码可得性
// 是硬义务）；POI 是 Apache-2.0（可商用、无版税、无源码要约）。功能面**一个字都不许变**：
// 判据是「同一夹具、换前换后逐格一致」，不是「看起来差不多」。取证方式见
// `422-JXL换POI-交付`：先用 jxl 版把两份真表（`物流.xls` / `到货库存.xls`）转成 CSV 存成
// 金标准，再换 POI 重转、逐字节比 SHA-256。
//
// ## 只 HSSF，不引 ooxml
//
// · **不引** `poi-ooxml` / `xmlbeans`：`.xlsx` 走的是自研 `LogisticsXlsx`，那条路本批一行不碰；
//   引了 ooxml 等于把一个 10MB+ 的 XML 栈拖进包，只为一条我们根本不走的分支。
// · **只用 `HSSFWorkbook` 构造器**，不用 `WorkbookFactory`：后者会按文件魔数去试 XSSF
//   （反射加载 `poi-ooxml` 的类），在「只有 poi 没有 poi-ooxml」的包里是条会炸的岔路。
// · `log4j-core` 在依赖侧显式排除（POI 只声明 `log4j-api`，见 `app/build.gradle.kts` 的注释）。
//
// ## 四条硬口径（派工单 §1 / §2）
//
// 1. **只转第一张**：多工作表默认取第一张，提示里写「另有 K 个工作表未读」；
// 2. **日期**：日期格一律格成 `yyyy-MM-dd HH:mm`，整点只写 `yyyy-MM-dd`。
//    ⚠️ 时区是这里唯一的坑，实测（真表 `到货库存.xls` r2c6）：
//      jxl 的 `cell.getContents()` = `2026-10-24 09:36:00`
//      `((DateCell) cell).getDate()` 按**本地时区**格式化 = `2026-10-24 17:36`（本机 Asia/Hong_Kong）
//      `((DateCell) cell).getDate()` 按 **UTC** 格式化      = `2026-10-24 09:36` ✔
//    ⇒ 必须按 **UTC** 格式化，才是 Excel 里那个墙上时间。按本地时区格式化比漏出 `46319.4`
//    更糟：它看起来是个正常日期，只是悄悄偏了 8 小时，没人会发现。
//    ⚠️ POI 侧同理且**更隐蔽**：`cell.dateCellValue` 返回 `java.util.Date`（毫秒数），
//    而 `DateUtil.getJavaDate(serial)` 是按**本地时区**把序列号折算成毫秒的。
//    本文件的做法是**不碰 `java.util.Date`**：直接拿 `cell.numericCellValue`（Excel 序列号）
//    按 UTC 纪元换算成 `Instant`，再交给两个不可变的 formatter —— 墙上时间原样落地，
//    与构建机 / 手机所在时区无关。
// 3. **上限**：单文件 ≤ 50MB、第一张表 ≤ 65536 行（**BIFF 自己的格式上限**，不是我们另设的阀门）、
//    转换后 CSV ≤ 50MB；超了**拒绝并给一句能动手的话**，不许 OOM、不许卡主线程。
//    ⚠️ 这里曾经写过「20 万行上限」——那是**永远碰不到的假判据**：BIFF8 一张表最多 65536 行，
//    真文件到不了。假判据比没有判据更坏：它看着很严格，真出事时什么也拦不住，还会让人以为
//    「20 万行以下都安全」。现在这条的真身份是**格式上限守卫**，真话是「这文件的行数超过了
//    .xls 能装下的行数 ⇒ 它不是一张正常的 .xls，另存为 .xlsx 再导」。
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
// - 「转换后列错位」：每一行都按 **`columns` 这个统一宽度**补齐。POI 的 `Row.lastCellNum`
//   只给到该行最后一个有格的位置，谁按行宽写谁就把中间空列挤掉了 —— 那才是错位；
// - 「日期仍是序列号」：见上面第 2 条；
// - 「失败静默」：**五种**失败各自一句能动手的话，见 [XlsFailure]；
// - 「大文件 OOM」：转换是**流式写**（一行一行进 `BufferedWriter`，不拼一个大 String），
//   且**先看大小/行数再打开**，写的过程中还盯着已写字节数；
// - 「缓存变成第二份原始数据」：路径固定进 [XlsCompat.DIR_CACHE]、按路径身份硬跳过、
//   源一变就清（见上面 R1 那三条）。
// - 「改了既有通道」：本文件的产出只是「一个 UTF-8 的 .csv 文件」，下游照旧。
package com.example.zhengdao.baopu.table

import org.apache.poi.EncryptedDocumentException
import org.apache.poi.hssf.OldExcelFormatException
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.NotOLE2FileException
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

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
     * Excel 的日期纪元：序列号 0 ＝ 1899-12-30T00:00:00Z（**含 1900 闰年 bug 的既定口径**）。
     *
     * 为什么自己算而不用 `DateUtil.getJavaDate`：后者返回 `java.util.Date`（毫秒），
     * 而它内部按**本地时区**把序列号折算成毫秒（`getJavaDate(serial, false)`）。
     * 我们全程只认「序列号 → UTC 墙上时间」，绕开 `java.util.Date` 就没有时区参与的机会。
     * 见文件头第 2 条：这个坑曾经让日期悄悄偏 8 小时。
     */
    private const val EXCEL_EPOCH_MILLIS = -2209161600000L // 1899-12-30T00:00:00Z

    private const val MILLIS_PER_DAY = 86_400_000L

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

    /**
     * 有显式格式串的数字格用它格式化（**只在 `dataFormat != 0` 时用**）。
     *
     * `Locale.US` 是刻意的：小数点必须是 `.`，不能随手机语言变成 `,`——否则同一张表在
     * 不同手机上转出来的 CSV 不一样，缓存与断言全部失去意义。
     */
    private val FORMATTER: DataFormatter = DataFormatter(Locale.US)

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
     *   能被单测到**——真 `.xls` 装不下 65537 行，造不出超限夹具
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
     * POI 的英文报错 → 中文。
     *
     * **只译它自己说过的那几种，不认得的照原样带出**——把「corrupt」翻成「文件是坏的」
     * 是翻译，把它翻成「网络不好」就是猜。用户拿到的那句话里永远看得到 POI 的原文。
     */
    private fun poiWhy(m: String): String = when {
        m.contains("corrupt", ignoreCase = true) -> "文件是坏的：导出中断或落盘不全；POI 原话=$m"
        m.contains("BIFF", ignoreCase = true) -> "不是 Excel 97–2003 那种 .xls；POI 原话=$m"
        m.contains("OLE2", ignoreCase = true) -> "不是 OLE 复合文档，多半压根不是 .xls；POI 原话=$m"
        m.contains("header", ignoreCase = true) -> "文件头不完整（多半被截断）；POI 原话=$m"
        else -> m.take(80)
    }

    private fun convertNow(src: File, out: File, rowLimit: Int): XlsConverted {
        // 只 HSSF：不用 WorkbookFactory（它会按魔数去反射试 XSSF，而本包**没有** poi-ooxml）。
        val wb: Workbook = try {
            FileInputStream(src).use { HSSFWorkbook(it) }
        } catch (e: EncryptedDocumentException) {
            throw XlsFailure.Encrypted
        } catch (e: OldExcelFormatException) {
            // BIFF5/7（Excel 5.0/95）：jxl 当年也不支持，口径一致 —— 换格式才是出路
            throw XlsFailure.Broken("这是 Excel 95 或更早的 .xls；POI 原话=" + (e.message ?: ""))
        } catch (e: NotOLE2FileException) {
            throw XlsFailure.Broken("不是 OLE 复合文档，多半压根不是 .xls；POI 原话=" + (e.message ?: ""))
        } catch (e: java.io.IOException) {
            throw XlsFailure.Broken("读不出来：" + (e.message?.take(80) ?: "IO 失败"))
        } catch (e: RuntimeException) {
            // 解析**别人给过来的二进制**：POI 对截断/畸形文件不保证都抛受检异常，
            // 越界与负长度这类 RuntimeException 在真机上一样会发生。吞掉它才是真静默，
            // 这里把它翻译成一句用户能动手的话（负例：「失败只抛异常」）。
            throw XlsFailure.Broken(poiWhy(e.message ?: e.javaClass.simpleName))
        }
        try {
            val count = wb.numberOfSheets
            if (count <= 0) throw XlsFailure.Broken("这个文件里没有工作表")
            val names = (0 until count).map { wb.getSheetName(it) }
            val first: Sheet = wb.getSheetAt(0)
            val rows = rowCountOf(first)
            if (rows > rowLimit) throw XlsFailure.AboveBiffLimit(rows, rowLimit)
            val cols = columnCountOf(first, rows)

            writeCsv(first, rows, cols, out)
            writeMeta(out, Meta(src.lastModified(), src.length(), rows, cols, names[0], names))
            convertCount++
            return XlsConverted(
                source = src,
                csv = out,
                sheet = names[0],
                sheets = names,
                rows = rows,
                columns = cols,
                cached = false,
            )
        } finally {
            wb.close() // POI 会开着底层输入流的句柄，漏 close 就是漏 fd
        }
    }

    /** 表有几行。空表＝0 行（不是 1 行空行）。 */
    private fun rowCountOf(sheet: Sheet): Int {
        if (sheet.physicalNumberOfRows == 0) return 0
        return (sheet.lastRowNum + 1).coerceAtLeast(0)
    }

    /**
     * 表有多宽：**逐行取最大** `lastCellNum`。
     *
     * 不能只看第 0 行：真表里常有「表头短、数据行更宽」（或反过来）的情形，
     * 只看表头会把右边几列整片截掉——那是「列错位」的另一种形态。
     */
    private fun columnCountOf(sheet: Sheet, rows: Int): Int {
        var max = 0
        for (r in 0 until rows) {
            val n = sheet.getRow(r)?.lastCellNum?.toInt() ?: continue
            if (n > max) max = n
        }
        return max
    }

    /**
     * 流式写 CSV（**不拼一个大 String**：65536 行 × 43 列拼起来就是几十上百 MB，那是负例 6 本人）。
     *
     * 先写 `.tmp` 再改名：转一半崩了不会留下一个「看着新鲜、其实是半截」的 CSV。
     */
    private fun writeCsv(sheet: Sheet, rows: Int, cols: Int, out: File) {
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        var bytes = 0L
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            for (r in 0 until rows) {
                val row = sheet.getRow(r)
                val line = StringBuilder()
                for (c in 0 until cols) {
                    if (c > 0) line.append(',')
                    line.append(escape(textOf(row?.getCell(c))))
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
    private fun textOf(cell: Cell?): String {
        if (cell == null) return ""
        // 公式格取**缓存结果**的类型：Excel 存的是上次算出来的值，jxl 当年也是这么给的
        // （`NumberFormulaCell extends NumberCell`）。不取公式文本——那会把 `SUM(A1:A9)`
        // 写进 CSV，下游当字符串算不动。
        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        return when (type) {
            CellType.NUMERIC -> numericText(cell)
            CellType.STRING -> cell.stringCellValue.orEmpty()
            // jxl 的 BooleanCell 给的是小写 true/false；POI 的 DataFormatter 会给 TRUE/FALSE。
            // 这一格没有夹具，但「换库不改行为」的判据在前，按 jxl 的写法落地。
            CellType.BOOLEAN -> if (cell.booleanCellValue) "true" else "false"
            // #DIV/0! 这类错误值：它不是数，写进 CSV 会被当成字符串，downstream 算不动
            CellType.ERROR -> ""
            CellType.BLANK, CellType._NONE -> ""
            // 缓存结果类型仍是公式（极少见）：退回公式文本，与 jxl 的兜底一致
            CellType.FORMULA -> cell.cellFormula.orEmpty()
        }
    }

    /** 数字格：日期按 UTC 格式化，其余按「Excel 里看到的那个数」写出去。 */
    private fun numericText(cell: Cell): String {
        val v = cell.numericCellValue
        if (DateUtil.isCellDateFormatted(cell)) {
            val millis = EXCEL_EPOCH_MILLIS + Math.round(v * MILLIS_PER_DAY)
            val t = Instant.ofEpochMilli(millis)
            val s = DATE_TIME.format(t)
            return if (s.endsWith(" 00:00")) DATE_ONLY.format(t) else s
        }
        // 「General」＝没有格式串：按最短往返表示写（17.0 → "17"，3660.16 → "3660.16"）。
        // ⚠️ 不能直接用 DataFormatter：它模仿 Excel 的**显示**，11 位以上会切成科学计数法
        // （`1.23457E+12`），而 jxl 当年给的是完整数字——那就不是「逐格一致」了。
        if (cell.cellStyle.dataFormat.toInt() == 0) return plainNumber(v)
        // 有显式格式串（`0.00` / `#,##0` / `yyyy`…）：按格式串来，这才与 jxl 的格式化口径一致。
        //
        // ⚠️ **Android 上没有 `java.awt`，而 POI 的格式化栈里有一处硬依赖它**（第四百二十二批实测）：
        //    `CellFormatPart` 的静态初始化块在造 `NAMED_COLORS` / `INDEXED_COLORS` 两个
        //    `java.awt.Color` 集合 ⇒ 该类**一初始化就** NoClassDefFoundError。用拒绝加载
        //    `java.awt.*` 的类加载器实跑（`_zd-private/tmp422/AwtProbe.java`）：
        //      · `new CellFormatPart("0.00")` → ClassNotFoundException: java.awt.Color  ✗
        //      · `DataFormatter.formatRawCellContents(1234.5678, 2, "0.00")` → `1234.57`  ✓
        //    差别在 POI 对「简单格式串」有快路径，根本不构造 `CellFormatPart`；只有带
        //    **颜色 `[Red]` / 条件 `[>100]` / 引号字面量**的格式串才会走进去。
        //
        //    这类格式串在 F9 导出里罕见，但**罕见不等于不会**：真出现时，JVM 上的单测全绿、
        //    真机上一问这张表就整表转换失败 —— 那正是「换库」最不该付出的代价。
        //    ⇒ 兜底：格式化成 LinkageError（缺类 / 初始化失败）时**降级成纯数字**，
        //    并记下这件事，后续单元格不再反复撞同一个错。
        if (awtMissing) return plainNumber(v)
        return try {
            FORMATTER.formatCellValue(cell)
        } catch (e: LinkageError) {
            // NoClassDefFoundError / ExceptionInInitializerError 都在 LinkageError 之下。
            awtMissing = true
            plainNumber(v)
        }
    }

    /**
     * 这台设备上 POI 的格式化栈能不能用（Android 无 `java.awt` ⇒ 带颜色的格式串会炸）。
     *
     * 一旦探到不能用就置真：失败是**类的静态初始化**失败，属进程级、不会自愈，
     * 没必要每个格子再撞一次（那张表可能几千行）。
     */
    @Volatile
    private var awtMissing: Boolean = false

    /** 数字的最短往返表示：整数不带 `.0`，小数按 `Double.toString`（最短可往返）。 */
    private fun plainNumber(v: Double): String = when {
        v.isNaN() || v.isInfinite() -> ""
        v == Math.rint(v) && abs(v) < 1e15 -> v.toLong().toString()
        else -> v.toString()
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
