// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十八批（2026-10-20，§B 92 进化地基 ⑤）：**纯策略**——输入评估，出建议与是否人批。
// 零 I/O、零 Android、零网络；审计与候选读取在别处，策略本身可 JVM 直测。
//
// 风险映射（派工单 §2）：
//   高风险 = 人设核心（PERSONA_CORE）／工具权限（TOOL_PERMISSION）／系统（ROOTFS_SYSTEM）
//            ⇒ 永远 requiresApproval = true，L3 开关打开也不放行；
//   中风险 = 技能正文结构变化（SKILL_BODY_STRUCTURE）／跨技能影响（CROSS_SKILL_IMPACT）
//            ⇒ v1 固定人批，不接开关；
//   低风险 = 提示词微调（PROMPT_TWEAK）／技能正文小补（SKILL_BODY_MINOR）
//            ⇒ 仍人批；只有 L3 开关打开、且 eval 过线＋效果正向＋回滚可用三项同时满足，
//              才允许 requiresApproval = false（唯一自动口）。
//
// 建议（不是执行）：
//   REJECT = 有硬证据反对（自检失败／eval 未过线／效果为负）；
//   DEFER  = 证据不足（自检/eval 没跑、效果样本不够）或回滚不可用；
//   ADOPT  = 自检过＋eval 过线＋效果正向＋回滚可用。
package com.example.zhengdao.baopu

/**
 * 风险类别：调用方按候选内容判定，本类只做**类别 → 等级**的确定性映射。
 *
 * 为什么单列类别而不是直接传等级：同一等级下「人设核心」和「提示词微调」的
 * 处置理由不同；审计要能看出是哪种风险，而不是只看到一个 HIGH。
 */
internal enum class EvolutionRiskCategory {
    /** 人设核心（B/C 层正文、人设追加）。 */
    PERSONA_CORE,

    /** 工具权限（工具约束、权限边界）。 */
    TOOL_PERMISSION,

    /** 系统层（rootfs、系统文件、进程/端口等）。 */
    ROOTFS_SYSTEM,

    /** 技能正文结构变化（章节、必需段、索引结构）。 */
    SKILL_BODY_STRUCTURE,

    /** 跨技能影响（一条技能改动会波及别的技能/索引）。 */
    CROSS_SKILL_IMPACT,

    /** 提示词微调（措辞、示例、排版）。 */
    PROMPT_TWEAK,

    /** 技能正文小补（追加一段不改变结构的规则）。 */
    SKILL_BODY_MINOR;

    /** 类别 → 等级（写死在 when 里，测试逐项钉）。 */
    val riskClass: EvolutionRisk
        get() = when (this) {
            PERSONA_CORE, TOOL_PERMISSION, ROOTFS_SYSTEM -> EvolutionRisk.HIGH
            SKILL_BODY_STRUCTURE, CROSS_SKILL_IMPACT -> EvolutionRisk.MEDIUM
            PROMPT_TWEAK, SKILL_BODY_MINOR -> EvolutionRisk.LOW
        }

    companion object {
        /**
         * 86 三种候选 kind 的**保守默认**：
         *   人设追加 → 高风险；工具规则 → 高风险；技能正文 → 中风险。
         *
         * 更细的判定（例如「技能正文只是小补」）由调用方显式传类别；
         * 默认值宁可高估风险，也不把结构改动误当微调。
         */
        fun defaultFor(kind: ProposalKind): EvolutionRiskCategory = when (kind) {
            ProposalKind.PERSONA_APPEND -> PERSONA_CORE
            ProposalKind.TOOL_RULE -> TOOL_PERMISSION
            ProposalKind.SKILL_BODY -> SKILL_BODY_STRUCTURE
        }
    }
}

/** 效果快照的判定（与 [EvolutionPolicy.effectVerdict] 一致）。 */
internal enum class EffectVerdict { POSITIVE, NEGATIVE, INSUFFICIENT }

/** 策略的纯输出（不含候选 id；由决策口补上并落审计）。 */
internal data class EvolutionJudgement(
    val recommendation: EvolutionRecommendation,
    val confidence: Double,
    val riskClass: EvolutionRisk,
    val requiresApproval: Boolean,
    val reasons: List<EvolutionReason>,
)

/**
 * v1 策略。所有阈值都可注入，但默认值写死在 [companion]；
 * `L3_AUTO_LOW_RISK` 默认 **false**，本批不读设置页、不读环境变量——开关只由调用方显式传。
 */
internal class EvolutionPolicy(
    val evalFloor: Double = DEFAULT_EVAL_FLOOR,
    val minEffectSamples: Int = DEFAULT_MIN_EFFECT_SAMPLES,
    val autoLowRiskEnabled: Boolean = L3_AUTO_LOW_RISK,
) {
    init {
        require(evalFloor in 0.0..1.0) { "eval 过线值必须在 0–1" }
        require(minEffectSamples >= 1) { "效果样本下限至少为 1" }
    }

    /**
     * 效果判定：
     *   POSITIVE = 成功 ≥ N 且成功多于失败/纠正；
     *   NEGATIVE = 失败/纠正 ≥ N 且多于成功；
     *   其余（含样本不足、势均力敌）一律 INSUFFICIENT——不猜。
     * 重试（retries）只进审计，不冒充成功或失败。
     *
     * 第一百批：算法本身搬到 [effectVerdictOf]（伴生纯函数），这样「采纳后效果」这一层
     * 可以直接调用**同一个判据**，不用为了拿判定去 new 一个策略实例（生产实例化只许留
     * 决策口一处，89 的门禁钉着）。
     */
    fun effectVerdict(effect: EffectSnapshot): EffectVerdict =
        effectVerdictOf(effect, minEffectSamples)

    /** 一条决策的完整策略判定（建议＋置信度＋等级＋是否人批＋原因链）。 */
    fun judge(input: EvolutionDecisionInput): EvolutionJudgement {
        val riskClass = input.riskCategory.riskClass
        val reasons = mutableListOf(
            EvolutionReason(EvolutionReasonCode.CANDIDATE_EVIDENCE, input.evidenceIds),
        )
        val recommendation = recommend(input, reasons)
        val approval = approval(input, riskClass, recommendation)
        reasons += approval.reasons
        return EvolutionJudgement(
            recommendation = recommendation,
            confidence = confidence(input),
            riskClass = riskClass,
            requiresApproval = approval.requiresApproval,
            reasons = reasons,
        )
    }

    /** 建议链：硬否决 → 证据不足 → 回滚不可用 → 效果判定 → 采纳。 */
    private fun recommend(
        input: EvolutionDecisionInput,
        reasons: MutableList<EvolutionReason>,
    ): EvolutionRecommendation {
        // 第一百批（唯一新增规则）：这条技能**采纳之后**已被判变差 ⇒ 先别在它身上再加东西，
        // 等人把回滚处理完。放在最前是为了「变差」优先于一切按证据算出来的建议；
        // 只影响建议与原因链，**不改置信度、不改人批判定**（88 的那套一个字没动）。
        val post = input.postAdoption
        if (post != null &&
            (post.verdict == PostAdoptionVerdict.ROLLBACK || post.verdict == PostAdoptionVerdict.RETIRE)
        ) {
            reasons += EvolutionReason(
                EvolutionReasonCode.POST_ADOPTION_NEGATIVE,
                listOf("adoption:${post.eventId}"),
            )
            return EvolutionRecommendation.DEFER
        }
        val selfCheck = input.selfCheck
        if (selfCheck == null) {
            reasons += EvolutionReason(EvolutionReasonCode.SELF_CHECK_NOT_RUN, input.evidenceIds)
            return EvolutionRecommendation.DEFER
        }
        if (!selfCheck.passed) {
            reasons += EvolutionReason(EvolutionReasonCode.SELF_CHECK_FAILED, selfCheck.evidenceIds())
            return EvolutionRecommendation.REJECT
        }
        val eval = input.eval
        if (eval == null) {
            reasons += EvolutionReason(EvolutionReasonCode.EVAL_NOT_RUN, selfCheck.caseIds)
            return EvolutionRecommendation.DEFER
        }
        if (eval.score < evalFloor) {
            reasons += EvolutionReason(EvolutionReasonCode.EVAL_BELOW_FLOOR, eval.caseIds)
            return EvolutionRecommendation.REJECT
        }
        if (!selfCheck.rollbackAvailable) {
            reasons += EvolutionReason(EvolutionReasonCode.ROLLBACK_UNAVAILABLE, selfCheck.caseIds)
            return EvolutionRecommendation.DEFER
        }
        return when (effectVerdict(input.effect)) {
            EffectVerdict.NEGATIVE -> {
                reasons += EvolutionReason(EvolutionReasonCode.EFFECT_NEGATIVE, input.effect.signalIds)
                EvolutionRecommendation.REJECT
            }

            EffectVerdict.INSUFFICIENT -> {
                reasons += EvolutionReason(EvolutionReasonCode.EFFECT_INSUFFICIENT, input.effect.signalIds)
                EvolutionRecommendation.DEFER
            }

            EffectVerdict.POSITIVE -> {
                reasons += EvolutionReason(EvolutionReasonCode.SELF_CHECK_PASSED, selfCheck.caseIds)
                reasons += EvolutionReason(EvolutionReasonCode.EVAL_PASSED, eval.caseIds)
                reasons += EvolutionReason(EvolutionReasonCode.EFFECT_POSITIVE, input.effect.signalIds)
                reasons += EvolutionReason(EvolutionReasonCode.ROLLBACK_AVAILABLE, selfCheck.caseIds)
                EvolutionRecommendation.ADOPT
            }
        }
    }

    private data class Approval(
        val requiresApproval: Boolean,
        val reasons: List<EvolutionReason>,
    )

    /**
     * 人批判定。顺序是红线：
     *   ① 高风险**先**返回 true（任何开关都不能绕过）；
     *   ② 中风险固定 true；
     *   ③ 低风险：开关关 ⇒ true；开关开但三项条件缺一 ⇒ true；
     *      三项全满足且建议为 ADOPT ⇒ false（唯一自动口）。
     */
    private fun approval(
        input: EvolutionDecisionInput,
        riskClass: EvolutionRisk,
        recommendation: EvolutionRecommendation,
    ): Approval {
        if (riskClass == EvolutionRisk.HIGH) {
            return Approval(
                requiresApproval = true,
                reasons = listOf(EvolutionReason(EvolutionReasonCode.HIGH_RISK_ALWAYS_APPROVAL)),
            )
        }
        if (riskClass == EvolutionRisk.MEDIUM) {
            return Approval(
                requiresApproval = true,
                reasons = listOf(EvolutionReason(EvolutionReasonCode.MEDIUM_RISK_HUMAN_APPROVAL)),
            )
        }
        if (!autoLowRiskEnabled) {
            return Approval(
                requiresApproval = true,
                reasons = listOf(EvolutionReason(EvolutionReasonCode.LOW_RISK_HUMAN_APPROVAL_DEFAULT)),
            )
        }
        val blocked = when {
            recommendation != EvolutionRecommendation.ADOPT ->
                EvolutionReason(EvolutionReasonCode.L3_AUTO_LOW_RISK_BLOCKED, input.evidenceIds)

            input.eval == null || input.eval.score < evalFloor ->
                EvolutionReason(EvolutionReasonCode.L3_AUTO_LOW_RISK_BLOCKED, input.eval?.caseIds.orEmpty())

            effectVerdict(input.effect) != EffectVerdict.POSITIVE ->
                EvolutionReason(EvolutionReasonCode.L3_AUTO_LOW_RISK_BLOCKED, input.effect.signalIds)

            input.selfCheck == null || !input.selfCheck.rollbackAvailable ->
                EvolutionReason(EvolutionReasonCode.L3_AUTO_LOW_RISK_BLOCKED, input.selfCheck?.caseIds.orEmpty())

            else -> null
        }
        if (blocked != null) {
            return Approval(requiresApproval = true, reasons = listOf(blocked))
        }
        return Approval(
            requiresApproval = false,
            reasons = listOf(EvolutionReason(EvolutionReasonCode.L3_AUTO_LOW_RISK_ELIGIBLE, input.evidenceIds)),
        )
    }

    /**
     * 置信度（0–1）：base 0.5。
     *   自检：过 +0.2／没跑 -0.2／失败 -0.3；
     *   eval：过线 +0.2／没跑 -0.1／未过线 -0.2；
     *   效果：正向 +0.1／负向 -0.1／不足 0；
     *   回滚可用 +0.1。
     * 只看证据强弱，不按风险等级加减——高风险不改变「证据有多硬」。
     */
    private fun confidence(input: EvolutionDecisionInput): Double {
        var score = 0.5
        val selfCheck = input.selfCheck
        when {
            selfCheck == null -> score -= 0.2
            selfCheck.passed -> score += 0.2
            else -> score -= 0.3
        }
        val eval = input.eval
        when {
            eval == null -> score -= 0.1
            eval.score >= evalFloor -> score += 0.2
            else -> score -= 0.2
        }
        when (effectVerdict(input.effect)) {
            EffectVerdict.POSITIVE -> score += 0.1
            EffectVerdict.NEGATIVE -> score -= 0.1
            EffectVerdict.INSUFFICIENT -> Unit
        }
        if (selfCheck?.rollbackAvailable == true) score += 0.1
        return score.coerceIn(0.0, 1.0)
    }

    companion object {
        /** L3 低风险自动开关（派工单 §2 原名）。默认 **false**；本批不接 UI/设置。 */
        const val L3_AUTO_LOW_RISK = false

        /** eval 过线默认值：0.8（v1 写死，可注入用于测试）。 */
        const val DEFAULT_EVAL_FLOOR = 0.8

        /** 效果样本下限：至少 3 次观察才谈正负（v1 写死，可注入用于测试）。 */
        const val DEFAULT_MIN_EFFECT_SAMPLES = 3

        /**
         * 效果判定的**唯一算法**（纯函数，不依赖实例）。
         *
         * 第一百批加的入口：采纳后效果判正负时直接调它，免得为了判定去 new 一个策略
         * （生产实例化只许留在决策口，89 的门禁钉着）。语义与 [effectVerdict] 完全一致。
         */
        fun effectVerdictOf(
            effect: EffectSnapshot,
            minSamples: Int = DEFAULT_MIN_EFFECT_SAMPLES,
        ): EffectVerdict {
            val negative = effect.failures + effect.corrections
            if (effect.successes >= minSamples && effect.successes > negative) {
                return EffectVerdict.POSITIVE
            }
            if (negative >= minSamples && negative > effect.successes) {
                return EffectVerdict.NEGATIVE
            }
            return EffectVerdict.INSUFFICIENT
        }
    }
}
