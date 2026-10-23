// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.preview

import com.example.zhengdao.testkit.TestAssume
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.text.Charsets

/**
 * 刀 4 §2.1 —— [PreviewServer] 的判据：**真起 socket、真发请求、真读响应**。
 *
 * 覆盖派工单 **J2（路径不读盘，源码级）／J3（只 GET、HEAD）／J4（危险子资源名）
 * ／J5（端口顺延与全占失败）／J9（线程名登记）**。
 *
 * ## 两类断言的剥注释口径不一样，是刻意的
 * - 「某字样**必须出现**」⇒ 先剥注释再断言（否则 KDoc 里提一嘴就算过了，E-133 的老坑）。
 * - 「某字样**必须不出现**」（J2 的禁项）⇒ 用**原文**断言，注释里出现也算红。
 *    absence 类断言用原文只会"误红"不会"漏红"，宁严勿松；被测文件里也确实一处都没有。
 *
 * ## 每个用例都真起服务
 * 端口候选钉死 `7[0-9]00` 段，用例开头 [PreviewServer.start]、结尾 [PreviewServer.stop]。
 * 客户端用手写 `Socket`（不引第三方 HTTP 库，也不用 HttpURLConnection——它对
 * 方法名与重定向有主张，验不出"服务端原样回了 405"这种事实）。
 */
class PreviewServerTest {

    // ── 源码读取（cwd 可能是仓根或 app/，与仓库其它源码门禁同一兜底法） ──────

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    /** 剥注释（只剥整行 `//` 与块注释，不碰字符串里的 `//`——`http://` 必须留着）。 */
    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val serverSrc = "src/main/java/com/example/zhengdao/preview/PreviewServer.kt"
    private val slotsSrc = "src/main/java/com/example/zhengdao/preview/PreviewSlots.kt"
    private val threadLedger = "src/test/java/com/example/zhengdao/util/BareThreadGuardTest.kt"

    // ── 生命周期 ────────────────────────────────────────────────────────────

    private lateinit var bound: PreviewServer.StartResult.Bound

    @Before
    fun start() {
        PreviewSlots.resetForTests()
        val r = PreviewServer.start()
        assertTrue("预览服务起不来（前一个用例没收回端口？）：$r", r is PreviewServer.StartResult.Bound)
        bound = r as PreviewServer.StartResult.Bound
    }

    @After
    fun stop() {
        PreviewServer.stop()
        PreviewSlots.resetForTests()
    }

    // ── HTTP 小客户端 ───────────────────────────────────────────────────────

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    /**
     * 发一条手写请求，读状态行＋头＋到 EOF 的正文（服务端固定 `Connection: close`）。
     *
     * 按**字节**切头/正文：头是 ISO-8859-1（HTTP 的规定），正文按服务端的 `charset=utf-8`
     * 解 UTF-8。用 `InputStreamReader` 一路读到正文会把中文 HTML 解成乱码（第一版就栽在
     * 这儿——那是客户端的错，不是服务端的，所以修客户端、不动服务端）。
     */
    private fun ask(port: Int, request: String, readBody: Boolean = true): Reply {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            sock.getOutputStream().flush()
            val all = sock.getInputStream().readBytes()
            val sep = indexOfHeaderEnd(all)
            val headText = String(all, 0, if (sep < 0) all.size else sep, Charsets.ISO_8859_1)
            val bodyBytes = if (sep < 0 || !readBody) ByteArray(0) else all.copyOfRange(sep + 4, all.size)
            val lines = headText.split("\r\n")
            val headers = LinkedHashMap<String, String>()
            lines.drop(1).forEach { line ->
                if (line.isEmpty()) return@forEach
                val at = line.indexOf(':')
                if (at > 0) headers[line.substring(0, at).trim().lowercase()] = line.substring(at + 1).trim()
            }
            val bits = lines[0].split(' ')
            return Reply(
                bits.getOrNull(1)?.toIntOrNull() ?: -1,
                headers,
                String(bodyBytes, Charsets.UTF_8),
            )
        }
    }

    /** 找 `\r\n\r\n`（头与正文的分界）的起始下标；没有回 -1。 */
    private fun indexOfHeaderEnd(bytes: ByteArray): Int {
        if (bytes.size < 4) return -1
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 13.toByte() && bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() && bytes[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun get(port: Int, path: String) = ask(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

    /** 放一份预览并回它的 uuid（服务已起 ⇒ put 必成功）。 */
    private fun placeId(html: String, assets: Map<String, ByteArray> = emptyMap()): String {
        val r = PreviewSlots.put(html, assets)
        assertTrue("put 应成功（服务已起）：$r", r is PreviewSlots.PutResult.Placed)
        return (r as PreviewSlots.PutResult.Placed).id
    }

    // ── J3：只 GET/HEAD；未知 uuid 404 ──────────────────────────────────────

    @Test
    fun `J3_GET 命中给 200 与正文`() {
        val id = placeId("<p>抱朴的卡</p>")
        val r = get(bound.port, "/p/$id")
        assertEquals(200, r.status)
        assertTrue("正文必须是那份 HTML：${r.body}", r.body.contains("抱朴的卡"))
        assertEquals("no-store", r.headers["cache-control"])
        assertEquals("nosniff", r.headers["x-content-type-options"])
        assertEquals("close", r.headers["connection"])
        assertTrue(r.headers["content-type"]!!.startsWith("text/html"))
        assertEquals(r.body.toByteArray(Charsets.UTF_8).size.toString(), r.headers["content-length"])
    }

    @Test
    fun `J3_POST 只能 405 且带 Allow`() {
        val id = placeId("<p>卡</p>")
        val r = ask(
            bound.port,
            "POST /p/$id HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 4\r\n\r\nbody",
        )
        assertEquals("预览是只读的，POST 必须 405", 405, r.status)
        assertEquals("GET, HEAD", r.headers["allow"])
    }

    @Test
    fun `J3_HEAD 有长度无正文`() {
        val id = placeId("<p>卡</p>")
        val r = ask(bound.port, "HEAD /p/$id HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n", readBody = false)
        assertEquals(200, r.status)
        assertNotNull(r.headers["content-length"])
        assertTrue("HEAD 不许把正文吐出去", r.body.isEmpty())
    }

    @Test
    fun `J3_未知 uuid 与别的形态一律 404_同一固定响应体`() {
        val id = placeId("<p>卡</p>")
        val fixed = get(bound.port, "/p/00000000-0000-4000-8000-000000000000")
        assertEquals(404, fixed.status)
        val paths = listOf("/", "/index", "/p", "/p/", "/healthz", "/p/$id/extra/deep", "/P/$id")
        paths.forEach { p ->
            val r = get(bound.port, p)
            assertEquals("$p 必须 404（只认 /p/<uuid> 一种路径）", 404, r.status)
            assertEquals("404 响应体固定一行，不区分存在与否：$p", "preview not found\n", r.body)
        }
        assertEquals("preview not found\n", fixed.body)
    }

    @Test
    fun `J1 收尾_被淘汰的 id 在 HTTP 侧真的变 404`() {
        val id = placeId("<p>第一张</p>")
        assertEquals(200, get(bound.port, "/p/$id").status)
        repeat(8) { PreviewSlots.put("<p>挤位</p>") }
        assertEquals("放满后必须淘汰掉第一张", 1, PreviewSlots.stats().evicted)
        assertEquals("被淘汰的 url 必须变 404", 404, get(bound.port, "/p/$id").status)
    }

    // ── J4：子资源（从 assets 取；危险名直接拒且不抛） ───────────────────────

    @Test
    fun `J4_子资源命中给 200 与按扩展名的 Content-Type`() {
        val css = "body{color:red}".toByteArray(Charsets.UTF_8)
        val png = byteArrayOf(-119, 80, 78, 71)
        val id = placeId("<p>卡</p>", mapOf("app.css" to css, "logo.png" to png))
        val a = get(bound.port, "/p/$id/app.css")
        assertEquals(200, a.status)
        assertEquals("text/css; charset=utf-8", a.headers["content-type"])
        assertTrue(a.body.contains("color:red"))
        val b = get(bound.port, "/p/$id/logo.png")
        assertEquals(200, b.status)
        assertEquals("image/png", b.headers["content-type"])
        assertEquals(css.size.toString(), a.headers["content-length"])
        // 没有的子资源同样是 404，且不能把 HTML 顶上去
        assertEquals(404, get(bound.port, "/p/$id/nope.css").status)
    }

    @Test
    fun `J4_危险子资源名一律 404 且不抛异常`() {
        val id = placeId("<p>卡</p>", mapOf("app.css" to "x".toByteArray()))
        val hostile = listOf(
            "/p/$id/../x",
            "/p/$id/..%2f..%2fetc/passwd",
            "/p/$id//etc/passwd",
            "/p/$id/etc/passwd",
            "/p/$id/a\\b",
            "/p/$id/%2e%2e/style.css",
            "/p/$id/app.css/../../..",
        )
        hostile.forEach { p ->
            val reply = runCatching { get(bound.port, p) }
            assertTrue("J4：$p 处理时不许抛异常（抛了就是 accept 循环被打断）：${reply.exceptionOrNull()}", reply.isSuccess)
            assertEquals("J4：$p 必须 404（不做路径归一化，直接拒）", 404, reply.getOrThrow().status)
        }
    }

    // ── J5：端口顺延与全占失败 ──────────────────────────────────────────────

    /**
     * 用一个「占位 socket」把候选端口占住（J5 两条用例的地基）。
     *
     * ## 为什么这里必须 `reuseAddress = true`，而产品代码必须 `false`
     *
     * 产品侧（`PreviewServer.start`）用 `false` 是判据本身：**被占就顺延**。
     * 占位器用 `true` 只影响「我能不能抢到这个口」，**废不掉判据** ——
     * 有人在 LISTEN 时，另一个 `reuseAddress=false` 的 socket 照样绑不上
     * （SO_REUSEADDR 不许两个 LISTENING 抢同一个 addr:port）。
     *
     * ## 为什么要有界重试
     *
     * CI 红（run 39120611540：J5 两条都 `BindException` at .kt:238/:262）真因在**测试侧**：
     * 前面的用例连过这个端口，Linux 上服务端连接会留在 `TIME_WAIT`，此刻
     * `reuseAddress = false` 直接绑不上。Windows 上不复现 —— 同一份代码两种读数。
     *
     * 重试上界 3 秒；仍占不上就返回 null，由调用方 `Assume` **跳过并写明「本次 J5 未实测」**，
     * 绝不当成绿过、也绝不改成「假装被占」。
     */
    private fun bindBlocker(port: Int, budgetMs: Long = 3_000): ServerSocket? {
        val deadline = System.currentTimeMillis() + budgetMs
        while (true) {
            val s = ServerSocket()
            try {
                s.reuseAddress = true
                s.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
                return s
            } catch (e: java.net.BindException) {
                runCatching { s.close() }
                if (System.currentTimeMillis() >= deadline) return null
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun `J5_候选端口恰好十个且是 7 到 9 百段`() {
        assertEquals(10, PreviewServer.PORT_CANDIDATES.size)
        assertEquals(7000, PreviewServer.PORT_CANDIDATES.first())
        assertEquals(7900, PreviewServer.PORT_CANDIDATES.last())
    }

    @Test
    fun `J5_第一个候选被占_自动顺延到下一个`() {
        PreviewServer.stop()
        val blocker = bindBlocker(PreviewServer.PORT_CANDIDATES.first())
        TestAssume.assume(
            condition = blocker != null,
            reason = "本次 J5（顺延）未实测：候选端口 ${PreviewServer.PORT_CANDIDATES.first()} 3 秒内占不上" +
                "（多半是并行的别的用例还拿着它）",
        )
        try {
            val r = PreviewServer.start()
            assertTrue("顺延失败：$r", r is PreviewServer.StartResult.Bound)
            assertEquals(
                "第一个候选被占就该顺延到第二个",
                PreviewServer.PORT_CANDIDATES[1],
                (r as PreviewServer.StartResult.Bound).port,
            )
            val id = (PreviewSlots.put("<p>顺延后还能放</p>") as PreviewSlots.PutResult.Placed).id
            assertEquals(200, get(r.port, "/p/$id").status)
        } finally {
            runCatching { blocker?.close() }
        }
    }

    @Test
    fun `J5_全部候选被占_put 明确失败并说清原因`() {
        PreviewServer.stop()
        val blockers = mutableListOf<ServerSocket>()
        try {
            PreviewServer.PORT_CANDIDATES.forEach { p ->
                val s = bindBlocker(p) ?: return@forEach
                blockers.add(s)
            }
            TestAssume.assume(
                condition = blockers.size == PreviewServer.PORT_CANDIDATES.size,
                reason = "本次 J5（全占）未实测：只占到 ${blockers.size}/10 个候选端口" +
                    "（多半是并行的别的用例还拿着剩下的）",
            )
            val r = PreviewServer.start()
            assertTrue("全占时不许假装起来：$r", r is PreviewServer.StartResult.Failed)
            val reason = (r as PreviewServer.StartResult.Failed).reason
            assertTrue("原因要数清候选：$reason", reason.contains("10 个候选端口"))
            assertTrue("原因要带上每个口的失败形状：$reason", reason.contains("7000="))
            val put = PreviewSlots.put("<p>卡</p>")
            assertTrue("起不来时 put 必须失败：$put", put is PreviewSlots.PutResult.Failed)
            assertTrue(
                "put 的失败原因必须把服务侧那句话原样带给调用方",
                (put as PreviewSlots.PutResult.Failed).reason.contains("全部被占"),
            )
        } finally {
            blockers.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `停服之后_put 转入明确失败而不是给一个连不上的 url`() {
        val id = (PreviewSlots.put("<p>卡</p>") as PreviewSlots.PutResult.Placed).id
        assertEquals(200, get(bound.port, "/p/$id").status)
        PreviewServer.stop()
        val put = PreviewSlots.put("<p>再来一张</p>")
        assertTrue("停了还回 url 就是静默失败：$put", put is PreviewSlots.PutResult.Failed)
        assertTrue((put as PreviewSlots.PutResult.Failed).reason.contains("预览服务已停止"))
        assertTrue(PreviewServer.port() == null)
    }

    // ── J2 与 J9：源码级断言 ────────────────────────────────────────────────

    @Test
    fun `J2_服务侧没有任何按请求路径读盘的入口`() {
        val src = raw(serverSrc) // absence 断言用原文：注释里出现也算红
        listOf(
            "File(", "java.io.File", "FileInputStream", "FileOutputStream", "RandomAccessFile",
            "readBytes(", "readText(", "readLines(", "openStream", "assets.open",
            "Files.", "Paths.", "getPath", "getAbsolutePath", "absolutePath",
        ).forEach { needle ->
            assertFalse("J2 硬红线：PreviewServer 里出现了 $needle —— 路径参与了磁盘操作", src.contains(needle))
        }
        // 反向钉：uuid 只进 map（唯一的取用点就是 PreviewSlots.get）
        val stripped = code(serverSrc)
        assertTrue("uuid 必须只当 map 的 key：找不到 PreviewSlots.get(", stripped.contains("PreviewSlots.get("))
        assertTrue("子资源必须只从 assets 的 map 里查", stripped.contains("slot.assets[name]"))
    }

    @Test
    fun `J2_槽侧同样不碰磁盘与网络`() {
        val src = raw(slotsSrc)
        listOf("File(", "java.io.File", "readBytes(", "readText(", "ServerSocket", "Socket(")
            .forEach { assertFalse("PreviewSlots 是纯逻辑，不许出现 $it", src.contains(it)) }
    }

    @Test
    fun `J2_只绑环回_不许出现通配地址`() {
        val stripped = code(serverSrc)
        assertEquals("127.0.0.1", PreviewServer.BIND_HOST)
        assertFalse("出现 0.0.0.0 就等于把预览开到局域网", stripped.contains("0.0.0.0"))
        assertTrue(
            "bind 必须用 BIND_HOST 常量（唯一真相源），不许裸写字面量",
            stripped.contains("InetSocketAddress(InetAddress.getByName(BIND_HOST), candidate)"),
        )
    }

    @Test
    fun `J9_预览线程名已在 BareThreadGuardTest 登记`() {
        val ledger = raw(threadLedger)
        // 134 批起对账单判据是「文件名＋行内容」（不再是 文件:行号）。
        assertTrue(
            "zd-preview-http 必须进对账单（J9）",
            ledger.contains("PreviewServer.kt|") || ledger.contains("PreviewServer.kt:"),
        )
        val stripped = code(serverSrc)
        assertTrue("线程必须起名", stripped.contains("\"zd-preview-http\""))
        assertTrue("线程必须 daemon", stripped.contains("isDaemon = true"))
    }

    @Test
    fun `服务是幂等的_重复 start 不回第二个端口`() {
        val again = PreviewServer.start()
        assertTrue("第二次 start 应回报 AlreadyBound：$again", again is PreviewServer.StartResult.AlreadyBound)
        assertEquals(bound.port, (again as PreviewServer.StartResult.AlreadyBound).port)
        assertTrue(PreviewServer.isRunning())
    }
}
