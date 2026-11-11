// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · **外发即租约**的闸（判据⑤、工作单「三条硬规矩」之一）。
//
// 任何对外的请求（探测、列清单、将来 A2A 委派）在发出**第一个字节**之前必须先过这：
// 授权本体是 467 的租约——探针能拿的只有 R0 级（cap=1、只握手＋列清单，调不了业务工具）；
// 未命中 ⇒ [GateDecision.Refused]，调用方**一个请求都不许发**，账上落一行 `gate/refused`。
//
// 为什么授权走 LeaseStore 而不是自己记一个布尔：467 的会话隔离/过期/用尽/撤销/淘汰/账行
// 全是现成的法条，自己再记一套就是第二本判定（迟早漂）。探针类目 `peer/probe` 是新类目，
// [LeaseRules] 的报价/拒授法条一个字没动——它永远不会从工具卡上被报出来，只有本闸授予。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseMissReason
import com.example.zhengdao.baopu.authz.LeaseOffer
import com.example.zhengdao.baopu.authz.LeaseScope
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.authz.LeaseUse
import com.example.zhengdao.baopu.authz.LeaseVerdict
import java.net.URI
import org.json.JSONObject

/** 闸的一次裁决：批准（附带租约扣次凭证）／拒绝（带人话）。 */
internal sealed interface GateDecision {
    data class Approved(val grant: OutboundGrant) : GateDecision
    data class Refused(val reason: String, val miss: LeaseMissReason?) : GateDecision
}

/** 租约扣次凭证：探针收场时交回闸里落「用一次」的账。 */
internal data class OutboundGrant(val leaseId: String, val use: LeaseUse)

internal interface PeerOutboundGate {

    /**
     * 发请求之前问一句。**位置是判据**：Refused 之后不许有任何网络字节。
     * @param opId 这次外发的操作号（账行对账用）
     * @param cls 过哪一类目的租约（第五百四十九批：委派走 `peer/delegate`，缺省仍是探针的
     *   `peer/probe`——既有调用点（MCP/A2A 探针）一个字不改，判定顺序原样）。
     */
    fun authorize(
        endpoint: String,
        opId: String,
        sessionId: Long,
        nowMs: Long,
        cls: String = LeaseOutboundGate.PROBE_CLS,
    ): GateDecision

    /** 收场结算：结果（[PeerFailureCode.wire]）落成租约的 use/outcome 两行。 */
    fun settle(grant: OutboundGrant, result: String, tool: String, nowMs: Long)

    companion object {
        /** 生产实现（缺省就是它）。 */
        val LIVE: PeerOutboundGate = LeaseOutboundGate
    }
}

/**
 * 生产实现：探针租约＝467 的一张真租约（类目 `peer/probe`，范围＝对端 host，一次一发）。
 *
 * 「未批准 ⇒ 不发起」在这里是**没有那条代码路径**：本闸不发起任何请求，
 * 它只把 467 的判定结果转成 [GateDecision]；Refused 时调用方手里只有人话。
 */
internal object LeaseOutboundGate : PeerOutboundGate {

    /** 探针租约的类目（新类目；与工具卡报出的任何 `组/种类` 都撞不上）。 */
    const val PROBE_CLS = "peer/probe"

    /** 探针租约的时间窗：10 分钟（比 467 默认 4h 短得多——探测是当场的事）。 */
    const val PROBE_TTL_MS = 10L * 60L * 1000L

    /**
     * 第五百四十九批 · **委派租约的类目**（同一条「外发即租约」法条的第二类目）。
     * 委派一次＝一次外发交换（准入考试的九道题＋派活后的发送/轮询/取消都算这一次交换之内），
     * 所以闸口与探针同形：cap=1、范围=对端 host、时间窗 10 分钟（考试 60s＋轮询 120s 装得下）。
     */
    const val DELEGATE_CLS = "peer/delegate"

    override fun authorize(
        endpoint: String,
        opId: String,
        sessionId: Long,
        nowMs: Long,
        cls: String,
    ): GateDecision =
        when (
            val v = LeaseStore.hit(
                sessionId = sessionId,
                cls = cls,
                targets = emptyList(),
                args = JSONObject().put("url", endpoint),
                cost = 1,
                opId = opId,
                nowMs = nowMs,
            )
        ) {
            is LeaseVerdict.Hit -> {
                val use = LeaseStore.takePending(opId)
                if (use == null) {
                    GateDecision.Refused("租约扣次没落上（内部状态不一致），这次不发起", LeaseMissReason.NOT_GRANTED)
                } else {
                    GateDecision.Approved(OutboundGrant(v.leaseId, use))
                }
            }
            is LeaseVerdict.Miss -> GateDecision.Refused(refusalText(v.reason), v.reason)
        }

    override fun settle(grant: OutboundGrant, result: String, tool: String, nowMs: Long) {
        LeaseStore.noteUsed(grant.use, tool, result, nowMs)
    }

    /**
     * 授一张探针租约（**人在环之后才调**：UI 点「连接/测试」、卡片批准、登记即 autoConnect）。
     * 范围取对端 host——换一台机器就不命中（467 的范围这一维原样生效）。
     */
    fun grantProbeLease(endpoint: String, sessionId: Long, sourceCardId: String, nowMs: Long): String {
        val offer = LeaseOffer(
            cls = PROBE_CLS,
            scope = LeaseScope(LeaseScope.Kind.HOST, hostOf(endpoint)),
            cap = 1,
            ttlMs = PROBE_TTL_MS,
        )
        return LeaseStore.grant(offer, sessionId, sourceCardId, nowMs).id
    }

    /**
     * 第五百四十九批 · 授一张**委派租约**（`peer/delegate`）。
     *
     * 授予动作＝**管线批准这次委派**（`delegate_peer_task` 是 R2 必问的第一方工具：授权卡
     * 点了批准，执行体开跑时在入口代记授予，授予源写这次调用的 opId——批准与授予是同一件事
     * 的两笔账，不是两道人）。之后的每个外发请求都过 [PeerOutboundGate.authorize] 扣次；
     * 撤销/过期/用尽当场断流（一单一个 opId 一张租约，不给下一次白用）。
     */
    fun grantDelegateLease(endpoint: String, sessionId: Long, sourceOpId: String, nowMs: Long): String {
        val offer = LeaseOffer(
            cls = DELEGATE_CLS,
            scope = LeaseScope(LeaseScope.Kind.HOST, hostOf(endpoint)),
            cap = 1,
            ttlMs = PROBE_TTL_MS,
        )
        return LeaseStore.grant(offer, sessionId, sourceOpId, nowMs).id
    }

    private fun hostOf(endpoint: String): String =
        runCatching { URI(endpoint.trim()).host }.getOrNull()?.lowercase().orEmpty()

    private fun refusalText(reason: LeaseMissReason): String = when (reason) {
        LeaseMissReason.NOT_GRANTED -> "这台还没有生效的外发批准：先批（连接/测试按钮或授权卡）再连"
        LeaseMissReason.CATEGORY_MISMATCH -> "批准的不是「外部探测」这一类：租约类目对不上，不发起"
        LeaseMissReason.REVOKED -> "这台的外发批准已被撤销：不发起"
        LeaseMissReason.EXPIRED -> "这台的外发批准已过期：重新批一次再连"
        LeaseMissReason.EXHAUSTED -> "这台的外发批准次数用完了：每次探测都要新批（外发即租约）"
        LeaseMissReason.OUT_OF_SCOPE -> "要连的地址超出了批准的范围（批准按对端 host 圈定）：不发起"
    }
}
