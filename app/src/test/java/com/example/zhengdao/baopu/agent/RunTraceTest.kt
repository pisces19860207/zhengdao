// 第八十七批 · 运行时「好」R1（trace/回放）的判据。
//
// 三层：
//   ① 纯规则（[RunTraceRules]）——hash/耗时/计数，JVM 直测；
//   ② 记录器（[TraceJournal]）——行序、字段、耗时配对、**不出现正文**；
//   ③ 库与门禁（[BaopuSql.RUN_TRACE_DDL] / [BaopuDb.VERSION]）——列形状、幂等、
//      删会话靠级联、只增不改、老库可读（只加表不动既有列）。
//
// 引擎那段 runLoop 要真 SQLite ＋ 网络才能跑（本仓无 Robolectric ⇒ 归真机，
// 与 RunEngineTest 头部口径一致），所以「接没接上」用源码结构钉在本文件末尾。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.LlmUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunTraceTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 收集器：假 emit（不落库），只把行攒起来供断言。 */
    private class Sink {
        val rows = mutableListOf<TraceRow>()
        fun emit(row: TraceRow) {
            rows += row
        }
    }

    private fun journal(sink: Sink): TraceJournal = TraceJournal(emit = sink::emit, clock = { 0L })

    // ── ① 纯规则 ────────────────────────────────────────────────────────

    @Test
    fun `R1_runId可读_带会话与开跑时刻`() {
        assertEquals("r7@1700000000000", RunTraceRules.newRunId(7L, 1_700_000_000_000L))
        assertEquals(
            "同毫秒起两次要能区分（非零 nonce 追加）",
            "r7@1700000000000#1",
            RunTraceRules.newRunId(7L, 1_700_000_000_000L, nonce = 1),
        )
    }

    @Test
    fun `R1_参数只留摘要_同样的参数同摘要_不同的参数不同摘要`() {
        val a = RunTraceRules.argsHashOf("""{"path":"章节/第一章.md","content":"正文甲"}""")
        val b = RunTraceRules.argsHashOf("""{"path":"章节/第一章.md","content":"正文甲"}""")
        val c = RunTraceRules.argsHashOf("""{"path":"章节/第一章.md","content":"正文乙"}""")
        assertEquals("同样的参数必须同摘要（回放才认得出是重试）", a, b)
        assertNotEquals("参数变了摘要必须跟着变", a, c)
        assertEquals("摘要长度写死 16 位十六进制", RunTraceRules.ARGS_HASH_LEN, a.length)
        assertTrue("必须是十六进制：$a", a.all { it in "0123456789abcdef" })
        assertEquals("空参数 ⇒ 空摘要（不编一个假 hash 出来）", "", RunTraceRules.argsHashOf(""))
        assertEquals("空 JSON 也走摘要（null ≠ 空对象）", "", RunTraceRules.argsHash(null))
    }

    @Test
    fun `R1_摘要不漏正文_原文一个字节都不在里面`() {
        val secret = "这是一整章正文，绝不能进轨迹表"
        val hashed = RunTraceRules.argsHash(JSONObject().put("path", "a.md").put("content", secret))
        assertFalse("摘要里出现了正文——红线（只存 hash/枚举/计数）", hashed.contains(secret))
        assertFalse("摘要里出现了路径原文", hashed.contains("a.md"))
        assertEquals(RunTraceRules.ARGS_HASH_LEN, hashed.length)
    }

    @Test
    fun `R1_耗时非负_未知记负一不记零`() {
        assertEquals("正常差值", 1_500L, RunTraceRules.durationBetween(1_000L, 2_500L))
        assertEquals("时钟回拨不许出现负耗时", 0L, RunTraceRules.durationBetween(2_000L, 1_000L))
        assertEquals(
            "起点未知 ⇒ 哨兵 -1（审批路径耗在等人点按钮上，不冒充工具耗时）",
            RunTraceRules.UNKNOWN_DURATION_MS,
            RunTraceRules.durationBetween(0L, 5_000L),
        )
        assertEquals(
            "终点未知同样记 -1",
            RunTraceRules.UNKNOWN_DURATION_MS,
            RunTraceRules.durationBetween(1_000L, 0L),
        )
    }

    @Test
    fun `R1_计数非负_null与负数都归零`() {
        assertEquals(0, RunTraceRules.nonNegative(null))
        assertEquals(0, RunTraceRules.nonNegative(-9))
        assertEquals(42, RunTraceRules.nonNegative(42))
    }

    @Test
    fun `R1_工具名超长截断_不把别的东西塞进这一列`() {
        assertEquals(RunTraceRules.TOOL_NAME_MAX, RunTraceRules.capToolName("x".repeat(200)).length)
        assertEquals("read_file", RunTraceRules.capToolName("read_file"))
    }

    // ── ② 记录器 ────────────────────────────────────────────────────────

    @Test
    fun `R1_行序与字段_一步一工具一行一个边界`() {
        val sink = Sink()
        val j = journal(sink)
        j.newRun(7L, startedAtMs = 1_000L)
        j.beginStep(1, at = 1_000L)
        j.noteUsage(1, LlmUsage(promptTokens = 120, completionTokens = 30))
        j.settleStep(1, TraceStatus.OK, at = 2_500L)
        j.noteTool(
            step = 1,
            tool = "read_file",
            args = JSONObject().put("path", "a.md"),
            startedAtMs = 2_600L,
            endedAtMs = 2_700L,
            status = TraceStatus.OK,
        )

        assertEquals("三步边界三行", 2, sink.rows.size)
        assertEquals(listOf(0, 1), sink.rows.map { it.seq })
        sink.rows.forEach {
            assertEquals("r7@1000", it.runId)
            assertEquals(7L, it.sessionId)
            assertEquals(1, it.step)
            assertTrue("耗时不许为负（未知用 -1）：${it.durationMs}", it.durationMs >= 0L)
            assertTrue("token 不许为负", it.tokensIn >= 0 && it.tokensOut >= 0)
        }
        val think = sink.rows[0]
        assertEquals(TracePhase.THINK, think.phase)
        assertEquals(TraceStatus.OK, think.status)
        assertEquals("耗时＝开步到读数到手", 1_500L, think.durationMs)
        assertEquals(120, think.tokensIn)
        assertEquals(30, think.tokensOut)
        assertEquals(1_000L, think.startedAt)
        assertEquals(2_500L, think.endedAt)
        assertEquals("这一步没失败 ⇒ 分类与结论都留空", "", think.failureKind)
        assertEquals("", think.decision)

        val act = sink.rows[1]
        assertEquals(TracePhase.ACT, act.phase)
        assertEquals("read_file", act.tool)
        assertEquals(100L, act.durationMs)
        assertEquals(
            "工具行只带摘要：${act.argsHash}",
            RunTraceRules.argsHash(JSONObject().put("path", "a.md")),
            act.argsHash,
        )
        assertEquals("工具行不带步级读数（读数是 THINK 行的事）", 0, act.tokensIn)
    }

    @Test
    fun `R1_失败步的策略结论也落行`() {
        val sink = Sink()
        val j = journal(sink)
        j.newRun(3L, startedAtMs = 10L)
        j.beginStep(2, at = 10L)
        j.settleStep(
            2,
            TraceStatus.FAILED,
            FailureKind.NETWORK,
            FailureAction.Retry(500L, 1),
            at = 900L,
        )
        val row = sink.rows.single()
        assertEquals(TraceStatus.FAILED, row.status)
        assertEquals("NETWORK", row.failureKind)
        assertEquals("RETRY#1", row.decision)
        assertEquals(890L, row.durationMs)
    }

    @Test
    fun `R1_工具行的状态与审批路径的未知耗时`() {
        val sink = Sink()
        val j = journal(sink)
        j.newRun(1L, startedAtMs = 1L)
        j.noteTool(1, "write_file", null, startedAtMs = 100L, endedAtMs = 160L, status = TraceStatus.OK)
        // 审批路径：起点未知 ⇒ -1（等人点按钮的时间不许冒充工具耗时）
        j.noteTool(1, "write_file", null, startedAtMs = 0L, endedAtMs = 500L, status = TraceStatus.OK)
        j.noteTool(1, "delete_path", null, startedAtMs = 0L, endedAtMs = 600L, status = TraceStatus.REJECTED)
        assertEquals(listOf(60L, RunTraceRules.UNKNOWN_DURATION_MS, RunTraceRules.UNKNOWN_DURATION_MS), sink.rows.map { it.durationMs })
        assertEquals(
            listOf(TraceStatus.OK, TraceStatus.OK, TraceStatus.REJECTED),
            sink.rows.map { it.status },
        )
    }

    @Test
    fun `R1_换run后序号从零起_不串上一轮的账`() {
        val sink = Sink()
        val j = journal(sink)
        j.newRun(1L, startedAtMs = 1L)
        j.beginStep(1, at = 1L)
        j.settleStep(1, TraceStatus.OK, at = 2L)
        val firstSeq = sink.rows.single().seq
        j.newRun(1L, startedAtMs = 3L)
        j.beginStep(1, at = 3L)
        j.settleStep(1, TraceStatus.OK, at = 4L)
        assertEquals("新一轮序号必须从 0 起", 0, firstSeq)
        assertEquals(listOf(0, 0), sink.rows.map { it.seq })
        assertEquals("runId 跟着换", listOf("r1@1", "r1@3"), sink.rows.map { it.runId })
    }

    @Test
    fun `R1_压缩边界也留痕_成与没成都记`() {
        val sink = Sink()
        val j = journal(sink)
        j.newRun(5L, startedAtMs = 1L)
        j.noteCompress(3, startedAtMs = 100L, endedAtMs = 900L, status = TraceStatus.OK)
        j.noteCompress(4, startedAtMs = 1_000L, endedAtMs = 1_050L, status = TraceStatus.SKIPPED)
        j.noteCompress(5, startedAtMs = 2_000L, endedAtMs = 2_100L, status = TraceStatus.FAILED, failureKind = FailureKind.MODEL_CHANNEL)
        assertEquals(listOf(TracePhase.COMPRESSING, TracePhase.COMPRESSING, TracePhase.COMPRESSING), sink.rows.map { it.phase })
        assertEquals(
            listOf(TraceStatus.OK, TraceStatus.SKIPPED, TraceStatus.FAILED),
            sink.rows.map { it.status },
        )
        assertEquals(listOf("", "", "MODEL_CHANNEL"), sink.rows.map { it.failureKind })
        assertEquals(800L, sink.rows[0].durationMs)
    }

    // ── ③ 库与门禁 ──────────────────────────────────────────────────────

    @Test
    fun `R1_建表按列序_列清单只有一份`() {
        val ddl = BaopuSql.RUN_TRACE_DDL.first()
        assertTrue("表名要是 run_trace", ddl.contains("CREATE TABLE IF NOT EXISTS run_trace ("))
        BaopuSql.RUN_TRACE_COLUMNS.forEach { col ->
            assertTrue("run_trace 缺列：$col", ddl.contains(" $col "))
        }
        assertEquals(
            "列序只有一份：RunTraceRules.COLUMNS 必须转手 BaopuSql.RUN_TRACE_COLUMNS",
            BaopuSql.RUN_TRACE_COLUMNS,
            RunTraceRules.COLUMNS,
        )
        assertEquals("十五列（模型面十五个字段），多一列少一列都不行", 15, BaopuSql.RUN_TRACE_COLUMNS.size)
    }

    @Test
    fun `R1_表里没有任何能装正文的列`() {
        val forbidden = listOf("text", "payload", "args", "content", "prompt", "body", "answer", "message")
        BaopuSql.RUN_TRACE_COLUMNS.forEach { col ->
            assertFalse("轨迹表出现了疑似正文列：$col（红线：只存 hash/枚举/计数）", col in forbidden)
        }
        val ddl = BaopuSql.RUN_TRACE_DDL.joinToString("\n")
        forbidden.forEach { word ->
            assertFalse("DDL 里出现了 $word 列", Regex("\\b$word\\b").containsMatchIn(ddl))
        }
        // 模型类同样不许出现能装正文的字段（改字段名也逃不过）
        val modelSrc = read("src/main/java/com/example/zhengdao/baopu/agent/RunTrace.kt")
        val rowBlock = modelSrc.substringAfter("internal data class TraceRow(").substringBefore(") {")
        listOf("text:", "payload:", "args:", "content:", "prompt:", "body:").forEach { f ->
            assertFalse("TraceRow 出现了正文形态字段：$f", rowBlock.contains(f))
        }
    }

    @Test
    fun `R1_落库与读取用同一份列序_读取按seq升序`() {
        val insert = BaopuSql.insertRunTrace(
            runId = "r1@1", sessionId = 1L, step = 1, seq = 0, phase = "THINK", tool = "",
            argsHash = "", status = "OK", durationMs = 1L, tokensIn = 2, tokensOut = 3,
            failureKind = "", decision = "", startedAt = 10L, endedAt = 11L,
        )
        BaopuSql.RUN_TRACE_COLUMNS.forEach { col ->
            assertTrue("INSERT 少了列 $col", insert.sql.contains(col))
        }
        assertEquals("十五个占位符", 15, insert.sql.count { it == '?' })
        assertEquals(
            listOf("r1@1", "1", "1", "0", "THINK", "", "", "OK", "1", "2", "3", "", "", "10", "11"),
            insert.args.toList(),
        )

        val sel = BaopuSql.selectRunTrace("r1@1")
        assertTrue("必须按 runId 过滤", sel.sql.contains("WHERE run_id = ?"))
        assertTrue("必须按 seq 升序（＝发生顺序）", sel.sql.contains("ORDER BY seq ASC"))
        BaopuSql.RUN_TRACE_COLUMNS.forEach { col ->
            assertTrue("SELECT 少了列 $col", sel.sql.contains(col))
        }
        assertEquals(listOf("r1@1"), sel.args.toList())

        val latest = BaopuSql.selectLatestRunTrace(9L)
        assertTrue(latest.sql.contains("WHERE session_id = ?"))
        assertTrue(latest.sql.contains("ORDER BY id DESC LIMIT 1"))
    }

    @Test
    fun `R1_迁移只看表不动既有列_老库照常可读`() {
        assertEquals("v15 = 第一百一十七批 run_queue（v14 = 第八十九批 memory 质量四列，v13 = 第八十七批 run_trace，v12 是第八十五批效果信号）", 15, BaopuDb.VERSION)
        BaopuSql.RUN_TRACE_DDL.forEach {
            assertTrue("迁移 DDL 必须 IF NOT EXISTS（幂等）：$it", it.contains("IF NOT EXISTS"))
            assertFalse("加表不许顺手 ALTER 既有表（老库可读的前提）：$it", it.contains("ALTER TABLE"))
        }
        val dbSrc = read("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt")
        assertTrue("onCreate 必须走 v12 迁移（新装与升级同一条路）", dbSrc.contains("applyV12Migration(db)"))
        assertTrue("onUpgrade 必须有 v12 分支", dbSrc.contains("if (oldVersion < 12) applyV12Migration(db)"))
        assertFalse("轨迹表是只增不改的账本：不许有 UPDATE", dbSrc.contains("UPDATE run_trace"))
        assertFalse("轨迹表是只增不改的账本：不许有 DELETE", dbSrc.contains("DELETE FROM run_trace"))
    }

    @Test
    fun `R1_删会话靠级联_并进那条从DDL反推的门禁`() {
        val ddl = BaopuSql.RUN_TRACE_DDL.joinToString("\n")
        assertTrue(
            "session_id 必须 ON DELETE CASCADE（否则删了会话留一堆孤儿轨迹）",
            ddl.contains("session_id INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE"),
        )
        val deleted = BaopuSql.deleteSessionStatements(1L).joinToString("\n") { it.sql }
        assertFalse("有级联就不要再显式删（删两遍是重复劳动）", deleted.contains("DELETE FROM run_trace"))
    }

    // ── ④ 源码门禁：引擎真的在这三处边界上记 ────────────────────────────

    @Test
    fun `R1_引擎在开步_读数_工具_压缩四处接了线`() {
        val code = File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt") }
            .readText()
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        assertTrue("开步没接（beginStep）", code.contains("trace.beginStep(stepCount, stepStartedAtMs)"))
        assertTrue("读数没接（noteUsage 必须在 persistUsage 里）", code.contains("trace.noteUsage(step, usage)"))
        assertTrue("工具批没接（noteTool）", code.contains("trace.noteTool("))
        assertTrue("压缩没接（noteCompress）", code.contains("trace.noteCompress("))
        assertTrue("步边界没接（settleStep）", code.contains("trace.settleStep("))
        assertTrue(
            "落库必须走唯一入口 persistTrace（判据：轨迹只在 db 单写者线程上写）",
            code.contains("private fun persistTrace(row: TraceRow)"),
        )
        assertFalse(
            "轨迹落库不许 .get()（不许让一次运行等它写盘）",
            code.contains("persistTrace(row).get()"),
        )
    }
}
