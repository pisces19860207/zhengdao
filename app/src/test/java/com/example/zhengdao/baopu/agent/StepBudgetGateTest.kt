// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十九批 · 引擎接线门禁（房型照 P1EngineGateTest）：runLoop 要真 SQLite ＋ 网络
// （本仓无 Robolectric ⇒ 归真机），所以「接没接上」钉在源码结构上——判据出处、分支顺序、
// 禁用调用，逐条可 grep。判据本体（阈值/文案/哈希/段号）在 StepBudgetRulesTest 直测。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StepBudgetGateTest {

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    /** 引擎源码（剥注释后比对——注释里的旧字面不许参与断言）。 */
    private fun engineCode(): String {
        val raw = repoRel(enginePath).readText()
        return raw.lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    private fun repoRel(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 取「fromMarker 起到下一个 toMarker 止」的源码片（marker 本身从切片头跳过）。 */
    private fun fnBody(code: String, fromMarker: String, toMarker: String): String {
        val s = code.indexOf(fromMarker)
        assertTrue("找不到锚点：$fromMarker", s >= 0)
        val bodyStart = s + fromMarker.length
        val e = code.indexOf(toMarker, bodyStart)
        return code.substring(bodyStart, if (e >= 0) e else code.length)
    }

    // ── §1.1 重复调用熔断的接线 ──────────────────────────────────────────

    @Test
    fun `重复熔断在工具分支内_执行前_走问人通道`() {
        val code = engineCode()
        val i = code.indexOf("runToolCalls += outcome.calls.size")
        assertTrue("找不到重复熔断接线（计数行）", i >= 0)
        val branch = code.substring(i, code.indexOf("_state.value = RunState.ACTING", i))
        assertTrue("判据出自 RunRules", branch.contains("RunRules.repeatCallKey"))
        assertTrue("阈值出自 RunRules", branch.contains("RunRules.REPEAT_CALL_LIMIT"))
        assertTrue("文案出自 RunRules（四要素在纯判据层钉）", branch.contains("RunRules.repeatCallNotice"))
        assertTrue("走既有问人通道", branch.contains("EngineEvent.AskUser"))
        assertTrue("停轮要收尾落库", branch.contains("settle(sessionId, stepCount, RunState.ABORTED"))
    }

    @Test
    fun `重复计数_重跑同一步不涨`() {
        val code = engineCode()
        val i = code.indexOf("if (!rerunSameStep) {")
        assertTrue("重跑同一步的调用不许进重复计数（P1-3 同口径）", i >= 0)
        val block = code.substring(i, code.indexOf("_state.value = RunState.ACTING", i))
        assertTrue(block.contains("seenCallKeys"))
    }

    // ── §1.2 段边界：记账点不是同步点 ────────────────────────────────────

    @Test
    fun `段复盘在循环头_300整不再记`() {
        val code = engineCode()
        val i = code.indexOf("maybeSegmentCheckpoint(sessionId, stepCount, userText, history)")
        assertTrue("段边界记账必须在步循环头（跨段继续的记账点）", i >= 0)
        assertTrue(
            "记账行必须落在 reuseStep 消费之后（重跑不算新段）",
            code.indexOf("if (reuseStep) reuseStep = false else stepCount++") < i,
        )
        // 300 步整不再记（复盘日志必须恰 3 行）——判据钉在函数体里
        val fn = fnBody(code, "private fun maybeSegmentCheckpoint", "private fun accountNoProgress")
        assertTrue(fn.contains("RunRules.isStepExhausted(step)"))
    }

    @Test
    fun `段边界不许阻塞_负例`() {
        // §1.4 负例：「把复盘点做成阻塞等待 ⇒ 用例红」——记账函数体里不许出现等待/挂起/问人卡。
        val fn = fnBody(engineCode(), "private fun maybeSegmentCheckpoint", "private fun accountNoProgress")
        listOf("Thread.sleep", "runBlocking", ".await(", "askUser(", "confirmFlow.enter", "latch").forEach {
            assertFalse("段边界不许 $it（记账点不是同步点）", fn.contains(it))
        }
    }

    @Test
    fun `段提示整轮最多一次_且非阻塞通知`() {
        val fn = fnBody(engineCode(), "private fun maybeSegmentCheckpoint", "private fun accountNoProgress")
        assertTrue("旗标必须有（可整轮只问一次）", fn.contains("segmentCardShown = true"))
        assertTrue("提示走 notice（非阻塞），不出确认卡", fn.contains("_notice.value = msg"))
        assertFalse("记账点不许收尾（收尾=打断了 run）", fn.contains("settle("))
    }

    // ── §1.3 无进展熔断的接线 ────────────────────────────────────────────

    @Test
    fun `无进展记账在when收尾与坏参数重试两处`() {
        val code = engineCode()
        val exhaustedAt = code.indexOf("RunRules.isStepExhausted(stepCount))")
        assertTrue("找不到步数刹车", exhaustedAt >= 0)
        val atWhenEnd = code.lastIndexOf("if (accountNoProgress(sessionId, stepCount)) return", exhaustedAt)
        assertTrue("ToolCalls 落到底部要记账（步数刹车之前的那一处）", atWhenEnd >= 0)
        assertTrue("记账必须在步数刹车之前（无进展先停）", exhaustedAt > atWhenEnd)
        val badArgsAt = code.indexOf("accountNoProgress(sessionId, stepCount)", code.indexOf("badArgsRetries++"))
        assertTrue("坏参数重试也占一步（空步计入无进展）", badArgsAt >= 0)
    }

    @Test
    fun `无进展记账函数_空步与重复都计数_到线问人`() {
        val body = fnBody(engineCode(), "private fun accountNoProgress", "private fun drainInterjections")
        assertTrue("判据出自 RunRules", body.contains("RunRules.stepResultHash"))
        assertTrue("空步（无哈希）也计数", body.contains("hash.isEmpty()"))
        assertTrue("重复也计数", body.contains("hash in seenStepHashes"))
        assertTrue("新结果清零", body.contains("noProgressSteps = 0"))
        assertTrue("到线走问人通道", body.contains("EngineEvent.AskUser"))
        assertTrue("到线收尾落库", body.contains("settle(sessionId, step, RunState.ABORTED"))
    }

    @Test
    fun `结果哈希来自围栏前_三分支都入账`() {
        val code = engineCode()
        val line = "result.contentHash.takeIf { it.isNotEmpty() }?.let { stepResultTexts += it }"
        val n = Regex(Regex.escape(line)).findAll(code).count()
        assertTrue("结果哈希入账必须三处（Done/Failed/Rejected），实际 $n 处", n == 3)
    }

    // ── §1.4 插话：安全点注入 ＋ ≠ 打断 ──────────────────────────────────

    @Test
    fun `插话在循环头最前注入_先于任何组装`() {
        val code = engineCode()
        val loopTop = code.indexOf("while (!stopRequested) {")
        val drain = code.indexOf("drainInterjections(sessionId, history)", loopTop)
        assertTrue("找不到注入点", drain >= 0)
        val assembleAt = code.indexOf("val probe = assemble(", drain)
        assertTrue("注入必须先于下一步的组装（模型本步就看得见）", assembleAt > drain)
    }

    @Test
    fun `插话不碰停止_负例`() {
        // §1.4 负例：「插话 ≠ 打断」——注入路径不许写停止标志、不许取消在飞调用。
        val body = fnBody(engineCode(), "private fun drainInterjections", "private fun persistInterjection")
        listOf("stopRequested = true", "activeCall", "confirmFlow.abandon", "clearQueue").forEach {
            assertFalse("插话路径不许碰 $it（插话 ≠ 打断）", body.contains(it))
        }
    }

    @Test
    fun `插话API存在且有运行态闸`() {
        val code = engineCode()
        assertTrue("引擎必须有 interject 入口（176 的 UI 半调它）", code.contains("fun interject(text: String): Boolean"))
        val body = fnBody(code, "fun interject(text: String): Boolean", "private fun assemble(")
        assertTrue("没在跑必须拒收（调用方转普通消息，不许塞进没人消费的队列）", body.contains("return false"))
    }

    // ── §1.4 停止：新增的等待不许削弱随时可停 ────────────────────────────

    @Test
    fun `工具批之间仍查停止_未执行的不执行`() {
        val code = engineCode()
        val i = code.indexOf("for (stage in stages) {")
        assertTrue(i >= 0)
        // headCall 的取段 when 在 stop 检查之前 ⇒ 锚点要越过它
        val stopAt = code.indexOf("if (stopRequested) {", code.indexOf("when (stage) {", i))
        assertTrue("每个段开头必须查 stopRequested（工具调用中可停）", stopAt > i)
    }

    @Test
    fun `run级账本在复位口统一清`() {
        val code = engineCode()
        val body = fnBody(code, "private fun resetRunScopedCounters(sessionId: Long)", "onSessionEnter(sessionId)")
        listOf(
            "seenCallKeys.clear()",
            "seenStepHashes.clear()",
            "noProgressSteps = 0",
            "runToolCalls = 0",
            "lastLoggedSegment = 0",
            "segmentCardShown = false",
        ).forEach { a -> assertTrue("复位缺账本：$a", body.contains(a)) }
    }
}
