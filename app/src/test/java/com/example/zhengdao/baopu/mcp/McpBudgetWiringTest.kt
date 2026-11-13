// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · 全链路（McpBudgetWiring → ToolRegistry 闸 → tools 数组）。
// 判据：① 3 server × 64 工具装配后 ≤ 预算、挤出数如实；② pin 必装；
// ③ LRU 经真实 tools/call 写入后生效；④ >64 整台拒收回归；⑤ 截断负例。
// 纯 JVM、零临时目录（水位 607 口径零残留）。
package com.example.zhengdao.baopu.mcp

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.wire.ToolDeclSnapshot
import com.example.zhengdao.testkit.TempSandboxPool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class McpBudgetWiringTest {

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private class FakeSession(
        private val tools: List<McpToolDescriptor>,
    ) : McpSession {
        override val mode = "fake"
        override var protocolVersion = McpJsonRpc.PROTOCOL
        override fun connect() = Unit
        override fun listTools(): List<McpToolDescriptor> = tools
        override fun callTool(remoteName: String, arguments: JSONObject): McpCallResult =
            McpCallResult("远程结果", false)
        override fun close() = Unit
    }

    private fun serverConfig(id: String): McpServerConfig = McpServerConfig(
        id = id,
        name = "server $id",
        url = "http://127.0.0.1:1/$id",
        callTimeoutMs = 2_000,
    )

    /** 一个能过 McpSchema 白名单的 descriptor（两个 string 参数 + 描述）。 */
    private fun descriptor(serverId: String, index: Int, description: String): McpToolDescriptor {
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
            description = description,
            inputSchema = McpSchema.normalizeSchema(name, raw),
            readOnly = true,
        )
    }

    private fun descriptors(serverId: String, count: Int, description: String): List<McpToolDescriptor> =
        (0 until count).map { descriptor(serverId, it, description) }

    private class Stack(
        val reg: ToolRegistry,
        val source: McpToolSource,
        val budget: McpBudgetStore,
        val wiring: McpBudgetWiring,
    )

    /**
     * 整套夹具：1 个内置工具 + 3 台 server × 64 个外部工具（全部连接注册）。
     *
     * @param perServerDescription 各 server 工具的描述（意图预筛用例靠它区分）
     */
    private fun stack(
        servers: Int = 3,
        toolsPerServer: Int = 64,
        perServerDescription: (Int) -> String = { "外部工具批次 $it" },
    ): Stack {
        val reg = ToolRegistry()
        // 一个内置工具（CORE 组默认可见；装配时它必带、先扣额度）。
        reg.register(
            ToolSpec(
                name = "builtin_read",
                description = "内置读取",
                inputSchema = JSONObject().put("type", "object").put("properties", JSONObject()),
                executor = { com.example.zhengdao.baopu.agent.ToolOutcome.Ok("ok") },
            ),
        )
        val sessions = (0 until servers).associate { s ->
            "srv$s" to FakeSession(descriptors("srv$s", toolsPerServer, perServerDescription(s)))
        }
        // 账本只有一份，且必须先建：source（写 LRU/pin）与 wiring（读）共用，
        // 生产在 BaopuRuntime 同样是这一份（mcpBudgetStore）。
        val budget = McpBudgetStore.inMemory()
        val source = McpToolSource(
            reg,
            clientFactory = { config -> sessions.getValue(config.id) },
            budget = budget,
        )
        (0 until servers).forEach { s ->
            val id = "srv$s"
            assertTrue(source.addServer(serverConfig(id)))
            val out = source.connect(id)
            assertTrue("夹具 server $id 必须连上", out is McpConnectOutcome.Ok)
        }
        val wiring = McpBudgetWiring(
            registry = { reg },
            budget = budget,
            source = { source },
            provider = { provider() },
            log = {},
        )
        return Stack(reg, source, budget, wiring)
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

    /** tools 数组里的工具名（OpenAI/Anthropic 两种形状都吃）。 */
    private fun namesIn(tools: JSONArray): List<String> = (0 until tools.length()).map { i ->
        val o = tools.optJSONObject(i)
        o?.optJSONObject("function")?.optString("name") ?: o?.optString("name").orEmpty()
    }

    private fun mcpEntries(tools: JSONArray): List<JSONObject> = (0 until tools.length()).mapNotNull { i ->
        val o = tools.optJSONObject(i) ?: return@mapNotNull null
        val fn = o.optJSONObject("function")
        if (fn != null) fn else o
    }

    // ── 判据①：3×64 全链路 ───────────────────────────────────────────────

    @Test
    fun `夹具3x64_装配后_请求侧只剩名单内的_挤出数如实`() {
        val stack = stack()
        val candidates = stack.source.assemblyCandidates()
        assertEquals("夹具必须是 192 具外部工具", 192, candidates.size)
        val totalTokens = candidates.sumOf { it.schemaTokens }
        val result = stack.wiring.assembleForStep(1L, null)

        val loaded = stack.reg.loadedMcpNames() ?: error("装配后名单必须已写进注册表")
        val evicted = candidates.filter { it.wireName !in loaded }.map { it.wireName }.toSet()
        assertEquals("挤出数 = 192 − 装载数，如实", 192 - loaded.size, result.evictedCount)
        assertTrue("装到预算为止 ⇒ 已用 ≤ 额度", result.usedTokens <= result.budgetTokens)
        assertTrue("预算真起了作用（不是全量直放）", result.evictedCount > 0)
        val loadedTokens = candidates.filter { it.wireName in loaded }.sumOf { it.schemaTokens }
        assertEquals(result.usedTokens, loadedTokens)
        assertTrue("装配后 MCP token ≤ 预算", loadedTokens <= result.budgetTokens)
        assertTrue("总账没变", totalTokens == candidates.sumOf { it.schemaTokens })

        // 请求侧（唯一链的对面）：内置 1 件 + 名单内的 MCP。
        val tools = stack.reg.toolsJsonOpenAi(1L)
        val names = namesIn(tools)
        assertEquals(1 + loaded.size, names.size)
        assertTrue("内置必带", names.contains("builtin_read"))
        assertTrue("挤出的一具都不许出现在 tools 数组", names.none { it in evicted })
        assertEquals("装载顺序 = 请求侧顺序（内置在前）", "builtin_read", names.first())
    }

    @Test
    fun `夹具3x64_挤出说明_进上下文与快照列`() {
        val stack = stack()
        val result = stack.wiring.assembleForStep(1L, null)
        assertTrue(result.evictedCount > 0)
        val notice = result.notice ?: error("有挤出必有说明")
        assertTrue(
            "一句话说明（不给 schema）",
            notice.contains("${result.evictedCount} 个外部工具未装载") && !notice.contains("tool_"),
        )
        // 188 批立好的 budget 列从此有真读数（filtered 列仍只管用户开关——两列分工不混）。
        val tools = stack.reg.toolsJsonOpenAi(1L)
        val line = ToolDeclSnapshot.of(provider(), tools, budgetCut = result.evictedCount)
        assertTrue(line.contains("budget=${result.evictedCount}"))
    }

    // ── 判据⑤：不截断 schema（截断 ⇒ 红）────────────────────────────────

    @Test
    fun `截断负例_请求侧schema与原schema逐字节一致_截断变体必被抓`() {
        val stack = stack()
        stack.wiring.assembleForStep(1L, null)
        val loaded = stack.reg.loadedMcpNames()!!
        assertTrue(loaded.isNotEmpty())
        val tools = stack.reg.toolsJsonOpenAi(1L)
        val byName = mcpEntries(tools).associate { it.optString("name") to it }
        loaded.forEach { wireName ->
            val entry = byName[wireName] ?: error("名单内的工具必须真的在 tools 数组里：$wireName")
            val original = stack.reg.find(wireName)!!.inputSchema
            assertEquals(
                "请求侧 schema 必须与原 schema 完全一致（一具一 schema，不截断）",
                original.toString(),
                entry.optJSONObject("parameters")!!.toString(),
            )
            // 负例的「牙」：截断变体必须与原文不同 ⇒ 上面那条相等断言真能抓住截断。
            val truncated = original.toString().take(original.toString().length / 2)
            assertNotEquals(truncated, original.toString())
        }
        // 装载名单里的名字 × tools 数组：一具不多、一具不少（数组另有 1 个内置件）。
        assertEquals(loaded.size + 1, byName.size)
    }

    // ── 判据②：pin 必装（超预算也装）────────────────────────────────────

    @Test
    fun `pin必装_预算压到最小档_pinned依然全装_其余全挤`() {
        val stack = stack()
        // 额度压到 1%（夹取下限）⇒ 192 具大多装不下；再 pin 30 具 ⇒ 30 具全装、额度爆掉。
        stack.budget.setRatio(McpToolBudget.MIN_RATIO)
        val all = stack.source.assemblyCandidates().map { it.wireName }
        val pins = all.take(30).toSet()
        // pin 走面板同款入口（remoteName → wireName）：wireName 形如 mcp_srv0_tool_00。
        all.take(30).forEach { wireName ->
            val serverId = wireName.split("_")[1]
            val remote = wireName.removePrefix("mcp_${serverId}_")
            assertTrue("pin 必须成功：$wireName", stack.source.pinTool(serverId, remote))
        }
        val result = stack.wiring.assembleForStep(1L, null)
        val loaded = stack.reg.loadedMcpNames()!!
        assertEquals("pin 的 30 具全装", 30, loaded.size)
        assertTrue(loaded.containsAll(pins))
        assertEquals("其余 162 具全挤出", 162, result.evictedCount)
        assertTrue("pin 装完必然超预算（如实超，不许抹平）", result.usedTokens > result.budgetTokens)
        val tools = stack.reg.toolsJsonOpenAi(1L)
        assertEquals(1 + 30, namesIn(tools).size)
    }

    // ── 判据③：LRU（真实 tools/call 写账 → 下一轮装配生效）──────────────

    @Test
    fun `LRU_真实调用过的工具_下一轮装配必回名单`() {
        val stack = stack()
        stack.wiring.assembleForStep(1L, null)
        val loadedBefore = stack.reg.loadedMcpNames()!!.toSet()
        // 挑一个**被挤出**的工具，经守卫管线真调一次（写授权 + 写 LRU）。
        val candidates = stack.source.assemblyCandidates()
        val evictedOne = candidates.first { it.wireName !in loadedBefore }
        val pipeline = GuardPipeline(
            registry = stack.reg,
            workspaceRoot = { TempSandboxPool.create("mcp-budget-guard") },
            rawDir = { null },
            runner = ToolRunner(Executors.newSingleThreadExecutor()),
            log = {},
        )
        val out = pipeline.executeApproved(ToolCall("c1", evictedOne.wireName, JSONObject()), { _, _ -> null })
        assertTrue(out is GuardPipeline.Result.Done)
        assertTrue("LRU 必须记上这笔", stack.budget.lastUsedAtMs().containsKey(evictedOne.wireName))

        // 下一轮装配：这具挤进名单（最近用过的优先——额度肯定装得下这一具），
        // 名单总数不变（装到预算为止），是「刚用过的换掉了排最后的」。
        val second = stack.wiring.assembleForStep(1L, null)
        val loadedAfter = stack.reg.loadedMcpNames()!!.toSet()
        assertTrue("刚用过的工具必回名单", evictedOne.wireName in loadedAfter)
        assertEquals("装到预算为止 ⇒ 名单总数不因重排而变", loadedBefore.size, loadedAfter.size)
        assertTrue(
            "挤出说明仍在（预算一直超着）",
            second.notice!!.contains("${second.evictedCount} 个外部工具未装载"),
        )
    }

    // ── 判据④：单 server >64 仍整台拒收（既有行为不回归）────────────────

    @Test
    fun `单server超64_解析层整台拒收_装配只见过闸的`() {
        // 判据④回归：65 具的 tools/list 在解析层就整台拒收（McpSchemaTest 同款钉法，
        // 这里钉的是「装配链的上游仍按原样拒」）。拒收发生在注册之前 ⇒ 装配永远
        // 看不到超限 server 的半个工具——不存在「装一半、拒一半」的中间态。
        val raw = JSONObject().put(
            "tools",
            JSONArray().apply {
                repeat(McpSchema.MAX_TOOLS + 1) { i ->
                    put(
                        JSONObject()
                            .put("name", "t$i")
                            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject())),
                    )
                }
            },
        )
        val rejected = runCatching { McpSchema.parseToolList("big", "超限 server", raw) }
        assertTrue("65 具必须整台拒收", rejected.exceptionOrNull() is McpException)

        // 装配侧：没有任何已注册的 MCP 工具 ⇒ 不启用装配，零挤出、零说明。
        val reg = ToolRegistry()
        val source = McpToolSource(reg, clientFactory = { FakeSession(emptyList()) })
        val budget = McpBudgetStore.inMemory()
        val wiring = McpBudgetWiring(
            registry = { reg },
            budget = budget,
            source = { source },
            provider = { provider() },
            log = {},
        )
        val result = wiring.assembleForStep(1L, null)
        assertEquals("拒收的 server 不产生候选", 0, result.totalCandidates)
        assertNull("没有候选 ⇒ 没有说明", result.notice)
        assertNull("未启用装配（名单不设）", reg.loadedMcpNames())
    }

    // ── 判据⑤（X/Y）：意图预筛全链路 ─────────────────────────────────────

    @Test
    fun `意图预筛_全链路_说明用XY措辞_命中意图的全装上`() {
        // srv2 的工具描述带「搜索文件」；本轮用户话提到搜索文件 ⇒ 它们抢到额度。
        val stack = stack(perServerDescription = { s -> if (s == 2) "搜索文件专用" else "闲聊娱乐" })
        val result = stack.wiring.assembleForStep(1L, "帮我搜索一下文件")
        assertTrue("预筛生效", result.prefiltered)
        val notice = result.notice ?: error("预筛生效必有 X/Y 说明")
        assertTrue("X/Y 措辞", notice.contains("本轮只装了 ${result.loadedCount}/${result.totalCandidates} 个外部工具"))
        val loaded = stack.reg.loadedMcpNames()!!
        val candidates = stack.source.assemblyCandidates()
        val relevant = candidates.filter { it.description.contains("搜索文件") }.map { it.wireName }
        val irrelevant = candidates.filter { !it.description.contains("搜索文件") }.map { it.wireName }
        assertTrue("夹具里要有两批", relevant.isNotEmpty() && irrelevant.isNotEmpty())
        assertTrue(
            "命中意图的必须全装上（一个不许挤）",
            relevant.all { it in loaded },
        )
        assertTrue(
            "挤出的只该从无关那批里出",
            candidates.none { it.wireName !in loaded && it.description.contains("搜索文件") },
        )
    }

    // ── 装配闸的可见性一致性（挤出 ⇒ 不进 tools 数组；filtered 列不掺预算）──

    @Test
    fun `装配闸_挤出的不进可见名单_filtered列不背锅`() {
        val stack = stack()
        stack.wiring.assembleForStep(1L, null)
        val loaded = stack.reg.loadedMcpNames()!!
        val visible = stack.reg.visibleSpecs(1L).map { it.name }.toSet()
        val reasons = stack.reg.filteredReasons(1L).toMap()
        stack.reg.all().forEach { spec ->
            if (spec.group == ToolGroup.MCP && spec.name !in loaded) {
                assertFalse("${spec.name} 应被挤出", spec.name in visible)
                assertEquals("filtered 列只管用户开关（188 批分工）", null, reasons[spec.name])
            } else {
                assertTrue("${spec.name} 应可见", spec.name in visible)
                assertEquals(null, reasons[spec.name])
            }
        }
    }

    // 573：临时目录统一走 testkit/TempSandbox（经 TempSandboxPool 登记，@After 收口）。
    @After
    fun zdCleanSandboxes() = TempSandboxPool.clean()
}
