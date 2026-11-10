// 第五百八十四单 · 环回 HTTP 传输层单测（真套接字，打 127.0.0.1：绑定/解析/回写/停服让端口）。
package com.example.zhengdao.baopu.mcp.server

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHttpTransportTest {

    /** 发一条请求（可带 method/path/auth/body），收完整响应（短连接：读到流尾）。 */
    private fun exchange(port: Int, path: String, method: String = "POST", body: String, auth: String? = "Bearer t", closeOutput: Boolean = false): String =
        Socket("127.0.0.1", port).use { s ->
            val raw = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                auth?.let { append("Authorization: $it\r\n") }
                append("Content-Length: ").append(body.toByteArray(StandardCharsets.UTF_8).size).append("\r\n")
                append("Connection: close\r\n\r\n")
                append(body)
            }
            s.getOutputStream().write(raw.toByteArray(StandardCharsets.UTF_8))
            s.getOutputStream().flush()
            if (closeOutput) s.shutdownOutput()
            readAll(s.getInputStream())
        }

    private fun readAll(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toString("UTF-8")
    }

    @Test
    fun `绑定127-0-0-1并整链回写（解析-处理-回写全通）`() {
        val t = LoopbackHttpTransport {}
        val seen = arrayOfNulls<String>(1)
        try {
            assertTrue(t.start(McpServerTransport.Config("127.0.0.1", 0)) { req ->
                seen[0] = "${req.method} ${req.path} auth=${req.headers["authorization"]} body=${req.bodyText()}"
                McpServerTransport.Response(200, "{\"ok\":true}")
            })
            val endpoint = t.endpoint()!!
            assertTrue("绑定必须是回环地址：$endpoint", endpoint.startsWith("127.0.0.1:"))
            val port = endpoint.substringAfter(':').toInt()
            val raw = exchange(port, "/mcp", body = "{\"jsonrpc\":\"2.0\"}")
            assertTrue(raw.startsWith("HTTP/1.1 200"))
            assertTrue(raw.contains("Content-Type: application/json"))
            assertTrue(raw.contains("MCP-Protocol-Version: 2026-07-28"))
            assertTrue(raw.contains("Connection: close"))
            assertTrue(raw.trimEnd().endsWith("{\"ok\":true}"))
            assertEquals("POST /mcp auth=Bearer t body={\"jsonrpc\":\"2.0\"}", seen[0])
        } finally {
            t.stop()
        }
        assertFalse(t.isRunning())
        assertNull(t.endpoint())
    }

    @Test
    fun `停服后同端口必须让得出来（判据④的JVM侧证据）`() {
        val fixed = ServerSocket(0).use { it.localPort }
        val a = LoopbackHttpTransport {}
        assertTrue(a.start(McpServerTransport.Config("127.0.0.1", fixed)) { McpServerTransport.Response(200, "1") })
        assertTrue(exchange(fixed, "/mcp", body = "{}").startsWith("HTTP/1.1 200"))
        a.stop()
        val b = LoopbackHttpTransport {}
        try {
            assertTrue("停服后同端口必须可重绑", b.start(McpServerTransport.Config("127.0.0.1", fixed)) { McpServerTransport.Response(200, "2") })
            assertTrue(exchange(fixed, "/mcp", body = "{}").trimEnd().endsWith("2"))
        } finally {
            b.stop()
        }
    }

    @Test
    fun `半截头跟着关流 ⇒ 400不悬挂`() {
        val t = LoopbackHttpTransport {}
        try {
            assertTrue(t.start(McpServerTransport.Config("127.0.0.1", 0)) { McpServerTransport.Response(200, "{}") })
            val port = t.endpoint()!!.substringAfter(':').toInt()
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("POST /mcp HTTP/1.1\r\nContent-Length: 999\r\n".toByteArray())
                s.getOutputStream().flush()
                s.shutdownOutput()
                val raw = readAll(s.getInputStream())
                assertTrue("半截请求要 400 收场", raw.startsWith("HTTP/1.1 400"))
            }
        } finally {
            t.stop()
        }
    }

    @Test
    fun `拒绝非字面量绑定地址（fail-closed）`() {
        val t = LoopbackHttpTransport {}
        try {
            assertFalse("域名不许绑", t.start(McpServerTransport.Config("evil.example.com", 0)) { McpServerTransport.Response(200, "{}") })
            assertFalse("意外值不许绑", t.start(McpServerTransport.Config("169.254.1.1", 0)) { McpServerTransport.Response(200, "{}") })
            assertFalse(t.isRunning())
        } finally {
            t.stop()
        }
    }

    @Test
    fun `处理器抛异常回500不外泄原文`() {
        val t = LoopbackHttpTransport {}
        try {
            assertTrue(t.start(McpServerTransport.Config("127.0.0.1", 0)) { error("boom-secret") })
            val port = t.endpoint()!!.substringAfter(':').toInt()
            val raw = exchange(port, "/mcp", body = "{}")
            assertTrue(raw.startsWith("HTTP/1.1 500"))
            assertFalse("堆栈原文不许回给客户端", raw.contains("boom-secret"))
        } finally {
            t.stop()
        }
    }

    @Test
    fun `重复start幂等 stop两次幂等`() {
        val t = LoopbackHttpTransport {}
        try {
            assertTrue(t.start(McpServerTransport.Config("127.0.0.1", 0)) { McpServerTransport.Response(200, "{}") })
            assertTrue("running 时再 start 必须 no-op 不炸", t.start(McpServerTransport.Config("0.0.0.0", 0)) { McpServerTransport.Response(200, "{}") })
            assertTrue(t.endpoint()!!.startsWith("127.0.0.1:"))
        } finally {
            t.stop()
            t.stop()
        }
        assertFalse(t.isRunning())
    }
}
