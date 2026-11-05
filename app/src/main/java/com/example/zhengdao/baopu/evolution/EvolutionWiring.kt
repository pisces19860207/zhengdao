// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十八批 · 进化闭环的**生产接线**（判定本体见 [CriteriaEvolution] 310／
// [EvolutionLoop] 376／[EpochRegistry] 364；380 派工单原定接线，未合；428 按 418 缝复核接上）。
//
// 三件都是"判定件已建、生产 0 调用"（418 复核 §二 #3 点名），本文件接 ①②：
//   ① 缺口累积触发处：[GapFeedback.pending] 最大 attempts 到交人线 ⇒ 提进化（开提案）；
//   ② 进化提议之后：三触发校验 → 四条验证 → 失败回滚（回滚走 364 的 [EpochRegistry] 语义
//     ——失败的纪不进册；[EvolutionLoop.verify] 内部已接好，本文件只调不改）。
//   ③ 接管五步归 [com.example.zhengdao.baopu.agent.TakeoverWiring]（同批另一文件）。
//
// 分工照 376 报告 §六原话：376 管"能不能进化"的闸；本文件管"怎么接进生产"——
// 谁读偏差账换成 [MisjudgmentRow]（换算归本文件：kind 线值＝判据键、runId＝账行出处）、
// 谁在收工时跑四条验证（[runEvolutionChain] 的 proofRunner 入参）、尝试账落不落盘（落）。
//
// 三条纪律（派工单 §二 判据）：
//   · **不自动改判据**：两个 seam 入口只开提案，不调 [EvolutionLoop.apply]；
//     apply 只发生在链里，且必须带御批编号＋非空新表（源级门禁 EvolutionWiringGateTest 钉缝）；
//   · **门拒绝不许静默**：准入拒／换表拒／验证败都落一行尝试账（[EvolutionAttemptRow] 七列），
//     提案状态同步改（OPEN→RETURNED/REFUSED/ADMITTED→LANDED/ROLLED_BACK）；
//   · **不动 364 语义**：[EpochRegistry] 只被 [EvolutionLoop.verify] 调 append，本文件零改写。
//
// 包位理由：不在 `agent/**`／`MediatorWiring`／`MediatorJudge`——那三处是
// `tools/check-criteria-gates.py` 尺一的禁区（宰相在线路径只查表，不参与准入）；
// 也不在 `policy/`——PolicySourceGateTest 钉该包逐文件零 IO。`evolution/` 两头都干净。
//
// 落盘两本（与 274/277/298/278 同房型）：`<App私有>/抱朴记忆/进化提案.jsonl` 与
// `进化尝试.jsonl`，追加-only、写失败只 [RunLog] 不抛；账根没挂 ⇒ 只落内存，读数照给。
// 第三百八十批另加真验证臂：读数收件箱与新表走 [ProofReading]（`进化读数.jsonl` 只读、
// `进化读数-已用.refs` 是它的 ref 账）——四条验证读数不再恒"没跑"。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.RoyalDecisionLedger
import com.example.zhengdao.baopu.agent.RoyalVerdict
import com.example.zhengdao.baopu.inspect.DeviationEntry
import com.example.zhengdao.baopu.inspect.DeviationLedger
import com.example.zhengdao.baopu.policy.AdmissionState
import com.example.zhengdao.baopu.policy.AnchorCase
import com.example.zhengdao.baopu.policy.AnchorHold
import com.example.zhengdao.baopu.policy.CriteriaCandidate
import com.example.zhengdao.baopu.policy.CriteriaEvolution
import com.example.zhengdao.baopu.policy.CriteriaOrigin
import com.example.zhengdao.baopu.policy.CriteriaOutputForm
import com.example.zhengdao.baopu.policy.CriteriaSnapshot
import com.example.zhengdao.baopu.policy.DetektDebt
import com.example.zhengdao.baopu.policy.EpochRegistry
import com.example.zhengdao.baopu.policy.EpochRuntime
import com.example.zhengdao.baopu.policy.EvolutionAttemptRow
import com.example.zhengdao.baopu.policy.EvolutionLoop
import com.example.zhengdao.baopu.policy.EvolutionOutcome
import com.example.zhengdao.baopu.policy.EvolutionProof
import com.example.zhengdao.baopu.policy.EvolutionTrigger
import com.example.zhengdao.baopu.policy.GapTrigger
import com.example.zhengdao.baopu.policy.GateRun
import com.example.zhengdao.baopu.policy.HumanOrderTrigger
import com.example.zhengdao.baopu.policy.MisjudgmentRow
import com.example.zhengdao.baopu.policy.MisjudgmentSource
import com.example.zhengdao.baopu.policy.MisjudgmentTrigger
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

private const val DAY_MS = 24L * 3_600_000L

/** 提案状态（闭集六档；照 310 口径不许"再讨论一轮"这种第四态）。 */
internal enum class ProposalStatus(val wire: String, val cn: String) {
    OPEN("open", "待回放与御批"),
    ADMITTED("admitted", "准入已过，待新表与验证"),
    RETURNED("returned", "退回候选区"),
    REFUSED("refused", "准入硬拒"),
    LANDED("landed", "已落版"),
    ROLLED_BACK("rolled_back", "验证失败已回滚"),
    ;

    companion object {
        fun ofWire(wire: String): ProposalStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一条进化提案（`进化提案.jsonl` 一行一条，追加-only；状态变更＝追加重写，不涂历史行）。 */
internal data class EvolutionProposal(
    val id: String,
    val triggerKind: String,
    val basis: String,
    val status: ProposalStatus,
    val reason: String,
    val atMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", id)
        .put("trigger", triggerKind)
        .put("basis", basis)
        .put("status", status.wire)
        .put("reason", reason)
        .put("at", atMs)

    companion object {
        const val VERSION = 1

        fun parse(raw: String): EvolutionProposal? = runCatching {
            val o = JSONObject(raw)
            val status = ProposalStatus.ofWire(o.optString("status")) ?: return null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            EvolutionProposal(id, o.optString("trigger"), o.optString("basis"), status, o.optString("reason"), o.optLong("at", 0L))
        }.getOrNull()
    }
}

/** 一次链的收尾读数（判据①的断言面：每一步过没过都看得见）。 */
internal data class ChainOutcome(
    val admission: AdmissionState,
    val applied: Boolean,
    val verify: EvolutionOutcome?,
    val refusalReason: String?,
) {
    fun landed(): Boolean = verify?.passed == true
    fun rolledBack(): Boolean = verify?.rolledBack == true
}

/**
 * 进化闭环生产接线（380 原定、428 落地）。入口三个：
 *   · [onGapsRecorded]——缺口累积触发（金吾卫喂板之后；只读内存板，零 IO）；
 *   · [onRunSettled]——收工触发（读偏差账开误判提案＋扫御批跑链）；
 *   · [runEvolutionChain]——完整链（准入→纪元计数→换表→四条验证→落版/回滚）。
 */
internal object EvolutionWiring {

    internal const val PROPOSAL_FILE = "进化提案.jsonl"
    internal const val ATTEMPT_FILE = "进化尝试.jsonl"

    private val proposals = LinkedHashMap<String, EvolutionProposal>()
    private val attempts = ArrayList<EvolutionAttemptRow>()

    // ── ① 缺口累积触发处（JinwuObserve 喂板之后一行调用） ─────────────────────

    /**
     * 缺口到交人线 ⇒ 提进化＝开提案（**不自动改判据**：这里绝不碰 apply）。
     *
     * 与 [GapFeedback.round] 的交人线同源（N＝HAND_OVER_ATTEMPTS，不自定）。
     * 同一条差距只开一单（id 含 gapKey；重复喂板不刷屏——提案是状态，不是日志）。
     */
    fun onGapsRecorded(nowMs: Long): String? {
        val items = GapFeedback.pending()
        if (items.isEmpty()) return null
        val gate = EvolutionLoop.canPropose(GapTrigger(items), nowMs)
        if (!gate.allowed) return null
        val worst = items.maxOf { it.attempts }
        val hit = items.first { it.attempts == worst }
        return openProposalIfNew("gap:${hit.gapKey}", EvolutionAttemptRow.TRIGGER_GAP, gate.basis, nowMs)
    }

    // ── ② 收工触发（BaopuChatController 的 Settled 收尾家族一行调用） ─────────

    /**
     * 一轮收工：误判累积开提案（偏差账换算）＋扫御批（批了的 OPEN 提案跑链）。
     *
     * 账根没挂 ⇒ 误判半边跳过并明说（不静默）；御批没到 ⇒ 提案留在 OPEN（不硬跑）。
     * 第四百六十一批：收工先试一次读数送达（[ReadingDelivery.onRoundStart]）——后台线程、
     * 失败退避、绝不阻断本轮；本轮 sweep 赶不上就在途，下一轮收工用（诚实异步，不装同步）。
     */
    fun onRunSettled(nowMs: Long) {
        ReadingDelivery.onRoundStart(nowMs)
        openMisjudgmentProposal(nowMs)
        sweepApprovedProposals(nowMs)
    }

    /** 触发 a 的换算（376 留白"谁读偏差账换成 MisjudgmentRow"的答案）：kind 线值＝判据键。 */
    private fun openMisjudgmentProposal(nowMs: Long) {
        val root = DeviationLedger.installedRoot()
        if (root == null) {
            RunLog.log("抱朴进化接线：偏差账没挂，误判触发本轮跳过（不是没误判，是没账可读）")
            return
        }
        val rows = DeviationLedger.read(root).map { it.toMisjudgmentRow() }
        if (rows.isEmpty()) return
        val gate = EvolutionLoop.canPropose(MisjudgmentTrigger(rows), nowMs)
        if (!gate.allowed) return
        val hitKey = rows
            .groupBy { it.criterionKey }
            .maxByOrNull { (_, v) -> v.count { it.atMs >= nowMs - DAY_MS } }
            ?.key ?: return
        openProposalIfNew("misjudgment:$hitKey", EvolutionAttemptRow.TRIGGER_MISJUDGMENT, gate.basis, nowMs)
    }

    /** 扫御批：OPEN 提案且御批账有 APPROVE 行 ⇒ 跑链（提案 id 就是御批编号）。 */
    private fun sweepApprovedProposals(nowMs: Long) {
        val approved = RoyalDecisionLedger.replay()
            .filter { it.verdict == RoyalVerdict.APPROVE }
            .map { it.opinionId }
            .toSet()
        proposals.values
            .filter { it.status == ProposalStatus.OPEN && it.id in approved }
            .forEach { p ->
                // 第三百八十批：真验证臂——读数与新表从收件箱来（[ProofReading.pick]）；
                // 没有合格读数 ⇒ next=null，链停在准入后（428 的诚实停法，不自动改判据）。
                val reading = ProofReading.pick(p.id, p.atMs, nowMs)
                runEvolutionChain(
                    p.toTrigger(),
                    p.toCandidate(),
                    reading?.snapshot,
                    p.id,
                    anchorsForAdmission(),
                    reading?.runner ?: { defaultProof() },
                    nowMs,
                )
            }
    }

    // ── ③ 完整链（判据①：每步可断言被调） ───────────────────────────────────

    /**
     * 一条真实链：提议（准入四道闸）→ 纪元计数 → 换表（五道闸）→ 四条验证 → 落版/回滚。
     *
     * 步骤与闸全在判定件里（本文件不写第二个判）；这里只按序调用＋落账：
     *   1. [CriteriaEvolution.admit]——形态→清单→回放→锚定；RETURNED/REFUSED ⇒ 提案状态改、
     *      尝试账落一行拒因（判据②：门拒绝不许静默），**链停在这一步**；
     *   2. [EpochRuntime.observe]——准入读数进纪元账（候选数/通过数/否决数）；
     *   3. [EvolutionLoop.apply]——待验不叠加→御批必填→三触发→不可进化条→310 边界闸；
     *      `next` 为 null（生产侧还没人写新表）⇒ 不调 apply，提案停在 ADMITTED；
     *   4. [proofRunner]——四条验证的执行归接线层（376 口径）；
     *   5. [EvolutionLoop.verify]——四条全绿 ⇒ [EpochRegistry.append] 落版；缺一 ⇒ 自动回滚，
     *      失败的纪不进纪元册（不许半进化——这条是 376 的闸，本文件一字不动）。
     */
    fun runEvolutionChain(
        trigger: EvolutionTrigger,
        candidate: CriteriaCandidate,
        next: CriteriaSnapshot?,
        rulingRef: String,
        anchors: List<AnchorCase>,
        proofRunner: (CriteriaSnapshot) -> EvolutionProof,
        nowMs: Long,
    ): ChainOutcome {
        val admit = CriteriaEvolution.admit(candidate, anchors)
        if (admit.state != AdmissionState.ADMIT) {
            val status = if (admit.state == AdmissionState.RETURNED) ProposalStatus.RETURNED else ProposalStatus.REFUSED
            val reason = admit.checks.last().detail
            setProposalStatus(candidate.id, status, reason)
            recordRefused(trigger.kind, candidate.id, rulingRef, "准入${admit.state.name}：$reason", nowMs)
            return ChainOutcome(admit.state, false, null, reason)
        }
        EpochRuntime.observe(admit, nowMs)
        setProposalStatus(candidate.id, ProposalStatus.ADMITTED, "准入四道闸全过（${admit.checks.size} 条检查）")
        if (next == null) {
            return ChainOutcome(AdmissionState.ADMIT, false, null, "新表还没人写：停在准入之后（不自动改判据）")
        }
        val apply = EvolutionLoop.apply(trigger, next, rulingRef, nowMs)
        if (!apply.accepted) {
            setProposalStatus(candidate.id, ProposalStatus.REFUSED, "换表拒：${apply.reason}")
            recordRefused(trigger.kind, apply.reason, rulingRef, "换表拒：${apply.reason}", nowMs)
            return ChainOutcome(AdmissionState.ADMIT, false, null, apply.reason)
        }
        val verify = EvolutionLoop.verify(proofRunner(next), nowMs)
        syncAttemptLedger()
        setProposalStatus(
            candidate.id,
            if (verify.passed) ProposalStatus.LANDED else ProposalStatus.ROLLED_BACK,
            verify.reason,
        )
        return ChainOutcome(AdmissionState.ADMIT, true, verify, null)
    }

    // ── 读数口 ──────────────────────────────────────────────────────────────

    fun proposals(): List<EvolutionProposal> = proposals.values.toList()

    fun attempts(): List<EvolutionAttemptRow> = ArrayList(attempts)

    /** 锚定集读口（装配点唯一在 BaopuRuntime；拒收⇒恒空⇒准入一律 REFUSED，310 的 fail-closed）。 */
    fun anchorsForAdmission(): List<AnchorCase> = BaopuRuntime.anchorSnapshot.forAdmission()

    /** 测试摘账（进程态不清就是跨套件变红——GapFeedback.resetForTest 同款教训）。 */
    fun resetForTest() {
        proposals.clear()
        attempts.clear()
        EvolutionLoop.reset()
        EpochRuntime.reset()
        EpochRegistry.reset()
    }

    // ── 内部：提案账 ────────────────────────────────────────────────────────

    private fun openProposalIfNew(id: String, triggerKind: String, basis: String, nowMs: Long): String? {
        if (proposals.containsKey(id)) return null
        val row = EvolutionProposal(
            id = id,
            triggerKind = triggerKind,
            basis = basis,
            status = ProposalStatus.OPEN,
            reason = "等回放对账与御批编号（不自动改判据）",
            atMs = nowMs,
        )
        proposals[id] = row
        root()?.let { appendLine(File(it, PROPOSAL_FILE), row.toJson().toString(), "提案 ${row.id}") }
        RunLog.log("抱朴进化接线：提案已开 ${row.id}（$triggerKind）——$basis")
        return id
    }

    private fun setProposalStatus(id: String, status: ProposalStatus, reason: String) {
        val old = proposals[id] ?: return
        val row = old.copy(status = status, reason = reason)
        proposals[id] = row
        val root = root() ?: return
        appendLine(File(root, PROPOSAL_FILE), row.toJson().toString(), "提案状态 ${row.id}→${status.wire}")
    }

    // ── 内部：尝试账（七列闭集，EvolutionAttemptRow 自己的 wire()） ───────────

    /** 把 [EvolutionLoop.attemptLedger] 里没落过盘的行补齐（成/败都由 376 的闸生成，这里只转写）。 */
    private fun syncAttemptLedger() {
        val seen = attempts.map { it.wire() }.toSet()
        EvolutionLoop.attemptLedger().filterNot { it.wire() in seen }.forEach { row ->
            attempts.add(row)
            val root = root() ?: return
            appendLine(File(root, ATTEMPT_FILE), row.wire(), "尝试账")
        }
    }

    /** 接线层自己造的拒行（准入拒/换表拒——376 的账还没生成这一行，这里按同列补落，判据②）。 */
    private fun recordRefused(triggerKind: String, basis: String, rulingRef: String, reason: String, nowMs: Long) {
        val row = EvolutionAttemptRow(
            triggerKind = triggerKind,
            basis = basis.ifBlank { "无据" },
            rulingRef = rulingRef.ifBlank { "无编号" },
            verdict = EvolutionAttemptRow.VERDICT_FAIL,
            rolledBack = false,
            reason = reason,
            atMs = nowMs,
        )
        attempts.add(row)
        val root = root() ?: return
        appendLine(File(root, ATTEMPT_FILE), row.wire(), "尝试账（拒行）")
    }

    private fun appendLine(file: File, line: String, what: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }.onFailure {
            RunLog.log("抱朴进化接线：$what 没落上——${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun root(): File? = DecisionLedger.installedRoot()

    // ── 内部：提案 → 触发/候选 的还原（扫御批用；字段都来自提案行本身） ────────

    private fun EvolutionProposal.toTrigger(): EvolutionTrigger = when (triggerKind) {
        EvolutionAttemptRow.TRIGGER_GAP -> GapTrigger(GapFeedback.pending())
        EvolutionAttemptRow.TRIGGER_MISJUDGMENT -> MisjudgmentTrigger(
            DeviationLedger.installedRoot()?.let { DeviationLedger.read(it) }
                .orEmpty().map { it.toMisjudgmentRow() },
        )
        else -> HumanOrderTrigger(orderRef = id, instruction = basis)
    }

    private fun EvolutionProposal.toCandidate(): CriteriaCandidate = CriteriaCandidate(
        id = id,
        intent = basis.take(200),
        target = id.substringAfter(":"),
        outputForm = CriteriaOutputForm.CHECK_ITEMS,
        origin = CriteriaOrigin.CANDIDATE_POOL,
        replay = null,
        verdicts = emptyMap(),
    )

    private fun DeviationEntry.toMisjudgmentRow(): MisjudgmentRow = MisjudgmentRow(
        source = MisjudgmentSource.DEVIATION_LEDGER,
        criterionKey = kind.wire,
        atMs = atMs,
        ref = runId,
    )

    /**
     * 生产默认验证臂：设备跑不了全量单测与 detekt ⇒ 读数一律"没跑"（ran=false/0 条）。
     *
     * 376 的口径兜住这里：**没跑过不算绿**——0 例的全绿是假绿，GateRun 构造期就拦。
     * 所以生产扫到御批后的诚实收场是"验证失败 ⇒ 自动回滚＋尝试账写明哪条没跑"；
     * 真验证臂（CI 读数）归后续批接入，换的是 proofRunner 这一个参数，链本身不动。
     */
    /** 收工 sweep 没拿到读数时的兜底臂（380 起放宽为 internal：[ProofReading] 也引用它当诚实空读数，不复制第二份）。 */
    internal fun defaultProof(): EvolutionProof = EvolutionProof(
        unitTests = GateRun(ran = false, total = 0, failed = 0),
        regressionSet = GateRun(ran = false, total = 0, failed = 0),
        anchorSet = AnchorHold(total = 0, mismatched = 0),
        detekt = DetektDebt(before = 0, after = 0),
    )
}
