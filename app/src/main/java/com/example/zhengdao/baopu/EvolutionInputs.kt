// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
    toDecisionInput(
        candidate,
        EvolutionAssessment(
            riskCategory = EvolutionRiskCategory.defaultFor(candidate.kind),
            selfCheck = evidence.selfCheck,
            eval = evidence.eval,
            effect = evidence.effect.toEffectSnapshot(evidence.effectSignalIds),
        ),
    )

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
