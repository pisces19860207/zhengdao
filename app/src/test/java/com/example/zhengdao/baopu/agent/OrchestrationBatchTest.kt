// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十四批 · 183-a（2026-10-25）：编排引擎的判据与接线门禁。
//
// 两层（与 P1EngineGateTest / StepBudgetGateTest 同口径）：
//   ① 纯判据：批上限、编号回灌（每调用有结局 / ≤1.5k / 超批合成区间 / 纯文本过
//      HistoryShapeGate）、checkpoint JSON、编排指引、开关默认关——JVM 直测；
//   ② 源码门禁：runLoop 要真 SQLite ＋ 网络（本仓无 Robolectric），所以「接没接上」
//      钉在源码结构上：关掉时走 native 的同一表达式、编排开才有一条回灌、插话只在
//      段边界、超批不进打转计数、批 checkpoint 行型与 DB 版本未动。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.HistoryShapeGate
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestrationBatchTest {

    private fun repoRel(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun code(rel: String): String =
        repoRel(rel).readText().lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun call(id: String, name: String, args: JSONObject = JSONObject()) = ToolCall(id, name, args)

    private fun entry(
        i: Int,
        n: Int,
        name: String = "read_file",
        ending: BatchRules.Ending = BatchRules.Ending.DONE,
        detail: String = "",
    ) = BatchRules.Entry(i, n, name, """{"path":"a.txt"}""", ending, detail)

    // ── ① 纯判据：批上限与回灌 ───────────────────────────────────────────

    @Test
    fun `批上限_8_与RunRules同址`() {
        assertEquals("MAX_BATCH_CALLS 必须是 8（设计 §3.2）", 8, RunRules.MAX_BATCH_CALLS)
    }

    @Test
    fun `回灌_每个调用都有结局_一行一个`() {
        val entries = listOf(
            entry(1, 4, detail = "312 字的内容"),
            entry(2, 4, name = "search_in_files", detail = "3 处命中"),
            entry(3, 4, name = "write_file", ending = BatchRules.Ending.FAILED, detail = "路径越界"),
            entry(4, 4, name = "delete_file", ending = BatchRules.Ending.SKIPPED, detail = "用户未批准"),
        )
        val feed = BatchRules.feed(3, entries)
        val lines = feed.lines()
        assertEquals("头部一行 ＋ 每个调用一行", 5, lines.size)
        assertTrue("头部要带批次号/总数/三档计数", lines[0] == "【第 3 批 · 4 个调用 · 2 成功 1 失败 1 未执行】")
        assertTrue("成功行要带正文摘要", lines[1].startsWith("[1/4] read_file(") && lines[1].contains("→ 成功（"))
        assertTrue("失败行要带原因", lines[3].contains("→ 失败：路径越界"))
        assertTrue("未执行行要带原因", lines[4].contains("→ 未执行：用户未批准"))
    }

    @Test
    fun `回灌_总长不超1500_超了按调用截断并标记`() {
        val long = "甲".repeat(4000)
        val entries = (1..8).map { entry(it, 8, detail = long) }
        val feed = BatchRules.feed(1, entries)
        assertTrue("整批回灌不许超过 1.5k 字，实际 ${feed.length}", feed.length <= BatchRules.FEED_MAX_CHARS)
        assertTrue("超长必须写「…（超长截断）」", feed.contains(BatchRules.TRUNCATED_MARK))
        // 每个调用仍有结局（截的是正文，不是行）
        (1..8).forEach { assertTrue("第 $it 个调用的行消失了", feed.contains("[$it/8]")) }
        assertEquals("截断后每条只占一行", 9, feed.lines().size)
    }

    @Test
    fun `回灌_超批逐条标_太多才合成区间`() {
        val entries = (1..12).map { i ->
            if (i <= 8) entry(i, 12) else {
                entry(i, 12, ending = BatchRules.Ending.SKIPPED, detail = BatchRules.OVER_LIMIT_REASON)
            }
        }
        val feed = BatchRules.feed(2, entries)
        assertTrue("第 9 个起必须出现「未执行：本批超过 8 个上限」", feed.contains("未执行：${BatchRules.OVER_LIMIT_REASON}"))
        assertFalse("超批的那几个一个都不许混进执行行", feed.contains("[9/12] read_file("))
        // 派工单 §1.1：第 9 个起**逐条**标（12 个还能装下 ⇒ 不合成区间）
        (9..12).forEach { assertTrue("第 $it 个必须逐条出现", feed.contains("[$it/12] read_file → 未执行：")) }
        assertFalse("装得下时不许提前合成区间", feed.contains("–[12/12]"))
        assertEquals("8 行执行 ＋ 4 行超批 ＋ 头部", 13, feed.lines().size)
        assertTrue("总长仍要守住 1.5k", feed.length <= BatchRules.FEED_MAX_CHARS)

        // 一次交 120 个：逐条会撑爆 1.5k ⇒ 必须合成区间，且序号全被覆盖
        val many = (1..120).map { i ->
            if (i <= 8) entry(i, 120) else {
                entry(i, 120, ending = BatchRules.Ending.SKIPPED, detail = BatchRules.OVER_LIMIT_REASON)
            }
        }
        val huge = BatchRules.feed(3, many)
        assertTrue("撑爆时必须合成区间", huge.contains("[9/120]–[120/120] 共 112 个调用"))
        assertTrue("合成后仍守预算，实际 ${huge.length}", huge.length <= BatchRules.FEED_MAX_CHARS)
        assertEquals("8 行执行 ＋ 1 行区间 ＋ 头部", 10, huge.lines().size)
    }

    @Test
    fun `回灌是纯文本_过HistoryShapeGate_原生块对照组会红`() {
        val feed = BatchRules.feed(
            3,
            listOf(
                entry(1, 2, detail = "文件内容"),
                entry(2, 2, name = "write_file", ending = BatchRules.Ending.FAILED, detail = "路径越界"),
            ),
        )
        val clean = HistoryShapeGate.replayHistoryViolations(
            listOf(HistoryMessage("m1", HistoryMessage.ROLE_ASSISTANT, feed)),
        )
        assertEquals("编号回灌必须是干净纯文本（否则 188 门禁红）", emptyList<String>(), clean)
        // 对照组：同样的位置换成原生块 ⇒ 门禁必红（证明上面那条不是空转）
        val native = HistoryShapeGate.replayHistoryViolations(
            listOf(
                HistoryMessage(
                    "m2",
                    HistoryMessage.ROLE_ASSISTANT,
                    """{"type":"tool_result","tool_use_id":"toolu_1","content":"文件内容"}""",
                ),
            ),
        )
        assertTrue("原生块必须被抓到（负例）", native.isNotEmpty())
    }

    @Test
    fun `回灌_短批不截断_不出现标记`() {
        val feed = BatchRules.feed(1, listOf(entry(1, 1, detail = "短")))
        assertFalse(feed.contains(BatchRules.TRUNCATED_MARK))
        assertEquals("【第 1 批 · 1 个调用 · 1 成功 0 失败 0 未执行】", feed.lines().first())
    }

    @Test
    fun `参数摘要_对齐189口径_上限80`() {
        val args = JSONObject().put("path", "长".repeat(300))
        val brief = BatchRules.argsBrief(args)
        assertTrue("摘要必须截断并标注", brief.endsWith("…（已截断）"))
        assertTrue(
            "摘要上限 80 字（截断标记另计），实际 ${brief.length}",
            brief.length <= BatchRules.ARGS_BRIEF_MAX + "…（已截断）".length,
        )
    }

    @Test
    fun `收集器_构造即预填结局_提前返回也不会少行`() {
        val calls = (1..10).map { call("g$it", "read_file", JSONObject().put("path", "f$it")) }
        val c = BatchCollector(1, "b1", calls)
        c.recordDone(calls[0], "<tool_result name=\"read_file\">\n内容\n</tool_result>")
        val feed = c.feed()
        assertTrue("执行过的那条是成功", feed.contains("[1/10] read_file(") && feed.lines()[1].contains("→ 成功"))
        (2..8).forEach {
            assertTrue("第 $it 个没执行 ⇒ 预填「本轮提前结束」，不许消失", feed.contains("[$it/10]"))
        }
        assertEquals("未执行的 7 条各有一行", 7, feed.lines().count { it.contains("→ 未执行：${BatchRules.EARLY_END_REASON}") })
        assertTrue("第 9、10 个是多出来的调用，逐条标超批", feed.contains("[9/10] read_file → 未执行：${BatchRules.OVER_LIMIT_REASON}"))
        assertTrue("第 10 个也要有", feed.contains("[10/10] read_file → 未执行：${BatchRules.OVER_LIMIT_REASON}"))
        assertEquals("每个调用一行：10 行 ＋ 头部", 11, feed.lines().size)
    }

    // ── ① 纯判据：checkpoint 与指引 ─────────────────────────────────────

    @Test
    fun `checkpoint_批首行_调用指纹与段序号`() {
        val calls = listOf(
            call("a", "read_file", JSONObject().put("path", "a.txt")),
            call("b", "list_dir", JSONObject().put("path", ".")),
            call("c", "write_file", JSONObject().put("path", "b.txt")),
        )
        val stages = listOf(
            ToolStage.Parallel(listOf(calls[0], calls[1])),
            ToolStage.Serial(calls[2]),
        )
        val json = JSONObject(BatchRules.checkpointStart("b3", 7, calls, stages))
        assertEquals("b3", json.getString("batch"))
        assertEquals(7, json.getInt("step"))
        val arr = json.getJSONArray("calls")
        assertEquals(3, arr.length())
        assertEquals(1, arr.getJSONObject(0).getInt("i"))
        assertEquals("read_file", arr.getJSONObject(0).getString("name"))
        assertEquals("指纹只留 8 位", 8, arr.getJSONObject(0).getString("args_sha").length)
        assertEquals(
            "同一参数同一指纹（与打转判据同一规范化）",
            BatchRules.argsSha(JSONObject().put("path", "a.txt")),
            arr.getJSONObject(0).getString("args_sha"),
        )
        val st = json.getJSONArray("stages")
        assertEquals("parallel", st.getJSONObject(0).getString("t"))
        assertEquals(2, st.getJSONObject(0).getJSONArray("m").length())
        assertEquals(2, st.getJSONObject(0).getJSONArray("m").getInt(1))
        assertEquals("serial", st.getJSONObject(1).getString("t"))
        assertEquals(3, st.getJSONObject(1).getJSONArray("m").getInt(0))
    }

    @Test
    fun `checkpoint_段收口行_done与doneAll`() {
        val json = JSONObject(BatchRules.checkpointStage("b3", 1, listOf(1, 2), listOf(1, 2)))
        assertEquals("b3", json.getString("batch"))
        assertEquals(1, json.getInt("stage"))
        val done = json.getJSONArray("done")
        assertEquals(2, done.length())
        assertEquals(2, json.getJSONArray("done_all").getInt(1))
    }

    @Test
    fun `编排指引_不超过6行_关掉时前缀逐字节一致`() {
        assertTrue("指引必须 ≤6 行，实际 ${BatchRules.ORCHESTRATION_GUIDE.lines().size}", BatchRules.ORCHESTRATION_GUIDE.lines().size <= 6)
        assertTrue("指引要带「批量调用编排」抬头", BatchRules.ORCHESTRATION_GUIDE.startsWith("【批量调用编排】"))
        val prefix = "人设＋硬事实＋记忆"
        val on = BatchRules.systemPrefix(prefix)
        assertEquals("开：原前缀逐字节保留，只在后面追加指引", prefix + "\n\n" + BatchRules.ORCHESTRATION_GUIDE, on)
        assertTrue("开：指引必须可见", on.contains(BatchRules.ORCHESTRATION_GUIDE))
        // 关掉时引擎根本不调 systemPrefix（源码门禁见下）——这里钉纯函数本身：
        assertNotEqualsOnOff(prefix)
    }

    private fun assertNotEqualsOnOff(prefix: String) {
        assertTrue("开与关的 system 文本必须不同（否则指引等于没加）", BatchRules.systemPrefix(prefix) != prefix)
    }

    @Test
    fun `开关_默认关_且没有旁路`() {
        assertFalse("默认必须关（派工单 §1.7）", OrchestrationGate.PREF_DEFAULT)
        assertFalse(OrchestrationGate.enabled(false))
        assertTrue(OrchestrationGate.enabled(true))
        val gate = code("src/main/java/com/example/zhengdao/baopu/agent/OrchestrationGate.kt")
        listOf("devMode", "BuildConfig.DEBUG", "System.getenv", "putBoolean", "edit {").forEach {
            assertFalse("闸门里不许有旁路/写口：$it", gate.contains(it))
        }
    }

    @Test
    fun `开关_写口唯一且在Settings`() {
        val settings = repoRel("src/main/java/com/example/zhengdao/baopu/agent/OrchestrationSettings.kt").readText()
        assertTrue("写口必须在 Settings", settings.contains("putBoolean(OrchestrationGate.PREF_KEY, on)"))
        assertTrue("读仍走闸门（不养第二份读法）", settings.contains("OrchestrationGate.prefOn(ctx)"))
    }

    // ── ② 源码门禁：关掉＝今天行为一行不改 ───────────────────────────────

    @Test
    fun `门禁_编排关仍走native表达式_构造默认关`() {
        val src = code(engine)
        assertTrue(
            "构造默认必须是 { false }（JVM 直测也因此走 native）",
            src.contains("private val orchestrationEnabled: () -> Boolean = { false }"),
        )
        assertTrue(
            "system 正文：开才追加指引，关就是 prefix 本身",
            src.contains("val systemText = if (orchestrated) BatchRules.systemPrefix(prefix) else prefix"),
        )
        assertTrue(
            "超批裁剪：关掉时 batchCalls 就是 outcome.calls 本身",
            src.contains("val batchCalls = if (orchestrated) {") && src.contains("} else {\n                        outcome.calls\n                    }"),
        )
        assertTrue("排段吃的是 batchCalls（关掉＝原批次）", src.contains("ToolScheduling.plan(batchCalls)"))
    }

    @Test
    fun `门禁_编排开才有一条编号回灌`() {
        val src = code(engine)
        assertTrue("必须合成一条", src.contains("val feed = c.feed()"))
        assertTrue("这一条要落库（重启回放看得见）", src.contains("persistAssistantMessage(sessionId, feed)"))
        assertTrue("这一条要进在途历史（模型下一步看得见）", src.contains("\"local-batch-${'$'}{System.nanoTime()}\""))
        assertTrue("编排开时逐条回灌必须被挡在 batch == null 后面", src.contains("if (batch == null) {"))
    }

    @Test
    fun `门禁_插话只在段边界_批内不注入`() {
        val src = code(engine)
        val loopAt = src.indexOf("for (stage in stages) {")
        assertTrue("找不到批执行循环", loopAt >= 0)
        val headAt = src.indexOf("val headCall = when (stage) {", loopAt)
        val between = src.substring(loopAt, headAt)
        assertTrue("段边界必须不是盲区：上一段收口后取一次插话", between.contains("drainInterjections(sessionId, history)"))
        assertTrue("只在第二段起取（第一段前循环头刚取空）", between.contains("stageIndex > 0"))
        assertTrue("只在编排开时取（native 的注入时点不动）", between.contains("collector != null"))
        // 批内（单个调用的处理函数里）不许有注入点
        listOf(
            "private fun handleSerialCall(" to "private fun planChangeGate(",
            "private fun runParallelStage(" to "private fun traceStatusOf(",
        ).forEach { (from, to) ->
            val s = src.indexOf(from)
            val e = src.indexOf(to, s)
            assertTrue("找不到函数切片：$from", s >= 0 && e > s)
            assertFalse("批内不许注入插话（$from）", src.substring(s, e).contains("drainInterjections"))
        }
    }

    @Test
    fun `门禁_超批不进打转计数`() {
        val src = code(engine)
        val i = src.indexOf("runToolCalls += outcome.calls.size")
        val act = src.indexOf("_state.value = RunState.ACTING", i)
        val block = src.substring(i, act)
        assertTrue("重复计数必须只数前 8 个（超批没执行，不许背重复）", block.contains("for (call in batchCalls)"))
        assertFalse("旧的整批计数写法必须消失", block.contains("for (call in outcome.calls)"))
        assertTrue("裁剪点用 RunRules.MAX_BATCH_CALLS", block.contains("outcome.calls.take(RunRules.MAX_BATCH_CALLS)"))
    }

    @Test
    fun `门禁_批checkpoint行型在引擎里接上`() {
        val src = code(engine)
        assertTrue("批首行必须落 state=BatchCheckpoint", src.contains("state = \"BatchCheckpoint\""))
        assertTrue("tool 必须留空（否则会混进 UI 的步活动列表）", src.contains("tool = \"\","))
        assertTrue("批首行要在执行前落", src.contains("persistBatchCheckpoint(sessionId, stepCount, c, stages)"))
        assertTrue("段收口要更新同一行", src.contains("updateBatchCheckpoint("))
        assertTrue(
            "更新目标必须是本批那一行（MAX(id) 的 BatchCheckpoint）",
            src.contains("state = 'BatchCheckpoint')"),
        )
        val runtime = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(
            "kill 在批里也必须报「中断于第 N 步」",
            runtime.contains("\"Thinking\", \"Acting\", \"Compressing\", \"BatchCheckpoint\""),
        )
    }

    @Test
    fun `门禁_DB版本与新表未动`() {
        val db = repoRel("src/main/java/com/example/zhengdao/baopu/BaopuDb.kt").readText()
        assertTrue(
            "本批不动 BaopuDb.VERSION（动了就是迁移，必须回来改这一行并说明）",
            db.contains("const val VERSION = 19"),
        )
        val mainRoot = repoRel("src/main/java")
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("batch_checkpoint", ignoreCase = true) }
            .map { it.name }
            .toList()
        assertEquals("不许为批账新建表：$offenders", emptyList<String>(), offenders)
    }

    // ── ② 源码门禁：开关在装配层接上 ──────────────────────────────────────

    @Test
    fun `门禁_装配层接闸门_引擎默认关`() {
        val runtime = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(
            "生产必须接只读闸门（默认关 ⇒ 线上行为不变）",
            runtime.contains("orchestrationEnabled = { OrchestrationGate.enabledNow(appCtx) }"),
        )
    }

    // ── 负例的机器证明：把 1.5k 预算调小 ⇒ 截断必然发生 ────────────────────

    @Test
    fun `负例_预算被放大_截断标记消失即红`() {
        val long = "乙".repeat(3000)
        val entries = (1..8).map { entry(it, 8, detail = long) }
        val roomy = BatchRules.feed(1, entries, limit = 40_000)
        assertFalse("预算够时不该截断（证明标记不是无条件打的）", roomy.contains(BatchRules.TRUNCATED_MARK))
        assertTrue("8 条正文都在", roomy.contains("乙".repeat(3000)))
        val tight = BatchRules.feed(1, entries, limit = 1500)
        assertTrue("预算回到 1.5k ⇒ 必须截断", tight.contains(BatchRules.TRUNCATED_MARK))
        assertTrue("截断后仍不超预算", tight.length <= 1500)
    }

    @Test
    fun `负例_空批也有头_不崩`() {
        val feed = BatchRules.feed(9, emptyList())
        assertNotNull(feed)
        assertEquals("【第 9 批 · 0 个调用】", feed)
        assertTrue("空批也不许超过预算", feed.length < BatchRules.FEED_MAX_CHARS)
    }

    @Test
    fun `负例_段收口JSON是对象不是数组`() {
        val json = JSONObject(BatchRules.checkpointStage("b1", 2, emptyList(), listOf(1)))
        assertTrue("必须是 JSON 对象（run_log.err 的既有形状）", json.has("batch"))
        assertFalse("不许写成裸数组", BatchRules.checkpointStage("b1", 2, emptyList(), listOf(1)).startsWith("["))
        assertTrue(json.getJSONArray("done").length() == 0)
        assertTrue(json.getJSONArray("done_all").length() == 1)
    }
}
