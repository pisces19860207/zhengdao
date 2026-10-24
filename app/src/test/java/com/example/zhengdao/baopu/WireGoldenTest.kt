// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.4：三家 encode golden（2026-10-24）。
//
// 这一份是 §1.3「encode 收成 OpenAiWire/GeminiWire/AnthropicWire」的**前后对账尺**：
// 期望值在搬家**之前**用一次性抓取器（GoldenDump，已删）从旧实现抓出冻结，
// 搬家后同一组输入必须产出**逐字节相同**的请求体。
//
// ⚠️ 一个必须知道的口径（不然会以为期望值写错了）：
// JVM 单测 classpath 上的 org.json 是**参考实现**（testImplementation libs.json），
// 它的 JSONObject 内部是 HashMap ⇒ toString 的键序是**哈希序，不是 put 插入序**
// （所以下面 "model" 排在最后）。真机（Android 的 org.json，LinkedHashMap）才是
// 插入序。因此：
// - 本文件的字节级断言在 JVM 上是确定性的（String.hashCode 由 Java 规范钉死），
//   能抓住一切**内容**变化（键增减、值变、嵌套变、类型变）；
// - 真正的 put 插入序（决定真机上的字节序）由下面的「put 序源码门禁」钉死，
//   加上搬家是字符级纯移动（git diff 可证），两边合起来才是「逐字节相同」的完整证据。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGoldenTest {

    // ── 夹具：显式造 provider（不依赖预设值，期望值才立得住）─────────────

    private fun p(
        protocol: LlmProtocol,
        maxOutput: Int? = null,
        effort: String? = null,
    ) = Provider(
        id = "golden",
        label = "golden",
        protocol = protocol,
        baseUrl = "https://golden.test/v1",
        model = "m",
        apiKey = "k",
        maxOutput = maxOutput,
        reasoningEffort = effort,
    )

    /** 一个工具、一个必填参数（OpenAI 形状；Anthropic 用平铺形状；Gemini 在 encode 里转）。 */
    private fun oneToolOpenAi(): org.json.JSONArray = org.json.JSONArray().put(
        org.json.JSONObject()
            .put("type", "function")
            .put(
                "function",
                org.json.JSONObject()
                    .put("name", "read_file")
                    .put("description", "读文件")
                    .put(
                        "parameters",
                        org.json.JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                org.json.JSONObject().put("path", org.json.JSONObject().put("type", "string")),
                            )
                            .put("required", org.json.JSONArray().put("path")),
                    ),
            ),
    )

    private fun oneToolAnthropic(): org.json.JSONArray = org.json.JSONArray().put(
        org.json.JSONObject()
            .put("name", "read_file")
            .put("description", "读文件")
            .put(
                "input_schema",
                org.json.JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        org.json.JSONObject().put("path", org.json.JSONObject().put("type", "string")),
                    )
                    .put("required", org.json.JSONArray().put("path")),
            ),
    )

    private val sysUser = listOf(
        LlmMessage(LlmMessage.ROLE_SYSTEM, "你是抱朴"),
        LlmMessage(LlmMessage.ROLE_USER, "你好"),
    )

    // ── OpenAI 兼容（put 序：model, stream, max_tokens, [stream_options],
    //    [reasoning_effort], messages, [tools]）────────────────────────

    @Test
    fun `golden_OpenAI_最小成功_一流式一必填参数_逐字节`() {
        assertEquals(
            """{"stream":true,"max_tokens":8192,"messages":[{"role":"system","content":"你是抱朴"},{"role":"user","content":"你好"}],"model":"m","stream_options":{"include_usage":true},"tools":[{"function":{"name":"read_file","description":"读文件","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}},"type":"function"}]}""",
            LlmWire.bodyOf(p(LlmProtocol.OPENAI_COMPAT), sysUser, stream = true, tools = oneToolOpenAi()),
        )
    }

    @Test
    fun `golden_OpenAI_非流式且无工具_不带stream_options与tools`() {
        val expected =
            """{"stream":false,"max_tokens":8192,"messages":[{"role":"user","content":"你好"}],"model":"m"}"""
        assertEquals(
            expected,
            LlmWire.bodyOf(p(LlmProtocol.OPENAI_COMPAT), listOf(LlmMessage("user", "你好")), stream = false),
        )
        // 空 tools 数组与 null 同口径：不带字段（向后兼容老调用点）
        assertEquals(
            expected,
            LlmWire.bodyOf(
                p(LlmProtocol.OPENAI_COMPAT),
                listOf(LlmMessage("user", "你好")),
                stream = false,
                tools = org.json.JSONArray(),
            ),
        )
    }

    @Test
    fun `golden_OpenAI_显式maxOutput与effort_脏值不发由J7J8管`() {
        assertEquals(
            """{"reasoning_effort":"high","stream":false,"max_tokens":4096,"messages":[{"role":"user","content":"hi"}],"model":"m"}""",
            LlmWire.bodyOf(
                p(LlmProtocol.OPENAI_COMPAT, maxOutput = 4096, effort = "high"),
                listOf(LlmMessage("user", "hi")),
                stream = false,
            ),
        )
    }

    @Test
    fun `golden_OpenAI_图片_content是数组且text段在前`() {
        assertEquals(
            """{"stream":false,"max_tokens":8192,"messages":[{"role":"user","content":[{"text":"看图","type":"text"},{"image_url":{"url":"data:image/png;base64,AAAA"},"type":"image_url"}]}],"model":"m"}""",
            LlmWire.bodyOf(
                p(LlmProtocol.OPENAI_COMPAT),
                listOf(LlmMessage("user", "看图", images = listOf("data:image/png;base64,AAAA"))),
                stream = false,
            ),
        )
    }

    // ── Anthropic（put 序：model, stream, max_tokens, [system], messages, [tools]）─

    @Test
    fun `golden_Anthropic_最小成功_system提到顶层_逐字节`() {
        assertEquals(
            """{"system":"你是抱朴","stream":true,"max_tokens":4096,"messages":[{"role":"user","content":"你好"}],"model":"m","tools":[{"input_schema":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]},"name":"read_file","description":"读文件"}]}""",
            LlmWire.bodyOf(p(LlmProtocol.ANTHROPIC), sysUser, stream = true, tools = oneToolAnthropic()),
        )
    }

    @Test
    fun `golden_Anthropic_没有system消息时顶层不带system字段`() {
        assertEquals(
            """{"stream":false,"max_tokens":4096,"messages":[{"role":"user","content":"你好"}],"model":"m"}""",
            LlmWire.bodyOf(p(LlmProtocol.ANTHROPIC), listOf(LlmMessage("user", "你好")), stream = false),
        )
    }

    @Test
    fun `golden_Anthropic_多条system按序合并`() {
        val msgs = listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, "第一段"),
            LlmMessage(LlmMessage.ROLE_SYSTEM, "第二段"),
            LlmMessage(LlmMessage.ROLE_USER, "问"),
        )
        assertEquals(
            """{"system":"第一段\n\n第二段","stream":false,"max_tokens":4096,"messages":[{"role":"user","content":"问"}],"model":"m"}""",
            LlmWire.bodyOf(p(LlmProtocol.ANTHROPIC), msgs, stream = false),
        )
    }

    @Test
    fun `golden_Anthropic_图片走base64_source形状`() {
        assertEquals(
            """{"stream":false,"max_tokens":4096,"messages":[{"role":"user","content":[{"text":"看图","type":"text"},{"source":{"data":"BBBB","media_type":"image/jpeg","type":"base64"},"type":"image"}]}],"model":"m"}""",
            LlmWire.bodyOf(
                p(LlmProtocol.ANTHROPIC),
                listOf(LlmMessage("user", "看图", images = listOf("data:image/jpeg;base64,BBBB"))),
                stream = false,
            ),
        )
    }

    // ── Gemini 原生（put 序：[systemInstruction], contents, [tools], generationConfig）─

    @Test
    fun `golden_Gemini_最小成功_contents与functionDeclarations_逐字节`() {
        val msgs = listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, "你是抱朴"),
            LlmMessage(LlmMessage.ROLE_USER, "你好"),
            LlmMessage(LlmMessage.ROLE_ASSISTANT, "在"),
        )
        assertEquals(
            """{"contents":[{"role":"user","parts":[{"text":"你好"}]},{"role":"model","parts":[{"text":"在"}]}],"generationConfig":{"maxOutputTokens":8192},"systemInstruction":{"parts":[{"text":"你是抱朴"}]},"tools":[{"functionDeclarations":[{"parametersJsonSchema":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]},"name":"read_file","description":"读文件"}]}]}""",
            LlmWire.bodyOf(p(LlmProtocol.GEMINI_NATIVE), msgs, stream = true, tools = oneToolOpenAi()),
        )
    }

    @Test
    fun `golden_Gemini_图片走inline_data_用户与模型角色映射`() {
        assertEquals(
            """{"contents":[{"role":"user","parts":[{"text":"读图"},{"inline_data":{"data":"AAAA","mime_type":"image/png"}}]},{"role":"model","parts":[{"text":"好"}]}],"generationConfig":{"maxOutputTokens":8192}}""",
            LlmWire.bodyOf(
                p(LlmProtocol.GEMINI_NATIVE),
                listOf(
                    LlmMessage("user", "读图", images = listOf("data:image/png;base64,AAAA")),
                    LlmMessage("assistant", "好"),
                ),
                stream = false,
            ),
        )
    }

    @Test
    fun `golden_Gemini_空内容消息不造空text段`() {
        assertEquals(
            """{"contents":[{"role":"user","parts":[{"inline_data":{"data":"AAAA","mime_type":"image/png"}}]}],"generationConfig":{"maxOutputTokens":8192}}""",
            LlmWire.bodyOf(
                p(LlmProtocol.GEMINI_NATIVE),
                listOf(LlmMessage("user", "", images = listOf("data:image/png;base64,AAAA"))),
                stream = false,
            ),
        )
    }

    // ── 压缩请求体（completeBody 也走搬家后的 encode；两条红线仍兜住）──

    @Test
    fun `golden_压缩体_不带tools不带stream_盖max_tokens与温度`() {
        val openaiBody = LlmClient(p(LlmProtocol.OPENAI_COMPAT)).completeBody(
            listOf(LlmMessage("user", "压我")),
            maxTokens = 512,
            temperature = 0.2,
        )
        assertEquals(
            """{"stream":false,"max_tokens":512,"temperature":0.2,"messages":[{"role":"user","content":"压我"}],"model":"m"}""",
            openaiBody,
        )
        assertFalse("压缩不许带工具", openaiBody.contains("\"tools\""))
        val geminiBody = LlmClient(p(LlmProtocol.GEMINI_NATIVE)).completeBody(
            listOf(LlmMessage("user", "压我")),
            maxTokens = 512,
            temperature = 0.2,
        )
        assertEquals(
            """{"contents":[{"role":"user","parts":[{"text":"压我"}]}],"generationConfig":{"temperature":0.2,"maxOutputTokens":512}}""",
            geminiBody,
        )
        assertFalse("压缩不许带工具", geminiBody.contains("\"tools\""))
    }

    // ── §1.3 门禁一：LlmClient 里不许再有散落的协议分叉 ─────────────────

    @Test
    fun `门禁_LlmClient不再出现按协议拼请求体的散落字面`() {
        val code = codeOf("app/src/main/java/com/example/zhengdao/baopu/LlmClient.kt")
        // 这些字面都是「按协议拼请求体」的施工细节，搬家后只许出现在 *Wire 文件里
        for (literal in listOf(
            "\"stream_options\"",
            "\"functionDeclarations\"",
            "\"parametersJsonSchema\"",
            "\"systemInstruction\"",
            "\"inline_data\"",
            "\"image_url\"",
            "\"input_schema\"",
        )) {
            assertFalse("LlmClient.kt 里还留着请求体施工细节 $literal（分叉只许在 *Wire 内部）", code.contains(literal))
        }
        // completeBody 的协议分叉也必须收走（收口后引擎侧只剩一次调用）。
        // 只查 completeBody 函数体——bodyOf/completeBodyOf 的三行路由器是归口点，
        // 不是「散落 if 拼请求体」，那是唯一允许出现协议分派的地方。
        val completeAt = code.indexOf("fun completeBody(")
        assertTrue("找不到 completeBody", completeAt >= 0)
        val completeEnd = code.indexOf("fun completeOnce(", completeAt).let { if (it > 0) it else code.length }
        val completeSrc = code.substring(completeAt, completeEnd)
        assertFalse("completeBody 里不许再按协议分叉", completeSrc.contains("LlmProtocol."))
        assertTrue("completeBody 必须走统一出口", code.contains("LlmWire.completeBodyOf("))
    }

    // ── §1.3 门禁二：put 插入序（真机字节序由它决定）────────────────────

    @Test
    fun `门禁_OpenAiWire的put序_模型流上限开关思考消息工具`() {
        val code = codeOf("app/src/main/java/com/example/zhengdao/baopu/wire/OpenAiWire.kt")
        assertPutOrder(
            code,
            listOf("model", "stream", "max_tokens", "stream_options", "reasoning_effort", "messages", "tools"),
        )
    }

    @Test
    fun `门禁_AnthropicWire的put序_模型流上限系统消息工具`() {
        val code = codeOf("app/src/main/java/com/example/zhengdao/baopu/wire/AnthropicWire.kt")
        assertPutOrder(
            code,
            listOf("model", "stream", "max_tokens", "system", "messages", "tools"),
        )
    }

    @Test
    fun `门禁_GeminiWire的put序_系统指令内容工具生成配置`() {
        val code = codeOf("app/src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt")
        assertPutOrder(
            code,
            listOf("systemInstruction", "contents", "tools", "generationConfig"),
        )
    }

    @Test
    fun `门禁_三个Wire文件存在且各自收口`() {
        val dir = File(
            if (File("app/src/main").isDirectory) {
                "app/src/main/java/com/example/zhengdao/baopu/wire"
            } else {
                "src/main/java/com/example/zhengdao/baopu/wire"
            },
        )
        assertTrue("wire 目录不存在（encode 没收口）", dir.isDirectory)
        for (name in listOf("OpenAiWire.kt", "AnthropicWire.kt", "GeminiWire.kt")) {
            assertTrue("缺 $name", File(dir, name).isFile)
        }
        // 工具声明转换与 schema 白名单随 GeminiWire 一起搬家（§1.3 点名含它俩）
        val gemini = codeOf("app/src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt")
        assertTrue("geminiToolDecls 必须住在 GeminiWire 里", gemini.contains("fun toolDecls("))
        assertTrue("sanitizeSchema 必须住在 GeminiWire 里", gemini.contains("fun sanitizeSchema("))
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun codeOf(path: String): String {
        val f = File(path)
        val real = if (f.isFile) f else File(path.removePrefix("app/"))
        assertTrue("找不到 $path（工作目录不对？）", real.isFile)
        return real.readText().lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    /**
     * put 序门禁：这些键必须按给定顺序首次出现（插入序＝真机字节序）。
     *
     * 用正则而不是字面量：键可能换行（`put(\n    "systemInstruction",` 是仓库
     * 既有排版），也可能在 apply 块里没有链式点号——判据是**顺序**，不是排版。
     */
    private fun assertPutOrder(code: String, keys: List<String>) {
        var last = -1
        for (key in keys) {
            val m = Regex("""put\s*\(\s*"${Regex.escape(key)}"""").find(code)
            assertTrue("put(\"$key\" 不在源码里（搬家漏了？）", m != null)
            val at = m!!.range.first
            assertTrue("put 序被打破：\"$key\" 出现在它前一个键之前", at > last)
            last = at
        }
    }
}
