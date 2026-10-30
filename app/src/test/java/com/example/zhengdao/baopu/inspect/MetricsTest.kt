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
import org.junit.Assert.assertNotEquals
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
        session: Long = -1L,
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
        sessionId = session,
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

    private fun req(
        runId: String = "run1",
        sessionId: Long = 1L,
        step: Int = 1,
        tool: String = "write_file",
        at: Long = now,
        confirm: Boolean = false,
        ok: Boolean = true,
    ) = ToolRequest(
        runId = runId,
        sessionId = sessionId,
        step = step,
        tool = tool,
        atMs = at,
        hasConfirm = confirm,
        hasOk = ok,
    )

    private fun runSummary(
        runId: String,
        sessionId: Long = 1L,
        start: Long = now - 3_600_000L,
        at: Long = now,
        statuses: List<String> = listOf("OK"),
    ) = RunSummary(runId = runId, sessionId = sessionId, startedAtMs = start, atMs = at, statuses = statuses)

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

    // ── 任务成功率：任务级（口径 §3.1，第二批）─────────────────────────────

    @Test
    fun `任务成功率_按run算_同一份数据里的子任务不许进这个数`() {
        // 子任务给一套完全不同的数（14 个终态／12 个 DONE）：混用就会算成 12/14
        val inputs = Metrics.Inputs(
            nowMs = now,
            runSummaries = (1..8).map { runSummary("r$it") } +
                (9..12).map { runSummary("r$it", statuses = listOf("FAILED")) },
            subTasks = (1..12).map { sub("t$it", "DONE") } +
                sub("f1", "FAILED") + sub("r1", "RUNNING") + sub("c1", "CANCELLED"),
        )
        val line = line(inputs, MetricKey.E1_TASK_SUCCESS)
        assertEquals(8, line.ratio.num)
        assertEquals("分母＝已结束任务数，不是子任务数", 12, line.ratio.denom)
        assertTrue(line.evidence.toString(), line.evidence.all { it.startsWith("task:r") })
    }

    @Test
    fun `任务成功率_最后一行是CONFIRM_还在等批不算结束`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            runSummaries = (1..10).map { runSummary("r$it") } +
                runSummary("wait1", statuses = listOf("OK", "CONFIRM")) +
                runSummary("rej1", statuses = listOf("OK", "REJECTED")) +
                runSummary("skip1", statuses = listOf("SKIPPED")),
        )
        val line = line(inputs, MetricKey.E1_TASK_SUCCESS)
        assertEquals("等批／被拒／跳过都不算结束", 10, line.ratio.denom)
        assertEquals(10, line.ratio.num)
    }

    @Test
    fun `任务成功率_FAILED之后又OK_算被救回来`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            runSummaries = (1..8).map { runSummary("r$it") } + listOf(
                runSummary("rescued", statuses = listOf("FAILED", "OK", "FAILED", "OK")),
                runSummary("dead", statuses = listOf("OK", "FAILED")),
                runSummary("timeout", statuses = listOf("TIMEOUT")),
                runSummary("cancelled", statuses = listOf("CANCELLED")),
            ),
        )
        val line = line(inputs, MetricKey.E1_TASK_SUCCESS)
        assertEquals("8 个普通 + 救回来那 1 个", 9, line.ratio.num)
        assertEquals(12, line.ratio.denom)
        assertTrue(line.evidence.toString(), line.evidence.contains("task:rescued"))
        assertFalse(line.evidence.toString(), line.evidence.contains("task:dead"))
    }

    @Test
    fun `任务成功率_窗内同会话有回滚算没成功_窗外与别的会话不算`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            // 回滚点在 1 小时前：普通任务的跨度都在更早（碰不到），hit 的跨度正好罩住它
            runSummaries = (1..8).map {
                runSummary("r$it", start = now - 30 * 3_600_000L, at = now - 26 * 3_600_000L)
            } + listOf(
                runSummary("hit", start = now - 2 * 3_600_000L, at = now - 1_800_000L),
                runSummary("other", sessionId = 2L, start = now - 2 * 3_600_000L, at = now - 1_800_000L),
                runSummary("after", sessionId = 1L, start = now - 10 * 3_600_000L, at = now - 5 * 3_600_000L),
            ),
            ledger = listOf(
                // 落在 hit 的跨度里（会话 1）⇒ hit 不算成功
                row("rb1", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a1", at = now - 3_600_000L, session = 1L),
                // 别的会话的回滚不归属 other
                row("rb2", kind = DecisionKind.ROLLBACK_OF, rollbackOf = "a2", at = now - 3_600_000L, session = 9L),
            ),
        )
        val line = line(inputs, MetricKey.E1_TASK_SUCCESS)
        assertEquals("只有 hit 被回滚", 10, line.ratio.num)
        assertEquals(11, line.ratio.denom)
        assertFalse(line.evidence.toString(), line.evidence.contains("task:hit"))
        assertTrue(line.evidence.toString(), line.evidence.contains("task:after"))
    }

    @Test
    fun `任务成功率_读数行必须写续跑未判`() {
        val inputs = Metrics.Inputs(nowMs = now, runSummaries = (1..12).map { runSummary("r$it") })
        val line = line(inputs, MetricKey.E1_TASK_SUCCESS)
        assertTrue(
            "读数行没写续跑未判，实际 note='${line.note}'",
            line.note.contains("续跑未判（缺标记）"),
        )
    }

    @Test
    fun `任务成功率_分母不够unknown时_续跑未判照样要写`() {
        val empty = line(Metrics.Inputs(nowMs = now), MetricKey.E1_TASK_SUCCESS)
        assertFalse("空输入应当是 unknown", empty.known)
        assertTrue(
            "unknown 时把续跑未判那句吞了，实际 note='${empty.note}'",
            empty.note.contains("续跑未判（缺标记）"),
        )
    }

    @Test
    fun `任务成功率_读数行必须写新旧不可比`() {
        val line = line(Metrics.Inputs(nowMs = now), MetricKey.E1_TASK_SUCCESS)
        assertTrue(
            "换了分母却没在读数行说不可比，实际 note='${line.note}'",
            line.note.contains("新旧数不可比"),
        )
    }

    // ── 子任务完成率：诊断口径（旧算式整段搬出）────────────────────────────

    @Test
    fun `子任务完成率_只算终态_没结束的不进分母`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            subTasks = (1..12).map { sub("t$it", "DONE") } +
                sub("f1", "FAILED") + sub("r1", "RUNNING") + sub("p1", "PENDING") + sub("c1", "CANCELLED"),
        )
        val line = line(inputs, MetricKey.E1_SUBTASK_DONE)
        assertEquals(12, line.ratio.num)
        assertEquals("DONE+FAILED+CANCELLED 是终态", 14, line.ratio.denom)
        assertTrue(line.note, line.note.contains("诊断口径"))
    }

    @Test
    fun `子任务完成率与任务成功率_各算各的_名字也不许混`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            runSummaries = (1..10).map { runSummary("r$it") } + runSummary("dead", statuses = listOf("FAILED")),
            subTasks = (1..8).map { sub("t$it", "DONE") } + sub("f1", "FAILED"),
        )
        val task = line(inputs, MetricKey.E1_TASK_SUCCESS)
        val sub = line(inputs, MetricKey.E1_SUBTASK_DONE)
        assertEquals("任务级：10/11", 10, task.ratio.num)
        assertEquals(11, task.ratio.denom)
        assertEquals("子任务级：8/9", 8, sub.ratio.num)
        assertEquals(9, sub.ratio.denom)
        assertNotEquals("两个数名不许一样", task.key.cn, sub.key.cn)
        assertNotEquals(task.key.wire, sub.key.wire)
    }

    // ── 需介入任务占比：任务级辅助（口径 §3.5）──────────────────────────────

    @Test
    fun `需介入任务占比_按run聚合_同一任务摆两次卡只算一个任务`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = listOf(
                req(runId = "r1", step = 1, confirm = true),
                req(runId = "r1", step = 2, confirm = true),
                req(runId = "r2", step = 1, tool = Metrics.TOOL_ASK_USER, confirm = false, ok = false),
            ) + (3..11).map { req(runId = "r$it", step = 1) },
        )
        val task = line(inputs, MetricKey.E1_TASK_NEEDS_CONFIRM)
        assertEquals("r1、r2 两个任务", 2, task.ratio.num)
        assertEquals("11 个任务有工具请求", 11, task.ratio.denom)
        assertEquals(listOf("task:r1", "task:r2"), task.evidence)
        assertTrue(task.note, task.note.contains("与人介入率不同层"))

        // 同一份数据差一层：人介入率是请求级 3/12
        val human = line(inputs, MetricKey.E1_HUMAN)
        assertEquals(3, human.ratio.num)
        assertEquals(12, human.ratio.denom)
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

    // ── 人介入率：请求级（口径 §3.2／裁定⑤⑦）──────────────────────────────

    @Test
    fun `人介入率_按请求算_同一次请求补第二行只算一次`() {
        // 取数层已把「挂起 CONFIRM 行 ＋ 批准后那行」并成一条请求：这里给的就是并完的形状
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = (1..11).map { req(step = it) } + req(step = 12, confirm = true),
        )
        val line = line(inputs, MetricKey.E1_HUMAN)
        assertEquals(1, line.ratio.num)
        assertEquals(12, line.ratio.denom)
        assertEquals(listOf("trace:1#12"), line.evidence)
    }

    @Test
    fun `人介入率_一步里多个工具调用各算一个请求`() {
        // 批编排一个 step 能连跑多个工具：按 (run_id, step) 去重会把 12 算成 10
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = listOf(
                req(step = 5, tool = "write_file"),
                req(step = 5, tool = "read_file"),
                req(step = 5, tool = "search_web"),
            ) + (6..14).map { req(step = it) },
        )
        val line = line(inputs, MetricKey.E1_HUMAN)
        assertEquals(0, line.ratio.num)
        assertEquals("一步三调用算三个请求", 12, line.ratio.denom)
    }

    @Test
    fun `人介入率_ask_user算摆卡`() {
        // ask_user 本身就是一张卡，但不产生 CONFIRM 行；漏掉会把"人介入"低估
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = (1..10).map { req(step = it) } +
                listOf(
                    req(step = 11, tool = Metrics.TOOL_ASK_USER, confirm = false),
                    req(step = 12, tool = Metrics.TOOL_ASK_USER, confirm = false),
                ),
        )
        val line = line(inputs, MetricKey.E1_HUMAN)
        assertEquals(2, line.ratio.num)
        assertEquals(12, line.ratio.denom)
    }

    // ── 不可逆动作占比：破坏动作白名单（口径 §3.3／§三·乙）──────────────────

    @Test
    fun `不可逆动作占比_按白名单命中已执行请求算`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = listOf(
                req(step = 1, tool = "delete_path"),
                req(step = 2, tool = "search_web"),
                req(step = 3, tool = "write_file"),
            ) + (4..12).map { req(step = it, tool = "read_file") },
        )
        val line = line(inputs, MetricKey.E1_IRREVERSIBLE)
        assertEquals(3, line.ratio.num)
        assertEquals(12, line.ratio.denom)
        assertTrue(line.known)
    }

    @Test
    fun `不可逆动作占比_剪贴板与浏览器不算`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = listOf(
                req(step = 1, tool = "clipboard_write"),
                req(step = 2, tool = "browser_click"),
                req(step = 3, tool = "notify"),
                req(step = 4, tool = "write_skill"),
            ) + (5..12).map { req(step = it, tool = "file_info") },
        )
        val line = line(inputs, MetricKey.E1_IRREVERSIBLE)
        assertEquals("这四支不在白名单", 0, line.ratio.num)
        assertEquals(12, line.ratio.denom)
    }

    @Test
    fun `不可逆动作占比_白名单含novel四支`() {
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = listOf(
                req(step = 1, tool = "novel_create"),
                req(step = 2, tool = "novel_continue"),
                req(step = 3, tool = "book_outline"),
                req(step = 4, tool = "card_build"),
            ) + (5..12).map { req(step = it, tool = "read_file") },
        )
        val line = line(inputs, MetricKey.E1_IRREVERSIBLE)
        assertEquals("裁定⑥：这套确实落文件", 4, line.ratio.num)
        assertEquals(12, line.ratio.denom)
    }

    @Test
    fun `不可逆动作占比_没执行的请求不进分母`() {
        // 摆卡没批 / 被拒 / 超时都没有 OK 行：没发生的动作谈不上"不可逆"
        val inputs = Metrics.Inputs(
            nowMs = now,
            requests = listOf(
                req(step = 1, tool = "delete_path", confirm = true, ok = false),
                req(step = 2, tool = "write_file", ok = false),
            ) + (3..14).map { req(step = it, tool = "read_file") },
        )
        val line = line(inputs, MetricKey.E1_IRREVERSIBLE)
        assertEquals(0, line.ratio.num)
        assertEquals("只有 12 条执行过", 12, line.ratio.denom)
    }

    // ── 不确定性必须跟数同行（用户 2026-10-29 硬要求）───────────────────────

    @Test
    fun `不可逆动作占比_读数行必须写覆盖写上界`() {
        val line = line(Metrics.Inputs(nowMs = now, requests = (1..12).map { req(step = it) }), MetricKey.E1_IRREVERSIBLE)
        assertTrue(
            "读数行没写覆盖写上界，实际 note='${line.note}'",
            line.note.contains("覆盖写上界：含新建文件"),
        )
    }

    @Test
    fun `不可逆动作占比_分母不够unknown时_上界照样要写`() {
        val empty = line(Metrics.Inputs(nowMs = now), MetricKey.E1_IRREVERSIBLE)
        assertFalse("空输入应当是 unknown", empty.known)
        assertTrue(
            "unknown 时把上界那句吞了，实际 note='${empty.note}'",
            empty.note.contains("覆盖写上界：含新建文件"),
        )
    }

    @Test
    fun `周报_不确定性跟数同行_display里也看得见`() {
        val report = Metrics.weekly(fullInputs())
        val shown = report.lines.joinToString(" ／ ") { it.display() }
        assertTrue("界面上看不到覆盖写上界：$shown", shown.contains("覆盖写上界：含新建文件"))
    }

    @Test
    fun `备注_原字不改_少了含新建文件也算红`() {
        val text = Metrics.weekly(fullInputs()).lines.joinToString(" ") { it.note }
        assertTrue("上界说明被改短：$text", text.contains("覆盖写上界：含新建文件"))
    }

    @Test
    fun `备注_原字不改_少了缺标记也算红`() {
        val text = Metrics.weekly(fullInputs()).lines.joinToString(" ") { it.note }
        // 少了「缺标记」＝把「没判」说成了「判过且没问题」
        assertTrue("续跑说明被改短：$text", text.contains("续跑未判（缺标记）"))
    }

    @Test
    fun `写侧账实不符率_复用I1_账本空就unknown`() {
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
        runSummaries = (1..10).map { runSummary("run$it") } + runSummary("runX", statuses = listOf("FAILED")),
        requests = (1..11).map { req(step = it, tool = "read_file") } +
            req(step = 12, tool = "write_file", confirm = true),
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
            runSummaries = a.runSummaries.reversed(),
            requests = a.requests.reversed(),
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
    fun `周报_十三项_unknown照实数`() {
        val report = Metrics.weekly(Metrics.Inputs(nowMs = now))
        assertEquals(MetricKey.entries.size, report.lines.size)
        assertEquals(13, report.lines.size)
        assertEquals(13, report.unknowns)
        assertTrue(report.headline, report.headline.contains("13 项"))
        assertTrue(report.headline, report.headline.contains("unknown 13"))
    }

    @Test
    fun `词表_每个key一个数名_两组容易混的必须不同`() {
        val names = MetricKey.entries.map { it.cn }
        assertEquals("有重名数名：$names", names.size, names.distinct().size)
        assertNotEquals(MetricKey.E1_TASK_SUCCESS.cn, MetricKey.E1_SUBTASK_DONE.cn)
        assertNotEquals(MetricKey.E1_HUMAN.cn, MetricKey.E1_TASK_NEEDS_CONFIRM.cn)
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

        // 按 key 线值钉，不按中文名钉：改名由用户裁定（R4），代码侧 cn 允许晚一批跟上
        val rows = section.lineSequence()
            .filter { it.startsWith("| ") }
            .map { it.split("|") }
            .filter { it.size >= 5 }
            .filterNot { it[1].trim().all { c -> c == '-' } }
            .filterNot { it[1].trim() == "唯一数名（key）" }
            .toList()
        assertTrue("同义词表没有数据行", rows.size >= MetricKey.entries.size)

        MetricKey.entries.forEach { key ->
            val hit = rows.filter { it[1].contains("`${key.wire}`") }
            assertEquals("`${key.wire}` 在表里出现 ${hit.size} 次（只许一处数名）", 1, hit.size)
            val row = hit.single()
            assertTrue("`${key.wire}` 那行没写用户读法", row[2].trim().isNotEmpty())
            assertTrue("`${key.wire}` 那行没写口径", row[3].trim().isNotEmpty())
        }
        // 每行只许一个 key；还没进词表的（第二批才落码）在 §七 里登记，不算漏写
        val wireOf = Regex("""`([a-z][a-z0-9_]*)`""")
        rows.forEach { row ->
            val wires = wireOf.findAll(row[1]).map { it.groupValues[1] }.toList()
            assertEquals("同义词表有一行没写 key 或写了多个名字：${row[1].trim()}", 1, wires.size)
            if (MetricKey.ofWire(wires.single()) == null) {
                assertTrue("`${wires.single()}` 不在词表里，§七 却没登记", text.contains("`${wires.single()}`"))
            }
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
    fun `取数_滚动窗不许把批准补记的行读丢`() {
        // 写入侧事实：`RunTrace.kt:235`「审批路径没有起点」⇒ 批准后补记那行 started_at = 0、只有 ended_at 是真时刻。
        // 窗口按 started_at 开 ⇒ 那行整条读丢：run 的末行退回 CONFIRM（跑完了却被判成「还在等批」）、
        // 请求的 status = OK 看不见（「摆卡获批后真执行了」判成没执行，不可逆动作占比少算覆盖度）。
        // 少算的正是需要人介入那批任务——低估比高估危险（口径 §3.6#1／#4）。
        val text = File("src/main/java/com/example/zhengdao/baopu/inspect/MetricsSources.kt")
            .readText(Charsets.UTF_8)
        listOf("runSummaries", "toolRequests").forEach { fn ->
            val body = text.substringAfter("fun $fn(").substringBefore("\n    /**")
            assertTrue("$fn 的窗口判据没按取大：${body.take(400)}", body.contains("MAX(started_at, ended_at) >= ?"))
            assertFalse("$fn 里还留着按 started_at 开窗：${body.take(400)}", body.contains("WHERE started_at >= ?"))
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
