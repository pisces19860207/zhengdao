// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第四十八批 P0-1（审计 §一）：`ask_user` 的超时口径。
//
// 病灶：`AskUserTool.spec()` 复用 `ToolTimeout.FILE_MS`（5 秒，文件 I/O 口径），
// 而 `RunEngine.askUser()` 最多等 `RunRules.CONFIRM_TIMEOUT_MS`（15 分钟）——
// 工具线程被 `ToolRunner.future.get(5s)` 判超时，`cancel(true)` 又收不回
// （`choiceLock.wait` 的 `InterruptedException` 被 `runCatching` 吞掉），
// `zd-baopu-tool` 单线程 ⇒ 后续每个工具排队并逐个 5 秒超时。
//
// 本文件钉三件事（派工单 §1「测试（必须先红）」）：
//   T1 超时值本身（快测，防回退）；
//   T2 经 GuardPipeline 全链路：6 秒后才点的卡片必须返回「用户选了…」，不是「执行超时」；
//   T3 同一单线程 runner 上，ask 结束后普通工具不再被堵。
//
// 为什么等 6 秒而不是假时钟：这是一条真线程真 Future 的链路（ToolRunner 的
// `future.get(timeout)` 是墙钟），换成假时钟就绕开了要验的那段代码。6 秒
// 只比 5 秒多 1 秒，单测可接受；15 分钟那条由 T1 的常量断言兜住，不真等。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class AskUserTimeoutTest {

    @After
    fun tearDown() {
        AskUserTool.sink = null
    }

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-ask-timeout-$name").toFile()

    private fun askArgs(): JSONObject = JSONObject()
        .put("question", "这三份稿子怎么处理？")
        .put(
            "options",
            JSONArray().apply {
                put(JSONObject().put("id", "keep").put("label", "都留着").put("recommended", true))
                put(JSONObject().put("id", "drop").put("label", "删掉旧的两份"))
            },
        )

    private fun pipeline(root: File): GuardPipeline {
        val reg = ToolRegistry().apply {
            register(AskUserTool.spec())
            register(ReadFileTool.spec())
        }
        // 单线程 runner——与生产 `zd-baopu-tool` 同构：上一条不结束，下一条就得排队
        return GuardPipeline(
            reg,
            { root },
            { null }, // P0-1 不涉及原件区；显式写 null
            ToolRunner(Executors.newSingleThreadExecutor()),
            {},
        )
    }

    // ── T1 超时值（快测，防回退） ─────────────────────────────────────────

    @Test
    fun `T1_ask_user 的超时是确认上限加 5 秒_不是文件 IO 的 5 秒`() {
        val expected = RunRules.CONFIRM_TIMEOUT_MS + 5_000L
        assertEquals(
            "ask_user 等的是用户（15 分钟），不是文件 I/O（5 秒）——复用 FILE_MS 会让每次点卡都先被判超时",
            expected,
            AskUserTool.spec().timeoutMs,
        )
        assertTrue(
            "必须严格大于引擎的等待上限（否则工具先于引擎超时，用户点了也收不到）",
            AskUserTool.spec().timeoutMs > RunRules.CONFIRM_TIMEOUT_MS,
        )
    }

    // ── T2 全链路：6 秒后才点，仍必须拿到选择 ────────────────────────────

    @Test
    fun `T2_经管线_6 秒后才点卡片_结果必须是用户选择而不是执行超时`() {
        val root = tmp("late")
        val started = System.currentTimeMillis()
        AskUserTool.sink = { req ->
            Thread.sleep(6_100) // > 旧口径 5 秒；< 15 分钟
            req.options.first() // 点推荐的第一个
        }
        val result = pipeline(root).execute(
            ToolCall("c-ask", "ask_user", askArgs()),
            { _, _ -> null },
        )
        val waited = System.currentTimeMillis() - started
        assertTrue("这条用例必须真的等过 6 秒（waited=$waited ms）", waited >= 6_000)
        assertTrue(
            "6 秒后的选择必须正常回灌；实得：$result",
            result is GuardPipeline.Result.Done,
        )
        val done = result as GuardPipeline.Result.Done
        assertTrue("回灌文本要说明用户选了什么：${done.wrapped}", done.wrapped.contains("用户选了"))
        assertTrue("不能出现「执行超时」：${done.wrapped}", !done.wrapped.contains("执行超时"))
    }

    // ── T3 队列不被堵 ────────────────────────────────────────────────────

    @Test
    fun `T3_ask 结束后同一单线程 runner 上的普通工具照常返回`() {
        val root = tmp("queue")
        File(root, "a.md").writeText("内容")
        AskUserTool.sink = { req ->
            Thread.sleep(5_200)
            req.options.first()
        }
        val p = pipeline(root)
        val ask = p.execute(ToolCall("c-ask", "ask_user", askArgs()), { _, _ -> null })
        assertTrue("先决条件：ask 本身要成功（$ask）", ask is GuardPipeline.Result.Done)
        val read = p.execute(
            ToolCall("c-read", "read_file", JSONObject().put("path", "a.md")),
            { _, _ -> null },
        )
        assertTrue("ask 结束后普通工具必须正常返回；实得：$read", read is GuardPipeline.Result.Done)
        assertTrue(
            "内容要读得到：${(read as GuardPipeline.Result.Done).wrapped}",
            read.wrapped.contains("内容"),
        )
    }
}
