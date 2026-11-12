// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 B（2026-11-12）· 中风险三域接既有进化链的**装配缝**。
//
// 派工单原话：「三域各自接入既有灰度链（428/473/495/517），不许新建进化机制」。
// 与 568-A 的 [LowRiskEvo] 同一条纪律——本文件只有"转手"，没有"判定"：
//   · 准入门 ⇒ 既有 `LowRiskGate.admit`（红线→档位→深度，三格全来自既有链，经
//     [LowRiskEvo.gate] 问档，本层不碰盘）；
//   · 红线拒账 ⇒ 既有 [LowRiskEvo.recordRefusal]（506 的参谋意见账，拒并记账同一口）；
//   · 观察账 ⇒ 既有 `ConsequenceWiring.observe`（473 观察行，指标的唯一来源）；
//   · 样本池 ⇒ 既有 `EvoSampleWiring.recordObservation`（589；经 [LowRiskEvo.observeRun]
//     这一个口，判据⑧的全仓唯一攒样本口不动）；
//   · 转正/回灰 ⇒ 既有 `ConsequenceWiring.decideCanaryBand`（473 梯子）；
//   · **样本不足不许转正** ⇒ 既有 `EvoSampleWiring.promote`（589 的样本门，拒并记账）；
//   · 阈值调整行 ⇒ 既有 `ConsequenceWiring.applyThresholdChange`（208 的
//     `threshold:` 族，与 517 加 `depth:` 前缀同一扩法；读回＝`currentThreshold`）。
//
// 本文件自己写死的判据只有一条：**区间闭集**（域①）——已定区间 1–10 是 122 拍板
// （`Policy.GROUP_REASK_MIN/MAX`，与 ApprovalPolicy 的 require、PolicyTable 的读表器
// 同一把尺），本层只是引用它，不另立第二个区间。出区间 ⇒ 拒并记账（要人批的那一类，
// 归 568-C 的人工通道）。
//
// 域①生产落点（一域一处，源级门禁 `MidRiskEvoGateTest` 逐缝钉）：
//   · `agent/ToolGroupGate.kt` 的未批准/无人应答/同意三处——组闸限流语义（97 批）一字未动，
//     只在"要不要再等几次才弹卡"这个既有动作边上问一次门；
//   · 生效值读口 `effectiveReaskThreshold`：档位开着才用账上调整行，否则政策表原值——
//     缺省灰度 ⇒ 接线当天生产行为逐字节不变（没调整行＝原值）。
//
// 边界：本文件不碰 550 的判档逻辑、不碰 482 的收尾判定、不碰 589 的样本口径、不碰
// `GuardPipeline`/`RunEngine`；208 账本结构零改动（只照 `depth:` 先例加一族 evidence 前缀）。
package com.example.zhengdao.baopu.evolution.midrisk

import com.example.zhengdao.baopu.CompanionDecision
import com.example.zhengdao.baopu.consequence.AdvisorOpinion
import com.example.zhengdao.baopu.consequence.AdvisorRules
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.consequence.ThresholdChange
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.baopu.plan.GoalRules
import com.example.zhengdao.baopu.policy.Policy
import com.example.zhengdao.core.RunLog

/** 域①的一次自调提议（`base±delta`；会话与轮次锚由调用方给——589 拍板：对话域锚＝轮次）。 */
internal data class ThresholdProposal(
    val base: Int,
    val delta: Int,
    val why: String,
    val sessionId: Long = 0L,
    val turnAnchor: String = "",
)

/**
 * 中风险域的装配缝（判据全在既有链上；本对象只有读账、转手、落账三个动作）。
 *
 * 域能力对照（473 登记表）：判定阈值=`JUDGMENT_THRESHOLD`（本单补登记，缺省灰度）。
 */
internal object MidRiskEvo {

    /** 参谋账里"这是中风险门出的意见"的标（advisor 线值前缀；读侧按它筛拒行）。 */
    internal const val ADVISOR_GATE_PREFIX: String = "midrisk-gate:"

    /** 域①候选的目标串（红线扫描、拒账 risks、体检对账都认它；指向政策表字段，不指向源码）。 */
    internal const val THRESHOLD_TARGET: String = "policy:approval.groupReaskThreshold"

    /** 中风险域在 473 登记表里的能力（扫档按这张清单逐一走梯子）。 */
    internal val CAPABILITIES: List<CanaryCapability> = listOf(
        CanaryCapability.JUDGMENT_THRESHOLD,
        CanaryCapability.COMPANION_RULE,
        CanaryCapability.GOAL_TEMPLATE,
    )

    // ── 域① 判定阈值：区间内自调 ─────────────────────────────────────────

    /**
     * 区间判定（**纯函数**，本单唯一自写判据）：提议值落在 122 拍板的已定区间内 ⇒ null；
     * 出区间 ⇒ 拒因（这一句就是"要人批"的读数，拒并记账）。
     */
    internal fun intervalVerdict(proposed: Int): String? =
        if (proposed in Policy.GROUP_REASK_MIN..Policy.GROUP_REASK_MAX) {
            null
        } else {
            "提议值 $proposed 超出已定区间 ${Policy.GROUP_REASK_MIN}–${Policy.GROUP_REASK_MAX}" +
                "（阈值不许越已定区间；出区间要人批，候选不执行）"
        }

    /**
     * 现在生效的组闸重问阈值：账上有调整行**且档位开着** ⇒ 用行值；否则政策表原值。
     *
     * 档位关闭 ⇒ 收权（候选保留在账上，闸照旧用原值——「档位关闭才收权」与 568-A 同一口）。
     */
    fun effectiveReaskThreshold(policyValue: Int): Int {
        val cap = CanaryCapability.JUDGMENT_THRESHOLD
        if (!ConsequenceWiring.installed()) return policyValue
        if (ConsequenceWiring.currentCanaryBand(cap.wire) == CanaryBand.OFF) return policyValue
        return ConsequenceWiring.currentThreshold(cap.wire) ?: policyValue
    }

    /**
     * 域①的一次自调提议（生产调用点：`ToolGroupGate` 的未批准/无人应答 ⇒ +1、同意 ⇒ −1）。
     *
     * 门序与 568-A 逐格相同：红线 → 档位 → 深度（[LowRiskEvo.gate]）→ **区间**。
     * 区间放在档位之后：档位关闭时提议只暂缓不判决（495 口径——没资格被判决的事不许记成失败），
     * 档位开着才轮到区间说话；出区间 ⇒ 拒并记账（要人批）。
     *
     * @return 调整行有没有真落上（Hold/拒/账写失败都返回 false；落上 true）
     */
    fun proposeThresholdShift(p: ThresholdProposal, atMs: Long): Boolean {
        val cap = CanaryCapability.JUDGMENT_THRESHOLD
        if (!ConsequenceWiring.installed()) {
            RunLog.log("判定阈值暂缓（后果链没装配 ⇒ 不自调不写账，候选保留）")
            return false
        }
        val proposed = p.base + p.delta
        val candidate = EvoCandidate(
            domain = cap.wire,
            target = THRESHOLD_TARGET,
            body = "${p.base}→$proposed：${p.why}",
        )
        return when (val verdict = LowRiskEvo.gate(cap, candidate)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(cap, verdict.redLine, verdict.note, candidate, atMs)
                false
            }
            is LowRiskVerdict.Hold -> {
                RunLog.log("判定阈值暂缓（${verdict.note}）")
                false
            }
            is LowRiskVerdict.Allow -> applyThreshold(cap, p, proposed, candidate, atMs)
        }
    }

    /**
     * 决策中心回滚阈值行的活体（`BaopuChatController` 回滚 when 的 threshold 支）。
     *
     * 只认"被回滚的那行就是现在生效的那行"：盘上已经换了新值 ⇒ 返回 false（老实话，
     * `DecisionRollbacks.run` 对 false 不留 `rollback_of`）。回滚本身也是一次调整行
     * （`before`→`after` 照写），闸下一问就用回退值——没有第二处真相。
     */
    fun revertThreshold(toValue: Int, expectCurrent: Int?, atMs: Long): Boolean {
        val cap = CanaryCapability.JUDGMENT_THRESHOLD
        if (!ConsequenceWiring.installed()) return false
        val from = ConsequenceWiring.currentThreshold(cap.wire) ?: return false
        if (expectCurrent != null && from != expectCurrent) return false
        if (from == toValue) return true
        val stamped = ConsequenceWiring.applyThresholdChange(
            ThresholdChange(cap.wire, from, toValue, atMs, "人按回滚（决策中心）"),
        )
        LowRiskEvo.observeRun(cap, ok = stamped, atMs = atMs)
        return stamped
    }

    /** Allow 之后的半边：区间终判 → 落调整行 → 观察＋样本（589 同一个口）。 */
    private fun applyThreshold(
        cap: CanaryCapability,
        p: ThresholdProposal,
        proposed: Int,
        candidate: EvoCandidate,
        atMs: Long,
    ): Boolean {
        val outOfInterval = intervalVerdict(proposed)
        if (outOfInterval != null) {
            ConsequenceWiring.recordAdvisor(
                AdvisorOpinion(
                    advisor = ADVISOR_GATE_PREFIX + cap.wire,
                    advice = AdvisorRules.ADVICE_FAIL,
                    reason = "$outOfInterval：${candidate.body}",
                    risks = listOf(THRESHOLD_TARGET),
                    aboutOwnOutput = false,
                    atMs = atMs,
                ),
            )
            return false
        }
        val stamped = ConsequenceWiring.applyThresholdChange(
            ThresholdChange(cap.wire, p.base, proposed, atMs, p.why),
        )
        LowRiskEvo.observeRun(
            cap,
            ok = stamped,
            atMs = atMs,
            sessionId = p.sessionId,
            anchor = p.turnAnchor,
        )
        return stamped
    }

    // ── 域② 相处规则候选（来自 550 的误判纠正，只出候选） ────────────────────

    /** 域②候选的目标串（指向相处模式的判档面；不含任何源码路径与账本名——红线扫描对它透明）。 */
    internal const val COMPANION_RULE_TARGET: String = "companion:mode-rules"

    /**
     * 一次误判纠正 ⇒ 一条相处规则候选（生产调用点：`MediatorWiring.onUserTurn` 的
     * 候选出口——550 的判档逻辑一字未动，这里只接"已经判完的纠正"）。
     *
     * 候选只出**不启用**：正文落一行 RunLog（路线②，550/482 的同一形态）＋观察＋样本；
     * 未转正不参与判档，**转正也不参与**——候选回流判档词表没有路（`MediatorWiring` 的
     * 出口只搬运结论；`CompanionJudge` 的词表是判定常量）。灰度链管的是这一域的
     * "产不产、记不记、升不升档"，不管判档本身。
     *
     * 会话号/轮次锚由调用方给（589 拍板①：对话域锚＝轮次）；没有会话（-1）⇒ 空，
     * 样本按时刻自记身份，不硬造会话号。
     *
     * @return 候选出没出成（档位关闭/红线拒不产出；观察照常不落——Hold/拒不算执行）
     */
    fun onCompanionCorrection(
        prev: CompanionDecision,
        now: CompanionDecision,
        turns: Int,
        sessionId: Long,
        atMs: Long,
    ): Boolean {
        val cap = CanaryCapability.COMPANION_RULE
        val candidate = EvoCandidate(
            domain = cap.wire,
            target = COMPANION_RULE_TARGET,
            body = "规则候选（只产不启用）：${prev.mode.cn}→${now.mode.cn} 的纠正样本" +
                "——第 $turns 轮用户点名切档（依据：${now.reason}）⇒ 建议「${now.mode.cn}档显式信号直判」进候选区",
        )
        return when (val verdict = LowRiskEvo.gate(cap, candidate)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(cap, verdict.redLine, verdict.note, candidate, atMs)
                false
            }
            is LowRiskVerdict.Hold -> {
                RunLog.log("相处规则候选暂缓（${verdict.note}）")
                false
            }
            is LowRiskVerdict.Allow -> {
                RunLog.log(
                    "相处规则候选（只产不启用）：第 $turns 轮 ${prev.mode.cn}→${now.mode.cn}" +
                        "｜依据 ${now.reason}｜session=$sessionId",
                )
                LowRiskEvo.observeRun(
                    cap,
                    ok = true,
                    atMs = atMs,
                    sessionId = if (sessionId >= 0L) sessionId else 0L,
                    anchor = if (sessionId >= 0L) "turn:$sessionId:$turns" else "",
                )
                true
            }
        }
    }

    // ── 域③ 目标模板候选（来自 482 的目标收尾，只出候选） ────────────────────

    /** 域③候选的目标串（指向目标模式的模板面；不含源码路径——红线扫描对它透明）。 */
    internal const val GOAL_TEMPLATE_TARGET: String = "goal:template-candidates"

    /**
     * 一次目标真收口的模板候选（生产调用点：`GoalBoard.settle` 的候选出口——482 的
     * 提炼逻辑一字未动，这里只接"已经提炼完的候选"）。
     *
     * 候选只出**不启用**：482 的口径（不落库、不启用、不当默认值）原样保持；这一域走的是
     * "记不记、升不升档"——观察＋样本照落（589 拍板②：后台域锚＝1 小时桶，一小时连收
     * 几次＝同一次条件成熟，只计 1；样本行的 eventId 带来源 goal id，追得回出处）。
     *
     * @return 候选记没记上（档位关闭 ⇒ 暂缓不记；红线拒 ⇒ 记账不记；灰度/全开 ⇒ 记）
     */
    fun onGoalTemplate(candidate: GoalRules.TemplateCandidate, atMs: Long): Boolean {
        val cap = CanaryCapability.GOAL_TEMPLATE
        val evo = EvoCandidate(
            domain = cap.wire,
            target = GOAL_TEMPLATE_TARGET,
            body = "${candidate.name}｜判据写法 ${candidate.criteriaPattern.size} 条｜来源 goal:${candidate.sourceGoalId}",
        )
        return when (val verdict = LowRiskEvo.gate(cap, evo)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(cap, verdict.redLine, verdict.note, evo, atMs)
                false
            }
            is LowRiskVerdict.Hold -> {
                RunLog.log("目标模板候选暂缓（${verdict.note}）")
                false
            }
            is LowRiskVerdict.Allow -> LowRiskEvo.observeRun(
                cap,
                ok = true,
                atMs = atMs,
                eventId = "goal:${candidate.sourceGoalId}",
            )
        }
    }

    // ── 收工扫档（生产调用点：`EvolutionWiring.onRunSettled` 一行） ──────────

    /**
     * 中风险域扫档：与低风险四域同一个收工节拍。
     *
     * 判据④落在这里：**样本不足不许转正**——升档方向先过 589 的样本门（[EvoSampleWiring.promote]，
     * 拒并记账），拒了就把"连续达标轮数"压到线下，梯子只走回灰方向；回灰是安全动作，
     * 不被样本门挡（495 的"没数不动档"照旧：指标算不出 ⇒ 梯子原档不动）。
     * 单域算不动不许带崩别域（runCatching 只 log）。
     */
    fun sweepOnSettle(nowMs: Long) {
        if (!ConsequenceWiring.installed()) {
            RunLog.log("中风险域扫档跳过（后果链没装配 ⇒ 无账可读、无档可动）")
            return
        }
        CAPABILITIES.forEach { cap ->
            runCatching {
                var rounds = LowRiskEvo.passedRoundsOf(cap.wire, nowMs)
                if (rounds >= CanaryLadder.PASS_ROUNDS_FOR_FULL &&
                    !EvoSampleWiring.promote(cap.wire, nowMs).allowed
                ) {
                    rounds = CanaryLadder.PASS_ROUNDS_FOR_FULL - 1
                }
                ConsequenceWiring.decideCanaryBand(cap.wire, nowMs, rounds)
            }.onFailure {
                RunLog.log("中风险域扫档没走完（${cap.wire}）：${it.javaClass.simpleName}")
            }
        }
    }
}
