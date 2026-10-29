// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百八十一批 · 读数层：把「四率／技能成功率／E1 五指标／M9 幻觉抽检」算成
// **有分子、有分母、点得开明细**的一行一指标。
//
// 三条规矩的落法（口径原文在 `docs/METRICS.md`，本文件一个字都不许先于它出现）：
// 1. **分母 <10 ⇒ unknown**：只由 [Ratio.known] 一个地方判，显示文案在 [Ratio.text] 里
//    也按它分支——两处各写一遍就是"同一件事两套口径"；
// 2. **先落盘再看数**：口径文档先提交，本文件照着写；
// 3. **同一份数据两次同一个数**：本文件零 IO、零时钟、零随机；输入顺序不影响结果
//    （证据排序去重、按 id 去重），窗口一律滚动（不按日历周切）。
//
// 分母禁忌：任何指标的分母都不取 `意见判定.jsonl` 的条数——那是"少报就好看"的循环。
// 四率的分母全是外部事实：账本行／判决条数／动作步数／巡按检查项。
//
// 本文件只有纯计算；取数在 [MetricsSources]（只读 SQL 与 JSONL），判词（M9）由调用方递进来。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.AcceptanceState
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import com.example.zhengdao.baopu.SkillAcceptance
import com.example.zhengdao.core.TimeFormats

/** M9 的三态（人判；`unverifiable` 既不进分子也不进分母）。 */
internal enum class M9Verdict(val wire: String) {
    /** 数字／文件／状态与源不符。 */
    MISMATCH("mismatch"),

    /** 与源相符。 */
    VERIFIED("verified"),

    /** 无法验证（源不在手边／结论本身不可核）——**不计入失败率**。 */
    UNVERIFIABLE("unverifiable"),
}

/** 一条 M9 抽检判词（[ref] 是点得开的证据 id，如 `msg:<id>`）。 */
internal data class M9Judgment(val ref: String, val atMs: Long, val verdict: M9Verdict)

/** `run_log` 的一行（读数只用到会话/步/状态/时刻；正文类字段一律不进来）。 */
internal data class LogStep(val id: Long, val sessionId: Long, val step: Int, val state: String, val atMs: Long)

/** `run_trace` 的一步（任务＝run_id；[atMs] 取该步 `started_at`）。 */
internal data class RunStep(val runId: String, val sessionId: Long, val step: Int, val atMs: Long)

/** `sub_task` 的一行（[status] 与 `SubTaskStatus` 枚举名同字面量；门禁不许 import agent 包）。 */
internal data class SubTaskRow(val taskId: String, val status: String, val endedAt: Long)

/** 一条技能的验收快照（255 的 `验收.json`）。 */
internal data class SkillSnapshot(val scope: String, val name: String, val state: AcceptanceState)

/** 一个指标的稳定键（`wire` 进周报与明细的键；改字面量＝老读数对不上）。 */
internal enum class MetricKey(
    val wire: String,
    val cn: String,
    /** 滚动窗口（天）——**不是日历周/月**。 */
    val windowDays: Int,
    /** 均值类（次/任务）：分母不够时写「算不出均值」，单位也不是 %。 */
    val perTask: Boolean = false,
) {
    VIOLATION("violation", "违规率", 7),
    MISJUDGE("misjudge", "误判率", 30),
    INTERVENTION("intervention", "干预率", 7),
    SKIP("skip", "跳过率", 7),
    SKILL_SUCCESS("skill_success", "技能成功率", 30),
    E1_TASK_SUCCESS("e1_task_success", "任务成功率", 7),
    E1_CORRECTIONS("e1_corrections", "平均纠偏次数", 7, perTask = true),
    E1_HUMAN("e1_human", "人介入率", 7),
    E1_IRREVERSIBLE("e1_irreversible", "不可逆动作占比", 7),
    E1_LEDGER_MISMATCH("e1_ledger_mismatch", "账实不符率", 7),
    M9_HALLUCINATION("m9_hallucination", "可验证幻觉率", 7),
    ;

    companion object {
        fun ofWire(wire: String): MetricKey? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条率的读数（分子分母**都给**）。
 *
 * [known] 只有一个判据：[Metrics.MIN_DENOM]。显示文案也只看它——不许在别处再判一次
 * "样本够不够"（两处判据迟早打架，那就是同一份数据两个数）。
 */
internal data class Ratio(val num: Int, val denom: Int, val perTask: Boolean = false) {

    /** 分母够不够算（<10 ⇒ unknown，不印百分比）。 */
    val known: Boolean get() = denom >= Metrics.MIN_DENOM

    /** 算得出才有值；算不出是 null，不是 0.0（0 会被读成"一次都没有"）。 */
    val value: Double?
        get() = when {
            !known -> null
            perTask -> num.toDouble() / denom
            else -> num * 100.0 / denom
        }

    /** 一行字（unknown 时**不出现 %**，并且把分子分母亮出来）。 */
    fun text(): String {
        val keys = "分子 $num／分母 $denom"
        val v = value ?: return "$keys（分母 <${Metrics.MIN_DENOM}），算不出${if (perTask) "均值" else "率"}"
        return if (perTask) "${Metrics.decimals(v)} 次/任务（$keys）" else "${InspectText.pct(v)}%（$keys）"
    }
}

/** 一个指标的一行读数（周报的一行＝明细页的一行，同一个对象）。 */
internal data class MetricLine(
    val key: MetricKey,
    val ratio: Ratio,
    /** 点得开的证据 id（已排序去重，最多 [Metrics.MAX_EVIDENCE] 条）。 */
    val evidence: List<String> = emptyList(),
    /** 证据总数（多于展示上限时界面写「共 N 条」）。 */
    val evidenceTotal: Int = evidence.size,
    /** 备注（降级几个、无法验证几条、跳过了几次巡按……只写事实，不写建议）。 */
    val note: String = "",
) {
    val known: Boolean get() = ratio.known

    fun display(): String =
        "${key.cn}：${ratio.text()}" + if (note.isEmpty()) "" else "；$note"
}

/** 一周读数（一行一指标，顺序＝[MetricKey] 声明顺序，稳定）。 */
internal data class WeeklyReport(val atMs: Long, val lines: List<MetricLine>) {

    val unknowns: Int get() = lines.count { !it.known }

    val headline: String
        get() = buildString {
            append("读数 ${TimeFormats.monthDay(atMs)}：${lines.size} 项")
            if (unknowns > 0) append("／unknown $unknowns")
        }

    fun lineOf(key: MetricKey): MetricLine? = lines.firstOrNull { it.key == key }
}

/**
 * 读数层的唯一计算入口。
 *
 * 两个入口（周报／明细）共用同一次 [compute]：[detail] 返回的就是周报里那一行本身，
 * 不是"另算一遍再凑成一样的字"。
 */
internal object Metrics {

    /** 分母线：少一条都不算率（与 255 `MIN_SAMPLES`、239 `MIN_RATE_SAMPLE` 同数）。 */
    const val MIN_DENOM = 10

    /** 一行读数最多摊开几条证据（其余写「共 N 条」；照 239 `MAX_EVIDENCE_PER_FINDING`）。 */
    const val MAX_EVIDENCE = 20

    /** M9 每周抽几条（派工单原文 k=20）。 */
    const val M9_WEEKLY_K = 20

    private const val DAY_MS = 86_400_000L

    /** `run_log.state` 里"摆了卡"的字面量（与 `RunEngine` 的稳定键同字，别改）。 */
    const val STATE_CONFIRM = "AwaitConfirm"

    /** `run_log.state` 里"动了手"的字面量。 */
    const val STATE_ACTING = "Acting"

    /** `sub_task.status` 的终态（与 `SubTaskStatus` 枚举名同字面量；门禁不许 import agent 包）。 */
    private val TERMINAL = setOf("DONE", "FAILED", "TIMEOUT", "CANCELLED")

    /** 读数的全部输入（一次快照；窗口在算的时候再按各指标的 [MetricKey.windowDays] 筛）。 */
    data class Inputs(
        val nowMs: Long,
        val ledger: List<DecisionRecord> = emptyList(),
        val reviews: List<DecisionReview.Entry> = emptyList(),
        val logs: List<LogStep> = emptyList(),
        val runs: List<RunStep> = emptyList(),
        val subTasks: List<SubTaskRow> = emptyList(),
        val skills: List<SkillSnapshot> = emptyList(),
        val patrols: List<PatrolEntry> = emptyList(),
        val acts: List<WriteTrace> = emptyList(),
        val m9: List<M9Judgment> = emptyList(),
    )

    /** 一次算完全部指标（顺序固定；纯函数，输入不变则结果不变）。 */
    fun compute(inputs: Inputs): List<MetricLine> = listOf(
        violation(inputs),
        misjudge(inputs),
        intervention(inputs),
        skip(inputs),
        skillSuccess(inputs),
        taskSuccess(inputs),
        corrections(inputs),
        human(inputs),
        irreversible(inputs),
        ledgerMismatch(inputs),
        hallucination(inputs),
    )

    /** 入口 A：周报。 */
    fun weekly(inputs: Inputs): WeeklyReport = WeeklyReport(inputs.nowMs, compute(inputs))

    /** 入口 B：从周报点开某一项（返回的就是周报里那一行本身）。 */
    fun detail(report: WeeklyReport, key: MetricKey): MetricLine? = report.lineOf(key)

    /** 入口 B 的等价写法（同一份输入现算；与 A 必须同数，有用例钉）。 */
    fun detail(inputs: Inputs, key: MetricKey): MetricLine? = compute(inputs).firstOrNull { it.key == key }

    /** 有效位数（均值用；四舍五入到两位，避免出现 `0.3333333333333333`）。 */
    fun decimals(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return r.toString()
    }

    // ── 四率 ────────────────────────────────────────────────────────────────

    /** 违规率（v1）：被撤销的免问动作 ÷ 免问动作（口径见 METRICS.md §1.1）。 */
    private fun violation(i: Inputs): MetricLine {
        val days = MetricKey.VIOLATION.windowDays
        val auto = i.ledger.filter { it.kind == DecisionKind.AUTO_ACT && inWindow(it.atMs, i.nowMs, days) }
        val rolledBack = i.ledger.filter { it.kind == DecisionKind.ROLLBACK_OF }
            .mapNotNull { it.rollbackOf }
            .toSet()
        val hit = auto.filter { it.id in rolledBack }
        return lineOf(MetricKey.VIOLATION, hit.size, auto.size, hit.map { "decision:${it.id}" })
    }

    /**
     * 误判率：判「错」的 ÷ 判过的（同 record_id 取最后一条；窗口按判定时刻）。
     *
     * 与 239 `PassRate.of` 同源同算法，只是这里要分子分母与证据 id，所以不直接调它
     * （调它只能拿到百分比，拿不到那几条 id）。
     */
    private fun misjudge(i: Inputs): MetricLine {
        val latest = DecisionReview.latestPerRecord(i.reviews).values
            .filter { inWindow(it.atMs, i.nowMs, MetricKey.MISJUDGE.windowDays) }
            .filter { it.verdict == DecisionRecord.VERDICT_CONFIRMED || it.verdict == DecisionRecord.VERDICT_WRONG }
        val wrong = latest.filter { it.verdict == DecisionRecord.VERDICT_WRONG }
        return lineOf(MetricKey.MISJUDGE, wrong.size, latest.size, wrong.map { "decision:${it.recordId}" })
    }

    /** 干预率：摆卡步数 ÷ 有痕迹的步数（都按 (会话, 步) 去重）。 */
    private fun intervention(i: Inputs): MetricLine {
        val rows = i.logs.filter { inWindow(it.atMs, i.nowMs, MetricKey.INTERVENTION.windowDays) }
        val steps = rows.map { it.sessionId to it.step }.toSet()
        val confirms = rows.filter { it.state == STATE_CONFIRM }.map { it.sessionId to it.step }.toSet()
        return lineOf(
            MetricKey.INTERVENTION,
            confirms.size,
            steps.size,
            confirms.map { "call:${it.first}#${it.second}" },
        )
    }

    /** 跳过率：勘验判「查不了」的检查项 ÷ 巡按跑过的检查项（无六项明细的老行不计入）。 */
    private fun skip(i: Inputs): MetricLine {
        val win = i.patrols.filter { inWindow(it.atMs, i.nowMs, MetricKey.SKIP.windowDays) }
        val withFindings = win.filter { it.findings.isNotEmpty() }
        val skipped = withFindings.flatMap { e ->
            e.findings.filter { it.verdict == InspectVerdict.NO_DATA }
                .map { "${e.id}#${it.check.wire}" }
        }
        val total = withFindings.sumOf { it.findings.size }
        val blind = win.size - withFindings.size
        val note = if (blind > 0) "$blind 次巡按无六项明细，未计入" else ""
        return lineOf(MetricKey.SKIP, skipped.size, total, skipped, note)
    }

    // ── 技能成功率（255）────────────────────────────────────────────────────

    /** Σ成功 ÷ Σ样本；窗口与样本筛选全用 255 的 [SkillAcceptance.windowOf]。 */
    private fun skillSuccess(i: Inputs): MetricLine {
        var ok = 0
        var total = 0
        var demoted = 0
        val fails = mutableListOf<String>()
        i.skills.sortedWith(compareBy({ it.scope }, { it.name })).forEach { s ->
            val win = SkillAcceptance.windowOf(s.state, i.nowMs).distinctBy { it.opId }
            total += win.size
            ok += win.count { it.ok }
            win.filter { !it.ok }.forEach { fails += "skill:${s.scope}/${s.name}#${it.opId}" }
            val demotedAt = s.state.demotedAtMs
            if (demotedAt != null && inWindow(demotedAt, i.nowMs, MetricKey.SKILL_SUCCESS.windowDays)) demoted++
        }
        return lineOf(
            MetricKey.SKILL_SUCCESS,
            ok,
            total,
            fails,
            "技能 ${i.skills.size} 个；窗内降级 $demoted 个",
        )
    }

    // ── E1 五指标 ───────────────────────────────────────────────────────────

    /** 任务成功率：DONE ÷ 终态（PENDING/RUNNING 不进分母）。 */
    private fun taskSuccess(i: Inputs): MetricLine {
        val days = MetricKey.E1_TASK_SUCCESS.windowDays
        val terminal = i.subTasks.filter { it.status in TERMINAL && inWindow(it.endedAt, i.nowMs, days) }
        val done = terminal.filter { it.status == "DONE" }
        return lineOf(MetricKey.E1_TASK_SUCCESS, done.size, terminal.size, terminal.map { "task:${it.taskId}" })
    }

    /** 平均纠偏次数：回滚行数 ÷ 任务数（均值，分母不够同样 unknown）。 */
    private fun corrections(i: Inputs): MetricLine {
        val days = MetricKey.E1_CORRECTIONS.windowDays
        val rolls = i.ledger.filter { it.kind == DecisionKind.ROLLBACK_OF && inWindow(it.atMs, i.nowMs, days) }
        val tasks = i.runs.filter { inWindow(it.atMs, i.nowMs, days) }.map { it.runId }.toSet()
        return lineOf(MetricKey.E1_CORRECTIONS, rolls.size, tasks.size, rolls.map { "decision:${it.id}" })
    }

    /** 人介入率：有摆卡的任务数 ÷ 任务数。 */
    private fun human(i: Inputs): MetricLine {
        val days = MetricKey.E1_HUMAN.windowDays
        val confirms = i.logs.filter { it.state == STATE_CONFIRM && inWindow(it.atMs, i.nowMs, days) }
            .map { it.sessionId to it.step }
            .toSet()
        val runs = i.runs.filter { inWindow(it.atMs, i.nowMs, days) }
        val tasks = runs.map { it.runId }.toSet()
        val touched = runs.filter { (it.sessionId to it.step) in confirms }.map { it.runId }.toSet()
        return lineOf(MetricKey.E1_HUMAN, touched.size, tasks.size, touched.map { "task:$it" })
    }

    /** 不可逆动作占比：`reversible = false` 的账行 ÷ 全部账行（读侧已回填回滚标记）。 */
    private fun irreversible(i: Inputs): MetricLine {
        val rows = i.ledger.filter { inWindow(it.atMs, i.nowMs, MetricKey.E1_IRREVERSIBLE.windowDays) }
        val hard = rows.filter { !it.reversible }
        return lineOf(MetricKey.E1_IRREVERSIBLE, hard.size, rows.size, hard.map { "decision:${it.id}" })
    }

    /** 账实不符率：直接吃 I1 的 [AuditGapCheck.compare]（账本空时 checked=0 ⇒ 自动 unknown）。 */
    private fun ledgerMismatch(i: Inputs): MetricLine {
        val res = AuditGapCheck.compare(i.acts, i.ledger)
        return lineOf(
            MetricKey.E1_LEDGER_MISMATCH,
            res.gaps.size,
            res.checked,
            res.gaps.map { "trace:${it.sessionId}#${it.step}" },
            if (res.beyondLedgerWindow > 0) "${res.beyondLedgerWindow} 条痕迹早于账本窗口，未核" else "",
        )
    }

    // ── M9 幻觉抽检 ─────────────────────────────────────────────────────────

    /** 可验证幻觉率：mismatch ÷ (mismatch + verified)；unverifiable 两边都不进。 */
    private fun hallucination(i: Inputs): MetricLine {
        val win = i.m9.filter { inWindow(it.atMs, i.nowMs, MetricKey.M9_HALLUCINATION.windowDays) }
        val bad = win.filter { it.verdict == M9Verdict.MISMATCH }
        val good = win.count { it.verdict == M9Verdict.VERIFIED }
        val unverifiable = win.count { it.verdict == M9Verdict.UNVERIFIABLE }
        val note = buildString {
            append("抽 ${win.size} 条／无法验证 $unverifiable 条（不计入分母）")
            if (win.size < M9_WEEKLY_K) append("；未满 k=$M9_WEEKLY_K")
        }
        return lineOf(MetricKey.M9_HALLUCINATION, bad.size, bad.size + good, bad.map { it.ref }, note)
    }

    // ── 公共件 ──────────────────────────────────────────────────────────────

    private fun inWindow(atMs: Long, nowMs: Long, windowDays: Int): Boolean =
        atMs in (nowMs - windowDays * DAY_MS)..nowMs

    /** 造一行读数：证据排序去重后钳到 [MAX_EVIDENCE]，总数照实记。 */
    private fun lineOf(
        key: MetricKey,
        num: Int,
        denom: Int,
        evidence: List<String>,
        note: String = "",
    ): MetricLine {
        val clean = evidence.asSequence().filter { it.isNotBlank() }.distinct().sorted().toList()
        return MetricLine(
            key = key,
            ratio = Ratio(num.coerceAtLeast(0), denom.coerceAtLeast(0), key.perTask),
            evidence = clean.take(MAX_EVIDENCE),
            evidenceTotal = clean.size,
            note = note,
        )
    }
}
