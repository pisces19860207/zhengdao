// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.OpinionAdapter
import com.example.zhengdao.baopu.inspect.OpinionLifecycle
import com.example.zhengdao.baopu.inspect.OpinionMerger
import com.example.zhengdao.baopu.inspect.Severity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 336 P2 · 生产接线（OpinionWiring）的闭合判据测试。
 *
 * 四条链逐条钉住（口径：两处都闭合）：
 *  事件键幂等（同事件重试并入、预算不重复消耗）→ Ruling.phase 落盘（含老行缺列读成 open）
 *  → 御案处置回写（RESOLVED＋追加处置账＋不再拦验收）→ DEFERRED 满 3 轮自动结案落账。
 */
class OpinionWiringClosureTest {

    /** L9 形状的意见；evFirst 不同 ⇒ 不同事件，相同 ⇒ 同一事件（重试形状）。 */
    private fun outside(evFirst: String) = OpinionAdapter.CLASSIFIER.adapt(
        Severity.MAJOR, "越界访问", ActionCode.REVIEW, LawRef.L9,
        listOf(evFirst, "tool:write_file", "matrix:v1"),
    )

    @Before
    fun reset() {
        OpinionWiring.resetForTest()
    }

    @Test
    fun `未处置的同事件再观察_照常判定_预算照挡_不算重试`() {
        // 修订（收窄到终态）：未处置（OPEN/DEFERRED）的再次观察是**新的意见发生**，
        // 照常过预算（同轮第二张被挡）——旧预算语义全保留；只有已处置后的重试才短路。
        val r1 = OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file", callId = "c1")
        assertEquals(OpinionDecision.POP, r1.decision)
        val r2 = OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file2", callId = "c2")
        assertEquals("同轮第二张照旧被预算挡下", OpinionDecision.DEFER, r2.decision)
        assertEquals("未处置的重试不算重试", 0, OpinionWiring.eventRetryCount())
        assertEquals(2, OpinionWiring.replay().size) // 两次都落账
        assertEquals(1, OpinionWiring.distinctEventCount()) // 但仍是同一事件
    }

    @Test
    fun `处置回写_RESOLVED_不再拦验收_追加处置账`() {
        OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file", callId = "c1")
        assertEquals(1, OpinionWiring.acceptanceBlockers().size)
        val row = OpinionWiring.onDisposition(
            OpinionMerger.eventKeyOf(outside("risk:outside_workspace")),
            "模拟用户应答：不允许",
        )
        assertNotNull(row)
        assertEquals(OpinionLifecycle.Phase.RESOLVED.wire, row!!.phase)
        assertEquals(OpinionWhy.DISPOSITION, row.why)
        assertTrue(OpinionWiring.acceptanceBlockers().isEmpty()) // 已处置 ⇒ 不再拦 C5
        // 处置后的重试：原样返回，不弹、不记新预算
        val before = OpinionWiring.state().second
        OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file", callId = "c2")
        assertEquals(before, OpinionWiring.state().second)
        assertTrue(OpinionWiring.acceptanceBlockers().isEmpty())
    }

    @Test
    fun `DEFERRED满3轮_自动结案落账_不再拦验收`() {
        // 轮源可控：每轮一个号（生产由 RunEngine 供；测试手动推轮）
        var round = 0
        OpinionWiring.installRoundSource { "t$round" }
        val b = outside("risk:outside_workspace:b")
        val bKey = OpinionMerger.eventKeyOf(b)
        // 轮 0：A 弹卡（OPEN）；B 同轮 ROUND_TAKEN ⇒ DEFERRED（第 1 轮）
        OpinionWiring.onOpinion(outside("risk:outside_workspace:a"), tool = "write_file", callId = "a1")
        OpinionWiring.onOpinion(b, tool = "write_file", callId = "b1")
        // 轮 1：换轮 ⇒ B 计第 2 轮
        round = 1
        OpinionWiring.onOpinion(outside("risk:outside_workspace:c"), tool = "write_file", callId = "c1")
        // 轮 2：再换轮 ⇒ B 计第 3 轮 ⇒ 自动 SETTLED 并落账
        round = 2
        OpinionWiring.onOpinion(outside("risk:outside_workspace:d"), tool = "write_file", callId = "d1")
        val settleRow = OpinionWiring.replay().firstOrNull { it.why == OpinionWhy.AUTO_SETTLED }
        assertNotNull("满 3 轮必须落自动结案账", settleRow)
        assertEquals(OpinionLifecycle.Phase.SETTLED.wire, settleRow!!.phase)
        assertEquals(bKey, OpinionMerger.eventKeyOf(settleRow.opinion))
        // 结案的事件不再拦验收（A 未处置仍在拦）
        val blockers = OpinionWiring.acceptanceBlockers()
        assertTrue(blockers.none { OpinionMerger.eventKeyOf(it) == bKey })
        assertTrue(blockers.any { OpinionMerger.eventKeyOf(it) == OpinionMerger.eventKeyOf(outside("risk:outside_workspace:a")) })
    }

    @Test
    fun `Ruling的phase字段_toJson与parse往返一致_老行缺列读成open`() {
        val ruling = OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file", callId = "c1")
        val parsed = Ruling.parse(ruling.toJson().toString())
        assertNotNull(parsed)
        assertEquals(ruling.phase, parsed!!.phase)
        // 老行（无 phase 列）：从账行文本里去掉该列 ⇒ 读成 open（旧账语义不变）
        val legacy = Regex("\"phase\":\"[^\"]*\",?").replace(ruling.toJson().toString(), "")
        val parsedLegacy = Ruling.parse(legacy)
        assertNotNull("legacy 行解析失败：$legacy", parsedLegacy)
        assertEquals(OpinionLifecycle.Phase.OPEN.wire, parsedLegacy!!.phase)
    }

    @Test
    fun `处置回写后_同事件重试_原样返回不记新账`() {
        val ek = OpinionMerger.eventKeyOf(outside("risk:outside_workspace"))
        OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file", callId = "c1")
        OpinionWiring.onDisposition(ek, "驳回")
        val rowsBefore = OpinionWiring.replay().size
        OpinionWiring.onOpinion(outside("risk:outside_workspace"), tool = "write_file", callId = "c2")
        assertEquals(rowsBefore, OpinionWiring.replay().size) // 终态事件的重试不落新账
        assertEquals(1, OpinionWiring.eventRetryCount())
    }
}
