// 第一百一十五批 · 工具自修 v1 的判据（纯函数层）。
//
// 五组：
//   ① 参数可修：缺必填 / 未知参数 / 类型 / 枚举 / 数值范围 ⇒ RETRY_WITH_ARGS；
//   ② 不是参数问题：工具超时 / 网络 / 权限 / 未知工具 ⇒ 各自归因，且不冒充重试；
//   ③ 不硬猜：拿不到结构化证据 ⇒ UNKNOWN + NONE；
//   ④ 建议参数是**副本**：原参数一个字节都不动（负例见安全门禁）；
//   ⑤ 审计行：单行、带锚、只带建议参数的键名（不泄参数内容）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureAdvisorTest {

    private fun advise(
        tool: String = "read_file",
        error: String,
        args: JSONObject? = null,
        kind: FailureKind? = null,
    ): ToolRepairAdvice = ToolFailureAdvisor.advise(
        ToolFailureEvidence(
            toolName = tool,
            errorText = error,
            arguments = args,
            failureKind = kind,
        ),
    )

    // ── ① 参数可修 ──────────────────────────────────────────────────────

    @Test
    fun `A1_缺少必填参数_给建议参数且原参数不变`() {
        val args = JSONObject().put("offset", 5)
        val before = args.toString()
        val a = advise(error = "缺少必填参数「path」", args = args)

        assertEquals(ToolFailureType.SCHEMA, a.type)
        assertEquals(ToolFailureCause.ARGUMENTS_FIXABLE, a.cause)
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, a.suggestion)
        assertTrue("原因要点名缺哪个字段", a.reason.contains("path"))
        val suggested = a.suggestedArgs
        assertNotNull("schema 错要给建议参数", suggested)
        assertEquals("占位值必须明确是待填", ToolFailureAdvisor.PLACEHOLDER, suggested!!.optString("path"))
        assertEquals("其余参数原样保留", 5, suggested.optInt("offset"))
        assertEquals("输入参数绝不能被原地改", before, args.toString())
        assertFalse(args.has("path"))
    }

    @Test
    fun `A2_未知参数_建议参数去掉该键`() {
        val args = JSONObject().put("path", "a.md").put("limit", 5)
        val before = args.toString()
        val a = advise(
            error = "未知参数「limit」（本工具不接受这个参数；可用参数：path）",
            args = args,
        )

        assertEquals(ToolFailureType.SCHEMA, a.type)
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, a.suggestion)
        val suggested = a.suggestedArgs!!
        assertFalse("原文点名的未知参数必须去掉", suggested.has("limit"))
        assertEquals("a.md", suggested.optString("path"))
        assertEquals(before, args.toString())
        assertTrue(args.has("limit"))
    }

    @Test
    fun `A3_类型不对_能无损转换才给建议参数`() {
        val convertible = JSONObject().put("limit", "10")
        val a = advise(
            error = "参数「limit」类型不对：期望 整数，实到 字符串",
            args = convertible,
        )
        assertEquals(ToolFailureType.TYPE, a.type)
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, a.suggestion)
        assertEquals("10", convertible.optString("limit"))
        assertEquals(10, a.suggestedArgs!!.optInt("limit"))

        val notConvertible = JSONObject().put("limit", "abc")
        val b = advise(
            error = "参数「limit」类型不对：期望 整数，实到 字符串",
            args = notConvertible,
        )
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, b.suggestion)
        assertNull("转不了就不许编一个值", b.suggestedArgs)
        assertTrue("要把「给不出值」说清楚", b.action.contains("无法从错误原文确定"))
    }

    @Test
    fun `A4_枚举外_只做大小写无损修正_不替模型挑值`() {
        val fixable = JSONObject().put("mode", "Read")
        val a = advise(
            error = "参数「mode」取值不在允许范围（read、write）",
            args = fixable,
        )
        assertEquals(ToolFailureType.ENUM, a.type)
        assertEquals("read", a.suggestedArgs!!.optString("mode"))

        val unfixable = JSONObject().put("mode", "append")
        val b = advise(
            error = "参数「mode」取值不在允许范围（read、write）",
            args = unfixable,
        )
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, b.suggestion)
        assertNull("允许值有两个，不能替模型猜一个", b.suggestedArgs)
        assertTrue(b.reason.contains("read"))
        assertTrue(b.reason.contains("write"))
        assertTrue(b.action.contains("不替模型猜具体值"))
    }

    @Test
    fun `A5_数值越界_按边界夹紧`() {
        val a = advise(
            error = "参数「limit」不能大于 10",
            args = JSONObject().put("limit", 11),
        )
        assertEquals("数值范围是 schema 约束，不是 enum", ToolFailureType.SCHEMA, a.type)
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, a.suggestion)
        assertEquals(10, a.suggestedArgs!!.optInt("limit"))
        assertTrue(a.reason.contains("10"))
    }

    @Test
    fun `A5b_负数下界也要认得_不因符号漏判`() {
        val a = advise(
            error = "参数「offset」不能小于 -10",
            args = JSONObject().put("offset", -20),
        )
        assertEquals("数值范围是 schema 约束，不是 enum", ToolFailureType.SCHEMA, a.type)
        assertEquals(ToolRepairSuggestion.RETRY_WITH_ARGS, a.suggestion)
        assertEquals("夹到负下界上", -10, a.suggestedArgs!!.optInt("offset"))
        assertTrue("原因里要保留负号", a.reason.contains("-10"))
    }

    // ── ② 不是参数问题 ──────────────────────────────────────────────────

    @Test
    fun `A6_工具执行超时_环境问题_不自动重试`() {
        val a = advise(
            error = "工具「read_file」执行超时（上限 5000 ms）——可重试一次",
            kind = FailureKind.TOOL_TIMEOUT,
        )
        assertEquals(ToolFailureType.TIMEOUT, a.type)
        assertEquals(ToolFailureCause.ENVIRONMENT, a.cause)
        assertEquals(ToolRepairSuggestion.ENV_ISSUE, a.suggestion)
        assertNull("超时不该给参数建议", a.suggestedArgs)
        assertTrue("要把决定权交还给用户", a.action.contains("用户决定"))
    }

    @Test
    fun `A7_连接超时_归网络而不是工具超时`() {
        val a = advise(error = "连接超时：SocketTimeoutException")
        assertEquals(ToolFailureType.NETWORK, a.type)
        assertEquals(ToolFailureCause.ENVIRONMENT, a.cause)
        assertEquals(ToolRepairSuggestion.ENV_ISSUE, a.suggestion)
    }

    @Test
    fun `A8_权限与未解锁_问用户`() {
        listOf(
            "路径越界被拒：../x 不在工作区（/work）内。",
            "该工具已被用户关闭（在 抱朴设置 → 抱朴工具 里打开）。",
            "该工具组未解锁：先调 capabilities",
            "读不了这个文件（权限或 IO 错误）：a.md",
        ).forEach { error ->
            val a = advise(error = error)
            assertEquals("应归权限类：$error", ToolFailureType.PERMISSION, a.type)
            assertEquals("应归需要用户：$error", ToolFailureCause.NEEDS_USER, a.cause)
            assertEquals("应建议问人：$error", ToolRepairSuggestion.ASK_USER, a.suggestion)
        }
    }

    @Test
    fun `A9_未知工具或明确改路_建议换工具`() {
        listOf(
            "未知工具「foo」（已注册：read_file、list_dir）",
            "暂不支持 @eN 句柄，请改用 CSS 选择器",
        ).forEach { error ->
            val a = advise(error = error)
            assertEquals("应归换工具：$error", ToolFailureCause.ALTERNATIVE_TOOL, a.cause)
            assertEquals("应建议换路：$error", ToolRepairSuggestion.TRY_ALTERNATIVE, a.suggestion)
        }
    }

    @Test
    fun `A10_工具崩溃_环境问题`() {
        val a = advise(error = "工具「read_file」执行崩了：NullPointerException")
        assertEquals(ToolFailureCause.ENVIRONMENT, a.cause)
        assertEquals(ToolRepairSuggestion.ENV_ISSUE, a.suggestion)
    }

    // ── ③ 不硬猜 ────────────────────────────────────────────────────────

    @Test
    fun `A11_未知原文_不硬猜`() {
        listOf("奇怪的事情发生了", "").forEach { error ->
            val a = advise(error = error)
            assertEquals(ToolFailureType.UNKNOWN, a.type)
            assertEquals(ToolFailureCause.UNKNOWN, a.cause)
            assertEquals(ToolRepairSuggestion.NONE, a.suggestion)
            assertNull(a.suggestedArgs)
        }
    }

    @Test
    fun `A12_用户已停止_永不建议重试`() {
        val a = advise(error = "用户点了停止", kind = FailureKind.CANCELLED)
        assertEquals(ToolRepairSuggestion.NONE, a.suggestion)
        assertTrue(a.action.contains("不要自动重试"))
    }

    // ── ④/⑤ 建议参数是副本 ＋ 审计行 ────────────────────────────────────

    @Test
    fun `A13_建议参数是副本_改它不影响原参数`() {
        val args = JSONObject().put("content", "SECRET-CONTENT")
        val a = advise(error = "缺少必填参数「path」", args = args)
        val suggested = a.suggestedArgs!!
        assertTrue("建议参数不能和输入共享同一对象", suggested !== args)
        suggested.put("mutated", true)
        assertFalse("改建议参数不能回写输入", args.has("mutated"))
        assertFalse(args.has("path"))
    }

    @Test
    fun `A14_审计行单行_只记键名不记参数内容`() {
        val args = JSONObject().put("content", "SECRET-CONTENT")
        val a = advise(error = "缺少必填参数「path」", args = args)
        val line = ToolFailureAdvisor.auditLine(a, "缺少必填参数「path」\n第二行")

        assertTrue("必须带可 grep 的锚", line.startsWith(ToolFailureAdvisor.TAG))
        assertTrue(line.contains("type=SCHEMA"))
        assertTrue(line.contains("cause=ARGUMENTS_FIXABLE"))
        assertTrue(line.contains("suggestion=RETRY_WITH_ARGS"))
        assertTrue("只记建议参数的键名", line.contains("suggested_keys=content,path"))
        assertFalse("日志不许成为正文出口", line.contains("SECRET-CONTENT"))
        assertFalse("审计行必须单行（错误原文的换行要压平）", line.contains("\n") || line.contains("\r"))
    }

    @Test
    fun `A15_回灌后缀可读_且明说不执行`() {
        val a = advise(error = "缺少必填参数「path」")
        val s = a.feedbackSuffix()
        assertTrue(s.contains("归因"))
        assertTrue(s.contains("建议"))
        assertTrue(s.contains("只给建议，不自动执行"))
        assertFalse("后缀必须单行", s.contains("\n") || s.contains("\r"))
    }
}
