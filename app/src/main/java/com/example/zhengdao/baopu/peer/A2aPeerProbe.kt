// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · A2A 对端的 **R0 探测**：过同一道外发闸，拉 agent card，身份进名册。
//
// 「外发即租约」不分协议：拉卡也是对外请求，未批准 ⇒ 一个字节不发。
// 业务委派（message/send 起任务）的闸上接线归 549（使臣团）——闸本身（[PeerOutboundGate]）
// 本批已经立好，谁发起谁先过闸。
package com.example.zhengdao.baopu.peer

import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient

internal class A2aPeerProbe(
    private val http: OkHttpClient,
    private val gate: PeerOutboundGate = PeerOutboundGate.LIVE,
    private val log: (String) -> Unit = {},
) {

    private val seq = AtomicLong(0)

    /**
     * 拉卡 ＋ 注册身份（form=a2a）。
     *
     * @return 与 MCP 探测同形的收场（entry 为 null：`.mcp-peers.json` 是 MCP 专属）；
     *   能力指纹与名册里的旧值不同 ⇒ human 里带「能力变了」。
     */
    fun probeCard(
        baseUrl: String,
        credential: A2aCredential? = null,
        sessionId: Long = McpPeerProbe.SESSION_MANUAL,
        nowMs: Long = System.currentTimeMillis(),
    ): PeerProbeOutcome {
        val opId = "ob-${nowMs}-${seq.incrementAndGet()}"
        val decision = gate.authorize(baseUrl, opId, sessionId, nowMs)
        if (decision is GateDecision.Refused) {
            PeerEventsLedger.append(PeerEventKinds.GATE, PeerFailureCode.REFUSED.wire, baseUrl, decision.reason, nowMs)
            log("[peer] a2a 探测未发起：${decision.reason}")
            return PeerProbeOutcome(opId, PeerFailureCode.REFUSED, decision.reason)
        }
        val grant = (decision as GateDecision.Approved).grant
        val client = A2aClient(baseUrl, http, log, credential)
        return try {
            val card = client.fetchCard()
            val capFp = card.capabilityFingerprint()
            val previous = AgentRegistry.active().firstOrNull {
                it.form == AgentForm.A2A && AgentIds.normalize(it.endpoint) == AgentIds.normalize(baseUrl)
            }
            val changed = previous != null && previous.capabilityFingerprint.isNotEmpty() &&
                previous.capabilityFingerprint != capFp
            AgentRegistry.register(
                name = card.name,
                form = AgentForm.A2A,
                endpoint = baseUrl,
                credentialFingerprint = PeerFingerprints.ofCredential(credential?.value),
                capabilityFingerprint = capFp,
                atMs = nowMs,
            )
            val human = buildString {
                append(PeerFailureText.human(PeerFailureCode.OK, card.name))
                append("：${card.skills.size} 项技能（${card.capabilities.joinToString(",").ifEmpty { "无能力声明" }}）")
                if (changed) append("　⚠️ 能力指纹变了：这台的技能/能力清单与上次不同，已记录，未自动启用任何委派")
            }
            gate.settle(grant, PeerFailureCode.OK.wire, TOOL_NAME, nowMs)
            PeerEventsLedger.append(
                PeerEventKinds.PROBE,
                PeerFailureCode.OK.wire,
                baseUrl,
                detail = "card=${card.name} skills=${card.skills.size} changed=$changed",
                atMs = nowMs,
            )
            log("[peer] a2a 探测成功：${card.name}（${card.skills.size} 项技能）")
            PeerProbeOutcome(opId, PeerFailureCode.OK, human)
        } catch (e: A2aException) {
            fail(baseUrl, grant, opId, e.failureCode, e.message.orEmpty(), nowMs)
        } catch (e: Exception) {
            fail(baseUrl, grant, opId, PeerFailureClassify.ofThrowable(e), e.javaClass.simpleName, nowMs)
        }
    }

    private fun fail(
        baseUrl: String,
        grant: OutboundGrant,
        opId: String,
        code: PeerFailureCode,
        detail: String,
        nowMs: Long,
    ): PeerProbeOutcome {
        val human = PeerFailureText.human(code, baseUrl)
        gate.settle(grant, code.wire, TOOL_NAME, nowMs)
        PeerEventsLedger.append(PeerEventKinds.PROBE, code.wire, baseUrl, detail = detail, atMs = nowMs)
        log("[peer] a2a 探测失败（${code.wire}）：$detail")
        return PeerProbeOutcome(opId, code, human)
    }

    companion object {
        /** 账面上的工具名（与 MCP 探针区分：`peer.a2acard`）。 */
        const val TOOL_NAME = "peer.a2acard"
    }
}
