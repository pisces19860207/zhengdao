// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 纹状体消费层的**落账**（自身产出，只追加不覆盖）。
//
// 两份文件（都在 `抱朴记忆/习惯/`，与决策记录同一棵记忆树，用户可看可备份）：
//   · `习惯账.jsonl`——三类行（kind 闭集）：observe（一次运行的序列＋差读数）、
//     suggest（出了一张建议卡）、adopt（人点了采纳，许愿递进流水线）。
//     建议采纳是**人**的动作（判据：转正永远人批）——账行里的人是「用户」，机器不冒充。
//   · `习惯-建议.json`——已建议过的序列键 → 建议时刻（去重闸的读回源；坏文件当作没建议过，
//     重提一遍比永不开口便宜）。
//
// 纪律（照 676 `SubconsciousWiring` 的房型）：写失败不抛、只记 RunLog 一行——
// 账是留痕不是闸门，卡住不许把已经发生的动作回滚（71 批①口径）。
package com.example.zhengdao.baopu.habit

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 习惯账文件名（`抱朴记忆/习惯/` 层；目录名一并住这里，装配与测试都用这一把）。 */
internal const val HABIT_DIR_NAME = "习惯"
internal const val HABIT_LEDGER_FILE = "习惯账.jsonl"
internal const val HABIT_STATE_FILE = "习惯-建议.json"

/** 账行 kind 闭集（observe／suggest／adopt；账上自造值读回时跳过，不静默当别的）。 */
internal const val HABIT_KIND_OBSERVE = "observe"
internal const val HABIT_KIND_SUGGEST = "suggest"
internal const val HABIT_KIND_ADOPT = "adopt"

/** 建议账行与采纳账行的依据前缀（对账 grep 用，一字不差）。 */
internal const val HABIT_WHY = "521 纹状体固化"

/** 习惯目录（root＝记忆根，[com.example.zhengdao.baopu.DecisionLedger.installedRoot] 同一深度）。 */
internal fun habitDir(root: File): File = File(root, HABIT_DIR_NAME)

internal fun habitLedgerFile(root: File): File = File(habitDir(root), HABIT_LEDGER_FILE)

internal fun habitStateFile(root: File): File = File(habitDir(root), HABIT_STATE_FILE)

/**
 * 追加一行（唯一写口；父目录不存在就建）。失败只记一行日志，不抛——
 * 观察行没落上顶多这轮少一条证据，不许把收工触发带下去（65/89 口径）。
 */
internal fun appendHabitRow(root: File, row: JSONObject) {
    runCatching {
        val f = habitLedgerFile(root)
        f.parentFile?.mkdirs()
        f.appendText(row.toString() + "\n", Charsets.UTF_8)
    }.onFailure { RunLog.log("习惯账：行没落上——${it.javaClass.simpleName}") }
}

/** 一次运行进账（observe 行：序列＋差读数＋runId 指回去，证据可回放）。 */
internal fun appendObserved(root: File, run: RunSeq, seqKey: String?) {
    appendHabitRow(
        root,
        JSONObject()
            .put("v", 1)
            .put("kind", HABIT_KIND_OBSERVE)
            .put("at", run.atMs)
            .put("runId", run.runId)
            .put("seq", seqKey ?: "")
            .put("ok", run.ok)
            .put("rpe", run.rpe)
            .put("why", HABIT_WHY),
    )
}

/** 出了一张建议卡（suggest 行：谁、什么时候、第几次达标）。 */
internal fun appendSuggested(root: File, suggestion: HabitSuggestion, nowMs: Long) {
    appendHabitRow(
        root,
        JSONObject()
            .put("v", 1)
            .put("kind", HABIT_KIND_SUGGEST)
            .put("at", nowMs)
            .put("seq", suggestion.seqKey)
            .put("cardId", suggestion.id)
            .put("count", suggestion.count)
            .put("why", HABIT_WHY),
    )
}

/** 人点了采纳（adopt 行：by 恒「用户」——机器不冒充人，判据钉死）。 */
internal fun appendAdopted(root: File, suggestion: HabitSuggestion, wishId: String, nowMs: Long) {
    appendHabitRow(
        root,
        JSONObject()
            .put("v", 1)
            .put("kind", HABIT_KIND_ADOPT)
            .put("at", nowMs)
            .put("seq", suggestion.seqKey)
            .put("cardId", suggestion.id)
            .put("wishId", wishId)
            .put("by", "用户")
            .put("why", HABIT_WHY),
    )
}

/** 读全账（测试与对账用；坏行跳过，不抛）。 */
internal fun readHabitRows(root: File): List<JSONObject> {
    val f = habitLedgerFile(root)
    if (!f.isFile) return emptyList()
    return runCatching {
        f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/**
 * 已建议过的序列键 → 建议时刻（判据③去重闸的读回源）。
 * 状态文件优先；没有或坏 ⇒ 从账行现算（suggest 行）——账是真相，状态只是加速。
 */
internal fun suggestedKeysWithTime(root: File): Map<String, Long> {
    val f = habitStateFile(root)
    val fromState = runCatching {
        if (!f.isFile) return@runCatching null
        val o = JSONObject(f.readText(Charsets.UTF_8))
        val m = mutableMapOf<String, Long>()
        o.keys().forEach { k -> m[k] = o.optLong(k, 0L) }
        if (m.isEmpty()) null else m
    }.getOrNull()
    if (fromState != null) return fromState
    return readHabitRows(root)
        .filter { it.optString("kind") == HABIT_KIND_SUGGEST && it.optString("seq").isNotEmpty() }
        .groupBy { it.optString("seq") }
        .mapValues { (_, rows) -> rows.maxOf { it.optLong("at", 0L) } }
}

/** 记一条"已建议"（出卡后调；失败只记日志——下轮顶多重提一次，去重闸不会因此失效）。 */
internal fun markSuggested(root: File, seqKey: String, atMs: Long) {
    runCatching {
        val merged = suggestedKeysWithTime(root).toMutableMap()
        merged[seqKey] = atMs
        val o = JSONObject()
        merged.forEach { (k, v) -> o.put(k, v) }
        habitStateFile(root).parentFile?.mkdirs()
        habitStateFile(root).writeText(o.toString(), Charsets.UTF_8)
    }.onFailure { RunLog.log("习惯账：建议状态没落上——${it.javaClass.simpleName}") }
}
