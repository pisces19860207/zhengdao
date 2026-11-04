// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百一十七批 · 改约风险档重算的三条判据（判定层 ＋ 接线层行为）。
//
// 造数原则：**不造档位**。删单文件／删整目录都只是两次真实的工具调用参数，
// 几档、必不必问一律由 `RiskMatrix.classify` 自己判（生产表）；测试只断言
// 「矩阵两次输出摆在一起之后，三态该走哪条路」。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskCtx
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.plan.AmendRiskProbe
import com.example.zhengdao.baopu.plan.AmendRiskRecheck
import com.example.zhengdao.baopu.plan.AmendRiskRules
import com.example.zhengdao.baopu.plan.AmendRiskShift
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules
import com.example.zhengdao.baopu.plan.PlanPledgeBoard
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AmendRiskTest {

    /** 假授权闸：只数「重新弹了几次、落了几行」，不弹真卡。 */
    private class FakeGate(var approved: Boolean = true) : AmendRiskGate {
        val reauthorizeCalls = mutableListOf<AmendRiskRecheck>()
        val downgradeRows = mutableListOf<Pair<AmendRiskRecheck, String>>()

        override fun reauthorize(recheck: AmendRiskRecheck): Boolean {
            reauthorizeCalls += recheck
            return approved
        }

        override fun recordDowngrade(recheck: AmendRiskRecheck, requirement: String) {
            downgradeRows += recheck to requirement
        }
    }

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("amend-417").toFile()
        PlanPledgeBoard.resetForTest()
        PlanPledgeBoard.attach(listOf(root))
    }

    @After
    fun tearDown() {
        PlanPledgeBoard.resetForTest()
        root.deleteRecursively()
    }

    // ── 造数：两次真实参数，档位全交给矩阵 ───────────────────────────────────

    /** 删单文件：`delete_path` 带一个 `path` ⇒ 矩阵判 R2、无额外必问判据。 */
    private fun deleteOne(id: String): ToolCall =
        ToolCall(id, "delete_path", JSONObject().put("path", "证道/报告.txt"))

    /** 删整目录：`paths` 列出 11 个目标 ⇒ 矩阵自己判 DELETE_OVER_10 ⇒ MUST_ASK。 */
    private fun deleteDir(id: String): ToolCall = ToolCall(
        id,
        "delete_path",
        JSONObject()
            .put("path", "证道/整目录")
            .put("paths", JSONArray((1..11).map { "证道/整目录/文件$it.txt" })),
    )

    private fun probe(before: ToolCall, after: ToolCall) = AmendRiskProbe(before, after)

    private fun recheck(before: ToolCall, after: ToolCall, table: RiskTable = RiskTable.DEFAULT) =
        AmendRiskRules.recheck(probe(before, after), RiskCtx(), table)

    // ── 判据①：低 → 高改约（删单文件 ⇒ 删目录）⇒ 必须重新弹确认 ─────────────

    @Test
    fun `判据1_删单文件改约成删目录_升档_必须重新过授权闸`() {
        val r = recheck(deleteOne("old"), deleteDir("new"))
        assertEquals("改约后档位该升（矩阵的必问判据命中）", AmendRiskShift.UP, r.shift)
        assertTrue(r.needsReauthorize)
        assertFalse(r.needsLedger)
        assertFalse(r.quiet)
        assertEquals("旧参数仍是矩阵判的 R2", RiskClass.R2, r.before.risk)
        assertTrue("新参数必须问（矩阵自己判的）", r.after.mustAsk)
        assertEquals(RiskCode.DELETE_OVER_10, r.after.reasonCode)

        val gate = FakeGate()
        val out = AmendRiskWiring.onAmendLanded(
            probe = probe(deleteOne("old"), deleteDir("new")),
            requirement = "把「删单文件」改成「删掉整个目录」",
            gate = gate,
        )
        assertEquals("升档必须重新弹一次确认", 1, gate.reauthorizeCalls.size)
        assertTrue("升档不该去落降档账", gate.downgradeRows.isEmpty())
        assertFalse("她点了重新确认 ⇒ 不许误拦", out.blocked)
        assertTrue("回灌要说清旧确认作废", out.line!!.contains("重新授权"))
        assertTrue(out.line!!.contains("旧确认作废"))
    }

    @Test
    fun `判据1_升档但重新授权没通过_本次动作不许执行`() {
        val gate = FakeGate(approved = false)
        val out = AmendRiskWiring.onAmendLanded(
            probe = probe(deleteOne("old"), deleteDir("new")),
            requirement = "改成删掉整个目录",
            gate = gate,
        )
        assertEquals(1, gate.reauthorizeCalls.size)
        assertTrue("没重新点头 ⇒ 必须拦下这次动作", out.blocked)
        assertTrue(out.line!!.contains("未通过"))
    }

    // ── 判据②：高 → 低改约 ⇒ 不许静默降，落变更账 ───────────────────────────

    @Test
    fun `判据2_删目录改回删单文件_降档_落一行变更账`() {
        val r = recheck(deleteDir("old"), deleteOne("new"))
        assertEquals(AmendRiskShift.DOWN, r.shift)
        assertTrue(r.needsLedger)
        assertFalse(r.needsReauthorize)

        val gate = FakeGate()
        val requirement = "把「删掉整个目录」改成「只删一个文件」"
        val out = AmendRiskWiring.onAmendLanded(
            probe = probe(deleteDir("old"), deleteOne("new")),
            requirement = requirement,
            gate = gate,
        )
        assertEquals("降档不许重复弹确认", 0, gate.reauthorizeCalls.size)
        assertEquals("降档必须落一行", 1, gate.downgradeRows.size)
        assertEquals(requirement, gate.downgradeRows.single().second)
        assertFalse("降档只留痕、不拦动作", out.blocked)
        assertTrue("回灌不许静默", out.line!!.contains("降档留痕"))

        // 真落盘：走 417 的落账口 ＋ 343 那本变更账，读回文件核对事件与前后档。
        val row = AmendRiskLedger.downgradeRow(r, requirement, 1_700_000_000_000L)
        PlanPledgeBoard.noteRiskDowngrade(row)
        val ledger = File(root, "计划军令状变更.jsonl")
        assertTrue("变更账文件没落盘", ledger.isFile)
        val text = ledger.readText()
        assertTrue("降档那行不在账上（静默降）", text.contains("\"event\":\"${AmendRiskLedger.EVENT}\""))
        assertTrue(text.contains("R2·必问"))
        assertTrue(text.contains(requirement))
        assertEquals("amend_risk_downgrade", row.optString("event"))
        // 同表不同事件：343 的 seedFrom 只认 event=amend，降档行不许被读成问过一轮／DROPPED。
        val seed = PlanPledgeAmendRules.seedFrom(listOf(row))
        assertEquals("降档行不许被 seedFrom 数成问过一轮", 0, seed.asksUsed)
        assertTrue("降档行不许被读成 DROPPED", seed.dropped.isEmpty())
        assertTrue("降档行不许被读成顶替关系", seed.superseded.isEmpty())
    }

    // ── 判据③：档位不变 ⇒ 不重复打扰 ────────────────────────────────────────

    @Test
    fun `判据3_档位不变_不重复弹也不记账也不回灌`() {
        val r = recheck(deleteOne("old"), deleteOne("new"))
        assertEquals(AmendRiskShift.SAME, r.shift)
        assertTrue(r.quiet)
        assertFalse(r.needsReauthorize)
        assertFalse(r.needsLedger)

        val gate = FakeGate()
        val out = AmendRiskWiring.onAmendLanded(
            probe = probe(deleteOne("old"), deleteOne("new")),
            requirement = "把「删单文件」改成「删另一个单文件」",
            gate = gate,
        )
        assertEquals("档位不变不许弹确认", 0, gate.reauthorizeCalls.size)
        assertEquals("档位不变不许记账", 0, gate.downgradeRows.size)
        assertNull("档位不变不许回灌一个字（不重复打扰）", out.line)
        assertFalse(out.blocked)
    }

    // ── 判据④的一半：判定只来自矩阵本体（改表 ⇒ 结论跟着变）─────────────────

    @Test
    fun `唯一口径_重算的两次结论逐字等于矩阵本体输出`() {
        val before = deleteOne("old")
        val after = deleteDir("new")
        val r = AmendRiskRules.recheck(probe(before, after))
        assertEquals(RiskMatrix.classify(before, RiskCtx(), RiskTable.DEFAULT), r.before)
        assertEquals(RiskMatrix.classify(after, RiskCtx(), RiskTable.DEFAULT), r.after)
    }

    @Test
    fun `唯一口径_改矩阵表_重算三态跟着变_证明没有硬编码判定`() {
        val loose = RiskTable.DEFAULT.copy(maxDeleteTargetsPerCall = 100)
        val r = recheck(deleteOne("old"), deleteDir("new"), table = loose)
        assertEquals("矩阵把删>10 的阈值放宽后，同一对参数不再升档", AmendRiskShift.SAME, r.shift)
        assertFalse("表放宽后矩阵不再判必问", r.after.mustAsk)
    }

    @Test
    fun `四档ordinal升档同样算升档_不只认必问`() {
        val read = ToolCall("read", "read_file", JSONObject().put("path", "证道/报告.txt"))
        val r = recheck(read, deleteOne("old"))
        assertEquals(RiskClass.R0, r.before.risk)
        assertEquals(RiskClass.R2, r.after.risk)
        assertEquals(AmendRiskShift.UP, r.shift)
    }

    // ── 边界：没有可比对的参数对，不假装算过、也不静默沿用旧批准 ─────────────

    @Test
    fun `没有旧新参数对_不重算不弹卡不记账_但明说旧批准不沿用`() {
        val gate = FakeGate()
        val out = AmendRiskWiring.onAmendLanded(
            probe = null,
            requirement = "改成删掉整个目录",
            gate = gate,
        )
        assertEquals(0, gate.reauthorizeCalls.size)
        assertEquals(0, gate.downgradeRows.size)
        assertFalse(out.blocked)
        assertEquals(AmendRiskWiring.NO_PROBE_LINE, out.line)
        assertTrue(out.line!!.contains("不静默沿用旧批准"))
    }
}
