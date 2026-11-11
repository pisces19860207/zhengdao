// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十批 · **A2A 自测床的判据**（派工单 570 的 ①–⑥）。
//
// 被测方一律是 548 已合的手写客户端（`A2aClient` / `A2aException` / `PeerFailureCode`），
// 本批**不写第二个客户端**；只有 `tasks/cancel` 一次是裸 HTTP——548 的客户端没有取消入口，
// 这个缺口记在交付报告里（G-570-3），不在本批偷偷补。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.testkit.A2aBedFault
import com.example.zhengdao.testkit.A2aBedScript
import com.example.zhengdao.testkit.Peer570A2aBed
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Peer570BedTest {

    private lateinit var bed: Peer570A2aBed

    @Before
    fun setUp() {
        bed = Peer570A2aBed()
        bed.start()
    }

    @After
    fun tearDown() {
        bed.close()
    }

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun client(credential: A2aCredential? = null): A2aClient =
        A2aClient(bed.base(), http(), credential = credential)

    // ── 判据① 卡：五样字段齐全，两条 well-known 路径都给同一张 ────────────

    @Test
    fun `床一_卡五样字段齐全_两条wellknown路径同一张卡`() {
        val card = client().fetchCard()
        assertTrue("name 要公布", card.name.isNotEmpty())
        assertTrue("description 要公布", card.description.isNotEmpty())
        assertEquals("技能清单", listOf("summarize"), card.skills.map { it.id })
        assertEquals("能力清单（按名排序）", listOf("pushNotifications", "streaming"), card.capabilities)
        assertEquals("安全方案名（不含凭据材料）", listOf("apiKey"), card.securitySchemeNames)
        assertEquals("协议版本", Peer570A2aBed.PROTOCOL, card.protocolVersion)

        // 派工单 570 ① 写的路径是 agent.json；548 客户端走的是 agent-card.json。两条都给。
        val legacy = rawGet("/.well-known/agent.json")
        assertEquals("agent.json 也要 200", 200, legacy.first)
        assertEquals("两条路径同一张卡", bed.agentName, JSONObject(legacy.second).getString("name"))
        assertEquals(
            "两次拉卡都记到了",
            listOf("/.well-known/agent-card.json", "/.well-known/agent.json"),
            bed.cardPaths.toList(),
        )
    }

    // ── 判据① 提交 → working → completed（带 artifacts） ─────────────────

    @Test
    fun `床二_提交到完成_548客户端逐步走完`() {
        val c = client()
        val card = c.fetchCard()
        val sent = c.messageSend(card, "把这份长文摘要一下")
        assertEquals("收下先回 submitted", A2aTaskState.SUBMITTED, sent.state)
        assertTrue("还没干活就不该有产物", sent.artifacts.isEmpty())

        val working = c.taskGet(card, sent.id)
        assertEquals("按剧本推进到 working", A2aTaskState.WORKING, working.state)
        val done = c.taskGet(card, sent.id)
        assertEquals("再到 completed", A2aTaskState.COMPLETED, done.state)
        assertTrue("completed 是终态", done.state.terminal)
        assertEquals("产物正文读得到", listOf("570 的产出正文"), done.artifacts.single().texts)
        assertEquals(
            "床侧吐出的状态序列（客户端真按这条线走的证据）",
            listOf("submitted", "working", "completed|570 的产出正文"),
            bed.servedTicks.toList(),
        )
    }

    @Test
    fun `床二b_客户端自带轮询环一次到完成`() {
        val c = client()
        val run = c.sendAndPoll(c.fetchCard(), "接着上一条", pollEveryMs = 1L, maxWaitMs = 5_000L, sleeper = { true })
        assertFalse("没超时", run.timedOut)
        assertEquals(A2aTaskState.COMPLETED, run.task.state)
        assertEquals(1, run.task.artifacts.size)
    }

    // ── 判据② 三种剧本：input-required 停下来要人 · failed 停为失败 ──────

    @Test
    fun `床三_剧本二_input-required_停下来要人_不当成败`() {
        bed.script = A2aBedScript.INPUT_REQUIRED
        val run = client().sendAndPoll(client().fetchCard(), "问一句", pollEveryMs = 1L, maxWaitMs = 5_000L, sleeper = { true })
        assertEquals(A2aTaskState.INPUT_REQUIRED, run.task.state)
        assertTrue("要人不是失败", run.needsHuman)
        assertFalse("要人也不是终态", run.task.state.terminal)
        assertTrue("要说什么得读得到：${run.task.statusMessage}", run.task.statusMessage.contains("还差一份原文"))
        assertTrue("要人的时候不带产物", run.task.artifacts.isEmpty())
    }

    @Test
    fun `床四_剧本三_failed_停为失败_带说明`() {
        bed.script = A2aBedScript.FAILED
        val run = client().sendAndPoll(client().fetchCard(), "办这件事", pollEveryMs = 1L, maxWaitMs = 5_000L, sleeper = { true })
        assertEquals(A2aTaskState.FAILED, run.task.state)
        assertTrue("failed 是终态", run.task.state.terminal)
        assertFalse("失败不算要人", run.needsHuman)
        assertTrue("失败原因读得到：${run.task.statusMessage}", run.task.statusMessage.contains("上游拒了"))
        assertTrue("失败不带产物", run.task.artifacts.isEmpty())
    }

    // ── 判据② tasks/cancel：回 canceled，且此后状态不再前进 ─────────────

    @Test
    fun `床五_取消回canceled_再查仍一致_不回completed`() {
        val c = client()
        val card = c.fetchCard()
        val sent = c.messageSend(card, "先开一个任务")
        assertEquals(A2aTaskState.WORKING, c.taskGet(card, sent.id).state)

        val beforeCancel = bed.rpcMethods.size
        assertEquals("canceled", cancelRaw(sent.id))
        assertEquals("走的确实是 tasks/cancel", "tasks/cancel", bed.rpcMethods[beforeCancel])
        assertEquals("取消后第一次查", A2aTaskState.CANCELED, c.taskGet(card, sent.id).state)
        assertEquals("取消后第二次查（粘住，不回 completed）", A2aTaskState.CANCELED, c.taskGet(card, sent.id).state)
        assertTrue("canceled 是终态", c.taskGet(card, sent.id).state.terminal)
        assertEquals(
            "线上的方法序列：发送→查一次→取消→查三次（取消之后只该有查）",
            listOf("message/send", "tasks/get", "tasks/cancel", "tasks/get", "tasks/get", "tasks/get"),
            bed.rpcMethods.toList(),
        )
    }

    // ── 判据③ 三条失败路：三类，三句人话，不混成「网络错误」 ─────────────

    @Test
    fun `床六_版本不符_错token_超时_三类三句`() {
        // ① 协议版本不符：卡上公布旧版，RPC 一律被 -32001 拒。
        bed.fault = A2aBedFault.BAD_PROTOCOL
        val stale = client()
        val staleCard = stale.fetchCard()
        assertEquals("卡上如实公布旧版", Peer570A2aBed.UNSUPPORTED_PROTOCOL, staleCard.protocolVersion)
        val e1 = failure { stale.messageSend(staleCard, "发一条") }
        assertEquals("版本对不上不是网络错", PeerFailureCode.PROTOCOL, e1.failureCode)

        // ② 错 token ⇒ 401 ⇒ AUTH。
        bed.fault = A2aBedFault.BAD_TOKEN
        // 头值只许 ASCII：中文 token 连请求都发不出去（OkHttp 先抛 IllegalArgumentException），
        // 验不到 401 这条路——所以这里用一把写得对的「错 token」。
        val wrong = A2aCredential(Peer570A2aBed.TOKEN_HEADER, "sk-wrong-570")
        val e2 = failure { A2aClient(bed.base(), http(), credential = wrong).fetchCard() }
        assertEquals("错 token 是「先验明身份」", PeerFailureCode.AUTH, e2.failureCode)

        // ②的负例：同一把床，换成对的 token 就通——证明 401 出自 token，不是一刀切。
        val right = A2aCredential(Peer570A2aBed.TOKEN_HEADER, Peer570A2aBed.TOKEN)
        val good = A2aClient(bed.base(), http(), credential = right)
        assertEquals("带对的 token 照常连", bed.agentName, good.fetchCard().name)

        // ③ 超时：床挂住 1.5 秒，客户端只等 0.3 秒。
        bed.fault = A2aBedFault.TIMEOUT
        val slow = client()
        val slowCard = slow.fetchCard()
        val e3 = failure { slow.messageSend(slowCard, "发一条", timeoutMs = 300L) }
        assertEquals("等不回音是超时", PeerFailureCode.TIMEOUT, e3.failureCode)

        val humans = listOf(e1, e2, e3).map { it.message.orEmpty() }
        assertEquals("三条路必须给三句不同的话：$humans", 3, humans.toSet().size)
        assertTrue("不许混成「网络错误」：$humans", humans.none { it.contains("网络错误") })
        assertEquals(
            "三类机器键互不相同（账上按键对账，不许两类共用一个键）",
            setOf(PeerFailureCode.PROTOCOL, PeerFailureCode.AUTH, PeerFailureCode.TIMEOUT),
            listOf(e1, e2, e3).map { it.failureCode }.toSet(),
        )
    }

    // ── 判据④ 幂等：同一剧本跑两遍，读数逐条一致 ────────────────────────

    @Test
    fun `床七_同一剧本跑两遍_读数逐条一致`() {
        val first = runNormalOnce()
        val second = runNormalOnce()
        assertEquals("同一剧本两遍必须逐条同形", first, second)
        assertEquals(listOf("submitted", "working", "completed|570 的产出正文"), first)
    }

    @Test
    fun `床七b_床内不用随机数与时钟_判定可复现`() {
        // 查整份源码（含注释）：注释里出现这些字面量也判红——宁可过严，不让「只改注释不改代码」蒙过去。
        val src = bedFile().readText()
        assertFalse("不许用随机数", src.contains("Random"))
        assertFalse("不许读时钟做判定", src.contains("currentTimeMillis"))
        assertFalse("不许读纳秒钟", src.contains("nanoTime"))
        assertFalse("不许读系统时间", src.contains("Instant.now"))
        assertFalse("不许读系统日期", src.contains("LocalDate.now") || src.contains("ZonedDateTime"))
    }

    // ── 判据⑤ 不进生产：main 源集零引用；且只连环回 ───────────────────

    @Test
    fun `床八_生产源集零引用本夹具`() {
        val root = repoFile("src/main/java")
        assertTrue("找不到生产源集，门禁不能当真绿：${root.absolutePath}", root.isDirectory)
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> Regex("Peer570|testkit").containsMatchIn(f.readText()) }
            .map { it.path.replace('\\', '/') }
            .toList()
        assertEquals("夹具名字只许出现在 app/src/test；生产源集出现即是漏进生产", emptyList<String>(), hits)

        val bedPath = bedFile().absolutePath.replace('\\', '/')
        assertTrue("床本身必须在测试源集里：$bedPath", bedPath.contains("/src/test/"))
    }

    @Test
    fun `床九_只连环回地址_不联网`() {
        assertEquals("床只监听环回", "127.0.0.1", URI(bed.base()).host)
        val src = bedFile().readText()
        val urls = Regex("http://[^\\s\"')]+").findAll(src).map { it.value }.toList()
        assertTrue("床源码里至少得有一处拼地址：$urls", urls.isNotEmpty())
        assertTrue("夹具只许拼环回地址（不联网）：$urls", urls.all { it.contains("127.0.0.1") })
        assertFalse("夹具不许出现 https 外呼", src.contains("https://"))
    }

    // ── 判据⑥ 不落盘：床不建临时目录，所以测试侧没有要清的残留 ─────────

    @Test
    fun `床十_不碰文件系统_无临时目录残留可清`() {
        val src = bedFile().readText()
        assertFalse("床不许开文件", src.contains("java.io.File") || src.contains("File("))
        assertFalse("床不许建临时目录", src.contains("createTempDir") || src.contains("createTempFile"))
        assertFalse("床不许写盘", src.contains("writeText") || src.contains("writeBytes"))
        assertFalse("床不许自己养沙盒池", src.contains("TempSandbox"))
    }

    // ── 小件 ────────────────────────────────────────────────────────────

    private fun runNormalOnce(): List<String> {
        bed.clearTicks()
        val c = client()
        val card = c.fetchCard()
        val sent = c.messageSend(card, "同一件事")
        c.taskGet(card, sent.id)
        c.taskGet(card, sent.id)
        return bed.servedTicks.toList()
    }

    private fun failure(block: () -> Unit): A2aException {
        val e = runCatching(block).exceptionOrNull()
        assertTrue("该失败却成功：$e", e is A2aException)
        return e as A2aException
    }

    /** 裸 HTTP 的 tasks/cancel（548 的客户端没有取消入口，见报告 G-570-3）。 */
    private fun cancelRaw(taskId: String): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 9001)
            .put("method", "tasks/cancel")
            .put("params", JSONObject().put("id", taskId))
            .toString()
        val req = Request.Builder().url(bed.base() + "/")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()
        http().newCall(req).execute().use { r ->
            val json = JSONObject(r.body?.string().orEmpty())
            return json.getJSONObject("result").getJSONObject("status").getString("state")
        }
    }

    private fun rawGet(path: String): Pair<Int, String> {
        val req = Request.Builder().url(bed.base() + path).header("Accept", "application/json").build()
        http().newCall(req).execute().use { r -> return r.code to r.body?.string().orEmpty() }
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun bedFile(): File = repoFile("src/test/java/com/example/zhengdao/testkit/Peer570A2aBed.kt")
}
