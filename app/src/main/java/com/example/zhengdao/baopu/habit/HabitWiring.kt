// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 纹状体消费层的**生产接线**（判据一行不落在这里，判据在 HabitRules 纯层）。
//
// 三条纪律（照 676 `SubconsciousWiring` 的房型）：
// 1. **对被读的东西只读**：`run_trace` 只 SELECT（87 批建的轨迹表，一行一个边界，
//    只存枚举/计数/hash——本件不碰引擎、不碰会话）；自身产出只写自己的
//    `习惯/习惯账.jsonl` 与一个去重状态文件。
// 2. **不写技能**（判据：不直写技能，源码门禁 `HabitSourceGateTest` 盯着全包）：
//    本文件没有 `write_skill`／`SkillAutoWrite`／`SkillStore` 的任何引用——出卡只出建议，
//    人点采纳才把许愿**递进既有许愿流水线**（`WishFlow.pipeline(ctx).submit`），
//    之后草稿→隔离试跑→自检→灰度→转正全是流水线既有的事，转正动作永远是人批。
// 3. **消费岛叶的四参数**（§二②的"身体参数"在本件的真消费点）：批量大小＝本轮回看
//    多少个最近运行；并发度＝一轮最多浮几张新卡；重试间隔＝本轮失败后的冷却；
//    延后重活＝整个轨迹扫描这一轮让开（轨迹扫描是收工路径上的重活，卡上说了会延后）。
package com.example.zhengdao.baopu.habit

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.TracePhase
import com.example.zhengdao.baopu.agent.TraceStatus
import com.example.zhengdao.baopu.agent.RunTraceStore
import com.example.zhengdao.baopu.bio.Striatum
import com.example.zhengdao.baopu.biogate.BioAnalogGate
import com.example.zhengdao.baopu.biogate.BioIntent
import com.example.zhengdao.baopu.biogate.GateVerdict
import com.example.zhengdao.baopu.biogate.ThreeQuestionGate
import com.example.zhengdao.baopu.insula.BioTuning
import com.example.zhengdao.baopu.insula.readTuning
import com.example.zhengdao.baopu.wish.WishFlow
import com.example.zhengdao.baopu.wish.WishOutcome
import com.example.zhengdao.baopu.wish.WishProduct
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 纹状体一轮状态文件（lastFailAt 一行；坏文件当从没失败过）。 */
internal const val HABIT_ROUND_STATE_FILE = "习惯-轮次.json"

/** 采纳时递进许愿流水线的提交口（生产接 [WishFlow]；测试接假件）。 */
internal fun interface WishSubmit {
    fun submit(wishText: String): WishOutcome
}

/** 收工触发的那一行（`TriggerWiring` 调；调用方在 IO 上串行驱动）。 */
internal fun habitOnRunSettled(ctx: Context, nowMs: Long): List<HabitSuggestion>? {
    val root = DecisionLedger.installedRoot() ?: run {
        RunLog.log("纹状体：账根没装配，这轮不跑（不是没轨迹，是没地方落账）")
        return null
    }
    return runCatching {
        val tuning = readTuning(root)
        runHabitRound(root, recentRunSeqs(BaopuRuntime.db(ctx), scanLimit(tuning)), tuning, nowMs, ThreeQuestionGate)
    }.onFailure {
        RunLog.log("纹状体：这轮没跑成——${it.javaClass.simpleName}")
        noteHabitFailure(root, nowMs)
    }.getOrNull()
}

/**
 * 纹状体一轮的**可测核心**（零 Android：轨迹与"现在"全由调用方注入）：
 * 让闸 → 记账 → 判候选 → 过门 → 出卡。
 * @return 本轮新出的建议卡（空表 ＝ 没新习惯；null ＝ 这轮整体让开）
 */
internal fun runHabitRound(
    root: File,
    recent: List<RunSeq>,
    tuning: BioTuning,
    nowMs: Long,
    gate: BioAnalogGate,
): List<HabitSuggestion>? {
    if (tuning.deferHeavy) {
        RunLog.log("纹状体：重活延后（岛叶档），这轮不扫轨迹")
        return null
    }
    if (habitCoolingDown(root, tuning, nowMs)) return null
    if (recent.isEmpty()) return emptyList()
    val suggested = suggestedKeysWithTime(root)
    // 观察行按 runId 去重：收工是高频机会，同一轮运行不重复记账（账是证据，不是流水）。
    val seen = observedRunIds(root)
    recent.filter { it.runId !in seen }.forEach { run ->
        appendObserved(root, run, sequenceKeyOf(run.tools))
    }
    val candidates = habitCandidatesOf(recent, suggested.keys, nowMs)
    if (candidates.isEmpty()) return emptyList()
    return emitSuggestions(root, candidates, tuning, nowMs, gate)
}

/** 一轮的扫描窗：岛叶的批量大小说了算，只许往下调（上限钉在 [HabitLimits.SCAN_RUNS_MAX]）。 */
private fun scanLimit(tuning: BioTuning): Int =
    tuning.batchSize.coerceAtMost(HabitLimits.SCAN_RUNS_MAX)

/** 出卡（过门＋落账＋去重状态；门拒＝兜底侧，卡不出、账有行）。 */
private fun emitSuggestions(
    root: File,
    candidates: List<HabitCandidate>,
    tuning: BioTuning,
    nowMs: Long,
    gate: BioAnalogGate,
): List<HabitSuggestion> {
    val emitted = mutableListOf<HabitSuggestion>()
    candidates.take(tuning.concurrency).forEach { candidate ->
        val suggestion = suggestionOf(candidate)
        val verdict = gate.admit(
            BioIntent(
                organ = ThreeQuestionGate.ORGAN_STRIATUM,
                action = ThreeQuestionGate.ACTION_SUGGEST,
                subject = suggestion.seqKey,
                observable = "习惯账 suggest 行＋HabitCardStore 出现 habit 卡",
            ),
        )
        if (verdict != GateVerdict.Admit) {
            RunLog.log("纹状体：建议被门拦（${verdict}）——不出卡")
            return@forEach
        }
        appendSuggested(root, suggestion, nowMs)
        markSuggested(root, suggestion.seqKey, nowMs)
        emitted.add(suggestion)
        RunLog.log("纹状体：${candidate.count} 次一次过 ⇒ 固化建议（${candidate.seqKey}）")
    }
    if (emitted.isNotEmpty()) HabitCardStore.publish(emitted)
    return emitted
}

/** 人点采纳：把建议递进**既有**许愿流水线（本件只递，不写技能、不转正）。 */
internal fun adoptHabit(root: File?, cardId: String, submit: WishSubmit, nowMs: Long): String? {
    if (root == null) return "账根没装配，递不了许愿"
    val card = HabitCardStore.byId(cardId) ?: return "建议卡不在了（可能已收）"
    if (card.wishId.isNotEmpty()) return "这张已经递过许愿（${card.wishId}），等「许愿确认」就行"
    val verdict = ThreeQuestionGate.admit(
        BioIntent(
            organ = ThreeQuestionGate.ORGAN_STRIATUM,
            action = ThreeQuestionGate.ACTION_SUBMIT_WISH,
            subject = card.seqKey,
            observable = "习惯账 adopt 行（by=用户）＋卡原位换成带 wishId 的",
        ),
    )
    if (verdict != GateVerdict.Admit) return "采纳被门拦（$verdict）——不动"
    return when (val outcome = submit.submit(card.wishText)) {
        is WishOutcome.AwaitingConfirmation -> {
            appendAdopted(root, card, outcome.wishId, nowMs)
            HabitCardStore.replace(card.copy(wishId = outcome.wishId))
            RunLog.log("纹状体：采纳落地，许愿 ${outcome.wishId} 已进流水线（等人在会话里确认验收）")
            "已把「${card.tools.joinToString("、")}」这条流程递进许愿流水线（${outcome.wishId}）。" +
                "回「许愿确认 ${outcome.wishId} 1 2」挑验收判据，草稿才会生成并进沙盒试跑"
        }
        is WishOutcome.Rejected -> "许愿流水线拒了这条建议：${outcome.reason}"
        else -> "许愿流水线没接（${outcome.javaClass.simpleName}）——卡还在，可再试"
    }
}

/** 已记过 observe 行的 runId 集合（去重读口；从账现算——账是真相）。 */
internal fun observedRunIds(root: File): Set<String> = readHabitRows(root)
    .filter { it.optString("kind") == HABIT_KIND_OBSERVE }
    .map { it.optString("runId") }
    .filter { it.isNotEmpty() }
    .toSet()

/** 重试冷却：上一轮失败还没到重试间隔 ⇒ 这轮让开。 */
internal fun habitCoolingDown(root: File, tuning: BioTuning, nowMs: Long): Boolean {
    val lastFail = runCatching {
        val f = File(habitDir(root), HABIT_ROUND_STATE_FILE)
        if (!f.isFile) return false
        JSONObject(f.readText(Charsets.UTF_8)).optLong("lastFailAt", -1L)
    }.getOrDefault(-1L)
    if (lastFail <= 0) return false
    return nowMs - lastFail < tuning.retryBackoffMs
}

/** 记一轮失败（写失败只记日志）。 */
internal fun noteHabitFailure(root: File, nowMs: Long) {
    runCatching {
        val f = File(habitDir(root), HABIT_ROUND_STATE_FILE)
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("v", 1).put("lastFailAt", nowMs).toString(), Charsets.UTF_8)
    }.onFailure { RunLog.log("习惯账：轮次状态没落上——${it.javaClass.simpleName}") }
}

/**
 * 最近运行的归一序列（只 SELECT 既有 `run_trace`；ACT 边界按发生序）。
 * 差读数消费 694 纹状体器官（四格表）。756 语义收口：`corrected` 只能来自显式修正事件
 * （cause/from/to 三件齐）；`run_trace` 只记成败、没有修正动作与来源指针，故本缝如实传
 * `evidence = null`（⇒ corrected=false），不凭空造事件、也不做跨轮回填。
 */
internal fun recentRunSeqs(db: BaopuDb, limit: Int): List<RunSeq> {
    val runIds = recentRunIds(db, limit)
    if (runIds.isEmpty()) return emptyList()
    val store = RunTraceStore(db)
    return runIds.mapNotNull { id ->
        val rows = runCatching { store.traceOf(id) }.getOrElse { return@mapNotNull null }
        val act = rows.filter { it.phase == TracePhase.ACT && it.tool.isNotBlank() }
        if (act.isEmpty()) return@mapNotNull null
        val ok = act.all { it.status == TraceStatus.OK }
        val rpe = Striatum.rpe(Striatum.Outcome(ok = ok, evidence = null)).value
        RunSeq(
            runId = id,
            tools = act.map { it.tool },
            ok = ok,
            rpe = rpe,
            atMs = act.maxOf { it.startedAt },
        )
    }
}

/** 最近的 run id（轨迹表里按行号倒序取；读不出/空表 ⇒ 空表——本轮当没轨迹）。 */
private fun recentRunIds(db: BaopuDb, limit: Int): List<String> = runCatching {
    val ids = mutableListOf<String>()
    db.read { d ->
        d.rawQuery(
            "SELECT run_id FROM run_trace GROUP BY run_id ORDER BY MAX(id) DESC LIMIT $limit",
            null,
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
    }
    ids
}.getOrDefault(emptyList())
