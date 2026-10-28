// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-27-第二百四十二批-计划即契约 §4 判据 2/3 ＋ §5 负例②③⑦ ＋ 红线①。
//
// 与 `PlanContractTest` 的分工：那边打的是**判据层与账**（纯函数，JVM 直测）；
// 本文件打的是**接线**——判据写得再对，没接到闸上就等于没写：
// - A 组：闸上的行为（GuardPipeline ＋ 假 ContractSource）——契约外动作必须在
//   **派发之前**停（判据 3），执行器一次不跑；契约内 R1 照直行（判据 2）；
//   R2/R3 照旧弹风险卡（红线①：批准计划不构成任何授权）。
// - B 组：PlanController 的契约链路——批准才装上限、没批只落库、回灌的每一句
//   都要跟板上的事实一致（不许"沿用上一版"盖在空板上）。
// - C 组：源码门禁（照 DecisionWiringGateTest 的房）——哪天有人把接线摘掉、
//   或者把扩计划重跑换成 executeApproved，这里当场红。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionAudit
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.ToolRule
import com.example.zhengdao.baopu.plan.ContractBreach
import com.example.zhengdao.baopu.plan.ContractDeviationRules
import com.example.zhengdao.baopu.plan.ContractSource
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanContractBoard
import com.example.zhengdao.baopu.plan.PlanContractRules
import com.example.zhengdao.baopu.plan.PlanStep
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 门禁按仓库相对路径找接线点（测试工作目录＝app 模块；`app/` 前缀兜底另一台跑法）。 */
private const val ENGINE = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
private const val GUARD = "src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt"
private const val CTRL = "src/main/java/com/example/zhengdao/baopu/agent/PlanController.kt"
private const val CARDS = "src/main/java/com/example/zhengdao/baopu/plan/PlanContractCards.kt"

class PlanContractWiringTest {

    // ── A 组 · 闸上的判定（GuardPipeline ＋ 假契约板） ────────────────────────

    /**
     * 假契约板：把三个读数（契约／计划／根）摆死，动作流水留在 [noted] 里给断言看。
     *
     * 为什么不打全局 [PlanContractBoard]：闸是执行层，注入接口本来就是为了让
     * 判据在参数表上可测——读全局单例反而把测试串了味。
     */
    private class FakeBoard(
        private val contract: PlanContract?,
        private val items: List<PlanRules.PlanItem>,
        private val root: String,
    ) : ContractSource {
        val noted = mutableListOf<PlanContractRules.ActualAction>()

        override fun activeContract(): PlanContract? = contract
        override fun planItems(): List<PlanRules.PlanItem> = items
        override fun workspaceRoot(): String = root
        override fun noteAction(action: PlanContractRules.ActualAction) {
            noted += action
        }
    }

    private fun gateItems() = listOf(PlanRules.PlanItem("整理稿子", PlanRules.STATUS_DOING))

    private fun gateContract(
        touches: List<String>,
        ceiling: RiskClass,
        readonly: List<String> = emptyList(),
    ): PlanContract = PlanContract(
        goal = "整理稿子",
        steps = listOf(PlanStep(1, "整理稿子", emptyList(), ceiling)),
        touches = touches,
        readonly = readonly,
        expectedAsks = 0,
        stopIf = emptyList(),
    )

    private fun writeSpec(name: String, ran: MutableList<String>, readOnly: Boolean): ToolSpec =
        ToolSpec(
            name = name,
            description = "契约闸测试的假工具（档位由注入矩阵给）",
            inputSchema = jsonObjectOf(
                "type" to "object",
                "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                "required" to jsonArrayOf("path"),
            ),
            readOnly = readOnly,
        ) { ran += name; ToolOutcome.Ok("写了") }

    private fun gatePipeline(
        root: File,
        board: ContractSource,
        ran: MutableList<String>,
    ): GuardPipeline {
        val reg = ToolRegistry().apply {
            register(writeSpec("contract_write_r1", ran, readOnly = false))
            register(writeSpec("contract_write_r2", ran, readOnly = false))
            register(writeSpec("contract_read_r0", ran, readOnly = true))
        }
        val table = RiskTable.DEFAULT.copy(
            tools = RiskTable.DEFAULT.tools + mapOf(
                "contract_write_r1" to ToolRule(RiskClass.R1),
                "contract_write_r2" to ToolRule(RiskClass.R2),
                "contract_read_r0" to ToolRule(RiskClass.R0),
            ),
        )
        return GuardPipeline(
            reg,
            { root },
            { null },
            ToolRunner(Executors.newSingleThreadExecutor()),
            { },
            riskTable = table,
            contracts = board,
        )
    }

    private fun gateCall(name: String, relPath: String) =
        ToolCall("g1", name, JSONObject().put("path", relPath))

    private fun tmpRoot(): File = Files.createTempDirectory("baopu-gate-242").toFile()

    @Test
    fun `判据3_契约外写在派发之前就停_执行器一次没跑`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R1), gateItems(), root.absolutePath)
        val r = gatePipeline(root, board, ran).execute(gateCall("contract_write_r1", "桌面/计划外.md")) { _, _ -> null }
        val nc = r as GuardPipeline.Result.NeedsConfirm
        assertNotNull("超白名单必须给偏离卡：$nc", nc.deviation)
        assertEquals(ContractBreach.OUT_OF_TOUCHES, nc.deviation!!.check.breach)
        assertNull("偏离卡先出：范围没谈拢不谈危险性", nc.reasonLine)
        assertTrue("卡上是三选一（扩计划在列）", nc.deviation!!.request.options.any { it.id == ContractDeviationRules.OPT_EXTEND })
        assertEquals("动作没发生（判据 3 的本体）", emptyList<String>(), ran)
        assertEquals("被拦下的不进对账的账", 0, board.noted.size)
    }

    @Test
    fun `判据2_契约内R1写_直行不弹卡`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R1), gateItems(), root.absolutePath)
        val r = gatePipeline(root, board, ran).execute(gateCall("contract_write_r1", "资料库/产物/a.md")) { _, _ -> null }
        assertTrue("契约内且不超预估 ⇒ 照跑：$r", r is GuardPipeline.Result.Done)
        assertEquals(listOf("contract_write_r1"), ran)
        assertEquals("真跑成的动作进对账的账", 1, board.noted.size)
        assertEquals(ContractBreach.NONE, board.noted[0].breach)
        assertTrue(board.noted[0].isWrite)
    }

    @Test
    fun `红线1_契约内R2照问_弹的是风险卡不是偏离卡`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        // 她批准的计划里这一步预估就是 R2 ⇒ 契约这一层干净；该问的是矩阵那一层
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R2), gateItems(), root.absolutePath)
        val nc = gatePipeline(root, board, ran)
            .execute(gateCall("contract_write_r2", "资料库/产物/a.md")) { _, _ -> null }
            as GuardPipeline.Result.NeedsConfirm
        assertNull("批准计划不构成授权：这一张必须是风险卡（deviation 为空）", nc.deviation)
        assertNotNull("该问的理由要带上卡面", nc.reasonLine)
        assertTrue("执行器没跑（等人点卡）", ran.isEmpty())
    }

    @Test
    fun `红线1_批准后的执行路径也过契约这道闸`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R1), gateItems(), root.absolutePath)
        val p = gatePipeline(root, board, ran)
        val r = p.executeApproved(gateCall("contract_write_r1", "桌面/计划外.md")) { _, _ -> null }
        val nc = r as GuardPipeline.Result.NeedsConfirm
        assertNotNull("确认卡批准的是那一次动作，不是契约外的通行证", nc.deviation)
        assertEquals(ContractBreach.OUT_OF_TOUCHES, nc.deviation!!.check.breach)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `C②_超过这一步预估的写在闸上就停`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R0), gateItems(), root.absolutePath)
        val nc = gatePipeline(root, board, ran)
            .execute(gateCall("contract_write_r1", "资料库/产物/a.md")) { _, _ -> null }
            as GuardPipeline.Result.NeedsConfirm
        val hit = nc.deviation!!
        assertEquals(ContractBreach.RISK_OVER_ESTIMATE, hit.check.breach)
        assertEquals("卡面说清预估与实际", RiskClass.R0, hit.check.ceiling)
        assertEquals(RiskClass.R1, hit.check.actual)
        assertTrue("预估偏保守可以靠扩计划解决", hit.request.options.any { it.id == ContractDeviationRules.OPT_EXTEND })
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `C②_写进只读区_卡上没有扩计划按钮`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(
            gateContract(listOf("资料库"), RiskClass.R1, readonly = listOf("资料库/原始")),
            gateItems(),
            root.absolutePath,
        )
        val nc = gatePipeline(root, board, ran)
            .execute(gateCall("contract_write_r1", "资料库/原始/a.md")) { _, _ -> null }
            as GuardPipeline.Result.NeedsConfirm
        val hit = nc.deviation!!
        assertEquals(ContractBreach.WRITE_IN_READONLY, hit.check.breach)
        assertEquals(
            "扩白名单洗不掉只读红线：卡上不许摆一个做不到的按钮",
            listOf(ContractDeviationRules.OPT_CANCEL, ContractDeviationRules.OPT_CHANGE),
            hit.request.options.map { it.id },
        )
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `判据3_读计划外目录同样停_读只吃白名单`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R1), gateItems(), root.absolutePath)
        val p = gatePipeline(root, board, ran)
        val out = p.execute(gateCall("contract_read_r0", "桌面/x.txt")) { _, _ -> null }
            as GuardPipeline.Result.NeedsConfirm
        assertEquals("读不改盘，但计划外就是计划外", ContractBreach.OUT_OF_TOUCHES, out.deviation!!.check.breach)
        val inside = p.execute(gateCall("contract_read_r0", "资料库/产物/a.md")) { _, _ -> null }
        assertTrue("计划内的读直行（不受风险上限约束）", inside is GuardPipeline.Result.Done)
        assertEquals("两次读里只有计划外那次被拦", listOf("contract_read_r0"), ran)
    }

    @Test
    fun `没契约_闸的行为与今天逐字节相同`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(null, gateItems(), root.absolutePath)
        val p = gatePipeline(root, board, ran)
        val r1 = p.execute(gateCall("contract_write_r1", "桌面/计划外.md")) { _, _ -> null }
        assertTrue("没有上限 ⇒ 白名单不存在，照今天的规则走", r1 is GuardPipeline.Result.Done)
        val r2 = p.executeApproved(gateCall("contract_write_r2", "资料库/产物/a.md")) { _, _ -> null }
        assertTrue(r2 is GuardPipeline.Result.Done)
        assertEquals("契约缺席 ⇒ 这一层完全惰性（连账都不记）", 0, board.noted.size)
    }

    @Test
    fun `批准路径的动作进账时没有本地档位_如实记null`() {
        val root = tmpRoot()
        val ran = mutableListOf<String>()
        val board = FakeBoard(gateContract(listOf("资料库/产物"), RiskClass.R2), gateItems(), root.absolutePath)
        gatePipeline(root, board, ran)
            .executeApproved(gateCall("contract_write_r2", "资料库/产物/a.md")) { _, _ -> null }
        assertTrue(ran.isNotEmpty())
        assertEquals(1, board.noted.size)
        assertNull("批准路径跳过第 ③ 段 ⇒ 没有读数就不编档位（对账按不参与处理）", board.noted[0].risk)
        assertTrue(board.noted[0].isWrite)
    }

    // ── B 组 · PlanController 的契约链路（批准才封顶；回灌句句实话） ──────────

    private class CStore : PlanStore {
        val inserts = mutableListOf<Pair<Long, String>>()
        override fun insert(sessionId: Long, payload: String) {
            inserts += sessionId to payload
        }

        override fun latest(sessionId: Long): String? = null
    }

    private fun controller(store: CStore): PlanController =
        PlanController(sessionId = { 7L }, store = store, log = { })

    private fun contractJson(touches: List<String> = listOf("资料库/产物"), asks: Int = 1): JSONObject =
        JSONObject()
            .put("goal", "把稿子整理成产物")
            .put("steps", JSONArray().put(JSONObject().put("risk", "R1")))
            .put("touches", JSONArray(touches))
            .put("readonly", JSONArray())
            .put("expected_asks", asks)
            .put("stop_if", JSONArray())

    /** 单条计划 ＋ 契约；[contract] = null 表示这次不带契约键（进度更新的形状）。 */
    private fun planWith(text: String, status: String, contract: JSONObject?): JSONObject {
        val args = JSONObject().put(
            "items",
            JSONArray().put(JSONObject().put("text", text).put("status", status)),
        )
        if (contract != null) args.put("contract", contract)
        return args
    }

    @Test
    fun `负例1_半张契约整次退回_不落库不装上限`() {
        val store = CStore()
        val c = controller(store)
        val broken = contractJson().apply { remove("goal") }
        val out = c.submit(planWith("读原稿", PlanRules.STATUS_TODO, broken))
        assertTrue("缺字段必须回错误原文：$out", out is ToolOutcome.Failed)
        assertTrue("不许落库（写一半的契约静默当成没契约＝悄悄放弃封顶）", store.inserts.isEmpty())
        assertFalse(PlanContractBoard.hasContract())
    }

    @Test
    fun `批准链路_她批了_上限才装上`() {
        val store = CStore()
        val c = controller(store)
        val args = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        val declared = c.previewContract(args)
        assertNotNull("新上限声明要先过批准卡", declared)
        c.answerContractCard(declared, true)
        val out = c.submit(args) as ToolOutcome.Ok
        assertTrue("回灌要如实说生效了", out.text.contains("契约已生效"))
        assertTrue("上限装进板（闸上从这一刻开始比契约）", PlanContractBoard.hasContract())
        assertEquals(declared, c.currentContract())
        val payload = store.inserts.single().second
        assertEquals("落库的契约就是生效的那一份", declared, PlanRules.contractFromJson(payload))
    }

    @Test
    fun `没批只落库_上限不装_回灌照实说`() {
        val store = CStore()
        val c = controller(store)
        val args = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        val out = c.submit(args) as ToolOutcome.Ok
        assertTrue(out.text.contains("没有生效"))
        assertFalse(PlanContractBoard.hasContract())
        assertNotNull("卡面上照旧看得见（不封顶≠看不见）", c.currentContract())
        assertTrue(store.inserts.single().second.contains("contract"))
    }

    @Test
    fun `批准卡没人答之后她补批_同版重发也装上限`() {
        val c = controller(CStore())
        val args = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        c.submit(args) // 第一次没人答：落库、不装上限
        assertFalse(PlanContractBoard.hasContract())
        val declared = c.previewContract(args)
        assertNotNull("板上没上限 ⇒ 同版重发要重新问（fail-closed）", declared)
        c.answerContractCard(declared, true)
        val out = c.submit(args) as ToolOutcome.Ok
        assertTrue("补批准也要装上（认「板上没有」这一路，不只认新版本）", out.text.contains("契约已生效"))
        assertTrue(PlanContractBoard.hasContract())
    }

    @Test
    fun `拒过的同版重发_不再弹卡_回灌照实说没生效`() {
        val c = controller(CStore())
        val args = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        val declared = c.previewContract(args)!!
        c.answerContractCard(declared, false)
        assertNull("同一版她明确拒过 ⇒ 不再打扰", c.previewContract(args))
        val out = c.submit(args) as ToolOutcome.Ok
        assertTrue("没生效就是没生效（不许说「沿用上一版」盖在空板上）", out.text.contains("没有生效"))
        assertFalse(PlanContractBoard.hasContract())
    }

    @Test
    fun `换版没批_旧上限退场_新版等批准`() {
        val c = controller(CStore())
        val v1 = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        c.answerContractCard(c.previewContract(v1)!!, true)
        c.submit(v1)
        assertTrue(PlanContractBoard.hasContract())
        val v2 = planWith("读原稿", PlanRules.STATUS_TODO, contractJson(touches = listOf("资料库/新版")))
        assertNotNull("改一版就重新问", c.previewContract(v2))
        val out = c.submit(v2) as ToolOutcome.Ok
        assertTrue(out.text.contains("没有生效"))
        assertFalse("旧上限不许压在新计划上（计划条目已经换掉了）", PlanContractBoard.hasContract())
    }

    @Test
    fun `进度更新_上限沿用_不重复弹卡`() {
        val c = controller(CStore())
        val v1 = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        c.answerContractCard(c.previewContract(v1)!!, true)
        c.submit(v1)
        val update = planWith("读原稿", PlanRules.STATUS_DONE, contract = null)
        assertNull("进度更新不问批准卡", c.previewContract(update))
        val out = c.submit(update) as ToolOutcome.Ok
        assertTrue("上限跟着计划走，打勾不打掉上限", out.text.contains("沿用上一版"))
        assertTrue(PlanContractBoard.hasContract())
    }

    @Test
    fun `换轮_上限跟着计划清空`() {
        val c = controller(CStore())
        val v1 = planWith("读原稿", PlanRules.STATUS_TODO, contractJson())
        c.answerContractCard(c.previewContract(v1)!!, true)
        c.submit(v1)
        assertTrue(PlanContractBoard.hasContract())
        c.newRound()
        assertFalse("新请求＝新上限，上一轮的封顶不许带过来", PlanContractBoard.hasContract())
        assertNull(c.currentContract())
    }

    @Test
    fun `上次的账挂进计划提醒_下一次写计划看得见`() {
        val c = controller(CStore())
        val v1 = planWith("读原稿", PlanRules.STATUS_TODO, contractJson(asks = 0))
        c.answerContractCard(c.previewContract(v1)!!, true)
        c.submit(v1)
        repeat(3) { PlanContractBoard.noteAsk() }
        assertNotNull("预估 0 实问 3 ⇒ 对账出不符（负例⑥）", PlanContractBoard.reconcile())
        val update = planWith("读原稿", PlanRules.STATUS_DONE, contract = null)
        c.submit(update)
        assertTrue(
            "下一版计划必须看见上次的错估算（派工单 D 段后半句）",
            c.reminderLine()!!.contains("【上次的账】"),
        )
    }

    // ── C 组 · 源码门禁（接线被摘掉就红；照 DecisionWiringGateTest 的房） ─────

    private fun moduleText(rel: String): String {
        val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
        assertTrue("文件不见了：${f.absolutePath}（门禁按它找接线点）", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** 剥掉纯注释行后的全文（门禁只认代码；注释里提到某个名字不许算接线）。 */
    private fun codeOf(rel: String): String = moduleText(rel).lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .joinToString("\n")

    /** 从 [start] 到 [end] 的函数体切片（剥注释）：窄范围钉"这一段"的形状。 */
    private fun codeSlice(rel: String, start: String, end: String): String {
        val t = moduleText(rel)
        val a = t.indexOf(start)
        assertTrue("切片起点不在 $rel：$start", a >= 0)
        val b = t.indexOf(end, a + start.length)
        assertTrue("切片终点不在 $rel：$end", b > a)
        return codeOfText(t.substring(a, b))
    }

    private fun codeOfText(t: String): String = t.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `C1_批准卡与偏离闸都长在真执行之前`() {
        val t = moduleText(ENGINE)
        val ask = t.indexOf("askContractApproval(call)")
        val exec = t.indexOf("val result = executeWithSwitch(switchForShot)")
        val gate = t.indexOf("val settled = contractDeviationGate(call, result)")
        assertTrue("批准卡要在 pipeline.execute 之前（判据 1/2 的本体）", ask in 0 until exec)
        assertTrue("偏离闸要在执行之后接住（没偏离原样返回）", gate > exec)
    }

    @Test
    fun `C2_扩计划重跑的是完整管线_不是批准路径`() {
        val gate = codeSlice(ENGINE, "private fun contractDeviationGate(", "private fun stillBreach(")
        assertTrue(gate.contains("rerun()"))
        assertFalse("走 executeApproved ＝ 把「同意扩范围」当成「批准这个动作」（红线①）", gate.contains("executeApproved"))
        val callSite = codeSlice(
            ENGINE,
            "val settled = contractDeviationGate(call, result) {",
            "if (settled !is GuardPipeline.Result.NeedsConfirm)",
        )
        assertTrue(callSite.contains("pipeline.execute(call)"))
        assertFalse(callSite.contains("executeApproved"))
    }

    @Test
    fun `C3_收工先结契约的账_再落库`() {
        val settle = codeSlice(ENGINE, "private fun settle(", "runCatching { persistStep(")
        assertTrue("中止／熔断／停止这几条收尾路也要结账（D 段）", settle.contains("PlanContractBoard.reconcile()"))
    }

    @Test
    fun `C4_出卡要进问人的账`() {
        val code = codeOf(ENGINE)
        val asks = code.split("PlanContractBoard.noteAsk()").size - 1
        assertTrue("确认卡／求助卡／偏离卡都要记账（对账第三项的「实际」那一侧）：$asks", asks >= 3)
    }

    @Test
    fun `C5_矩阵只判一次_契约块长在档位读数之后`() {
        val gp = codeOf(GUARD)
        assertEquals(
            "RiskGate.judge 只许调一次（带调用计频，多调一次＝自己给自己加账）",
            1,
            gp.split("RiskGate.judge(").size - 1,
        )
        val lr = gp.indexOf("localRisk = verdict?.risk")
        val cb = gp.indexOf("contracts.activeContract()")
        val ra = gp.indexOf("if (mustAsk) return Prepared.Settled")
        assertTrue("先拿档位读数", lr in 0 until cb)
        assertTrue("契约块要压在风险卡之前（偏离卡优先）", cb < ra)
        assertTrue(
            "弹卡的决定必须挂在布尔上，不能挂在提示串上" +
                "（R1/CONFIRM 与 MCP 的提示串是 null——挂串上这两组就从问滑成直行，全量门禁四红实证）",
            gp.contains("var mustAsk = false") && gp.contains("mustAsk = true"),
        )
        assertTrue("生产缺省就是全局板（存量调用点零改）", gp.contains("contracts: ContractSource = PlanContractBoard"))
    }

    @Test
    fun `C6_上限只认批准这一票_换版未批旧上限退场`() {
        val pc = codeOf(CTRL)
        assertTrue(
            "生效认两条路：新版本＋批准，或板上没有上限＋批准（补批准那一路）",
            pc.contains("inForce && (freshContract || !PlanContractBoard.hasContract())"),
        )
        assertEquals("activate 只许这一处", 1, pc.split("PlanContractBoard.activate(").size - 1)
        assertEquals(
            "上限只跟轮/版走：newRound、续跑回种、换版未批",
            3,
            pc.split("PlanContractBoard.clear()").size - 1,
        )
        assertTrue("续跑回种：契约回卡面，上限不复活", pc.contains("contract = PlanRules.contractFromJson(payload)"))
    }

    @Test
    fun `C7_计划卡把契约画出来_判据1的卡面侧`() {
        val card = codeOf("src/main/java/com/example/zhengdao/ui/baopu/BaopuPlanCard.kt")
        assertTrue(card.contains("BaopuUiContract"))
        assertTrue("每一步都要带上自己的预估档", card.contains("planStepRiskTag("))
        listOf("goal", "touches", "readonly", "asks", "stop_if", "note").forEach {
            assertTrue("契约行缺 $it 的字符串", card.contains("R.string.baopu_plan_contract_$it"))
        }
    }

    @Test
    fun `C8_契约行的中英字串一一对应`() {
        val keyRe = Regex("name=\"([^\"]+)\"")
        val zh = keyRe.findAll(moduleText("src/main/res/values/strings_baopu_plan_contract.xml"))
            .map { it.groupValues[1] }.toList()
        val en = keyRe.findAll(moduleText("src/main/res/values-en/strings_baopu_plan_contract.xml"))
            .map { it.groupValues[1] }.toList()
        assertEquals("键集合必须逐字对齐", zh, en)
        assertEquals(6, zh.size)
    }

    @Test
    fun `C9_负例7_对账不符真的写208_且闭集不扩`() {
        val cards = codeOf(CARDS)
        assertTrue(cards.contains("DecisionKind.PLAN_CONTRACT_FINDING"))
        assertTrue("记账要真落 DecisionLedger", cards.contains("DecisionLedger.record("))
        assertEquals("plan_contract_finding", DecisionKind.PLAN_CONTRACT_FINDING.wire)
        assertEquals(DecisionGroup.EVAL, DecisionKind.PLAN_CONTRACT_FINDING.group)
        assertFalse("跑完的动作没有回滚引用可取", DecisionKind.PLAN_CONTRACT_FINDING.reversible)
        assertEquals(DecisionAudit.WEEKLY, DecisionKind.PLAN_CONTRACT_FINDING.audit)
        assertTrue(
            "REQUIRED 是派工单点名的十类闭集，不许顺手扩",
            DecisionKind.PLAN_CONTRACT_FINDING !in DecisionKind.REQUIRED,
        )
    }

    @After
    fun tearDown() {
        PlanContractBoard.resetForTest()
    }
}
