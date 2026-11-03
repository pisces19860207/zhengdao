// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · 御医修复自己的账：`修复记录.jsonl`。
//
// 为什么另立一本，不塞体检账：体检账记的是"谁没在干活"（一行一份体检单），
// 修复账记的是"我动了什么、凭什么、能不能撤回"——**分母不同**，混一本会让两边读数互相污染。
//
// 为什么在 `repair/` 而不在 `inspect/`：体检层被 `InspectReadOnlyGateTest` 从源码层钉死
// "只读"（禁 `appendText`／`mkdirs`／`renameTo`），而修复账天生要追加。所以分层：
// **判据在 `inspect/`（零 IO），执行与账在 `repair/`（可写）**。
//
// 三条纪律（与 208／影子账／体检账同款，多一条"先记后修"）：
//   1. **append-only**：一次修复写**两行**——`intent`（要修什么／依据哪条体检）先落盘，
//      `result`（修完怎样）后落盘；**intent 落不上就不许动手**（铁律②）；
//   2. **留痕不留正文**：只存类目、目标名、原因、依据与结果，不存任何用户内容；
//   3. **写失败不抛、也不打日志**：本类刻意**不调 `RunLog`**——它的失败路径会被单测走到
//      （"intent 落不上 ⇒ 不许动手"那条用例就是故意造失败），而 JVM 单测里
//      `android.util.Log` 是桩，一调就抛。失败只回 `false`，由调用方决定怎么办。
package com.example.zhengdao.baopu.repair

import java.io.File
import org.json.JSONObject

/**
 * 修复账的一行。
 *
 * @param kind 类目的 `wire`（`index`／`ledger`／`temp`；见 `inspect.RepairKind`）
 * @param phase [RepairLedger.PHASE_INTENT] 或 [RepairLedger.PHASE_RESULT]
 * @param target 动了什么（相对路径；多个用 `、` 连）
 * @param reason 凭什么（判据层给的那句话）
 * @param evidence 依据哪一次体检（`diagnose:<时刻>:<类目>`）
 * @param outcome 结果（`done`／`failed`；intent 行留空）
 */
internal data class RepairEntry(
    val id: String,
    val atMs: Long,
    val kind: String,
    val phase: String,
    val target: String,
    val reason: String,
    val evidence: String,
    val outcome: String = "",
) {

    fun toJson(): JSONObject = JSONObject()
        .put("v", RepairLedger.VERSION)
        .put("id", id)
        .put("at", atMs)
        .put("kind", kind)
        .put("phase", phase)
        .put("target", target)
        .put("reason", reason)
        .put("evidence", evidence)
        .put("outcome", outcome)

    companion object {

        /** 认不出的行 ⇒ null（坏行不许当"修过"用——与体检账 parse 同一口径）。 */
        fun parse(raw: String): RepairEntry? = runCatching {
            val o = JSONObject(raw)
            RepairEntry(
                id = o.getString("id"),
                atMs = o.getLong("at"),
                kind = o.getString("kind"),
                phase = o.getString("phase"),
                target = o.optString("target"),
                reason = o.optString("reason"),
                evidence = o.optString("evidence"),
                outcome = o.optString("outcome"),
            )
        }.getOrNull()
    }
}

/** 修复账的读写（**只追加**；读侧带上界）。 */
internal object RepairLedger {

    const val FILE_NAME = "修复记录.jsonl"

    const val VERSION = 1

    /** 一次最多读几行（一次修复两行，200 行＝100 次修复；再多也没人看）。 */
    const val READ_LIMIT = 200

    const val PHASE_INTENT = "intent"

    const val PHASE_RESULT = "result"

    const val OUTCOME_DONE = "done"

    const val OUTCOME_FAILED = "failed"

    fun fileIn(root: File): File = File(root, FILE_NAME)

    /** 追加一行；返回有没有落上（**落不上只回 false，不抛、不打日志**）。 */
    fun write(root: File, entry: RepairEntry): Boolean = runCatching {
        val f = fileIn(root)
        f.parentFile?.mkdirs()
        f.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 读账（新→旧，最多 [limit] 行）。 */
    fun read(root: File, limit: Int = READ_LIMIT): List<RepairEntry> = runCatching {
        val f = fileIn(root)
        if (!f.isFile) return emptyList()
        f.readLines(Charsets.UTF_8)
            .mapNotNull(RepairEntry::parse)
            .takeLast(limit.coerceAtLeast(1))
            .reversed()
    }.getOrDefault(emptyList())

    /**
     * 已经**修成过**的目标（同一类目、`outcome = done` 的 result 行）。
     *
     * 账本那一路靠它去重：同一本账的同一批损坏记过一次就不再记（重复也是噪音）。
     */
    fun recordedTargets(root: File, kind: String): Set<String> = read(root)
        .filter { it.phase == PHASE_RESULT && it.kind == kind && it.outcome == OUTCOME_DONE }
        .map { it.target }
        .toSet()

    /**
     * 上一次修复只落了 `intent`、没有 `result` ⇒ 那次修到一半（返回那条 intent）。
     *
     * 为什么要问：进程被杀／盘满都可能停在中间。这时**不许再修**，先报需人处理——
     * 否则第二次修复会盖在"不知道上次做到哪"的状态上（铁律③：可回滚先要说得清）。
     */
    fun unfinishedIntent(root: File): RepairEntry? =
        read(root, 1).firstOrNull()?.takeIf { it.phase == PHASE_INTENT }
}
