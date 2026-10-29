// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百八十一批：读数层的用例。钉四件事：
// ① 每个数都点得开（证据 id 有前缀、有上限、总数照实记）；
// ② 分母 <10 ⇒ unknown 且**一个字都不许出现 %**；
// ③ 口径与本仓的 `docs/METRICS.md` 对得上（文档里每个 key 都要在词表里，反之亦然）；
// ④ 同一份数据两次算出同一个数（输入顺序不影响、两个入口同一个对象）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.AcceptanceSample
import com.example.zhengdao.baopu.AcceptanceState
import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.DecisionReview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsTest {

    private val now = 1_760_000_000_000L
    private val day = 86_400_000L

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private fun row(
        id: String,
        at: Long = now,
        kind: DecisionKind = DecisionKind.AUTO_ACT,
        reversible: Boolean = true,
        rollbackOf: String? = null,
    ) = DecisionRecord(
        id = id,
        kind = kind,
        group = DecisionGroup.GRANT,
        actor = DecisionActor.MODEL,
        reason = "夹具",
        evidence = "tool:file_write:a.txt",
        before = "",
        after = "",
        atMs = at,
        sessionId = -1L,
        reversible = reversible,
        rollbackRef = "",
        rollbackOf = rollbackOf,
    )

    private fun review(recordId: String, verdict: String, at: Long = now) = DecisionReview.Entry(
        recordId = recordId,
        kind = "auto_act",
        verdict = verdict,
        actionSummary = "",
        matrixVersion = "m1",
        reasonCode = "auto_act",
        evidence = "",
        atMs = at,
    )

    private fun act(at: Long = now, sessionId: Long = 1L, step: Int = 1) =
        WriteTrace(atMs = at, tool = "file_write", sessionId = sessionId, step = step)

    private fun log(sessionId: Long = 1L, step: Int = 1, state: String = Metrics.STATE_ACTING, at: Long = now) =
        LogStep(id = step.toLong(), sessionId = sessionId, step = step, state = state, atMs = at)

    private fun run(runId: String, sessionId: Long = 1L, step: Int = 1, at: Long = now) =
        RunStep(runId = runId, sessionId = sessionId, step = step, atMs = at)

    private fun sub(taskId: String, status: String, at: Long = now) = SubTaskRow(taskId, status, at)

    private fun patrol(id: String, at: Long = now, verdicts: List<InspectVerdict>) = PatrolEntry(
        id = id,
        atMs = at,
        headline = "",
        lines = emptyList(),
        hits = verdicts.count { it == InspectVerdict.HIT },
        noData = verdicts.count { it == InspectVerdict.NO_DATA },
        manual = false,
        elapsedMs = 0L,
        findings = verdicts.mapIndexed { idx, v ->
            InspectFinding(InspectCheck.entries[idx], v, "x", "y")
        },
    )

    private fun skill(name: String, samples: List<AcceptanceSample>, demotedAt: Long? = null) =
        SkillSnapshot(
            scope = "project",
            name = name,
            state = AcceptanceState(samples = samples, demotedAtMs = demotedAt),
        )

    private fun sample(op: String, ok: Boolean, at: Long = now) =
        AcceptanceSample(opId = op, atMs = at, ok = ok, fingerprintOk = true, driftCount = 0, site = "a.com")

    private fun line(inputs: Metrics.Inputs, key: MetricKey): MetricLine =
        requireNotNull(Metrics.detail(inputs, key)) { "读数里没有 $key" }

    // ── 判据②：分母 <10 ⇒ unknown 且不印百分比 ─────────────────────────────

    @Test
    fun `判据②_分母九条_只报分子分母_不出现百分比`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..9).map { row("a$it") } + row("rb", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a1"),
        )
        val line = line(inputs, MetricKey.VIOLATION)
        assertEquals(1, line.ratio.num)
        assertEquals(9, line.ratio.denom)
        assertFalse("分母 9 条不许算率", line.known)
        val text = line.display()
        assertTrue(text, text.contains("分子 1／分母 9"))
        assertFalse("unknown 不许出现百分比：$text", text.contains("%"))
        assertTrue(text, text.contains("算不出率"))
    }

    @Test
    fun `判据②_分母十条起才给百分比`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..10).map { row("a$it") } +
                listOf(
                    row("rb1", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a1"),
                    row("rb2", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a2"),
                ),
        )
        val line = line(inputs, MetricKey.VIOLATION)
        assertTrue(line.known)
        assertEquals(20.0, line.ratio.value!!, 1e-9)
        assertTrue(line.display(), line.display().contains("20%"))
    }

    @Test
    fun `判据②_零分母是unknown不是零发生`() {
        val line = line(Metrics.Inputs(nowMs = now), MetricKey.MISJUDGE)
        assertFalse(line.known)
        assertFalse(line.display().contains("%"))
        assertTrue(line.display().contains("分母 0"))
    }

    // ── 四率 ───────────────────────────────────────────────────────────────

    @Test
    fun `违规率_被撤销的免问动作_按原行id去重`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..10).map { row("a$it") } +
                listOf(
                    row("rb1", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a1"),
                    row("rb2", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a1"),
                ),
        )
        val line = line(inputs, MetricKey.VIOLATION)
        assertEquals("同一条被回滚两次只算一次", 1, line.ratio.num)
        assertEquals(10, line.ratio.denom)
        assertEquals(listOf("decision:a1"), line.evidence)
    }

    @Test
    fun `误判率_同一条取最后一次判定_窗口按判定时刻`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            reviews = (1..9).map { review("c$it", DecisionRecord.VERDICT_CONFIRMED) } +
                (1..3).map { review("w$it", DecisionRecord.VERDICT_WRONG) } +
                review("x1", DecisionRecord.VERDICT_WRONG, now - 2 * day) +
                review("x1", DecisionRecord.VERDICT_CONFIRMED, now - day) +
                review("old", DecisionRecord.VERDICT_WRONG, now - 31 * day),
        )
        val line = line(inputs, MetricKey.MISJUDGE)
        assertEquals("窗口外那一条不算", 3, line.ratio.num)
        assertEquals("x1 以最后一条（确认）为准", 13, line.ratio.denom)
        assertTrue(line.evidence.toString(), line.evidence.all { it.startsWith("decision:w") })
    }

    @Test
    fun `干预率_同一步多行日志只算一步`() {
        val logs = (1..10).map { log(step = it) } +
            listOf(
                log(step = 1, state = Metrics.STATE_CONFIRM),
                log(step = 1, state = Metrics.STATE_CONFIRM),
                log(step = 1, state = Metrics.STATE_CONFIRM),
            )
        val line = line(Metrics.Inputs(nowMs = now, logs = logs), MetricKey.INTERVENTION)
        assertEquals(1, line.ratio.num)
        assertEquals(10, line.ratio.denom)
        assertEquals(listOf("call:1#1"), line.evidence)
    }

    @Test
    fun `跳过率_查不了算跳过_没有六项明细的老行不计入`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            patrols = listOf(
                patrol(
                    "patrol:1",
                    verdicts = listOf(
                        InspectVerdict.HIT,
                        InspectVerdict.NO_DATA,
                        InspectVerdict.CLEAR,
                        InspectVerdict.NO_DATA,
                        InspectVerdict.CLEAR,
                        InspectVerdict.CLEAR,
                    ),
                ),
                PatrolEntry("patrol:old", now, "", emptyList(), 0, 0, false, 0L, findings = emptyList()),
            ),
        )
        val line = line(inputs, MetricKey.SKIP)
        assertEquals(2, line.ratio.num)
        assertEquals(6, line.ratio.denom)
        assertTrue(line.note, line.note.contains("1 次巡按无六项明细"))
        assertTrue(line.evidence.toString(), line.evidence.all { it.startsWith("patrol:1#") })
    }

    @Test
    fun `窗口_滚动七天_不按日历周`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..10).map { row("a$it", at = now - 6 * day - 21 * 3_600_000L) } +
                row("old", at = now - 7 * day - 2 * 3_600_000L),
        )
        val line = line(inputs, MetricKey.VIOLATION)
        assertEquals("窗口外那条不许进分母", 10, line.ratio.denom)
    }

    // ── 技能成功率（255）────────────────────────────────────────────────────

    @Test
    fun `技能成功率_九条样本unknown_十条才出率`() {
        val nine = Metrics.Inputs(
            nowMs = now,
            skills = listOf(skill("s1", (1..9).map { sample("op$it", ok = true) })),
        )
        assertFalse(line(nine, MetricKey.SKILL_SUCCESS).known)

        val ten = Metrics.Inputs(
            nowMs = now,
            skills = listOf(skill("s1", (1..9).map { sample("op$it", ok = true) } + sample("op10", ok = false))),
        )
        val line = line(ten, MetricKey.SKILL_SUCCESS)
        assertTrue(line.known)
        assertEquals(9, line.ratio.num)
        assertEquals(10, line.ratio.denom)
        assertEquals(listOf("skill:project/s1#op10"), line.evidence)
    }

    @Test
    fun `技能成功率_窗内降级记数_窗外样本不算`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            skills = listOf(
                skill(
                    "s1",
                    (1..10).map { sample("op$it", ok = true, at = now - 1 * day) } +
                        sample("old", ok = false, at = now - 31 * day),
                    demotedAt = now - day,
                ),
            ),
        )
        val line = line(inputs, MetricKey.SKILL_SUCCESS)
        assertEquals(10, line.ratio.denom)
        assertEquals(10, line.ratio.num)
        assertTrue(line.note, line.note.contains("窗内降级 1"))
    }

    // ── E1 五指标 ───────────────────────────────────────────────────────────

    @Test
    fun `任务成功率_只算终态_没结束的不进分母`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            subTasks = (1..12).map { sub("t$it", "DONE") } +
                sub("f1", "FAILED") + sub("r1", "RUNNING") + sub("p1", "PENDING") + sub("c1", "CANCELLED"),
        )
        val line = line(inputs, MetricKey.E1_TASK_SUCCESS)
        assertEquals(12, line.ratio.num)
        assertEquals("DONE+FAILED+CANCELLED 是终态", 14, line.ratio.denom)
    }

    @Test
    fun `平均纠偏_分母是任务数_单位是次每任务`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..3).map { row("rb$it", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a$it") },
            runs = (1..10).map { run("run$it") } + run("run1") + run("run1"),
        )
        val line = line(inputs, MetricKey.E1_CORRECTIONS)
        assertEquals(3, line.ratio.num)
        assertEquals("重复的 run_id 只算一个任务", 10, line.ratio.denom)
        assertTrue(line.display(), line.display().contains("0.3 次/任务"))
    }

    @Test
    fun `人介入率_按任务去重_同任务多步摆卡算一次`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            runs = (1..12).map { run("run$it", step = it) },
            logs = (1..12).map { log(step = it) } +
                listOf(
                    log(step = 1, state = Metrics.STATE_CONFIRM),
                    log(step = 1, state = Metrics.STATE_CONFIRM),
                    log(step = 2, state = Metrics.STATE_CONFIRM),
                ),
        )
        val line = line(inputs, MetricKey.E1_HUMAN)
        assertEquals(2, line.ratio.num)
        assertEquals(12, line.ratio.denom)
    }

    @Test
    fun `不可逆动作占比_读侧reversible直接算`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..16).map { row("r$it") } + (1..4).map { row("h$it", reversible = false) },
        )
        val line = line(inputs, MetricKey.E1_IRREVERSIBLE)
        assertEquals(4, line.ratio.num)
        assertEquals(20, line.ratio.denom)
        assertTrue(line.known)
    }

    @Test
    fun `账实不符率_复用I1_账本空就unknown`() {
        val acts = (1..10).map { act(step = it) }
        val emptyLedger = Metrics.Inputs(nowMs = now, acts = acts)
        assertFalse("账本空不许报 0%", line(emptyLedger, MetricKey.E1_LEDGER_MISMATCH).known)

        val paired = Metrics.Inputs(nowMs = now, acts = acts, ledger = (1..10).map { row("r$it") })
        val clean = line(paired, MetricKey.E1_LEDGER_MISMATCH)
        assertEquals(0, clean.ratio.num)
        assertEquals(10, clean.ratio.denom)

        val missing = Metrics.Inputs(nowMs = now, acts = acts, ledger = (1..9).map { row("r$it") })
        val gap = line(missing, MetricKey.E1_LEDGER_MISMATCH)
        assertEquals(1, gap.ratio.num)
        assertTrue(gap.evidence.single().startsWith("trace:"))
    }

    // ── M9 ────────────────────────────────────────────────────────────────

    @Test
    fun `M9_无法验证不计入失败率_也不进分母`() {
        val eight = Metrics.Inputs(
            nowMs = now,
            m9 = (1..8).map { M9Judgment("msg:$it", now, M9Verdict.MISMATCH) } +
                (1..5).map { M9Judgment("msg:u$it", now, M9Verdict.UNVERIFIABLE) },
        )
        assertFalse("可验证只有 8 条 ⇒ unknown", line(eight, MetricKey.M9_HALLUCINATION).known)

        val mixed = Metrics.Inputs(
            nowMs = now,
            m9 = (1..8).map { M9Judgment("msg:$it", now, M9Verdict.MISMATCH) } +
                (1..5).map { M9Judgment("msg:v$it", now, M9Verdict.VERIFIED) } +
                (1..5).map { M9Judgment("msg:u$it", now, M9Verdict.UNVERIFIABLE) },
        )
        val line = line(mixed, MetricKey.M9_HALLUCINATION)
        assertEquals(8, line.ratio.num)
        assertEquals("分母只有 8+5，无法验证那 5 条不进", 13, line.ratio.denom)
        assertEquals(8 * 100.0 / 13, line.ratio.value!!, 1e-9)
        assertTrue(line.note, line.note.contains("无法验证 5 条"))
        assertTrue(line.note, line.note.contains("未满 k=20"))
    }

    @Test
    fun `M9_窗外的判词不算`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            m9 = (1..10).map { M9Judgment("msg:$it", now, M9Verdict.VERIFIED) } +
                M9Judgment("msg:old", now - 8 * day, M9Verdict.MISMATCH),
        )
        val line = line(inputs, MetricKey.M9_HALLUCINATION)
        assertEquals(0, line.ratio.num)
        assertEquals(10, line.ratio.denom)
    }

    // ── 判据④：幂等与两个入口 ─────────────────────────────────────────────

    private fun fullInputs(): Metrics.Inputs = Metrics.Inputs(
        nowMs = now,
        ledger = (1..20).map { row("a$it") } +
            row("rb1", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a1") +
            row("h1", reversible = false),
        reviews = (1..12).map { review("c$it", DecisionRecord.VERDICT_CONFIRMED) } +
            review("w1", DecisionRecord.VERDICT_WRONG),
        logs = (1..12).map { log(step = it) } + log(step = 1, state = Metrics.STATE_CONFIRM),
        runs = (1..10).map { run("run$it", step = it) },
        subTasks = (1..10).map { sub("t$it", if (it % 2 == 0) "DONE" else "FAILED") },
        skills = listOf(skill("s1", (1..10).map { sample("op$it", ok = it != 10) })),
        patrols = listOf(patrol("patrol:1", verdicts = List(6) { InspectVerdict.CLEAR })),
        acts = (1..10).map { act(step = it) },
        m9 = (1..10).map { M9Judgment("msg:$it", now, M9Verdict.VERIFIED) },
    )

    @Test
    fun `判据④_同一份数据两次算出同一个数`() {
        val inputs = fullInputs()
        assertEquals(Metrics.weekly(inputs), Metrics.weekly(inputs))
        assertEquals(Metrics.compute(inputs), Metrics.compute(inputs))
    }

    @Test
    fun `判据④_输入顺序打乱也同数`() {
        val a = fullInputs()
        val b = a.copy(
            ledger = a.ledger.reversed(),
            reviews = a.reviews.reversed(),
            logs = a.logs.reversed(),
            runs = a.runs.reversed(),
            subTasks = a.subTasks.reversed(),
            skills = a.skills.reversed(),
            patrols = a.patrols.reversed(),
            acts = a.acts.reversed(),
            m9 = a.m9.reversed(),
        )
        assertEquals(Metrics.compute(a), Metrics.compute(b))
    }

    @Test
    fun `判据_两个入口算出同一个数`() {
        val inputs = fullInputs()
        val report = Metrics.weekly(inputs)
        MetricKey.entries.forEach { key ->
            val fromReport = Metrics.detail(report, key)
            assertNotNull("周报里少了 $key", fromReport)
            assertSame("明细页拿到的必须是周报那一行本身", fromReport, report.lineOf(key))
            assertEquals(Metrics.detail(inputs, key), fromReport)
        }
    }

    // ── 判据①：每个数都点得开 ─────────────────────────────────────────────

    @Test
    fun `判据①_有分子就有证据_证据带前缀`() {
        val report = Metrics.weekly(fullInputs())
        val prefixes = listOf("decision:", "call:", "patrol:", "skill:", "task:", "trace:", "msg:")
        report.lines.filter { it.ratio.num > 0 }.forEach { line ->
            assertTrue("${line.key.cn} 有分子却没有证据 id", line.evidence.isNotEmpty())
            line.evidence.forEach { id ->
                assertTrue("${line.key.cn} 的证据 id 没有前缀：$id", prefixes.any { id.startsWith(it) })
            }
            assertEquals(line.evidence.distinct().size, line.evidence.size)
        }
        // 分子为 0 的指标也要能点开"我核过哪些行"：证据可以空，但总数要一致
        report.lines.forEach { assertEquals(it.evidence.distinct().size, it.evidence.size) }
    }

    @Test
    fun `判据①_证据有上限_总数照实记`() {
        fun id(n: Int) = n.toString().padStart(2, '0')
        val inputs = Metrics.Inputs(
            nowMs = now,
            ledger = (1..30).map { row("a${id(it)}") } +
                (1..30).map { row("rb${id(it)}", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a${id(it)}") },
        )
        val line = line(inputs, MetricKey.VIOLATION)
        assertEquals(30, line.ratio.num)
        assertEquals(Metrics.MAX_EVIDENCE, line.evidence.size)
        assertEquals(30, line.evidenceTotal)
    }

    @Test
    fun `周报_十一项_unknown照实数`() {
        val report = Metrics.weekly(Metrics.Inputs(nowMs = now))
        assertEquals(MetricKey.entries.size, report.lines.size)
        assertEquals(11, report.lines.size)
        assertEquals(11, report.unknowns)
        assertTrue(report.headline, report.headline.contains("11 项"))
        assertTrue(report.headline, report.headline.contains("unknown 11"))
    }

    // ── 判据③：口径先落盘（词表与文档一一对应）─────────────────────────────

    @Test
    fun `判据③_口径文档在_且每个key都在文档里`() {
        val doc = File("../docs/METRICS.md")
        assertTrue("口径文档不在：${doc.absolutePath}", doc.isFile)
        val text = doc.readText(Charsets.UTF_8)
        MetricKey.entries.forEach { key ->
            assertTrue("METRICS.md 少了口径：${key.wire}", text.contains("`${key.wire}`"))
        }
        assertTrue("文档没写分母线", text.contains("<10"))
        assertTrue("文档没写意见账不许当分母", text.contains("意见判定.jsonl"))
    }

    @Test
    fun `判据③_同义词表_每个指标只许一行一个数名`() {
        // 用户 2026-10-29 裁定②：同一件事只许一个数名；换名先改这张表（R4）
        val text = File("../docs/METRICS.md").readText(Charsets.UTF_8)
        val head = "## 〇甲、同义词对照表"
        val start = text.indexOf(head)
        assertTrue("METRICS.md 少了同义词对照表", start >= 0)
        val rest = text.substring(start + head.length)
        val end = rest.indexOf("\n## ")
        val section = if (end >= 0) rest.substring(0, end) else rest

        val cells = section.lineSequence()
            .filter { it.startsWith("| ") }
            .map { it.split("|")[1].trim() }
            .filter { it.isNotEmpty() && it != "唯一数名（key）" && !it.all { c -> c == '-' } }
            .toList()
        assertTrue("同义词表没有数据行", cells.size >= MetricKey.entries.size)

        MetricKey.entries.forEach { key ->
            val hits = cells.count { it.startsWith(key.cn) }
            assertEquals("「${key.cn}」在表里出现 $hits 次（只许一处数名）", 1, hits)
            val cell = cells.first { it.startsWith(key.cn) }
            assertTrue("「${key.cn}」那行没写 key", cell.contains("`${key.wire}`"))
        }
    }

    @Test
    fun `判据③_取数层不碰意见账_分母不取意见条数`() {
        // 注释里写"不许拿意见条数当分母"是说明（口径文档就是这么写的），要扫的是代码面
        fun code(text: String): String = text
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
        val files = listOf("Metrics.kt", "MetricsSources.kt")
        files.forEach { name ->
            val text = code(File("src/main/java/com/example/zhengdao/baopu/inspect/$name").readText(Charsets.UTF_8))
            assertFalse("$name 读了意见账", text.contains("意见判定"))
            assertFalse("$name 接了意见层", text.contains("Opinion"))
        }
    }

    @Test
    fun `词表_key线值唯一且可回读`() {
        val wires = MetricKey.entries.map { it.wire }
        assertEquals(wires.size, wires.distinct().size)
        MetricKey.entries.forEach { assertEquals(it, MetricKey.ofWire(it.wire)) }
        assertNull(MetricKey.ofWire("made_up"))
    }
}
