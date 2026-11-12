// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · **EnvoyDeliver 六个私有分支的分支级夹具**（用户 2026-11-12 拍板的验收门槛）：
//   refusalOutcome / admissionRefused / kindRefusal / lineFailed / anomaly / settled
//   —— 每支一条「能被独立触发」的用例，断言**该支的产物**（拒因原话／账行 kind／终态／失败码），
//   不是只断言「整体返回了某结果」。流程级覆盖见 EnvoyFlow570Test（它只走通 settled 那条）。
//
// 为什么要有这一套：把 `if (!admission.admitted)` 换成 `if (false)`、再把 `allows` 的
// `!admitted -> false` 换成 `false -> false`，流程测试仍全绿 ⇒ 拒绝路径没人看着（打回依据）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.testkit.A2aBedScript
import com.example.zhengdao.testkit.Peer570A2aBed
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnvoyDeliverBranchTest {

    private lateinit var bed: Peer570A2aBed
    private lateinit var root: File
    private lateinit var admitRoot: File

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        bed = Peer570A2aBed().also { it.start() }
        root = TempSandbox.create("zd549-branch")
        admitRoot = File(root, "准入").also { it.mkdirs() }
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.resetForTest()
        PeerEventsLedger.installRoot { root }
    }

    @After
    fun tearDown() {
        bed.close()
        LeaseStore.resetForTest()
        PeerEventsLedger.resetForTest()
        assertTrue("沙盒要删干净（水位纪律）", TempSandbox.deleteTree(root) == 0)
    }

    // ── 夹具小件 ────────────────────────────────────────────────────────────

    private fun deliver(
        task: String = "把这份长文摘要一下",
        endpoint: String = bed.base(),
        writes: Boolean = false,
        admitRootOf: File? = admitRoot,
        grantOnApproval: Boolean = true,
    ): EnvoyOutcome = EnvoyDeliver(http).deliver(
        EnvoyRequest(
            endpoint = endpoint,
            task = task,
            writes = writes,
            sessionId = SESSION,
            root = admitRootOf,
            grantOnApproval = grantOnApproval,
            pollEveryMs = 1,
            maxWaitMs = 5_000,
            sleeper = { true },
            examChannelOf = { c, card ->
                A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true })
            },
        ),
    )

    private fun replyOn(taskId: String, track: EnvoyTrack): EnvoyOutcome = EnvoyDeliver(http).reply(
        EnvoyReply(
            endpoint = bed.base(),
            taskId = taskId,
            text = "原文如下：……",
            sessionId = SESSION,
            root = admitRoot,
            track = track,
            pollEveryMs = 1,
            maxWaitMs = 5_000,
            sleeper = { true },
        ),
    )

    private fun eventsOf(kind: String): List<JSONObject> =
        PeerEventsLedger.replay().filter { it.optString("kind") == kind }

    // ── ① refusalOutcome：租约闸拒 ⇒ 零任务字节，拒因是闸原话 ────────────────

    @Test
    fun `分支一_refusalOutcome_租约闸拒_零任务字节_拒因来自闸`() {
        val outcome = deliver(grantOnApproval = false)

        assertNotNull("必须走拒这一支（不是异常）", outcome.refusal)
        assertTrue(
            "拒因是那条固定句式（外发即租约）: ${outcome.refusal}",
            outcome.refusal!!.startsWith("这次委派没有批准，没有发起（外发即租约）："),
        )
        assertEquals("任务号空（一个字节没发）", "", outcome.taskId)
        assertEquals("状态未知", A2aTaskState.UNKNOWN, outcome.state)
        assertNull("不是线上失败", outcome.failure)
        assertEquals("床侧一次 tasks/get 都没有（零轮询）", 0, bed.rpcMethods.count { it == "tasks/get" })
        assertEquals("床侧一次 message/send 都没有（零派活）", 0, bed.servedTicks.count { it == "submitted" })
        assertEquals("闸上留痕一行 gate/refused", 1, eventsOf(PeerEventKinds.GATE).size)
        assertEquals(
            "账行写的就是 refused",
            PeerFailureCode.REFUSED.wire,
            eventsOf(PeerEventKinds.GATE).single().optString("result"),
        )
        assertTrue("派活账一行都没有", eventsOf(PeerEventKinds.DELEGATE).isEmpty())
    }

    // ── ② admissionRefused：准入没装配 ⇒ fail-closed，不派活 ────────────────

    @Test
    fun `分支二_admissionRefused_准入没装配_不派活_账上记准入拒`() {
        val outcome = deliver(admitRootOf = null)

        assertNotNull("必须走准入拒这一支", outcome.refusal)
        assertEquals("拒因是准入那侧的读数人话（no_root fail-closed）", EnvoyAdmission.NO_ROOT, outcome.admission?.verdictWire)
        assertEquals("任务号空（零任务字节）", "", outcome.taskId)
        assertEquals("床侧零派活", 0, bed.servedTicks.count { it == "submitted" })
        assertEquals("闸上留痕一行 gate/refused", 1, eventsOf(PeerEventKinds.GATE).size)
        assertTrue(
            "账行点明是准入闸拦的: ${eventsOf(PeerEventKinds.GATE).single().optString("detail")}",
            eventsOf(PeerEventKinds.GATE).single().optString("detail").startsWith("准入（"),
        )
    }

    // ── ③ kindRefusal：门开了但只读档 ⇒ 写类活不派，读类活照派 ──────────────

    @Test
    fun `分支三_kindRefusal_只读档派写类活_拒且点名降权_读类活仍派`() {
        val write = deliver(writes = true)

        assertNotNull("写类活必须被拒", write.refusal)
        assertTrue(
            "拒因是 kindRefusal 那句（降权不是禁用）: ${write.refusal}",
            write.refusal!!.contains("准入档是只读（降权不是禁用）：写类活不派给它，读类活照派"),
        )
        assertEquals("档位是只读", com.example.zhengdao.baopu.intake.Clearance.READ_ONLY, write.admission?.tier)
        // 注意：不能数 bed.servedTicks 的 submitted——九道考卷也走 message/send，会被算进去。
        assertEquals("零任务字节（没派活）", "", write.taskId)
        assertTrue("没有派活账行（派活都被拒了）", eventsOf(PeerEventKinds.DELEGATE).isEmpty())

        // 对照：同一档，读类活照派（证明拒的是「写」不是「这个人」）
        val read = deliver(writes = false)
        assertNull("读类活放行: ${read.refusal.orEmpty()}", read.refusal)
        assertEquals(A2aTaskState.COMPLETED, read.state)
    }

    // ── ④ lineFailed：线上失败（连不上）⇒ 租约照结算，账行按失败类落 ────────

    @Test
    fun `分支四_lineFailed_连不上对端_失败类落账_不是拒`() {
        val outcome = deliver(endpoint = "http://127.0.0.1:1")

        assertNull("这不是闸拒（闸过了）", outcome.refusal)
        assertNotNull("是线上失败", outcome.failure)
        assertEquals("状态未知", A2aTaskState.UNKNOWN, outcome.state)
        val rows = eventsOf(PeerEventKinds.DELEGATE)
        assertEquals("派活账一行", 1, rows.size)
        assertEquals(
            "账行的失败类＝端口不通",
            PeerFailureCode.PORT.wire,
            rows.single().optString("result"),
        )
        assertTrue("账行带 op= 与 trail=", rows.single().optString("detail").contains("op="))
        assertEquals("零任务字节（卡都没拉到）", 0, bed.servedTicks.count { it == "submitted" })
    }

    // ── ⑤ anomaly：对端把状态机讲乱 ⇒ 停手收场，归 protocol ──────────────────

    @Test
    fun `分支五_anomaly_终态后回退_停手收场_不硬猜`() {
        val first = deliver()
        assertEquals("先走通一条", A2aTaskState.COMPLETED, first.state)
        assertTrue("终态粘住", first.track.settled)

        // 对端把这条已完成的任务「回退」成 working ⇒ 非法迁移 ⇒ anomaly
        bed.replyScript = A2aBedScript.NORMAL
        val back = replyOn(first.taskId, first.track)

        assertNotNull("必须走 anomaly 那一支（失败有话说）", back.failure)
        assertTrue(
            "失败话术点明状态机对不上: ${back.failure}",
            back.failure!!.contains("状态迁移对不上 A2A 状态机"),
        )
        assertEquals("状态取对端最新（working），不粉饰", A2aTaskState.WORKING, back.state)
        val lastStep = back.track.trail.last()
        assertFalse(
            "轨迹上那一步是非法迁移（终态→working），账照记但认得出",
            EnvoyLaw.legal(lastStep.from, lastStep.to),
        )
        val rows = eventsOf(PeerEventKinds.DELEGATE)
        assertEquals("前一条正常收场 ＋ 这一条 anomaly 收场", 2, rows.size)
        // settled() 写的 result 是 task.state.wire（不是失败码）；非法那一步在 detail 的 trail 里留痕。
        // 注意：回放顺序不等于追加顺序（同毫秒 + 按 id 排序），所以不取 last()，按内容找。
        assertTrue(
            "必须有一行账把那条非法迁移记下来: ${rows.map { it.optString("detail") }}",
            rows.any { it.optString("detail").contains("→working(reply)") },
        )
        assertEquals(
            "恰好一行是 anomaly 那条（result 是对端真吐的 working，不粉饰）",
            1,
            rows.count { it.optString("result") == "working" },
        )
    }

    // ── ⑥ settled：唯一正常收场（流程测试已覆盖，这里把「该支产物」钉住）────

    @Test
    fun `分支六_settled_正常收场_租约结算ok_派活账ok`() {
        val outcome = deliver()

        assertNull("没有拒", outcome.refusal)
        assertNull("没有失败", outcome.failure)
        assertFalse("没超时", outcome.timedOut)
        assertEquals(A2aTaskState.COMPLETED, outcome.state)
        assertEquals("产物收读", listOf("570 的产出正文"), outcome.artifacts.single().texts)
        val rows = eventsOf(PeerEventKinds.DELEGATE)
        assertEquals("派活账一行", 1, rows.size)
        assertEquals("账行是终态不是 ok 字面", "completed", rows.single().optString("result"))
        assertTrue("账行带 task= 与 trail=", rows.single().optString("detail").contains("task="))
        assertEquals("走的是 settled 那条（无 gate 拒行）", 0, eventsOf(PeerEventKinds.GATE).size)
    }

    private companion object {
        const val SESSION = 549L
    }
}
