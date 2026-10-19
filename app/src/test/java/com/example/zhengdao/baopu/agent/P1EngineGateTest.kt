// 第五十四批 · 抱朴引擎 P1 三件（审计第二批 A 段）的门禁。
//
// 三条病灶都是「注释与实现打架」型：注释说「不涨步数」代码照涨、注释说「检查停止」
// 文本流却是盲区、坏参数被塞进「通道坏了」的通道。共同后果是**状态与日志撒谎**。
//
// 测试分两层：
//   ① 纯判据（RunRules.shouldRetryBadArgs）——JVM 直测；
//   ② 源码门禁——引擎 runLoop 要真 SQLite + 网络（本仓无 Robolectric ⇒ 归真机，
//      与 RunEngineTest 头部口径一致），所以把「接没接上」钉在源码结构上：
//      分支顺序、禁用调用、必须出现的调用，逐条可 grep。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class P1EngineGateTest {

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun engineCode(): String {
        val raw = File(enginePath).readText()
        return raw.lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    /** 取某个 `when` 分支的代码片（从 head 到下一个分支头为止）。 */
    private fun branchOf(code: String, head: String, nextHeads: List<String>): String {
        val i = code.indexOf(head)
        assertTrue("找不到分支头：$head", i >= 0)
        val rest = code.substring(i + head.length)
        val end = nextHeads.mapNotNull { h -> val j = rest.indexOf(h); if (j >= 0) j else null }.minOrNull()
            ?: rest.length
        return rest.substring(0, end)
    }

    // ── ① 纯判据 ────────────────────────────────────────────────────────

    @Test
    fun `坏参数重试判据_只给一次机会`() {
        assertTrue("第一次坏 ⇒ 还能重试", RunRules.shouldRetryBadArgs(0))
        assertFalse("第二次坏 ⇒ 不再试（如实收尾）", RunRules.shouldRetryBadArgs(1))
        assertFalse("第三次坏 ⇒ 更不试", RunRules.shouldRetryBadArgs(2))
    }

    // ── ② P1-1：流式期间点停止不许记 DONE ──────────────────────────────

    @Test
    fun `P1_1_Text分支先查停止_再收尾`() {
        val code = engineCode()
        val textBranch = branchOf(
            code,
            "is RoundOutcome.Text -> {",
            listOf("is RoundOutcome.ToolCalls -> {", "is RoundOutcome.BadArgs -> {", "is RoundOutcome.Failed -> {"),
        )
        val stopAt = textBranch.indexOf("if (stopRequested)")
        assertTrue("Text 分支里没有停止检查（流式期间点停止会照出 + 记 DONE）", stopAt >= 0)
        val doneAt = textBranch.indexOf("RunState.DONE")
        assertTrue("Text 分支里找不到 DONE 收尾", doneAt >= 0)
        assertTrue(
            "停止检查必须在 DONE 收尾**之前**（否则先记了 DONE 再判停止，日志照样撒谎）",
            stopAt < doneAt,
        )
        assertTrue("停止分支必须落 ABORTED", textBranch.contains("RunState.ABORTED"))
        assertTrue(
            "停止分支必须把已收到的正文留下（用户不丢已经打出来的字）",
            textBranch.contains("persistAssistantMessage(sessionId, outcome.text)"),
        )
    }

    @Test
    fun `P1_1_停止分支不许再走学习卡与计划提示`() {
        // 这一条要看**原文**（含注释）：停止块的边界用「下一个 usage 接线注释」认，
        // 剥了注释就找不到边界，会把整条分支都算进停止块（假红）。
        val raw = File(enginePath).readText()
        val textAt = raw.indexOf("is RoundOutcome.Text -> {")
        assertTrue("找不到 Text 分支", textAt >= 0)
        val stopAt = raw.indexOf("if (stopRequested) {", textAt)
        assertTrue("Text 分支里没有停止检查", stopAt >= 0)
        val blockEnd = raw.indexOf("// usage 接线（J13）", stopAt)
        assertTrue("找不到停止块的结尾（usage 接线注释）", blockEnd > stopAt)
        val stopBlock = raw.substring(stopAt, blockEnd)
        assertFalse(
            "停止分支里不许再 emitLearnCardIfDue（都停了还出「这次学到」卡）",
            stopBlock.contains("emitLearnCardIfDue"),
        )
        assertFalse(
            "停止分支里不许再提示「计划还有几条没勾」",
            stopBlock.contains("unfinishedCount"),
        )
        assertFalse(
            "停止分支里不许再跑收尾闸（verifyTouched/ArtifactVerifier）",
            stopBlock.contains("ArtifactVerifier"),
        )
    }

    // ── ② P1-2：坏参数独立成类，不走通道失败 ───────────────────────────

    @Test
    fun `P1_2_坏参数不再复用Failed通道`() {
        val code = engineCode()
        val badLine = code.lines().firstOrNull { it.contains("DecodeResult.Bad ->") }
        assertTrue("streamRound 里找不到 DecodeResult.Bad 的返回", badLine != null)
        assertTrue(
            "坏参数必须返回 RoundOutcome.BadArgs（复用 Failed 会让它记通道失败/熔断）",
            badLine!!.contains("RoundOutcome.BadArgs"),
        )
    }

    @Test
    fun `P1_2_BadArgs分支不碰通道熔断三件套`() {
        val code = engineCode()
        val branch = branchOf(
            code,
            "is RoundOutcome.BadArgs -> {",
            listOf("is RoundOutcome.Failed -> {"),
        )
        assertFalse("坏参数不许记通道失败（一次参数错不是网络坏）", branch.contains("consecutiveChannelFailures++"))
        assertFalse("坏参数不许触发换通道判定", branch.contains("channelDecision"))
        assertFalse("坏参数不许谎称 DONE", branch.contains("RunState.DONE"))
        assertTrue("坏参数要给一次重试（纯判据）", branch.contains("RunRules.shouldRetryBadArgs"))
        assertTrue("错误原文必须回灌进历史（只说「参数不对」模型改不对）", branch.contains("local-badargs"))
        assertTrue("第二次仍坏必须如实收尾 ABORTED", branch.contains("RunState.ABORTED"))
    }

    // ── ② P1-3：换通道不涨步数 ─────────────────────────────────────────

    @Test
    fun `P1_3_换通道置reuseStep且循环头跳过计数`() {
        val code = engineCode()
        assertTrue(
            "循环头必须按 reuseStep 决定是否 stepCount++（旧行为无条件 ++）",
            code.contains("if (reuseStep) reuseStep = false else stepCount++"),
        )
        val switchAt = code.indexOf("RunRules.ChannelDecision.SwitchTo ->")
        assertTrue("找不到换通道分支", switchAt >= 0)
        val switchBranch = code.substring(switchAt)
        val contAt = switchBranch.indexOf("continue")
        assertTrue("换通道分支里找不到 continue", contAt >= 0)
        val beforeContinue = switchBranch.substring(0, contAt)
        assertTrue(
            "continue 之前必须置 reuseStep = true（否则重跑同一步照涨预算）",
            beforeContinue.contains("reuseStep = true"),
        )
        assertTrue(
            "换通道的 persistStep 仍用当前 stepCount（不许写成新步）",
            beforeContinue.contains("persistStep(sessionId, stepCount, \"Switched\""),
        )
    }

    @Test
    fun `P1_3_别的continue不沾reuseStep`() {
        val code = engineCode()
        // 收尾闸重跑、坏参数重试都是**新的一步**，照计预算——不许被顺手改掉
        val verifyAt = code.indexOf("if (findings.isNotEmpty() && verifyRepairs < RunRules.MAX_VERIFY_REPAIRS)")
        assertTrue("找不到收尾闸分支", verifyAt >= 0)
        val verifyBranch = code.substring(verifyAt, code.indexOf("is RoundOutcome.Failed -> {").let { if (it > verifyAt) it else code.length })
        assertFalse("收尾闸重跑不许跳过计步（它是新的一步）", verifyBranch.contains("reuseStep"))
        val badBranch = branchOf(code, "is RoundOutcome.BadArgs -> {", listOf("is RoundOutcome.Failed -> {"))
        assertFalse("坏参数重试不许跳过计步（它是新的一步）", badBranch.contains("reuseStep"))
    }
}
