// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§1「子 Agent：独立上下文/
// 工具白名单/超时」与《第五百三十一批 · 子智能体 v2》四件（上下文隔离/白名单/
// 账归属/回滚粒度）；开工闸门《多Agent协作v1-协作模型-2026-10-21.md》§3/§6。
//
// 定位：**单条**子任务的有界小循环。不建线程、不管并发——并发与截止归
// [SubAgentCoordinator]，本类只管「这一条怎么从开始跑到终态」。
// 判据全部可注：turn（模型一轮）、toolExec（工具执行）、now（时钟）、
// isCancelled（父任务停止观测）、ledger（账）、beforeWrite（写前入库）——
// JVM 单测喂假件即可跑完全部路径。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.agent.DataFence
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONArray

/** 跑一条子任务要的外围件（生产实现由装配层注入；测试注假件）。 */
internal data class SubAgentEnv(
    /** 模型一轮。 */
    val turn: SubAgentTurn,
    /** 执行一次工具调用（白名单已在 [SubAgentRunner] 判过，这层只管真执行）。 */
    val toolExec: (ToolCall) -> ToolOutcome,
    /** 本轮要发给模型的 tools 数组（白名单内的 spec 序列化；空名单传 null）。 */
    val toolsJson: JSONArray?,
    /** 时钟（测试可拨）。 */
    val now: () -> Long = System::currentTimeMillis,
    /** 父任务是否已被用户停止（轮次边界观测；见协作模型 §6）。 */
    val isCancelled: () -> Boolean = { false },
    /** 日志（生产走 RunLog；测试收集断言）。 */
    val log: (String) -> Unit = {},
    /**
     * 父上下文此刻的 token 估算（第五百三十一批 ①）。
     *
     * 缺省 [Int.MAX_VALUE] ＝「不裁」（v1 单测与「父未知」同形）；生产传
     * `run_log.tokens_in` 的最近读数——子上下文按它的 1/3 收。
     */
    val parentTokens: Int = Int.MAX_VALUE,
    /** 这个工具是不是写类（决定要不要在调用前入库；缺省全读）。 */
    val isWrite: (String) -> Boolean = { false },
    /** 写类调用前的入库动作（生产＝470 保险箱；缺省不做事）。 */
    val beforeWrite: (ToolCall) -> Unit = {},
    /** 子 Agent 账（第五百三十一批 ③：每条带 agent_id＋parent_id）。 */
    val ledger: (SubAgentLedgerRow) -> Unit = {},
)

/**
 * 单条子任务的执行器（有界小循环）。
 *
 * 边界（派工单 §1）：
 * - 步数 ≤ [SubTask.maxSteps]（默认 16）：用完即「失败：步数用尽」，不无限续；
 * - 墙钟 ≤ [SubTask.timeoutMs]（默认 90s，钳制 10–300s）：每轮开头查，超时即止；
 * - 取消：每轮开头 + 每次工具调用前查 [SubAgentEnv.isCancelled] ⇒ CANCELLED；
 * - 工具结果按父引擎同款回灌（[DataFence] 围栏 ＋ **user** 角色进历史；不发 assistant，见 UI-D10）；
 * - 白名单（v2）：[SubAgentWhitelist.decide] 先判，名单外/递归/未授权写一律拒并落 REJECT 账。
 */
internal class SubAgentRunner(private val env: SubAgentEnv) {

    /**
     * 跑一条子任务，返回终态结果（**不抛异常**：任何意外都收成 FAILED 带原因）。
     *
     * @param task 已收敛过的子任务（白名单/超时/步数都是生效值）
     */
    fun run(task: SubTask): SubTaskResult {
        val startedAt = env.now()
        var steps = 0
        // 第一百八十一批 #5：各轮 usage 合计（null=从没读到；有一轮读到就开账）
        var tokensIn: Long? = null
        var tokensOut: Long? = null
        // 第五百三十一批 ①：开局只有「目标＋片段＋约束」，按父预算裁剪（不含父历史）
        val messages = mutableListOf<LlmMessage>()
        messages += SubAgentIsolation.seed(task, env.parentTokens)
        // 同一工具同一参数调了几次（≥3 即提前收口；见 playCalls 注释）。
        val seenCalls = mutableMapOf<String, Int>()
        fun tapUsage(u: com.example.zhengdao.baopu.LlmUsage?) {
            if (u == null) return
            tokensIn = (tokensIn ?: 0L) + (u.promptTokens ?: 0)
            tokensOut = (tokensOut ?: 0L) + (u.completionTokens ?: 0)
        }
        try {
            while (steps < task.maxSteps) {
                if (env.isCancelled()) {
                    return finish(task, SubTaskStatus.CANCELLED, "", "父任务已停止", steps, startedAt, tokensIn, tokensOut)
                }
                val elapsed = env.now() - startedAt
                if (elapsed >= task.timeoutMs) {
                    return finish(
                        task, SubTaskStatus.TIMEOUT, "",
                        "子任务超时（限 ${task.timeoutMs / 1000}s，已跑 ${elapsed / 1000}s）",
                        steps, startedAt, tokensIn, tokensOut,
                    )
                }
                steps++
                val outcome = env.turn.round(messages, env.toolsJson)
                tapUsage(outcome.usage)
                noteRound(task, outcome)
                when (outcome) {
                    is TurnOutcome.Text ->
                        return finish(task, SubTaskStatus.DONE, outcome.text, "", steps, startedAt, tokensIn, tokensOut)

                    is TurnOutcome.Failed ->
                        return finish(task, SubTaskStatus.FAILED, "", outcome.reason, steps, startedAt, tokensIn, tokensOut)

                    is TurnOutcome.Calls -> {
                        val stop = playCalls(task, outcome, messages, seenCalls)
                        if (stop != null) {
                            return finish(task, stop.status, "", stop.reason, steps, startedAt, tokensIn, tokensOut)
                        }
                    }
                }
            }
            return finish(
                task, SubTaskStatus.FAILED, "",
                "步数用尽（${task.maxSteps} 步内没给出结论；把子任务拆小点再试）",
                steps, startedAt, tokensIn, tokensOut,
            )
        } catch (e: Exception) {
            // 小循环自己收异常：一条子任务崩了不该把整笔分解带崩（失败可报告，87 口径）
            env.log("子 Agent ${task.id} 崩了：${e.javaClass.simpleName}: ${e.message}")
            return finish(
                task, SubTaskStatus.FAILED, "",
                "子任务内部错误（${e.javaClass.simpleName}）", steps, startedAt, tokensIn, tokensOut,
            )
        }
    }

    /** 一次工具批：逐件判白名单/防重复/入库/执行，结果并进回灌；需要提前收口时返回收口单。 */
    private fun playCalls(
        task: SubTask,
        outcome: TurnOutcome.Calls,
        messages: MutableList<LlmMessage>,
        seenCalls: MutableMap<String, Int>,
    ): EarlyStop? {
        // 2026-10-22 真机 UI-D10：子 Agent 三连
        // `HTTP 400 The reasoning_content in the thinking mode must be passed back to the API`。
        // 根因：旧实现把工具结果当 **assistant** 消息回灌，而 thinking 模式的
        // 服务端要求每条 assistant 都带回 `reasoning_content`；子 Agent 没有
        // 这条回传链（主链路本就不发 assistant 消息——历史全部渲染进 user）。
        // ⇒ 与父引擎同形：preamble 与围栏结果并进**一条 user 消息**，历史里
        // 不出现 assistant，也就不再触发该 400。
        //
        // 2026-10-22 二次（「8 步用尽」）：真机那条子任务只是列两个目录却兜了 8 步
        // ⇒ 不是工具额度不够，是**不收敛**。两道收口：①回灌消息里明说「够了就收尾」；
        // ②同一工具同一参数第 3 次出现即提前失败（结果不会变，多跑只会烧步数）。
        val sb = StringBuilder()
        if (outcome.preamble.isNotBlank()) {
            sb.append("【你上一步说】").append(outcome.preamble).append("\n\n")
        }
        for (call in outcome.calls) {
            if (env.isCancelled()) return EarlyStop(SubTaskStatus.CANCELLED, "父任务已停止（工具调用前）")
            val times = bump(seenCalls, call)
            if (times >= 3) {
                return EarlyStop(
                    SubTaskStatus.FAILED,
                    "同一工具同一参数重复调用 $times 次（结果不会变），提前收口；" +
                        "换个路径/换个工具，或把子任务拆小点",
                )
            }
            sb.append(fenced(call.name, execute(task, call))).append("\n\n")
        }
        if (sb.isNotEmpty()) {
            sb.append("【提示】上面是工具结果；如果已经够回答目标，现在就给结论，不要重复调用同一工具。")
            messages += LlmMessage(LlmMessage.ROLE_USER, sb.toString().trim())
        }
        return null
    }

    /** 一件调用：先判白名单（v2），放行才入库＋执行；被拒也落一条 REJECT 账。 */
    private fun execute(task: SubTask, call: ToolCall): ToolOutcome =
        when (val v = SubAgentWhitelist.decide(call.name, task)) {
            is SubAgentWhitelist.Decision.Reject -> {
                env.ledger(
                    SubAgentLedger.row(
                        task, SubAgentLedgerKind.REJECT, tool = call.name, ok = false, detail = v.reason, nowMs = env.now(),
                    ),
                )
                ToolOutcome.Rejected(v.reason)
            }

            SubAgentWhitelist.Decision.Allow -> {
                if (env.isWrite(call.name)) env.beforeWrite(call)
                val result = env.toolExec(call)
                env.ledger(
                    SubAgentLedger.row(
                        task, SubAgentLedgerKind.TOOL, tool = call.name,
                        ok = result is ToolOutcome.Ok, detail = briefOf(result), nowMs = env.now(),
                    ),
                )
                result
            }
        }

    /** 同一工具同一参数的调用次数（键＝工具名＋参数原文）。 */
    private fun bump(seen: MutableMap<String, Int>, call: ToolCall): Int {
        val key = call.name + "|" + call.arguments.toString()
        val times = (seen[key] ?: 0) + 1
        seen[key] = times
        return times
    }

    /** 一轮落一条账（不分好坏；失败带原因）。 */
    private fun noteRound(task: SubTask, outcome: TurnOutcome) {
        val detail = when (outcome) {
            is TurnOutcome.Text -> "给出结论（${outcome.text.length} 字）"
            is TurnOutcome.Calls -> "要调 ${outcome.calls.size} 件工具"
            is TurnOutcome.Failed -> outcome.reason
        }
        env.ledger(
            SubAgentLedger.row(
                task, SubAgentLedgerKind.ROUND, ok = outcome !is TurnOutcome.Failed, detail = detail, nowMs = env.now(),
            ),
        )
    }

    /** 工具结果的一句话摘要（账用；不搬正文）。 */
    private fun briefOf(result: ToolOutcome): String = when (result) {
        is ToolOutcome.Ok -> result.text
        is ToolOutcome.Failed -> "失败：${result.error}"
        is ToolOutcome.Rejected -> "被拒：${result.reason}"
    }

    /** 工具结果 ⇒ 回灌文本（与父引擎在线路径同形：Done 已围栏；失败/被拒补包）。 */
    private fun fenced(name: String, result: ToolOutcome): String = when (result) {
        is ToolOutcome.Ok -> DataFence.wrap(name, env.now(), result.text)
        is ToolOutcome.Failed -> DataFence.wrap(name, env.now(), "失败：${result.error}")
        is ToolOutcome.Rejected -> DataFence.wrap(name, env.now(), "被拒：${result.reason}")
    }

    private fun finish(
        task: SubTask,
        status: SubTaskStatus,
        text: String,
        reason: String,
        steps: Int,
        startedAt: Long,
        tokensIn: Long? = null,
        tokensOut: Long? = null,
    ): SubTaskResult = SubTaskResult(
        id = task.id,
        status = status,
        text = text,
        failureReason = reason,
        steps = steps,
        attempts = 1,
        startedAt = startedAt,
        endedAt = env.now(),
        tokensIn = tokensIn,
        tokensOut = tokensOut,
    )

    /** 一轮里要提前收口的形态（取消/重复调用）：只带状态与原因，步数由调用点补。 */
    private data class EarlyStop(val status: SubTaskStatus, val reason: String)
}
