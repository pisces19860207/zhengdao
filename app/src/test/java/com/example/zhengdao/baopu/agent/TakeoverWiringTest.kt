// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十八批 · 接管接线的**行为门禁**（判据②③）。
//
// 判据② STALLED 现场 ⇒ 触发接管，且接管不动别人的历史分支（有测试）
// 判据③ 门拒绝时不许静默（落账＋说明理由）
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.plan.ProgressNote
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TakeoverWiringTest {

    private lateinit var root: File

    /** 假分支：自带 forcePush 写口——跑完五步后它必须一次都没被碰过（判据②）。 */
    private class FakeBranch(private val sha: String) {
        var reads = 0
            private set
        var forcePushed = false
            private set

        fun handle(): FrozenBranch = FrozenBranch {
            reads++
            sha
        }

        fun forcePush() {
            forcePushed = true
        }
    }

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("takeover-wiring-").toFile()
        DecisionLedger.install(root)
        TakeoverWiring.resetForTest()
    }

    @After
    fun tearDown() {
        runCatching { root.deleteRecursively() }
        TakeoverWiring.resetForTest()
    }

    private fun order(
        id: String = "O1",
        traces: List<ShardTrace> = listOf(ShardTrace(1, true, true, true)),
        totalShards: Int = 3,
        commitAt: Long = NOW - 10 * MIN,
        logAt: Long = NOW - 10 * MIN,
        suspensions: Int = 0,
        exhausted: Boolean = false,
    ) = TrackedOrder(
        orderId = id,
        origin = "甲会话",
        successor = "乙会话",
        totalShards = totalShards,
        stallThresholdMs = 2 * HOUR,
        traces = traces,
        lastCommitAtMs = commitAt,
        lastLogAtMs = logAt,
        suspensionsWithoutProgress = suspensions,
        selfReportedExhausted = exhausted,
        headSha = "abc123",
    )

    private fun note(done: Int, total: Int = 3) =
        ProgressNote("O1", done, total, null, emptyList())

    // ── 判据② STALLED 现场 ⇒ 触发接管 ─────────────────────────────────────

    @Test
    fun `判据②_STALLED 现场_触发接管五步`() {
        // 连续两份读数同进度（done 不变）⇒ stallOf 判 STALLED（374 消费口留给 366 的那条）
        val first = TakeoverWiring.reportProgress(order(), note(done = 1), NOW)
        assertNull("首份读数不比对 ⇒ 无信号", first.verdict)
        val second = TakeoverWiring.reportProgress(order(), note(done = 1), NOW + MIN)
        assertNotNull("同进度第二份 ⇒ 判出信号", second.verdict)
        assertEquals(TakeoverSignal.STALLED, second.verdict?.signal)
        assertNotNull("STALLED ⇒ 接管五步被调", second.record)
        val record = second.record!!
        assertEquals(TakeoverGate.STEPS, record.steps)
        assertEquals("①冻结现场→②读留痕→③影子先验→④接剩余片→⑤记账 逐字可查", TakeoverGate.STEPS, record.steps)
        assertEquals(listOf(2, 3), record.remainingShards)
        assertEquals(2, record.resumeFromShard)
        // 回执落接管账（判据③同款：不许静默）
        assertTrue("账行必须落盘", TakeoverWiring.ledger().any { it.contains("接管 单 O1") })
    }

    @Test
    fun `判据②_接管不动别人的历史分支_句柄只有读口且只读两次`() {
        val branch = FakeBranch("deadbeef")
        val outcome = TakeoverWiring.onProgressNote(
            order = order(),
            previousNote = note(done = 1),
            note = note(done = 1),
            recomputeReason = null,
            branch = branch.handle(),
            nowMs = NOW,
        )
        assertNotNull(outcome.record)
        // 冻结凭证：前后 sha 相等＝没动
        assertTrue("冻结前后头 sha 必须一致", outcome.record!!.freeze.unchanged)
        assertEquals("deadbeef", outcome.record.freeze.shaBefore)
        assertEquals("deadbeef", outcome.record.freeze.shaAfter)
        // 句柄恰好被读两次（入口＋出口），没有第三处
        assertEquals("对分支只有两次读，没有任何写", 2, branch.reads)
        // 假分支自带的写口从未被碰（想违规，编译都过不去——类型上就没有口）
        assertTrue("forcePush 写口从未被调用过——它根本不在 FrozenBranch 类型上", !branch.forcePushed)
    }

    @Test
    fun `判据②_EXHAUSTED 自报_也触发接管`() {
        // 两片都三样齐 ⇒ done=2 不虚报（谎报序在最前，影子核验必须先过关）
        val twoVerified = order(
            exhausted = true,
            traces = listOf(ShardTrace(1, true, true, true), ShardTrace(2, true, true, true)),
        )
        val outcome = TakeoverWiring.onProgressNote(
            order = twoVerified,
            previousNote = note(done = 1),
            note = note(done = 2),
            recomputeReason = null,
            branch = FakeBranch("cafe").handle(),
            nowMs = NOW,
        )
        assertEquals(TakeoverSignal.EXHAUSTED, outcome.verdict?.signal)
        assertNotNull("EXHAUSTED ⇒ 接管", outcome.record)
        // 额度耗尽不追责：账行没有「按谎报处理」后缀
        val line = outcome.record!!.ledgerLine
        assertTrue(line.contains("额度耗尽"))
        assertTrue(!line.contains("谎报"))
    }

    @Test
    fun `有进展的读数_不触发接管`() {
        val twoVerified = order(
            traces = listOf(ShardTrace(1, true, true, true), ShardTrace(2, true, true, true)),
        )
        val outcome = TakeoverWiring.onProgressNote(
            order = twoVerified,
            previousNote = note(done = 1),
            note = note(done = 2),
            recomputeReason = null,
            branch = FakeBranch("cafe").handle(),
            nowMs = NOW,
        )
        assertNull("片数涨了、提交新鲜、没自报耗尽 ⇒ 无信号", outcome.verdict)
        assertNull(outcome.record)
    }

    // ── 判据③ 门拒绝不许静默 ────────────────────────────────────────────────

    @Test
    fun `判据③_读数被进度闸拒_落拒账带原拒因`() {
        val outcome = TakeoverWiring.reportProgress(order(), note(done = 5, total = 3), NOW)
        assertNotNull("done>total 自相矛盾 ⇒ 被拒", outcome.rejection)
        assertTrue("拒账必须落接管账", TakeoverWiring.ledger().any { it.contains("读数被进度闸拒") })
        assertNull("被拒的读数不进 classify", outcome.verdict)
    }

    @Test
    fun `判据③_无声倒退被拒_落拒账_带理由的倒退出账行`() {
        val rejected = TakeoverWiring.onProgressNote(
            order = order(), previousNote = note(done = 2), note = note(done = 1),
            recomputeReason = null, branch = FakeBranch("x").handle(), nowMs = NOW,
        )
        assertNotNull("无声倒退 ⇒ 拒", rejected.rejection)
        assertTrue(TakeoverWiring.ledger().any { it.contains("倒退被拒") })
        val allowed = TakeoverWiring.onProgressNote(
            order = order(), previousNote = note(done = 2), note = note(done = 1),
            recomputeReason = "重算：切片口径变了", branch = FakeBranch("x").handle(), nowMs = NOW,
        )
        assertNull("带理由的倒退放行", allowed.rejection)
        assertTrue("放行要出显式重算账行（374 裁定 Q2）", TakeoverWiring.ledger().any { it.contains("显式重算") })
    }

    @Test
    fun `判据③_接管闸拒_全部已验_落拒账不硬接`() {
        val allVerified = order(traces = (1..3).map { ShardTrace(it, true, true, true) })
        val outcome = TakeoverWiring.onProgressNote(
            order = allVerified, previousNote = note(done = 1), note = note(done = 1),
            recomputeReason = null, branch = FakeBranch("x").handle(), nowMs = NOW,
        )
        assertNotNull(outcome.verdict)
        assertNotNull("没有剩余片 ⇒ 接管闸拒", outcome.rejection)
        assertNull(outcome.record)
        assertTrue(TakeoverWiring.ledger().any { it.contains("接管闸拒") })
    }

    @Test
    fun `INCOMPLETE 谎报_落信号行但不接管（本批只接 STALLED 与 EXHAUSTED）`() {
        val outcome = TakeoverWiring.onProgressNote(
            order = order(traces = listOf(ShardTrace(1, true, true, true))),
            previousNote = note(done = 1),
            note = note(done = 3), // 自报 3 片完成，影子只认 1 片 ⇒ 谎报
            recomputeReason = "重算",
            branch = FakeBranch("x").handle(),
            nowMs = NOW,
        )
        assertEquals(TakeoverSignal.INCOMPLETE, outcome.verdict?.signal)
        assertNull("谎报不在本批接管线", outcome.record)
        assertTrue(TakeoverWiring.ledger().any { it.contains("INCOMPLETE") })
    }

    @Test
    fun `无痕接管_单独一行账`() {
        val outcome = TakeoverWiring.onProgressNote(
            order = order(traces = listOf(ShardTrace(1, false, false, false))),
            previousNote = note(done = 0),
            note = note(done = 0),
            recomputeReason = null,
            branch = FakeBranch("x").handle(),
            nowMs = NOW,
        )
        assertNotNull(outcome.record)
        assertNotNull("三样全缺 ⇒ 无痕行", outcome.record!!.tracelessLine)
        assertTrue(TakeoverWiring.ledger().any { it.contains("无痕接管") })
    }

    // ── 心跳扫（Settled 收尾家族的生产调用点） ──────────────────────────────

    @Test
    fun `心跳扫_从进度账重建两行读数_判停滞并接管`() {
        // 报两份同进度读数（reportProgress 当场消费过一次；这里验证 sweep 的账本重建能力）
        TakeoverWiring.reportProgress(order(), note(done = 1), NOW)
        TakeoverWiring.reportProgress(order(), note(done = 1), NOW + MIN)
        // 已接管的单不再重复接
        val again = TakeoverWiring.sweepStalled(NOW + 2 * MIN)
        assertTrue("已接过的单不重复开账", again.isEmpty())
        assertTrue(TakeoverWiring.ledger().count { it.contains("接管 单 O1") } == 1)
    }

    companion object {
        private const val MIN = 60_000L
        private const val HOUR = 3_600_000L
        private const val NOW = 1_700_000_000_000L
    }
}
