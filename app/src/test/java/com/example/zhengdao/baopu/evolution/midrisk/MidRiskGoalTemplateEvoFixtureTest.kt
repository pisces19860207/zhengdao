// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 B · **目标模板域夹具**（判据①②③④的域内走法，盘上真账）：
//   一次目标真收口（482 的 settle 真跑：判据未兑现 ⇒ FAILED）＝482 提炼候选（一字未动）→
//   候选出口交中风险门 → 缺省灰度记上（观察＋样本，1 小时桶锚）→ 样本够格扫档转全开 → 一键回灰；
//   样本不足 ⇒ 不许转正；红线假动作 ⇒ 共用门拒并记账；档位关闭 ⇒ 暂缓不记（482 的日志照落）；
//   **未转正不参与判据**（转正也不参与——判据写法本体一字不动，回流没有路）。
//
// 走的全是既有机制：提炼＝482 的 templateCandidate（真收口才提炼）、门＝568-A 的 LowRiskGate、
// 梯子＝473、观察账＝506、样本门＝589——本测试一条新判不写。
// BaopuRuntime 的装配（sink 注入）在 JVM 起不全：夹具按装配层同款注入，接线由源级门禁 G9/G10 钉。
package com.example.zhengdao.baopu.evolution.midrisk

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.plan.PlanPledgeRules
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.ConsequenceEvidence
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.EvoRedLine
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring
import com.example.zhengdao.baopu.plan.GoalBoard
import com.example.zhengdao.baopu.plan.GoalRules
import com.example.zhengdao.baopu.plan.GoalStore
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidRiskGoalTemplateEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.GOAL_TEMPLATE

    private lateinit var sandbox: File
    private lateinit var memory: File

    @org.junit.Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568b-goal-")
        memory = File(sandbox, "抱朴记忆")
        DecisionLedger.install(memory)
        ConsequenceWiring.install(sandbox, now) { entropy }
        EvoSampleWiring.install(sandbox)
        // 装配层同款注入（BaopuRuntime.wireGoalCloseout 的 JVM 版）。
        GoalBoard.attachTemplateSink { candidate, atMs ->
            MidRiskEvo.onGoalTemplate(candidate, atMs)
        }
    }

    @After
    fun tearDown() {
        GoalBoard.resetForTest()
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(sandbox)
    }

    private fun rows(): List<DecisionRecord> = DecisionLedger.read(memory)

    private fun observationRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.CANARY_RUN}${cap.wire}:") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    /** 与 GoalModeTest 同款形状（解析能过 ⇒ 后面判的都是真形状）。 */
    private fun args(): JSONObject = JSONObject(
        """{"goal":{"sentence":"把这批补丁做完并出报告","criteria":[{"text":"报告在","kind":"file","path":"产物/报告.md"}],"boundaries":[],"budget":{"maxSteps":5}}}""",
    )

    private fun pledge(): PlanPledgeRules.PledgeSnapshot {
        val items = (1..5).map { PlanRules.PlanItem("第 $it 步", PlanRules.STATUS_TODO) }
        return PlanPledgeRules.snapshotOf(items, (1..5).map { "第 $it 步的验收方式" }, 1, "把这批补丁做完", 900L, 7L)
    }

    /**
     * 生产同款一次收尾（BaopuRuntime 装配后的真实路径）：立目标 → 判据没兑现 ⇒ FAILED ⇒
     * 482 提炼候选（真收口才提炼）→ 候选出口交中风险门。每次独立工作区（.goal.json 各自落）。
     */
    private lateinit var lastWs: File

    private fun oneFailedGoal(atMs: Long, tag: Int = 0) {
        GoalBoard.resetForTest()
        GoalBoard.attachTemplateSink { candidate, at -> MidRiskEvo.onGoalTemplate(candidate, at) }
        lastWs = File(sandbox, "ws-$atMs").apply { mkdirs() }
        GoalBoard.attach(lastWs)
        val goalArgs = JSONObject(
            """{"goal":{"sentence":"把这批补丁做完并出报告（第 $tag 批）","criteria":[{"text":"报告在","kind":"file","path":"产物/报告.md"}],"boundaries":[],"budget":{"maxSteps":5}}}""",
        )
        val draft = (GoalRules.parse(goalArgs, hasPledge = true) as GoalRules.GoalParse.Ok).draft
        assertNotNull("军令状没挂立不起来（482 判据）", GoalBoard.freeze(draft, pledge(), atMs, 7L))
        GoalBoard.settle(0, 5, { false }, atMs)
    }

    // ── 判据①：候选→灰度→转正（一次真收口＝一条候选＝一条观察＋一条样本） ──────

    @Test
    fun `夹具①_真收口提炼候选_缺省灰度记上_样本够格扫档转全开`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        oneFailedGoal(now)
        assertEquals("一次收口一条观察", 1, observationRows().size)
        assertEquals("候选记上 ⇒ ok", "ok", observationRows().single().after)
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("锚类型＝后台一小时桶（589 拍板②）", "hourly_bucket", reading.anchorKind)
        // 20 次独立收口（每小时一个桶）、间隔 9 小时 ⇒ 池里 20 次／≥7 天窗；观察同刻落 ⇒ 473 两窗够。
        repeat(19) { i -> oneFailedGoal(now + (i + 1) * 9L * 3_600_000L, tag = i + 1) }
        assertEquals("20 次收口 20 条观察", 20, observationRows().size)
        assertTrue("窗口 ≥7 天（589 的 PROMOTE_DAYS）", EvoSampleWiring.reading(cap.wire).days >= 7)

        MidRiskEvo.sweepOnSettle(now + 20 * 9L * 3_600_000L)
        assertEquals("样本门放行＋梯子判达标 ⇒ 转正", CanaryBand.FULL, band())
        assertTrue("482 的账照旧落盘（.goal.json 一字未动）", GoalStore.fileOf(lastWs)!!.isFile)
    }

    // ── 判据②：转正后可一键回灰（域无自有值，档位回灰即全量回滚） ──────────────

    @Test
    fun `夹具②_转正后一键回灰_回灰后候选照常记上`() {
        oneFailedGoal(now)
        MidRiskEvo.sweepOnSettle(now)
        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 3_600_000L)
        assertEquals("一键回灰（判据②）", CanaryBand.CANARY, after)
        oneFailedGoal(now + 2L * 3_600_000L, tag = 9)
        assertEquals("回灰后收口照常记上（灰度档放行）", 2, observationRows().size)
    }

    // ── 判据③：直接生效（跳过灰度）⇒ 门禁红；档位关闭 ⇒ 暂缓不记 ─────────────

    @Test
    fun `判据③_档位被拨关_收口不记候选_482日志照落`() {
        oneFailedGoal(now)
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
        oneFailedGoal(now + 3L * 3_600_000L, tag = 8)
        assertEquals("关闭档 ⇒ 暂缓：收口不记候选（没观察）", 1, observationRows().size)
        assertEquals("482 的账没被碰（.goal.json 照写）", GoalRules.GoalState.FAILED, GoalStore.readGoal(lastWs)!!.state)
    }

    // ── 判据④：样本不足不许转正＋红线负例＋未转正不参与判据 ──────────────────

    @Test
    fun `判据④a_样本不足_梯子达标也不许转正_样本门拒并记账`() {
        repeat(10) { i -> oneFailedGoal(now + i * 12L * 3_600_000L, tag = i) }
        MidRiskEvo.sweepOnSettle(now + 10 * 12L * 3_600_000L)
        assertEquals("样本门拒了升档：档位停在灰度", CanaryBand.CANARY, band())
        val refusal = rows().filter {
            it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("只够灰度")
        }
        assertEquals("样本门的拒行在账上（evo-samples 前缀）", 1, refusal.size)
    }

    @Test
    fun `判据④b_红线假动作_打目标模式源码_共用门拒并记账`() {
        val fake = EvoCandidate(
            domain = cap.wire,
            target = "app/src/main/java/com/example/zhengdao/baopu/plan/GoalMode.kt",
            body = "把模板候选改成自动替换下次立目标的判据",
        )
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("APP 源码红线要拒：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals(1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.APP_SOURCE.cn))
    }

    /** 立一份目标（不挂工作区根 ⇒ 不写盘；freeze 的纯内存路径）。 */
    private fun goalOf(): GoalRules.Goal {
        val draft = (GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok).draft
        return GoalBoard.freeze(draft, pledge(), 1_000L, 7L)!!
    }

    @Test
    fun `判据④c_未转正不参与判据_转正也不参与_判据写法字面不变`() {
        // 基线：同一句目标，判据写法逐条同形（模板候选只产值，482 判据 D7 同款）。
        val before = GoalRules.templateCandidate(goalOf())

        // 候选出过 + 档位拨到全开（比"未转正"更严的条件下）⇒ 判据写法逐条不变。
        repeat(3) { i -> oneFailedGoal(now + i * 3_600_000L) }
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
        val after = GoalRules.templateCandidate(goalOf())
        assertEquals("模板候选＝纯函数：档位与候选都进不去", before, after)
        val draftAfter = GoalRules.parse(args(), hasPledge = true) as GoalRules.GoalParse.Ok
        assertEquals("判据写法本体一字不动", "file:报告在", draftAfter.draft.criteria.single().let { "${it.kind.wire}:${it.text}" })
    }

    // ── 589 锚口径：同一小时连收几次＝同一次条件成熟，只计 1 次 ─────────────────

    @Test
    fun `锚口径_同一小时连收两次_样本只计1次`() {
        val hourMs = com.example.zhengdao.baopu.evolution.samples.EvoSampleRules.HOUR_MS
        val sameHour = (now / hourMs + 1) * hourMs - 60_000L
        oneFailedGoal(now)
        oneFailedGoal(sameHour, tag = 1)
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("同桶合并：样本只计 1", 1, reading.events)
        assertEquals("被合并的那条留痕", 1, reading.droppedAnchor)
        oneFailedGoal(sameHour + 2L * hourMs, tag = 2)
        assertEquals("跨小时是新的一次", 2, EvoSampleWiring.reading(cap.wire).events)
    }
}
