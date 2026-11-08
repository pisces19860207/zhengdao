// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 越权降权要**在治理账本里看得见**（定点 D10）：镜像行落在
// `打扰治理.jsonl`（kind=violation / who=lease），且不动任何既有计数。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.authz.LeaseLedger
import com.example.zhengdao.baopu.authz.LeaseLimits
import com.example.zhengdao.baopu.authz.LeaseOffer
import com.example.zhengdao.baopu.authz.LeaseScope
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.authz.LiveLeaseGate
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaseGovernanceMirrorTest {

    private lateinit var root: File
    private val spec = ToolSpec(
        name = "delete_path",
        description = "t",
        inputSchema = jsonObjectOf("type" to "object"),
        permission = ToolPermission.CONFIRM,
        group = ToolGroup.FILES,
    ) { ToolOutcome.Ok("ok") }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu467-mirror").toFile()
        LeaseLedger.installRoot { root }
        AskGovernance.resetForTest()
        DecisionLedger.install(File(root, "私有"))
    }

    @After
    fun tearDown() {
        LeaseStore.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
    }

    private fun grantLease(): String = LeaseStore.grant(
        LeaseOffer("files/delete", LeaseScope(LeaseScope.Kind.WORKSPACE, root.path), 10, 3_600_000L),
        1L,
        "card-1",
    ).id

    @Test
    fun `越权满三_治理账本里有 lease 那一行`() {
        grantLease()
        val call = ToolCall("op-1", "delete_path", JSONObject())
        repeat(LeaseLimits.VIOLATION_STEP) { i ->
            LiveLeaseGate.onRejected(spec, 1L, call.copy(id = "op-$i"), "risk_matrix")
        }
        val rows = AskGovLedger.replay().filter { it.who == LiveLeaseGate.WHO_LEASE }
        assertEquals("降一档要在治理账本里留一行", 1, rows.size)
        assertEquals(AskGovLedger.KIND_VIOLATION, rows.first().kind)
        assertTrue("依据里点名类目", rows.first().basis.contains("files/delete"))
        assertTrue("明细里带 lease id", rows.first().detail.contains("lease="))
    }

    @Test
    fun `不是三道闸的拒_治理账本里一个字都不写`() {
        grantLease()
        val call = ToolCall("op-x", "delete_path", JSONObject())
        repeat(9) { i -> LiveLeaseGate.onRejected(spec, 1L, call.copy(id = "op-x$i"), "") }
        assertTrue(AskGovLedger.replay().none { it.who == LiveLeaseGate.WHO_LEASE })
    }

    @Test
    fun `没有租约的类目_越权不写治理账本`() {
        val call = ToolCall("op-1", "delete_path", JSONObject())
        repeat(LeaseLimits.VIOLATION_STEP) { i ->
            LiveLeaseGate.onRejected(spec, 1L, call.copy(id = "op-$i"), "risk_matrix")
        }
        assertTrue("没授过租约就没有可降的东西", AskGovLedger.replay().none { it.who == LiveLeaseGate.WHO_LEASE })
    }
}
