// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 评测的**自动触发判据**（接线闭合清单 #3：评测／决策没有自动触发，168 断点）。
//
// 四条件（可执行、可证伪，**没有一条靠"感觉该评"**）：
//   ① 终态 FAILED 或回滚 ≥1 次（终态判定复用 281 的 `RunVerdict`——口径一处，不另写一套）；
//   ② 用户连续两次纠同一件事（同一技能最后两条效果信号都是 USER_CORRECTED，证据是
//      85 批记下的用户否定，**不是模型自评**）；
//   ③ 某技能成功率跌破阈值（30 天窗、样本 ≥[RATE_MIN_SAMPLES]，分母口径写死在 KDoc）；
//   ④ 用户手动（预留：接线口在 [EvalTriggerWiring.triggerManually]，本批不画界面）。
//
// 两条边界：
//   1. **判不了就不猜**：取数缺一路（库里没行／读失败），那一条件就不出信号——
//      不许拿"空表"冒充"都正常"，也不许拿"空表"冒充"该评测"；
//   2. **触发 ≠ 评测**：这里只出"该评"的判定；跑什么、记账、进哪本账，在
//      [EvalTriggerEngine]（触发后）与 `EvalTriggerWiring`（生产装配）。
package com.example.zhengdao.baopu.evolution.trigger

import com.example.zhengdao.baopu.SkillOutcome
import com.example.zhengdao.baopu.inspect.RunOutcome
import com.example.zhengdao.baopu.inspect.RunVerdict

/** 触发类型（限频的"同类"就是按它分桶；wire 是账本里的稳定键，改了老账读不出来）。 */
internal enum class TriggerType(val wire: String, val cn: String) {
    /** ① 终态 FAILED 或回滚 ≥1 次。 */
    RUN_FAILED("run_failed", "任务失败"),

    /** ② 用户连续两次纠同一件事。 */
    USER_CORRECTION("user_correction", "连续纠错"),

    /** ③ 某技能成功率跌破阈值。 */
    SKILL_RATE("skill_rate", "技能成功率过低"),

    /** ④ 用户手动（预留；同样过限频，手动不是旁路）。 */
    MANUAL("manual", "手动触发"),
    ;

    companion object {
        fun ofWire(wire: String): TriggerType? = entries.firstOrNull { it.wire == wire }
    }
}

/** `run_trace` 按 run 聚合的最小行（字段与 281 的 `RunSummary` 同义；触发器只看得到这些）。 */
internal data class TriggerRunRow(
    val runId: String,
    val sessionId: Long,
    val startedAtMs: Long,
    val atMs: Long,
    val statuses: List<String>,
)

/** `skill_event` 的一条效果信号（只带 id／名字／结论／时刻，不带任何正文）。 */
internal data class TriggerSkillRow(
    val id: Long,
    val name: String,
    /** `SkillOutcome` 的枚举名；认不出的脏值由 [EvalTriggerRules] 丢弃（不冒充任何一种）。 */
    val outcome: String,
    val atMs: Long,
)

/** 触发器的一次取数快照（由接线层从库／账本里取好；判据层零 I/O）。 */
internal data class EvalTriggerInputs(
    val nowMs: Long,
    val sessionId: Long,
    /** 本会话最近的 run（按发生序；取数层保证升序）。 */
    val runs: List<TriggerRunRow>,
    /** 本会话的回滚账行时刻（`DecisionKind.ROLLBACK_OF`）。 */
    val rollbackAtMs: List<Long>,
    /** 本会话的效果信号（按 id 升序——连续性看写入顺序，不看不单调的钟）。 */
    val sessionSkillRows: List<TriggerSkillRow>,
    /** 30 天窗内的效果信号（全体会话；成功率看窗，不看单轮）。 */
    val windowSkillRows: List<TriggerSkillRow>,
    /** 本会话最后一条用户消息正文（人话；进未办成单的"想做的事"，可空）。 */
    val intent: String,
)

/** 一条待预算判定的触发信号（[ref] 是证据引用，形状与未办成单的 `reasonRef` 同一套）。 */
internal data class EvalTriggerSignal(
    val type: TriggerType,
    /** 证据引用：`task:<run_id>` / `skill_event:<id>` / `manual`。 */
    val ref: String,
    /** 人话补充（未办成单的 intent／提案 payload 的素材）；可为空。 */
    val intent: String,
    /** 提案路由要用的证据 id（skill_event id；其余类型为空）。 */
    val skillEventIds: List<Long> = emptyList(),
    /** 提案路由要用的目标（技能名；其余类型为空）。 */
    val target: String = "",
    /** 事实数字（成功率信号用：成功／失败／被纠；其余为 0）。 */
    val successes: Int = 0,
    val failures: Int = 0,
    val corrections: Int = 0,
    /** ① 专用：该 run 时间跨度内的回滚账行数（其余类型为 0）。 */
    val rollbackCount: Int = 0,
)

/**
 * 四条件的**纯判据**（零 I/O、零模型；JVM 直测）。
 *
 * 每个条件一个函数，[signalsOf] 只做"取哪条"的组合——判据与组合分开，
 * 是为了让每个阈值都能被钉子钉住（负例：把判据删了只剩组合 ⇒ 测试红）。
 */
internal object EvalTriggerRules {

    // ── ① 终态 FAILED 或回滚 ≥1 次 ────────────────────────────────────────

    /**
     * 最近一个**已结束**的 run：终态判 FAILURE，或它的时间跨度里有回滚账行 ⇒ 触发。
     *
     * 为什么只看最近一个：本判据挂在会话收尾（Settled）上，最近一个就是刚收尾的这个；
     * 更早的 run 在它们各自的收尾点已经被判过一次。终态判定走 [RunVerdict]——
     * 「FAILED 之后又有 OK＝被救回来」那条口径在这里不用重写。
     */
    fun failedRun(runs: List<TriggerRunRow>, rollbackAtMs: List<Long>): TriggerRunRow? {
        val last = runs.lastOrNull() ?: return null
        if (RunVerdict.outcomeOf(last.statuses) == RunOutcome.FAILURE) return last
        val rolledBack = rollbackAtMs.any { it in last.startedAtMs..last.atMs }
        return if (rolledBack) last else null
    }

    // ── ② 用户连续两次纠同一件事 ─────────────────────────────────────────

    /**
     * 同一技能**最后两条**效果信号都是 USER_CORRECTED ⇒ 触发。
     *
     * "连续"看写入顺序（id 序，[rows] 由取数层保证升序）：中间夹着任何别的结论
     * （成了／砸了）都算打断——用户纠一句、模型改对了，就不是"连续两次"。
     * 认不出的 outcome 丢弃（判不了就不猜；也不许拿脏值打断真实序列）。
     */
    fun correctedSkill(rows: List<TriggerSkillRow>): Pair<String, List<Long>>? {
        val byName = LinkedHashMap<String, MutableList<TriggerSkillRow>>()
        for (row in rows) {
            if (SkillOutcome.of(row.outcome) == null) continue
            byName.getOrPut(row.name) { ArrayList() }.add(row)
        }
        // 多个技能都满足时取**最近被纠**的那个（最后一行 id 最大的）。
        var best: Pair<String, List<Long>>? = null
        for ((name, rs) in byName) {
            if (rs.size < 2) continue
            val lastTwo = rs.takeLast(2)
            val bothCorrected = lastTwo.all {
                SkillOutcome.of(it.outcome) == SkillOutcome.USER_CORRECTED
            }
            if (!bothCorrected) continue
            val recency = rs.last().id
            if (best == null || recency > best.second.last()) {
                best = name to lastTwo.map { it.id }
            }
        }
        return best
    }

    // ── ③ 某技能成功率跌破阈值 ───────────────────────────────────────────

    /**
     * 分母线：少于这个样本数不判率（与 281 `Metrics.MIN_DENOM`、255 `MIN_SAMPLES` 同数——
     * 三处共用一个口径不是巧合，是"分母不够不算率"的全仓纪律）。
     */
    const val RATE_MIN_SAMPLES = 10

    /** 阈值：成功率 < 50% 才触发（等号不动——"恰好一半"还算不上跌破）。 */
    const val RATE_THRESHOLD = 0.5

    /**
     * 成功率口径（写死在这里，别处不许再算一遍）：
     * **SUCCESS ÷（SUCCESS＋FAILURE＋USER_CORRECTED）**，RETRIED 不进分母——
     * 它标记的是"上一条砸了这次重来"，不是这次自己的结果（85 批的四值语义）。
     */
    fun successRate(successes: Int, failures: Int, corrections: Int): Double? {
        val denom = successes + failures + corrections
        if (denom < RATE_MIN_SAMPLES) return null
        return successes.toDouble() / denom
    }

    /** ③ 的信号：窗内（30 天）有技能样本够且率跌破 ⇒ 触发；多个都跌破取**最近用过**的。 */
    fun skillBelowRate(rows: List<TriggerSkillRow>): EvalTriggerSignal? {
        var best: EvalTriggerSignal? = null
        for ((name, tally) in tallyBySkill(rows)) {
            val candidate = signalIfBelow(name, tally) ?: continue
            // 多个都跌破取**最近用过**的（证据 id 最大＝写入顺序最靠后）。
            if (best == null || (candidate.skillEventIds.lastOrNull() ?: 0L) > (best.skillEventIds.lastOrNull() ?: 0L)) {
                best = candidate
            }
        }
        return best
    }

    /** 按技能聚合：outcome → 证据 id（id 列表同时当证据与"最近用过"的依据）。 */
    private fun tallyBySkill(rows: List<TriggerSkillRow>): LinkedHashMap<String, MutableMap<SkillOutcome, MutableList<Long>>> {
        val byName = LinkedHashMap<String, MutableMap<SkillOutcome, MutableList<Long>>>()
        for (row in rows) {
            val o = SkillOutcome.of(row.outcome) ?: continue
            byName.getOrPut(row.name) { LinkedHashMap() }
                .getOrPut(o) { ArrayList() }
                .add(row.id)
        }
        return byName
    }

    /** 一个技能的聚合 ⇒ 信号（样本不够或率过线 ⇒ null，不触发）。 */
    private fun signalIfBelow(name: String, tally: Map<SkillOutcome, List<Long>>): EvalTriggerSignal? {
        val s = tally[SkillOutcome.SUCCESS]?.size ?: 0
        val f = tally[SkillOutcome.FAILURE]?.size ?: 0
        val c = tally[SkillOutcome.USER_CORRECTED]?.size ?: 0
        val rate = successRate(s, f, c) ?: return null
        if (rate >= RATE_THRESHOLD) return null
        val ids = tally.values.flatten().sorted()
        return EvalTriggerSignal(
            type = TriggerType.SKILL_RATE,
            ref = "skill_event:${ids.lastOrNull() ?: 0L}",
            intent = "技能「$name」最近 30 天成功率 ${"%d/%d".format(s, s + f + c)}（${"%d%%".format((rate * 100).toInt())}），跌破 ${"%d%%".format((RATE_THRESHOLD * 100).toInt())}",
            skillEventIds = ids,
            target = name,
            successes = s,
            failures = f,
            corrections = c,
        )
    }

    // ── 组合：一次取数 ⇒ 待判定信号（每类至多一条，顺序＝四条件编号） ────

    fun signalsOf(inputs: EvalTriggerInputs): List<EvalTriggerSignal> {
        val out = ArrayList<EvalTriggerSignal>(3)
        failedRun(inputs.runs, inputs.rollbackAtMs)?.let { run ->
            val rolls = inputs.rollbackAtMs.count { it in run.startedAtMs..run.atMs }
            out.add(
                EvalTriggerSignal(
                    type = TriggerType.RUN_FAILED,
                    ref = "task:${run.runId}",
                    intent = inputs.intent,
                    rollbackCount = rolls,
                ),
            )
        }
        correctedSkill(inputs.sessionSkillRows)?.let { (name, ids) ->
            out.add(
                EvalTriggerSignal(
                    type = TriggerType.USER_CORRECTION,
                    ref = "skill_event:${ids.lastOrNull() ?: 0L}",
                    intent = "技能「$name」被连续纠正两次",
                    skillEventIds = ids,
                    target = name,
                ),
            )
        }
        skillBelowRate(inputs.windowSkillRows)?.let { out.add(it) }
        return out
    }
}
