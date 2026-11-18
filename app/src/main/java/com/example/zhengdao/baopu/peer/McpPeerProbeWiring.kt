// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百零七单 · **548 探针的生产接线**（G-705-2：`McpPeerProbe` 至今只有测试在调，
// `.mcp-peers.json` 的「陈旧」标记无生产调用方、无 UI 入口）。
//
// 这里只做两件事，都不新造网络路径：
//   1. 面板「探测」一次的装配：人点按钮＝人在环 ⇒ 先授一张探针租约（外发即租约，
//      R0：cap=1、范围=对端 host、10 分钟），再走 [McpPeerProbe]（握手＋列清单＋
//      清单落盘＋两本账各一行）。账本落点在使用侧带上（rememberRoot——与 467 租约账
//      同一条纪律：不存 Context，账永远落在当前工作区）。
//   2. 面板一行的**探测三态**判据件（纯函数）：可用 / 陈旧 / 未探测——
//      陈旧必须看得见（有登记但探测失败/超时 ⇒ 标「陈旧」＋最后一次成功时间，
//      不许假装可用）；探测失败**不删登记**（清单里那行 OK 原样留着，就是要给人看
//      「最后成功是什么时候」）。
//
// 本文件是 707 唯一新增的「碰租约」文件：LeaseEntryGuardTest 的名册已登记（扩面走明账）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.mcp.McpOAuthClient
import com.example.zhengdao.baopu.mcp.McpServerConfig
import com.example.zhengdao.baopu.mcp.McpTokenProvider
import java.io.File

/** 面板「探测」一次的收场（给 UI：成功与否＋人话）。 */
internal data class PeerPanelProbe(
    /** true ＝ 探测成功（清单已按本次结果落盘）。 */
    val ok: Boolean,
    /** 一句人话（成功/失败各是各的话，来自 [PeerFailureText]，不静默）。 */
    val human: String,
)

internal object McpPeerProbeWiring {

    /**
     * 面板点「探测」：人在环的直连动作（与「连接/测试按钮」同一类目）。
     *
     * 顺序是判据：先授租约、再发起——未批准（这里总是批准，因为授予就在上一行）之外，
     * 闸若拒（内部状态不一致）时 [McpPeerProbe] 自己一个字节都不发并落账。
     *
     * @param root 工作区根（清单/名册/两本账的落点；null ＝ 没有落点，账不落）
     */
    fun probeFromPanel(
        config: McpServerConfig,
        root: File?,
        tokenProvider: McpTokenProvider?,
        nowMs: Long,
        log: (String) -> Unit = {},
    ): PeerPanelProbe {
        if (root != null) {
            // 账落「当前工作区/产物」：每次带上，不跟着换工作区变旧（467 同一条）。
            LeaseLedger.rememberRoot(root)
            PeerEventsLedger.rememberRoot(root)
            // 名册只有 provider 一条路（没有 rememberRoot），这里按当次根装上。
            AgentRegistry.installRoot { root }
        }
        // 人在环（面板点「探测」）⇒ 先授一张探针租约再发起（外发即租约：cap=1、
        // 范围=对端 host、10 分钟——与「连接/测试按钮」同一类目，判定层零改动）。
        LeaseOutboundGate.grantProbeLease(config.url, McpPeerProbe.SESSION_MANUAL, "mcp-panel-probe", nowMs)
        val outcome = McpPeerProbe(McpOAuthClient.defaultHttp(), log = log).probe(
            config,
            root = root,
            tokenProvider = tokenProvider,
            sessionId = McpPeerProbe.SESSION_MANUAL,
            nowMs = nowMs,
        )
        return PeerPanelProbe(ok = outcome.code == PeerFailureCode.OK, human = outcome.human)
    }

    /** 配置 → 清单里的登记行（按对端身份键；没有登记 ＝ null ＝ 未探测）。 */
    fun manifestEntryFor(config: McpServerConfig, root: File?): PeerManifestEntry? {
        if (root == null) return null
        return McpPeersManifest.load(root)[AgentIds.stableId(config.url)]
    }
}

/** 面板一行的探测三态（判据①：三态可显；陈旧不许装可用）。 */
internal enum class PeerPanelState {
    /** 有登记且探测成功、清单没过期。 */
    OK,

    /** 有登记但不可用：最近一次探测失败/超时，或清单超过 TTL 没刷新成。 */
    STALE,

    /** 没探测过（清单里没有这台的登记行）。 */
    UNKNOWN,
}

/** 三态判据件：state ＋ 最后一次成功探测的时刻（陈旧态必须亮出来）。 */
internal data class PeerPanelReading(
    val state: PeerPanelState,
    val lastOkAtMs: Long?,
    /** 清单里登记的工具数（0 也如实显示）。 */
    val toolCount: Int,
    /** 陈旧的细分：true ＝ 清单超过 24h 没刷新成（TTL）；false ＝ 最近一次探测失败。 */
    val staleByTtl: Boolean = false,
)

/**
 * 三态推导（**纯函数**，单测逐态钉死）。
 *
 * @param entry 清单登记行（null ＝ 没登记过）
 * @param lastProbeOk 本次进程内最近一次探测的结果；null ＝ 还没点过「探测」
 */
internal fun peerPanelReading(
    entry: PeerManifestEntry?,
    lastProbeOk: Boolean?,
    nowMs: Long,
): PeerPanelReading {
    if (entry == null) return PeerPanelReading(PeerPanelState.UNKNOWN, null, 0)
    // 探测失败 ⇒ 当场陈旧（判据①负例：夹具停了还显示可用＝红）。清单那行 OK 原样不动，
    // 它的 probedAtMs 恰好就是「最后一次成功时间」。
    if (lastProbeOk == false) return PeerPanelReading(PeerPanelState.STALE, entry.probedAtMs, entry.tools.size)
    // 登记行自己过期（TTL 24h）：不自动沿用（548 判据⑬），也标陈旧。
    if (entry.isStale(nowMs)) {
        return PeerPanelReading(PeerPanelState.STALE, entry.probedAtMs, entry.tools.size, staleByTtl = true)
    }
    return PeerPanelReading(PeerPanelState.OK, entry.probedAtMs, entry.tools.size)
}
