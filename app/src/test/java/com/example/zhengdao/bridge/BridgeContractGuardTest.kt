// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥连接契约测试（§B 61，2026-10-15）。
 *
 * 背景：newTab 在合法 URL 下偶发「agent 收到 TCP 零字节关闭」（设备侧与宿主侧 curl 均
 * RC=52），违背第㉓刀契约「桥的每条命令都必须回 JSON，失败也要回，不许静默断连」。
 * 旧实现 `runCatching { handle(...) }.onFailure { close(sock) }` 把异常整个吞掉：
 * 不回包、不落日志——观测黑洞。修复后 [BrowserAgentBridge.serveGuarded] 保证：
 * handle 抛任何异常 ⇒ 尽力回一条 500 JSON + 异常类型/消息进 RunLog，再关 socket。
 *
 * 本文件用**真本机 socket**（127.0.0.1 临时端口，不碰外网）做正负例自证：
 * - 负例：伪造一个抛异常的 handle ⇒ 客户端必须收到 500 + JSON 错误，而非裸断连；
 * - 正例：handle 正常写 200 响应 ⇒ 客户端原样收到，且 Content-Length 与实体一致；
 * - 源码门禁：acceptLoop 不许回退到 `onFailure { close(sock) }` 的旧形态。
 *
 * 注：RunLog 在 JVM 单测里未初始化（appContext == null），其内部走 android.util.Log 桩
 * （`unitTests.isReturnDefaultValues = true`），serveGuarded 又对日志包了 runCatching，
 * 契约路径不会被日志拖炸——这正是负例要顺带验证的。
 */
class BridgeContractGuardTest {

    // ── 基础设施：本机 socket 上的最小 HTTP 客户端/服务端 ──────────────────

    /** 起一个临时监听，返回 (serverSocket, 端口)。 */
    private fun listen(): Pair<ServerSocket, Int> {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        return ss to ss.localPort
    }

    /** 一次性 HTTP 客户端：连上、（可选）发原始请求、把响应读到 EOF（服务端 Connection: close）。
     *  默认**不发请求**：服务端未读数据就 close 会触发 RST（Connection reset），契约测试
     *  只关心响应到达与 EOF，不模拟半途而废的请求——那归 production 的读超时路径。 */
    private fun fetch(port: Int, request: String = ""): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            // 服务端 finally 必关 socket ⇒ 读到 EOF（-1）即完整响应
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            val input = s.getInputStream()
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
            }
            return String(buf.toByteArray(), Charsets.UTF_8)
        }
    }

    /** 在服务端线程上跑 [block]（模拟 acceptLoop 里 pool.execute 的那一发）。
     *  不等 accept：客户端连接由内核 listen backlog 兜住，线程起来即可去连。 */
    private fun serveInBackground(
        ss: ServerSocket,
        block: (Socket) -> Unit,
    ): Thread {
        return Thread {
            val sock = ss.accept()
            block(sock)
        }.apply { start() }
    }

    /** 拆响应：status 行 / 头 / 体（按 Content-Length 截，防未来 keep-alive 语义变化）。 */
    private fun parse(raw: String): Triple<String, Map<String, String>, String> {
        val headEnd = raw.indexOf("\r\n\r\n")
        assertTrue("响应里没有头体分隔（\\r\\n\\r\\n）", headEnd > 0)
        val head = raw.substring(0, headEnd)
        val body = raw.substring(headEnd + 4)
        val lines = head.split("\r\n")
        val headers = lines.drop(1).associate {
            val i = it.indexOf(':')
            it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
        }
        return Triple(lines[0], headers, body)
    }

    // ── 负例自证：handle 抛异常 ⇒ 必须收到 JSON 错误，而非 TCP 零字节断连 ──

    @Test
    fun `负例_handle 抛异常_客户端必须收到 500 与 JSON 错误`() {
        val (ss, port) = listen()
        try {
            val server = serveInBackground(ss) { sock ->
                BrowserAgentBridge.serveGuarded(sock) {
                    throw RuntimeException("伪造异常：handle 在半路炸了")
                }
            }
            val raw = fetch(port)
            server.join(5_000)
            assertFalse("负例失败：客户端什么都没收到（回到 RC=52 老路）", raw.isEmpty())
            val (status, headers, body) = parse(raw)
            assertEquals("HTTP/1.1 500 Internal Server Error", status)
            assertTrue("错误体必须是桥的 JSON 错误形态", body.contains("\"ok\":false"))
            assertTrue(
                "JSON 错误里必须带异常类型（诊断用）",
                body.contains("RuntimeException"),
            )
            assertTrue(
                "JSON 错误里必须带异常消息（诊断用）",
                body.contains("伪造异常"),
            )
            // Content-Length 必须与实体字节数一致（curl 会按它校验截断）
            val declared = headers["content-length"]?.toIntOrNull()
            assertNotNull("缺 Content-Length", declared)
            assertEquals(
                "Content-Length 与实体字节数不符",
                declared,
                body.toByteArray(Charsets.UTF_8).size,
            )
        } finally {
            ss.close()
        }
    }

    // ── 正例自证：正常路径 200 原样到达 ────────────────────────────────────

    @Test
    fun `正例_handle 正常写响应_客户端收到 200 与完整 JSON`() {
        val (ss, port) = listen()
        try {
            val server = serveInBackground(ss) { sock ->
                BrowserAgentBridge.serveGuarded(sock) {
                    BrowserAgentBridge.respondOn(sock, 200, "{\"ok\":true,\"note\":\"契约正例\"}")
                }
            }
            val raw = fetch(port)
            server.join(5_000)
            val (status, headers, body) = parse(raw)
            assertEquals("HTTP/1.1 200 OK", status)
            assertTrue(body.contains("\"ok\":true"))
            assertTrue(body.contains("契约正例"))
            assertEquals(
                "Content-Length 与实体字节数不符",
                headers["content-length"]?.toIntOrNull(),
                body.toByteArray(Charsets.UTF_8).size,
            )
        } finally {
            ss.close()
        }
    }

    // ── 契约收尾：两条路都必关 socket（不泄漏连接） ────────────────────────

    @Test
    fun `异常路径收尾必关 socket_客户端读到 EOF 而不是挂死`() {
        val (ss, port) = listen()
        try {
            val server = serveInBackground(ss) { sock ->
                BrowserAgentBridge.serveGuarded(sock) { throw IllegalStateException("boom") }
            }
            // fetch() 内部读到 EOF 才返回 ⇒ 能返回本身就是「关了 socket」的判据
            val raw = fetch(port)
            server.join(5_000)
            assertTrue(raw.isNotEmpty())
            assertFalse("server 线程不该还活着（可能卡在泄漏的连接上）", server.isAlive)
        } finally {
            ss.close()
        }
    }

    // ── 源码门禁：acceptLoop 不许回退到「吞异常只关 socket」的旧形态 ───────

    /** 门禁读源码的口径与 BrowserBridgeGateTest 一致（工作目录 = app/）。 */
    private fun bridgeSource(): String {
        val rel = "src/main/java/com/example/zhengdao/bridge/BrowserAgentBridge.kt"
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `门禁_acceptLoop 必须经 serveConnection_不许回退 onFailure 只关 socket`() {
        val src = bridgeSource()
        assertTrue(
            "acceptLoop 必须把每条连接交给 serveConnection（契约外壳）",
            src.contains("pool.execute { serveConnection(app, sock) }"),
        )
        assertFalse(
            "旧形态 `onFailure { close(sock) }` 回来了——它就是 §B 61 的观测黑洞，不许再出现",
            src.contains("onFailure { close(sock) }"),
        )
    }
}
