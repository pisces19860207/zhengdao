package com.example.zhengdao.bridge

import android.content.Context
import android.util.Log
import com.example.zhengdao.rootfs.RunLog
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 浏览器 Agent 桥的 HTTP 端点（设计文档 §4.5「端点与鉴权」）。
 *
 * ## 形态
 *
 * ```
 * 终端 agent（proot，同设备）
 *   │  HTTP POST 127.0.0.1:14080/bridge/v1/<cmd>   (+ Authorization: Basic browser:<token>)
 *   ▼
 * ┌───────────────────────────────────────────────┐
 * │ BrowserAgentBridge（本类）                     │
 * │   ① 验 token（BridgeToken.matches）            │
 * │   ② 路径 → 命令名 → BridgePolicy.checkCommand  │
 * │   ③ 取 body 里的参数 → BrowserCommands.<cmd>   │
 * │   ④ 响应体一律过一遍"凭据键名"过滤（双保险）     │
 * └───────────────────────────────────────────────┘
 * ```
 *
 * ## 为什么自研 HTTP 而不是引库
 *
 * 只需要：`POST` 一个路径、读一个 `Authorization` header、读一个 body、回一段 JSON。
 * 引 NanoHTTPD/Ktor server 会带进几十个传递依赖（项目"零新增依赖"纪律），
 * 而这一层用 `ServerSocket` + 手写 HTTP/1.1 解析约 150 行就能覆盖，且**只认白名单形态**：
 * 别的 Method、别的路径、畸形请求行，一律 400/404/405，不猜。
 *
 * ## 三条硬纪律
 *
 * 1. **只绑 `127.0.0.1`**（[BridgePolicy.BIND_HOST]）—— 绝不绑 `0.0.0.0`。
 *    绑错一个字符，这条通道就从"本机环回"变成"对着全网开放"，且**不会有任何编译错误或测试变红**。
 *    源码门禁 `BrowserBridgeGateTest` 有一条专门钉它。
 * 2. **每个连接一个线程、线程全 daemon**：进程要能直接退出（不留僵尸线程）。
 *    复用项目既有的 daemon executor 纪律（`zd-` 前缀命名，照 `BareThreadGuardTest` 的口径：
 *    这里用 `Executors.newFixedThreadPool` + 自定义 [ThreadFactory] 取名 + setDaemon，
 *    不走裸 `Thread(` —— 那会顶红裸线程门禁）。
 * 3. **默认关**：[start] 只在用户于「浏览器桥」设置页打开开关时才调；
 *    进程内单例（[instance] 竞争用 CAS 保证只有一个在听）。
 *
 * ## 与抱朴的关系（设计文档 §4.5 注）
 *
 * 抱朴（内置 OpenCode）**不接入本桥** —— 它是 App 内独立 agent，浏览器能力由其自身
 * 插件机制提供。本桥只服务「用户直接操作 + 终端外部 agent（Claude/Codex/Hermes/AGY）」。
 */
object BrowserAgentBridge {

    private const val TAG = "BrowserAgentBridge"

    /** 单连接读超时（毫秒）：agent 发一半挂住不该拖住桥。 */
    private const val SO_TIMEOUT_MS = 15_000

    /** 响应里的 Server 头（不暴露框架信息）。 */
    private const val SERVER_NAME = "zhengdao-bridge/1"

    private val running = AtomicBoolean(false)

    @Volatile
    private var server: ServerSocket? = null

    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "zd-bridge-http").apply { isDaemon = true }
    }

    /** 是否正在监听（设置页开关的判据）。 */
    fun isRunning(): Boolean = running.get()

    /**
     * 启动监听。已在跑则直接返回 true（幂等）。
     *
     * @return true = 现在正在监听
     */
    fun start(context: Context): Boolean {
        if (running.get()) return true
        val app = context.applicationContext
        val ss = runCatching { ServerSocket() }.getOrElse {
            RunLog.log("浏览器桥：建 socket 失败：${it.javaClass.simpleName}")
            return false
        }
        runCatching { ss.reuseAddress = true }
        val bound = runCatching {
            ss.bind(InetSocketAddress(BridgePolicy.BIND_HOST, BridgePolicy.PORT))
        }.isSuccess
        if (!bound) {
            runCatching { ss.close() }
            RunLog.log("浏览器桥：绑不上 ${BridgePolicy.BIND_HOST}:${BridgePolicy.PORT}（端口被占？）")
            return false
        }
        server = ss
        running.set(true)
        // 先置 running 再起 acceptor：否则 stop() 与这里的竞争会让连接漏处理
        pool.execute { acceptLoop(app, ss) }
        RunLog.log("浏览器桥：已监听 ${BridgePolicy.BIND_HOST}:${BridgePolicy.PORT}（默认关，本次由用户开启）")
        return true
    }

    /** 停止监听（幂等）。已建立的连接由读超时自然结束。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        server = null
        RunLog.log("浏览器桥：已停止监听")
    }

    // ── 接受连接 ───────────────────────────────────────────────────────────

    private fun acceptLoop(app: Context, ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val sock = runCatching { ss.accept() }.getOrNull() ?: break
            // §B 61（2026-10-15）：handle 抛任何异常都不许只 close(sock) —— 那是观测黑洞，
            // agent 只看到 TCP 零字节关闭（curl RC=52），连异常类型都没留下。
            // 契约（第㉓刀）：每条命令必须回 JSON，失败也要回。见 serveConnection。
            pool.execute { serveConnection(app, sock) }
        }
    }

    /**
     * 每条连接的**契约外壳**（§B 61）：[handle] 正常返回 = 响应已写出；
     * 抛任何异常 = 尽力回一条 500 JSON 错误再关 socket，并把异常类型/消息记入 RunLog。
     *
     * 为什么拆出 [serveGuarded]：JVM 单测没有 Context，契约测试直接喂 [serveGuarded]
     * 一个会抛异常的假 handle（负例自证：必须收到 JSON 而非断连）。
     */
    internal fun serveConnection(app: Context, sock: Socket) {
        serveGuarded(sock) { handle(app, sock) }
    }

    /** [serveConnection] 的无 Context 内核（单测入口）。无论成败，收尾必关 socket。 */
    internal fun serveGuarded(sock: Socket, block: () -> Unit) {
        val t0 = System.nanoTime()
        try {
            block()
            logSafely("浏览器桥：连接处理完成（${elapsedMs(t0)}ms，thread=${Thread.currentThread().name}）")
        } catch (t: Throwable) {
            // RunLog 落日志必须先于回错误响应：日志是最后防线（连 JSON 都写不出去时也得有它）
            logSafely(
                "浏览器桥：连接处理异常（${t.javaClass.name}：${t.message}，" +
                    "thread=${Thread.currentThread().name}，${elapsedMs(t0)}ms）——尽力回 JSON 错误再关闭",
            )
            runCatching {
                respondOn(sock, 500, err("桥内部错误：${t.javaClass.simpleName}：${t.message ?: "无消息"}"))
            }.onFailure { writeFailure ->
                logSafely(
                    "浏览器桥：错误响应也写不出去（${writeFailure.javaClass.name}：${writeFailure.message}）" +
                        "——对端大概率已断开，仅留本日志",
                )
            }
        } finally {
            close(sock)
        }
    }

    /** 从 accept 起算的已耗毫秒（JVM/真机通用，不用 SystemClock）。 */
    private fun elapsedMs(t0Nanos: Long): Long = (System.nanoTime() - t0Nanos) / 1_000_000

    /**
     * 日志兜底：[RunLog.log] 自身永不抛（生产），但 JVM 单测里未初始化的 RunLog 会走到
     * android.util.Log 桩——契约外壳的**任何一步都不许因日志而二次炸**，包一层 runCatching。
     */
    private fun logSafely(line: String) {
        runCatching { RunLog.log(line) }
    }

    // ── 单连接处理 ─────────────────────────────────────────────────────────

    private fun handle(app: Context, sock: Socket) {
        sock.soTimeout = SO_TIMEOUT_MS
        sock.use { s ->
            // 第㊷刀扩围：请求解析改**字节级**。原来整条请求走 UTF-8 Reader、body 按
            // Content-Length 个**字符**读——但 Content-Length 是**字节数**，中文一字
            // 3 字节 ⇒ 非 ASCII 请求体必错位（实测 type 带中文 ⇒ 空回 RC=52；
            // ASCII 碰巧一字一字节所以此前没暴露）。头是 ASCII，按 ISO-8859-1 逐行；
            // body 按字节数读全再整体 UTF-8 解码（readRequestBody）。
            val input = java.io.BufferedInputStream(s.getInputStream())
            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))

            // ── 请求行：只认 `POST /bridge/v1/<cmd> HTTP/1.1` ──
            val requestLine = readByteLine(input)
            if (requestLine == null) {
                // §B 61：对端没发请求行就断了——这是一条「不回包就关闭」的合法路径，
                // 但必须留痕，否则它和「异常吞掉」在观测上无法区分。
                logSafely("浏览器桥：agent 在请求行前就断开（EOF，无响应关闭）")
                return
            }
            val parts = requestLine.split(" ")
            if (parts.size < 3) return respond(writer, 400, err("请求行不完整"))
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val version = parts[2]
            if (method != "POST") {
                return respond(writer, 405, err("只接受 POST（GET 会被当成探测，一律拒）"))
            }
            if (!version.startsWith("HTTP/1.")) {
                return respond(writer, 400, err("只支持 HTTP/1.x"))
            }
            // §B 61：每条请求落一行（含线程名），与 serveGuarded 的完成/异常行、
            // BrowserTabManager 的 before/after-webview 探针按时间轴对齐——
            // 上次 newTab 偶发零字节断连（RC=52）就是因为没有这条链路日志。
            logSafely("浏览器桥：<- ${requestLine.take(120)}（thread=${Thread.currentThread().name}）")

            // ── headers：只取 Authorization 与 Content-Length ──
            var authHeader: String? = null
            var contentLength = 0
            while (true) {
                val line = readByteLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                when (name) {
                    "authorization" -> authHeader = value
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                }
            }

            // ── ① 验 token（在路由之前：没过鉴权的请求不该知道命令是否存在）──
            val token = runCatching { BridgeToken.getOrCreate(app) }.getOrNull()
            if (token == null || !BridgeToken.matches(token, authHeader)) {
                return respond(writer, 401, err("鉴权失败：Authorization 应为 Basic base64(\"browser:<token>\")"))
            }

            // ── ② 路由 + 白名单 ──
            val cmdName = BridgePolicy.commandFromPath(rawPath)
                ?: return respond(writer, 404, err("路径不认识：$rawPath（应为 /bridge/v1/<cmd>）"))
            val cv = BridgePolicy.checkCommand(cmdName)
            if (cv is BridgePolicy.Verdict.Deny) return respond(writer, 403, err(cv.reason))

            // ── ③ body：按 Content-Length 的**字节数**读全，再整体 UTF-8 解码 ──
            if (contentLength > BridgePolicy.MAX_BODY_BYTES) {
                return respond(writer, 413, err("请求体超上限（${BridgePolicy.MAX_BODY_BYTES} 字节）"))
            }
            val body = if (contentLength > 0) {
                readRequestBody(input, contentLength)
                    ?: return respond(writer, 400, err("请求体没读全（对端提前断开）"))
            } else {
                ""
            }

            val json = dispatch(app, cmdName, body)
            respond(writer, 200, json)
        }
    }

    // ── 字节级请求解析（第㊷刀扩围，纯函数可单测） ──────────────────────────

    /**
     * 按 HTTP 契约读请求体：Content-Length 是**字节数**，所以这里读满
     * [contentLength] 个**字节**再整体按 UTF-8 解码。绝不能用 Reader 按「字符数」
     * 读——中文一字 3 字节，字符数 ≠ 字节数（本刀实测：按字符读 ⇒ type 带中文
     * 必空回 RC=52）。读不满（对端提前断开）返回 null，由调用方回 400。
     */
    internal fun readRequestBody(input: java.io.InputStream, contentLength: Int): String? {
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bytes, read, contentLength - read)
            if (n < 0) return null
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 读一行字节（到 `\n` 为止，容忍 `\r\n`），按 ISO-8859-1 解码——请求行与
     * 头部按规约是 ASCII/字节安全字段，不按 UTF-8 猜。流末尾且一行都没读时返回
     * null（对齐旧 `BufferedReader.readLine` 的 EOF 语义）。
     */
    internal fun readByteLine(input: java.io.InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1)
            }
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                val end = if (arr.isNotEmpty() && arr[arr.size - 1] == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, end, Charsets.ISO_8859_1)
            }
            buf.write(b)
        }
    }

    // ── body 解析（极简 JSON：只取扁平的 "key":"value" 与 "key":123） ──────

    /**
     * 从 body 里取一个字符串/数字参数；取不到返回 null。
     *
     * 极简而不是真 JSON 解析：桥的 body 只有一层扁平字段（url/selector/text/mode/tabId…），
     * 不值得为此引一个解析器（android 的 org.json 在 JVM 单测里是 stub，引了也测不了）。
     * 代价：**不支持嵌套对象/数组转义** —— 而桥根本不需要。真需要时再换，别提前优化。
     */
    internal fun param(body: String, key: String): String? {
        // "key":"value"（value 里的 \" 已转义）
        val strRe = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        strRe.find(body)?.let { m ->
            return m.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        // "key":123 / true / false（数字与布尔按字符串取用）
        val rawRe = Regex("\"$key\"\\s*:\\s*(-?[0-9]+|true|false)")
        rawRe.find(body)?.let { return it.groupValues[1] }
        return null
    }

    // ── 分发 ───────────────────────────────────────────────────────────────

    private fun dispatch(app: Context, cmdName: String, body: String): String {
        val cmd = BridgePolicy.Command.fromWire(cmdName) ?: return err("命令不在白名单：$cmdName")
        // ㊽-C（§B 78，2026-10-16）：七条「操作标签」的命令统一透传 body 里的 tabId ——
        // 给了就按 id 路由（BrowserCommands.target），缺省/空白仍落活跃标签（老调用方零改动）。
        // 此前这七条只走活跃标签，body 里的 tabId 被静默吞掉（真机实证 N4）。
        return when (cmd) {
            BridgePolicy.Command.STATUS -> BrowserCommands.status(param(body, "tabId"))
            BridgePolicy.Command.SNAPSHOT -> BrowserCommands.snapshot(
                param(body, "mode"),
                param(body, "selector"),
                param(body, "tabId"),
            )
            BridgePolicy.Command.EXEC_JS -> BrowserCommands.execJs(
                param(body, "code"),
                param(body, "tabId"),
            )
            BridgePolicy.Command.CLICK -> BrowserCommands.click(
                param(body, "selector"),
                param(body, "tabId"),
            )
            BridgePolicy.Command.TYPE -> BrowserCommands.type(
                param(body, "selector"),
                param(body, "text"),
                param(body, "tabId"),
            )
            BridgePolicy.Command.SCROLL -> BrowserCommands.scroll(
                param(body, "x"),
                param(body, "y"),
                param(body, "selector"),
                param(body, "tabId"),
            )
            BridgePolicy.Command.NAVIGATE -> BrowserCommands.navigate(
                param(body, "url"),
                param(body, "tabId"),
            )
            BridgePolicy.Command.NEW_TAB -> BrowserCommands.newTab(app, param(body, "url"))
            BridgePolicy.Command.ACTIVE_TAB -> BrowserCommands.activeTab(param(body, "tabId"))
            BridgePolicy.Command.CLOSE_TAB -> BrowserCommands.closeTab(param(body, "tabId"))
            BridgePolicy.Command.SCREENSHOT -> BrowserCommands.screenshot(
                app,
                param(body, "tabId"),
                param(body, "fullPage"),
                // 刀 4 §2.3（蓝总管裁决追加，限本支）：只搬运 selector，校验仍在 BrowserCommands 里走
                // BridgePolicy.checkSelector——其余 10 条命令、param() 解析器、鉴权/监听一律不动。
                param(body, "selector"),
            )
        }
    }

    // ── 响应 ───────────────────────────────────────────────────────────────

    /** 直接对 socket 写响应（serveGuarded 的错误兜底用；internal 供契约单测复用）。 */
    internal fun respondOn(sock: Socket, code: Int, json: String) {
        val writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))
        respond(writer, code, json)
    }

    private fun respond(writer: BufferedWriter, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
            else -> "Error"
        }
        writer.write("HTTP/1.1 $code $reason\r\n")
        writer.write("Server: $SERVER_NAME\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()
        writer.write(String(bytes, Charsets.UTF_8))
        writer.flush()
    }

    private fun err(msg: String): String = "{\"ok\":false,\"error\":\"${escape(msg)}\"}"

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun close(sock: Socket) {
        runCatching { sock.close() }
    }

    @Suppress("unused")
    private fun debugLog(msg: String) = Log.d(TAG, msg)
}
