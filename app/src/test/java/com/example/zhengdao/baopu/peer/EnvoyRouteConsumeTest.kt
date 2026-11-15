// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十四批（2026-11-15）· **路由消费缝的真转发用例**（判据"路由改后请求真发往新 peer"）：
// 两台真 A2A 床（Peer570A2aBed，570 同款）——路由行指向床 A 的名册 id，调用方给的 endpoint 却是
// 床 B：经 EnvoyDeliver 的 routeOf（注入 RouteEvo::resolveEndpoint，生产同款一行）⇒ 请求真发往 A，
// B 零字节。负例：没有路由行／routeOf 没接（接线前形状）⇒ 请求按原值发往 B；续任务不挪
// （reply 仍住原 peer——路由只挪新派活）。
//
// 走的全是既有机制：路由行＝568-C 域②的 RouteEvo（纯偏好）、名册＝548 的 AgentRegistry
// （agentId⇒endpoint 的唯一真源）、闸序＝549 的 deliver 原样（租约→卡→准入→档位）。
// 本测试一条新判不写——它证的是"生效值真的进了生产分发"。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.evolution.highrisk.RouteEvo
import com.example.zhengdao.baopu.evolution.highrisk.RouteInput
import com.example.zhengdao.baopu.evolution.highrisk.RouteProposal
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.testkit.A2aBedScript
import com.example.zhengdao.testkit.Peer570A2aBed
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnvoyRouteConsumeTest {

    private val now = 1_760_100_000_000L

    private lateinit var sandbox: File
    private lateinit var memory: File
    private lateinit var admitRoot: File
    private lateinit var bedA: Peer570A2aBed
    private lateinit var bedB: Peer570A2aBed

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        bedA = Peer570A2aBed().also { it.start() }
        bedB = Peer570A2aBed().also { it.start() }
        sandbox = TempSandbox.create("zd644-route-")
        memory = File(sandbox, "抱朴记忆")
        admitRoot = File(sandbox, "准入").also { it.mkdirs() }
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now)
        EvoSampleWiring.install(sandbox)
        AgentRegistry.installRoot { sandbox }
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { sandbox }
        PeerEventsLedger.resetForTest()
        PeerEventsLedger.installRoot { sandbox }
    }

    @After
    fun tearDown() {
        bedA.close()
        bedB.close()
        LeaseStore.resetForTest()
        PeerEventsLedger.resetForTest()
        AgentRegistry.resetForTest()
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────────

    /** 生产同款一行：EnvoyDeliver 的 routeOf 注入 RouteEvo::resolveEndpoint（BaopuRuntime 未来接线位）。 */
    private fun deliverWithRouteOf(endpoint: String): EnvoyOutcome = EnvoyDeliver(
        http,
        log = {},
        routeOf = { requested -> RouteEvo.resolveEndpoint(requested) },
    ).deliver(
        EnvoyRequest(
            endpoint = endpoint,
            task = "把这份长文摘要一下",
            sessionId = 1L,
            root = admitRoot,
            pollEveryMs = 1,
            maxWaitMs = 5_000,
            sleeper = { true },
            examChannelOf = { c, card -> A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true }) },
        ),
    )

    /** 不接消费缝的旧形状（568-C 交付态：routeOf 缺省 null）。 */
    private fun deliverWithoutRouteOf(endpoint: String): EnvoyOutcome = EnvoyDeliver(http, log = {}).deliver(
        EnvoyRequest(
            endpoint = endpoint,
            task = "把这份长文摘要一下",
            sessionId = 1L,
            root = admitRoot,
            pollEveryMs = 1,
            maxWaitMs = 5_000,
            sleeper = { true },
            examChannelOf = { c, card -> A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true }) },
        ),
    )

    /** 路由行指向床 A 的名册 id（568-C 域②同款：纯偏好，不假设状态迁移）。 */
    private fun pointRouteAtA() {
        val id = AgentRegistry.register("peerA", AgentForm.A2A, bedA.base(), "", "").record.id
        assertTrue(
            "路由行落上（缺省灰度档）",
            RouteEvo.proposeRouteChange(
                RouteProposal(before = null, after = RouteInput(id, ""), why = "夹具：新 peer 准入", anchor = "rt:644"),
                now,
            ),
        )
    }

    // ── 判据：路由改后请求真发往新 peer ────────────────────────────────────────

    @Test
    fun `消费缝②_路由行指向peerA_调用方给的endpoint是B_请求真发往A`() {
        pointRouteAtA()
        val outcome = deliverWithRouteOf(bedB.base())
        assertTrue("真发出去了：${outcome.refusal.orEmpty()}${outcome.failure.orEmpty()}", outcome.sent)
        assertEquals("收场记在实际发往的 peer（A）", bedA.base(), outcome.endpoint)
        assertTrue("A 真收过活（不止看返回值）", bedA.servedTicks.isNotEmpty())
        assertTrue("B 零字节（路由生效＝原 endpoint 一口都没碰）", bedB.servedTicks.isEmpty())
        assertTrue("B 连卡都没被拉过", bedB.cardPaths.isEmpty())
    }

    @Test
    fun `负例_没有路由行_按调用方endpoint发往B_不挪`() {
        val outcome = deliverWithRouteOf(bedB.base())
        assertTrue("真发出去了", outcome.sent)
        assertEquals("没有路由行 ⇒ 原值照发", bedB.base(), outcome.endpoint)
        assertTrue("B 收了活", bedB.servedTicks.isNotEmpty())
        assertTrue("A 一口都没碰", bedA.servedTicks.isEmpty())
    }

    @Test
    fun `负例_路由行在但routeOf没接_接线前形状_照发B`() {
        pointRouteAtA()
        val outcome = deliverWithoutRouteOf(bedB.base())
        assertTrue("真发出去了", outcome.sent)
        assertEquals("消费缝没接 ⇒ 生效值无处消费＝568-C 的病，回归必须红", bedB.base(), outcome.endpoint)
    }

    @Test
    fun `续任务不挪_reply仍住原peer_路由行改不了既有任务的住址`() {
        pointRouteAtA()
        // 先在 B 上真开一个任务（旧形状＝派活时的原值），再带路由行续它 ⇒ 仍发往 B。
        val first = deliverWithoutRouteOf(bedB.base())
        assertTrue(first.sent)
        val resumed = EnvoyDeliver(
            http,
            log = {},
            routeOf = { requested -> RouteEvo.resolveEndpoint(requested) },
        ).reply(
            EnvoyReply(
                endpoint = bedB.base(),
                taskId = first.taskId,
                text = "补上你要的原文",
                sessionId = 1L,
                root = admitRoot,
                track = first.track,
                pollEveryMs = 1,
                maxWaitMs = 5_000,
                sleeper = { true },
            ),
        )
        assertTrue("续照发（任务住在原 peer）：${resumed.refusal.orEmpty()}${resumed.failure.orEmpty()}", resumed.sent)
        assertEquals("续任务仍发往 B（路由只挪新派活）", bedB.base(), resumed.endpoint)
        assertTrue("B 被续过（是续不是重开）", bedB.replies.isNotEmpty())
    }
}
