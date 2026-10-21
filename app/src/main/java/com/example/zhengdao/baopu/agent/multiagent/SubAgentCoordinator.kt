// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§1「并发：受 117 的任务队列/
// 限流约束，默认最多 2 个子 Agent」与开工闸门《多Agent协作v1-协作模型-2026-10-21.md》
// §5/§6/§7。
//
// 定位：一笔分解的调度器——起子任务（遵守并发上限）、收结果、重试、取消级联、
// 全局截止、落审计账。判定全在 [SubAgentRules]（纯函数），本类只做驱动与 IO。
//
// 线程：专用池 `zd-baopu-sub`（fixed＝并发上限，命名＋daemon；已在
// util/BareThreadGuardTest 对账单登记，房型照第九十二批读池先例）。
// 等待策略：完成队列 poll（50ms 一圈）——取消/截止要在**一个轮询周期内**被看见，
// 不能 take() 死等（与 ConfirmFlow 的轮询同一立场）。
package com.example.zhengdao.baopu.agent.multiagent

import java.util.ArrayDeque
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal class SubAgentCoordinator(
    /** 每条子任务的执行器工厂（白名单/toolsJson 按条不同 ⇒ 按条造；测试注假件）。 */
    private val newRunner: (SubTask) -> SubAgentRunner,
    private val store: SubTaskStore,
    private val maxParallel: Int = SubAgentRules.MAX_PARALLEL_SUBAGENTS,
    private val isCancelled: () -> Boolean,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {

    /** 完成队列（提交顺序即提交，完成顺序由队列给）。 */
    private val pool = Executors.newFixedThreadPool(maxParallel.coerceAtLeast(1)) { r ->
        Thread(r, "zd-baopu-sub").apply { isDaemon = true }
    }
    private val completion = ExecutorCompletionService<SubTaskResult>(pool)

    /** 轮询周期（ms）：取消/截止的最坏察觉延迟。 */
    private val pollMs = 50L

    /**
     * 跑完一笔分解，返回按序号排的结果列表（**不抛异常**）。
     *
     * 任何一条的内部错误都已被 [SubAgentRunner] 收成 FAILED；本层只可能被
     * 存储故障影响（只 log）。取消/截止时在飞的条目一律记 CANCELLED。
     */
    fun runAll(decomposition: TaskDecomposition): List<SubTaskResult> {
        val tasks = decomposition.subtasks
        if (tasks.isEmpty()) return emptyList()
        val t0 = now()
        // 存储是账本不是闸门：接口实现坏了只 log，流程照跑（子任务结果仍回给父）
        quietly { store.insertBatch(tasks, decomposition.sessionId, t0) }

        val pending = ArrayDeque(tasks.sortedBy { it.seq })
        val running = LinkedHashMap<String, Future<SubTaskResult>>()
        val attempts = tasks.associate { it.id to 1 }.toMutableMap()
        val stepSum = tasks.associate { it.id to 0 }.toMutableMap()
        val results = LinkedHashMap<String, SubTaskResult>()
        val deadline = t0 + SubAgentRules.GLOBAL_DEADLINE_MS
        var stopReason: String? = null

        try {
            while (pending.isNotEmpty() || running.isNotEmpty()) {
                if (isCancelled()) {
                    stopReason = "父任务已停止"
                    break
                }
                if (now() >= deadline) {
                    stopReason = "全局截止（${SubAgentRules.GLOBAL_DEADLINE_MS / 1000}s）"
                    break
                }
                // 起一批（不超过并发上限；提交体自带 id ⇒ 结果永远认得出是哪条）
                var startable = SubAgentRules.nextStartable(running.size, pending.size, maxParallel)
                while (startable > 0 && pending.isNotEmpty()) {
                    // pollFirst() 在 isNotEmpty 之内不可能空；`?: continue` 是给编译器看的
                    val task = pending.pollFirst() ?: continue
                    val runner = newRunner(task)
                    quietly { store.markRunning(task, now()) }
                    running[task.id] = completion.submit {
                        runCatching { runner.run(task) }.getOrElse { e ->
                            SubTaskResult(
                                id = task.id,
                                status = SubTaskStatus.FAILED,
                                failureReason = "执行异常（${e.javaClass.simpleName}）",
                                startedAt = now(),
                                endedAt = now(),
                            )
                        }
                    }
                    startable--
                }
                if (running.isEmpty()) break // 理论上到不了（pending 空才可能；保险）
                // 收一个（带超时 poll：这一圈没完成也回去重查取消/截止）
                val finished = completion.poll(pollMs, TimeUnit.MILLISECONDS)
                if (finished != null) {
                    val raw = finished.get()
                    val id = raw.id
                    running.remove(id)
                    val task = tasks.firstOrNull { it.id == id }
                    if (task == null) {
                        log("子 Agent 协调器：收到不认识的结果 id=$id（忽略）")
                        continue
                    }
                    val acc = (stepSum[id] ?: 0) + raw.steps
                    stepSum[id] = acc
                    val merged = raw.copy(steps = acc, attempts = attempts[id] ?: 1)
                    if (SubAgentRules.shouldRetry(merged.status, merged.attempts) && !isCancelled()) {
                        // 重试一次：attempts+1 重新入队（步数累计，不重置——审计要看得出
                        // 「重试是不是又烧了一遍」；首战失败原因只进日志，行内状态回 PENDING
                        // 由随后的 markRunning 覆盖）
                        attempts[id] = merged.attempts + 1
                        log(
                            "子 Agent ${task.id} ${statusLabel(merged.status)}" +
                                "（${merged.failureReason}），重试第 ${merged.attempts + 1} 次",
                        )
                        pending.addLast(task)
                    } else {
                        results[id] = merged
                        quietly {
                            store.markFinished(
                                merged, task.goal, task.input, task.acceptance, task.tools, merged.attempts,
                            )
                        }
                    }
                }
            }
        } finally {
            // 收口：在飞的一律取消并记 CANCELLED（用户停止/截止/异常出口都走这里）
            running.values.forEach { it.cancel(true) }
            running.keys.forEach { id ->
                val task = tasks.firstOrNull { it.id == id } ?: return@forEach
                val r = SubTaskResult(
                    id = id,
                    status = SubTaskStatus.CANCELLED,
                    failureReason = stopReason ?: "协调器收口",
                    steps = stepSum[id] ?: 0,
                    attempts = attempts[id] ?: 1,
                )
                results[id] = r
                quietly { store.markFinished(r, task.goal, task.input, task.acceptance, task.tools, r.attempts) }
            }
            pending.forEach { task ->
                val r = SubTaskResult(
                    id = task.id,
                    status = SubTaskStatus.CANCELLED,
                    failureReason = stopReason ?: "未开跑",
                    steps = 0,
                    attempts = attempts[task.id] ?: 1,
                )
                results[task.id] = r
                quietly {
                    store.markFinished(r, task.goal, task.input, task.acceptance, task.tools, r.attempts)
                }
            }
            pool.shutdown()
            runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
        }

        val ordered = tasks.mapNotNull { results[it.id] }
        val done = ordered.count { it.ok }
        log(
            "子 Agent 分解 ${decomposition.parentId}：$done/${ordered.size} 成功，" +
                "${now() - t0}ms${if (stopReason != null) "（$stopReason）" else ""}",
        )
        return ordered
    }

    /** 审计写失败只 log 不抛（账本卡住业务＝本末倒置，与轨迹/台账同口径）。 */
    private inline fun quietly(block: () -> Unit) {
        runCatching(block).onFailure {
            log("子 Agent 审计：写失败（不影响执行）${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun statusLabel(status: SubTaskStatus): String = when (status) {
        SubTaskStatus.FAILED -> "失败"
        SubTaskStatus.TIMEOUT -> "超时"
        else -> status.name
    }
}
