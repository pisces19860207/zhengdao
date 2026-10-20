// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九十五批（94 后续）——「坏参数回灌文案分清 JSON 与 Schema」。
//
// 病灶：`RunEngine` 无论「JSON 语法坏」还是「JSON 合法但 Schema 不符」，都回灌同一句
// 「工具调用参数不是合法 JSON」。后者会让模型去数引号、补括号——它的 JSON 本来就是对的，
// 于是再错一次、用完唯一一次重试预算、整轮停下。
//
// 这组用例分两层（与 P1EngineGateTest 同一口径）：
//   ① 纯逻辑：Codec 判来源（JSON_SYNTAX / SCHEMA），RunRules 按来源给前缀；
//   ② 源码门禁：引擎那一段必须真调 badArgsFeed、且不许再出现写死的旧文案
//      （RunEngine 要真 SQLite + 网络，JVM 里跑不起来 ⇒ 「接没接上」钉在源码结构上）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RawToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BadArgsFeedbackTest {

    // ── ① Codec 判来源 ───────────────────────────────────────────────────

    @Test
    fun `语法坏_来源是JSON_前缀说JSON`() {
        // `{path:}` —— 冒号后面没值，parseArgs 直接 null
        val bad = codec("read_file", """{"path":}""") as ToolCallCodec.DecodeResult.Bad
        assertEquals("参数不是合法 JSON ⇒ 来源必须是 JSON_SYNTAX", ToolCallCodec.BadArgsKind.JSON_SYNTAX, bad.kind)

        val feed = RunRules.badArgsFeed(bad.kind, bad.error)
        assertTrue("语法坏的前缀要保留原来的说法：$feed", feed.startsWith("工具调用参数不是合法 JSON："))
        assertTrue("错误原文必须逐字保留（模型靠它知道错在哪）", feed.contains(bad.error))
        assertFalse("语法坏不许扯 schema：$feed", feed.contains("schema"))
    }

    @Test
    fun `schema坏_来源是SCHEMA_前缀说schema且原文含字段名与允许范围`() {
        // 94 金样本那几类：缺字段 / 类型错 / 枚举外 / 未知参数
        val cases = listOf(
            BadCase("缺必填", "read_file", """{}""", "path"),
            BadCase("缺必填", "write_file", """{"path":"a.md"}""", "content"),
            BadCase("类型错", "read_file", """{"path":5}""", "path"),
            BadCase("类型错", "write_file", """{"path":"a.md","content":"x","overwrite":"true"}""", "overwrite"),
            BadCase("枚举外", "edit_file", """{"path":"a.md","op":"delete","content":"x"}""", "op"),
            BadCase("未知参数", "read_file", """{"path":"a.md","encoding":"gbk"}""", "encoding"),
        )
        for ((label, tool, args, field) in cases) {
            val bad = codec(tool, args) as ToolCallCodec.DecodeResult.Bad
            assertEquals("$label（$tool $args）的 JSON 是合法的，来源必须是 SCHEMA", ToolCallCodec.BadArgsKind.SCHEMA, bad.kind)

            val feed = RunRules.badArgsFeed(bad.kind, bad.error)
            assertTrue("$label 的前缀要说 schema：$feed", feed.startsWith("工具调用参数不符合 schema："))
            assertTrue("$label 的原文要点名字段「$field」：$feed", feed.contains(field))
            assertTrue("$label 的原文要逐字保留：$feed", feed.contains(bad.error))
            assertFalse("$label 不许再说「不是合法 JSON」——那会把模型引去修引号：$feed", feed.contains("不是合法 JSON"))
        }
        // 枚举外还要把允许范围带给模型（否则它只能瞎猜另一个值）
        val enumFeed = RunRules.badArgsFeed(
            ToolCallCodec.BadArgsKind.SCHEMA,
            (codec("edit_file", """{"path":"a.md","op":"delete","content":"x"}""") as ToolCallCodec.DecodeResult.Bad).error,
        )
        assertTrue("枚举外要带允许范围：$enumFeed", enumFeed.contains("append") && enumFeed.contains("replace_lines"))
    }

    // ── ① 一次修正：预算与来源无关 ────────────────────────────────────────

    @Test
    fun `一次修正的预算不变_两种来源都一样`() {
        assertTrue("第一次坏参数（语法）还要给机会", RunRules.shouldRetryBadArgs(0))
        assertTrue("第一次坏参数（schema）还要给机会", RunRules.shouldRetryBadArgs(0))
        assertFalse("第二次就停", RunRules.shouldRetryBadArgs(1))
        for (kind in ToolCallCodec.BadArgsKind.values()) {
            assertTrue(
                "$kind 的回灌文案不能是空的（空文案等于没告诉模型错在哪）",
                RunRules.badArgsFeed(kind, "原文").contains("原文"),
            )
        }
    }

    // ── ② 负例：把两种错写成同一句话就红 ─────────────────────────────────

    @Test
    fun `负例_把schema错写成JSON错就红`() {
        val schemaFeed = RunRules.badArgsFeed(ToolCallCodec.BadArgsKind.SCHEMA, "缺少必填参数「path」")
        assertFalse(
            "schema 错被写成「不是合法 JSON」⇒ 模型会去修引号而不是补字段，本用例必红",
            schemaFeed.contains("不是合法 JSON"),
        )
        assertTrue("schema 错必须自称 schema：$schemaFeed", schemaFeed.contains("不符合 schema"))

        val syntaxFeed = RunRules.badArgsFeed(ToolCallCodec.BadArgsKind.JSON_SYNTAX, "工具「x」的参数不是合法 JSON：{path:}")
        assertFalse(
            "反过来也不行：语法错说成 schema，模型会去补字段，实际该修引号",
            syntaxFeed.contains("不符合 schema"),
        )
    }

    // ── ② 源码门禁：引擎真接上了 ─────────────────────────────────────────

    @Test
    fun `RunEngine的BadArgs分支必须按来源取文案`() {
        val code = engineCode()
        val branch = branchOf(code, "is RoundOutcome.BadArgs -> {", listOf("is RoundOutcome.Failed -> {"))
        assertTrue(
            "回灌文案必须走 RunRules.badArgsFeed(kind, error)，不许在引擎里再手拼一份：$branch",
            branch.contains("RunRules.badArgsFeed("),
        )
        assertFalse(
            "引擎里不许再出现写死的旧文案（写死就等于两种错又合并成一句）",
            branch.contains("不是合法 JSON："),
        )
        assertTrue(
            "来源要从解码结果一路带到引擎（RoundOutcome.BadArgs 必须带 kind）",
            code.contains("decoded.kind"),
        )
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /** 一条「JSON 合法但 Schema 不符」的样本（label 只用于报错信息）。 */
    private data class BadCase(
        val label: String,
        val tool: String,
        val args: String,
        val field: String,
    )

    /** 真注册表（真 Schema，不是手搓假件）：只需 validateCall，不涉可见性/权限。 */
    private fun codec(tool: String, argsJson: String): ToolCallCodec.DecodeResult {
        val root = kotlin.io.path.createTempDirectory("zd-feed-").toFile()
        return try {
            val reg = ToolRegistry(InMemoryToolPrefs())
            BaopuToolCatalog.loadInto(reg) { root }
            ToolCallCodec.decode(listOf(RawToolCall(id = "c1", name = tool, argumentsJson = argsJson)), reg)
        } finally {
            root.deleteRecursively()
        }
    }

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    /** 去掉注释行再判（注释里写着旧文案不算违背）。 */
    private fun engineCode(): String = File(enginePath).readText().lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun branchOf(code: String, head: String, nextHeads: List<String>): String {
        val i = code.indexOf(head)
        assertTrue("找不到分支头：$head", i >= 0)
        val rest = code.substring(i + head.length)
        val end = nextHeads.mapNotNull { h -> rest.indexOf(h).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }
}
