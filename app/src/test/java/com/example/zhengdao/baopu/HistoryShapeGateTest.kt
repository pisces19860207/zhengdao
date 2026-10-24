// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.1：历史形状门禁（2026-10-24）。
//
// 判据：历史与请求体里不许出现 provider 原生工具块（Anthropic tool_use/tool_result、
// Gemini functionCall、OpenAI tool_calls/role=tool），出现即红。
//
// 为什么值得单测：我们的历史是纯文本，「孤儿 tool_use 撞 Anthropic 400」这个坑
// **结构上不存在**（派工单 19:5x 更正）；但一旦将来有人为了「保真」加一条原生块，
// 换家当场炸、且只在真机上炸。这条门禁把「我们永远不落原生块」钉在 JVM 里。
//
// 两层：
//   ① 纯函数（HistoryShapeGate）：绿例（三家真实请求体/真实在途历史）＋ 红例
//      （手搓的原生块——生产代码产不出来，正是要防的东西）；
//   ② 源码门禁：RunEngine 写历史的路径上不许出现原生块字面（解码侧
//      ToolCallCodec 里的不算——那是读响应，不是写历史）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.DataFence
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryShapeGateTest {

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /** 一个工具、一个必填参数（OpenAI 形状；Gemini 在 encode 里转 functionDeclarations）。 */
    private fun oneTool(): org.json.JSONArray = org.json.JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "read_file")
                    .put("description", "读文件")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
                            .put("required", org.json.JSONArray().put("path")),
                    ),
            ),
    )

    private val messages = listOf(
        LlmMessage(LlmMessage.ROLE_SYSTEM, "你是抱朴"),
        LlmMessage(LlmMessage.ROLE_USER, "读一下 a.txt"),
    )

    // ── ① 绿例：我们自己的形状必须干净 ──────────────────────────────────

    @Test
    fun `绿_三家真实请求体过门禁_纯文本消息不误伤`() {
        val tools = oneTool()
        for ((label, p) in listOf(
            "OpenAI" to Providers.OPENAI_COMPAT.copy(apiKey = "k"),
            "Anthropic" to Providers.ANTHROPIC.copy(apiKey = "k"),
            "Gemini" to Providers.GEMINI.copy(apiKey = "k"),
        )) {
            val body = LlmWire.bodyOf(p, messages, stream = true, tools = tools)
            val v = HistoryShapeGate.bodyViolations(p.protocol, body)
            assertEquals("$label 的请求体必须干净（顶层 tools 声明是合法的，不扫）: $v", emptyList<String>(), v)
        }
    }

    @Test
    fun `绿_真实在途历史过门禁_工具结果围栏文本不是原生块`() {
        val history = listOf(
            LlmMessage(LlmMessage.ROLE_USER, "把第一行改成 X"),
            LlmMessage(LlmMessage.ROLE_ASSISTANT, "好，我来改"),
            // 在线路径的 Done 行：围栏文本（我们的工具结果就长这样）
            LlmMessage(LlmMessage.ROLE_ASSISTANT, DataFence.wrap("write_file", 1L, "已覆盖 x.txt（2 行）")),
            // 坏参数回灌行
            LlmMessage(LlmMessage.ROLE_ASSISTANT, "工具调用参数不符合 schema：缺少必填参数「path」；请修正参数后重试"),
            // 正文里偶然出现这些词也不算（判据认结构，不认字面）
            LlmMessage(LlmMessage.ROLE_USER, "帮我看看 tool_use 和 functionCall 是什么意思"),
        )
        assertEquals(emptyList<String>(), HistoryShapeGate.historyViolations(history))
    }

    @Test
    fun `绿_半截JSON的历史文本不抛也不误判`() {
        // 模型把参数写崩了的回灌文本（以 { 开头但不是合法 JSON）⇒ 不抛、不算原生块
        val history = listOf(LlmMessage(LlmMessage.ROLE_ASSISTANT, """工具「x」的参数不是合法 JSON：{"cmd":"""))
        assertEquals(emptyList<String>(), HistoryShapeGate.historyViolations(history))
    }

    // ── ① 红例：手搓原生块（生产产不出来，正是要防的东西）──────────────

    @Test
    fun `红_OpenAI请求体里出现原生tool_calls即红`() {
        val bad = """
            {"model":"m","stream":true,"max_tokens":8192,"messages":[
              {"role":"user","content":"读 a.txt"},
              {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}]}]}
        """.trimIndent()
        val v = HistoryShapeGate.bodyViolations(LlmProtocol.OPENAI_COMPAT, bad)
        assertTrue("原生 tool_calls 必须被门禁抓到: $v", v.isNotEmpty())
        assertTrue("错误要能读出是哪一层: $v", v.any { it.contains("tool_calls") })
    }

    @Test
    fun `红_OpenAI请求体里出现role_tool消息即红`() {
        val bad = """
            {"model":"m","stream":true,"max_tokens":8192,"messages":[
              {"role":"tool","tool_call_id":"call_1","content":"文件内容"}]}
        """.trimIndent()
        val v = HistoryShapeGate.bodyViolations(LlmProtocol.OPENAI_COMPAT, bad)
        assertTrue("role=tool 必须被门禁抓到: $v", v.isNotEmpty())
    }

    @Test
    fun `红_Anthropic请求体里出现原生tool_use与tool_result块即红`() {
        val bad = """
            {"model":"m","stream":true,"max_tokens":4096,"messages":[
              {"role":"user","content":[{"type":"text","text":"读 a.txt"}]},
              {"role":"assistant","content":[
                {"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.txt"}}]},
              {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"toolu_1","content":"文件内容"}]}]}
        """.trimIndent()
        val v = HistoryShapeGate.bodyViolations(LlmProtocol.ANTHROPIC, bad)
        assertTrue("原生 tool_use/tool_result 必须被门禁抓到: $v", v.isNotEmpty())
        assertTrue("两类块都要点到: $v", v.any { it.contains("tool_use") } && v.any { it.contains("tool_result") })
    }

    @Test
    fun `红_Gemini请求体里出现原生functionCall部件即红`() {
        val bad = """
            {"contents":[
              {"role":"user","parts":[{"text":"读 a.txt"}]},
              {"role":"model","parts":[{"functionCall":{"name":"read_file","args":{"path":"a.txt"}}}]}],
             "generationConfig":{"maxOutputTokens":8192}}
        """.trimIndent()
        val v = HistoryShapeGate.bodyViolations(LlmProtocol.GEMINI_NATIVE, bad)
        assertTrue("原生 functionCall 部件必须被门禁抓到: $v", v.isNotEmpty())
        assertTrue("错误要能读出是 Gemini 层: $v", v.any { it.contains("Gemini") })
    }

    @Test
    fun `红_在途历史里混进原生块即红`() {
        val history = listOf(
            LlmMessage(LlmMessage.ROLE_USER, "读 a.txt"),
            // 将来某人为了「保真」把模型上一轮的工具调用原样落进历史——就长这样
            LlmMessage(
                LlmMessage.ROLE_ASSISTANT,
                """{"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.txt"}}""",
            ),
        )
        val v = HistoryShapeGate.historyViolations(history)
        assertTrue("历史里的原生 tool_use 必须被门禁抓到: $v", v.isNotEmpty())
    }

    @Test
    fun `红_请求体不是合法JSON也算一条`() {
        val v = HistoryShapeGate.bodyViolations(LlmProtocol.OPENAI_COMPAT, "")
        assertEquals(1, v.size)
    }

    // ── 负例：门禁必须是「判别式」的，不是恒真 ───────────────────────────

    @Test
    fun `负例_门禁若被关成恒真_同一具请求体加一条原生块就发现不了`() {
        // 「把 1.1 的门禁关掉」的负例演练：干净体与差一条原生块的体，门禁必须给出
        // **不同**判决。若检测被清空/恒真（永远返回空表），本用例第二条断言即红——
        // 红例用例也全塌，这就是「关掉门禁 ⇒ 红」的机器证明。
        val clean = LlmWire.bodyOf(
            Providers.ANTHROPIC.copy(apiKey = "k"),
            messages,
            stream = true,
            tools = oneTool(),
        )
        assertTrue("干净体必须过", HistoryShapeGate.bodyViolations(LlmProtocol.ANTHROPIC, clean).isEmpty())

        // 同一具请求体，往 messages 里插一条 Anthropic 原生 tool_use 块
        val dirty = clean.replace(
            "\"messages\":[",
            """"messages":[{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"read_file","input":{}}]},""",
        )
        val v = HistoryShapeGate.bodyViolations(LlmProtocol.ANTHROPIC, dirty)
        assertTrue("差一条原生块的体必须被抓住（恒真门禁在这里露馅）: $v", v.isNotEmpty())
    }

    // ── ② 源码门禁：引擎写历史的路径上没有原生块字面 ────────────────────

    @Test
    fun `源码门禁_RunEngine写历史路径不出现原生块字面`() {
        val code = engineCode()
        for (literal in listOf("\"tool_use\"", "\"tool_result\"", "\"functionCall\"")) {
            assertFalse(
                "RunEngine 里出现了原生块字面 $literal——" +
                    "原生块只许出现在解码侧（ToolCallCodec 读响应），不许出现在写历史的路径上",
                code.contains(literal),
            )
        }
        assertFalse("引擎不许把消息角色写成原生 tool 角色", code.contains("ROLE_TOOL"))
        // 我们自己的 DB part kind "tool" 是内部账目，不是原生块——允许存在
        assertTrue("工具结果落库的 part kind 仍在（内部账目，不是原生块）", code.contains("\"tool\""))
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    /** 去掉注释行再判（注释里提到原生块不算违背——KDoc 里本来就有的形状说明）。 */
    private fun engineCode(): String = File(enginePath).readText().lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")
}
