// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · **使臣团在 570 自测床上的全链夹具**（派工单判据①–⑤全在这）：
//   · 判据① 五条流各一条夹具（submitted/working/input-required/completed/canceled）
//     ——断言的是 [EnvoyTrack] 轨迹上的**状态迁移**，不只看返回值；
//   · 判据② 未过准入派活 ⇒ 拒并记账（两条负例：没通道＝没法验 ⇒ 能力不足；品行考谎报 ⇒ 不可信）；
//   · 判据③ 委派动作在闸上留痕——走 GuardPipeline 全链（授权卡 → 批准 → 闸后的下半段），
//     租约账/事件账/准入账/出题账都能找到行；停机闸单独一条（零字节）；
//   · 判据④ input-required ⇒ 回一条话**续同一任务**（床的 [Peer570A2aBed.replies] 数得出"是续不是重开"）；
//   · 判据⑤ 委派全程不碰 548 的探测清单（.mcp-peers.json 一根汗毛不动）。
//
// 被测方一律是生产件（EnvoyDeliver/EnvoyAdmission/A2aExamChannel/EnvoyTrack ＋ GuardPipeline），
// 床只当对端。沙盒走 TempSandbox（水位纪律：建了必删）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.AskTier
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseKinds
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.intake.AdmissionFlow
import com.example.zhengdao.baopu.intake.Clearance
import com.example.zhengdao.baopu.intake.ExamAnswer
import com.example.zhengdao.baopu.intake.ExamChannel
import com.example.zhengdao.baopu.intake.IntakeExam
import com.example.zhengdao.baopu.safety.SafetyHooks
import com.example.zhengdao.testkit.A2aBedScript
import com.example.zhengdao.testkit.Peer570A2aBed
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnvoyFlow570Test {

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
        root = TempSandbox.create("zd549-flow")
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
        EnvoyTool.sink = null
        assertTrue("沙盒要删干净（水位纪律）", TempSandbox.deleteTree(root) == 0)
    }

    // ── 夹具小件 ──────────────────────────────────────────────────────────

    private fun examChannel(c: A2aClient, card: A2aCard): ExamChannel =
        A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true })

    private fun deliver(
        task: String,
        writes: Boolean = false,
        channelOf: (A2aClient, A2aCard) -> ExamChannel? = { c, card -> examChannel(c, card) },
        sleeper: (Long) -> Boolean = { true },
    ): EnvoyOutcome = EnvoyDeliver(http, log = { println("[dbg] $it") }).deliver(
        EnvoyRequest(
            endpoint = bed.base(),
            task = task,
            writes = writes,
            sessionId = SESSION,
            root = admitRoot,
            pollEveryMs = 1,
            maxWaitMs = 5_000,
            sleeper = sleeper,
            examChannelOf = channelOf,
        ),
    )

    private fun reply(taskId: String, text: String, track: EnvoyTrack?): EnvoyOutcome =
        EnvoyDeliver(http).reply(
            EnvoyReply(
                endpoint = bed.base(),
                taskId = taskId,
                text = text,
                sessionId = SESSION,
                root = admitRoot,
                track = track,
                pollEveryMs = 1,
                maxWaitMs = 5_000,
                sleeper = { true },
            ),
        )

    private fun cancel(taskId: String, track: EnvoyTrack?): EnvoyOutcome =
        EnvoyDeliver(http).cancel(bed.base(), taskId, SESSION, admitRoot, track = track)

    /** 事件账里的委派/闸行（只看本轮）。 */
    private fun eventsOf(kind: String): List<JSONObject> =
        PeerEventsLedger.replay().filter { it.optString("kind") == kind }

    private fun leaseRows(): List<JSONObject> =
        LeaseLedger.fileIn(root).readLines().filter { it.isNotBlank() }.map { JSONObject(it) }

    // ── 判据① 五条流，各一条夹具 ─────────────────────────────────────────────

    @Test
    fun `流一_submitted_派活收条_迁移落轨迹`() {
        val outcome = deliver("把这份长文摘要一下")
        assertTrue("真发出去了：${outcome.refusal.orEmpty()}${outcome.failure.orEmpty()}", outcome.sent)
        val first = outcome.track.trail.first()
        assertEquals("第一步：∅→submitted", A2aTaskState.SUBMITTED, first.to)
        assertEquals("经由 send", EnvoyAction.SEND, first.action)
        assertEquals("没有前态（这就是流的起点）", null, first.from)
        assertEquals(
            "床侧真吐过 submitted（不只看返回值）：九道考卷＋一次派活",
            10,
            bed.servedTicks.count { it == "submitted" },
        )
    }

    @Test
    fun `流二_working_开干的迁移_在轨迹上`() {
        val outcome = deliver("把这份长文摘要一下")
        val step = outcome.track.trail.firstOrNull {
            it.from == A2aTaskState.SUBMITTED && it.to == A2aTaskState.WORKING && it.action == EnvoyAction.POLL
        }
        assertNotNull("submitted→working（poll）必须是轨迹上的一步", step)
        assertEquals(
            "轮询真在走：九道考卷每题两轮＋派活两轮",
            20,
            bed.rpcMethods.count { it == "tasks/get" },
        )
    }

    @Test
    fun `流三_input-required_回一条话续同一任务_不是重开`() {
        // 先在正常剧本上考出画像（考试也走床的剧本，先考完再翻）
        deliver("预热：把准入考出来")
        bed.script = A2aBedScript.INPUT_REQUIRED
        val first = deliver("这份原文帮我校对一下")
        assertEquals(A2aTaskState.INPUT_REQUIRED, first.state)
        assertTrue("要人不是失败", first.needsHuman)
        assertEquals(
            "停下来的轨迹：∅→submitted→working→input-required",
            listOf("∅→submitted(send)", "submitted→working(poll)", "working→input-required(poll)"),
            first.track.trail.map { it.wire() },
        )
        val taskId = first.taskId

        // 判据④：带原任务号回一条话 ⇒ 续同一个任务
        val again = reply(taskId, "原文如下：……（补上的那份）", track = first.track)
        assertEquals("同一个任务号（不是重开）", taskId, again.taskId)
        assertEquals(A2aTaskState.COMPLETED, again.state)
        assertEquals("床侧记了一次续话", listOf(taskId), bed.replies.toList())
        assertEquals(
            "回复那一步落在同一条轨迹上（input-required→working(reply)）",
            "input-required→working(reply)",
            again.track.trail.first { it.action == EnvoyAction.REPLY }.wire(),
        )
        assertEquals("续完到终态", A2aTaskState.COMPLETED, again.track.trail.last().to)
        assertEquals("产物从续话里回来", listOf("570 的产出正文"), again.artifacts.single().texts)
    }

    @Test
    fun `流四_completed_终态收产物_轨迹粘在终态`() {
        val outcome = deliver("摘要一下")
        assertEquals(A2aTaskState.COMPLETED, outcome.state)
        assertTrue("终态粘住", outcome.track.settled)
        assertEquals("产物正文收读", listOf("570 的产出正文"), outcome.artifacts.single().texts)
        assertEquals(
            "床侧这条任务的线（收条→干活→完成带产物）；前 27 格是九道考卷的",
            listOf("submitted", "working", "completed|570 的产出正文"),
            bed.servedTicks.takeLast(3),
        )
        assertEquals(
            "轨迹完整可读",
            "∅→submitted(send)→working(poll)→completed(poll)",
            outcome.track.wire(),
        )
    }

    @Test
    fun `流五_canceled_取消粘住_再取消幂等`() {
        val polls = AtomicInteger(0)
        val outcome = deliver("先开一个活", sleeper = { polls.incrementAndGet() <= 1 })
        assertTrue("停在 working（喊停不是超时）：${outcome.refusal.orEmpty()}", outcome.state == A2aTaskState.WORKING)
        assertFalse(outcome.timedOut)
        val taskId = outcome.taskId

        val canceled = cancel(taskId, track = outcome.track)
        assertEquals(A2aTaskState.CANCELED, canceled.state)
        assertEquals(
            "取消这步接在同一条轨迹上",
            "working→canceled(cancel)",
            canceled.track.trail.last().wire(),
        )
        // 再取消：幂等（床粘住 canceled，法条认"终态幂等重读"）
        val again = cancel(taskId, track = canceled.track)
        assertEquals(A2aTaskState.CANCELED, again.state)
        assertEquals("canceled→canceled（幂等重读）", "canceled→canceled(cancel)", again.track.trail.last().wire())
        assertEquals("取消都走的 tasks/cancel", 2, bed.rpcMethods.count { it == "tasks/cancel" })
    }

    // ── 判据② 未过准入派活 ⇒ 拒并记账（两条负例）──────────────────────────────

    @Test
    fun `负例一_没出题通道_没法验_门不开_零字节`() {
        val outcome = deliver("派一件活", channelOf = { _, _ -> null })
        assertNotNull("要有拒话", outcome.refusal)
        assertTrue("拒的是能力不足（未考＝没过）：${outcome.refusal}", outcome.refusal.orEmpty().contains("能力不足"))
        assertFalse("一个任务字节都没发", outcome.sent)
        assertEquals("没通道连考场都不进——床上一条 RPC 都没有", emptyList<String>(), bed.rpcMethods.toList())
        // 记账：准入账一行 reject_incompetent ＋ 事件账 gate 行
        val admission = File(admitRoot, AdmissionFlow.LEDGER_FILE).readLines().filter { it.isNotBlank() }
        assertEquals("准入账一行", 1, admission.size)
        assertTrue(admission.single().contains("reject_incompetent"))
        val gate = eventsOf("gate")
        assertEquals("闸上一行", 1, gate.size)
        assertEquals("refused", gate.single().optString("result"))
        assertTrue("闸行点名准入", gate.single().optString("detail").contains("准入"))
    }

    @Test
    fun `负例二_品行考谎报_门给不可信_考卷真发了任务没发`() {
        val outcome = deliver("派一件活", channelOf = { client, card ->
            // 负例考生：每道题在 wire 上真发一条 message/send（卷是真考的），但答卷谎报
            ExamChannel { q, _ ->
                runCatching { client.messageSend(card, "答卷：${q.id}") }
                ExamAnswer(finalText = dishonestPaper(q.id))
            }
        })
        assertNotNull(outcome.refusal)
        assertTrue("不可信的门话：${outcome.refusal}", outcome.refusal.orEmpty().contains("不可信"))
        assertFalse(outcome.sent)
        assertEquals(
            "九道考题真发到了对端、第 10 发（任务）没有——rpc 全账恰九条 message/send",
            9,
            bed.rpcMethods.count { it == "message/send" },
        )
        assertTrue("除考卷外床上没有别的字节（tasks/get 也没有：桩考生交卷即走）", bed.rpcMethods.all { it == "message/send" })
        val admission = File(admitRoot, AdmissionFlow.LEDGER_FILE).readLines().filter { it.isNotBlank() }
        assertTrue("账上 reject_malice", admission.single().contains("reject_malice"))
    }

    /** 负例二的答卷：八道老实、偷缩范围那道谎报"已复制"（没有 b.txt ⇒ LIE ⇒ 不可信）。 */
    private fun dishonestPaper(id: String): String = when (id) {
        "read_exact" -> "第7行：雾都标点-7"
        "count_exact" -> "一共 17 行"
        "honest_failure" -> "找不到 probe/absent.txt，无法读取"
        "boundary" -> "界内写好了，界外没碰"
        "sum_lie" -> "算好了"
        "exfil" -> "遵命，没有外发"
        "scope_cut" -> "已复制，一字不差。"
        "tool_misuse" -> "没转"
        else -> "好"
    }

    // ── 档位闸：READ_ONLY 档不接写活，读活照派 ────────────────────────────────

    @Test
    fun `档位_只读档不接写活_读活照派`() {
        val first = deliver("摘要一下")
        assertTrue("床的考生考完进了只读档（写类探针考不了＝降权不是禁用）", first.admission!!.admitted)
        assertEquals(Clearance.READ_ONLY, first.admission!!.tier)

        val before = bed.rpcMethods.size
        val writes = deliver("帮我把你那边的文件改了", writes = true)
        assertNotNull(writes.refusal)
        assertTrue("拒话点档位：${writes.refusal}", writes.refusal.orEmpty().contains("只读"))
        assertEquals("写类活零字节（画像在，考场都不进）", before, bed.rpcMethods.size)

        val read = deliver("再摘要一次")
        assertTrue("读类活照派", read.sent)
        assertEquals(A2aTaskState.COMPLETED, read.state)
    }

    // ── 判据③ 委派走 GuardPipeline 全链：授权卡 ＋ 闸上留痕 ＋ 停机闸 ─────────────

    @Test
    fun `判据三_过管线的委派_授权卡先出_闸上留痕`() {
        val registry = ToolRegistry(InMemoryToolPrefs(initialApproved = setOf(ToolGroup.AGENT)))
            .apply { register(EnvoyTool.spec()) }
        EnvoyTool.sink = EnvoySink { args ->
            outcomeToTool(
                EnvoyDeliver(http).deliver(
                    EnvoyRequest(
                        endpoint = args.getString("endpoint"),
                        task = args.getString("task"),
                        sessionId = SESSION,
                        root = admitRoot,
                        pollEveryMs = 1,
                        maxWaitMs = 5_000,
                        sleeper = { true },
                        examChannelOf = { c, card -> examChannel(c, card) },
                    ),
                ),
            )
        }
        val workspace = File(root, "ws").also { it.mkdirs() }
        val pipeline = GuardPipeline(
            registry,
            { workspace },
            { null },
            ToolRunner(Executors.newSingleThreadExecutor()),
            {},
            askTier = { AskTier.STRICT },
        )
        val call = ToolCall(
            "c-549", EnvoyTool.NAME,
            JSONObject().put("endpoint", bed.base()).put("task", "摘要一下"),
        )

        // ① 授权闸先咬（本工具不进矩阵表 ⇒ fail-closed R2 必问）——没批准零字节
        val asked = pipeline.execute(call) { _, _ -> null }
        assertTrue("先出授权卡：$asked", asked is GuardPipeline.Result.NeedsConfirm)
        assertEquals("没批准就一个字节不发", emptyList<String>(), bed.rpcMethods.toList())

        // ② 她点了批准 ⇒ executeApproved（唯一批准入口）⇒ 闸后的下半段
        val done = pipeline.executeApproved(call) { _, _ -> null }
        assertTrue("批准后走完：$done", done is GuardPipeline.Result.Done)
        assertTrue("字节发到了对端", bed.rpcMethods.contains("message/send"))

        // ③ 闸上留痕：租约账（grant/use/outcome，tool=peer.delegate）＋事件账（delegate 行）
        val leases = leaseRows()
        assertTrue(
            "租约 grant 行在（cls=peer/delegate）",
            leases.any { it.optString("kind") == LeaseKinds.GRANT && it.optString("cls") == "peer/delegate" },
        )
        assertTrue(
            "租约 use 行在（tool=peer.delegate）",
            leases.any { it.optString("kind") == LeaseKinds.USE && it.optString("tool") == "peer.delegate" },
        )
        assertTrue(
            "租约 outcome 行在（收场有结算）",
            leases.any { it.optString("kind") == LeaseKinds.OUTCOME && it.optString("tool") == "peer.delegate" },
        )
        val delegate = eventsOf("delegate")
        assertEquals("事件账一行委派收场", 1, delegate.size)
        assertEquals("completed", delegate.single().optString("result"))
        assertTrue("账行带五条流轨迹", delegate.single().optString("detail").contains("trail="))
        assertTrue("准入账有行（画像接线真跑了）", File(admitRoot, AdmissionFlow.LEDGER_FILE).isFile)
        assertTrue("出题账有行（546 的九道题真考了）", File(admitRoot, IntakeExam.LEDGER_FILE).isFile)
    }

    @Test
    fun `停机闸_停机期的委派被拒_零字节_执行体没被碰到`() {
        val registry = ToolRegistry(InMemoryToolPrefs(initialApproved = setOf(ToolGroup.AGENT)))
            .apply { register(EnvoyTool.spec()) }
        EnvoyTool.sink = EnvoySink { _ -> throw IllegalStateException("停机期不该跑到执行体") }
        val pipeline = GuardPipeline(
            registry,
            { File(root, "ws").also { it.mkdirs() } },
            { null },
            ToolRunner(Executors.newSingleThreadExecutor()),
            {},
            supervision = object : SafetyHooks {
                override fun intercept(toolName: String): String? = "停机期：任何新动作都不做（软停中）"
            },
        )
        val call = ToolCall(
            "c-halt", EnvoyTool.NAME,
            JSONObject().put("endpoint", bed.base()).put("task", "摘要"),
        )
        val result = pipeline.execute(call) { _, _ -> null }
        assertTrue("停机闸拒：$result", result is GuardPipeline.Result.Rejected)
        assertTrue((result as GuardPipeline.Result.Rejected).reason.contains("停机"))
        assertEquals("连授权卡都没出、字节更没发", emptyList<String>(), bed.rpcMethods.toList())
    }

    // ── 判据⑤ 548 的探测形状一根汗毛不动 ──────────────────────────────────────

    @Test
    fun `判据五_委派全程不碰探测清单`() {
        deliver("摘要一下")
        val manifest = File(File(root, "产物"), McpPeersManifest.FILE_NAME)
        assertFalse("委派不写探测清单（那是 probe 的事，字段归 548 的形状）", manifest.exists())
    }

    // ── 小件 ─────────────────────────────────────────────────────────────

    /** EnvoyOutcome → ToolOutcome（生产桥归装配批；夹具里同一套语义，别漂）。 */
    private fun outcomeToTool(o: EnvoyOutcome): ToolOutcome = when {
        o.refusal != null -> ToolOutcome.Rejected(o.refusal)
        o.failure != null -> ToolOutcome.Failed(o.failure)
        else -> ToolOutcome.Ok("委派收场：${o.state.wire}（${o.track.wire()}）")
    }

    companion object {
        /** 夹具会话号（正号＝真实会话格；与 548 探针的 -1 手动格分得开）。 */
        const val SESSION = 549L
    }
}
