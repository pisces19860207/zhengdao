// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§2「测试」与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》§9。本文件钉调度器：并发上限、
// 串行档、失败不互染、重试口径、取消级联、全局截止、存储故障不拦流程。
//
// 并发观测用「睡眠窗口 + 活跃计数」：假 turn 每轮睡 50ms 并记活跃数——
// 串行实现会观测到 1，超限实现会观测到 3，只有守规矩的实现是 2。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubAgentCoordinatorTest {

    /** 内存假存储：记账 + 可查（childrenOf/ofSession 与生产同口径）。 */
    private class FakeStore(private val failInsert: Boolean = false) : SubTaskStore {
        val rows = mutableListOf<SubTaskRecord>()
        val events = mutableListOf<String>()

        override fun insertBatch(tasks: List<SubTask>, sessionId: Long, now: Long) {
            if (failInsert) throw IllegalStateException("存储故意坏")
            tasks.forEach { t ->
                rows += SubTaskRecord(
                    taskId = t.id, parentId = t.parentId, sessionId = sessionId, seq = t.seq,
                    goal = t.goal, input = t.input, acceptance = t.acceptance, tools = t.tools.joinToString(","),
                    status = "PENDING", attempts = 1, steps = 0, startedAt = 0, endedAt = 0,
                    failureReason = "", resultText = "",
                )
                events += "insert:${t.id}"
            }
        }

        override fun markRunning(task: SubTask, at: Long) {
            events += "run:${task.id}"
        }

        override fun markFinished(
            result: SubTaskResult,
            goal: String,
            input: String,
            acceptance: String,
            tools: List<String>,
            attempts: Int,
        ) {
            val i = rows.indexOfFirst { it.taskId == result.id }
            if (i >= 0) {
                rows[i] = rows[i].copy(
                    status = result.status.name,
                    attempts = attempts,
                    steps = result.steps,
                    startedAt = result.startedAt,
                    endedAt = result.endedAt,
                    failureReason = result.failureReason,
                    resultText = result.text,
                )
            }
            events += "finish:${result.id}:${result.status.name}"
        }

        override fun childrenOf(parentId: String): List<SubTaskRecord> =
            rows.filter { it.parentId == parentId }.sortedBy { it.seq }

        override fun ofSession(sessionId: Long): List<SubTaskRecord> =
            rows.filter { it.sessionId == sessionId }
    }

    /** 按 taskId 出招的假 turn；可观测并发（睡眠窗口 + 活跃计数）。 */
    private class ScriptedTurn(
        private val script: Map<String, List<TurnOutcome>>,
        private val sleepMs: Long = 0L,
    ) : SubAgentTurn {
        private val calls = mutableMapOf<String, Int>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        override fun round(messages: List<LlmMessage>, tools: org.json.JSONArray?): TurnOutcome {
            // 从本轮用户消息里认 taskId 不现实 ⇒ 由工厂包一层按任务出招（见 factoryOf）
            throw IllegalStateException("ScriptedTurn 要包 PerTaskTurn 用")
        }

        fun next(taskId: String): TurnOutcome {
            val n = (calls[taskId] ?: 0)
            calls[taskId] = n + 1
            val list = script[taskId] ?: listOf(TurnOutcome.Text("没脚本默认完成"))
            return list.getOrElse(n) { TurnOutcome.Text("脚本用尽默认完成") }
        }

        fun enter() {
            val cur = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, cur) }
            if (sleepMs > 0) Thread.sleep(sleepMs)
        }

        fun leave() {
            active.decrementAndGet()
        }
    }

    /** 每条子任务一个出招窗口的 turn（包住 ScriptedTurn 的计数与并发观测）。 */
    private class PerTaskTurn(
        private val taskId: String,
        private val scripted: ScriptedTurn,
    ) : SubAgentTurn {
        override fun round(messages: List<LlmMessage>, tools: org.json.JSONArray?): TurnOutcome {
            scripted.enter()
            return try {
                scripted.next(taskId)
            } finally {
                scripted.leave()
            }
        }
    }

    private fun decomposition(n: Int, sessionId: Long = 1L, parentId: String = "p-1"): TaskDecomposition {
        val subs = (1..n).map { i ->
            SubTask(id = "$parentId-$i", parentId = parentId, seq = i, goal = "任务$i")
        }
        return TaskDecomposition(parentId = parentId, sessionId = sessionId, goal = "总任务", subtasks = subs, createdAt = 0L)
    }

    private fun coordinatorOf(
        store: SubTaskStore,
        turn: ScriptedTurn,
        maxParallel: Int,
        isCancelled: () -> Boolean = { false },
        now: () -> Long = { System.currentTimeMillis() },
    ) = SubAgentCoordinator(
        newRunner = { task ->
            SubAgentRunner(
                SubAgentEnv(
                    turn = PerTaskTurn(task.id, turn),
                    toolExec = { ToolOutcome.Ok("工具结果") },
                    toolsJson = null,
                    now = now,
                    isCancelled = isCancelled,
                ),
            )
        },
        store = store,
        maxParallel = maxParallel,
        isCancelled = isCancelled,
        now = now,
        log = {},
    )

    private fun script(vararg pairs: Pair<String, List<TurnOutcome>>) = pairs.toMap()

    private fun done(text: String) = TurnOutcome.Text(text)

    // ── 串行档（派工单 §4：先串行两子任务跑通） ──────────────────────────

    @Test
    fun `串行两子任务_都成功_按序返回_审计行终态正确`() {
        val store = FakeStore()
        val turn = ScriptedTurn(
            script("p-1-1" to listOf(done("甲读完")), "p-1-2" to listOf(done("乙读完"))),
        )
        val results = coordinatorOf(store, turn, maxParallel = 1).runAll(decomposition(2))

        assertEquals(2, results.size)
        assertEquals(listOf("p-1-1", "p-1-2"), results.map { it.id })
        assertTrue("都成功", results.all { it.ok })
        assertEquals("甲读完", results[0].text)
        assertEquals(1, results[0].steps)
        // 串行 ⇒ 同时活跃最多 1
        assertEquals(1, turn.maxActive.get())
        // 审计：两行都 DONE、attempts=1、可查
        val rows = store.childrenOf("p-1")
        assertEquals(2, rows.size)
        assertTrue("行按 seq 排", rows.map { it.taskId } == listOf("p-1-1", "p-1-2"))
        assertTrue("行都是 DONE", rows.all { it.status == "DONE" })
        assertEquals(1, rows[0].attempts)
        assertEquals(2, store.ofSession(1L).size)
        // 事件顺序：先落账、再开跑、再收尾
        assertTrue("先 insert 再 run：${store.events}", store.events.indexOf("insert:p-1-1") < store.events.indexOf("run:p-1-1"))
    }

    // ── 并行上限 ─────────────────────────────────────────────────────────

    @Test
    fun `并行上限2_三条子任务_同时最多两个`() {
        val store = FakeStore()
        val turn = ScriptedTurn(
            script(
                "p-1-1" to listOf(done("一")),
                "p-1-2" to listOf(done("二")),
                "p-1-3" to listOf(done("三")),
            ),
            sleepMs = 50,
        )
        val results = coordinatorOf(store, turn, maxParallel = SubAgentRules.MAX_PARALLEL_SUBAGENTS)
            .runAll(decomposition(3))

        assertEquals(3, results.size)
        assertTrue("三条都成功：$results", results.all { it.ok })
        assertEquals("并发峰值必须正好是 2（串行会观测 1，超限会观测 3）", 2, turn.maxActive.get())
    }

    // ── 失败不互染 ───────────────────────────────────────────────────────

    @Test
    fun `一个失败不拖垮另一个_汇总照实`() {
        val store = FakeStore()
        val turn = ScriptedTurn(
            script(
                "p-1-1" to listOf(done("甲好了")),
                "p-1-2" to listOf(TurnOutcome.Failed("等模型回复超时（90s）"), done("乙重试后好了")),
            ),
        )
        val results = coordinatorOf(store, turn, maxParallel = 1).runAll(decomposition(2))

        assertEquals(SubTaskStatus.DONE, results[0].status)
        assertEquals("乙重试后好了", results[1].text)
        assertEquals(2, results[1].attempts) // 失败自动重试一次
        assertEquals(2, results[1].steps) // 步数累计（不重置）
        val agg = SubAgentRules.aggregate(results) { id -> "目标-$id" }
        assertTrue("聚合两条都在：$agg", agg.contains("甲好了") && agg.contains("乙重试后好了"))
        assertTrue("总览 2/2：$agg", agg.contains("2/2 成功"))
    }

    @Test
    fun `一条崩_其他照跑_结果里有失败原因`() {
        val store = FakeStore()
        val turn = ScriptedTurn(
            script(
                "p-1-1" to listOf(done("甲")),
                "p-1-2" to listOf(TurnOutcome.Failed("通道炸了"), TurnOutcome.Failed("通道还炸")),
                "p-1-3" to listOf(done("丙")),
            ),
        )
        val results = coordinatorOf(store, turn, maxParallel = 2).runAll(decomposition(3))

        assertEquals(3, results.size)
        assertTrue(results[0].ok && results[2].ok)
        assertEquals(SubTaskStatus.FAILED, results[1].status)
        assertEquals("重试一次仍失败 ⇒ attempts=2", 2, results[1].attempts)
        assertTrue("失败原因保留：${results[1].failureReason}", results[1].failureReason.contains("通道"))
        val rows = store.childrenOf("p-1")
        assertEquals("DONE", rows[0].status)
        assertEquals("FAILED", rows[1].status)
        assertEquals(2, rows[1].attempts)
    }

    // ── 取消级联 ─────────────────────────────────────────────────────────

    @Test
    fun `一开跑就取消_一条不跑_全记取消`() {
        val store = FakeStore()
        val turn = ScriptedTurn(script("p-1-1" to listOf(done("不该出现"))))
        val results = coordinatorOf(store, turn, maxParallel = 2, isCancelled = { true })
            .runAll(decomposition(3))

        assertEquals(3, results.size)
        assertTrue("全取消", results.all { it.status == SubTaskStatus.CANCELLED })
        assertTrue("理由统一：${results.map { it.failureReason }.toSet()}", results.all { it.failureReason == "父任务已停止" })
        assertTrue("turn 一次都没调", turn.maxActive.get() == 0)
        assertTrue("审计行全 CANCELLED", store.childrenOf("p-1").all { it.status == "CANCELLED" })
        assertFalse("没有 run 事件", store.events.any { it.startsWith("run:") })
    }

    @Test
    fun `跑到一半取消_在飞与未开都记取消_已完成的保留`() {
        val store = FakeStore()
        val gate = CountDownLatch(1)
        val task2Started = CountDownLatch(1)
        var cancelled = false
        val coordinator = SubAgentCoordinator(
            newRunner = { task ->
                // 三条特制：1 秒完；2 开门后阻塞等人放行；3 永远不该开跑
                val taskTurn = SubAgentTurn { _, _ ->
                    when (task.id) {
                        "p-1-1" -> done("甲完成")
                        "p-1-2" -> {
                            task2Started.countDown()
                            gate.await(3, TimeUnit.SECONDS)
                            done("乙迟到的完成")
                        }

                        else -> done("丙不该跑")
                    }
                }
                SubAgentRunner(
                    SubAgentEnv(
                        turn = taskTurn,
                        toolExec = { ToolOutcome.Ok("x") },
                        toolsJson = null,
                        isCancelled = { cancelled },
                    ),
                )
            },
            store = store,
            maxParallel = 1, // 串行：1 完成 ⇒ 2 开跑 ⇒ 3 未开
            isCancelled = { cancelled },
            now = { System.currentTimeMillis() },
            log = {},
        )
        val resultsBox = mutableListOf<SubTaskResult>()
        val t = Thread { resultsBox += coordinator.runAll(decomposition(3)) }
        t.start()
        assertTrue("第 2 条要开跑", task2Started.await(3, TimeUnit.SECONDS))
        cancelled = true // 用户点停止
        t.join(5_000)
        // 在飞那条的 turn 卡在 gate 上 ⇒ 协调器收口时 cancel(true) 打断它，
        // 结果被丢弃记 CANCELLED（不受「谁先完成」的竞态影响）
        gate.countDown()

        val byId = resultsBox.associateBy { it.id }
        assertEquals("已完成的保留 DONE", SubTaskStatus.DONE, byId["p-1-1"]?.status)
        assertEquals("在飞的记 CANCELLED", SubTaskStatus.CANCELLED, byId["p-1-2"]?.status)
        assertTrue(
            "在飞的带原因：${byId["p-1-2"]?.failureReason}",
            byId["p-1-2"]?.failureReason?.contains("父任务已停止") == true,
        )
        assertEquals("未开的记 CANCELLED", SubTaskStatus.CANCELLED, byId["p-1-3"]?.status)
        val rows = store.childrenOf("p-1")
        assertEquals("DONE", rows.first { it.taskId == "p-1-1" }.status)
        assertTrue("后两行取消", rows.filter { it.taskId != "p-1-1" }.all { it.status == "CANCELLED" })
    }

    // ── 全局截止 ─────────────────────────────────────────────────────────

    @Test
    fun `全局截止到点_剩余记取消带截止原因`() {
        val store = FakeStore()
        val turn = ScriptedTurn(
            script(
                "p-1-1" to listOf(done("甲")),
                "p-1-2" to listOf(done("乙")),
                "p-1-3" to listOf(done("丙")),
            ),
        )
        var now = 0L
        // 第 1 条一收尾就把时钟推到截止外（串行 ⇒ 2/3 没机会开）
        val clockStore = object : SubTaskStore by store {
            override fun markFinished(
                result: SubTaskResult,
                goal: String,
                input: String,
                acceptance: String,
                tools: List<String>,
                attempts: Int,
            ) {
                store.markFinished(result, goal, input, acceptance, tools, attempts)
                now = SubAgentRules.GLOBAL_DEADLINE_MS + 1
            }
        }
        val results = coordinatorOf(clockStore, turn, maxParallel = 1, now = { now })
            .runAll(decomposition(3))

        assertEquals("甲在截止前跑完", SubTaskStatus.DONE, results.first { it.id == "p-1-1" }.status)
        val rest = results.filter { it.id != "p-1-1" }
        assertEquals(2, rest.size)
        assertTrue("其余取消", rest.all { it.status == SubTaskStatus.CANCELLED })
        assertTrue("带截止原因：${rest.map { it.failureReason }.toSet()}", rest.all { it.failureReason.contains("全局截止") })
    }

    // ── 存储故障不拦流程 ─────────────────────────────────────────────────

    @Test
    fun `存储坏_流程照跑_结果照回`() {
        val turn = ScriptedTurn(script("p-1-1" to listOf(done("甲")), "p-1-2" to listOf(done("乙"))))
        val results = coordinatorOf(FakeStore(failInsert = true), turn, maxParallel = 2)
            .runAll(decomposition(2))
        assertEquals(2, results.size)
        assertTrue("存储是账本不是闸门", results.all { it.ok })
    }

    @Test
    fun `空分解_空结果_不碰存储`() {
        val store = FakeStore()
        val d = TaskDecomposition("p-0", 1L, "空", emptyList(), 0L)
        val results = coordinatorOf(store, ScriptedTurn(script()), maxParallel = 2).runAll(d)
        assertTrue(results.isEmpty())
        assertTrue("存储零事件", store.events.isEmpty())
    }

    // ── 工具注入占位（协调器不直接调工具；执行层白名单另见 SubTaskToolTest） ──

    @Test
    fun `协调器不给子任务塞写工具_白名单天花板只读六件`() {
        assertEquals(6, SubAgentRules.DEFAULT_SUBAGENT_TOOLS.size)
        assertTrue(SubAgentRules.DEFAULT_SUBAGENT_TOOLS.none { it.startsWith("write") || it == "delete_path" })
    }
}
