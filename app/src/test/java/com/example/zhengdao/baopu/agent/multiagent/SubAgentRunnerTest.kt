// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§2「测试」与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》§9。本文件钉单条子任务的有界循环：
// 步数刹车 / 墙钟刹车 / 取消边界 / 工具回灌同形 / 异常不收崩。
//
// 全部路径用假件驱动：turn 按脚本出招、toolExec 记账、时钟可拨——不联网、
// 不起真线程（线程归协调器那件测）。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.agent.DataFence
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRunnerTest {

    /** 按脚本出招的假一轮；同时记账「每一轮收到的消息」（验回灌）。 */
    private class FakeTurn(private val script: List<TurnOutcome>) : SubAgentTurn {
        val seen = mutableListOf<List<LlmMessage>>()
        private var i = 0

        override fun round(messages: List<LlmMessage>, tools: org.json.JSONArray?): TurnOutcome {
            seen += messages
            val outcome = script.getOrElse(i) { TurnOutcome.Failed("脚本用尽（不该到这）") }
            i++
            return outcome
        }
    }

    private fun task(
        id: String = "t-1",
        goal: String = "读文件写摘要",
        timeoutMs: Long = SubAgentRules.DEFAULT_TIMEOUT_MS,
        maxSteps: Int = SubAgentRules.MAX_STEPS,
    ) = SubTask(id = id, parentId = "p-1", seq = 1, goal = goal, timeoutMs = timeoutMs, maxSteps = maxSteps)

    private fun env(
        turn: SubAgentTurn,
        toolExec: (ToolCall) -> ToolOutcome = { ToolOutcome.Ok("工具结果") },
        now: () -> Long = { 0L },
        isCancelled: () -> Boolean = { false },
    ) = SubAgentEnv(turn = turn, toolExec = toolExec, toolsJson = null, now = now, isCancelled = isCancelled)

    // ── 正常路径 ─────────────────────────────────────────────────────────

    @Test
    fun `两轮_先调工具再给结论_步数与结果都对_回灌同形`() {
        val turn = FakeTurn(
            listOf(
                TurnOutcome.Calls(listOf(ToolCall("c1", "read_file", JSONObject().put("path", "a.md"))), preamble = "先看看"),
                TurnOutcome.Text("a.md 讲的是甲乙丙"),
            ),
        )
        val executed = mutableListOf<String>()
        val runner = SubAgentRunner(env(turn, toolExec = { call -> executed += call.name; ToolOutcome.Ok("文件内容") }))

        val r = runner.run(task())

        assertEquals(SubTaskStatus.DONE, r.status)
        assertEquals("a.md 讲的是甲乙丙", r.text)
        assertEquals(2, r.steps)
        assertEquals(1, r.attempts)
        assertEquals(listOf("read_file"), executed)
        // 第 2 轮（结论那轮）看到的消息里必须有第 1 轮的回灌：preamble + 围栏结果
        val secondRound = turn.seen[1]
        assertTrue("回灌要含 preamble：$secondRound", secondRound.any { it.content.contains("先看看") })
        assertTrue(
            "回灌要含围栏工具结果：$secondRound",
            secondRound.any { it.content.contains(DataFence.FENCE_OPEN) && it.content.contains("文件内容") },
        )
        assertTrue("回灌要含工具名：$secondRound", secondRound.any { it.content.contains("read_file") })
        // UI-D10（2026-10-22 真机）：子 Agent 历史里**不许出现 assistant 消息**。
        // thinking 模式要求 assistant 带回 reasoning_content，子 Agent 没有这条链 ⇒ 全 400。
        // 现在与父引擎同形：工具结果并进 user。
        assertEquals(
            "子 Agent 历史里不许出现 assistant（会触发 reasoning_content 400）：$secondRound",
            0,
            secondRound.count { it.role == LlmMessage.ROLE_ASSISTANT },
        )
    }

    @Test
    fun `一轮直接给结论_不调工具也行`() {
        val runner = SubAgentRunner(env(FakeTurn(listOf(TurnOutcome.Text("直接答")))))
        val r = runner.run(task())
        assertEquals(SubTaskStatus.DONE, r.status)
        assertEquals(1, r.steps)
        assertEquals("直接答", r.text)
    }

    @Test
    fun `失败与被拒的回灌_带失败与被拒前缀`() {
        val turn = FakeTurn(
            listOf(
                TurnOutcome.Calls(listOf(ToolCall("c1", "read_file", JSONObject()), ToolCall("c2", "find_files", JSONObject())), ""),
                TurnOutcome.Text("收工"),
            ),
        )
        val runner = SubAgentRunner(
            env(turn, toolExec = { call ->
                when (call.name) {
                    "read_file" -> ToolOutcome.Failed("不存在：a.md")
                    else -> ToolOutcome.Rejected("不在白名单")
                }
            }),
        )
        val r = runner.run(task())
        assertEquals(SubTaskStatus.DONE, r.status)
        val fed = turn.seen[1].joinToString("\n") { it.content }
        assertTrue("失败要带前缀：$fed", fed.contains("失败：不存在：a.md"))
        assertTrue("被拒要带前缀：$fed", fed.contains("被拒：不在白名单"))
    }

    // ── 刹车 ─────────────────────────────────────────────────────────────

    @Test
    fun `步数用尽_失败且说清上限`() {
        // 脚本永远给 Calls ⇒ 永远不结束（参数每轮不同，避开「重复调用」提前收口）
        val turn = FakeTurn(
            List(20) { TurnOutcome.Calls(listOf(ToolCall("c$it", "list_dir", JSONObject().put("i", it))), "") },
        )
        val runner = SubAgentRunner(env(turn))
        val r = runner.run(task(maxSteps = 3))
        assertEquals(SubTaskStatus.FAILED, r.status)
        assertEquals(3, r.steps)
        assertTrue("原因要点名步数：${r.failureReason}", r.failureReason.contains("步数用尽") && r.failureReason.contains("3"))
    }

    @Test
    fun `同一工具同一参数反复调用_提前收口_不烧满步数`() {
        // 2026-10-22 真机「8 步用尽」的形态：模型反复拿同样的参数调同一个工具。
        val same = JSONObject().put("path", "资料库")
        val turn = FakeTurn(List(10) { TurnOutcome.Calls(listOf(ToolCall("c$it", "list_dir", same)), "") })
        val r = SubAgentRunner(env(turn)).run(task(maxSteps = 16))
        assertEquals(SubTaskStatus.FAILED, r.status)
        assertTrue(
            "原因要点名重复调用：${r.failureReason}",
            r.failureReason.contains("重复调用") && r.failureReason.contains("3"),
        )
        assertTrue("要在 3 步内收口（strides=${r.steps}）", r.steps <= 3)
    }

    @Test
    fun `墙钟超时_在该轮开头就止`() {
        var now = 0L
        // 第 1 轮正常出招；工具一跑就把钟拨到线外（真实路径里是工具/网络跑了 6 秒），
        // 第 2 轮**开头**的截止检查必须拦住它
        val turn = FakeTurn(
            listOf(
                TurnOutcome.Calls(listOf(ToolCall("c1", "list_dir", JSONObject())), ""),
                TurnOutcome.Text("迟到的结论"),
            ),
        )
        val r = SubAgentRunner(
            env(turn, toolExec = { now = 6_000L; ToolOutcome.Ok("x") }, now = { now }),
        ).run(task(timeoutMs = 5_000L))
        assertEquals(SubTaskStatus.TIMEOUT, r.status)
        assertTrue("原因要带时限与已跑：${r.failureReason}", r.failureReason.contains("5s") && r.failureReason.contains("6s"))
        assertEquals("第 1 轮跑了、第 2 轮开头撞线", 1, r.steps)
        assertTrue("超时不该留下结论：${r.text}", r.text.isEmpty())
    }

    @Test
    fun `一轮失败_不再试_照实报`() {
        val turn = FakeTurn(listOf(TurnOutcome.Failed("等模型回复超时（90s）")))
        val r = SubAgentRunner(env(turn)).run(task())
        assertEquals(SubTaskStatus.FAILED, r.status)
        assertEquals("等模型回复超时（90s）", r.failureReason)
        assertEquals(1, r.steps)
    }

    @Test
    fun `turn抛异常_收成失败不崩`() {
        val boom = SubAgentTurn { _, _ -> throw IllegalStateException("炸了") }
        val r = SubAgentRunner(env(boom)).run(task())
        assertEquals(SubTaskStatus.FAILED, r.status)
        assertTrue("原因要带异常类名：${r.failureReason}", r.failureReason.contains("IllegalStateException"))
    }

    // ── 取消边界 ─────────────────────────────────────────────────────────

    @Test
    fun `取消在轮次头_一条都不跑`() {
        val turn = FakeTurn(listOf(TurnOutcome.Text("不该出现")))
        val r = SubAgentRunner(env(turn, isCancelled = { true })).run(task())
        assertEquals(SubTaskStatus.CANCELLED, r.status)
        assertEquals(0, r.steps)
        assertEquals("父任务已停止", r.failureReason)
        assertTrue("取消了不该有结论：${r.text}", r.text.isEmpty())
        assertTrue("turn 一次都不该被调", turn.seen.isEmpty())
    }

    @Test
    fun `取消落在工具调用前_在飞的那轮作废`() {
        val turn = FakeTurn(
            listOf(
                TurnOutcome.Calls(listOf(ToolCall("c1", "list_dir", JSONObject()), ToolCall("c2", "list_dir", JSONObject())), ""),
                TurnOutcome.Text("收尾"),
            ),
        )
        var calls = 0
        val runner = SubAgentRunner(
            env(turn, toolExec = { calls++; ToolOutcome.Ok("x") }, isCancelled = { calls >= 1 }),
        )
        val r = runner.run(task())
        assertEquals(SubTaskStatus.CANCELLED, r.status)
        assertEquals("第二个调用前撞上取消", 1, calls)
        assertTrue("原因要说明位置：${r.failureReason}", r.failureReason.contains("工具调用前"))
    }

    // ── 开局消息 ─────────────────────────────────────────────────────────

    @Test
    fun `开局消息只有目标任务_没有多余历史`() {
        val turn = FakeTurn(listOf(TurnOutcome.Text("完")))
        SubAgentRunner(env(turn)).run(task(goal = "读设定"))
        val first = turn.seen.first()
        assertEquals(2, first.size)
        assertEquals(LlmMessage.ROLE_SYSTEM, first[0].role)
        assertEquals(LlmMessage.ROLE_USER, first[1].role)
        assertTrue(first[1].content.contains("读设定"))
        assertFalse("没有图片", first.any { it.images.isNotEmpty() })
    }
}
