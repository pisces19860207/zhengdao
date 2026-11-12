// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 B · **判定阈值域夹具**（判据①②③④的域内走法，盘上真账）：
//   一次组闸未批准/同意＝候选 → 缺省灰度放行 → 调整行真落账 → 一条观察＋一条样本（589 同口）→
//   样本够格（589：≥20 次且 ≥7 天）扫档自动转全开 → 一键回滚（档位回灰＋阈值行退回原值）；
//   样本不足 ⇒ 不许转正（589 样本门拒并记账）；出区间 ⇒ 拒并记账；档位关闭 ⇒ 暂缓不执行；
//   红线假动作 ⇒ 共用门拒并记账。
//
// 走的全是既有机制：门＝568-A 的 LowRiskGate（红线→档位→深度）、梯子＝473、观察账＝506、
// 样本门＝589、阈值行＝208 的 tool_grant（threshold: 族，照 517 depth: 先例）——本测试一条新判不写。
// ToolGroupGate 是 Context 胶水层不可 JVM 直测（本仓无 Robolectric）：夹具走生产同款三步
// （effectiveThreshold → proposeThresholdShift → 生效值读回），接线顺序由源级门禁钉（MidRiskEvoGateTest）。
package com.example.zhengdao.baopu.evolution.midrisk

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionRollbackHandler
import com.example.zhengdao.baopu.DecisionRollbacks
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.EvoRedLine
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.baopu.policy.Policy
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MidRiskThresholdEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.JUDGMENT_THRESHOLD

    /** 政策表原值（97 批的组闸默认 N；区间 1–10 是 122 拍板）。 */
    private val policyValue: Int = Policy.GROUP_REASK_THRESHOLD

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568b-thr-")
        memory = File(sandbox, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
        EvoSampleWiring.install(sandbox)
    }

    @After
    fun tearDown() {
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun observationRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }

    private fun thresholdRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.THRESHOLD}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    private fun effective(): Int = MidRiskEvo.effectiveReaskThreshold(policyValue)

    /** 生产同款一步（ToolGroupGate 的提议半边；接线由源级门禁 G1 钉）。 */
    private fun propose(base: Int, delta: Int, turn: Int, atMs: Long): Boolean =
        MidRiskEvo.proposeThresholdShift(
            ThresholdProposal(
                base = base,
                delta = delta,
                why = "夹具：未批准",
                sessionId = 42L,
                turnAnchor = "turn:$turn",
            ),
            atMs,
        )

    /**
     * 区间内往返走 N 轮（顶了就往回、到底了就往上）——生产自调本来就是双向的；
     * 每轮一个轮次锚（独立事件）、间隔 ≥1 小时（589 防刷 10 分钟碰不着）。
     */
    private fun zigzag(rounds: Int, gapHours: Long): List<Boolean> =
        (0 until rounds).map { i ->
            val at = now + i * gapHours * 3_600_000L
            val base = effective()
            val delta = if (base >= Policy.GROUP_REASK_MAX) -1 else 1
            propose(base, delta, turn = i + 1, atMs = at)
        }

    // ── 判据①：候选→灰度→转正（一次提议＝一条观察＋一条样本，够格扫档转全开） ──

    @Test
    fun `夹具①_缺省灰度放行_调整行真落账_样本够格扫档转全开`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        assertEquals("没调整行 ⇒ 生效值＝政策表原值", policyValue, effective())
        // 20 次独立事件（每轮一个轮次锚）、间隔 9 小时 ⇒ 池里 20 次／≥7 天窗（589 的 PROMOTE 两条线都够）；
        // 观察同刻落 ⇒ 14 天整窗与 7 天近窗各有 ≥10 条 ok（473 的 MIN_RUNS 两窗都够、介入率 0%）。
        val outcomes = zigzag(rounds = 20, gapHours = 9L)
        assertTrue("20 轮全放行并落调整行", outcomes.all { it })
        assertEquals("每次提议一条观察", 20, observationRows().size)
        assertTrue("提议全成功 ⇒ 全 ok", observationRows().all { it.after == "ok" })
        assertEquals("每次提议一条调整行", 20, thresholdRows().size)
        assertTrue(
            "生效值始终在已定区间内（自调只在区间内挪）",
            effective() in Policy.GROUP_REASK_MIN..Policy.GROUP_REASK_MAX,
        )

        MidRiskEvo.sweepOnSettle(now + 20 * 9L * 3_600_000L)
        assertEquals("样本门放行（589 够格）＋梯子判达标 ⇒ 转正", CanaryBand.FULL, band())
    }

    // ── 判据②：转正后可一键回滚（档位回灰＋阈值行退回，走决策中心同款机制） ────

    @Test
    fun `夹具②_转正后一键回滚_档位回灰_阈值行退回`() {
        // 先走完夹具①同一条链到转正（灰度→FULL），再验两个半边的回滚。
        assertTrue(zigzag(rounds = 20, gapHours = 9L).all { it })
        MidRiskEvo.sweepOnSettle(now + 20 * 9L * 3_600_000L)
        assertEquals(CanaryBand.FULL, band())

        val latest = thresholdRows().maxByOrNull { it.atMs }!!
        val rolledBack = DecisionRollbacks.run(
            target = latest,
            root = memory,
            handler = DecisionRollbackHandler { r ->
                MidRiskEvo.revertThreshold(
                    r.before.toIntOrNull() ?: return@DecisionRollbackHandler false,
                    r.after.toIntOrNull(),
                    now + 21 * 9L * 3_600_000L,
                )
            },
            atMs = now + 21 * 9L * 3_600_000L,
        )
        assertTrue("回滚成（决策中心同款：rollback_of 照留）", rolledBack)
        assertEquals("阈值半边：生效值退回被回滚那行的 before", latest.before.toInt(), effective())
        assertTrue("回滚行在账上", rows().any { it.kind == DecisionKind.ROLLBACK_OF })

        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 22 * 9L * 3_600_000L)
        assertEquals("档位半边：一键回灰（判据②）", CanaryBand.CANARY, after)
    }

    @Test
    fun `夹具②负例_盘上已换新值再回滚旧行_老实话false`() {
        assertTrue(propose(effective(), 1, turn = 1, atMs = now))
        val stale = thresholdRows().single()
        assertTrue(propose(effective(), 1, turn = 2, atMs = now + 3_600_000L))
        assertFalse(
            "旧行已不是生效值 ⇒ 不盲退",
            MidRiskEvo.revertThreshold(
                stale.before.toIntOrNull()!!,
                stale.after.toIntOrNull(),
                now + 7_200_000L,
            ),
        )
    }

    // ── 判据③：直接生效（跳过灰度）⇒ 门禁红；档位关闭 ⇒ 暂缓不执行 ─────────

    @Test
    fun `判据③_档位被拨关_自调不出门_生效值回政策原值`() {
        repeat(2) { i ->
            assertTrue(propose(effective(), 1, turn = i + 1, atMs = now + i * 3_600_000L))
        }
        assertEquals(policyValue + 2, effective())
        DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = now + 7_200_000L,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        val ok = propose(effective(), 1, turn = 3, atMs = now + 3L * 3_600_000L)
        assertFalse("关闭档 ⇒ 暂缓，不出门", ok)
        assertEquals("关闭档 ⇒ 收权：生效值回政策表原值（候选保留在账上）", policyValue, effective())
        assertEquals("暂缓不落调整行", 2, thresholdRows().size)
        assertEquals("暂缓不算一次执行（没观察）", 2, observationRows().size)
    }

    // ── 判据④：样本不足不许转正（589 样本门拒并记账）＋出区间拒并记账＋红线负例 ──

    @Test
    fun `判据④a_样本不足_梯子达标也不许转正_样本门拒并记账`() {
        // 10 次独立事件、间隔 12 小时 ⇒ 473 两窗都够（≥10）但 589 只够灰度（10 < 20）。
        val outcomes = zigzag(rounds = 10, gapHours = 12L)
        assertTrue("10 轮全放行", outcomes.all { it })
        MidRiskEvo.sweepOnSettle(now + 10 * 12L * 3_600_000L)
        assertEquals("样本门拒了升档：档位停在灰度", CanaryBand.CANARY, band())
        val refusal = rows().filter {
            it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("只够灰度")
        }
        assertEquals("样本门的拒行在账上（evo-samples 前缀）", 1, refusal.size)
    }

    @Test
    fun `判据④b_出区间_拒并记账_不落调整行`() {
        val top = Policy.GROUP_REASK_MAX
        assertFalse("到顶再加 ⇒ 出区间，拒", propose(base = top, delta = 1, turn = 1, atMs = now))
        assertEquals("没落调整行", 0, thresholdRows().size)
        assertEquals("拒了不算一次执行（没观察）", 0, observationRows().size)
        fun outOfIntervalRows() = rows().filter {
            it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("超出已定区间")
        }
        assertEquals("拒行在账上（要人批的那一类）", 1, outOfIntervalRows().size)
        val bottom = Policy.GROUP_REASK_MIN
        assertFalse("到底再减 ⇒ 出区间，拒", propose(base = bottom, delta = -1, turn = 2, atMs = now + 3_600_000L))
        assertEquals("两个方向各一条拒行", 2, outOfIntervalRows().size)
    }

    @Test
    fun `判据⑨_后果链没装配_不自调不写账_闸行为与接线前一致`() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        assertFalse("没装配 ⇒ 不提议不落行", propose(base = 3, delta = 1, turn = 1, atMs = now))
        assertEquals("没装配 ⇒ 生效值＝政策表原值（闸行为与接线前逐字节一致）", policyValue, effective())
    }

    /**
     * 半装配现场（568-B 收尾趟补）：只摘后果链、账本留在盘上——这才是 installed() 守卫能区分的一态。
     * 上一条判据⑨ 把两把装口一起摘（账本空 ⇒ currentThreshold 恒 null），摘不摘守卫都绿，钉不住守卫。
     */
    @Test
    fun `判据⑨b_半装配_账还在后果链摘了_生效值不跟账跑_不落新行`() {
        repeat(2) { i ->
            assertTrue(propose(effective(), 1, turn = i + 1, atMs = now + i * 3_600_000L))
        }
        assertEquals("装配齐 ⇒ 生效值跟着账走", policyValue + 2, effective())
        assertEquals("半装配现场先让账上有料", 2, thresholdRows().size)
        ConsequenceWiring.uninstallForTest()
        assertEquals("链没装 ⇒ 生效值回政策表原值（不许读还留在盘上的账）", policyValue, effective())
        assertFalse("链没装 ⇒ 不自调", propose(effective(), 1, turn = 3, atMs = now + 7_200_000L))
        assertEquals("链没装 ⇒ 账上不多写调整行", 2, thresholdRows().size)
        assertEquals("链没装 ⇒ 账上不多写观察行", 2, observationRows().size)
    }

    @Test
    fun `判据④c_红线假动作_共用门拒并记账`() {
        val fake = EvoCandidate(
            domain = cap.wire,
            target = "app/src/main/java/com/example/zhengdao/baopu/agent/ToolGroupGate.kt",
            body = "把组闸限流改成永不再问",
        )
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("APP 源码红线要拒：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals(1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.APP_SOURCE.cn))
        assertEquals("红线拒不产生任何阈值行", 0, thresholdRows().size)
    }

    // ── 589 锚口径：对话域 turn——同一轮多次事件只计 1 次 ─────────────────────

    @Test
    fun `锚口径_同一轮连提两次_样本只计1次_观察照落`() {
        val at = now
        assertTrue(propose(effective(), 1, turn = 7, atMs = at))
        assertTrue("同一轮的第二次提议照常判", propose(effective(), 1, turn = 7, atMs = at + 60_000L))
        assertEquals("观察行两条（473 面不去重）", 2, observationRows().size)
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("锚类型＝对话轮次（589 拍板①）", "conv_turn", reading.anchorKind)
        assertEquals("同锚合并：样本只计 1", 1, reading.events)
        assertEquals("被合并的那条留痕", 1, reading.droppedAnchor)
    }

    @Test
    fun `锚口径_没有轮次_兜底按时刻自记身份_不硬造轮次号`() {
        assertTrue(
            MidRiskEvo.proposeThresholdShift(
                ThresholdProposal(base = effective(), delta = 1, why = "夹具：无轮次", sessionId = 0L, turnAnchor = ""),
                now,
            ),
        )
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("兜底锚不合并任何东西：计 1", 1, reading.events)
    }
}
