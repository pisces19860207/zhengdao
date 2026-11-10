// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端的**传输层缝**＋本仓自带的**最小环回 HTTP 实现**。
//
// ── TODO(548) 边界（派工单原话级）─────────────────────────────────────────
// 「传输层与凭据库**复用 548**（548 未合时按接口名实现并写 TODO）」。548 未合入，
// 所以传输先立 [McpServerTransport] 接口（548 合入后由其传输实现顶替，调用点不动），
// 本文件同时交付一个**只够本单用**的 HTTP/1.1 最小实现 [LoopbackHttpTransport]：
//   · 只认 `POST /mcp`（JSON-RPC 一次一来回，无 SSE/无长连——Streamable HTTP 的
//     无状态 JSON 模式；GET 一律 405，stdio 边界外不做）；
//   · **绑定地址是闸不是配置装饰**：默认 `127.0.0.1`（判据⑤），换 `0.0.0.0` 必须
//     显式开 bindAll（局域网可选档）——公网档不存在，本文件没有那个分支；
//   · 纯 java.net（零新增依赖是仓里的红线），线程名 `zd-mcp-*`（判据⑥的主线程
//     取证靠它：accept/conn/ctl 三类线程都不可能是 main）。
package com.example.zhengdao.baopu.mcp.server

import java.io.BufferedInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 传输层接口（**548 的缝**）：把「字节」翻译成 [Request] 交给处理方、把 [Response] 写回去。
 *
 * 实现方只管三件事：绑在哪（[Config.host]）、跑没跑（[isRunning]）、怎么停（[stop]）。
 * 鉴权/白名单/租约/审计/停机五闸一概不归它——那是 [McpServerCore] 的活。
 */
interface McpServerTransport {

    /** 绑定配置。host 只许是字面量地址（`127.0.0.1` / `0.0.0.0`），域名不收。 */
    data class Config(val host: String, val port: Int)

    /** 一条已解析的入站请求（body 已按 Content-Length 收齐）。 */
    class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun bodyText(): String = String(body, StandardCharsets.UTF_8)
    }

    /** 一条待写回的响应（body 为空串 ⇒ 202/204 这类无内容语义，由 status 决定）。 */
    data class Response(
        val status: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8",
    )

    fun interface Handler {
        fun handle(request: Request): Response
    }

    /** 绑定并开始服务；失败（端口占用/无权限）返回 false 并已记日志，**不抛**。 */
    fun start(config: Config, handler: Handler): Boolean

    /** 停：关监听、清连接、回收线程。停完 [isRunning] 必须 false，端口必须让得出来。 */
    fun stop()

    fun isRunning(): Boolean

    /** 跑起来之后的绑定端点（`127.0.0.1:8765`）；没跑 ⇒ null（判据⑤取证用）。 */
    fun endpoint(): String?
}

/**
 * 最小环回 HTTP/1.1 实现（**全仓唯一的 ServerSocket 绑定点**——门禁测试钉着这条）。
 *
 * 三条硬纪律：
 * 1. **绑定前先核字面量**：host 不是 `127.0.0.1`/`0.0.0.0`/`localhost` 直接拒——
 *    防有人把域名/意外值塞进来造成"以为只绑本机其实绑到了别的卡"；
 * 2. **短连接**：每条请求一答一关（`Connection: close`）。MCP 客户端的长连/SSE
 *    归 548 的传输实现，本实现不为它背复杂度；
 * 3. **有界**：头部 32KB、body 4MB、读超时 10s——夹具与自配客户端都远用不满，
 *    超了就 400/413/超时断开，不留悬挂连接。
 */
class LoopbackHttpTransport(
    private val log: (String) -> Unit = {},
) : McpServerTransport {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var bound: String? = null

    @Volatile
    private var running: Boolean = false

    private var connPool: ExecutorService? = null

    private val connSeq = AtomicLong(0)

    @Synchronized
    override fun start(config: McpServerTransport.Config, handler: McpServerTransport.Handler): Boolean {
        if (running) return true
        val addr = addressOf(config.host) ?: run {
            log("[mcp-server] 拒绝绑定非字面量地址：${mask(config.host)}")
            return false
        }
        try {
            val socket = ServerSocket(config.port, BACKLOG, addr)
            serverSocket = socket
            running = true
            bound = "${socket.inetAddress.hostAddress}:${socket.localPort}"
            connPool = Executors.newCachedThreadPool { r ->
                Thread(r, "zd-mcp-conn-${connSeq.incrementAndGet()}").apply { isDaemon = true }
            }
            val t = Thread({ acceptLoop(socket, handler) }, "zd-mcp-accept").apply { isDaemon = true }
            acceptThread = t
            t.start()
            log("[mcp-server] 传输层已绑定 $bound（白名单外一律不收）")
            return true
        } catch (e: Exception) {
            running = false
            bound = null
            serverSocket = null
            log("[mcp-server] 传输层绑定失败（${e.javaClass.simpleName}）：端口 ${config.port}")
            return false
        }
    }

    @Synchronized
    override fun stop() {
        if (!running && serverSocket == null) return
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { connPool?.shutdownNow() }
        connPool = null
        acceptThread?.let { runCatching { it.join(STOP_JOIN_MS) } }
        acceptThread = null
        log("[mcp-server] 传输层已停（端口释放）")
        bound = null
    }

    override fun isRunning(): Boolean = running

    override fun endpoint(): String? = bound?.takeIf { running }

    /** accept 循环：每条连接丢给连接池；池已停/套接字已关就退出。 */
    private fun acceptLoop(socket: ServerSocket, handler: McpServerTransport.Handler) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) log("[mcp-server] accept 断（${e.javaClass.simpleName}）")
                break
            }
            val pool = connPool ?: break
            runCatching { pool.submit { serve(client, handler) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    /** 单连接：读一条请求 → 交 [handler] → 写回 → 关（短连接）。 */
    private fun serve(client: Socket, handler: McpServerTransport.Handler) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            client.use { s ->
                val req = readRequest(s)
                if (req == null) {
                    runCatching {
                        writeResponse(s, McpServerTransport.Response(400, "{\"error\":\"bad_request\"}"))
                    }
                    return
                }
                val resp = try {
                    handler.handle(req)
                } catch (e: Exception) {
                    // 处理器崩了也是 500，但原文只进日志不回客户端（不外泄堆栈）。
                    log("[mcp-server] 处理器异常（${e.javaClass.simpleName}: ${e.message}）")
                    McpServerTransport.Response(500, "{\"error\":\"internal_error\"}")
                }
                writeResponse(s, resp)
            }
        } catch (e: Exception) {
            // 读/写半路的断连：夹具按 Ctrl-C 是常态，不值得一行账。
            if (e !is SocketTimeoutException && e !is EOFException) {
                log("[mcp-server] 连接异常（${e.javaClass.simpleName}）")
            }
        }
    }

    /** 解析一条 HTTP 请求；不合法 ⇒ null（调用方直接断开，回 400 交 [serve] 前置分支）。 */
    private fun readRequest(s: Socket): McpServerTransport.Request? {
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return null
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) return null
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) return null
            read += n
        }
        return McpServerTransport.Request(parts[0].uppercase(), parts[1], headers, body)
    }

    /** 按 CRLFCRLF 收头部；超界/断流 ⇒ null。 */
    private fun readHead(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        var prev2 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (prev2 == '\r'.code && prev == '\n'.code && b == '\r'.code) {
                // 还差一个 \n——上面 append 已含 \r，再读一位；此刻 sb 以 \r 结尾，去掉它
                // 就是"最后一行 + \r\n"的完整头部（CRLFCRLF 的前一个 \r 正是误入的那个）。
                val last = input.read()
                if (last != '\n'.code) return null
                return sb.substring(0, sb.length - 1)
            }
            if (sb.length > MAX_HEAD) return null
            prev2 = prev
            prev = b
        }
    }

    private fun writeResponse(s: Socket, resp: McpServerTransport.Response) {
        val body = resp.body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(resp.status).append(' ').append(statusText(resp.status)).append("\r\n")
            append("Content-Type: ").append(resp.contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("MCP-Protocol-Version: ").append(MCP_PROTOCOL).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        s.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            if (body.isNotEmpty()) write(body)
            flush()
        }
    }

    /** host 字面量 → InetAddress；不认识 ⇒ null（start 拒绑）。 */
    private fun addressOf(host: String): InetAddress? = when (host.trim()) {
        "127.0.0.1", "localhost" -> runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull()
        "0.0.0.0" -> runCatching { InetAddress.getByName("0.0.0.0") }.getOrNull()
        else -> null
    }

    /** 日志里地址只脱敏不删（万一有人塞了带内部信息的东西，账上不留原文）。 */
    private fun mask(raw: String): String =
        if (raw.length <= 3) "***" else raw.take(3) + "***"

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    companion object {
        private const val BACKLOG = 16
        private const val MAX_HEAD = 32 * 1024
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val READ_TIMEOUT_MS = 10_000
        private const val STOP_JOIN_MS = 1_000L

        /** 与客户端侧 [com.example.zhengdao.baopu.mcp.McpJsonRpc.PROTOCOL] 同源的唯一常量。 */
        const val MCP_PROTOCOL = "2026-07-28"
    }
}
