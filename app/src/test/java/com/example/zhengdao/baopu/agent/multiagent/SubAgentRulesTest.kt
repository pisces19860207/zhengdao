// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§2「测试」与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》§9。本文件钉纯判据层：
// 解析准入 / 白名单收敛 / 并发上限 / 重试口径 / 聚合截断 / 提示词隔离。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRulesTest {

    private val allAvailable = SubAgentRules.DEFAULT_SUBAGENT_TOOLS.toSet()

    // ── 解析准入 ─────────────────────────────────────────────────────────

    @Test
    fun `解析_合法两子任务_Ok且id序号白名单都对`() {
        val args = json(
            "goal" to "总结三个文件",
            "subtasks" to arr(
                obj("goal" to "读 a.md 写摘要", "input" to "a.md 在前", "timeout_seconds" to 60),
                obj("goal" to "读 b.md 写摘要", "acceptance" to "不超过 200 字"),
            ),
        )
        val r = SubAgentRules.parseDecomposition(args, allAvailable, sessionId = 42L, now = 1000L, attemptSeq = 1)
        assertTrue("合法分解必须 Ok：$r", r is SubAgentRules.ParseResult.Ok)
        val d = (r as SubAgentRules.ParseResult.Ok).decomposition
        assertEquals(42L, d.sessionId)
        assertEquals("总结三个文件", d.goal)
        assertEquals(2, d.subtasks.size)
        assertEquals(1, d.subtasks[0].seq)
        assertEquals(2, d.subtasks[1].seq)
        assertEquals("sg-42-rs-1", d.parentId) // 1000.toString(36) = "rs"
        assertEquals("sg-42-rs-1-1", d.subtasks[0].id)
        assertEquals("sg-42-rs-1-2", d.subtasks[1].id)
        // 没申请 tools ⇒ 默认全配（天花板 ∩ 可用）
        assertEquals(SubAgentRules.DEFAULT_SUBAGENT_TOOLS, d.subtasks[0].tools)
        // timeout 60s ⇒ 60000ms；第二条没给 ⇒ 默认 90s
        assertEquals(60_000L, d.subtasks[0].timeoutMs)
        assertEquals(SubAgentRules.DEFAULT_TIMEOUT_MS, d.subtasks[1].timeoutMs)
        assertEquals(SubAgentRules.MAX_STEPS, d.subtasks[1].maxSteps)
    }

    @Test
    fun `解析_缺subtasks_空数组_超五条_各拒且理由人话`() {
        val noArr = SubAgentRules.parseDecomposition(JSONObject(), allAvailable, 1L, 0L, 1)
        assertTrue("缺数组要拒：$noArr", noArr is SubAgentRules.ParseResult.Rejected)

        val empty = SubAgentRules.parseDecomposition(json("subtasks" to emptyArray<Any>()), allAvailable, 1L, 0L, 1)
        assertTrue("空数组要拒（不拆就别调）：$empty", empty is SubAgentRules.ParseResult.Rejected)

        val six = json("subtasks" to arr(*Array(6) { obj("goal" to "任务$it") }))
        val tooMany = SubAgentRules.parseDecomposition(six, allAvailable, 1L, 0L, 1)
        assertTrue("超 5 条要拒：$tooMany", tooMany is SubAgentRules.ParseResult.Rejected)
        assertTrue(
            "理由要带数字：${(tooMany as SubAgentRules.ParseResult.Rejected).reason}",
            tooMany.reason.contains("5") && tooMany.reason.contains("6"),
        )
    }

    @Test
    fun `解析_goal缺失或超长_input超长_acceptance超长_各拒`() {
        fun reject(args: JSONObject): String {
            val r = SubAgentRules.parseDecomposition(args, allAvailable, 1L, 0L, 1)
            assertTrue("应当被拒：$r", r is SubAgentRules.ParseResult.Rejected)
            return (r as SubAgentRules.ParseResult.Rejected).reason
        }
        assertTrue(reject(json("subtasks" to arr(obj()))).contains("goal"))
        assertTrue(reject(json("subtasks" to arr(obj("goal" to "x".repeat(201))))).contains("200"))
        assertTrue(reject(json("subtasks" to arr(obj("goal" to "g", "input" to "x".repeat(2001))))).contains("input"))
        assertTrue(
            reject(json("subtasks" to arr(obj("goal" to "g", "acceptance" to "x".repeat(201))))).contains("acceptance"),
        )
        // 第 2 条坏也要整体拒（点名第几条）
        assertTrue(
            reject(
                json("subtasks" to arr(obj("goal" to "好的"), obj("goal" to "  "))),
            ).contains("第 2 条"),
        )
    }

    @Test
    fun `解析_越权与钳制_申请写工具被丢弃_超时钳到边界`() {
        val args = json(
            "subtasks" to arr(
                obj(
                    "goal" to "干活的",
                    "tools" to arr("write_file", "browser_click", "read_file"),
                    "timeout_seconds" to 1,
                ),
                obj("goal" to "另一个", "timeout_seconds" to 9999),
            ),
        )
        val r = SubAgentRules.parseDecomposition(args, allAvailable, 7L, 0L, 3)
        val d = (r as SubAgentRules.ParseResult.Ok).decomposition
        // write_file / browser_click 不在天花板 ⇒ 丢弃，只留 read_file
        assertEquals(listOf("read_file"), d.subtasks[0].tools)
        // 1s ⇒ 钳到 10s；9999s ⇒ 钳到 300s
        assertEquals(SubAgentRules.MIN_TIMEOUT_MS, d.subtasks[0].timeoutMs)
        assertEquals(SubAgentRules.MAX_TIMEOUT_MS, d.subtasks[1].timeoutMs)
    }

    @Test
    fun `解析_用户关了工具_可用集里没有就不给`() {
        // 父会话只解锁了 core 两个 ⇒ find_files/file_info/book_outline 够不着
        val available = setOf("read_file", "list_dir", "search_in_files")
        val r = SubAgentRules.parseDecomposition(
            json("subtasks" to arr(obj("goal" to "查资料"))),
            available, 1L, 0L, 1,
        )
        val d = (r as SubAgentRules.ParseResult.Ok).decomposition
        assertEquals(listOf("read_file", "list_dir", "search_in_files"), d.subtasks[0].tools)
    }

    @Test
    fun `解析_白名单全被关_空名单合法_纯凭input作答`() {
        val r = SubAgentRules.parseDecomposition(
            json("subtasks" to arr(obj("goal" to "靠input作答"))),
            emptySet(), 1L, 0L, 1,
        )
        val d = (r as SubAgentRules.ParseResult.Ok).decomposition
        assertTrue("空名单要合法（子任务可以没有工具）：${d.subtasks[0].tools}", d.subtasks[0].tools.isEmpty())
    }

    // ── 白名单判据 ───────────────────────────────────────────────────────

    @Test
    fun `生效白名单_三种请求形状都对`() {
        // 没给 ⇒ 天花板 ∩ 可用
        assertEquals(allAvailable.toList(), SubAgentRules.effectiveWhitelist(null, allAvailable))
        // 收窄 ⇒ 只留交集的（顺序恒按天花板表序，不按申请顺序）
        assertEquals(
            listOf("read_file", "list_dir"),
            SubAgentRules.effectiveWhitelist(listOf("list_dir", "read_file"), allAvailable),
        )
        // 申请天花板外的 ⇒ 静默丢弃（不是整笔失败）
        assertEquals(allAvailable.toList(), SubAgentRules.effectiveWhitelist(listOf("write_file"), allAvailable))
        // 可用集没有的 ⇒ 不给（模型申请了也够不着）
        assertEquals(
            listOf("read_file"),
            SubAgentRules.effectiveWhitelist(listOf("read_file"), setOf("read_file", "find_files")),
        )
    }

    // ── 并发判据 ─────────────────────────────────────────────────────────

    @Test
    fun `并发上限_还能起几个_全组合`() {
        assertEquals(2, SubAgentRules.nextStartable(running = 0, pending = 3, maxParallel = 2))
        assertEquals(1, SubAgentRules.nextStartable(running = 1, pending = 3, maxParallel = 2))
        assertEquals(0, SubAgentRules.nextStartable(running = 2, pending = 3, maxParallel = 2))
        assertEquals(0, SubAgentRules.nextStartable(running = 2, pending = 0, maxParallel = 2))
        assertEquals(1, SubAgentRules.nextStartable(running = 0, pending = 1, maxParallel = 2))
        // 串行档（派工单 §4：先串行跑通再谈并行）就是同一条判据的 max=1
        assertEquals(1, SubAgentRules.nextStartable(running = 0, pending = 5, maxParallel = 1))
        assertEquals(0, SubAgentRules.nextStartable(running = 1, pending = 5, maxParallel = 1))
        // 脏输入不许变负
        assertEquals(2, SubAgentRules.nextStartable(running = -1, pending = 9, maxParallel = 2))
    }

    // ── 重试判据 ─────────────────────────────────────────────────────────

    @Test
    fun `重试_只救失败与超时_至多一次_取消不救`() {
        assertTrue("首次失败要救", SubAgentRules.shouldRetry(SubTaskStatus.FAILED, attempts = 1))
        assertTrue("首次超时要救", SubAgentRules.shouldRetry(SubTaskStatus.TIMEOUT, attempts = 1))
        assertFalse("第二次不救（有界）", SubAgentRules.shouldRetry(SubTaskStatus.FAILED, attempts = 2))
        assertFalse("取消不救（父都停了）", SubAgentRules.shouldRetry(SubTaskStatus.CANCELLED, attempts = 1))
        assertFalse("成功无所谓重试", SubAgentRules.shouldRetry(SubTaskStatus.DONE, attempts = 1))
    }

    // ── 聚合 ─────────────────────────────────────────────────────────────

    @Test
    fun `聚合_总览逐条原因结果都在_一条不落`() {
        val results = listOf(
            SubTaskResult("t-1", SubTaskStatus.DONE, text = "a.md 讲的是甲", steps = 3, attempts = 1, startedAt = 1_000, endedAt = 2_200),
            SubTaskResult("t-2", SubTaskStatus.FAILED, failureReason = "等模型回复超时（90s）", steps = 1, attempts = 2, startedAt = 1_000, endedAt = 1_500),
            SubTaskResult("t-3", SubTaskStatus.CANCELLED, failureReason = "父任务已停止", steps = 0, attempts = 1),
        )
        val text = SubAgentRules.aggregate(results) { id -> "目标-$id" }
        assertTrue("总览计数：$text", text.contains("1/3 成功"))
        assertTrue("失败计数：$text", text.contains("1 条失败"))
        assertTrue("取消计数：$text", text.contains("1 条取消"))
        assertTrue("成功条带结果：$text", text.contains("a.md 讲的是甲"))
        assertTrue("成功条带耗时步数：$text", text.contains("1.2s") && text.contains("3 步"))
        assertTrue("失败条带原因：$text", text.contains("等模型回复超时"))
        assertTrue("失败条带尝试次数：$text", text.contains("试了 2 次"))
        assertTrue("取消条带原因：$text", text.contains("父任务已停止"))
        assertTrue("逐条带目标：$text", text.contains("目标-t-1"))
        assertTrue("要告诉父模型别自动落产物：$text", text.contains("不会自动写进任何文件"))
    }

    @Test
    fun `聚合_超长结果被截断_总长有上限`() {
        val huge = "内".repeat(SubAgentRules.RESULT_TEXT_CAP + 500)
        val results = List(5) { SubTaskResult("t-$it", SubTaskStatus.DONE, text = huge, steps = 1) }
        val text = SubAgentRules.aggregate(results)
        assertTrue("总长必须 ≤ 上限＋截断标记：${text.length}", text.length <= SubAgentRules.AGGREGATE_CAP + 40)
        assertTrue("截断要有标记：${text.takeLast(60)}", text.contains("截断"))
    }

    @Test
    fun `聚合_空列表有说法_不静默`() {
        assertTrue(SubAgentRules.aggregate(emptyList()).contains("一条都没有"))
    }

    @Test
    fun `截断函数_短原样_长带标记`() {
        assertEquals("短", SubAgentRules.truncate("短", 10))
        val long = SubAgentRules.truncate("一二三四五", 3)
        assertTrue("长的要截：$long", long.startsWith("一二三") && long.contains("截断"))
    }

    // ── 提示词（上下文隔离） ─────────────────────────────────────────────

    @Test
    fun `子任务提示词_两条开局_只有目标输入验收_没有父痕迹`() {
        val task = SubTask(
            id = "t-1", parentId = "p-1", seq = 1,
            goal = "读设定写摘要", input = "资料库/原始/设定.md", acceptance = "300 字内",
        )
        val msgs = SubAgentRules.subTaskMessages(task)
        assertEquals(2, msgs.size)
        assertEquals(LlmMessage.ROLE_SYSTEM, msgs[0].role)
        assertEquals(LlmMessage.ROLE_USER, msgs[1].role)
        assertTrue("系统提示要有纪律：${msgs[0].content}", msgs[0].content.contains("没有写工具"))
        assertTrue("用户消息带目标", msgs[1].content.contains("读设定写摘要"))
        assertTrue("用户消息带输入", msgs[1].content.contains("资料库/原始/设定.md"))
        assertTrue("用户消息带验收", msgs[1].content.contains("300 字内"))
        // 隔离：提示词里不许出现「人设/记忆/上一轮」这类父上下文钩子
        for (m in msgs) {
            assertFalse("子提示词不许带人设：${m.content}", m.content.contains("人设"))
            assertFalse("子提示词不许带记忆：${m.content}", m.content.contains("记忆"))
        }
    }

    // ── id 与状态名 ──────────────────────────────────────────────────────

    @Test
    fun `id格式与条号解析`() {
        // 1000L.toString(36) = "rs"（36 进制压长度，人仍可读）
        assertEquals("sg-42-rs-1", SubAgentRules.decompositionId(42L, 1000L, 1))
        assertEquals("p-3", SubAgentRules.subTaskId("p", 3))
        assertEquals(3, SubAgentRules.seqOf("sg-1-rs-1-3"))
        assertEquals(0, SubAgentRules.seqOf("没有序号"))
        assertEquals("DONE", SubAgentRules.statusName(SubTaskStatus.DONE))
    }

    @Test
    fun `终态判定`() {
        assertTrue(SubTaskStatus.DONE.terminal)
        assertTrue(SubTaskStatus.FAILED.terminal)
        assertTrue(SubTaskStatus.TIMEOUT.terminal)
        assertTrue(SubTaskStatus.CANCELLED.terminal)
        assertFalse(SubTaskStatus.PENDING.terminal)
        assertFalse(SubTaskStatus.RUNNING.terminal)
    }

    @Test
    fun `结果耗时_没跑过是0_跑过算差`() {
        val notRun = SubTaskResult("t", SubTaskStatus.CANCELLED)
        assertEquals(0L, notRun.durationMs)
        val ran = SubTaskResult("t", SubTaskStatus.DONE, startedAt = 100, endedAt = 350)
        assertEquals(250L, ran.durationMs)
        assertTrue(ran.ok)
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().also { j ->
        pairs.forEach { (k, v) -> j.put(k, v) }
    }

    private fun obj(vararg pairs: Pair<String, Any?>): JSONObject = json(*pairs)

    /** 真 JSONArray（org.json 的 put 不会把 Kotlin Array 自动包成 JSONArray——踩过）。 */
    private fun arr(vararg items: Any?): org.json.JSONArray =
        org.json.JSONArray().also { a -> items.forEach { a.put(it) } }

    @Test
    fun `夹具自检_嵌套数组形状就是工具参数形状`() {
        val args = json("subtasks" to arr(obj("goal" to "g")))
        assertNotNull(args.optJSONArray("subtasks"))
        assertEquals("g", args.getJSONArray("subtasks").getJSONObject(0).getString("goal"))
    }
}
