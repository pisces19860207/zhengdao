// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批 · 御医体检的门禁。四条红线，每条都对应派工单 291 §负例里的一句：
// ①没调用点仍报"健康" ⇒ 红；②零产出不写原因 ⇒ 红；③把"没人派活"记成"它坏了" ⇒ 红；
// ④体检自己弹卡打扰用户 ⇒ 红。另加两条自查：普查数字不许过期、四态/信号/卡点都是闭集。
//
// 还有一条最容易写成假绿的：**"没发现信号"不许写成"没有 bug"**——
// 所以这里钉的不是"有结论"，而是三种结果各自的话术（命中／未发现信号／未取样）不许互相冒充。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthCheckTest {

    private val srcRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")

    private fun code(f: File): String = f.readText(Charsets.UTF_8)
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun probe(
        name: String = "试件",
        callSites: Int = 1,
        present: Boolean = true,
        sink: String? = "账本",
        outputs: Int? = 1,
        lastOutputMs: Long? = 1L,
        previous: OrganState? = null,
        orphan: Boolean = false,
        byDesign: String? = null,
    ) = OrganProbe(
        name = name,
        entry = "X.y",
        declaredIn = "app/src/main/java/x/X.kt",
        callSites = callSites,
        present = present,
        sink = sink,
        outputs = outputs,
        lastOutputMs = lastOutputMs,
        previous = previous,
        orphan = orphan,
        byDesign = byDesign,
        evidence = listOf("X.kt:1 证据"),
    )

    // ── ① 没调用点仍报"健康" ────────────────────────────────────────────────

    @Test
    fun `负例①_零调用点在任何组合下都不会判成在干活`() {
        val states = OrganState.entries + listOf(null)
        states.forEach { prev ->
            listOf(true, false).forEach { present ->
                listOf<Int?>(0, null, 5).forEach { out ->
                    val v = HealthCheck.organ(probe(callSites = 0, present = present, outputs = out, previous = prev))
                    assertFalse("零调用点被判成 ${v.state}", v.state == OrganState.ACTIVE)
                }
            }
        }
    }

    @Test
    fun `负例①_零调用点默认进缺陷台账`() {
        val v = HealthCheck.organ(probe(callSites = 0))
        assertEquals(OrganState.NOT_WIRED, v.state)
        assertEquals(Disposition.DEFECT, v.disposition)
        assertEquals(NoOutputCause.NEVER_DISPATCHED, v.cause)
    }

    @Test
    fun `裁定关着的不进缺陷台账`() {
        val v = HealthCheck.organ(probe(callSites = 0, byDesign = "按 §10③ 关着"))
        assertEquals(OrganState.NOT_WIRED, v.state)
        assertEquals(Disposition.BY_DESIGN, v.disposition)
    }

    @Test
    fun `没实现的不算死代码`() {
        val v = HealthCheck.organ(probe(callSites = 0, present = false, byDesign = "290 在飞"))
        assertEquals(OrganState.NOT_WIRED, v.state)
        assertEquals(Disposition.BY_DESIGN, v.disposition)
    }

    @Test
    fun `孤儿件判该撤掉`() {
        val v = HealthCheck.organ(probe(callSites = 0, orphan = true))
        assertEquals(OrganState.UNUSED_OR_DEAD, v.state)
        assertEquals(Disposition.DEFECT, v.disposition)
    }

    // ── ② 零产出必须写原因 ─────────────────────────────────────────────────

    @Test
    fun `负例②_零产出必带原因`() {
        val v = HealthCheck.organ(probe(outputs = 0))
        assertEquals(OrganState.WIRED_NO_OUTPUT, v.state)
        assertNotNull(v.cause)
        assertTrue("原因不许为空", v.reason.isNotBlank())
    }

    @Test
    fun `负例②_有调用点没落点算接线半截`() {
        val v = HealthCheck.organ(probe(sink = null, outputs = 0))
        assertEquals(OrganState.WIRED_NO_OUTPUT, v.state)
        assertEquals(NoOutputCause.NO_SINK, v.cause)
        assertEquals(Disposition.DEFECT, v.disposition)
    }

    // ── ③ 没人派活 ≠ 它坏了 ────────────────────────────────────────────────

    @Test
    fun `负例③_从没产出过记没人派活`() {
        val v = HealthCheck.organ(probe(outputs = 0, lastOutputMs = null))
        assertEquals(NoOutputCause.NEVER_DISPATCHED, v.cause)
        assertTrue(v.reason.contains("没人派活"))
        assertFalse("不许把没人派活记成坏了", v.reason.contains("坏"))
    }

    @Test
    fun `负例③_以前有过产出这窗没有才算派了没效果`() {
        val v = HealthCheck.organ(probe(outputs = 0, lastOutputMs = 1_000L))
        assertEquals(NoOutputCause.DISPATCHED_NO_EFFECT, v.cause)
    }

    @Test
    fun `连续两次零产出判该撤掉`() {
        val v = HealthCheck.organ(probe(outputs = 0, previous = OrganState.WIRED_NO_OUTPUT))
        assertEquals(OrganState.UNUSED_OR_DEAD, v.state)
        assertEquals(Disposition.DEFECT, v.disposition)
    }

    // ── 未取样不许冒充四态 ─────────────────────────────────────────────────

    @Test
    fun `取不到产出数时四态留空_不许当成零产出`() {
        val v = HealthCheck.organ(probe(outputs = null))
        assertNull("未取样不是第五态，state 必须为空", v.state)
        assertEquals(NoOutputCause.UNMEASURABLE, v.cause)
        assertNotNull("要写清补什么才测得出", v.missingToMeasure)
        assertFalse("未取样不许写成零产出", v.reason.contains("零产出"))
    }

    @Test
    fun `有产出才判在干活`() {
        val v = HealthCheck.organ(probe(outputs = 7))
        assertEquals(OrganState.ACTIVE, v.state)
        assertNull(v.cause)
    }

    @Test
    fun `没有证据行连构造都不许`() {
        val bad = probe().copy(evidence = emptyList())
        val e = runCatching { HealthCheck.organ(bad) }.exceptionOrNull()
        assertNotNull("L14：无证据的结论必须抛", e)
        assertTrue(e!!.message!!.contains("L14"))
    }

    // ── 四态是闭集 ─────────────────────────────────────────────────────────

    @Test
    fun `四态只有四个且线值可往返`() {
        assertEquals(4, OrganState.entries.size)
        OrganState.entries.forEach { assertEquals(it, OrganState.ofWire(it.wire)) }
        assertNull(OrganState.ofWire("healthy"))
    }

    @Test
    fun `六类信号与七类卡点都是闭集`() {
        assertEquals(6, DefectSignal.entries.size)
        assertEquals(7, Blocker.entries.size)
        assertEquals(3, ScanOutcome.entries.size)
    }

    // ── 机能画像 ───────────────────────────────────────────────────────────

    @Test
    fun `理解卡_可见但零选择才判_不可见不算`() {
        val visible = HealthCheck.tool(ToolProbe("read_file", "CORE", visibleByDefault = true, calls = 0))
        assertTrue(visible.blockers.contains(Blocker.UNDERSTANDING))
        assertTrue("要写改描述不是删", visible.note.contains("改描述"))
        val hidden = HealthCheck.tool(ToolProbe("generate_image", "IMAGE", visibleByDefault = false, calls = 0))
        assertFalse(hidden.blockers.contains(Blocker.UNDERSTANDING))
        assertTrue(hidden.note.contains("不是描述问题"))
    }

    @Test
    fun `失败率低于六成才报_样本不够不报`() {
        val low = HealthCheck.tool(ToolProbe("a", "CORE", true, calls = 10, ok = 5))
        assertEquals(50, low.successRatePct)
        assertEquals(DefectSignal.LOW_SUCCESS, low.signal)
        val few = HealthCheck.tool(ToolProbe("b", "CORE", true, calls = 9, ok = 4))
        assertNull("样本 <10 不许报失败率异常", few.signal)
    }

    @Test
    fun `性能卡按P90判`() {
        val slow = HealthCheck.tool(ToolProbe("c", "CORE", true, calls = 3, ok = 3, p90Ms = 9_000L))
        assertTrue(slow.blockers.contains(Blocker.PERFORMANCE))
        val fast = HealthCheck.tool(ToolProbe("d", "CORE", true, calls = 3, ok = 3, p90Ms = 1_000L))
        assertFalse(fast.blockers.contains(Blocker.PERFORMANCE))
    }

    // ── 六类信号：三种结果不许互相冒充 ──────────────────────────────────────

    @Test
    fun `没取到数的四类一律未取样_不许说没问题`() {
        val s = HealthCheck.signals(SignalInput())
        listOf(DefectSignal.CRASH_ANR, DefectSignal.LEDGER_MISMATCH, DefectSignal.TEST_RED, DefectSignal.REPEAT_ASK)
            .forEach { assertEquals("$it 缺数却给了结论", ScanOutcome.NO_DATA, s.first { v -> v.signal == it }.outcome) }
        assertEquals(6, s.size)
    }

    @Test
    fun `空catch没写理由才算吞_写了理由算未发现`() {
        val silent = HealthCheck.signals(
            SignalInput(swallowSampled = true, swallowNotes = listOf(SwallowNote("A.kt:1", hasReason = false))),
        ).first { it.signal == DefectSignal.SWALLOWED }
        assertEquals(ScanOutcome.HIT, silent.outcome)

        val explained = HealthCheck.signals(
            SignalInput(swallowSampled = true, swallowNotes = listOf(SwallowNote("A.kt:1", hasReason = true))),
        ).first { it.signal == DefectSignal.SWALLOWED }
        assertEquals(ScanOutcome.NO_SIGNAL, explained.outcome)
        assertTrue(explained.note.contains("理由"))
    }

    @Test
    fun `测试绿只算未发现信号_不写成健康`() {
        val v = HealthCheck.signals(SignalInput(gatesRan = true)).first { it.signal == DefectSignal.TEST_RED }
        assertEquals(ScanOutcome.NO_SIGNAL, v.outcome)
        assertFalse(v.note.contains("健康"))
    }

    // ── 七类卡点 ───────────────────────────────────────────────────────────

    @Test
    fun `权限读数全缺时判未取样_不许报没有权限卡`() {
        val rows = listOf(HealthCheck.tool(ToolProbe("a", "CORE", true, calls = 5, ok = 5)))
        val b = HealthCheck.blockers(emptyList(), rows, SignalInput()).first { it.blocker == Blocker.PERMISSION }
        assertEquals(ScanOutcome.NO_DATA, b.outcome)
    }

    @Test
    fun `权限被拒三次算权限卡`() {
        val rows = listOf(HealthCheck.tool(ToolProbe("a", "CORE", true, calls = 5, ok = 2, permissionDenied = 3)))
        val b = HealthCheck.blockers(emptyList(), rows, SignalInput()).first { it.blocker == Blocker.PERMISSION }
        assertEquals(ScanOutcome.HIT, b.outcome)
        assertTrue(b.evidence.isNotEmpty())
    }

    @Test
    fun `接线卡只数进缺陷台账的那些_裁定关着的不算`() {
        val defect = HealthCheck.organ(probe(name = "金吾卫", callSites = 0))
        val byDesign = HealthCheck.organ(probe(name = "不良人", callSites = 0, byDesign = "按裁定关着"))
        val b = HealthCheck.blockers(listOf(defect, byDesign), emptyList(), SignalInput())
            .first { it.blocker == Blocker.WIRING }
        assertEquals(ScanOutcome.HIT, b.outcome)
        assertTrue(b.where.contains("金吾卫"))
        assertFalse("裁定关着的不许算接线卡", b.where.contains("不良人"))
    }

    @Test
    fun `七类卡点每类都有一行`() {
        val b = HealthCheck.blockers(emptyList(), emptyList(), SignalInput())
        assertEquals(7, b.size)
        assertEquals(Blocker.entries.toList(), b.map { it.blocker })
    }

    // ── 体检单：≤1 页、不弹卡、尾注不许省 ───────────────────────────────────

    @Test
    fun `体检单不超过一页且带尾注`() {
        val organs = (1..30).map { HealthCheck.organ(probe(name = "部位$it", callSites = 0)) }
        val sheet = HealthCheck.sheet(0L, organs, emptyList(), HealthCheck.signals(SignalInput()), emptyList())
        assertTrue("体检单超了一页：${sheet.lines.size}", sheet.lines.size <= HealthLimits.SHEET_MAX_LINES)
        assertTrue(sheet.lines.last().contains("未发现信号"))
        assertTrue(sheet.lines.any { it.contains("还有") })
    }

    @Test
    fun `体检单里没有健康二字`() {
        val sheet = HealthCheck.sheet(
            0L,
            listOf(HealthCheck.organ(probe(outputs = 3))),
            emptyList(),
            HealthCheck.signals(SignalInput(gatesRan = true)),
            emptyList(),
        )
        assertFalse("体检单不许出现『健康』这种结论级字样", sheet.lines.joinToString("\n").contains("健康"))
    }

    // ── 只读 / 不弹卡：源码门禁 ────────────────────────────────────────────

    @Test
    fun `体检层没有写口_也没有弹卡出口`() {
        val banned = listOf(
            "writeText", "appendText", "writeBytes", "mkdirs", "mkdir(", "execSQL",
            "DecisionLedger.record(", "AlertDialog", "Toast", "Snackbar", "OpinionWiring",
            "Budget(", "Dialog(",
        )
        listOf("HealthCheck.kt", "HealthSources.kt").forEach { name ->
            val text = code(File(srcRoot, name))
            banned.forEach { word -> assertFalse("$name 里出现了 $word（体检只读、不弹卡）", text.contains(word)) }
        }
    }

    @Test
    fun `体检层的SQL都带上界`() {
        val text = code(File(srcRoot, "HealthSources.kt"))
        val marks = Regex("""rawQuery\(""").findAll(text).map { it.range.first }.toList()
        assertTrue("取数层一条查询都没有？", marks.isNotEmpty())
        marks.forEachIndexed { i, start ->
            val end = if (i + 1 < marks.size) marks[i + 1] else text.length
            assertTrue("第 ${i + 1} 条查询没有 LIMIT", text.substring(start, end).contains("LIMIT"))
        }
    }

    // ── 普查数字不许过期（体检自己也要账实相符）──────────────────────────────

    @Test
    fun `普查表与源码一致`() {
        assertTrue("普查表空了？", OrganCensus.PROBES.size >= 14)
        OrganCensus.PROBES.forEach { p ->
            if (!p.present) return@forEach
            val actual = countCalls(p.entry, p.declaredIn)
            if (p.callSites >= OrganCensus.CALL_SITES_FLOOR_ONLY) {
                assertTrue("${p.name}（${p.entry}）实际 $actual 处，普查记 ${p.callSites}：接线被删了？", actual >= p.callSites)
            } else {
                assertEquals("${p.name}（${p.entry}）实际 $actual 处，普查记 ${p.callSites}：改了接线要同步改普查与证据行", p.callSites, actual)
            }
        }
    }

    @Test
    fun `普查覆盖派工单点名的部位`() {
        val names = OrganCensus.PROBES.map { it.name }.toSet()
        listOf(
            "分类器", "勘验", "计簿", "影子", "宰相", "学习器", "反思器", "斥候",
            "起居注", "不良人", "金吾卫", "中书舍人", "调度·夜巡",
        ).forEach { assertTrue("普查缺部位：$it", it in names) }
    }

    /** 与普查同一口径：剥注释后数 `entry` 在 `app/src/main` 里、不在定义文件里的出现次数。 */
    private fun countCalls(entry: String, declaredIn: String): Int {
        val pat = if (entry.contains('.')) {
            val (base, member) = entry.split('.')
            Regex("""\b${Regex.escape(base)}\s*\.\s*${Regex.escape(member)}\b""")
        } else {
            Regex("""\b${Regex.escape(entry)}\b""")
        }
        val skip = declaredIn.substringAfter("src/main/java/", "")
        // 普查表自己的字符串（entry/证据行）不是调用点：把它排除，否则每加一条证据就自己给自己 +1。
        val census = "com/example/zhengdao/baopu/inspect/HealthSources.kt"
        var total = 0
        File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("src/main/java/", "")
            if (rel == census) return@forEach
            if (skip.isNotEmpty() && rel == skip) return@forEach
            total += pat.findAll(code(f)).count()
        }
        return total
    }

    // ── 取数层的纯函数（聚合与百分位）──────────────────────────────────────

    @Test
    fun `轨迹聚合成每个工具一行`() {
        val rows = listOf(
            TraceRaw("read_file", "OK", 100, 10),
            TraceRaw("read_file", "OK", 300, 20),
            TraceRaw("read_file", "REJECTED", 0, 30),
            TraceRaw("read_file", "SKIPPED", 0, 40),
            TraceRaw("write_file", "FAILED", 50, 50),
        )
        val stats = HealthSources.aggregate(rows)
        val read = stats.first { it.tool == "read_file" }
        assertEquals(4, read.calls)
        assertEquals(2, read.ok)
        assertEquals(1, read.rejected)
        assertEquals(1, read.skipped)
        assertEquals(40L, read.lastAtMs)
        assertEquals(300L, read.p90Ms)
    }

    @Test
    fun `百分位空表回零`() {
        assertEquals(0L, HealthSources.percentile(emptyList(), 90))
        assertEquals(1L, HealthSources.percentile(listOf(1L), 90))
        assertEquals(9L, HealthSources.percentile((1L..10L).toList(), 90))
    }

    @Test
    fun `产出数三态_没有是零_读不动是空`() {
        val dir = File("build/tmp/health291").apply { mkdirs() }
        assertEquals(0, HealthSources.outputLines(dir, "不存在.jsonl"))
        val f = File(dir, "有.jsonl").apply { writeText("{\"a\":1}\n{\"a\":2}\n") }
        assertEquals(2, HealthSources.outputLines(dir, f.name))
    }

    // ── 首次体检单（可复现：普查表 + 无运行时数据；转写落 build/reports）────────

    @Test
    fun `首次体检单可复现`() {
        val tools = toolCensus()
        assertEquals("工具表条数与派工单的 68 对不上", 68, tools.size)
        val organs = OrganCensus.PROBES.map { HealthCheck.organ(it) }
        val rows = tools.map { HealthCheck.tool(it) }
        val signals = HealthCheck.signals(
            SignalInput(
                swallowSampled = true,
                swallowNotes = listOf(
                    SwallowNote("MainActivity.kt:381", hasReason = true),
                    SwallowNote("agent/ToolScheduling.kt:264", hasReason = true),
                ),
                toolRows = rows,
                gatesRan = true,
                // 2026-10-30 实测：detektWall 334 条 / 上限 334 ⇒ 绿。
                // 同日上午上限还是 326、这一条是红的（本单三文件 0 条）；同日 `a7856aed`
                // 带理由把上限上调到 334 ⇒ 转绿。体检单是**快照**，不是实时读数：
                // 下一趟重跑要改这里（红了就把那一行填进 redGates）。
                redGates = emptyList(),
            ),
        )
        // 读数层零调用是**源码事实**（普查里查得到），所以"账在、没人算"这趟就能判，不必等数据。
        val readerWired = OrganCensus.of("读数层")?.let { it.callSites > 0 }
        val sheet = HealthCheck.sheet(
            FIRST_SHEET_AT_MS,
            organs,
            rows,
            signals,
            HealthCheck.blockers(organs, rows, SignalInput(ledgerReaderWired = readerWired)),
        )

        // 首次体检（无设备数据）的读数：能静态判的只有 NOT_WIRED，其余一律未取样。
        // 第二百九十六批 §2/§3：影子有了生产调用点 ⇒ 从 NOT_WIRED 挪进"未取样"（6/8，原来是 7/7）。
        assertEquals(6, sheet.count(OrganState.NOT_WIRED))
        assertEquals(0, sheet.count(OrganState.ACTIVE))
        assertEquals(8, sheet.unmeasured())
        assertEquals(ScanOutcome.HIT, sheet.outcomeOf(Blocker.WIRING))
        assertEquals(ScanOutcome.HIT, sheet.outcomeOf(Blocker.LEDGER))
        assertEquals(ScanOutcome.NO_SIGNAL, sheet.outcomeOf(DefectSignal.TEST_RED))
        assertEquals(ScanOutcome.NO_DATA, sheet.outcomeOf(DefectSignal.CRASH_ANR))

        val out = File("build/reports").apply { mkdirs() }
        File(out, "health291-sheet.md").writeText(sheet.lines.joinToString("\n"), Charsets.UTF_8)
    }

    /** 首次体检单的日期（写死：单子要能逐字复现，不许随跑的时间漂）。 */
    private val FIRST_SHEET_AT_MS = 1_793_289_600_000L // 2026-10-30 00:00 +08:00

    /** 从注册表源码里数工具（68 条）＋分组＋默认可见（CORE/EDIT 两组，策略表钉着）。 */
    private fun toolCensus(): List<ToolProbe> {
        val registry = File("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt")
        val text = code(registry)
        assertTrue(
            "默认可见组变了？先改这条断言，再改体检的『默认可见』口径",
            code(File("src/main/java/com/example/zhengdao/baopu/policy/Policy.kt"))
                .contains("defaultVisible = setOf(ToolGroup.CORE, ToolGroup.EDIT, ToolGroup.COMPAT)"),
        )
        val group = Regex(""""([a-z0-9_]+)"\s+to\s+ToolGroup\.([A-Z]+)""")
            .findAll(text.substringAfter("val GROUP_OF"))
            .associate { it.groupValues[1] to it.groupValues[2] }
        val names = Regex("""Entry\(\s*"([a-z0-9_]+)"""")
            .findAll(text.substringAfter("val ENTRIES"))
            .map { it.groupValues[1] }
            .toList()
        val visible = setOf("CORE", "EDIT")
        return names.map { n ->
            val g = group[n] ?: "?"
            ToolProbe(name = n, group = g, visibleByDefault = g in visible, calls = null)
        }
    }
}
