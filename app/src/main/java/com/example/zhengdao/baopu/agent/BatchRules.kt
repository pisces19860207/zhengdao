// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十四批 · 183-a（2026-10-25）：编排引擎的**纯判据**——批上限、编号回灌、
// 批 checkpoint 的 JSON 形状、系统前缀后的编排指引。
//
// 为什么单独一个文件：runLoop 要真 SQLite ＋ 网络（本仓无 Robolectric ⇒ 归真机），
// 「回灌长什么样」「超批怎么截」必须能在 JVM 里逐字验。引擎只接线（RunEngine）。
//
// 设计依据：《Harness编排形态-设计-2026-10-24》§3.2（MAX_BATCH_CALLS=8）、
// §3.4（批回灌格式）、§3.5（批 checkpoint）、§3.6（编排指引 ≤6 行）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 编排的纯判据内核（无 Android/IO/线程；JVM 直测）。
 *
 * 两条硬口径：
 * 1. **回灌是一条编号文本**（§3.4）：`[i/N] 工具名(参数摘要≤80) → 成功/失败/未执行`，
 *    每个调用都有结局，整条 ≤ [FEED_MAX_CHARS] 字；纯文本 ⇒ `HistoryShapeGate` 天然通过。
 * 2. **超批不整批拒**（§3.2）：前 [RunRules.MAX_BATCH_CALLS] 个照跑，第 9 个起只回灌
 *    「未执行」，且不进打转计数（引擎侧接线见 RunEngine 的重复计数循环）。
 */
internal object BatchRules {

    /** 整批回灌的总长上限（派工单 §1.3：整批回灌总长 ≤1.5k 字）。 */
    const val FEED_MAX_CHARS = 1500

    /** 每行参数摘要的上限（§3.4：对齐 189 的 `RunRules.argsBrief` 再砍半到 80）。 */
    const val ARGS_BRIEF_MAX = 80

    /** 按调用截断时写的标记（派工单 §1.3 原文；不许静默截断）。 */
    const val TRUNCATED_MARK = "…（超长截断）"

    /** 超批调用的结局（第 9 个起统一这一句）。 */
    val OVER_LIMIT_REASON: String =
        "本批超过 ${RunRules.MAX_BATCH_CALLS} 个上限，请下轮重试" +
            "（每批至多 ${RunRules.MAX_BATCH_CALLS} 个）"

    /** 本轮提前收尾（拒绝/失败到线/停止）时，没轮到的调用统一这一句。 */
    const val EARLY_END_REASON = "本轮提前结束"

    /**
     * 系统前缀后追加的编排指引（§3.6 原文，≤6 行）。
     *
     * **只在编排开时追加**：关掉时引擎用的是原始 prefix 逐字节，这段不出现
     * （判据见 [systemPrefix] 与 `OrchestrationBatchTest`）。
     */
    val ORCHESTRATION_GUIDE: String = buildString {
        append("【批量调用编排】")
        append('\n').append("- 这一轮你可以一次提交多个工具调用（最多 ${RunRules.MAX_BATCH_CALLS} 个）。")
        append('\n').append("- 只把相互独立的调用放同一批；后者需要前者结果时，分两轮。")
        append('\n').append("- 读操作可并行；写操作逐个顺序执行；高风险动作每批只问你一次。")
        append('\n').append("- 同一批里不要提交相同的调用（同参重复会被熔断）。")
    }

    /**
     * 编排开时的 system 正文：原前缀 ＋ 空行 ＋ 指引。
     *
     * @param prefix 组装层给的原前缀（可能为空）
     * @return 原前缀非空 ⇒ `prefix + "\n\n" + 指引`；为空 ⇒ 只有指引
     */
    fun systemPrefix(prefix: String): String =
        if (prefix.isBlank()) ORCHESTRATION_GUIDE else prefix + "\n\n" + ORCHESTRATION_GUIDE

    /** 参数摘要（≤80 字；复用 189 的截断口径，不另写一份）。 */
    fun argsBrief(args: JSONObject): String = RunRules.argsBrief(args, ARGS_BRIEF_MAX)

    /** 参数指纹（§3.5 `args_sha`）：规范化参数的 sha256 前 8 位（与打转判据同一规范化）。 */
    fun argsSha(args: JSONObject): String =
        RunRules.sha256Hex(RunRules.canonicalArgs(args)).take(8)

    /** 一个调用的结局：三选一（§3.4），不许有第四种「不见了」。 */
    enum class Ending { DONE, FAILED, SKIPPED }

    /**
     * 回灌里的一行（纯数据）。
     *
     * @param index 批内序号（1-based，含超批的那些）
     * @param total 本批提交总数
     * @param detail 结局的细节：成功 = 正文摘要；失败 = 错误原文；未执行 = 原因
     */
    data class Entry(
        val index: Int,
        val total: Int,
        val name: String,
        val argsBrief: String,
        val ending: Ending,
        val detail: String = "",
    )

    /**
     * 把一条工具结果围栏压成回灌里的一行正文（不改工具侧截断，只去掉围栏外壳）。
     *
     * 围栏（`<tool_result …>` / 「以下是工具返回的数据」）是**逐条落库**用的形状；
     * 编号回灌是一条人话，把外壳去掉、换行压成空格，避免一整段围栏塞进 `[i/N]` 行。
     */
    fun excerpt(fenced: String): String = fenced
        .lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith(DataFence.FENCE_OPEN) ||
                t.startsWith("</tool_result") ||
                t.startsWith("（以下是工具返回的数据")
        }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 合成整批回灌（纯函数；同输入永远同样字节）。
     *
     * 形状（§3.4）：
     * ```
     * 【第 3 批 · 4 个调用 · 2 成功 1 失败 1 未执行】
     * [1/4] read_file({"path":"a.md"}) → 成功（312 字）：……
     * …
     * ```
     *
     * 超批（index > [RunRules.MAX_BATCH_CALLS]）默认**逐条**标
     * `[9/12] read_file → 未执行：本批超过 8 个上限…`；只有「一次交太多、逐条会撑爆
     * 预算」时才合成一行区间 `[9/120]–[120/120] 共 112 个调用 → 未执行：…`
     * ——区间仍覆盖每个序号，每个调用仍有结局。
     *
     * @param batchNo 本 run 的第几批（1-based）
     * @param entries 全部提交的调用（**含超批**），按提交顺序
     * @param limit 总长上限（测试可传小值；生产用 [FEED_MAX_CHARS]）
     */
    fun feed(batchNo: Int, entries: List<Entry>, limit: Int = FEED_MAX_CHARS): String {
        if (entries.isEmpty()) return "【第 $batchNo 批 · 0 个调用】"
        val total = entries.maxOf { it.total }.coerceAtLeast(entries.size)
        val head = entries.filter { it.index <= RunRules.MAX_BATCH_CALLS }
        val tail = entries.filter { it.index > RunRules.MAX_BATCH_CALLS }
        val done = entries.count { it.ending == Ending.DONE }
        val failed = entries.count { it.ending == Ending.FAILED }
        val skipped = entries.size - done - failed
        val header = "【第 $batchNo 批 · $total 个调用 · $done 成功 $failed 失败 $skipped 未执行】"

        // 每行的「结局之前」部分固定不动；detail 是唯一可截的部分（结局永远留着）。
        val prefixes = head.map { e -> "[${e.index}/$total] ${e.name}(${e.argsBrief}) → ${endingLabel(e.ending)}" }
        val details = head.map { e -> detailText(e) }
        // 超批默认**逐条**标（派工单 §1.1：「第 9 个起逐条标未执行」）；
        // 只有逐条会把 1.5k 撑爆（一次交上百个）时才合成一行区间——
        // 那时区间仍覆盖每个序号，总长仍守预算。
        val tailPerCall = tail.map { e ->
            "[${e.index}/$total] ${e.name} → 未执行：${e.detail.ifBlank { OVER_LIMIT_REASON }}"
        }
        val tailRange = if (tail.isEmpty()) {
            null
        } else {
            "[${tail.first().index}/$total]–[${tail.last().index}/$total] 共 ${tail.size} 个调用" +
                " → 未执行：$OVER_LIMIT_REASON"
        }
        val perCallCost = header.length + prefixes.size + prefixes.sumOf { it.length } +
            tailPerCall.size + tailPerCall.sumOf { it.length }
        val tailLines = if (tailRange != null && perCallCost > limit) listOf(tailRange) else tailPerCall
        val lineCount = prefixes.size + tailLines.size
        val base = header.length + lineCount + prefixes.sumOf { it.length } + tailLines.sumOf { it.length }
        val avail = (limit - base).coerceAtLeast(0)
        val full = details.sumOf { it.length }
        val rendered = if (full <= avail) {
            details
        } else {
            val per = if (details.isEmpty()) 0 else {
                ((avail - details.size * TRUNCATED_MARK.length) / details.size).coerceAtLeast(0)
            }
            details.map { d ->
                if (d.length <= per) d else d.take(per) + TRUNCATED_MARK
            }
        }
        return buildString {
            append(header)
            prefixes.forEachIndexed { i, p ->
                append('\n').append(p).append(rendered[i])
            }
            tailLines.forEach { append('\n').append(it) }
        }
    }

    private fun endingLabel(ending: Ending): String = when (ending) {
        Ending.DONE -> "成功"
        Ending.FAILED -> "失败"
        Ending.SKIPPED -> "未执行"
    }

    private fun detailText(e: Entry): String = when (e.ending) {
        // 成功：带字数＋摘要，让人一眼看出「这条回灌是不是空的」
        Ending.DONE -> if (e.detail.isBlank()) "" else "（${e.detail.length} 字）：${e.detail}"
        // 失败 / 未执行：结局后面直接跟原因
        Ending.FAILED, Ending.SKIPPED -> if (e.detail.isBlank()) "" else "：${e.detail}"
    }

    // ── 批 checkpoint（§3.5；只进 run_log 的 err 列，不动表结构） ─────────────

    /**
     * 批首行的 payload：`{"batch":"b3","step":7,"calls":[{"i":1,"name":…,"args_sha":…}],"stages":[…]}`。
     *
     * `args_sha` 只留指纹（前 8 位），参数原文不落库——批账是取证用的，不是参数备份。
     * [ToolStage] 的成员写成**批内序号**（1-based），与回灌的 `[i/N]` 同一口径。
     */
    fun checkpointStart(
        batchId: String,
        step: Int,
        calls: List<ToolCall>,
        stages: List<ToolStage>,
    ): String {
        val callsJson = JSONArray()
        calls.forEachIndexed { i, c ->
            callsJson.put(
                JSONObject()
                    .put("i", i + 1)
                    .put("name", c.name)
                    .put("args_sha", argsSha(c.arguments)),
            )
        }
        val stagesJson = JSONArray()
        stages.forEach { s ->
            when (s) {
                is ToolStage.Serial -> stagesJson.put(
                    JSONObject()
                        .put("t", "serial")
                        .put("m", JSONArray().put(indexOf(calls, s.call))),
                )

                is ToolStage.Parallel -> {
                    val members = JSONArray()
                    s.calls.forEach { members.put(indexOf(calls, it)) }
                    stagesJson.put(JSONObject().put("t", "parallel").put("m", members))
                }
            }
        }
        return JSONObject()
            .put("batch", batchId)
            .put("step", step)
            .put("calls", callsJson)
            .put("stages", stagesJson)
            .toString()
    }

    /**
     * 段收口的 payload：`{"batch":"b3","stage":1,"done":[1,2],"done_all":[1,2]}`。
     *
     * `done` = 这一段有哪些调用落了地；`done_all` = 到这一刻为止整批落了哪些
     * （同一行反复 UPDATE ⇒ 进程被杀时最后一条就是「跑到哪了」）。
     */
    fun checkpointStage(
        batchId: String,
        stage: Int,
        done: List<Int>,
        doneAll: List<Int>,
    ): String = JSONObject()
        .put("batch", batchId)
        .put("stage", stage)
        .put("done", JSONArray(done))
        .put("done_all", JSONArray(doneAll))
        .toString()

    private fun indexOf(calls: List<ToolCall>, call: ToolCall): Int =
        calls.indexOfFirst { it === call }
            .let { if (it >= 0) it else calls.indexOf(call) }
            .let { if (it >= 0) it + 1 else 0 }
}

/**
 * 一批调用的收口器（纯内存；落库与回灌由引擎调）。
 *
 * 构造时**先把每个调用都填上结局**（未执行：本轮提前结束 / 本批超过上限）——
 * 「每个调用必须有结局」因此是结构保证：后面的执行结果只是**覆盖**已有结局，
 * 任何提前返回/异常都不会让某个调用凭空消失。
 */
internal class BatchCollector(
    val batchNo: Int,
    val batchId: String,
    val calls: List<ToolCall>,
) {

    private class Slot(
        val call: ToolCall,
        val index: Int,
        var ending: BatchRules.Ending,
        var detail: String,
    ) {
        var resolved = false
    }

    private val slots: List<Slot> = calls.mapIndexed { i, c ->
        val index = i + 1
        val over = index > RunRules.MAX_BATCH_CALLS
        Slot(
            call = c,
            index = index,
            ending = BatchRules.Ending.SKIPPED,
            detail = if (over) BatchRules.OVER_LIMIT_REASON else BatchRules.EARLY_END_REASON,
        )
    }

    val size: Int get() = slots.size

    /** 已收口的批内序号（段收口 payload 的 `done_all`）。 */
    private val closed = LinkedHashSet<Int>()

    fun recordDone(call: ToolCall, wrapped: String) {
        fill(call) { it.ending = BatchRules.Ending.DONE; it.detail = BatchRules.excerpt(wrapped) }
    }

    fun recordFailed(call: ToolCall?, error: String) {
        fill(call) { it.ending = BatchRules.Ending.FAILED; it.detail = error }
    }

    fun recordRejected(call: ToolCall?, reason: String) {
        fill(call) { it.ending = BatchRules.Ending.FAILED; it.detail = reason }
    }

    fun recordSkipped(call: ToolCall?, why: String) {
        fill(call) { it.ending = BatchRules.Ending.SKIPPED; it.detail = why }
    }

    /** 这一段收口：记下这几条已落地，返回整批到此刻的 `done_all`。 */
    fun stageClosed(stage: ToolStage): List<Int> {
        callsOf(stage).forEach { c ->
            val idx = slots.indexOfFirst { it.call === c }
            if (idx >= 0) closed += slots[idx].index
        }
        return closed.toList()
    }

    /** 这一段里各调用的批内序号（checkpoint 的 `done`）。 */
    fun stageIndices(stage: ToolStage): List<Int> = callsOf(stage).mapNotNull { c ->
        slots.indexOfFirst { it.call === c }.takeIf { it >= 0 }?.let { slots[it].index }
    }

    fun feed(limit: Int = BatchRules.FEED_MAX_CHARS): String = BatchRules.feed(
        batchNo,
        slots.map {
            BatchRules.Entry(
                index = it.index,
                total = slots.size,
                name = it.call.name,
                argsBrief = BatchRules.argsBrief(it.call.arguments),
                ending = it.ending,
                detail = it.detail,
            )
        },
        limit,
    )

    private fun callsOf(stage: ToolStage): List<ToolCall> = when (stage) {
        is ToolStage.Serial -> listOf(stage.call)
        is ToolStage.Parallel -> stage.calls
    }

    private inline fun fill(call: ToolCall?, block: (Slot) -> Unit) {
        val slot = if (call != null) {
            slots.firstOrNull { it.call === call && !it.resolved }
                ?: slots.firstOrNull { it.call.id == call.id && !it.resolved }
                ?: slots.firstOrNull { it.call.name == call.name && !it.resolved }
        } else {
            null
        } ?: return
        slot.resolved = true
        block(slot)
    }
}
