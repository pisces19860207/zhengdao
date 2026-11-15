// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十二批 · **L3 端到端演练**（派工单 572：用一个假 A2A agent 走完
// 「接入 → 派任务 → 五流 → 回滚 → 进化记一笔」，出一次演练报告）。
//
// 五步（每步一个用例，步内断言即"预期 vs 实际"的机器读数）：
//   步① 接入：拉卡（570 床）→ 548 探针过外发闸注册身份名册（.agents.jsonl）→
//        549 准入画像（546 九道题真考，经 570 床）→ 画像/准入账/出题账落盘；
//   步② 派任务：delegate_peer_task 走 GuardPipeline 全链（授权卡先出、批准后闸序执行）→
//        租约账 grant/use/outcome 三行 ＋ 事件账 delegate 行（带五条流轨迹）；
//   步③ 五条流：submitted / working / input-required(＋回复续同一任务) / completed / canceled
//        （含再取消幂等）——断言的是 EnvoyTrack 轨迹，不只看返回值；
//   步④ 回滚：路由候选（568-C 域② envoy_route，缺省灰度 CANARY 落调整行）→
//        决策中心同款回滚（DecisionRollbacks.run → RouteEvo.revertRoute）→
//        rollback_of 行 ＋ 回滚调整行 ＋ 生效值退回缺省；
//   步⑤ 进化记一笔：进化账（决策记录.jsonl）读回演练的这一笔（tool_grant 路由行）＋
//        灰度观察行 ＋ 样本池读数 ＋ 收工扫档（样本不足不许转正，档位不动）。
//
// 零生产改动：本文件只在测试源集；被测方一律是生产件（A2aClient/A2aPeerProbe/EnvoyAdmission/
// EnvoyDeliver/EnvoyTrack/GuardPipeline/RouteEvo/DecisionRollbacks/ConsequenceWiring），
// 床只当对端。需要改生产代码才能走通的地方不在这里绕——如实红、进报告"缺口"。
//
// 复跑口径（同一脚本两次结果一致）：
//   · 账行时刻全部用固定常量（nowMs 显式传入），轨迹 wire 不含时刻；
//   · 取证落盘前过一遍归一化（床端口→<bed>、13 位以上时刻→<ts>、uuid→<uuid>、
//     哈希型 id→<id>、长 hex→<hash>）——归一化后的取证文件两次运行逐字节一致；
//   · 取证落点由系统属性 drill572.out 给（Gradle init 脚本注入，仓外）；不给则只打印不落盘。
//
// 取证（每步）：账行/事件/轨迹/床侧计数逐条打印（进 JUnit XML 的 system-out）＋
// 归一化后落盘。沙盒走 TempSandbox（水位纪律：建了必删）。
package com.example.zhengdao.baopu.drill

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionRollbackHandler
import com.example.zhengdao.baopu.DecisionRollbacks
import com.example.zhengdao.baopu.agent.AskTier
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseKinds
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.evolution.highrisk.RouteEvo
import com.example.zhengdao.baopu.evolution.highrisk.RouteSemantics
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.baopu.intake.AdmissionFlow
import com.example.zhengdao.baopu.intake.Clearance
import com.example.zhengdao.baopu.intake.ExamChannel
import com.example.zhengdao.baopu.intake.IntakeExam
import com.example.zhengdao.baopu.peer.A2aClient
import com.example.zhengdao.baopu.peer.A2aCard
import com.example.zhengdao.baopu.peer.A2aExamChannel
import com.example.zhengdao.baopu.peer.A2aPeerProbe
import com.example.zhengdao.baopu.peer.A2aTaskState
import com.example.zhengdao.baopu.peer.AgentIds
import com.example.zhengdao.baopu.peer.AgentRegistry
import com.example.zhengdao.baopu.peer.EnvoyAction
import com.example.zhengdao.baopu.peer.EnvoyAdmission
import com.example.zhengdao.baopu.peer.EnvoyDeliver
import com.example.zhengdao.baopu.peer.EnvoyOutcome
import com.example.zhengdao.baopu.peer.EnvoyReply
import com.example.zhengdao.baopu.peer.EnvoyRequest
import com.example.zhengdao.baopu.peer.EnvoySink
import com.example.zhengdao.baopu.peer.EnvoyTool
import com.example.zhengdao.baopu.peer.EnvoyTrack
import com.example.zhengdao.baopu.peer.LeaseOutboundGate
import com.example.zhengdao.baopu.peer.PeerEventsLedger
import com.example.zhengdao.baopu.peer.PeerFailureCode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Drill572L3Test {

    // ── 固定常量（确定性口径：账行时刻全部显式传入，不用墙钟）────────────────────
    private val entropy = "zd572-drill-entropy-2026-11-15"
    private val SESSION = 572L

    /** 步①：接入（探针拉卡＋名册＋准入考试）。 */
    private val T1 = 1_763_180_000_000L

    /** 步②：派任务（管线批准 → 委派）。 */
    private val T2 = 1_763_180_100_000L

    /** 步③：五条流（三件活：正常单／要人单／取消单）。 */
    private val T3A = 1_763_180_200_000L
    private val T3B = 1_763_180_201_000L
    private val T3C = 1_763_180_202_000L

    /** 步④：回滚（候选落行 T4A → 决策中心回滚 T4B）。 */
    private val T4A = 1_763_180_300_000L
    private val T4B = 1_763_180_301_000L

    /** 步⑤：进化记（候选落行 T5A → 收工扫档 T5B）。 */
    private val T5A = 1_763_180_400_000L
    private val T5B = 1_763_180_401_000L

    private lateinit var bed: Peer570A2aBed
    private lateinit var root: File
    private lateinit var admitRoot: File

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // ── 取证：打印 ＋ 归一化落盘（drill572.out 由 init 脚本注入；不给则只打印）──────

    private fun outDir(): File? = System.getProperty("drill572.out")?.let { File(it) }

    /** 归一化：两次运行里**合法会变**的东西（床端口、时刻、各类 id）折成占位符。 */
    private fun norm(s: String): String = s
        .replace(Regex("127\\.0\\.0\\.1:\\d+"), "<bed>")
        .replace(Regex("\\d{13,}"), "<ts>")
        .replace(Regex("\"elapsed_ms\":\\d+"), "\"elapsed_ms\":<ms>")
        .replace(Regex("\\b\\d{1,5}ms\\b"), "<ms>ms")
        .replace(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"), "<uuid>")
        .replace(Regex("\\b(?:ag|zdx|d|le|ls|ex|ob|ev)-[0-9a-f]{6,}\\b"), "<id>")
        .replace(Regex("\\b[0-9a-f]{16,}\\b"), "<hash>")

    /** 一步的取证笔：println（进 XML system-out）＋ 归一化落盘（可复跑比对）。 */
    private inner class Ev(private val step: String) {
        private val file: File? = outDir()?.let { dir ->
            dir.mkdirs()
            val f = File(dir, "step$step.jsonl")
            if (f.isFile) f.delete()
            f
        }

        fun line(s: String) {
            println("[572/$step] ${norm(s)}")
            file?.appendText(norm(s) + "\n", Charsets.UTF_8)
        }

        fun rows(what: String, lines: List<String>) {
            line("$what 共 ${lines.size} 行")
            lines.forEachIndexed { i, raw -> line("$what[$i] $raw") }
        }

        /** 步毕一行（复跑比对与报告都认它：在＝这一步全部断言走完）。 */
        fun pass(what: String) = line("步毕 verdict=PASS what=$what")
    }

    // ── 场地（每步一份：床＋沙盒＋账根；谁装谁还）────────────────────────────────

    private fun stage(prefix: String) {
        bed = Peer570A2aBed().also { it.start() }
        root = TempSandbox.create(prefix)
        admitRoot = File(root, "准入").also { it.mkdirs() }
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.resetForTest()
        PeerEventsLedger.installRoot { root }
    }

    /** 进化链的三件装法（与 568-C 夹具同款：DecisionLedger ＋ ConsequenceWiring ＋ 样本池）。 */
    private fun stageEvolution(nowMs: Long): File {
        val memory = File(root, "抱朴记忆").also { it.mkdirs() }
        DecisionLedger.install(memory)
        ConsequenceWiring.install(root, nowMs) { entropy }
        EvoSampleWiring.install(root)
        return memory
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    private fun examChannel(c: A2aClient, card: A2aCard): ExamChannel =
        A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true })

    private fun deliver(
        task: String,
        nowMs: Long,
        writes: Boolean = false,
        sleeper: (Long) -> Boolean = { true },
    ): EnvoyOutcome = EnvoyDeliver(http).deliver(
        EnvoyRequest(
            endpoint = bed.base(),
            task = task,
            writes = writes,
            sessionId = SESSION,
            root = admitRoot,
            pollEveryMs = 1,
            maxWaitMs = 5_000,
            sleeper = sleeper,
            examChannelOf = { c, card -> examChannel(c, card) },
        ),
        nowMs = nowMs,
    )

    private fun reply(taskId: String, text: String, track: EnvoyTrack?, nowMs: Long): EnvoyOutcome =
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
            nowMs = nowMs,
        )

    private fun cancel(taskId: String, track: EnvoyTrack?, nowMs: Long): EnvoyOutcome =
        EnvoyDeliver(http).cancel(bed.base(), taskId, SESSION, admitRoot, nowMs = nowMs, track = track)

    private fun ledgerRows(f: File): List<String> =
        if (!f.isFile) emptyList() else f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }

    private fun eventsOf(kind: String): List<JSONObject> =
        PeerEventsLedger.replay().filter { it.optString("kind") == kind }

    private fun leaseRows(): List<String> = ledgerRows(LeaseLedger.fileIn(root))

    /** 账行取证：一条 DecisionRecord 逐字段成行（时刻等由 norm 归一化）。 */
    private fun dumpRecord(ev: Ev, what: String, r: DecisionRecord) {
        ev.line("$what id=${r.id} kind=${r.kind.wire} actor=${r.actor.wire} reversible=${r.reversible} " +
            "rolledBack=${r.rolledBack} rollbackOf=${r.rollbackOf}")
        ev.line("$what evidence=${r.evidence} before=${r.before} after=${r.after} rollbackRef=${r.rollbackRef}")
        ev.line("$what reason=${r.reason} at=${r.atMs} session=${r.sessionId}")
    }

    // ── 步① 接入 ────────────────────────────────────────────────────────────

    @Test
    fun `步一_接入_拉卡_名册_准入画像`() {
        stage("zd572-接入")
        val ev = Ev("1-接入")
        ev.line("预期：拉卡五样字段读得出；探针过外发闸（peer/probe）注册 .agents.jsonl；" +
            "准入九道题真考（经 570 床）⇒ accept_read_only；准入账/出题账落盘")

        // ① 裸拉卡：卡上五样（570 床一的同款读数）
        val client = A2aClient(bed.base(), http)
        val card = client.fetchCard()
        ev.line("卡 name=${card.name} protocolVersion=${card.protocolVersion} version=${card.version}")
        ev.line("卡 capabilities=${card.capabilities} securitySchemeNames=${card.securitySchemeNames}")
        ev.line("卡 skills=${card.skills.map { it.id }} capabilityFingerprint=${card.capabilityFingerprint()}")
        assertEquals("卡名来自床", "守夜人-570床", card.name)
        assertTrue("卡要声明安全方案名（不含凭据本体）", card.securitySchemeNames.contains("apiKey"))
        assertTrue("卡要有技能清单", card.skills.isNotEmpty())

        // ② 探针＝过外发闸的第一段字节：人批（grantProbeLease）→ 拉卡 → 名册
        LeaseOutboundGate.grantProbeLease(bed.base(), SESSION, "drill572-接入", T1)
        val probe = A2aPeerProbe(http).probeCard(bed.base(), sessionId = SESSION, nowMs = T1)
        ev.line("探针 opId=${probe.opId} code=${probe.code.wire} human=${probe.human}")
        assertEquals("探测收场 OK", PeerFailureCode.OK, probe.code)

        val roster = AgentRegistry.active()
        assertEquals("名册恰一台在册", 1, roster.size)
        val agent = roster.single()
        ev.line("名册行 id=${agent.id} form=${agent.form.wire} endpoint=${agent.endpoint} " +
            "capFp=${agent.capabilityFingerprint} credFp=${agent.credentialFingerprint} addedAt=${agent.addedAtMs}")
        assertEquals("身份形态是 A2A", "a2a", agent.form.wire)
        assertEquals("能力指纹与卡一致", card.capabilityFingerprint(), agent.capabilityFingerprint)
        val rosterLines = ledgerRows(AgentRegistry.fileIn(root))
        ev.rows("名册", rosterLines)
        assertEquals(".agents.jsonl 一行 register", 1, rosterLines.size)
        assertTrue(rosterLines.single().contains("\"kind\":\"register\""))

        // ③ 准入画像：546 九道题真考（考场开卷、对端是纯文本 ⇒ 写类探针必不过 ⇒ 只读档）
        val reading = EnvoyAdmission.ensure(
            root = admitRoot,
            endpoint = bed.base(),
            executorVersion = card.capabilityFingerprint(),
            channel = examChannel(client, card),
            claimed = card.skills.map { it.id },
            nowMs = T1 + 1,
            log = { ev.line("准入日志 $it") },
        )
        ev.line("准入 agentId=${reading.agentId} verdict=${reading.verdictWire} admitted=${reading.admitted} " +
            "tier=${reading.tier} examined=${reading.examined}")
        assertTrue("准入门开", reading.admitted)
        assertEquals("纯文本对端＝只读档（降权不是禁用）", Clearance.READ_ONLY, reading.tier)
        assertEquals("结论机器键", "accept_read_only", reading.verdictWire)
        assertTrue("这一步是真考", reading.examined)

        val admissionLines = ledgerRows(File(admitRoot, AdmissionFlow.LEDGER_FILE))
        val examLines = ledgerRows(File(admitRoot, IntakeExam.LEDGER_FILE))
        ev.rows("准入账", admissionLines)
        ev.rows("出题账", examLines)
        assertTrue("准入账有 accept 行", admissionLines.any { it.contains("accept") })
        assertEquals("出题账一行（一次考试一行账，行里带题数）", 1, examLines.size)
        val examRow = JSONObject(examLines.single())
        assertEquals("九道题真考了（行里 asked=9）", 9, examRow.optInt("asked"))
        assertFalse("不是没法验（UNVERIFIABLE）", examRow.optBoolean("unverifiable"))
        ev.line("床侧计数 message/send=${bed.rpcMethods.count { it == "message/send" }} " +
            "tasks/get=${bed.rpcMethods.count { it == "tasks/get" }} 拉卡路径=${bed.cardPaths.toList()}")
        ev.pass("接入：卡/名册/准入画像三件全落")
    }

    // ── 步② 派任务 ──────────────────────────────────────────────────────────

    @Test
    fun `步二_派任务_管线全链_授权卡_租约_事件账`() {
        stage("zd572-派任务")
        val ev = Ev("2-派任务")
        ev.line("预期：delegate_peer_task 只从注册表经 GuardPipeline 到达——execute 先出授权卡" +
            "（此刻零字节），executeApproved 后闸序（租约→卡→准入→档位→发活）跑完，" +
            "租约账 grant/use/outcome 三行 ＋ 事件账 delegate 行（带五条流轨迹）")

        // 落点：测试注册表注册生产 spec ＋ sink 接生产 EnvoyDeliver（生产注册与接线是已知缺口，
        // 见报告 G-572-1/G-572-2——本步验证的是闸链本身，不是缺的那两行）
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
                    nowMs = T2,
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
            "c-572", EnvoyTool.NAME,
            JSONObject().put("endpoint", bed.base()).put("task", "把这段材料摘要一下（572 演练）"),
        )

        // ① 未批准：授权卡先出，一个字节不发
        val asked = pipeline.execute(call) { _, _ -> null }
        ev.line("execute ⇒ ${asked::class.simpleName} $asked")
        assertTrue("先出授权卡（R2 必问）", asked is GuardPipeline.Result.NeedsConfirm)
        assertEquals("没批准零字节", emptyList<String>(), bed.rpcMethods.toList())

        // ② 批准：executeApproved ⇒ 闸序执行到底
        val done = pipeline.executeApproved(call) { _, _ -> null }
        ev.line("executeApproved ⇒ ${done::class.simpleName} $done")
        assertTrue("批准后走完", done is GuardPipeline.Result.Done)
        assertTrue("任务字节发到了对端", bed.rpcMethods.contains("message/send"))

        // ③ 闸上留痕：租约三行 ＋ 事件账 delegate 行 ＋ 准入/出题账
        val leases = leaseRows()
        ev.rows("租约账", leases)
        assertTrue(
            "租约 grant 行（cls=peer/delegate）",
            leases.any { it.contains("\"kind\":\"${LeaseKinds.GRANT}\"") && it.contains("peer/delegate") },
        )
        assertTrue(
            "租约 use 行（tool=peer.delegate）",
            leases.any { it.contains("\"kind\":\"${LeaseKinds.USE}\"") && it.contains("peer.delegate") },
        )
        assertTrue(
            "租约 outcome 行（收场结算）",
            leases.any { it.contains("\"kind\":\"${LeaseKinds.OUTCOME}\"") && it.contains("peer.delegate") },
        )
        val delegate = eventsOf("delegate")
        ev.rows("事件账 delegate", delegate.map { it.toString() })
        assertEquals("事件账一行委派收场", 1, delegate.size)
        assertEquals("收场 completed", "completed", delegate.single().optString("result"))
        assertTrue("账行带五条流轨迹", delegate.single().optString("detail").contains("trail="))
        val admissionLines = ledgerRows(File(admitRoot, AdmissionFlow.LEDGER_FILE))
        ev.rows("准入账", admissionLines)
        assertTrue("准入账有行（画像接线真跑了）", admissionLines.isNotEmpty())
        assertTrue("出题账有行（九道题真考了）", ledgerRows(File(admitRoot, IntakeExam.LEDGER_FILE)).isNotEmpty())
        ev.line("床侧计数 message/send=${bed.rpcMethods.count { it == "message/send" }}（九道考卷＋一次派活） " +
            "tasks/get=${bed.rpcMethods.count { it == "tasks/get" }}")
        assertEquals("十发：九道考卷＋一次派活", 10, bed.rpcMethods.count { it == "message/send" })
        assertEquals("二十轮询：考卷每题两轮＋派活两轮", 20, bed.rpcMethods.count { it == "tasks/get" })
        ev.pass("派任务：授权卡→批准→闸序→留痕全链走通")
    }

    // ── 步③ 五条流 ──────────────────────────────────────────────────────────

    @Test
    fun `步三_五条流_submitted_working_input_required_completed_canceled`() {
        stage("zd572-五流")
        val ev = Ev("3-五流")
        ev.line("预期：五条流各在 EnvoyTrack 轨迹上留一步——∅→submitted(send)、submitted→working(poll)、" +
            "working→input-required(poll)、input-required→working(reply) 续同一任务到 completed、" +
            "working→canceled(cancel) 且再取消幂等")

        // 正常单（第一件活把准入考出来；轨迹覆盖 流一/流二/流四）
        val a = deliver("把这份长文摘要一下", nowMs = T3A)
        assertTrue("正常单真发出：${a.refusal.orEmpty()}${a.failure.orEmpty()}", a.sent)
        assertEquals("流四：终态收产物", A2aTaskState.COMPLETED, a.state)
        assertTrue("终态粘住", a.track.settled)
        assertEquals("流一：∅→submitted(send)", "∅→submitted(send)", a.track.trail.first().wire())
        assertEquals("流二：submitted→working(poll)", "submitted→working(poll)", a.track.trail[1].wire())
        assertEquals("流四：completed(poll)", "working→completed(poll)", a.track.trail.last().wire())
        assertEquals("产物正文收读", listOf("570 的产出正文"), a.artifacts.single().texts)
        assertEquals("正常单整条轨迹", "∅→submitted(send)→working(poll)→completed(poll)", a.track.wire())
        ev.line("正常单 opId=${a.opId} taskId=${a.taskId} trail=${a.track.wire()}")

        // 要人单（画像已考过 ⇒ 缓存命中不重考；对端翻到 input-required 剧本）
        bed.script = A2aBedScript.INPUT_REQUIRED
        val b = deliver("这份原文帮我校对一下", nowMs = T3B)
        assertEquals("流三：停下来要人", A2aTaskState.INPUT_REQUIRED, b.state)
        assertTrue("要人不是失败", b.needsHuman)
        assertFalse("不是超时", b.timedOut)
        assertEquals("要人单轨迹", "∅→submitted(send)→working(poll)→input-required(poll)", b.track.wire())
        ev.line("要人单 opId=${b.opId} taskId=${b.taskId} trail=${b.track.wire()}")

        // 回复续同一任务（不是重开）
        val b2 = reply(b.taskId, "原文如下：……（补上的那份）", track = b.track, nowMs = T3B + 1)
        assertEquals("同一个任务号（续不是重开）", b.taskId, b2.taskId)
        assertEquals("续完到终态", A2aTaskState.COMPLETED, b2.state)
        assertEquals("床侧记了一次续话", listOf(b.taskId), bed.replies.toList())
        assertEquals(
            "回复那步落在同一条轨迹上",
            "input-required→working(reply)",
            b2.track.trail.first { it.action == EnvoyAction.REPLY }.wire(),
        )
        assertEquals("续话产物回来", listOf("570 的产出正文"), b2.artifacts.single().texts)
        ev.line("续话 trail=${b2.track.wire()}")

        // 取消单（停在 working 时喊停）
        bed.script = A2aBedScript.NORMAL
        val polls = AtomicInteger(0)
        val c = deliver("先开一个活", nowMs = T3C, sleeper = { polls.incrementAndGet() <= 1 })
        assertEquals("停在 working（喊停不是超时）", A2aTaskState.WORKING, c.state)
        assertFalse(c.timedOut)
        val c1 = cancel(c.taskId, track = c.track, nowMs = T3C + 1)
        assertEquals("流五：取消到 canceled", A2aTaskState.CANCELED, c1.state)
        assertEquals("取消这步接在同一条轨迹上", "working→canceled(cancel)", c1.track.trail.last().wire())
        val c2 = cancel(c.taskId, track = c1.track, nowMs = T3C + 2)
        assertEquals("再取消幂等", A2aTaskState.CANCELED, c2.state)
        assertEquals("canceled→canceled（终态幂等重读）", "canceled→canceled(cancel)", c2.track.trail.last().wire())
        assertEquals("两次取消都走的 tasks/cancel", 2, bed.rpcMethods.count { it == "tasks/cancel" })
        ev.line("取消单 trail=${c2.track.wire()} 床侧 tasks/cancel=${bed.rpcMethods.count { it == "tasks/cancel" }}")

        // 五条流各归其位的账面（事件账 delegate 行按收场分：三件活＋一次续话＋两次取消）
        val delegates = eventsOf("delegate")
        ev.rows("事件账 delegate", delegates.map { it.toString() })
        assertEquals(
            "六行收场（正常单/要人单/续话/取消单停点/取消/再取消）",
            6,
            delegates.size,
        )
        assertEquals(
            "收场序列（账是后进先出，倒读＝时序：正常单/要人单/续话/取消单停点/取消/再取消）",
            listOf("completed", "input-required", "completed", "working", "canceled", "canceled"),
            delegates.reversed().map { it.optString("result") },
        )
        ev.line("床侧总账 message/send=${bed.rpcMethods.count { it == "message/send" }} " +
            "tasks/get=${bed.rpcMethods.count { it == "tasks/get" }}")
        ev.pass("五条流：submitted/working/input-required(＋续话)/completed/canceled 全在轨迹上")
    }

    // ── 步④ 回滚 ────────────────────────────────────────────────────────────

    @Test
    fun `步四_回滚_路由候选_决策中心回滚_生效值退回`() {
        stage("zd572-回滚")
        val ev = Ev("4-回滚")
        val memory = stageEvolution(T4A)
        ev.line("预期：envoy_route 缺省灰度（CANARY）⇒ 路由候选（新 peer 准入 → 偏好指向它）落成" +
            "调整行（tool_grant/route: 族）＋观察行；决策中心同款回滚 ⇒ rollback_of 行＋回滚调整行，" +
            "生效值退回缺省；被回滚行读回标 rolledBack")

        val cap: CanaryCapability = CanaryCapability.ENVOY_ROUTE
        assertEquals(
            "缺省灰度（接线当天生产行为不变的那一格）",
            CanaryBand.CANARY,
            ConsequenceWiring.currentCanaryBand(cap.wire),
        )
        ev.line("档位 envoy_route=${ConsequenceWiring.currentCanaryBand(cap.wire).wire}（缺省灰度）")
        assertNull("起手无调整行 ⇒ 生效路由＝缺省", RouteEvo.effectiveRoute(RouteEvo.BASELINE_ROUTE))

        // 候选：新 peer 准入 ⇒ 路由偏好指向它（生产调用点 AdmissionWiring.routeSink → onPeerAdmitted）
        val agentId = AgentIds.stableId("http://127.0.0.1:570572")
        val landed = RouteEvo.onPeerAdmitted(agentId, T4A)
        ev.line("候选 onPeerAdmitted($agentId) ⇒ $landed")
        assertTrue("灰度档 ⇒ 候选落成调整行", landed)
        val before = RouteEvo.effectiveRoute(RouteEvo.BASELINE_ROUTE)
        assertNotNull("生效值变成候选值", before)
        assertEquals("偏好指向新 peer", agentId, before!!.preferredPeer)
        ev.line("生效路由 preferredPeer=${before.preferredPeer} path=${before.path} " +
            "raw=${ConsequenceWiring.currentRoute(cap.wire)}")

        // 账面：调整行（进化记那一笔的形状）＋观察行（读回是 新→旧）
        val rows0 = DecisionLedger.read(memory)
        val routeRows0 = rows0.filter { it.evidence.startsWith("${ConsequenceEvidence.ROUTE}${cap.wire}:") }
        val obsRows0 = rows0.filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }
        assertEquals("路由调整行一行", 1, routeRows0.size)
        assertTrue("观察行至少一行（每次放行都要落观察账）", obsRows0.isNotEmpty())
        val target = routeRows0.single()
        dumpRecord(ev, "候选调整行", target)
        obsRows0.forEach { dumpRecord(ev, "观察行", it) }
        assertEquals("行 kind=tool_grant（route: 族照先例）", DecisionKind.TOOL_GRANT, target.kind)
        assertTrue("可回滚", target.reversible)
        assertEquals("回滚引用指回 before（缺省＝空串）", "${ConsequenceEvidence.ROUTE}${cap.wire}:", target.rollbackRef)
        assertEquals("行里 before 是缺省（空串）", "", target.before)
        assertEquals("行里 after 是候选值", ConsequenceWiring.currentRoute(cap.wire), target.after)

        // 回滚：决策中心同款（DecisionRollbacks.run → 域活体 RouteEvo.revertRoute）
        val ok = DecisionRollbacks.run(
            target = target,
            root = memory,
            handler = DecisionRollbackHandler { r ->
                RouteEvo.revertRoute(RouteSemantics.decode(r.before), RouteSemantics.decode(r.after), T4B)
            },
            atMs = T4B,
        )
        ev.line("回滚 DecisionRollbacks.run ⇒ $ok")
        assertTrue("回滚成功（成功才留痕）", ok)

        // 回滚后：rollback_of 行 ＋ 回滚调整行 ＋ 生效值退回
        val rows1 = DecisionLedger.read(memory)
        val rollbackRows = rows1.filter { it.kind == DecisionKind.ROLLBACK_OF }
        assertEquals("rollback_of 一行", 1, rollbackRows.size)
        assertEquals("指回被回滚那行", target.id, rollbackRows.single().rollbackOf)
        dumpRecord(ev, "回滚行", rollbackRows.single())
        val routeRows1 = rows1.filter { it.evidence.startsWith("${ConsequenceEvidence.ROUTE}${cap.wire}:") }
        assertEquals("路由行共两行（候选＋回滚）", 2, routeRows1.size)
        val revertRow = routeRows1.first() // 读回 新→旧：回滚行（T4B）在前
        dumpRecord(ev, "回滚调整行", revertRow)
        assertEquals("回滚把值退回 before（缺省＝空串）", "", revertRow.after)
        assertNull("生效值退回缺省", RouteEvo.effectiveRoute(RouteEvo.BASELINE_ROUTE))
        val reread = rows1.first { it.id == target.id }
        assertTrue("被回滚行读回标 rolledBack（读侧折叠）", reread.rolledBack)
        assertFalse("被回滚行不再可回滚（reversible 降级）", reread.reversible)
        assertEquals("档位没动（回滚不拨档）", CanaryBand.CANARY, ConsequenceWiring.currentCanaryBand(cap.wire))
        ev.rows("进化账全量", ledgerRows(DecisionLedger.fileIn(memory)))
        ev.pass("回滚：候选落行→决策中心回滚→生效值退回→账面留痕")
    }

    // ── 步⑤ 进化记一笔 ─────────────────────────────────────────────────────

    @Test
    fun `步五_进化记_进化账读回一笔_样本池_扫档`() {
        stage("zd572-进化记")
        val ev = Ev("5-进化记")
        val memory = stageEvolution(T5A)
        ev.line("预期：演练这件事在进化账上留一笔可读的行（tool_grant/route:envoy_route:…，可回滚、带回滚引用）" +
            "＋灰度观察行＋样本池攒上样本；收工扫档（生产同款）样本不足 ⇒ 档位不动（不许转正）")

        val cap: CanaryCapability = CanaryCapability.ENVOY_ROUTE
        // 进化记的一笔：路由候选落行（候选→灰度 的落账动作，与步④同一条生产写路径）
        val agentId = AgentIds.stableId("http://127.0.0.1:570572")
        assertTrue(RouteEvo.onPeerAdmitted(agentId, T5A))
        // 同一条生产写路径的观察半边（473 观察行＝指标的唯一来源）已由落行带出；
        // 这里再显式记一笔"演练收尾"的观察（生产同款 observeRun 口径）：
        val obsStamped = LowRiskEvo.observeRun(
            cap, ok = true, atMs = T5A + 1, sessionId = SESSION, eventId = "drill572-final",
        )
        ev.line("观察行落账 ⇒ $obsStamped")
        assertTrue("观察行落上", obsStamped)

        // 读回：决策中心/自治理中心同一条读路径（DecisionLedger.read）
        val rows = DecisionLedger.read(memory)
        val routeRows = rows.filter { it.evidence.startsWith("${ConsequenceEvidence.ROUTE}${cap.wire}:") }
        val obsRows = rows.filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }
        assertEquals("路由调整行一行（这就是那一笔）", 1, routeRows.size)
        assertTrue("观察行至少两行（落行一笔＋演练收尾一笔）", obsRows.size >= 2)
        dumpRecord(ev, "进化记·那一笔", routeRows.single())
        obsRows.forEach { dumpRecord(ev, "观察行", it) }
        val entry = routeRows.single()
        assertEquals("kind=tool_grant", DecisionKind.TOOL_GRANT, entry.kind)
        assertEquals(
            "evidence=route:envoy_route:<after>",
            "${ConsequenceEvidence.ROUTE}${cap.wire}:${entry.after}",
            entry.evidence,
        )
        assertTrue("reversible=true（可回滚）", entry.reversible)
        assertTrue(
            "rollbackRef 指回 before",
            entry.rollbackRef.startsWith("${ConsequenceEvidence.ROUTE}${cap.wire}:"),
        )
        assertEquals(
            "生效值读回一致",
            agentId,
            RouteEvo.effectiveRoute(RouteEvo.BASELINE_ROUTE)!!.preferredPeer,
        )

        // 样本池：观察行落完样本跟着攒（589 的口径），读数如实
        val reading = EvoSampleWiring.reading(cap.wire)
        ev.line("样本池读数 ${reading.line()}")
        assertTrue("envoy_route 攒上了样本", reading.events > 0)

        // 收工扫档（生产调用点 EvolutionWiring.onRunSettled 一行）：样本不足 ⇒ 档位不动
        RouteEvo.sweepOnSettle(T5B)
        assertEquals(
            "样本不足不许转正（档位仍灰度）",
            CanaryBand.CANARY,
            ConsequenceWiring.currentCanaryBand(cap.wire),
        )
        val rowsAfterSweep = DecisionLedger.read(memory)
        assertTrue(
            "扫档没动档（档位没动不落账）",
            rowsAfterSweep.none { it.evidence.startsWith("${ConsequenceEvidence.CANARY}${cap.wire}:") },
        )
        ev.line("扫档后档位=${ConsequenceWiring.currentCanaryBand(cap.wire).wire} 样本不足 ⇒ 不落档位行")
        ev.rows("进化账全量", ledgerRows(DecisionLedger.fileIn(memory)))
        ev.pass("进化记一笔：调整行＋观察行＋样本池＋扫档纪律全在账上")
    }

    // ── 小件（续）────────────────────────────────────────────────────────────

    /** EnvoyOutcome → ToolOutcome（生产桥归装配批；夹具里同一套语义，别漂）。 */
    private fun outcomeToTool(o: EnvoyOutcome): ToolOutcome = when {
        o.refusal != null -> ToolOutcome.Rejected(o.refusal)
        o.failure != null -> ToolOutcome.Failed(o.failure)
        else -> ToolOutcome.Ok("委派收场：${o.state.wire}（${o.track.wire()}）")
    }

    @After
    fun tearDown() {
        if (::bed.isInitialized) bed.close()
        LeaseStore.resetForTest()
        PeerEventsLedger.resetForTest()
        AgentRegistry.resetForTest()
        EnvoyTool.sink = null
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        if (::root.isInitialized) {
            assertTrue("沙盒要删干净（水位纪律）", TempSandbox.deleteTree(root) == 0)
        }
    }
}
