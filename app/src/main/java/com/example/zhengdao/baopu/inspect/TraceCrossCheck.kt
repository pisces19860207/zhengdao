// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十二批 · 旁路对拍器（法典 L28 落地）：RunTrace（引擎旁路轨迹）↔ 208（自报账）双向比对。
//
// 来历：外部对照意见指出「自报账的凭证伪造风险」——208 由执行器自己写，模型可能遗漏或重写；
// RunTrace 在引擎边界已经旁路记了摘要（argsHash＋枚举＋耗时，不存正文），但从没人拿它跟 208 对。
//
// 四维（派工单 §1 那张表；全部计数、来自两侧）：
//   ① 工具调用次数：轨迹 ACT/OK 且工具在写侧名册的行 ↔ 208 的 auto_act「tool:」行
//     （配对：同名＋时刻容差，I1 同一款；配不上任一侧都算差——同数错名也是伪造）；
//   ② 失败分类计数：轨迹 failure_kind 非空的行 ↔ 208 里自认失败的行（认「失败／没通过」字样）；
//   ③ 步数：轨迹去重 step 数 ↔ 208 的步边界行——**208 的写侧契约里没有这类行**，
//     本维永远缺侧 ⇒ 永远报「无法对拍」，绝不因「都是 0」冒充一致（负例①的落点）；
//     本批红线不改 208 写入，哪天 208 有了步边界行，这一维自然转正；
//   ④ 耗时量级：轨迹 THINK 行耗时之和 ↔ 208 该 run 窗口内首尾时刻差（±20% 容差，只比量级）。
//
// 判据：**差一条就报**（不设豁免）；只产出 finding 报给勘验（Opinion source=PATROL, lawRef=L28），
// 不直接打扰用户；**连续 3 次不一致 ⇒ 升格 CRITICAL**（之后走 L21 紧急上报的路）。
// 「连续」＝窗内按 run 起跑顺序、同维的连续 MISMATCH；缺侧与清白都断链
// （没查成的一趟不能算「连续不一致」，升格宁可慢半拍也不许虚张）。
//
// 三条纪律（负例逐条红，见 TraceCrossCheckTest）：
//   1. **只读**：本文件零 IO、零时钟、零 Android；两侧数据调用方折好递进来；
//   2. **缺侧不当一致**：一侧没数据（RunTrace 没记录／208 空／超可见账窗）⇒ 报「无法对拍」；
//   3. **对拍器不写账**：连自己的一本账都不落（勘验层写口由 InspectReadOnlyGateTest 钉着），
//      发现什么只报什么。
//
// 配对口径（①的 like-for-like）：208 的 auto_act 只在「非只读工具执行成功」时落
// （GuardPipeline.finish），轨迹侧就只取 ACT/OK 且工具在写侧名册的行——写侧名册与 I1
// 用同一份事实（PatrolSources.writeToolNames，RiskTable 派生），不另造第二份。
// 轨迹行的相（phase/status）认字面量：那是 TracePhase/TraceStatus 的线值，
// 改一个字母＝老轨迹读不出来（RunTrace.kt 的稳定键纪律），这里照抄不转译。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord

/** 一行轨迹折出的最小形状（从 `run_trace` 表读出；字段名照 BaopuSql.RUN_TRACE_COLUMNS）。 */
internal data class TraceEdge(
    val runId: String,
    val sessionId: Long,
    val seq: Int,
    val step: Int,
    /** TracePhase 线值：THINK／ACT／COMPRESSING。 */
    val phase: String,
    /** TraceStatus 线值：OK／FAILED／REJECTED／CONFIRM／TIMEOUT／SKIPPED／CANCELLED。 */
    val status: String,
    val tool: String,
    /** 毫秒；-1＝没测到（RunTraceRules.UNKNOWN_DURATION_MS，不冒充 0）。 */
    val durationMs: Long,
    /** 失败分类（FailureKind 名；空串＝这次没失败）。 */
    val failureKind: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    /** 轨迹侧证据 id（`trace:<runId>#<seq>`；ReflectionRules 的引用拼法同款）。 */
    val evidenceId: String get() = "trace:$runId#$seq"
}

/** 对拍的四个维度（`wire` 是报告里的稳定键）。 */
internal enum class CrossCheckDimension(val wire: String, val cn: String) {
    TOOL_CALLS("tool_calls", "工具调用次数"),
    FAIL_KINDS("fail_kinds", "失败分类"),
    STEPS("steps", "步数"),
    DURATION("duration", "耗时量级"),
    ;
}

/** 一维的对拍结论（三态闭集）。 */
internal enum class CrossCheckVerdict(val wire: String) {
    /** 两侧对得上。 */
    CLEAR("clear"),

    /** 计数不一致（差一条就算，不设豁免）。 */
    MISMATCH("mismatch"),

    /** 无法对拍（缺侧）：一侧没数据或超可见账窗——**永不算一致**。 */
    MISSING_SIDE("missing_side"),
    ;
}

/** 一维的对拍读数（CLEAR 是读数不是 finding；出报告时只带非 CLEAR 的）。 */
internal data class CrossCheckFinding(
    val runId: String,
    val dimension: CrossCheckDimension,
    val verdict: CrossCheckVerdict,
    val traceCount: Int,
    val ledgerCount: Int,
    /** 两侧证据 id：trace 行 `trace:<runId>#<seq>`，208 行账本 id；缺侧给一行说明。 */
    val evidence: List<String>,
)

/** 一次对拍的全貌（PatrolRunner 挂在巡按结果上；界面与台账要的都在里面）。 */
internal data class CrossCheckPass(
    /** 检了几个 run。 */
    val checked: Int,
    /** 非 CLEAR 的 finding（要报给勘验的那部分）。 */
    val findings: List<CrossCheckFinding>,
    /** 按 270 契约折好的意见（source=PATROL, lawRef=L28）。 */
    val opinions: List<Opinion>,
    /** 一句话读数（进 RunLog，不进 208）。 */
    val summaryLine: String,
)

internal object TraceCrossCheck {

    /** ④ 的容差（±20%，只比量级；派工单 §1 点名的数，别处不许再抄一份）。 */
    const val DURATION_TOLERANCE = 0.20

    /** 连续这么多次不一致 ⇒ 升格 CRITICAL（法典 L28：连续 3 次）。 */
    const val ESCALATE_AFTER = 3

    /**
     * ②的账侧认法：208 里**自认失败**的行（认字样，不猜语义）。
     *
     * 为什么是字样不是 kind：208 的写侧契约里没有「失败」这一类，执行器失败时写下来的行
     * （自改没通过对拍、执行失败降级）都把败况写在 reason 里。认行不认类有一个已知的偏，
     * ——账侧可能**少认**——那只会让 MISMATCH 报得更凶，不会把不一致洗成一致，方向是安全的。
     */
    val FAILURE_MARKERS: List<String> = listOf("失败", "没通过")

    /** ①的配对容差：与 I1 同一个数（同一次工具交换里落账，差的是写队列排队）。 */
    private val PAIR_TOLERANCE_MS = InspectLimits.GAP_TOLERANCE_MS

    /**
     * 对一次 run 做四维对拍（纯函数；**恰好返回四条**，每维一条，CLEAR 也在内）。
     *
     * @param trace 该 run 的全部轨迹行（seq 升序；空 ⇒ 全维缺侧）
     * @param ledger 208 的现读（调用方已按上限读好；**空 ⇒ 全维缺侧**，不许当一致）
     * @param writeNames 写侧工具名册（PatrolSources.writeToolNames，与 I1 同一份）
     */
    fun checkRun(
        runId: String,
        trace: List<TraceEdge>,
        ledger: List<DecisionRecord>,
        writeNames: Set<String>,
    ): List<CrossCheckFinding> {
        if (trace.isEmpty()) {
            return CrossCheckDimension.entries.map { dim ->
                CrossCheckFinding(runId, dim, CrossCheckVerdict.MISSING_SIDE, 0, 0, listOf("轨迹侧 0 行（RunTrace 没记录）"))
            }
        }
        val window = runWindow(trace)
            ?: return CrossCheckDimension.entries.map { dim ->
                CrossCheckFinding(runId, dim, CrossCheckVerdict.MISSING_SIDE, 0, 0, listOf("轨迹没有可信的起止时刻"))
            }
        val (from, to) = window
        val inWindow = ledger.filter { it.atMs in from..to }.sortedBy { it.atMs }
        val beyondWindow = ledger.isNotEmpty() &&
            ledger.minOf { it.atMs } - PAIR_TOLERANCE_MS > from
        val beyondNote = "超出可见账窗（208 最老一行晚于本 run 起点）"
        return listOf(
            toolCalls(runId, trace, ledger, inWindow, writeNames, beyondWindow, beyondNote),
            failKinds(runId, trace, inWindow, ledger, beyondWindow, beyondNote),
            steps(runId, trace),
            duration(runId, trace, inWindow),
        )
    }

    /** run 的窗口：轨迹最早的起点到最晚的终点（208 行落不落得上，只认这个窗）。 */
    fun runWindow(trace: List<TraceEdge>): Pair<Long, Long>? {
        val from = trace.map { it.startedAt }.filter { it > 0 }.minOrNull() ?: return null
        val to = trace.map { it.endedAt }.filter { it > 0 }.maxOrNull() ?: return null
        if (to < from) return null
        return from to to
    }

    // ── ① 工具调用次数 ─────────────────────────────────────────────────────

    private fun toolCalls(
        runId: String,
        trace: List<TraceEdge>,
        ledger: List<DecisionRecord>,
        inWindow: List<DecisionRecord>,
        writeNames: Set<String>,
        beyondWindow: Boolean,
        beyondNote: String,
    ): CrossCheckFinding {
        val traceRows = trace
            .filter { it.phase == "ACT" && it.status == "OK" && it.tool in writeNames }
            .sortedBy { it.startedAt }
        val ledgerRows = inWindow
            .filter { it.kind == DecisionKind.AUTO_ACT && it.evidence.startsWith("tool:") }
            .sortedBy { it.atMs }
        if (beyondWindow) {
            return CrossCheckFinding(
                runId, CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISSING_SIDE,
                traceRows.size, ledgerRows.size, listOf(beyondNote),
            )
        }
        if (ledger.isEmpty()) {
            // 负例①：208 整本空 ⇒ 无法对拍，不许当一致（轨迹侧有动作也一样）。
            if (traceRows.isEmpty()) {
                return CrossCheckFinding(
                    runId, CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISSING_SIDE,
                    0, 0, listOf("两侧都没有写侧动作，且 208 整本空"),
                )
            }
            return CrossCheckFinding(
                runId, CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISSING_SIDE,
                traceRows.size, 0, listOf("208 整本空（没装配或没写出来）"),
            )
        }
        if (ledgerRows.isEmpty()) {
            // 账本有行、但这个 run 窗口里一行都没有：轨迹有写动作 ⇒ 差 N 条（漏账）；
            // 轨迹也没有 ⇒ 两侧都空，清白。
            if (traceRows.isEmpty()) {
                return CrossCheckFinding(
                    runId, CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.CLEAR, 0, 0,
                    listOf("两侧都没有写侧动作"),
                )
            }
            return CrossCheckFinding(
                runId, CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISMATCH,
                traceRows.size, 0,
                traceRows.map { "${it.evidenceId}(${it.tool}) 208 无行" },
            )
        }
        val (unpairedTrace, unpairedLedger) = pair(traceRows, ledgerRows)
        val verdict = if (unpairedTrace.isEmpty() && unpairedLedger.isEmpty()) {
            CrossCheckVerdict.CLEAR
        } else {
            CrossCheckVerdict.MISMATCH
        }
        val evidence = unpairedTrace.map { "${it.evidenceId}(${it.tool})" } +
            unpairedLedger.map { "${it.id}(${it.evidence})" }
        return CrossCheckFinding(
            runId, CrossCheckDimension.TOOL_CALLS, verdict,
            traceRows.size, ledgerRows.size, evidence,
        )
    }

    /**
     * 同名＋时刻最近的贪心配对（I1 同款）；返回两侧**没配上**的行。
     * 差一条就报 ⇒ 配对不成任一侧都算差：同名才算同一次动作，同数错名也是伪造。
     */
    private fun pair(
        traceRows: List<TraceEdge>,
        ledgerRows: List<DecisionRecord>,
    ): Pair<List<TraceEdge>, List<DecisionRecord>> {
        val taken = BooleanArray(ledgerRows.size)
        val looseTrace = mutableListOf<TraceEdge>()
        for (t in traceRows) {
            var best = -1
            var bestDelta = Long.MAX_VALUE
            ledgerRows.forEachIndexed { i, rec ->
                if (taken[i]) return@forEachIndexed
                if (toolOfEvidence(rec.evidence) != t.tool) return@forEachIndexed
                val delta = kotlin.math.abs(rec.atMs - t.endedAt)
                if (delta <= PAIR_TOLERANCE_MS && delta < bestDelta) {
                    bestDelta = delta
                    best = i
                }
            }
            if (best >= 0) taken[best] = true else looseTrace += t
        }
        val looseLedger = ledgerRows.filterIndexed { i, _ -> !taken[i] }
        return looseTrace to looseLedger
    }

    /** 208 的 `tool:<name>:<target>` 房型里取工具名（I1 同一款；只切第一段）。 */
    private fun toolOfEvidence(evidence: String): String? {
        if (!evidence.startsWith("tool:")) return null
        val name = evidence.substring("tool:".length).substringBefore(':').trim()
        return name.ifEmpty { null }
    }

    // ── ② 失败分类 ────────────────────────────────────────────────────────

    private fun failKinds(
        runId: String,
        trace: List<TraceEdge>,
        inWindow: List<DecisionRecord>,
        ledger: List<DecisionRecord>,
        beyondWindow: Boolean,
        beyondNote: String,
    ): CrossCheckFinding {
        val traceRows = trace.filter { it.failureKind.isNotBlank() }
        val ledgerRows = inWindow.filter { rec -> FAILURE_MARKERS.any { rec.reason.contains(it) } }
        if (beyondWindow && traceRows.isNotEmpty()) {
            return CrossCheckFinding(
                runId, CrossCheckDimension.FAIL_KINDS, CrossCheckVerdict.MISSING_SIDE,
                traceRows.size, ledgerRows.size, listOf(beyondNote),
            )
        }
        if (ledger.isEmpty()) {
            // 负例①：208 整本空 ⇒ 无法对拍，不许因「两侧都是 0」当一致。
            return CrossCheckFinding(
                runId, CrossCheckDimension.FAIL_KINDS, CrossCheckVerdict.MISSING_SIDE,
                traceRows.size, 0, listOf("208 整本空（没装配或没写出来）"),
            )
        }
        if (traceRows.isEmpty() && ledgerRows.isEmpty()) {
            return CrossCheckFinding(
                runId, CrossCheckDimension.FAIL_KINDS, CrossCheckVerdict.CLEAR, 0, 0,
                listOf("两侧都没有失败"),
            )
        }
        val verdict = if (traceRows.size == ledgerRows.size) CrossCheckVerdict.CLEAR else CrossCheckVerdict.MISMATCH
        val byKind = traceRows.groupingBy { it.failureKind }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString("×", "轨迹失败：") { (kind, n) -> "$kind$n" }
        val evidence = traceRows.map { "${it.evidenceId}(${it.failureKind})" } +
            ledgerRows.map { "${it.id}(${it.reason.take(20)})" }
        return CrossCheckFinding(
            runId, CrossCheckDimension.FAIL_KINDS, verdict,
            traceRows.size, ledgerRows.size,
            (if (byKind != "轨迹失败：") listOf(byKind) else emptyList()) + evidence,
        )
    }

    // ── ③ 步数 ────────────────────────────────────────────────────────────

    private fun steps(runId: String, trace: List<TraceEdge>): CrossCheckFinding {
        val stepCount = trace.map { it.step }.distinct().size
        // 208 的写侧契约里没有步边界行（翻遍 DecisionKind：没有哪类记录「一步开始了/收了」）。
        // 侧账缺失 ⇒ 按负例①报「无法对拍」，绝不因「0=0」装作对上了。
        return CrossCheckFinding(
            runId, CrossCheckDimension.STEPS, CrossCheckVerdict.MISSING_SIDE,
            stepCount, 0, listOf("轨迹 ${stepCount}步；208 无步边界行（写侧契约没有这一类）"),
        )
    }

    // ── ④ 耗时量级 ────────────────────────────────────────────────────────

    private fun duration(
        runId: String,
        trace: List<TraceEdge>,
        inWindow: List<DecisionRecord>,
    ): CrossCheckFinding {
        // 步耗时之和＝THINK 行的 durationMs（ACT 的耗时含在步内，不重复加）；-1＝没测到，不进和。
        val traceMs = trace.filter { it.phase == "THINK" }.sumOf { it.durationMs.takeIf { d -> d > 0 } ?: 0L }
        if (traceMs <= 0) {
            return CrossCheckFinding(
                runId, CrossCheckDimension.DURATION, CrossCheckVerdict.MISSING_SIDE,
                0, inWindow.size, listOf("轨迹侧没有可信的步耗时（全没测到）"),
            )
        }
        if (inWindow.size < 2) {
            return CrossCheckFinding(
                runId, CrossCheckDimension.DURATION, CrossCheckVerdict.MISSING_SIDE,
                traceMs.toInt(), inWindow.size, listOf("208 侧该 run 不足两行，量不出耗时"),
            )
        }
        val ledgerMs = inWindow.maxOf { it.atMs } - inWindow.minOf { it.atMs }
        if (ledgerMs <= 0) {
            return CrossCheckFinding(
                runId, CrossCheckDimension.DURATION, CrossCheckVerdict.MISSING_SIDE,
                traceMs.toInt(), inWindow.size, listOf("208 侧该 run 的行都落在同一时刻"),
            )
        }
        val deviation = kotlin.math.abs(traceMs - ledgerMs).toDouble() / maxOf(traceMs, ledgerMs)
        val verdict = if (deviation > DURATION_TOLERANCE) CrossCheckVerdict.MISMATCH else CrossCheckVerdict.CLEAR
        val note = "轨迹步耗时和 ${traceMs}ms；208 窗口跨度 ${ledgerMs}ms；偏差 ${(deviation * 100).toInt()}%"
        return CrossCheckFinding(
            runId, CrossCheckDimension.DURATION, verdict,
            traceMs.toInt(), ledgerMs.toInt(), listOf(note),
        )
    }

    // ── 升格（连续 3 次不一致 ⇒ CRITICAL）────────────────────────────────
    /**
     * 尾部连续 MISMATCH 的长度（缺侧与清白都断链）。
     *
     * @param verdicts 同一维按 run 起跑顺序排好的判定序列（旧→新）
     */
    fun streakOf(verdicts: List<CrossCheckVerdict>): Int {
        var streak = 0
        for (v in verdicts.asReversed()) {
            if (v != CrossCheckVerdict.MISMATCH) break
            streak += 1
        }
        return streak
    }

    /** 升格规则：MISMATCH 到 [ESCALATE_AFTER] 连 ⇒ CRITICAL，其余 MISMATCH ⇒ MAJOR，缺侧 ⇒ MINOR。 */
    fun severityOf(verdict: CrossCheckVerdict, streak: Int): Severity = when {
        verdict == CrossCheckVerdict.MISMATCH && streak >= ESCALATE_AFTER -> Severity.CRITICAL
        verdict == CrossCheckVerdict.MISMATCH -> Severity.MAJOR
        else -> Severity.MINOR
    }

    /**
     * 非 CLEAR 的 finding ⇒ 270 契约的意见（source=PATROL, lawRef=L28）。
     *
     * @param findings checkRun 的全部产出（含 CLEAR），**按 run 起跑顺序排**——
     *   升格看的是每维序列的尾部连续。
     */
    fun opinions(findings: List<CrossCheckFinding>): List<Opinion> {
        val byDim = findings.filter { it.verdict != CrossCheckVerdict.CLEAR }
            .groupBy { it.dimension }
        return byDim.flatMap { (dim, rows) ->
            val streak = streakOf(rows.map { it.verdict })
            rows.map { opinionOf(it, severityOf(it.verdict, streak)) }
        }
    }

    /** 一条 finding ⇒ 一条意见（gist ≤8 字；evidence 带两侧证据 id，不许空）。 */
    fun opinionOf(finding: CrossCheckFinding, severity: Severity): Opinion = when (finding.verdict) {
        CrossCheckVerdict.MISMATCH -> Opinion(
            source = Source.PATROL,
            severity = severity,
            gist = "对拍不符",
            action = ActionCode.REVIEW,
            lawRef = LawRef.L28,
            evidence = finding.evidence.ifEmpty { listOf("对拍:${finding.dimension.wire}:${finding.runId}") },
        )
        else -> Opinion(
            source = Source.PATROL,
            severity = severity,
            gist = "对拍缺侧",
            action = ActionCode.SUPPLEMENT_EVIDENCE,
            lawRef = LawRef.L28,
            evidence = finding.evidence.ifEmpty { listOf("对拍:${finding.dimension.wire}:${finding.runId}") },
        )
    }
}
