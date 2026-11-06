// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九十九批（2026-10-20 · 进化闭环 v1 §1/§1.5）：**证据桥**——把已有证据凑成
// [EvolutionDecisionInput]。零新理论、零新计数：
//   效果计数 ＝ 85 的 [SkillEffects.outcomesFor]（本单之后它才有生产调用点）；
//   自检     ＝ 84 的 [SkillBodySelfCheck] 纯判据层（决策前 dry-run，只读不写）；
//   eval     ＝ 94 的发版结果文件（EvalResultFile；拿不到 ⇒ null ⇒ policy 判 DEFER）；
//   回滚     ＝ 84 的 [SkillBodySelfCheck.rollbackVia]（事前判走哪条路）；
//   风险类别 ＝ 88 的 [EvolutionRiskCategory.defaultFor]（调用方与 UI 都不另写风险表）。
//
// 两条边界（派工单 §1 原文）：
//   1. [inputFor] 是**纯函数**，不读文件——证据由调用方取好传入。本文件唯一会碰磁盘的是
//      [dryRunSelfCheck] 读目标技能的「备份份数／文件在不在」，那是 84 事前判回滚路的既有
//      口径，**只读不写**（JVM 用例钉住工作区零变化）；
//   2. 缺证据就留 null／空计数，**不许猜**：policy 会判 DEFER 并把缺什么写进原因链。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.eval.RollbackVia
import com.example.zhengdao.baopu.eval.SkillBodyInput
import com.example.zhengdao.baopu.eval.SkillBodySelfCheck
import com.example.zhengdao.baopu.eval.SkillBodyVerdict
import com.example.zhengdao.baopu.eval.EvalEvidenceSource
import com.example.zhengdao.baopu.evolution.trigger.TriggerRow
import java.io.File

/**
 * 一条候选的**证据包**（调用方现取，桥只组装）：
 *
 * @param effect [SkillEffects.outcomesFor] 的返回值原样（四键计数；没记过的技能是 0，不是「没有」）
 * @param effectSignalIds 效果证据的引用 id（候选自带的 run_log / skill_event id）
 * @param selfCheck 84 的正文自检结论（dry-run 拿到的；非技能类候选没有正文形状可检 ⇒ null）
 * @param eval 最近一次发版 eval 的结论（拿不到 ⇒ null，policy 判 DEFER，**不许默认过线**）
 */
internal data class EvolutionEvidence(
    val effect: Map<SkillOutcome, Int> = emptyMap(),
    val effectSignalIds: List<String> = emptyList(),
    val selfCheck: SelfCheckEvidence? = null,
    val eval: EvolutionEval? = null,
)

/**
 * 四键计数 ⇒ [EffectSnapshot]。
 *
 * 键不在按 0 算：没记过效果的技能不是「效果为零」，是「样本为零」——两者都通向
 * INSUFFICIENT（政策判 DEFER），但审计里看得见是哪一种。
 */
internal fun Map<SkillOutcome, Int>.toEffectSnapshot(signalIds: List<String> = emptyList()): EffectSnapshot =
    EffectSnapshot(
        successes = this[SkillOutcome.SUCCESS] ?: 0,
        failures = this[SkillOutcome.FAILURE] ?: 0,
        corrections = this[SkillOutcome.USER_CORRECTED] ?: 0,
        retries = this[SkillOutcome.RETRIED] ?: 0,
        signalIds = signalIds,
    )

/**
 * 候选 ＋ 证据 ⇒ 决策输入（派工单 §1 的桥，薄封装 88 的 [toDecisionInput]）。
 *
 * 风险类别走 [EvolutionRiskCategory.defaultFor]（保守默认：人设追加/工具规则 ⇒ 高风险，
 * 技能正文 ⇒ 中风险）。候选正文、target、createdAt 一个都不进来——决策与审计只认 id 与计数。
 */
internal fun inputFor(candidate: ProposalCandidate, evidence: EvolutionEvidence): EvolutionDecisionInput =
    toDecisionInput(candidate, evidence.toAssessment(candidate.kind))

/**
 * 证据包 ⇒ 评估输入（[inputFor] 的组装步，第五百零七批抽出来给生产装配点复用）。
 *
 * 为什么抽：99 批之后生产的证据装配在控制器里（`judgeProposalCandidates`），
 * 一直没走 [inputFor]——本批把"触发账本读数喂进 eval 槽"接上时，装配处与桥
 * 必须是**同一把尺**，所以把这一步抽成显式函数，两边共用，不许长出第二套字段映射。
 */
internal fun EvolutionEvidence.toAssessment(kind: ProposalKind): EvolutionAssessment =
    EvolutionAssessment(
        riskCategory = EvolutionRiskCategory.defaultFor(kind),
        selfCheck = selfCheck,
        eval = eval,
        effect = effect.toEffectSnapshot(effectSignalIds),
    )

/** 汇总触发账本读数时最多取多少条**有读数**的真触发行（507；与 304 的月度上限同数）。 */
internal const val TRIGGER_EVAL_MAX_ROWS = 20

/** 失败场景 id 进原因链的上限（20 行 × 13 场景的最坏情形不许整段灌进审计）。 */
internal const val TRIGGER_EVAL_MAX_CASE_IDS = 32

/**
 * 触发账本最近 N 条读数 ⇒ 一份 eval 证据（507 主件；**纯函数**，行由调用方读好传入）。
 *
 * 与 304 账本字段对齐：只收 `triggered=true` 且带读数的行（[TriggerRow.evalPassRate] 非 null
 * ——「评了但没跑出读数」的行带 [TriggerRow.evalError]，不许按 0 分冒充"全挂"），
 * 汇总四样：run（[TriggerRow.evalRan] 求和）、passed（[TriggerRow.evalPassed] 求和）、
 * passRate（passed/run）、时间窗（最新一条的 [TriggerRow.atMs] 当 ranAt，保鲜期由
 * `selectEvalEvidence` 按同一把尺判）。**没有这样的行 ⇒ null ⇒ 决策口 DEFER（UNVERIFIABLE，
 * 不得当通过）**——账本空＝从没在本机评过，不许编一个分数。
 *
 * 为什么落进 [EvolutionEval]：`EvolutionEvidence.eval` 这一格不许新增字段（507 派工单），
 * 触发读数与发版/个人生产证据同形（0–1 的通过率＋失败场景 id＋来源＋时刻），
 * 差别只在 [EvalEvidenceSource.TRIGGER] 这个来源标——卡与审计必须看得出来路。
 */
internal fun triggerEvalOf(
    rows: List<TriggerRow>,
    maxRows: Int = TRIGGER_EVAL_MAX_ROWS,
): EvolutionEval? {
    val readings = rows
        .filter { it.triggered && it.evalPassRate != null }
        .takeLast(maxRows)
    if (readings.isEmpty()) return null
    val run = readings.sumOf { it.evalRan }
    val passed = readings.sumOf { it.evalPassed }
    val score = if (run > 0) {
        passed.toDouble() / run.toDouble()
    } else {
        // run 求和为 0 而行里带 passRate：账面自洽性破了（304 的行不该长这样）——
        // 退回各行通过率的均值，宁可粗也不丢"有读数"这个事实。
        readings.mapNotNull { it.evalPassRate }.average()
    }
    val failedCases = buildList {
        for (row in readings) {
            for (id in row.evalFailedCases) {
                if (id.isNotBlank() && id !in this) add(id)
                if (size >= TRIGGER_EVAL_MAX_CASE_IDS) return@buildList
            }
        }
    }
    return EvolutionEval(
        score = score.coerceIn(0.0, 1.0),
        caseIds = failedCases,
        source = EvalEvidenceSource.TRIGGER,
        ranAt = readings.maxOf { it.atMs },
    )
}

/**
 * 候选证据 id 的统一形状（与 88 的 [toDecisionInput] 同一把尺：`run_log:N` / `skill_event:N`）。
 *
 * 抽出来是为了效果证据与候选证据用**同一串 id**——审计里「效果为什么是负的」能指回具体事件。
 */
internal fun evidenceIdsOf(candidate: ProposalCandidate): List<String> = buildList {
    candidate.evidence.runLogIds.forEach { add("run_log:$it") }
    candidate.evidence.skillEventIds.forEach { add("skill_event:$it") }
}

/**
 * dry-run 自检引用的四条判据（84 §0.1 的编号；只存 id，不存判据原文）。
 *
 * 自检「跑过」这件事本身要可追溯：通过的决策引用跑过的判据，失败的决策引用同一组
 * （84 的 verdict 只给 Ok/Bad，不逐条点名，所以失败时也引用全组而不是猜哪条）。
 */
internal val SELF_CHECK_CASE_IDS: List<String> = listOf(
    "selfcheck:structure",
    "selfcheck:required_section",
    "selfcheck:forbidden",
    "selfcheck:rollback",
)

/**
 * 决策前 **dry-run** 正文自检（派工单 §1.5.1）：拿 84 的纯判据层对候选正文试跑一次，
 * 结论当 selfCheck 证据。
 *
 * **只读不写**：「备份份数」与「文件在不在」现读（[SkillStore.backupsOf]），一个字节都不落盘、
 * 不改草稿状态。派工单说的「临时目录／试运行语义」在本批的落地就是**不碰工作区**——判据层是
 * 纯函数（字符串进、结论出），压根不需要真落临时文件；「工作区零变化」由 JVM 用例钉死。
 *
 * 为什么只有技能正文候选跑：人设追加／工具约束没有「标题段＋正文段」的形状，硬跑只会得到
 * 莫须有的「缺标题段」。那两类返回 null ⇒ policy 判 DEFER 并说明「自检未跑」——保守且有理由，
 * 不是漏做（交付报告「未做项」同款口径）。
 *
 * @param targetFile 候选目标技能在盘上的文件（项目层优先、全局层兜底；不在 ⇒ 首写）
 * @return null ＝ 这类候选不跑正文自检（调用方照常进 policy，判 DEFER）
 */
internal fun dryRunSelfCheck(candidate: ProposalCandidate, targetFile: File?): SelfCheckEvidence? {
    if (candidate.kind != ProposalKind.SKILL_BODY) return null
    val existed = targetFile?.isFile == true
    val backups = if (existed) SkillStore.backupsOf(targetFile!!).size else 0
    val verdict = SkillBodySelfCheck.check(
        SkillBodyInput(
            name = candidate.target,
            body = candidate.payload,
            backups = backups,
            existed = existed,
        ),
    )
    return SelfCheckEvidence(
        passed = verdict is SkillBodyVerdict.Ok,
        // 回滚走哪条路（84 的事前判）：有备份 ⇒ 恢复；首写 ⇒ 删除；都没有 ⇒ 没有路
        rollbackAvailable = SkillBodySelfCheck.rollbackVia(backups, existed) != RollbackVia.NONE,
        caseIds = SELF_CHECK_CASE_IDS,
    )
}
