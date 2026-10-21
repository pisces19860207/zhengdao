// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§2「测试」与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》§9。本文件钉工具层：契约（schema/
// 权限/只读/超时）、sink 房型（没接线明确拒绝）、默认可见面不扩张。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.SchemaCheck
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolSpec
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubTaskToolTest {

    private var savedSink: SubTaskSink? = null

    @Before
    fun saveSink() {
        savedSink = SubTaskTool.sink
    }

    @After
    fun restoreSink() {
        SubTaskTool.sink = savedSink
    }

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { j ->
        pairs.forEach { (k, v) -> j.put(k, v) }
    }

    private fun sub(goal: String) = JSONObject().put("goal", goal)

    private fun arr(vararg items: Any?): org.json.JSONArray =
        org.json.JSONArray().also { a -> items.forEach { a.put(it) } }

    // ── 契约 ─────────────────────────────────────────────────────────────

    @Test
    fun `工具表里有run_subtasks_默认启用_归AGENT组`() {
        val names = BaopuToolCatalog.ENTRIES.map { it.name }
        assertTrue("一张表里必须有 run_subtasks", names.contains(SubTaskTool.NAME))
        assertTrue("默认启用（看得见与否是组的事）", BaopuToolCatalog.DEFAULT_ENABLED.contains(SubTaskTool.NAME))
    }

    @Test
    fun `spec契约_schema权限只读超时都对`() {
        val spec = SubTaskTool.spec()
        assertEquals(SubTaskTool.NAME, spec.name)
        assertTrue("描述不能空", spec.description.isNotBlank())
        assertTrue("描述不超上限", spec.description.length <= ToolSpec.MAX_DESC)
        val schema = spec.inputSchema
        assertEquals("object", schema.optString("type"))
        assertFalse("顶层不许放行未知参数", schema.optBoolean("additionalProperties", true))
        val required = schema.optJSONArray("required")
        assertNotNull(required)
        assertTrue("required 必须含 subtasks", (0 until required!!.length()).map { required.optString(it) }.contains("subtasks"))
        val items = schema.getJSONObject("properties").getJSONObject("subtasks").getJSONObject("items")
        assertEquals("object", items.optString("type"))
        assertFalse("条目也不许放行未知参数", items.optBoolean("additionalProperties", true))
        assertTrue(
            "条目的 required 必须含 goal",
            (0 until items.getJSONArray("required").length())
                .map { items.getJSONArray("required").optString(it) }.contains("goal"),
        )
        // 免确认 + 只读盘 + 不并行 + 兜底超时
        assertEquals(ToolPermission.FREE, spec.permission)
        assertTrue("只读盘（子任务是只读研究工）", spec.readOnly)
        assertFalse("不许并行（整段分解跑完才返回的元调用）", spec.parallelSafe)
        assertEquals(SubAgentRules.TOOL_TIMEOUT_MS, spec.timeoutMs)
    }

    @Test
    fun `AGENT组默认不可见_默认面不扩张`() {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { File(".") }
        val visibleDefault = reg.toolsJsonOpenAi().let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertFalse("默认 tools 数组里不许有 run_subtasks（渐进披露）", visibleDefault.contains(SubTaskTool.NAME))
        // 解锁 AGENT 组后就出现（同一处判据，不是第二套表）
        reg.currentSessionId = 7L
        reg.unlockStore().unlock(7L, listOf(ToolGroup.AGENT))
        val visibleAfter = reg.toolsJsonOpenAi(7L).let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
        }
        assertTrue("解锁后要出现", visibleAfter.contains(SubTaskTool.NAME))
    }

    // ── sink 房型 ────────────────────────────────────────────────────────

    @Test
    fun `没接线_明确拒绝_不静默丢弃`() {
        SubTaskTool.sink = null
        val out = SubTaskTool.spec().executor(args("subtasks" to arr(sub("读文件"))))
        assertTrue("没接线要拒绝：$out", out is com.example.zhengdao.baopu.agent.ToolOutcome.Rejected)
        assertTrue(
            "理由要教模型怎么办：${(out as com.example.zhengdao.baopu.agent.ToolOutcome.Rejected).reason}",
            out.reason.contains("没接线") && out.reason.contains("自己完成"),
        )
    }

    @Test
    fun `接了线_原样转交_args一字不改`() {
        var seen: JSONObject? = null
        SubTaskTool.sink = SubTaskSink { a -> seen = a; com.example.zhengdao.baopu.agent.ToolOutcome.Ok("汇总文本") }
        val inArgs = args("subtasks" to arr(sub("读文件")))
        val out = SubTaskTool.spec().executor(inArgs)
        assertTrue("接线后要执行：$out", out is com.example.zhengdao.baopu.agent.ToolOutcome.Ok)
        assertEquals("汇总文本", (out as com.example.zhengdao.baopu.agent.ToolOutcome.Ok).text)
        assertEquals("args 要原样到 sink", inArgs.toString(), seen?.toString())
    }

    // ── schema 校验（与发给模型的同一份） ────────────────────────────────

    @Test
    fun `非法参数_校验层就拦下_原文点名`() {
        val spec = SubTaskTool.spec()
        // 缺 subtasks
        val e1 = SchemaCheck.validate(spec.inputSchema, args())
        assertNotNull("缺 subtasks 要报错", e1)
        assertTrue("点名缺的参数：$e1", e1!!.contains("subtasks"))
        // subtasks 类型错（给了字符串）
        val e2 = SchemaCheck.validate(spec.inputSchema, args("subtasks" to "不是数组"))
        assertNotNull("类型错要报错", e2)
        assertTrue("点名类型：$e2", e2!!.contains("subtasks"))
        // 未知参数
        val e4 = SchemaCheck.validate(spec.inputSchema, args("subtasks" to arr(sub("g")), "mode" to "fast"))
        assertNotNull("未知参数要报错", e4)
        assertTrue("点名 mode：$e4", e4!!.contains("mode"))
        // 合法参数放行
        assertTrue(
            "合法参数不许误报：${SchemaCheck.validate(spec.inputSchema, args("subtasks" to arr(sub("g"))))}",
            SchemaCheck.validate(spec.inputSchema, args("subtasks" to arr(sub("g")))) == null,
        )
    }

    @Test
    fun `条目级约束_schema声明_规则层执行_分层不重叠`() {
        val spec = SubTaskTool.spec()
        val items = spec.inputSchema.getJSONObject("properties").getJSONObject("subtasks").getJSONObject("items")
        // schema 层声明：条目必填 goal、timeout 10-300（给模型看的契约）
        assertTrue(
            "条目 schema 必须声明 goal 必填",
            (0 until items.getJSONArray("required").length())
                .map { items.getJSONArray("required").optString(it) }.contains("goal"),
        )
        assertEquals(10, items.getJSONObject("properties").getJSONObject("timeout_seconds").optInt("minimum"))
        assertEquals(300, items.getJSONObject("properties").getJSONObject("timeout_seconds").optInt("maximum"))
        // 但 SchemaCheck 不递归数组条目（手写最小校验器的既定边界，与 update_plan 同房型）
        // ⇒ 条目内的坏参数由 SubAgentRules.parseDecomposition 拦（那层的用例在
        // SubAgentRulesTest：缺 goal/超长/越权工具/超时钳制各拒）——两层分工，不重复拦。
        val badEntry = args("subtasks" to arr(JSONObject()))
        assertTrue(
            "SchemaCheck 放过条目级错误是既定分层（规则层兜）",
            SchemaCheck.validate(spec.inputSchema, badEntry) == null,
        )
        val r = SubAgentRules.parseDecomposition(badEntry, SubAgentRules.DEFAULT_SUBAGENT_TOOLS.toSet(), 1L, 0L, 1)
        assertTrue("规则层必须拦住缺 goal 的条目：$r", r is SubAgentRules.ParseResult.Rejected)
    }
}
