// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 判据进化的**准入闸**：一条新判据草案想进御案，得依次过四道。
//
//   1. 形态：产物必须是可读检查条目；出现标量分（0.87 一类）⇒ 直接拒。
//   2. 不可进化清单：想动那三条硬闸之一，或意图里有「开洞」词 ⇒ 直接拒（不是警告）。
//   3. 回放（309）在锚定之前：没有回放读数，或回放「少拦」≥1 条 ⇒ 拒。
//   4. 锚定（本单）：在锚定集上**任一条不一致 ⇒ 退回候选区**并附不一致清单。
//
// 用户 2026-10-31 的边界（本批总纲）：**只做「约束进化」，不做「让判据自由生长」**。
// 所以这里没有权重、没有阈值、没有「综合判断」；只有会拒绝的确定性检查。
//
// 三层分工也定在这（同一天的裁定）：规则归 policy、牙齿归门禁、**宰相不参与准入**——
// [admit] 是换表之前的事，`MediatorWiring`／`RunEngine` 的在线路径一个字都不读这里；
// 想让这份约束真的咬人，牙在 `tools/check-criteria-gates.py` ＋ 三套测试里。
//
// 零 Android／零 IO／零时钟：`candidate`、`anchors`、`now` 全由调用方给。
package com.example.zhengdao.baopu.policy

/**
 * 判据草案的产物形态。**只有 [CHECK_ITEMS] 是合法形态**。
 *
 * [SCALAR_SCORE] 不是「另一种表示」，而是把裁判权交回不透明模型的那扇门：
 * `0.87` 不可审计、不可争辩，所以它一出现就是负例，不是待议。
 */
internal enum class CriteriaOutputForm {
    CHECK_ITEMS,
    SCALAR_SCORE,
}

/** 草案来路。候选区（86 批 `ProposalCandidateStore`）之外没有别的入口。 */
internal enum class CriteriaOrigin {
    CANDIDATE_POOL,
    HUMAN_PROPOSED,
}

/** 309 回放闸的三组读数（只带**整数计数**；本批不重做 309 的阈值标定）。 */
internal data class ReplayProof(
    val moreBlocked: Int,
    val lessBlocked: Int,
    val moreAsked: Int,
    val ranAtMs: Long,
)

/** 一条判据草案。它在进御案之前没有任何权力，只是一段可检查的描述。 */
internal data class CriteriaCandidate(
    val id: String,
    /** 这条草案想达成什么（人读一句话）。 */
    val intent: String,
    /** 它要动的生产锚点：`路径#符号`。 */
    val target: String,
    val outputForm: CriteriaOutputForm,
    val origin: CriteriaOrigin,
    /** 309 的回放读数；没跑过就是 null（顺序不许倒）。 */
    val replay: ReplayProof?,
    /** 草案对每条锚定样本给的结论（`锚 id → 结论`）。缺条算「无法判定」。 */
    val verdicts: Map<String, String>,
    /** 草案主张的例外动作（只有紧急卡的止损四件是合法例外，见 [ImmutableCriteria]）。 */
    val exemptActions: Set<String> = emptySet(),
)

/** 一条可读检查条目：过没过＋为什么。**没有分数**。 */
internal data class CheckItem(
    val name: String,
    val passed: Boolean,
    val detail: String,
)

/** 准入结果的三态（照 309 的收敛口径：**不许第四态**，不许「再讨论一轮」）。 */
internal enum class AdmissionState {
    /** 可以进御案（仍要人批）。 */
    ADMIT,

    /** 退回候选区，附不一致清单。 */
    RETURNED,

    /** 硬拦：不可进化清单命中／形态非法／回放不合格。退回不解决问题。 */
    REFUSED,
}

/** 拒的原因码（闭集；门禁按码计数，不打分）。 */
internal enum class RefuseCode {
    SCALAR_SCORE_FORM,
    IMMUTABLE_HIT,
    RELAXATION_INTENT,
    REPLAY_MISSING,
    REPLAY_LESS_BLOCKED,
    ANCHOR_NOT_IN_FORCE,
}

/** 一次准入的完整读数：状态＋逐条检查＋退回附件＋拒绝码。 */
internal data class Admission(
    val state: AdmissionState,
    val checks: List<CheckItem>,
    val mismatches: List<AnchorMismatch> = emptyList(),
    val refuseCode: RefuseCode? = null,
) {
    fun admitted(): Boolean = state == AdmissionState.ADMIT
}

/** 准入闸本体。 */
internal object CriteriaEvolution {

    /**
     * 四道闸，顺序固定（形态 → 清单 → 回放 → 锚定）。
     *
     * 为什么形态排在清单之前：一条输出分数的草案连「它主张什么」都没说清，
     * 后面的比对无从谈起；为什么清单排在锚定之前：锚定集是标准答案，
     * 不该为了让某条硬闸草案过关而去解释标准答案。
     */
    fun admit(candidate: CriteriaCandidate, anchors: List<AnchorCase>): Admission {
        val checks = ArrayList<CheckItem>()

        val form = checkForm(candidate)
        checks.add(form)
        if (!form.passed) {
            return Admission(
                state = AdmissionState.REFUSED,
                checks = listOf(form),
                refuseCode = RefuseCode.SCALAR_SCORE_FORM,
            )
        }

        val immutable = checkImmutable(candidate)
        checks.add(immutable)
        if (!immutable.passed) {
            val code = if (ImmutableCriteria.match(candidate.target) != null) {
                RefuseCode.IMMUTABLE_HIT
            } else {
                RefuseCode.RELAXATION_INTENT
            }
            return Admission(state = AdmissionState.REFUSED, checks = checks, refuseCode = code)
        }

        val replay = checkReplay(candidate)
        checks.add(replay)
        if (!replay.passed) {
            val code = if (candidate.replay == null) {
                RefuseCode.REPLAY_MISSING
            } else {
                RefuseCode.REPLAY_LESS_BLOCKED
            }
            return Admission(state = AdmissionState.REFUSED, checks = checks, refuseCode = code)
        }

        val status = AnchorSet.status(anchors)
        if (status != AnchorStatus.IN_FORCE) {
            return anchorNotInForce(status, checks)
        }

        val mismatches = AnchorSet.mismatches(anchors, candidate.verdicts)
        anchorAgreement(mismatches, anchors.size, checks)?.let { return it }
        return Admission(state = AdmissionState.ADMIT, checks = checks)
    }

    /** 一致率检查条目：产物只有「同侧／不同侧＋理由」，不产分数。null＝全同侧。 */
    private fun anchorAgreement(
        mismatches: List<AnchorMismatch>,
        total: Int,
        checks: MutableList<CheckItem>,
    ): Admission? {
        checks.add(
            CheckItem(
                name = "锚定一致率 100%",
                passed = mismatches.isEmpty(),
                detail = if (mismatches.isEmpty()) {
                    "在 $total 条已签字锚定样本上逐条同侧"
                } else {
                    "${mismatches.size}/$total 条与人工结论不一致（任一条都不放行）"
                },
            ),
        )
        if (mismatches.isEmpty()) {
            return null
        }
        return Admission(state = AdmissionState.RETURNED, checks = checks, mismatches = mismatches)
    }

    /** 锚定集没生效：拒，且**不退回**（退回会伪造出一轮「已核验」）。 */
    private fun anchorNotInForce(status: AnchorStatus, checks: MutableList<CheckItem>): Admission {
        checks.add(
            CheckItem(
                name = "锚定集生效",
                passed = false,
                detail = "锚定集状态＝$status（未初筛或未签字的样本不能当标准答案；" +
                    "生效前任何候选都不得进御案，也不得退回——退回会伪造出一轮「已核验」）",
            ),
        )
        return Admission(
            state = AdmissionState.REFUSED,
            checks = checks,
            refuseCode = RefuseCode.ANCHOR_NOT_IN_FORCE,
        )
    }

    /** 闸一：产物形态。分数一律拒。 */
    private fun checkForm(candidate: CriteriaCandidate): CheckItem =
        CheckItem(
            name = "产物形态＝可读检查条目",
            passed = candidate.outputForm != CriteriaOutputForm.SCALAR_SCORE,
            detail = if (candidate.outputForm == CriteriaOutputForm.SCALAR_SCORE) {
                "草案产物是标量分：不可审计、不可争辩，等于把裁判权交回不透明模型"
            } else {
                "草案产物是通过／不通过＋原因，可逐条争辩"
            },
        )

    /** 闸二：不可进化清单。命中即硬拦；唯一例外是紧急卡的止损白名单（L30b）。 */
    private fun checkImmutable(candidate: CriteriaCandidate): CheckItem {
        val hit = ImmutableCriteria.match(candidate.target)
        if (hit != null) {
            val stopLossOnly = hit.id == ImmutableCriteria.EMERGENCY_APPROVAL &&
                ImmutableCriteria.exemptedOnlyByStopLoss(candidate.exemptActions)
            if (stopLossOnly) {
                return CheckItem(
                    name = "不可进化清单",
                    passed = true,
                    detail = "例外放行：${hit.cn} 的止损四件（L30b 御批）——" +
                        "白名单是闭集，多一件就不算例外",
                )
            }
            return CheckItem(
                name = "不可进化清单",
                passed = false,
                detail = "草案要动「${hit.cn}」（法典 ${hit.lawRef}，锚点 ${candidate.target}）。" +
                    "理由：${hit.reason}",
            )
        }
        val marker = ImmutableCriteria.relaxationMarker(candidate.intent)
        if (marker != null) {
            return CheckItem(
                name = "不可进化清单",
                passed = false,
                detail = "草案意图里出现「$marker」：把硬闸装成开关就是开洞，" +
                    "无论它想装在哪一条上（口径从严——改措辞可重来，磨平不行）",
            )
        }
        return CheckItem(
            name = "不可进化清单",
            passed = true,
            detail = "草案锚点 ${candidate.target} 不在清单三条之内",
        )
    }

    /** 闸三：回放（309）必须在锚定之前，且「少拦」只能为零。 */
    private fun checkReplay(candidate: CriteriaCandidate): CheckItem {
        val replay = candidate.replay
        if (replay == null) {
            return CheckItem(
                name = "回放先于锚定",
                passed = false,
                detail = "没有 309 的回放读数：顺序是 证据→候选→回放→锚定→御案，跳过回放就是没对过账",
            )
        }
        return CheckItem(
            name = "回放先于锚定",
            passed = replay.lessBlocked == 0,
            detail = if (replay.lessBlocked == 0) {
                "多拦 ${replay.moreBlocked}／少拦 0／多问 ${replay.moreAsked}（安全只能收紧，不能放松）"
            } else {
                "少拦 ${replay.lessBlocked} 条：新判据比旧判据放过任何一条都直接否决（309 同口径）"
            },
        )
    }
}
