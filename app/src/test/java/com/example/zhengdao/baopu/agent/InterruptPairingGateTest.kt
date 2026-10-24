// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.2：三条中断路径的「配对」单测（2026-10-24）。
//
// 口径（派工单 19:5x 更正后）：我们的历史是纯文本，**结构上不存在**孤儿 tool_use——
// 工具结果以围栏文本回灌，模型侧的工具调用从不落历史。本组用例把三条中断路径
// （用户点停止 / 流超时 / STOP_RUN）各钉一遍：
//   ① 落进历史的 tool_use 必有配对，或者干脆没落（源码结构判据）；
//   ② 续跑回放（HistoryCodec.decode）出来的历史必须过 §1.1 的形状门禁；
//   ③ 负例：往回放行里插一条原生块（将来某人的「保真」改法）⇒ 三条路全红
//      ——这就是「把 1.1 的门禁关掉 ⇒ 至少一条红」的机器证明。
//
// 为什么是源码门禁＋回放层：RunEngine 要真 SQLite + 网络，JVM 里起不来
// （与 P1EngineGateTest / BadArgsFeedbackTest 同口径），「接没接上」钉在源码结构上，
// 「历史长什么样」用真回放链（HistoryCodec + HistoryShapeGate）钉。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.HistoryShapeGate
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptPairingGateTest {

    private val enginePath = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun engineCode(): String = File(enginePath).readText().lines()
        .filterNot { l ->
            val t = l.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun branchOf(code: String, head: String, nextHeads: List<String>): String {
        val i = code.indexOf(head)
        assertTrue("找不到分支头：$head", i >= 0)
        val rest = code.substring(i + head.length)
        val end = nextHeads.mapNotNull { h -> rest.indexOf(h).takeIf { it >= 0 } }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    // ── 回放层夹具（DB 行的原始形状，见 persistToolExchange/persistAssistantMessage）──

    private fun userRow(id: String, text: String) =
        HistoryRow(id, HistoryMessage.ROLE_USER, "text", text)

    private fun assistantTextRow(id: String, text: String) =
        HistoryRow(id, HistoryMessage.ROLE_ASSISTANT, "text", text)

    /** Done 行：落库存的就是围栏后的文本（在线回灌同形）。 */
    private fun toolDoneRow(id: String, tool: String, step: Int, result: String) = HistoryRow(
        id, HistoryMessage.ROLE_ASSISTANT, "tool",
        JSONObject().put("tool", tool).put("step", step).put("result", result).toString(),
    )

    /** 失败行：落库存裸文本（解码时补包围栏）。 */
    private fun toolFailedRow(id: String, tool: String, step: Int) = HistoryRow(
        id, HistoryMessage.ROLE_ASSISTANT, "tool",
        JSONObject().put("tool", tool).put("step", step).put("result", "失败：文件不存在").toString(),
    )

    /** 回放并过形状门禁（空表 = 干净）。 */
    private fun replayViolations(rows: List<HistoryRow>): List<String> {
        val decoded = HistoryCodec.decode(rows)
        return HistoryShapeGate.replayHistoryViolations(decoded)
    }

    // ── 路径一：用户点停止（工具批执行中）─────────────────────────────

    @Test
    fun `路径一_用户点停止_未执行的调用不落任何历史或账`() {
        val code = engineCode()
        // 工具批循环里的停止检查：置位 ⇒ abortBeforeCall ⇒ 直接 return
        val headAt = code.indexOf("val headCall = when (stage) {")
        assertTrue("找不到工具批循环的段头", headAt >= 0)
        val stopAt = code.indexOf("if (stopRequested) {", headAt)
        assertTrue("工具批循环里没有停止检查", stopAt >= 0)
        val stopBlock = code.substring(stopAt, code.indexOf("when (stage) {", stopAt))
        assertTrue("停止时必须先 abortBeforeCall（如实记 CANCELLED）", stopBlock.contains("abortBeforeCall(sessionId"))
        assertTrue("abort 之后必须当场 return（批里剩下的不再执行）", stopBlock.contains("return"))

        // abortBeforeCall 本体：只记轨迹＋收尾，**不许碰历史与工具账**
        val abortAt = code.indexOf("private fun abortBeforeCall(")
        assertTrue("找不到 abortBeforeCall", abortAt >= 0)
        val abortBody = code.substring(abortAt, code.indexOf("private fun handleSerialCall(", abortAt))
        assertTrue("abort 必须记 CANCELLED 轨迹", abortBody.contains("TraceStatus.CANCELLED"))
        assertFalse("被停掉的未执行调用不许落历史（落了就是没有配对的 tool_use）", abortBody.contains("history +="))
        assertFalse("被停掉的未执行调用不许落工具账", abortBody.contains("persistToolExchange"))
    }

    @Test
    fun `路径一_落了结果的必有执行过的调用_三个结果分支都成对`() {
        val code = engineCode()
        // Done / Failed / Rejected 三个分支：每个都同时 persistToolExchange（账）＋
        // history +=（回灌）⇒ 结果落历史 ⇔ 调用真执行过（配对是结构保证，不是自觉）
        val heads = listOf(
            "is GuardPipeline.Result.Done -> {",
            "is GuardPipeline.Result.Failed -> {",
            "is GuardPipeline.Result.Rejected -> {",
        )
        val bounds = heads.drop(1) + listOf("is GuardPipeline.Result.NeedsConfirm -> {")
        for ((head, next) in heads.zip(bounds)) {
            val branch = branchOf(code, head, listOf(next))
            assertTrue("$head 必须落工具账（配对的一边）", branch.contains("persistToolExchange("))
            assertTrue("$head 必须回灌进历史（配对的另一边）", branch.contains("history += HistoryMessage("))
        }
    }

    @Test
    fun `路径一_回放干净_执行过的结果与未执行的调用各得其所`() {
        // 停止落在第二个调用之前：第一个（write_file）执行过 ⇒ 它的结果在历史里；
        // 第二个（read_file）没执行 ⇒ 历史里什么也不落。回放必须干净。
        val rows = listOf(
            userRow("u1", "把第一行改成 X，然后读一下"),
            assistantTextRow("a1", "好，我先改再读"),
            toolDoneRow("t1", "write_file", 1, "<tool_result name=\"write_file\">已覆盖 x.txt（2 行）</tool_result>"),
        )
        assertEquals("停止路径的回放历史必须干净", emptyList<String>(), replayViolations(rows))
        val decoded = HistoryCodec.decode(rows)
        assertEquals("未执行的 read_file 不许出现在历史里", 0, decoded.count { it.text.contains("read_file") })
    }

    @Test
    fun `路径一_负例_谁在停止路径落一条原生块_回放即红`() {
        val rows = listOf(
            userRow("u1", "把第一行改成 X"),
            // 将来某人的「保真」改法：把模型那轮没执行完的 tool_use 原样落进历史
            assistantTextRow(
                "a1",
                """{"type":"tool_use","id":"toolu_1","name":"read_file","input":{"path":"a.txt"}}""",
            ),
        )
        assertTrue("原生块必须被形状门禁抓到（门禁关掉 ⇒ 本路红）", replayViolations(rows).isNotEmpty())
    }

    // ── 路径二：流超时（STREAM_WAIT_TIMEOUT_MS）───────────────────────

    @Test
    fun `路径二_流超时_在解码之前就作废_没有任何工具调用落地`() {
        val code = engineCode()
        val streamAt = code.indexOf("private fun streamRound(")
        assertTrue("找不到 streamRound", streamAt >= 0)
        val body = code.substring(streamAt, code.indexOf("private fun toolSummary(", streamAt))
        val waitAt = body.indexOf("latch.await(RunRules.STREAM_WAIT_TIMEOUT_MS")
        assertTrue("streamRound 必须有有界等待", waitAt >= 0)
        val timeoutAt = body.indexOf("if (!arrived) {", waitAt)
        assertTrue("等待超时必须按失败处理", timeoutAt >= 0)
        val timeoutBlock = body.substring(timeoutAt, body.indexOf("failure?.let {", timeoutAt))
        assertTrue("超时必须砍掉底层 Call", timeoutBlock.contains("call.cancel()"))
        assertTrue("超时必须返回 Failed（本轮作废，不假装成功）", timeoutBlock.contains("RoundOutcome.Failed("))
        // 关键：解码在超时判定**之后** ⇒ 超时那一轮 rawCalls 根本没被 decode 过
        val decodeAt = body.indexOf("ToolCallCodec.decode(")
        assertTrue("streamRound 里找不到解码点", decodeAt >= 0)
        assertTrue("解码必须排在超时作废之后（否则超时轮的工具调用会落地）", timeoutAt < decodeAt)
    }

    @Test
    fun `路径二_Failed分支不落任何工具历史或账`() {
        val code = engineCode()
        val branch = branchOf(code, "is RoundOutcome.Failed -> {", listOf("if (RunRules.isStepExhausted(stepCount))"))
        assertFalse("流超时（Failed）不许落工具账——这轮没有任何工具执行过", branch.contains("persistToolExchange"))
        assertFalse("流超时（Failed）不许往历史里写工具结果", branch.contains("local-tool"))
    }

    @Test
    fun `路径二_回放干净_超时那一轮什么也没落`() {
        val rows = listOf(
            userRow("u1", "读一下 a.txt"),
            assistantTextRow("a1", "好，我来读"), // 上一轮的正文；本轮超时 ⇒ 无新行
        )
        assertEquals("流超时后的回放历史必须干净", emptyList<String>(), replayViolations(rows))
    }

    @Test
    fun `路径二_负例_谁把超时轮的工具调用落历史_回放即红`() {
        // 原生块以**文本消息**落进历史（围栏只包工具结果，包不住这种「保真」改法）
        val rows = listOf(
            userRow("u1", "读一下 a.txt"),
            assistantTextRow("a1", """{"functionCall":{"name":"read_file","args":{"path":"a.txt"}}}"""),
        )
        assertTrue("原生块必须被形状门禁抓到（门禁关掉 ⇒ 本路红）", replayViolations(rows).isNotEmpty())
    }

    // ── 路径三：STOP_RUN（三个产出点）────────────────────────────────

    @Test
    fun `路径三_未经批准的NeedsConfirm_不执行不落历史`() {
        val code = engineCode()
        val branch = branchOf(
            code,
            "is GuardPipeline.Result.NeedsConfirm -> {",
            listOf("private fun laneOfCall("),
        )
        assertTrue("必须记日志（负例演练的靶心）", branch.contains("log("))
        assertTrue("必须返回 STOP_RUN", branch.contains("ToolResultFlow.STOP_RUN"))
        assertFalse("未经批准的 NeedsConfirm 不许落历史（没执行就没有配对的另一边）", branch.contains("history +="))
        assertFalse("未经批准的 NeedsConfirm 不许落工具账", branch.contains("persistToolExchange"))
    }

    @Test
    fun `路径三_工具失败到线_失败结果先落_再STOP_RUN_顺序即配对`() {
        val code = engineCode()
        val branch = branchOf(
            code,
            "is GuardPipeline.Result.Failed -> {",
            listOf("is GuardPipeline.Result.Rejected -> {"),
        )
        val persistAt = branch.indexOf("persistToolExchange(")
        val historyAt = branch.indexOf("history += HistoryMessage(")
        val stopAt = branch.indexOf("ToolResultFlow.STOP_RUN")
        assertTrue("失败分支必须落工具账", persistAt >= 0)
        assertTrue("失败分支必须回灌历史", historyAt >= 0)
        assertTrue("失败分支必须能走到 STOP_RUN", stopAt >= 0)
        assertTrue("失败结果必须先落（账＋历史）再 STOP_RUN——落了的必有执行过的调用", persistAt < stopAt && historyAt < stopAt)
    }

    @Test
    fun `路径三_确认超时_调用没执行_只落一句人话不落工具账`() {
        val code = engineCode()
        val branch = branchOf(
            code,
            "is ConfirmFlow.Outcome.TimedOut -> {",
            listOf("is ConfirmFlow.Outcome.Ignored ->"),
        )
        assertTrue("超时必须给用户一句人话", branch.contains("persistAssistantMessage(sessionId, oc.note)"))
        assertTrue("超时必须收尾 ABORTED", branch.contains("RunState.ABORTED"))
        assertTrue("超时必须 STOP_RUN", branch.contains("ToolResultFlow.STOP_RUN"))
        assertFalse("调用没执行过 ⇒ 不许落工具账（没有配对的另一边）", branch.contains("persistToolExchange"))
    }

    @Test
    fun `路径三_回放干净_失败到线的结果与它的调用同在`() {
        // 工具执行过但失败到线：失败结果落历史（配对边就是那次执行），回放干净。
        val rows = listOf(
            userRow("u1", "把第一行改成 X"),
            toolFailedRow("t1", "write_file", 1),
        )
        val v = replayViolations(rows)
        assertEquals("失败到线后的回放历史必须干净", emptyList<String>(), v)
        val decoded = HistoryCodec.decode(rows)
        assertTrue("失败结果要补包围栏（与在线同形）", decoded.last().text.startsWith("<tool_result name=\"write_file\""))
    }

    @Test
    fun `路径三_负例_谁在STOP_RUN前补一条原生tool_result_回放即红`() {
        val rows = listOf(
            userRow("u1", "把第一行改成 X"),
            assistantTextRow(
                "a1",
                """{"type":"tool_result","tool_use_id":"toolu_1","content":"文件内容"}""",
            ),
        )
        assertTrue("原生块必须被形状门禁抓到（门禁关掉 ⇒ 本路红）", replayViolations(rows).isNotEmpty())
    }
}
