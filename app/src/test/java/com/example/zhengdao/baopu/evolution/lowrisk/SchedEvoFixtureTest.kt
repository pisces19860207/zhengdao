// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · **调度降频域夹具**（判据①②③④的域内走法，盘上真账）：
//   一趟**自动**抽检（暗卫）＝候选 → 缺省灰度放行（既有行为不变）→ 真跑 →
//   结局一条观察账 → 观察够数扫档自动转全开 → 一键回灰；
//   档位关闭 ⇒ 自动档不出门（延后行照写，人工入口不受限）；红线假动作 ⇒ 拒并记账。
//
// 走的全是既有机制：机会条件/负载/冷却判定＝511/479 的 Opportunistic＋MaintBrake
// （顺序与判据一字未动）、档位与梯子＝473、观察账＝506 的 canary-run 行——本测试一条新判不写。
// runDue 是 Context 胶水（本仓无 Robolectric）：夹具走生产同款三步（gate → 跑 → observe），
// 接线顺序与"急停盖过档位、档位管自动不管人手"由源级门禁 G6 钉。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
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

class SchedEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.NOCTURNAL_PATROL

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568a-sched-")
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

    private fun observationRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    private fun candidate() = EvoCandidate(
        domain = cap.wire,
        target = "nightwatch:nightwatch",
    )

    /** 生产同款三步（NocturnalAuto.runDue 的顺序；急停/机会条件在夹具外，接线由 G6 钉）。 */
    private fun gateThenRun(ok: Boolean, atMs: Long): LowRiskVerdict {
        val v = LowRiskEvo.gate(cap, candidate())
        if (v !is LowRiskVerdict.Allow) {
            if (v is LowRiskVerdict.Refuse) {
                LowRiskEvo.recordRefusal(cap, v.redLine, v.note, candidate(), atMs)
            }
            return v
        }
        LowRiskEvo.observeRun(cap, ok = ok, atMs = atMs)
        return v
    }

    // ── 判据①：候选→灰度→转正（一趟自动抽检＝一条观察，够数扫档转全开） ────

    @Test
    fun `夹具①_缺省灰度放行_逐轮观察_够数扫档转全开_一键回灰`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        repeat(CanaryLadder.MIN_RUNS) { i ->
            val v = gateThenRun(ok = true, atMs = now + i * 60_000L)
            assertTrue("第 ${i + 1} 轮放行：$v", v is LowRiskVerdict.Allow)
        }
        assertEquals(CanaryLadder.MIN_RUNS, observationRows().size)
        LowRiskEvo.sweepOnSettle(now + CanaryLadder.MIN_RUNS * 60_000L)
        assertEquals("转正", CanaryBand.FULL, band())
        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 3_600_000L)
        assertEquals("一键回滚（判据②）", CanaryBand.CANARY, after)
    }

    @Test
    fun `跑挂的轮次记fail观察_指标过不了全开线`() {
        repeat(CanaryLadder.MIN_RUNS - 1) { i -> gateThenRun(ok = true, atMs = now + i * 60_000L) }
        gateThenRun(ok = false, atMs = now + 10 * 60_000L)
        assertTrue("fail 行在账上", observationRows().any { it.after == "fail" })
        LowRiskEvo.sweepOnSettle(now + 20 * 60_000L)
        assertEquals("一成失败率 ⇒ 未达标不转", CanaryBand.CANARY, band())
        assertTrue("未达标不落档位行", rows().none { it.evidence.startsWith("${ConsequenceEvidence.CANARY}${cap.wire}:") })
    }

    // ── 判据③：档位关闭 ⇒ 自动档不出门（人工入口不受限） ───────────────────

    @Test
    fun `判据③_档位被拨关_自动档不出门`() {
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
        val v = gateThenRun(ok = true, atMs = now)
        assertTrue("关闭档只出 Hold：$v", v is LowRiskVerdict.Hold)
        assertEquals("没跑就没观察账", 0, observationRows().size)
    }

    // ── 判据④：红线负例（假动作 ⇒ 拒并记账） ────────────────────────────────

    @Test
    fun `判据④_调度候选假动作打红线_拒并记账`() {
        // R1：调度候选声称要改引擎源码（自动重排周期作业的实现文件）
        val fake1 = EvoCandidate(domain = cap.wire, target = "app/src/main/java/com/example/zhengdao/baopu/inspect/NocturnalAuto.kt")
        assertTrue(LowRiskEvo.gate(cap, fake1) is LowRiskVerdict.Refuse)
        // R2：调度候选想给停机闸开"放行窗口"
        val fake2 = EvoCandidate(domain = cap.wire, target = "nightwatch:nightwatch", body = "绕过 HaltGate 把急停位清掉")
        assertTrue(LowRiskEvo.gate(cap, fake2) is LowRiskVerdict.Refuse)
        // R3：调度候选想给不可逆动作排班（凌晨自动群发）
        val fake3 = EvoCandidate(domain = cap.wire, target = "nightwatch:nightwatch", body = "凌晨 3 点自动群发短信")
        assertTrue(LowRiskEvo.gate(cap, fake3) is LowRiskVerdict.Refuse)
        // 三条各记一行（拒并记账走既有参谋账）
        listOf(
            EvoRedLine.APP_SOURCE to fake1,
            EvoRedLine.KERNEL to fake2,
            EvoRedLine.HEAD_THREE to fake3,
        ).forEach { (line, fake) ->
            val v = LowRiskEvo.gate(cap, fake) as LowRiskVerdict.Refuse
            LowRiskEvo.recordRefusal(cap, v.redLine, v.note, fake, now)
        }
        val refusals = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals("三条红线各一行", 3, refusals.size)
        listOf(EvoRedLine.APP_SOURCE, EvoRedLine.KERNEL, EvoRedLine.HEAD_THREE).forEach { line ->
            assertTrue("红线「${line.cn}」的拒行要在账上", refusals.any { it.reason.contains(line.cn) })
        }
        assertFalse("假动作一个都不真跑", observationRows().any { it.after == "ok" })
    }
}
