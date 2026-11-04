// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十八批 · 进化闭环接线的**行为门禁**（判据①②③）。
//
// 判据① 一条真实链走完：误判累积 → 提议 → 验证 → 回滚/落版（每步可断言被调）
// 判据② STALLED 现场 ⇒ 触发接管（接管侧归 [TakeoverWiringTest]）
// 判据③ 门拒绝时不许静默（落账＋说明理由）
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.inspect.DeviationEntry
import com.example.zhengdao.baopu.inspect.DeviationKind
import com.example.zhengdao.baopu.inspect.DeviationLedger
import com.example.zhengdao.baopu.policy.AdmissionState
import com.example.zhengdao.baopu.policy.AnchorCase
import com.example.zhengdao.baopu.policy.AnchorHold
import com.example.zhengdao.baopu.policy.CriteriaCandidate
import com.example.zhengdao.baopu.policy.CriteriaEvolution
import com.example.zhengdao.baopu.policy.CriteriaOrigin
import com.example.zhengdao.baopu.policy.CriteriaOutputForm
import com.example.zhengdao.baopu.policy.CriteriaSnapshot
import com.example.zhengdao.baopu.policy.CriterionEntry
import com.example.zhengdao.baopu.policy.DetektDebt
import com.example.zhengdao.baopu.policy.EpochId
import com.example.zhengdao.baopu.policy.EpochRegistry
import com.example.zhengdao.baopu.policy.EpochRuntime
import com.example.zhengdao.baopu.policy.EvolutionAttemptRow
import com.example.zhengdao.baopu.policy.EvolutionProof
import com.example.zhengdao.baopu.policy.GapTrigger
import com.example.zhengdao.baopu.policy.GateRun
import com.example.zhengdao.baopu.policy.MisjudgmentRow
import com.example.zhengdao.baopu.policy.MisjudgmentSource
import com.example.zhengdao.baopu.policy.MisjudgmentTrigger
import com.example.zhengdao.baopu.policy.ReplayProof
import com.example.zhengdao.baopu.verify.GapFeedback
import com.example.zhengdao.baopu.verify.GapItem
import com.example.zhengdao.baopu.verify.GapKind
import com.example.zhengdao.baopu.verify.GapSource
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvolutionWiringTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("evo-wiring-").toFile()
        DeviationLedger.install(root)
        com.example.zhengdao.baopu.DecisionLedger.install(root)
        EvolutionWiring.resetForTest()
    }

    @After
    fun tearDown() {
        runCatching { root.deleteRecursively() }
        EvolutionWiring.resetForTest()
        GapFeedback.resetForTest()
    }

    // ── 判据① 真实链：误判累积 → 提议 → 验证 → 回滚/落版 ───────────────────

    @Test
    fun `判据①_完整链走完_四条验证全绿_落版`() {
        // 1. 误判累积：偏差账写 3 条同 kind.wire（24h 内）
        val now = 1_700_000_000_000L
        val kind = DeviationKind.OVER_STEPS
        repeat(3) { i ->
            DeviationLedger.write(
                root,
                DeviationEntry(
                    id = "dev:$i",
                    atMs = now - i * 1_000L,
                    runId = "run-$i",
                    taskId = "",
                    kind = kind,
                    facts = emptyMap(),
                ),
            )
        }
        // 2. 收工触发 ⇒ 提案被开（提议步）
        EvolutionWiring.onRunSettled(now)
        val proposals = EvolutionWiring.proposals()
        assertEquals(1, proposals.size)
        val proposal = proposals.first()
        assertEquals("misjudgment:${kind.wire}", proposal.id)
        assertEquals(ProposalStatus.OPEN, proposal.status)

        // 3. 跑完整链：准入四道闸（用带 replay+verdicts 的候选）
        val candidate = candidateWithReplay(proposal.id)
        val anchors = signedAnchors()
        val next = nextSnapshot()
        val proof = allGreenProof()
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = MisjudgmentTrigger(
                DeviationLedger.read(root).map {
                    MisjudgmentRow(MisjudgmentSource.DEVIATION_LEDGER, it.kind.wire, it.atMs, it.runId)
                },
            ),
            candidate = candidate,
            next = next,
            rulingRef = "御批-428",
            anchors = anchors,
            proofRunner = { proof },
            nowMs = now,
        )
        // 每步都被调了：admit 过 ⇒ applied ⇒ verify 过 ⇒ 落版
        assertEquals(AdmissionState.ADMIT, outcome.admission)
        assertTrue("apply 必须被调到", outcome.applied)
        assertNotNull("verify 必须被调到", outcome.verify)
        assertTrue("验证全绿 ⇒ 落版", outcome.landed())
        assertFalse("验证全绿 ⇒ 不回滚", outcome.rolledBack())
        // 纪元册落账（EpochRegistry.append 被调到）
        assertTrue("纪元册必须有纪", EpochRegistry.epochs().isNotEmpty())
        // 尝试账落一行"通过·未回滚"
        val attempts = EvolutionWiring.attempts()
        assertTrue("尝试账必须有行", attempts.isNotEmpty())
        val last = attempts.last()
        assertEquals(EvolutionAttemptRow.VERDICT_PASS, last.verdict)
        assertFalse(last.rolledBack)
        // 提案状态 LANDED
        val landed = EvolutionWiring.proposals().first { it.id == proposal.id }
        assertEquals(ProposalStatus.LANDED, landed.status)
    }

    @Test
    fun `判据①_完整链走完_验证红_自动回滚`() {
        val now = 1_700_000_000_000L
        val candidate = candidateWithReplay("gap:tool_misuse|x")
        val anchors = signedAnchors()
        val next = nextSnapshot()
        // 验证读数：全量单测红了 1 条
        val proof = EvolutionProof(
            unitTests = GateRun(ran = true, total = 100, failed = 1),
            regressionSet = GateRun(ran = true, total = 13, failed = 0),
            anchorSet = AnchorHold(total = 50, mismatched = 0),
            detekt = DetektDebt(before = 345, after = 345),
        )
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(listOf(gapItem("x", attempts = GapFeedback.HAND_OVER_ATTEMPTS))),
            candidate = candidate,
            next = next,
            rulingRef = "御批-428-red",
            anchors = anchors,
            proofRunner = { proof },
            nowMs = now,
        )
        assertTrue("apply 必须被调到", outcome.applied)
        assertTrue("验证红 ⇒ 回滚", outcome.rolledBack())
        assertFalse("验证红 ⇒ 没落版", outcome.landed())
        // 回滚账：有上一张表 ⇒ rollbackToPrevious；没 ⇒ reset
        // 当前表回到空（首张表失败走 reset）
        assertNull("回滚后当前表为空", EpochRuntime.currentSnapshot())
        // 尝试账落一行"失败·已回滚"
        val last = EvolutionWiring.attempts().last()
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, last.verdict)
        assertTrue(last.rolledBack)
        // 失败的纪不进纪元册（376 硬规矩：不许半进化）
        assertTrue("失败的纪不进纪元册", EpochRegistry.epochs().isEmpty())
    }

    // ── 判据③ 门拒绝不许静默 ────────────────────────────────────────────────

    @Test
    fun `判据③_准入硬拒_落一行拒账带原拒因`() {
        val now = 1_700_000_000_000L
        // 形态非法：SCALAR_SCORE ⇒ 准入 REFUSED
        val candidate = CriteriaCandidate(
            id = "scalar",
            intent = "给个 0.87 分",
            target = "some/target",
            outputForm = CriteriaOutputForm.SCALAR_SCORE,
            origin = CriteriaOrigin.CANDIDATE_POOL,
            replay = null,
            verdicts = emptyMap(),
        )
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(listOf(gapItem("x", attempts = GapFeedback.HAND_OVER_ATTEMPTS))),
            candidate = candidate,
            next = nextSnapshot(),
            rulingRef = "御批-scalar",
            anchors = signedAnchors(),
            proofRunner = { allGreenProof() },
            nowMs = now,
        )
        assertEquals(AdmissionState.REFUSED, outcome.admission)
        assertFalse("准入拒 ⇒ apply 不调", outcome.applied)
        assertNull("准入拒 ⇒ verify 不调", outcome.verify)
        assertNotNull("拒因必须给出来", outcome.refusalReason)
        // 尝试账落一行拒行（判据③：门拒绝不许静默）
        val refused = EvolutionWiring.attempts().last()
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, refused.verdict)
        assertTrue("拒行必须带原拒因", refused.reason.contains("准入"))
    }

    @Test
    fun `判据③_换表拒_落一行拒账带原拒因`() {
        val now = 1_700_000_000_000L
        val candidate = candidateWithReplay("x")
        // 空 rulingRef ⇒ apply 拒（御批必填）
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(listOf(gapItem("x", attempts = GapFeedback.HAND_OVER_ATTEMPTS))),
            candidate = candidate,
            next = nextSnapshot(),
            rulingRef = "",
            anchors = signedAnchors(),
            proofRunner = { allGreenProof() },
            nowMs = now,
        )
        assertEquals(AdmissionState.ADMIT, outcome.admission)
        assertFalse("换表拒 ⇒ apply 没成功", outcome.applied)
        assertNotNull(outcome.refusalReason)
        val refused = EvolutionWiring.attempts().last()
        assertEquals(EvolutionAttemptRow.VERDICT_FAIL, refused.verdict)
        assertTrue(refused.reason.contains("换表拒"))
    }

    // ── 判据③ 的另一半：不自动改判据（seam 入口只开提案不调 apply） ───────────

    @Test
    fun `判据③_seam_入口只开提案_当前表不变`() {
        val now = 1_700_000_000_000L
        // 喂板：缺口到交人线
        GapFeedback.record(listOf(gapItem("x", attempts = GapFeedback.HAND_OVER_ATTEMPTS)))
        // seam 入口：只开提案
        val id = EvolutionWiring.onGapsRecorded(now)
        assertNotNull("缺口到线 ⇒ 提案被开", id)
        // 当前表没变（不自动改判据）
        assertNull("seam 入口不自动换表", EpochRuntime.currentSnapshot())
        // 纪元册没落纪（apply 没调）
        assertTrue("seam 入口不自动落纪", EpochRegistry.epochs().isEmpty())
    }

    // ── 缺口累积触发（判据①的另一半） ─────────────────────────────────────

    @Test
    fun `缺口到交人线_提案被开_不到线不开`() {
        val now = 1_700_000_000_000L
        // 不到线：attempts = 2 < HAND_OVER_ATTEMPTS
        GapFeedback.record(listOf(gapItem("y", attempts = 2)))
        assertNull("不到线 ⇒ 不开提案", EvolutionWiring.onGapsRecorded(now))
        // 到线：attempts = 3
        GapFeedback.record(listOf(gapItem("z", attempts = GapFeedback.HAND_OVER_ATTEMPTS)))
        assertNotNull("到线 ⇒ 提案被开", EvolutionWiring.onGapsRecorded(now))
        // 重复喂板不重开（同 gapKey）
        GapFeedback.record(listOf(gapItem("z", attempts = GapFeedback.HAND_OVER_ATTEMPTS)))
        assertNull("同 gapKey 不重开", EvolutionWiring.onGapsRecorded(now))
    }

    // ── 误判累积触发（判据①的另一半） ─────────────────────────────────────

    @Test
    fun `误判累积_24h 内同判据键_3 次_提案被开`() {
        val now = 1_700_000_000_000L
        val kind = DeviationKind.TIMEOUT
        repeat(3) { i ->
            DeviationLedger.write(
                root,
                DeviationEntry("d$i", now - i * 1_000L, "r$i", "", kind, emptyMap()),
            )
        }
        EvolutionWiring.onRunSettled(now)
        val p = EvolutionWiring.proposals().firstOrNull { it.id == "misjudgment:${kind.wire}" }
        assertNotNull("24h 内 3 次同判据 ⇒ 提案被开", p)
    }

    @Test
    fun `误判累积_24h 内不足 3 次_提案不开`() {
        val now = 1_700_000_000_000L
        val kind = DeviationKind.TIMEOUT
        repeat(2) { i ->
            DeviationLedger.write(
                root,
                DeviationEntry("d$i", now - i * 1_000L, "r$i", "", kind, emptyMap()),
            )
        }
        EvolutionWiring.onRunSettled(now)
        val p = EvolutionWiring.proposals().firstOrNull { it.id == "misjudgment:${kind.wire}" }
        assertNull("24h 内不足 3 次 ⇒ 提案不开", p)
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private fun gapItem(target: String, attempts: Int): GapItem = GapItem(
        source = GapSource.JINWU,
        kind = GapKind.TOOL_MISUSE,
        target = target,
        evidence = listOf("ev:$target"),
        minimalFix = "fix $target",
        attempts = attempts,
    )

    private fun candidateWithReplay(id: String): CriteriaCandidate = CriteriaCandidate(
        id = id,
        intent = "判据进化测试",
        target = "some/target",
        outputForm = CriteriaOutputForm.CHECK_ITEMS,
        origin = CriteriaOrigin.CANDIDATE_POOL,
        replay = ReplayProof(moreBlocked = 2, lessBlocked = 0, moreAsked = 0, ranAtMs = 0L),
        verdicts = signedAnchors().associate { it.id to it.humanVerdict },
    )

    private fun signedAnchors(): List<AnchorCase> = listOf(
        AnchorCase(
            id = "A1",
            scene = "场景 1",
            oldVerdict = "旧判据通过",
            humanVerdict = "通过：人工确认",
            evidence = "ev1",
            screenedBy = "蓝总管",
            signedBy = "用户",
            signedAt = "2026-11-04",
        ),
    )

    private fun nextSnapshot(): CriteriaSnapshot = CriteriaSnapshot.of(
        epoch = EpochId.of(1_700_000_000_000L),
        seq = 1L,
        criteria = listOf(
            CriterionEntry(
                id = "c1",
                description = "新判据",
                target = "some/target",
                admittedInEpoch = 1L,
            ),
        ),
    )

    private fun allGreenProof(): EvolutionProof = EvolutionProof(
        unitTests = GateRun(ran = true, total = 100, failed = 0),
        regressionSet = GateRun(ran = true, total = 13, failed = 0),
        anchorSet = AnchorHold(total = 1, mismatched = 0),
        detekt = DetektDebt(before = 345, after = 345),
    )
}
