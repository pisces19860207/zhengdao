// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.preview

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket

/**
 * 第六十批 · P2-8：[PreviewServer] 接受连接后必须设**请求超时**。
 *
 * ## 故障形态（为什么这条是真 bug，不是洁癖）
 *
 * 预览服务是**单线程串行**：`accept → handle（读一行请求）→ 关连接 → 再 accept`。
 * 如果连上来的客户端一个字节都不发，`reader.readLine()` 会永远阻塞在那条唯一的工作线程上
 * ⇒ 之后**所有**预览请求一起卡住，日志里一条报错都没有（线程没死，只是在等）。
 * 浏览器标签被冻结、半开连接、局域网扫描器都能触发。
 *
 * ## 判据怎么钉
 *
 * - 行为级：真起 socket，连上不发数据 ⇒ 服务端必须在 `REQUEST_TIMEOUT_MS` 量级内**主动关**
 *   （客户端 `read()` 读到 EOF＝-1），且**紧接着**一条正常 GET 仍能 200（证明串行循环没被钉死）。
 * - 「不许秒关」：如果实现退化成 accept 之后立刻 close，本用例的 EOF 也会成立 —— 所以同时
 *   断言耗时不下于超时的一半，并用「后面的正常请求」兜住功能性。
 * - 源码级：**请求超时是单列常量**，不许拿 accept 的 1 秒轮询间隔当请求超时用
 *   （两者语义相反：一个是醒来查停止标志的频率，一个是容忍慢客户端的上界）。
 *
 * ## 只用 java.net
 *
 * 不引第三方 HTTP 库、不用 HttpURLConnection（它对方法名/重定向有主张）。
 * 客户端自己也设 soTimeout：用例宁可在 6 秒内失败，也不许挂住整个测试套件。
 */
class SilentClientTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val serverSrc = "src/main/java/com/example/zhengdao/preview/PreviewServer.kt"

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

    /** 手写一条 GET，只回状态码（本用例不关心正文）。 */
    private fun statusOf(port: Int, path: String): Int {
        Socket(InetAddress.getByName("127.0.0.1"), port).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            sock.getOutputStream().flush()
            val head = String(sock.getInputStream().readBytes(), Charsets.ISO_8859_1)
            return head.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
        }
    }

    @Test
    fun `P2-8_连上不发数据的客户端被超时关掉_服务继续可用`() {
        val id = (PreviewSlots.put("<p>超时之后的正常请求</p>") as PreviewSlots.PutResult.Placed).id
        val started = System.currentTimeMillis()
        Socket(InetAddress.getByName("127.0.0.1"), bound.port).use { silent ->
            // 客户端自己的读超时给足余量：正常情况服务端先关，我们读到 EOF 就返回
            silent.soTimeout = PreviewServer.REQUEST_TIMEOUT_MS + 2_000
            val first = silent.getInputStream().read()
            val cost = System.currentTimeMillis() - started
            assertEquals("服务端必须在请求超时后主动关连接（读到 EOF），实际读到 $first", -1, first)
            assertTrue(
                "不许「accept 完立刻关」——那会把正常请求也一起关掉（本次 ${cost}ms）",
                cost >= PreviewServer.REQUEST_TIMEOUT_MS / 2,
            )
            assertTrue(
                "止损必须在超时量级内（本次 ${cost}ms，上限 ${PreviewServer.REQUEST_TIMEOUT_MS + 2_000}ms）",
                cost <= PreviewServer.REQUEST_TIMEOUT_MS + 2_000,
            )
        }
        // 串行循环没被钉死：静默连接之后，普通请求照常
        assertEquals("静默客户端之后服务必须还能正常响应", 200, statusOf(bound.port, "/p/$id"))
    }

    @Test
    fun `P2-8_请求超时是单列常量_不复用 accept 轮询间隔`() {
        val src = code(serverSrc)
        assertTrue("必须有明确的请求超时常量", src.contains("const val REQUEST_TIMEOUT_MS"))
        assertTrue("accept 出来的连接必须设读超时", src.contains("sock.soTimeout = REQUEST_TIMEOUT_MS"))
        assertTrue("超时当坏请求：要单独记一行日志（现场看得见）", src.contains("预览请求超时"))
        assertTrue(
            "超时要走 SocketTimeoutException 分支（不许混进「处理异常」里，否则日志看不出是慢客户端）",
            src.contains("SocketTimeoutException") && src.contains("e is SocketTimeoutException"),
        )
        assertFalse(
            "不许把 accept 的轮询间隔当请求超时用（语义相反：1 秒会冤枉真机慢 WebView）",
            src.contains("sock.soTimeout = ACCEPT_TIMEOUT_MS"),
        )
        assertTrue("请求超时至少要大于 accept 轮询间隔，否则连正常握手都可能被掐", PreviewServer.REQUEST_TIMEOUT_MS >= 2_000)
    }
}
