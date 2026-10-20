// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十二批 · 工具调度的**门禁**（派工单 §2 的负例在这里落地）。
//
// 这些用例不看「代码长什么样」，只看三件会被悄悄改坏的事：
// 1. 分类自洽：写工具/确认工具**永远**进不了并行段（负例：故意标错 ⇒ 立刻红）；
// 2. 并行集合不许自己长大：并行名单是一个写死的期望集，加一个工具必须回来改这里
//    （否则「顺手标个 parallelSafe」就能让写工具开始并发改盘，而没人会注意到）；
// 3. 确认卡仍在执行前：`execute` 只出卡不执行、`executeApproved` 才执行——
//    把确认挪到执行之后，这条立刻红（派工单 §2 的第二个负例）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ToolSchedulingGateTest {

    /** 真实工具表（JVM 里能建的那份：ENTRIES 的工厂只吃 `workspaceRoot`）。 */
    private fun catalog(): List<ToolSpec> =
        BaopuToolCatalog.ENTRIES.map { it.factory { File(".") } }

    /**
     * 允许并行的**全部**工具（写死）。
     *
     * 为什么写死：`parallelSafe` 是个「加上去就生效」的开关，一旦有人给某个会写盘/
     * 抢 WebView 的工具顺手打开，运行时就真的开始并发——这种退化不会报错，只会偶发丢更新。
     * 这张表让任何新增/删改都必须回来改一行显式期望，review 时躲不过去。
     */
    private val EXPECTED_PARALLEL_READS = setOf(
        "read_file", "list_dir", "search_in_files", "find_files",
        "book_outline", "file_info", "library_list", "card_inspect",
    )

    // ── ① 分类自洽 ────────────────────────────────────────────────────────

    @Test
    fun `门禁_真实工具表的并行集合恰好是这几个文件读`() {
        val parallel = catalog().filter { it.parallelSafe }.map { it.name }.toSet()
        assertEquals(
            "并行集合变了：要么是这批有意的扩展（回来改这张期望表），要么是顺手标错（改回去）",
            EXPECTED_PARALLEL_READS,
            parallel,
        )
    }

    @Test
    fun `门禁_真实工具表分类自洽`() {
        val errors = ToolScheduling.classificationErrors(catalog())
        assertTrue("分类自洽性被破坏：\n${errors.joinToString("\n")}", errors.isEmpty())
    }

    @Test
    fun `门禁_并行名单里的工具都必须是只读`() {
        catalog().filter { it.parallelSafe }.forEach {
            assertTrue("「${it.name}」标了可并行却不是 readOnly", it.readOnly)
            assertEquals("并行工具必须免确认（确认卡没有并行的位置）", ToolPermission.FREE, it.permission)
        }
    }

    @Test
    fun `门禁_写工具永不进并行段`() {
        val calls = catalog().map { ToolCall("g-${it.name}", it.name, JSONObject()) }
        val byName = catalog().associateBy { it.name }
        val stages = ToolScheduling.plan(calls) { c -> ToolScheduling.laneOf(byName[c.name]) }
        val offenders = stages.filterIsInstance<ToolStage.Parallel>()
            .flatMap { it.calls }
            .filterNot { byName.getValue(it.name).readOnly }
        assertTrue("写工具混进了并行段：${offenders.map { it.name }}", offenders.isEmpty())
        assertEquals("排段必须覆盖整批、一个不漏", calls.size, stages.sumOf {
            when (it) {
                is ToolStage.Serial -> 1
                is ToolStage.Parallel -> it.calls.size
            }
        })
    }

    @Test
    fun `门禁_确认工具永不进并行段`() {
        val confirm = catalog().filter { it.permission == ToolPermission.CONFIRM }
        assertTrue("前置：真实表里本来就有确认工具（没有的话这条门禁是空转）", confirm.isNotEmpty())
        confirm.forEach {
            assertEquals("「${it.name}」是可确认的写工具，必须走串行", ToolLane.SERIAL, ToolScheduling.laneOf(it))
        }
    }

    // ── ② 负例：标错立刻红（证明上面那几条门禁不是空转） ──────────────────

    @Test
    fun `负例_把写工具标成可并行_门禁立刻红`() {
        val write = catalog().first { it.name == "write_file" }
        val tampered = write.copy(parallelSafe = true) // 只加一个标记，别的都不动
        val errors = ToolScheduling.classificationErrors(listOf(tampered))
        assertTrue("写工具标了 parallelSafe 必须被抓出来，实际：$errors", errors.isNotEmpty())
        assertTrue("要指名道姓说明是哪个工具", errors.first().contains("write_file"))
        assertEquals(
            "标错的写工具不许被调度成并行读",
            ToolLane.SERIAL,
            ToolScheduling.laneOf(tampered),
        )
    }

    @Test
    fun `负例_把确认工具标成可并行_门禁立刻红`() {
        // 真实表里没有「只读的确认工具」，所以这里自己造一个（正是要拦的那种配置）
        val tampered = ToolSpec(
            name = "confirm_read",
            description = "只读但要确认（假设出来的错配）",
            inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
            permission = ToolPermission.CONFIRM,
            readOnly = true,
            parallelSafe = true,
            executor = { _ -> ToolOutcome.Ok("x") },
        )
        val errors = ToolScheduling.classificationErrors(listOf(tampered))
        assertTrue("确认工具标了 parallelSafe 必须被抓出来，实际：$errors", errors.isNotEmpty())
        assertTrue("要指名道姓说明是哪个工具", errors.first().contains("confirm_read"))
        assertEquals("确认工具即使标了也不许进并行", ToolLane.SERIAL, ToolScheduling.laneOf(tampered))
    }

    // ── ③ 确认卡仍在执行前（派工单 §2 的第二个负例） ──────────────────────

    @Test
    fun `门禁_确认卡先于执行_没批准前执行器一次都不跑`() {
        val ran = AtomicInteger(0)
        val reg = ToolRegistry().apply {
            register(
                ToolSpec(
                    name = "confirm_write",
                    description = "需确认的写工具（本用例只关心「先问后做」）",
                    inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
                    permission = ToolPermission.CONFIRM,
                    executor = { _ ->
                        ran.incrementAndGet()
                        ToolOutcome.Ok("写了")
                    },
                ),
            )
        }
        val pipeline = GuardPipeline(reg, { File(".") }, { null }, ToolRunner(Executors.newSingleThreadExecutor()))
        val call = ToolCall("g1", "confirm_write", JSONObject())

        val first = pipeline.execute(call) { _, _ -> null }
        assertTrue("第一次必须是 NeedsConfirm：$first", first is GuardPipeline.Result.NeedsConfirm)
        assertEquals("确认卡必须在**执行前**出——执行器一次都不许跑", 0, ran.get())

        val approved = pipeline.executeApproved(call) { _, _ -> null }
        assertTrue("批准后才是 Done：$approved", approved is GuardPipeline.Result.Done)
        assertEquals("批准后恰好执行一次", 1, ran.get())

        // 负例：若有人把确认挪到执行之后，上面第一个断言（ran==0）必然先红
        assertTrue("NeedsConfirm 里带的是有效调用（批准后要按它执行）",
            (first as GuardPipeline.Result.NeedsConfirm).call.name == "confirm_write")
    }

    @Test
    fun `门禁_并行读那条路仍要先过路径守卫与Schema`() {
        val reg = ToolRegistry().apply { register(ReadFileTool.spec()) }
        val root = File(".").absoluteFile
        val pipeline = GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()))

        val outside = pipeline.prepare(
            ToolCall("g1", "read_file", JSONObject().put("path", "../外面.txt")),
        )
        assertTrue("越界路径在 prepare 阶段就该被拒（不许进了并发池才拒）", outside is GuardPipeline.Prepared.Settled)
        assertTrue((outside as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.Rejected)

        val badSchema = pipeline.prepare(
            ToolCall("g2", "read_file", JSONObject().put("path", "a.txt").put("nope", 1)),
        )
        assertTrue("Schema 错也在 prepare 阶段定论", badSchema is GuardPipeline.Prepared.Settled)
        assertTrue((badSchema as GuardPipeline.Prepared.Settled).result is GuardPipeline.Result.Failed)

        val fine = pipeline.prepare(ToolCall("g3", "read_file", JSONObject().put("path", "a.txt")))
        assertTrue("合法调用才拿到 Ready：$fine", fine is GuardPipeline.Prepared.Ready)
    }

    // ── ④ 失败语义与串行逐字相同（并行只改「谁先谁后」） ──────────────────

    @Test
    fun `并行读_崩了的原文与串行同一句`() {
        val reg = ToolRegistry().apply {
            register(
                ToolSpec(
                    name = "boom_read",
                    description = "只读但会抛的工具",
                    inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
                    readOnly = true,
                    parallelSafe = true,
                    executor = { _ -> throw IllegalStateException("炸了") },
                ),
            )
        }
        val pipeline = GuardPipeline(reg, { File(".") }, { null }, ToolRunner(Executors.newSingleThreadExecutor()))
        val call = ToolCall("g1", "boom_read", JSONObject())
        val inline = pipeline.executeInline(call) { _, _ -> null }
        val serial = pipeline.execute(call) { _, _ -> null }
        assertEquals(
            "两条 lane 的失败原文必须逐字相同（否则模型看到的「失败」随 lane 漂移）",
            (serial as GuardPipeline.Result.Failed).error,
            (inline as GuardPipeline.Result.Failed).error,
        )
        assertTrue("原文要能认成工具崩了", inline.error.contains("执行崩了"))
    }

    @Test
    fun `并行读_超时只在调度层封_管线内不再上一次超时`() {
        val reg = ToolRegistry().apply {
            register(
                ToolSpec(
                    name = "slow_read",
                    description = "只读但慢",
                    inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
                    timeoutMs = 40L,
                    readOnly = true,
                    parallelSafe = true,
                    executor = { _ ->
                        Thread.sleep(150)
                        ToolOutcome.Ok("慢但回来了")
                    },
                ),
            )
        }
        val pipeline = GuardPipeline(reg, { File(".") }, { null }, ToolRunner(Executors.newSingleThreadExecutor()))
        val call = ToolCall("g1", "slow_read", JSONObject())

        // 串行 lane：ToolRunner 自己带超时
        assertTrue(
            "串行路必须按 timeoutMs 判超时",
            pipeline.execute(call) { _, _ -> null } is GuardPipeline.Result.Failed,
        )
        // 并行 lane：管线直跑（不再上一次超时），超时由 ToolBatchRunner 的 deadline 封
        assertTrue(
            "并行路的 executeInline 不自己判超时（否则就是两次超时、两套文案）",
            pipeline.executeInline(call) { _, _ -> null } is GuardPipeline.Result.Done,
        )
    }

    // ── ⑤ 源码门禁：并行段真的走「先 prepare 再 executeInline」 ────────────

    @Test
    fun `门禁_引擎的并行段先过三段再并发执行`() {
        val src = File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt") }
            .readText()
        val prepare = src.indexOf("pipeline.prepare(")
        val inline = src.indexOf("pipeline.executeInline(")
        val serial = src.indexOf("pipeline.execute(call)")
        assertTrue("并行段的执行入口找不到", inline > 0)
        assertTrue("串行入口必须还在（写工具与确认卡走它）", serial > 0)
        assertTrue("①②③ 必须在并发之前跑完（prepare 要先于 executeInline）", prepare in 0 until inline)
        assertEquals("executeInline 只许有一个调用点（第二条链＝第二套语义）", 1, Regex("pipeline\\.executeInline\\(").findAll(src).count())
        assertFalse(
            "并行池不许自己在管线里再 submit 一次（池内等自己＝自锁）",
            File("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt")
                .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt") }
                .readText()
                .substringAfter("fun executeInline(")
                .substringBefore("fun prepare(")
                .contains("runner.run("),
        )
    }
}
