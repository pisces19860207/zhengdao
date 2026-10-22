// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百四十九批 组1（`ToolCallCodec.decodeOpenAi` / `decodeAnthropic`
// 接进**生产解码入口**，按 provider 协议选；单一真相，不另写解析；失败可读、不静默）。
//
// 这个文件钉四件事：
// 1. 两类协议的**整包（非流式）响应**都能从 `decodeNonStream` 解出来（正例）；
// 2. **协议走错** ⇒ 必红，而且报的是「这一族的形状里没有 X」，不是含糊的「模型没给调用」；
// 3. 与数组入口 `decodeOpenAi`/`decodeAnthropic` **同一个判据**（单一真相，不是第二套解析）；
// 4. 生产调用点（`EvalHarness.call` / `RuntimeEval.configOf`）真的从这里进，
//    原来那份手写 tool_calls 循环已经没了 —— 这条在接线前必红，接线后转绿。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.LlmProtocol
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallCodecProtocolTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = appFile(rel).readText()

    private val evalHarness: String
        get() = read("src/main/java/com/example/zhengdao/baopu/eval/EvalHarness.kt")

    private val runtimeEval: String
        get() = read("src/main/java/com/example/zhengdao/baopu/eval/RuntimeEval.kt")

    /** 只解码不校验时用的注册表（要验「校验同表」的那几条用它）。 */
    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(
            ToolSpec(
                name = "read_file",
                description = "读文件",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                    "required" to jsonArrayOf("path"),
                    "additionalProperties" to false,
                ),
            ) { ToolOutcome.Ok("ok") },
        )
    }

    // ── 响应样本（两种协议的形状各自照公开文档写死在这里） ──────────────────

    private fun openAiRoot(toolCalls: JSONArray?): JSONObject =
        JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "message",
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "我看看")
                                .apply { if (toolCalls != null) put("tool_calls", toolCalls) },
                        ),
                ),
            )

    private fun openAiCall(id: String, name: String, arguments: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))

    private fun anthropicRoot(vararg blocks: JSONObject): JSONObject =
        JSONObject().put("content", JSONArray().apply { blocks.forEach { put(it) } })

    // ── 1. 两类协议正例 ─────────────────────────────────────────────────

    @Test
    fun `OpenAI兼容整包_按协议解出工具调用`() {
        val root = openAiRoot(JSONArray().put(openAiCall("c1", "read_file", """{"path":"a.md"}""")))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root, registry())
        assertTrue("OpenAI 兼容整包该解出调用，实际：$r", r is ToolCallCodec.DecodeResult.Ok)
        val calls = (r as ToolCallCodec.DecodeResult.Ok).calls
        assertEquals(1, calls.size)
        assertEquals("c1", calls[0].id)
        assertEquals("read_file", calls[0].name)
        assertEquals("a.md", calls[0].arguments.getString("path"))
    }

    @Test
    fun `Anthropic整包_只挑tool_use块`() {
        val root = anthropicRoot(
            JSONObject().put("type", "text").put("text", "我看看"),
            JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_1")
                .put("name", "read_file")
                .put("input", JSONObject().put("path", "a.md")),
        )
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.ANTHROPIC, root, registry())
        assertTrue("Anthropic 整包该解出 tool_use，实际：$r", r is ToolCallCodec.DecodeResult.Ok)
        val calls = (r as ToolCallCodec.DecodeResult.Ok).calls
        assertEquals(1, calls.size)
        assertEquals("toolu_1", calls[0].id)
        assertEquals("a.md", calls[0].arguments.getString("path"))
    }

    @Test
    fun `Anthropic整包_文本块不算调用`() {
        val root = anthropicRoot(JSONObject().put("type", "text").put("text", "纯文本"))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.ANTHROPIC, root, registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        val bad = r as ToolCallCodec.DecodeResult.Bad
        assertTrue(
            "报的必须是「没有 tool_use 块」，实际：${bad.error}",
            bad.error.contains("没有 tool_use 块"),
        )
    }

    @Test
    fun `Anthropic缺id_按toolu序号补`() {
        val root = anthropicRoot(
            JSONObject().put("type", "tool_use").put("name", "read_file")
                .put("input", JSONObject().put("path", "a.md")),
        )
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.ANTHROPIC, root, registry())
        assertEquals("toolu_0", (r as ToolCallCodec.DecodeResult.Ok).calls[0].id)
    }

    // ── 2. 协议走错 ⇒ 红（负例） ────────────────────────────────────────

    @Test
    fun `OpenAI响应当Anthropic解_红且报协议形状不对`() {
        val root = openAiRoot(JSONArray().put(openAiCall("c1", "read_file", """{"path":"a.md"}""")))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.ANTHROPIC, root, registry())
        assertTrue("拿 A 家的形状解 B 家的响应必须红，不能「解出 0 个调用」就当纯文本放过",
            r is ToolCallCodec.DecodeResult.Bad)
        val err = (r as ToolCallCodec.DecodeResult.Bad).error
        assertTrue("错误原文要点出缺的是 content 块数组，实际：$err", err.contains("content 块数组"))
        assertTrue(err.contains("协议走错"))
    }

    @Test
    fun `Anthropic响应当OpenAI兼容解_红且报choices不对`() {
        val root = anthropicRoot(
            JSONObject().put("type", "tool_use").put("id", "toolu_1").put("name", "read_file")
                .put("input", JSONObject().put("path", "a.md")),
        )
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root, registry())
        assertTrue("反过来也一样：Anthropic 响应里没有 choices[0] ⇒ 必红",
            r is ToolCallCodec.DecodeResult.Bad)
        val err = (r as ToolCallCodec.DecodeResult.Bad).error
        assertTrue("错误原文要点出缺的是 choices[0]，实际：$err", err.contains("choices[0]"))
    }

    @Test
    fun `OpenAI响应缺message_红且不落到空数组那句`() {
        val root = JSONObject().put("choices", JSONArray().put(JSONObject()))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root, registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        val err = (r as ToolCallCodec.DecodeResult.Bad).error
        assertTrue("缺 message 要说缺 message，不许退化成「模型返回了空的 tool_calls 数组」",
            err.contains("message"))
        assertFalse(err.contains("空的 tool_calls"))
    }

    @Test
    fun `tool_calls条目缺function_红不静默跳过`() {
        // 接线前 EvalHarness 那份额外解析在这里是 `?: continue`——条目被丢掉、整轮当纯文本结束，
        // 既不报错也不执行。接上生产解码入口后必须红（派工单：失败可读、不静默）。
        val root = openAiRoot(JSONArray().put(JSONObject().put("id", "c1")))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root, registry())
        assertTrue("缺 function 的条目不许静默丢，实际：$r", r is ToolCallCodec.DecodeResult.Bad)
        assertTrue(
            (r as ToolCallCodec.DecodeResult.Bad).error.contains("function"),
        )
    }

    @Test
    fun `空tool_calls数组_红`() {
        val root = openAiRoot(JSONArray())
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root, registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
    }

    // ── 3. 单一真相：与数组入口同判 ─────────────────────────────────────

    @Test
    fun `整包入口与数组入口同判_不另写一套解析`() {
        val calls = JSONArray()
            .put(openAiCall("c1", "read_file", "{\"path\": ")) // 坏 JSON
            .put(openAiCall("c2", "read_file", """{"path":"b.md"}"""))
        val viaArray = ToolCallCodec.decodeOpenAi(calls, registry())
        val viaWhole = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, openAiRoot(calls), registry())
        assertTrue(viaArray is ToolCallCodec.DecodeResult.Bad)
        assertEquals(
            "整包入口必须原样复用数组入口的判据（错误原文逐字相同）",
            (viaArray as ToolCallCodec.DecodeResult.Bad).error,
            (viaWhole as ToolCallCodec.DecodeResult.Bad).error,
        )
    }

    @Test
    fun `整包入口同样过注册表校验_未知工具报Schema错`() {
        val root = openAiRoot(JSONArray().put(openAiCall("c1", "delete_everything", """{"path":"a"}""")))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root, registry())
        assertTrue(r is ToolCallCodec.DecodeResult.Bad)
        val bad = r as ToolCallCodec.DecodeResult.Bad
        assertTrue(bad.error.contains("未知工具"))
        assertEquals("回灌文案按 Schema 那套走", ToolCallCodec.BadArgsKind.SCHEMA, bad.kind)
        assertEquals("delete_everything", bad.toolName)
    }

    @Test
    fun `不传注册表时只解码不校验`() {
        val root = openAiRoot(JSONArray().put(openAiCall("c1", "whatever", "{}")))
        val r = ToolCallCodec.decodeNonStream(LlmProtocol.OPENAI_COMPAT, root)
        assertTrue(r is ToolCallCodec.DecodeResult.Ok)
    }

    // ── 4. 生产调用点接线守卫（接线前红 / 接线后绿） ──────────────────────

    @Test
    fun `EvalHarness的非流式回合走生产解码入口`() {
        assertTrue(
            "EvalHarness.call 必须从 ToolCallCodec.decodeNonStream 进（单一真相）",
            evalHarness.contains("ToolCallCodec.decodeNonStream("),
        )
        assertTrue(
            "解码器按 cfg.protocol 选，不许在调用点写死协议",
            evalHarness.contains("decodeNonStream(cfg.protocol"),
        )
    }

    @Test
    fun `EvalHarness不再自己手写tool_calls循环`() {
        assertFalse(
            "原来那份 `for (i in 0 until rawCalls.length())` 的内联解析必须消失，" +
                "否则判据又变成两套（缺 function 静默 continue 那条就还在）",
            evalHarness.contains("for (i in 0 until rawCalls.length())"),
        )
        assertFalse(
            "内联那份还顺手用了 ToolCallCodec.parseArgs——整包解码不该在外面碰它",
            evalHarness.contains("ToolCallCodec.parseArgs("),
        )
        // 但「空 tool_calls ⇒ 纯文本回合」这条合法行为一个字都不许变
        assertTrue(
            "空 tool_calls 仍要走纯文本回合（不报错），这是合法行为，不许被接线顺手动掉",
            Regex("""rawCalls\.length\(\)\s*==\s*0\s*\)\s*return\s+Round\(\s*content\s*,\s*emptyList\(\)""")
                .containsMatchIn(evalHarness),
        )
    }

    @Test
    fun `协议由provider带入自检配置`() {
        assertTrue(
            "RuntimeEval.configOf 必须把 provider.protocol 传进 EvalConfig（按 provider 协议选）",
            Regex("""protocol\s*=\s*provider\.protocol""").containsMatchIn(runtimeEval),
        )
    }
}
