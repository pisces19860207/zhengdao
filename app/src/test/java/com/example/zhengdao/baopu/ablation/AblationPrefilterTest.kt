// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十九批（2026-11-11）· 消融① 历史预过滤：免模型直测（用户裁定：本轮不真调模型）。
// 口径预登记在 `_zd-private/docs/experiments/559-消融纪律首轮/口径-预登记-2026-11-11.md` §二①——
// 同一段合成历史，两臂唯一差异 = ScaffoldSwitches.historySkipIds 的开关态；
// 其余 assemble 参数逐字相同。直测 render 字符数与估算 tokens（TokenEstimator 生产估算器口径，
// 不是 provider usage）。成功率/真实步数不在本轮：待钥匙（ZHENGDAO_EVAL_KEY）。
// 零网络、零临时目录；读数落盘仅在预登记目录已存在时覆写（内容全固定 ⇒ 幂等）。
package com.example.zhengdao.baopu.ablation

import com.example.zhengdao.baopu.ContextAssembler
import com.example.zhengdao.baopu.HardFacts
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.ScaffoldSwitches
import com.example.zhengdao.baopu.SegmentKind
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AblationPrefilterTest {

    @Test
    fun `历史预过滤两态_装配体积直测`() {
        val history = history()
        val compressedIds = history.take(COMPRESSED_COUNT).map { it.id }.toSet()

        val on = assembleArm(history, compressedIds, ScaffoldSwitches.historySkipIds(true, compressedIds))
        val off = assembleArm(history, compressedIds, ScaffoldSwitches.historySkipIds(false, compressedIds))

        // 机械断言（行为层）：开关改变装配体积与已压原文的可见性——谁跑都该一样的红绿。
        assertTrue("开态字符数应小于关态（${on.chars} vs ${off.chars}）", on.chars < off.chars)
        assertTrue("开态估算 tokens 应小于关态（${on.tokens} vs ${off.tokens}）", on.tokens < off.tokens)
        assertTrue("关态应仍含已压消息原文（预过滤没生效）", off.render.contains(history.first().text))
        assertTrue("开态不应再含已压消息原文", !on.render.contains(history.first().text))
        assertTrue("开态应含摘要替身", on.render.contains(SUMMARY))
        assertTrue("开态 HISTORY 可见条数应为 ${history.size - COMPRESSED_COUNT}，实为 ${on.historyVisible}",
            on.historyVisible == history.size - COMPRESSED_COUNT)
        assertTrue("关态 HISTORY 可见条数应为 ${history.size}，实为 ${off.historyVisible}",
            off.historyVisible == history.size)

        val lines = listOf(row("on", on), row("off", off))
        lines.forEach { println("[559-①] $it") }
        writeReadings("readings-559-prefilter.jsonl", lines)
    }

    // ── 两臂共用的组装（唯一差异由调用方传入 skipIds） ─────────────────────
    private fun assembleArm(
        history: List<HistoryMessage>,
        compressedIds: Set<String>,
        skipIds: Set<String>,
    ): Arm {
        val prompt = ContextAssembler.assemble(
            persona = emptyList(),
            facts = HardFacts(workspacePath = "/ws/559", model = "ablation-fake"),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = history,
            current = "继续",
            windowTokens = WINDOW,
            compressionSummary = SUMMARY,
            compressedIds = compressedIds,
            skipIds = skipIds,
        )
        val render = prompt.render()
        val historyText = prompt.segments.find { it.kind == SegmentKind.HISTORY }?.text.orEmpty()
        return Arm(
            chars = render.length,
            tokens = prompt.estimatedTokens,
            historyVisible = if (historyText.isEmpty()) 0 else historyText.lines().size,
            render = render,
        )
    }

    private data class Arm(val chars: Int, val tokens: Int, val historyVisible: Int, val render: String)

    private fun row(arm: String, a: Arm): String =
        """{"batch":559,"ablation":"history-prefilter","arm":"$arm","chars":${a.chars},""" +
            """"estimatedTokens":${a.tokens},"historyVisible":${a.historyVisible},""" +
            """"historyTotal":${HISTORY_COUNT},"compressedCount":$COMPRESSED_COUNT,"tokenRuler":"TokenEstimator"}"""

    // ── 固定输入（预登记 §二①：40 条 × 恰 120 字符；前 30 条已压） ──────────
    private fun history(): List<HistoryMessage> = (1..HISTORY_COUNT).map { i ->
        val id = "h%02d".format(i)
        val base = "消息$id 的固定正文，用于五五九批消融直测。"
        HistoryMessage(
            id = id,
            role = if (i % 2 == 1) HistoryMessage.ROLE_USER else HistoryMessage.ROLE_ASSISTANT,
            text = base + "填".repeat(MSG_CHARS - base.length),
        )
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
        const val HISTORY_COUNT = 40
        const val COMPRESSED_COUNT = 30
        const val MSG_CHARS = 120
        const val WINDOW = 8192
        const val SUMMARY =
            "目标：完成五五九批消融纪律首轮的免模型直测，验证历史预过滤开关对装配体积的影响。\n" +
                "已定的决定：两臂唯一差异是 skipIds；其余组装参数逐字相同；不真调模型。\n" +
                "已排除的路：以估算冒充实测读数；无 live 读数即删件。\n" +
                "未决：成功率与真实步数待钥匙（ZHENGDAO_EVAL_KEY）。\n" +
                "涉及的文件路径：docs/harness-audit-registry.md"
    }
}