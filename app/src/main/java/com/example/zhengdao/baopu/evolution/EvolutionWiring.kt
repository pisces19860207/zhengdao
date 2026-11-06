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
//
// 第四百九十五批改的是**缺一句话的语义**：取不到读数时，链不许拿 [defaultProof] 当"验过了"。
// 分流在取数层（[ProofReading.gapFor]），结论落进提案账的 reason（判据⑤：376 的 verify 判定
// 与 310 的构造闸、提案状态闭集六档，495 一笔未动）：
//   · `reading_missing`（没送到）⇒ 结论**没法验**：不换表、不回滚，提案停在 ADMITTED 等下一轮读数；
//   · `reading_invalid`（送到了但格式/时间窗/ref 不合格）⇒ 结论**失败**：带得出新表就照 376
//     走换表→验证红→自动回滚；带不出新表就没换表（账上写明白"没换表故无纪可退"，不假称回滚）；
//   · 读数合格 ⇒ 照旧，四条全绿才算 LANDED。
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

/**
 * 一场进化的结论三态（第四百九十五批）。
 *
 * 分家的理由：**没数可验**不等于**验不过**。把"CI 还没跑"记成"它做错了"，账上的进化
 * 就永远是红的，人也就再不会去看那本账。三态仍是一句话能说清的结论——不许开
 * "再讨论一轮"那种第四态（310 口径：结论闭集）。
 */
internal enum class EvolutionConclusion(val cn: String) {
    LANDED("通过"),
    FAILED("失败"),
    UNVERIFIABLE("没法验"),
}

/** 一次链的收尾读数（判据①的断言面：每一步过没过、结论是哪一态，都看得见）。 */
internal data class ChainOutcome(
    val admission: AdmissionState,
    val applied: Boolean,
    val verify: EvolutionOutcome?,
    val refusalReason: String?,
    val conclusion: EvolutionConclusion,
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

    /**
     * 两本账里"为什么没成"的口径（495 判据①②）。
     *
     * 走提案账的 reason 而不是尝试账的 verdict：后者的闭集是 376 定的
     * {通过, 失败}，构造期 `require` 挡着——"没法验"不是第三种判决，是**没资格被判决**，
     * 硬塞进去就是把 376 的判定改了（判据⑤不许）。
     */
    internal const val REASON_MISSING = "reason=reading_missing"
    internal const val REASON_INVALID = "reason=reading_invalid"

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

    /**
     * 扫御批：已批的提案跑链。状态面两档（第四百九十五批）——
     *   · OPEN：第一次过链；
     *   · ADMITTED：**待验队列**（上一轮因缺读数停住的那些），下一轮继续判。
     * 御批没到 ⇒ 提案留在 OPEN（不硬跑）。
     */
    private fun sweepApprovedProposals(nowMs: Long) {
        val approved = RoyalDecisionLedger.replay()
            .filter { it.verdict == RoyalVerdict.APPROVE }
            .map { it.opinionId }
            .toSet()
        proposals.values
            .filter { it.id in approved && (it.status == ProposalStatus.OPEN || it.status == ProposalStatus.ADMITTED) }
            .forEach { p -> sweepOne(p, nowMs) }
    }

    /** 一场提案的一轮扫——第四百九十五批的三态分流就发生在这几行，判定本身仍全在 376/310 的闸里。 */
    private fun sweepOne(p: EvolutionProposal, nowMs: Long) {
        // 第三百八十批：真验证臂——读数与新表从收件箱来（[ProofReading.pick]）。
        val reading = ProofReading.pick(p.id, p.atMs, nowMs)
        // pick 只答"有没有"，gapFor 才答"没有的话是哪种没有"（没送到 ≠ 不合格）。
        val gap = if (reading == null) ProofReading.gapFor(p.id, p.atMs, nowMs) else ProofGap.Available
        // 待验队列（上一轮已判过"没法验"的那些）：这轮仍拿不出能换的表就一步都不走——
        // 准入计数与拒行不许按轮涨，结论上一轮已写明，等的只是读数。
        if (p.status == ProposalStatus.ADMITTED && !gap.actionable()) return
        runEvolutionChain(
            p.toTrigger(),
            p.toCandidate(),
            reading?.snapshot ?: (gap as? ProofGap.Invalid)?.snapshot,
            p.id,
            anchorsForAdmission(),
            // 兜底臂现在只剩一支用途：读数不合格（495②）——"四条没一条能作证"按 376 就是红。
            // 缺读数那一支 next 必为 null，链在 verify 之前就返回，这个臂永不被调。
            reading?.runner ?: { defaultProof() },
            nowMs,
            gap,
        )
    }

    // ── ③ 完整链（判据①：每步可断言被调） ───────────────────────────────────

    /**
     * 一条真实链：提议（准入四道闸）→ 纪元计数 → 换表（五道闸）→ 四条验证 → 落版/回滚。
     *
     * 步骤与闸全在判定件里（本文件不写第二个判）；这里只按序调用＋落账：
     *   1. [CriteriaEvolution.admit]——形态→清单→回放→锚定；RETURNED/REFUSED ⇒ 提案状态改、
     *      尝试账落一行拒因（判据②：门拒绝不许静默），**链停在这一步**；
     *   2. [EpochRuntime.observe]——准入读数进纪元账（候选数/通过数/否决数）；
     *   3. 没有合格读数（[gap] 非 [ProofGap.Available] 且换不出表）⇒ [stopWithoutReading]：
     *      **不换表、不 verify、不回滚**——这一步是 495 的全部；
     *   4. [EvolutionLoop.apply]——待验不叠加→御批必填→三触发→不可进化条→310 边界闸；
     *   5. [proofRunner]——四条验证的执行归接线层（376 口径）；
     *   6. [EvolutionLoop.verify]——四条全绿 ⇒ [EpochRegistry.append] 落版；缺一 ⇒ 自动回滚，
     *      失败的纪不进纪元册（不许半进化——这条是 376 的闸，本文件一字不动）。
     *
     * @param gap 取数层的分流结果（[ProofReading.gapFor]）；默认 [ProofGap.Available]＝
     *   调用方自己带表进来（380 的原路），495 之前这个入参根本不存在。
     */
    fun runEvolutionChain(
        trigger: EvolutionTrigger,
        candidate: CriteriaCandidate,
        next: CriteriaSnapshot?,
        rulingRef: String,
        anchors: List<AnchorCase>,
        proofRunner: (CriteriaSnapshot) -> EvolutionProof,
        nowMs: Long,
        gap: ProofGap = ProofGap.Available,
    ): ChainOutcome {
        val admit = CriteriaEvolution.admit(candidate, anchors)
        if (admit.state != AdmissionState.ADMIT) {
            val status = if (admit.state == AdmissionState.RETURNED) ProposalStatus.RETURNED else ProposalStatus.REFUSED
            val reason = admit.checks.last().detail
            setProposalStatus(candidate.id, status, reason)
            recordRefused(trigger.kind, candidate.id, rulingRef, "准入${admit.state.name}：$reason", nowMs)
            return ChainOutcome(admit.state, false, null, reason, EvolutionConclusion.FAILED)
        }
        EpochRuntime.observe(admit, nowMs)
        setProposalStatus(candidate.id, ProposalStatus.ADMITTED, "准入四道闸全过（${admit.checks.size} 条检查）")
        if (next == null) return stopWithoutReading(trigger, candidate.id, rulingRef, gap, nowMs)
        val apply = EvolutionLoop.apply(trigger, next, rulingRef, nowMs)
        if (!apply.accepted) {
            setProposalStatus(candidate.id, ProposalStatus.REFUSED, "换表拒：${apply.reason}")
            recordRefused(trigger.kind, apply.reason, rulingRef, "换表拒：${apply.reason}", nowMs)
            return ChainOutcome(AdmissionState.ADMIT, false, null, apply.reason, EvolutionConclusion.FAILED)
        }
        val verify = EvolutionLoop.verify(proofRunner(next), nowMs)
        syncAttemptLedger()
        setProposalStatus(
            candidate.id,
            if (verify.passed) ProposalStatus.LANDED else ProposalStatus.ROLLED_BACK,
            verify.reason + if (gap is ProofGap.Invalid) "（$REASON_INVALID）" else "",
        )
        val conclusion = if (verify.passed) EvolutionConclusion.LANDED else EvolutionConclusion.FAILED
        return ChainOutcome(AdmissionState.ADMIT, true, verify, null, conclusion)
    }

    /**
     * 没有合格读数时的收尾（495①②）——这一步**不碰 376**：没 apply 就没 pending，
     * 没 verify 就没回滚，当前表一行没动（判据⑤：分流只在取数层，判定本体一字不动）。
     *
     * 两态分别是这个函数的全部用途：
     *   · 没送到 ⇒ [EvolutionConclusion.UNVERIFIABLE]：提案留在 ADMITTED（待验队列），
     *     下一轮有读数再判；尝试账**一行不加**——"CI 还没跑"不是"它做错了"；
     *   · 送到了却坏到连新表都建不出来 ⇒ [EvolutionConclusion.FAILED]：这里没换过表，
     *     故没有纪可退——尝试账落一行"失败·未回滚"（判据②：门拒绝不许静默），
     *     两本账都写明 `reading_invalid`，好跟"没送到"分开对账。
     */
    private fun stopWithoutReading(
        trigger: EvolutionTrigger,
        candidateId: String,
        rulingRef: String,
        gap: ProofGap,
        nowMs: Long,
    ): ChainOutcome = when (gap) {
        is ProofGap.Invalid -> {
            setProposalStatus(candidateId, ProposalStatus.ADMITTED, "${gap.why}（$REASON_INVALID）")
            recordRefused(
                trigger.kind,
                candidateId,
                rulingRef,
                "${gap.why}（$REASON_INVALID·没换表故无纪可退）",
                nowMs,
            )
            ChainOutcome(AdmissionState.ADMIT, false, null, gap.why, EvolutionConclusion.FAILED)
        }
        is ProofGap.Missing -> {
            setProposalStatus(candidateId, ProposalStatus.ADMITTED, "${gap.why}（$REASON_MISSING）")
            ChainOutcome(AdmissionState.ADMIT, false, null, gap.why, EvolutionConclusion.UNVERIFIABLE)
        }
        ProofGap.Available -> ChainOutcome(
            AdmissionState.ADMIT,
            false,
            null,
            "新表还没人写：停在准入之后（不自动改判据）",
            EvolutionConclusion.UNVERIFIABLE,
        )
    }

    // ── 读数口 ──────────────────────────────────────────────────────────────

    fun proposals(): List<EvolutionProposal> = proposals.values.toList()

    fun attempts(): List<EvolutionAttemptRow> = ArrayList(attempts)

    /** 锚定集读口（装配点唯一在 BaopuRuntime；拒收⇒恒空⇒准入一律 REFUSED，310 的 fail-closed）。 */
    fun anchorsForAdmission(): List<AnchorCase> = BaopuRuntime.anchorSnapshot.forAdmission()

    // ── 体检单那一格（495 判据④：三态要区分得开） ────────────────────────────

    /**
     * 提案账每 id 的最新一版（追加-only ⇒ 后写的行就是当前状态）。
     *
     * 盘上没文件（没挂账根／一场没开过）时退回内存态：体检单不许把"还没落账"读成"没进化"。
     */
    internal fun latestProposals(): List<EvolutionProposal> {
        val root = root() ?: return proposals.values.toList()
        val f = File(root, PROPOSAL_FILE)
        if (!f.isFile) return proposals.values.toList()
        val byId = LinkedHashMap<String, EvolutionProposal>()
        runCatching { f.readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
            .forEach { raw -> EvolutionProposal.parse(raw)?.let { byId[it.id] = it } }
        return byId.values.toList()
    }

    /**
     * 一行提案 ⇒ 一个结论（判据④的地基；只**转写**已落账的状态与 reason，不重判一条）。
     *
     * OPEN/ADMITTED 都没走到 verify ⇒ 没资格算失败；LANDED 只可能是 verify 绿的产物。
     */
    internal fun conclusionOf(status: ProposalStatus, reason: String): EvolutionConclusion = when {
        status == ProposalStatus.LANDED -> EvolutionConclusion.LANDED
        status == ProposalStatus.OPEN || status == ProposalStatus.ADMITTED ->
            if (reason.contains("reading_invalid")) EvolutionConclusion.FAILED else EvolutionConclusion.UNVERIFIABLE
        else -> EvolutionConclusion.FAILED
    }

    /** 结论在单子上的原话：三态加"不合格"这一支各有互不相同的说法（判据④要的"区分显示"）。 */
    internal fun conclusionText(status: ProposalStatus, reason: String): String = when {
        status == ProposalStatus.LANDED -> "通过"
        reason.contains("reading_missing") -> "没法验（缺读数）"
        reason.contains("reading_invalid") -> "失败（读数不合格）"
        conclusionOf(status, reason) == EvolutionConclusion.UNVERIFIABLE -> "没法验（还没验到）"
        else -> "失败"
    }

    /** 体检单的行（判据④）：三态各一格计数＋最新一场的原话；一行不进时不许被读成"进化总在失败"。 */
    internal fun sheetLines(): List<String> {
        val rows = latestProposals().filter { it.status != ProposalStatus.OPEN }
        if (rows.isEmpty()) return listOf("进化结论：没有一场走到验证（没提案或御批没到）——不是进化在失败")
        val counts = rows.groupingBy { conclusionText(it.status, it.reason) }.eachCount()
        val cells = listOf(
            "通过", "失败（读数不合格）", "失败", "没法验（缺读数）", "没法验（还没验到）",
        ).filter { counts.containsKey(it) }.map { "$it ${counts.getValue(it)}" }
        val last = rows.last()
        return listOf(
            "进化结论：${cells.joinToString("｜")}",
            "进化结论·最新一场：${last.id}→${conclusionText(last.status, last.reason)}",
        )
    }

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
     * 兜底验证臂：读数不合格（495②）时链上"四条没一条能作证"的那份诚实空读数。
     *
     * 376 的口径兜住这里：**没跑过不算绿**——0 例的全绿是假绿，GateRun 构造期就拦。
     * 第四百九十五批之后它只剩这一支用途：**缺读数（495①）根本走不到 verify**——
     * 没有新表就不换表，链在 [stopWithoutReading] 里判"没法验"，这个臂一次也不会被调。
     * 380 起放宽为 internal：[ProofReading] 也引用它，不复制第二份。
     */
    internal fun defaultProof(): EvolutionProof = EvolutionProof(
        unitTests = GateRun(ran = false, total = 0, failed = 0),
        regressionSet = GateRun(ran = false, total = 0, failed = 0),
        anchorSet = AnchorHold(total = 0, mismatched = 0),
        detekt = DetektDebt(before = 0, after = 0),
    )
}
