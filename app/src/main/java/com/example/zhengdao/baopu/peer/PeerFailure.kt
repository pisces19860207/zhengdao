// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · 外发的失败闭集与**人话**（MCP 探针与 A2A 客户端共用）。
//
// 三条硬规矩（派工单 548「三条硬规矩」之三）：
// · 四类失败给不同人话——DNS／端口／证书／协议各是各的一句，不许统一「网络错误」；
// · 账行里每类一个机器键（[PeerFailureCode.wire]），对账按键不按文案猜；
// · 需要鉴权不是「失败」：它是自己的类（AUTH），给的话是「先授权」，不是「连不上」。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.mcp.McpException
import com.example.zhengdao.baopu.mcp.McpFailure
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** 外发失败的机器键（闭集；改 wire 等于让老账读不出）。 */
internal enum class PeerFailureCode(val wire: String) {
    OK("ok"),
    DNS("dns"),
    PORT("port"),
    TLS("tls"),
    TIMEOUT("timeout"),
    PROTOCOL("protocol"),
    AUTH("auth"),
    NETWORK("network"),

    /** 没被批准就发起了——这行账记在闸头上，请求一个都没发出去。 */
    REFUSED("refused"),
    ;

    companion object {
        fun ofWire(wire: String): PeerFailureCode? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 异常 → 失败类（MCP 与 A2A 共用一把尺）。
 *
 * MCP 的 [McpClient] 会把底层 IOException 包进 [McpException]（cause 保留），所以
 * 先看 MCP 的失败枚举，CONNECT 再顺 cause 链细分 DNS／端口；纯 A2A 的异常直接顺链。
 */
internal object PeerFailureClassify {

    fun ofThrowable(e: Throwable): PeerFailureCode {
        var cur: Throwable? = e
        var guard = 0
        while (cur != null && guard < MAX_CHAIN) {
            val direct = directOf(cur)
            if (direct != null) return direct
            cur = cur.cause
            guard++
        }
        return PeerFailureCode.NETWORK
    }

    /** 一环上的直接判定（null = 这环认不出，往 cause 顺）。 */
    private fun directOf(e: Throwable): PeerFailureCode? = when {
        e is McpException -> ofMcp(e)
        e is UnknownHostException -> PeerFailureCode.DNS
        e is ConnectException -> PeerFailureCode.PORT
        e is SSLException -> PeerFailureCode.TLS
        e is InterruptedIOException -> PeerFailureCode.TIMEOUT
        e is java.net.MalformedURLException || e is IllegalArgumentException -> PeerFailureCode.PROTOCOL
        else -> null
    }

    private fun ofMcp(e: McpException): PeerFailureCode = when (e.failure) {
        McpFailure.TIMEOUT -> PeerFailureCode.TIMEOUT
        McpFailure.PROTOCOL, McpFailure.SCHEMA, McpFailure.CONFIG -> PeerFailureCode.PROTOCOL
        McpFailure.AUTH, McpFailure.AUTH_REQUIRED, McpFailure.INSUFFICIENT_SCOPE -> PeerFailureCode.AUTH
        McpFailure.CLOSED -> PeerFailureCode.NETWORK
        McpFailure.CONNECT, McpFailure.HTTP, McpFailure.TOOL -> e.cause?.let { ofThrowable(it) } ?: PeerFailureCode.NETWORK
    }

    private const val MAX_CHAIN = 8
}

/** 每类一句人话（**判据②**：协议不符／超时／需鉴权三句必须互相听得出来）。 */
internal object PeerFailureText {

    fun human(code: PeerFailureCode, peerName: String): String = when (code) {
        PeerFailureCode.OK -> "外部能力「$peerName」探测成功"
        PeerFailureCode.DNS -> "找不到「$peerName」这台服务器：域名解析不了（DNS）——查一下地址写对没有、网络通不通"
        PeerFailureCode.PORT -> "「$peerName」门口没人应：端口拒绝连接——对方服务多半没起，或端口写错了"
        PeerFailureCode.TLS -> "「$peerName」的证书信不过：HTTPS 握手失败（自签/过期/域名不符）——证书这一关没过"
        PeerFailureCode.TIMEOUT -> "「$peerName」等太久没回音：超时——对方忙，或不在这个网络里"
        PeerFailureCode.PROTOCOL -> "「$peerName」说的不是一路话：协议不符——对方不是 MCP/A2A，或版本对不上，已停止（不会硬猜着用）"
        PeerFailureCode.AUTH -> "「$peerName」要先验明身份：需要授权或凭据无效——先授权/换凭据，再连"
        PeerFailureCode.NETWORK -> "「$peerName」的连接半路断了：网络错误"
        PeerFailureCode.REFUSED -> "「$peerName」这次外发没有批准，没有发起（先批再连）"
    }
}
