// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十四批 · 注入规则与执行器的 JVM 直测（**只对 debug 变体编译**）。
//
// 为什么放在 `src/testDebug`：被测类在 `src/debug`，release 变体的单测编不到它
// （那正是"release 里没有注入"的结构保证）。`testDebugUnitTest` 会带上这个源集。
//
// 判据落点：
//   · 规则解析：坏行必须**跳过并报错**，不许猜（判据①的前提：注入的失败码必须是真码）；
//   · 第 N 次 / 参数模式 / 次数上限：三种命中方式各一条；
//   · 失败原文＝`ToolErrCodes` 的固定短句 ⇒ 归因层认得出「行为类」（判据②的入口）。
package com.example.zhengdao.debug

import com.example.zhengdao.baopu.FailureOwner
import com.example.zhengdao.baopu.ToolFailureAttribution
import com.example.zhengdao.baopu.agent.ToolErrCodes
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultInjectionRulesTest {

    private fun args(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    @Test
    fun 解析五行格式与通配工具() {
        val rules = FaultRuleParser.parse(
            """
            # 注释行
            read_file | 0 | 3 | protocol |

            * | 2 | 1 | timeout | "path":"资料库/
            """.trimIndent(),
        )
        assertEquals(2, rules.size)
        assertEquals("read_file", rules[0].tool)
        assertEquals(0, rules[0].nth)
        assertEquals(3, rules[0].limit)
        assertEquals("protocol", rules[0].code)
        assertNull(rules[0].argsPattern)
        assertEquals(FaultRule.WILDCARD, rules[1].tool)
        assertEquals(2, rules[1].nth)
        assertEquals("timeout", rules[1].code)
        assertEquals("\"path\":\"资料库/", rules[1].argsPattern?.pattern)
    }

    @Test
    fun 坏行跳过并逐行报错() {
        val bad = mutableListOf<String>()
        val rules = FaultRuleParser.parse(
            """
            read_file | 0 | 3 | protocol |
            read_file | 0 | 3 |
            read_file | x | 3 | protocol |
            read_file | 0 | 3 | not-a-code |
            read_file | 0 | 3 | protocol | [
            """.trimIndent(),
        ) { line, why -> bad += "L$line: $why" }
        assertEquals("坏行一条都不许进规则表", 1, rules.size)
        assertEquals("四条坏行各报一次", 4, bad.size)
        assertTrue("要指出是第 2 行", bad.any { it.startsWith("L2:") })
        assertTrue("未知码要列可用码", bad.any { it.contains("ToolErrCodes") })
        assertTrue("坏正则需要报出来", bad.any { it.contains("argsRegex") })
    }

    @Test
    fun 码必须来自共享码表() {
        val rules = FaultRuleParser.parse("read_file | 0 | 0 | PROTOCOL |")
        assertEquals("大小写归一后必须认得出", "protocol", rules.single().code)
        val outcome = FaultInjector(rules).intercept("read_file", args("path" to "/tmp/x"))
        val error = (outcome as ToolOutcome.Failed).error
        assertEquals(
            "失败原文就是码表那一句（归因层靠它认码）",
            ToolErrCodes.byCode("protocol")!!.line,
            error,
        )
    }

    // ── 命中规则 ────────────────────────────────────────────────────────────

    @Test
    fun 每次命中都注入且受次数上限约束() {
        val injector = FaultInjector(FaultRuleParser.parse("read_file | 0 | 3 | protocol |"))
        val hits = (1..5).map { injector.intercept("read_file", args("path" to "/tmp/x")) != null }
        assertEquals("前三次失败、之后放行", listOf(true, true, true, false, false), hits)
    }

    @Test
    fun 只在第N次命中时注入() {
        val injector = FaultInjector(FaultRuleParser.parse("read_file | 2 | 1 | timeout |"))
        val hits = (1..4).map { injector.intercept("read_file", args("path" to "/tmp/x")) != null }
        assertEquals("只有第 2 次失败", listOf(false, true, false, false), hits)
    }

    @Test
    fun 参数模式只匹配参数文本() {
        // 模式取「路径里的一段」而不是整串：参数 JSON 里前面还有 /tmp/，整串匹配是另一种规则。
        val injector = FaultInjector(
            FaultRuleParser.parse("write_file | 0 | 0 | protocol | 资料库/原始"),
        )
        assertNull(
            "参数不含那段 ⇒ 不注入",
            injector.intercept("write_file", args("path" to "/tmp/别的.txt", "content" to "x")),
        )
        assertTrue(
            "参数含那段 ⇒ 注入",
            injector.intercept("write_file", args("path" to "/tmp/资料库/原始/a.txt")) != null,
        )
    }

    @Test
    fun 别的工具不受影响() {
        val injector = FaultInjector(FaultRuleParser.parse("read_file | 0 | 0 | protocol |"))
        assertNull(injector.intercept("list_dir", args("path" to "/tmp")))
    }

    // ── 与归因层的接口 ──────────────────────────────────────────────────────

    @Test
    fun 注入的失败进归因层判行为类() {
        val injector = FaultInjector(FaultRuleParser.parse("read_file | 0 | 3 | protocol |"))
        val outcome = injector.intercept("read_file", args("path" to "/tmp/x"))
        val error = (outcome as ToolOutcome.Failed).error
        val attribution = ToolFailureAttribution.classify(error)
        assertEquals("protocol 码归行为类（判据②要的那一类）", FailureOwner.BEHAVIOR, attribution.owner)
        assertTrue("根因键要稳定（同轮限一次靠它）", attribution.rootCause.startsWith("BEHAVIOR|"))
        assertTrue("行为类才允许提约束卡", ToolFailureAttribution.mayProposeRule(attribution))
    }

    @Test
    fun 环境类码不会误判成行为类() {
        val injector = FaultInjector(FaultRuleParser.parse("read_file | 0 | 1 | timeout |"))
        val error = (injector.intercept("read_file", args()) as ToolOutcome.Failed).error
        val attribution = ToolFailureAttribution.classify(error)
        assertEquals(FailureOwner.ENVIRONMENT, attribution.owner)
        assertFalse("环境类不许提约束卡", ToolFailureAttribution.mayProposeRule(attribution))
    }
}
