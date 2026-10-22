// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十三批 · 纯函数边界测试（第 5 组）。
//
// `RunRules` 的刹车/确认超时/args 截断判据都是纯函数；本组只补**阈值两侧与空值占位**
// 的边界，不改实现：步数到线、确认超时恰好到线、argsJson 恰好上限/超一字符、
// 拒绝备注空白、确认摘要缺字段。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRulesBoundaryTest {

    @Test
    fun `isStepExhausted_到线才算_前一格不算`() {
        assertFalse(RunRules.isStepExhausted(0))
        assertFalse("差一步不该停", RunRules.isStepExhausted(RunRules.MAX_STEPS - 1))
        assertTrue("恰好到线就停", RunRules.isStepExhausted(RunRules.MAX_STEPS))
        assertTrue(RunRules.isStepExhausted(RunRules.MAX_STEPS + 1))
    }

    @Test
    fun `confirmExpired_恰好到线算超时_前一毫秒不算`() {
        assertFalse(RunRules.confirmExpired(RunRules.CONFIRM_TIMEOUT_MS - 1))
        assertTrue(RunRules.confirmExpired(RunRules.CONFIRM_TIMEOUT_MS))
    }

    @Test
    fun `capArgsJson_恰好上限_原样返回`() {
        val json = "x".repeat(RunRules.CONFIRM_ARGS_MAX_CHARS)
        assertEquals(json, RunRules.capArgsJson(json))
    }

    @Test
    fun `capArgsJson_超一字符_截断并标注原文长度`() {
        val json = "x".repeat(RunRules.CONFIRM_ARGS_MAX_CHARS + 1)
        val capped = RunRules.capArgsJson(json)
        assertTrue("截断正文就是原前 N 个字符", capped.startsWith("x".repeat(RunRules.CONFIRM_ARGS_MAX_CHARS)))
        assertTrue("必须标注原文长度：$capped", capped.contains("${RunRules.CONFIRM_ARGS_MAX_CHARS + 1} 字符"))
        assertTrue(
            "截断正文之后的标注不能缺",
            capped.substring(RunRules.CONFIRM_ARGS_MAX_CHARS).startsWith("\n…（参数过长已截断"),
        )
    }

    @Test
    fun `rejectNote_空白备注不带分号`() {
        assertEquals("用户拒绝：删文件", RunRules.rejectNote("删文件", null))
        assertEquals("用户拒绝：删文件", RunRules.rejectNote("删文件", "   "))
        assertEquals("用户拒绝：删文件；备注：换一种做法", RunRules.rejectNote("删文件", "换一种做法"))
    }

    @Test
    fun `confirmSummary_缺字段走占位不崩`() {
        assertEquals("新建 （未给路径）（1 行）", RunRules.confirmSummary("write_file", JSONObject()))
        assertEquals("编辑 （未给路径）", RunRules.confirmSummary("edit_file", JSONObject()))
        assertEquals("移动 ? → ?", RunRules.confirmSummary("move_path", JSONObject()))
    }
}
