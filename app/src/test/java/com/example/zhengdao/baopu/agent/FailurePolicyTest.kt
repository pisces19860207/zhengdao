// 第八十七批 · 运行时「好」R2（统一失败策略）的判据。
//
// 五组：
//   ① 每类错误 → 决策（表格驱动，六类全覆盖）；
//   ② 重试**有界** ＋ 退避（含负例：把上限拆掉就红）；
//   ③ fallback 出口（有候选才切，没候选绝不切到 null）；
//   ④ ask / abort 出口；
//   ⑤ 与既有判据同源（RunRules 的两个老函数已成策略表的适配器，语义不许漂）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.Providers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailurePolicyTest {

    private val preset: Provider = Providers.all().first { !it.builtinFree }

    private fun retry(action: FailureAction): FailureAction.Retry? = action as? FailureAction.Retry

    // ── ① 每类错误对应决策 ──────────────────────────────────────────────

    @Test
    fun `R2_网络类_先退避重试再换通道`() {
        val noCandidate = FailurePolicy.decide(FailureKind.NETWORK, 1, fallbackAvailable = false)
        assertTrue("第 1 次失败 ⇒ 重试（这是本批唯一改到的默认口径）", noCandidate is FailureAction.Retry)
        assertEquals("退避基数 500ms", 500L, retry(noCandidate)!!.delayMs)

        assertEquals(
            "第 2 次失败 ⇒ 重试预算用尽，又没到熔断线 ⇒ 停下",
            FailureAction.Abort,
            FailurePolicy.decide(FailureKind.NETWORK, 2, fallbackAvailable = false),
        )
        assertEquals(
            "到熔断线且没有候选 ⇒ 问人",
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.NETWORK, 3, fallbackAvailable = false),
        )
        assertEquals(
            "到熔断线且有候选 ⇒ 换通道",
            FailureAction.Fallback,
            FailurePolicy.decide(FailureKind.NETWORK, 3, fallbackAvailable = true),
        )
    }

    @Test
    fun `R2_通道类_从不重试_到线才换`() {
        listOf(1, 2).forEach { n ->
            assertEquals(
                "401/403 重试一万次也一样，第 $n 次只能停下",
                FailureAction.Abort,
                FailurePolicy.decide(FailureKind.MODEL_CHANNEL, n, fallbackAvailable = true),
            )
        }
        assertEquals(
            "到熔断线才换通道",
            FailureAction.Fallback,
            FailurePolicy.decide(FailureKind.MODEL_CHANNEL, 3, fallbackAvailable = true),
        )
        assertEquals(
            "到线但没候选 ⇒ 问人",
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.MODEL_CHANNEL, 3, fallbackAvailable = false),
        )
    }

    @Test
    fun `R2_工具类_超时与报错都先交给模型_到线问人`() {
        assertTrue(
            "工具超时给一次（工具自己写着「可重试一次」）",
            FailurePolicy.decide(FailureKind.TOOL_TIMEOUT, 1, fallbackAvailable = false) is FailureAction.Retry,
        )
        assertTrue(
            "工具报错第 1 次回灌给模型自己看着办（＝Retry）",
            FailurePolicy.decide(FailureKind.TOOL_ERROR, 1, fallbackAvailable = false) is FailureAction.Retry,
        )
        assertTrue(FailurePolicy.decide(FailureKind.TOOL_ERROR, 2, false) is FailureAction.Retry)
        assertEquals(
            "第 3 次到线（MAX_TOOL_FAILURES）⇒ 停下问人",
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.TOOL_ERROR, 3, fallbackAvailable = false),
        )
        assertEquals(
            "工具坏了换通道没用 ⇒ 永远不判 Fallback",
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.TOOL_ERROR, 3, fallbackAvailable = true),
        )
    }

    @Test
    fun `R2_坏参数给一次_第二次停`() {
        assertTrue(
            "第一次坏 ⇒ 回灌原文重试",
            FailurePolicy.decide(FailureKind.BAD_JSON, 1, fallbackAvailable = false) is FailureAction.Retry,
        )
        assertEquals(
            "连着坏第二次 ⇒ 这轮状态不对，如实收尾",
            FailureAction.Abort,
            FailurePolicy.decide(FailureKind.BAD_JSON, 2, fallbackAvailable = false),
        )
    }

    @Test
    fun `R2_取消与停止_永不重试`() {
        listOf(1, 2, 3, 99).forEach { n ->
            assertEquals(
                "用户点了停止，第 $n 次都只能是 Abort（重试＝跟用户对着干）",
                FailureAction.Abort,
                FailurePolicy.decide(FailureKind.CANCELLED, n, fallbackAvailable = true),
            )
        }
    }

    // ── ② 重试有界 ＋ 退避 ──────────────────────────────────────────────

    @Test
    fun `R2_退避是指数且封顶`() {
        assertEquals(500L, FailurePolicy.backoffMs(1))
        assertEquals(1_000L, FailurePolicy.backoffMs(2))
        assertEquals("指数增长不许越界（attempt 被压到硬上限）", 1_000L, FailurePolicy.backoffMs(9))
        val custom = FailurePolicyConfig(backoffBaseMs = 2_000L, backoffMaxMs = 3_000L)
        assertEquals(2_000L, FailurePolicy.backoffMs(1, custom))
        assertEquals("第二次要封顶在 max", 3_000L, FailurePolicy.backoffMs(2, custom))
        assertEquals("负数基数不许产生负延迟", 0L, FailurePolicy.backoffMs(1, custom.copy(backoffBaseMs = -5L)))
    }

    /**
     * 负例（派工单 §3 点名的那条）：**把 abort 改成 retry 无限 ⇒ 用例红**。
     *
     * 做法：把重试预算写成无限大（`Int.MAX_VALUE`），再真的按策略结论循环 50 次——
     * 如果硬上限 [FailurePolicy.MAX_RETRY_ATTEMPTS] 被谁拆掉，这里会一路 Retry 到底，
     * 「重试次数 ≤ 硬上限」和「一定会走到非 Retry」两条同时红。
     */
    @Test
    fun `R2_负例_配置写无限也不会无限重试`() {
        val infinite = FailurePolicyConfig(
            retries = FailureKind.values().associateWith { Int.MAX_VALUE },
        )
        listOf(FailureKind.NETWORK, FailureKind.BAD_JSON).forEach { kind ->
            var retries = 0
            var lastAction: FailureAction = FailureAction.Abort
            for (attempt in 1..50) {
                lastAction = FailurePolicy.decide(kind, attempt, fallbackAvailable = true, config = infinite)
                if (lastAction is FailureAction.Retry) retries++ else break
            }
            assertTrue(
                "$kind 重试了 $retries 次，超过硬上限 ${FailurePolicy.MAX_RETRY_ATTEMPTS}——" +
                    "「abort 被改成 retry 无限」的退化必须被这条抓到",
                retries <= FailurePolicy.MAX_RETRY_ATTEMPTS,
            )
            assertFalse("$kind 在第 50 次之后还在重试（无限循环）", lastAction is FailureAction.Retry)
        }
    }

    @Test
    fun `R2_配置能覆盖默认口径_也能整份退回旧行为`() {
        val noRetry = FailurePolicyConfig(retries = FAILURE_DEFAULT_RETRIES.mapValues { 0 })
        assertEquals(
            "retries 全 0 ⇒ 网络失败第 1 次就停（＝第八十七批之前的老口径）",
            FailureAction.Abort,
            FailurePolicy.decide(FailureKind.NETWORK, 1, fallbackAvailable = false, config = noRetry),
        )
        assertEquals(
            "预设的 LEGACY_NO_RETRY 就是这一份",
            FailureAction.Abort,
            FailurePolicy.decide(
                FailureKind.NETWORK,
                1,
                fallbackAvailable = false,
                config = FailurePolicyConfig.LEGACY_NO_RETRY,
            ),
        )
        assertEquals(
            "关掉 fallback ⇒ 到线也不换，直接问人",
            FailureAction.AskUser,
            FailurePolicy.decide(
                FailureKind.NETWORK,
                3,
                fallbackAvailable = true,
                config = FailurePolicy.DEFAULT.copy(allowFallback = false),
            ),
        )
    }

    // ── ③ fallback 出口 ────────────────────────────────────────────────

    @Test
    fun `R2_fallback后成功_策略与物理出路对得上`() {
        val action = FailurePolicy.decide(FailureKind.NETWORK, 3, fallbackAvailable = true)
        val physical = RunRules.channelDecision(action, preset)
        assertTrue("策略说换 ⇒ 物理动作必须是切到候选，实际 $physical", physical is RunRules.ChannelDecision.SwitchTo)
        assertEquals(preset, (physical as RunRules.ChannelDecision.SwitchTo).next)
    }

    @Test
    fun `R2_候选缺失时绝不切到null`() {
        val action = FailurePolicy.decide(FailureKind.NETWORK, 3, fallbackAvailable = true)
        assertEquals(
            "策略说 Fallback 但装配层没给候选 ⇒ 只能问人",
            RunRules.ChannelDecision.BrokenAskUser,
            RunRules.channelDecision(action, null),
        )
    }

    @Test
    fun `R2_重试与中止都不许被翻译成换通道`() {
        assertEquals(
            "Retry ⇒ 留在原通道（换通道是另一件事）",
            RunRules.ChannelDecision.KeepCurrent,
            RunRules.channelDecision(FailureAction.Retry(500L, 1), preset),
        )
        assertEquals(
            "Abort ⇒ 留在原通道并停下",
            RunRules.ChannelDecision.KeepCurrent,
            RunRules.channelDecision(FailureAction.Abort, preset),
        )
    }

    // ── ④ ask / abort 出口 ────────────────────────────────────────────

    @Test
    fun `R2_ask映射成问人_abort映射成停`() {
        assertEquals(
            RunRules.ChannelDecision.BrokenAskUser,
            RunRules.channelDecision(FailureAction.AskUser, preset),
        )
        assertEquals(
            RunRules.ChannelDecision.KeepCurrent,
            RunRules.channelDecision(FailureAction.Abort, null),
        )
    }

    @Test
    fun `R2_旧签名逐条不变（ProviderSwitchTest 的口径）`() {
        for (n in 0 until RunRules.CHANNEL_BREAK_THRESHOLD) {
            assertEquals(
                "连续失败 $n 次不该切通道（旧签名没有重试这一层）",
                RunRules.ChannelDecision.KeepCurrent,
                RunRules.channelDecision(n, preset),
            )
        }
        assertTrue(
            RunRules.channelDecision(RunRules.CHANNEL_BREAK_THRESHOLD, preset) is RunRules.ChannelDecision.SwitchTo,
        )
        assertEquals(
            RunRules.ChannelDecision.BrokenAskUser,
            RunRules.channelDecision(RunRules.CHANNEL_BREAK_THRESHOLD, null),
        )
    }

    // ── ⑤ 分类与「同源」 ───────────────────────────────────────────────

    @Test
    fun `R2_分类_Key与地区归通道_超时限流归网络`() {
        assertEquals(FailureKind.MODEL_CHANNEL, FailurePolicy.classifyModel("HTTP 401 unauthorized"))
        assertEquals(FailureKind.MODEL_CHANNEL, FailurePolicy.classifyModel("该通道拒绝访问（403）"))
        assertEquals(FailureKind.MODEL_CHANNEL, FailurePolicy.classifyModel("RegionError: not available"))
        assertEquals(FailureKind.NETWORK, FailurePolicy.classifyModel("等模型回复超时（90s）"))
        assertEquals(FailureKind.NETWORK, FailurePolicy.classifyModel("HTTP 500 internal"))
        assertEquals(FailureKind.NETWORK, FailurePolicy.classifyModel("截断"))
        assertEquals("空原文按网络类兜底（至少走一次有界重试再停）", FailureKind.NETWORK, FailurePolicy.classifyModel(""))
    }

    @Test
    fun `R2_分类_工具超时单独一类`() {
        assertEquals(
            FailureKind.TOOL_TIMEOUT,
            FailurePolicy.classifyTool("工具「write_file」执行超时（上限 30000 ms）——可重试一次"),
        )
        assertEquals(FailureKind.TOOL_ERROR, FailurePolicy.classifyTool("找不到文件：a.md"))
    }

    @Test
    fun `R2_旧判据是策略表的适配器_不会各说各话`() {
        for (n in 0..3) {
            assertEquals(
                "shouldRetryBadArgs($n) 与策略不一致",
                FailurePolicy.decide(FailureKind.BAD_JSON, n + 1, false) is FailureAction.Retry,
                RunRules.shouldRetryBadArgs(n),
            )
            assertEquals(
                "shouldAskUser($n) 与策略不一致",
                FailurePolicy.decide(FailureKind.TOOL_ERROR, n, false) !is FailureAction.Retry,
                RunRules.shouldAskUser(n),
            )
        }
        assertFalse(RunRules.shouldAskUser(2))
        assertTrue(RunRules.shouldAskUser(3))
    }

    @Test
    fun `R2_结论标签是枚举文字_回放按它筛`() {
        assertEquals("RETRY#2", FailurePolicy.decisionLabel(FailureAction.Retry(1_000L, 2)))
        assertEquals("FALLBACK", FailurePolicy.decisionLabel(FailureAction.Fallback))
        assertEquals("ASK_USER", FailurePolicy.decisionLabel(FailureAction.AskUser))
        assertEquals("ABORT", FailurePolicy.decisionLabel(FailureAction.Abort))
    }

    // ── 第一百四十三批 · 阈值/边界补强（只钉当前实现） ─────────────────

    @Test
    fun `R2_attempt非正按第1次失败算`() {
        // decide 的 n = attempt.coerceAtLeast(1)；Retry.attempt 也回填 1（不是原始 0/-7）
        assertEquals(FailureAction.Retry(500L, 1), FailurePolicy.decide(FailureKind.NETWORK, 0, false))
        assertEquals(FailureAction.Retry(500L, 1), FailurePolicy.decide(FailureKind.NETWORK, -7, false))
        assertEquals(FailureAction.Retry(500L, 1), FailurePolicy.decide(FailureKind.BAD_JSON, 0, false))
        assertEquals(
            "取消类与次数无关，负次数也只 Abort",
            FailureAction.Abort,
            FailurePolicy.decide(FailureKind.CANCELLED, -1, fallbackAvailable = true),
        )
    }

    @Test
    fun `R2_重试硬上限2_第三次一定不重试`() {
        // 配置写 99 也越不过 MAX_RETRY_ATTEMPTS=2：第 3 次既不在预算内、也没到熔断线 ⇒ Abort
        val wide = FailurePolicyConfig(
            retries = FAILURE_DEFAULT_RETRIES + (FailureKind.NETWORK to 99),
            channelBreakThreshold = 99,
        )
        assertTrue(FailurePolicy.decide(FailureKind.NETWORK, 1, false, wide) is FailureAction.Retry)
        assertTrue(FailurePolicy.decide(FailureKind.NETWORK, 2, false, wide) is FailureAction.Retry)
        assertEquals(
            FailureAction.Abort,
            FailurePolicy.decide(FailureKind.NETWORK, 3, false, wide),
        )
    }

    @Test
    fun `R2_工具失败先判到线_再判重试预算`() {
        // 源码顺序是 `n >= toolFailureLimit` 在前 ⇒ 线设成 1 时第 1 次直接问人，
        // 即使 retries 预算还给着 1 次（按当前实现钉住的优先级）。
        val lineFirst = FailurePolicyConfig(toolFailureLimit = 1)
        assertEquals(
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.TOOL_TIMEOUT, 1, false, lineFirst),
        )
        assertEquals(
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.TOOL_ERROR, 1, true, lineFirst),
        )
    }

    @Test
    fun `R2_网络重试分支优先于熔断线`() {
        // 熔断线设成 1：第 1 次仍走「n <= retries」的重试分支（顺序在源码里就是先重试）
        val breakAtOnce = FailurePolicyConfig(channelBreakThreshold = 1)
        assertTrue(
            FailurePolicy.decide(FailureKind.NETWORK, 1, false, breakAtOnce) is FailureAction.Retry,
        )
        assertEquals(
            "第 2 次重试预算用尽且已过熔断线 ⇒ 有候选就换",
            FailureAction.Fallback,
            FailurePolicy.decide(FailureKind.NETWORK, 2, true, breakAtOnce),
        )
        assertEquals(
            "第 2 次过线但没候选 ⇒ 问人",
            FailureAction.AskUser,
            FailurePolicy.decide(FailureKind.NETWORK, 2, false, breakAtOnce),
        )
    }

    @Test
    fun `R2_配置重试为负_按0算_第一次就停`() {
        val negative = FailurePolicyConfig(retries = mapOf(FailureKind.NETWORK to -3))
        assertEquals(
            FailureAction.Abort,
            FailurePolicy.decide(FailureKind.NETWORK, 1, false, negative),
        )
    }

    @Test
    fun `R2_退避_attempt0与max小于base的边界`() {
        // attempt 0 被压到 1 ⇒ 基数 500
        assertEquals(500L, FailurePolicy.backoffMs(0))
        // max < base：无论第几次都只能落在 max（且不为负）
        val capped = FailurePolicyConfig(backoffBaseMs = 2_000L, backoffMaxMs = 500L)
        assertEquals(500L, FailurePolicy.backoffMs(1, capped))
        assertEquals(500L, FailurePolicy.backoffMs(2, capped))
        // max 为负：结果被夹到 0，绝不产生负延迟
        val negativeMax = FailurePolicyConfig(backoffBaseMs = 500L, backoffMaxMs = -1L)
        assertEquals(0L, FailurePolicy.backoffMs(1, negativeMax))
        assertEquals(0L, FailurePolicy.backoffMs(2, negativeMax))
    }

    @Test
    fun `R2_分类_子串命中比数字边界宽`() {
        // 分类用的是 contains 子串："1401" 里含 "401" 也会被判成通道类（按当前实现钉住，
        // 交付报告登记为「脆弱但当前无害」——请求号恰好含 401 的概率极低）。
        assertEquals(
            FailureKind.MODEL_CHANNEL,
            FailurePolicy.classifyModel("请求号 1401 超时"),
        )
        assertEquals(FailureKind.MODEL_CHANNEL, FailurePolicy.classifyModel("regionerror"))
        assertEquals(FailureKind.MODEL_CHANNEL, FailurePolicy.classifyModel("FreeTier 限制"))
        assertEquals(FailureKind.NETWORK, FailurePolicy.classifyModel("HTTP 429"))
        assertEquals(FailureKind.TOOL_TIMEOUT, FailurePolicy.classifyTool("TIMEOUT"))
        assertEquals(
            "只认 timeouts 子串写法；timed out 不含 timeout ⇒ 归工具报错",
            FailureKind.TOOL_ERROR,
            FailurePolicy.classifyTool("timed out"),
        )
    }

    @Test
    fun `R2_结论标签不再归一_attempt0原样`() {
        // decisionLabel 只做字符串拼接，不 coerce attempt（按当前实现钉住）
        assertEquals("RETRY#0", FailurePolicy.decisionLabel(FailureAction.Retry(500L, 0)))
        assertEquals("RETRY#-1", FailurePolicy.decisionLabel(FailureAction.Retry(500L, -1)))
    }
}
