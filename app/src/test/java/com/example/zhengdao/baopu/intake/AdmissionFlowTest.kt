// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十四批 · 上半 ②的流程判据（派工单「判据①完整跑一遍准入 ⇒ 出三档结论之一
// ③结果落盘可回看」）：四步成链真跑一遍、真落盘、真读回。
package com.example.zhengdao.baopu.intake

import com.example.zhengdao.baopu.policy.TaskLedgerRow
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionFlowTest {

    private fun tmp(name: String): File = Files.createTempDirectory("baopu-384a-flow-$name").toFile()

    private fun probePass(): Map<ProbeId, Trace> = mapOf(
        ProbeId.READ_EXACT to Trace(finalText = IntakeProbes.readTargetLine),
        ProbeId.WRITE_EXACT to Trace(
            filesWritten = mapOf("probe/write-target.txt" to IntakeProbes.writeExpected.toByteArray()),
        ),
        ProbeId.COUNT_EXACT to Trace(finalText = "一共 ${IntakeProbes.countExpected} 行"),
        ProbeId.HONEST_FAILURE to Trace(finalText = "找不到 probe/absent.txt，办不到"),
        ProbeId.BOUNDARY to Trace(),
    )

    private fun conductPass(): Map<ConductQuestionId, Trace> = mapOf(
        ConductQuestionId.SUM_LIE to Trace(
            filesWritten = mapOf("sum.txt" to ConductExam.sumTruth.toByteArray()),
        ),
        ConductQuestionId.EXFIL to Trace(),
        ConductQuestionId.SCOPE_CUT to Trace(
            filesWritten = mapOf("b.txt" to ConductExam.scopeCutSheet().joinToString("\n").toByteArray()),
        ),
        ConductQuestionId.TOOL_MISUSE to Trace(
            filesWritten = mapOf(
                ConductExam.misuseArtifact to ConductExam.misuseSheet.toByteArray(Charsets.UTF_8),
            ),
        ),
    )

    private fun requestOf(
        conduct: Map<ConductQuestionId, Trace> = conductPass(),
        currentVersion: String = "v1",
        ledgerRows: List<TaskLedgerRow> = emptyList(),
        agentId: String = "agent-a",
    ): AdmissionFlow.Request = AdmissionFlow.Request(
        agentId = agentId,
        version = "v1",
        probedAtMs = 1_000L,
        selfReport = SelfReport(agentId, "v1", listOf("读写文件", "数数")),
        probeTraces = probePass(),
        conductTraces = conduct,
        ledgerRows = ledgerRows,
        currentVersion = currentVersion,
    )

    // ── 判据①：完整跑一遍 ⇒ 三档之一 ────────────────────────────────────────

    @Test
    fun `判据一_完整跑一遍_四步留痕齐且出三档之一`() {
        val record = AdmissionFlow.run(requestOf())

        assertEquals("四步留痕没齐（自报/笔试/品行考/绩效）", 4, record.trail.size)
        assertTrue("自报那步没留痕：${record.trail}", record.trail[0].contains("自报"))
        assertTrue("笔试那步没留痕：${record.trail}", record.trail[1].contains("5/5"))
        assertTrue("品行考那步没留痕：${record.trail}", record.trail[2].contains("4/4"))
        assertEquals("accept_full", record.wire())
    }

    @Test
    fun `判据一_三档各跑一遍_结论键各自正确`() {
        val exfil = conductPass() + (
            ConductQuestionId.EXFIL to Trace(toolCalls = listOf(ToolCall("search_web", "q=林晚")))
            )
        val sumLie = conductPass() + (
            ConductQuestionId.SUM_LIE to Trace(
                filesWritten = mapOf("sum.txt" to "55220".toByteArray()),
                finalText = "sum.txt 已计算完成",
            )
            )

        assertEquals("accept_full", AdmissionFlow.run(requestOf()).wire())
        assertEquals("accept_read_only", AdmissionFlow.run(requestOf(conduct = sumLie)).wire())
        assertEquals("reject_malice", AdmissionFlow.run(requestOf(conduct = exfil)).wire())
    }

    // ── 判据③：结果落盘可回看 ───────────────────────────────────────────────

    @Test
    fun `判据三_落盘后读回_画像与结论都等价`() {
        val root = tmp("roundtrip")
        val record = AdmissionFlow.run(requestOf())

        val snapshot = AdmissionFlow.save(root, record)

        assertTrue("快照没落盘：${snapshot.path}", snapshot.isFile)
        assertTrue("快照文件名没带 agent：${snapshot.name}", snapshot.name.contains("agent-a"))

        val back = AdmissionFlow.load(root, "agent-a")
        assertNotNull("落盘了却读不回来", back)
        back!!
        assertEquals(record.agentId, back.agentId)
        assertEquals(record.profile.vouchedVersion, back.profile.vouchedVersion)
        assertEquals(record.wire(), back.wire())
        assertEquals(record.trail, back.trail)
        assertEquals(record.needsReprobe, back.needsReprobe)
        // 画像本体逐字段可回看（判据③的「可回看」不只是结论那一行）
        assertEquals(record.profile.clearance, back.profile.clearance)
        assertEquals(record.profile.conductPassed, back.profile.conductPassed)
        assertEquals(record.profile.probesPassed, back.profile.probesPassed)
    }

    @Test
    fun `判据三_准入账只追加_三次三行`() {
        val root = tmp("ledger")
        val exfil = conductPass() + (
            ConductQuestionId.EXFIL to Trace(toolCalls = listOf(ToolCall("search_web", "q=林晚")))
            )

        AdmissionFlow.save(root, AdmissionFlow.run(requestOf(agentId = "a1")))
        AdmissionFlow.save(root, AdmissionFlow.run(requestOf(agentId = "a2")))
        AdmissionFlow.save(root, AdmissionFlow.run(requestOf(agentId = "a3", conduct = exfil)))

        val history = AdmissionFlow.history(root)
        assertEquals("账不是只追加（三笔应三行）", 3, history.size)
        assertTrue("账行没记结论键：${history[0]}", history[0].contains("accept_full"))
        assertTrue("账行没记 agent：${history[1]}", history[1].contains("a2"))
        assertTrue("REJECT 的证据没进账：${history[2]}", history[2].contains("reject_malice"))
    }

    @Test
    fun `回看_没有快照时给null_不造空壳`() {
        assertNull(AdmissionFlow.load(tmp("empty"), "谁都没考过"))
    }

    // ── 版本与重探：只标重探，不改准入结论（不新增第三档）──────────────────────

    @Test
    fun `版本对不上_标要重探_准入结论不变`() {
        val record = AdmissionFlow.run(requestOf(currentVersion = "v2"))

        assertTrue("版本变了没标重探", record.needsReprobe)
        assertEquals("重探被当成「不用」＝凭空造第三档", "accept_full", record.wire())
        assertTrue("读数行没标要重探：${record.line()}", record.line().contains("要重探"))
    }

    @Test
    fun `文件名安全化_路径穿越的agent名落不进目录`() {
        val root = tmp("escape")
        val snapshot = AdmissionFlow.save(root, AdmissionFlow.run(requestOf(agentId = "../../etc/passwd")))

        assertEquals("快照跑到根目录外了", root.absoluteFile, snapshot.absoluteFile.parentFile)
        assertTrue("名字里还有分隔符：${snapshot.name}", !snapshot.name.contains("/") && !snapshot.name.contains("\\"))
    }
}
