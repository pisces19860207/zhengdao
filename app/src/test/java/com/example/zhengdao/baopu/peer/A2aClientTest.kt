// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十八批 · A2A 客户端（手写）与 A2A 过闸的判据测试（夹具＝570 自测床）。
//
// 线：agent card → message/send → 任务状态轮询 → artifacts；
// 失败：需鉴权／协议不符各有类有人话；拉卡未批准 ⇒ 一个请求不发。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import com.example.zhengdao.baopu.peer.LeaseOutboundGate
import com.example.zhengdao.testkit.Peer570Fixture
import com.example.zhengdao.testkit.TempSandboxPool
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class A2aClientTest {

    private lateinit var fx: Peer570Fixture

    @Before
    fun setUp() {
        fx = Peer570Fixture()
        fx.start()
        val root = TempSandboxPool.create("a2a548")
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        LeaseLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        fx.close()
        LeaseStore.resetForTest()
        AgentRegistry.resetForTest()
        PeerEventsLedger.resetForTest()
        TempSandboxPool.clean()
    }

    private fun http(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun client(credential: A2aCredential? = null) = A2aClient(fx.a2aBase(), http(), credential = credential)

    private fun card() = client().fetchCard()

    private fun grant(base: String = fx.a2aBase()): String =
        LeaseOutboundGate.grantProbeLease(base, McpPeerProbe.SESSION_MANUAL, "test-card", NOW)

    // ── ② 四步线：card → send → 轮询 → artifacts ────────────────────────

    @Test
    fun `线_拉卡_能力技能安全方案都读得出`() {
        val card = card()
        assertEquals("守夜人-570", card.name)
        assertEquals(fx.a2aBase() + "/", card.url)
        assertEquals("1.0", card.version)
        assertEquals(listOf("streaming"), card.capabilities)
        assertEquals(1, card.skills.size)
        assertEquals("summarize", card.skills.single().id)
        assertEquals(listOf("apiKey"), card.securitySchemeNames)
        assertTrue(card.capabilityFingerprint().length == 64)
    }

    @Test
    fun `线_发送_轮询到完成_artifacts收得到`() {
        val card = card()
        val sent = client().messageSend(card, "把这份长文摘要一下")
        assertEquals("task-570", sent.id)
        assertEquals(A2aTaskState.SUBMITTED, sent.state)
        assertTrue(sent.artifacts.isEmpty())

        val run = client().sendAndPoll(
            card,
            "接着上一条",
            taskId = sent.id,
            pollEveryMs = 1L,
            maxWaitMs = 10_000L,
            sleeper = { true },
            nowMs = { System.currentTimeMillis() },
        )
        assertFalse(run.timedOut)
        assertEquals(A2aTaskState.COMPLETED, run.task.state)
        assertEquals(1, run.task.artifacts.size)
        assertEquals("摘要稿", run.task.artifacts.single().name)
        assertEquals(listOf("570 的产出正文"), run.task.artifacts.single().texts)
        assertTrue("轮询确实发生了", fx.a2aPolls.get() >= 1)
    }

    @Test
    fun `线_input-required_停下来_不当成功也不当失败`() {
        fx.a2aStates = listOf("input-required")
        val run = client().sendAndPoll(card(), "问一句", pollEveryMs = 1L, sleeper = { true })
        assertFalse(run.timedOut)
        assertTrue(run.needsHuman)
        assertEquals(A2aTaskState.INPUT_REQUIRED, run.task.state)
        assertFalse(run.task.state.terminal)
    }

    @Test
    fun `线_轮询超时_带timedOut旗子_不死循环`() {
        fx.a2aStates = listOf("working")
        val run = client().sendAndPoll(
            card(), "慢任务",
            pollEveryMs = 1L, maxWaitMs = 50L,
            sleeper = { true },
            nowMs = { System.currentTimeMillis() },
        )
        assertTrue(run.timedOut)
        assertEquals(A2aTaskState.WORKING, run.task.state)
    }

    // ── ② 失败分类：需鉴权／协议不符 ────────────────────────────────────

    @Test
    fun `需鉴权_401_归类AUTH_人话指授权`() {
        val guarded = mockwebserver3.MockWebServer()
        guarded.start()
        guarded.enqueue(mockwebserver3.MockResponse.Builder().code(401).build())
        try {
            val c = A2aClient("http://127.0.0.1:${guarded.port}", http())
            val e = runCatching { c.fetchCard() }.exceptionOrNull() as A2aException
            assertEquals("401 是「要先验明身份」，不是「连不上」", PeerFailureCode.AUTH, e.failureCode)
            assertTrue(e.message!!.contains("先验明身份"))
        } finally {
            guarded.close()
        }
    }

    @Test
    fun `协议不符_卡不是JSON_归类PROTOCOL_人话说不是一路A2A`() {
        val bad = mockwebserver3.MockWebServer()
        bad.start()
        bad.enqueue(mockwebserver3.MockResponse.Builder().code(200).body("<html>不是卡</html>").build())
        try {
            val c = A2aClient("http://127.0.0.1:${bad.port}", http())
            val e = runCatching { c.fetchCard() }.exceptionOrNull() as A2aException
            assertEquals(PeerFailureCode.PROTOCOL, e.failureCode)
            assertTrue(e.message!!.contains("不是一路 A2A"))
        } finally {
            bad.close()
        }
    }

    @Test
    fun `凭据走内存头_不落盘不进日志`() {
        val secret = "sk-a2a-secret-548"
        val card = card()
        client(A2aCredential("X-Api-Key", secret)).messageSend(card, "带凭据发一条")
        // 客户端不落盘（本类无任何写盘调用）；日志面不给凭据（log lambda 未收到含密文的行）。
        val seen = ArrayList<String>()
        A2aClient(fx.a2aBase(), http(), log = { seen.add(it) }, credential = A2aCredential("X-Api-Key", secret))
            .messageSend(card, "再来一条")
        assertTrue(seen.none { it.contains(secret) })
    }

    // ── A2A 过闸：拉卡＝外发，未批准不发起 ──────────────────────────────

    @Test
    fun `A2A拉卡_未批准_零请求_闸头落refused`() {
        val out = A2aPeerProbe(http()).probeCard(fx.a2aBase(), nowMs = NOW)
        assertEquals(PeerFailureCode.REFUSED, out.code)
        assertEquals("拉卡也一个请求不许发", 0, fx.a2aCardGets.get())
        assertEquals("refused", PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.GATE }.optString("result"))
    }

    @Test
    fun `A2A拉卡_批准后成功_名册进a2a行_带能力指纹`() {
        grant()
        val out = A2aPeerProbe(http()).probeCard(fx.a2aBase(), nowMs = NOW)
        assertEquals(PeerFailureCode.OK, out.code)
        assertTrue(out.human.contains("守夜人-570"))
        val rec = AgentRegistry.active().single()
        assertEquals(AgentForm.A2A, rec.form)
        assertEquals(fx.a2aBase(), rec.endpoint)
        assertEquals(64, rec.capabilityFingerprint.length)
        assertEquals("", rec.credentialFingerprint) // 没带凭据
        // 租约账：peer.a2acard 的 use 行。
        assertTrue(LeaseLedger.replay().any { it.optString("kind") == "use" && it.optString("tool") == "peer.a2acard" })
        assertEquals("ok", PeerEventsLedger.replay().first { it.optString("kind") == PeerEventKinds.PROBE }.optString("result"))
    }

    @Test
    fun `A2A能力漂移_改名加技能_指纹变_提示能力变了`() {
        val root = AgentRegistry.root()!!
        grant()
        val first = A2aPeerProbe(http()).probeCard(fx.a2aBase(), nowMs = NOW)
        assertFalse(first.human.contains("能力指纹变了"))
        val oldFp = AgentRegistry.active().single().capabilityFingerprint

        fx.a2aAgentName = "守夜人-570-改组"
        fx.a2aExtraSkill = true
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.installRoot { root }
        grant()
        val second = A2aPeerProbe(http()).probeCard(fx.a2aBase(), nowMs = NOW + 1)
        assertTrue("卡变了要提示：${second.human}", second.human.contains("能力指纹变了"))
        assertNotEquals(oldFp, AgentRegistry.active().single().capabilityFingerprint)
        assertEquals("守夜人-570-改组", AgentRegistry.active().single().name)
    }

    private companion object {
        const val NOW = 1_760_000_000_000L
    }
}
