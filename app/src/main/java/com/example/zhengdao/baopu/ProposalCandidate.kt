// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十六批（2026-10-20，§B 92 进化地基 ④）：任务收尾把**已有记录**里的可复用经验
// 变成**待决策的候选**——只生成、只落候选档，**不改技能、不改人设、不调用任何 apply/restore**。
//
// 三条边界（测试与源码门禁照它钉）：
//   1. 证据只存事件 id 与时间（run_log.id / skill_event.id），**不复制正文、原话、错误原文**；
//   2. 候选正文只进 payload（给人/给 ⑤ 决策口看的建议），不进 evidence；
//   3. 候选状态只有 PENDING；本批没有「采纳/应用」这条写盘路径。
package com.example.zhengdao.baopu

/** 候选类型（派工单 §2 原文三种）。 */
internal enum class ProposalKind {
    /** 技能正文补充（已有技能或新技能规则）。 */
    SKILL_BODY,

    /** 人设追加（B/C 层；本批只生成，不写盘）。 */
    PERSONA_APPEND,

    /** 工具使用约束（某个工具反复失败后的最小规则）。 */
    TOOL_RULE,
}

/** 候选状态。本批只产出 PENDING；APPLIED/REJECTED 留给 ⑤ 决策口。 */
internal enum class ProposalStatus { PENDING }

/**
 * 候选的证据引用：**只有事件 id 与时间**。
 *
 * 为什么只有两类 id：现有可引用的稳定事件账本只有 `run_log`（工具步）与
 * `skill_event`（技能使用/采纳丢弃）；SkillAudit 的 JSONL 没有稳定 id，
 * 本批不把它硬凑成证据（见交付报告「未做项」）。
 *
 * ⚠️ 不许往这里加 String 字段（原话/错误原文/正文）——那会把证据变成正文副本，
 * 判据「证据只存 id/时间」由 `ProposalCandidateWiringTest` 的源码门禁钉住。
 */
internal data class ProposalEvidence(
    val runLogIds: List<Long> = emptyList(),
    val skillEventIds: List<Long> = emptyList(),
    val atMs: Long = 0L,
)

/**
 * 一条候选提案。
 *
 * @param id 稳定 hash＝`SkillRefine.dedupeKey(target, payload)`（16 位 hex）。
 *   与第五十七批「采纳/丢弃留痕」同一把尺：学习卡候选的 id 恰好等于
 *   [SkillRefine.dedupeKeyOf]，所以 `readRejectedDecisionHashes()` 里的旧账能直接挡住它。
 * @param target 技能名 / 人设层（`人设`）/ 工具名。
 * @param payload 建议正文（**唯一允许出现自然语言的地方**；可能来自学习卡 content）。
 * @param evidence 事件 id 与时间，不含正文。
 * @param status 本批恒为 PENDING。
 * @param createdAt 生成时刻（毫秒）。
 */
internal data class ProposalCandidate(
    val id: String,
    val kind: ProposalKind,
    val target: String,
    val payload: String,
    val evidence: ProposalEvidence = ProposalEvidence(),
    val status: ProposalStatus = ProposalStatus.PENDING,
    val createdAt: Long,
)
