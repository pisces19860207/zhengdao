// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约的**管线接缝**（`GuardPipeline` 只认这个接口）。
//
// 为什么要有这一层（而不是让管线直接摸 `LeaseStore`）：照 `contracts`/`grantWiring`/
// `askTier`/`supervision` 同一条老规矩——管线是执行层，"偷偷读世界"就没法在闸上直接测；
// 负例（过期/用尽/越界/会话不等）全靠从参数喂假件才抓得住。
//
// 三个口，各一行调用：
//   · [onRiskCardBeforeAsk]：第 ③ 段末尾、出卡之前——**位置是判据**（命中即免卡）；
//   · [onRejected]：被某道闸拒的那一笔——按闸名归因记越权（不是按文案猜）；
//   · [onFinished]：`finish` 收口——把判定点扣掉的次数落成一行账（带真实结果）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.agent.AskGovLedger
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.escalate.CapabilityKind
import java.io.File
import org.json.JSONObject

/** 判定一次要的全部事实（管线采集，判定层不取世界）。 */
internal data class LeaseAskRequest(
    val sessionId: Long,
    val call: ToolCall,
    val spec: ToolSpec,
    val risk: RiskClass?,
    val unclassified: Boolean,
    val mustAsk: Boolean,
    val inRoot: Boolean,
    val reversible: Boolean,
    val targets: List<File>,
    val args: JSONObject,
    val root: File?,
    val nowMs: Long,
    /** 任一写目标落在原件区（采集在管线侧：它才知道原件区在哪）。 */
    val targetsInRawZone: Boolean,
)

/** 一次判定的结论：命中（免卡）／未命中（附可摆的报价）。 */
internal sealed interface LeaseAskOutcome {
    data class Hit(val leaseId: String, val remaining: Int) : LeaseAskOutcome
    data class Miss(val reason: LeaseMissReason, val offer: LeaseOffer?) : LeaseAskOutcome
}

internal interface LeaseGate {

    /** 第 ③ 段末尾：命中 ⇒ 免卡；未命中 ⇒ 原样弹卡（[LeaseAskOutcome.Miss.offer] 摆卡上那一句）。 */
    fun onRiskCardBeforeAsk(req: LeaseAskRequest): LeaseAskOutcome

    /**
     * 被某道闸拒掉的那一笔：按闸名归因记一次越权（只有满档才动账本）。
     *
     * 三个调用点就是三道闸：`path`（② 段红线）／`risk_matrix`／`task_grant`。
     * 别处的拒（未知工具、停机、停用组）**一个都不算**——那些不是"越权"，猜着记会误降权。
     */
    fun onRejected(spec: ToolSpec, sessionId: Long, call: ToolCall, gate: String)

    /** `finish` 收口：租约放行的这笔落"用一次"的账（没放行过 ⇒ 静默）。 */
    fun onFinished(call: ToolCall, outcome: ToolOutcome)

    companion object {
        /** 生产实现（缺省就是这个：存量调用点一个字不改）。 */
        val LIVE: LeaseGate = LiveLeaseGate
    }
}

/**
 * 生产实现。
 *
 * 它自己不算任何判据：类目/拒授/范围/计次全在 [LeaseRules]，账在 [LeaseStore]，
 * 这里只做三件事——拼事实、转结论、把降权镜像进**治理账本**（定点 D10）。
 */
internal object LiveLeaseGate : LeaseGate {

    /** 治理账本里租约这一路的 `who`（`打扰治理.jsonl` 的 who 是自由串，profile/intake 同款）。 */
    const val WHO_LEASE = "lease"

    override fun onRiskCardBeforeAsk(req: LeaseAskRequest): LeaseAskOutcome {
        LeaseLedger.rememberRoot(req.root)
        val facts = factsOf(req)
        val offer = LeaseRules.offerOf(facts)
        // 不可授 ⇒ 连"命中"都不该发生（表里不该有这类租约），但历史账里可能有 ⇒ 仍走一遍命中，
        // 命中即放行会让"先授后改判"偷跑；所以这里先看 offer：null 就直接当未命中。
        if (offer == null) return LeaseAskOutcome.Miss(LeaseMissReason.NOT_GRANTED, null)
        return when (
            val v = LeaseStore.hit(
                sessionId = req.sessionId,
                cls = facts.cls,
                targets = req.targets,
                args = req.args,
                cost = LeaseRules.costOf(req.targets),
                opId = req.call.id,
                nowMs = req.nowMs,
            )
        ) {
            is LeaseVerdict.Hit -> LeaseAskOutcome.Hit(v.leaseId, v.remaining)
            is LeaseVerdict.Miss -> LeaseAskOutcome.Miss(v.reason, offer)
        }
    }

    override fun onRejected(spec: ToolSpec, sessionId: Long, call: ToolCall, gate: String) {
        val affected = LeaseStore.noteRejected(
            sessionId = sessionId,
            cls = LeaseRules.classOf(spec),
            gate = gate,
            tool = spec.name,
            opId = call.id,
        ) ?: return
        mirrorDowngrade(affected, spec.name)
    }

    override fun onFinished(call: ToolCall, outcome: ToolOutcome) {
        val use = LeaseStore.takePending(call.id) ?: return
        LeaseStore.noteUsed(use, call.name, labelOf(outcome))
    }

    private fun factsOf(req: LeaseAskRequest): LeaseFacts = LeaseFacts(
        cls = LeaseRules.classOf(req.spec),
        tool = req.spec.name,
        risk = req.risk,
        unclassified = req.unclassified,
        mustAsk = req.mustAsk,
        inRoot = req.inRoot,
        reversible = req.reversible,
        groupKey = req.spec.group.key,
        capability = CapabilityKind.ofTool(req.spec.group.key, req.spec.readOnly),
        targetsInRawZone = req.targetsInRawZone,
        targets = req.targets,
        args = req.args,
        root = req.root,
    )

    private fun labelOf(outcome: ToolOutcome): String = when (outcome) {
        is ToolOutcome.Ok -> "ok"
        is ToolOutcome.Failed -> "failed"
        is ToolOutcome.Rejected -> "rejected"
    }

    /** 降权/撤销镜像进治理账本（`kind=violation`，`who=lease`）——**只镜像，不改任何判定**。 */
    private fun mirrorDowngrade(g: LeaseGrant, tool: String) {
        val revoked = g.revokedAtMs != null
        AskGovLedger.append(
            kind = AskGovLedger.KIND_VIOLATION,
            who = WHO_LEASE,
            basis = if (revoked) "租约降权到底：${g.cls}（已撤）" else "租约降一档：${g.cls}",
            tool = tool,
            detail = "lease=${g.id} cap=${g.cap} used=${g.used}",
        )
    }
}
