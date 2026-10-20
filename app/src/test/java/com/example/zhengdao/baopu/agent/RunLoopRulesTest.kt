// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十九批 · 结构拆分第八阶段：`RunLoopRules` 的 JVM 直测（派工单 §2：每个抽出的
// 纯函数 ≥3 例，覆盖正常/边界/空值/冲突优先级）。
//
// 这些判据改前全埋在 `RunEngine` 里，要真 SQLite ＋ 真网络才够得着（本仓无
// Robolectric，runLoop 归真机）——单抽出来之后，「notice 覆盖顺序、失败人话化
// 优先级、熔断二选一」这些**纯值决策**第一次有了直接用例。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLoopRulesTest {

    // ── ① 收尾闸重修提醒 ─────────────────────────────────────────────────

    @Test
    fun `修复提醒_带未过项数`() {
        assertEquals("自检发现 3 项未过，已让抱朴修一轮", RunLoopRules.verifyRepairNotice(3))
    }

    @Test
    fun `修复提醒_一项也是同一句式`() {
        assertEquals("自检发现 1 项未过，已让抱朴修一轮", RunLoopRules.verifyRepairNotice(1))
    }

    // ── ② 收尾 notice 三级覆盖（改前源码的顺序就是优先级） ────────────────

    @Test
    fun `收尾notice_什么都不命中就不动_notice保留原值`() {
        assertNull(RunLoopRules.textSettleNotice(findingsCount = 0, findingsSummary = null, unfinishedPlanCount = 0, truncated = false))
    }

    @Test
    fun `收尾notice_只有收尾闸未过时贴摘要`() {
        assertEquals(
            "a.md(文本)；b.md(二进制)",
            RunLoopRules.textSettleNotice(
                findingsCount = 2,
                findingsSummary = "a.md(文本)；b.md(二进制)",
                unfinishedPlanCount = 0,
                truncated = false,
            ),
        )
    }

    @Test
    fun `收尾notice_计划未勾覆盖收尾闸摘要`() {
        assertEquals(
            "计划里还有 4 条没勾（进度看顶部计划卡）",
            RunLoopRules.textSettleNotice(
                findingsCount = 2,
                findingsSummary = "a.md(文本)",
                unfinishedPlanCount = 4,
                truncated = false,
            ),
        )
    }

    @Test
    fun `收尾notice_截断覆盖计划未勾_后写的赢`() {
        assertEquals(
            "回复被输出上限截断了（服务端说的），点「继续说完」接着写",
            RunLoopRules.textSettleNotice(
                findingsCount = 2,
                findingsSummary = "a.md(文本)",
                unfinishedPlanCount = 4,
                truncated = true,
            ),
        )
    }

    @Test
    fun `收尾notice_截断单独命中_无计划无未过项`() {
        assertEquals(
            "回复被输出上限截断了（服务端说的），点「继续说完」接着写",
            RunLoopRules.textSettleNotice(findingsCount = 0, findingsSummary = null, unfinishedPlanCount = 0, truncated = true),
        )
    }

    @Test
    fun `收尾notice_计划未勾单独命中_条数插值`() {
        assertEquals(
            "计划里还有 7 条没勾（进度看顶部计划卡）",
            RunLoopRules.textSettleNotice(findingsCount = 0, findingsSummary = null, unfinishedPlanCount = 7, truncated = false),
        )
    }

    @Test
    fun `收尾notice_count才是判据_summary不自行其是`() {
        // count=0 却给了 summary：不能贴（判据是 count，summary 只是载荷）
        assertNull(RunLoopRules.textSettleNotice(findingsCount = 0, findingsSummary = "a.md(文本)", unfinishedPlanCount = 0, truncated = false))
    }

    @Test
    fun `收尾notice_count大于0但summary缺失_安全降级不动notice`() {
        // 调用方保证 count>0 时传非空；真传 null 也不许崩、不许贴字面 "null"
        assertNull(RunLoopRules.textSettleNotice(findingsCount = 2, findingsSummary = null, unfinishedPlanCount = 0, truncated = false))
    }

    // ── ③ 失败人话化（when 的先后就是优先级） ────────────────────────────

    @Test
    fun `人话化_403给换通道指路`() {
        assertEquals(
            "该通道拒绝访问（403）：Key 不对、没权限，或这条通道在你的网络里不通——换通道",
            RunLoopRules.humanizeNetFailure("HTTP 403 Forbidden"),
        )
    }

    @Test
    fun `人话化_401说不认Key`() {
        assertEquals(
            "该通道不认这把 Key（401）：检查 Key 是否填对，或换通道",
            RunLoopRules.humanizeNetFailure("HTTP 401 unauthorized"),
        )
    }

    @Test
    fun `人话化_429说限流`() {
        assertEquals(
            "模型端限流（429）：稍等再试，或换一个 provider",
            RunLoopRules.humanizeNetFailure("HTTP 429 Too Many Requests"),
        )
    }

    @Test
    fun `人话化_5xx说服务错误`() {
        assertEquals(
            "模型端服务错误（5xx）：稍等再试",
            RunLoopRules.humanizeNetFailure("HTTP 500 Internal Server Error"),
        )
    }

    @Test
    fun `人话化_响应流中断`() {
        assertEquals(
            "响应流中断：该轮作废重发（已花的 token 记在账里）",
            RunLoopRules.humanizeNetFailure("stream 被截断"),
        )
    }

    @Test
    fun `人话化_超时大小写不敏感`() {
        val expected = "连接超时：检查网络或换一个 provider"
        assertEquals(expected, RunLoopRules.humanizeNetFailure("Connection Timeout"))
        assertEquals(expected, RunLoopRules.humanizeNetFailure("read TIMEOUT after 60s"))
    }

    @Test
    fun `人话化_认不出的走兜底`() {
        assertEquals("模型调用失败（原文已记日志）", RunLoopRules.humanizeNetFailure("SSL handshake aborted"))
    }

    @Test
    fun `人话化_空串也走兜底_不抛异常`() {
        assertEquals("模型调用失败（原文已记日志）", RunLoopRules.humanizeNetFailure(""))
    }

    @Test
    fun `人话化_优先级_403压过同段的429`() {
        // 一条原文同时含两个号段：先命中的赢（403 在 429 前面）
        assertEquals(
            "该通道拒绝访问（403）：Key 不对、没权限，或这条通道在你的网络里不通——换通道",
            RunLoopRules.humanizeNetFailure("HTTP 403：rate limited 429"),
        )
    }

    @Test
    fun `人话化_优先级_401压过429_429压过5xx`() {
        assertEquals(
            "该通道不认这把 Key（401）：检查 Key 是否填对，或换通道",
            RunLoopRules.humanizeNetFailure("401 unauthorized, 429"),
        )
        assertEquals(
            "模型端限流（429）：稍等再试，或换一个 provider",
            RunLoopRules.humanizeNetFailure("429 之后 HTTP 502"),
        )
    }

    // ── ④ 熔断问人二选一 ─────────────────────────────────────────────────

    @Test
    fun `熔断notice_没到换通道上限_说连续失败次数`() {
        assertEquals(
            "当前通道连续失败 3 次（已熔断）：换个通道或稍后再试",
            RunLoopRules.channelBrokenNotice(channelSwitches = 1, consecutiveChannelFailures = 3, step = 7),
        )
    }

    @Test
    fun `熔断notice_到换通道上限_说换过N次都不通并带停步`() {
        assertEquals(
            "同一轮已自动换过 2 次通道都不通：换个通道或稍后再试（已停在第 9 步）",
            RunLoopRules.channelBrokenNotice(channelSwitches = 2, consecutiveChannelFailures = 3, step = 9),
        )
    }

    @Test
    fun `熔断notice_超过上限也归到顶分支`() {
        assertEquals(
            "同一轮已自动换过 2 次通道都不通：换个通道或稍后再试（已停在第 12 步）",
            RunLoopRules.channelBrokenNotice(channelSwitches = 5, consecutiveChannelFailures = 3, step = 12),
        )
    }

    @Test
    fun `熔断notice_两个计数不串门_写反了会一本正经胡说`() {
        // 没换过通道（switches=0）但失败已到线：必须说「连续失败」，
        // 说成「换过 2 次」就是把两个计数器写反了
        val msg = RunLoopRules.channelBrokenNotice(channelSwitches = 0, consecutiveChannelFailures = 2, step = 4)
        assertTrue("没换过通道不许说换过：$msg", msg.startsWith("当前通道连续失败 2 次"))
        assertFalse("没换过通道不许出现「换过」：$msg", msg.contains("换过"))
    }

    // ── ⑤ 坏参数重试提醒 ─────────────────────────────────────────────────

    @Test
    fun `坏参数notice_序号是本次重试的序号`() {
        assertEquals("模型这轮给的参数不对，已让它重试（第 1 次）", RunLoopRules.badArgsRetryNotice(1))
        assertEquals("模型这轮给的参数不对，已让它重试（第 2 次）", RunLoopRules.badArgsRetryNotice(2))
    }

    // ── ⑥ 服务端截断标记 ─────────────────────────────────────────────────

    @Test
    fun `截断标记_两种协议各认各的`() {
        assertTrue(RunLoopRules.isTruncatedStopReason("length")) // OpenAI 系
        assertTrue(RunLoopRules.isTruncatedStopReason("max_tokens")) // Anthropic 系
    }

    @Test
    fun `截断标记_其余stop reason都不算`() {
        assertFalse(RunLoopRules.isTruncatedStopReason("stop"))
        assertFalse(RunLoopRules.isTruncatedStopReason("tool_calls"))
        assertFalse(RunLoopRules.isTruncatedStopReason(""))
        assertFalse(RunLoopRules.isTruncatedStopReason(null))
    }

    @Test
    fun `截断标记_大小写敏感_不猜`() {
        assertFalse("写个 Length 也算截断等于猜", RunLoopRules.isTruncatedStopReason("Length"))
        assertFalse(RunLoopRules.isTruncatedStopReason("MAX_TOKENS"))
    }

    // ── ⑦ run_log 的 ok 标记 ─────────────────────────────────────────────

    @Test
    fun `ok标记_正常出参与空串都算成功`() {
        assertTrue(RunLoopRules.toolExchangeOk("读到了 12 行"))
        assertTrue(RunLoopRules.toolExchangeOk(""))
    }

    @Test
    fun `ok标记_失败与被拒两种前缀算失败`() {
        assertFalse(RunLoopRules.toolExchangeOk("失败：文件不存在"))
        assertFalse(RunLoopRules.toolExchangeOk("被拒：工作区之外"))
    }

    @Test
    fun `ok标记_只判前缀_正文中间出现不算`() {
        assertTrue("正文里提到「失败」二字不算失败", RunLoopRules.toolExchangeOk("结果：失败 0 项，通过 3 项"))
    }
}
