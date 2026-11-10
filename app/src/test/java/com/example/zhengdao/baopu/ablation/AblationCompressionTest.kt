// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十九批（2026-11-11）· 消融② 压卡摘要：免模型直测（用户裁定：本轮不真调模型）。
// 口径预登记在 `_zd-private/docs/experiments/559-消融纪律首轮/口径-预登记-2026-11-11.md` §二②——
// 假 provider 回放 30 步增长历史，两臂唯一差异 = ScaffoldSwitches.shouldRunCompression 的开关态；
// 预过滤两臂都开（隔离变量）。压缩真跑 CompressionRunner.runOnce，但 complete 是固定摘要假闭包
// （零网络零花费），store 是内存假件。看「什么时候触发压缩/装多少」；成功率/真实步数待钥匙。
// 零临时目录；读数落盘仅在预登记目录已存在时覆写（输入全固定 ⇒ 幂等）。
package com.example.zhengdao.baopu.ablation

import com.example.zhengdao.baopu.CompletionResult
import com.example.zhengdao.baopu.CompressionRecord
import com.example.zhengdao.baopu.ContextAssembler
import com.example.zhengdao.baopu.HardFacts
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.ScaffoldSwitches
import com.example.zhengdao.baopu.agent.CompressionRunner
import com.example.zhengdao.baopu.agent.CompressionStore
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AblationCompressionTest {

    @Test
    fun `压卡摘要两态_触发点与装配量直测`() {
        val on = replay(enabled = true)
        val off = replay(enabled = false)

        // 机械断言（行为层）：开关改变触发点与装配量——谁跑都该一样的红绿。
        assertTrue("关态应零触发（实为 ${off.fires}）", off.fires == 0)
        assertTrue("关态应零压缩调用（实为 ${off.completeCalls}）", off.completeCalls == 0)
        assertTrue("开态应至少触发一次", on.fires >= 1)
        assertTrue("开态首次触发步号应在 1..$STEPS（实为 ${on.firstFireStep}）", on.firstFireStep in 1..STEPS)
        assertTrue("开态触发几次就该调几次假模型（${on.fires} vs ${on.completeCalls}）",
            on.completeCalls == on.fires)
        assertTrue("末步开态估算 tokens 应小于关态（${on.finalTokens} vs ${off.finalTokens}）",
            on.finalTokens < off.finalTokens)
        assertTrue("关态估算 tokens 应逐步不降（历史只增不减）",
            off.steps.zipWithNext().all { (a, b) -> b.tokens >= a.tokens })
        assertTrue("开态应至少有一步装配量回落（压缩真生效）",
            on.steps.zipWithNext().any { (a, b) -> b.tokens < a.tokens })

        val lines = mutableListOf<String>()
        listOf("on" to on, "off" to off).forEach { (arm, r) ->
            r.steps.forEach { s ->
                lines += """{"batch":559,"ablation":"compression-prompt","arm":"$arm","step":${s.step},""" +
                    """"estimatedTokens":${s.tokens},"chars":${s.chars},"needsCompressing":${s.needs},""" +
                    """"fired":${s.fired},"compressedTotal":${s.compressedTotal}}"""
            }
            lines += """{"batch":559,"ablation":"compression-prompt","arm":"$arm","kind":"summary",""" +
                """"fires":${r.fires},"firstFireStep":${r.firstFireStep},"completeCalls":${r.completeCalls},""" +
                """"finalTokens":${r.finalTokens},"finalChars":${r.finalChars},"compressedTotal":${r.compressedTotal},""" +
                """"threshold":${r.threshold},"window":${r.window},"tokenRuler":"TokenEstimator"}"""
        }
        lines.forEach { println("[559-②] $it") }
        writeReadings("readings-559-compression.jsonl", lines)
    }

    // ── 单臂回放（照生产顺序：probe → 开关闸 → runOnce → 回种摘要/已压集合） ──
    private fun replay(enabled: Boolean): Replay {
        val provider = Provider(
            id = "ablation-fake",
            label = "消融假通道",
            protocol = LlmProtocol.OPENAI_COMPAT,
            baseUrl = "https://x/v1",
            model = "fake-model",
            contextWindow = WINDOW,
            maxOutput = 1024,
        )
        val window = ContextAssembler.ContextBudgets.windowOf(provider)
        val store = FakeStore()
        var completeCalls = 0
        val runner = CompressionRunner(
            complete = { _, _, _ ->
                completeCalls++
                CompletionResult.Ok(FAKE_SUMMARY, "fake-model")
            },
            store = store,
            log = { },
            now = { 0L },
        )
        val history = mutableListOf<HistoryMessage>()
        var summary: String? = null
        var compressedIds = emptySet<String>()
        var fires = 0
        var firstFireStep = -1
        val steps = mutableListOf<Step>()
        for (step in 1..STEPS) {
            history += msg(step * 2 - 1, HistoryMessage.ROLE_USER)
            history += msg(step * 2, HistoryMessage.ROLE_ASSISTANT)
            val oldCount = ContextAssembler.pendingOldMessageCount(history, compressedIds)
            val probe = ContextAssembler.assemble(
                persona = emptyList(),
                facts = HardFacts(workspacePath = "/ws/559", model = "fake-model"),
                stickyMemories = emptyList(),
                newMemories = emptyList(),
                history = history,
                current = "",
                windowTokens = window,
                compressionSummary = summary,
                compressedIds = compressedIds,
                skipIds = compressedIds,
                oldMessageCount = oldCount,
            )
            var fired = false
            if (ScaffoldSwitches.shouldRunCompression(enabled, probe.needsCompressing)) {
                fired = true
                fires++
                if (firstFireStep < 0) firstFireStep = step
                val outcome = runner.runOnce(
                    sessionId = 1L,
                    history = history,
                    provider = provider,
                    step = step,
                    skipIds = compressedIds,
                    previousSummary = summary,
                )
                if (outcome is CompressionRunner.Outcome.Ok) {
                    compressedIds = compressedIds + outcome.record.compressedIds
                    summary = outcome.record.summary
                }
            }
            // 「装多少」读的是压后装配（引擎压完会拿新摘要重新拼正式请求）；
            // 没压的那步 probe 就是正式装配，不重拼。
            val sent = if (fired) {
                ContextAssembler.assemble(
                    persona = emptyList(),
                    facts = HardFacts(workspacePath = "/ws/559", model = "fake-model"),
                    stickyMemories = emptyList(),
                    newMemories = emptyList(),
                    history = history,
                    current = "",
                    windowTokens = window,
                    compressionSummary = summary,
                    compressedIds = compressedIds,
                    skipIds = compressedIds,
                )
            } else {
                probe
            }
            steps += Step(step, sent.estimatedTokens, sent.render().length, probe.needsCompressing, fired, compressedIds.size)
        }
        val last = steps.last()
        return Replay(
            steps = steps,
            fires = fires,
            firstFireStep = firstFireStep,
            completeCalls = completeCalls,
            finalTokens = last.tokens,
            finalChars = last.chars,
            compressedTotal = last.compressedTotal,
            threshold = ContextAssembler.ContextBudgets.thresholdOf(provider),
            window = window,
        )
    }

    private data class Step(
        val step: Int,
        val tokens: Int,
        val chars: Int,
        val needs: Boolean,
        val fired: Boolean,
        val compressedTotal: Int,
    )

    private data class Replay(
        val steps: List<Step>,
        val fires: Int,
        val firstFireStep: Int,
        val completeCalls: Int,
        val finalTokens: Int,
        val finalChars: Int,
        val compressedTotal: Int,
        val threshold: Double,
        val window: Int,
    )

    /** 内存假件（照 CompressionRunnerTest.FakeStore 房型）：不落真库，只记账。 */
    private class FakeStore : CompressionStore {
        private val disk = mutableListOf<CompressionRecord>()
        override fun insert(sessionId: Long, record: CompressionRecord, sourceTokens: Int, summaryTokens: Int) {
            disk += record
        }
        override fun latest(sessionId: Long): CompressionRecord? = disk.lastOrNull()
        override fun lastFailedStep(sessionId: Long): Int? = null
        override fun markFailed(sessionId: Long, step: Int, code: Int, message: String) = Unit
        override fun markSkipped(sessionId: Long, step: Int, reason: String) = Unit
    }

    /** 固定输入（预登记 §二②：每步 +2 条 × 恰 600 字符）。 */
    private fun msg(i: Int, role: String): HistoryMessage {
        val id = "m%03d".format(i)
        val base = "消息$id 的固定正文，用于五五九批压卡摘要消融直测。"
        return HistoryMessage(id = id, role = role, text = base + "填".repeat(MSG_CHARS - base.length))
    }

    /** 读数落盘：预登记目录存在才写（CI 上不存在 ⇒ 只 println）；覆写幂等。 */
    private fun writeReadings(name: String, lines: List<String>) {
        val dir = System.getenv("ZHENGDAO_ABLATION_559_DIR")?.let { File(it) }
            ?: File(repoRoot().parentFile, "_zd-private/docs/experiments/559-消融纪律首轮")
        if (!dir.isDirectory) return
        File(dir, name).writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private companion object {
        const val STEPS = 30
        const val MSG_CHARS = 600
        const val WINDOW = 8192
        const val FAKE_SUMMARY =
            "目标：回放固定摘要（假模型产出，零网络）\n已定的决定：无\n已排除的路：无\n未决：无\n涉及的文件路径：无"
    }
}