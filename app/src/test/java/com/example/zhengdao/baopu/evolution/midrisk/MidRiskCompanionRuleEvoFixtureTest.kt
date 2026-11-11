// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 B · **相处规则域夹具**（判据①②③④的域内走法，盘上真账）：
//   一次用户点名纠正（550 的判档真跑一遍：工作档 → 显式学习词 ⇒ 纠正）＝候选 → 缺省灰度放行 →
//   候选落一行 RunLog（路线②，550/482 同款"只产不启用"）→ 一条观察＋一条样本（对话域轮次锚）→
//   样本够格扫档自动转全开 → 一键回灰；
//   样本不足 ⇒ 不许转正；红线假动作 ⇒ 共用门拒并记账；档位关闭 ⇒ 暂缓不产出；
//   **未转正不参与判档**（转正也不参与——判档词表是判定常量，回流没有路）。
//
// 走的全是既有机制：判档＝550 的 CompanionJudge（生产路径直跑）、门＝568-A 的 LowRiskGate、
// 梯子＝473、观察账＝506、样本门＝589——本测试一条新判不写。
// BaopuRuntime 的装配（sink 注入）在 JVM 起不全：夹具按装配层同款注入，接线由源级门禁 G7/G8 钉。
package com.example.zhengdao.baopu.evolution.midrisk

import com.example.zhengdao.baopu.CompanionJudge
import com.example.zhengdao.baopu.CompanionMode
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.MediatorWiring
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.EvoRedLine
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MidRiskCompanionRuleEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.COMPANION_RULE

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568b-cmp-")
        memory = File(sandbox, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
        EvoSampleWiring.install(sandbox)
        MediatorWiring.resetForTest()
        // 装配层同款注入（BaopuRuntime.wireGovernanceState 的 JVM 版）。
        MediatorWiring.companionRuleSink = { prev, corrected, turns, sessionId, atMs ->
            MidRiskEvo.onCompanionCorrection(prev, corrected, turns, sessionId, atMs)
        }
    }

    @After
    fun tearDown() {
        MediatorWiring.companionRuleSink = null
        MediatorWiring.resetForTest()
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun observationRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    /**
     * 生产同款两轮（BaopuRuntime 装配后的真实路径）：第一轮显式点名工作（词表直判，不赌
     * 任务类型那一级），第二轮显式点名学习 ⇒ 550 的纠正识别真触发 ⇒ 候选出口真调中风险门。
     * 每对用独立会话（锚＝轮次：turn:<session>:2，589 拍板①）。
     */
    private fun oneCorrection(sessionId: Long, atMs: Long) {
        MediatorWiring.onUserTurn("简单说，直接给结论", "", sessionId, atMs)
        MediatorWiring.onUserTurn("教我一下这块", "", sessionId, atMs + 60_000L)
    }

    // ── 判据①：候选→灰度→转正（一次纠正＝一条候选＝一条观察＋一条样本） ──────

    @Test
    fun `夹具①_纠正真触发候选_缺省灰度放行_样本够格扫档转全开`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        // 20 次独立纠正（每对独立会话＝独立轮次锚）、间隔 9 小时 ⇒ 池里 20 次／≥7 天窗；
        // 观察同刻落 ⇒ 473 两窗各 ≥10 条 ok（介入率 0%）。
        repeat(20) { i -> oneCorrection(100L + i, now + i * 9L * 3_600_000L) }
        assertEquals("每次纠正一条观察", 20, observationRows().size)
        assertTrue("候选全出成 ⇒ 全 ok", observationRows().all { it.after == "ok" })
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("锚类型＝对话轮次", "conv_turn", reading.anchorKind)
        assertEquals("20 次独立事件", 20, reading.events)
        assertTrue("窗口 ≥7 天（589 的 PROMOTE_DAYS）", reading.days >= 7)

        MidRiskEvo.sweepOnSettle(now + 20 * 9L * 3_600_000L)
        assertEquals("样本门放行＋梯子判达标 ⇒ 转正", CanaryBand.FULL, band())
    }

    // ── 判据②：转正后可一键回滚（域无自有值，档位回灰即全量回滚） ────────────

    @Test
    fun `夹具②_转正后一键回灰_候选停产但不丢历史`() {
        repeat(3) { i -> oneCorrection(200L + i, now + i * 3_600_000L) }
        assertEquals(3, observationRows().size)
        MidRiskEvo.sweepOnSettle(now + 3 * 3_600_000L)
        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 4L * 3_600_000L)
        assertEquals("一键回灰（判据②）", CanaryBand.CANARY, after)
        // 回灰后纠正照常发生，但候选停产（档位关闭才收权——灰度只是收窄到"记账照走"）。
        MediatorWiring.onUserTurn("简单说，直接给结论", "", 210L, now + 5L * 3_600_000L)
        MediatorWiring.onUserTurn("教我一下这块", "", 210L, now + 5L * 3_600_000L + 60_000L)
        assertEquals("回灰后候选照常产出（灰度档放行）", 4, observationRows().size)
    }

    // ── 判据③：直接生效（跳过灰度）⇒ 门禁红；档位关闭 ⇒ 暂缓不产出 ─────────

    @Test
    fun `判据③_档位被拨关_纠正不产候选_历史保留`() {
        assertTrue(oneCorrectionPair())
        assertEquals(1, observationRows().size)
        DecisionLedger.record(
            com.example.zhengdao.baopu.DecisionDraft(
                kind = com.example.zhengdao.baopu.DecisionKind.TOOL_GRANT,
                actor = com.example.zhengdao.baopu.DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = now + 7_200_000L,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        MediatorWiring.onUserTurn("简单说，直接给结论", "", 301L, now + 3L * 3_600_000L)
        MediatorWiring.onUserTurn("教我一下这块", "", 301L, now + 3L * 3_600_000L + 60_000L)
        assertEquals("关闭档 ⇒ 暂缓：纠正不产候选（没观察）", 1, observationRows().size)
    }

    private fun oneCorrectionPair(): Boolean {
        MediatorWiring.onUserTurn("简单说，直接给结论", "", 300L, now)
        MediatorWiring.onUserTurn("教我一下这块", "", 300L, now + 60_000L)
        return true
    }

    // ── 判据④：样本不足不许转正＋红线负例＋未转正不参与判档 ──────────────────

    @Test
    fun `判据④a_样本不足_梯子达标也不许转正_样本门拒并记账`() {
        repeat(10) { i -> oneCorrection(400L + i, now + i * 12L * 3_600_000L) }
        MidRiskEvo.sweepOnSettle(now + 10 * 12L * 3_600_000L)
        assertEquals("样本门拒了升档：档位停在灰度", CanaryBand.CANARY, band())
        val refusal = rows().filter {
            it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("只够灰度")
        }
        assertEquals("样本门的拒行在账上（evo-samples 前缀）", 1, refusal.size)
    }

    @Test
    fun `判据④b_红线假动作_打判档源码_共用门拒并记账`() {
        val fake = EvoCandidate(
            domain = cap.wire,
            target = "policy/src/main/java/com/example/zhengdao/baopu/CompanionMode.kt",
            body = "把学习档的显式词表改成自动加词",
        )
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("APP 源码红线要拒：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals(1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.APP_SOURCE.cn))
    }

    @Test
    fun `判据④c_未转正不参与判档_转正也不参与_判档字面不变`() {
        // 两条基线：判档在"有候选/转正档"之前的行为。
        val workVerdict = CompanionJudge.judge("简单说结论就行")
        val studyVerdict = CompanionJudge.judge("这个原理是什么")
        assertEquals("工作词照判工作", CompanionMode.WORK, workVerdict.mode)
        assertTrue("措辞级学习照判学习（explicit=false）", studyVerdict.mode == CompanionMode.STUDY && !studyVerdict.explicit)

        // 候选出过 + 档位拨到全开（比"未转正"更严的条件下）⇒ 判档逐字节不变。
        assertTrue(oneCorrectionPair())
        DecisionLedger.record(
            com.example.zhengdao.baopu.DecisionDraft(
                kind = com.example.zhengdao.baopu.DecisionKind.TOOL_GRANT,
                actor = com.example.zhengdao.baopu.DecisionActor.USER,
                reason = "灰度档位 灰度→全开（夹具：人工拨到全开）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:full:human",
                before = "canary",
                after = "full",
                atMs = now + 7_200_000L,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        assertEquals(
            "判档在转正档下不变（actual=${CompanionJudge.judge("简单说结论就行")}）",
            CompanionMode.WORK,
            CompanionJudge.judge("简单说结论就行").mode,
        )
        assertEquals(
            "调解器状态照常跟判档走（候选不改变判档行为）",
            CompanionMode.STUDY,
            MediatorWiring.currentCompanion(),
        )
        val studyAfter = CompanionJudge.judge("这个原理是什么")
        assertTrue(studyAfter.mode == CompanionMode.STUDY && !studyAfter.explicit)
        assertEquals("判档词表是判定常量：候选与档位都进不去", workVerdict, CompanionJudge.judge("简单说结论就行"))
    }

    // ── 589 锚口径：同一轮里的纠正回声只计 1 次 ─────────────────────────────

    @Test
    fun `锚口径_同一轮的纠正回声_样本只计1次`() {
        // 同一会话三个显式轮：工作 → 学习（纠正①，锚 turn:500:2）→ 工作（纠正②，锚 turn:500:3）
        // ——两次纠正轮次不同 ⇒ 2 个独立事件；两次间隔 11 分钟（589 防刷 10 分钟碰不着）。
        MediatorWiring.onUserTurn("简单说，直接给结论", "", 500L, now)
        MediatorWiring.onUserTurn("教我一下这块", "", 500L, now + 60_000L)
        MediatorWiring.onUserTurn("别啰嗦，直接给", "", 500L, now + 12L * 60_000L)
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("两轮两次纠正＝2 个独立事件", 2, reading.events)
        assertEquals("防刷没碰着", 0, reading.droppedBurst)
    }
}
