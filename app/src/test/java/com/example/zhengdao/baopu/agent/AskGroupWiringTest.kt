// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零三批的接线门禁（房型照 AskUserTimeoutTest／StepBudgetGateTest）：
// - 真线程全链路：问题组经 GuardPipeline 走到 groupSink，6 秒后提交 ⇒ 结果必须是
//   `{"answers":{…}}` 一条，不是「执行超时」，且 id 与答案对号；
// - 源码结构：runtime 接了 groupSink、屏幕在输入栏上方渲染多问卡（两种卡都在
//   composer 之前）、多问卡不抢焦点不拦输入（负例 4 的机器判据，截图作视觉证据）、
//   引擎先查账本再拆卡弹卡（负例 6 的接线顺序）。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class AskGroupWiringTest {

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-ask-group-$name").toFile()

    private fun groupArgs(): JSONObject = JSONObject().put(
        "questions",
        JSONArray()
            .put(JSONObject().put("id", "q1").put("title", "删哪些？").put(
                "options",
                JSONArray()
                    .put(JSONObject().put("id", "keep").put("label", "都留着"))
                    .put(JSONObject().put("id", "drop").put("label", "删掉旧的两份")),
            ))
            .put(JSONObject().put("id", "q2").put("title", "几层？").put("kind", "NUMBER"))
            .put(JSONObject().put("id", "q3").put("title", "要报告吗？").put("kind", "BOOL")),
    )

    private fun pipeline(root: File): GuardPipeline = GuardPipeline(
        ToolRegistry().apply { register(AskUserTool.spec()) },
        { root },
        { null },
        ToolRunner(Executors.newSingleThreadExecutor()),
        {},
    )

    // ── 全链路（真线程真 Future） ────────────────────────────────────────────

    @Test
    fun `W1_经管线_6秒后整组提交_一条结果答案齐全id对应`() {
        val root = tmp("late")
        val started = System.currentTimeMillis()
        AskUserTool.groupSink = { req ->
            assertTrue("sink 收到整组 3 项", req.questions.size == 3)
            Thread.sleep(6_100) // > 旧文件口径 5 秒；< 15 分钟
            mapOf("q1" to "drop", "q2" to "2", "q3" to "true")
        }
        try {
            val result = pipeline(root).execute(
                ToolCall("c-ask-group", "ask_user", groupArgs()),
                { _, _ -> null },
            )
            assertTrue("这条用例必须真的等过 6 秒", System.currentTimeMillis() - started >= 6_000)
            assertTrue("必须成功：$result", result is GuardPipeline.Result.Done)
            val wrapped = (result as GuardPipeline.Result.Done).wrapped
            assertTrue("不能出现「执行超时」：$wrapped", !wrapped.contains("执行超时"))
            // wrapped 是带围栏头的回灌文本：从 {"answers" 起用 Tokener 截第一段完整 JSON
            val start = wrapped.indexOf("{\"answers\"")
            assertTrue("回灌文本里没有 answers JSON：$wrapped", start >= 0)
            val answers = (org.json.JSONTokener(wrapped.substring(start)).nextValue() as JSONObject)
                .getJSONObject("answers")
            assertEquals("3 个答案齐全（一卡 3 项一次答完）", 3, answers.length())
            assertEquals("id 对应不许串位", "drop", answers.optString("q1"))
            assertEquals("2", answers.optString("q2"))
            assertEquals("true", answers.optString("q3"))
        } finally {
            AskUserTool.groupSink = null
        }
    }

    // ── 源码结构 ────────────────────────────────────────────────────────────

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `W2_runtime把groupSink接到engine_askGroup`() {
        val rt = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("BaopuRuntime 没接问题组的 sink", rt.contains("AskUserTool.groupSink = { req -> engine.askGroup(req) }"))
    }

    @Test
    fun `W3_多问卡在输入栏上方_与旧选项卡同一位置`() {
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        val cardIdx = screen.indexOf("BaopuQuestionGroupCard(")
        val composerIdx = screen.indexOf("BaopuComposer(")
        assertTrue("屏幕里没渲染多问卡", cardIdx >= 0)
        assertTrue("多问卡必须在输入栏之前（上方）", cardIdx < composerIdx)
        assertTrue("旧选项卡还在（老形状零改动）", screen.contains("BaopuChoiceCard("))
    }

    @Test
    fun `N4_多问卡不抢焦点不拦输入_输入框照常能用`() {
        val card = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuQuestionGroupCard.kt")
        assertFalse("多问卡不许抢焦点（autofocus 会把键盘拽走、挡住输入框）", card.contains("FocusRequester"))
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        val branch = screen.substring(
            screen.indexOf("controller.pendingChoice?.let { req ->"),
            screen.indexOf("BaopuComposer("),
        )
        assertFalse("多问卡分支不许动输入框的可用性（enabled 状态不归它管）", branch.contains("enabled ="))
    }

    @Test
    fun `W4_引擎先查账本再拆卡弹卡_答完才记账`() {
        val engine = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val body = engine.substring(
            engine.indexOf("fun askGroup("),
            engine.indexOf("fun answerGroup("),
        )
        val ledgerIdx = body.indexOf("AskBatchLedger.answers(")
        val splitIdx = body.indexOf("AskGroupRules.split(")
        val recordIdx = body.indexOf("AskBatchLedger.record(")
        assertTrue("askGroup 里没查账本", ledgerIdx >= 0)
        assertTrue("askGroup 里没拆卡", splitIdx >= 0)
        assertTrue("查账本必须在拆卡/弹卡之前（负例 6：先查，免打扰）", ledgerIdx < splitIdx)
        assertTrue("记账必须在答完之后（body 末段）", recordIdx > splitIdx)
    }

    @Test
    fun `W5_控制器与屏幕的回灌链路齐`() {
        val controller = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        assertTrue("控制器没有 answerGroup 透传", controller.contains("fun answerGroup(answers: Map<String, String>) = engine.answerGroup(answers)"))
        val screen = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        assertTrue("屏幕没把提交接到控制器", screen.contains("controller.answerGroup(answers)"))
    }
}
