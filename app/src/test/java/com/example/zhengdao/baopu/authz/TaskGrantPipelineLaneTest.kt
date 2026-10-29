// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P1：授权链接在**闸上**的行为——负例 N6（"免卡那一支其实是死代码"）与
// N7（"放行的动作落了两条账"）都在这里打；`TaskGrantWiringGateTest` 只能证明线接上了，
// `TaskGrantWireBehaviorTest` 只能证明构造层判得对，**这两条都必须打在真实执行路径上**。
//
// ## 为什么这里要一个假接线口
// P1 生产到不了 ALLOW（第⑤步恒没影子 ⇒ NO_DATA），所以"ALLOW 之后到底免不免卡、
// 落不落两条账"在全是默认真件的跑法里**永远跑不到**——跑不到就等于没接。
// 2026-10-29 用户裁定 Q1：本批不造影子适配器（判据补强归 239）；要证明分支是活的，
// 只剩注入那一条路（与 242 的 `ContractSource`、223 的 `riskTable` 同一个房型）。
// 假的只有"这一格判成什么"，事实（对象、字节、opId）与落账全部走真构造层。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.ToolRule
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.baopu.plan.ContractSource
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanContractRules
import com.example.zhengdao.baopu.plan.PlanStep
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskGrantPipelineLaneTest {

    private lateinit var ws: File
    private lateinit var ledgerRoot: File
    private val epoch get() = ProcessEpoch.current

    @Before
    fun setup() {
        ws = Files.createTempDirectory("baopu-lane276-ws").toFile()
        ledgerRoot = Files.createTempDirectory("baopu-lane276-ledger").toFile()
        DecisionLedger.install(ledgerRoot)
        TaskGrantRuntime.resetForTest()
        TaskGrantBoard.resetForTest()
    }

    @After
    fun teardown() {
        TaskGrantRuntime.resetForTest()
        TaskGrantBoard.resetForTest()
        DecisionLedger.uninstallForTest()
        listOf(ws, ledgerRoot).forEach { it.deleteRecursively() }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 只假一件事：这一格判成哪种处置。事实与落账都是真的。 */
    private class FakeWiring(
        val disposition: WiringDisposition,
        val request: ActionRequest? = null,
        val note: String = "",
    ) : GrantWiring {
        val asked = mutableListOf<String>()

        override fun decide(
            call: ToolCall,
            targets: List<File>,
            verdict: RiskVerdict?,
            root: File,
            isWrite: Boolean,
            nowMonoMs: Long,
            epoch: String,
            shadow: ShadowSignal,
            log: (String) -> Unit,
        ): WiringResult {
            asked += call.id
            // 与真构造层同一条纪律：不 ALLOW 就清场（否则"没做成的事"会一直挂着已放行）。
            TaskGrantBoard.setPending(if (disposition == WiringDisposition.ALLOW) request else null)
            return WiringResult(disposition, request = request, note = note)
        }
    }

    /** 板上没契约（今天的一切）：本批判据不掺 242 的变量。 */
    private object NoContract : ContractSource {
        override fun activeContract(): PlanContract? = null
        override fun planItems(): List<PlanRules.PlanItem> = emptyList()
        override fun workspaceRoot(): String = ""
        override fun noteAction(action: PlanContractRules.ActualAction) = Unit
    }

    private fun touch(rel: String, text: String = "初稿"): File =
        File(ws, rel).also { f ->
            f.parentFile?.mkdirs()
            f.writeText(text)
        }

    private fun writeSpec(ran: MutableList<String>, content: String): ToolSpec = ToolSpec(
        name = "write_file",
        description = "接线用的假写盘工具（真管线、假执行器）",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "path" to jsonObjectOf("type" to "string"),
                "content" to jsonObjectOf("type" to "string"),
            ),
            "required" to jsonArrayOf("path", "content"),
            "additionalProperties" to false,
        ),
        readOnly = false,
    ) { args ->
        ran += "write_file"
        // 第②段已把 `path` 改写成绝对写法（`TouchFace.facesOf` 的注释就是这条口径），
        // 所以执行器直接拿它当文件用——真工具也这么办。
        File(args.optString("path")).writeText(content)
        ToolOutcome.Ok("写了")
    }

    /** `write_file` 的矩阵档：R2＝今天必问那一档；R1＝直行那一档。 */
    private fun tableAt(risk: RiskClass): RiskTable = RiskTable.DEFAULT.copy(
        tools = RiskTable.DEFAULT.tools + mapOf(
            "write_file" to ToolRule(risk, writePathKeys = setOf("path")),
        ),
    )

    private fun pipeline(
        ran: MutableList<String>,
        wiring: GrantWiring,
        risk: RiskClass,
    ): GuardPipeline {
        val reg = ToolRegistry().apply { register(writeSpec(ran, "新稿子")) }
        return GuardPipeline(
            reg,
            { ws },
            { null },
            ToolRunner(Executors.newSingleThreadExecutor()),
            { },
            riskTable = tableAt(risk),
            contracts = NoContract,
            grantWiring = wiring,
        )
    }

    private fun call(opId: String, rel: String = "notes/a.md") =
        ToolCall(opId, "write_file", JSONObject().put("path", rel).put("content", "新稿子"))

    /** 真开一单（208 记批准 → 装快照 → 板认单），返回装着的那份。 */
    private fun openGrant(rel: String): TaskGrant {
        val now = TaskGrantWire.monoMs()
        val contract = PlanContract(
            goal = "整理稿子",
            steps = listOf(PlanStep(1, "写第一稿", listOf(rel), RiskClass.R2)),
            touches = listOf(rel),
            readonly = emptyList(),
            expectedAsks = 0,
            stopIf = emptyList(),
        )
        val offer = TaskGrantWire.buildOffer(contract, ws, 7L, epoch, now, System.currentTimeMillis()).first
            ?: throw AssertionError("这一轮没生成候选单：${contract.touches}")
        val r = TaskGrantWire.openGrant(offer, defaultGrantSelection(offer.facts), ws, epoch, now, System.currentTimeMillis())
        assertTrue("开单没成：${r.reason}", r.installed)
        return TaskGrantRuntime.current()!!.grant
    }

    /** 与真构造层同一个请求构造（假的只是结论，事实不许假）。 */
    private fun requestFor(opId: String, rel: String, grant: TaskGrant): ActionRequest =
        TaskGrantWire.requestOf(
            call = call(opId, rel),
            grant = grant,
            objectId = rel,
            target = File(ws, rel),
            verdict = RiskVerdict(risk = RiskClass.R2, decision = RiskDecision.PROCEED),
            nowMonoMs = TaskGrantWire.monoMs(),
            epoch = epoch,
        )

    private fun rows() = DecisionLedger.read(ledgerRoot, DecisionLedger.READ_LIMIT)

    private fun appliedRows() = rows().count { it.evidence.endsWith(":applied") }

    private fun genericRows() = rows().count { it.kind == DecisionKind.AUTO_ACT && it.evidence.startsWith("tool:") }

    // ── 负例 N6：免卡那一支是活的 ───────────────────────────────────────────

    @Test
    fun N6_注入放行后这张卡真的免了执行器跑一次() {
        touch("notes/a.md")
        val grant = openGrant("notes/a.md")
        val ran = mutableListOf<String>()
        val wiring = FakeWiring(WiringDisposition.ALLOW, requestFor("op-n6", "notes/a.md", grant))
        val r = pipeline(ran, wiring, RiskClass.R2).execute(call("op-n6")) { _, _ -> null }
        assertTrue("ALLOW 必须是直行（判据①的活体证据：$r）", r is GuardPipeline.Result.Done)
        assertEquals("执行器恰好跑了一次", listOf("write_file"), ran)
        assertEquals("闸上问了一次", listOf("op-n6"), wiring.asked)
    }

    @Test
    fun N6反面_生产默认判到第五步就停_一张卡都不省() {
        // 她那句「P1 不会少弹一张卡」的真机口径证据（JVM 侧的同一格）：
        // 真授权、真开单、真管线、不注入任何判定 ⇒ 六步跑过（读数在板上），卡照弹。
        touch("notes/a.md")
        openGrant("notes/a.md")
        val ran = mutableListOf<String>()
        val r = pipeline(ran, TaskGrantWire, RiskClass.R2).execute(call("op-real")) { _, _ -> null }
        assertTrue("R2 写照旧弹卡：$r", r is GuardPipeline.Result.NeedsConfirm)
        assertEquals("弹了卡就没执行", emptyList<String>(), ran)
        val line = TaskGrantBoard.readingLine()
        assertNotNull("六步必须真的被跑过（读数不在板上＝没接线）", line)
        assertTrue("停在第⑤步的 NO_DATA：$line", line!!.contains("no_data"))
        assertTrue("三元组是相符的（拦它的不是 L26）：$line", line.contains("三元组相符"))
        assertNull("停下＝不留待落账的那一格", TaskGrantBoard.pendingFor("op-real"))
    }

    @Test
    fun 硬拒那一支动作不发生() {
        touch("notes/a.md")
        val grant = openGrant("notes/a.md")
        val ran = mutableListOf<String>()
        val wiring = FakeWiring(WiringDisposition.REJECT, requestFor("op-rj", "notes/a.md", grant), note = "219 判了硬拒")
        val r = pipeline(ran, wiring, RiskClass.R2).execute(call("op-rj")) { _, _ -> null }
        assertTrue("硬拒必须直接拒（降级成问就是把红线交给用户点）：$r", r is GuardPipeline.Result.Rejected)
        assertEquals("动作没发生", emptyList<String>(), ran)
    }

    @Test
    fun 停下与不适用与未授权都逐字走今天() {
        listOf(WiringDisposition.STOPPED, WiringDisposition.UNAUTHORIZED, WiringDisposition.NOT_APPLICABLE).forEach { d ->
            touch("notes/a.md")
            val grant = openGrant("notes/a.md")
            val ran = mutableListOf<String>()
            val r = pipeline(ran, FakeWiring(d, requestFor("op-$d", "notes/a.md", grant)), RiskClass.R2)
                .execute(call("op-$d")) { _, _ -> null }
            assertTrue("$d 不该改变今天的处置：$r", r is GuardPipeline.Result.NeedsConfirm)
            assertEquals("$d 不许替动作发生", emptyList<String>(), ran)
            TaskGrantRuntime.resetForTest()
            TaskGrantBoard.resetForTest()
        }
    }

    // ── 负例 N7：放行的动作只落一条账 ───────────────────────────────────────

    @Test
    fun N7_放行的动作落一条账不补第二条() {
        touch("notes/a.md")
        val grant = openGrant("notes/a.md")
        val ran = mutableListOf<String>()
        val wiring = FakeWiring(WiringDisposition.ALLOW, requestFor("op-n7", "notes/a.md", grant))
        val p = pipeline(ran, wiring, RiskClass.R2)
        assertTrue(p.execute(call("op-n7")) { _, _ -> null } is GuardPipeline.Result.Done)
        assertEquals("授权账恰好一条", 1, appliedRows())
        assertEquals("通用账一条都不补（一动作两账就是这儿红）", 0, genericRows())

        // 同一个 opId 再走一遍（引擎重试那条路）：两笔账都不许变成第二条。
        assertTrue(p.execute(call("op-n7")) { _, _ -> null } is GuardPipeline.Result.Done)
        assertEquals("重跑不重复落账", 1, appliedRows())
        assertEquals("重跑也不补通用账", 0, genericRows())
        assertEquals("额度也只推进一次", 1, TaskGrantRuntime.current()!!.actionsUsed)
    }

    @Test
    fun 对照_不免卡时通用账照旧一条() {
        // N7 的正面对照：接管**不是**关掉通用账。R1 直行那一格没有授权参与 ⇒ `auto_act` 照旧。
        touch("notes/a.md")
        val ran = mutableListOf<String>()
        val r = pipeline(ran, FakeWiring(WiringDisposition.NOT_APPLICABLE), RiskClass.R1)
            .execute(call("op-gen")) { _, _ -> null }
        assertTrue("R1 直行：$r", r is GuardPipeline.Result.Done)
        assertEquals("通用账照旧一条", 1, genericRows())
        assertEquals("没有授权就没有授权账", 0, appliedRows())
        assertNotNull("208 那条记录的 evidence 形状没被本批改掉", rows().firstOrNull { it.evidence.startsWith("tool:") })
    }

    @Test
    fun 对照_未授权那一格也不影响通用账() {
        // 六步说"改走单次人批"之后动作仍被批准执行（executeApproved 那条路本批没碰）——
        // 这里钉的是：授权链**不接管**不属于它的账。
        touch("notes/a.md")
        val ran = mutableListOf<String>()
        val r = pipeline(ran, FakeWiring(WiringDisposition.UNAUTHORIZED), RiskClass.R1)
            .execute(call("op-na")) { _, _ -> null }
        assertTrue("R1 直行不受影响：$r", r is GuardPipeline.Result.Done)
        assertEquals(1, genericRows())
        assertEquals(0, appliedRows())
    }

    // ── 落账失败不许把动作吐出来（208 是留痕不是闸门） ───────────────────────

    @Test
    fun 落不上账也照常完成() {
        touch("notes/a.md")
        val grant = openGrant("notes/a.md")
        DecisionLedger.uninstallForTest() // 落点没了：账写不出去，但写入是真发生了的
        val ran = mutableListOf<String>()
        val wiring = FakeWiring(WiringDisposition.ALLOW, requestFor("op-miss", "notes/a.md", grant))
        val r = pipeline(ran, wiring, RiskClass.R2).execute(call("op-miss")) { _, _ -> null }
        assertTrue("落不上账不改变结果：$r", r is GuardPipeline.Result.Done)
        assertEquals(listOf("write_file"), ran)
        assertEquals("没落上就是零条", 0, appliedRows())
        val state = TaskGrantRuntime.current()!!
        assertEquals("但额度照样推进（不然上限判成假宽）", 1, state.actionsUsed)
    }

    @Test
    fun 放行之后板上的那一格归授权() {
        touch("notes/a.md")
        val grant = openGrant("notes/a.md")
        val request = requestFor("op-own", "notes/a.md", grant)
        val ran = mutableListOf<String>()
        pipeline(ran, FakeWiring(WiringDisposition.ALLOW, request), RiskClass.R2).execute(call("op-own")) { _, _ -> null }
        assertTrue("执行成功后这一格钉成「账已由授权链落」", TaskGrantBoard.isAccounted("op-own"))
        assertEquals(request, TaskGrantBoard.pendingFor("op-own"))
    }
}
