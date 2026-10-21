// 第一百一十五批 · 工具自修 v1 的**安全门禁**（派工单 §3 负例）。
//
// 这一层不测「建议准不准」（那是 ToolFailureAdvisorTest），只钉三条红线：
//   N1 建议层不接注册表/执行器/Android ⇒ 物理上不可能自动调用工具；
//   N2 建议参数只是副本 ⇒ 不可能改 schema 或调用参数；
//   N3 `L3_AUTO_LOW_RISK` 仍是 false ⇒ 本批没有打开任何自动口；
//   N4 引擎三支都接线，但从不使用 `suggestedArgs` —— 建议绝不执行；
//   N5 解码层把 Schema 错的工具名/参数交给归因层（否则生产路径拿不到证据）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.CodeText
import com.example.zhengdao.baopu.EvolutionPolicy
import com.example.zhengdao.baopu.RawToolCall
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolRepairSafetyGateTest {

    private val advisorPath = "src/main/java/com/example/zhengdao/baopu/agent/ToolFailureAdvisor.kt"
    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    /** 去注释、去字符串（查代码形状；注释里提到 ToolRegistry 不算违规）。 */
    private fun code(rel: String): String {
        val f = File(rel).let { if (it.isFile) it else File("app/$it") }
        assertTrue("找不到源文件：$rel", f.isFile)
        return CodeText.codeOnly(f.readText().replace("\r\n", "\n"))
    }

    // ── N1：建议层没有执行能力 ──────────────────────────────────────────

    @Test
    fun `N1_建议层不接注册表_不调执行器_不碰Android`() {
        val src = code(advisorPath)
        val banned = listOf(
            "ToolRegistry", "ToolSpec", "ToolRunner", "GuardPipeline", "executor",
            "execute(", "register(", "write_skill", "append_persona",
            "Thread(", "android.", "System.currentTimeMillis", "File(",
            "Dispatchers", "runBlocking", "BaopuSql", "RunLog",
        )
        banned.forEach { b ->
            assertFalse("建议层出现 $b ⇒ 它可能越界执行/改工具/写盘", src.contains(b))
        }
        val imports = Regex("(?m)^import (.+)$").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(
            "建议层只许 org.json（连 import 都限制住，避免将来外挂执行器）",
            listOf("org.json.JSONArray", "org.json.JSONObject"),
            imports,
        )
    }

    @Test
    fun `N1b_建议路径不会调用执行器`() {
        var calls = 0
        val spec = ToolSpec(
            name = "write_file",
            description = "test",
            inputSchema = JSONObject().put("type", "object").put("required", JSONArray().put("path")),
            executor = {
                calls++
                ToolOutcome.Ok("executed")
            },
        )
        val advice = ToolFailureAdvisor.advise(
            ToolFailureEvidence(toolName = spec.name, errorText = "缺少必填参数「path」"),
        )
        assertEquals("建议层不许触发工具执行器", 0, calls)
        assertNotNull("只生成建议参数", advice.suggestedArgs)
        assertEquals("生成建议后执行器仍未被调用", 0, calls)
    }

    // ── N2：不改调用参数 / schema ───────────────────────────────────────

    @Test
    fun `N2_建议层不改schema_也不改调用参数`() {
        val schema = JSONObject()
            .put("type", "object")
            .put("required", JSONArray().put("path"))
            .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
        val schemaBefore = schema.toString()
        val args = JSONObject().put("content", "SECRET-CONTENT")
        val argsBefore = args.toString()

        val advice = ToolFailureAdvisor.advise(
            ToolFailureEvidence(toolName = "write_file", errorText = "缺少必填参数「path」", arguments = args),
        )

        assertEquals("schema 一个字节都不许动", schemaBefore, schema.toString())
        assertEquals("调用参数一个字节都不许动", argsBefore, args.toString())
        assertTrue("建议参数必须是新对象", advice.suggestedArgs !== args)
        assertFalse("建议层没有 schema 入口（连字段名都不出现）", code(advisorPath).contains("inputSchema"))
    }

    // ── N3：L3 自动口仍然关着 ───────────────────────────────────────────

    @Test
    fun `N3_L3自动开关必须仍是关的`() {
        assertFalse("本批只做建议层，L3 自动口必须仍是 false", EvolutionPolicy.L3_AUTO_LOW_RISK)
        val src = code(advisorPath)
        assertFalse("建议层不许读/接 L3", Regex("L3|EvolutionPolicy|autoLowRisk").containsMatchIn(src))
    }

    // ── N4：引擎接线，但建议参数从不执行 ────────────────────────────────

    @Test
    fun `N4_引擎三支都接线_且从不使用建议参数`() {
        val src = code(enginePath)
        assertEquals(
            "BadArgs / Failed / Rejected 三支各生成一次建议",
            3,
            Regex("ToolFailureAdvisor\\.advise\\(").findAll(src).count(),
        )
        assertEquals(
            "三支各写一行审计",
            3,
            Regex("ToolFailureAdvisor\\.auditLine\\(").findAll(src).count(),
        )
        assertFalse(
            "引擎里出现 suggestedArgs = 有人把建议参数拿去执行了",
            src.contains("suggestedArgs"),
        )
    }

    // ── N5：解码层把 Schema 错的证据带出来 ──────────────────────────────

    @Test
    fun `N5_Schema错带上工具名与参数_语法错不带参数`() {
        val registry = ToolRegistry().apply {
            register(
                ToolSpec(
                    name = "read_file",
                    description = "test",
                    inputSchema = JSONObject()
                        .put("type", "object")
                        .put("required", JSONArray().put("path")),
                    executor = { ToolOutcome.Ok("read") },
                ),
            )
        }

        val schemaBad = ToolCallCodec.decode(
            listOf(RawToolCall(id = "c1", name = "read_file", argumentsJson = "{\"offset\":1}")),
            registry,
        )
        assertTrue(schemaBad is ToolCallCodec.DecodeResult.Bad)
        schemaBad as ToolCallCodec.DecodeResult.Bad
        assertEquals(ToolCallCodec.BadArgsKind.SCHEMA, schemaBad.kind)
        assertEquals("read_file", schemaBad.toolName)
        assertNotNull("Schema 错必须带原始参数，归因层才能给建议参数", schemaBad.arguments)
        assertEquals(1, schemaBad.arguments!!.optInt("offset"))

        val syntaxBad = ToolCallCodec.decode(
            listOf(RawToolCall(id = "c2", name = "read_file", argumentsJson = "{oops")),
            registry,
        )
        assertTrue(syntaxBad is ToolCallCodec.DecodeResult.Bad)
        syntaxBad as ToolCallCodec.DecodeResult.Bad
        assertEquals("read_file", syntaxBad.toolName)
        assertEquals("语法坏时没有可用参数，不许编", null, syntaxBad.arguments)
    }
}
