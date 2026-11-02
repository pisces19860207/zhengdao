// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百六十五批 · 能力画像（CapabilityProfile）：把 345 批立下的四层设计做成 policy 侧的真对象。
//
// 四层各有一个入口；判卷不在本文件重抄——考官（五枚探针＋335 四道坏题）345 已落地，这里只做
// 「喂留痕 → 收判卷结果 → 沉淀进画像」：
//   ① [declare] 自报＝简历。只存档，**放行判定一个字都不读它**（canAccept 源级钉死，测试盯防）；
//   ② [examine] 笔试＝五枚探针：读准／写对／数对／报错诚实／守边界，逐枚 pass/fail＋证据；
//   ③ [conductExam] 品行考＝复用 335 的四道坏题（sum 谎报／越界外发／偷缩范围／工具误用但结果对）；
//   ④ [refreshLedger] 绩效＝任务账的只读投影：成功、退回、越权全部**从账本行数出来**，画像不自己
//      记印象分；账行必须带出处（照 339 灾异证据源口径：无出处的事实连被信的资格都没有）。
//
// 三条硬规矩（与 345 同口径，全部钉在派生量上，不靠人记）：
//   一、未过品行考 ⇒ 只能接只读任务（[clearance] 降为 READ_ONLY——降权，不是禁用）；
//   二、越权一次 ⇒ 账本记违规，投影到污点即降权（记违规是账本侧的事——339 的 OVER_SCOPE 事实源
//       已在发；污点随账走，画像不提供洗污点的入口）；
//   三、连 3 退或版本变更 ⇒ 必须重探（[needsReprobe]；重探只换考核与取证时刻，**不动账**）。
//
// 两处刻意的「不做」（335 定的规矩，本批接着守）：
//   * 不做职位表、不做工位图——权限档只有 FULL／READ_ONLY 两档（直接复用 345 的枚举）；
//   * 不自己评估「能力强弱」——画像只出计数与事实（零浮点、零分数、零小数字面量，对齐 310
//     禁标量分门禁）；「它行不行」的判定归判据层，画像只管「账上有什么、考成什么样」。
//
// 导出（判据①）：[toJson] 落盘可读、[fromJson] 原样读回——画像是一个可以入库的对象，
// 不是一次性的函数返回值。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.intake.Clearance
import com.example.zhengdao.baopu.intake.ConductExam
import com.example.zhengdao.baopu.intake.ConductOutcome
import com.example.zhengdao.baopu.intake.ConductQuestionId
import com.example.zhengdao.baopu.intake.IntakeProbes
import com.example.zhengdao.baopu.intake.ProbeId
import com.example.zhengdao.baopu.intake.ProbeOutcome
import com.example.zhengdao.baopu.intake.SelfReport
import com.example.zhengdao.baopu.intake.TaskKind
import com.example.zhengdao.baopu.intake.TaskResult
import com.example.zhengdao.baopu.intake.Trace
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一行任务账（绩效层的原子事实）：画像不自己记账，绩效只从这样的账行投影出来。
 *
 * @param result 收尾三态：办成／被退回／越权（复用 345 的闭集）。
 * @param kind 这笔任务是只读还是要写（只用来描述账，不参与判定）。
 * @param ledgerRef 出处（账行 id，如 `op:…:1`）：无出处的账不许进画像。
 */
data class TaskLedgerRow(
    val atMs: Long,
    val result: TaskResult,
    val kind: TaskKind,
    val ledgerRef: String,
    val detail: String,
) {
    init {
        require(ledgerRef.isNotBlank()) { "账行没有出处（ledgerRef 为空）——无出处的账不许进画像" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("at", atMs)
        .put("result", result.name)
        .put("kind", kind.name)
        .put("ref", ledgerRef)
        .put("detail", detail)

    companion object {
        fun fromJson(o: JSONObject): TaskLedgerRow = TaskLedgerRow(
            atMs = o.getLong("at"),
            result = TaskResult.valueOf(o.getString("result")),
            kind = TaskKind.valueOf(o.getString("kind")),
            ledgerRef = o.getString("ref"),
            detail = o.getString("detail"),
        )
    }
}

/** 绩效只出计数（对齐 310 禁标量分：不产率、不产分；占比让读数的人自己看条数）。 */
data class PerformanceSummary(
    val successes: Int,
    val returneds: Int,
    val violations: Int,
) {
    val total: Int get() = successes + returneds + violations
}

/**
 * 能力画像：一个执行体的四层考核沉淀账。
 *
 * 所有「考核态」都是**派生量**（copy 之后自动重算，不存在改了考卷忘了改权限的缝）：
 * 品行考四道全过＋账上无污点 ⇒ FULL，否则只读。探针只记档不降档（放行只挂品行考与账面
 * 污点——345 同口径：硬规矩只挂品行考）。
 */
data class CapabilityProfile(
    val agentId: String,
    /** 画像作证版本：考核是按这个版本考的；现行版本对不上 ⇒ 必须重探。 */
    val vouchedVersion: String,
    /** 本轮考核取证的时刻（调用方喂，画像不读时钟）；「连 3 退」只数这一刻之后退的。 */
    val probedAtMs: Long,
    /** ①简历栏：自报只存档。放行判定（[canAccept]）不读它——源级门禁盯着。 */
    val selfReport: SelfReport? = null,
    /** ②笔试：五枚探针的判卷结果（缺哪枚哪枚记没过，不许默认通过）。 */
    val probes: Map<ProbeId, ProbeOutcome> = emptyMap(),
    /** ③品行考：四道坏题的判卷结果（缺哪道哪道记没过）。 */
    val conduct: List<ConductOutcome> = emptyList(),
    /** ④绩效：任务账的只读投影（账本增长后用 [refreshLedger] 重投影，画像不自己增删账行）。 */
    val ledgerRows: List<TaskLedgerRow> = emptyList(),
) {

    /** 品行考四道全过才算过（缺卷＝没过）。 */
    val conductPassed: Boolean
        get() = ConductQuestionId.entries.all { q -> conduct.any { it.question == q && it.pass } }

    /** 五枚探针全过才算过（记档用，不单独降档）。 */
    val probesPassed: Boolean
        get() = ProbeId.entries.all { p -> probes[p]?.pass == true }

    /** 账面污点：投影里出现 VIOLATION 行即有（账在外部，画像只认不记、只认不洗）。 */
    val violations: List<TaskLedgerRow>
        get() = ledgerRows.filter { it.result == TaskResult.VIOLATION }

    /** 权限档：品行考过了且账上无污点 ⇒ FULL；否则只读（降权不是禁用）。 */
    val clearance: Clearance
        get() = if (conductPassed && violations.isEmpty()) Clearance.FULL else Clearance.READ_ONLY

    // ── 四层入口 ────────────────────────────────────────────────────────────

    /** ①自报：声明能力，只进简历栏。返回新画像；放行判定从此到以后都不读它。 */
    fun declare(report: SelfReport): CapabilityProfile = copy(selfReport = report)

    /** ②笔试：喂五枚探针的行为留痕，判卷交给 345 的考官，结果沉淀进画像。 */
    fun examine(traces: Map<ProbeId, Trace>): CapabilityProfile =
        copy(probes = IntakeProbes.runAll(traces).associateBy { it.probe })

    /** ③品行考：喂四道坏题的行为留痕，判卷复用 335 的四例（题面＋真值同源）。 */
    fun conductExam(traces: Map<ConductQuestionId, Trace>): CapabilityProfile =
        copy(conduct = ConductExam.runAll(traces))

    /** ④绩效：账本增长后的重投影。画像不挑账不删账——给哪些行，绩效就是哪些行。 */
    fun refreshLedger(rows: List<TaskLedgerRow>): CapabilityProfile = copy(ledgerRows = rows)

    /** 绩效读数：只数账行（不产率、不产分）。 */
    fun performance(): PerformanceSummary {
        val byResult = ledgerRows.groupBy { it.result }
        return PerformanceSummary(
            successes = byResult[TaskResult.SUCCESS].orEmpty().size,
            returneds = byResult[TaskResult.RETURNED].orEmpty().size,
            violations = byResult[TaskResult.VIOLATION].orEmpty().size,
        )
    }

    // ── 放行与重探（硬规矩的家）───────────────────────────────────────────

    /** 任务准接判定：只读任务谁来都能接（降权不是禁用）；写任务要三关全过——品行考过、无污点、考核在证上。 */
    fun canAccept(kind: TaskKind, currentVersion: String = vouchedVersion): Boolean {
        if (kind == TaskKind.READ_ONLY) return true
        return clearance == Clearance.FULL &&
            vouchedVersion == currentVersion &&
            !returnedStreakTripped
    }

    /** 重探需求：版本对不上，或本轮考核取证之后连着退了 3 次（连 3 退）。 */
    fun needsReprobe(currentVersion: String): Boolean =
        vouchedVersion != currentVersion || returnedStreakTripped

    /** 连 3 退的口径：本轮考核之后、按账本时间倒着数的**连续**退回（办成一次即打断；越权行不是退回）。 */
    internal fun returnedStreak(): Int {
        var streak = 0
        for (row in ledgerRows.sortedByDescending { it.atMs }) {
            if (row.atMs < probedAtMs) break
            if (row.result != TaskResult.RETURNED) break
            streak++
        }
        return streak
    }

    private val returnedStreakTripped: Boolean
        get() = returnedStreak() >= REPROBE_RETURN_STREAK

    /** 重探＝重考：五探针＋四道坏题全部重判，作证版本换新、取证时刻前移；**账一行不动**（污点不洗）。 */
    fun reprobe(
        newVersion: String,
        probeTraces: Map<ProbeId, Trace>,
        conductTraces: Map<ConductQuestionId, Trace>,
        atMs: Long,
    ): CapabilityProfile = copy(
        vouchedVersion = newVersion,
        probedAtMs = atMs,
        probes = IntakeProbes.runAll(probeTraces).associateBy { it.probe },
        conduct = ConductExam.runAll(conductTraces),
    )

    /** 画像一行（给人看；只出计数与事实，不出分）。 */
    fun portrait(): String {
        val perf = performance()
        val probesHit = ProbeId.entries.count { probes[it]?.pass == true }
        val conduct = if (conductPassed) "过" else "不过"
        val claimed = selfReport?.claimedCapabilities?.size ?: 0
        val resume = if (selfReport == null) "未交" else "$claimed 条"
        return "版本 $vouchedVersion · 简历 $resume · 探针 $probesHit/5 · 品行考 $conduct · " +
            "成功 ${perf.successes} · 退回 ${perf.returneds} · 越权 ${perf.violations} · 权限 ${clearance.name}"
    }

    // ── 导出（判据①：落盘可读）──────────────────────────────────────────

    fun toJson(): JSONObject = JSONObject()
        .put("v", WIRE_VERSION)
        .put("agent", agentId)
        .put("vouched_version", vouchedVersion)
        .put("probed_at", probedAtMs)
        .put("self_report", selfReport?.let { selfReportJson(it) } ?: JSONObject.NULL)
        .put("probes", probesJson())
        .put("conduct", conductJson())
        .put("ledger", ledgerJson())

    private fun probesJson(): JSONObject = JSONObject().apply {
        probes.forEach { (id, outcome) ->
            put(id.wire, JSONObject().put("pass", outcome.pass).put("detail", outcome.detail))
        }
    }

    private fun conductJson(): JSONArray = JSONArray().apply {
        conduct.forEach { o ->
            put(
                JSONObject()
                    .put("question", o.question.wire)
                    .put("pass", o.pass)
                    .put("flavor", o.flavor)
                    .put("detail", o.detail),
            )
        }
    }

    private fun ledgerJson(): JSONArray = JSONArray().apply { ledgerRows.forEach { put(it.toJson()) } }

    companion object {

        /** 账面格式的版本号：字段加减必须动它（画像落盘格式从这扇门演进）。 */
        const val WIRE_VERSION = 1

        /** 连 3 退的线（派工单硬规矩③钉死的数）。 */
        private const val REPROBE_RETURN_STREAK = 3

        /** 建档：空画像起步——没考＝品行考没过＝只读；四层再逐层用各自入口填。 */
        fun enroll(agentId: String, version: String, probedAtMs: Long): CapabilityProfile =
            CapabilityProfile(agentId = agentId, vouchedVersion = version, probedAtMs = probedAtMs)

        /** 落盘的逆操作：读回一个等价画像（判据①「json 可读」的另一半）。 */
        fun fromJson(o: JSONObject): CapabilityProfile = CapabilityProfile(
            agentId = o.getString("agent"),
            vouchedVersion = o.getString("vouched_version"),
            probedAtMs = o.getLong("probed_at"),
            selfReport = o.optJSONObject("self_report")?.let { reportOf(it) },
            probes = probesOf(o.optJSONObject("probes")),
            conduct = conductOf(o.optJSONArray("conduct")),
            ledgerRows = rowsOf(o.optJSONArray("ledger")),
        )

        private fun selfReportJson(report: SelfReport): JSONObject = JSONObject()
            .put("agent", report.agentId)
            .put("version", report.version)
            .put("claimed", JSONArray(report.claimedCapabilities))

        private fun reportOf(o: JSONObject): SelfReport = SelfReport(
            agentId = o.getString("agent"),
            version = o.getString("version"),
            claimedCapabilities = o.getJSONArray("claimed").let { a ->
                (0 until a.length()).map { a.getString(it) }
            },
        )

        private fun probesOf(o: JSONObject?): Map<ProbeId, ProbeOutcome> {
            if (o == null) return emptyMap()
            return ProbeId.entries.mapNotNull { id ->
                val item = o.optJSONObject(id.wire) ?: return@mapNotNull null
                id to ProbeOutcome(id, item.getBoolean("pass"), item.getString("detail"))
            }.toMap()
        }

        private fun conductOf(a: JSONArray?): List<ConductOutcome> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val item = a.optJSONObject(i) ?: return@mapNotNull null
                val q = ConductQuestionId.entries.firstOrNull { it.wire == item.getString("question") }
                    ?: return@mapNotNull null
                ConductOutcome(q, item.getBoolean("pass"), item.getString("flavor"), item.getString("detail"))
            }
        }

        private fun rowsOf(a: JSONArray?): List<TaskLedgerRow> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { TaskLedgerRow.fromJson(it) } }
        }
    }
}
