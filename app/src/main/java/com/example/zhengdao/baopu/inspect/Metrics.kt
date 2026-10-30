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

/**
 * `run_trace` 的**一次工具请求**（读数层的人介入率／不可逆动作占比按它算）。
 *
 * 去重键是 **(run_id, step, tool, args_hash) 四元组**（口径 §3.2／裁定⑤），所以这里不带
 * `argsHash` 以外的参数——轨迹本来也只存 hash。同一次请求在库里可能落两行
 * （挂起记 `CONFIRM`、批准后按真结果再记一行，`RunEngine.kt:3629`），两行在取数层就被并成一条。
 *
 * [atMs] 取该请求 `started_at` 的**最大值**：批准后补的那一行 `started_at = 0`（耗在等人点按钮上，
 * 不算工具耗时），取最小会把整个请求的时刻拖到 0、掉出滚动窗。
 */
internal data class ToolRequest(
    val runId: String,
    val sessionId: Long,
    val step: Int,
    val tool: String,
    val atMs: Long,
    /** 该请求的行里出现过 `status = CONFIRM`（摆过确认卡）。 */
    val hasConfirm: Boolean,
    /** 该请求的行里出现过 `status = OK`（真执行了）。 */
    val hasOk: Boolean,
)

/**
 * 一个 run 的终态摘要（第二批：任务成功率按**任务**算，口径 §3.1）。
 *
 * [statuses] 是**按 `run_trace.id` 升序**的状态序列（自增主键＝真发生顺序）。
 * 为什么不用 `started_at` 排序：批准路径补的那一行 `started_at = 0`（耗在等人点按钮上），
 * 按时刻排会把它当成"最早发生的"，终态判定就整个反了。
 */
internal data class RunSummary(
    val runId: String,
    val sessionId: Long,
    /** 该 run 首行 `started_at`（正数行里最小的那个）——回滚归属的时间跨度起点。 */
    val startedAtMs: Long,
    /** 末次活动时刻（`started_at`／`ended_at` 取大）——窗口依据。 */
    val atMs: Long,
    val statuses: List<String>,
)

/** 一个 run 的结局（[ended] 才进任务成功率的分母）。 */
internal enum class RunOutcome(val ended: Boolean) {
    SUCCESS(true),
    FAILURE(true),

    /** 最后一行是 `CONFIRM`：还在等你批。 */
    WAITING(false),

    /** 最后一行不是终态状态（`REJECTED`／`SKIPPED` 等），或这个 run 一行都没有。 */
    UNFINISHED(false),
}

/**
 * 终态判定（纯函数，只吃状态序列；口径 §3.1）。
 *
 * 「FAILED 之后同 run 又有 OK＝被救回来」不用单独判：最后一行是 `OK` 本身就说明
 * 前面那次失败后面已经有 `OK` 兜住；反过来最后一行是 FAILED/TIMEOUT/CANCELLED 就是没兜住。
 */
internal object RunVerdict {

    const val STATUS_OK = "OK"
    const val STATUS_CONFIRM = "CONFIRM"

    private val FAILED = setOf("FAILED", "TIMEOUT", "CANCELLED")

    fun outcomeOf(statuses: List<String>): RunOutcome {
        val last = statuses.lastOrNull() ?: return RunOutcome.UNFINISHED
        return when {
            last == STATUS_CONFIRM -> RunOutcome.WAITING
            last == STATUS_OK -> RunOutcome.SUCCESS
            last in FAILED -> RunOutcome.FAILURE
            else -> RunOutcome.UNFINISHED
        }
    }
}

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
    /** 第二批：任务级（`run_trace.run_id` 终态），不是子任务级——两个名字**不许混用**（R4）。 */
    E1_TASK_SUCCESS("e1_task_success", "任务成功率", 7),

    /** 第二批：旧「任务成功率」那段算式整段搬过来，只改名叫子任务完成率（诊断）。 */
    E1_SUBTASK_DONE("e1_subtask_done", "子任务完成率", 7),
    E1_CORRECTIONS("e1_corrections", "平均纠偏次数", 7, perTask = true),
    E1_HUMAN("e1_human", "人介入率", 7),

    /** 第二批：任务级辅助指标（有过摆卡的任务占比）——与人介入率**不许混用**（R4）。 */
    E1_TASK_NEEDS_CONFIRM("e1_task_needs_confirm", "需介入任务占比", 7),
    E1_IRREVERSIBLE("e1_irreversible", "不可逆动作占比", 7),
    /** 2026-10-29 裁定④：算术不动（分母＝核对过的写侧动作数），只改名——「差条数÷总条数」那种读法作废。 */
    E1_LEDGER_MISMATCH("e1_ledger_mismatch", "写侧账实不符率", 7),
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

    /** 询问用户的工具名（口径 §3.2／裁定⑦：它本身就是一张卡，算「摆过确认卡」）。 */
    const val TOOL_ASK_USER = "ask_user"

    /** 覆盖写判不出时读数行必须带的上界说明（口径 §3.6 缺口①）。 */
    const val NOTE_OVERWRITE_UPPER = "覆盖写上界：含新建文件"

    /** 任务成功率的两条不确定性（口径 §3.1 原文；unknown 时也照样写——数算不出不等于不用提）。 */
    const val NOTE_TASK_SUCCESS =
        "续跑未判（缺标记）；新旧数不可比（旧口径按子任务算）；在跑任务可能被算成已结束（无 run 级终态行）"

    /** 子任务完成率的防混用说明（R4：两个名字不许混用）。 */
    const val NOTE_SUBTASK_DIAG = "诊断口径（子任务级），不是任务成功率"

    /** 需介入任务占比的防混用说明（与人介入率不同层）。 */
    const val NOTE_NEEDS_CONFIRM = "任务级（有过摆卡即算），与人介入率不同层"

    /**
     * 破坏动作白名单（口径 §三·乙，**写死**：新增动作必须显式加名并同步改 METRICS.md）。
     *
     * 不用 `DecisionRecord.reversible = false`：那一列含 `read_scope`／`eval_verdict`／
     * `rollback_of`／`skill_demoted` 等非破坏动作，当成"不可逆"会多算。
     *
     * 覆盖写那八支里，后四支（`novel_*`／`book_outline`／`card_build`）**可能只是新建**，
     * 所以读数行永远带 [NOTE_OVERWRITE_UPPER]——上界说明对这八支都成立（裁定⑥）。
     */
    private val DANGER_TOOLS = setOf(
        // 删除
        "delete_path", "move_path",
        // 外发
        "search_web", "generate_image", "card_export", "phone_dial",
        // 覆盖写（可能新建）
        "write_file", "edit_file", "convert_encoding", "image_ops",
        "novel_create", "novel_continue", "book_outline", "card_build",
    )

    /** `sub_task.status` 的终态（与 `SubTaskStatus` 枚举名同字面量；门禁不许 import agent 包）。 */
    private val TERMINAL = setOf("DONE", "FAILED", "TIMEOUT", "CANCELLED")

    /** 读数的全部输入（一次快照；窗口在算的时候再按各指标的 [MetricKey.windowDays] 筛）。 */
    data class Inputs(
        val nowMs: Long,
        val ledger: List<DecisionRecord> = emptyList(),
        val reviews: List<DecisionReview.Entry> = emptyList(),
        val logs: List<LogStep> = emptyList(),
        val runs: List<RunStep> = emptyList(),
        val runSummaries: List<RunSummary> = emptyList(),
        val requests: List<ToolRequest> = emptyList(),
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
        subtaskDone(inputs),
        corrections(inputs),
        human(inputs),
        needsConfirm(inputs),
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

    /**
     * 任务成功率：终态「成功」的任务 ÷ **已结束任务数**（口径 §3.1；单位＝任务，不是子任务）。
     *
     * 成功＝[RunVerdict] 判 SUCCESS ＋ 该 run 时间跨度内同会话**没有**回滚账行。
     * 续跑 v1 判不了、在跑的 run 没有终态行——两条不确定性都写在 note 上（用户 2026-10-29 硬要求）。
     */
    private fun taskSuccess(i: Inputs): MetricLine {
        val days = MetricKey.E1_TASK_SUCCESS.windowDays
        val rolls = i.ledger.filter { it.kind == DecisionKind.ROLLBACK_OF }.groupBy { it.sessionId }
        val ended = i.runSummaries
            .filter { inWindow(it.atMs, i.nowMs, days) }
            .map { it to RunVerdict.outcomeOf(it.statuses) }
            .filter { it.second.ended }
        val ok = ended.filter { it.second == RunOutcome.SUCCESS && !rolledBack(it.first, rolls) }
            .map { it.first.runId }
        return lineOf(
            MetricKey.E1_TASK_SUCCESS,
            ok.size,
            ended.size,
            ok.map { "task:$it" },
            NOTE_TASK_SUCCESS,
        )
    }

    /** 该 run 的时间跨度内、同会话有没有回滚账行（账本不带 `run_id` ⇒ v1 只能按会话＋时间归属）。 */
    private fun rolledBack(run: RunSummary, rollsBySession: Map<Long, List<DecisionRecord>>): Boolean =
        rollsBySession[run.sessionId].orEmpty().any { it.atMs in run.startedAtMs..run.atMs }

    /**
     * 子任务完成率（诊断）：`DONE` ÷ 终态（`PENDING`/`RUNNING` 不进分母）。
     *
     * 算式是旧「任务成功率」**整段搬过来**的，一字未改语义——改的只是名字与位置（口径 §3.5）。
     */
    private fun subtaskDone(i: Inputs): MetricLine {
        val days = MetricKey.E1_SUBTASK_DONE.windowDays
        val terminal = i.subTasks.filter { it.status in TERMINAL && inWindow(it.endedAt, i.nowMs, days) }
        val done = terminal.filter { it.status == "DONE" }
        return lineOf(
            MetricKey.E1_SUBTASK_DONE,
            done.size,
            terminal.size,
            terminal.map { "task:${it.taskId}" },
            NOTE_SUBTASK_DIAG,
        )
    }

    /** 平均纠偏次数：回滚行数 ÷ 任务数（均值，分母不够同样 unknown）。 */
    private fun corrections(i: Inputs): MetricLine {
        val days = MetricKey.E1_CORRECTIONS.windowDays
        val rolls = i.ledger.filter { it.kind == DecisionKind.ROLLBACK_OF && inWindow(it.atMs, i.nowMs, days) }
        val tasks = i.runs.filter { inWindow(it.atMs, i.nowMs, days) }.map { it.runId }.toSet()
        return lineOf(MetricKey.E1_CORRECTIONS, rolls.size, tasks.size, rolls.map { "decision:${it.id}" })
    }

    /**
     * 人介入率：摆过确认卡的工具**请求** ÷ 工具请求总数（请求级，口径 §3.2）。
     *
     * 请求已在取数层按四元组去重（批准后补的那行并进来了）；这里只做窗口与判定，不再按行数算。
     * `ask_user` 计入摆卡（裁定⑦）：它不产生 CONFIRM 行，漏掉会把"人介入"低估。
     */
    private fun human(i: Inputs): MetricLine {
        val reqs = i.requests.filter { inWindow(it.atMs, i.nowMs, MetricKey.E1_HUMAN.windowDays) }
        val asked = reqs.filter { it.hasConfirm || it.tool == TOOL_ASK_USER }
        return lineOf(
            MetricKey.E1_HUMAN,
            asked.size,
            reqs.size,
            asked.map { "trace:${it.sessionId}#${it.step}" },
        )
    }

    /**
     * 需介入任务占比（辅助）：有过摆卡请求的任务 ÷ **有过工具请求的任务**（口径 §3.5）。
     *
     * 与人介入率同一张请求表、不同单位：这里按 `run_id` 聚合。没有请求的 run 不进分母——
     * 纯思考的 run 不可能需要确认，拉进来只会把比例稀释成好看。
     */
    private fun needsConfirm(i: Inputs): MetricLine {
        val days = MetricKey.E1_TASK_NEEDS_CONFIRM.windowDays
        val reqs = i.requests.filter { inWindow(it.atMs, i.nowMs, days) }
        val tasks = reqs.map { it.runId }.toSet()
        val touched = reqs.filter { it.hasConfirm || it.tool == TOOL_ASK_USER }.map { it.runId }.toSet()
        return lineOf(
            MetricKey.E1_TASK_NEEDS_CONFIRM,
            touched.size,
            tasks.size,
            touched.map { "task:$it" },
            NOTE_NEEDS_CONFIRM,
        )
    }

    /**
     * 不可逆动作占比：破坏动作白名单命中数 ÷ 已执行工具请求数（口径 §3.3／§三·乙）。
     *
     * 「已执行」＝该请求有一行 `status = OK`：摆卡没批、被拒、超时的请求都不进分母——
     * 没发生的动作谈不上"不可逆"。覆盖写判不出目标当时在不在 ⇒ 读数行永远带上界说明。
     */
    private fun irreversible(i: Inputs): MetricLine {
        val reqs = i.requests.filter { inWindow(it.atMs, i.nowMs, MetricKey.E1_IRREVERSIBLE.windowDays) }
        val executed = reqs.filter { it.hasOk }
        val danger = executed.filter { it.tool in DANGER_TOOLS }
        return lineOf(
            MetricKey.E1_IRREVERSIBLE,
            danger.size,
            executed.size,
            danger.map { "trace:${it.sessionId}#${it.step}" },
            NOTE_OVERWRITE_UPPER,
        )
    }

    /**
     * 写侧账实不符率：直接吃 I1 的 [AuditGapCheck.compare]（账本空时 checked=0 ⇒ 自动 unknown）。
     *
     * 2026-10-29 裁定④：**算术不动**，只是名字从「账实不符率」改成「写侧账实不符率」——
     * 分母是"核对过的写侧动作数"，不是"账本总条数"；「差条数 ÷ 总条数」那种读法作废。
     */
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
