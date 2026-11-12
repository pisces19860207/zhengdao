// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百零六批 · 五套尺 → 12 键映射的**一条断言**（判据⑥；文档表在 PeerFailureClassify 的 KDoc，
// 本测试是那张表的执行版：全值覆盖、目标全在闭集内）。
//
// 顺带钉两处收口：
// · ⑤ ofMcp 不再把 McpFailure.CONFIG 压平成 protocol；
// · 裸 IllegalArgumentException 归 config（OkHttp 对本机给的头值/地址摇头，是本机配置问题），
//   MalformedURLException 维持 protocol（A2A 账史不变）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.mcp.McpAuthFailure
import com.example.zhengdao.baopu.mcp.McpAuthStatus
import com.example.zhengdao.baopu.mcp.McpException
import com.example.zhengdao.baopu.mcp.McpFailure
import com.example.zhengdao.baopu.mcp.McpProbeFailure
import com.example.zhengdao.baopu.mcp.McpServerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpFailureMappingTest {

    // ── ⑥ 一条断言：五套尺全值有键 ─────────────────────────────────────

    @Test
    fun `收口十七_12键闭集_五套尺全值有键_一条断言`() {
        // 硬线：闭集 12 键、wire 全集不变（不许增键、不许改名）。
        assertEquals(12, PeerFailureCode.entries.size)
        assertEquals(
            setOf(
                "ok", "dns", "port", "tls", "timeout", "protocol",
                "version", "endpoint", "auth", "config", "network", "refused",
            ),
            PeerFailureCode.entries.map { it.wire }.toSet(),
        )

        // 尺①：McpFailure（MCP 请求异常）——ofMcp 穷尽 when 已由编译期钉住，这里钉行为。
        val mcpFailureMap = mapOf(
            McpFailure.CONFIG to PeerFailureCode.CONFIG, // 606⑤：不再压平
            McpFailure.ENDPOINT to PeerFailureCode.ENDPOINT, // 606①：HTTP 404
            McpFailure.VERSION_MISMATCH to PeerFailureCode.VERSION_MISMATCH, // 606①
            McpFailure.TIMEOUT to PeerFailureCode.TIMEOUT,
            McpFailure.AUTH to PeerFailureCode.AUTH,
            McpFailure.AUTH_REQUIRED to PeerFailureCode.AUTH,
            McpFailure.INSUFFICIENT_SCOPE to PeerFailureCode.AUTH,
            McpFailure.PROTOCOL to PeerFailureCode.PROTOCOL,
            McpFailure.SCHEMA to PeerFailureCode.PROTOCOL,
            McpFailure.CONNECT to PeerFailureCode.NETWORK, // 无 cause 兜底
            McpFailure.HTTP to PeerFailureCode.NETWORK, // 4xx 已在源头分流，只剩 5xx/3xx
            McpFailure.TOOL to PeerFailureCode.NETWORK,
            McpFailure.CLOSED to PeerFailureCode.NETWORK,
        )
        assertEquals("尺①全值覆盖", McpFailure.entries.size, mcpFailureMap.size)
        mcpFailureMap.forEach { (failure, code) ->
            assertEquals(
                "尺① $failure 该归 ${code.wire}",
                code,
                PeerFailureClassify.ofThrowable(McpException(failure, "606映射")),
            )
        }

        // 尺③：McpProbeFailure（测试连接）——文档表逐值断言（UI 就近用同义文案）。
        val probeMap = mapOf(
            McpProbeFailure.BAD_URL to PeerFailureCode.CONFIG,
            McpProbeFailure.DNS to PeerFailureCode.DNS,
            McpProbeFailure.TLS to PeerFailureCode.TLS,
            McpProbeFailure.TIMEOUT to PeerFailureCode.TIMEOUT,
            McpProbeFailure.REFUSED to PeerFailureCode.REFUSED,
            McpProbeFailure.NETWORK to PeerFailureCode.NETWORK,
        )
        assertEquals("尺③全值覆盖", McpProbeFailure.entries.size, probeMap.size)
        assertTrue(probeMap.values.all { it in PeerFailureCode.entries })

        // 尺④：McpAuthFailure（OAuth 授权）——文档表逐值断言。
        val authMap = mapOf(
            McpAuthFailure.NO_METADATA to PeerFailureCode.PROTOCOL,
            McpAuthFailure.BAD_METADATA to PeerFailureCode.CONFIG,
            McpAuthFailure.REGISTRATION_FAILED to PeerFailureCode.PROTOCOL,
            McpAuthFailure.STATE_MISMATCH to PeerFailureCode.PROTOCOL,
            McpAuthFailure.AUTHORIZATION_DENIED to PeerFailureCode.AUTH,
            McpAuthFailure.TOKEN_EXCHANGE_FAILED to PeerFailureCode.AUTH,
            McpAuthFailure.REFRESH_FAILED to PeerFailureCode.AUTH,
            McpAuthFailure.AUDIENCE_MISMATCH to PeerFailureCode.AUTH,
            McpAuthFailure.UNSUPPORTED to PeerFailureCode.PROTOCOL,
            McpAuthFailure.NO_PENDING to PeerFailureCode.PROTOCOL,
            McpAuthFailure.NETWORK to PeerFailureCode.NETWORK,
            McpAuthFailure.TLS to PeerFailureCode.TLS,
            McpAuthFailure.TIMEOUT to PeerFailureCode.TIMEOUT,
        )
        assertEquals("尺④全值覆盖", McpAuthFailure.entries.size, authMap.size)
        assertTrue(authMap.values.all { it in PeerFailureCode.entries })

        // 尺⑤：状态枚举（授权/连接状态）不是失败——按文档表不映射，只钉形状。
        assertEquals(5, McpAuthStatus.entries.size)
        assertEquals(3, McpServerStatus.entries.size)
    }

    // ── ⑤＋裸 IAE 的分类回归 ────────────────────────────────────────────

    @Test
    fun `收口十八_裸IAE归CONFIG_MalformedURL仍PROTOCOL_CONFIG不再压平`() {
        assertEquals(
            "OkHttp 对本机头值/地址摇头 ⇒ 本机配置（602 落点 PeerFailure.kt:104）",
            PeerFailureCode.CONFIG,
            PeerFailureClassify.ofThrowable(IllegalArgumentException("non-ascii header")),
        )
        assertEquals(
            "MalformedURL 维持原账（A2A 侧历史读数不变）",
            PeerFailureCode.PROTOCOL,
            PeerFailureClassify.ofThrowable(java.net.MalformedURLException("x")),
        )
        assertEquals(
            "606⑤：MCP 的 CONFIG 不再压平成 protocol",
            PeerFailureCode.CONFIG,
            PeerFailureClassify.ofThrowable(McpException(McpFailure.CONFIG, "凭据非 ASCII")),
        )
        assertEquals(
            "cause 链：HTTP 包着 IAE ⇒ 顺链到 config",
            PeerFailureCode.CONFIG,
            PeerFailureClassify.ofThrowable(
                McpException(McpFailure.HTTP, "x", cause = IllegalArgumentException("iae")),
            ),
        )
        // 既有分类回归：PROTOCOL/AUTH 族不受本批影响。
        assertEquals(
            PeerFailureCode.PROTOCOL,
            PeerFailureClassify.ofThrowable(McpException(McpFailure.PROTOCOL, "x")),
        )
        assertEquals(
            PeerFailureCode.AUTH,
            PeerFailureClassify.ofThrowable(McpException(McpFailure.AUTH_REQUIRED, "x")),
        )
    }
}
