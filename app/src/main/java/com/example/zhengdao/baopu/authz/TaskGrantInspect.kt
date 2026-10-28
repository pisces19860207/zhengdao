// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：勘验**授权证据**（§7 末句「勘验看授权证据，不数卡」）。
//
// Sonus 评审点名的两个坑，都在这个文件的形状里被消掉：
// 1. **不数卡**：本文件没有任何「弹了几张卡／免了几次卡」的入口——那种数只能证明
//    「UI 弹得少了」，证明不了「这次动作凭什么放行」；
// 2. **不看源码文案**：证据取自三样**事实**——快照本身（对象清单/上限/有效期）、
//    内存状态（活没活、用了几次）、208 账（这个 opId 有没有落、落了几条）。
//
// 一条放行动作要同时满足：六步读数全过、快照合法、进程没换、没到期、账正好一条。
// 缺任何一样都报 NO_DATA——宁可说「说不清」，不许说「大概没问题」。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionRecord

/** 一条动作是**凭什么**做成的（闭集；勘验只认这四种结论）。 */
internal enum class AuthEvidence(val wire: String, val cn: String) {
    /** 任务级授权（本批的主角）。 */
    TASK_GRANT("task_grant", "任务级授权"),

    /** 用户单次点批。 */
    USER_SINGLE("user_single", "单次人批"),

    /** 既有策略放行（R0/R1 那类不需要问的）。 */
    EXISTING_POLICY("existing_policy", "既有策略放行"),

    /** 没有证据（没放行、或证据链断了）。 */
    NO_DATA("no_data", "没有证据"),
}

/** 勘验结论（[ok] ＝ 证据链完整；剩下的字段是**给人对账**的读数）。 */
internal data class AuthInspection(
    val evidence: AuthEvidence,
    /** 证据指的是哪一条（grantId／`single:<opId>`／`policy:<code>`）。 */
    val evidenceId: String,
    /** 放出这个结论的那条 208 账（没落账 ⇒ 空串）。 */
    val ledgerId: String = "",
    /** 六步里实际过掉的事实（按步序，`步=读数`；不写人话总结）。 */
    val facts: List<String> = emptyList(),
    /** 证据链断在哪（空 ＝ 没断）。 */
    val problem: String = "",
) {
    val ok: Boolean get() = evidence != AuthEvidence.NO_DATA && problem.isEmpty()
}

/** 授权证据的勘验（纯函数：只读入参，不查盘、不看 UI）。 */
internal object TaskGrantInspect {

    /**
     * 勘验一次**已放行**的动作。
     *
     * @param decision 六步的结论（非 ALLOW ⇒ 直接是 NO_DATA：没放行就没有授权证据）
     * @param state 内存里那张授权（null ⇒ 断）
     * @param records 208 里按 evidence 能查到的账（调用方给；本层不读盘）
     * @param nowMonoMs 现刻（单调钟）
     * @param processEpoch 当前进程代号
     */
    fun inspect(
        decision: GateDecision,
        state: GrantState?,
        records: List<DecisionRecord>,
        nowMonoMs: Long,
        processEpoch: String,
    ): AuthInspection {
        if (!decision.allow) {
            return AuthInspection(
                evidence = AuthEvidence.NO_DATA,
                evidenceId = "",
                problem = "这不是放行的动作（${decision.outcome.wire}／${decision.code}）",
            )
        }
        val grant = state?.grant
        if (grant == null) {
            return AuthInspection(AuthEvidence.NO_DATA, "", problem = "内存里没有这张授权")
        }
        val facts = decision.steps.map { "${it.key.wire}=${it.passed}" }
        // ① 六步必须一步不少地过（少一步＝这一次没走完放行流程）
        if (decision.steps.size != GrantStep.entries.size || decision.steps.any { it.passed != true }) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "六步没走完（${decision.passedSteps}/${GrantStep.entries.size}）",
            )
        }
        // ② 快照本身必须合法（发授时坏掉的快照不许当证据）
        val issues = TaskGrantRules.validate(grant)
        if (issues.isNotEmpty()) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "快照不合法：${issues.joinToString("、") { it.wire }}",
            )
        }
        // ③ 进程没换（重启后老快照不是证据，是历史）
        if (state.epoch != processEpoch || grant.processEpoch != processEpoch) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "进程换了（快照 ${grant.processEpoch.take(8)}／当前 ${processEpoch.take(8)}）",
            )
        }
        // ④ 没结束、没到期
        if (!state.alive) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "授权已结束（${state.endReason?.wire ?: "?"}）",
            )
        }
        if (nowMonoMs >= grant.expiresMonoMs) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "授权已到期",
            )
        }
        // ⑤ 这个 opId 的账**正好一条**（零条＝没落账，两条＝一动作两账）
        val mine = records.filter { it.evidence == decision.ledgerKey }
        if (mine.isEmpty()) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "208 里没有这条动作的账（${decision.ledgerKey}）",
            )
        }
        if (mine.size > 1) {
            return AuthInspection(
                AuthEvidence.NO_DATA,
                grant.grantId,
                facts = facts,
                problem = "一个动作落了 ${mine.size} 条账（${decision.ledgerKey}）",
            )
        }
        return AuthInspection(
            evidence = AuthEvidence.TASK_GRANT,
            evidenceId = grant.grantId,
            ledgerId = mine.single().id,
            facts = facts,
        )
    }
}
