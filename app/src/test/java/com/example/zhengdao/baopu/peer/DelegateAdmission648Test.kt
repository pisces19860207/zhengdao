// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十八批 · **委派准入两缺口**的判据（派工单 648，来源 572 L3 演练取证）。
//
// 572 把整条链跑通了，取到的是两个"没接上"：
//   · G-572-3 —— `SubAgentWhitelist.FORBIDDEN` 是子代理禁区清单，可委派路径**根本没查它**：
//     一台对端把禁区工具换个皮挂成 A2A skill，派活的闸一句都没说。
//   · G-572-4 —— 准入考试只挂在"首次委派"的闸序里：用户想摸清一台对端的底，
//     要么等模型哪天派活，要么没办法。548 的探针只拉卡不考。
//
// 本文件钉的是**改完之后**：
//   判据① 禁区真挡委派：工具/能力/端点三类逐项比对；命中 ⇒ 拒 ＋ 一行 `gate/refused` 账
//          （要说清命中哪一类哪一项）＋ 零任务字节；未命中 ⇒ 549 那条链照走（不回归）。
//   判据① 同一份清单：主 agent（子代理派发）与委派两条路径**调同一个判定函数**（源码断言），
//          禁区扫描里没有第二份 `in FORBIDDEN`。
//   判据② 独立出题入口：主动出题 ⇒ 真考九道题、三本既有账各落自己的格（不新造账本）；
//          补考 ⇒ 再真考一场（拿旧卷交差就是红）；未通过 ⇒ 只读可用、写类被拒（负例）。
//   判据② 一套考试实现：出题入口与首次委派合用同一个考试体（源码断言：`IntakeExam.take`
//          在 peer 侧只出现一次；行为断言：先出题、后委派 ⇒ 委派那一次不重考）。
//   判据③ 登记不松硬线：新工具一样 AGENT 组（默认不可见）＋ R2 点名必问；放权档免不掉卡；
//          落点没装配 ⇒ 明确拒绝，不静默成功。
//
// 纪律：549 的判定（EnvoyDeliver 闸序/EnvoyAdmission 判卷/EnvoyTrack 状态机）一字未动，
// 384/546 的判卷规则一字未改（本批只给 546 的入口加了一格"主动补考不读缓存"，缺省行为不变）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.agent.AskTier
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.multiagent.SubAgentWhitelist
import com.example.zhengdao.baopu.agent.multiagent.SubTask
import com.example.zhengdao.baopu.authz.LeaseKinds
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.intake.AdmissionFlow
import com.example.zhengdao.baopu.intake.ExamChannel
import com.example.zhengdao.baopu.intake.IntakeExam
import com.example.zhengdao.testkit.Peer570A2aBed
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateAdmission648Test {

    private val sessionKey = "id:648"
    private val sessionId = 648L

    private lateinit var bed: Peer570A2aBed
    private lateinit var root: File
    private lateinit var admitRoot: File
    private lateinit var workspace: File

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // ── 场地 ────────────────────────────────────────────────────────────────

    /**
     * 只起床＋三本账的落点，**不预跑准入**：判据① 要看的正是「出发前有没有咬到」，
     * 判据② 要数的是「第几趟真考了」——场地里先考一场就把读数污染了。
     */
    private fun stage() {
        bed = Peer570A2aBed().also { it.start() }
        root = TempSandbox.create("zd648")
        admitRoot = File(root, "抱朴记忆").also { it.mkdirs() }
        workspace = File(root, "ws").also { it.mkdirs() }
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.resetForTest()
        PeerEventsLedger.installRoot { root }
        assertTrue("单活跃门要抢到（出题与委派都按这一轮会话记账）", ActiveSessionGate.tryAcquire(sessionKey))
    }

    @After
    fun tearDown() {
        ActiveSessionGate.release(sessionKey)
        EnvoyTool.sink = null
        EnvoyExamTool.sink = null
        LeaseStore.resetForTest()
        PeerEventsLedger.resetForTest()
        AgentRegistry.resetForTest()
        if (::bed.isInitialized) bed.close()
        if (::root.isInitialized) {
            assertTrue("沙盒要删干净（水位纪律）", TempSandbox.deleteTree(root) == 0)
        }
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    /** 快通道：题一道不落空、轮询不真睡（生产默认 200ms，九道题就是 4 秒白等）。 */
    private fun fastChannel(): (A2aClient, A2aCard) -> ExamChannel =
        { c, card -> A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true }) }

    private fun examRequest(endpoint: String = bed.base(), grantOnApproval: Boolean = true) = EnvoyExamRequest(
        endpoint = endpoint,
        sessionId = sessionId,
        root = admitRoot,
        grantOnApproval = grantOnApproval,
        examChannelOf = fastChannel(),
    )

    private fun deliverRequest(task: String, writes: Boolean = false, endpoint: String = bed.base()) = EnvoyRequest(
        endpoint = endpoint,
        task = task,
        writes = writes,
        sessionId = sessionId,
        root = admitRoot,
        examChannelOf = fastChannel(),
    )

    /** 生产注册表：走 [BaopuToolCatalog.loadInto]（表序即注册序），不手搓 spec。 */
    private fun productionRegistry(agentUnlocked: Boolean): ToolRegistry {
        val prefs =
            if (agentUnlocked) InMemoryToolPrefs(initialApproved = setOf(ToolGroup.AGENT)) else InMemoryToolPrefs()
        return ToolRegistry(prefs).also { BaopuToolCatalog.loadInto(it) { workspace } }
    }

    private fun pipeline(reg: ToolRegistry, tier: AskTier = AskTier.STANDARD) = GuardPipeline(
        reg,
        { workspace },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        {},
        askTier = { tier },
    )

    /** 真装配那一行（[BaopuRuntime.examPeerAgent]）：测试里复刻一份接线就等于没接。 */
    private fun installProductionExamSink() {
        EnvoyExamTool.sink = EnvoyExamSink { args ->
            BaopuRuntime.examPeerAgent(EnvoyDeliver(http), admitRoot, args)
        }
    }

    private fun examArgs(endpoint: String = bed.base()) = JSONObject().put("endpoint", endpoint)

    private fun examCall(endpoint: String = bed.base()) = ToolCall("c-648", EnvoyExamTool.NAME, examArgs(endpoint))

    private fun sends(): Int = bed.rpcMethods.count { it == "message/send" }

    /** 出题账逐行（`准入出题账.jsonl` 原样，不改笔迹）。 */
    private fun examLines(): List<JSONObject> = IntakeExam.history(admitRoot).map { JSONObject(it) }

    /** 准入账逐行（`准入账.jsonl`：384-A 那本，`admission` 那一格就是档位）。 */
    private fun admissionLines(): List<JSONObject> {
        val f = File(admitRoot, AdmissionFlow.LEDGER_FILE)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    private fun gateRows(): List<JSONObject> =
        PeerEventsLedger.replay().filter { it.optString("kind") == PeerEventKinds.GATE }

    private fun mainSource(relative: String): String {
        val f = File("src/main/java/com/example/zhengdao/baopu/$relative")
        assertTrue("找不到 $f（单测工作目录应在 app/ 下）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    // ── 判据① 禁区真挡委派：三类各咬一条，命中 ⇒ 拒＋账行＋零任务字节 ─────────────

    @Test
    fun `判据一 端点里挂着禁区名 连卡都不拉`() {
        stage()
        val out = EnvoyDeliver(http).deliver(deliverRequest("派一件活", endpoint = "${bed.base()}/novel_readthrough_run/a2a"))
        val refusal = out.refusal
        assertNotNull("端点命中禁区却放行了：$out", refusal)
        assertTrue("拒因要说清命中哪一类哪一项：$refusal", refusal!!.contains("命中禁区端点「novel_readthrough_run」"))

        assertEquals("这一步在拉卡之前，卡节的字节都不该出", 0, bed.cardPaths.size)
        assertEquals("禁区命中后还发了任务字节", 0, sends())
        assertTrue("出题账不该有行（考试在禁区之后）", examLines().isEmpty())
        assertTrue("准入账不该有行", admissionLines().isEmpty())

        val gate = gateRows().single()
        assertEquals("命中要落一行 gate/refused 账", PeerFailureCode.REFUSED.wire, gate.optString("result"))
        assertTrue("账行要说清命中哪条：${gate.optString("detail")}", gate.optString("detail").contains("命中禁区端点"))
    }

    @Test
    fun `判据一 卡上自报的禁区工具与禁区能力各咬一条`() {
        stage()
        bed.extraSkills = listOf("portrait_appearance")
        val byTool = EnvoyDeliver(http).deliver(deliverRequest("把这份材料摘要一下"))
        assertTrue("工具类没咬住：${byTool.refusal}", byTool.refusal!!.contains("命中禁区工具「portrait_appearance」"))
        assertEquals("卡到手就命中，考卷一道没发", 0, sends())
        assertTrue("拉卡本身要发生（不然测不到卡上扫描）", bed.cardPaths.isNotEmpty())
        assertEquals("事件账缺一行 gate", 1, gateRows().size)
        assertTrue(gateRows().first().optString("detail").contains("命中禁区工具"))

        bed.extraSkills = emptyList()
        bed.extraCapabilities = listOf("novel_readthrough_run")
        val byCap = EnvoyDeliver(http).deliver(deliverRequest("换个对端再派一次"))
        assertTrue("能力类没咬住：${byCap.refusal}", byCap.refusal!!.contains("命中禁区能力「novel_readthrough_run」"))
        assertEquals("能力类命中也不许发任务字节", 0, sends())
        assertTrue("出题账两次的读数是假的（禁区在考试之前）", examLines().isEmpty())
    }

    @Test
    fun `判据一 未命中的照常走完 不回归`() {
        stage()
        // 床默认卡：skills=[summarize]、capabilities=[pushNotifications,streaming]——一件不在禁区里。
        val out = EnvoyDeliver(http).deliver(deliverRequest("把这份材料摘要一下（648 不回归）"))
        assertNull("未命中禁区却被挡了：${out.refusal}", out.refusal)
        assertEquals("549 那条链的形状变了", A2aTaskState.COMPLETED, out.state)
        assertTrue("委派账要有一行 completed 收场", PeerEventsLedger.replay().any { it.optString("kind") == PeerEventKinds.DELEGATE })
        assertEquals("租约结算收口", "ok", LeaseLedger.rowsForOp(out.opId).last().optString("result"))
    }

    @Test
    fun `判据一 两条路径同一份清单同一个判定函数`() {
        // 行为侧：清单里每一件，委派的两档扫描都要咬、子代理的判定也要咬；清单外一件不咬。
        for (name in SubAgentWhitelist.FORBIDDEN) {
            assertNotNull("$name 在卡上扫不出来（清单没共用）", SubAgentWhitelist.forbiddenOnCard(listOf(name), listOf(name), emptyList()))
            assertNotNull("$name 藏在端点里扫不出来", SubAgentWhitelist.forbiddenAtEndpoint("https://peer.example/$name/a2a"))
            val task = SubTask(id = "s-648", parentId = "", seq = 0, goal = "g", tools = listOf(name))
            assertTrue("$name 子代理判定放行了", SubAgentWhitelist.decide(name, task) is SubAgentWhitelist.Decision.Reject)
        }
        assertNull(
            "清单外的一件被扫成命中（禁区在长胖）",
            SubAgentWhitelist.forbiddenOnCard(listOf("summarize"), listOf("摘要"), listOf("streaming", "pushNotifications")),
        )

        // 源码侧：两条路径调的是同一个判定函数，委派那一侧没有第二份清单、也没有第二次比对。
        val runner = mainSource("agent/multiagent/SubAgentRunner.kt")
        assertTrue("子代理那一侧不再走 decide（判定函数被搬走了）", runner.contains("SubAgentWhitelist.decide("))
        val deliver = mainSource("peer/EnvoyDeliver.kt")
        assertTrue("委派那一侧没调端点扫描", deliver.contains("SubAgentWhitelist.forbiddenAtEndpoint(endpoint)"))
        assertTrue("委派那一侧没调卡上扫描", deliver.contains("SubAgentWhitelist.forbiddenOnCard("))
        assertFalse("委派侧自己比了一份清单（两套判定的起点）", deliver.contains("FORBIDDEN"))

        val whitelist = mainSource("agent/multiagent/SubAgentWhitelist.kt")
        val scan = whitelist.substringAfter("private fun scan(").substringBefore("private fun endpointParts")
        assertFalse("扫描体里自己写了一遍 in FORBIDDEN（判定函数不再唯一）", scan.contains("FORBIDDEN"))
        assertTrue("扫描体没走 decide", scan.contains("decide("))
    }

    @Test
    fun `判据一 子代理不许委派 写名单也不给它`() {
        stage()
        assertTrue("delegate_peer_task 没进禁区（549 §七.4 那条遗留又活了）", SubAgentWhitelist.FORBIDDEN.contains(EnvoyTool.NAME))
        val task = SubTask(
            id = "s-648", parentId = "", seq = 0, goal = "把活转手派出去",
            tools = listOf(EnvoyTool.NAME), writes = listOf(EnvoyTool.NAME),
        )
        val d = SubAgentWhitelist.decide(EnvoyTool.NAME, task)
        assertTrue("子 Agent 点名给它也不拒（无人在场外发）：$d", d is SubAgentWhitelist.Decision.Reject)
        val writes = SubAgentWhitelist.effectiveWrites(listOf(EnvoyTool.NAME), setOf(EnvoyTool.NAME)) { true }
        assertFalse("禁区工具混进了写名单", EnvoyTool.NAME in writes)
    }

    // ── 判据② 独立出题入口：真考、落既有三本账、不新造账本 ─────────────────────────

    @Test
    fun `判据二 主动出题真考九道 三本既有账各落自己的格`() {
        stage()
        val out = EnvoyDeliver(http).exam(examRequest())
        assertTrue("这一场没真考：$out", out.examined)

        assertEquals("九道题＝九次 message/send（一件任务也没派）", 9, sends())
        val agentId = out.agentId
        assertTrue("身份要落进既有画像账", agentId.startsWith("ag-"))

        val lines = examLines()
        assertEquals("出题账缺一行", 1, lines.size)
        assertFalse("主动出题不许拿缓存交差", lines[0].optBoolean("from_cache"))
        assertEquals("题量必须是生产常量那九道", 9, lines[0].optInt("asked"))
        assertEquals(agentId, lines[0].optString("agent"))

        val admission = admissionLines()
        assertEquals("准入账缺一行（不新造账本＝就落这本）", 1, admission.size)
        assertEquals("两把尺：档位记在准入账", AdmissionFlow.load(admitRoot, agentId)?.wire(), admission[0].optString("admission"))

        assertNotNull("画像快照没落盘", AdmissionFlow.load(admitRoot, agentId))
        assertTrue("考卷缓存没落盘", IntakeExam.cacheFile(admitRoot, agentId).isFile)

        val lease = LeaseLedger.rowsForOp(out.opId)
        assertEquals("出题那两行租约账（use＋outcome）配不齐：$lease", 2, lease.size)
        assertEquals(LeaseKinds.USE, lease[0].optString("kind"))
        assertEquals(LeaseKinds.OUTCOME, lease[1].optString("kind"))
        assertEquals("账面工具名要分清出题与派活", EnvoyDeliver.EXAM_LEDGER_TOOL, lease[1].optString("tool"))
        assertEquals("租约照结算（考完要收口）", "ok", lease[1].optString("result"))
    }

    @Test
    fun `判据二 补考真重考一场 而委派那一趟不重考`() {
        stage()
        val client = A2aClient(bed.base(), http)
        val card = client.fetchCard()
        val version = card.capabilityFingerprint()

        EnvoyDeliver(http).exam(examRequest())
        assertEquals(1, examLines().size)
        // 同一场再补一次：缓存还在（版本一个字没变），但主动补考**不许**读它。
        EnvoyDeliver(http).exam(examRequest())
        val lines = examLines()
        assertEquals("补考没多出一行（旧卷被当成交卷了）", 2, lines.size)
        assertFalse("补考那一行是从缓存里翻出来的", lines[1].optBoolean("from_cache"))
        assertEquals("补考要重新发九道题", 18, sends())

        // 对照：同一条链走 ensure（首次委派那一格）⇒ 画像在、版本对得上 ⇒ 不重考。
        val reading = EnvoyAdmission.ensure(
            root = admitRoot, endpoint = bed.base(), executorVersion = version,
            channel = fastChannel()(client, card), claimed = card.skills.map { it.id }, nowMs = 0L, log = {},
        )
        assertFalse("委派那一趟重考了（两道入口的分工没成立）", reading.examined)
        assertEquals("ensure 偷跑了一场考试", 2, examLines().size)
        assertEquals("不重考就不许多发字节", 18, sends())
    }

    @Test
    fun `判据二 未通过只读可用 写类被拒`() {
        stage()
        val exam = EnvoyDeliver(http).exam(examRequest())
        val tier = exam.admission?.tier
        assertEquals("570 床这场考出来该是只读档（写类探针必不过）", "READ_ONLY", tier?.name)
        val sendsAfterExam = sends()

        val read = EnvoyDeliver(http).deliver(deliverRequest("读它的清单（只读档该接）"))
        assertNull("只读档连读活也不给了：${read.refusal}", read.refusal)

        val write = EnvoyDeliver(http).deliver(deliverRequest("替我把它的配置改掉（写类活）", writes = true))
        assertNotNull("写类活放行＝负例该红", write.refusal)
        assertTrue("拒因要说清是档位说的：${write.refusal}", write.refusal!!.contains("写类活不派给它"))
        assertEquals("被拒的写活多发任务字节了", sendsAfterExam + 1, sends())
    }

    @Test
    fun `判据二 出题入口与首次委派共用同一套考试实现`() {
        // 源码侧：peer 这一侧的考试体只有一处调用点，两道入口合流到同一个 private。
        val admission = mainSource("peer/EnvoyAdmission.kt")
        assertEquals(
            "EnvoyAdmission 里出现了不止一处 IntakeExam.take(（两套考试就是这么长出来的）",
            1, Regex(Regex.escape("IntakeExam.take(")).findAll(admission).count(),
        )
        assertTrue("ensure 没走合流后的考试体", admission.substringAfter("fun ensure(").substringBefore("\n    }").contains("examine("))
        assertTrue("retest 没走合流后的考试体", admission.substringAfter("fun retest(").substringBefore("\n    }").contains("examine("))
        val examTool = mainSource("peer/EnvoyExamTool.kt")
        assertTrue("出题工具没声明走 retest 那一条链", mainSource("peer/EnvoyDeliver.kt").contains("EnvoyAdmission.retest("))
        assertTrue("出题工具不该自己出题", !examTool.contains("IntakeExam."))

        // 行为侧：先出题、后委派 ⇒ 委派那一次只多发一件活，考卷零字节。
        stage()
        EnvoyDeliver(http).exam(examRequest())
        val after = sends()
        val out = EnvoyDeliver(http).deliver(deliverRequest("出题之后再派一件读活"))
        assertNull("委派被自己的考试挡了：${out.refusal}", out.refusal)
        assertEquals("委派又考了一场（缓存/画像没共用）", after + 1, sends())
        assertEquals("出题账仍只有一场", 1, examLines().size)
    }

    @Test
    fun `判据二 没批准就考不成 零考卷字节`() {
        stage()
        val out = EnvoyDeliver(http).exam(examRequest(grantOnApproval = false))
        assertNotNull("没人批也考成了（外发即租约失守）：$out", out.refusal)
        assertEquals("未批准不许出一个外发字节", 0, sends())
        assertEquals("未批准不许出一个卡节字节", 0, bed.cardPaths.size)
        assertTrue("没考就不该落出题账", examLines().isEmpty())
    }

    // ── 判据③ 登记不松硬线（614 那套 ritual 对新工具原样成立）──────────────────────

    @Test
    fun `判据三 生产表里有它 解锁后模型清单见名见描述`() {
        assertTrue("ENTRIES 没登记它", BaopuToolCatalog.ENTRIES.map { it.name }.contains(EnvoyExamTool.NAME))
        assertEquals(ToolGroup.AGENT, ToolGroupTable.GROUP_OF[EnvoyExamTool.NAME])
        assertTrue("DEFAULT_ENABLED 漏了它", BaopuToolCatalog.DEFAULT_ENABLED.contains(EnvoyExamTool.NAME))

        assertTrue(productionRegistry(true).names().contains(EnvoyExamTool.NAME))
        assertFalse(
            "未解锁 AGENT 组就露面",
            productionRegistry(false).visibleSpecs(sessionId).any { it.name == EnvoyExamTool.NAME },
        )
        val entry = productionRegistry(true).toolsJsonOpenAi(sessionId).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull {
                it.getJSONObject("function").optString("name") == EnvoyExamTool.NAME
            }
        }
        assertNotNull("tools 数组里没有它", entry)
        assertEquals("描述没走注入", EnvoyExamTool.DESCRIPTION, entry!!.getJSONObject("function").optString("description"))
    }

    @Test
    fun `判据三 放权档也要人批 批准前零字节`() {
        stage()
        installProductionExamSink()
        val verdict = RiskMatrix.classify(examCall())
        assertEquals("档位不是 R2", RiskClass.R2, verdict.risk)
        assertFalse("登记后反而未分类", verdict.unclassified)
        assertTrue("矩阵没点名必问", verdict.mustAsk)
        assertTrue("表里这行 alwaysAsk 没打开", RiskTable.DEFAULT.tools.getValue(EnvoyExamTool.NAME).alwaysAsk)

        val pipeline = pipeline(productionRegistry(true), tier = AskTier.DELEGATED)
        val asked = pipeline.execute(examCall()) { _, _ -> null }
        assertTrue("放权档免掉了出题的授权卡：$asked", asked is GuardPipeline.Result.NeedsConfirm)
        assertEquals("没批准就发了考卷字节", 0, sends())
    }

    @Test
    fun `判据三 落点没装配就明确拒绝 不静默成功`() {
        // 不起床、不 stage：这一条要的就是「装配没跑过」那个状态。
        EnvoyExamTool.sink = null
        val spec = productionRegistry(true).find(EnvoyExamTool.NAME) ?: error("生产注册表没给出 spec")
        val out = spec.executor(JSONObject().put("endpoint", "https://peer.example/a2a"))
        assertTrue("没接线却成了成功：$out", out is ToolOutcome.Rejected)
        assertTrue("拒绝要说明原因：$out", (out as ToolOutcome.Rejected).reason.contains("没接线"))
    }

    @Test
    fun `判据三 生产接线经管线跑到 examPeerAgent 那一段`() {
        stage()
        installProductionExamSink()
        // 这一条走生产默认节奏（200ms 轮询），要的就是「真装配那两行」被跑到：约四秒，不注快捷通道。
        val done = pipeline(productionRegistry(true)).executeApproved(examCall()) { _, _ -> null }
        assertTrue("出题没走完：$done", done is GuardPipeline.Result.Done)
        val wrapped = (done as GuardPipeline.Result.Done).wrapped
        assertTrue("回执不是从 examPeerAgent 出来的：$wrapped", wrapped.contains("准入考试收场："))
        assertTrue("回执要带档位（准入账那一格的真值）：$wrapped", wrapped.contains(AdmissionFlow.load(admitRoot, AgentIds.stableId(bed.base()))!!.wire()))
        assertTrue("两把尺这句话要跟着回执走：$wrapped", wrapped.contains("两把尺"))
        assertEquals("经管线这一次也是九道题", 9, sends())
        assertEquals("装配那一段落的出题账", 1, examLines().size)
    }

    @Test
    fun `判据三 装配点在BaopuRuntime 落点是EnvoyDeliver的exam`() {
        // 真装配要 Android Context（JVM 起不来），所以源面钉住那两行没被人搬走/删掉。
        val src = mainSource("BaopuRuntime.kt")
        assertTrue("出题工具的 sink 没在装配处注入", src.contains("EnvoyExamTool.sink = EnvoyExamSink"))
        assertTrue("落点不是 examPeerAgent", src.contains("examPeerAgent(envoyDeliver, File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)"))
        assertTrue("examPeerAgent 没走同一条闸序", mainSource("BaopuRuntime.kt").contains("deliver.exam(EnvoyExamRequest("))
        assertTrue("委派与出题各建一台外发机（opId 序号会各走各的）", src.contains("EnvoyTool.sink = EnvoySink"))
    }
}
