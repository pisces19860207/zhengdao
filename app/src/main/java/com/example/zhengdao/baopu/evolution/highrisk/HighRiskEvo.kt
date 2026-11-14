// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C（2026-11-14）· 高风险两域接既有进化链的**装配缝**。
//
// 派工单原话：「两域各自接入既有链（428/473/495/517），只用既有机制，不许新建进化机制」。
// 与 568-A 的 [LowRiskEvo]／568-B 的 [com.example.zhengdao.baopu.evolution.midrisk.MidRiskEvo]
// 同一条纪律——本文件只有"转手"，没有"判定"：
//   · 准入门 ⇒ 既有 `LowRiskGate.admit`（红线→档位→深度，经 [LowRiskEvo.gate] 问档，本层不碰盘）；
//   · 红线拒账 ⇒ 既有 [LowRiskEvo.recordRefusal]（506 参谋意见账，拒并记账同一口）；
//   · 观察账＋样本池 ⇒ 既有 [LowRiskEvo.observeRun]（473 观察行＋589 样本，全仓唯一攒样本口）；
//   · 转正/回灰 ⇒ 既有 `ConsequenceWiring.decideCanaryBand`（473 梯子）＋ `EvoSampleWiring.promote`（589 样本门）；
//   · 白名单调整行 ⇒ 既有 `ConsequenceWiring.applyWhitelistChange`（208 的 `whitelist:` 族，
//     照 517 加 `depth:`／568-B 加 `threshold:` 的先例；读回＝`currentWhitelist`）。
//
// 本文件自己写死的判据只有两条，都在 [HighRiskRules]／RouteSemantics 里（纯函数）：
//   · 域①**方向**：收窄自动生效、加宽必须过 467 准入（[AdmissionGate.judge]，复用准入画像，不新造通道）；
//   · 域②**状态语义守卫**：路由输入可调，五流状态机的一字一句不许动（第二笔提交补入）。
//
// 域①生产落点（一域一处，源级门禁 `HighRiskEvoGateTest` 逐缝钉）：
//   · `agent/ToolGroupGate.kt` 的 [com.example.zhengdao.baopu.agent.ToolGroupAudit] 批准/手动/撤销三处
//     ——组授权语义（97/104 批）一字未动，只在「同意/开关/撤销」这个既有动作边上出一次白名单候选；
//   · 生效值读口 [effectiveWhitelist]：档位开着才用账上调整行，否则缺省集——
//     缺省灰度 ⇒ 接线当天生产行为逐字节不变（没调整行＝缺省集）。
//
// 边界：本文件不碰 `ToolRegistry`（632 租约）、不碰 `peer/` 五流状态机（549 租约＋判据①）、
// 不碰 `GuardPipeline`/`RunEngine`；208 账本结构零改动（只照先例加一族 evidence 前缀）。
package com.example.zhengdao.baopu.evolution.highrisk

import com.example.zhengdao.baopu.consequence.AdvisorOpinion
import com.example.zhengdao.baopu.consequence.AdvisorRules
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.consequence.WhitelistChange
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.baopu.intake.AdmissionGate
import com.example.zhengdao.baopu.policy.CapabilityProfile
import com.example.zhengdao.core.RunLog

/**
 * 域①的一次白名单变更提议（before/after 是键集；加宽时 [profile] 喂 467 准入画像）。
 *
 * [profile] 为 null 的加宽 ⇒ 准入按「空画像＝未考＝能力不足」拒（fail-closed）：
 * 没有画像就没有准入结论，没有准入结论就不许加宽——这正是「加宽必须过准入」的安全侧。
 */
internal data class WhitelistProposal(
    val before: Set<String>,
    val after: Set<String>,
    val why: String,
    val profile: CapabilityProfile? = null,
    val sessionId: Long = 0L,
    val anchor: String = "",
)

/**
 * 高风险域的装配缝（判据全在既有链上；本对象只有读账、转手、落账三个动作）。
 *
 * 域能力对照（473 登记表）：工具白名单=`TOOL_WHITELIST`（本单补登记，缺省灰度）·
 * 路由与五流=`ENVOY_ROUTE`（第二笔提交补登记）。
 */
internal object HighRiskEvo {

    /** 参谋账里"这是高风险门出的意见"的标（advisor 线值前缀；读侧按它筛拒行）。 */
    internal const val ADVISOR_GATE_PREFIX: String = "highrisk-gate:"

    /** 域①候选的目标串（指向工具白名单面；不含源码路径与账本名——红线扫描对它透明）。 */
    internal const val WHITELIST_TARGET: String = "tools:whitelist"

    /**
     * 进化层白名单的缺省集（生产候选出口 [onWhitelistEvent] 的起点）。
     *
     * 空集起步：进化层自己维护一份账-backed 白名单，组授权事件往上加（加宽过准入）、
     * 撤销事件往下减（收窄自动）。**实际工具可见性仍由既有人工通道 govern**（`ToolRegistry`，
     * 632 租约）——本层不改可见性，只把「白名单该怎么变」记成账、按档位生效，接线当天行为不变。
     */
    internal val BASELINE_WHITELIST: Set<String> = emptySet()

    /** 高风险域在 473 登记表里的能力（扫档按这张清单逐一走梯子）。 */
    internal val CAPABILITIES: List<CanaryCapability> = listOf(
        CanaryCapability.TOOL_WHITELIST,
    )

    // ── 域① 工具白名单：收窄自动生效、加宽过准入 ─────────────────────────────

    /**
     * 现在生效的白名单：账上有调整行**且档位开着** ⇒ 用行值；否则缺省集。
     * 档位关闭 ⇒ 收权（候选保留在账上，照旧用缺省集——与 568-A/568-B 同一口）。
     */
    fun effectiveWhitelist(default: Set<String>): Set<String> {
        val cap = CanaryCapability.TOOL_WHITELIST
        if (!ConsequenceWiring.installed()) return default
        if (ConsequenceWiring.currentCanaryBand(cap.wire) == CanaryBand.OFF) return default
        val raw = ConsequenceWiring.currentWhitelist(cap.wire) ?: return default
        return WhitelistRules.decode(raw)
    }

    /**
     * 域①的一次变更提议（生产调用点：[onWhitelistEvent] ← `ToolGroupAudit` 批准/手动/撤销）。
     *
     * 门序与 568-A/B 逐格相同：红线 → 档位 → 深度（[LowRiskEvo.gate]）→ **方向＋准入**。
     * 方向放在档位之后：档位关闭时提议只暂缓不判决（495 口径），档位开着才轮到方向说话；
     * 加宽未过准入 ⇒ 拒并记账（要人批的那一类）。
     *
     * @return 调整行有没有真落上（Hold/拒/账写失败/未变都返回 false；落上 true）
     */
    fun proposeWhitelistChange(p: WhitelistProposal, atMs: Long): Boolean {
        val cap = CanaryCapability.TOOL_WHITELIST
        if (!ConsequenceWiring.installed()) {
            RunLog.log("工具白名单暂缓（后果链没装配 ⇒ 不自调不写账，候选保留）")
            return false
        }
        val candidate = EvoCandidate(
            domain = cap.wire,
            target = WHITELIST_TARGET,
            body = "${WhitelistRules.encode(p.before)}→${WhitelistRules.encode(p.after)}：${p.why}",
        )
        return when (val verdict = LowRiskEvo.gate(cap, candidate)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(cap, verdict.redLine, verdict.note, candidate, atMs)
                false
            }
            is LowRiskVerdict.Hold -> {
                RunLog.log("工具白名单暂缓（${verdict.note}）")
                false
            }
            is LowRiskVerdict.Allow -> applyWhitelist(cap, p, candidate, atMs)
        }
    }

    /**
     * 决策中心回滚白名单行的活体（与 `MidRiskEvo.revertThreshold` 同款）。
     * 只认"被回滚的那行就是现在生效的那行"：盘上已换了新值 ⇒ false。回滚本身也是一次调整行。
     */
    fun revertWhitelist(toKeys: Set<String>, expectCurrent: Set<String>?, atMs: Long): Boolean {
        val cap = CanaryCapability.TOOL_WHITELIST
        if (!ConsequenceWiring.installed()) return false
        val from = ConsequenceWiring.currentWhitelist(cap.wire)?.let { WhitelistRules.decode(it) } ?: return false
        if (expectCurrent != null && from != expectCurrent) return false
        if (from == toKeys) return true
        val stamped = ConsequenceWiring.applyWhitelistChange(
            WhitelistChange(cap.wire, WhitelistRules.encode(from), WhitelistRules.encode(toKeys), atMs, "人按回滚（决策中心）"),
        )
        LowRiskEvo.observeRun(cap, ok = stamped, atMs = atMs)
        return stamped
    }

    /**
     * 组授权事件 ⇒ 一次白名单候选（生产调用点：`ToolGroupAudit.whitelistSink`，BaopuRuntime 注入）。
     *
     * 批准/手动开 ⇒ 加宽（过准入）；手动关/撤销 ⇒ 收窄（自动）。before 从生效集现读，
     * after＝before±组键。加宽不带画像 ⇒ 准入 fail-closed 拒（生产真画像归 467 接入，见报告移交）。
     *
     * @return 候选有没有落成调整行（收窄落行 true；加宽被准入拒/档位关闭 ⇒ false）
     */
    fun onWhitelistEvent(groupKey: String, widening: Boolean, atMs: Long): Boolean {
        val before = effectiveWhitelist(BASELINE_WHITELIST)
        val after = if (widening) before + groupKey else before - groupKey
        val anchor = "${WhitelistRules.encode(before)}→${WhitelistRules.encode(after)}"
        return proposeWhitelistChange(
            WhitelistProposal(
                before = before,
                after = after,
                why = "组授权事件：$groupKey ${if (widening) "加宽" else "收窄"}",
                anchor = anchor,
            ),
            atMs,
        )
    }

    /** Allow 之后的半边：方向终判 → 加宽过准入 → 落调整行 → 观察＋样本（589 同一个口）。 */
    private fun applyWhitelist(
        cap: CanaryCapability,
        p: WhitelistProposal,
        candidate: EvoCandidate,
        atMs: Long,
    ): Boolean {
        val direction = WhitelistRules.directionOf(p.before, p.after)
        if (direction == WhitelistDirection.NOOP) return false
        if (direction == WhitelistDirection.WIDEN && !wideningAdmitted(cap, p, candidate, atMs)) return false
        val stamped = ConsequenceWiring.applyWhitelistChange(
            WhitelistChange(cap.wire, WhitelistRules.encode(p.before), WhitelistRules.encode(p.after), atMs, p.why),
        )
        LowRiskEvo.observeRun(cap, ok = stamped, atMs = atMs, sessionId = p.sessionId, anchor = p.anchor)
        return stamped
    }

    /**
     * 加宽准入（复用 467 [AdmissionGate.judge]，不新造通道）：Accept ⇒ true；
     * Reject ⇒ 拒并记账（参谋意见账，与红线拒同一口）后 false。无画像 ⇒ 空画像（未考＝能力不足）⇒ 拒。
     */
    private fun wideningAdmitted(
        cap: CanaryCapability,
        p: WhitelistProposal,
        candidate: EvoCandidate,
        atMs: Long,
    ): Boolean {
        val profile = p.profile ?: CapabilityProfile.enroll(agentId = "whitelist", version = "v1", probedAtMs = atMs)
        return when (val admission = AdmissionGate.judge(profile)) {
            is AdmissionGate.Admission.Accept -> true
            is AdmissionGate.Admission.Reject -> {
                ConsequenceWiring.recordAdvisor(
                    AdvisorOpinion(
                        advisor = ADVISOR_GATE_PREFIX + cap.wire,
                        advice = AdvisorRules.ADVICE_FAIL,
                        reason = "加宽未过准入（${admission.kind.cn}）：${admission.evidence}｜${candidate.body}",
                        risks = listOf(WHITELIST_TARGET),
                        aboutOwnOutput = false,
                        atMs = atMs,
                    ),
                )
                false
            }
        }
    }

    // ── 收工扫档（生产调用点：`EvolutionWiring.onRunSettled` 一行） ──────────

    /**
     * 高风险域扫档：与低/中风险域同一个收工节拍。
     * 样本不足不许转正（升档先过 589 样本门，拒了把连续达标轮数压到线下，梯子只走回灰方向）；
     * 单域算不动不许带崩别域（runCatching 只 log）。
     */
    fun sweepOnSettle(nowMs: Long) {
        if (!ConsequenceWiring.installed()) {
            RunLog.log("高风险域扫档跳过（后果链没装配 ⇒ 无账可读、无档可动）")
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
                RunLog.log("高风险域扫档没走完（${cap.wire}）：${it.javaClass.simpleName}")
            }
        }
    }
}