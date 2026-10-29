// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十九批 §2 的判据靶：⑧三块齐且无证据的结论拒入报告、⑨三行红线原样不动，
// 外加「报告是输出不是卡」与「留痕可回读」。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoldenGuardTest {

    private val srcRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")

    private fun finding(
        check: InspectCheck = InspectCheck.AUDIT_GAP,
        verdict: InspectVerdict = InspectVerdict.HIT,
        evidence: List<String> = listOf("decision:1"),
        oneLine: String = "漏了一行",
    ) = InspectFinding(
        check = check,
        verdict = verdict,
        oneLine = oneLine,
        suggestion = "补上那一行",
        evidence = evidence,
    )

    // ── 判据⑧：三块齐 ───────────────────────────────────────────────────────

    @Test
    fun `判据⑧_三块齐且结论不超过三行`() {
        val digest = GoldenGuard.digest(
            findings = listOf(finding(), finding(verdict = InspectVerdict.NO_DATA), finding(check = InspectCheck.SHADOW_MISMATCH)),
            reviews = listOf(DigestReview("night:1", 1L, ReviewVerdict.FALSE_POSITIVE)),
            nowMs = 100L,
        )
        assertTrue("三块不齐：${digest.conclusion}／${digest.counters}／${digest.issues.size}", digest.fitsForm)
        assertTrue(digest.conclusion.size in 1..DigestLimits.CONCLUSION_MAX_LINES)
        assertTrue(digest.counters.isNotEmpty())
        assertEquals(2, digest.issues.size)
        assertTrue(digest.conclusion.all { it.length <= InspectLimits.MAX_LINE_CHARS })
    }

    @Test
    fun `负例_无证据的结论不许进报告`() {
        val digest = GoldenGuard.digest(
            findings = listOf(finding(evidence = emptyList())),
            reviews = emptyList(),
            nowMs = 100L,
        )
        assertTrue("无证据的命中不许进清单", digest.issues.isEmpty())
        assertTrue(
            "无证据这件事要在结论里露字：${digest.conclusion}",
            digest.conclusion.any { it.contains("无证据") },
        )
        assertTrue(digest.counters.any { it.contains("无证据 1") })
        try {
            DigestIssue("guard:1#1", "一句话", LawRef.NONE, emptyList())
            fail("DigestIssue 构造期就该拒（L14）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("L14"))
        }
    }

    @Test
    fun `计数表的数字来自真数据`() {
        val digest = GoldenGuard.digest(
            findings = listOf(
                finding(evidence = listOf("decision:7", "trace:r1#3")),
                finding(check = InspectCheck.NUMBER_GROUNDING, evidence = listOf("answer:9")),
            ),
            reviews = emptyList(),
            nowMs = 1L,
        )
        val counts = digest.counters.first()
        assertTrue(counts, counts.contains("记录 1") && counts.contains("轨迹 1") && counts.contains("其它 1"))
        assertTrue(
            "没有池子读数就不许写「查了 N 条」：${digest.counters}",
            digest.counters.none { it.contains("查了") },
        )
    }

    // ── 默认只显示前两块 ────────────────────────────────────────────────────

    @Test
    fun `默认只显示前两块`() {
        val digest = GoldenGuard.digest(listOf(finding()), emptyList(), 1L)
        assertEquals(digest.conclusion + digest.counters, digest.visibleBlocks)
        assertTrue("证据清单不许混进默认两块", digest.visibleBlocks.none { it.contains("decision:1") })
    }

    // ── 判据⑨：三行红线原样不动，报告另立 ───────────────────────────────────

    @Test
    fun `判据⑨_三行红线与报告形制各归各`() {
        val vocab = code(File(srcRoot, "InspectVocab.kt").readText(Charsets.UTF_8))
        assertTrue("三行红线必须原样在判据层", vocab.contains("MAX_REPORT_LINES = 3"))
        val guard = code(File(srcRoot, "GoldenGuard.kt").readText(Charsets.UTF_8))
        assertFalse("报告不许塞进 PatrolReport", guard.contains("PatrolReport("))
        assertFalse("报告不许改三行常量", guard.contains("MAX_REPORT_LINES"))
        assertTrue("报告要另立自己的结论行上限", guard.contains("CONCLUSION_MAX_LINES"))
        // 两份报告各是各的类型：夜巡那趟仍受三行约束，报告走自己的形制
        val patrol = Inspector.report(InspectCheck.entries.map { finding(check = it) }, 1L)
        assertTrue(patrol.fitsForm)
        assertTrue("三行报告不许被撑到第三行以外", patrol.lines.size <= InspectLimits.MAX_REPORT_LINES - 1)
        assertTrue(GoldenGuard.digest(listOf(finding()), emptyList(), 1L).fitsForm)
    }

    @Test
    fun `负例_报告不许自己升级成弹卡也不碰预算`() {
        val guard = File(srcRoot, "GoldenGuard.kt").readText(Charsets.UTF_8)
        val text = code(guard)
        listOf("OpinionWiring", "Budget(", "AlertDialog", "Dialog(", "Toast", "Snackbar", "import com.example.zhengdao.ui.")
            .forEach { word -> assertFalse("报告里出现了 $word（报告是输出不是卡）", text.contains(word)) }
    }

    // ── 留痕可回读 ──────────────────────────────────────────────────────────

    @Test
    fun `报告的留痕能原样读回来`() {
        val digest = GoldenGuard.digest(
            findings = listOf(finding(evidence = listOf("decision:1", "trace:r#1"))),
            reviews = listOf(
                DigestReview("night:1", 5L, ReviewVerdict.CONFIRMED, listOf("decision:1")),
                DigestReview("night:2", 6L, ReviewVerdict.NO_DATA),
            ),
            nowMs = 10L,
        )
        val json = GuardLedger.toJson(digest)
        assertFalse("留痕里不许出现正文键", json.has("text") || json.has("body"))
        val back = GuardLedger.parse(json.toString())
        assertEquals(digest.id, back!!.id)
        assertEquals(digest.conclusion, back.conclusion)
        assertEquals(digest.counters, back.counters)
        assertEquals(digest.issues, back.issues)
        assertEquals(digest.reviews, back.reviews)
    }

    @Test
    fun `留痕_坏行跳过`() {
        assertNull(GuardLedger.parse("{\"at\":1}"))
        assertNull(GuardLedger.parse("{}"))
    }

    @Test
    fun `查不了的项要露字`() {
        val digest = GoldenGuard.digest(
            findings = listOf(finding(verdict = InspectVerdict.NO_DATA)),
            reviews = emptyList(),
            nowMs = 1L,
        )
        assertTrue("藏掉「没查成」是批要纠的弊", digest.conclusion.any { it.contains("查不了 1") })
        assertTrue(digest.counters.any { it.contains("查不了 1 项") })
    }

    /** 注释里写「不许」是说明，不是调用（照 239 的同一把尺）。 */
    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")
}
