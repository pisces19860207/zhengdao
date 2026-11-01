// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第三百四十八批 · 读数层接线的门禁。五条，每条对着派工单 348 的一句判据／负例：
// ① 取数口有**生产调用点**（源级：非测试文件里至少一处，且不是定义文件自己）；
// ② 有样本 ⇒ 印真实条数（分子分母都在），不是"0"；
// ③ 空样本／分母不够 ⇒ 只写「样本不足」，**一个字都不许出现 %**（0% 与没数据是两件事）；
// ④ 对外文案无内部词（MetricsSources／snapshot／E1／线值）；
// ⑤ 接线壳只读、不重算（不写盘、不自己乘 100），读数不许喂给任何"触发动作"的入口。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionGroup
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsWiringTest {

    private val now = 1_760_000_000_000L

    private val mainRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")

    /** 剥注释：KDoc 里写「不许 writeText」是说明，不是调用（237／291 同规矩）。 */
    private fun code(f: File): String = f.readText(Charsets.UTF_8)
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    // ── 夹具 ───────────────────────────────────────────────────────────────

    private fun act(id: String, at: Long = now) = DecisionRecord(
        id = id,
        kind = DecisionKind.AUTO_ACT,
        group = DecisionGroup.GRANT,
        actor = DecisionActor.MODEL,
        reason = "夹具",
        evidence = "tool:file_write:a.txt",
        before = "",
        after = "",
        atMs = at,
        sessionId = 1L,
        reversible = true,
        rollbackRef = "",
        rollbackOf = null,
    )

    private fun rollback(id: String, of: String, at: Long = now) = act(id, at).copy(
        kind = DecisionKind.ROLLBACK_OF,
        rollbackOf = of,
    )

    /** 空样本：一条记录都没有（第一次装机／刚清过账）。 */
    private fun emptyInputs() = Metrics.Inputs(nowMs = now)

    // ── ① 源级门禁：取数口真有生产调用点 ────────────────────────────────────

    @Test
    fun `判据①_取数口在非测试文件里至少一处调用`() {
        val defFile = "inspect/MetricsSources.kt"
        // 普查表自己写着那个符号（证据行），不是调用点——与 `HealthCheckTest` 同一排除口径。
        val census = "inspect/HealthSources.kt"
        val pat = Regex("""\bMetricsSources\s*\.\s*snapshot\s*\(""")
        val hits = ArrayList<String>()
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { f ->
                val rel = f.path.replace('\\', '/').substringAfter("src/main/java/com/example/zhengdao/baopu/")
                if (rel == defFile || rel == census) return@forEach
                if (pat.containsMatchIn(code(f))) hits += rel
            }
        assertTrue(
            "取数口又退回零调用了（281 的老毛病：算了没人看）。调用点：$hits",
            hits.isNotEmpty(),
        )
    }

    @Test
    fun `判据①_体检编排每次都读一趟`() {
        val run = code(File(mainRoot, "HealthRun.kt"))
        assertTrue("体检编排里没有读数这一步", run.contains("MetricsWiring.read("))
        assertTrue("读数那一行没印进单子", run.contains("readings = reading?.sheetLines.orEmpty()"))
    }

    // ── ② 有样本 ⇒ 真实条数 ─────────────────────────────────────────────────

    @Test
    fun `判据②_造数据后四率印的是真实分子分母`() {
        val ledger = (1..32).map { act("a$it") } + rollback("r1", of = "a1")
        val line = MetricsWiring.fourRateLine(
            Metrics.compute(emptyInputs().copy(ledger = ledger)),
        )
        assertTrue("违规率没印真实条数：$line", line.contains("违规率 3.1%（1/32）"))
        assertTrue("四率那行没抬头：$line", line.startsWith(MetricsWiring.FOUR_RATE_HEAD))
        assertEquals("有样本的只有违规率一项 ⇒ 只许出现一个百分号：$line", 1, line.count { it == '%' })
    }

    @Test
    fun `判据②_分母刚好十条就算得出来`() {
        val ledger = (1..10).map { act("a$it") } + rollback("r1", of = "a1")
        val line = MetricsWiring.fourRateLine(Metrics.compute(emptyInputs().copy(ledger = ledger)))
        assertTrue("十条够算（分母线是 10）：$line", line.contains("违规率 10%（1/10）"))
    }

    // ── ③ 空态：样本不足，不许 0% ───────────────────────────────────────────

    @Test
    fun `负例_空样本写样本不足且不出现百分号`() {
        val line = MetricsWiring.fourRateLine(Metrics.compute(emptyInputs()))
        assertTrue("空样本要写样本不足：$line", line.contains("样本不足"))
        assertFalse("空样本印了百分号（0% 与没数据是两件事）：$line", line.contains("%"))
    }

    @Test
    fun `负例_分母不够只写样本不足不写零百分比`() {
        // 5 条免问动作、一次都没被回滚：真按 0% 印出来就是"一次都没错"的假清白。
        val ledger = (1..5).map { act("a$it") }
        val line = MetricsWiring.fourRateLine(Metrics.compute(emptyInputs().copy(ledger = ledger)))
        assertFalse("分母 5 也印了百分号：$line", line.contains("%"))
        assertTrue("要写清差多少条：$line", line.contains("样本不足"))
    }

    @Test
    fun `四项全算不出时只留一句样本不足`() {
        val line = MetricsWiring.fourRateLine(Metrics.compute(emptyInputs()))
        assertEquals("四项全空却铺了四遍：$line", 1, Regex("样本不足").findAll(line).count())
    }

    @Test
    fun `单项空态也带差多少条`() {
        val ledger = (1..5).map { act("a$it") }
        val lines = Metrics.compute(emptyInputs().copy(ledger = ledger))
        val text = MetricsWiring.human(lines.first { it.key == MetricKey.VIOLATION })
        assertTrue("没说差多少条：$text", text.contains("只有 5 条，不到 10 条"))
        assertFalse(text.contains("%"))
    }

    // ── ④ 对外文案无内部词 ─────────────────────────────────────────────────

    @Test
    fun `判据④_对外文案不出现内部字段名`() {
        val ledger = (1..32).map { act("a$it") } + rollback("r1", of = "a1")
        val lines = Metrics.compute(emptyInputs().copy(ledger = ledger))
        val shown = (listOf(MetricsWiring.fourRateLine(lines)) + MetricsWiring.centerLines(lines))
            .joinToString("\n")
        listOf("MetricsSources", "snapshot", "E1", "MetricKey", "wire", "ratio", "Inputs").forEach {
            assertFalse("对外文案出现了内部词 $it：$shown", shown.contains(it))
        }
        // 而且确实是中文读数，不是空串
        assertTrue(shown.contains("违规率"))
        assertTrue(shown.contains("任务成功率"))
    }

    @Test
    fun `判据④_治理面板只挑三项_不把十三项全塞`() {
        val lines = Metrics.compute(emptyInputs())
        val center = MetricsWiring.centerLines(lines)
        assertEquals("四率一行＋三项＝四行", 4, center.size)
        assertTrue(center.first().startsWith(MetricsWiring.FOUR_RATE_HEAD))
        assertTrue(center.any { it.contains("任务成功率") })
        assertTrue(center.any { it.contains("技能成功率") })
        assertTrue(center.any { it.contains("不可逆动作占比") })
    }

    // ── ⑤ 接线壳只读、不重算、不触发动作 ───────────────────────────────────

    @Test
    fun `判据⑤_接线壳没有写口也没有弹卡出口`() {
        val text = code(File(mainRoot, "MetricsWiring.kt"))
        listOf(
            "writeText", "appendText", "writeBytes", "mkdirs", "execSQL", "INSERT INTO",
            "UPDATE ", "DELETE FROM", "DecisionLedger.record(", "OpinionWiring",
            "AlertDialog", "Toast", "Snackbar",
        ).forEach { word -> assertFalse("接线壳里出现了 $word（读数只许看）", text.contains(word)) }
    }

    @Test
    fun `判据⑤_不重算比例_数字只从281来`() {
        val text = code(File(mainRoot, "MetricsWiring.kt"))
        assertTrue("没调取数口", text.contains("MetricsSources.snapshot("))
        assertTrue("没调计算口", text.contains("Metrics.compute("))
        // 自己乘 100、自己除分母 = 第二套口径（281 的 Ratio.value 才是唯一算法）
        listOf("* 100", "/ denom", "100.0", "*100").forEach {
            assertFalse("接线壳在自己算比例（$it）——同一份数据两套口径", text.contains(it))
        }
    }

    @Test
    fun `判据⑤_读数不喂给任何触发动作的入口`() {
        val run = code(File(mainRoot, "HealthRun.kt"))
        // 读数只有"印成一行字"这一个去处：明细与面板三行都留给 322，不进判断。
        assertFalse("读数明细进了体检判断", run.contains("reading.lines"))
        assertFalse("面板那三行进体检判断", run.contains("centerLines"))
        assertFalse("读数被当成「账在没人算」的判据喂了回去", run.contains("SignalInput(ledgerReaderWired"))
    }

    // ── 体检单：那一行真的印出来了（空则不许编）──────────────────────────────

    @Test
    fun `单子那行就是四率那行加一句现算无落账`() {
        val lines = Metrics.compute(emptyInputs())
        assertEquals(
            MetricsWiring.fourRateLine(lines) + MetricsWiring.NO_LEDGER_TAIL,
            MetricsWiring.sheetLines(lines).single(),
        )
    }

    @Test
    fun `体检单印出读数那一行`() {
        val organs = listOf(
            HealthCheck.organ(
                OrganProbe(
                    name = "读数层",
                    entry = "MetricsSources.snapshot",
                    declaredIn = "x",
                    callSites = 1,
                    sink = "四率",
                    evidence = listOf("证据"),
                ),
            ),
        )
        val sheet = HealthCheck.sheet(
            now,
            organs,
            emptyList(),
            HealthCheck.signals(SignalInput()),
            emptyList(),
            readings = listOf("读数（四率）：违规率 3.1%（1/32）"),
        )
        assertTrue(sheet.lines.any { it.contains("违规率 3.1%（1/32）") })
    }

    @Test
    fun `读数没算出来时体检单不编一行`() {
        val sheet = HealthCheck.sheet(
            now,
            emptyList(),
            emptyList(),
            HealthCheck.signals(SignalInput()),
            emptyList(),
        )
        assertFalse("没读数却印了一行", sheet.lines.any { it.contains("读数（四率）") })
    }
}
