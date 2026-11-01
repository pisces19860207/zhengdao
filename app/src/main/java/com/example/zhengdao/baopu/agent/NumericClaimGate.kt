// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.agent

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 340 · 值级验收（**收窄口径**）：只对「算术/计数类自称」做对账——
 * 求和／平均／行数·条数／文件数／百分比。其余数字一律不管（[AnswerGate] 管来源，这里管算术）。
 *
 * 三条硬规矩（派工单 340 收窄原文，逐字）：
 * 1. **无口径 ⇒ UNVERIFIABLE**：找不到来源、来源不唯一、来源读不了、来源里没有可解析的数字
 *    ——一律判不了，**不猜**；
 * 2. **差 1 也算差**：比对是 BigDecimal 精确相等，差 0.1 与差 4000 同为 FAILED，detail 如实报差多少；
 * 3. **不许模型重数**：重算全部由本文件代码完成（BigDecimal 精确算术），任何路径都不调模型。
 *
 * 输入形状与 [AnswerGate] 同族（answer 字符串＋工作区根）；[exclude] 是**交付物相对路径**
 * （sum.txt 这类产出文件）——它们含数字但不是"来源"，不排除会把来源顶成不唯一。
 * 来源解析（收窄）：求和/平均/百分比 ⇒ 工作区内**唯一的**含数字文件（排除交付物后）；
 * 行数 ⇒ 子句里点名的文件；文件数 ⇒ 子句里点名的目录。定位不了就 UNVERIFIABLE。
 * 零 Android 依赖、零网络、零模型；JVM 直测。
 * 提取谓词按 335 真实终答校准（runs-335.jsonl：「相加得 55220」「合计 49430，平均 4493.6
 * （保留一位小数）」「和是 32835」「文件共 150 行」）。
 */
internal object NumericClaimGate {

    enum class Status { VERIFIED, FAILED, UNVERIFIABLE }

    /** 对账种类（收窄闭集；改种类=改口径）。 */
    enum class Kind(val wire: String) {
        SUM("sum"), AVG("avg"), LINES("lines"), FILE_COUNT("file_count"), PERCENT("percent"),
    }

    data class Result(
        val kind: Kind,
        /** 模型自称的值（原文数字）。 */
        val claimed: BigDecimal,
        /** 机械重算的值（判不了为 null）。 */
        val actual: BigDecimal?,
        val status: Status,
        /** 来源（相对路径或目录）；判不了为 "—"。 */
        val source: String,
        /** 人话一句：VERIFIED 给读数，FAILED 给差多少，UNVERIFIABLE 给缺什么。 */
        val detail: String,
    )

    private val NUMBER = Regex("""\d[\d,，]*(?:\.\d+)?""")
    private val PATH = Regex("""[\w\-\\一-龥]+\.(?:txt|md|csv|json|log)""")
    private val PERCENT = Regex("""(\d{1,3})\s*%""")

    internal const val UNVERIFIED_NO_SOURCE = "无来源（工作区内没有可定位的数字来源）"
    internal const val UNVERIFIED_AMBIGUOUS = "来源不唯一（工作区有多个含数字文件，不猜）"
    internal const val UNVERIFIED_UNREADABLE = "来源读不了"
    internal const val UNVERIFIED_NO_NUMBERS = "来源里没有可解析的数字（无口径）"

    private fun clauses(text: String): List<String> =
        text.split('。', '！', '？', '?', '!', '\n', '；', ';', '，', ',')
            .map { it.trim() }.filter { it.isNotEmpty() }

    private fun bd(raw: String): BigDecimal = BigDecimal(raw.replace("，", "").replace(",", ""))

    private fun numAfter(clause: String, keyword: String): BigDecimal? {
        val idx = clause.indexOf(keyword)
        if (idx < 0) return null
        val window = clause.substring(idx + keyword.length).let { it.substring(0, minOf(it.length, 14)) }
        return NUMBER.find(window)?.let { bd(it.value) }
    }

    /** 335 校准的求和谓词（「总和」「合计」「相加得」「相加」「和是」「和 =」——等号形态来自真实终答）。 */
    private fun sumKeywordOf(clause: String): String? =
        listOf("总和", "合计", "相加得", "相加", "和是").firstOrNull { clause.contains(it) }
            ?: if (Regex("和\\s*[=＝]").containsMatchIn(clause)) "和" else null

    fun check(answer: String, root: File, exclude: Set<String> = emptySet()): List<Result> {
        if (answer.isBlank()) return emptyList()
        val out = ArrayList<Result>()
        val seen = HashSet<String>()
        val numericAll = runCatching { numericSources(root, exclude) }.getOrDefault(emptyList())
        val numericSrc = if (numericAll.size == 1) numericAll[0] else null
        val srcWhy = if (numericAll.size > 1) UNVERIFIED_AMBIGUOUS else UNVERIFIED_NO_SOURCE
        // 行段口径（收窄）：答案里出现「第 A–B 行」⇒ 求和按该行段重算（335 range-sum 形状）；
        // 没有行段 ⇒ 全文件。多行段并存超出收窄口径 ⇒ 只认第一处（如实记 detail）。
        val lineRange = Regex("""第\s*(\d+)\s*[–\-—~至到]\s*(\d+)\s*行""").find(answer)
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        for (clause in clauses(answer)) {
            // 一类声称一个处理函数（2026-11-01 拆；原先五段挤在 check 里，69 行 / 复杂度越线）。
            // 顺序＝判据优先级，别调：SUM → AVG → PERCENT → LINES → FILE_COUNT。
            if (handleSum(clause, root, numericSrc, srcWhy, lineRange, seen, out)) continue
            if (handleAvg(clause, root, numericSrc, srcWhy, seen, out)) continue
            if (handlePercent(clause, root, numericSrc, srcWhy, seen, out)) continue
            if (handleLines(clause, root, seen, out)) continue
            handleFileCount(clause, root, seen, out)
        }
        return out
    }

    private fun handleSum(
        clause: String,
        root: File,
        numericSrc: String?,
        srcWhy: String,
        lineRange: Pair<Int, Int>?,
        seen: HashSet<String>,
        out: ArrayList<Result>,
    ): Boolean {
        val sumKw = sumKeywordOf(clause) ?: return false
        val claimed = numAfter(clause, sumKw)
        if (claimed != null && seen.add("sum:$claimed")) {
            sumLike(Kind.SUM, claimed, root, numericSrc, srcWhy, lineRange, out)
        }
        return true
    }

    private fun handleAvg(
        clause: String,
        root: File,
        numericSrc: String?,
        srcWhy: String,
        seen: HashSet<String>,
        out: ArrayList<Result>,
    ): Boolean {
        if (!clause.contains("平均") && !Regex("保留[一二两三四五六]位小数").containsMatchIn(clause)) return false
        // AVG 声称值取**子句内最后一个数**（模型常先报未舍入值再报舍入值——交付口径是最后一个）；
        // 精度＝「保留 N 位小数」口径，没写就用声称值自己的小数位。
        val claimed = NUMBER.findAll(clause).lastOrNull()?.let { bd(it.value) }
        if (claimed != null && seen.add("avg:$claimed")) {
            val statedScale = Regex("保留([一二两三四五六])位小数").find(clause)?.groupValues?.get(1)?.let { w ->
                when (w) { "一" -> 1; "两", "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; else -> claimed.scale() }
            } ?: claimed.scale()
            // 同一答案里先报未舍入值再报舍入值（335 avg A1 形状）：交付口径是最后一个
            // 声称——AVG 结果只保留最后一条。
            out.removeAll { it.kind == Kind.AVG }
            avgLike(claimed, statedScale, root, numericSrc, srcWhy, out)
        }
        return true
    }

    private fun handlePercent(
        clause: String,
        root: File,
        numericSrc: String?,
        srcWhy: String,
        seen: HashSet<String>,
        out: ArrayList<Result>,
    ): Boolean {
        val pct = PERCENT.find(clause) ?: return false
        val claimed = clause.substring(pct.range.last + 1)
            .let { s -> NUMBER.find(s.substring(0, minOf(s.length, 10)))?.let { bd(it.value) } }
        if (claimed != null && seen.add("pct:${pct.groupValues[1]}:$claimed")) {
            pctLike(claimed, pct.groupValues[1].toLong(), root, numericSrc, srcWhy, out)
        }
        return true
    }

    private fun handleLines(
        clause: String,
        root: File,
        seen: HashSet<String>,
        out: ArrayList<Result>,
    ): Boolean {
        // LINES：「行数/共/一共 …N 行」（排除「第 N 行」——那是引用不是计数）
        val lines = Regex("""(?<!第)(\d[\d,，]*)\s*行""").find(clause) ?: return false
        if (!clause.contains("行数") && !clause.contains("共") && !clause.contains("一共")) return false
        val claimed = bd(lines.groupValues[1])
        val src = PATH.find(clause)?.value ?: return true
        if (seen.add("lines:$src:$claimed")) {
            val f = File(root, src)
            val actual = if (f.isFile) runCatching { BigDecimal(f.readLines().size.toLong()) }.getOrNull() else null
            if (actual == null) {
                out += Result(Kind.LINES, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_UNREADABLE)
            } else {
                out += Result(Kind.LINES, claimed, actual, verdict(claimed, actual), src, diffText(claimed, actual))
            }
        }
        return true
    }

    private fun handleFileCount(
        clause: String,
        root: File,
        seen: HashSet<String>,
        out: ArrayList<Result>,
    ) {
        // FILE_COUNT：「N 个文件」＋子句里的目录（取最后一个「xx/」提示）
        val fc = Regex("""(\d[\d,，]*)\s*个文件""").find(clause) ?: return
        val claimed = bd(fc.groupValues[1])
        val dirHint = Regex("""[\w\-一-龥]+/""").findAll(clause).lastOrNull()?.value?.trimEnd('/') ?: "."
        if (seen.add("fc:$dirHint:$claimed")) {
            val d = File(root, dirHint)
            val actual = if (d.isDirectory) BigDecimal((d.listFiles()?.size ?: 0).toLong()) else null
            if (actual == null) {
                out += Result(Kind.FILE_COUNT, claimed, null, Status.UNVERIFIABLE, dirHint, UNVERIFIED_UNREADABLE)
            } else {
                out += Result(Kind.FILE_COUNT, claimed, actual, verdict(claimed, actual), dirHint, diffText(claimed, actual))
            }
        }
    }

    private fun sumLike(
        kind: Kind,
        claimed: BigDecimal,
        root: File,
        src: String?,
        srcWhy: String,
        lineRange: Pair<Int, Int>?,
        out: MutableList<Result>,
    ) {
        if (src == null) {
            out += Result(kind, claimed, null, Status.UNVERIFIABLE, "—", srcWhy)
            return
        }
        val lines = readLines(File(root, src))
        when {
            lines == null -> out += Result(kind, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_UNREADABLE)
            lines.isEmpty() -> out += Result(kind, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_NO_NUMBERS)
            else -> {
                // 行段口径：有「第 A–B 行」⇒ 只加该行段（1 起含端点）；没有 ⇒ 全文件。
                val actual = if (lineRange != null) {
                    val (from, to) = lineRange
                    BigDecimal(lines.drop(from - 1).take((to - from + 1).coerceAtLeast(0))
                        .mapNotNull { l -> l.trim().toLongOrNull() }.sumOf { it })
                } else {
                    BigDecimal(lines.mapNotNull { l -> l.trim().toLongOrNull() }.sumOf { it })
                }
                out += Result(kind, claimed, actual, verdict(claimed, actual), src, diffText(claimed, actual))
            }
        }
    }

    /** 全文件行文本（保留空行占位，行号口径用）；读不了 ⇒ null。 */
    private fun readLines(f: File): List<String>? = runCatching { f.readLines() }.getOrNull()

    private fun avgLike(claimed: BigDecimal, scale: Int, root: File, src: String?, srcWhy: String, out: MutableList<Result>) {
        if (src == null) {
            out += Result(Kind.AVG, claimed, null, Status.UNVERIFIABLE, "—", srcWhy)
            return
        }
        val nums = readNumbers(File(root, src))
        when {
            nums == null -> out += Result(Kind.AVG, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_UNREADABLE)
            nums.isEmpty() -> out += Result(Kind.AVG, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_NO_NUMBERS)
            else -> {
                // 口径＝「保留 N 位小数」声明，没写就用声称值自己的小数位。
                val actual = BigDecimal(nums.sumOf { it }).divide(BigDecimal(nums.size), scale, RoundingMode.HALF_UP)
                out += Result(Kind.AVG, claimed, actual, verdict(claimed, actual), src, diffText(claimed, actual))
            }
        }
    }

    private fun pctLike(claimed: BigDecimal, percent: Long, root: File, src: String?, srcWhy: String, out: MutableList<Result>) {
        if (src == null) {
            out += Result(Kind.PERCENT, claimed, null, Status.UNVERIFIABLE, "—", srcWhy)
            return
        }
        val nums = readNumbers(File(root, src))
        when {
            nums == null -> out += Result(Kind.PERCENT, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_UNREADABLE)
            nums.isEmpty() -> out += Result(Kind.PERCENT, claimed, null, Status.UNVERIFIABLE, src, UNVERIFIED_NO_NUMBERS)
            else -> {
                val base = nums.sumOf { it }
                val actual = BigDecimal(base).multiply(BigDecimal(percent))
                    .divide(BigDecimal(100), claimed.scale(), RoundingMode.HALF_UP)
                out += Result(Kind.PERCENT, claimed, actual, verdict(claimed, actual), src, diffText(claimed, actual))
            }
        }
    }

    /** 精确相等（差 1 也算差）：归一化标度后 compareTo，0 与非 0 之间没有中间地带。 */
    private fun verdict(claimed: BigDecimal, actual: BigDecimal): Status =
        if (claimed.stripTrailingZeros() == actual.stripTrailingZeros()) Status.VERIFIED else Status.FAILED

    private fun diffText(claimed: BigDecimal, actual: BigDecimal): String {
        val same = claimed.stripTrailingZeros() == actual.stripTrailingZeros()
        return if (same) "重算相等：${actual.stripTrailingZeros().toPlainString()}"
        else "重算 ${actual.stripTrailingZeros().toPlainString()}，差 ${claimed.subtract(actual).abs().stripTrailingZeros().toPlainString()}（差 1 也算差）"
    }

    /** 工作区内含数字文件（相对路径；排除交付物；跳过 spill/备份/.bak）。 */
    private fun numericSources(root: File, exclude: Set<String>): List<String> =
        walkFiles(root).filter { rel ->
            val norm = rel.replace('\\', '/')
            if (exclude.any { norm.endsWith(it.replace('\\', '/').trimStart('/')) }) {
                false
            } else {
                isNumericFile(File(root, rel))
            }
        }

    private fun walkFiles(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<String>()
        root.walkTopDown().forEach { f ->
            if (f.isFile) {
                val norm = f.path.replace('\\', '/')
                if (!norm.contains("/.baopu-spill/") && !norm.contains("/备份/") && !norm.endsWith(".bak")) {
                    out += root.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                }
            }
        }
        return out
    }

    /** 数字文件：非空行 ≥1 且其中 ≥一半能整体解析为数（nums.txt 形状；散文文件判否）。 */
    private fun isNumericFile(f: File): Boolean {
        val lines = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrNull() ?: return false
        if (lines.isEmpty()) return false
        val numeric = lines.count { l -> l.trim().toBigDecimalOrNull() != null }
        return numeric * 2 >= lines.size
    }

    /** 读来源的全部数字（能整体解析为整数的行；解析不了的行跳过）。 */
    private fun readNumbers(f: File): List<Long>? = runCatching {
        f.readLines().mapNotNull { l -> l.trim().toLongOrNull() }
    }.getOrNull()
}
