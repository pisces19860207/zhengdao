// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C · **路由与五流域夹具**（判据「路由可调／冲突时以状态机为准」的域内走法，盘上真账）：
//   路由输入（选哪个 peer、走哪条路）可调 ⇒ 纯偏好落调整行＋观察；候选假设了一条非法收场迁移
//   （如终态翻案）⇒ 状态机赢，拒并记账（不落行）；假设合法迁移 ⇒ 放行。转正后可一键回滚
//   （档位回灰＋路由行退回）；样本够格（589：≥20 次且 ≥7 天）扫档转全开；样本不足 ⇒ 不许转正；
//   档位关闭 ⇒ 暂缓不执行；红线假动作（改 549 状态机源码）⇒ 共用门拒并记账。
//
// 走的全是既有机制：门＝568-A 的 LowRiskGate（红线→档位→深度）、梯子＝473、观察账＝506、
// 样本门＝589、状态机守卫＝549 的 EnvoyLaw.legal（RouteSemantics.flowLegal 只委托，不另判）、
// 路由行＝208 的 tool_grant（route: 族，照域① whitelist:／568-B threshold:／517 depth: 先例）
// ——本测试一条新判不写。AdmissionWiring 是 Context 胶水层不可 JVM 直测（本仓无 Robolectric）：
// 夹具走生产同款两步（effectiveRoute → proposeRouteChange/onPeerAdmitted → 生效值读回），
// 接线顺序由源级门禁钉（HighRiskRouteEvoGateTest）；状态语义不可改由 HighRiskRouteSemanticsGateTest 钉。
package com.example.zhengdao.baopu.evolution.highrisk

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
import com.example.zhengdao.baopu.peer.A2aTaskState
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HighRiskRouteEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "9b1d4c72-568c-4e21-9f0a-7c3d5e8b1204"
    private val cap: CanaryCapability = CanaryCapability.ENVOY_ROUTE

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568c-rt-")
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

    private fun routeRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.ROUTE}${cap.wire}:") }

    private fun conflictRefusals(): List<DecisionRecord> =
        rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("路由候选与五流状态机冲突") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    private fun effective(): RouteInput? = RouteEvo.effectiveRoute(RouteEvo.BASELINE_ROUTE)

    /** 生产同款一步（AdmissionWiring.routeSink 的提议半边；接线由源级门禁钉）。 */
    private fun propose(
        before: RouteInput?,
        after: RouteInput,
        anchor: String,
        atMs: Long,
        assumedFrom: A2aTaskState? = null,
        assumedTo: A2aTaskState? = null,
    ): Boolean = RouteEvo.proposeRouteChange(
        RouteProposal(
            before = before,
            after = after,
            why = "夹具",
            assumedFrom = assumedFrom,
            assumedTo = assumedTo,
            anchor = anchor,
        ),
        atMs,
    )

    /** 人工拨关档位（决策中心同款 tool_grant 行；判据③的现场）。 */
    private fun turnOff(atMs: Long) {
        DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = atMs,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
    }

    /**
     * 偏好往返走 N 轮（peerA/peerB 交替，每轮 before≠after，纯偏好不假设状态迁移）；
     * 每轮一个独立锚、间隔 ≥1 小时（589 防刷 10 分钟碰不着）。
     */
    private fun zigzag(rounds: Int, gapHours: Long): List<Boolean> =
        (0 until rounds).map { i ->
            val at = now + i * gapHours * 3_600_000L
            val before = effective()
            val after = when {
                before == null -> RouteInput("peerA", "")
                before.preferredPeer == "peerA" -> RouteInput("peerB", before.path)
                else -> RouteInput("peerA", before.path)
            }
            propose(before, after, anchor = "rt:$i", atMs = at)
        }

    // ── 判据：路由可调（纯偏好夹具），落调整行＋观察 ───────────────────────────

    @Test
    fun `路由可调_纯偏好_落行生效`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        assertNull("没调整行 ⇒ 生效值＝缺省路由（null）", effective())
        val after = RouteInput("peerA", "fast")
        assertTrue("纯路由偏好（不假设状态迁移）⇒ 可调", propose(before = null, after = after, anchor = "rt:1", atMs = now))
        assertEquals("生效值跟着账走", after, effective())
        assertEquals("落一条路由调整行", 1, routeRows().size)
        assertEquals("落一条观察", 1, observationRows().size)
        assertTrue("观察 ok", observationRows().single().after == "ok")
        assertEquals("纯偏好不产生状态机冲突拒行", 0, conflictRefusals().size)
    }

    @Test
    fun `路由可调_换路径标也算一次调整`() {
        assertTrue(propose(null, RouteInput("peerA", "fast"), anchor = "rt:p1", atMs = now))
        assertTrue("同 peer 换路径标 ⇒ 仍是一次路由调整", propose(RouteInput("peerA", "fast"), RouteInput("peerA", "safe"), anchor = "rt:p2", atMs = now + 3_600_000L))
        assertEquals(RouteInput("peerA", "safe"), effective())
        assertEquals(2, routeRows().size)
    }

    // ── 判据：冲突时以状态机为准（假设非法迁移 ⇒ 拒并记账、不落行） ─────────────

    @Test
    fun `冲突以状态机为准_假设终态翻案_拒并记账_不落行`() {
        val after = RouteInput("peerA", "fast")
        assertFalse(
            "假设 completed→working（终态翻案）⇒ 状态机赢，候选拒",
            propose(null, after, anchor = "rt:bad", atMs = now, assumedFrom = A2aTaskState.COMPLETED, assumedTo = A2aTaskState.WORKING),
        )
        assertEquals("没落路由调整行", 0, routeRows().size)
        assertEquals("拒了不算一次执行（没观察）", 0, observationRows().size)
        assertNull("生效值仍是缺省路由", effective())
        assertEquals("状态机冲突拒行在账上", 1, conflictRefusals().size)
        assertTrue("拒行写明以状态机为准", conflictRefusals().single().reason.contains("以状态机为准"))
    }

    @Test
    fun `冲突以状态机为准_假设合法迁移_放行落行`() {
        val after = RouteInput("peerA", "fast")
        assertTrue(
            "假设 working→completed（合法）⇒ 不冲突，放行落行",
            propose(null, after, anchor = "rt:ok", atMs = now, assumedFrom = A2aTaskState.WORKING, assumedTo = A2aTaskState.COMPLETED),
        )
        assertEquals(after, effective())
        assertEquals(1, routeRows().size)
        assertEquals(0, conflictRefusals().size)
    }

    // ── 未变不落行 ───────────────────────────────────────────────────────────

    @Test
    fun `路由未变_不落行`() {
        val same = RouteInput("peerA", "fast")
        assertTrue(propose(null, same, anchor = "rt:s1", atMs = now))
        assertFalse("before==after ⇒ 未变，不落行", propose(same, same, anchor = "rt:s2", atMs = now + 3_600_000L))
        assertEquals(1, routeRows().size)
        assertEquals(1, observationRows().size)
    }

    // ── 判据②：转正后可一键回滚（档位回灰＋路由行退回，走决策中心同款机制） ────

    @Test
    fun `判据②_转正后一键回滚_档位回灰_路由行退回`() {
        assertTrue(zigzag(rounds = 20, gapHours = 9L).all { it })
        RouteEvo.sweepOnSettle(now + 20 * 9L * 3_600_000L)
        assertEquals(CanaryBand.FULL, band())

        val latest = routeRows().maxByOrNull { it.atMs }!!
        val toRoute = RouteSemantics.decode(latest.before)
        val rolledBack = DecisionRollbacks.run(
            target = latest,
            root = memory,
            handler = DecisionRollbackHandler { r ->
                RouteEvo.revertRoute(
                    RouteSemantics.decode(r.before),
                    RouteSemantics.decode(r.after),
                    now + 21 * 9L * 3_600_000L,
                )
            },
            atMs = now + 21 * 9L * 3_600_000L,
        )
        assertTrue("回滚成（决策中心同款：rollback_of 照留）", rolledBack)
        assertEquals("路由半边：生效值退回被回滚那行的 before", toRoute, effective())
        assertTrue("回滚行在账上", rows().any { it.kind == DecisionKind.ROLLBACK_OF })

        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 22 * 9L * 3_600_000L)
        assertEquals("档位半边：一键回灰（判据②）", CanaryBand.CANARY, after)
    }

    @Test
    fun `判据②负例_盘上已换新值再回滚旧行_老实话false`() {
        assertTrue(propose(null, RouteInput("peerA", ""), anchor = "rt:r1", atMs = now))
        val stale = routeRows().single()
        assertTrue(propose(RouteInput("peerA", ""), RouteInput("peerB", ""), anchor = "rt:r2", atMs = now + 3_600_000L))
        assertFalse(
            "旧行已不是生效值 ⇒ 不盲退",
            RouteEvo.revertRoute(
                RouteSemantics.decode(stale.before),
                RouteSemantics.decode(stale.after),
                now + 7_200_000L,
            ),
        )
    }

    // ── 判据③：档位关闭 ⇒ 暂缓不执行，生效值回缺省路由 ───────────────────────

    @Test
    fun `判据③_档位被拨关_自调不出门_生效值回缺省路由`() {
        assertTrue(propose(null, RouteInput("peerA", ""), anchor = "rt:o1", atMs = now))
        assertEquals(RouteInput("peerA", ""), effective())
        turnOff(now + 3_600_000L)
        val ok = propose(RouteInput("peerA", ""), RouteInput("peerB", ""), anchor = "rt:o2", atMs = now + 7_200_000L)
        assertFalse("关闭档 ⇒ 暂缓，不出门", ok)
        assertNull("关闭档 ⇒ 收权：生效值回缺省路由（候选保留在账上）", effective())
        assertEquals("暂缓不落调整行", 1, routeRows().size)
        assertEquals("暂缓不算一次执行（没观察）", 1, observationRows().size)
    }

    // ── 判据④：样本不足不许转正（589 样本门拒并记账） ─────────────────────────

    @Test
    fun `判据④a_样本不足_梯子达标也不许转正_样本门拒并记账`() {
        val outcomes = zigzag(rounds = 10, gapHours = 12L)
        assertTrue("10 轮全放行", outcomes.all { it })
        RouteEvo.sweepOnSettle(now + 10 * 12L * 3_600_000L)
        assertEquals("样本门拒了升档：档位停在灰度", CanaryBand.CANARY, band())
        val refusal = rows().filter {
            it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("只够灰度")
        }
        assertEquals("样本门的拒行在账上（evo-samples 前缀）", 1, refusal.size)
    }

    @Test
    fun `判据④b_红线假动作_改549状态机源码_共用门拒并记账`() {
        val fake = EvoCandidate(
            domain = cap.wire,
            target = "app/src/main/java/com/example/zhengdao/baopu/peer/EnvoyTrack.kt",
            body = "把五流状态机的终态判据改成永不粘住",
        )
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("APP 源码红线要拒（硬要求①：不改 549 状态判定）：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals(1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.APP_SOURCE.cn))
        assertEquals("红线拒不产生任何路由行", 0, routeRows().size)
    }

    // ── 判据⑨：后果链没装配 ⇒ 不自调不写账，闸行为与接线前一致 ─────────────────

    @Test
    fun `判据⑨_后果链没装配_不自调不写账_闸行为与接线前一致`() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        assertFalse("没装配 ⇒ 不提议不落行", propose(null, RouteInput("peerA", ""), anchor = "rt:u1", atMs = now))
        assertNull("没装配 ⇒ 生效值＝缺省路由（闸行为与接线前逐字节一致）", effective())
    }

    @Test
    fun `判据⑨b_半装配_账还在后果链摘了_生效值不跟账跑_不落新行`() {
        assertTrue(propose(null, RouteInput("peerA", ""), anchor = "rt:h1", atMs = now))
        assertEquals("装配齐 ⇒ 生效值跟着账走", RouteInput("peerA", ""), effective())
        assertEquals("半装配现场先让账上有料", 1, routeRows().size)
        ConsequenceWiring.uninstallForTest()
        assertNull("链没装 ⇒ 生效值回缺省路由（不许读还留在盘上的账）", effective())
        assertFalse("链没装 ⇒ 不自调", propose(RouteInput("peerA", ""), RouteInput("peerB", ""), anchor = "rt:h2", atMs = now + 3_600_000L))
        assertEquals("链没装 ⇒ 账上不多写调整行", 1, routeRows().size)
        assertEquals("链没装 ⇒ 账上不多写观察行", 1, observationRows().size)
    }

    // ── 生产出口：新 peer 准入 ⇒ 路由偏好指向它（AdmissionWiring.routeSink 同款） ──

    @Test
    fun `生产出口_新peer准入_路由偏好指向它_落行`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        assertNull("起点＝缺省路由", effective())
        assertTrue("新 peer 准入 ⇒ 路由偏好指向它（纯偏好，不假设状态迁移）", RouteEvo.onPeerAdmitted("mcp-foo", now))
        assertEquals("生效值＝偏好指向新准入 peer", RouteInput("mcp-foo", ""), effective())
        assertEquals("落一条路由调整行", 1, routeRows().size)
        assertEquals("落一条观察", 1, observationRows().size)
        assertEquals("纯偏好不产生状态机冲突拒行", 0, conflictRefusals().size)
    }

    @Test
    fun `生产出口_档位关闭_新peer准入也不自调`() {
        turnOff(now)
        assertFalse("档位关闭 ⇒ 新 peer 准入也不自调", RouteEvo.onPeerAdmitted("mcp-bar", now + 3_600_000L))
        assertNull("生效值回缺省路由", effective())
        assertEquals("关闭档不落路由行", 0, routeRows().size)
    }

    // ── 589 锚口径：路由域同一锚多次事件只计 1 次 ─────────────────────────────

    @Test
    fun `锚口径_同一锚连提两次_样本只计1次_观察照落`() {
        assertTrue(propose(null, RouteInput("peerA", ""), anchor = "rt:same", atMs = now))
        assertTrue("同一锚的第二次提议照常判", propose(RouteInput("peerA", ""), RouteInput("peerB", ""), anchor = "rt:same", atMs = now + 60_000L))
        assertEquals("观察行两条（473 面不去重）", 2, observationRows().size)
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("锚类型＝路由变更事件（589 注册表）", "route_event", reading.anchorKind)
        assertEquals("同锚合并：样本只计 1", 1, reading.events)
        assertEquals("被合并的那条留痕", 1, reading.droppedAnchor)
    }

    @Test
    fun `锚口径_没有锚_兜底按时刻自记身份_不硬造事件号`() {
        assertTrue(propose(null, RouteInput("peerA", ""), anchor = "", atMs = now))
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("兜底锚不合并任何东西：计 1", 1, reading.events)
    }
}