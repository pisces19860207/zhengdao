// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十二批 · 旁路对拍器的判据（法典 L28 的门禁）。
//
// 全部走纯核（[TraceCrossCheck]，零 IO 零 Android，JVM 直测）：
//   - §2.5 判据：假 RunTrace（3 条）＋ 假 208（2 条）⇒ 必须报「少 1 条」；
//   - 负例①：一侧缺数据 ⇒ 报「无法对拍」，不许当一致；
//   - 负例②：容差不许调成「全部都算一致」（±20% 钉死，量级差必报）；
//   - 负例③：对拍器不写账（源码钉：两个新文件零写口）；
//   - 负例④：发现不一致必须产 finding（opinions() 非空、形状按 270 契约）；
//   - 负例⑤：连续 3 次不一致必须升 CRITICAL。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceCrossCheckTest {

    private val runId = "r7@1700000000000"
    private val writeNames = setOf("write_file", "edit_file")

    /** 轨迹行（默认＝写侧成功一次：ACT/OK/write_file，窗在 10_000..20_000 之内）。 */
    private fun edge(
        seq: Int,
        step: Int = 1,
        phase: String = "ACT",
        status: String = "OK",
        tool: String = "write_file",
        dur: Long = 0,
        fail: String = "",
        started: Long = 10_000L,
        ended: Long = 10_500L,
    ): TraceEdge = TraceEdge(runId, 7L, seq, step, phase, status, tool, dur, fail, started, ended)

    /** 208 行（默认＝GuardPipeline 同款 auto_act：`tool:write_file:<target>`）。 */
    private fun rec(
        id: String,
        atMs: Long,
        kind: DecisionKind = DecisionKind.AUTO_ACT,
        evidence: String = "tool:write_file:p",
        reason: String = "工具 write_file 执行成功",
    ): DecisionRecord = DecisionRecord(
        id, kind, DecisionGroup.ARTIFACT, DecisionActor.MODEL,
        reason, evidence, "before", "after", atMs, -1L, false, "",
    )

    private fun check(trace: List<TraceEdge>, ledger: List<DecisionRecord>): Map<CrossCheckDimension, CrossCheckFinding> =
        TraceCrossCheck.checkRun(runId, trace, ledger, writeNames).associateBy { it.dimension }

    private fun finding(
        trace: List<TraceEdge>,
        ledger: List<DecisionRecord>,
        dim: CrossCheckDimension,
    ): CrossCheckFinding = check(trace, ledger).getValue(dim)

    // ── §2.5 判据：差一条就报 ─────────────────────────────────────────────

    @Test
    fun `判据_假轨迹3条假账2条_必报少一条`() {
        val trace = listOf(
            edge(seq = 1, ended = 10_100),
            edge(seq = 2, ended = 10_200),
            edge(seq = 3, ended = 10_300),
        )
        val ledger = listOf(
            rec("auto_act:1", 10_101),
            rec("auto_act:2", 10_201),
        )
        val f = finding(trace, ledger, CrossCheckDimension.TOOL_CALLS)
        assertEquals(CrossCheckVerdict.MISMATCH, f.verdict)
        assertEquals(3, f.traceCount)
        assertEquals(2, f.ledgerCount)
        // evidence 带两侧证据 id：短的那侧（轨迹第 3 条）必须在列。
        assertTrue(f.evidence.any { it.startsWith("trace:$runId#3") })
    }

    @Test
    fun `判据_账多一条也报_伪造方向不豁免`() {
        val trace = listOf(edge(seq = 1, ended = 10_100))
        val ledger = listOf(
            rec("auto_act:1", 10_050),
            rec("auto_act:2", 10_060),
        )
        val f = finding(trace, ledger, CrossCheckDimension.TOOL_CALLS)
        assertEquals(CrossCheckVerdict.MISMATCH, f.verdict)
        assertEquals(1, f.traceCount)
        assertEquals(2, f.ledgerCount)
        assertTrue("多出的那条 208 行要给 id", f.evidence.any { it.contains("auto_act:") })
    }

    @Test
    fun `判据_同数错名也是差_不是只比总数`() {
        val trace = listOf(
            edge(seq = 1, tool = "write_file", ended = 10_100),
            edge(seq = 2, tool = "edit_file", ended = 10_200),
        )
        val ledger = listOf(
            rec("auto_act:1", 10_101, evidence = "tool:write_file:p"),
            rec("auto_act:2", 10_105, evidence = "tool:write_file:q"),
        )
        val f = finding(trace, ledger, CrossCheckDimension.TOOL_CALLS)
        assertEquals("各 2 条但 edit_file 没配上：也是伪造，必须报", CrossCheckVerdict.MISMATCH, f.verdict)
        assertTrue(f.evidence.any { it.contains("edit_file") })
    }

    @Test
    fun `判据_对上了_四维读数给全`() {
        val trace = listOf(
            edge(seq = 1, step = 1, phase = "THINK", tool = "", dur = 1_000, started = 10_000, ended = 11_000),
            edge(seq = 2, step = 1, ended = 10_100),
            edge(seq = 3, step = 2, phase = "THINK", tool = "", dur = 1_000, started = 11_000, ended = 12_000),
            edge(seq = 4, step = 2, ended = 11_100),
        )
        val ledger = listOf(
            rec("auto_act:1", 10_101),
            rec("auto_act:2", 11_101),
        )
        val all = check(trace, ledger)
        assertEquals("恰好四维，一维一条", 4, all.size)
        assertEquals(CrossCheckDimension.entries.toSet(), all.keys)
        assertEquals(CrossCheckVerdict.CLEAR, all.getValue(CrossCheckDimension.TOOL_CALLS).verdict)
        assertEquals(2, all.getValue(CrossCheckDimension.TOOL_CALLS).traceCount)
        assertEquals(CrossCheckVerdict.CLEAR, all.getValue(CrossCheckDimension.FAIL_KINDS).verdict)
        // ③ 永远缺侧（208 写侧契约没有步边界行）——在「都好」的 run 里也不许冒充一致。
        assertEquals(CrossCheckVerdict.MISSING_SIDE, all.getValue(CrossCheckDimension.STEPS).verdict)
        assertEquals(2, all.getValue(CrossCheckDimension.STEPS).traceCount)
        // ④：步耗时和 2000ms，账窗 10_101..11_101 = 1000ms，偏差 50% ⇒ 报。
        assertEquals(CrossCheckVerdict.MISMATCH, all.getValue(CrossCheckDimension.DURATION).verdict)
    }

    // ── ①的口径：只比「非只读＋成功」的 like-for-like ────────────────────

    @Test
    fun `口径①_只读工具不进比对_账里1条配1条算清白`() {
        val trace = listOf(
            edge(seq = 1, tool = "write_file", ended = 10_100),
            edge(seq = 2, tool = "read_file", ended = 10_200),
        )
        val ledger = listOf(rec("auto_act:1", 10_101))
        val f = finding(trace, ledger, CrossCheckDimension.TOOL_CALLS)
        assertEquals(CrossCheckVerdict.CLEAR, f.verdict)
        assertEquals("轨迹侧只数写侧名册内的", 1, f.traceCount)
        assertEquals(1, f.ledgerCount)
    }

    @Test
    fun `口径①_失败与未批的工具不进①_它们归②`() {
        val trace = listOf(
            edge(seq = 1, status = "FAILED", fail = "TOOL_ERROR", ended = 10_100),
            edge(seq = 2, status = "CONFIRM", ended = 10_200),
        )
        val ledger = listOf(rec("auto_act:9", 99_999)) // 窗外
        val f = finding(trace, ledger, CrossCheckDimension.TOOL_CALLS)
        assertEquals("轨迹侧没有可配对行，账侧窗外也没有：两侧都空 ⇒ 清白", CrossCheckVerdict.CLEAR, f.verdict)
        // 而失败那两条进 ②。
        val fails = finding(trace, emptyList(), CrossCheckDimension.FAIL_KINDS)
        assertEquals(1, fails.traceCount)
    }

    // ── ② 失败分类 ───────────────────────────────────────────────────────

    @Test
    fun `口径②_轨迹2条失败账0条_报不符`() {
        val trace = listOf(
            edge(seq = 1, phase = "THINK", tool = "", status = "FAILED", fail = "TOOL_ERROR", ended = 10_100),
            edge(seq = 2, status = "FAILED", fail = "NETWORK", ended = 10_200),
        )
        val ledger = listOf(rec("auto_act:1", 10_050))
        val f = finding(trace, ledger, CrossCheckDimension.FAIL_KINDS)
        assertEquals(CrossCheckVerdict.MISMATCH, f.verdict)
        assertEquals(2, f.traceCount)
        assertEquals(0, f.ledgerCount)
        assertTrue(f.evidence.any { it.contains("TOOL_ERROR") })
    }

    @Test
    fun `口径②_账里自认失败1条_2比1照报`() {
        val trace = listOf(
            edge(seq = 1, phase = "THINK", tool = "", status = "FAILED", fail = "TOOL_ERROR", ended = 10_100),
            edge(seq = 2, status = "FAILED", fail = "NETWORK", ended = 10_200),
        )
        val ledger = listOf(rec("auto_act:1", 10_150, reason = "A 档自改没通过对拍，自动退回"))
        val f = finding(trace, ledger, CrossCheckDimension.FAIL_KINDS)
        assertEquals(CrossCheckVerdict.MISMATCH, f.verdict)
        assertEquals(1, f.ledgerCount)
    }

    @Test
    fun `口径②_两侧都0_清白`() {
        val trace = listOf(edge(seq = 1))
        val ledger = listOf(rec("auto_act:1", 10_101))
        assertEquals(CrossCheckVerdict.CLEAR, finding(trace, ledger, CrossCheckDimension.FAIL_KINDS).verdict)
    }

    // ── ③ 步数：208 没有步边界行 ⇒ 永远缺侧，不许当一致 ───────────────────

    @Test
    fun `口径③_步数永远缺侧_不许当一致`() {
        val trace = listOf(
            edge(seq = 1, step = 1),
            edge(seq = 2, step = 2, ended = 11_000),
            edge(seq = 3, step = 3, ended = 12_000),
        )
        val f = finding(trace, listOf(rec("auto_act:1", 10_101)), CrossCheckDimension.STEPS)
        assertEquals(CrossCheckVerdict.MISSING_SIDE, f.verdict)
        assertEquals(3, f.traceCount)
        assertEquals(0, f.ledgerCount)
        assertTrue(f.evidence.first().contains("步边界"))
    }

    // ── ④ 耗时量级：±20% 容差，只比量级 ──────────────────────────────────

    @Test
    fun `口径④_偏差20%整算清白_超了算不符`() {
        // 两步各 1000ms，和 2000；账窗（10_000..11_600）＝1600 ⇒ 偏差恰 20% ⇒ 清白（闭区间）。
        val trace = listOf(
            edge(seq = 1, phase = "THINK", tool = "", dur = 1_000, started = 10_000, ended = 11_000),
            edge(seq = 2, phase = "THINK", tool = "", dur = 1_000, started = 11_000, ended = 12_000),
        )
        assertEquals(
            CrossCheckVerdict.CLEAR,
            finding(trace, listOf(rec("a", 10_000), rec("b", 11_600)), CrossCheckDimension.DURATION).verdict,
        )
        // 账窗 1540 ⇒ 偏差 23% ⇒ 报。
        assertEquals(
            CrossCheckVerdict.MISMATCH,
            finding(trace, listOf(rec("a", 10_000), rec("b", 11_540)), CrossCheckDimension.DURATION).verdict,
        )
    }

    @Test
    fun `口径④_负例②_量级差十倍必须报_容差调不成恒一致`() {
        assertEquals(0.20, TraceCrossCheck.DURATION_TOLERANCE, 0.0)
        // 步耗时和 1000ms；账侧行同窗内挤在一处（跨度 90ms）⇒ 差一个量级，必须报。
        val trace = listOf(edge(seq = 1, phase = "THINK", tool = "", dur = 1_000, started = 10_000, ended = 11_000))
        val ledger = listOf(rec("a", 10_000), rec("b", 10_090))
        val f = finding(trace, ledger, CrossCheckDimension.DURATION)
        assertEquals(CrossCheckVerdict.MISMATCH, f.verdict)
        assertEquals(90, f.ledgerCount.toLong())
    }

    @Test
    fun `口径④_没测到的耗时不算0_账侧不足两行算缺侧`() {
        val trace = listOf(
            edge(seq = 1, phase = "THINK", tool = "", dur = -1, started = 10_000, ended = 11_000),
        )
        val ledger = listOf(rec("a", 10_000), rec("b", 10_500))
        assertEquals(
            "全没测到 ⇒ 缺侧，不许拿 0 冒充",
            CrossCheckVerdict.MISSING_SIDE,
            finding(trace, ledger, CrossCheckDimension.DURATION).verdict,
        )
        val oneRow = finding(listOf(edge(seq = 1, phase = "THINK", tool = "", dur = 1_000)), listOf(rec("a", 10_000)), CrossCheckDimension.DURATION)
        assertEquals(CrossCheckVerdict.MISSING_SIDE, oneRow.verdict)
    }

    // ── 负例①：一侧缺数据 ⇒ 无法对拍，不许当一致 ─────────────────────────

    @Test
    fun `负例①_轨迹空_四维全部缺侧`() {
        val all = TraceCrossCheck.checkRun(runId, emptyList(), listOf(rec("a", 10_000)), writeNames)
        assertEquals(4, all.size)
        all.forEach { assertEquals(CrossCheckVerdict.MISSING_SIDE, it.verdict) }
    }

    @Test
    fun `负例①_208整本空_缺侧不许当一致`() {
        val trace = listOf(
            edge(seq = 1, ended = 10_100),
            edge(seq = 2, phase = "THINK", tool = "", status = "FAILED", fail = "TOOL_ERROR", ended = 10_200),
        )
        val all = check(trace, emptyList())
        assertEquals(CrossCheckVerdict.MISSING_SIDE, all.getValue(CrossCheckDimension.TOOL_CALLS).verdict)
        assertEquals(CrossCheckVerdict.MISSING_SIDE, all.getValue(CrossCheckDimension.FAIL_KINDS).verdict)
        assertEquals(CrossCheckVerdict.MISSING_SIDE, all.getValue(CrossCheckDimension.DURATION).verdict)
        all.values.forEach { assertFalse("缺侧永不算一致", it.verdict == CrossCheckVerdict.CLEAR) }
    }

    @Test
    fun `负例①_账窗盖不住的老run_缺侧不误报`() {
        // 208 只读得到很新的行（READ_LIMIT 截断的房型）：老 run 的窗在可见账窗之前 ⇒ 缺侧，
        // 不是「账 0 条」的假不符（I1「账窗之外不冒充看见」同款）。
        val trace = listOf(edge(seq = 1, ended = 10_100))
        val ledger = listOf(rec("auto_act:new", 900_000))
        assertEquals(
            CrossCheckVerdict.MISSING_SIDE,
            finding(trace, ledger, CrossCheckDimension.TOOL_CALLS).verdict,
        )
    }

    @Test
    fun `口径_账行落在窗外的_不计入这个run的账侧`() {
        val trace = listOf(edge(seq = 1, ended = 10_100))
        // 10_050 在窗内（配对成功）；9_000 在窗前、99_999 在窗后——都不进账侧计数。
        val ledger = listOf(
            rec("auto_act:old", 9_000),
            rec("auto_act:1", 10_050),
            rec("auto_act:new", 99_999),
        )
        val f = finding(trace, ledger, CrossCheckDimension.TOOL_CALLS)
        assertEquals(CrossCheckVerdict.CLEAR, f.verdict)
        assertEquals(1, f.ledgerCount)
    }

    // ── 负例⑤：连续 3 次不一致 ⇒ 升格 CRITICAL ──────────────────────────

    @Test
    fun `负例⑤_连续2次MAJOR_第3次升CRITICAL`() {
        val mm = CrossCheckVerdict.MISMATCH
        assertEquals(2, TraceCrossCheck.streakOf(listOf(mm, mm)))
        assertEquals(Severity.MAJOR, TraceCrossCheck.severityOf(mm, 2))
        assertEquals(3, TraceCrossCheck.streakOf(listOf(mm, mm, mm)))
        assertEquals(Severity.CRITICAL, TraceCrossCheck.severityOf(mm, 3))
        assertEquals(Severity.CRITICAL, TraceCrossCheck.severityOf(mm, 5))
    }

    @Test
    fun `负例⑤_清白与缺侧都断链_不许攒虚张的连续`() {
        val mm = CrossCheckVerdict.MISMATCH
        assertEquals(2, TraceCrossCheck.streakOf(listOf(mm, CrossCheckVerdict.CLEAR, mm, mm)))
        assertEquals(1, TraceCrossCheck.streakOf(listOf(mm, CrossCheckVerdict.MISSING_SIDE, mm)))
        assertEquals("没查成的一趟不算「连续不一致」", Severity.MAJOR, TraceCrossCheck.severityOf(mm, 1))
    }

    // ── 负例④：产的是 finding ＋ Opinion（270 契约形状），不是只 log ──────

    @Test
    fun `负例④_不一致产Opinion_source等于PATROL_lawRef等于L28`() {
        val trace = listOf(
            edge(seq = 1, ended = 10_100),
            edge(seq = 2, ended = 10_200),
            edge(seq = 3, ended = 10_300),
        )
        val ledger = listOf(rec("auto_act:1", 10_101), rec("auto_act:2", 10_201))
        val opinions = TraceCrossCheck.opinions(
            TraceCrossCheck.checkRun(runId, trace, ledger, writeNames),
        )
        val mismatch = opinions.filter { it.gist == "对拍不符" }
        assertTrue("少 1 条必须出意见", mismatch.isNotEmpty())
        mismatch.forEach { o ->
            assertEquals(Source.PATROL, o.source)
            assertEquals(LawRef.L28, o.lawRef)
            assertEquals(ActionCode.REVIEW, o.action)
            assertTrue(o.evidence.isNotEmpty())
            assertTrue(o.evidence.all { it.isNotBlank() })
        }
        // 缺侧（③步数）也出意见：MINOR、走补证据。
        val missing = opinions.filter { it.gist == "对拍缺侧" }
        assertTrue(missing.isNotEmpty())
        missing.forEach { o ->
            assertEquals(Severity.MINOR, o.severity)
            assertEquals(ActionCode.SUPPLEMENT_EVIDENCE, o.action)
            assertEquals(LawRef.L28, o.lawRef)
        }
    }

    @Test
    fun `负例④_连续3次不一致的意见升CRITICAL`() {
        val mm = listOf(
            CrossCheckFinding("r1", CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISMATCH, 3, 2, listOf("trace:r1#3")),
            CrossCheckFinding("r2", CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISMATCH, 3, 2, listOf("trace:r2#3")),
            CrossCheckFinding("r3", CrossCheckDimension.TOOL_CALLS, CrossCheckVerdict.MISMATCH, 3, 2, listOf("trace:r3#3")),
        )
        val opinions = TraceCrossCheck.opinions(mm)
        assertEquals(3, opinions.size)
        assertEquals("构成连续三次的那段整段升格", Severity.CRITICAL, opinions.last().severity)
        assertEquals(Severity.CRITICAL, opinions.first().severity)
    }

    // ── 负例③：对拍器不写账（源码钉）＋ SQL 上界（源码钉）──────────────────

    @Test
    fun `负例③_两个新文件零写口_对拍器不写208也不写自己`() {
        val dir = File("src/main/java/com/example/zhengdao/baopu/inspect")
        val src = (listOf("TraceCrossCheck.kt", "TraceCrossCheckSources.kt").map { f ->
            File(dir, f).let { if (it.exists()) it else File("app/$dir", f) }.readText()
                .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
                .replace(Regex("(?m)//.*$"), " ")
        })
        val banned = listOf(
            "appendText", "writeText", "writeBytes", "execSQL", "INSERT INTO", "UPDATE ",
            "DELETE FROM", "mkdirs(", "DecisionLedger.record(", "DecisionLedger.install(",
        )
        src.forEachIndexed { i, text ->
            banned.forEach { word ->
                assertFalse("第 ${i + 1} 个对拍器文件里有写口 $word", text.contains(word))
            }
        }
        // 208 只许读。
        assertTrue(src[1].contains("DecisionLedger.read("))
    }

    @Test
    fun `负例④_取数壳每条SQL都带LIMIT`() {
        val f = File("src/main/java/com/example/zhengdao/baopu/inspect/TraceCrossCheckSources.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/inspect/TraceCrossCheckSources.kt") }
        val text = f.readText().replace(Regex("(?m)//.*$"), " ")
        val marks = Regex("""rawQuery\(""").findAll(text).map { it.range.first }.toList()
        assertEquals(2, marks.size)
        marks.forEachIndexed { i, start ->
            val end = if (i + 1 < marks.size) marks[i + 1] else text.length
            assertTrue("第 ${i + 1} 条查询没有 LIMIT", text.substring(start, end).contains("LIMIT"))
        }
    }

    // ── 接线（源码钉，归真机验证——本仓无 Robolectric）────────────────────

    @Test
    fun `接线_巡按挂了对拍_结果带crosscheck`() {
        val f = File("src/main/java/com/example/zhengdao/baopu/inspect/PatrolRunner.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/inspect/PatrolRunner.kt") }
        val text = f.readText()
        assertTrue(text.contains("TraceCrossCheckSources.pass("))
        assertTrue(text.contains("crosscheck"))
        assertNotNull(crossCheckPassShape())
    }

    /** 形状钉：CrossCheckPass 的字段名（checked／findings／opinions／summaryLine）。 */
    private fun crossCheckPassShape(): CrossCheckPass =
        CrossCheckPass(0, emptyList(), emptyList(), "对拍：窗内没有 run")
}
