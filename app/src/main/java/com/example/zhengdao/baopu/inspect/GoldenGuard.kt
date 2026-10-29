// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百七十九批 §2：金吾卫——**用户点名**的全量巡一遍，出「结论级报告」。
//
// 为什么另立 [PatrolDigest] 而不塞进 [PatrolReport]：三行是**御案卡**的形制
// （人 3 秒看完、要拍板），`InspectLimits.MAX_REPORT_LINES = 3` 那条负例钉的就是它。
// 报告不是卡——塞进去要么改常量（拆 239 的负例），要么把报告压成三行（等于没有报告）。
//
// 三块（默认只显示前两块，第三块可展开）：
//   ① 结论（≤3 行：巡了哪些／有没有问题／要不要你管）
//   ② 计数表（查了 N 条记录／M 条轨迹／K 条其它证据，命中几处）
//   ③ 证据清单（每条 issue：证据行 id ＋ lawRef ＋ 一句话）
//
// 四条硬绑：
// - 每条 issue **必须带 evidence**（L14：没有证据的结论不许进报告，构造期就拒）；
// - 报告是**输出不是卡**：不占御案额度，也不许自己升级成弹卡（[GoldenGuard.sweep] 只返回
//   报告对象，不碰 `OpinionWiring`、不碰 `Budget`）；
// - 报告里发现的问题要人管，走 `Opinion` → 延后队列 → 御案（那才受三行约束）；
// - 留痕只记报告本体：`金吾卫报告.jsonl` 一行一份，供暗卫事后抽查。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 报告的形制上限（单里的数；**另立，不复用**三行红线）。 */
internal object DigestLimits {

    /** 结论块 ≤3 行：这是「报告的开头」，不是御案卡本身。 */
    const val CONCLUSION_MAX_LINES = 3

    /** 计数表最多几行（多出来说明有人在往里塞明细）。 */
    const val MAX_COUNTERS = 6

    /** 一份报告留痕最多存几条 issue（证据清单可折叠，但不许无限长）。 */
    const val MAX_ISSUES = 200
}

/** 报告里的一条问题（[evidence] 非空是构造期硬要求＝L14）。 */
internal data class DigestIssue(
    val id: String,
    /** 一句话（≤40 字，构造期钳）。 */
    val gist: String,
    val lawRef: LawRef,
    val evidence: List<String>,
) {
    init {
        require(evidence.isNotEmpty()) { "L14：没有证据的结论不许进报告" }
        require(evidence.none { it.isBlank() }) { "证据 id 不许是空白" }
    }
}

/** 金吾卫对某一条暗卫记录的复核结论（互查的正向：结论可被复核）。 */
internal enum class ReviewVerdict(val wire: String, val cn: String) {
    /** 属实。 */
    CONFIRMED("confirmed", "属实"),

    /** 误报（进误报率分子）。 */
    FALSE_POSITIVE("false_positive", "误报"),

    /** 查不了（**不进误报率分母，也不进分子**）。 */
    NO_DATA("no_data", "查不了"),
    ;

    companion object {
        fun ofWire(wire: String): ReviewVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

internal data class DigestReview(
    /** 复核的是哪一轮暗卫（[NightRound.id]）。 */
    val roundId: String,
    val atMs: Long,
    val verdict: ReviewVerdict,
    val evidence: List<String> = emptyList(),
)

/**
 * 结论级报告本体。
 *
 * [visibleBlocks] 才是默认给眼睛看的两块；[issues] 要展开才显示——
 * 「默认只显示前两块」是形制，不是界面的临时决定。
 */
internal data class PatrolDigest(
    val id: String,
    val atMs: Long,
    val conclusion: List<String>,
    val counters: List<String>,
    val issues: List<DigestIssue>,
    val reviews: List<DigestReview> = emptyList(),
) {
    /** 默认显示的两块（结论＋计数表）。 */
    val visibleBlocks: List<String> get() = conclusion + counters

    /** 三块齐不齐（门禁与用例都拿它断言，不各自数一遍）。 */
    val fitsForm: Boolean
        get() = conclusion.size in 1..DigestLimits.CONCLUSION_MAX_LINES &&
            counters.size in 1..DigestLimits.MAX_COUNTERS &&
            (conclusion + counters).all { it.length <= InspectLimits.MAX_LINE_CHARS } &&
            issues.size <= DigestLimits.MAX_ISSUES
}

internal object GoldenGuard {

    fun idOf(atMs: Long): String = "guard:$atMs"

    /**
     * 全量巡一遍并出报告。
     *
     * 走的是**用户点名**那条入口（[PatrolRunner.run] `manual = true`：六项全跑，不抽样）；
     * 与暗卫的抽样是两回事。这里不写业务状态、不弹卡、不占御案额度。
     */
    fun sweep(ctx: Context, nowMs: Long = System.currentTimeMillis()): PatrolDigest {
        val result = PatrolRunner.run(ctx, manual = true, nowMs = nowMs)
        val digest = digest(result.report.findings, emptyList(), nowMs)
        PatrolSources.memoryRoot()?.let { GuardLedger.append(it, digest) }
        return digest
    }

    /**
     * 由六项勘验的明细造报告（纯函数；用例从这里进）。
     *
     * 没有证据的命中**不入清单**，但在计数表里露出条数——藏起来就成了「查过没问题」。
     */
    fun digest(
        findings: List<InspectFinding>,
        reviews: List<DigestReview>,
        nowMs: Long,
    ): PatrolDigest {
        val hits = findings.filter { it.verdict == InspectVerdict.HIT }
        val noData = findings.count { it.verdict == InspectVerdict.NO_DATA }
        val issues = hits.mapIndexedNotNull { index, finding -> issueOf(finding, index + 1, nowMs) }
        val noEvidence = hits.size - issues.size
        val kept = issues.take(DigestLimits.MAX_ISSUES)
        return PatrolDigest(
            id = idOf(nowMs),
            atMs = nowMs,
            conclusion = conclusionOf(findings.size, hits.size, noData, noEvidence),
            counters = countersOf(findings, kept, reviews, noEvidence),
            issues = kept,
            reviews = reviews,
        )
    }

    /** 一条命中 ⇒ 一条 issue（没证据 ⇒ null，计进「无证据」那一格）。 */
    private fun issueOf(finding: InspectFinding, number: Int, atMs: Long): DigestIssue? {
        if (finding.evidence.isEmpty()) return null
        return DigestIssue(
            id = "guard:$atMs#$number",
            gist = InspectText.line(finding.oneLine),
            lawRef = LawRef.NONE,
            evidence = finding.evidence.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING),
        )
    }

    /** 结论那三行（少于三行也合法：没关系要报时不该凑行数）。 */
    private fun conclusionOf(total: Int, hits: Int, noData: Int, noEvidence: Int): List<String> {
        val shown = hits - noEvidence
        val head = InspectText.line("巡 $total 项：命中 $hits，查不了 $noData")
        val ask = InspectText.line(
            when {
                hits == 0 -> "不用你管：这一趟没查出问题"
                // 命中却一条证据都没有：不许说「见证据清单」（那儿是空的）
                shown == 0 -> "命中 $hits 处，但都无证据，不入清单（L14）"
                else -> "有 $shown 处要你看（见证据清单）"
            },
        )
        val lines = mutableListOf(head, ask)
        if (shown > 0 && noEvidence > 0) lines += InspectText.line("另有 $noEvidence 处无证据，未入清单（L14）")
        return lines
    }

    /** 计数表（数字全部来自这次的真数据，不估算）。 */
    private fun countersOf(
        findings: List<InspectFinding>,
        issues: List<DigestIssue>,
        reviews: List<DigestReview>,
        noEvidence: Int,
    ): List<String> {
        val evidence = issues.flatMap { it.evidence }
        val decisions = evidence.count { it.startsWith("${PatrolRecord.KIND_DECISION}:") }
        val traces = evidence.count { it.startsWith("${PatrolRecord.KIND_TRACE}:") }
        val others = evidence.size - decisions - traces
        val falsePositives = reviews.count { it.verdict == ReviewVerdict.FALSE_POSITIVE }
        val noData = findings.count { it.verdict == InspectVerdict.NO_DATA }
        // 这里**没有**「查了 N 条」：`PatrolRunner.run` 只回传六项结论、不回传池子大小，
        // 自己凑一个数就是假账（「查过没问题」和「没查」必须分得开）。等编排壳回传池子读数再补。
        return listOf(
            InspectText.line("命中证据：记录 $decisions／轨迹 $traces／其它 $others"),
            InspectText.line("命中 ${issues.size} 处，无证据 $noEvidence 处未入清单"),
            InspectText.line("复核暗卫 ${reviews.size} 条：误报 $falsePositives 条"),
            InspectText.line("六项里查不了 $noData 项"),
        )
    }
}

/** 金吾卫报告台账（`金吾卫报告.jsonl`；一行一份，供暗卫事后抽查）。 */
internal object GuardLedger {

    const val FILE_NAME = "金吾卫报告.jsonl"

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    @Synchronized
    fun append(globalRoot: File, digest: PatrolDigest): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(toJson(digest).toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun read(globalRoot: File, limit: Int = 50): List<PatrolDigest> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull { parse(it) }
            .sortedByDescending { it.atMs }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())

    fun toJson(d: PatrolDigest): JSONObject = JSONObject()
        .put("id", d.id)
        .put("at", d.atMs)
        .put("conclusion", JSONArray(d.conclusion))
        .put("counters", JSONArray(d.counters))
        .put(
            "issues",
            JSONArray().apply {
                d.issues.forEach { i ->
                    put(
                        JSONObject()
                            .put("id", i.id)
                            .put("gist", i.gist)
                            .put("law", i.lawRef.name)
                            .put("evidence", JSONArray(i.evidence)),
                    )
                }
            },
        )
        .put(
            "reviews",
            JSONArray().apply {
                d.reviews.forEach { r ->
                    put(
                        JSONObject()
                            .put("round", r.roundId)
                            .put("at", r.atMs)
                            .put("verdict", r.verdict.wire)
                            .put("evidence", JSONArray(r.evidence)),
                    )
                }
            },
        )

    fun parse(raw: String): PatrolDigest? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        return PatrolDigest(
            id = id,
            atMs = o.optLong("at", 0L),
            conclusion = strings(o, "conclusion"),
            counters = strings(o, "counters"),
            issues = objects(o, "issues").mapNotNull { i ->
                val iid = i.optString("id")
                val evidence = strings(i, "evidence")
                if (iid.isEmpty() || evidence.isEmpty()) return@mapNotNull null
                DigestIssue(
                    id = iid,
                    gist = i.optString("gist"),
                    lawRef = runCatching { LawRef.valueOf(i.optString("law", "NONE")) }
                        .getOrDefault(LawRef.NONE),
                    evidence = evidence,
                )
            },
            reviews = objects(o, "reviews").mapNotNull { r ->
                val verdict = ReviewVerdict.ofWire(r.optString("verdict")) ?: return@mapNotNull null
                DigestReview(
                    roundId = r.optString("round"),
                    atMs = r.optLong("at", 0L),
                    verdict = verdict,
                    evidence = strings(r, "evidence"),
                )
            },
        )
    }

    private fun strings(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
    }

    private fun objects(o: JSONObject, key: String): List<JSONObject> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
    }
}
