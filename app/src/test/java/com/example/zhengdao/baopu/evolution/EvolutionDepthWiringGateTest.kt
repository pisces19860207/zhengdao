// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · 深度裁定**生产接线**的门禁（派工单 §三 判据①②）。
//
// 505 交付时深度裁定"有实现、没调用方"——一直默认浅，等于没生效。本批把它接进
// [EvolutionWiring.runEvolutionChain]（进化提案的判定入口本体）：进判定前定一次档，
// 同轮 FAIL 当场降一档。两件事都必须**测得出来**：
//   · 判据① 生产调用点：源级 grep 给出 文件:行（接线批的话要留地址）；
//     读数为空 ⇒ 浅档（装配缝 [DepthFactsWiring] 的默认值全偏保守，走真链验）；
//   · 判据② 同轮 FAIL ⇒ 立刻降一档（先升到中档、造一场红链，账上当场出降档行）；
//     落账三件（谁定/依据哪几项/当时读数）在同一行里（505 判据①的老话，接线上再验一遍）。
package com.example.zhengdao.baopu.evolution

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.policy.AnchorCase
import com.example.zhengdao.baopu.policy.CriteriaCandidate
import com.example.zhengdao.baopu.policy.CriteriaOrigin
import com.example.zhengdao.baopu.policy.CriteriaOutputForm
import com.example.zhengdao.baopu.policy.GapTrigger
import com.example.zhengdao.baopu.policy.ReplayProof
import com.example.zhengdao.law.LawIo
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvolutionDepthWiringGateTest {

    private lateinit var root: File
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd517-depth-wiring-").toFile()
        DecisionLedger.install(root)
        EvolutionWiring.resetForTest()
        EvolutionDepth.resetForTest()
        DepthFactsWiring.resetForTest()
    }

    @After
    fun tearDown() {
        EvolutionDepth.resetForTest()
        DepthFactsWiring.resetForTest()
        EvolutionWiring.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
    }

    // ── 判据① 生产调用点（源级） ────────────────────────────────────────────

    @Test
    fun `判据一_深度裁定的生产调用点在判定入口_前后两拍都有`() {
        // 源级 grep（判据①原话「grep 给 文件:行」）：进判定前一次 + 同轮 FAIL 降档一次。
        // 只扫 runEvolutionChain 函数体（定义行上的同名函数不算调用）。
        val rel = "baopu/evolution/EvolutionWiring.kt"
        val raw = LawIo.sources(LawIo.MAIN_ROOT).first { it.first == rel }.second
        val code = LawIo.stripComments(raw)
        val lines = code.lines()
        val runStart = lines.indexOfFirst { it.contains("fun runEvolutionChain(") }
        val bodyDef = lines.indexOfFirst { it.contains("private fun chainBody(") }
        assertTrue("runEvolutionChain（${rel}:${runStart + 1}）与 chainBody（${rel}:${bodyDef + 1}）都要在", runStart in 0 until bodyDef)
        val region = lines.subList(runStart, bodyDef)
        val pre = region.indexOfFirst { it.contains("adjudicateDepth(") }
        val call = region.indexOfFirst { it.contains("chainBody(") }
        val down = region.indexOfFirst { it.contains("downgradeAfterFailure(") }
        assertTrue("进判定**前**定档：${rel}:${runStart + pre + 1}", pre >= 0)
        assertTrue("定档必须在链本体之前：${rel}:${runStart + pre + 1} < ${rel}:${runStart + call + 1}", pre < call)
        assertTrue("同轮 FAIL 的降档拍在链调用之后：${rel}:${runStart + down + 1}", down > call)
        assertTrue(
            "真正落账的那一行（EvolutionDepth.adjudicate 的生产调用）要在文件里：$rel",
            code.contains("EvolutionDepth.adjudicate("),
        )
        assertTrue("降档拍必须挂在链结论上", code.contains("outcome.conclusion == EvolutionConclusion.FAILED"))
    }

    // ── 判据① 读数为空 ⇒ 浅档（走真链，不用手搓事实） ────────────────────────

    @Test
    fun `判据一_读数为空_走真链定出的档是浅档且账行三件齐全`() {
        // 装配缝没接（默认读数全保守）＋没定过档 ⇒ 判定入口这一拍必然停在最底档。
        assertEquals(DepthSideReadings(), DepthFactsWiring.readings())
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(emptyList()),
            candidate = admittedCandidate("gap:none|x"),
            next = null,
            rulingRef = "御批-517-shallow",
            anchors = signedAnchors(),
            proofRunner = { EvolutionWiring.defaultProof() },
            nowMs = now,
        )
        // 链照旧收场（缺读数 ⇒ 没法验，495 的语义不动）；深度账上多了一行浅档
        assertEquals(EvolutionConclusion.UNVERIFIABLE, outcome.conclusion)
        val rows = DepthLedger.replay()
        assertEquals("进判定前那一次定档要落账", 1, rows.size)
        val d = rows.first()
        assertEquals(DepthTier.SHALLOW, d.depth)
        assertEquals("谁定的", EvolutionDepth.DECIDER, d.by)
        assertTrue("依据哪几项（不为空）", d.reasons.isNotEmpty())
        assertTrue("当时读数（不为空）", d.readings.isNotEmpty())
        assertTrue("读数要如实记『上一场没法验』", d.readings["last"] == "UNVERIFIABLE")
        assertEquals(now, d.atMs)
    }

    // ── 判据② 同轮 FAIL ⇒ 立刻降一档 ──────────────────────────────────────

    @Test
    fun `判据二_先升到中档_同轮链判红_当场降回浅档`() {
        // 先把档挣到中档（证据 PASS＋预算有余＋画像置信高——505 合成规则的三条）
        val medium = EvolutionDepth.adjudicate(
            cost = CostFacts(cardBudgetLeft = 2, profileConfidence = 0.9, interruptedToday = 1, authTier = 1),
            evidence = EvidenceFacts(last = EvolutionConclusion.LANDED, evidenceEnough = true),
            nowMs = now - 1_000,
        )
        assertEquals(DepthTier.MEDIUM, medium.depth)
        // 造一场红链：读数不合格（ProofGap.Invalid 且带不出新表）⇒ 同轮结论 FAILED
        val outcome = EvolutionWiring.runEvolutionChain(
            trigger = GapTrigger(emptyList()),
            candidate = admittedCandidate("gap:red|x"),
            next = null,
            rulingRef = "御批-517-red",
            anchors = signedAnchors(),
            proofRunner = { EvolutionWiring.defaultProof() },
            nowMs = now,
            gap = ProofGap.Invalid("没有可用读数（弃因：格式建不出 1 行）", null),
        )
        assertEquals(EvolutionConclusion.FAILED, outcome.conclusion)
        // 账上当场出降档行：同一轮（nowMs）、浅档、降档依据点名上一场 FAIL。
        // 读盘账的最末行（同毫秒的两行——定档一行、降档一行——都要在盘上）。
        val diskRows = File(root, DepthLedger.FILE_NAME).readLines(Charsets.UTF_8).mapNotNull(DepthLedger::parse)
        assertEquals("同毫秒两行都要落盘（定档＋降档）", 3, diskRows.size)
        val after = diskRows.last()
        assertEquals("同轮 FAIL ⇒ 立刻降一档", DepthTier.SHALLOW, after.depth)
        assertTrue("降档依据要看得懂：${after.reasons}", after.reasons.any { it.contains("FAIL") || it.contains("失败") })
        assertEquals("读数里要留同轮败因", "FAILED", after.readings["last"])
        // 下一拍再问档位，已经是浅档（降档即时生效，不是"下一轮再生效"）
        assertEquals(DepthTier.SHALLOW, EvolutionDepth.current())
    }

    // ── 判据① 另一半：左相读数缺哪格，哪格就不给升档（装配缝注入形状） ─────────

    @Test
    fun `判据一_装配缝注入的读数缺置信_预算再足也升不到中档`() {
        DepthFactsWiring.sideReadings = {
            DepthSideReadings(cardBudgetLeft = 3, interruptedToday = 0, authTier = 2, profileConfidence = 0.0)
        }
        val d = EvolutionDepth.adjudicate(
            cost = DepthFactsWiring.readings().toCostFacts(),
            evidence = EvidenceFacts(last = EvolutionConclusion.LANDED, evidenceEnough = true),
            nowMs = now,
        )
        assertEquals("画像置信 0＝没有读数＝不许升档（缺一不升）", DepthTier.SHALLOW, d.depth)
        // 注满三条 ⇒ 中档（缝的形状对：读数齐了规则就动）
        DepthFactsWiring.sideReadings = {
            DepthSideReadings(cardBudgetLeft = 3, interruptedToday = 0, authTier = 2, profileConfidence = 0.9)
        }
        val up = EvolutionDepth.adjudicate(
            cost = DepthFactsWiring.readings().toCostFacts(),
            evidence = EvidenceFacts(last = EvolutionConclusion.LANDED, evidenceEnough = true),
            nowMs = now + 1,
        )
        assertEquals(DepthTier.MEDIUM, up.depth)
    }

    // ── 桩件（照 428 行为门禁同款的最小准入形状） ─────────────────────────────

    private fun admittedCandidate(id: String): CriteriaCandidate = CriteriaCandidate(
        id = id,
        intent = "深度裁定接线测试",
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
            signedAt = "2026-11-07",
        ),
    )
}
