// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十一批 · 影子按需 ①的「执行后对表」＋②「复核排到下一轮」的调度逻辑。
//
// 分工（与影子链既有三段同构，一条界线都不糊）：
// · **采集与预测归 [ShadowBaseline]**（预演进门采基线、清副本前收预测后态）——本文件不碰盘
//   采指纹，只调它；
// · **比对归这里**（[diffOf]，纯函数）：真盘现状 × 副本重做的预测后态，逐对象比
//   存在性/字节/摘要三格（mtime 是取证格，**永远不进比对**——副本与真盘的落盘时刻天生不同，
//   拿它当判据则「零差异不报」做不到）；
// · **记账归影子自己的两本**：差异 ⇒ `影子按需.jsonl` 记 **一条** DISAGREEMENT（判据「有差异
//   记一条」——一个动作一行，差异逐格列在行内，不许一格格刷行）；零差异 ⇒ **什么都不落**
//   （判据「零差异不报」，连"一致"的行都不写——一致不是新闻）；验不了 ⇒ `影子复核.jsonl`
//   排队（[ShadowRecheck]），**只在开轮点**消化——判据②「复核排到下一轮（不在收尾重做）」。
//
// 四条界线：
// · **不阻断**：本文件的产出只有账行与日志，没有一颗按钮、没有一个闸——DISAGREEMENT 不喂
//   任何放行判据（放行归授权链自己的第⑤步，那是执行前的事，与本文件无关）；
// · **不写主账**：全程只写 `影子基线/影子按需/影子复核` 三本影子自有文件，run_log/决策记录/
//   文件操作账/偏差账/灾异事件一字不动；
// · **不重跑动作**：复核只重读真盘与已有账行，绝不在副本里把动作再跑一遍——不可逆动作的
//   重做只发生在执行前的预演里，事后重做没有意义（真盘已经不是那个现场）；
// · **不无限重试**：一次动作最多两试（执行后首验＋下一轮复核一次），仍验不了 ⇒ 记一条
//   unverifiable 收口（验不了不许冒充清白，也不许把队列欠成死账）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一格比对差（`grid` ∈ 三格常量；expected=预测后态，actual=真盘现状）。 */
internal data class ShadowDiff(
    val path: String,
    val grid: String,
    val expected: String,
    val actual: String,
)

/** `影子按需.jsonl` 的一行：DISAGREEMENT 或收口的 unverifiable（零差异**没有行**）。 */
internal data class ShadowOnDemandRow(
    val id: String,
    val atMs: Long,
    val opId: String,
    val sessionId: Long,
    val roundId: Int,
    val step: Int,
    val tool: String,
    val outcome: String,
    val baselineId: String,
    val diffs: List<ShadowDiff>,
    val reason: String,
    val attempts: Int,
)

/**
 * 影子按需（431）：执行后对表 ＋ 下一轮复核的调度。
 *
 * 本层零判定权：DISAGREEMENT 是**事实认定**（预测与实测对不上），不是闸——谁都不因它停下。
 */
internal object ShadowOnDemand {

    /** 比对三格（mtime 有意不在列——取证格，理由见文件头）。 */
    const val GRID_EXISTS = "exists"
    const val GRID_SIZE = "size"
    const val GRID_SHA = "sha"

    /** 影子按需账（DISAGREEMENT／收口 unverifiable）文件名。 */
    const val FILE_NAME = "影子按需.jsonl"
    const val ROW_ID_PREFIX = "od:"
    const val OUTCOME_DISAGREE = "disagree"
    const val OUTCOME_UNVERIFIABLE = "unverifiable"

    /** 一次动作一共两试：执行后首验 ＋ 下一轮复核一次（再多就是把队列欠成死账）。 */
    const val MAX_ATTEMPTS = 2

    /** 验不了的原因（闭集，进账与日志）。 */
    const val REASON_NO_PREDICTION = "NO_PREDICTION"
    const val REASON_READ_FAILED = "READ_FAILED"

    /**
     * 副本重做收手后读**预测后态**（清副本之前调——清了就永远读不到）。
     *
     * 副本里没落盘的对象是合法观察（exists=false 是读数）；存在但读不出字节 ⇒ 整份作废（null）：
     * 半份预测比出来的差异是假的，保守方向是"验不了"。
     */
    fun predictedOf(copyRoot: String, declaredPaths: List<String>): List<ShadowFingerprint>? {
        if (copyRoot.isBlank()) return null
        val root = File(copyRoot)
        val prints = declaredPaths.map { rel -> ShadowBaseline.readFingerprint(root, rel) }
        if (prints.any { it == null }) return null
        return prints.mapNotNull { it }
    }

    /**
     * 落基线行（执行前采集 ＋ 副本重做的预测，一次成行；capture=null ⇒ 不在范围，安静跳过）。
     *
     * 账落不落不影响判定（照 [ShadowLedger] 口径：账丢了是损失，不是事故——判定的真伪由
     * [diffOf] 对表时的读数负责）。
     */
    fun completeBaseline(
        capture: ShadowBaselineCapture?,
        input: ShadowInput,
        predicted: List<ShadowFingerprint>?,
    ): Boolean {
        if (capture == null) return false
        val row = ShadowBaselineRow(
            id = ShadowBaseline.rowIdOf(input.opId),
            atMs = capture.atMs,
            opId = input.opId,
            sessionId = input.sessionId,
            roundId = input.roundId,
            step = input.step,
            tool = input.tool,
            baseline = capture.fingerprints,
            predicted = predicted.orEmpty(),
        )
        return ShadowBaseline.append(File(input.ledgerRoot), row)
    }

    /**
     * ①执行后按需对表（真执行成功后由管线调；失败/被拒的动作**不调**——没发生的事没有后态）。
     *
     * 没采过基线 ⇒ 不在影子链范围，安静返回（一个字不落——判据「执行前必采到」的另一面：
     * 没采到就没有可比性，事后不补造基线）。
     */
    fun onExecuted(opId: String, tool: String, workspaceRoot: String, log: (String) -> Unit = {}) {
        if (opId.isBlank()) return
        val bookRoot = DecisionLedger.installedRoot() ?: return
        val row = ShadowBaseline.latestFor(bookRoot, opId) ?: return
        attempt(opId, tool, workspaceRoot, row, bookRoot, firstAttempt = true, log = log)
    }

    /**
     * ②下一轮复核（RunEngine 开轮点调；settle/收尾**不许**调）。
     *
     * 只消化 pending 行：concluded/gaveup 是终态；两试到顶的直接收口。
     */
    fun drainDue(log: (String) -> Unit = {}) {
        val bookRoot = DecisionLedger.installedRoot() ?: return
        ShadowRecheck.pendingRows(bookRoot).forEach { pending ->
            val row = ShadowBaseline.latestFor(bookRoot, pending.opId)
            if (row == null) {
                ShadowRecheck.conclude(bookRoot, pending, ShadowRecheck.STATE_GAVEUP, REASON_NO_PREDICTION, System.currentTimeMillis())
                log("抱朴影子按需：复核 op=${pending.opId.take(12)} ⇒ gaveup（基线行没了，无从可比）")
                return@forEach
            }
            if (pending.attempts >= MAX_ATTEMPTS) {
                ShadowRecheck.conclude(bookRoot, pending, ShadowRecheck.STATE_GAVEUP, "attempts=${pending.attempts}", System.currentTimeMillis())
                log("抱朴影子按需：复核 op=${pending.opId.take(12)} ⇒ gaveup（${pending.attempts} 试到顶）")
                return@forEach
            }
            attempt(pending.opId, pending.tool, pending.workspaceRoot, row, bookRoot, firstAttempt = false, log = log)
        }
    }

    /** 一次对表（首验与复核共用同一段；结论只有三向：一致不落／差异一条／验不了排队或收口）。 */
    private fun attempt(
        opId: String,
        tool: String,
        workspaceRoot: String,
        baseline: ShadowBaselineRow,
        bookRoot: File,
        firstAttempt: Boolean,
        log: (String) -> Unit,
    ) {
        val nowMs = System.currentTimeMillis()
        val pending = ShadowRecheck.rowOf(bookRoot, opId)
        val done = (pending?.attempts ?: 0) + 1
        if (!baseline.hasPrediction) {
            unverifiable(bookRoot, baseline, opId, tool, workspaceRoot, REASON_NO_PREDICTION, done, firstAttempt, nowMs, log)
            return
        }
        val real = realOf(workspaceRoot, baseline)
        if (real == null) {
            unverifiable(bookRoot, baseline, opId, tool, workspaceRoot, REASON_READ_FAILED, done, firstAttempt, nowMs, log)
            return
        }
        val diffs = diffOf(baseline.predicted, real)
        if (diffs.isEmpty()) {
            // 判据「零差异不报」：一致连按需账的行都不落；队列若在（复核路），落一行 concluded。
            pending?.let { ShadowRecheck.conclude(bookRoot, it, ShadowRecheck.STATE_CONCLUDED, "agree", nowMs) }
            log("抱朴影子按需：真盘与重做预测一致（op=${opId.take(12)} 零差异不报）")
            return
        }
        recordDisagreement(bookRoot, baseline, opId, tool, diffs, done, nowMs)
        pending?.let { ShadowRecheck.conclude(bookRoot, it, ShadowRecheck.STATE_CONCLUDED, OUTCOME_DISAGREE, nowMs) }
        log("抱朴影子按需：DISAGREEMENT（op=${opId.take(12)} ${diffs.size} 处差异）已记一条（不阻断）")
    }

    /** 真盘现状逐对象读（任何一路读不动 ⇒ null——整份作废，不许半份读数充数）。 */
    private fun realOf(workspaceRoot: String, baseline: ShadowBaselineRow): List<ShadowFingerprint>? {
        if (workspaceRoot.isBlank()) return null
        val root = File(workspaceRoot)
        val prints = baseline.baseline.map { it.path }.map { rel -> ShadowBaseline.readFingerprint(root, rel) }
        if (prints.any { it == null }) return null
        return prints.mapNotNull { it }
    }

    /** 纯比对：三格逐对象对，路径按影子链同一把尺归一（正斜杠＋小写）。 */
    internal fun diffOf(predicted: List<ShadowFingerprint>, real: List<ShadowFingerprint>): List<ShadowDiff> {
        val exp = predicted.associateBy { keyOf(it.path) }
        val act = real.associateBy { keyOf(it.path) }
        return (exp.keys + act.keys).flatMap { k ->
            val e = exp[k]
            val a = act[k]
            diffsOf(k, e, a)
        }.sortedBy { it.path }
    }

    private fun diffsOf(k: String, e: ShadowFingerprint?, a: ShadowFingerprint?): List<ShadowDiff> {
        if (e == null || a == null) {
            return listOf(ShadowDiff(k, GRID_EXISTS, e?.exists?.toString() ?: "absent", a?.exists?.toString() ?: "absent"))
        }
        val out = mutableListOf<ShadowDiff>()
        if (e.exists != a.exists) out += ShadowDiff(k, GRID_EXISTS, "${e.exists}", "${a.exists}")
        if (e.exists && a.exists) {
            if (e.sizeBytes != a.sizeBytes) out += ShadowDiff(k, GRID_SIZE, "${e.sizeBytes}", "${a.sizeBytes}")
            if (e.sha256.isNotBlank() && a.sha256.isNotBlank() && e.sha256 != a.sha256) {
                out += ShadowDiff(k, GRID_SHA, briefOf(e.sha256), briefOf(a.sha256))
            }
        }
        return out
    }

    /** DISAGREEMENT 落账：一个动作**一条**（id 按 opId 一把，追加前查重——第二遍执行不刷第二条）。 */
    private fun recordDisagreement(
        bookRoot: File,
        baseline: ShadowBaselineRow,
        opId: String,
        tool: String,
        diffs: List<ShadowDiff>,
        attempts: Int,
        nowMs: Long,
    ) {
        val row = ShadowOnDemandRow(
            id = rowIdOf(opId),
            atMs = nowMs,
            opId = opId,
            sessionId = baseline.sessionId,
            roundId = baseline.roundId,
            step = baseline.step,
            tool = tool,
            outcome = OUTCOME_DISAGREE,
            baselineId = baseline.id,
            diffs = diffs,
            reason = "",
            attempts = attempts,
        )
        if (!hasRow(bookRoot, row.id)) appendRow(bookRoot, row)
    }

    /** 验不了：首验 ⇒ 排到下一轮；复核（第二试）仍验不了 ⇒ 收口＋按需账记一条（不冒充清白）。 */
    private fun unverifiable(
        bookRoot: File,
        baseline: ShadowBaselineRow,
        opId: String,
        tool: String,
        workspaceRoot: String,
        reason: String,
        attempts: Int,
        firstAttempt: Boolean,
        nowMs: Long,
        log: (String) -> Unit,
    ) {
        if (firstAttempt) {
            ShadowRecheck.enqueue(bookRoot, opId, tool, workspaceRoot, attempts, reason, nowMs)
            log("抱朴影子按需：验不了（reason=$reason）⇒ 复核排到下一轮（不在收尾重做）")
            return
        }
        if (attempts >= MAX_ATTEMPTS) {
            val pending = ShadowRecheck.rowOf(bookRoot, opId)
            if (pending != null) ShadowRecheck.conclude(bookRoot, pending, ShadowRecheck.STATE_GAVEUP, reason, nowMs)
            val row = ShadowOnDemandRow(
                id = rowIdOf(opId),
                atMs = nowMs,
                opId = opId,
                sessionId = baseline.sessionId,
                roundId = baseline.roundId,
                step = baseline.step,
                tool = tool,
                outcome = OUTCOME_UNVERIFIABLE,
                baselineId = baseline.id,
                diffs = emptyList(),
                reason = reason,
                attempts = attempts,
            )
            if (!hasRow(bookRoot, row.id)) appendRow(bookRoot, row)
            log("抱朴影子按需：两试仍验不了（reason=$reason）⇒ 收口记一条（不冒充清白，不再重试）")
            return
        }
        // 理论不可达（复核路 attempts 至少 1，1 < 上限 ⇒ 上面已收口）：到这里说明计数口径漂了，
        // 保守按"再排一轮"处理，绝不静默吞掉。
        ShadowRecheck.enqueue(bookRoot, opId, tool, workspaceRoot, attempts, reason, nowMs)
        log("抱朴影子按需：验不了（reason=$reason attempts=$attempts）⇒ 仍排下一轮")
    }

    // ---- 影子按需账（JSONL；房型与影子基线同） ----

    private fun rowIdOf(opId: String): String = "$ROW_ID_PREFIX$opId"

    private fun appendRow(root: File, row: ShadowOnDemandRow): Boolean = runCatching {
        val f = fileIn(root)
        f.parentFile?.mkdirs()
        f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    private fun fileIn(root: File): File = File(root, FILE_NAME)

    private fun hasRow(root: File, id: String): Boolean = runCatching {
        fileIn(root).takeIf { it.isFile }?.readLines(Charsets.UTF_8)
            ?.any { line -> runCatching { JSONObject(line).optString("id") == id }.getOrDefault(false) }
    }.getOrDefault(false) ?: false

    private fun ShadowOnDemandRow.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("op", opId)
        .put("session", sessionId)
        .put("round", roundId)
        .put("step", step)
        .put("tool", tool)
        .put("outcome", outcome)
        .put("baseline", baselineId)
        .put("diffs", JSONArray().apply { diffs.forEach { put(diffToJson(it)) } })
        .put("reason", reason)
        .put("attempts", attempts)

    private fun diffToJson(d: ShadowDiff): JSONObject = JSONObject()
        .put("p", d.path)
        .put("g", d.grid)
        .put("exp", d.expected)
        .put("act", d.actual)

    // ---- 小尺子 ----

    /** 路径键（与影子链同一口径：正斜杠＋小写；比较与联键只用它）。 */
    private fun keyOf(p: String): String = p.replace('\\', '/').trim().trimEnd('/').lowercase()

    /** 摘要进账只留前 8 位（整串没意义还撑爆行——照 ShadowRehearsal.shaBrief 同一截法）。 */
    private fun briefOf(sha: String): String = if (sha.isBlank()) "—" else sha.take(8)
}
