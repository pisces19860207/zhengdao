// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十八批 · 接管的**生产接线**（判定本体见 [TakeoverGate]，366；读数形状见
// [ProgressNote]／[ProcessObjectRules]，374）。366 交付报告 §六点名：「消费 ProgressNote
// 的调用方不在本批租约，留待后续批接线」——本文件就是那个后续批。
//
// 接一条链（派工单 §一③＋§三判据②）：
//   ProgressNote 进来 ⇒ 先过 374 的消费顺序（progressRejection → progressRegressionRejection
//   → regressionAuditLine 出账行）⇒ [TakeoverGate.classify] 判三类信号 ⇒ STALLED/EXHAUSTED
//   ⇒ [TakeoverGate.takeoverRejection] 过闸 ⇒ [TakeoverGate.run] 接管五步
//   （冻结现场→读留痕→影子先验→接剩余片→记账）⇒ 回执落 `接管账.jsonl`。
//
// 三条纪律：
//   · **不动别人的历史分支**（判据②）：冻结句柄 [FrozenBranch] 类型上只有 headSha() 一个
//     读口——本文件造的句柄读的是**账上记着的那枚 sha**，连写口都没有，更别提 force-push；
//   · **门拒绝不许静默**（判据③同款）：读数被 374 拒、接管被 [TakeoverGate.takeoverRejection]
//     拒，都落一行拒账（带原拒因），不吞；
//   · **不顺手重构**（派工单 §三）：判定全在 366/374 的件里，本文件只按序调用＋转写落账，
//     不写第二个判、不改一行判定。
//
// INCOMPLETE（谎报）不在本批接管线：派工单 §一③ 只点名 STALLED/EXHAUSTED ⇒ 接管五步；
// 谎报按协议 §二走"谎报处理线"（归后续批裁定），本文件只把信号落成事实行、不接管、不吞。
//
// 生产调用点两个：
//   · [reportProgress]——承接方报进度那一行的入口（派工单系统/UI 归后续批，本批先把口接上）；
//   · [sweepStalled]——`BaopuChatController` 的 Settled 收尾家族一行调用（心跳扫停滞：
//     读进度账每单最新两行，过消费顺序判信号）。
//
// 落盘两本（与 274/277/298/278 同房型）：`<App私有>/抱朴记忆/进度自报.jsonl`（读数）与
// `接管账.jsonl`（回执＋拒账），追加-only、写失败只 [RunLog] 不抛。
// 跨进程续跑：已接管集合是进程态（重启后同一单可再评——进度账不丢，接管账也不丢，
// 重复接管会被 [TakeoverGate.takeoverRejection] 的"全部已验无需接管"闸自然拒住，不静默）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.plan.ProcessObjectRules
import com.example.zhengdao.baopu.plan.ProgressNote
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一单接管的现场读数（派单层给什么就记什么；本层不判，判在 [TakeoverGate]）。 */
internal data class TrackedOrder(
    val orderId: String,
    val origin: String,
    val successor: String,
    val totalShards: Int,
    val stallThresholdMs: Long,
    val traces: List<ShardTrace>,
    val lastCommitAtMs: Long,
    val lastLogAtMs: Long,
    val suspensionsWithoutProgress: Int,
    val selfReportedExhausted: Boolean,
    /** ①冻结现场读的那枚头 sha（派单层报进度时顺手带；没带＝空串，句柄照读不误）。 */
    val headSha: String,
)

/** 一次消费的产出（判据②的断言面：拒因／信号／五步回执各看得见）。 */
internal data class TakeoverOutcome(
    val orderId: String,
    val rejection: String?,
    val verdict: SignalVerdict?,
    val record: TakeoverRecord?,
) {
    fun takenOver(): Boolean = record != null
}

/** 接管生产接线（366 留白的消费方）。 */
internal object TakeoverWiring {

    internal const val PROGRESS_FILE = "进度自报.jsonl"
    internal const val TAKEOVER_FILE = "接管账.jsonl"

    /** 每单最近一份读数（进程态；sweep 从账本重建）。 */
    private val lastNotes = LinkedHashMap<String, ProgressNote>()

    /** 本进程已接管的单（防同一单在一场内被接两次；跨进程口径见文件头）。 */
    private val takenOver = LinkedHashSet<String>()

    /** 已消费过的读数对（`单号@该行时刻`——同一对读数只消费一次，心跳不重复开账）。 */
    private val consumedPairs = LinkedHashSet<String>()

    /** 接管账回执行（读数口，测试与体检用）。 */
    private val ledgerLines = ArrayList<String>()

    // ── 生产入口一：报进度（承接方的读数从这里进） ────────────────────────────

    /**
     * 记一行进度并立刻消费：过 374 消费顺序 → classify → STALLED/EXHAUSTED → 五步。
     *
     * 首份读数（该单没有上一份）只入账不比对——374 的 [ProcessObjectRules.stallOf] 口径：
     * prev＝null 不判停滞。
     */
    fun reportProgress(order: TrackedOrder, note: ProgressNote, nowMs: Long): TakeoverOutcome {
        persistProgress(order, note, nowMs)
        val prev = lastNotes[order.orderId]
        lastNotes[order.orderId] = note
        if (prev != null) consumedPairs.add("${order.orderId}@$nowMs")
        return onProgressNote(order, prev, note, null, shaHandle(order.headSha), nowMs)
    }

    // ── 生产入口二：收工心跳扫（Settled 收尾家族一行调用） ────────────────────

    /**
     * 扫进度账：每单取最近两行（旧→新）重放消费顺序。
     *
     * 只在"这一对读数还没被消费过"时动手——同一对读数重复扫会重复开接管账；
     * 已接过的单（[takenOver]）跳过。
     */
    fun sweepStalled(nowMs: Long): List<TakeoverOutcome> {
        val rows = readProgressLedger()
        val byOrder = rows.groupBy { it.order.orderId }
        val out = ArrayList<TakeoverOutcome>()
        byOrder.forEach { (orderId, pairs) ->
            if (orderId in takenOver) return@forEach
            if (pairs.size < 2) return@forEach
            val newest = pairs.last()
            val prev = pairs[pairs.size - 2]
            val key = "$orderId@${newest.at}"
            if (key in consumedPairs) return@forEach
            consumedPairs.add(key)
            lastNotes[orderId] = newest.note
            out.add(onProgressNote(newest.order, prev.note, newest.note, null, shaHandle(newest.order.headSha), nowMs))
        }
        return out
    }

    // ── 直连缝（测试与未来派工单系统都走这里；branch 可注入假句柄） ────────────

    /**
     * 一份读数的完整消费（判据②的主链；顺序照 374 文件头，一步不乱）。
     *
     * 被任何一道门拒 ⇒ 落一行拒账并带原拒因返回（判据③：不许静默）；
     * 放行且判出 STALLED/EXHAUSTED ⇒ 五步接管＋回执落账；INCOMPLETE ⇒ 只落信号行（不接管，
     * 谎报处理线归后续批）；都不中 ⇒ 无动作（"看不出来"不是一种状态，374 口径）。
     */
    fun onProgressNote(
        order: TrackedOrder,
        previousNote: ProgressNote?,
        note: ProgressNote,
        recomputeReason: String?,
        branch: FrozenBranch,
        nowMs: Long,
    ): TakeoverOutcome {
        ProcessObjectRules.progressRejection(note)?.let { reason ->
            appendTakeoverLine("拒 单 ${order.orderId}：读数被进度闸拒——$reason")
            return TakeoverOutcome(order.orderId, reason, null, null)
        }
        if (previousNote != null) {
            ProcessObjectRules.progressRegressionRejection(previousNote, note, recomputeReason)?.let { reason ->
                appendTakeoverLine("拒 单 ${order.orderId}：倒退被拒——$reason")
                return TakeoverOutcome(order.orderId, reason, null, null)
            }
            ProcessObjectRules.regressionAuditLine(previousNote, note, recomputeReason)?.let { line ->
                appendTakeoverLine(line)
            }
        }
        val scene = SceneSnapshot(
            orderId = order.orderId,
            stallThresholdMs = order.stallThresholdMs,
            trail = ActivityTrail(
                lastCommitAtMs = order.lastCommitAtMs,
                lastLogAtMs = order.lastLogAtMs,
                suspensionsWithoutProgress = order.suspensionsWithoutProgress,
            ),
            selfReportedExhausted = order.selfReportedExhausted,
            previousNote = previousNote,
            note = note,
            traces = order.traces,
        )
        val verdict = TakeoverGate.classify(scene, nowMs) ?: return TakeoverOutcome(order.orderId, null, null, null)
        if (verdict.signal == TakeoverSignal.INCOMPLETE) {
            appendTakeoverLine("信号 单 ${order.orderId}：INCOMPLETE · ${verdict.trigger}（谎报处理线归后续批，本批不接管）")
            return TakeoverOutcome(order.orderId, null, verdict, null)
        }
        val takeoverScene = TakeoverScene(
            orderId = order.orderId,
            verdict = verdict,
            origin = order.origin,
            successor = order.successor,
            totalShards = order.totalShards,
            traces = order.traces,
        )
        TakeoverGate.takeoverRejection(takeoverScene)?.let { reason ->
            appendTakeoverLine("拒 单 ${order.orderId}：接管闸拒——$reason")
            return TakeoverOutcome(order.orderId, reason, verdict, null)
        }
        val record = TakeoverGate.run(takeoverScene, branch)
        takenOver.add(order.orderId)
        appendTakeoverLine(record.ledgerLine)
        record.tracelessLine?.let { appendTakeoverLine(it) }
        return TakeoverOutcome(order.orderId, null, verdict, record)
    }

    // ── 读数口 ──────────────────────────────────────────────────────────────

    /** 接管账行（按发生顺序）。 */
    fun ledger(): List<String> = ArrayList(ledgerLines)

    /** 测试摘态（进程态不清就是跨套件变红——GapFeedback.resetForTest 同款教训）。 */
    fun resetForTest() {
        lastNotes.clear()
        takenOver.clear()
        consumedPairs.clear()
        ledgerLines.clear()
    }

    // ── 内部：冻结句柄（只有读口——想 force-push，编译都过不去） ───────────────

    /** 把账上记着的 sha 做成只读句柄：两次读返回同一枚，前后相等＝冻结成立。 */
    private fun shaHandle(sha: String): FrozenBranch = FrozenBranch { sha }

    // ── 内部：两本账的读写 ──────────────────────────────────────────────────

    /** 进度账一行：现场＋读数＋时刻（sweep 靠它重建最近两份读数）。 */
    private data class ProgressRow(val order: TrackedOrder, val note: ProgressNote, val at: Long)

    private fun persistProgress(order: TrackedOrder, note: ProgressNote, nowMs: Long) {
        val traces = JSONArray()
        order.traces.forEach { t ->
            traces.put(
                JSONObject()
                    .put("i", t.shardIndex)
                    .put("c", t.hasCommit)
                    .put("r", t.hasReport)
                    .put("d", t.hasReadings),
            )
        }
        val json = JSONObject()
            .put("v", 1)
            .put("order", order.orderId)
            .put("origin", order.origin)
            .put("successor", order.successor)
            .put("totalShards", order.totalShards)
            .put("thresholdMs", order.stallThresholdMs)
            .put("lastCommitAt", order.lastCommitAtMs)
            .put("lastLogAt", order.lastLogAtMs)
            .put("suspensions", order.suspensionsWithoutProgress)
            .put("exhausted", order.selfReportedExhausted)
            .put("headSha", order.headSha)
            .put("traces", traces)
            .put("done", note.done)
            .put("total", note.total)
            .put("eta", note.etaMinutes ?: JSONObject.NULL)
            .put("blockers", JSONArray(note.blockers))
            .put("at", nowMs)
        appendToFile(PROGRESS_FILE, json.toString(), "进度账")
    }

    private fun readProgressLedger(): List<ProgressRow> {
        val root = DecisionLedger.installedRoot() ?: return emptyList()
        val f = File(root, PROGRESS_FILE)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8).mapNotNull { line -> parseProgress(line) }
        }.getOrDefault(emptyList())
    }

    private fun parseProgress(raw: String): ProgressRow? = runCatching {
        val o = JSONObject(raw)
        val traceArr = o.optJSONArray("traces") ?: JSONArray()
        val traces = (0 until traceArr.length()).map { i ->
            val t = traceArr.getJSONObject(i)
            ShardTrace(t.getInt("i"), t.getBoolean("c"), t.getBoolean("r"), t.getBoolean("d"))
        }
        val order = TrackedOrder(
            orderId = o.getString("order"),
            origin = o.optString("origin"),
            successor = o.optString("successor"),
            totalShards = o.getInt("totalShards"),
            stallThresholdMs = o.getLong("thresholdMs"),
            traces = traces,
            lastCommitAtMs = o.getLong("lastCommitAt"),
            lastLogAtMs = o.getLong("lastLogAt"),
            suspensionsWithoutProgress = o.getInt("suspensions"),
            selfReportedExhausted = o.getBoolean("exhausted"),
            headSha = o.optString("headSha"),
        )
        val blockerArr = o.optJSONArray("blockers") ?: JSONArray()
        val note = ProgressNote(
            orderId = order.orderId,
            done = o.getInt("done"),
            total = o.getInt("total"),
            etaMinutes = if (o.isNull("eta")) null else o.getInt("eta"),
            blockers = (0 until blockerArr.length()).map { blockerArr.getString(it) },
        )
        ProgressRow(order, note, o.optLong("at", 0L))
    }.getOrNull()

    private fun appendTakeoverLine(line: String) {
        ledgerLines.add(line)
        appendToFile(TAKEOVER_FILE, line, "接管账")
        RunLog.log("抱朴接管：$line")
    }

    private fun appendToFile(name: String, line: String, what: String) {
        val root = DecisionLedger.installedRoot() ?: return
        runCatching {
            val f = File(root, name)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴接管：$what 没落上——${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
