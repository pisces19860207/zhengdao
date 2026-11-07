// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 474 轨迹监控的**黑匣子**（派工单 §一）。
//
// 病灶（派工单原话「黑匣子，不是采样器」）：金吾卫（313）是**触发式采样**——没命中四个
// 触发条件就只记步、不产出；运行中「偏没偏」因此在采样口外面。想知道「这一轮走到哪儿、
// 哪儿歪了」，只有把每一步都落下来才答得出。
//
// 本文件落的正是「每一步一行」，而它**不是第三个观察器**（派工单 §一④的红线）：
// 喂入口照旧只有 `agent/JinwuObserve.observe` 那一个（管线五处收口调它，一处不多），
// 本文件只是那条喂入口里的**第二路去向**——第一路是金吾卫采样（313），这一路是落盘。
// 参数摘要复用同一个口径 [com.example.zhengdao.baopu.inspect.JinwuPatrol.argsDigestOf]
// （digest 在喂入口就算好，匣子拿不到参数本体），风险档复用 [RiskMatrix.baseOf] 的**底档**
// （派工单 §四「不改风险判档」：只取档名进轨迹，判档那条链一个字不动，查不到就如实记
// UNCLASSIFIED，不拿 fail-closed 的 R2 冒充这一行的档位）。
//
// 四类异常（判据①②，闭集 [TraceAnomaly]）与它们的**既有口径**（一条都不另立）：
//   同工具连败 ≥3    ⇒ 线借内核 [BaopuKernel.Budget.MAX_TOOL_FAILURES]（那是「工具坏了」那把尺）；
//   反复重试同参数   ⇒ 线借内核 [BaopuKernel.Budget.REPEAT_CALL_LIMIT]（那是「同参再调」那把尺）；
//   计划外路径       ⇒ 事实借 242 的契约偏离（`NeedsConfirm.deviation != null`——
//                       全仓唯一说「计划里没有这一步」的生产事实，本层不从文案里反推）；
//   单轮步数超阈 25  ⇒ 本层唯一的新数 [TraceBoxRules.ROUND_STEP_WARN]，它是**提醒线**，与引擎
//                       300 步硬刹（[BaopuKernel.Budget.MAX_STEPS]）不是一把尺：硬刹停手，这条只标异常。
//
// 只发现、不阻断（判据②）：[observe] 返回 Unit、全程 runCatching，落盘失败只留日志——
// 匣子是记事的，没有一票否决权。达阈值那一路把事实交给 475（`escalate/Escalation`），
// 「要不要停下来问人」由那边判，这边只递；递之前先查出处（轮次为空的升级件构造期就拦，
// 与 339「无出处不发」同一条纪律）。
//
// 正文进不来（判据⑥）：[BlackboxRow] 里没有能装正文的字段——参数只留 16 位摘要，工具名有
// 长度上限，异常列是闭集键名；[ObservedStep] 同样只收 digest，不收参数对象。
// 落点是一本 JSONL 分片（[TraceBlackbox.ROWS_PER_SHARD] × [TraceBlackbox.SHARD_KEEP]，
// 满了起新片、片数超上限删最老那片：轮转有上限，历史不无限长）。
package com.example.zhengdao.baopu.trace

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.agent.DeviationWiring
import com.example.zhengdao.baopu.escalate.BoundaryFacts
import com.example.zhengdao.baopu.escalate.BoundaryRules
import com.example.zhengdao.baopu.escalate.BoundaryTable
import com.example.zhengdao.baopu.escalate.BoundaryVerdict
import com.example.zhengdao.baopu.escalate.CapabilityBoundaries
import com.example.zhengdao.baopu.escalate.CapabilityKind
import com.example.zhengdao.baopu.escalate.Escalation
import com.example.zhengdao.baopu.escalate.EscalationTrigger
import com.example.zhengdao.baopu.escalate.SayFact
import com.example.zhengdao.baopu.escalate.StopFact
import com.example.zhengdao.baopu.escalate.StopMode
import com.example.zhengdao.baopu.inspect.JinwuPatrol
import com.example.zhengdao.baopu.inspect.StepResultCode
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 运行中异常的四类（闭集；[wire] 是账上稳定键，文案会改键不许改）。 */
internal enum class TraceAnomaly(val wire: String, val cn: String) {
    /** 同一工具连着失败到线（该工具成功一次即打断）。 */
    SAME_TOOL_FAIL("same_tool_fail", "同工具连败"),

    /** 计划里没有这一步（242 契约偏离带上来的事实）。 */
    OFF_PLAN("off_plan", "计划外路径"),

    /** 同一工具＋同一参数摘要反复再来。 */
    REPEAT_ARGS("repeat_args", "反复重试同参数"),

    /** 一轮里步数超过提醒线。 */
    ROUND_STEP_OVER("round_step_over", "单轮步数超阈"),
    ;

    companion object {
        fun ofWire(wire: String): TraceAnomaly? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 黑匣子的一行（＝一个被观测到的工具动作；判据①「轨迹行数 == 工具调用数」数的是这个）。
 *
 * 声明里没有正文位、[toJson] 的键集也没有——想多带一段原文就得改这两处，判据⑥那条用例先红。
 *
 * @param index 本轮内的序号（从 1 起；引擎步号在管线收口处拿不到，见交付报告留白）
 * @param argsDigest 参数摘要（16 位十六进制，口径＝[JinwuPatrol.argsDigestOf]）
 * @param riskTier 风险**底档**名（[RiskMatrix.baseOf]；查不到＝`UNCLASSIFIED`，不冒充档位）
 * @param escape 这一笔是不是被第②段红线当越界拦下（事实转手，本层不判）
 * @param accumulatedMs 本轮自第一行起的累计耗时（真墙钟差；不知起点＝[TraceBoxRules.NO_START]）
 */
internal data class BlackboxRow(
    val id: String,
    val atMs: Long,
    val runId: String,
    val index: Int,
    val tool: String,
    val argsDigest: String,
    val result: String,
    val riskTier: String,
    val escape: Boolean,
    val accumulatedMs: Long,
    val anomalies: List<String>,
) {
    fun toJson(): String = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("run", runId)
        .put("ix", index)
        .put("tool", tool)
        .put("ad", argsDigest)
        .put("res", result)
        .put("risk", riskTier)
        .put("esc", escape)
        .put("cum", accumulatedMs)
        .put("an", JSONArray(anomalies))
        .toString()

    companion object {
        /** 行格式版本（改形状才 +1；加字段不算）。 */
        const val VERSION = 1

        fun parse(raw: String): BlackboxRow? {
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = o.optString("id")
            val tool = o.optString("tool")
            if (id.isBlank() || tool.isBlank()) return null
            return runCatching {
                BlackboxRow(
                    id = id,
                    atMs = o.optLong("at", 0L),
                    runId = o.optString("run"),
                    index = o.optInt("ix", 0),
                    tool = tool,
                    argsDigest = o.optString("ad"),
                    result = o.optString("res"),
                    riskTier = o.optString("risk"),
                    escape = o.optBoolean("esc", false),
                    accumulatedMs = o.optLong("cum", TraceBoxRules.NO_START),
                    anomalies = (o.optJSONArray("an") ?: JSONArray()).let { a ->
                        (0 until a.length()).mapNotNull { i -> a.optString(i).ifEmpty { null } }
                    },
                )
            }.getOrNull()
        }
    }
}

/**
 * 喂给黑匣子的那一步（由唯一的喂入口 `agent/JinwuObserve` 组好——它手上有管线结论与工具表）。
 *
 * 注意这一格**没有参数对象**：摘要在喂入口算，匣子从形状上就拿不到正文。
 *
 * @param capability [CapabilityKind.wire]，升级件的身份键（没归类也给 `other`）
 * @param escape 第②段红线拦下且理由是越界（别的拒绝理由不算——派工单 §二②）
 * @param offPlan 242 的契约偏离事实（deviation != null）
 */
internal data class ObservedStep(
    val atMs: Long,
    val callId: String,
    val tool: String,
    val argsDigest: String,
    val result: StepResultCode,
    val capability: String,
    val escape: Boolean = false,
    val offPlan: Boolean = false,
)

/** 黑匣子的纯规则（无 IO / 无时钟；四条线的合成规则写死在这里，JVM 直测）。 */
internal object TraceBoxRules {

    /** 同工具连败线：借内核那把「工具坏了」的尺，不另立第二个数。 */
    val SAME_TOOL_FAIL_MIN: Int = BaopuKernel.Budget.MAX_TOOL_FAILURES

    /** 反复重试同参数线：借内核那把「同参再调」的尺。 */
    val REPEAT_ARGS_MIN: Int = BaopuKernel.Budget.REPEAT_CALL_LIMIT

    /** 单轮步数提醒线（派工单 §一②点名的默认 25）；与引擎 300 步硬刹不是一把尺。 */
    const val ROUND_STEP_WARN = 25

    /** 一轮里带异常的行攒到几行才交 475（派工单 §一③「达阈值 ⇒ 交 475」）。 */
    const val ESCALATE_ANOMALY_MIN = 3

    /** 工具名落账上限（与 87 批 `RunTraceRules.TOOL_NAME_MAX` 同口径）。 */
    const val TOOL_NAME_MAX = 64

    /** 没登记进表时的风险档写法（只说「表上没有」，不替判档编一个数）。 */
    const val UNCLASSIFIED_TIER = "UNCLASSIFIED"

    /** 「不知道本轮从哪一毫秒起」的哨兵（负数＝未知，不写 0 假装知道）。 */
    const val NO_START = -1L

    /** 失败类结果（连败计数吃这三种；DONE／NEEDS_CONFIRM 打断）。 */
    val FAILURE_RESULTS: Set<String> = setOf(
        StepResultCode.FAILED.wire,
        StepResultCode.REJECTED.wire,
        StepResultCode.SCHEMA_INVALID.wire,
    )

    fun capToolName(name: String): String = name.trim().take(TOOL_NAME_MAX)

    /** 风险底档名（查不到／豁免都记 [UNCLASSIFIED_TIER]：这一格只说「表上有没有」）。 */
    fun riskTierOf(tool: String): String = RiskMatrix.baseOf(tool)?.name ?: UNCLASSIFIED_TIER

    /** 是不是失败类结果。 */
    fun isFailure(resultWire: String): Boolean = resultWire in FAILURE_RESULTS

    /**
     * 一步上的异常（纯函数：把已经数好的计数摆出来，谁到线谁说）。
     *
     * @param failRun 该工具当前连败次数（含这一步）
     * @param repeatRun 该 tool＋参数摘要的第几次出现（含这一步）
     * @param crossedStepLine 这一步是不是正好跨过步数提醒线（一轮只标一次，由调用方保证）
     */
    fun anomaliesOf(
        offPlan: Boolean,
        failed: Boolean,
        index: Int,
        failRun: Int,
        repeatRun: Int,
        crossedStepLine: Boolean,
    ): List<TraceAnomaly> {
        val out = ArrayList<TraceAnomaly>(4)
        if (failed && failRun >= SAME_TOOL_FAIL_MIN) out += TraceAnomaly.SAME_TOOL_FAIL
        if (offPlan) out += TraceAnomaly.OFF_PLAN
        if (repeatRun >= REPEAT_ARGS_MIN) out += TraceAnomaly.REPEAT_ARGS
        if (index > ROUND_STEP_WARN && crossedStepLine) out += TraceAnomaly.ROUND_STEP_OVER
        return out
    }

    /** 累计耗时：本轮起点已知 ⇒ 真差值（时钟回拨归 0）；未知 ⇒ [NO_START]。 */
    fun accumulatedMs(startMs: Long?, atMs: Long): Long =
        if (startMs == null || startMs <= 0L) NO_START else (atMs - startMs).coerceAtLeast(0L)
}

/** 一轮的计数（跑一轮的四个维度都在这；跨轮靠 [RUN_CAP] 淘汰最老的，不无底洞）。 */
private class RunCounters {
    var steps = 0
    var startMs = 0L
    var anomalyRows = 0
    var stepWarned = false
    var escalated = false
    val failByTool = HashMap<String, Int>()
    val repeatByCall = HashMap<String, Int>()
}

/**
 * 轨迹黑匣子（进程内单例：一行一步，只追加，轮转有上限，可回放）。
 *
 * 落点＝208 装配的那棵目录（与 `灾异事件.jsonl`／`升级请求.jsonl` 同一棵）；没装配
 * （纯 JVM 单测）⇒ 只进内存，回放照样查得到。写不动 ⇒ 一行日志，不抛、不挡主流程。
 */
internal object TraceBlackbox {

    /** 分片文件名前缀（`轨迹黑匣子-<n>.jsonl`，n 单调递增＝时间序）。 */
    const val SHARD_PREFIX = "轨迹黑匣子-"

    /** 分片文件后缀。 */
    const val SHARD_SUFFIX = ".jsonl"

    /** 一片最多几行（超了起新片）。 */
    const val ROWS_PER_SHARD = 500

    /** 最多留几片（超了删编号最老的——「有上限轮转」就是这一格）。 */
    const val SHARD_KEEP = 4

    /** 内存里最多留多少行（没装配落点时的兜底；回放以盘为准）。 */
    private const val MEMORY_CAP = 400

    /** 同时记几轮的计数（超了按最老的先出——长会话不许把计数表撑成无底洞）。 */
    private const val RUN_CAP = 32

    /** 幂等表最多记多少笔调用（同上，最老的先出）。 */
    private const val CALL_CAP = 2000

    private val memory = ArrayDeque<BlackboxRow>()

    /** 已落过的 callId（插入序＝时间序，超上界按最老的出）。 */
    private val seenCalls = LinkedHashMap<String, Boolean>()

    /** 轮次计数（runId → [RunCounters]）。 */
    private val runs = LinkedHashMap<String, RunCounters>()

    private var shardNo = -1
    private var shardRows = 0
    private var seq = 0

    /**
     * 喂一步（唯一入口，调用方只有 `agent/JinwuObserve`；判据②：不阻断、不抛）。
     *
     * 匣子只做两件事——落一行、把攒到的异常递给 475；它没有拦东西的权力。
     */
    fun observe(step: ObservedStep) {
        val row = runCatching { record(step) }.onFailure(::logFault).getOrNull()
        runCatching { handOff(row, step) }.onFailure {
            RunLog.log("抱朴轨迹：异常递不出去（475 那侧抛了）——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 落一行（返回 null＝这一笔已经落过，幂等丢弃）。 */
    @Synchronized
    private fun record(step: ObservedStep): BlackboxRow? {
        val callKey = step.callId.trim()
        if (callKey.isNotEmpty() && !markSeen(callKey)) return null
        val runId = DeviationWiring.currentRunId()
        val c = counters(runId)
        c.steps += 1
        if (c.startMs == 0L && step.atMs > 0L) c.startMs = step.atMs
        val failed = TraceBoxRules.isFailure(step.result.wire)
        val failRun = if (failed) (c.failByTool[step.tool] ?: 0) + 1 else 0
        if (failed) c.failByTool[step.tool] = failRun else c.failByTool.remove(step.tool)
        val repeatKey = "${step.tool}#${step.argsDigest}"
        val repeatRun = (c.repeatByCall[repeatKey] ?: 0) + 1
        c.repeatByCall[repeatKey] = repeatRun
        val crossed = c.steps > TraceBoxRules.ROUND_STEP_WARN && !c.stepWarned
        if (crossed) c.stepWarned = true
        val anomalies = TraceBoxRules.anomaliesOf(
            offPlan = step.offPlan,
            failed = failed,
            index = c.steps,
            failRun = failRun,
            repeatRun = repeatRun,
            crossedStepLine = crossed,
        )
        if (anomalies.isNotEmpty()) c.anomalyRows += 1
        val row = BlackboxRow(
            id = "bx:${step.atMs}:${++seq}",
            atMs = step.atMs,
            runId = runId,
            index = c.steps,
            tool = TraceBoxRules.capToolName(step.tool),
            argsDigest = step.argsDigest,
            result = step.result.wire,
            riskTier = TraceBoxRules.riskTierOf(step.tool),
            escape = step.escape,
            accumulatedMs = TraceBoxRules.accumulatedMs(c.startMs, step.atMs),
            anomalies = anomalies.map { it.wire },
        )
        memory.addLast(row)
        while (memory.size > MEMORY_CAP) memory.removeFirst()
        append(row)
        RunLog.log(
            "抱朴轨迹：#${row.index} ${row.tool} ${row.result}" +
                if (anomalies.isEmpty()) "" else "｜异常=${anomalies.joinToString("、")}",
        )
        return row
    }

    /**
     * 交 475（判据③＋§一③「达阈值 ⇒ 交 475」）。
     *
     * 「不许把不确定当越界」在这里执行：越界与否**由边界层判、不由匣子自己说**——
     * 匣子只把管线给的那条事实转手（`scopeEscapeDenied = step.escape`，没拦下就是 false，
     * 不硬写一个 true），判出 [BoundaryVerdict.OUT_OF_SCOPE] 才递；
     * 判出不确定就什么都不递（负例 M2 咬"不管管线有没有说越界都一律按越界递"）。
     * 本轮异常攒到 [TraceBoxRules.ESCALATE_ANOMALY_MIN] 行 ⇒ 递一件
     * [EscalationTrigger.TRACE_OVER_LIMIT]，一轮只递一次（[RunCounters.escalated]）。
     */
    private fun handOff(row: BlackboxRow?, step: ObservedStep) {
        if (row == null || row.runId.isBlank()) return
        val kind = CapabilityKind.ofWire(step.capability) ?: CapabilityKind.OTHER
        // 第五百一十七批：边界对象**唯一来源换声明表**（§二②「越界判定读它」）；
        // 表没装配（纯 JVM 单测/早期启动）⇒ 回落 505 的代码内声明，行为逐字不变。
        // 判定本体 [BoundaryRules] 一字未动（§四：只提供边界来源，不改 475 三要素）。
        val verdict = BoundaryRules.verdictOf(
            BoundaryTable.boundaryOf(kind) ?: CapabilityBoundaries.of(kind),
            BoundaryFacts(tool = step.tool, scopeEscapeDenied = step.escape),
        )
        if (verdict == BoundaryVerdict.OUT_OF_SCOPE) {
            Escalation.raise(
                capability = kind,
                trigger = EscalationTrigger.OUT_OF_SCOPE,
                stop = StopFact(StopMode.SAFE_ABORT, row.runId, "② 段红线拦下 ${row.tool}，动作未发生"),
                say = SayFact(
                    what = "要一次越界授权：${row.tool} 的目标在边界之外",
                    why = "第②段路径守卫把它拦下了（风险底档 ${row.riskTier}）",
                    suggestion = "要么换个边界内的目标，要么明确授权这一次写到哪儿",
                ),
                nowMs = step.atMs,
            )
        } else if (step.escape) {
            RunLog.log("抱朴轨迹：管线说是越界，边界层判出来是「${verdict.cn}」——不递升级")
        }
        val c = runs[row.runId] ?: return
        if (c.anomalyRows >= TraceBoxRules.ESCALATE_ANOMALY_MIN && !c.escalated) {
            c.escalated = true
            Escalation.raise(
                capability = kind,
                trigger = EscalationTrigger.TRACE_OVER_LIMIT,
                stop = StopFact(StopMode.WRAP_UP, row.runId, "本轮已攒 ${c.anomalyRows} 行轨迹异常"),
                say = SayFact(
                    what = "这一轮偏到线了：要不要停手看一眼轨迹",
                    why = anomalyNote(row.runId),
                    suggestion = "建议先回放本轮轨迹，再决定继续、换路还是收工",
                ),
                nowMs = step.atMs,
            )
        }
    }

    /** 本轮哪几类异常最多（体检单与升级件的「为什么」共用这一句）。 */
    @Synchronized
    fun anomalyNote(runId: String): String {
        val named = memory.filter { it.runId == runId }
            .flatMap { row -> row.anomalies.mapNotNull(TraceAnomaly::ofWire) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
        return if (named.isEmpty()) "本轮轨迹没有异常"
        else "前 ${named.size} 类：" + named.joinToString("、") { "${it.key.cn}×${it.value}" }
    }

    /** 本轮带异常的行数（达阈口径就是它）。 */
    @Synchronized
    fun anomalyRowCount(runId: String): Int = runs[runId]?.anomalyRows ?: 0

    /** 本轮已落几步（判据①与体检单那一行共用）。 */
    @Synchronized
    fun stepCount(runId: String): Int = runs[runId]?.steps ?: 0

    /** 回放一轮（判据②「可回放」；按序号升序，跨分片拼齐）。 */
    @Synchronized
    fun replay(runId: String, limit: Int = 0): List<BlackboxRow> {
        if (runId.isBlank()) return emptyList()
        val rows = (readAllRows() + memory.toList()).distinctBy { it.id }
            .filter { it.runId == runId }.sortedBy { it.index }
        return if (limit > 0) rows.take(limit) else rows
    }

    /**
     * 体检单那一行（派工单 §一③的字面格式：本轮轨迹 N 步 / 异常 M（前 3 条））。
     *
     * 「本轮」＝最近一次喂进来的那一轮；没喂过 ⇒ 明说没有，不留一格让人猜。
     */
    @Synchronized
    fun sheetLines(): List<String> {
        val runId = lastRunId() ?: return listOf("轨迹：本轮没有落盘观测")
        return listOf("本轮轨迹 ${stepCount(runId)} 步 / 异常 ${anomalyRowCount(runId)}${topThree(runId)}")
    }

    private fun topThree(runId: String): String {
        val named = memory.filter { it.runId == runId }
            .flatMap { row -> row.anomalies.mapNotNull(TraceAnomaly::ofWire) }.distinct().take(3)
        return if (named.isEmpty()) "" else "（前 ${named.size} 条：${named.joinToString("、") { it.cn }}）"
    }

    /** 最近一次喂进来的轮次（内存里没有 ⇒ 回落引擎信箱，测试与重启后照旧查得到）。 */
    @Synchronized
    fun lastRunId(): String? = runs.keys.lastOrNull()
        ?: DeviationWiring.currentRunId().takeIf { it.isNotBlank() }

    /** 分片文件（n 升序；没有落点 ⇒ 空表）。 */
    fun shardFiles(): List<File> {
        val root = DecisionLedger.installedRoot() ?: return emptyList()
        val files: List<File> = runCatching {
            root.listFiles { f: File -> f.isFile && f.name.startsWith(SHARD_PREFIX) && f.name.endsWith(SHARD_SUFFIX) }
                ?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        return files.sortedBy { shardNoOf(it.name) }
    }

    private fun readAllRows(): List<BlackboxRow> = shardFiles().flatMap { f ->
        runCatching { f.readLines(Charsets.UTF_8).mapNotNull(BlackboxRow::parse) }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun counters(runId: String): RunCounters {
        val c = runs.getOrPut(runId) { RunCounters() }
        while (runs.size > RUN_CAP) runs.remove(runs.keys.first())
        return c
    }

    /** 幂等表：见过返回 false（同一笔动作第二次不落到账上）。 */
    @Synchronized
    private fun markSeen(callKey: String): Boolean {
        val fresh = seenCalls.put(callKey, true) == null
        while (seenCalls.size > CALL_CAP) seenCalls.remove(seenCalls.keys.first())
        return fresh
    }

    /** 追加一行＋轮转：片满 ⇒ 起新片；片数超 [SHARD_KEEP] ⇒ 删编号最老的（写不动只留日志）。 */
    @Synchronized
    private fun append(row: BlackboxRow) {
        val root = DecisionLedger.installedRoot() ?: return
        val ok = runCatching {
            if (shardNo < 0) locateShard(root)
            if (shardRows >= ROWS_PER_SHARD) rollShard()
            shardFile(root, shardNo).appendText(row.toJson() + "\n", Charsets.UTF_8)
            shardRows += 1
            prune()
            true
        }.getOrDefault(false)
        if (!ok) RunLog.log("抱朴轨迹：这行没落上（${row.id}）——匣子在内存里有、盘上没有")
    }

    /** 盘上定位当前片：取编号最大那片，数它已有几行（重启后接着写同一片，不起一堆半片）。 */
    private fun locateShard(root: File) {
        val newest = shardFiles().lastOrNull()
        shardNo = newest?.let { shardNoOf(it.name) } ?: 0
        shardRows = newest?.let { f ->
            runCatching { f.readLines(Charsets.UTF_8).count { it.isNotBlank() } }.getOrDefault(0)
        } ?: 0
    }

    private fun rollShard() {
        shardNo += 1
        shardRows = 0
        RunLog.log("抱朴轨迹：起第 $shardNo 片（每片上限 $ROWS_PER_SHARD 行）")
    }

    /** 只留最近 [SHARD_KEEP] 片：更早的删掉（上限轮转；删失败只留日志，不抛）。 */
    private fun prune() {
        val all: List<File> = shardFiles()
        if (all.size <= SHARD_KEEP) return
        all.subList(0, all.size - SHARD_KEEP).forEach { f ->
            val gone = runCatching { f.delete() }.getOrDefault(false)
            RunLog.log(if (gone) "抱朴轨迹：轮转删掉老片 ${f.name}" else "抱朴轨迹：老片删不掉 ${f.name}")
        }
    }

    private fun shardFile(root: File, no: Int): File {
        val f = File(root, "$SHARD_PREFIX$no$SHARD_SUFFIX")
        f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        return f
    }

    private fun shardNoOf(name: String): Int =
        name.removePrefix(SHARD_PREFIX).removeSuffix(SHARD_SUFFIX).toIntOrNull() ?: 0

    private fun logFault(it: Throwable) {
        RunLog.log("抱朴轨迹：这一步没落账——${it.javaClass.simpleName}: ${it.message}")
    }

    /** 直测收尾（生产不调）：单例状态跨用例污染，谁用谁还；磁盘分片不动（回放靠它）。 */
    @Synchronized
    fun resetForTest() {
        memory.clear()
        seenCalls.clear()
        runs.clear()
        shardNo = -1
        shardRows = 0
        seq = 0
    }
}
