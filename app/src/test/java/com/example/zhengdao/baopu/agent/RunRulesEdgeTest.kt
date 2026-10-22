// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百四十四批 · 纯函数边界测试第二批（第 2 组）。
//
// 143 批（RunRulesBoundaryTest）已钉「步数/确认超时/args 截断/备注/摘要」的值边界；
// 本组按派工单换到另一面：**重试与停止的负值归一**、失败分类文案、确认计时的空值。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunRulesEdgeTest {

    private val preset: Provider = Providers.all().first { !it.builtinFree }

    // ── 重试 / 停止：负值按「第一次」算 ────────────────────────────────────

    @Test
    fun `坏参数重试_负值按第一次算_只给一次机会`() {
        assertTrue("负值归一成第 1 次 ⇒ 仍给一次", RunRules.shouldRetryBadArgs(-1))
        assertTrue(RunRules.shouldRetryBadArgs(0))
        assertFalse("第 2 次不再给", RunRules.shouldRetryBadArgs(1))
        assertFalse(RunRules.shouldRetryBadArgs(2))
        assertFalse(RunRules.shouldRetryBadArgs(Int.MAX_VALUE - 1))
    }

    @Test
    fun `坏参数重试_Int最大值加一溢出_当前实现会再给一次`() {
        // 发现（不改代码，按当前实现钉住）：retries + 1 在 Int.MAX_VALUE 处溢出成负数，
        // FailurePolicy 把 ≤0 归一成第 1 次 ⇒ 这个本该「早已用完机会」的输入反而拿到 Retry。
        assertTrue(
            "Int.MAX_VALUE + 1 溢出后仍被判重试（实现缺陷，见交付报告差异清单）",
            RunRules.shouldRetryBadArgs(Int.MAX_VALUE),
        )
    }

    @Test
    fun `工具失败问人_到线才停_负值不越线`() {
        assertFalse("负值归一成第 1 次", RunRules.shouldAskUser(-1))
        assertFalse(RunRules.shouldAskUser(0))
        assertFalse(RunRules.shouldAskUser(RunRules.MAX_TOOL_FAILURES - 1))
        assertTrue("恰好到线就停", RunRules.shouldAskUser(RunRules.MAX_TOOL_FAILURES))
        assertTrue(RunRules.shouldAskUser(RunRules.MAX_TOOL_FAILURES + 1))
        assertTrue(RunRules.shouldAskUser(Int.MAX_VALUE))
    }

    @Test
    fun `通道熔断_到线才算_负值与零都不熔断`() {
        assertFalse(RunRules.shouldBreakChannel(-1))
        assertFalse(RunRules.shouldBreakChannel(0))
        assertFalse(RunRules.shouldBreakChannel(RunRules.CHANNEL_BREAK_THRESHOLD - 1))
        assertTrue(RunRules.shouldBreakChannel(RunRules.CHANNEL_BREAK_THRESHOLD))
    }

    @Test
    fun `通道处置_负的连续失败留在原通道`() {
        assertEquals(
            RunRules.ChannelDecision.KeepCurrent,
            RunRules.channelDecision(-1, preset),
        )
        assertEquals(
            RunRules.ChannelDecision.KeepCurrent,
            RunRules.channelDecision(0, preset),
        )
    }

    // ── 失败回灌文案 ──────────────────────────────────────────────────────

    @Test
    fun `坏参数回灌_只改前缀_错误原文逐字保留`() {
        assertEquals(
            "工具调用参数不是合法 JSON：缺右括号；请修正参数后重试",
            RunRules.badArgsFeed(ToolCallCodec.BadArgsKind.JSON_SYNTAX, "缺右括号"),
        )
        assertEquals(
            "工具调用参数不符合 schema：字段 a 缺失；请修正参数后重试",
            RunRules.badArgsFeed(ToolCallCodec.BadArgsKind.SCHEMA, "字段 a 缺失"),
        )
    }

    @Test
    fun `坏参数回灌_空原文也不吞前缀`() {
        assertEquals(
            "工具调用参数不是合法 JSON：；请修正参数后重试",
            RunRules.badArgsFeed(ToolCallCodec.BadArgsKind.JSON_SYNTAX, ""),
        )
    }

    // ── 确认计时 ──────────────────────────────────────────────────────────

    @Test
    fun `确认超时_负值与零都不算超时`() {
        assertFalse(RunRules.confirmExpired(-1))
        assertFalse(RunRules.confirmExpired(0))
    }

    @Test
    fun `确认计时_负已结账归零_时长按当前段落现算`() {
        assertEquals("负已结账按 0", 0L, RunRules.confirmElapsedMs(-5L, null, 1_000L))
        assertEquals("在后台只算已结账", 100L, RunRules.confirmElapsedMs(100L, null, 1_000L))
        assertEquals(
            "since 晚于 now 时这一段按 0，不倒扣",
            100L,
            RunRules.confirmElapsedMs(100L, 1_000L, 500L),
        )
        assertEquals(
            "已结账 + 当前段落",
            600L,
            RunRules.confirmElapsedMs(100L, 1_000L, 1_500L),
        )
    }

    // ── 其余空值边界 ──────────────────────────────────────────────────────

    @Test
    fun `args截断_空串原样返回`() {
        assertEquals("", RunRules.capArgsJson(""))
    }

    @Test
    fun `步数耗尽_负步数不算耗尽`() {
        assertFalse(RunRules.isStepExhausted(-1))
        assertFalse(RunRules.isStepExhausted(0))
    }
}
