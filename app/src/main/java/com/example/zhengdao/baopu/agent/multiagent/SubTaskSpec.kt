// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§0/§1 与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》。本文件只放**数据模型**（无判据、无 IO），
// 判据在 [SubAgentRules]，执行在 [SubAgentRunner]，调度在 [SubAgentCoordinator]。
package com.example.zhengdao.baopu.agent.multiagent

/**
 * 子任务状态（审计表 `sub_task.status` 存枚举名；纯内存路径同样用它）。
 *
 * 为什么没有 "RETRYING"：重试是**同一条子任务**的第二次尝试，状态机停在
 * PENDING→RUNNING→终态，尝试次数走 `attempts` 计数——多一个状态就让
 * 「这条到底成功没有」出现两种答法。
 */
internal enum class SubTaskStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    TIMEOUT,
    CANCELLED,
    ;

    /** 终态：不会再变（协调器据此决定还能不能重试）。 */
    val terminal: Boolean get() = this == DONE || this == FAILED || this == TIMEOUT || this == CANCELLED
}

/**
 * 一条子任务（分解里的一条）。
 *
 * [tools] 是**生效白名单**（已经过 [SubAgentRules.effectiveWhitelist] 收敛），
 * 不是模型原始申请——模型申请只进解析器，不进这里。
 */
internal data class SubTask(
    val id: String,
    val parentId: String,
    val seq: Int,
    val goal: String,
    val input: String = "",
    val acceptance: String = "",
    val tools: List<String> = emptyList(),
    val timeoutMs: Long = SubAgentRules.DEFAULT_TIMEOUT_MS,
    val maxSteps: Int = SubAgentRules.MAX_STEPS,
)

/**
 * 一条子任务的执行结果（含重试后的最终态）。
 *
 * [text] 只在 [SubTaskStatus.DONE] 时是子 Agent 的结论；失败时看 [failureReason]。
 * [steps] 是**累计**步数（含重试的那一次）——审计要能看出「重试是不是又烧了一遍」。
 */
internal data class SubTaskResult(
    val id: String,
    val status: SubTaskStatus,
    val text: String = "",
    val failureReason: String = "",
    val steps: Int = 0,
    val attempts: Int = 1,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
) {
    val ok: Boolean get() = status == SubTaskStatus.DONE

    /** 耗时（ms）；没跑过（未开/被取消在排队态）⇒ 0。 */
    val durationMs: Long get() = if (startedAt > 0L && endedAt >= startedAt) endedAt - startedAt else 0L
}

/**
 * 一次分解（父任务）＝父 Agent 的一笔 `run_subtasks` 调用。
 *
 * [goal] 是父 Agent 对**整体任务**的自述（审计用；可空——工具参数里可选）。
 * [subtasks] 已通过解析校验（1–5 条、白名单已收敛、超时已钳制）。
 */
internal data class TaskDecomposition(
    val parentId: String,
    val sessionId: Long,
    val goal: String,
    val subtasks: List<SubTask>,
    val createdAt: Long,
)

/** 审计表 `sub_task` 的一行（落库形状；查询回来的也是它）。[taskId] 即 [SubTask.id]。 */
internal data class SubTaskRecord(
    val taskId: String,
    val parentId: String,
    val sessionId: Long,
    val seq: Int,
    val goal: String,
    val input: String,
    val acceptance: String,
    val tools: String,
    val status: String,
    val attempts: Int,
    val steps: Int,
    val startedAt: Long,
    val endedAt: Long,
    val failureReason: String,
    val resultText: String,
)
