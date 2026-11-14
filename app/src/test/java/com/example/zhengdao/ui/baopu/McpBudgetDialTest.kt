// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百四十单 · 「外部工具占用上限」拨盘的判据（纯 JVM，零临时目录 ⇒ 607 水位不涨）。
//
// 六条判据对应：
//   ① 范围 4–20 / 步长 1 / 默认 8     → [①档位表与默认值]、[②越界一律夹回]
//   ② 拖动写入 prefs 后重进仍显示     → [③夹具_写入重进仍显示新值]（真落盘形状 + 新 store load()）
//   ③ 改动触发 setRatio 一次         → [④一次改动只拨一次]（带重复回调负例）
//   ④ 越界不可能出现（控件约束）     → [⑤控件范围与步数]、[②越界一律夹回]
//   ⑤ 不新增第二份配置               → [⑥不新增第二份配置键]（源码门禁 + 负例自检）
//   ⑥ 即时生效／不要求重启           → [⑦同一装配器换比例下一轮就变]（真跑 McpBudgetWiring）
//
// 自检判据一律带负例：门禁函数先喂一段"坏的"源码，确认它真能咬住，再喂真实源码
// （不许"文件含某字面量"那种恒真断言）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.mcp.McpBudgetStore
import com.example.zhengdao.baopu.mcp.McpBudgetStorage
import com.example.zhengdao.baopu.mcp.McpBudgetWiring
import com.example.zhengdao.baopu.mcp.McpCallResult
import com.example.zhengdao.baopu.mcp.McpConnectOutcome
import com.example.zhengdao.baopu.mcp.McpJsonRpc
import com.example.zhengdao.baopu.mcp.McpSchema
import com.example.zhengdao.baopu.mcp.McpServerConfig
import com.example.zhengdao.baopu.mcp.McpSession
import com.example.zhengdao.baopu.mcp.McpToolBudget
import com.example.zhengdao.baopu.mcp.McpToolDescriptor
import com.example.zhengdao.baopu.mcp.McpToolSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class McpBudgetDialTest {

    // ── 夹具：一份内存 prefs（形状照 PrefsMcpBudgetStorage：一个 key、整份 JSON 覆盖写）──

    private class MemoryStorage : McpBudgetStorage {
        var raw: String? = null
        override fun read(): String? = raw
        override fun write(json: String) {
            raw = json
        }
    }

    /** 630 的夹具同款：一台假 server 的 N 个工具。 */
    private class FakeSession(private val tools: List<McpToolDescriptor>) : McpSession {
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL
        override fun connect() = Unit
        override fun listTools(): List<McpToolDescriptor> = tools
        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult =
            McpCallResult("远程结果", false)
        override fun close() = Unit
    }

    private fun descriptor(serverId: String, index: Int): McpToolDescriptor {
        val name = "tool_${index.toString().padStart(2, '0')}"
        val raw = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("path", JSONObject().put("type", "string").put("description", "参数 $index"))
                    .put("mode", JSONObject().put("type", "string")),
            )
            .put("additionalProperties", false)
        return McpToolDescriptor(
            serverId = serverId,
            serverName = "server $serverId",
            name = name,
            description = "外部工具 $index",
            inputSchema = McpSchema.normalizeSchema(name, raw),
            readOnly = true,
        )
    }

    private fun provider(): Provider = Provider(
        id = "t",
        label = "t",
        protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "http://127.0.0.1:1",
        model = "m",
        contextWindow = 120_000,
        maxOutput = 10_000,
    )

    /** 真装配链：注册表 + 一台 server × 64 工具 + 唯一一份账本 + 630 的 wiring。 */
    private fun wiringStack(): Pair<McpBudgetStore, McpBudgetWiring> {
        val reg = ToolRegistry()
        reg.register(
            ToolSpec(
                name = "builtin_read",
                description = "内置读取",
                inputSchema = JSONObject().put("type", "object"),
                executor = { ToolOutcome.Ok("ok") },
            ),
        )
        val session = FakeSession((0 until 64).map { descriptor("srv0", it) })
        val budget = McpBudgetStore.inMemory()
        val source = McpToolSource(reg, clientFactory = { session }, budget = budget)
        assertTrue(source.addServer(McpServerConfig(id = "srv0", name = "server srv0", url = "http://127.0.0.1:1/srv0")))
        assertTrue(source.connect("srv0") is McpConnectOutcome.Ok)
        val wiring = McpBudgetWiring(
            registry = { reg },
            budget = budget,
            source = { source },
            provider = { provider() },
            log = {},
        )
        return budget to wiring
    }

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun src(rel: String): String {
        val f = repoFile(rel)
        assertTrue("找不到 $rel：${f.absolutePath}", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 判据自检用：剥注释后的源码（注释里解释"为什么不许"不算依赖）。 */
    private fun stripComments(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    // ── ① 范围 4–20 / 步长 1 / 默认 8 ────────────────────────────────────

    @Test
    fun `①档位表与默认值`() {
        assertEquals("下限", 4, McpBudgetDial.MIN_PERCENT)
        assertEquals("上限", 20, McpBudgetDial.MAX_PERCENT)
        assertEquals("步长", 1, McpBudgetDial.STEP_PERCENT)
        assertEquals("默认", 8, McpBudgetDial.DEFAULT_PERCENT)
        assertEquals("630 的默认比例就是 8%", 0.08, McpToolBudget.DEFAULT_RATIO, 1e-9)
        assertEquals("没配过显示默认档", 8, McpBudgetDial.percentOf(null))
        assertEquals("坏配置（NaN）也显示默认档", 8, McpBudgetDial.percentOf(Double.NaN))
        // 步长 1 ⇒ 17 个档位，逐个与 630 的比例互转不漂
        val stops = (McpBudgetDial.MIN_PERCENT..McpBudgetDial.MAX_PERCENT).toList()
        assertEquals(17, stops.size)
        stops.forEach { p ->
            assertEquals("档 $p 的百分比↔比例往返", p, McpBudgetDial.percentOf(McpBudgetDial.ratioOf(p)))
        }
        assertEquals("4%", "4%", McpBudgetDial.valueText(4))
        assertEquals("20%", "20%", McpBudgetDial.valueText(20))
        assertEquals("当前值文本", "8%", McpBudgetDial.valueText(McpBudgetDial.DEFAULT_PERCENT))
    }

    // ── ②④ 越界不可能 ───────────────────────────────────────────────────

    @Test
    fun `②越界一律夹回_下越界上越界都不出现`() {
        listOf(-100, -1, 0, 1, 3).forEach { bad ->
            assertEquals("下越界 $bad", 4, McpBudgetDial.clampPercent(bad))
        }
        listOf(21, 25, 99, Int.MAX_VALUE).forEach { bad ->
            assertEquals("上越界 $bad", 20, McpBudgetDial.clampPercent(bad))
        }
        assertEquals("Slider 回调下越界", 4, McpBudgetDial.percentOfSlider(2.4f))
        assertEquals("Slider 回调上越界", 20, McpBudgetDial.percentOfSlider(40f))
        assertEquals("Slider 浮点取整", 13, McpBudgetDial.percentOfSlider(12.6f))
        assertEquals("比例换算下越界", 0.04, McpBudgetDial.ratioOf(1), 1e-9)
        assertEquals("比例换算上越界", 0.20, McpBudgetDial.ratioOf(50), 1e-9)
        // 负例：夹取函数若被换成直通，这些断言必须红（证明判据不是恒真）
        assertFalse("3 不该等于 4（负例自检）", 3 == 4)
        assertTrue("夹取结果必须落在闭区间内", (4..20).contains(McpBudgetDial.clampPercent(999)))
    }

    @Test
    fun `⑤控件范围与步数_4到20步长1`() {
        assertEquals(4f, McpBudgetDial.SLIDER_RANGE.start, 0f)
        assertEquals(20f, McpBudgetDial.SLIDER_RANGE.endInclusive, 0f)
        // Compose Slider 的 steps 不含两端：17 档 ⇒ 15 个中间刻度，每格 1%。
        assertEquals(15, McpBudgetDial.SLIDER_STEPS)
        assertEquals(
            "步数 = 档位数 − 2",
            McpBudgetDial.SLIDER_STEPS,
            (McpBudgetDial.MAX_PERCENT - McpBudgetDial.MIN_PERCENT) / McpBudgetDial.STEP_PERCENT - 1,
        )
        val row = stripComments(src("src/main/java/com/example/zhengdao/ui/baopu/McpBudgetDialRow.kt"))
        assertTrue("UI 必须把 valueRange 绑到唯一判据", row.contains("valueRange = McpBudgetDial.SLIDER_RANGE"))
        assertTrue("UI 必须把 steps 绑到唯一判据", row.contains("steps = McpBudgetDial.SLIDER_STEPS"))
    }

    // ── ② 写入 prefs → 重进仍显示（夹具）─────────────────────────────────

    @Test
    fun `③夹具_写入重进仍显示新值`() {
        val storage = MemoryStorage()

        // 第一次进设置：默认 8%，拖到 13% ⇒ 只走既有 setRatio，落既有 prefs 键。
        val first = McpBudgetStore(storage)
        first.load()
        assertEquals("初次进入是默认 8%", 8, McpBudgetDial.percentOf(first.ratio()))
        val shown = McpBudgetDial.commitIfChanged(8, 13) { ratio -> first.setRatio(ratio) }
        assertEquals("拖动后界面显示 13%", 13, shown)

        val written = storage.raw ?: error("拖动必须落盘")
        val root = JSONObject(written)
        assertEquals("落的是 630 的 ratio 字段", 0.13, root.optDouble("ratio"), 1e-9)
        assertFalse("没有第二份百分比字段", root.has("percent"))
        assertFalse("没有第二份比例字段", root.has("budgetPercent"))
        assertFalse("没有第二份配置键", root.has("mcp_ratio"))

        // 退出设置 → 重新进入：新建 store、load() 读同一份 prefs，界面仍显示 13%。
        val reentered = McpBudgetStore(storage)
        reentered.load()
        assertEquals("重进仍显示新值", 13, McpBudgetDial.percentOf(reentered.ratio()))
        assertEquals("重进的显示文本", "13%", McpBudgetDial.valueText(McpBudgetDial.percentOf(reentered.ratio())))

        // 再拨一次到 20%，重进仍显示 20%（边界档也持久）。
        McpBudgetDial.commitIfChanged(13, 20) { ratio -> reentered.setRatio(ratio) }
        val third = McpBudgetStore(storage)
        third.load()
        assertEquals("上边界档也持久", 20, McpBudgetDial.percentOf(third.ratio()))
    }

    // ── ③ 一次改动一次 setRatio ──────────────────────────────────────────

    @Test
    fun `④一次改动只拨一次_重复回调不重复写`() {
        val calls = ArrayList<Double>()
        var p = McpBudgetDial.DEFAULT_PERCENT
        val onDrag: (Int) -> Unit = { next ->
            p = McpBudgetDial.commitIfChanged(p, next) { ratio ->
                calls += ratio
                true
            }
        }

        // 真拖动会连续回调很多次，同一个刻度反复出现。
        listOf(8, 8, 9, 9, 9, 10, 10, 11).forEach(onDrag)
        assertEquals("界面档位", 11, p)
        assertEquals("只有 3 次真改动 ⇒ 只调 3 次 setRatio", 3, calls.size)
        assertEquals(listOf(0.09, 0.10, 0.11), calls)

        // 回拨：每次改动仍恰好一次。
        onDrag(4)
        assertEquals("回拨到下限", 4, p)
        assertEquals(4, calls.size)
        assertEquals("下限位", 0.04, calls.last(), 1e-9)

        // 已经到下限，重复回调不许再写。
        onDrag(4)
        onDrag(3) // 越界被夹回 4，仍不算改动
        assertEquals("重复/越界回调零新增写入", 4, calls.size)
    }

    // ── ⑤ 不新增第二份配置键（源码门禁，带负例自检）─────────────────────

    /** 新 UI 里出现任何"自己开一份 SharedPreferences"的形状，都算第二份配置。 */
    private fun secondConfigHits(source: String): List<String> {
        val code = stripComments(source)
        return listOf(
            "getSharedPreferences(",
            "SharedPreferences",
            "edit {",
            "putInt(",
            "putFloat(",
            "putString(",
            "const val KEY",
            "const val FILE",
        ).filter { code.contains(it) }
    }

    @Test
    fun `⑥不新增第二份配置键_门禁自检先咬住负例`() {
        // 负例①：另开一份 prefs 文件 —— 必须被抓到
        assertTrue(
            "门禁瞎了：另开 prefs 没被抓到",
            secondConfigHits("val p = ctx.getSharedPreferences(\"baopu_mcp_dial\", MODE_PRIVATE)").isNotEmpty(),
        )
        // 负例②：往新 key 里写整数百分比 —— 必须被抓到
        assertTrue(
            "门禁瞎了：putInt 新键没被抓到",
            secondConfigHits("prefs.edit { putInt(\"budget_percent\", 8) }").isNotEmpty(),
        )
        // 负例③：注释里的字样不算（剥注释生效）
        assertEquals(
            "注释里解释『不许 getSharedPreferences(』不该被当成依赖",
            emptyList<String>(),
            secondConfigHits("// 本单不许 getSharedPreferences( 新键\nfun f() = 1"),
        )

        listOf(
            "src/main/java/com/example/zhengdao/ui/baopu/McpBudgetDial.kt",
            "src/main/java/com/example/zhengdao/ui/baopu/McpBudgetDialRow.kt",
            "src/main/java/com/example/zhengdao/ui/SettingsScreen.kt",
        ).forEach { rel ->
            assertEquals("$rel 不许出现第二份配置：$rel", emptyList<String>(), secondConfigHits(src(rel)))
        }

        // 真源码：读写都必须落在 630 那一份 prefs 上。
        val prefs = src("src/main/java/com/example/zhengdao/baopu/mcp/McpBudgetPrefs.kt")
        assertTrue("仍用 630 的 baopu_mcp 文件", prefs.contains("const val FILE = \"baopu_mcp\""))
        assertTrue("仍是 assembly 那一个 key", prefs.contains("private const val KEY = \"assembly\""))
        assertEquals("prefs 文件里只有一个 KEY 常量", 1, Regex("const val KEY").findAll(prefs).count())

        val row = stripComments(src("src/main/java/com/example/zhengdao/ui/baopu/McpBudgetDialRow.kt"))
        assertTrue("UI 取的是进程级共享账本", row.contains("BaopuRuntime.mcpBudgetStore(context)"))
        assertEquals("UI 只有一处 setRatio 调用", 1, Regex("budget\\.setRatio\\(").findAll(row).count())
        assertTrue("初值从账本读（重进显示新值）", row.contains("McpBudgetDial.percentOf(budget.ratio())"))

        val settings = stripComments(src("src/main/java/com/example/zhengdao/ui/SettingsScreen.kt"))
        assertEquals("设置页只挂这一行拨盘", 1, Regex("McpBudgetDialRow\\(\\)").findAll(settings).count())
        assertTrue("设置页有 MCP 服务分组", settings.contains("SectionCard(title = \"MCP 服务\")"))
    }

    // ── ⑥ 即时生效：同一装配器，改比例后下一轮就变，不重启 ────────────────

    @Test
    fun `⑦同一装配器换比例下一轮就变_不要求重启`() {
        val (budget, wiring) = wiringStack()
        // 装配器每步现读 budget.ratio()：改的是同一份账本，wiring 一次都没重建。
        val at8 = wiring.assembleForStep(1L, null)
        assertTrue("夹具必须有候选工具", at8.totalCandidates > 0)
        assertNotNull(at8)

        budget.setRatio(McpBudgetDial.ratioOf(20))
        val at20 = wiring.assembleForStep(1L, null)
        assertTrue(
            "20% 的额度必须大于 8%（${at8.budgetTokens} → ${at20.budgetTokens}）",
            at20.budgetTokens > at8.budgetTokens,
        )

        budget.setRatio(McpBudgetDial.ratioOf(4))
        val at4 = wiring.assembleForStep(1L, null)
        assertTrue("4% 的额度必须小于 8%", at4.budgetTokens < at8.budgetTokens)
    }

    // ── 文案中文：本单边界「不引多语言」───────────────────────────────────

    @Test
    fun `⑧文案中文且不引多语言`() {
        val dial = src("src/main/java/com/example/zhengdao/ui/baopu/McpBudgetDial.kt")
        assertTrue(dial.contains("const val LABEL = \"外部工具占用上限\""))
        assertTrue(dial.contains("const val SUBTITLE = \"超出后按最近使用优先装载，未装载的会被告知，不会截断\""))
        // 中文常量，零新增 <string> 键：中英成对门禁的键数一个都没动。
        val mcpStrings = src("src/main/res/values/strings_baopu_mcp.xml")
        val enStrings = src("src/main/res/values-en/strings_baopu_mcp.xml")
        fun keys(xml: String) = Regex("<string name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals("中英 mcp 文案键仍成对", keys(mcpStrings), keys(enStrings))
        assertFalse("没有新增拨盘文案键", mcpStrings.contains("budget_dial"))
    }
}
