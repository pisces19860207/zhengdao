// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百五十批 · taskId 回填的**接线面**（"起单 → installRun → pending"链的第一格）。
//
// 打三件事：
// 1. 真起单（契约入效）⇒ 出处信箱的 taskId＝已批准契约的 contractDigest（276-Q5 判型：
//    taskId 就是契约摘要本身，不是另一套 id ⇒ 直接传，不造第二套解析）；
// 2. 闲聊／续跑／短任务 ⇒ 不喂、不报错：新轮 installRun 只带 runId ⇒ taskId 归零回
//    NULL；无契约声明、契约没批准、信箱没轮 id，都是"不喂"的正路（宁空勿猜）；
// 3. 出处可追：起单落一行「taskId 来源」账（抱朴出处）；处置账行带起单的 taskId
//    （430 的 pending 行联测随其并册补——列还没进 main，本单钉住信箱这一格）。
// 判据④"解析只用既有实现"的 grep 证据在本文件活体化（源级闸）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.authz.TaskGrantWire
import com.example.zhengdao.baopu.inspect.DeviationLedger
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanContractBoard
import com.example.zhengdao.baopu.plan.PlanPledgeBoard
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskIdBackfillTest {

    private class CStore : PlanStore {
        val inserts = mutableListOf<Pair<Long, String>>()
        override fun insert(sessionId: Long, payload: String) {
            inserts += sessionId to payload
        }

        override fun latest(sessionId: Long): String? = null
    }

    private lateinit var root: File
    private lateinit var ledgerRoot: File

    @Before
    fun setUp() {
        DeviationWiring.resetForTest()
        PlanPledgeBoard.resetForTest()
        PlanContractBoard.clear()
        root = Files.createTempDirectory("taskid-450").toFile()
        PlanPledgeBoard.attach(listOf(root))
        ledgerRoot = Files.createTempDirectory("taskid-450-ledger").toFile()
        DeviationLedger.install(ledgerRoot)
    }

    @After
    fun tearDown() {
        // 进程级全局不清就是随执行顺序变红（DeviationDetectJourneyTest 同款让法：
        // 账本无卸载口 ⇒ 让给一次性空目录，不指向别人数据区）。
        DeviationWiring.resetForTest()
        PlanPledgeBoard.resetForTest()
        PlanContractBoard.clear()
        DeviationLedger.install(Files.createTempDirectory("taskid-450-detach").toFile())
        listOf(root, ledgerRoot).forEach { runCatching { it.deleteRecursively() } }
    }

    // ── 造数（房型照 PlanControllerAmendTest） ──────────────────────────────

    private fun planArgs(vararg texts: String): JSONObject = JSONObject().put(
        "items",
        JSONArray().also { arr ->
            texts.forEach { arr.put(JSONObject().put("text", it).put("status", PlanRules.STATUS_TODO)) }
        },
    )

    private fun pledgeOf(vararg texts: String): JSONObject =
        JSONObject().put("acceptance", JSONArray(texts.map { "交付「$it」并核对" }))

    private fun contractOf(goal: String, steps: Int): JSONObject = JSONObject()
        .put("goal", goal)
        .put(
            "steps",
            JSONArray().also { arr ->
                repeat(steps) { arr.put(JSONObject().put("risk", "R1").put("may_touch", JSONArray(listOf("产物")))) }
            },
        )
        .put("touches", JSONArray(listOf("产物")))
        .put("expected_asks", 1)

    private class Rig(val controller: PlanController, val logs: MutableList<String>)

    private fun rig(): Rig {
        val logs = mutableListOf<String>()
        val controller = PlanController(
            sessionId = { 7L },
            store = CStore(),
            log = { logs += it },
            workspaceRoot = { root.absolutePath },
        )
        return Rig(controller, logs)
    }

    private val five = arrayOf("甲", "乙", "丙", "丁", "戊")

    /** 立状（开工卡批准）＋ 批准契约 ⇒ 计划落库、契约入效；返回入效的那份契约。 */
    private fun standingWithContract(c: PlanController, goal: String = "把这周的笔记整理成报告"): PlanContract {
        val args = planArgs(*five).put("pledge", pledgeOf(*five))
        val candidate = c.previewPledge(args)
        assertNotNull("长程任务必须给开工卡候选", candidate)
        c.answerPledgeCard(candidate!!, approved = true)
        assertTrue("立状提交要成功", c.submit(args) is ToolOutcome.Ok)

        val withContract = planArgs(*five)
            .put("pledge", pledgeOf(*five))
            .put("contract", contractOf(goal, five.size))
        val declared = c.previewContract(withContract)
        assertNotNull("契约卡候选没出来", declared)
        c.answerContractCard(declared, accepted = true)
        assertTrue("带契约的提交要成功", c.submit(withContract) is ToolOutcome.Ok)
        assertTrue("上限没装进板子（前提不成立）", PlanContractBoard.hasContract())
        return declared!!
    }

    // ── 判据①⑥：真起单 ⇒ 回填 ＋ 来源账行 ─────────────────────────────────

    @Test
    fun `判据1_真起单_信箱taskId等于已批准契约的摘要_runId不动`() {
        DeviationWiring.installRun("run-450-1")
        val rig = rig()
        val declared = standingWithContract(rig.controller)

        assertEquals(TaskGrantWire.contractDigest(declared), DeviationWiring.currentTaskId())
        assertEquals("run-450-1", DeviationWiring.currentRunId())
        assertTrue("taskId 非空（430 侧 pending 行非 NULL 的前提）", DeviationWiring.currentTaskId().isNotBlank())
    }

    @Test
    fun `判据6_起单落一行来源账_写明taskId出处与轮id`() {
        DeviationWiring.installRun("run-450-2")
        val rig = rig()
        val declared = standingWithContract(rig.controller)

        val line = rig.logs.firstOrNull { it.startsWith("抱朴出处：") }
        assertNotNull("来源账行没落（起单时落一行「taskId 来源」）", line)
        assertTrue("账行要带 taskId 真值", line!!.contains(TaskGrantWire.contractDigest(declared)))
        assertTrue("账行要写明来源（contractDigest）", line.contains("contractDigest"))
        assertTrue("账行要带轮 id", line.contains("run-450-2"))
    }

    // ── 判据②：闲聊／续跑／短任务 ⇒ 仍 NULL 且不报错 ────────────────────────

    @Test
    fun `判据2a_新轮只带runId_taskId归零回NULL`() {
        DeviationWiring.installRun("run-450-3", "上一轮的任务")
        // 引擎每轮开跑的同一句（RunEngine 的 installRun(roundRunId)，本单零改动）：
        // 第二参缺省 ⇒ taskId 归零——闲聊/续跑自然回 NULL，不用另设清零口。
        DeviationWiring.installRun("run-450-4")
        assertEquals("", DeviationWiring.currentTaskId())
        assertEquals("run-450-4", DeviationWiring.currentRunId())
    }

    @Test
    fun `判据2b_短任务无契约_照常落库_不喂不报错`() {
        DeviationWiring.installRun("run-450-5")
        val rig = rig()
        val out = rig.controller.submit(planArgs("一", "二", "三"))
        assertTrue("短任务要照常落库：$out", out is ToolOutcome.Ok)
        assertEquals("", DeviationWiring.currentTaskId())
        assertEquals("run-450-5", DeviationWiring.currentRunId())
        assertTrue(rig.logs.none { it.startsWith("抱朴出处：") })
    }

    @Test
    fun `判据2c_契约没批准_落库可以_不喂`() {
        DeviationWiring.installRun("run-450-6")
        val rig = rig()
        val args = planArgs(*five).put("pledge", pledgeOf(*five))
        val candidate = rig.controller.previewPledge(args)
        assertNotNull(candidate)
        rig.controller.answerPledgeCard(candidate!!, approved = true)
        assertTrue(rig.controller.submit(args) is ToolOutcome.Ok)

        val withContract = planArgs(*five)
            .put("pledge", pledgeOf(*five))
            .put("contract", contractOf("新要求", five.size))
        val declared = rig.controller.previewContract(withContract)
        assertNotNull(declared)
        rig.controller.answerContractCard(declared, accepted = false)
        val out2 = rig.controller.submit(withContract)
        assertTrue("没批准也要落库（只是不入效）：$out2", out2 is ToolOutcome.Ok)
        assertFalse(PlanContractBoard.hasContract())
        assertEquals("", DeviationWiring.currentTaskId())
        assertTrue(rig.logs.none { it.startsWith("抱朴出处：") })
    }

    @Test
    fun `判据2d_信箱没轮id_契约照常入效_不编taskId`() {
        val rig = rig() // 不 installRun：信箱空——出处无从挂起，宁空勿猜
        val declared = standingWithContract(rig.controller)
        assertNotNull(declared)
        assertEquals("", DeviationWiring.currentRunId())
        assertEquals("", DeviationWiring.currentTaskId())
        assertTrue(rig.logs.none { it.startsWith("抱朴出处：") })
    }

    // ── 判据③：处置账行带起单的 taskId ─────────────────────────────────────

    @Test
    fun `判据3_真起单后_处置账行带得起单的taskId`() {
        DeviationWiring.installRun("run-450-9")
        val rig = rig()
        val declared = standingWithContract(rig.controller)

        // 430 的 pending 列还没进 main ⇒ pending 行联测随它并册补；这里钉住账行
        // 这一跳：记账口的取数（currentTaskId）必须拿到起单喂进去的真值。
        DeviationWiring.recordOverSteps(4, 3, 3)
        val e = DeviationLedger.read(ledgerRoot).single()
        assertEquals("run-450-9", e.runId)
        assertEquals(TaskGrantWire.contractDigest(declared), e.taskId)
    }

    // ── 改版重批 ⇒ taskId 跟着换 ────────────────────────────────────────────

    @Test
    fun `改版重新批准_taskId跟着换_不沿用旧摘要`() {
        DeviationWiring.installRun("run-450-10")
        val rig = rig()
        val first = standingWithContract(rig.controller, goal = "把这周的笔记整理成报告")
        val d1 = DeviationWiring.currentTaskId()
        assertEquals(TaskGrantWire.contractDigest(first), d1)

        val withContract2 = planArgs(*five)
            .put("pledge", pledgeOf(*five))
            .put("contract", contractOf("改成只整理日程部分", five.size))
        val declared2 = rig.controller.previewContract(withContract2)
        assertNotNull("改版契约要重新弹卡", declared2)
        rig.controller.answerContractCard(declared2, accepted = true)
        assertTrue("改版提交要成功", rig.controller.submit(withContract2) is ToolOutcome.Ok)

        val d2 = DeviationWiring.currentTaskId()
        assertEquals(TaskGrantWire.contractDigest(declared2!!), d2)
        assertTrue("改了要求 taskId 必须换：$d1 vs $d2", d1 != d2)
    }

    // ── 判据④：解析只用既有实现（源级闸，活体化的 grep） ────────────────────

    @Test
    fun `判据4_源级闸_contractDigest的生产命中只许两文件`() {
        val main = File("src/main/java/com/example/zhengdao/baopu").takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/example/zhengdao/baopu")
        val hits = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().replace("\r\n", "\n").contains("contractDigest(") }
            .map { it.name }
            .sorted()
            .toList()
        // TaskGrantWire＝既有实现（objectsOf/buildOffer/定义都在本文件）；
        // PlanController＝本批回填的唯一起单侧调用。多出第三处＝造了第二套解析。
        assertEquals(listOf("PlanController.kt", "TaskGrantWire.kt"), hits)
    }
}
