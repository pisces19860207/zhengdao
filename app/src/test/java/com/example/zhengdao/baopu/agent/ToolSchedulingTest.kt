// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十二批 · 工具调度：**读并行 ＋ 写单飞 ＋ 工具级超时/取消**的用例。
//
// 分三层，各自钉一件事：
// 1. 分类/排段/预算 —— 纯判据，零线程零时钟（跑得快、结论硬）；
// 2. 并行段执行器 —— 真起线程池：并发上限、按提交顺序收口、慢读超时、停止中断、
//    整批到点；
// 3. 单飞语义 —— 用一个小驱动器照引擎的段顺序跑一遍：写与写不并行、写之后的读
//    一定看到新值、读绝不在写结束前开始。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ToolSchedulingTest {

    private var seq = 0

    private fun call(name: String): ToolCall =
        ToolCall("c${seq++}", name, JSONObject().put("path", "a.txt"))

    private inline fun spec(
        name: String,
        readOnly: Boolean = false,
        parallelSafe: Boolean = false,
        permission: ToolPermission = ToolPermission.FREE,
        timeoutMs: Long = 1_000L,
        crossinline body: (JSONObject) -> ToolOutcome = { ToolOutcome.Ok("ok") },
    ): ToolSpec = ToolSpec(
        name = name,
        description = "测试工具",
        inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
        permission = permission,
        timeoutMs = timeoutMs,
        readOnly = readOnly,
        parallelSafe = parallelSafe,
        executor = { args -> body(args) },
    )

    private fun okResult(call: ToolCall) = GuardPipeline.Result.Done(call, "ok", false)

    // ── ① 分类（未标注按写处理：保守） ──────────────────────────────────────

    @Test
    fun `分类_未标注的工具一律按写处理`() {
        val plain = spec("mystery")
        assertFalse("缺省必须 false（保守）", plain.readOnly)
        assertFalse("缺省必须 false（保守）", plain.parallelSafe)
        assertEquals(ToolLane.SERIAL, ToolScheduling.laneOf(plain))
        assertEquals("未知工具也要落串行（让管线自己去拒）", ToolLane.SERIAL, ToolScheduling.laneOf(null))
    }

    @Test
    fun `分类_只读但没标可并行_仍然串行`() {
        // browser_snapshot / preview_local_file / ask_user 这一型：只读盘，但会抢 WebView、
        // 切页签或者干等人——不许并行
        val snapshot = spec("browser_snapshot", readOnly = true)
        assertEquals(ToolLane.SERIAL, ToolScheduling.laneOf(snapshot))
    }

    @Test
    fun `分类_只读且可并行且免确认_才进读并行`() {
        val read = spec("read_file", readOnly = true, parallelSafe = true)
        assertEquals(ToolLane.PARALLEL_READ, ToolScheduling.laneOf(read))
    }

    @Test
    fun `分类_可并行但需确认_落回串行`() {
        // 确认卡必须先于执行出；并行段里没有卡片的位置
        val confirmRead = spec("weird", readOnly = true, parallelSafe = true, permission = ToolPermission.CONFIRM)
        assertEquals(ToolLane.SERIAL, ToolScheduling.laneOf(confirmRead))
    }

    // ── ② 排段（段是屏障） ────────────────────────────────────────────────

    @Test
    fun `排段_连续读合并_写各自独占_写之后的读单独成段`() {
        val r1 = call("read_file")
        val r2 = call("read_file")
        val w = call("write_file")
        val r3 = call("read_file")
        val lanes = mapOf(r1 to ToolLane.PARALLEL_READ, r2 to ToolLane.PARALLEL_READ, r3 to ToolLane.PARALLEL_READ)
        val stages = ToolScheduling.plan(listOf(r1, r2, w, r3)) { lanes[it] ?: ToolLane.SERIAL }

        assertEquals("三段：前两个读并行 → 写单飞 → 写之后的读", 3, stages.size)
        assertEquals(ToolStage.Parallel(listOf(r1, r2)), stages[0])
        assertEquals(ToolStage.Serial(w), stages[1])
        assertEquals("写之后那个读**不许**并回前一段（否则读不到新值）", ToolStage.Serial(r3), stages[2])
    }

    @Test
    fun `排段_单个读不白开并发`() {
        val r1 = call("read_file")
        val stages = ToolScheduling.plan(listOf(r1)) { ToolLane.PARALLEL_READ }
        assertEquals(listOf<ToolStage>(ToolStage.Serial(r1)), stages)
    }

    @Test
    fun `排段_全是写就一段一个_且顺序不丢`() {
        val calls = listOf(call("write_file"), call("edit_file"), call("delete_path"))
        val stages = ToolScheduling.plan(calls) { ToolLane.SERIAL }
        assertEquals(3, stages.size)
        assertEquals(calls.map { ToolStage.Serial(it) }, stages)
        assertEquals("排段不许重排", calls, stages.map { (it as ToolStage.Serial).call })
    }

    // ── ③ 预算（总时限的算法） ────────────────────────────────────────────

    @Test
    fun `预算_并行段取成员最大_串行段取自身_整批求和`() {
        val r1 = call("read_file")
        val r2 = call("library_list")
        val w = call("write_file")
        val timeouts = mapOf("read_file" to 5_000L, "library_list" to 20_000L, "write_file" to 7_000L)
        fun timeoutOf(c: ToolCall) = timeouts.getValue(c.name)

        val parallel = ToolStage.Parallel(listOf(r1, r2))
        assertEquals("并行的取最大（它们同时跑）", 20_000L, ToolScheduling.stageBudgetMs(parallel, ::timeoutOf))
        assertEquals(7_000L, ToolScheduling.stageBudgetMs(ToolStage.Serial(w), ::timeoutOf))

        val batch = ToolScheduling.batchBudgetMs(listOf(parallel, ToolStage.Serial(w)), ::timeoutOf)
        assertEquals("整批 = 各段预算之和", 27_000L, batch)
    }

    // ── ④ 并行段执行器（真线程池） ─────────────────────────────────────────

    @Test
    fun `并行_两个读同时跑_总耗时明显小于串行`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val arrived = CountDownLatch(2)
            val both = AtomicBoolean(true)
            val runner = ToolBatchRunner(pool)
            val stage = ToolStage.Parallel(listOf(call("read_file"), call("read_file")))
            val startedAt = System.currentTimeMillis()
            val items = runner.runParallel(
                stage = stage,
                timeoutOf = { 2_000L },
                execute = { _, c ->
                    // 真并行的判据：**两个都到了栅栏**才放行。串行时第一个到了没人接
                    // ⇒ await 超时 ⇒ both=false（这条用例就红了）。
                    arrived.countDown()
                    if (!arrived.await(2, TimeUnit.SECONDS)) both.set(false)
                    okResult(c)
                },
                shouldStop = { false },
            )
            val elapsed = System.currentTimeMillis() - startedAt
            assertTrue("两个读必须同时在跑（串行的话第二个到不了栅栏）", both.get())
            assertTrue("两个读应当同时收口，实测 ${elapsed}ms（串行至少 2 秒）", elapsed < 1_500L)
            assertEquals(2, items.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `并行_并发上限不超过池子大小_读池就是那个数`() {
        val cap = ToolScheduling.MAX_READ_PARALLEL
        assertEquals("读池线程数 = 并发上限（结构性封顶，不靠计数器自觉）", cap, ToolScheduling.MAX_READ_PARALLEL)
        val pool = Executors.newFixedThreadPool(cap)
        try {
            val running = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val runner = ToolBatchRunner(pool)
            val stage = ToolStage.Parallel((1..cap + 3).map { call("read_file") })
            runner.runParallel(
                stage = stage,
                timeoutOf = { 5_000L },
                execute = { _, c ->
                    val now = running.incrementAndGet()
                    peak.updateAndGet { maxOf(it, now) }
                    try {
                        Thread.sleep(60)
                        okResult(c)
                    } finally {
                        running.decrementAndGet()
                    }
                },
                shouldStop = { false },
            )
            assertTrue("并发峰值 ${peak.get()} 超过了上限 $cap", peak.get() <= cap)
            assertTrue("并发峰值只有 ${peak.get()}，说明根本没并行起来", peak.get() >= 2)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `并行_结果按提交顺序收口_不按完成顺序`() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val runner = ToolBatchRunner(pool)
            val calls = listOf(call("slow_read"), call("fast_read"), call("mid_read"))
            val delays = mapOf("slow_read" to 120L, "fast_read" to 5L, "mid_read" to 60L)
            val items = runner.runParallel(
                stage = ToolStage.Parallel(calls),
                timeoutOf = { 2_000L },
                execute = { _, c ->
                    Thread.sleep(delays.getValue(c.name))
                    GuardPipeline.Result.Done(c, c.name, false)
                },
                shouldStop = { false },
            )
            assertEquals("回灌顺序必须与模型给的顺序一致", calls, items.map { it.call })
            assertEquals(
                "每个结果必须属于它自己那次调用（张冠李戴比慢更糟）",
                calls.map { it.name },
                items.map { (it.result as GuardPipeline.Result.Done).wrapped },
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `并行_慢读超时走失败_不拖死同段其它读`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runner = ToolBatchRunner(pool)
            val slow = call("slow_read")
            val fast = call("fast_read")
            val startedAt = System.currentTimeMillis()
            val items = runner.runParallel(
                stage = ToolStage.Parallel(listOf(slow, fast)),
                timeoutOf = { if (it.name == "slow_read") 120L else 2_000L },
                execute = { _, c ->
                    if (c.name == "slow_read") {
                        Thread.sleep(3_000) // 远超它自己的 120ms 上限
                        GuardPipeline.Result.Done(c, "慢读居然回来了", false)
                    } else {
                        Thread.sleep(20)
                        GuardPipeline.Result.Done(c, "快读回来了", false)
                    }
                },
                shouldStop = { false },
            )
            val elapsed = System.currentTimeMillis() - startedAt
            val slowItem = items[0]
            val fastItem = items[1]
            assertTrue(
                "慢读必须是失败（超时），实际=${slowItem.result}",
                slowItem.result is GuardPipeline.Result.Failed &&
                    (slowItem.result as GuardPipeline.Result.Failed).error.contains("超时"),
            )
            assertTrue("慢读的超时原文要带上限（与串行路同一句）",
                (slowItem.result as GuardPipeline.Result.Failed).error.contains("上限 120 ms"))
            assertTrue("快读必须照常成功", fastItem.result is GuardPipeline.Result.Done)
            assertTrue("一个慢读不许拖死整段，实测 ${elapsed}ms", elapsed < 1_500L)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `并行_停止信号能中断读`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val runner = ToolBatchRunner(pool)
            val started = AtomicBoolean(false)
            val items = runner.runParallel(
                stage = ToolStage.Parallel(listOf(call("read_file"), call("read_file"))),
                timeoutOf = { 5_000L },
                execute = { _, c ->
                    started.set(true)
                    Thread.sleep(3_000)
                    okResult(c)
                },
                shouldStop = { started.get() }, // 一开跑就点停止
            )
            assertTrue("两次调用都要被标记成「没采信」", items.all { it.cancelled })
            assertTrue("取消的调用不许有结果（结果为空才是如实）", items.all { it.result == null })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `并行_整批总时限到点按超时收口并写明是谁到点`() {
        val pool = Executors.newFixedThreadPool(1)
        try {
            val runner = ToolBatchRunner(pool)
            val c = call("read_file")
            val items = runner.runParallel(
                stage = ToolStage.Parallel(listOf(c, call("read_file"))),
                timeoutOf = { 10_000L },
                execute = { _, x ->
                    Thread.sleep(2_000)
                    okResult(x)
                },
                shouldStop = { false },
                deadlineAtMs = System.currentTimeMillis() + 80L,
            )
            val failed = items.mapNotNull { it.result as? GuardPipeline.Result.Failed }
            assertTrue("到点必须收成失败（不许当成功）", failed.isNotEmpty())
            assertTrue(
                "文案要点明「本批总时限」——与工具自己超时分开记，回放才看得出是谁到点",
                failed.first().error.contains("本批总时限"),
            )
            assertTrue("仍然要能被失败策略认成工具超时", failed.first().error.contains("超时"))
        } finally {
            pool.shutdownNow()
        }
    }

    // ── ⑤ 单飞语义（照引擎的段顺序跑一遍） ────────────────────────────────

    @Test
    fun `单飞_两个写严格串行_写之后读看到新值`() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val runner = ToolBatchRunner(pool)
            val sharedFile = StringBuilder("v0")
            val order = mutableListOf<String>()
            // 记录「谁在跑」的区间，用来证明写与写不重叠、读不在写结束前开始
            var writeDepth = AtomicInteger(0)
            var writeOverlap = AtomicBoolean(false)
            val w1 = call("write_file")
            val w2 = call("edit_file")
            val r = call("read_file")
            val lanes = mapOf(w1.name to ToolLane.SERIAL, w2.name to ToolLane.SERIAL, r.name to ToolLane.PARALLEL_READ)
            // 这一段故意让读也标成可并行 ⇒ 排段会把它排成「单读段」（退化串行），
            // 语义仍是「读在写之后」。真正要钉的是段顺序，不是段型。
            val stages = ToolScheduling.plan(listOf(w1, w2, r)) { lanes[it.name] ?: ToolLane.SERIAL }

            val seen = mutableListOf<String>()
            for (stage in stages) {
                when (stage) {
                    is ToolStage.Serial -> {
                        val c = stage.call
                        when (c.name) {
                            "write_file" -> {
                                if (writeDepth.incrementAndGet() > 1) writeOverlap.set(true)
                                order += "write_file:start"
                                Thread.sleep(40)
                                sharedFile.setLength(0)
                                sharedFile.append("v1")
                                order += "write_file:end"
                                writeDepth.decrementAndGet()
                            }
                            "edit_file" -> {
                                if (writeDepth.incrementAndGet() > 1) writeOverlap.set(true)
                                order += "edit_file:start"
                                Thread.sleep(40)
                                sharedFile.append("+v2")
                                order += "edit_file:end"
                                writeDepth.decrementAndGet()
                            }
                            else -> seen += sharedFile.toString()
                        }
                    }
                    is ToolStage.Parallel -> {
                        val items = runner.runParallel(
                            stage = stage,
                            timeoutOf = { 1_000L },
                            execute = { _, c ->
                                seen += sharedFile.toString() // 读到的必须是写完之后的值
                                okResult(c)
                            },
                            shouldStop = { false },
                        )
                        assertTrue(items.all { it.result is GuardPipeline.Result.Done })
                    }
                }
            }
            assertFalse("写与写重叠了（单飞被破坏）", writeOverlap.get())
            assertEquals(
                "两次写必须严格串行且顺序正确",
                listOf("write_file:start", "write_file:end", "edit_file:start", "edit_file:end"),
                order,
            )
            assertEquals("写之后的读必须看到新值（v1+v2）", listOf("v1+v2"), seen)
        } finally {
            pool.shutdownNow()
        }
    }
}
