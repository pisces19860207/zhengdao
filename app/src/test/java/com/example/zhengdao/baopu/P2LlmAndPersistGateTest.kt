// 第五十六批 · 并发/落库 P2 六件（审计第三批 A 段）的门禁。
//
// 分层：
//   ① 源码门禁——六件里有五件的「接没接上」只能钉在源码结构上（引擎要真 SQLite ＋
//      真网络；runLoop 归真机，与 RunEngineTest 头部口径一致）；
//   ② 行为用例——P2-5 的「取消后不再回调成功」用本地只连不回的 ServerSocket 真跑一次
//      （不新增依赖，java.net 就够）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class P2LlmAndPersistGateTest {

    private val llmPath = "src/main/java/com/example/zhengdao/baopu/LlmClient.kt"
    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun code(path: String): String {
        val raw = File(path).readText()
        return raw.lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    /** 取某个函数体（从 `fun <name>(` 到下一个同级 `fun `/文件尾）。 */
    private fun bodyOf(path: String, signature: String): String {
        val src = code(path)
        val i = src.indexOf(signature)
        assertTrue("找不到 $signature（$path）", i >= 0)
        val rest = src.substring(i + signature.length)
        val next = rest.indexOf("\n    fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    // ── P2-3：三处落库等提交 + 事件在事务外 ─────────────────────────────

    @Test
    fun `P2_3_三处消息落库都等提交`() {
        val engine = code(enginePath)
        for (fn in listOf(
            "private fun persistUserMessage(",
            "private fun persistAssistantMessage(",
            "private fun persistToolExchange(",
        )) {
            val body = bodyOf(enginePath, fn)
            assertTrue("$fn 必须 .get() 等事务提交（旧实现丢弃 Future ⇒ 写失败静默）", body.contains(".get()"))
        }
    }

    @Test
    fun `P2_3_MessagePersisted不许出现在事务体内`() {
        val engine = code(enginePath)
        for (fn in listOf("private fun persistUserMessage(", "private fun persistAssistantMessage(")) {
            val body = bodyOf(enginePath, fn)
            val begin = body.indexOf("d.beginTransaction()")
            val end = body.indexOf("d.endTransaction()")
            assertTrue("$fn 找不到事务边界", begin >= 0 && end > begin)
            val inside = body.substring(begin, end)
            assertFalse(
                "$fn 在事务体内就发 MessagePersisted ⇒ UI 可能先收到事件、库里还没有",
                inside.contains("MessagePersisted"),
            )
            assertTrue("$fn 必须在事务外发事件", body.substring(end).contains("MessagePersisted"))
        }
    }

    @Test
    fun `P2_3_RunRules新增流等待上限常量`() {
        assertEquals("等一轮模型流的总上限（90s）", 90_000L, RunRules.STREAM_WAIT_TIMEOUT_MS)
    }

    // ── P2-4：OkHttp 进程级共享 ────────────────────────────────────────

    @Test
    fun `P2_4_LlmClient用共享client_不再每个实例新建`() {
        val src = code(llmPath)
        assertTrue(
            "LlmClient 必须持 sharedHttp（进程级共享）",
            src.contains("private val http: OkHttpClient = sharedHttp"),
        )
        assertFalse(
            "不许再 private val http = defaultHttp()（那等于每个客户端新建一套连接池）",
            src.contains("private val http: OkHttpClient = defaultHttp()"),
        )
        // defaultHttp() 只允许出现在 companion 的 sharedHttp 初始化里
        val companionAt = src.indexOf("companion object")
        assertTrue("找不到 companion object", companionAt >= 0)
        val beforeCompanion = src.substring(0, companionAt)
        assertFalse(
            "类体里不许再调 defaultHttp()（只有 companion 单例初始化能调）",
            beforeCompanion.contains("defaultHttp()"),
        )
        assertTrue("companion 里必须有 sharedHttp 单例", src.substring(companionAt).contains("val sharedHttp: OkHttpClient by lazy { defaultHttp() }"))
    }

    // ── P2-5：可取消 + 等待有上限 ──────────────────────────────────────

    @Test
    fun `P2_5_streamAsync返回Call句柄`() {
        val src = code(llmPath)
        assertTrue(
            "streamAsync 必须返回 Call（否则调用方无法取消）",
            src.contains("fun streamAsync(messages: List<LlmMessage>, tools: JSONArray? = null, sink: (LlmEvent) -> Unit): Call"),
        )
    }

    @Test
    fun `P2_5_等待有上限_不许无参await`() {
        val src = code(enginePath)
        assertFalse("不许再 latch.await() 无参（通道只连上不收数据会挂死引擎线程）", src.contains("latch.await()"))
        assertTrue(
            "必须带总上限等（RunRules.STREAM_WAIT_TIMEOUT_MS）",
            src.contains("latch.await(RunRules.STREAM_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)"),
        )
    }

    @Test
    fun `P2_5_stop必须取消正在飞的Call`() {
        val body = bodyOf(enginePath, "fun stop() {")
        assertTrue("stop() 必须取消 activeCall", body.contains("activeCall?.cancel()"))
    }

    @Test
    fun `P2_5_streamRound把句柄存进字段`() {
        val body = bodyOf(enginePath, "private fun streamRound(")
        assertTrue("streamRound 必须保存 Call 句柄", body.contains("activeCall = call"))
        assertTrue("流结束要清句柄（不许一直持有上一个 Call）", body.contains("activeCall = null"))
        assertTrue("超时必须取消 Call", body.contains("call.cancel()"))
    }

    /**
     * 行为用例：本地 ServerSocket **只连上、什么都不回**，然后取消。
     *
     * 断言「取消后不再回调成功」——`stream()` 对 CancellationException 是重新抛出的
     * （不当失败报），所以正确的表现是**一个成功事件都没有**。
     */
    @Test
    fun `P2_5_行为_取消后不再回调成功`() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val acceptor = Thread {
            runCatching {
                server.accept().use { sock ->
                    // 收下连接后故意什么都不写：让客户端干等
                    Thread.sleep(3_000)
                    sock.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                }
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
        try {
            val provider = Provider(
                id = "p2-test",
                label = "P2 测试通道",
                protocol = LlmProtocol.OPENAI_COMPAT,
                baseUrl = "http://127.0.0.1:$port",
                model = "test-model",
                apiKey = "k",
            )
            val client = LlmClient(provider)
            val events = CopyOnWriteArrayList<LlmEvent>()
            val call = client.streamAsync(
                listOf(LlmMessage(LlmMessage.ROLE_USER, "hi")),
                null,
            ) { ev -> events.add(ev) }

            Thread.sleep(400) // 让请求真的发出去、连接真的建立
            call.cancel()     // 用户点停止
            Thread.sleep(600) // 给底层异常一点时间（不该有任何成功事件）

            assertTrue("取消后不许回调成功：$events", events.none { it is LlmEvent.Finished })
            assertTrue("取消后不该有正文增量：$events", events.none { it is LlmEvent.Delta })
        } finally {
            server.close()
        }
    }
}
