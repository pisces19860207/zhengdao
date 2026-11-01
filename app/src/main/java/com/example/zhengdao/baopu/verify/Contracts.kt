// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百三十二批 · 任务型验收契约：**四类任务各自「怎么算完成」**（纯函数，零模型、零联网）。
//
// 为什么要有这张表：340 的 `ValueCheck` 只管「说的数对不对」，302 的 `ChancellorGate` 管「交付物
// 在不在、声称有没有账」，两处都**不回答**「这一单到底算不算做完」——因为「做完」的标准随任务类型变：
// 拷文件看的是路径集合与字节，写文档看的是结构与用户给的检查项，改代码看的是编译/测试/diff/预算，
// 做表看的是行列数与抽样。用同一把尺子量四类活，只会量出「看着差不多」。
//
// 六种证据（342 `EvidenceKind`：exists／sha256／ci／test_xml／ledger／law_gate）**不是万能**：
// 它们是**载体**，不是**判据**。sha256 一致只说明字节相同，不说明内容合你意——
// 「把 A 抄成 B」和「把 A 抄成 A」在这六种证据里长得一模一样；
// 「文档存在且哈希对得上」也不说明用户要的五条检查项打了几条勾。
// 所以本文件的做法是：**每类任务列自己的判据**，六种证据只是判据的取数来源；
// 证据类型对不上契约，就判 UNVERIFIABLE（见 `ContractSelector`），绝不硬套。
//
// 四类任务 × 判据（闭集，改集合=改口径；`ContractsTest` 钉死条数与两两不相交）：
//   文件整理/复制  path_set 路径集合精确相等／byte_identical 源目标 size+hash 一致／
//                  no_escape 无逃逸（`..`、绝对路径、盘符、符号链接都不许出根）／ledger_complete 账行齐
//   文档/内容产出  exists 存在／structure 结构校验／user_checklist 用户给的检查项逐条打勾
//   代码修改       compile 编译通过／tests_green 相关测试绿／diff_in_scope diff 落在声明范围／
//                  budget 无新增超预算
//   数据/表格      shape 行列数符合／sample_5 抽样 5 行与源一致／reopenable 输出可被再次读取
//
// 三条硬规矩（派工单 332 原文，`ContractsTest` 各带造红负例）：
//  ① **类型不匹配 ⇒ UNVERIFIABLE**：拿复制证据去判文档，不是「勉强算过」，是「判不了」——不许硬套；
//  ② **契约列了但拿不到证据 ⇒ FAIL(missing_evidence)**：缺证据是「不过」，不是「跳过」也不是「待定」；
//  ③ **禁止「六项满足任意三项就算过」**：判据是**合取**（AND），一条不过就是不过；
//     更禁止输出综合评分——本文件里**根本没有**分数/权重/比例这类字段（反射测试反钉）。
//
// 三态口径（与 302/340 一致，汇总优先级写死）：
//   PASS          判据全过；
//   FAIL          任一判据不过（[Reason.VALUE_MISMATCH]）或任一判据拿不到证据（[Reason.MISSING_EVIDENCE]）；
//                 两者同时出现时**顶层原因报缺证据**（它让「剩下的到底过没过」也不可信），
//                 但每一条的明细都在 `checks` 里，一条都不许吞；
//   UNVERIFIABLE  判不了（只有类型不匹配这一种，由 `ContractSelector` 判）。
//
// 与 342 的分工（不许越界）：342 的 `TaskContract` 只**承载声明**（目标/交付/授权/预算/证据/出口），
// 明写「任务类型×契约表归 332，本层不做判定」；本文件就是那张表，**不碰 :core、不碰协议**。
//
// 取数纪律（照 340 硬规矩③「不许模型重数」）：[sha256Of]／[measureCopy]／[readDoc] 由本文件代码
// 亲自读盘算，调用方**不许**自己填哈希与字节数——填得进去就等于没验。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.FileOpKind
import java.io.File
import java.security.MessageDigest

// ── 闭集词表 ─────────────────────────────────────────────────────────────

/** 四类任务（闭集）。新增一类 = 新增一整列判据，不许拿别的类型的判据凑。 */
internal enum class TaskType(val wire: String, val cn: String) {
    FILE_ORGANIZE("file_organize", "文件整理/复制"),
    DOC_PRODUCE("doc_produce", "文档/内容产出"),
    CODE_CHANGE("code_change", "代码修改"),
    DATA_TABLE("data_table", "数据/表格"),
}

/** 判据编号（闭集，14 条，四类各占一段；两两不相交 ⇒ 结构上就没法跨类型套用）。 */
internal enum class CheckId(val wire: String, val cn: String) {
    // 文件整理/复制
    PATH_SET("path_set", "路径集合精确相等"),
    BYTE_IDENTICAL("byte_identical", "源目标 size/hash 一致"),
    NO_ESCAPE("no_escape", "无逃逸"),
    LEDGER_COMPLETE("ledger_complete", "账行齐"),

    // 文档/内容产出
    EXISTS("exists", "产物存在"),
    STRUCTURE("structure", "结构校验"),
    USER_CHECKLIST("user_checklist", "用户给的检查项逐条打勾"),

    // 代码修改
    COMPILE("compile", "编译通过"),
    TESTS_GREEN("tests_green", "相关测试绿"),
    DIFF_IN_SCOPE("diff_in_scope", "diff 落在声明范围"),
    BUDGET("budget", "无新增超预算"),

    // 数据/表格
    SHAPE("shape", "行列数符合"),
    SAMPLE_5("sample_5", "抽样 5 行与源一致"),
    REOPENABLE("reopenable", "输出可被再次读取"),
    ;

    companion object {
        fun ofWire(raw: String): CheckId? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
    }
}

/** 三态。`UNVERIFIABLE` 只有一个来源：证据类型与契约不匹配（不许硬套）。 */
internal enum class Status(val cn: String) {
    PASS("PASS（过）"),
    FAIL("FAIL（不过）"),
    UNVERIFIABLE("UNVERIFIABLE（判不了）"),
}

/** 不过的原因码（口径闭集；`OK` 只与 PASS 同行）。 */
internal enum class Reason(val wire: String, val cn: String) {
    OK("ok", "全过"),
    MISSING_EVIDENCE("missing_evidence", "缺证据"),
    VALUE_MISMATCH("value_mismatch", "对不上"),
    TYPE_MISMATCH("type_mismatch", "证据类型不匹配"),
}

/** 一条判据的判定结果。[missing] 与 [ok] 互斥：缺证据的判据一定不算过。 */
internal data class CheckOutcome(
    val id: CheckId,
    val ok: Boolean,
    val missing: Boolean,
    /** PASS＝读数；不过＝「我说 X、实际 Y」；缺证据＝缺什么。 */
    val detail: String,
)

/**
 * 一张验收单（本文件唯一的产出）。
 *
 * 刻意**没有** score／grade／weight／ratio 之类字段：四类判据不是同一种东西，加权平均出来的数字
 * 只能骗人（派工单 332 硬规矩③）。反射测试反钉这条——想加分数，先过测试。
 */
internal data class ContractReport(
    /** 声明的任务类型；连类型都没声明时为 null。 */
    val taskType: TaskType?,
    val status: Status,
    val reason: Reason,
    /** 判据逐条结果，顺序＝契约声明顺序；类型不匹配时为空（一条都没判）。 */
    val checks: List<CheckOutcome>,
    /** 一句话口径说明（为什么这么判）。 */
    val note: String,
) {
    val missing: List<CheckId> get() = checks.filter { it.missing }.map { it.id }
    val failed: List<CheckId> get() = checks.filter { !it.ok && !it.missing }.map { it.id }

    /** 人话报告（进验收单/回灌用）；不含分数、不含比例。 */
    fun render(): String {
        val sb = StringBuilder()
        sb.append("任务型验收：").append(status.cn)
        if (reason != Reason.OK) sb.append("（").append(reason.cn).append("）")
        sb.append('\n').append("  任务类型：").append(taskType?.cn ?: "未声明").append('\n')
        if (checks.isEmpty()) {
            sb.append("  判据：一条都没判 —— ").append(note).append('\n')
            return sb.toString().trimEnd()
        }
        sb.append("  判据 ").append(checks.size).append(" 条（全过才算过，不投票、不评分）：\n")
        for (c in checks) {
            sb.append("    ").append(mark(c)).append(' ').append(c.id.cn).append(" —— ").append(c.detail).append('\n')
        }
        sb.append("  口径：").append(note).append('\n')
        return sb.toString().trimEnd()
    }

    private fun mark(c: CheckOutcome): String = when {
        c.missing -> "[缺证据]"
        c.ok -> "[过]"
        else -> "[不过]"
    }
}

// ── 证据（四类各一种；类型写在类型上，不在字符串里猜） ──────────────────────

/**
 * 一类任务的证据。**类型即族**：`Copy` 只能喂给「文件整理/复制」契约，喂错了判 UNVERIFIABLE。
 *
 * 每条判据要的读数在下面各自的 data class 里；**取不到的用 null 表示**（`compile`／`tests`／
 * `budget`／`reopen`），null ⇒ 那条判据 FAIL(missing_evidence)，而不是「没这一条」。
 */
internal sealed class Evidence {

    abstract val taskType: TaskType

    // ── 文件整理/复制 ────────────────────────────────────────────────────

    /** 一个文件的源/目标读数（由 [measureCopy] 读盘填，调用方不许自己编）。 */
    data class CopyPair(
        val rel: String,
        val srcSize: Long,
        val srcSha256: String,
        val dstSize: Long,
        val dstSha256: String,
    )

    /**
     * @param sourcePaths 源侧应有清单（相对 [sourceRoot]；**必须是原始串、不许预先归一化**——
     *   归一化会吃掉前导 `/`，绝对路径就看不出来了，逃逸判据当场失效，见 [measureCopy] 的注释）
     * @param targetPaths 目标侧**实际扫到**的清单（相对 [destRoot]，由 [measureCopy] 扫盘得到）
     * @param pairs       逐文件读数；缺哪个文件就没有哪条
     * @param canonicalEscapes 真实路径（解过符号链接）落在目标根之外的相对路径
     * @param ledger      账行（真账本行，不是自报字符串）
     */
    data class FileCopy(
        val sourceRoot: String,
        val destRoot: String,
        val sourcePaths: List<String>,
        val targetPaths: List<String>,
        val pairs: List<CopyPair>,
        val canonicalEscapes: List<String>,
        val ledger: List<FileOpEntry>,
    ) : Evidence() {
        override val taskType: TaskType = TaskType.FILE_ORGANIZE
    }

    // ── 文档/内容产出 ────────────────────────────────────────────────────

    /** 一个小节（标题层级＋正文非空白字符数，由 [readDoc] 解析）。 */
    data class Section(val level: Int, val title: String, val bodyChars: Int)

    /** 用户检查项的一条打勾记录；[note] 是「凭什么」——空 note 的打勾算假打勾。 */
    data class Tick(val item: String, val done: Boolean, val note: String)

    /**
     * @param declaredItems 用户原话给的检查项（逐条；少一条都算没打勾）
     * @param ticks         实际打勾记录（键＝检查项原文）
     */
    data class Doc(
        val path: String,
        val exists: Boolean,
        val sizeBytes: Long,
        val sections: List<Section>,
        val declaredItems: List<String>,
        val ticks: List<Tick>,
    ) : Evidence() {
        override val taskType: TaskType = TaskType.DOC_PRODUCE
    }

    // ── 代码修改 ────────────────────────────────────────────────────────

    /** 一次编译的读数。[ok] 与 [exitCode] 打架 ⇒ 判定里直接报自相矛盾。 */
    data class Compile(val task: String, val ok: Boolean, val exitCode: Int)

    /** 一次测试运行的读数（[total]＝跑了多少例；0 例等于没跑，不算证据）。 */
    data class Tests(val suite: String, val total: Int, val failed: Int, val skipped: Int)

    /** 预算读数。[newOver]＝本次新增的超预算条目（空＝没新增）。 */
    data class Budget(val before: Int, val after: Int, val ceiling: Int, val newOver: List<String>)

    /**
     * @param declaredScope 声明范围（租约）：以 `/` 结尾＝目录，否则＝精确文件
     * @param changedPaths  相对基线 commit 的实际改动清单
     */
    data class Code(
        val compile: Compile?,
        val tests: Tests?,
        val declaredScope: List<String>,
        val changedPaths: List<String>,
        val budget: Budget?,
    ) : Evidence() {
        override val taskType: TaskType = TaskType.CODE_CHANGE
    }

    // ── 数据/表格 ───────────────────────────────────────────────────────

    /** 一行抽样：[expected]＝源表该行，[actual]＝产物该行（逐格比）。 */
    data class RowSample(val index: Int, val expected: List<String>, val actual: List<String>)

    /** 再读一次的结果（[parser] 必须写明用什么读的，否则读数不成立）。 */
    data class Reopen(val ok: Boolean, val rows: Int, val cols: Int, val parser: String)

    /**
     * 行列数口径：[declaredRows]／[actualRows] 都是**数据行，不含表头**；列数＝表头列数。
     */
    data class Table(
        val declaredRows: Int,
        val declaredCols: Int,
        val actualRows: Int,
        val actualCols: Int,
        val samples: List<RowSample>,
        val reopen: Reopen?,
    ) : Evidence() {
        override val taskType: TaskType = TaskType.DATA_TABLE
    }
}

// ── 契约表与判定 ─────────────────────────────────────────────────────────

internal object Contracts {

    /** 四类任务各自的判据表（顺序＝报告顺序）。 */
    fun checksOf(type: TaskType): List<CheckId> = when (type) {
        TaskType.FILE_ORGANIZE -> listOf(
            CheckId.PATH_SET, CheckId.BYTE_IDENTICAL, CheckId.NO_ESCAPE, CheckId.LEDGER_COMPLETE,
        )
        TaskType.DOC_PRODUCE -> listOf(
            CheckId.EXISTS, CheckId.STRUCTURE, CheckId.USER_CHECKLIST,
        )
        TaskType.CODE_CHANGE -> listOf(
            CheckId.COMPILE, CheckId.TESTS_GREEN, CheckId.DIFF_IN_SCOPE, CheckId.BUDGET,
        )
        TaskType.DATA_TABLE -> listOf(
            CheckId.SHAPE, CheckId.SAMPLE_5, CheckId.REOPENABLE,
        )
    }

    /** 按证据自身的族判（族由类型给，不从内容猜）。 */
    fun evaluate(evidence: Evidence): ContractReport = when (evidence) {
        is Evidence.FileCopy -> report(evidence.taskType, copyChecks(evidence))
        is Evidence.Doc -> report(evidence.taskType, docChecks(evidence))
        is Evidence.Code -> report(evidence.taskType, codeChecks(evidence))
        is Evidence.Table -> report(evidence.taskType, tableChecks(evidence))
    }

    /**
     * 合取汇总（硬规矩③）：**全部过才 PASS**，没有任何「凑够几条」的口子。
     * 缺证据与对不上同时出现时，顶层原因报缺证据（明细一条不吞）。
     */
    private fun report(type: TaskType, checks: List<CheckOutcome>): ContractReport {
        val missing = checks.count { it.missing }
        val failed = checks.count { !it.ok && !it.missing }
        val reason = when {
            missing > 0 -> Reason.MISSING_EVIDENCE
            failed > 0 -> Reason.VALUE_MISMATCH
            else -> Reason.OK
        }
        val status = if (reason == Reason.OK) Status.PASS else Status.FAIL
        return ContractReport(
            taskType = type,
            status = status,
            reason = reason,
            checks = checks,
            note = "判据 ${checks.size} 条全过才算过；缺一条证据也算不过（合取，不投票、不评分）",
        )
    }

    // ── 文件整理/复制 ────────────────────────────────────────────────────

    private fun copyChecks(e: Evidence.FileCopy): List<CheckOutcome> {
        // 原始串留给「出根判定」：归一化会把开头的 `/` 吃掉，绝对路径就看不出来了。
        val rawSrc = e.sourcePaths.map { it.trim() }.filter { it.isNotEmpty() }
        val rawDst = e.targetPaths.map { it.trim() }.filter { it.isNotEmpty() }
        val src = rawSrc.map(::normPath).filter { it.isNotEmpty() }.toSortedSet()
        val dst = rawDst.map(::normPath).filter { it.isNotEmpty() }.toSortedSet()
        return listOf(
            pathSetCheck(src, dst),
            byteCheck(e.pairs, src),
            escapeCheck(e, rawSrc, rawDst),
            ledgerCheck(e, dst),
        )
    }

    private fun pathSetCheck(src: Set<String>, dst: Set<String>): CheckOutcome {
        if (src.isEmpty()) return missing(CheckId.PATH_SET, "源清单为空：没有可比对的集合")
        val extra = (dst - src).sorted()
        val lost = (src - dst).sorted()
        if (extra.isEmpty() && lost.isEmpty()) {
            return ok(CheckId.PATH_SET, "源 ${src.size} 个、目标 ${dst.size} 个，逐一对上（不多不少）")
        }
        val bits = mutableListOf<String>()
        if (extra.isNotEmpty()) bits += "目标多出 ${brief(extra)}"
        if (lost.isNotEmpty()) bits += "目标少了 ${brief(lost)}"
        return fail(CheckId.PATH_SET, bits.joinToString("；"))
    }

    private fun byteCheck(pairs: List<Evidence.CopyPair>, src: Set<String>): CheckOutcome {
        if (pairs.isEmpty()) return missing(CheckId.BYTE_IDENTICAL, "一个文件的 size/hash 读数都没有")
        val uncovered = (src - pairs.map { normPath(it.rel) }.toSet()).sorted()
        if (uncovered.isNotEmpty()) {
            return missing(CheckId.BYTE_IDENTICAL, "这些文件没有读数：${brief(uncovered)}")
        }
        val absent = pairs.filter { it.srcSize < 0 || it.dstSize < 0 }
        if (absent.isNotEmpty()) {
            return fail(CheckId.BYTE_IDENTICAL, "这些文件读不到：${brief(absent.map { it.rel })}")
        }
        val malformed = pairs.filter { !isSha256(it.srcSha256) || !isSha256(it.dstSha256) }
        if (malformed.isNotEmpty()) {
            return missing(CheckId.BYTE_IDENTICAL, "哈希不是 64 位十六进制，读数不成立：${brief(malformed.map { it.rel })}")
        }
        val bad = pairs.filter { it.srcSize != it.dstSize || !it.srcSha256.equals(it.dstSha256, ignoreCase = true) }
        if (bad.isNotEmpty()) {
            return fail(CheckId.BYTE_IDENTICAL, bad.take(3).joinToString("；", transform = ::describePair))
        }
        return ok(
            CheckId.BYTE_IDENTICAL,
            "${pairs.size} 个文件 size 与 sha256 逐个相等（只证字节相同，不证内容合你意）",
        )
    }

    private fun describePair(p: Evidence.CopyPair): String {
        val sizeBit = if (p.srcSize != p.dstSize) {
            "size 源 ${p.srcSize}、目标 ${p.dstSize}"
        } else {
            "size ${p.srcSize} 相等"
        }
        val shaBit = if (p.srcSha256.equals(p.dstSha256, ignoreCase = true)) {
            "sha 相等"
        } else {
            "sha 源 ${short(p.srcSha256)}、目标 ${short(p.dstSha256)}"
        }
        return "${normPath(p.rel)}：$sizeBit；$shaBit"
    }

    private fun escapeCheck(e: Evidence.FileCopy, rawSrc: List<String>, rawDst: List<String>): CheckOutcome {
        val bad = (rawSrc + rawDst).filter { !isSafeRel(it) }.sorted()
        if (bad.isNotEmpty()) return fail(CheckId.NO_ESCAPE, "这些路径跑出了根：${brief(bad)}")
        if (e.canonicalEscapes.isNotEmpty()) {
            return fail(CheckId.NO_ESCAPE, "真实路径落在目标根之外（符号链接等）：${brief(e.canonicalEscapes.sorted())}")
        }
        return ok(CheckId.NO_ESCAPE, "源 ${rawSrc.size} + 目标 ${rawDst.size} 条路径都在各自根内，真实路径也在")
    }

    private fun ledgerCheck(e: Evidence.FileCopy, dst: Set<String>): CheckOutcome {
        val rows = e.ledger.filter { it.kind == FileOpKind.COPY || it.kind == FileOpKind.MOVE }
        if (rows.isEmpty()) return missing(CheckId.LEDGER_COMPLETE, "账行一条都没有（复制/搬动）")
        val resolved = rows.map { it.path to ledgerRel(it.path, e.destRoot) }
        val outside = resolved.filter { it.second == null }.map { it.first }
        if (outside.isNotEmpty()) return fail(CheckId.LEDGER_COMPLETE, "账行指向目标根之外：${brief(outside.sorted())}")
        val rels = resolved.mapNotNull { it.second }
        val dupes = rels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (dupes.isNotEmpty()) return fail(CheckId.LEDGER_COMPLETE, "同一路径记了多行账：${brief(dupes)}")
        val noRow = (dst - rels.toSet()).sorted()
        if (noRow.isNotEmpty()) return fail(CheckId.LEDGER_COMPLETE, "这些路径没有账行：${brief(noRow)}")
        val ghost = (rels.toSet() - dst).sorted()
        if (ghost.isNotEmpty()) return fail(CheckId.LEDGER_COMPLETE, "账上记了没落地的路径：${brief(ghost)}")
        return ok(CheckId.LEDGER_COMPLETE, "目标 ${dst.size} 条路径与 ${rows.size} 行账一一对应")
    }

    // ── 文档/内容产出 ────────────────────────────────────────────────────

    private fun docChecks(e: Evidence.Doc): List<CheckOutcome> = listOf(
        existsCheck(e),
        structureCheck(e.sections),
        checklistCheck(e.declaredItems, e.ticks),
    )

    private fun existsCheck(e: Evidence.Doc): CheckOutcome {
        if (e.path.isBlank()) return missing(CheckId.EXISTS, "没写产物路径")
        if (!e.exists) return fail(CheckId.EXISTS, "产物不存在：${e.path}")
        if (e.sizeBytes <= 0) return fail(CheckId.EXISTS, "产物是空文件：${e.path}")
        return ok(CheckId.EXISTS, "${e.path}：${e.sizeBytes} 字节")
    }

    private fun structureCheck(sections: List<Evidence.Section>): CheckOutcome {
        if (sections.isEmpty()) return fail(CheckId.STRUCTURE, "没解析到任何标题：结构无从谈起")
        val first = sections.first()
        if (first.level != 1) return fail(CheckId.STRUCTURE, "首个标题是 H${first.level}，应从 H1 起")
        val blank = sections.firstOrNull { it.title.isBlank() }
        if (blank != null) return fail(CheckId.STRUCTURE, "有空标题（H${blank.level}）")
        val jump = sections.zipWithNext().firstOrNull { (a, b) -> b.level - a.level > 1 }
        if (jump != null) {
            val (a, b) = jump
            return fail(CheckId.STRUCTURE, "H${a.level}「${a.title}」下面直接 H${b.level}「${b.title}」——跳级")
        }
        val empty = sections.filter { it.bodyChars <= 0 }
        if (empty.isNotEmpty()) {
            return fail(CheckId.STRUCTURE, "这些小节正文为空：${brief(empty.map { "H${it.level}「${it.title}」" })}")
        }
        return ok(CheckId.STRUCTURE, "${sections.size} 个标题，从 H1 起、层级连续、无空节")
    }

    private fun checklistCheck(declared: List<String>, ticks: List<Evidence.Tick>): CheckOutcome {
        val items = declared.map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return missing(CheckId.USER_CHECKLIST, "用户检查项一条都没交：逐条打勾无从谈起")
        if (ticks.isEmpty()) return missing(CheckId.USER_CHECKLIST, "一条打勾记录都没有")
        val byItem = ticks.associateBy { it.item.trim() }
        val noTick = items.filter { it !in byItem }
        if (noTick.isNotEmpty()) {
            return missing(CheckId.USER_CHECKLIST, "这些检查项没有打勾记录：${brief(noTick)}")
        }
        val picked = items.mapNotNull { byItem[it] }
        val unticked = picked.filter { !it.done }
        if (unticked.isNotEmpty()) {
            return fail(CheckId.USER_CHECKLIST, "没打勾：${brief(unticked.map { it.item })}")
        }
        val noProof = picked.filter { it.note.isBlank() }
        if (noProof.isNotEmpty()) {
            return fail(CheckId.USER_CHECKLIST, "打了勾但没写凭什么：${brief(noProof.map { it.item })}")
        }
        return ok(CheckId.USER_CHECKLIST, "${items.size} 条逐条打勾，每条都带依据")
    }

    // ── 代码修改 ────────────────────────────────────────────────────────

    private fun codeChecks(e: Evidence.Code): List<CheckOutcome> = listOf(
        compileCheck(e.compile),
        testsCheck(e.tests),
        diffCheck(e.declaredScope, e.changedPaths),
        budgetCheck(e.budget),
    )

    private fun compileCheck(c: Evidence.Compile?): CheckOutcome {
        if (c == null) return missing(CheckId.COMPILE, "没有编译读数")
        if (c.task.isBlank()) return missing(CheckId.COMPILE, "编译读数没写是哪个任务")
        if (c.ok != (c.exitCode == 0)) {
            return fail(CheckId.COMPILE, "${c.task}：自报 ok=${c.ok}、退出码 ${c.exitCode}，自相矛盾")
        }
        if (!c.ok) return fail(CheckId.COMPILE, "${c.task} 编译不过：exit=${c.exitCode}")
        return ok(CheckId.COMPILE, "${c.task} exit=0")
    }

    private fun testsCheck(t: Evidence.Tests?): CheckOutcome {
        if (t == null) return missing(CheckId.TESTS_GREEN, "没有测试读数")
        if (t.total <= 0) return missing(CheckId.TESTS_GREEN, "${t.suite}：0 例，等于没跑，不算绿")
        if (t.failed > 0) return fail(CheckId.TESTS_GREEN, "${t.suite}：红 ${t.failed} 例（共 ${t.total} 例）")
        val skip = if (t.skipped > 0) "（skip ${t.skipped} 例）" else ""
        return ok(CheckId.TESTS_GREEN, "${t.suite}：${t.total} 例 0 红$skip")
    }

    private fun diffCheck(scope: List<String>, changed: List<String>): CheckOutcome {
        // 范围保留原始串：末尾那个 `/` 是「目录」的唯一标记，归一化会把它吃掉。
        val scopes = scope.map { it.trim() }.filter { it.isNotEmpty() }
        if (scopes.isEmpty()) return missing(CheckId.DIFF_IN_SCOPE, "没声明范围（租约），无从谈落点")
        val paths = changed.map(::normPath).filter { it.isNotEmpty() }
        if (paths.isEmpty()) return fail(CheckId.DIFF_IN_SCOPE, "diff 为空：这单没有改动")
        val out = paths.filter { p -> scopes.none { covers(it, p) } }.sorted()
        if (out.isNotEmpty()) return fail(CheckId.DIFF_IN_SCOPE, "改动落在声明范围外：${brief(out)}")
        return ok(CheckId.DIFF_IN_SCOPE, "${paths.size} 个改动全在声明的 ${scopes.size} 条范围内")
    }

    private fun budgetCheck(b: Evidence.Budget?): CheckOutcome {
        if (b == null) return missing(CheckId.BUDGET, "没有预算读数")
        if (b.ceiling <= 0) return missing(CheckId.BUDGET, "没给上限，超没超无从判")
        if (b.before < 0) return missing(CheckId.BUDGET, "改动前的数没读到，无法判「新增」")
        if (b.newOver.isNotEmpty()) return fail(CheckId.BUDGET, "新增超预算：${brief(b.newOver)}")
        if (b.after > b.ceiling) return fail(CheckId.BUDGET, "我说 ${b.after}、上限 ${b.ceiling}")
        return ok(CheckId.BUDGET, "${b.before}→${b.after}（上限 ${b.ceiling}），无新增超项")
    }

    // ── 数据/表格 ───────────────────────────────────────────────────────

    private fun tableChecks(e: Evidence.Table): List<CheckOutcome> = listOf(
        shapeCheck(e),
        sampleCheck(e),
        reopenCheck(e),
    )

    private fun shapeCheck(e: Evidence.Table): CheckOutcome {
        if (e.declaredRows <= 0 || e.declaredCols <= 0) {
            return missing(CheckId.SHAPE, "没声明行列数（口径：数据行不含表头）")
        }
        if (e.actualRows < 0 || e.actualCols <= 0) return missing(CheckId.SHAPE, "产物行列数没读出来")
        val rowOk = e.declaredRows == e.actualRows
        val colOk = e.declaredCols == e.actualCols
        if (rowOk && colOk) {
            return ok(CheckId.SHAPE, "${e.actualRows} 行 × ${e.actualCols} 列（不含表头），与声明一致")
        }
        val bits = mutableListOf<String>()
        if (!rowOk) bits += "行：说 ${e.declaredRows}、实际 ${e.actualRows}"
        if (!colOk) bits += "列：说 ${e.declaredCols}、实际 ${e.actualCols}"
        return fail(CheckId.SHAPE, bits.joinToString("；"))
    }

    private fun sampleCheck(e: Evidence.Table): CheckOutcome {
        val expected = sampleRowIndices(e.actualRows)
        if (expected.isEmpty()) return missing(CheckId.SAMPLE_5, "行数没读出来，抽不了样")
        if (e.samples.isEmpty()) return missing(CheckId.SAMPLE_5, "一行都没抽")
        val got = e.samples.map { it.index }.distinct().sorted()
        if (got.size < expected.size) {
            return missing(CheckId.SAMPLE_5, "抽了 ${got.size} 行，契约要 ${expected.size} 行（$expected）")
        }
        if (got != expected) {
            return fail(CheckId.SAMPLE_5, "抽的不是约定的那几行：约定 $expected、实际 $got（挑行＝改口径）")
        }
        val bad = e.samples.filter { it.expected != it.actual }
        if (bad.isNotEmpty()) {
            return fail(
                CheckId.SAMPLE_5,
                bad.take(3).joinToString("；") { "第 ${it.index} 行：源 ${it.expected}、产物 ${it.actual}" },
            )
        }
        return ok(CheckId.SAMPLE_5, "抽 ${expected.size} 行（$expected）逐格与源一致")
    }

    private fun reopenCheck(e: Evidence.Table): CheckOutcome {
        val r = e.reopen ?: return missing(CheckId.REOPENABLE, "没有再读一次的读数")
        if (r.parser.isBlank()) return missing(CheckId.REOPENABLE, "再读用的解析器没写，读数不成立")
        if (!r.ok) return fail(CheckId.REOPENABLE, "${r.parser} 再读失败")
        if (r.rows != e.actualRows || r.cols != e.actualCols) {
            return fail(CheckId.REOPENABLE, "再读得到 ${r.rows}×${r.cols}，产物自报 ${e.actualRows}×${e.actualCols}")
        }
        return ok(CheckId.REOPENABLE, "${r.parser} 再读 ${r.rows}×${r.cols}，与产物一致")
    }

    /**
     * 抽样行号口径（**防挑行**：行号由行数唯一决定，调用方不许自选）。
     *
     * 行数 > 5：首行、末行、再加 1/4、1/2、3/4 三处（去重后正好 5 行）；
     * 行数 ≤ 5：全抽——表只有 3 行却硬要 5 行是强人所难，此时 detail 里写明抽了几行。
     */
    fun sampleRowIndices(rowCount: Int): List<Int> {
        if (rowCount <= 0) return emptyList()
        if (rowCount <= 5) return (0 until rowCount).toList()
        return listOf(0, rowCount / 4, rowCount / 2, rowCount * 3 / 4, rowCount - 1).distinct().sorted()
    }

    // ── 取数（本文件亲自读盘：调用方不许自己填哈希） ──────────────────────

    /** 文件 sha256（小写十六进制 64 位）；读不到返回 null（不是空串——空串会被当成「读数不成立」）。 */
    fun sha256Of(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }.getOrNull()
    }

    /**
     * 扫盘量一份复制/整理证据：目标清单**扫**出来（不是抄源清单），逐文件 size+sha256 亲算，
     * 真实路径（解符号链接）出根的单独记一笔。
     *
     * @param sourcePaths 源侧应有清单（相对 [sourceRoot]；调用方给的是「应该有哪些」，不是「实际有哪些」）
     */
    fun measureCopy(
        sourceRoot: File,
        destRoot: File,
        sourcePaths: List<String>,
        ledger: List<FileOpEntry> = emptyList(),
    ): Evidence.FileCopy {
        // ⚠️ 原始串**原样留着**，不在这里归一化：`normPath` 会把开头的 `/` 吃掉，
        // 一旦在这里规整，"这是绝对路径"这条信息就没了 ⇒ 逃逸永远判不出来。
        // 这正是 CI #1734 在 Linux 上红、Windows 上绿的那条：`/tmp/x/a.txt` 被规整成 `tmp/x/a.txt`，
        // `isSafeRel` 只看到一个"相对路径"；而 Windows 的 `C:\…` 规整后首段 `C:` 还在，DRIVE 正则还能抓。
        val raw = sourcePaths.map { it.trim() }.filter { it.isNotEmpty() }
        val dst = scanFiles(destRoot)
        val pairs = raw.map { rawPath ->
            val rel = normPath(rawPath)
            val s = File(sourceRoot, rel)
            val d = File(destRoot, rel)
            Evidence.CopyPair(
                rel = rel,
                srcSize = if (s.isFile) s.length() else -1L,
                srcSha256 = sha256Of(s).orEmpty(),
                dstSize = if (d.isFile) d.length() else -1L,
                dstSha256 = sha256Of(d).orEmpty(),
            )
        }
        val rootCanon = canon(destRoot)
        val escapes = dst.filter { rel -> !canon(File(destRoot, rel)).startsWith(rootCanon + File.separator) }
        return Evidence.FileCopy(
            sourceRoot = sourceRoot.absolutePath,
            destRoot = destRoot.absolutePath,
            sourcePaths = raw,
            targetPaths = dst,
            pairs = pairs,
            canonicalEscapes = escapes,
            ledger = ledger,
        )
    }

    /** 目标根下的实际文件清单（相对路径、`/` 分隔、升序）；根不存在＝空清单。 */
    private fun scanFiles(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    /** 读一份文档：存在/字节数/标题结构亲算；检查项由用户给、打勾由调用方交（都要原文对得上）。 */
    fun readDoc(file: File, declaredItems: List<String>, ticks: List<Evidence.Tick>): Evidence.Doc {
        val text = if (file.isFile) runCatching { file.readText() }.getOrElse { "" } else ""
        return Evidence.Doc(
            path = file.absolutePath,
            exists = file.isFile,
            sizeBytes = if (file.isFile) file.length() else 0L,
            sections = parseSections(text),
            declaredItems = declaredItems,
            ticks = ticks,
        )
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")

    /** 标题结构（围栏代码块里的 `#` 不算标题——否则贴一段 shell 注释就能造出假结构）。 */
    private fun parseSections(text: String): List<Evidence.Section> {
        val out = mutableListOf<Evidence.Section>()
        var level = 0
        var title = ""
        var body = 0
        var inFence = false
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd()
            if (line.startsWith("```")) {
                inFence = !inFence
                continue
            }
            val m = if (inFence) null else HEADING.find(line)
            if (m != null) {
                if (level > 0) out += Evidence.Section(level, title, body)
                level = m.groupValues[1].length
                title = m.groupValues[2].trim()
                body = 0
            } else if (level > 0) {
                body += line.count { !it.isWhitespace() }
            }
        }
        if (level > 0) out += Evidence.Section(level, title, body)
        return out
    }

    // ── 小工具（路径口径统一走这里，别在各处各写一套） ─────────────────────

    private fun ok(id: CheckId, detail: String) = CheckOutcome(id, ok = true, missing = false, detail = detail)

    private fun fail(id: CheckId, detail: String) = CheckOutcome(id, ok = false, missing = false, detail = detail)

    private fun missing(id: CheckId, detail: String) = CheckOutcome(id, ok = false, missing = true, detail = detail)

    /**
     * 相对路径口径：`\\`→`/`、去掉空段与 `.`；`..` **保留**（留给 [isSafeRel] 抓）。
     *
     * ⚠️ 它**会吃掉前导 `/`**（`/tmp/a` → `tmp/a`）——这是刻意的「只做集合比对」的口径。
     * 所以判绝对路径/出根时**必须喂原始串**给 [isSafeRel]，别拿这里的返回值去判。
     */
    private fun normPath(raw: String): String = raw.trim()
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")

    /** 出根判定（吃**原始串**）：绝对路径、盘符、`..` 段一律不安全；空也算不安全。 */
    private fun isSafeRel(raw: String): Boolean {
        val u = raw.trim().replace('\\', '/')
        if (u.isEmpty()) return false
        if (u.startsWith("/")) return false
        if (DRIVE.containsMatchIn(u)) return false
        return u.split('/').none { it == ".." }
    }

    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * 范围是否覆盖某路径：**原始串**以 `/` 结尾＝目录前缀，否则＝精确文件。
     * 不做通配——通配能把范围悄悄放大（`**` 一写，哪儿都算「声明范围内」）。
     */
    private fun covers(rawScope: String, path: String): Boolean {
        val isDir = rawScope.trim().replace('\\', '/').endsWith("/")
        val s = normPath(rawScope)
        if (s.isEmpty()) return false
        return if (isDir) path.startsWith("$s/") else path == s
    }

    /** 账行路径→相对目标根的路径；落在根外返回 null（绝对路径按原样解释，相对路径按目标根解释）。 */
    private fun ledgerRel(path: String, root: String): String? {
        val f = if (File(path).isAbsolute) File(path) else File(root, path)
        val c = canon(f)
        val r = canon(File(root))
        if (!c.startsWith(r + File.separator)) return null
        return c.removePrefix(r + File.separator).replace('\\', '/')
    }

    private fun canon(f: File): String = runCatching { f.canonicalPath }.getOrElse { f.absolutePath }

    private fun isSha256(s: String): Boolean = s.length == 64 && s.all { it in "0123456789abcdefABCDEF" }

    private fun short(sha: String): String = if (sha.length <= 8) sha.ifEmpty { "（无）" } else sha.take(8)

    private fun brief(items: List<String>): String {
        val head = items.take(3).joinToString("、")
        return if (items.size <= 3) head else "$head…共 ${items.size} 个"
    }
}
