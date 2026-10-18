// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §1.1/§3 J1（update_plan 出现在工具表；参数校验拒绝空 items）
// ＋ §1.1「写类但免确认」的理由钉死。集成用例走**真的** GuardPipeline + 真
// PlanController + 假 store（不 mock 执行链——「截断/sticky/落库一条龙」要跑到底）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlanToolTest {

    private var savedSink: PlanSink? = null

    @Before
    fun saveSink() {
        savedSink = PlanTool.sink
    }

    @After
    fun restoreSink() {
        PlanTool.sink = savedSink
    }

    private fun catalogRegistry(): ToolRegistry = ToolRegistry().apply {
        BaopuToolCatalog.loadInto(this) { File(".") }
    }

    // ── J1：注册与开关 ─────────────────────────────────────────────────

    @Test
    fun `工具表里有update_plan`() {
        val names = BaopuToolCatalog.ENTRIES.map { it.name }
        assertTrue("一张表里必须有 update_plan", names.contains(PlanTool.NAME))
        assertTrue("默认启用", BaopuToolCatalog.DEFAULT_ENABLED.contains(PlanTool.NAME))
        val spec = BaopuToolCatalog.ENTRIES.first { it.name == PlanTool.NAME }.factory { File(".") }
        assertEquals(PlanTool.NAME, spec.name)
    }

    @Test
    fun `进得了模型的tools数组`() {
        val reg = catalogRegistry()
        val openai = reg.toolsJsonOpenAi().let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        assertTrue(
            "OpenAI 兼容 tools 数组里有 update_plan",
            openai.any { it.getJSONObject("function").getString("name") == PlanTool.NAME },
        )
        val anthropic = reg.toolsJsonAnthropic().let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
        assertTrue(
            "Anthropic tools 数组里有 update_plan",
            anthropic.any { it.getString("name") == PlanTool.NAME },
        )
    }

    @Test
    fun `被用户关掉_幻觉调用拿到拒绝而不是静默`() {
        val reg = catalogRegistry()
        reg.setDisabled(PlanTool.NAME, true)
        val spec = reg.find(PlanTool.NAME)!!
        val out = spec.executor(JSONObject().put("items", JSONArray()))
        assertTrue("被关 ⇒ 拒绝（桩）", out is ToolOutcome.Rejected)
        assertFalse("被关的不进 tools 数组", reg.isEnabled(PlanTool.NAME))
    }

    // ── 「写类但免确认」的口径 ─────────────────────────────────────────

    @Test
    fun `权限是FREE_写类免确认`() {
        val spec = BaopuToolCatalog.ENTRIES.first { it.name == PlanTool.NAME }.factory { File(".") }
        assertEquals(
            "它只改自己的计划态、不碰用户文件 ⇒ 免确认（派工单 §1.1）",
            ToolPermission.FREE,
            spec.permission,
        )
    }

    @Test
    fun `管线直行不弹确认`() {
        // FREE ⇒ pipeline.execute 直接执行，不会退 NeedsConfirm
        val store = RecordingStore()
        PlanTool.sink = PlanController(sessionId = { 1L }, store = store)
        val reg = catalogRegistry()
        val pipeline = GuardPipeline(reg, { File(".") })
        val call = ToolCall("c1", PlanTool.NAME, planArgs("读文件" to "todo"))
        val result = pipeline.execute(call) { _, _ -> null }
        assertTrue("FREE 工具不该走确认：$result", result is GuardPipeline.Result.Done)
    }

    // ── schema 校验（顶层） ────────────────────────────────────────────

    @Test
    fun `缺items_schema层就拒`() {
        val reg = catalogRegistry()
        val err = reg.validateCall(PlanTool.NAME, JSONObject())
        assertNotNull("缺必填参数必须拒", err)
        assertTrue(err!!.contains("items"))
    }

    @Test
    fun `未知参数_schema层拒`() {
        val reg = catalogRegistry()
        val err = reg.validateCall(
            PlanTool.NAME,
            JSONObject().put("items", JSONArray()).put("itemz", 1),
        )
        assertNotNull("模型幻觉出的参数名不许直通", err)
        assertTrue(err!!.contains("itemz"))
    }

    @Test
    fun `items类型不对_schema层拒`() {
        val reg = catalogRegistry()
        val err = reg.validateCall(PlanTool.NAME, JSONObject().put("items", "读文件"))
        assertNotNull(err)
        assertTrue(err!!.contains("items"))
    }

    @Test
    fun `条目层的坏数据_schema放过执行器拦`() {
        // SchemaCheck 不递归数组元素 ⇒ 空数组过了 schema，由执行器拒（J1 的另一半）
        val reg = catalogRegistry()
        val err = reg.validateCall(PlanTool.NAME, JSONObject().put("items", JSONArray()))
        assertEquals("schema 层放过（数组语法合法）", null, err)
    }

    // ── 描述（进系统提示，≤500 字符） ─────────────────────────────────

    @Test
    fun `描述不超上限`() {
        assertTrue(
            "描述 ${PlanTool.DESCRIPTION.length} 字超过 ${ToolSpec.MAX_DESC}",
            PlanTool.DESCRIPTION.length <= ToolSpec.MAX_DESC,
        )
    }

    @Test
    fun `描述写清了三条纪律`() {
        assertTrue(PlanTool.DESCRIPTION.contains("全量覆盖"))
        assertTrue(PlanTool.DESCRIPTION.contains("8"))
        assertTrue(PlanTool.DESCRIPTION.contains("60"))
        assertTrue(PlanTool.DESCRIPTION.contains("不要写进回复正文"))
    }

    // ── enum 同源（schema 与校验不许漂移） ────────────────────────────

    @Test
    fun `schema的status枚举与PlanRules同源`() {
        val spec = BaopuToolCatalog.ENTRIES.first { it.name == PlanTool.NAME }.factory { File(".") }
        val itemSchema = spec.inputSchema
            .getJSONObject("properties")
            .getJSONObject("items")
            .getJSONObject("items")
            .getJSONObject("properties")
            .getJSONObject("status")
        val enumVals = itemSchema.getJSONArray("enum")
        val got = (0 until enumVals.length()).map { enumVals.getString(it) }
        assertEquals(PlanRules.STATUSES, got)
    }

    // ── 没接线 ⇒ 明确拒绝（不静默丢弃） ──────────────────────────────

    @Test
    fun `sink没接线_拒绝并说明`() {
        PlanTool.sink = null
        val reg = catalogRegistry()
        val pipeline = GuardPipeline(reg, { File(".") })
        val call = ToolCall("c1", PlanTool.NAME, planArgs("读文件" to "todo"))
        val result = pipeline.execute(call) { _, _ -> null }
        assertTrue("没接线必须 Rejected（终止本轮并回灌理由）", result is GuardPipeline.Result.Rejected)
    }

    // ── 集成：真管线 + 真内核 + 假 store ──────────────────────────────

    @Test
    fun `管线集成_一条龙_截断落库回灌都走真代码`() {
        val store = RecordingStore()
        PlanTool.sink = PlanController(sessionId = { 99L }, store = store)
        val reg = catalogRegistry()
        val pipeline = GuardPipeline(reg, { File(".") })
        val thirteen = (1..13).map { "条目$it" to "todo" }.toTypedArray()
        val result = pipeline.execute(ToolCall("c1", PlanTool.NAME, planArgs(*thirteen))) { _, _ -> null }
        assertTrue(result is GuardPipeline.Result.Done)
        val wrapped = (result as GuardPipeline.Result.Done).wrapped
        assertTrue("回灌带围栏（唯一出口）", wrapped.startsWith(DataFence.FENCE_OPEN))
        assertTrue("回灌带进度（分母是 12 条上限）", wrapped.contains("0/12"))
        assertEquals("落库一条，截断到 12", 1, store.inserts.size)
        assertEquals(99L, store.inserts.single().first)
        val payload = store.inserts.single().second
        val parsed = PlanRules.fromJson(payload)
        assertEquals("截断到 12 条", 12, parsed.size)
        assertEquals("读回逐字一致", payload, PlanRules.toJson(parsed))
    }

    @Test
    fun `协议A解码_同样过注册表校验`() {
        val reg = catalogRegistry()
        val toolCalls = JSONArray().put(
            JSONObject()
                .put("id", "call_1")
                .put(
                    "function",
                    JSONObject().put("name", PlanTool.NAME).put("arguments", "{}"),
                ),
        )
        val decoded = ToolCallCodec.decodeOpenAi(toolCalls, reg)
        assertTrue("缺 items 的解码必须 Bad（错误原文回灌）", decoded is ToolCallCodec.DecodeResult.Bad)
    }

    // ── 测试辅具 ──────────────────────────────────────────────────────

    private class RecordingStore : PlanStore {
        val inserts = mutableListOf<Pair<Long, String>>()
        override fun insert(sessionId: Long, payload: String) {
            inserts += sessionId to payload
        }

        override fun latest(sessionId: Long): String? = inserts.lastOrNull()?.second
    }

    private fun planArgs(vararg items: Pair<String, String>): JSONObject =
        JSONObject().put(
            "items",
            JSONArray().also { arr ->
                items.forEach { (text, status) ->
                    arr.put(JSONObject().put("text", text).put("status", status))
                }
            },
        )
}
