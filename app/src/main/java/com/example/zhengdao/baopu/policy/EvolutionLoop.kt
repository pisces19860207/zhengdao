// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十六批 · 进化闭环闸：**什么时候许进化、进化完怎么验、验不过怎么退**。
//
// 364 把纪元实体立起来了（身份证、账、快照），310 把换表与回滚的写口钉死了；但四时闭环里
// "进化时"还缺那一整圈。本文件就是那一圈——**只做闸**：接线（谁在生产上调用它）归第三百八十批，
// 不做 UI，不碰 RunEngine 主循环，不改锚定集内容。
//
// 三触发（任一满足才许提进化；**没有账行依据不许提**）：
//   a. **误判**：同一判据 24h 内 ≥3 次（[MisjudgmentRow]，从偏差账／退回记录读——偏差账一行的
//      kind 线值就是判据键，退回记录一行的候选 id 就是判据键；账本读出来换成行的换算归 380）；
//   b. **人令**：用户明确要求改（[HumanOrderTrigger]，编号与原话都必填——没编号＝没令）；
//   c. **缺口**：缺口累积 ≥N（[GapTrigger]，从 GapItem 读；N 用现值
//      [GapFeedback.HAND_OVER_ATTEMPTS]＝交人线，**不自定**——这就是为什么本文件要引 verify
//      包的两个纯符号：抄一个 3 过来，那天交人线改了进化线不跟着走）。
//
// 进化后验证（四条全绿才算成，**缺一即失败**）：全量单测绿 · 问题回归集全绿 · 锚定集不掉 ·
// 无新增 detekt 债。四条的**执行**不归本文件（零 IO 口径）：接线层跑完把读数交给 [EvolutionProof]，
// 这里只判"数对不对"——没跑过不算绿，0 例的全绿是假绿（[GateRun] 构造期就建不出来）。
//
// 失败自动回滚：验证失败 ⇒ 自动回到上一快照——有上一张表走 310 的 [EpochRuntime.rollbackToPrevious]；
// 首张表失败没有"上一张"，走 [EpochRuntime.reset] 回到"从没发生过"（398 口径；其纪元计数本来就还
// 是空的，清的是失败那一纪自己的读数，失败经过本身由 [EvolutionAttemptRow] 留着）。
// **不许半进化**：失败的表在 310 那里退干净，也**不进纪元册**（[EpochRegistry] 只收验证通过的纪
// ——没验过就落账，等于账上先答应了）；每一场进化（成与败）另落一行 [EvolutionAttemptRow]：
// 谁触发、哪条依据、通过与否、回滚没——派工单说的"纪元账一行"就是它。
//
// 硬规矩：**不可进化清单永不动**。想借进化通道改那三条，[apply] 这一步就明拒（310 的快照构造期
// 是第二道结构闸，两条各有各的测试——闸面拒得明白，结构拦得无声，缺一不可）。
//
// 零 Android／零 IO／零时钟：误判行、缺口板、验证读数、`nowMs` 全由调用方给。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.baopu.verify.GapItem

/** 误判证据出自哪本账（闭集两本；第三本要先立单）。 */
internal enum class MisjudgmentSource(val wire: String, val cn: String) {
    DEVIATION_LEDGER("deviation_ledger", "偏差账"),
    RETURN_RECORD("return_record", "退回记录"),
}

/**
 * 触发 a 的一行证据：一次误判。
 *
 * @param criterionKey 同一判据的键（偏差账＝kind 线值；退回记录＝候选/判据 id——两本账各按各的键数）。
 * @param ref 账行出处（偏差账的 runId／退回记录的候选号）：**没有出处＝没有据**，构造期拦空。
 */
internal data class MisjudgmentRow(
    val source: MisjudgmentSource,
    val criterionKey: String,
    val atMs: Long,
    val ref: String,
) {
    init {
        require(criterionKey.isNotBlank()) { "误判行缺判据键：不知道哪条判据被误判，就没法数「同一判据」" }
        require(ref.isNotBlank()) { "误判行缺账行出处：没有出处＝没有据（没有账行依据不许提进化）" }
        require(atMs >= 0L) { "误判时刻由调用方给（本包零时钟），且不许为负" }
    }
}

/** 三触发（闭集；机读名落账用，见 [EvolutionAttemptRow] 的触发名列）。 */
internal sealed interface EvolutionTrigger {
    /** 机读触发名（闭集常量，不许自造第四种）。 */
    val kind: String
}

/** 触发 a：同一判据 24h 内 ≥3 次误判。rows 是账上读来的行，本闸只数数，不替账本编行。 */
internal data class MisjudgmentTrigger(val rows: List<MisjudgmentRow>) : EvolutionTrigger {
    override val kind: String = EvolutionAttemptRow.TRIGGER_MISJUDGMENT
}

/** 触发 b：用户明确要求改（人令）。编号与原话都必填——没编号＝没令，没原话＝没法对账。 */
internal data class HumanOrderTrigger(val orderRef: String, val instruction: String) : EvolutionTrigger {
    override val kind: String = EvolutionAttemptRow.TRIGGER_HUMAN_ORDER

    init {
        require(orderRef.isNotBlank()) { "人令缺编号：没编号＝没令（谁下的、哪一句，事后要查得到）" }
        require(instruction.isNotBlank()) { "人令缺原话：改什么、为什么改，只有原话能对账" }
    }
}

/** 触发 c：缺口累积 ≥N。items 从缺口板读来（GapFeedback.pending() 同源），本闸取最大 attempts 对现值。 */
internal data class GapTrigger(val items: List<GapItem>) : EvolutionTrigger {
    override val kind: String = EvolutionAttemptRow.TRIGGER_GAP
}

/** 三触发的裁决：许不许提 ＋ 一句人读原因 ＋ 依据句（落账"哪条依据"那一列就抄它）。 */
internal data class TriggerResult(val allowed: Boolean, val reason: String, val basis: String)

/** 一条验证读数：跑没跑、几例、几红。ran=true 却 0 例根本建不出来——空集的全绿是假绿。 */
internal data class GateRun(val ran: Boolean, val total: Int, val failed: Int) {
    init {
        require(total >= 0) { "用例数不许为负" }
        require(failed >= 0) { "失败数不许为负" }
        require(failed <= total) { "失败数超过用例数（$failed > $total）：读数本身坏了" }
        require(!ran || total > 0) { "ran=true 却 0 例：空集的全绿是假绿（BUILD SUCCESSFUL 不等于真跑），没跑就写 ran=false" }
    }

    fun green(): Boolean = ran && failed == 0

    fun describe(): String = if (!ran) "没跑过" else "共 $total 例、红 $failed 例"
}

/** 锚定集读数："不掉"＝条条同侧且真的跑了（0 条＝没跑，不算不掉）。 */
internal data class AnchorHold(val total: Int, val mismatched: Int) {
    init {
        require(total >= 0) { "锚定条数不许为负" }
        require(mismatched >= 0) { "不一致条数不许为负" }
        require(mismatched <= total) { "不一致条数超过总数（$mismatched > $total）：读数本身坏了" }
    }

    fun steady(): Boolean = total > 0 && mismatched == 0

    fun describe(): String = if (total == 0) "锚定集没跑（0 条）" else "共 $total 条、不一致 $mismatched 条"
}

/** detekt 债读数："无新增"＝收工不高于开工（只许降不许涨）。 */
internal data class DetektDebt(val before: Int, val after: Int) {
    init {
        require(before >= 0 && after >= 0) { "detekt 债读数不许为负" }
    }

    fun noNewDebt(): Boolean = after <= before

    fun describe(): String = "债 $before → $after"
}

/** 进化后验证的四条读数（执行归接线层，本文件只判数）。 */
internal data class EvolutionProof(
    val unitTests: GateRun,
    val regressionSet: GateRun,
    val anchorSet: AnchorHold,
    val detekt: DetektDebt,
) {
    /** 四条逐条过：全绿返回空表；红几条返几条（缺一即失败，且失败原因要能逐条指认）。 */
    fun failures(): List<String> {
        val bad = ArrayList<String>()
        if (!unitTests.green()) bad += "全量单测未绿：${unitTests.describe()}"
        if (!regressionSet.green()) bad += "问题回归集未绿：${regressionSet.describe()}"
        if (!anchorSet.steady()) bad += "锚定集掉了：${anchorSet.describe()}"
        if (!detekt.noNewDebt()) bad += "detekt 新增债：${detekt.describe()}"
        return bad
    }
}

/**
 * 进化的一行账（派工单硬规矩：每次进化落纪元账一行）。
 *
 * 七列：`触发名|依据|御批编号|通过与否|回滚没|原因|时刻`。触发名与"通过与否"都是**闭集**
 * （不许第四态——"再讨论一轮"不是结论）；`basis` 与 `reason` 由本文件自己产出，不含竖线，
 * 调用方若塞了竖线，落盘时换成全角斜线（列分隔符不许被内容撑破）。
 */
internal data class EvolutionAttemptRow(
    val triggerKind: String,
    val basis: String,
    val rulingRef: String,
    val verdict: String,
    val rolledBack: Boolean,
    val reason: String,
    val atMs: Long,
) {
    init {
        require(triggerKind in TRIGGER_KINDS) { "触发名必须是闭集 $TRIGGER_KINDS，实测「$triggerKind」" }
        require(verdict in VERDICTS) { "通过与否必须是闭集 $VERDICTS（不许第四态），实测「$verdict」" }
        require(basis.isNotBlank() && rulingRef.isNotBlank() && reason.isNotBlank()) {
            "依据、御批编号、原因三句都不许空：账是给人看的"
        }
        require(atMs >= 0L) { "时刻由调用方给（本包零时钟），且不许为负" }
    }

    /** 落盘形态：`触发名|依据|御批编号|通过与否|回滚没|原因|时刻`。 */
    fun wire(): String = listOf(
        triggerKind, basis, rulingRef, verdict,
        if (rolledBack) ROLLED_YES else ROLLED_NO,
        reason, "$atMs",
    ).joinToString("|") { it.replace("|", "／") }

    companion object {
        const val TRIGGER_MISJUDGMENT = "MISJUDGMENT"
        const val TRIGGER_HUMAN_ORDER = "HUMAN_ORDER"
        const val TRIGGER_GAP = "GAP"
        val TRIGGER_KINDS = setOf(TRIGGER_MISJUDGMENT, TRIGGER_HUMAN_ORDER, TRIGGER_GAP)
        const val VERDICT_PASS = "通过"
        const val VERDICT_FAIL = "失败"
        val VERDICTS = setOf(VERDICT_PASS, VERDICT_FAIL)
        const val ROLLED_YES = "已回滚"
        const val ROLLED_NO = "未回滚"
    }
}

/** 一次 verify 的收尾读数。 */
internal data class EvolutionOutcome(val passed: Boolean, val reason: String, val rolledBack: Boolean)

/**
 * 进化闭环闸本体。
 *
 * 一场进化只有两种收场：**验证通过**（新表生效、纪元册落账、账行记"通过·未回滚"）或
 * **验证失败**（自动回滚、不落纪元册、账行记"失败·已回滚＋原因"）。中间态（[pending]）
 * 只在 apply 与 verify 之间短暂存在，且此时不许提下一场（叠加就是这么叠出半进化的）。
 */
internal object EvolutionLoop {

    /** 触发 a 的线（派工单现值：同一判据 24h 内 ≥3 次）。 */
    private const val MISJUDGMENT_MIN = 3

    private const val DAY_MS = 24L * 3_600_000L

    /** 待验的进化：换表已生效、验证未回。进程态（落盘归 380，与缺口板同口径）。 */
    private class PendingEvolution(
        val triggerKind: String,
        val basis: String,
        val rulingRef: String,
        val snapshot: CriteriaSnapshot,
        val epoch: Epoch,
    )

    @Volatile
    private var pending: PendingEvolution? = null

    private val attemptLedger = ArrayList<EvolutionAttemptRow>()

    /** 进化账（按发生顺序，只增不改；成与败都落）。 */
    fun attemptLedger(): List<EvolutionAttemptRow> = ArrayList(attemptLedger)

    /** 现在有没有挂着待验的进化（接线层与测试的读数口；null＝没有）。 */
    fun pendingReadout(): String? = pending?.let { "${it.triggerKind}|${it.basis}|${it.rulingRef}" }

    // ── 三触发的闸（判据①：各有正例/负例，没据不许提） ────────────────────

    /** 三触发逐条裁：任一成立即许提。纯函数，不动任何状态。 */
    fun canPropose(trigger: EvolutionTrigger, nowMs: Long): TriggerResult = when (trigger) {
        is MisjudgmentTrigger -> judgeMisjudgment(trigger, nowMs)
        is HumanOrderTrigger -> judgeHumanOrder(trigger)
        is GapTrigger -> judgeGap(trigger)
    }

    /** 触发 a：同一判据 24h 内 ≥3 次。两本账**按判据键合数**（来源只是出处，不拆组）；
     *  同一条账行读两遍只数一次（防刷）；有行落在时刻之后＝账钟不对。 */
    private fun judgeMisjudgment(trigger: MisjudgmentTrigger, nowMs: Long): TriggerResult {
        val deduped = trigger.rows.distinctBy { Triple(it.source, it.criterionKey, it.ref) }
        val future = deduped.filter { it.atMs > nowMs }
        if (future.isNotEmpty()) {
            return TriggerResult(
                false,
                "误判账里有 ${future.size} 行落在时刻之后（${future.minOf { it.atMs }} > $nowMs）：" +
                    "账钟不对，先对账再提进化",
                "",
            )
        }
        val windowStart = nowMs - DAY_MS
        val hit = deduped
            .groupBy { it.criterionKey }
            .entries
            .map { (key, rows) -> key to rows.filter { it.atMs >= windowStart } }
            .firstOrNull { (_, inWindow) -> inWindow.size >= MISJUDGMENT_MIN }
        if (hit == null) {
            return TriggerResult(
                false,
                "同一判据 24h 内不足 $MISJUDGMENT_MIN 次误判（去重后 ${deduped.size} 行、" +
                    "跨 ${deduped.map { it.criterionKey }.distinct().size} 个判据键）：触发 a 不成立",
                "",
            )
        }
        val (key, inWindow) = hit
        val bySource = inWindow.groupBy { it.source.cn }
            .entries.joinToString("、") { (cn, rows) -> "${cn}×${rows.size}" }
        val basis = "判据「$key」24h 内误判 ${inWindow.size} 次（$bySource），" +
            "出处 ${inWindow.joinToString("、") { it.ref }}"
        return TriggerResult(true, "触发 a 成立：$basis", basis)
    }

    /** 触发 b：编号与原话的构造期闸在 [HumanOrderTrigger]——能建出对象就成立。 */
    private fun judgeHumanOrder(trigger: HumanOrderTrigger): TriggerResult {
        val basis = "人令「${trigger.orderRef}」：${trigger.instruction}"
        return TriggerResult(true, "触发 b 成立：用户明确要求改（$basis）", basis)
    }

    /** 触发 c：缺口累积 ≥ 现值交人线（N＝[GapFeedback.HAND_OVER_ATTEMPTS]，不自定）。 */
    private fun judgeGap(trigger: GapTrigger): TriggerResult {
        val line = GapFeedback.HAND_OVER_ATTEMPTS
        val worst = trigger.items.maxOfOrNull { it.attempts }
        if (worst == null || worst < line) {
            return TriggerResult(
                false,
                "缺口累积最大 ${worst ?: 0} 次，不到交人线 $line（N 用现值，不自定）：触发 c 不成立",
                "",
            )
        }
        val worstItem = trigger.items.first { it.attempts == worst }
        val basis = "差距「${worstItem.gapKey}」累积 $worst 次到交人线 $line"
        return TriggerResult(true, "触发 c 成立：$basis", basis)
    }

    // ── 进化本体：换表（apply）→ 验证（verify）→ 成则落账 / 败则回滚 ─────────

    /**
     * 硬规矩的那道明拒：新表里任何一条锚点撞不可进化清单 ⇒ 返回人读原因，null 才许过。
     *
     * 抽成纯函数不是多余——310 的快照构造期已经结构拦了一遍（撞清单的表**建不出来**），
     * 但正因如此，这一层拒不拒、拒得明不明白，只有让测试拿原始判据表直测才看得见
     * （364 的 [EpochRegistry.append] 留着同款第二道闸，口径同源）。
     */
    internal fun immutableReject(criteria: List<CriterionEntry>): String? {
        val hit = criteria.firstOrNull { ImmutableCriteria.match(it.target) != null } ?: return null
        val hitCriterion = ImmutableCriteria.match(hit.target)
        return "这张新表要动不可进化条「${hitCriterion?.cn}」" +
            "（法典 ${hitCriterion?.lawRef}，锚点 ${hit.target}）：" +
            "不可进化清单永不动，进化通道也不许——这三条只有用户亲手改"
    }

    /**
     * 提进化并换表。五道闸按序：待验不许叠加 → 御批编号必填 → 三触发有据 → 不可进化条拒 →
     * 310 的边界闸。全过了才换表并挂起待验；**任何一道拒，310 那边一字未动**。
     */
    fun apply(trigger: EvolutionTrigger, next: CriteriaSnapshot, rulingRef: String, nowMs: Long): EpochResult =
        synchronized(this) {
            val waiting = pending
            if (waiting != null) {
                return@synchronized refused(
                    "上一场进化（${waiting.triggerKind}，${waiting.basis}）还没验完：先 verify，" +
                        "不许叠加——半进化就是这么叠出来的"
                )
            }
            if (rulingRef.isBlank()) {
                return@synchronized refused("进化必须有御批/人令编号：没有编号＝没有据（硬规矩）")
            }
            val gate = canPropose(trigger, nowMs)
            if (!gate.allowed) {
                return@synchronized refused("三触发不成立，不许提进化：${gate.reason}")
            }
            if (next.criteria.isEmpty()) {
                return@synchronized refused("空判据表不算进化：把判据拆光不叫进化（Epoch 同口径）")
            }
            val immutableReason = immutableReject(next.criteria)
            if (immutableReason != null) {
                return@synchronized refused(immutableReason)
            }
            val swap = EpochRuntime.swap(next, nowMs)
            if (!swap.swapped) {
                return@synchronized refused("310 的换表闸先拒了：${swap.reason}")
            }
            pending = PendingEvolution(
                triggerKind = trigger.kind,
                basis = gate.basis,
                rulingRef = rulingRef,
                snapshot = next,
                epoch = Epoch.of(epoch = next.epoch, startedAt = nowMs, criteria = next.criteria, reason = gate.basis),
            )
            EpochResult(true, "进化已换表待验（触发 ${trigger.kind}）：${swap.reason}；四条验证全绿才算成，验不过自动回滚")
        }

    /**
     * 进化后验证：四条全绿才算成，缺一即失败。
     *
     * 通过 ⇒ 纪元册落账（[EpochRegistry.append]）＋账行记"通过·未回滚"；
     * 失败 ⇒ 自动回滚（上一张表走 [EpochRuntime.rollbackToPrevious]，首张表走 [EpochRuntime.reset]）
     * ＋账行记"失败·已回滚＋原因"；纪元册**不收**失败的纪。落账本身被拒也按失败处理
     * （落不了账的进化不算成——账与表必须同一时刻说同一句话）。
     */
    fun verify(proof: EvolutionProof, nowMs: Long): EvolutionOutcome = synchronized(this) {
        val waiting = pending
            ?: return@synchronized EvolutionOutcome(false, "没有待验的进化：verify 只答 apply 之后的那一场", false)
        val failures = proof.failures()
        if (failures.isEmpty()) {
            val append = EpochRegistry.append(waiting.epoch, waiting.snapshot, waiting.rulingRef, nowMs)
            if (!append.accepted) {
                rollbackAndRecord(waiting, "落账被拒：${append.reason}", nowMs)
            } else {
                attemptLedger += EvolutionAttemptRow(
                    triggerKind = waiting.triggerKind,
                    basis = waiting.basis,
                    rulingRef = waiting.rulingRef,
                    verdict = EvolutionAttemptRow.VERDICT_PASS,
                    rolledBack = false,
                    reason = "四条验证全绿：${append.reason}",
                    atMs = nowMs,
                )
                pending = null
                EvolutionOutcome(true, "进化成立：${waiting.basis}；${append.reason}", false)
            }
        } else {
            rollbackAndRecord(waiting, failures.joinToString("；"), nowMs)
        }
    }

    /** 失败收场：退表（不许半进化）＋账行记"失败·已回滚＋原因"。退完 [pending] 必空。 */
    private fun rollbackAndRecord(waiting: PendingEvolution, reason: String, nowMs: Long): EvolutionOutcome {
        val rb = if (EpochRuntime.previousSnapshot() == null) {
            // 首张表没有"上一张"可退：reset 回到"从没发生过"（398 口径）——同样是"不在半路上"。
            EpochRuntime.reset()
            RollbackResult(true, "首张表失败，已 reset 回到无表（从没发生过）")
        } else {
            EpochRuntime.rollbackToPrevious()
        }
        pending = null
        attemptLedger += EvolutionAttemptRow(
            triggerKind = waiting.triggerKind,
            basis = waiting.basis,
            rulingRef = waiting.rulingRef,
            verdict = EvolutionAttemptRow.VERDICT_FAIL,
            rolledBack = rb.rolledBack,
            reason = reason,
            atMs = nowMs,
        )
        return EvolutionOutcome(false, "验证失败（$reason）；${rb.reason}", rb.rolledBack)
    }

    private fun refused(reason: String): EpochResult = EpochResult(false, reason)

    /** 直测收尾（生产不调：进程态，照缺口板口径）。 */
    fun reset() = synchronized(this) {
        pending = null
        attemptLedger.clear()
        Unit
    }
}
