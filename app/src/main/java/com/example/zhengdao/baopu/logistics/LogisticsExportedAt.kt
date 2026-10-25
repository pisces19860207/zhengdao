// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十五批 · 「数据时间」怎么定（派工单 §1 最后一句：优先文件名 → 表内容 → 文件 mtime，
// 并且**必须在界面明示**「数据时间 = X」）。
//
// ## 为什么这一层要单独存在
// 答案第 3 行的原话是「数据：09:12 导出的配货单；之后的变化不在这份表里」。这句里的时间是
// **唯一允许出现在答案里的时间**，它错了，整份表的新鲜度就是假的——下午装完的车会被当成「还没发」。
// 所以取数只走三条有证据的路，且**取到哪条就明说是哪条**：
// 1. 文件名里的日期时间（T9/Excel 导出普遍带）；
// 2. 表里**明确标了「导出/打印/生成时间」**的那一格（没有这个标签的一律不算——
//    「发货日期」「制单日期」都是单据自己的日期，把它们当导出时间就是把新鲜度算错，
//    那是猜，不是降级）；
// 3. 文件在磁盘上的修改时间（mtime）——最弱的一条，用到它必须在界面写「按文件时间」。
//
// ## 两条硬规矩
// - **不认没有年份的时间戳**：`10-25 09:12` 缺年份，补哪一年都是猜（1 月导 12 月的表就会补错），
//   所以带不出年份的写法直接不算候选；
// - **越界不滚日历**：`2026-13-40` 这种写法拒收，不是悄悄变成 `2027-01-09`。
//   （`Calendar` 默认 lenient，会滚——这里显式关掉。）
package com.example.zhengdao.baopu.logistics

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** 数据时间的来路——界面要把这两个字念出来（「取自文件名」／「取自文件时间」）。 */
enum class ExportedAtSource(val label: String) {
    FILENAME("文件名"),
    CONTENT("表里标注的导出时间"),
    MTIME("文件时间（表里和文件名都没有时间戳）"),
    NONE("取不到"),
}

/**
 * 一张表的「数据时间」。
 *
 * @param millis 毫秒（`null` ＝ 三条路都没取到：这时答案**不许**给任何时间，
 *   要写「这份表取不到导出时间，新鲜度没法保证」）
 * @param source 取到的那条路
 * @param hasClock 时间戳里**有没有钟点**。只有日期时不许写「00:00 导出的」——那是把
 *   「只知道哪天」伪装成「知道几点」；这时答案里出现的是日期。
 */
data class ExportedAt(val millis: Long?, val source: ExportedAtSource, val hasClock: Boolean = true) {
    val known: Boolean get() = millis != null
}

/**
 * 导出时间判定（纯函数：进字符串与毫秒、出 [ExportedAt]；时区由调用方给，测试里钉死）。
 */
object LogisticsExportedAt {

    /** 合理窗口：早于今天 366 天、晚于今天 1 天以外的时间戳都不当候选（导错年份的表不配当「今天的数据」）。 */
    private const val MAX_PAST_MILLIS = 366L * 24 * 60 * 60 * 1000
    private const val MAX_FUTURE_MILLIS = 24L * 60 * 60 * 1000

    /** 只有带**年月日**的写法才算候选（缺年份的不算，见文件头那条硬规矩）。 */
    private val DASHED =
        Regex("""(?<!\d)(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?:[ T_](\d{1,2})[:：\-](\d{2})(?::(\d{2}))?)?(?!\d)""")
    private val DIGITS = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?:(\d{2})(\d{2})(?:(\d{2}))?)?(?!\d)""")
    private val CN = Regex("""(20\d{2})年(\d{1,2})月(\d{1,2})日?(?:\s*(\d{1,2})[时点](\d{2})分?)?""")

    /** 表里能被当成「导出时间」的标签字（只有这几个词，多一个都是放宽）。 */
    private val EXPORT_LABELS = listOf("导出时间", "导出日期", "打印时间", "生成时间", "报表时间")

    /** 文件名里找（找不到返回 null）。 */
    fun fromName(fileName: String, nowMillis: Long, zone: TimeZone): Long? = hitFromName(fileName, nowMillis, zone)?.millis

    /** 文件名里找，连「有没有钟点」一起给（[resolve] 用这条）。 */
    internal fun hitFromName(fileName: String, nowMillis: Long, zone: TimeZone): Hit? =
        firstCandidate(fileName, nowMillis, zone)

    /** 三条路的次序就是派工单 §1 的次序，不许调换。 */
    fun resolve(fileName: String, grid: Grid?, mtimeMillis: Long, nowMillis: Long, zone: TimeZone): ExportedAt {
        hitFromName(fileName, nowMillis, zone)?.let {
            return ExportedAt(it.millis, ExportedAtSource.FILENAME, it.hasClock)
        }
        grid?.let { firstCandidate(it.exportScanText(), nowMillis, zone) }?.let {
            return ExportedAt(it.millis, ExportedAtSource.CONTENT, it.hasClock)
        }
        if (plausible(mtimeMillis, nowMillis)) return ExportedAt(mtimeMillis, ExportedAtSource.MTIME)
        return ExportedAt(null, ExportedAtSource.NONE, hasClock = false)
    }

    /** 只在**带导出标签**的格子里找，所以这里先把表摊成行文本（见 [fromGrid] 的口径）。 */
    private fun Grid.exportScanText(): String {
        val lines = ArrayList<String>(rows.size + 1)
        lines += header.joinToString(" ")
        rows.forEach { lines += it.joinToString(" ") }
        notes.forEach { lines += it }
        val labelled = lines.filter { l -> EXPORT_LABELS.any { l.contains(it) } }
        val colOfLabel = header.indexOfFirst { h -> EXPORT_LABELS.any { h.contains(it) } }
        val sideways = if (colOfLabel < 0) {
            emptyList()
        } else {
            rows.mapNotNull { r -> r.getOrNull(colOfLabel)?.let { "$it ${r.getOrElse(colOfLabel + 1) { "" }}" } }
                .filter { EXPORT_LABELS.any { k -> it.contains(k) } }
        }
        return (labelled + sideways).joinToString("\n")
    }

    /** 表内容里找：只认**标签明确**的那一格（口径见 [Grid.exportScanText]）。 */
    fun fromGrid(grid: Grid, nowMillis: Long, zone: TimeZone): Long? =
        firstCandidate(grid.exportScanText(), nowMillis, zone)?.millis

    /** 一个时间戳候选：值 ＋ 有没有钟点。 */
    internal data class Hit(val millis: Long, val hasClock: Boolean)

    /**
     * 从左到右取第一个**能成立**的日期时间。
     *
     * 一段文字里同时出现 `2026-10-25` 与 `2026-10-25 09:12` 时取**带钟点**的那个：
     * 只取日期会得到「00:00 导出的」，那是把「只知道哪天」写成「知道几点」。
     * 都不带钟点时取最左边那个（书写顺序就是这张表想让你读的顺序）。
     */
    private fun firstCandidate(text: String, nowMillis: Long, zone: TimeZone): Hit? {
        if (text.isEmpty()) return null
        val hits = ArrayList<Hit>(3)
        fun collect(m: MatchResult, clockAt: Int) {
            val hasClock = m.groupValues.size > clockAt && m.groupValues[clockAt].isNotEmpty()
            build(m, hasClock, nowMillis, zone)?.let { hits += Hit(it, hasClock) }
        }
        CN.findAll(text).forEach { collect(it, 4) }
        DASHED.findAll(text).forEach { collect(it, 4) }
        DIGITS.findAll(text).forEach { collect(it, 4) }
        if (hits.isEmpty()) return null
        return hits.firstOrNull { it.hasClock } ?: hits.first()
    }

    /** 越界返回 null（不滚日历）。 */
    private fun build(m: MatchResult, hasClock: Boolean, nowMillis: Long, zone: TimeZone): Long? {
        val g = m.groupValues
        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        if (day !in 1..31) return null
        var hour = 0
        var minute = 0
        if (hasClock) {
            hour = g[4].toIntOrNull() ?: return null
            minute = g[5].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            if (g.size > 6 && g[6].isNotEmpty()) {
                val sec = g[6].toIntOrNull() ?: return null
                if (sec !in 0..59) return null
            }
        }
        val cal = GregorianCalendar(zone)
        cal.isLenient = false
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val millis = runCatching { cal.timeInMillis }.getOrNull() ?: return null
        return if (plausible(millis, nowMillis)) millis else null
    }

    private fun plausible(millis: Long, nowMillis: Long): Boolean =
        millis <= nowMillis + MAX_FUTURE_MILLIS && millis >= nowMillis - MAX_PAST_MILLIS

    /** 当天键（`20261025`）：当日本地表的 `day` 列与台账文件名都用它。 */
    fun dayKey(millis: Long, zone: TimeZone): String {
        val c = GregorianCalendar(zone).apply { timeInMillis = millis }
        return "%04d%02d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 钟点（答案第 3 行那句「09:12」）。 */
    fun clock(millis: Long, zone: TimeZone): String {
        val c = GregorianCalendar(zone).apply { timeInMillis = millis }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** 界面上的完整数据时间（`2026-10-25 09:12`）。 */
    fun display(millis: Long, zone: TimeZone): String {
        val c = GregorianCalendar(zone).apply { timeInMillis = millis }
        return "%04d-%02d-%02d %02d:%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    /**
     * 答案第 3 行（派工单 §3.5 固定三行的最后一行，**新鲜度不许装作最新**）。
     *
     * 只写时间戳本身：来源是文件名还是文件时间属于**界面字段**（见 [sourceNote]），
     * 塞进这一行会多出 §3.5 明令禁止的废话。取不到时间时这一行仍然必须出现——
     * 写「取不到」比什么都不写诚实（负例⑤钉的就是「不带数据时间」要红）。
     */
    fun lineOf(at: ExportedAt, fileLabel: String, zone: TimeZone, nowMillis: Long): String {
        val m = at.millis ?: return "数据：这份$fileLabel 取不到导出时间，新旧没法判断——要重新导出才有"
        val day = dayKey(m, zone)
        val today = dayKey(nowMillis, zone)
        val when0 = if (!at.hasClock) m.monthDay(zone) else clock(m, zone)
        val stale = if (day != today) "（不是今天）" else ""
        return "数据：$when0 导出的$fileLabel$stale；之后的变化不在这份表里"
    }

    /** 界面/台账里的「数据时间 = X」＋它从哪条路取来（派工单 §1：必须明示）。 */
    fun sourceNote(at: ExportedAt, zone: TimeZone): String {
        val m = at.millis ?: return "数据时间：取不到（来源：${at.source.label}）"
        val shown = if (at.hasClock) display(m, zone) else "${dayKey(m, zone).ymd()}"
        return "数据时间：$shown（取自${at.source.label}）"
    }

    private fun Long.monthDay(zone: TimeZone): String {
        val c = GregorianCalendar(zone).apply { timeInMillis = this@monthDay }
        return "%02d-%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** `20261025` → `2026-10-25`（只给界面用，不参与任何判断）。 */
    private fun String.ymd(): String = if (length == 8) "${substring(0, 4)}-${substring(4, 6)}-${substring(6, 8)}" else this
}
