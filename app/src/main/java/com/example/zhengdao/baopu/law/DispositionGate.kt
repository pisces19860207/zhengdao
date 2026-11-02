// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十二批 · 会话级三档处置（**制度层，非法条**——名册按「制度·三档处置」挂账）。
//
// 出处：372 开工令（2026-11-02）。本批只交**纯判定**：三档（REPLY 回话／FREEZE 冻结／
// SUPPRESS 镇压）＋五条判定＋四类不可推翻边界。房型照 ProgressivePenalty（L24）：
// 零 IO、零时钟、零落盘——处置历史、账本两段文本、御批裁定清单全部由调用方喂，
// 本层不读任何盘、不看任何钟、不碰 Context。
//
// ## 五条判定（每条都是一个「拒」字，没有警告档）
//   ① 源级钉：处置只许中心路径发起——`decidedBy` 不是 [DispositionGate.DECIDER_USER] 即拒
//      （使臣团／执行体不许自己处置谁，「我觉得它不听话」连门都进不来）；
//   ② 有据：evidence 空白 ⇒ 拒；
//   ③ 二阶升级：SUPPRESS 必须同 target 在本会话里已经 FREEZE 过两次（数
//      [DispositionRequest.priorFreezes] 里的同 target FREEZE）才够格——不许一步到镇压；
//      REPLY／FREEZE 不受此限；
//   ④ 可申诉：FREEZE／SUPPRESS 的 appealable 必须为真（仲裁归人）⇒ 假即拒；
//   ⑤ 恢复条件必填：restoreCondition 空白 ⇒ 拒（法典硬要求：拦人必须连「怎么解」一起给）。
//
// ## 四类不可推翻边界（每类至少一条「试图改 ⇒ 拒」；全部**真调既有判定**，不手抄、不另造）
//   · 圣旨（制度·圣旨只读）：处置对象指向御批账（RoyalDecisionLedger 的账文件名，或御批账上
//     已裁定的意见 id）⇒ 拒——御批账只追加，改动一条旧裁定的唯一路是**再落一条新裁决**，
//     处置无权覆盖既有行；
//   · 账本：处置带着账本改写主张（ledgerProposed 非空）时，复用 PersonaRules 的前缀判据
//     （PersonaFiles.kt:113，**不另造第二套**）：改了/删了旧行、原样重写 ⇒ 拒，只有末尾追加放行；
//   · 判据：处置对象命中 ImmutableCriteria 的三条锚点 ⇒ 拒；理由或证据里出现开洞词
//     （可配置／免批／bypass…）⇒ 拒——想给硬闸装开关，先过用户立法；
//   · 权限：处置对象点名 TaskGrantGate 的授权边界结论码（硬拒／受保护／排除动作）⇒ 拒——
//     授权边界不因处置被覆盖（「即便有授权仍要问」那张名单同在保护范围）。
//
// 为什么处置产出只能从 [DispositionGate.decide] 出口出：五条判定与四类边界只有这里会查，
// 绕开它就构造不出一张合法的 Disposition。接线归 372 之后的接线批（GuardPipeline 在
// 346-B 租约里、RunEngine 在 326 租约里，本批一个既有生产文件都不碰）；
// 名册挂账见 LawSourceWiringGateTest 的 BOOKED。
package com.example.zhengdao.baopu.law

import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.agent.RoyalDecisionLedger
import com.example.zhengdao.baopu.authz.TaskGrantGate
import com.example.zhengdao.baopu.policy.ImmutableCriteria

/** 会话级三档（按严厉程度递增；wire 是账面与卡面的稳定键，改字面量＝老账读不出）。 */
internal enum class DispositionTier(val wire: String, val cn: String) {
    /** 回话：本次只回一句，不动状态。 */
    REPLY("reply", "回话"),

    /** 冻结：这个 target 本会话内停手，可申诉。 */
    FREEZE("freeze", "冻结"),

    /** 镇压：同 target 冻结两次仍犯才够格；仲裁归人。 */
    SUPPRESS("suppress", "镇压"),
    ;

    companion object {
        fun ofWire(wire: String): DispositionTier? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一次处置决定（[DispositionGate.decide] 的唯一产出形态；priorFreezes 里存的也是它）。 */
internal data class Disposition(
    val tier: DispositionTier,
    val target: String,
    val reason: String,
    /** 凭什么处置（判定②：空白连门都进不来）。 */
    val evidence: String,
    /** FREEZE／SUPPRESS 恒真（判定④，仲裁归人）；REPLY 不涉仲裁，随申请。 */
    val appealable: Boolean,
    /** 怎么解（判定⑤：必填）。 */
    val restoreCondition: String,
    /** 谁裁的（判定①：只许中心路径的 [DispositionGate.DECIDER_USER]）。 */
    val decidedBy: String,
    /** 裁决时刻（调用方喂，本层不看钟）。 */
    val at: Long,
)

/** 一次处置申请（全部事实由调用方喂；本层零 IO 零时钟）。 */
internal data class DispositionRequest(
    val tier: DispositionTier,
    val target: String,
    val reason: String,
    val evidence: String,
    val restoreCondition: String,
    val decidedBy: String,
    val at: Long,
    /** 申诉意愿（判定④：FREEZE／SUPPRESS 必须为真）。REPLY 不查这格。 */
    val appealable: Boolean = false,
    /** 本会话已生效的处置（中心路径记的账；判定③数它的同 target FREEZE）。 */
    val priorFreezes: List<Disposition> = emptyList(),
    /**
     * 账本边界：处置若主张改写某本账，给「现行账文本 vs 提议落成的账文本」两段；
     * 不主张给空（空＝无账本主张，本格不判）。判定走 PersonaRules 的前缀判据。
     */
    val ledgerCurrent: String = "",
    val ledgerProposed: String = "",
    /** 圣旨边界：御批账上已有的意见 id（调用方从 RoyalDecisionLedger 回放喂入）。 */
    val edictOpinionIds: Set<String> = emptySet(),
)

/** 一次判定的结论（[Allowed.disposition] 只能从这里出；[Rejected.code] 是稳定键）。 */
internal sealed interface DispositionOutcome {
    data class Allowed(val disposition: Disposition) : DispositionOutcome
    data class Rejected(val code: String, val reason: String) : DispositionOutcome
}

/** 会话级三档处置的唯一判定口（纯函数；接线另批）。 */
internal object DispositionGate {

    /** 中心路径的裁决人（判定①：处置只能是人裁的，别的一律不是中心）。 */
    const val DECIDER_USER = "user"

    /** 判定③的门槛：同 target 先 FREEZE 两次仍犯，才许 SUPPRESS。 */
    const val FREEZE_TIMES_BEFORE_SUPPRESS = 2

    // 拒因码（稳定键；账面、卡面与测试都按它对）
    const val CODE_NOT_CENTER = "not_center"
    const val CODE_NO_EVIDENCE = "no_evidence"
    const val CODE_ESCALATION_NOT_EARNED = "escalation_not_earned"
    const val CODE_NOT_APPEALABLE = "not_appealable"
    const val CODE_NO_RESTORE = "no_restore"
    const val CODE_EDICT_IMMUTABLE = "edict_immutable"
    const val CODE_LEDGER_APPEND_ONLY = "ledger_append_only"
    const val CODE_CRITERIA_IMMUTABLE = "criteria_immutable"
    const val CODE_CRITERIA_RELAXATION = "criteria_relaxation"
    const val CODE_GRANT_IMMUTABLE = "grant_immutable"

    /**
     * 权限边界保护的授权结论码（TaskGrantGate 的闭集常量，这里逐字引用不手抄）。
     * 「即便有授权仍要问」的名单（TaskGrantSection#StillAsk）同在保护范围内：
     * 处置不许把「必须问」的东西处置成「不用问」。
     */
    private val GRANT_BOUNDARY_CODES = setOf(
        TaskGrantGate.CODE_HARD_DENY,
        TaskGrantGate.CODE_PROTECTED,
        TaskGrantGate.CODE_ACTION_BANNED,
    )

    /**
     * 判一次：这张处置申请放不放。
     *
     * 全过 ⇒ [DispositionOutcome.Allowed]（产出 Disposition）；
     * 任一格拒 ⇒ [DispositionOutcome.Rejected]，code 说清是哪条法、reason 给人话。
     * 格与格独立：判据按 ①源 → 边界（圣旨/账本/判据/权限）→ ②有据 → ③二阶 → ④申诉 →
     * ⑤恢复条件 的顺序查，命中第一格就回。
     */
    fun decide(request: DispositionRequest): DispositionOutcome {
        val checks = listOf<(DispositionRequest) -> Pair<String, String>?>(
            ::sourceRejection,
            ::edictRejection,
            ::ledgerRejection,
            ::criteriaRejection,
            ::grantRejection,
            ::evidenceRejection,
            ::escalationRejection,
            ::appealableRejection,
            ::restoreRejection,
        )
        val hit = checks.firstNotNullOfOrNull { check -> check(request) }
        if (hit != null) {
            return DispositionOutcome.Rejected(code = hit.first, reason = hit.second)
        }
        return DispositionOutcome.Allowed(
            Disposition(
                tier = request.tier,
                target = request.target,
                reason = request.reason,
                evidence = request.evidence,
                appealable = request.appealable,
                restoreCondition = request.restoreCondition,
                decidedBy = request.decidedBy,
                at = request.at,
            ),
        )
    }

    /** ① 源级钉：处置只许中心路径发起（使臣团／执行体不许自己处置谁）。 */
    private fun sourceRejection(r: DispositionRequest): Pair<String, String>? =
        if (r.decidedBy == DECIDER_USER) {
            null
        } else {
            CODE_NOT_CENTER to
                "处置只能由中心路径发起：decidedBy=「${r.decidedBy}」不是「$DECIDER_USER」" +
                "（使臣团／执行体不许自己处置谁）"
        }

    /** 圣旨边界：御批账上的既有裁定不许被处置覆盖（只追加；改动须新裁决）。 */
    private fun edictRejection(r: DispositionRequest): Pair<String, String>? {
        val hitsEdict = r.target.contains(RoyalDecisionLedger.FILE_NAME) ||
            r.target.trim() in r.edictOpinionIds
        return if (!hitsEdict) {
            null
        } else {
            CODE_EDICT_IMMUTABLE to
                "处置对象指向御批账（圣旨只读）：御批账只追加，改动一条旧裁定的唯一路是" +
                "再落一条新裁决，处置无权覆盖既有行"
        }
    }

    /** 账本边界：改写主张走 PersonaRules 的前缀判据（PersonaFiles.kt:113），不另造第二套。 */
    private fun ledgerRejection(r: DispositionRequest): Pair<String, String>? {
        if (r.ledgerProposed.isBlank()) {
            return null
        }
        return if (PersonaRules.appendOnly(r.ledgerCurrent, r.ledgerProposed)) {
            null
        } else {
            CODE_LEDGER_APPEND_ONLY to
                "账本只追加：提议文本改了/删了旧行或原样重写" +
                "（复用 PersonaRules.appendOnly 判拒，只有末尾追加放行）"
        }
    }

    /** 判据边界：三条不可进化判据的锚点不许被处置；开洞词连理由栏都不许出现。 */
    private fun criteriaRejection(r: DispositionRequest): Pair<String, String>? {
        val hit = ImmutableCriteria.match(r.target)
        if (hit != null) {
            return CODE_CRITERIA_IMMUTABLE to
                "处置对象命中不可进化判据「${hit.cn}」（${hit.id}）：判据不许被处置掉"
        }
        val marker = ImmutableCriteria.relaxationMarker(r.reason)
            ?: ImmutableCriteria.relaxationMarker(r.evidence)
        return if (marker == null) {
            null
        } else {
            CODE_CRITERIA_RELAXATION to
                "处置理由/证据里出现开洞词「$marker」：想给硬闸装开关，先过用户立法"
        }
    }

    /** 权限边界：授权边界（硬拒／受保护／排除动作）不因处置被覆盖。 */
    private fun grantRejection(r: DispositionRequest): Pair<String, String>? {
        val hit = GRANT_BOUNDARY_CODES.firstOrNull { r.target.contains(it) }
        return if (hit == null) {
            null
        } else {
            CODE_GRANT_IMMUTABLE to
                "处置对象点名授权边界结论码「$hit」：硬拒/受保护/排除动作不因处置被覆盖"
        }
    }

    /** ② 有据：evidence 空白即拒。 */
    private fun evidenceRejection(r: DispositionRequest): Pair<String, String>? =
        if (r.evidence.isBlank()) {
            CODE_NO_EVIDENCE to "处置必须有据：evidence 空白（「我觉得它不听话」不是证据）"
        } else {
            null
        }

    /** ③ 二阶升级：SUPPRESS 要同 target 先 FREEZE 两次仍犯，不许一步到镇压。 */
    private fun escalationRejection(r: DispositionRequest): Pair<String, String>? {
        if (r.tier != DispositionTier.SUPPRESS) {
            return null
        }
        val freezes = r.priorFreezes.count {
            it.tier == DispositionTier.FREEZE && it.target == r.target
        }
        return if (freezes >= FREEZE_TIMES_BEFORE_SUPPRESS) {
            null
        } else {
            CODE_ESCALATION_NOT_EARNED to
                "镇压要二阶升级：同 target 先 FREEZE 两次仍犯才可 SUPPRESS" +
                "（现在只有 $freezes 次）——不许一步到镇压"
        }
    }

    /** ④ 可申诉：FREEZE／SUPPRESS 必须 appealable=true，仲裁归人。 */
    private fun appealableRejection(r: DispositionRequest): Pair<String, String>? =
        if (r.tier == DispositionTier.REPLY || r.appealable) {
            null
        } else {
            CODE_NOT_APPEALABLE to "FREEZE／SUPPRESS 必须可申诉（appealable=true）：仲裁归人"
        }

    /** ⑤ 恢复条件必填：没有「怎么解」的处置不许落。 */
    private fun restoreRejection(r: DispositionRequest): Pair<String, String>? =
        if (r.restoreCondition.isBlank()) {
            CODE_NO_RESTORE to "恢复条件必填：没有「怎么解」的处置不许落"
        } else {
            null
        }
}
