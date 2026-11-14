// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C（2026-11-14）· 域②路由与使臣五流的**装配缝**（第二笔提交）。
//
// 派工单 §一⑧：「549 的状态机；路由可调，状态语义不可改」。与域① [HighRiskEvo] 同一条纪律
// ——本文件只有"转手"，没有"判定"：
//   · 准入门 ⇒ 既有 `LowRiskGate.admit`（红线→档位→深度，经 [LowRiskEvo.gate] 问档，本层不碰盘）；
//   · 红线拒账 ⇒ 既有 [LowRiskEvo.recordRefusal]（506 参谋意见账，拒并记账同一口）；
//   · 观察账＋样本池 ⇒ 既有 [LowRiskEvo.observeRun]（473 观察行＋589 样本，全仓唯一攒样本口）；
//   · 转正/回灰 ⇒ 既有 `ConsequenceWiring.decideCanaryBand`（473 梯子）＋ `EvoSampleWiring.promote`（589 样本门）；
//   · 路由调整行 ⇒ 既有 `ConsequenceWiring.applyRouteChange`（208 的 `route:` 族，
//     照域① `whitelist:`／568-B `threshold:`／517 `depth:` 的先例；读回＝`currentRoute`）。
//
// 本文件自己写死的判据只有一条：**冲突时以状态机为准**——[RouteSemantics.flowLegal] 就是 549 的
// `EnvoyLaw.legal`，本层一字不另判。路由输入（选哪个 peer、走哪条路）可调；五流状态机的一字一句
// 不许动（硬要求①：只调路由输入，不改 549 的状态判定）。候选假设了一条非法迁移（如终态翻案）
// ⇒ 状态机赢，候选拒并记账。
//
// 域②生产落点（一域一处，源级门禁 `HighRiskRouteEvoGateTest` 逐缝钉）：
//   · `intake/AdmissionWiring.kt` 的 routeSink（新 peer 准入 ⇒ 一次路由偏好候选）——
//     546 的准入判定一字未动，只在「登记完」这个既有动作边上出一次候选；
//   · 生效值读口 [effectiveRoute]：档位开着才用账上调整行，否则缺省路由——
//     缺省灰度 ⇒ 接线当天生产行为逐字节不变（没调整行＝缺省）。
//
// 边界：本文件不碰 `peer/` 五流状态机（549 租约＋硬要求①）、不碰 `EnvoyDeliver` 的 endpoint
// 消费（549 租约——实际路由消费归接线移交，见报告）、不碰 `GuardPipeline`/`RunEngine`；
// 208 账本结构零改动（只照先例加一族 evidence 前缀）。
package com.example.zhengdao.baopu.evolution.highrisk

import com.example.zhengdao.baopu.consequence.AdvisorOpinion
import com.example.zhengdao.baopu.consequence.AdvisorRules
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.consequence.RouteChange
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.baopu.peer.A2aTaskState
import com.example.zhengdao.core.RunLog

/**
 * 域②的一次路由变更提议（before/after 是路由输入）。
 *
 * [assumedFrom]/[assumedTo] 是候选**假设的收场迁移**：给了就过状态机守卫（[RouteSemantics.flowLegal]），
 * 非法 ⇒ 拒（冲突时以状态机为准）；不给＝纯路由偏好，不假设任何状态迁移，状态机无从冲突。
 * 路由自调只挪 [RouteInput] 的两个值，状态词表/终态集/迁移法条全归 549，本提议碰都不碰。
 */
internal data class RouteProposal(
    val before: RouteInput?,
    val after: RouteInput,
    val why: String,
    val assumedFrom: A2aTaskState? = null,
    val assumedTo: A2aTaskState? = null,
    val sessionId: Long = 0L,
    val anchor: String = "",
)

/**
 * 域②路由的装配缝（判据全在既有链上；本对象只有读账、转手、落账三个动作）。
 *
 * 域能力对照（473 登记表）：路由与五流=`ENVOY_ROUTE`（本单补登记，缺省灰度）。
 */
internal object RouteEvo {

    /** 参谋账里"这是高风险门出的意见"的标（与域①同前缀，按 cap.wire 区分两域）。 */
    internal const val ADVISOR_GATE_PREFIX: String = "highrisk-gate:"

    /** 域②候选的目标串（指向路由面；不含源码路径与账本名——红线扫描对它透明）。 */
    internal const val ROUTE_TARGET: String = "envoy:route"

    /**
     * 进化层路由的缺省（无偏好）：生产候选出口 [onPeerAdmitted] 的起点。
     *
     * null 起步：进化层自己维护一份账-backed 路由偏好，新 peer 准入往上指（纯偏好、不假设状态迁移）。
     * **实际派活走哪条路仍由既有通道 govern**（`EnvoyDeliver` 的 endpoint，549 租约）——本层不改
     * 状态判定，只把「路由偏好该怎么变」记成账、按档位生效，接线当天行为不变。
     */
    internal val BASELINE_ROUTE: RouteInput? = null

    /** 域②在 473 登记表里的能力（扫档按这张清单走梯子）。 */
    internal val CAPABILITIES: List<CanaryCapability> = listOf(
        CanaryCapability.ENVOY_ROUTE,
    )

    // ── 域② 路由：输入可调、状态语义不可改、冲突以状态机为准 ─────────────────

    /**
     * 现在生效的路由：账上有调整行**且档位开着** ⇒ 用行值；否则缺省。
     * 档位关闭 ⇒ 收权（候选保留在账上，照旧用缺省——与域①/568-A/B 同一口）。
     */
    fun effectiveRoute(default: RouteInput?): RouteInput? {
        val cap = CanaryCapability.ENVOY_ROUTE
        if (!ConsequenceWiring.installed()) return default
        if (ConsequenceWiring.currentCanaryBand(cap.wire) == CanaryBand.OFF) return default
        val raw = ConsequenceWiring.currentRoute(cap.wire) ?: return default
        return RouteSemantics.decode(raw) ?: default
    }

    /**
     * 域②的一次变更提议（生产调用点：[onPeerAdmitted] ← `AdmissionWiring.routeSink`）。
     *
     * 门序与域①逐格相同：红线 → 档位 → 深度（[LowRiskEvo.gate]）→ **状态机守卫**。
     * 守卫放在档位之后：档位关闭时提议只暂缓不判决（495 口径），档位开着才轮到状态机说话。
     *
     * @return 调整行有没有真落上（Hold/拒/冲突/账写失败/未变都返回 false；落上 true）
     */
    fun proposeRouteChange(p: RouteProposal, atMs: Long): Boolean {
        val cap = CanaryCapability.ENVOY_ROUTE
        if (!ConsequenceWiring.installed()) {
            RunLog.log("路由暂缓（后果链没装配 ⇒ 不自调不写账，候选保留）")
            return false
        }
        val candidate = EvoCandidate(
            domain = cap.wire,
            target = ROUTE_TARGET,
            body = "${p.before?.let { RouteSemantics.encode(it) } ?: "（缺省）"}→${RouteSemantics.encode(p.after)}：${p.why}",
        )
        return when (val verdict = LowRiskEvo.gate(cap, candidate)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(cap, verdict.redLine, verdict.note, candidate, atMs)
                false
            }
            is LowRiskVerdict.Hold -> {
                RunLog.log("路由暂缓（${verdict.note}）")
                false
            }
            is LowRiskVerdict.Allow -> applyRoute(cap, p, candidate, atMs)
        }
    }

    /**
     * 决策中心回滚路由行的活体（与域① [HighRiskEvo.revertWhitelist] 同款）。
     * 只认"被回滚的那行就是现在生效的那行"：盘上已换了新值 ⇒ false。回滚本身也是一次调整行。
     */
    fun revertRoute(to: RouteInput?, expectCurrent: RouteInput?, atMs: Long): Boolean {
        val cap = CanaryCapability.ENVOY_ROUTE
        if (!ConsequenceWiring.installed()) return false
        val fromRaw = ConsequenceWiring.currentRoute(cap.wire) ?: return false
        val from = RouteSemantics.decode(fromRaw)
        if (expectCurrent != null && from != expectCurrent) return false
        if (from == to) return true
        val stamped = ConsequenceWiring.applyRouteChange(
            RouteChange(cap.wire, fromRaw, to?.let { RouteSemantics.encode(it) } ?: "", atMs, "人按回滚（决策中心）"),
        )
        LowRiskEvo.observeRun(cap, ok = stamped, atMs = atMs)
        return stamped
    }

    /**
     * 新 peer 准入 ⇒ 一次路由偏好候选（生产调用点：`AdmissionWiring.routeSink`，BaopuRuntime 注入）。
     *
     * before 从生效路由现读，after＝偏好指向新准入的 peer（路径标沿用）。**纯偏好、不假设状态迁移**
     * （assumedTo 留空）——选哪个 peer 是路由输入，不碰五流状态机。措辞避开红线词（不指认源码/内核）。
     *
     * @return 候选有没有落成调整行（档位关闭/红线拒 ⇒ false；灰度/全开 ⇒ 落行 true）
     */
    fun onPeerAdmitted(agentId: String, atMs: Long): Boolean {
        val before = effectiveRoute(BASELINE_ROUTE)
        val after = RouteInput(preferredPeer = agentId, path = before?.path ?: "")
        val anchor = "${before?.let { RouteSemantics.encode(it) } ?: ""}→${RouteSemantics.encode(after)}"
        return proposeRouteChange(
            RouteProposal(
                before = before,
                after = after,
                why = "新 peer 准入：$agentId 可用 ⇒ 路由偏好指向它",
                anchor = anchor,
            ),
            atMs,
        )
    }

    /** Allow 之后的半边：状态机守卫 → 落调整行 → 观察＋样本（589 同一个口）。 */
    private fun applyRoute(
        cap: CanaryCapability,
        p: RouteProposal,
        candidate: EvoCandidate,
        atMs: Long,
    ): Boolean {
        if (conflictsWithStateMachine(cap, p, candidate, atMs)) return false
        val beforeRaw = p.before?.let { RouteSemantics.encode(it) } ?: ""
        val afterRaw = RouteSemantics.encode(p.after)
        if (beforeRaw == afterRaw) return false
        val stamped = ConsequenceWiring.applyRouteChange(RouteChange(cap.wire, beforeRaw, afterRaw, atMs, p.why))
        LowRiskEvo.observeRun(cap, ok = stamped, atMs = atMs, sessionId = p.sessionId, anchor = p.anchor)
        return stamped
    }

    /**
     * 冲突时以状态机为准：候选假设了收场迁移（[RouteProposal.assumedTo] 非空）且
     * [RouteSemantics.flowLegal]（＝549 `EnvoyLaw.legal`）判它非法 ⇒ 拒并记账（参谋意见账）。
     * 没假设迁移 ⇒ 不冲突（纯路由偏好不碰状态机）。本层一字不另判状态合法性——549 说了算。
     */
    private fun conflictsWithStateMachine(
        cap: CanaryCapability,
        p: RouteProposal,
        candidate: EvoCandidate,
        atMs: Long,
    ): Boolean {
        val to = p.assumedTo ?: return false
        if (RouteSemantics.flowLegal(p.assumedFrom, to)) return false
        ConsequenceWiring.recordAdvisor(
            AdvisorOpinion(
                advisor = ADVISOR_GATE_PREFIX + cap.wire,
                advice = AdvisorRules.ADVICE_FAIL,
                reason = "路由候选与五流状态机冲突（${p.assumedFrom?.wire ?: "起点"}→${to.wire} 非法）：" +
                    "状态语义不可改，以状态机为准｜${candidate.body}",
                risks = listOf(ROUTE_TARGET),
                aboutOwnOutput = false,
                atMs = atMs,
            ),
        )
        return true
    }

    // ── 收工扫档（生产调用点：`EvolutionWiring.onRunSettled` 一行） ──────────

    /**
     * 域②扫档：与域①/低/中风险域同一个收工节拍。
     * 样本不足不许转正（升档先过 589 样本门，拒了把连续达标轮数压到线下，梯子只走回灰方向）；
     * 单域算不动不许带崩别域（runCatching 只 log）。
     */
    fun sweepOnSettle(nowMs: Long) {
        if (!ConsequenceWiring.installed()) {
            RunLog.log("路由域扫档跳过（后果链没装配 ⇒ 无账可读、无档可动）")
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
                RunLog.log("路由域扫档没走完（${cap.wire}）：${it.javaClass.simpleName}")
            }
        }
    }
}