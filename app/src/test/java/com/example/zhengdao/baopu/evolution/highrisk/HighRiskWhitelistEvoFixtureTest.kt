// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C · **工具白名单域夹具**（判据①②③④的域内走法，盘上真账）：
//   收窄（移除工具/缩权）⇒ 自动生效、落调整行＋观察；加宽（新增工具/提权）⇒ 必须过 467 准入：
//   无画像/空画像 ⇒ 准入拒并记账（不落行）；喂过考画像 ⇒ 放行落行；既加又删仍按加宽（堵换键提权）。
//   转正后可一键回滚（档位回灰＋白名单行退回）；样本够格（589：≥20 次且 ≥7 天）扫档转全开；
//   样本不足 ⇒ 不许转正；档位关闭 ⇒ 暂缓不执行；红线假动作 ⇒ 共用门拒并记账。
//
// 走的全是既有机制：门＝568-A 的 LowRiskGate（红线→档位→深度）、梯子＝473、观察账＝506、
// 样本门＝589、准入＝467 的 AdmissionGate（复用画像，不新造通道）、白名单行＝208 的 tool_grant
// （whitelist: 族，照 517 depth:／568-B threshold: 先例）——本测试一条新判不写。
// ToolGroupAudit 是 Context 胶水层不可 JVM 直测（本仓无 Robolectric）：夹具走生产同款两步
// （effectiveWhitelist → proposeWhitelistChange → 生效值读回），接线顺序由源级门禁钉（HighRiskEvoGateTest）。
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
import com.example.zhengdao.baopu.intake.ConductOutcome
import com.example.zhengdao.baopu.intake.ConductQuestionId
import com.example.zhengdao.baopu.intake.ProbeId
import com.example.zhengdao.baopu.intake.ProbeOutcome
import com.example.zhengdao.baopu.policy.CapabilityProfile
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HighRiskWhitelistEvoFixtureTest {

    private val now = 1_760_000_000_000L
    private val entropy = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val cap: CanaryCapability = CanaryCapability.TOOL_WHITELIST

    private lateinit var sandbox: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        sandbox = TempSandbox.create("zd568c-wl-")
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

    private fun whitelistRows(): List<DecisionRecord> =
        rows().filter { it.evidence.startsWith("${ConsequenceEvidence.WHITELIST}${cap.wire}:") }

    private fun admissionRefusals(): List<DecisionRecord> =
        rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("加宽未过准入") }

    private fun band(): CanaryBand = ConsequenceWiring.currentCanaryBand(cap.wire)

    private fun effective(): Set<String> = HighRiskEvo.effectiveWhitelist(HighRiskEvo.BASELINE_WHITELIST)

    /** 过考画像：五枚探针全过＋四道品行考全过 ⇒ 467 准入 Accept（加宽放行）。 */
    private fun acceptProfile(): CapabilityProfile =
        CapabilityProfile.enroll("wl", "v1", now).copy(
            probes = ProbeId.entries.associateWith { ProbeOutcome(it, true, "对") },
            conduct = ConductQuestionId.entries.map { ConductOutcome(it, true, "PASS", "对") },
        )

    /** 生产同款一步（ToolGroupAudit 的提议半边；接线由源级门禁 G1 钉）。 */
    private fun propose(
        before: Set<String>,
        after: Set<String>,
        anchor: String,
        atMs: Long,
        profile: CapabilityProfile? = null,
    ): Boolean = HighRiskEvo.proposeWhitelistChange(
        WhitelistProposal(before = before, after = after, why = "夹具", profile = profile, anchor = anchor),
        atMs,
    )

    /**
     * 区间内往返走 N 轮（同一个键加宽/收窄交替，每轮 before≠after）——加宽轮喂过考画像；
     * 每轮一个独立锚、间隔 ≥1 小时（589 防刷 10 分钟碰不着）。
     */
    private fun zigzag(rounds: Int, gapHours: Long): List<Boolean> =
        (0 until rounds).map { i ->
            val at = now + i * gapHours * 3_600_000L
            val before = effective()
            val after = if (before.contains("bash")) before - "bash" else before + "bash"
            propose(before, after, anchor = "wl:$i", atMs = at, profile = acceptProfile())
        }

    // ── 判据①：收窄自动生效一次（无画像也放行），落调整行＋观察 ──────────────────

    @Test
    fun `判据①_收窄自动生效_无画像也落行`() {
        assertTrue("缺省灰度档", band() == CanaryBand.CANARY)
        assertEquals("没调整行 ⇒ 生效值＝缺省集", HighRiskEvo.BASELINE_WHITELIST, effective())
        val before = setOf("web", "bash")
        val after = setOf("web")
        assertTrue("收窄不需准入 ⇒ 自动生效", propose(before, after, anchor = "wl:n1", atMs = now))
        assertEquals("生效值跟着账走", after, effective())
        assertEquals("收窄落一条调整行", 1, whitelistRows().size)
        assertEquals("收窄落一条观察", 1, observationRows().size)
        assertTrue("观察 ok", observationRows().single().after == "ok")
        assertEquals("收窄不产生准入拒行", 0, admissionRefusals().size)
    }

    // ── 判据①：加宽被拒一次（无画像＝空画像＝能力不足），拒并记账、不落行 ────────

    @Test
    fun `判据①_加宽无画像_准入拒并记账_不落行`() {
        val before = setOf("web")
        val after = setOf("web", "bash")
        assertFalse("加宽无画像 ⇒ 准入 fail-closed 拒", propose(before, after, anchor = "wl:w1", atMs = now))
        assertEquals("没落调整行", 0, whitelistRows().size)
        assertEquals("拒了不算一次执行（没观察）", 0, observationRows().size)
        assertEquals("生效值仍是缺省集", HighRiskEvo.BASELINE_WHITELIST, effective())
        assertEquals("准入拒行在账上（要人批的那一类）", 1, admissionRefusals().size)
        assertTrue("拒行写明能力不足", admissionRefusals().single().reason.contains("能力不足"))
    }

    // ── 判据①：加宽过准入后生效一次，落调整行＋观察 ─────────────────────────────

    @Test
    fun `判据①_加宽过准入_生效并落行`() {
        val before = setOf("web")
        val after = setOf("web", "bash")
        assertTrue("加宽喂过考画像 ⇒ 准入放行", propose(before, after, anchor = "wl:w2", atMs = now, profile = acceptProfile()))
        assertEquals("生效值跟着账走", after, effective())
        assertEquals("加宽落一条调整行", 1, whitelistRows().size)
        assertEquals("加宽落一条观察", 1, observationRows().size)
        assertEquals("过准入不产生拒行", 0, admissionRefusals().size)
    }

    // ── 判据①负例：既加又删仍按加宽（堵「换键提权」绕准入） ─────────────────────

    @Test
    fun `判据①负例_既加又删仍按加宽_无画像照样拒`() {
        val before = setOf("web", "bash")
        val after = setOf("web", "skill")
        assertFalse("删一个加一个＝加宽（加宽优先）⇒ 无画像拒", propose(before, after, anchor = "wl:swap", atMs = now))
        assertEquals("没落调整行", 0, whitelistRows().size)
        assertEquals("准入拒行在账上", 1, admissionRefusals().size)
        assertTrue("喂过考画像后同一条换键放行", propose(before, after, anchor = "wl:swap2", atMs = now + 3_600_000L, profile = acceptProfile()))
        assertEquals(after, effective())
    }

    // ── 判据①：一个键没动＝未变，不落行 ───────────────────────────────────────

    @Test
    fun `判据①_未变不落行`() {
        val same = setOf("web")
        assertFalse("before==after ⇒ 未变，不落行", propose(same, same, anchor = "wl:noop", atMs = now, profile = acceptProfile()))
        assertEquals(0, whitelistRows().size)
        assertEquals(0, observationRows().size)
    }

    // ── 判据②：转正后可一键回滚（档位回灰＋白名单行退回，走决策中心同款机制） ────

    @Test
    fun `判据②_转正后一键回滚_档位回灰_白名单行退回`() {
        assertTrue(zigzag(rounds = 20, gapHours = 9L).all { it })
        HighRiskEvo.sweepOnSettle(now + 20 * 9L * 3_600_000L)
        assertEquals(CanaryBand.FULL, band())

        val latest = whitelistRows().maxByOrNull { it.atMs }!!
        val toKeys = WhitelistRules.decode(latest.before)
        val rolledBack = DecisionRollbacks.run(
            target = latest,
            root = memory,
            handler = DecisionRollbackHandler { r ->
                HighRiskEvo.revertWhitelist(
                    WhitelistRules.decode(r.before),
                    WhitelistRules.decode(r.after),
                    now + 21 * 9L * 3_600_000L,
                )
            },
            atMs = now + 21 * 9L * 3_600_000L,
        )
        assertTrue("回滚成（决策中心同款：rollback_of 照留）", rolledBack)
        assertEquals("白名单半边：生效值退回被回滚那行的 before", toKeys, effective())
        assertTrue("回滚行在账上", rows().any { it.kind == DecisionKind.ROLLBACK_OF })

        val after = ConsequenceWiring.humanRevertCanary(cap.wire, now + 22 * 9L * 3_600_000L)
        assertEquals("档位半边：一键回灰（判据②）", CanaryBand.CANARY, after)
    }

    @Test
    fun `判据②负例_盘上已换新值再回滚旧行_老实话false`() {
        assertTrue(propose(emptySet(), setOf("bash"), anchor = "wl:r1", atMs = now, profile = acceptProfile()))
        val stale = whitelistRows().single()
        assertTrue(propose(setOf("bash"), setOf("bash", "web"), anchor = "wl:r2", atMs = now + 3_600_000L, profile = acceptProfile()))
        assertFalse(
            "旧行已不是生效值 ⇒ 不盲退",
            HighRiskEvo.revertWhitelist(
                WhitelistRules.decode(stale.before),
                WhitelistRules.decode(stale.after),
                now + 7_200_000L,
            ),
        )
    }

    // ── 判据③：档位关闭 ⇒ 暂缓不执行，生效值回缺省集 ─────────────────────────

    @Test
    fun `判据③_档位被拨关_自调不出门_生效值回缺省集`() {
        assertTrue(propose(emptySet(), setOf("bash"), anchor = "wl:o1", atMs = now, profile = acceptProfile()))
        assertEquals(setOf("bash"), effective())
        DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.TOOL_GRANT,
                actor = DecisionActor.USER,
                reason = "灰度档位 灰度→关闭（夹具：人工拨关）",
                evidence = "${ConsequenceEvidence.CANARY}${cap.wire}:off:human",
                before = "canary",
                after = "off",
                atMs = now + 3_600_000L,
                rollbackRef = "${ConsequenceEvidence.CANARY}${cap.wire}:canary",
                reversible = true,
            ),
        )
        val ok = propose(setOf("bash"), setOf("bash", "web"), anchor = "wl:o2", atMs = now + 7_200_000L, profile = acceptProfile())
        assertFalse("关闭档 ⇒ 暂缓，不出门", ok)
        assertEquals("关闭档 ⇒ 收权：生效值回缺省集（候选保留在账上）", HighRiskEvo.BASELINE_WHITELIST, effective())
        assertEquals("暂缓不落调整行", 1, whitelistRows().size)
        assertEquals("暂缓不算一次执行（没观察）", 1, observationRows().size)
    }

    // ── 判据④：样本不足不许转正（589 样本门拒并记账） ─────────────────────────

    @Test
    fun `判据④a_样本不足_梯子达标也不许转正_样本门拒并记账`() {
        val outcomes = zigzag(rounds = 10, gapHours = 12L)
        assertTrue("10 轮全放行", outcomes.all { it })
        HighRiskEvo.sweepOnSettle(now + 10 * 12L * 3_600_000L)
        assertEquals("样本门拒了升档：档位停在灰度", CanaryBand.CANARY, band())
        val refusal = rows().filter {
            it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("只够灰度")
        }
        assertEquals("样本门的拒行在账上（evo-samples 前缀）", 1, refusal.size)
    }

    @Test
    fun `判据④b_红线假动作_共用门拒并记账`() {
        val fake = EvoCandidate(
            domain = cap.wire,
            target = "app/src/main/java/com/example/zhengdao/baopu/agent/ToolGroupGate.kt",
            body = "把组闸白名单改成永不再问",
        )
        val v = LowRiskEvo.gate(cap, fake)
        assertTrue("APP 源码红线要拒：$v", v is LowRiskVerdict.Refuse)
        LowRiskEvo.recordRefusal(cap, (v as LowRiskVerdict.Refuse).redLine, v.note, fake, now)
        val refusal = rows().filter { it.kind == DecisionKind.ADVISOR_OPINION && it.reason.contains("红线拒") }
        assertEquals(1, refusal.size)
        assertTrue(refusal.single().reason.contains(EvoRedLine.APP_SOURCE.cn))
        assertEquals("红线拒不产生任何白名单行", 0, whitelistRows().size)
    }

    // ── 判据⑨：后果链没装配 ⇒ 不自调不写账，闸行为与接线前一致 ─────────────────

    @Test
    fun `判据⑨_后果链没装配_不自调不写账_闸行为与接线前一致`() {
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        assertFalse("没装配 ⇒ 不提议不落行", propose(emptySet(), setOf("bash"), anchor = "wl:u1", atMs = now, profile = acceptProfile()))
        assertEquals("没装配 ⇒ 生效值＝缺省集（闸行为与接线前逐字节一致）", HighRiskEvo.BASELINE_WHITELIST, effective())
    }

    @Test
    fun `判据⑨b_半装配_账还在后果链摘了_生效值不跟账跑_不落新行`() {
        assertTrue(propose(emptySet(), setOf("bash"), anchor = "wl:h1", atMs = now, profile = acceptProfile()))
        assertEquals("装配齐 ⇒ 生效值跟着账走", setOf("bash"), effective())
        assertEquals("半装配现场先让账上有料", 1, whitelistRows().size)
        ConsequenceWiring.uninstallForTest()
        assertEquals("链没装 ⇒ 生效值回缺省集（不许读还留在盘上的账）", HighRiskEvo.BASELINE_WHITELIST, effective())
        assertFalse("链没装 ⇒ 不自调", propose(setOf("bash"), setOf("bash", "web"), anchor = "wl:h2", atMs = now + 3_600_000L, profile = acceptProfile()))
        assertEquals("链没装 ⇒ 账上不多写调整行", 1, whitelistRows().size)
        assertEquals("链没装 ⇒ 账上不多写观察行", 1, observationRows().size)
    }

    // ── 589 锚口径：白名单域同一锚多次事件只计 1 次 ───────────────────────────

    @Test
    fun `锚口径_同一锚连提两次_样本只计1次_观察照落`() {
        assertTrue(propose(emptySet(), setOf("bash"), anchor = "wl:same", atMs = now, profile = acceptProfile()))
        assertTrue("同一锚的第二次提议照常判", propose(setOf("bash"), emptySet(), anchor = "wl:same", atMs = now + 60_000L))
        assertEquals("观察行两条（473 面不去重）", 2, observationRows().size)
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("锚类型＝白名单变更事件（589 注册表）", "whitelist_event", reading.anchorKind)
        assertEquals("同锚合并：样本只计 1", 1, reading.events)
        assertEquals("被合并的那条留痕", 1, reading.droppedAnchor)
    }

    @Test
    fun `锚口径_没有锚_兜底按时刻自记身份_不硬造事件号`() {
        assertTrue(propose(emptySet(), setOf("bash"), anchor = "", atMs = now, profile = acceptProfile()))
        val reading = EvoSampleWiring.reading(cap.wire)
        assertEquals("兜底锚不合并任何东西：计 1", 1, reading.events)
    }
}