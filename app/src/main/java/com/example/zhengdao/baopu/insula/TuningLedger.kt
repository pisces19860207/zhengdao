// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶消费层的**落账**（调了什么、旧值→新值、依据哪条读数——一行不缺）。
//
// 两份文件（都在 `抱朴记忆/岛叶/`，与决策记录同一棵记忆树）：
//   · `参数.json`——四参数现值（岛叶唯一写盘的"参数面"；白名单外没有第五个键的路径，
//     文件形状本身就是白名单：读回时认不得的键直接丢弃，账上自造值不生效）。
//   · `调节账.jsonl`——三类行（kind 闭集）：tune（一次调节：param/from/to/basis）、
//     reset（一键回默认：整组默认值一栏）、observe（一次体征读数留底，卡对账用）。
//
// 可撤销＋可审计（§二④）：每次调节必落一行账；回默认把现值整组还原为出厂档并落 reset 行——
// **回默认也是一次账**，谁在什么时候回到什么值都对得上。历史账行永不删（停用不删历史的同款口径）。
//
// 写失败不抛（71 批①口径）：参数读不回 ⇒ 出厂默认（兜底＝现状），调节行没落上只记 RunLog。
package com.example.zhengdao.baopu.insula

import com.example.zhengdao.baopu.biogate.BioAnalogGate
import com.example.zhengdao.baopu.biogate.BioIntent
import com.example.zhengdao.baopu.biogate.GateVerdict
import com.example.zhengdao.baopu.biogate.ThreeQuestionGate
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 岛叶目录与两份文件（目录名一并住这里，装配与测试都用这一把）。 */
internal const val INSULA_DIR_NAME = "岛叶"
internal const val INSULA_PARAMS_FILE = "参数.json"
internal const val INSULA_LEDGER_FILE = "调节账.jsonl"

/** 账行 kind 闭集（tune／reset／observe）。 */
internal const val INSULA_KIND_TUNE = "tune"
internal const val INSULA_KIND_RESET = "reset"
internal const val INSULA_KIND_OBSERVE = "observe"

/** 岛叶目录（root＝记忆根，`DecisionLedger.installedRoot()` 同一深度）。 */
internal fun insulaDir(root: File): File = File(root, INSULA_DIR_NAME)

internal fun insulaParamsFile(root: File): File = File(insulaDir(root), INSULA_PARAMS_FILE)

internal fun insulaLedgerFile(root: File): File = File(insulaDir(root), INSULA_LEDGER_FILE)

/** 参数现值读回（唯一读口）。坏文件/没文件/键认不得 ⇒ 出厂默认（兜底＝现状，不编数）。 */
internal fun readTuning(root: File): BioTuning {
    val f = insulaParamsFile(root)
    if (!f.isFile) return BioTuning.DEFAULT
    return runCatching {
        val o = JSONObject(f.readText(Charsets.UTF_8))
        BioTuning(
            concurrency = o.optInt(TunableParam.CONCURRENCY.wire, BioTuning.DEFAULT_CONCURRENCY)
                .coerceIn(BioTuning.MIN_CONCURRENCY, BioTuning.DEFAULT_CONCURRENCY),
            batchSize = o.optInt(TunableParam.BATCH_SIZE.wire, BioTuning.DEFAULT_BATCH_SIZE)
                .coerceIn(BioTuning.MIN_BATCH_SIZE, BioTuning.DEFAULT_BATCH_SIZE),
            retryBackoffMs = o.optLong(TunableParam.RETRY_BACKOFF.wire, BioTuning.DEFAULT_RETRY_BACKOFF_MS)
                .coerceIn(BioTuning.DEFAULT_RETRY_BACKOFF_MS, BioTuning.MAX_RETRY_BACKOFF_MS),
            deferHeavy = o.optBoolean(TunableParam.DEFER_HEAVY.wire, false),
        )
    }.getOrDefault(BioTuning.DEFAULT)
}

/** 参数现值写回（唯一写口；只写白名单四键——文件形状就是白名单）。 */
internal fun writeTuning(root: File, tuning: BioTuning) {
    runCatching {
        val o = JSONObject()
            .put(TunableParam.CONCURRENCY.wire, tuning.concurrency)
            .put(TunableParam.BATCH_SIZE.wire, tuning.batchSize)
            .put(TunableParam.RETRY_BACKOFF.wire, tuning.retryBackoffMs)
            .put(TunableParam.DEFER_HEAVY.wire, tuning.deferHeavy)
        insulaParamsFile(root).parentFile?.mkdirs()
        insulaParamsFile(root).writeText(o.toString(), Charsets.UTF_8)
    }.onFailure { RunLog.log("岛叶账：参数没落上——${it.javaClass.simpleName}") }
}

/** 追加一行账（唯一写口；失败只记日志，不抛）。 */
internal fun appendInsulaRow(root: File, row: JSONObject) {
    runCatching {
        val f = insulaLedgerFile(root)
        f.parentFile?.mkdirs()
        f.appendText(row.toString() + "\n", Charsets.UTF_8)
    }.onFailure { RunLog.log("岛叶账：行没落上——${it.javaClass.simpleName}") }
}

/** 读全账（测试与对账用；坏行跳过，不抛）。 */
internal fun readInsulaRows(root: File): List<JSONObject> {
    val f = insulaLedgerFile(root)
    if (!f.isFile) return emptyList()
    return runCatching {
        f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/**
 * 应用一批调节动作（每动作一行账＋参数面一次写回）。
 * [gate] 过不了的（白名单外）不落账不生效——门拒＝兜底侧。
 * @return 真正生效的动作（空表 ＝ 没调：没动作或全被门拦）
 */
internal fun applyTuningMoves(root: File, moves: List<TuningMove>, nowMs: Long, gate: BioAnalogGate): List<TuningMove> {
    if (moves.isEmpty()) return emptyList()
    val current = readTuning(root)
    val admitted = mutableListOf<TuningMove>()
    var next = current
    moves.forEach { move ->
        val verdict = gate.admit(
            BioIntent(
                organ = ThreeQuestionGate.ORGAN_INSULA,
                action = "TUNE_${move.param.name}",
                subject = move.param.wire,
                observable = "调节账 tune 行 param=${move.param.wire}，参数.json 现值变化",
            ),
        )
        if (verdict != GateVerdict.Admit) return@forEach
        next = applyMoves(next, listOf(move))
        appendInsulaRow(
            root,
            JSONObject()
                .put("v", 1)
                .put("kind", INSULA_KIND_TUNE)
                .put("at", nowMs)
                .put("param", move.param.wire)
                .put("cn", move.param.cn)
                .put("from", move.from)
                .put("to", move.to)
                .put("basis", move.basis)
                .put("why", "521 岛叶自调"),
        )
        admitted.add(move)
    }
    if (admitted.isNotEmpty()) writeTuning(root, next)
    return admitted
}

/**
 * 一键回默认（撤销面：整组还原为出厂档＋落一行 reset 账；历史账行不删）。
 * @return 回默认前的现值（调用方上屏"从什么回到什么"用；写盘失败也返回它——
 * 参数读回口下一轮自然回到旧值，账行缺失只是少一条证据，不许把撤的动作谎报成功）
 */
internal fun resetTuningToDefaults(root: File, nowMs: Long): BioTuning {
    val before = readTuning(root)
    writeTuning(root, BioTuning.DEFAULT)
    appendInsulaRow(
        root,
        JSONObject()
            .put("v", 1)
            .put("kind", INSULA_KIND_RESET)
            .put("at", nowMs)
            .put("from", before.wireLine())
            .put("to", BioTuning.DEFAULT.wireLine())
            .put("by", "用户")
            .put("why", "521 岛叶自调·一键回默认"),
    )
    return before
}

/** 读数留底（observe 行：这轮看见了什么体征——调节行的依据可回放）。 */
internal fun appendObserved(root: File, vitals: BioVitals, basisLine: String, nowMs: Long) {
    appendInsulaRow(
        root,
        JSONObject()
            .put("v", 1)
            .put("kind", INSULA_KIND_OBSERVE)
            .put("at", nowMs)
            .put("battery", vitals.batteryPct)
            .put("charging", vitals.charging)
            .put("hot", vitals.hot)
            .put("heapPressure", vitals.heapPressure ?: -1.0)
            .put("storageFreeRatio", vitals.storageFreeRatio ?: -1.0)
            .put("actP95Ms", vitals.actP95Ms ?: -1L)
            .put("budgetLeft", vitals.budgetLeft)
            .put("basis", basisLine)
            .put("why", "521 岛叶自调"),
    )
}

/** 现值压成一行（reset 账行的 from/to 格；四个白名单键按定序）。 */
internal fun BioTuning.wireLine(): String =
    "并发度=$concurrency,批量大小=$batchSize,重试间隔=${retryBackoffMs}ms,延后重活=$deferHeavy"
