// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§1「子 Agent：独立上下文/
// 工具白名单/超时」与开工闸门《多Agent协作v1-协作模型-2026-10-21.md》§3/§6。
//
// 定位：**单条**子任务的有界小循环。不建线程、不管并发——并发与截止归
// [SubAgentCoordinator]，本类只管「这一条怎么从开始跑到终态」。
// 判据全部可注：turn（模型一轮）、toolExec（工具执行＋白名单）、now（时钟）、
// isCancelled（父任务停止观测）——JVM 单测喂假件即可跑完全部路径。
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
    /** 执行一次工具调用（白名单在执行层拦；路径解析也在这层做）。 */
    val toolExec: (ToolCall) -> ToolOutcome,
    /** 本轮要发给模型的 tools 数组（白名单内的 spec 序列化；空名单传 null）。 */
    val toolsJson: JSONArray?,
    /** 时钟（测试可拨）。 */
    val now: () -> Long = System::currentTimeMillis,
    /** 父任务是否已被用户停止（轮次边界观测；见协作模型 §6）。 */
    val isCancelled: () -> Boolean = { false },
    /** 日志（生产走 RunLog；测试收集断言）。 */
    val log: (String) -> Unit = {},
)

/**
 * 单条子任务的执行器（有界小循环）。
 *
 * 边界（派工单 §1）：
 * - 步数 ≤ [SubTask.maxSteps]（默认 8）：用完即「失败：步数用尽」，不无限续；
 * - 墙钟 ≤ [SubTask.timeoutMs]（默认 90s，钳制 10–300s）：每轮开头查，超时即止；
 * - 取消：每轮开头 + 每次工具调用前查 [SubAgentEnv.isCancelled] ⇒ CANCELLED；
 * - 工具结果按父引擎同款回灌（[DataFence] 围栏 ＋ **user** 角色进历史；不发 assistant，见 UI-D10）。
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
        val messages = mutableListOf<LlmMessage>()
        messages += SubAgentRules.subTaskMessages(task)
        try {
            while (steps < task.maxSteps) {
                if (env.isCancelled()) {
                    return finish(task, SubTaskStatus.CANCELLED, "", "父任务已停止", steps, startedAt)
                }
                val elapsed = env.now() - startedAt
                if (elapsed >= task.timeoutMs) {
                    return finish(
                        task, SubTaskStatus.TIMEOUT, "",
                        "子任务超时（限 ${task.timeoutMs / 1000}s，已跑 ${elapsed / 1000}s）",
                        steps, startedAt,
                    )
                }
                steps++
                when (val outcome = env.turn.round(messages, env.toolsJson)) {
                    is TurnOutcome.Text -> {
                        return finish(task, SubTaskStatus.DONE, outcome.text, "", steps, startedAt)
                    }

                    is TurnOutcome.Failed -> {
                        return finish(task, SubTaskStatus.FAILED, "", outcome.reason, steps, startedAt)
                    }

                    is TurnOutcome.Calls -> {
                        // 2026-10-22 真机 UI-D10：子 Agent 三连
                        // `HTTP 400 The reasoning_content in the thinking mode must be passed back to the API`。
                        // 根因：旧实现把工具结果当 **assistant** 消息回灌，而 thinking 模式的
                        // 服务端要求每条 assistant 都带回 `reasoning_content`；子 Agent 没有
                        // 这条回传链（主链路本就不发 assistant 消息——历史全部渲染进 user）。
                        // ⇒ 与父引擎同形：preamble 与围栏结果并进**一条 user 消息**，历史里
                        // 不出现 assistant，也就不再触发该 400。
                        val sb = StringBuilder()
                        if (outcome.preamble.isNotBlank()) {
                            sb.append("（你上一步说：").append(outcome.preamble).append("）\n\n")
                        }
                        for (call in outcome.calls) {
                            if (env.isCancelled()) {
                                return finish(
                                    task, SubTaskStatus.CANCELLED, "",
                                    "父任务已停止（工具调用前）", steps, startedAt,
                                )
                            }
                            val result = env.toolExec(call)
                            sb.append(fenced(call.name, result)).append("\n\n")
                        }
                        if (sb.isNotEmpty()) {
                            messages += LlmMessage(LlmMessage.ROLE_USER, sb.toString().trim())
                        }
                    }
                }
            }
            return finish(
                task, SubTaskStatus.FAILED, "",
                "步数用尽（${task.maxSteps} 步内没给出结论；把子任务拆小点再试）",
                steps, startedAt,
            )
        } catch (e: Exception) {
            // 小循环自己收异常：一条子任务崩了不该把整笔分解带崩（失败可报告，87 口径）
            env.log("子 Agent ${task.id} 崩了：${e.javaClass.simpleName}: ${e.message}")
            return finish(
                task, SubTaskStatus.FAILED, "",
                "子任务内部错误（${e.javaClass.simpleName}）", steps, startedAt,
            )
        }
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
    ): SubTaskResult = SubTaskResult(
        id = task.id,
        status = status,
        text = text,
        failureReason = reason,
        steps = steps,
        attempts = 1,
        startedAt = startedAt,
        endedAt = env.now(),
    )
}
