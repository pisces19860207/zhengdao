// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · MCP 对端的 **R0 探测**（只握手＋列清单，不调业务工具）。
//
// 一次探测的形状（派工单 548「客户端探测与能力清单」①⑤）：
//   过闸（外发即租约，未批准 ⇒ 一个字节都不发）→ initialize（拿 capabilities）
//   → initialized 通知 → **按能力**拉 tools/list（没声明 tools 能力就不发）
//   → 清单落盘（.mcp-peers.json，schema sha256 指纹）→ 与上次对比（能力变了要报）
//   → 身份进名册（.agents.jsonl）→ 租约结算 ＋ 事件账一行。
//
// 发出的请求**只有三类**：initialize / notifications/initialized / tools/list——
// 这不是自觉，是结构：本文件没有任何调用 tools/call（或 resources/prompts）的路径，
// 测试在 wire 层逐请求断言（判据④）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.mcp.McpClient
import com.example.zhengdao.baopu.mcp.McpException
import com.example.zhengdao.baopu.mcp.McpServerConfig
import com.example.zhengdao.baopu.mcp.McpTokenProvider
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import org.json.JSONObject

/** 一次探测的收场（code ＋ 人话；ok 时清单/对比齐全）。 */
internal data class PeerProbeOutcome(
    val opId: String,
    val code: PeerFailureCode,
    val human: String,
    val entry: PeerManifestEntry? = null,
    val change: CapabilityChangeReport? = null,
) {
    /** 指纹变了 ⇒ 能力变了（判据③的旗子；调用方不得拿这台的清单当没变用）。 */
    val capabilityChanged: Boolean get() = change?.changed == true
}

internal class McpPeerProbe(
    private val http: OkHttpClient,
    private val gate: PeerOutboundGate = PeerOutboundGate.LIVE,
    private val log: (String) -> Unit = {},
) {

    private val seq = AtomicLong(0)

    /**
     * 探一台 MCP 服务器（R0：握手＋列清单）。
     *
     * @param root 工作区根（清单与名册的落点；null = 纯内存，单测/无落点环境）
     * @param sessionId 租约会话（人在环的直连动作统一用 [SESSION_MANUAL]）
     * @param tokenProvider 凭据供给（来自 McpVault；null = 无凭据，401 会归 AUTH）
     */
    fun probe(
        config: McpServerConfig,
        root: File?,
        tokenProvider: McpTokenProvider? = null,
        sessionId: Long = SESSION_MANUAL,
        nowMs: Long = System.currentTimeMillis(),
    ): PeerProbeOutcome {
        val opId = "ob-${nowMs}-${seq.incrementAndGet()}"
        // ① 闸：未批准 ⇒ 不发起（结构保证：下面才第一次碰到网络）。
        val decision = gate.authorize(config.url, opId, sessionId, nowMs)
        if (decision is GateDecision.Refused) {
            PeerEventsLedger.append(PeerEventKinds.GATE, PeerFailureCode.REFUSED.wire, config.url, decision.reason, nowMs)
            log("[peer] ${config.id} 探测未发起：${decision.reason}")
            return PeerProbeOutcome(opId, PeerFailureCode.REFUSED, decision.reason)
        }
        val grant = (decision as GateDecision.Approved).grant

        // ② 握手：initialize → capabilities → initialized（McpClient，streamable 优先）。
        val client = McpClient(config, http, log, tokenProvider)
        return try {
            client.connect()
            val capabilities = capabilityKeys(client.serverCapabilities)
            // ③ 按能力拉清单：声明了 tools 能力才发 tools/list。
            val tools = if (capabilities.contains(CAP_TOOLS)) {
                client.listTools().map { PeerToolFp(it.name, it.description, PeerFingerprints.ofToolSchema(it.inputSchema)) }
            } else {
                log("[peer] ${config.id} 未声明 tools 能力，跳过 tools/list")
                emptyList()
            }
            finishOk(config, root, tokenProvider, client, capabilities, tools, grant, opId, nowMs)
        } catch (e: McpException) {
            fail(config, grant, opId, PeerFailureClassify.ofThrowable(e), e.userMessage, nowMs)
        } catch (e: Exception) {
            fail(config, grant, opId, PeerFailureClassify.ofThrowable(e), e.javaClass.simpleName, nowMs)
        } finally {
            runCatching { client.close() }
        }
    }

    /** 成功收场：清单落盘 → 指纹对比 → 名册 → 结算 ＋ 账。 */
    private fun finishOk(
        config: McpServerConfig,
        root: File?,
        tokenProvider: McpTokenProvider?,
        client: McpClient,
        capabilities: List<String>,
        tools: List<PeerToolFp>,
        grant: OutboundGrant,
        opId: String,
        nowMs: Long,
    ): PeerProbeOutcome {
        val entry = PeerManifestEntry(
            agentId = AgentIds.stableId(config.url),
            serverId = config.id,
            name = config.name,
            endpoint = config.url,
            protocolVersion = client.protocolVersion,
            mode = client.mode,
            capabilities = capabilities,
            tools = tools,
            probedAtMs = nowMs,
            probeResult = PeerFailureCode.OK.wire,
        )
        val change = if (root != null) diffCapabilities(McpPeersManifest.load(root)[entry.agentId], entry) else null
        if (root != null) McpPeersManifest.upsert(root, entry)

        // 身份进名册：凭据只带指纹（本体在 McpVault），能力指纹由能力键＋工具指纹算出。
        val registered = AgentRegistry.register(
            name = config.name,
            form = AgentForm.MCP,
            endpoint = config.url,
            credentialFingerprint = PeerFingerprints.ofCredential(tokenProvider?.accessToken()),
            capabilityFingerprint = PeerFingerprints.ofCapabilitySet(capabilities, tools),
            atMs = nowMs,
        )

        val human = buildString {
            append(PeerFailureText.human(PeerFailureCode.OK, config.name))
            append("：${tools.size} 个工具（清单已落盘，未自动启用）")
            change?.takeIf { it.changed }?.let { append('　').append(it.human()) }
        }
        gate.settle(grant, PeerFailureCode.OK.wire, TOOL_NAME, nowMs)
        PeerEventsLedger.append(
            PeerEventKinds.PROBE,
            PeerFailureCode.OK.wire,
            config.url,
            detail = "tools=${tools.size} cap=${capabilities.joinToString(",")}" +
                " changed=${change?.changed == true} agent=${registered.record.id}",
            atMs = nowMs,
        )
        log("[peer] ${config.id} 探测成功：${tools.size} 个工具（${entry.mode}）")
        return PeerProbeOutcome(opId, PeerFailureCode.OK, human, entry, change)
    }

    /** 失败收场：人话按类给，账行按类落，租约照常结算（批了没用成也是「用一次」）。 */
    private fun fail(
        config: McpServerConfig,
        grant: OutboundGrant,
        opId: String,
        code: PeerFailureCode,
        detail: String,
        nowMs: Long,
    ): PeerProbeOutcome {
        val human = PeerFailureText.human(code, config.name)
        gate.settle(grant, code.wire, TOOL_NAME, nowMs)
        PeerEventsLedger.append(PeerEventKinds.PROBE, code.wire, config.url, detail = detail, atMs = nowMs)
        log("[peer] ${config.id} 探测失败（${code.wire}）：$detail")
        return PeerProbeOutcome(opId, code, human)
    }

    /** capabilities 里真正声明了的键（值是对象才算声明；空对象也算「有这一路」）。 */
    private fun capabilityKeys(caps: JSONObject?): List<String> {
        if (caps == null) return emptyList()
        return caps.keys().asSequence().filter { caps.opt(it) != null }.sorted().toList()
    }

    companion object {
        /** 探针在账面上的工具名（租约 use/outcome 行与事件账共用）。 */
        const val TOOL_NAME = "peer.probe"

        /** 人在环（UI 直连/登记 autoConnect）的租约会话号：系统会话，固定 -1。 */
        const val SESSION_MANUAL = -1L

        /** capabilities 里「有工具」的键（有它才发 tools/list）。 */
        const val CAP_TOOLS = "tools"
    }
}
