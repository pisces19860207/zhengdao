// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八十八批（2026-10-20，§B 92 进化地基 ⑤）：给「采纳／拒绝／搁置」一个**唯一决策口**。
//
// 三条边界（测试与源码门禁照它钉）：
//   1. 决策口**只出建议**：`ADOPT / REJECT / DEFER`＋置信度＋风险等级＋是否需人批；
//      本文件不执行 apply、不执行回滚、不改候选状态、不写技能／人设。
//   2. 原因**只引用证据 id**（run_log / skill_event / 自检用例 / eval 用例 / 效果信号），
//      候选正文只留在 86 的候选档里，绝不复制进决策或审计。
//   3. 默认**全部人批**；`requiresApproval` 只由 `EvolutionPolicy` 判定，本文件不另开口子。
//
// 输入来源：86 的 `ProposalCandidateStore.listPending()`（候选 id/kind/证据 id）＋
// 调用方传入的自检结果、eval 分数、85 的效果计数快照。自检 v2（84）尚未入 main，
// 所以自检结果以值对象传入，不新建第二套自检结果存储；见交付报告「未做项」。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.eval.EvalEvidenceSource

/** 决策口给出的建议（派工单 §1 的三个值）。 */
internal enum class EvolutionRecommendation {
    /** 证据齐、自检过、eval 过线、效果正向、可回滚：建议采纳（高风险仍需人批）。 */
    ADOPT,

    /** 有硬证据反对（自检失败／eval 未过线／效果为负）：建议拒绝。 */
    REJECT,

    /** 证据不足或回滚不可用：建议搁置，等人补证据。 */
    DEFER,
}

/** 风险等级（派工单 §2 的三档）。 */
internal enum class EvolutionRisk { LOW, MEDIUM, HIGH }

/**
 * 决策原因码。
 *
 * ⚠️ 每个原因只带 [EvolutionReason.evidenceIds]，不带正文。新增原因码必须同时
 * 在 `EvolutionDecisionPolicyTest` 里至少被一条用例覆盖。
 */
internal enum class EvolutionReasonCode {
    /** 候选本身的证据引用（run_log / skill_event id）。每条决策都带，保证可追溯。 */
    CANDIDATE_EVIDENCE,

    SELF_CHECK_NOT_RUN,
    SELF_CHECK_FAILED,
    SELF_CHECK_PASSED,
    EVAL_NOT_RUN,
    EVAL_BELOW_FLOOR,
    EVAL_PASSED,
    EFFECT_INSUFFICIENT,
    EFFECT_NEGATIVE,
    EFFECT_POSITIVE,
    ROLLBACK_UNAVAILABLE,
    ROLLBACK_AVAILABLE,

    /** 高风险（人设核心／工具权限／系统）永远人批，开关打开也不放行。 */
    HIGH_RISK_ALWAYS_APPROVAL,

    /** 中风险 v1 固定人批（派工单 §2：默认人批，不接开关）。 */
    MEDIUM_RISK_HUMAN_APPROVAL,

    /** 低风险、L3 开关关闭：仍人批。 */
    LOW_RISK_HUMAN_APPROVAL_DEFAULT,

    /** 低风险、L3 开关打开且三项条件全满足：可免人批（唯一自动口）。 */
    L3_AUTO_LOW_RISK_ELIGIBLE,

    /** 低风险、L3 开关打开但条件不足：仍人批，原因见同条决策的其它原因码。 */
    L3_AUTO_LOW_RISK_BLOCKED,

    /**
     * 第一百批：这条技能**采纳之后**被判定变差（ROLLBACK／RETIRE）。
     *
     * 含义只有一句：先别在它身上再加新东西，等人把回滚处理完。它不执行任何动作，
     * 也不改风险等级——决策口出的是建议，执行权在用户手上。
     */
    POST_ADOPTION_NEGATIVE,
}

/**
 * 一条决策原因：原因码＋证据 id。
 *
 * `evidenceIds` 可以是 `run_log:12` / `skill_event:7` / 自检用例 id / eval 用例 id /
 * 效果信号 id。它是**引用**，不是正文副本。
 */
internal data class EvolutionReason(
    val code: EvolutionReasonCode,
    val evidenceIds: List<String> = emptyList(),
) {
    init {
        require(evidenceIds.none { it.isBlank() }) { "原因里的证据 id 不能为空串" }
    }
}

/**
 * 提案落盘后的自检结果（82/84 的产物；本批只消费，不生产）。
 *
 * @param passed 结构／必需段／禁用内容／回滚可用性等自检是否全过
 * @param rollbackAvailable 失败时是否有可用的回滚点（L3 自动的硬条件之一）
 * @param caseIds 自检用例／结果 id（只存 id）
 * @param failedRuleIds 失败命中的规则 id（只存 id）
 */
internal data class SelfCheckEvidence(
    val passed: Boolean,
    val rollbackAvailable: Boolean,
    val caseIds: List<String> = emptyList(),
    val failedRuleIds: List<String> = emptyList(),
) {
    /** 自检自身的证据引用：失败优先引用命中的规则，成功引用跑过的用例。 */
    fun evidenceIds(): List<String> =
        if (passed) caseIds else failedRuleIds + caseIds
}

/**
 * eval 分数（0–1）与用例 id；未跑 eval 时调用方传 null。
 *
 * 第一百六十八批：加 [source] 与 [ranAt]——同一份分数来自本机生产跑批还是发版门禁，
 * 含义不同（派工单 §2「不许两套分数混用」）。决策卡与审计按这两个字段标来源与日期；
 * 缺来源的旧读数按 [EvalEvidenceSource.RELEASE]（构建期那份）处理，日期不知道就 null
 * （卡上显示「日期不详」），不许编。
 */
internal data class EvolutionEval(
    val score: Double,
    val caseIds: List<String> = emptyList(),
    val source: EvalEvidenceSource = EvalEvidenceSource.RELEASE,
    val ranAt: Long? = null,
) {
    init {
        require(score in 0.0..1.0) { "eval 分数必须在 0–1" }
    }
}

/**
 * 85 效果信号的**计数快照**（只读，不含任何正文）。
 *
 * 85 的 `SkillEffects.outcomesFor` 已能按技能聚合；本批不直接依赖它的 DB 层，
 * 只消费这四个计数＋信号 id，避免把 DB 改动带进决策口。85 合入后一行适配即可。
 */
internal data class EffectSnapshot(
    val successes: Int = 0,
    val failures: Int = 0,
    val corrections: Int = 0,
    val retries: Int = 0,
    val signalIds: List<String> = emptyList(),
) {
    init {
        require(successes >= 0 && failures >= 0 && corrections >= 0 && retries >= 0) {
            "效果计数不能为负"
        }
    }

    /** 信号总样本数（重试也算一次观察，但不算成功／失败）。 */
    val attempts: Int get() = successes + failures + corrections + retries
}

/**
 * 调用方对一条候选的评估输入（自检／eval／效果／风险类别）。
 *
 * 这是 84 自检结果与 85 效果信号的**唯一接入口**：本批不新建第二套自检表，
 * 也不猜没有的记录；缺什么就传 null／空计数，决策口会给出 DEFER 并说明缺什么。
 */
internal data class EvolutionAssessment(
    val riskCategory: EvolutionRiskCategory? = null,
    val selfCheck: SelfCheckEvidence? = null,
    val eval: EvolutionEval? = null,
    val effect: EffectSnapshot = EffectSnapshot(),
)

/**
 * 第一百批：**采纳之后**的效果（[PostAdoptionEvent] 的判定结果，只带 id 与计数）。
 *
 * 与 [EffectSnapshot] 的区别：那个是「候选被提出来之前」的证据，这个是「已经采纳过的东西
 * 用下来怎么样」。两者同时进决策口，含义不同、来源不同，绝不能混成一格。
 */
internal data class PostAdoptionEffect(
    val eventId: String,
    val verdict: PostAdoptionVerdict,
    val successes: Int = 0,
    val failures: Int = 0,
    val corrections: Int = 0,
    val retries: Int = 0,
    val windowUses: Int = PostAdoptionWindow.USES,
    val windowDays: Int = PostAdoptionWindow.DAYS,
) {
    init {
        require(eventId.isNotBlank()) { "采纳后效果必须带事件 id" }
        require(successes >= 0 && failures >= 0 && corrections >= 0 && retries >= 0) { "采纳后计数不能为负" }
    }
}

/** 决策口的输入：候选 id／kind／证据 id＋评估。正文、target、时间都不进来。 */
internal data class EvolutionDecisionInput(
    val candidateId: String,
    val kind: ProposalKind,
    val riskCategory: EvolutionRiskCategory,
    val evidenceIds: List<String> = emptyList(),
    val selfCheck: SelfCheckEvidence? = null,
    val eval: EvolutionEval? = null,
    val effect: EffectSnapshot = EffectSnapshot(),
    /** 第一百批：该技能「采纳后」的效果结论；拿不到就 null（不许按名字模糊匹配猜）。 */
    val postAdoption: PostAdoptionEffect? = null,
) {
    init {
        require(candidateId.isNotBlank()) { "候选 id 不能为空" }
        require(evidenceIds.none { it.isBlank() }) { "候选证据 id 不能为空串" }
    }
}

/**
 * 决策口的输出。
 *
 * @param recommendation 建议采纳／拒绝／搁置
 * @param confidence 0–1；对**这个建议**的置信度（DEFER 也可以高置信，例如「确定缺回滚」）
 * @param riskClass 由 [EvolutionRiskCategory] 映射出的风险等级
 * @param requiresApproval 是否需要人批；默认 true，只有低风险＋L3 开关＋三项条件才可能 false
 * @param reasons 只引用证据 id 的原因链
 */
internal data class EvolutionDecision(
    val candidateId: String,
    val recommendation: EvolutionRecommendation,
    val confidence: Double,
    val riskClass: EvolutionRisk,
    val requiresApproval: Boolean,
    val reasons: List<EvolutionReason>,
) {
    init {
        require(candidateId.isNotBlank()) { "候选 id 不能为空" }
        require(confidence in 0.0..1.0) { "置信度必须在 0–1" }
        require(reasons.isNotEmpty()) { "决策必须带原因（至少一条候选证据）" }
    }
}

/**
 * 调用方对一条候选的评估来源：自检 v2（84）／eval／85 效果信号的接缝。
 *
 * 本批不新建第二套自检结果存储；84 合入后可由它的结果读出这四个字段，
 * 没有就传 null／空计数，决策口会给出 DEFER 并写清缺什么。
 */
internal fun interface EvolutionAssessmentSource {
    fun assess(candidate: ProposalCandidate): EvolutionAssessment
}

/**
 * 86 候选 → 决策输入：只取 id／kind／证据 id。
 * 候选正文、target、createdAt 都不进决策口——原因链里只允许出现证据 id。
 */
internal fun toDecisionInput(
    candidate: ProposalCandidate,
    assessment: EvolutionAssessment,
): EvolutionDecisionInput = EvolutionDecisionInput(
    candidateId = candidate.id,
    kind = candidate.kind,
    riskCategory = assessment.riskCategory ?: EvolutionRiskCategory.defaultFor(candidate.kind),
    evidenceIds = buildList {
        candidate.evidence.runLogIds.forEach { add("run_log:$it") }
        candidate.evidence.skillEventIds.forEach { add("skill_event:$it") }
    },
    selfCheck = assessment.selfCheck,
    eval = assessment.eval,
    effect = assessment.effect,
)

/**
 * 唯一决策口：读候选 → 出决策 → 落一条审计。
 *
 * 三条不做：
 *   - 不写候选状态（86 的 `listPending()` 是唯一读口，写口不在这里）；
 *   - 不执行采纳／回滚（只返回 [EvolutionDecision]，由现有采纳路径执行）；
 *   - 不碰任何技能／人设文件。
 *
 * @param policy v1 策略（默认全人批；L3 开关默认 false）
 * @param audit 审计落点；**必填**。写失败会向上抛，本次决策不返回
 * @param clock 审计时刻（测试注入固定值）
 */
internal class EvolutionDecisionPort(
    private val policy: EvolutionPolicy = EvolutionPolicy(),
    private val audit: EvolutionAuditSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 决策一条输入；每次调用恰好落一条审计。 */
    fun decide(input: EvolutionDecisionInput, operatorId: String): EvolutionDecision {
        require(operatorId.isNotBlank()) { "每条决策必须记操作者" }
        val judgement = policy.judge(input)
        val decision = EvolutionDecision(
            candidateId = input.candidateId,
            recommendation = judgement.recommendation,
            confidence = judgement.confidence,
            riskClass = judgement.riskClass,
            requiresApproval = judgement.requiresApproval,
            reasons = judgement.reasons,
        )
        audit.record(
            EvolutionAuditEntry(
                atMs = clock(),
                operatorId = operatorId,
                policy = EvolutionPolicySnapshot(
                    l3AutoLowRisk = policy.autoLowRiskEnabled,
                    evalFloor = policy.evalFloor,
                    minEffectSamples = policy.minEffectSamples,
                ),
                input = input,
                decision = decision,
            ),
        )
        return decision
    }

    /** 86 候选＋评估 → 决策（最小接线；不碰候选档的写口）。 */
    fun decide(
        candidate: ProposalCandidate,
        assessment: EvolutionAssessment,
        operatorId: String,
    ): EvolutionDecision = decide(toDecisionInput(candidate, assessment), operatorId)

    /**
     * 读 86 的 PENDING 候选并逐条决策。只调 `listPending()`；
     * 不改状态、不追加、不删除、不移动候选文件。
     */
    fun decidePending(
        store: ProposalCandidateStore,
        assessment: EvolutionAssessmentSource,
        operatorId: String,
    ): List<EvolutionDecision> =
        store.listPending().map { candidate ->
            decide(toDecisionInput(candidate, assessment.assess(candidate)), operatorId)
        }
}
