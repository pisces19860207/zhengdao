// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十六批 · 偏差接线的**源级门禁**（行为判据见 DeviationDetectJourneyTest）。
// 照 DeviationLedgerWiringGateTest 的口径：只认代码行、注释不算、只扫生产——
// 把记账调用从判定处悄悄挪走，行为测试未必红（没偏差时账面本来就是空的），
// 只有盯着生产源码才看得见。
//
// 钉四件事（接线点与判定处的相对位置——位置错了账行就记到别的结局头上）：
//   1. 超步数：recordOverSteps 在步数刹车的 if 块**内**、且在 settle 之前；
//   2. 出处信箱：installRun 在 roundRunId 赋值之后（出处跟着真 runId）；clearRun 在
//      reflectOnSettled 的摘空处（收工后不许记给旧轮）；
//   3. 超时：recordTimeout 在 ToolRunner 的 TimeoutException 分支内（等满上限那一刻）；
//   4. 挂起超时：recordSuspendTimeout 在 ConfirmFlow 的 null verdict 分支内、
//      返回 Outcome.TimedOut 之前（判定与记账同处）。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviationWiringGateTest {

    /** Gradle 单测的工作目录是 `app/`，仓库根取法与 DeviationLedgerWiringGateTest 同款。 */
    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun sourceOf(rel: String): String {
        val f = repoFile(rel)
        require(f.isFile) { "$rel 没找到：${f.absolutePath}" }
        return codeOnly(f.readText())
    }

    /** 剥注释（行注释＋块注释，KDoc 属块注释）；剥完的才是"代码行"。 */
    private fun codeOnly(src: String): String {
        val out = StringBuilder()
        var i = 0
        var inBlock = false
        var inString = false
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (inBlock) {
                if (two == "*/") { inBlock = false; i += 2 } else i += 1
                continue
            }
            if (inString) {
                when (src[i]) {
                    '\\' -> i += 2
                    '"' -> { inString = false; i += 1 }
                    else -> i += 1
                }
                continue
            }
            when (two) {
                "/*" -> { inBlock = true; i += 2 }
                "//" -> { while (i < src.length && src[i] != '\n') i += 1 }
                "\"" -> { inString = true; out.append(src[i]); i += 1 }
                else -> { out.append(src[i]); i += 1 }
            }
        }
        return out.toString()
    }

    private val runEngine by lazy { sourceOf("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt") }
    private val guardPipeline by lazy { sourceOf("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt") }
    private val toolScheduling by lazy { sourceOf("src/main/java/com/example/zhengdao/baopu/agent/ToolScheduling.kt") }
    private val confirmFlow by lazy { sourceOf("src/main/java/com/example/zhengdao/baopu/agent/ConfirmFlow.kt") }

    @Test
    fun `超步数记账在步数刹车块内且在收尾之前`() {
        val brake = runEngine.indexOf("if (RunRules.isStepExhausted(stepCount)) {")
        assertTrue("步数刹车判定没找到", brake >= 0)
        val record = runEngine.indexOf("DeviationWiring.recordOverSteps(", brake)
        assertTrue("刹车块里没有落账调用", record >= 0)
        // 刹车块内：落账必须在块收尾（settle 那行）之前——位置错了就是记给别的结局。
        val settleInBlock = runEngine.indexOf("settle(sessionId, stepCount, RunState.DONE", brake)
        assertTrue("刹车块内的 settle 没找到", settleInBlock >= 0)
        assertTrue("落账必须先于 settle", record < settleInBlock)
    }

    @Test
    fun `出处信箱_起轮装_收工清`() {
        val newRun = runEngine.indexOf("roundRunId = trace.newRun(")
        val install = runEngine.indexOf("DeviationWiring.installRun(")
        val clear = runEngine.indexOf("DeviationWiring.clearRun()")
        assertTrue("newRun 没找到", newRun >= 0)
        assertTrue("起轮没装出处信箱", install >= 0)
        assertTrue("收工没清出处信箱", clear >= 0)
        assertTrue("出处必须在真 runId 诞生之后装", install > newRun)
        // 摘空处：clearRun 与 roundRunId = "" 同在 reflectOnSettled，且在摘空之后。
        val blank = runEngine.indexOf("roundRunId = \"\"")
        assertTrue("摘空处没找到", blank >= 0)
        assertTrue("清信箱必须在摘空之后（同一收工路）", clear > blank)
    }

    @Test
    fun `超时记账在串行超时分支内`() {
        val catch = guardPipeline.indexOf("catch (e: TimeoutException)")
        assertTrue("串行超时分支没找到", catch >= 0)
        val record = guardPipeline.indexOf("DeviationWiring.recordTimeout(", catch)
        assertTrue("TimeoutException 分支里没有落账调用", record >= 0)
        val timeoutFailure = guardPipeline.indexOf("timeoutFailure(spec)", catch)
        assertTrue("timeoutFailure 收口没找到", timeoutFailure >= 0)
        assertTrue("落账必须在收口之前", record < timeoutFailure)
    }

    @Test
    fun `并行超时记账在到点未回收口处`() {
        // 并行路超时收口的两条腿共用一个落账点：ownDeadline 算出来之后。
        val marker = toolScheduling.indexOf("val ownDeadline = slot.submittedAtMs + timeoutOf(slot.call)")
        assertTrue("并行收口没找到", marker >= 0)
        val record = toolScheduling.indexOf("DeviationWiring.recordTimeout(", marker)
        assertTrue("到点未回的收口处没有落账调用", record >= 0)
    }

    @Test
    fun `挂起超时记账在超时分支内且在TimedOut之前`() {
        val branch = confirmFlow.indexOf("DeviationWiring.recordSuspendTimeout(")
        assertTrue("挂起超时没有落账调用", branch >= 0)
        val timedOut = confirmFlow.indexOf("Outcome.TimedOut(", branch)
        assertTrue("TimedOut 结局没找到", timedOut >= 0)
        assertTrue("落账必须在返回 TimedOut 之前", branch < timedOut)
    }
}
