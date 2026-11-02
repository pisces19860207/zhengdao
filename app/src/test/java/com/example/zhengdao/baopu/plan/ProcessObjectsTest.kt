// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-02-第三百七十四批-流程编排雏形 §三 判据 ①②③（④是 detekt 预算闸，跑在门禁脚本里）。
//
// 房型照 `PlanPledgeTest`（312）：判定层零 Android、零 IO，所以每一条负例都能在 JVM 里红。
// 三条判据各打什么：
//   判据① 三个对象各有正负例——无判据派工⇒拒、无据报问题⇒拒、进度倒退⇒拒；
//   判据② 与军令状/缺口项不重复造——源级钉死「本文件只声明派工单点名的那六个类型」
//         ＋三个对象的字段集逐字封死＋代码里不许出现 312/313/342 那几件的重造引用；
//   判据③ 366 能消费 STALLED——照文件头声明的消费顺序走一条链，并钉住账面值逐字是「STALLED」。
// 扫源码前先证明尺子自己会咬人（合成违规行必须被抓出来），免得「全绿」只是没扫到。
package com.example.zhengdao.baopu.plan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessObjectsTest {

    // ── 造数 ────────────────────────────────────────────────────────────────

    private val at = 1_730_000_000_000L

    private fun order(
        criteria: List<String> = listOf("三个对象建齐", "每条闸有负例"),
        lease: List<String> = listOf(MAIN_FILE, TEST_FILE),
        budget: Int? = 45,
    ): DispatchOrder = DispatchOrder(
        id = "374",
        title = "流程编排雏形",
        criteria = criteria,
        lease = lease,
        budget = budget,
        base = "cefa1ecd",
        assignedTo = "qoder",
        at = at,
    )

    private fun back(
        kind: ReturnKind,
        evidence: List<String> = listOf("app/build/test-results/ProcessObjects.xml"),
    ): ReturnItem = ReturnItem(kind = kind, text = "第 2 片做完，第 3 片卡在门禁", evidence = evidence)

    private fun note(done: Int, total: Int = 3, orderId: String = "374", blockers: List<String> = emptyList()): ProgressNote =
        ProgressNote(orderId = orderId, done = done, total = total, etaMinutes = 20, blockers = blockers)

    /** 与 PlanPledgeTest 同法：剥掉注释再扫（注释里提军令状是说明，不是重造）。 */
    private fun strippedCode(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun processObjectsCode(): String = strippedCode(File(SRC_UNDER_GATE).readText(Charsets.UTF_8))

    // ── 判据①：派工单闸 ──────────────────────────────────────────────────────

    @Test
    fun `判据1_派工单有判据有租约_放行`() {
        assertNull(ProcessObjectRules.dispatchRejection(order()))
    }

    @Test
    fun `负例1_无判据派工_拒`() {
        val reason = ProcessObjectRules.dispatchRejection(order(criteria = emptyList()))
        assertNotNull("没判据的单子必须拒", reason)
        assertTrue("拒因要点名「无判据不派活」：$reason", reason!!.contains("无判据不派活"))
    }

    @Test
    fun `负例1b_判据里塞空行_拒`() {
        val reason = ProcessObjectRules.dispatchRejection(order(criteria = listOf("交付三个对象", "  ")))
        assertNotNull("空行判据等于没判据：必须拒", reason)
        assertTrue("拒因要点名判据里的空行：$reason", reason!!.contains("空行"))
    }

    @Test
    fun `负例2_无租约开工_拒`() {
        val reason = ProcessObjectRules.dispatchRejection(order(lease = emptyList()))
        assertNotNull("没租约的单子必须拒", reason)
        assertTrue("拒因要点名「没租约不许开工」：$reason", reason!!.contains("没租约不许开工"))
    }

    @Test
    fun `负例2b_租约里塞空路径_拒`() {
        val reason = ProcessObjectRules.dispatchRejection(order(lease = listOf(MAIN_FILE, "")))
        assertNotNull("空路径撞不了也查不了：必须拒", reason)
        assertTrue("拒因要点名租约里的空路径：$reason", reason!!.contains("空路径"))
    }

    @Test
    fun `判据1_没写预算与没写基线都不拦`() {
        // §一 只给 criteria／lease 设闸；budget 与 base 没写是记录不全，不是不许派活。
        assertNull(ProcessObjectRules.dispatchRejection(order(budget = null)))
        assertNull(ProcessObjectRules.dispatchRejection(order(budget = null).copy(base = "")))
    }

    // ── 判据①：回传闸 ────────────────────────────────────────────────────────

    @Test
    fun `判据1_PROBLEM与NEED_DECISION带证据_放行`() {
        assertNull(ProcessObjectRules.returnRejection(back(ReturnKind.PROBLEM)))
        assertNull(ProcessObjectRules.returnRejection(back(ReturnKind.NEED_DECISION)))
    }

    @Test
    fun `负例3_PROBLEM不带证据_拒`() {
        val reason = ProcessObjectRules.returnRejection(back(ReturnKind.PROBLEM, evidence = emptyList()))
        assertNotNull("无据报问题必须拒", reason)
        assertTrue("拒因要点名没带证据：$reason", reason!!.contains("没带证据"))
    }

    @Test
    fun `负例3b_NEED_DECISION不带证据_拒`() {
        val reason = ProcessObjectRules.returnRejection(back(ReturnKind.NEED_DECISION, evidence = emptyList()))
        assertNotNull("无据要人拍板必须拒", reason)
        assertTrue("拒因要点名「要人拍板」这一类：$reason", reason!!.contains(ReturnKind.NEED_DECISION.name))
    }

    @Test
    fun `判据1_FYI不带证据_放行`() {
        assertNull(ProcessObjectRules.returnRejection(back(ReturnKind.FYI, evidence = emptyList())))
    }

    @Test
    fun `负例3c_证据里塞空行_三类都拒`() {
        ReturnKind.entries.forEach { kind ->
            val reason = ProcessObjectRules.returnRejection(back(kind, evidence = listOf("")))
            assertNotNull("$kind 带了假证据（空行）也必须拒")
            assertTrue("$kind 的拒因要点名空行：$reason", reason!!.contains("空行"))
        }
    }

    @Test
    fun `域_回传三类闭集_没有第四类`() {
        assertEquals(listOf("PROBLEM", "NEED_DECISION", "FYI"), ReturnKind.entries.map { it.name })
        assertTrue("只有 FYI 可以不带证据", ReturnKind.entries.filter { !it.requiresEvidence() } == listOf(ReturnKind.FYI))
    }

    // ── 判据①：进度闸 ────────────────────────────────────────────────────────

    @Test
    fun `判据1_进度不超总数_放行`() {
        assertNull(ProcessObjectRules.progressRejection(note(done = 2, total = 3)))
        assertNull(ProcessObjectRules.progressRejection(note(done = 3, total = 3)))
    }

    @Test
    fun `负例4_进度超过总数_拒`() {
        val reason = ProcessObjectRules.progressRejection(note(done = 4, total = 3))
        assertNotNull("做完的比总数还多必须拒", reason)
        assertTrue("拒因要带出这两个数：$reason", reason!!.contains("4/3"))
    }

    @Test
    fun `负例5_进度倒退_拒`() {
        val reason = ProcessObjectRules.progressRegressionRejection(note(done = 3), note(done = 2))
        assertNotNull("片数倒退必须拒（片不会自己消失）", reason)
        assertTrue("拒因要带出退到哪：$reason", reason!!.contains("3/3 退到 2/3"))
    }

    @Test
    fun `负例5b_倒退的读数不先拦会被当成有进展`() {
        // 这条钉的是**消费顺序**：stallOf 只认「相等＝停滞」，倒退在它眼里是「有进展」。
        // 所以 366 必须先过倒退闸，否则谎报的读数反而洗白。
        assertNotNull(ProcessObjectRules.progressRegressionRejection(note(done = 3), note(done = 2)))
        assertEquals(StallState.ON_TRACK, ProcessObjectRules.stallOf(note(done = 3), note(done = 2)))
    }

    @Test
    fun `域_换单号不比对_不判倒退`() {
        assertNull(ProcessObjectRules.progressRegressionRejection(note(done = 3), note(done = 2, orderId = "375")))
    }

    // ── 判据③：STALLED 的接口 ────────────────────────────────────────────────

    @Test
    fun `判据3_连续两次同进度_标STALLED`() {
        assertEquals(StallState.STALLED, ProcessObjectRules.stallOf(note(done = 1), note(done = 1)))
    }

    @Test
    fun `判据3_第一次读数不判停滞`() {
        assertEquals(StallState.ON_TRACK, ProcessObjectRules.stallOf(null, note(done = 1)))
    }

    @Test
    fun `判据3_片数动了不算停滞`() {
        assertEquals(StallState.ON_TRACK, ProcessObjectRules.stallOf(note(done = 1), note(done = 2)))
    }

    @Test
    fun `判据3_总数变了但片没多仍算停滞`() {
        // 口径记在文件头：「同进度」按做完的片数判——重新切过片不代表进展。
        assertEquals(StallState.STALLED, ProcessObjectRules.stallOf(note(done = 1, total = 3), note(done = 1, total = 5)))
    }

    @Test
    fun `判据3_换单号不判停滞`() {
        assertEquals(StallState.ON_TRACK, ProcessObjectRules.stallOf(note(done = 1), note(done = 1, orderId = "375")))
    }

    @Test
    fun `判据3_366消费的账面值逐字是STALLED`() {
        assertEquals("STALLED", StallState.STALLED.name)
        assertEquals("ON_TRACK", StallState.ON_TRACK.name)
        assertTrue(StallState.STALLED.stalled)
        assertFalse(StallState.ON_TRACK.stalled)
    }

    @Test
    fun `判据3_366按声明顺序消费一条链`() {
        assertNull("单要先放行才开工", ProcessObjectRules.dispatchRejection(order()))
        val readings = listOf(note(done = 1), note(done = 1), note(done = 2), note(done = 2), note(done = 2))
        val states = mutableListOf<StallState>()
        var prev: ProgressNote? = null
        for (reading in readings) {
            assertNull("每份读数先过单份闸", ProcessObjectRules.progressRejection(reading))
            prev?.let { assertNull("再过倒退闸", ProcessObjectRules.progressRegressionRejection(it, reading)) }
            states += ProcessObjectRules.stallOf(prev, reading)
            prev = reading
        }
        assertEquals(
            listOf(
                StallState.ON_TRACK,
                StallState.STALLED,
                StallState.ON_TRACK,
                StallState.STALLED,
                StallState.STALLED,
            ),
            states,
        )
    }

    // ── 判据②：不重复造（源级） ───────────────────────────────────────────────

    @Test
    fun `判据2_源级_只声明派工单点名的六个类型`() {
        val declared = Regex("""(?m)\b(?:data class|enum class|class|interface|object)\s+(\w+)""")
            .findAll(processObjectsCode()).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("DispatchOrder", "ReturnKind", "ReturnItem", "ProgressNote", "StallState", "ProcessObjectRules"),
            declared,
        )
    }

    @Test
    fun `判据2_源级_三个对象的字段集逐字封死`() {
        assertEquals(
            listOf("id", "title", "criteria", "lease", "budget", "base", "assignedTo", "at"),
            fieldsOf("internal data class DispatchOrder("),
        )
        assertEquals(listOf("kind", "text", "evidence"), fieldsOf("internal data class ReturnItem("))
        assertEquals(
            listOf("orderId", "done", "total", "etaMinutes", "blockers"),
            fieldsOf("internal data class ProgressNote("),
        )
    }

    @Test
    fun `判据2_源级_代码里不重造军令状与缺口项那几件`() {
        val code = processObjectsCode()
        // 判据②「引用即可」的正直落法：本层不 import、不构造、不子类化那五件（引用写在注释里，已剥掉）。
        listOf("GapItem", "GapKind", "PledgeSnapshot", "TaskBudget", "AmendBudget").forEach { name ->
            assertFalse("代码里出现了 $name 的引用＝另起一份格式", code.contains(name))
        }
        assertTrue("闸得留在本文件里（不许把三条闸推给别的层）", code.contains("ProcessObjectRules"))
    }

    @Test
    fun `租约_源级_本文件不碰RunEngine与GuardPipeline`() {
        val code = processObjectsCode()
        listOf("RunEngine", "GuardPipeline", "MainActivity").forEach { name ->
            assertFalse("§一 点名不改 $name，代码里连引用都不该有", code.contains(name))
        }
    }

    @Test
    fun `判据2_尺子先量自己_合成违规必须被抓`() {
        val fields = Regex("""val (\w+)""").findAll("val id: String,\n    val pledgeId: String,\n)  {").map { it.groupValues[1] }.toList()
        assertEquals("拆字段段的尺子必须真拆出字段", listOf("id", "pledgeId"), fields)
        val sneaky = strippedCode("object ProcessObjectRules /* data class GapItem */ {\n}")
        assertFalse("剥注释后不许留下注释里的类型声明", sneaky.contains("data class"))
    }

    /** 取某个 data class 的形参名列表（本文件的 data class 都没有类体，右括号即结束）。 */
    private fun fieldsOf(header: String): List<String> = File(SRC_UNDER_GATE).readText(Charsets.UTF_8)
        .substringAfter(header)
        .substringBefore(")")
        .let { Regex("""val (\w+)""").findAll(it).map { m -> m.groupValues[1] }.toList() }

    private companion object {
        const val MAIN_FILE = "app/src/main/java/com/example/zhengdao/baopu/plan/ProcessObjects.kt"
        const val TEST_FILE = "app/src/test/java/com/example/zhengdao/baopu/plan/ProcessObjectsTest.kt"

        /** 被扫的源文件：Gradle 单测的工作目录是模块目录 `app/`（与 PlanPledgeTest 同一把尺子的取径）。 */
        const val SRC_UNDER_GATE = "src/main/java/com/example/zhengdao/baopu/plan/ProcessObjects.kt"
    }
}
