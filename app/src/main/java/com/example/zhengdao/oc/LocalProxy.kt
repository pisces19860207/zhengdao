// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：java.net.ServerSocket / Socket（POSIX TCP 语义）与 HTTP/1.1 报文格式（RFC 7230）。
package com.example.zhengdao.oc

import android.util.Base64
import com.example.zhengdao.rootfs.RunLog
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 本地透传代理（太极 v1 鉴权桥，2026-10-06）。
 *
 * 为什么存在：OpenCode v2 serve 的 API 走 HTTP Basic 鉴权，而 Android WebView 的
 * fetch/XHR 收到 401 **不会**触发 onReceivedHttpAuthRequest（真机实测），SPA 拿不到
 * 凭据就卡死在启动页。WebView 侧无法给 fetch 注入全局 header（shouldInterceptRequest
 * 拿不到 POST body）——所以在 App 内做一层 TCP 透传：监听 127.0.0.1:PROXY_PORT，
 * 给每个请求的头部块注入 Authorization 后原样转发到 serve 端口。
 *
 * 透传是字节级的：SSE（event stream）、chunked、POST body 全部原样通过。
 */
object LocalProxy {

    const val PROXY_PORT = 14001

    @Volatile
    private var server: ServerSocket? = null

    private val running = AtomicBoolean(false)

    fun start(upstreamPort: Int, passwordProvider: () -> String?) {
        android.util.Log.w("TaijiProxy", "start() called, running=${running.get()}")
        if (running.getAndSet(true)) return
        thread(name = "oc-proxy") {
            try {
                val ss = ServerSocket(PROXY_PORT, 64, InetAddress.getByName("127.0.0.1"))
                server = ss
                android.util.Log.w("TaijiProxy", "listening 127.0.0.1:$PROXY_PORT -> :$upstreamPort")
                RunLog.log("太极: 本地代理就绪 127.0.0.1:$PROXY_PORT -> :$upstreamPort")
                while (running.get()) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    thread(name = "oc-proxy-conn") {
                        try {
                            handle(client, upstreamPort, passwordProvider)
                        } catch (_: Throwable) {
                            runCatching { client.close() }
                        }
                    }
                }
                android.util.Log.w("TaijiProxy", "accept loop exited (running=${running.get()})")
            } catch (t: Throwable) {
                android.util.Log.w("TaijiProxy", "bind/loop error: ${t.javaClass.simpleName} ${t.message}")
                RunLog.log("太极: 本地代理异常 ${t.message}")
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    /**
     * 单连接处理：**单请求单连接语义**——读请求头+body → 注入 Authorization →
     * 转发 → 双向泵响应直到断开。请求头强制 Connection: close，让浏览器为每个
     * 新请求开新连接（每请求都经过注入；keep-alive 复用会让后续请求裸奔 401，
     * 真机实测 ERR_HTTP_RESPONSE_CODE_FAILURE）。SSE 长流不受影响（字节级透传）。
     */
    private fun handle(client: Socket, upstreamPort: Int, passwordProvider: () -> String?) {
        client.tcpNoDelay = true
        val upstream = Socket(InetAddress.getByName("127.0.0.1"), upstreamPort)
        upstream.tcpNoDelay = true
        val cin = client.getInputStream()
        val cout = client.getOutputStream()
        val uout = upstream.getOutputStream()

        // 读取请求头部块（直到 \r\n\r\n）
        val headBytes = readHeaderBlock(cin) ?: run {
            runCatching { cout.close() }; runCatching { upstream.close() }; runCatching { client.close() }
            return
        }
        var head = String(headBytes, Charsets.ISO_8859_1)
        val contentLength = Regex("(?i)content-length:\\s*(\\d+)").find(head)
            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        // 重写：剥客户端自带 Authorization + 强制 close + 注入我们的 Basic
        head = head.lines()
            .filter { !it.startsWith("Authorization:", ignoreCase = true) }
            .filter { !it.startsWith("Connection:", ignoreCase = true) }
            .joinToString("\r\n")
        val pw = passwordProvider() ?: ""
        val basic = Base64.encodeToString("opencode:$pw".toByteArray(), Base64.NO_WRAP)
        head = head.trimEnd('\r', '\n') +
            "\r\nAuthorization: Basic $basic\r\nConnection: close\r\n\r\n"
        uout.write(head.toByteArray(Charsets.ISO_8859_1))
        uout.flush()

        // 透传请求 body（按 Content-Length 精确读）
        if (contentLength > 0) copyExactly(cin, uout, contentLength)

        // 双向泵：客户端剩余（如有）→ 上游；上游响应（含 SSE 长流）→ 客户端
        val up = thread(name = "oc-proxy-up") { pump(cin, uout) }
        pump(upstream.getInputStream(), cout)
        up.join(3000)
        runCatching { cout.flush() }
        runCatching { client.close() }
        runCatching { upstream.close() }
    }

    /** 读 HTTP 头部块（到 \r\n\r\n 为止）；连接断开返回 null。 */
    private fun readHeaderBlock(cin: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val one = ByteArray(1)
        while (buf.size() < 65536) {
            val r = cin.read(one)
            if (r < 0) return null
            buf.write(one, 0, r)
            val b = buf.toByteArray()
            if (b.size >= 4 &&
                b[b.size - 4] == '\r'.code.toByte() && b[b.size - 3] == '\n'.code.toByte() &&
                b[b.size - 2] == '\r'.code.toByte() && b[b.size - 1] == '\n'.code.toByte()
            ) return b
        }
        return null
    }

    /** 精确复制 n 字节（请求 body）。 */
    private fun copyExactly(input: InputStream, output: OutputStream, n: Long) {
        val buf = ByteArray(16 * 1024)
        var remaining = n
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r < 0) return
            output.write(buf, 0, r)
            remaining -= r
        }
        output.flush()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val r = try {
                input.read(buf)
            } catch (_: Throwable) {
                return
            }
            if (r < 0) return
            try {
                output.write(buf, 0, r)
                output.flush()
            } catch (_: Throwable) {
                return
            }
        }
    }
}
