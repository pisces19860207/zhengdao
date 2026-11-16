// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十四批 · `delegate_peer_task` **生产登记**的判据（派工单 614）。
// 549 把使臣团的闸链做完了，但工具没进注册表、sink 没人装（572 演练取证 G-572-1/G-572-2），
// 于是这件工具在模型的世界里根本不存在。本批改的就是那三行，这里钉的是**改完之后**：
//
//   判据① 注册可见：生产工具表（BaopuToolCatalog.ENTRIES）里有它；AGENT 组解锁后，
//          给模型的那份 tools 数组里有它的名字**和描述**（描述靠注入，不靠表）。
//   判据② 调一次：走 549 的闸序（租约→卡→准入→档位→发送/轮询→结算）。
//          过了 ⇒ 事件账 delegate 一行 ＋ 租约账 use/outcome 两行，两边同一个 opId 配得上对；
//          没过（只读档接写活）⇒ 拒，而且**拒也记账**（gate 行 refused ＋ 租约 refused）。
//   判据③ 负例：sink 没装配 ⇒ 明确拒绝，绝不静默成功（静默成功就是假完成）。
//   判据④ 硬线：登记成 R2 之后**每次仍必问**——放权档免不掉这张卡，批准前零任务字节。
//   判据⑤ 装配：BaopuRuntime 里那两行装配代码在源面上钉住（真装配要 Android Context，
//          JVM 单测起不来；判据② 调的是同一段生产接线本体，不在测试里复刻第二份）。
//
// 纪律：549 的判定（EnvoyDeliver/EnvoyAdmission/EnvoyTrack）一字未动，本文件也不重判——
// 断言的是「登记＋接线」这两处补上之后，闸链真的能被调用到。
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
import com.example.zhengdao.baopu.authz.LeaseKinds
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseStore
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
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegateRegisterTest {

    /** 会话号＝外发租约的主体；[ActiveSessionGate] 里的键形如 `id:<n>`（装配层现读的就是它）。 */
    private val sessionKey = "id:614"
    private val sessionId = 614L

    private lateinit var bed: Peer570A2aBed

    /** 沙盒根（租约账/事件账落这里）；[admitRoot] 是准入账落点＝生产里的「抱朴记忆」那一格。 */
    private lateinit var root: File
    private lateinit var admitRoot: File
    private lateinit var workspace: File

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // ── 场地 ────────────────────────────────────────────────────────────────

    private fun stage() {
        bed = Peer570A2aBed().also { it.start() }
        root = TempSandbox.create("zd614")
        admitRoot = File(root, "抱朴记忆").also { it.mkdirs() }
        workspace = File(root, "ws").also { it.mkdirs() }
        LeaseStore.resetForTest()
        LeaseLedger.installRoot { root }
        AgentRegistry.installRoot { root }
        PeerEventsLedger.resetForTest()
        PeerEventsLedger.installRoot { root }
        assertTrue("单活跃门要抢到（租约主体是这一轮会话）", ActiveSessionGate.tryAcquire(sessionKey))
        // 准入画像先考出来（与 572 步① 同一走法）：之后 [EnvoyDeliver] 里的 ensure 命中缓存，
        // 判据② 那一次调用测的是派活闸链，不是重考九道题。
        val client = A2aClient(bed.base(), http)
        val card = client.fetchCard()
        val reading = EnvoyAdmission.ensure(
            root = admitRoot,
            endpoint = bed.base(),
            executorVersion = card.capabilityFingerprint(),
            channel = A2aExamChannel(client, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true }),
            claimed = card.skills.map { it.id },
            nowMs = 0L,
            log = {},
        )
        assertTrue("床要过准入（不过就没有一条判据能跑）", reading.admitted)
    }

    @After
    fun tearDown() {
        ActiveSessionGate.release(sessionKey)
        EnvoyTool.sink = null
        LeaseStore.resetForTest()
        PeerEventsLedger.resetForTest()
        AgentRegistry.resetForTest()
        if (::bed.isInitialized) bed.close()
        if (::root.isInitialized) {
            assertTrue("沙盒要删干净（水位纪律）", TempSandbox.deleteTree(root) == 0)
        }
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    /** 生产注册表：走 [BaopuToolCatalog.loadInto]（表序即注册序），不手搓 spec。 */
    private fun productionRegistry(agentUnlocked: Boolean): ToolRegistry {
        val prefs = if (agentUnlocked) InMemoryToolPrefs(initialApproved = setOf(ToolGroup.AGENT)) else InMemoryToolPrefs()
        return ToolRegistry(prefs).also { BaopuToolCatalog.loadInto(it) { workspace } }
    }

    /** 把生产接线装成 sink（与 BaopuRuntime.build 里那一行同一件函数、同一条落点）。 */
    private fun installProductionSink() {
        EnvoyTool.sink = EnvoySink { args ->
            BaopuRuntime.delegatePeerTask(EnvoyDeliver(http), admitRoot, args)
        }
    }

    private fun pipeline(reg: ToolRegistry, tier: AskTier = AskTier.STANDARD) = GuardPipeline(
        reg,
        { workspace },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        {},
        askTier = { tier },
    )

    private fun args(task: String, writes: Boolean = false, taskId: String = "") = JSONObject()
        .put("endpoint", bed.base())
        .put("task", task)
        .put("writes", writes)
        .apply { if (taskId.isNotEmpty()) put("task_id", taskId) }

    private fun call(args: JSONObject) = ToolCall("c-614", EnvoyTool.NAME, args)

    private fun sends(): Int = bed.rpcMethods.count { it == "message/send" }

    /** 事件账委派行里的 opId（`task=… op=ev-… trail=…`）——账对配对就靠它。 */
    private fun opOf(row: JSONObject): String =
        Regex("op=(\\S+)").find(row.optString("detail"))?.groupValues?.get(1).orEmpty()

    // ── 判据① 注册可见 ──────────────────────────────────────────────────────

    @Test
    fun `判据一 生产工具表里有它 解锁后模型清单见名见描述`() {
        // 表这三格（漏任一格就是「注册了却看不见」那族翻车点）
        assertTrue(
            "BaopuToolCatalog.ENTRIES 没登记它（模型世界里没有这件工具）",
            BaopuToolCatalog.ENTRIES.map { it.name }.contains(EnvoyTool.NAME),
        )
        assertEquals("分组表把它归 AGENT（渐进披露：默认不可见）", ToolGroup.AGENT, ToolGroupTable.GROUP_OF[EnvoyTool.NAME])
        assertTrue("DEFAULT_ENABLED 漏了它（AGENT 组默认不可见但默认启用）", BaopuToolCatalog.DEFAULT_ENABLED.contains(EnvoyTool.NAME))

        val reg = productionRegistry(agentUnlocked = true)
        assertTrue("注册表里没有它", reg.names().contains(EnvoyTool.NAME))
        // 渐进披露的另一半：没解锁 AGENT 组时，这份清单里**不该**有它
        val locked = productionRegistry(agentUnlocked = false)
        assertFalse("未解锁就露面（AGENT 组默认不可见）", locked.visibleSpecs(sessionId).any { it.name == EnvoyTool.NAME })

        // 解锁后给模型的那份 tools 数组：名字＋描述都在，描述逐字来自 spec（**靠注入**，不是靠表）
        val entry = reg.toolsJsonOpenAi(sessionId).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull {
                it.getJSONObject("function").optString("name") == EnvoyTool.NAME
            }
        }
        assertNotNull("tools 数组里没有它", entry)
        assertEquals("描述没走注入（tools 数组里的不是 EnvoyTool.DESCRIPTION）", EnvoyTool.DESCRIPTION, entry!!.getJSONObject("function").optString("description"))
    }

    // ── 判据② 调一次：过了 ⇒ 两本账同 opId 配对 ─────────────────────────────

    @Test
    fun `判据二 批准过一次委派 事件账与租约账同一opId配对`() {
        stage()
        installProductionSink()
        val pipeline = pipeline(productionRegistry(agentUnlocked = true))
        val sendsBefore = sends()

        val done = pipeline.executeApproved(call(args("把这份材料摘要一下（614 判据②）"))) { _, _ -> null }
        assertTrue("委派没走完：$done", done is GuardPipeline.Result.Done)
        val wrapped = (done as GuardPipeline.Result.Done).wrapped
        assertTrue("回执里没有收场终态：$wrapped", wrapped.contains("委派收场：completed"))
        assertTrue("回执要带任务号（否则模型只会重开新任务）：$wrapped", wrapped.contains("task_id="))
        assertTrue("任务字节没发到对端", sends() > sendsBefore)

        val delegate = PeerEventsLedger.replay().single { it.optString("kind") == PeerEventKinds.DELEGATE }
        assertEquals("事件账终态", "completed", delegate.optString("result"))
        assertEquals("事件账 endpoint 是实际发往的对端", bed.base(), delegate.optString("endpoint"))
        val op = opOf(delegate)
        assertTrue("事件账行没带 opId（配不了对）：${delegate.optString("detail")}", op.isNotEmpty())

        val lease = LeaseLedger.rowsForOp(op)
        assertEquals("租约账按 opId 配不上两行（use＋outcome）：$op", 2, lease.size)
        assertEquals(LeaseKinds.USE, lease[0].optString("kind"))
        assertEquals(LeaseKinds.OUTCOME, lease[1].optString("kind"))
        assertEquals("账面工具名要用 549 那一格", EnvoyDeliver.LEDGER_TOOL, lease[1].optString("tool"))
        assertEquals("租约结算没收口", "ok", lease[1].optString("result"))
        val grant = LeaseLedger.replay().single { it.optString("kind") == LeaseKinds.GRANT }
        assertEquals("租约类目是委派这一类", LeaseOutboundGate.DELEGATE_CLS, grant.optString("cls"))
        assertEquals("租约主体是这一轮会话", sessionId, grant.optLong("session"))
    }

    // ── 判据② 的反面：没过闸 ⇒ 拒且记账，一个任务字节都不发 ───────────────────

    @Test
    fun `判据二反面 只读档拒写活 拒也记账且零任务字节`() {
        stage()
        installProductionSink()
        val pipeline = pipeline(productionRegistry(agentUnlocked = true))
        val sendsBefore = sends()

        val done = pipeline.executeApproved(call(args("替我把它的配置改掉（写类活）", writes = true))) { _, _ -> null }
        assertTrue("只读档接写活该被拒，结果是：$done", done is GuardPipeline.Result.Rejected)
        val reason = (done as GuardPipeline.Result.Rejected).reason
        assertTrue("拒因没回给模型：$reason", reason.contains("写类活不派给它"))
        assertEquals("拒的那一次多发任务字节了", sendsBefore, sends())

        val gate = PeerEventsLedger.replay().single { it.optString("kind") == PeerEventKinds.GATE }
        assertEquals("准入闸拒要落一行 gate/refused 账", PeerFailureCode.REFUSED.wire, gate.optString("result"))
        assertTrue("拒因要说清是哪道闸：${gate.optString("detail")}", gate.optString("detail").contains("准入"))

        val outcome = LeaseLedger.replay().single { it.optString("kind") == LeaseKinds.OUTCOME }
        assertEquals("租约照结算（拒也要收口）", PeerFailureCode.REFUSED.wire, outcome.optString("result"))
        assertEquals(EnvoyDeliver.LEDGER_TOOL, outcome.optString("tool"))
        assertEquals("租约两行要配得上同一个 opId", 2, LeaseLedger.rowsForOp(outcome.optString("op")).size)
        assertTrue("不该有委派收场行（任务字节没发）", PeerEventsLedger.replay().none { it.optString("kind") == PeerEventKinds.DELEGATE })
    }

    // ── 判据③ 负例：sink 没装配 ⇒ 明确拒绝 ──────────────────────────────────

    @Test
    fun `判据三 落点没装配就明确拒绝 不静默成功`() {
        // 不起床、不 stage：这一条要的就是「装配没跑过」那个状态。
        EnvoyTool.sink = null
        val reg = productionRegistry(agentUnlocked = true)
        val spec = reg.find(EnvoyTool.NAME) ?: error("生产注册表没给出 ${EnvoyTool.NAME} 的 spec")
        val out = spec.executor(
            JSONObject().put("endpoint", "https://peer.example/a2a").put("task", "引擎没装配时派活"),
        )
        assertTrue("没接线却成了成功（静默假完成）：$out", out is ToolOutcome.Rejected)
        val reason = (out as ToolOutcome.Rejected).reason
        assertTrue("拒绝要说明原因：$reason", reason.contains("没接线"))
    }

    // ── 判据④ 硬线：登记没把「每次必问」松动 ────────────────────────────────

    @Test
    fun `判据四 放权档也要人批 批准前零任务字节`() {
        stage()
        installProductionSink()
        // 矩阵侧：登记成 R2 之后仍是「点名必问」，也没掉进未分类那一支
        val verdict = RiskMatrix.classify(call(args("派一件活")))
        assertEquals("档位不是 R2", RiskClass.R2, verdict.risk)
        assertFalse("登记后反而未分类（fail-closed 那条硬线不该靠它）", verdict.unclassified)
        assertTrue("矩阵没点名必问", verdict.mustAsk)
        assertTrue("表里这行 alwaysAsk 没打开", RiskTable.DEFAULT.tools.getValue(EnvoyTool.NAME).alwaysAsk)

        // 行为侧：最松的放权档也出卡，出卡阶段一个任务字节都不发
        val pipeline = pipeline(productionRegistry(agentUnlocked = true), tier = AskTier.DELEGATED)
        val sendsBefore = sends()
        val asked = pipeline.execute(call(args("派一件活"))) { _, _ -> null }
        assertTrue("放权档免掉了授权卡（字节会静默发去外部 agent）：$asked", asked is GuardPipeline.Result.NeedsConfirm)
        assertEquals("没批准就发了任务字节", sendsBefore, sends())
    }

    // ── 判据⑤ 装配那两行在源面上钉住 ────────────────────────────────────────

    @Test
    fun `判据五 装配点在BaopuRuntime 落点是EnvoyDeliver`() {
        // 真装配要 Android Context（JVM 起不来），所以源面钉住「那两行没被人搬走/删掉」；
        // 判据② 已经调过同一段接线本体（BaopuRuntime.delegatePeerTask），两份合起来才是全链。
        val f = File("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("找不到 $f（单测工作目录应在 app/ 下）", f.isFile)
        val src = f.readText(Charsets.UTF_8)
        assertTrue("sink 没在装配处注入", src.contains("EnvoyTool.sink = EnvoySink"))
        assertTrue("落点不是 EnvoyDeliver", src.contains("EnvoyDeliver("))
        assertTrue("准入落点没和 208/427/517 同区", src.contains("delegatePeerTask(envoyDeliver, File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)"))
        assertTrue("644 的生效路由消费缝没接上", src.contains("RouteEvo.resolveEndpoint"))
    }
}
