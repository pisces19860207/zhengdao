// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · 四域共用的**档位链夹具**（判据①②的能力级走法，盘上真账）：
//   候选产生观察 → 观察够数达标 ⇒ 扫档自动转全开（判据①"转正"）⇒ 一键回滚（判据②）；
//   顺带钉住 495 的口径（没数不动档）与 473 的两条牙（未达标不许转、坏消息自动回灰）。
//
// 每条都走 [LowRiskEvo]（真落账）⇒ 再 [DecisionLedger.read]（真读回）⇒ 才断言，
// 与 ConsequenceWiringTest 同款纪律：只有碰盘才算数。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.CanaryObservation
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LowRiskEvoSweepTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

    private val cap: CanaryCapability = CanaryCapability.REFLECTION_AUTO_EDIT

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568a-sweep-")
        memory = File(sandbox, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
    }

    @After
    fun tearDown() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun bandRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    /** 最近 7 天内落 [n] 条干净观察（7 天窗与 14 天窗同时够数 ⇒ 两轮都达标）。 */
    private fun observeClean(n: Int, from: Long = now - 6L * DAY) {
        repeat(n) { i -> LowRiskEvo.observeRun(cap, ok = true, atMs = from + i * 60_000L) }
    }

    // ── 判据①：候选→灰度→转正（达标自动转全开） ────────────────────────────

    @Test
    fun `夹具①_缺省灰度_观察够数_扫档自动转全开`() {
        assertEquals("登记表缺省档＝灰度（生产行为不变的根）", CanaryBand.CANARY, band())
        observeClean(CanaryLadder.MIN_RUNS)
        LowRiskEvo.sweepOnSettle(now)
        assertEquals("连续两轮达标（14 天整窗＋7 天近窗）⇒ 转全开", CanaryBand.FULL, band())
        val promote = bandRows().maxByOrNull { it.atMs }!!
        assertEquals("转正落账 actor=auto（不需要人点）", DecisionActor.AUTO, promote.actor)
        assertTrue("依据是那一轮读数：${promote.reason}", promote.reason.contains("条"))
    }

    @Test
    fun `夹具①_一轮达标不转_两轮才转（拿运气当依据不行）`() {
        observeClean(CanaryLadder.MIN_RUNS)
        ConsequenceWiring.decideCanaryBand(cap.wire, now, passedRounds = 1)
        assertEquals(CanaryBand.CANARY, band())
    }

    // ── 判据②：转正后可一键回滚（人按的那颗） ──────────────────────────────

    @Test
    fun `夹具②_转全开后一键回灰_档位行带actor人_账上看得见`() {
        observeClean(CanaryLadder.MIN_RUNS)
        LowRiskEvo.sweepOnSettle(now)
        assertEquals(CanaryBand.FULL, band())
        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 60_000L)
        assertEquals("一键回滚落回灰度", CanaryBand.CANARY, after)
        val revert = bandRows().maxByOrNull { it.atMs }!!
        assertEquals("回滚是人按的：actor=user", DecisionActor.USER, revert.actor)
        assertTrue("账行说人话：${revert.reason}", revert.reason.contains("灰度"))
        // 回滚真的管得住：之后域门读到的档就是灰度（档从账读，没有第二处真相）
        assertEquals(CanaryBand.CANARY, band())
    }

    // ── 495 口径：没数不动档（UNVERIFIABLE 不是失败，也不是通过） ────────────

    @Test
    fun `没数不动档_扫档不落任何档位行`() {
        LowRiskEvo.sweepOnSettle(now)
        assertEquals("缺省档不动", CanaryBand.CANARY, band())
        assertTrue("档位没动不落空变动（473 的牙）", bandRows().isEmpty())
    }

    @Test
    fun `样本不够MIN_RUNS_同样不动档`() {
        observeClean(CanaryLadder.MIN_RUNS - 1)
        LowRiskEvo.sweepOnSettle(now)
        assertEquals(CanaryBand.CANARY, band())
        assertTrue(bandRows().isEmpty())
    }

    // ── 473 的两条牙：未达标不许转；坏消息自动回灰 ──────────────────────────

    @Test
    fun `未达标不许转_一成失败率过不了全开线`() {
        repeat(CanaryLadder.MIN_RUNS - 1) { i -> LowRiskEvo.observeRun(cap, ok = true, atMs = now - 3 * DAY + i) }
        LowRiskEvo.observeRun(cap, ok = false, atMs = now - DAY)
        LowRiskEvo.sweepOnSettle(now)
        assertEquals(CanaryBand.CANARY, band())
        assertTrue(bandRows().isEmpty())
    }

    @Test
    fun `永久损失_自动回灰不许等下一轮`() {
        observeClean(CanaryLadder.MIN_RUNS)
        LowRiskEvo.sweepOnSettle(now)
        assertEquals(CanaryBand.FULL, band())
        LowRiskEvo.observeRun(cap, ok = false, atMs = now + 60_000L, permanentLoss = true)
        LowRiskEvo.sweepOnSettle(now + 120_000L)
        assertEquals("永久损失 ⇒ 立刻回灰", CanaryBand.CANARY, band())
    }

    // ── 两轮怎么数（账支撑，内存不攒状态） ──────────────────────────────────

    @Test
    fun `passedRounds_两窗都够数才是2_近窗不够只有1`() {
        observeClean(CanaryLadder.MIN_RUNS)
        assertEquals(2, LowRiskEvo.passedRoundsOf(cap.wire, now))
        // 样本全挪出 7 天窗 ⇒ 近窗没数 ⇒ 只剩整窗那一轮（生产公式在测试里逐字重放）
        val stale = List(CanaryLadder.MIN_RUNS) {
            com.example.zhengdao.baopu.consequence.CanarySample(
                atMs = now - (CanaryLadder.WINDOW_DAYS - 1) * DAY,
                ok = true,
                askedHuman = false,
            )
        }
        val windows = listOf(
            com.example.zhengdao.baopu.consequence.CanaryMetrics.of(stale, now),
            com.example.zhengdao.baopu.consequence.CanaryMetrics.of(stale, now, CanaryLadder.WINDOW_DAYS / 2),
        )
        assertEquals(1, windows.count { it.passedForFull })
    }

    // ── 判据③的行为面：档位被拨关 ⇒ 门只出 Hold（直接生效被拒） ────────────

    @Test
    fun `判据③_档位关闭_门出Hold_候选不执行`() {
        // W4 同款夹具：人拨的关闭行（refusalOf 只许人拨 OFF；夹具手摆行，不走判据层）
        DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = now,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        assertEquals(CanaryBand.OFF, band())
        val v = LowRiskEvo.gate(cap, cleanCandidate())
        assertTrue("直接生效被拒：$v", v is LowRiskVerdict.Hold)
    }

    @Test
    fun `观察账真的是四域自己的_别域样本不串档`() {
        observeClean(CanaryLadder.MIN_RUNS)
        ConsequenceWiring.observe(
            CanaryObservation(CanaryCapability.MEMORY_IMPORT.wire, ok = false, permanentLoss = true),
            now,
        )
        LowRiskEvo.sweepOnSettle(now)
        assertEquals("别域的坏账不压本域：本域照转", CanaryBand.FULL, band())
        assertEquals(
            "收编域只有一条坏样本 ⇒ 读数算不出 ⇒ 不动档（缺省灰度，无档位行）",
            CanaryBand.CANARY,
            ConsequenceWiring.currentCanaryBand(CanaryCapability.MEMORY_IMPORT.wire),
        )
    }

    private fun cleanCandidate() = EvoCandidate(
        domain = cap.wire,
        target = "skill:project/夹具流程",
        body = "1. 打开；2. 点签到。",
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
