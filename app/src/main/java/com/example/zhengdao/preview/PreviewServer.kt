// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.preview

import com.example.zhengdao.rootfs.RunLog
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.text.Charsets

/**
 * 预览服务（刀 4 §2.1 第二块）：**手写的最小 HTTP**，只有一个用途——把 [PreviewSlots]
 * 里的内存内容按 `GET /p/<uuid>` 吐出去。不引第三方库、不起子进程。
 *
 * ## 安全口径（每一条都有判据，写在这里是为了让门禁钉得住）
 *
 * | 口径 | 为什么 | 判据 |
 * |---|---|---|
 * | 只 bind [BIND_HOST]＝`127.0.0.1` | 预览内容是刚做出来的卡片，可能带用户隐私；绑到 0.0.0.0 等于把整个局域网当成收件人 | J2 附带（与桥同一口径） |
 * | **永不按请求路径读盘** | 路径由外部可控字符串拼出来 ⇒ 一条 `../../` 就能把设备上任何可读文件端出去。这里 uuid 只当 **map 的 key**，子资源只从 [PreviewSlots.Slot.assets] 里查，查不到就 404 | **J2（源码级断言）** |
 * | 只认 `/p/<uuid>` 一种路径 | 少一种形状就少一类漏判；`/`、`/index`、`/healthz` 之类一律 404（与"未知 uuid"同一固定响应体，不区分存在与否） | J3 |
 * | 只允许 GET/HEAD，其余 405 | 预览是只读的；POST/PUT 没有任何语义，留着就是攻击面 | J3 |
 * | 子资源名里出现 `..`、`/`、`\` ⇒ 直接 404 | **不做路径归一化**（归一化＝自己实现一遍解析器，而解析器最容易错在这里） | J4 |
 * | `Cache-Control: no-store` ＋ `X-Content-Type-Options: nosniff` | 不留缓存（预览是一次性的）；不嗅探类型（防一张 png 被当 html 执行） | J3 附带 |
 * | 端口起不来 ⇒ 明确失败并带原因 | 静默不启动的话，调用方拿着 url 只会看到一个连不上的端口，查不到根因 | J5 |
 *
 * ## 一条连接一个请求
 * 响应头固定带 `Connection: close`，不做 keep-alive、不解析 body。好处：accept 循环
 * 可以**串行**处理（只用一条线程），不必给每条连接再起一条——预览的负载是「打开一张卡」
 * 的十几条子资源请求，逐条毫秒级完成，串行足够，且线程数与并发数脱钩后
 * `BareThreadGuardTest` 的对账单只多一条（J9）。
 *
 * ## 线程
 * 一条 `zd-preview-http`（有名＋daemon，登记进对账单）。`soTimeout` 让 accept 每秒醒来
 * 一次看 `running` 标志 ⇒ [stop] 能把线程收回来，而不是靠进程退出顺手收拾。
 */
object PreviewServer {

    /** 只绑环回。写成常量是为了让门禁钉得住（字面量会被顺手改成 0.0.0.0 而没有任何测试变红）。 */
    const val BIND_HOST = "127.0.0.1"

    /** 固定端口候选：`7[0-9]00` 段共 10 个。占用就顺延下一个，全占 ⇒ put 明确失败（J5）。 */
    val PORT_CANDIDATES: List<Int> = (0 until 10).map { 7000 + it * 100 }

    private const val PATH_PREFIX = "/p/"
    // 线程名不用常量：`BareThreadGuardTest` 的扫描器判「有名」看的是构造函数里的字符串字面量
    // （`Thread(r, "名字")` 或紧随其后的 `name = "…"`），传常量会让门禁判成无名 ⇒ 名字必须写死在这。
    private const val BACKLOG = 16
    private const val ACCEPT_TIMEOUT_MS = 1_000
    private const val MAX_REQUEST_LINES = 64

    /** 404 的固定响应体：一行、不区分「uuid 不存在」与「已被淘汰」（§2.1）。 */
    private val NOT_FOUND_BODY: ByteArray = "preview not found\n".toByteArray(Charsets.UTF_8)

    /** start() 的结果：起来了 / 早就在跑 / 起不来（带原因）。 */
    sealed class StartResult {
        class Bound(val port: Int) : StartResult()
        class AlreadyBound(val port: Int) : StartResult()
        class Failed(val reason: String) : StartResult()
    }

    private val lock = Any()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var boundPort: Int? = null

    @Volatile
    private var running = false

    private var acceptor: Thread? = null

    // ── 生命周期 ────────────────────────────────────────────────────────────

    /**
     * 起服务（幂等：已经在跑就回报 [StartResult.AlreadyBound]）。
     *
     * 十个候选端口按顺序试；**每个失败都记进原因串**，全失败时既回 [StartResult.Failed]
     * 又把这个原因交给 [PreviewSlots.release]——此后 `put` 会把同一句话原样回给调用方
     * （§2.1「不许静默不启动」）。
     */
    fun start(): StartResult {
        synchronized(lock) {
            val now = boundPort
            if (running && now != null) return StartResult.AlreadyBound(now)
            val failures = mutableListOf<String>()
            for (candidate in PORT_CANDIDATES) {
                val srv = ServerSocket()
                // 显式关掉"复用地址"：Java 在 Windows 上把 SO_REUSEADDR 的语义改成「别人占着的
                // 口我也能绑」，那会让"端口被占就顺延"（J5）在这台开发机上根本测不出来。
                // 关掉之后，被占就是干净的 BindException ⇒ 顺延，真机口径也一致。
                srv.reuseAddress = false
                val opened = runCatching {
                    srv.bind(InetSocketAddress(InetAddress.getByName(BIND_HOST), candidate), BACKLOG)
                    srv.soTimeout = ACCEPT_TIMEOUT_MS
                }
                if (opened.isFailure) {
                    runCatching { srv.close() }
                    val kind = opened.exceptionOrNull()?.javaClass?.simpleName ?: "未知异常"
                    failures.add("$candidate=$kind")
                    continue
                }
                server = srv
                boundPort = candidate
                running = true
                val t = Thread({ acceptLoop() }, "zd-preview-http").apply { isDaemon = true }
                acceptor = t
                t.start()
                val base = "http://$BIND_HOST:$candidate"
                PreviewSlots.bind(base)
                log("预览服务已启动：$base（候选端口第 ${PORT_CANDIDATES.indexOf(candidate) + 1} 个）")
                return StartResult.Bound(candidate)
            }
            val reason = "预览服务起不来：$BIND_HOST 的 ${PORT_CANDIDATES.size} 个候选端口" +
                "（${PORT_CANDIDATES.first()}–${PORT_CANDIDATES.last()}）全部被占：" +
                failures.joinToString(", ")
            PreviewSlots.release(reason)
            log(reason)
            return StartResult.Failed(reason)
        }
    }

    /** 当前端口（没起来 ⇒ null）。 */
    fun port(): Int? = boundPort

    /** 在跑与否（回执/测试用）。 */
    fun isRunning(): Boolean = running && boundPort != null

    /**
     * 停服务：关监听、把线程收回来（靠 soTimeout 最多 1 秒后醒）、并让 [PreviewSlots]
     * 转入「明确失败」状态——停了之后 put 不会返回一个连不上的 url。
     * 槽里的内容不清掉：重新 start() 后同一 uuid 仍可访问（同进程内不泄漏，随进程死）。
     */
    fun stop() {
        var dying: Thread? = null
        synchronized(lock) {
            running = false
            runCatching { server?.close() }.onFailure { log("预览监听关闭异常：${it.message}") }
            server = null
            boundPort = null
            dying = acceptor
            acceptor = null
        }
        // 收回线程：accept 带 soTimeout ⇒ 最多 1 秒就醒来看清 running 已假（join 只是等它醒）
        runCatching { dying?.join(ACCEPT_TIMEOUT_MS * 3L) }
        PreviewSlots.release("预览服务已停止（PreviewServer.stop 调过，重新 start 才能 put）")
    }

    // ── accept 循环 ─────────────────────────────────────────────────────────

    /** 串行处理：一条连接读完一个请求就写响应、由本循环关连接。异常只记日志，不许把线程搞死。 */
    private fun acceptLoop() {
        while (running) {
            val srv = server ?: break
            val sock = runCatching { srv.accept() }
                .onFailure { if (running && it !is SocketTimeoutException) log("预览 accept 失败：${it.message}") }
                .getOrNull()
            if (sock == null) continue
            runCatching { handle(sock) }.onFailure { if (running) log("预览请求处理异常：${it.message}") }
            runCatching { sock.close() }.onFailure { log("预览连接关闭异常：${it.message}") }
        }
    }

    // ── 一个请求 ────────────────────────────────────────────────────────────

    /**
     * 读请求行＋请求头（**body 一个字节都不读**：预览只有 GET/HEAD，多余的字节随连接关闭丢掉，
     * 这样一条超大 body 也拖不住串行循环），判路，写响应。
     */
    private fun handle(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine()
        if (requestLine.isNullOrBlank()) return
        var headerLines = 0
        while (headerLines < MAX_REQUEST_LINES) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headerLines++
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) {
            respond(sock, 400, "GET", null, "bad request\n".toByteArray(Charsets.UTF_8))
            return
        }
        val method = parts[0].uppercase()
        if (method != "GET" && method != "HEAD") {
            respond(sock, 405, method, null, "method not allowed\n".toByteArray(Charsets.UTF_8))
            return
        }
        route(sock, method, parts[1])
    }

    /**
     * 路由：`/p/<uuid>` ⇒ 那份 HTML；`/p/<uuid>/<name>` ⇒ 从 assets 里取子资源；其余全 404。
     *
     * `uuid` 到这里只用于 [PreviewSlots.get]（map 查 key），`name` 只用于
     * [PreviewSlots.Slot.assets] 的 map 查 key——两者都不参与任何磁盘构造（J2）。
     */
    private fun route(sock: Socket, method: String, rawPath: String) {
        // query/fragment 不参与路由：浏览器会给 url 补 `?`，但这里没有任何按查询串变化的行为
        val path = rawPath.substringBefore('?').substringBefore('#')
        if (!path.startsWith(PATH_PREFIX)) {
            respond(sock, 404, method, null, NOT_FOUND_BODY)
            return
        }
        val rest = path.removePrefix(PATH_PREFIX)
        val slash = rest.indexOf('/')
        val id = if (slash < 0) rest else rest.substring(0, slash)
        val name = if (slash < 0) null else rest.substring(slash + 1)
        if (id.isEmpty()) {
            respond(sock, 404, method, null, NOT_FOUND_BODY)
            return
        }
        val slot = PreviewSlots.get(id)
        if (slot == null) {
            respond(sock, 404, method, null, NOT_FOUND_BODY)
            return
        }
        if (name == null) {
            respond(sock, 200, method, HTML_TYPE, slot.html.toByteArray(Charsets.UTF_8))
            return
        }
        // 危险名直接拒，不做归一化（J4）：`..`、`/`、`\` 任一出现 ⇒ 404
        if (name.contains("..") || name.contains('/') || name.contains('\\')) {
            respond(sock, 404, method, null, NOT_FOUND_BODY)
            return
        }
        val asset = slot.assets[name]
        if (asset == null) {
            respond(sock, 404, method, null, NOT_FOUND_BODY)
            return
        }
        respond(sock, 200, method, contentTypeOf(name), asset)
    }

    /** 写响应：状态行＋六个头＋空行＋（非 HEAD 时）实体。 */
    private fun respond(sock: Socket, status: Int, method: String, type: String?, body: ByteArray) {
        val payload = if (method == "HEAD") ByteArray(0) else body
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append(CRLF)
            append("Content-Type: ").append(type ?: "text/plain; charset=utf-8").append(CRLF)
            append("Content-Length: ").append(body.size).append(CRLF)
            append("Cache-Control: no-store").append(CRLF)
            append("X-Content-Type-Options: nosniff").append(CRLF)
            if (status == 405) append("Allow: GET, HEAD").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        val out = BufferedOutputStream(sock.getOutputStream())
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.flush()
    }

    // ── 纯函数小工具（internal ⇒ JVM 单测直接钉，不必起 socket） ─────────────

    /**
     * Content-Type 按扩展名（§2.1 点名的六类；其余 `application/octet-stream`）。
     * 扩展名取**最后一个点之后**；没有点 ⇒ octet-stream。大小写不敏感。
     */
    internal fun contentTypeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> HTML_TYPE
            "css" -> "text/css; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

    /** 状态码 → reason phrase（只列本服务真会回的那几个）。 */
    internal fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    private const val CRLF = "\r\n"
    private const val HTML_TYPE = "text/html; charset=utf-8"

    /** 一切异常只落 RunLog（现场看得见）；写日志自身失败不许把服务拖死。 */
    private fun log(line: String) {
        runCatching { RunLog.log("预览槽：$line") }
    }
}
