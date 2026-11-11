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

/**
 * 外发失败的机器键（闭集；**改既有 wire 等于让老账读不出**——只许增，不许改名、不许删）。
 *
 * 失败码说明（模型/对接方看这里；每类的人话在 [PeerFailureText]，对账按键不按文案猜）。
 * 语义互不重叠：一类只管一件事，网络不通只归 network。
 *
 * | wire | 语义（唯一） | 典型来源 |
 * |---|---|---|
 * | ok | 探测/调用成功 | 探针收场 |
 * | dns | 域名解析不了 | `UnknownHostException` |
 * | port | 端口拒绝连接 | `ConnectException` |
 * | tls | 证书信不过 | `SSLException` |
 * | timeout | 等太久没回音 | 读/连接超时（`InterruptedIOException`） |
 * | protocol | 对方说的不是一路话：响应形状坏（非 JSON/缺字段/空响应/JSON-RPC error/其余 4xx） | 解析与拒收路 |
 * | version | **版本不在支持区间**（卡上 protocolVersion 越界，或 400/422 错误体点名 version）——只表版本 | A2A 预检＋HTTP 分类 |
 * | endpoint | **地址不存在**（HTTP 404）——只表这条路没有 | A2A HTTP 分类 |
 * | auth | 要先验明身份：需要授权或凭据无效（HTTP 401/403） | A2A/MCP 401 路 |
 * | config | **本机配置不过检**（如凭据含非 ASCII 字符）——只表本地问题，请求未发出 | A2A 凭据过检 |
 * | network | 连接半路断了（5xx/无响应/其余传输层错）——网络不通只归这类 | [PeerFailureClassify] 兜底 |
 * | refused | 外发未批准，请求一个都没发 | 外发闸 |
 *
 * 扩值台账（谁/何时/为什么/清单）：
 * - **590 批**（zcode，2026-11-12）：590 派工单判据①②④需要独立的「版本不符／端点不存在／
 *   本地配置错」键，按「确需新值先立单」新增 **version／endpoint／config** 三值——既有九键的
 *   语义与 wire **一字未动**；验收条件五条见交付报告《590-A2A收口-交付-2026-11-11》§九
 *   （三值算 590 之内，不另立单）。
 *
 * 读侧兜底（老账照读的保证）：[ofWire] 对认不出的 wire 返回 **null，不抛**——本枚举后来的
 * 新值在旧读侧眼里就是「未知值」，不许崩；事件账/租约账与能力清单的 `probeResult` 都按
 * **字符串**存取，未知值原样透传。消费方拿到 null 自行兜底，**不许 `!!`**。
 */
internal enum class PeerFailureCode(val wire: String) {
    OK("ok"),
    DNS("dns"),
    PORT("port"),
    TLS("tls"),
    TIMEOUT("timeout"),
    PROTOCOL("protocol"),

    /** A2A 协议版本对不上：卡上 protocolVersion 超出支持区间（或 400/422 错误体点名 version）。 */
    VERSION_MISMATCH("version"),

    /** 路径不存在：HTTP 404——服务在那儿，但这个地址不给这条接口（与「说不上话」「连不上」分开）。 */
    ENDPOINT("endpoint"),
    AUTH("auth"),

    /** 本机配置就没过检（如凭据含非 ASCII 字符）——请求一个字节都没发。 */
    CONFIG("config"),
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
        PeerFailureCode.PROTOCOL -> "「$peerName」说的不是一路话：协议不符——对方不是 MCP/A2A，已停止（不会硬猜着用）"
        PeerFailureCode.VERSION_MISMATCH -> "「$peerName」的 A2A 协议版本对不上：不在本端支持的区间——要么它太旧要么太新，已停住不硬谈（先对齐 protocolVersion）"
        PeerFailureCode.ENDPOINT -> "「$peerName」那儿没有这条接口：地址给的是 404——端点/路径不对（服务在，但这条路不通）"
        PeerFailureCode.AUTH -> "「$peerName」要先验明身份：需要授权或凭据无效——先授权/换凭据，再连"
        PeerFailureCode.CONFIG -> "「$peerName」还没出门就卡住了：本机配置不过检（如凭据含非 ASCII 字符）——请换 ASCII token 或改用自定头"
        PeerFailureCode.NETWORK -> "「$peerName」的连接半路断了：网络错误"
        PeerFailureCode.REFUSED -> "「$peerName」这次外发没有批准，没有发起（先批再连）"
    }
}
