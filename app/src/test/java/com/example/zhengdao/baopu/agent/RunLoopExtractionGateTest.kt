// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十九批 · 结构拆分第八阶段：RunEngine.runLoop 切片的**源码门禁**（派工单 §2：
// 「RunEngine 对应逻辑只剩调用，不得留两份实现」）。
//
// 判据只吃**去注释行、保留字符串**的文本——G1/G3/G4/G5 钉代码形状（调用点、判据字面、
// 依赖禁令），G2 钉**字符串字面**（文案不许有第二份）。两条读法分工的原因（第七十九批
// 负例批 3 实测）：codeOnly 连字符串一起剥，用它查文案字面永远是空转——把 helper
// 调用还原成内联字面时 G2 一例都不会红。形状用 codeOnly、字面用 codeLines，
// 与 P1EngineGateTest 的 engineCode() 同款口径。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.CodeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunLoopExtractionGateTest {

    /** 去注释行、保留字符串（查文案字面用；与 codeOnly 分工见文件头）。 */
    private fun codeLines(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }
            .readText()
            .replace("\r\n", "\n")
            .lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /** 去注释与字符串（查代码形状用）。 */
    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }
            .readText()
            .replace("\r\n", "\n")
            .let { CodeText.codeOnly(it) }

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
    private val helper = "src/main/java/com/example/zhengdao/baopu/agent/RunLoopRules.kt"

    // ── G1：七处调用都在位（每个恰好一次） ────────────────────────────────

    @Test
    fun `G1_七处helper调用都在引擎里_各恰好一次`() {
        val src = code(engine)
        val calls = listOf(
            "RunLoopRules.verifyRepairNotice(",
            "RunLoopRules.textSettleNotice(",
            "RunLoopRules.humanizeNetFailure(",
            "RunLoopRules.channelBrokenNotice(",
            "RunLoopRules.badArgsRetryNotice(",
            "RunLoopRules.isTruncatedStopReason(",
            "RunLoopRules.toolExchangeOk(",
        )
        calls.forEach { c ->
            assertEquals("调用点必须恰好一处（少了＝抽取没落地，多了＝第二条链）：$c", 1, Regex(Regex.escape(c)).findAll(src).count())
        }
        assertEquals("RunLoopRules 引用总共就这七处", 7, Regex("RunLoopRules\\.").findAll(src).count())
    }

    // ── G2：搬走的字面在引擎里归零（没有第二份实现） ──────────────────────

    @Test
    fun `G2_被搬走的文案与判据字面在RunEngine归零`() {
        // 字面查字符串保留文本（codeLines）；两个代码形状用正则钉（字面量本身会被
        // codeOnly 剥掉，但 `stopReason == "length"` 这种**形状**不会）
        val src = codeLines(engine)
        val moved = listOf(
            "已让抱朴修一轮",                          // ① verifyRepairNotice
            "条没勾（进度看顶部计划卡）",                // ① textSettleNotice·计划臂
            "回复被输出上限截断了（服务端说的）",        // ① textSettleNotice·截断臂
            "这轮给的参数不对",                        // ③ badArgsRetryNotice
            "该通道拒绝访问（403）",                    // ② humanizeNetFailure 首臂
            "模型端限流（429）",                        // ② humanizeNetFailure
            "响应流中断：该轮作废重发",                  // ② humanizeNetFailure
            "同一轮已自动换过",                        // ③ channelBrokenNotice 到顶臂
            "当前通道连续失败",                        // ③ channelBrokenNotice 未到顶臂
        )
        moved.forEach { lit ->
            assertFalse("RunEngine 里还留着被搬走的字面（第二份实现）：$lit", src.contains(lit))
        }
        // 两个代码形状（字面量在字符串里，查形状不查内容）
        assertFalse(
            "stopReason == \"length\" 的比较又回引擎里了（isTruncatedStopReason 被绕开）",
            Regex("stopReason\\s*==\\s*\"length\"").containsMatchIn(src),
        )
        assertFalse(
            "wrapped.startsWith(\"失败\") 的判据又回引擎里了（toolExchangeOk 被绕开）",
            Regex("wrapped\\.startsWith\\(\"失败\"\\)").containsMatchIn(src),
        )
    }

    // ── G3：流判定字面仍在引擎里（P1 门禁没被绕开） ───────────────────────

    @Test
    fun `G3_停止与收尾闸的流判定仍按P1门禁留在引擎里`() {
        val src = code(engine)
        // P1EngineGateTest 钉的两处：Text 分支先查停止；收尾闸重修条件原文
        assertTrue("if (stopRequested) 必须仍在 Text 分支（P1-1）", src.contains("if (stopRequested)"))
        assertTrue(
            "收尾闸重修条件必须仍在引擎里（P1-3 的定位锚点）",
            src.contains("if (findings.isNotEmpty() && verifyRepairs < RunRules.MAX_VERIFY_REPAIRS)"),
        )
        // 收尾闸未过的那行日志也仍按 findings 非空才打（notice 搬走不等于日志搬走）
        assertTrue("收尾闸未过日志的条件仍在", src.contains("if (findings.isNotEmpty())"))
    }

    // ── G4：helper 零 Android/IO/线程/网络/存储依赖 ───────────────────────

    @Test
    fun `G4_helper不碰Android IO线程网络存储`() {
        val src = code(helper)
        val banned = listOf(
            "Context", "android.", "Compose", "File(", "Thread(", "thread {",
            "Dispatchers", "runBlocking", "LlmClient", "BaopuDb", "okhttp",
            "SharedPreferences", "JSONObject", "org.json", "System.currentTimeMillis",
        )
        banned.forEach { b ->
            assertFalse("helper 里不许出现 $b（纯判据不外挂）：$b", src.contains(b))
        }
        assertEquals("helper 零 import（同包直接用 RunRules）", 0, Regex("(?m)^import ").findAll(src).count())
    }

    // ── G5：helper 公开函数恰好七个，没有死函数 ───────────────────────────

    @Test
    fun `G5_helper公开函数恰好七个_与引擎调用一一对应`() {
        val src = code(helper)
        val fns = Regex("(?m)^    fun (\\w+)\\(").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(
            "helper 的公开函数应与 G1 的七处调用一一对应（多一个＝死函数）",
            listOf(
                "verifyRepairNotice",
                "textSettleNotice",
                "humanizeNetFailure",
                "channelBrokenNotice",
                "badArgsRetryNotice",
                "isTruncatedStopReason",
                "toolExchangeOk",
            ),
            fns,
        )
    }
}
