// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 三司会审的判据与负例（派工单 311 §判据/§负例，逐条对应）：
//   ① 四触发各有测试；② 任一 FAIL ⇒ 状态 FAIL 并交人（UNVERIFIABLE 同罪）；
//   ③ 右相接学习器 ⇒ 红（源级门禁）；④ 锚定一致率下降 ⇒ 报退化（造数据）；
//   ⑤ 日常交付不触发三司（convene 拒收＋daily 案由不命中）。
// 负例：多数决放行／UNVERIFIABLE 当 PASS／右相接学习器建议／暗卫抽检不记违规／模型自述当证据。
// 扫源码的门禁先证「会咬人」（照 ChancellorSourceGateTest 房型），免得全绿只是没扫到。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.law.PenaltyTier
import com.example.zhengdao.baopu.law.Violation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThreeCourtsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        const val NOW = 1_780_000_000_000L
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    /** 纯文本交付：右相处直通 PASS（三司测试的「右相过」底座）。 */
    private fun textOnlySub(): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(ChancellorDeliverable(ChancellorClaimKind.TEXT_ONLY, "交付已完成")),
        opinions = emptyList(),
        ledger = ChancellorLedger(),
    )

    /** 学习成果且零样本零契约：右相处 UNVERIFIABLE（NO_SAMPLES）。 */
    private fun unverifiableSub(): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(
            ChancellorDeliverable(
                kind = ChancellorClaimKind.LEARNING,
                summary = "学到一条",
                contractText = null,
                evidence = emptyList(),
            ),
        ),
        opinions = emptyList(),
        ledger = ChancellorLedger(),
    )

    /** 落盘产物但文件不在：右相处 FAIL（C1）。 */
    private fun missingFileSub(root: File): ChancellorSubmission = ChancellorSubmission(
        deliverables = listOf(
            ChancellorDeliverable(kind = ChancellorClaimKind.FILE, summary = "写了个报告", path = "产物/缺.md"),
        ),
        opinions = emptyList(),
        ledger = ChancellorLedger(),
        root = root,
    )

    private fun fileOpRow(path: String): FileOpEntry = FileOpEntry(
        at = NOW,
        sessionId = 7L,
        tool = "write_file",
        kind = FileOpKind.WRITE,
        path = path,
        purpose = "三司测试",
    )

    // ── 判据①⑤：四触发各有测试；日常不进三司 ─────────────────────────────

    @Test
    fun `触发四条件_各命中一次_且取值有优先级`() {
        assertEquals(CourtTrigger.IRREVERSIBLE, CourtReferral("d1", irreversible = true).trigger())
        assertEquals(CourtTrigger.REPEAT_FAIL, CourtReferral("d2", consecutiveFails = 2).trigger())
        assertEquals(CourtTrigger.LEARNING_INGEST, CourtReferral("d3", learningIngest = true).trigger())
        assertEquals(CourtTrigger.USER_NAMED, CourtReferral("d4", userNamed = true).trigger())
    }

    @Test
    fun `连续FAIL只1次_不算触发`() {
        assertNull(CourtReferral("d5", consecutiveFails = CourtReferral.REPEAT_FAIL_THRESHOLD - 1).trigger())
    }

    @Test
    fun `日常交付_四旗标全false_不命中任何触发`() {
        assertNull(CourtReferral.daily("日常d1").trigger())
    }

    @Test
    fun `判据⑤_convene对无触发案由拒收_日常交付进不了三司`() {
        var thrown: IllegalArgumentException? = null
        try {
            ThreeCourts.convene(CourtReferral.daily("日常d1"), textOnlySub(), emptyList(), emptyList(), NOW)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("日常交付竟被收进三司", thrown)
        assertTrue(thrown!!.message!!.contains("日常交付不进三司"))
    }

    // ── 判据②：任一 FAIL ⇒ 状态 FAIL 并交人（不是多数决） ────────────────

    @Test
    fun `刑部事实不符_右相虽PASS_仍FAIL并交人_多数决不放行`() {
        val report = ThreeCourts.convene(
            CourtReferral("d1", userNamed = true),
            textOnlySub(),
            listOf(FactSpec.ledgerRow("产物/a.md")),
            emptyList(),
            NOW,
        )
        assertEquals("右相过了但事实不符，1:2 也必须 FAIL", ChancellorVerdict.PASS, report.dali.verdict)
        assertEquals(CourtStatus.FAIL, report.status)
        assertTrue(report.escalate)
        assertTrue(report.reasons.any { it.contains("刑部") })
        assertTrue("假PASS 也要被暗卫抓到", report.ducha.fakePass)
    }

    @Test
    fun `大理寺FAIL_刑部暗卫无可挑_仍FAIL并交人`() {
        val report = ThreeCourts.convene(
            CourtReferral("d2", irreversible = true),
            missingFileSub(tmp.root),
            emptyList(),
            emptyList(),
            NOW,
        )
        assertEquals(ChancellorVerdict.FAIL, report.dali.verdict)
        assertEquals(CourtStatus.FAIL, report.status)
        assertTrue(report.escalate)
        assertTrue(report.reasons.any { it.contains("大理寺") })
    }

    @Test
    fun `三司全过_才PASS且不交人`() {
        val report = ThreeCourts.convene(
            CourtReferral("d3", userNamed = true),
            textOnlySub(),
            emptyList(),
            emptyList(),
            NOW,
        )
        assertEquals(CourtStatus.PASS, report.status)
        assertFalse(report.escalate)
        assertTrue(report.reasons.isEmpty())
    }

    @Test
    fun `负例_UNVERIFIABLE不许当PASS_也交人`() {
        val report = ThreeCourts.convene(
            CourtReferral("d4", learningIngest = true),
            unverifiableSub(),
            emptyList(),
            emptyList(),
            NOW,
        )
        assertEquals(ChancellorVerdict.UNVERIFIABLE, report.dali.verdict)
        assertEquals(CourtStatus.FAIL, report.status)
        assertTrue(report.escalate)
        assertTrue(report.reasons.any { it.contains("UNVERIFIABLE") })
    }

    // ── 刑部：机械事实核验 ────────────────────────────────────────────────

    @Test
    fun `刑部_文件在不在_字节对不对`() {
        val root = tmp.root
        val f = tmp.newFile("产物a.md")
        f.writeBytes(ByteArray(5))
        val ok = Xingbu.verify(
            root,
            listOf(FactSpec.fileExists("产物a.md"), FactSpec.bytesMatch("产物a.md", 5L)),
            ChancellorLedger(),
        )
        assertTrue(ok.all { it.ok })
        val bad = Xingbu.verify(
            root,
            listOf(FactSpec.bytesMatch("产物a.md", 6L), FactSpec.fileExists("不存在.md")),
            ChancellorLedger(),
        )
        assertTrue(bad.none { it.ok })
        assertTrue(bad.any { it.detail.contains("字节不符") })
    }

    @Test
    fun `刑部_账行有没有_快照里查键`() {
        val ledger = ChancellorLedger(fileOps = listOf(fileOpRow("产物/a.md")))
        val hits = Xingbu.verify(null, listOf(FactSpec.ledgerRow("产物/a.md")), ledger)
        assertTrue(hits.single().ok)
        val misses = Xingbu.verify(null, listOf(FactSpec.ledgerRow("产物/没记.md")), ledger)
        assertFalse(misses.single().ok)
    }

    @Test
    fun `负例_事实种类闭集_没有模型自述的口子`() {
        assertEquals(
            "FactKind 出现了闭集之外的种类（自述不是事实）",
            listOf("FILE_EXISTS", "BYTES_MATCH", "LEDGER_ROW"),
            FactKind.entries.map { it.name },
        )
        // 路径越界的核对单必须核不过（不许把「核不了」当「核过了」）。
        val probe = Xingbu.verify(tmp.root, listOf(FactSpec.fileExists("../逃逸.md")), ChancellorLedger())
        assertFalse(probe.single().ok)
        assertTrue(probe.single().detail.contains("越界"))
    }

    // ── 都察院（暗卫抽检）：假PASS 必记 L24 违规 ──────────────────────────

    @Test
    fun `暗卫_右相PASS而事实不符_记违规且L24累进`() {
        val bad = listOf(ProbeResult(FactKind.LEDGER_ROW, "产物/a.md", ok = false, detail = "账上没有"))
        val first = Duchayuan.spotCheck(ChancellorVerdict.PASS, bad, emptyList(), NOW)
        assertTrue(first.fakePass)
        assertNotNull("抽检发现假PASS 却不记违规", first.penalty)
        assertEquals(PenaltyTier.RECORD, first.penalty!!.tier)
        assertTrue(first.penalty!!.counted)
        // 同 kind 窗口内已有两笔 ⇒ 这次累进到 BLOCK（可再一再二，绝不可过三）。
        val history = listOf(
            Violation(kind = Duchayuan.VIOLATION_KIND, atMs = NOW - 1_000, evidenceSufficient = true),
            Violation(kind = Duchayuan.VIOLATION_KIND, atMs = NOW - 2_000, evidenceSufficient = true),
        )
        val third = Duchayuan.spotCheck(ChancellorVerdict.PASS, bad, history, NOW)
        assertEquals(PenaltyTier.BLOCK, third.penalty!!.tier)
    }

    @Test
    fun `暗卫_干净交付不记违规_非PASS判定不在抽查范围`() {
        val clean = Duchayuan.spotCheck(
            ChancellorVerdict.PASS,
            listOf(ProbeResult(FactKind.FILE_EXISTS, "产物/a.md", ok = true, detail = "文件在")),
            emptyList(),
            NOW,
        )
        assertFalse(clean.fakePass)
        assertNull(clean.penalty)
        val skipped = Duchayuan.spotCheck(ChancellorVerdict.FAIL, listOf(ProbeResult(FactKind.FILE_EXISTS, "x", ok = false, detail = "")), emptyList(), NOW)
        assertFalse(skipped.fakePass)
        assertNull(skipped.penalty)
    }

    // ── 判据③：右相不得接学习器（源级门禁） ──────────────────────────────

    /** 只扫代码行（照 ChancellorSourceGateTest 的剔注释口径）。 */
    private fun scan(src: String, forbidden: List<String>): List<String> = src.lineSequence()
        .map { it.trimStart() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("*/") }
        .map { it.substringBefore("//") }
        .flatMap { line -> forbidden.filter { line.contains(it) }.map { "$it ← ${line.trim().take(80)}" } }
        .toList()

    private fun verifyFile(name: String): File =
        File("src/main/java/com/example/zhengdao/baopu/verify/$name")

    @Test
    fun `门禁_合成违规行会被抓出来`() {
        val violations = listOf(
            "import com.example.zhengdao.baopu.agent.LearnWiring",
            "val advice = LearnWiring.conclusionsFor(delivery)",
            "val ledger = DecisionLedger.read(root)",
            "val now = System.currentTimeMillis()",
            "f.writeText(\"内容\")",
        )
        for (line in violations) {
            assertTrue(
                "门禁没咬人：$line",
                scan(line, listOf("LearnWiring", "DecisionLedger", "System.currentTimeMillis", "writeText")).isNotEmpty(),
            )
        }
    }

    @Test
    fun `判据③_右相模块零学习器引用_接了建议即红`() {
        val banned = listOf("LearnWiring", "JudgmentExperience", "ExperienceRow", "onUserSignal")
        val files = listOf("ChancellorGate.kt", "ChancellorInput.kt", "ChancellorWiring.kt")
        val hits = files.flatMap { name ->
            scan(verifyFile(name).readText(Charsets.UTF_8), banned).map { "$name: $it" }
        }
        assertTrue("右相模块出现学习器引用：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `本批新件_同守verify包纪律_零时钟零写盘零全局账本_不碰313片区`() {
        val banned = listOf(
            "System.currentTimeMillis", "System.nanoTime",
            "LlmClient", "ModelRouter", "RunEngine", "kotlinx.coroutines", "Thread(",
            "writeText", "appendText", "mkdir",
            "DecisionLedger", "FileOpLog", "BaopuDb",
            "JinwuPatrol", "GapItem", "GapFeedback",
        )
        val files = listOf("ThreeCourts.kt", "JudgeRegression.kt")
        val hits = files.flatMap { name ->
            scan(verifyFile(name).readText(Charsets.UTF_8), banned).map { "$name: $it" }
        }
        assertTrue("三司新件出现越界依赖：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    // ── 判据④：锚定一致率下降 ⇒ 报退化（造数据可验） ─────────────────────

    /** 假判官：答案表里没点名的按期望值判（＝同意）。 */
    private fun judgeOf(answers: Map<String, ChancellorVerdict>) = AnchoredJudge { c ->
        answers[c.id] ?: c.expected
    }

    private fun tenCases(): List<AnchoredCase> = (1..10).map { i ->
        AnchoredCase("s$i", "锚定案由 $i", ChancellorVerdict.PASS)
    }

    @Test
    fun `判据④_一致率不掉_不报退化`() {
        val readout = JudgeRegression.run(tenCases(), judgeOf(emptyMap()), baselineRate = 1.0)
        assertEquals(1.0, readout.rate, 1e-9)
        assertFalse(readout.degraded)
    }

    @Test
    fun `判据④_一致率下降超阈_报退化并带不一致清单`() {
        val flips = mapOf("s3" to ChancellorVerdict.UNVERIFIABLE, "s7" to ChancellorVerdict.FAIL)
        val readout = JudgeRegression.run(tenCases(), judgeOf(flips), baselineRate = 1.0)
        assertEquals(0.8, readout.rate, 1e-9)
        assertTrue(readout.degraded)
        assertEquals(listOf("s3：期望 PASS，实判 UNVERIFIABLE", "s7：期望 PASS，实判 FAIL"), readout.disagreements)
    }

    @Test
    fun `判据④_掉幅恰在阈内_不算退化`() {
        // 基线 0.95、本次 0.9：掉幅 0.05 = 阈本身（< 严格成立才退化）⇒ 不算退化。
        val flips = mapOf("s10" to ChancellorVerdict.FAIL)
        val readout = JudgeRegression.run(tenCases(), judgeOf(flips), baselineRate = 0.95)
        assertEquals(0.9, readout.rate, 1e-9)
        assertFalse(readout.degraded)
    }

    @Test
    fun `回归_首轮无基线_不判退化由调用方存rate`() {
        val readout = JudgeRegression.run(tenCases(), judgeOf(emptyMap()), baselineRate = null)
        assertNull(readout.baselineRate)
        assertFalse(readout.degraded)
    }

    @Test
    fun `回归_零样本拒收_不产出假读数`() {
        var thrown: IllegalArgumentException? = null
        try {
            JudgeRegression.run(emptyList(), judgeOf(emptyMap()), baselineRate = 1.0)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull(thrown)
    }
}
