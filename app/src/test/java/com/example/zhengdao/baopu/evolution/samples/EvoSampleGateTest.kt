// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批 · 判据③（样本不足强行转正 ⇒ 拒并记账）与判据⑤（不改 495/517 聚合口径）。
//
// 判据③走**真盘账**：拒了不算数，"拒＋账上有那一行"才算（506 给参谋意见账写明的用途就是
// "被拦下来的意见也记这一行"——只拦不记，等于没人知道有人试过强行转正）。
// 判据⑤是**反向钉**：473/495/517 的三个门槛常量与"照数不重"的计数口径逐条断言，
// 本批要是哪天顺手把它们改了，这里立刻红（589 的 20/7/8 是另一把尺，不许合并）。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.CanaryMetrics
import com.example.zhengdao.baopu.consequence.CanarySample
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.EvolutionDepth
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvoSampleGateTest {

    private val day = EvoSampleRules.DAY_MS
    private val now = 1_760_000_000_000L
    private val domain = CanaryCapability.REFLECTION_AUTO_EDIT.wire

    private lateinit var ws: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        ws = TempSandbox.create("zd589-gate-")
        memory = File(ws, "抱朴记忆")
        DecisionLedger.install(memory)
        EvoSampleWiring.install(ws)
    }

    @After
    fun tearDown() {
        EvoSampleWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(ws)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun advisorRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.ADVISOR}${EvoSampleWiring.ADVISOR_PREFIX}") }

    private fun fill(n: Int, days: Int) {
        val span = days.toLong() * day
        (0 until n).forEach { i ->
            val at = if (n <= 1) now - span else now - span + i * span / (n - 1)
            EvoSampleWiring.record(
                EvoSampleEvent(domain, 9_000L + i, "run-$i", "act", at),
            )
        }
    }

    // ── 判据③：样本不足强行转正 ⇒ 拒并记账 ────────────────────────────────

    @Test
    fun `判据③_3次要求转正_拒并记账`() {
        fill(3, 8)
        val out = EvoSampleWiring.promote(domain, now)
        assertFalse("3 次要求转正必须被拒", out.allowed)
        assertTrue("拒的理由要说清是样本不足：${out.reason}", out.reason.contains("样本不足"))
        assertTrue("拒行要记账（判据③的记账半边）", out.refusalAccepted)

        val row = advisorRows().singleOrNull()
        assertEquals("只落一行，不许刷屏", 1, advisorRows().size)
        assertEquals("账行走既有参谋意见 kind（不新开闭集）", DecisionKind.ADVISOR_OPINION, row!!.kind)
        // after=accepted ≠ 转正成功：这一列说的是「这条参谋意见被账收下了」。
        // blocked 是**参谋意见自己违规**（模型给自己发票之类）那一支，与样本门无关——
        // 样本门的拒绝写在 reason 里，账收下了才叫「拒也留了痕」。
        assertEquals("被拒的意见要真被账收下", "accepted", row.after)
        assertTrue("账上要写得出为什么：${row.reason}", row.reason.contains("样本不足"))
        assertTrue("账上要认得出是哪个域：${row.evidence}", row.evidence.contains(domain))
    }

    @Test
    fun `判据③_10次要求转正_拒并写只够灰度`() {
        fill(10, 8)
        val out = EvoSampleWiring.promote(domain, now)
        assertFalse(out.allowed)
        assertTrue("10 次差在次数，账上要写「只够灰度」：${out.reason}", out.reason.contains("只够灰度"))
        assertEquals(1, advisorRows().size)
    }

    @Test
    fun `判据③_25次8天_放行且不落拒行`() {
        fill(25, 8)
        val before = rows().size
        val out = EvoSampleWiring.promote(domain, now)
        assertTrue("够格就该放行到 473 的梯子：${out.reason}", out.allowed)
        assertEquals("放行不落拒账", before, rows().size)
        assertEquals(0, advisorRows().size)
    }

    @Test
    fun `判据③_没登记在册的域_拒且不记账`() {
        val out = EvoSampleWiring.promote("no_such_domain", now)
        assertFalse(out.allowed)
        assertFalse("问错对象不是「差样本」，不记账", out.refusalAccepted)
        assertEquals(0, rows().size)
    }

    @Test
    fun `读数_evo_samples每域一行_没跑过的域也出现`() {
        fill(25, 8)
        val lines = EvoSampleWiring.readout()
        assertEquals("登记表里每个域都要有一行", CanaryCapability.entries.size, lines.size)
        assertTrue(lines.all { it.startsWith(EvoSampleReading.READING_KEY + "|") })
        assertTrue(lines.any { it.contains(domain) && it.endsWith(EvoSampleVerdict.PROMOTE_READY.cn) })
        assertTrue(
            "没跑过的域要报样本不足，不是消失：${lines.first { it.contains(CanaryCapability.SCOUT.wire) }}",
            lines.first { it.contains(CanaryCapability.SCOUT.wire) }.endsWith(EvoSampleVerdict.INSUFFICIENT.cn),
        )
    }

    // ── 判据⑤：不改 473/495/517 的聚合口径（反向钉） ──────────────────────

    @Test
    fun `判据⑤_495的样本门槛与窗口没被本批顺手改`() {
        assertEquals("473 动档门槛仍是 10", 10, CanaryLadder.MIN_RUNS)
        assertEquals("指标窗口仍是 14 天", 14, CanaryLadder.WINDOW_DAYS)
        assertEquals("转全开仍要连续两轮", 2, CanaryLadder.PASS_ROUNDS_FOR_FULL)
        assertEquals("589 的转正线是另一把尺（20 次）", 20, EvoSampleRules.PROMOTE_EVENTS)
        assertEquals("589 的灰度线是另一把尺（8 次）", 8, EvoSampleRules.CANARY_EVENTS)
    }

    @Test
    fun `判据⑤_同样的10条观察_495照数10条_589按独立事件数5条`() {
        val samples = (0 until 10).map { i -> CanarySample(atMs = now - i * day, ok = true, askedHuman = false) }
        val metrics = CanaryMetrics.of(samples, now)
        assertEquals("495 的口径是「跑了几次就数几次」，本批不许往里塞去重", 10, metrics.runs)
        assertTrue("10 条在 473 那边仍是「读得出来」", metrics.known)

        // 同样这 10 条，其中 5 条是同一会话同一事件（重复投递）⇒ 589 只认 5 条独立事件。
        val events = (0 until 10).map { i ->
            EvoSampleEvent(domain, 3L, "e${i % 5}", "act", now - i * day)
        }
        val reading = EvoSampleRules.reading(domain, events)
        assertEquals(5, reading.events)
        assertEquals(EvoSampleVerdict.INSUFFICIENT, reading.verdict)
    }

    @Test
    fun `判据⑤_517的深度读侧口径不变_没账仍是浅档`() {
        assertEquals(
            "没深度账 ⇒ 浅档（517 判据②），589 不碰这条路",
            EvolutionDepth.SHALLOW,
            EvolutionDepth.DEFAULT,
        )
    }
}
