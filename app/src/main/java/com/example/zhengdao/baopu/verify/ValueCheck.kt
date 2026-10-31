// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 值级验收（对账闸，纯函数，不碰数据库、不碰 Android、不碰模型）：
// 收尾闸 [ArtifactVerifier] 只验「有没有做」（存在/非空/编码/JSON），本闸验「说的数对不对」。
package com.example.zhengdao.baopu.verify

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 340 · 可机核的六种自称（v1 闭集，改集合=改口径）：
 *  - SUM/AVG      「相加得 55220」「平均 4493.6」⇒ 用来源文件重算（BigDecimal 精确算术）
 *  - RECORDS      「有 M 条」⇒ 数产物真实条数（JSONL 非空行 / CSV 数据行 / 文本非空行 / 调用方喂的 DB count）
 *  - FILE_OPS     「处理/删除/移动了 N 个文件」⇒ 账本变更数与磁盘实际变更数，三方必须相等
 *  - BYTES        「释放/占用 X 字节」⇒ 前后 size 差（口径必带：LOGICAL=逻辑大小 / ALLOCATED=磁盘占用）
 *  - FILES_CHANGED 「改了 K 个文件」⇒ git diff --name-only 的路径集合或账本 touched 集合的大小
 *
 * 四条硬规矩（派工单 340 原文）：
 *  ① 数字必须带口径；口径立不住（无来源/来源不唯一/读不了/缺前后快照/缺 size 口径）⇒ **UNVERIFIABLE，不许当通过**；
 *  ② **差 1 也算差**：BigDecimal 精确相等，没有「约等于」；
 *  ③ **不许模型重数**：重算全部由本文件代码完成（BigDecimal＋文件系统），任何路径不调模型、不联网；
 *  ④ 对不上时如实报「**我说 X、实际 Y**」，不悄悄改成 Y，更不装没发生。
 *
 * 词表校准来源：私有仓 `_zd-private/docs/experiments/335-对抗用例集/runs-335.jsonl` 的真实终答
 * （「12 个数字相加得 55220」「11 个数字合计 49430，平均 4493.6（保留一位小数）」）。
 * 文本解析只做算术类自称（SUM/AVG，即 335 验收路径）；其余四类由调用方喂账本/文件系统/DB 的机械数据走结构化入口。
 */
object ValueCheck {

    enum class Status { VERIFIED, FAILED, UNVERIFIABLE }

    /** 对账口径闭集；wire 名给验收报告/回灌用，改名=改口径。 */
    enum class Kind(val wire: String) {
        SUM("sum"),
        AVG("avg"),
        RECORDS("records"),
        FILE_OPS("file_ops"),
        BYTES("bytes"),
        FILES_CHANGED("files_changed"),
    }

    /** size 口径（硬规矩①：没口径不许判）。 */
    enum class SizeMode { LOGICAL, ALLOCATED }

    /** 字节差方向：释放＝before−after；占用＝after−before。方向错了就是错，不取绝对值糊弄。 */
    enum class ByteTense { FREED, TAKEN }

    /** 条数口径。 */
    enum class RecordFormat { JSONL, CSV, LINES }

    data class Result(
        val kind: Kind,
        /** 自称的值；UNVERIFIABLE 里连自称都立不住时可为 null。 */
        val claimed: BigDecimal?,
        /** 机械重算/点数的值；口径立不住时为 null。 */
        val actual: BigDecimal?,
        val status: Status,
        /** 对账依据（来源文件/账本/快照的去处）；立不住时给原因短语。 */
        val source: String,
        /** VERIFIED＝读数；FAILED＝「我说 X、实际 Y」；UNVERIFIABLE＝缺什么。 */
        val detail: String,
    )

    /** 从终答里解析出的一条算术自称。 */
    data class NumericClaim(
        val kind: Kind,
        val claimed: BigDecimal,
        /** 自称写入的产物（子句里点名的文件），没有则 null。 */
        val artifact: String?,
        val sentence: String,
    )

    // ── 算术对账（335 验收路径；也可直接喂结构化来源）──────────────────────────

    /** 求和自称对账：claimed 必须＝来源文件全部数字的精确和。 */
    fun checkSum(claimed: BigDecimal, source: File): Result {
        val nums = readNumbers(source) ?: return unverifiable(Kind.SUM, claimed, "来源读不了：${source.path}")
        if (nums.isEmpty()) return unverifiable(Kind.SUM, claimed, "来源里没有可解析的数（口径立不住）：${source.path}")
        val actual = nums.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        return judge(Kind.SUM, claimed, actual, source.path)
    }

    /**
     * 平均自称对账：口径＝「自称值自带的小数位」——他说到哪一位，就按哪一位 HALF_UP 重算再比；
     * 自称没给小数位（整数）就按个位比。差 0.1 与差 4000 同为 FAILED。
     */
    fun checkAvg(claimed: BigDecimal, source: File): Result {
        val nums = readNumbers(source) ?: return unverifiable(Kind.AVG, claimed, "来源读不了：${source.path}")
        if (nums.isEmpty()) return unverifiable(Kind.AVG, claimed, "来源里没有可解析的数（口径立不住）：${source.path}")
        val total = nums.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
        val actual = total.divide(BigDecimal(nums.size), claimed.scale().coerceAtLeast(0), RoundingMode.HALF_UP)
        return judge(Kind.AVG, claimed, actual, source.path)
    }

    // ── 四类结构化对账（数据只认账本/文件系统/DB，由调用方机械喂入）──────────────

    /**
     * 「有 M 条记录/行/项」：产物给了就读产物（JSONL=非空行、CSV=数据行不含表头、LINES=非空行），
     * 产物没给就用调用方机械查得的 DB count；两者都没有 ⇒ UNVERIFIABLE。
     */
    fun checkRecords(claimed: Long, artifact: File?, format: RecordFormat = RecordFormat.LINES, dbCount: Long? = null): Result {
        val c = BigDecimal(claimed)
        if (artifact != null) {
            if (!artifact.isFile) return unverifiable(Kind.RECORDS, c, "产物读不了（不存在或不是文件）：${artifact.path}")
            val n = countRecords(artifact, format)
                ?: return unverifiable(Kind.RECORDS, c, "产物读不了：${artifact.path}")
            return judge(Kind.RECORDS, c, BigDecimal(n), "${artifact.path}（${format} 口径）")
        }
        if (dbCount != null) return judge(Kind.RECORDS, c, BigDecimal(dbCount), "DB count（调用方机械查得）")
        return unverifiable(Kind.RECORDS, c, UNVERIFIED_NO_BASIS)
    }

    /**
     * 「处理/删除/移动了 N 个文件」：账本变更数、磁盘实际变更数、自称 N——**三方必须相等**。
     * 账本或磁盘快照任一缺失 ⇒ UNVERIFIABLE（没法对账，不许当通过）。
     */
    fun checkFileOps(claimed: Long, ledgerPaths: Collection<String>?, diskPaths: Collection<String>?): Result {
        val c = BigDecimal(claimed)
        if (ledgerPaths == null || diskPaths == null) {
            return unverifiable(Kind.FILE_OPS, c, "账本或磁盘快照缺失，$UNVERIFIED_NO_BASIS")
        }
        val ledger = ledgerPaths.map { canonical(it) }.toSortedSet()
        val disk = diskPaths.map { canonical(it) }.toSortedSet()
        val l = BigDecimal(ledger.size.toLong())
        val d = BigDecimal(disk.size.toLong())
        if (eq(c, l) && eq(l, d)) {
            return Result(Kind.FILE_OPS, c, l, Status.VERIFIED, "账本 ${ledger.size} 条＋磁盘 ${disk.size} 条", "三方相等：${fmt(c)}")
        }
        var detail = "我说 ${fmt(c)}、账本 ${fmt(l)}、磁盘 ${fmt(d)}（三方必须相等）"
        val onlyLedger = ledger.subtract(disk).take(3)
        val onlyDisk = disk.subtract(ledger).take(3)
        if (onlyLedger.isNotEmpty()) detail += "；只在账本：${onlyLedger.joinToString()}"
        if (onlyDisk.isNotEmpty()) detail += "；只在磁盘：${onlyDisk.joinToString()}"
        return Result(Kind.FILE_OPS, c, d, Status.FAILED, "账本=${ledger.size} 磁盘=${disk.size}", detail)
    }

    /** 「释放/占用 X 字节」：方向按自称算（释放=before−after、占用=after−before），口径必带。 */
    fun checkBytes(claimedBytes: Long, tense: ByteTense, mode: SizeMode?, beforeBytes: Long?, afterBytes: Long?): Result {
        val c = BigDecimal(claimedBytes)
        if (mode == null) {
            return unverifiable(Kind.BYTES, c, "缺 size 口径（LOGICAL=逻辑大小 / ALLOCATED=磁盘占用），$UNVERIFIED_NO_BASIS")
        }
        val before = beforeBytes ?: return unverifiable(Kind.BYTES, c, "缺 before 快照，$UNVERIFIED_NO_BASIS")
        val after = afterBytes ?: return unverifiable(Kind.BYTES, c, "缺 after 快照，$UNVERIFIED_NO_BASIS")
        val diff = if (tense == ByteTense.FREED) before - after else after - before
        return judge(Kind.BYTES, c, BigDecimal(diff), "$mode 口径：before=$before after=$after")
    }

    /** 「改了 K 个文件」：changedPaths＝git diff --name-only 的行或账本 touched 集合（去重后点数）。 */
    fun checkFilesChanged(claimed: Long, changedPaths: Collection<String>?): Result {
        val c = BigDecimal(claimed)
        if (changedPaths == null) {
            return unverifiable(Kind.FILES_CHANGED, c, "没有 git diff --name-only 输出也没有账本 touched 集合，$UNVERIFIED_NO_BASIS")
        }
        val set = changedPaths.map { canonical(it) }.toSortedSet()
        return judge(Kind.FILES_CHANGED, c, BigDecimal(set.size.toLong()), "changed 集合 ${set.size} 项（去重后）")
    }

    // ── 终答自称解析（只做算术类自称；词表按 335 真实终答校准）───────────────────

    private val SENT_BREAK = charArrayOf('。', '！', '？', '?', '!', '\n', '；', ';')
    private val NUMBER = Regex("""\d[\d,，]*(?:\.\d+)?""")
    private val PATH = Regex("""[\w\-一-龥]+\.(?:txt|md|csv|tsv|json|log)""")
    private val SUM_WORDS = listOf("总和", "合计", "相加得", "相加", "和是")
    private const val AVG_WORD = "平均"

    /** 每个句子、每个关键词只取其后第一个数（自称值自带的小数位就是精度口径）。 */
    fun claimsIn(text: String): List<NumericClaim> {
        val out = ArrayList<NumericClaim>()
        val seen = HashSet<String>()
        if (text.isBlank()) return out
        for (raw in text.split(*SENT_BREAK)) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            // 同句可以同时有 sum 与 avg 自称（335 真实终答：「合计 49430，平均 4493.6」——两个都要对）
            val sumWord = SUM_WORDS.firstOrNull { s.contains(it) }
            if (sumWord != null) {
                claimOf(s, sumWord, Kind.SUM)?.let { c ->
                    if (seen.add("${c.kind.wire}|${fmt(c.claimed)}|${c.artifact}")) out += c
                }
            }
            if (s.contains(AVG_WORD)) {
                claimOf(s, AVG_WORD, Kind.AVG)?.let { c ->
                    if (seen.add("${c.kind.wire}|${fmt(c.claimed)}|${c.artifact}")) out += c
                }
            }
        }
        return out
    }

    private fun claimOf(sentence: String, keyword: String, kind: Kind): NumericClaim? {
        val kwAt = sentence.indexOf(keyword)
        if (kwAt < 0) return null
        val after = sentence.substring(kwAt + keyword.length, minOf(sentence.length, kwAt + keyword.length + 16))
        val m = NUMBER.find(after) ?: return null
        val claimed = BigDecimal(m.value.replace("，", "").replace(",", ""))
        val numEnd = kwAt + keyword.length + m.range.last
        // 数字之后的路径＝自称写入的产物；数字之前的路径＝来源点名（不做产物）
        val artifact = PATH.findAll(sentence).lastOrNull { it.range.first > numEnd }?.value
        return NumericClaim(kind, claimed, artifact, sentence)
    }

    /**
     * 终答值级验收：解析算术自称 → 定位来源（子句点名的数字文件优先，否则取工作区唯一数字文件，
     * 产物与 exclude 不做来源——不猜）→ 重算 → 比自称 → 比产物。
     */
    fun verifyAnswer(text: String, workspace: File, exclude: Set<String> = emptySet()): List<Result> {
        val claims = claimsIn(text)
        if (claims.isEmpty()) return emptyList()
        val out = ArrayList<Result>(claims.size)
        for (c in claims) {
            val excl = exclude + listOfNotNull(c.artifact)
            out += verifyArithmetic(c, workspace, excl)
        }
        return out
    }

    private fun verifyArithmetic(c: NumericClaim, workspace: File, exclude: Set<String>): Result {
        val source = resolveNumericSource(workspace, exclude, c.sentence)
        if (source == null) {
            val why = if (numericSources(workspace, exclude).size > 1) UNVERIFIED_AMBIGUOUS else UNVERIFIED_NO_SOURCE
            return unverifiable(c.kind, c.claimed, why)
        }
        val srcFile = File(workspace, source)
        val base = if (c.kind == Kind.SUM) checkSum(c.claimed, srcFile) else checkAvg(c.claimed, srcFile)
        if (base.status == Status.UNVERIFIABLE) return base
        var status = base.status
        var detail = base.detail
        val artifact = c.artifact?.let { resolveArtifact(workspace, it) }
        if (c.artifact != null) {
            when {
                artifact == null -> {
                    status = worst(status, Status.UNVERIFIABLE)
                    detail += "；自称产物 ${c.artifact} 在工作区找不到，产物腿判不了"
                }
                else -> {
                    val av = readFirstNumber(artifact)
                    if (av == null) {
                        status = worst(status, Status.UNVERIFIABLE)
                        detail += "；产物 ${c.artifact} 里读不出数，产物腿判不了"
                    } else if (!eq(av, base.actual!!)) {
                        status = Status.FAILED
                        detail += "；产物 ${c.artifact} 里是 ${fmt(av)}、重算 ${fmt(base.actual!!)}"
                    }
                }
            }
        }
        return Result(c.kind, c.claimed, base.actual, status, base.source, detail)
    }

    // ── 机械件（全部本地、全部确定性）─────────────────────────────────────────

    private fun judge(kind: Kind, claimed: BigDecimal, actual: BigDecimal, source: String): Result =
        if (eq(claimed, actual)) {
            Result(kind, claimed, actual, Status.VERIFIED, source, "重算 ${fmt(actual)}，与自称一致")
        } else {
            val diff = claimed.subtract(actual).abs()
            Result(kind, claimed, actual, Status.FAILED, source, "我说 ${fmt(claimed)}、实际 ${fmt(actual)}，差 ${fmt(diff)}")
        }

    private fun unverifiable(kind: Kind, claimed: BigDecimal?, why: String): Result =
        Result(kind, claimed, null, Status.UNVERIFIABLE, why, "判不了：$why")

    /** 精确相等（硬规矩②）：compareTo 忽略标度，59220 与 59220.0 是同一个数；除此之外没有中间地带。 */
    private fun eq(a: BigDecimal, b: BigDecimal): Boolean = a.compareTo(b) == 0

    private fun worst(a: Status, b: Status): Status =
        if (a == Status.FAILED || b == Status.FAILED) Status.FAILED
        else if (a == Status.UNVERIFIABLE || b == Status.UNVERIFIABLE) Status.UNVERIFIABLE
        else Status.VERIFIED

    private fun fmt(v: BigDecimal): String = v.toPlainString()

    /** 来源定位：子句点名且真实是数字文件的优先；否则工作区唯一数字文件；都不行 ⇒ null（不猜）。 */
    private fun resolveNumericSource(workspace: File, exclude: Set<String>, sentence: String): String? {
        val named = PATH.findAll(sentence)
            .map { it.value }
            .firstOrNull { p -> !exclude.any { e -> samePath(p, e) } && isNumericFile(File(workspace, p)) }
        if (named != null) return named
        val all = numericSources(workspace, exclude)
        return if (all.size == 1) all[0] else null
    }

    private fun numericSources(root: File, exclude: Set<String>): List<String> =
        walkFiles(root).filter { rel -> !exclude.any { e -> samePath(rel, e) } && isNumericFile(File(root, rel)) }

    private fun samePath(a: String, b: String): Boolean {
        val x = canonical(a)
        val y = canonical(b)
        return x == y || x.endsWith("/$y") || y.endsWith("/$x")
    }

    private fun canonical(p: String): String = p.trim().replace('\\', '/').trimEnd('/')

    private fun walkFiles(root: File): List<String> {
        val out = ArrayList<String>()
        if (root.isDirectory) walkInto(root, "", 0, out)
        return out
    }

    /** 深度 ≤4；隐藏目录与「备份」不进；.bak、超大文件、.baopu-spill 不做来源。 */
    private fun walkInto(dir: File, prefix: String, depth: Int, out: MutableList<String>) {
        if (depth > 4) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in children) {
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) {
                if (f.name == "备份" || f.name.startsWith(".")) continue
                walkInto(f, rel, depth + 1, out)
            } else {
                val skip = f.name.endsWith(".bak") || f.length() > MAX_SOURCE_BYTES ||
                    rel.contains(".baopu-spill/")
                if (!skip) out += rel
            }
        }
    }

    /** 数字文件＝非空行 ≥1 且其中 ≥一半整行可解析为 BigDecimal（散文报告判否）。 */
    private fun isNumericFile(f: File): Boolean {
        if (!f.isFile || f.length() > MAX_SOURCE_BYTES) return false
        val lines = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrNull() ?: return false
        if (lines.isEmpty()) return false
        val numeric = lines.count { it.trim().toBigDecimalOrNull() != null }
        return numeric * 2 >= lines.size
    }

    private fun readNumbers(f: File): List<BigDecimal>? = runCatching {
        f.readLines().mapNotNull { it.trim().toBigDecimalOrNull() }
    }.getOrNull()

    private fun countRecords(f: File, format: RecordFormat): Long? = runCatching {
        val lines = f.readLines().filter { it.isNotBlank() }
        when (format) {
            RecordFormat.JSONL, RecordFormat.LINES -> lines.size.toLong()
            RecordFormat.CSV -> (lines.size - 1).coerceAtLeast(0).toLong() // 表头一行不算数据
        }
    }.getOrNull()

    private fun readFirstNumber(f: File): BigDecimal? = runCatching {
        if (f.length() > MAX_ARTIFACT_BYTES) return@runCatching null
        NUMBER.find(f.readText())?.value?.let { BigDecimal(it.replace("，", "").replace(",", "")) }
    }.getOrNull()

    private fun resolveArtifact(root: File, name: String): File? {
        val direct = File(root, name)
        if (direct.isFile) return direct
        val hit = walkFiles(root).firstOrNull { rel -> samePath(rel, name) }
        return hit?.let { File(root, it) }?.takeIf { it.isFile }
    }

    internal const val UNVERIFIED_NO_SOURCE = "无来源（工作区内没有可定位的数字文件）"
    internal const val UNVERIFIED_AMBIGUOUS = "来源不唯一（多个数字文件，不猜）"
    internal const val UNVERIFIED_NO_BASIS = "没有对账依据（账本/文件系统/DB 三者都没给）"
    private const val MAX_SOURCE_BYTES = 8L * 1024 * 1024
    private const val MAX_ARTIFACT_BYTES = 1L * 1024 * 1024
}
