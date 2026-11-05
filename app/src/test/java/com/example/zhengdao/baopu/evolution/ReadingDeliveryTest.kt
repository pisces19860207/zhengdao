// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十一批 · 读数送达通道的行为门禁。
//
// 判据①（用户）：假 URL 正例可读——注入假传输喂一条好读数，送达落收件箱、ProofReading.pick 选得中。
// 判据②（用户）：四种非法读数各一条弃用——好行照落，坏行一个不进收件箱（弃用判据只认构造器）。
// 判据③（用户）：断网不崩——异常就地接住、一行诊断、退避写入；同轮/节流期不取。
// 判据④（用户）：非白名单 URL 拒——白名单全字匹配那一个常量，其余当场拒。
// 判据⑤（用户）：release 也能用——零 Android／零 debug 闸／零 run-as，源级钉缝防拆。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class ReadingDeliveryTest {

    private lateinit var root: File

    private fun inbox(): File = File(root, ProofReading.READINGS_FILE)

    private fun stateFile(): File = File(root, ReadingDelivery.STATE_FILE)

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("reading-delivery-").toFile()
        DecisionLedger.install(root)
        EvolutionWiring.resetForTest()
        ReadingDelivery.resetForTest()
    }

    @After
    fun tearDown() {
        ReadingDelivery.resetForTest()
        runCatching { root.deleteRecursively() }
        EvolutionWiring.resetForTest()
    }

    // ── 判据① 假 URL 正例可读 ───────────────────────────────────────────────

    @Test
    fun `判据①_假URL正例_送达落收件箱_pick选得中_ref不被pick烧`() {
        val called = ArrayList<String>()
        ReadingDelivery.transport = { url ->
            called.add(url)
            goodRow(ref = "ci-461-1")
        }
        val outcome = ReadingDelivery.attemptOnce(NOW)
        assertTrue("送达要成功", outcome.ok)
        assertEquals("远端 1 行", 1, outcome.remoteLines)
        assertEquals("新落 1 行", 1, outcome.appended)
        assertEquals("传输收到的必须就是白名单常量", listOf(ReadingDelivery.READINGS_URL), called)
        assertEquals("收件箱恰好 1 行", 1, inbox().readLines().filter { it.isNotBlank() }.size)

        // 与 380 链路对上：pick 拿得到这条读数，且 pick 不消耗 ref（消耗归 runner）。
        val reading = ProofReading.pick(PROPOSAL, OPENED_AT, NOW)
        assertNotNull("送达的读数要能被选中", reading)
        assertTrue(ProofReading.usedRefs(root).isEmpty())
        reading!!.runner(reading.snapshot)
        assertEquals("runner 被调才落 USED", setOf("ci-461-1"), ProofReading.usedRefs(root))
    }

    // ── 判据② 四种非法读数各一条弃用 ────────────────────────────────────────

    @Test
    fun `判据二_四种非法行各一条弃用_好行照落_收件箱只有好行`() {
        ReadingDelivery.transport = {
            listOf(
                // ① parse 失败：根本不是 JSON。
                "这不是JSON{{{",
                // ② 必填缺失：合法 JSON 但没有 unit（schema v1 缺任一必填＝坏行）。
                JSONObject().put("v", 1).put("proposal", PROPOSAL).put("ref", "ci-bad-2")
                    .put("at", NOW - 1_000L).toString(),
                // ③ 构造器拒（假绿）：ran=true 却 0 例——GateRun 根本建不出来。
                JSONObject().put("v", 1).put("proposal", PROPOSAL).put("ref", "ci-bad-3")
                    .put("at", NOW - 1_000L)
                    .put("unit", JSONObject().put("ran", true).put("total", 0).put("failed", 0))
                    .put("regression", JSONObject().put("ran", true).put("total", 13).put("failed", 0))
                    .put("anchor", JSONObject().put("total", 50).put("mismatched", 0))
                    .put("detekt", JSONObject().put("before", 341).put("after", 341))
                    .put(
                        "snapshot",
                        snapshotJson(epochMs = NOW - 1_000L),
                    )
                    .toString(),
                // ④ 构造器拒（坏表）：snapshot 的 criteria 是空表——新表都不存在，读数无处安放。
                goodRow(ref = "ci-bad-4", rawSnapshot = snapshotJson(criteriaCount = 0)),
                // 好行：只有它该落。
                goodRow(ref = "ci-461-ok"),
            ).joinToString("\n")
        }
        val outcome = ReadingDelivery.attemptOnce(NOW)
        assertTrue(outcome.ok)
        assertEquals("远端 5 行", 5, outcome.remoteLines)
        assertEquals("四种非法行各弃一条", 4, outcome.badLines)
        assertEquals("只落好行", 1, outcome.appended)
        val lines = inbox().readLines().filter { it.isNotBlank() }
        assertEquals("收件箱恰好 1 行", 1, lines.size)
        assertTrue("落的是那条好行", lines[0].contains("ci-461-ok"))
        // 弃用的 ref 一个都不许进收件箱（防坏 ref 占坑）。
        lines.forEach { line ->
            listOf("ci-bad-2", "ci-bad-3", "ci-bad-4").forEach { badRef ->
                assertFalse("弃用 ref $badRef 不许落盘", line.contains(badRef))
            }
        }
    }

    // ── 判据③ 断网不崩＋退避＋同轮一次 ─────────────────────────────────────

    @Test
    fun `判据三_断网_一行诊断不抛_退避落盘_收件箱不动`() {
        ReadingDelivery.transport = { throw IOException("Network unreachable") }
        val outcome = ReadingDelivery.attemptOnce(NOW) // 不抛＝判据本身
        assertFalse("失败要如实报", outcome.ok)
        val state = ReadingDelivery.State.load(root)
        assertEquals("第 1 连败", 1, state.fails)
        assertEquals("退避 5 分钟", NOW + 5L * 60_000L, state.nextTryMs)
        assertFalse("收件箱不许被建出来", inbox().isFile)
    }

    @Test
    fun `判据三_连败退避指数走_成功清零`() {
        // 三连败：5 → 10 → 20 分钟。
        val schedule = listOf(5L, 10L, 20L)
        var now = NOW
        schedule.forEachIndexed { idx, minutes ->
            ReadingDelivery.transport = { throw IOException("down") }
            val o = ReadingDelivery.attemptOnce(now)
            assertFalse(o.ok)
            val s = ReadingDelivery.State.load(root)
            assertEquals("第 ${idx + 1} 连败退避 $minutes 分钟", now + minutes * 60_000L, s.nextTryMs)
            now = s.nextTryMs // 退避到期后再试下一轮
        }
        // 成功：连败清零、节流窗从成功时刻起算。
        ReadingDelivery.transport = { goodRow(ref = "ci-ok-after-fails") }
        val ok = ReadingDelivery.attemptOnce(now)
        assertTrue(ok.ok)
        val s = ReadingDelivery.State.load(root)
        assertEquals("成功清零连败", 0, s.fails)
        assertEquals("节流窗起点＝成功时刻", now, s.lastOkMs)
    }

    @Test
    fun `判据三_节流与退避期内不取_到期恢复`() {
        // 成功后：一小时内不取。
        ReadingDelivery.transport = { goodRow(ref = "ci-throttle") }
        assertTrue(ReadingDelivery.attemptOnce(NOW).ok)
        assertFalse("成功后 1 小时内不取", ReadingDelivery.due(root, NOW + 30L * 60_000L))
        assertTrue("1 小时后恢复", ReadingDelivery.due(root, NOW + 61L * 60_000L))

        // 失败后：退避期内不取，到期恢复。
        ReadingDelivery.transport = { throw IOException("down") }
        val t = NOW + 2L * 3_600_000L
        assertFalse(ReadingDelivery.attemptOnce(t).ok)
        assertFalse("退避期内不取", ReadingDelivery.due(root, t + 60_000L))
        assertTrue("退避到期恢复", ReadingDelivery.due(root, t + 5L * 60_000L + 1_000L))
    }

    @Test
    fun `判据三_onRoundStart_到点才提交_每轮至多一次_不往调用方抛`() {
        val latch = CountDownLatch(1)
        val calls = AtomicInteger(0)
        ReadingDelivery.transport = { _ ->
            calls.incrementAndGet()
            latch.countDown()
            goodRow(ref = "ci-round")
        }
        // 第一次 onRoundStart：到点 ⇒ 后台提交一次（等传输真被调到）。
        ReadingDelivery.onRoundStart(NOW)
        assertTrue("传输要在后台线程被调到", latch.await(10, TimeUnit.SECONDS))
        // 等送达收尾（lastOk 落盘）再判节流，避免"状态还没写、下一轮就放行"的竞态。
        val deadline = System.currentTimeMillis() + 10_000L
        while (ReadingDelivery.State.load(root).lastOkMs != NOW && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals("成功时刻要落盘", NOW, ReadingDelivery.State.load(root).lastOkMs)
        // 成功 ⇒ 节流窗内：再怎么调 onRoundStart 都不取。
        ReadingDelivery.onRoundStart(NOW + 60_000L)
        ReadingDelivery.onRoundStart(NOW + 120_000L)
        Thread.sleep(300) // 给可能的误提交留窗口（提交了就会被数出来）
        assertEquals("同轮/节流期不叠加取数", 1, calls.get())
    }

    // ── 判据④ 非白名单 URL 拒 ──────────────────────────────────────────────

    @Test
    fun `判据四_非白名单URL一律拒_白名单常量放行`() {
        val evil = listOf(
            "https://raw.githubusercontent.com/pisces19860207/zhengdao/main/readings/%E8%BF%9B%E5%8C%96%E8%AF%BB%E6%95%B0.jsonl", // 换分支
            "https://raw.githubusercontent.com/pisces19860207/zhengdao/ci/readings/readings/other.jsonl", // 换文件
            "https://evil.example/readings/%E8%BF%9B%E5%8C%96%E8%AF%BB%E6%95%B0.jsonl", // 换 host
            "http://raw.githubusercontent.com/pisces19860207/zhengdao/ci/readings/readings/%E8%BF%9B%E5%8C%96%E8%AF%BB%E6%95%B0.jsonl", // http 降级
            "https://raw.githubusercontent.com/pisces19860207/zhengdao/ci/readings/readings/%E8%BF%9B%E5%8C%96%E8%AF%BB%E6%95%B0.jsonl ", // 尾随空格
            "https://raw.githubusercontent.com/pisces19860207/zhengdao/ci/readings/readings/进化读数.jsonl", // 未编码路径
        )
        val used = ArrayList<String>()
        ReadingDelivery.transport = { url -> used.add(url); "[]" }
        evil.forEach { url ->
            try {
                ReadingDelivery.fetchAllowed(url)
                fail("非白名单 URL 必须被拒：$url")
            } catch (expected: IllegalArgumentException) {
                assertTrue("拒因要点名非白名单", expected.message!!.contains("非白名单"))
            }
        }
        assertEquals("被拒的 URL 一个都不许到传输层", 0, used.size)
        assertTrue("白名单常量本身放行", ReadingDelivery.isAllowedUrl(ReadingDelivery.READINGS_URL))
    }

    // ── 判据⑤ release 也能用（源级钉缝） ────────────────────────────────────

    @Test
    fun `判据五_零Android零debug闸_源级钉缝防拆`() {
        val src = stripComments(
            File("src/main/java/com/example/zhengdao/baopu/evolution/ReadingDelivery.kt")
                .readText(Charsets.UTF_8),
        )
        listOf(
            "import android", // 零 Android
            "BuildConfig", // 零 debug/release 分叉
            "run-as", // 手送口不许混进送达件（那才是 debug 独有）
            "POST", // 只 GET 不 POST（剥过注释再扫，头注释里的"不 POST"不算）
            "android.util.Log", // 诊断只走 RunLog
        ).forEach { banned ->
            assertFalse("送达件不许出现「$banned」（release 同路的根）", src.contains(banned))
        }
        assertTrue("必须显式 GET", src.contains("requestMethod = \"GET\""))
        assertTrue("必须钉死不跟重定向（跟了＝GET 第二个 URL）", src.contains("instanceFollowRedirects = false"))
        assertTrue("UA 必须是固定常量（默认 Dalvik UA 才带设备信息）", src.contains("USER_AGENT"))
    }

    /** 剥 // 行注释与 /* */ 块注释（含 KDoc）——源级断言只认代码（EvolutionWiringGateTest 同款思路）。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        val n = src.length
        while (i < n) {
            when {
                src.startsWith("//", i) -> while (i < n && src[i] != '\n') i++
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < n && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(n)
                }
                src[i] == '"' -> { // 字符串里的注释记号照抄，不剥
                    out.append('"')
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < n) {
                            out.append(src[i]).append(src[i + 1]); i += 2
                        } else {
                            out.append(src[i]); i++
                        }
                    }
                    if (i < n) {
                        out.append('"'); i++
                    }
                }
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    // ── 送达去重（ref 不重复落；状态从盘上现读，重启不重取） ─────────────────

    @Test
    fun `送达去重_同ref不重复落_状态盘上现读`() {
        ReadingDelivery.transport = { goodRow(ref = "ci-dup") }
        assertTrue(ReadingDelivery.attemptOnce(NOW).ok)
        assertEquals(1, inbox().readLines().filter { it.isNotBlank() }.size)
        // 模拟进程重启：清状态文件的 lastOk ⇒ due() 只看盘。
        stateFile().writeText("FAILS|0\nNEXT_TRY|0\nLAST_OK|0\n", Charsets.UTF_8)
        assertTrue("盘上无节流 ⇒ 到点", ReadingDelivery.due(root, NOW + 1_000L))
        val again = ReadingDelivery.attemptOnce(NOW + 1_000L)
        assertTrue(again.ok)
        assertEquals("同 ref 不重复落", 0, again.appended)
        assertEquals("收件箱还是 1 行", 1, inbox().readLines().filter { it.isNotBlank() }.size)
    }

    // ── 真传输（httpGet）的 HTTP 层行为：本地起服务验状态码/UA/重定向 ─────────

    @Test
    fun `HTTP层_200读体_UA固定常量_404报错_重定向不跟`() {
        var seenUa: String? = null
        var seenMethod: String? = null
        var redirects = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { exchange ->
            seenUa = exchange.requestHeaders.getFirst("User-Agent")
            seenMethod = exchange.requestMethod
            val body = goodRow(ref = "ci-http").toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }
        server.createContext("/bounce") { exchange ->
            redirects++
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1)
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val body = ReadingDelivery.httpGet("$base/ok")
            assertTrue("200 的体要读回来", body.contains("ci-http"))
            assertEquals("method 恒 GET", "GET", seenMethod)
            assertEquals("UA 是固定常量，不带设备信息", "zhengdao-readings/1", seenUa)
            try {
                ReadingDelivery.httpGet("$base/missing")
                fail("404 必须报错（落一行诊断那半条由 attemptOnce 的 catch 兜）")
            } catch (expected: IOException) {
                assertTrue("404 要进异常消息", expected.message!!.contains("404"))
            }
            try {
                ReadingDelivery.httpGet("$base/bounce")
                fail("重定向必须当失败（跟了＝GET 第二个 URL）")
            } catch (expected: IOException) {
                assertEquals("302 不许被跟", 1, redirects)
            }
        } finally {
            server.stop(0)
        }
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private val PROPOSAL = "misjudgment:TIMEOUT"
    private val OPENED_AT = 1_700_000_000_000L
    private val NOW = OPENED_AT + 2_000L

    /** 一条合法读数行（schema v1，与 CI 产的同款；rawSnapshot 供换表用例注入坏表）。 */
    private fun goodRow(ref: String, rawSnapshot: String = snapshotJson()): String = JSONObject()
        .put("v", 1)
        .put("proposal", PROPOSAL)
        .put("ref", ref)
        .put("at", NOW - 1_000L)
        .put("unit", JSONObject().put("ran", true).put("total", 100).put("failed", 0))
        .put("regression", JSONObject().put("ran", true).put("total", 13).put("failed", 0))
        .put("anchor", JSONObject().put("total", 50).put("mismatched", 0))
        .put("detekt", JSONObject().put("before", 341).put("after", 341))
        .put("snapshot", JSONObject(rawSnapshot))
        .toString()

    private fun snapshotJson(epochMs: Long = NOW - 1_000L, criteriaCount: Int = 1): String {
        val criteria = JSONArray()
        repeat(criteriaCount) { i ->
            criteria.put(
                JSONObject()
                    .put("id", "c$i")
                    .put("description", "新判据 $i")
                    .put("target", "some/target$i")
                    .put("admittedInEpoch", 1L),
            )
        }
        return JSONObject()
            .put("epochMs", epochMs)
            .put("seq", 1L)
            .put("criteria", criteria)
            .toString()
    }
}
