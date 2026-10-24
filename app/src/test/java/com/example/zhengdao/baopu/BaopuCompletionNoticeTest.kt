// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百六十九批 · 接线刀》§3（后台完成才通知、点击进会话、拒绝权限不崩、
// 重复合并、无敏感内容、不常驻）与 §4 判据。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.RunState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完成通知的行为判据（纯规则）＋接线门禁（源码形状）。
 *
 * 行为面钉四件事：结局分类、只后台发、权限拒绝不发、同会话合并窗口。
 * 结构面钉「接线真的在、敏感内容真的没进通知」——行为用例证明不了这些。
 */
class BaopuCompletionNoticeTest {

    private fun file(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun code(rel: String): String = file(rel).readText().replace("\r\n", "\n")
        .split("\n").joinToString("\n") { it.substringBefore("//") }
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val notifierSrc = "src/main/java/com/example/zhengdao/baopu/BaopuCompletionNotifier.kt"
    private val controllerSrc = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
    private val mainSrc = "src/main/java/com/example/zhengdao/MainActivity.kt"
    private val screenSrc = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
    private val appSrc = "src/main/java/com/example/zhengdao/ZhengdaoApp.kt"
    private val approvalSrc = "src/main/java/com/example/zhengdao/ui/baopu/BaopuBackupScreen.kt"

    // ── 结局分类 ─────────────────────────────────────────────────────────

    @Test
    fun 正常结束是成功_用户停止算中止_出错算失败() {
        assertEquals(CompletionOutcome.SUCCESS, BaopuCompletionRules.outcomeOf(RunState.DONE, null))
        assertEquals(
            CompletionOutcome.ABORTED,
            BaopuCompletionRules.outcomeOf(RunState.ABORTED, "已停止（这轮的回答到这儿为止）"),
        )
        assertEquals(
            CompletionOutcome.FAILED,
            BaopuCompletionRules.outcomeOf(RunState.ABORTED, "引擎内部错误（已记录日志）：IOException"),
        )
        assertEquals(
            "没有 notice 的 ABORTED 不能冒充「用户主动停止」",
            CompletionOutcome.FAILED,
            BaopuCompletionRules.outcomeOf(RunState.ABORTED, null),
        )
        assertEquals(CompletionOutcome.SUCCESS, BaopuCompletionRules.subAgentOutcome(ok = true))
        assertEquals(CompletionOutcome.FAILED, BaopuCompletionRules.subAgentOutcome(ok = false))
    }

    // ── 只在后台发 / 权限 / 合并 ─────────────────────────────────────────

    @Test
    fun 前台不弹_后台才弹() {
        assertFalse(
            "前台完成时用户看得见会话，通知只是噪音",
            BaopuCompletionRules.shouldPost(
                foreground = true, permissionGranted = true, nowMs = 1_000,
                lastPostedAtMs = 0, lastSessionId = -1, sessionId = 7,
            ),
        )
        assertTrue(
            BaopuCompletionRules.shouldPost(
                foreground = false, permissionGranted = true, nowMs = 1_000,
                lastPostedAtMs = 0, lastSessionId = -1, sessionId = 7,
            ),
        )
    }

    @Test
    fun 通知权限被拒时不发_不崩() {
        assertFalse(
            "拒绝权限 ⇒ 只更新会话，不弹通知（页面另有明示）",
            BaopuCompletionRules.shouldPost(
                foreground = false, permissionGranted = false, nowMs = 1_000,
                lastPostedAtMs = 0, lastSessionId = -1, sessionId = 7,
            ),
        )
    }

    @Test
    fun 同一会话五秒内合并_不同会话不互相压制() {
        val window = BaopuCompletionRules.MERGE_WINDOW_MS
        assertFalse(
            "子 Agent 完成后一秒整轮又结束 ⇒ 后一条不再响",
            BaopuCompletionRules.shouldPost(
                foreground = false, permissionGranted = true, nowMs = 1_000 + window - 1,
                lastPostedAtMs = 1_000, lastSessionId = 7, sessionId = 7,
            ),
        )
        assertTrue(
            "窗口外照常发",
            BaopuCompletionRules.shouldPost(
                foreground = false, permissionGranted = true, nowMs = 1_000 + window,
                lastPostedAtMs = 1_000, lastSessionId = 7, sessionId = 7,
            ),
        )
        assertTrue(
            "别的会话在窗口内也该发（合并不是全局静音）",
            BaopuCompletionRules.shouldPost(
                foreground = false, permissionGranted = true, nowMs = 1_000 + 1,
                lastPostedAtMs = 1_000, lastSessionId = 7, sessionId = 8,
            ),
        )
    }

    // ── 内容纪律 ─────────────────────────────────────────────────────────

    @Test
    fun 标题含状态_正文不含正文与Key() {
        CompletionKind.entries.forEach { kind ->
            CompletionOutcome.entries.forEach { outcome ->
                val title = BaopuCompletionRules.titleOf(kind, outcome)
                val text = BaopuCompletionRules.textOf(kind, outcome)
                assertTrue("标题要是通知标题且带状态：$title", title.startsWith("抱朴 · "))
                assertTrue("正文要告诉用户点按能做什么：$text", text.contains("点按回到会话"))
                listOf("sk-", "apiKey", "Bearer", "{", "\n").forEach {
                    assertFalse("通知内容不许带敏感/正文片段 [$it]：$title / $text", text.contains(it))
                }
            }
        }
        assertEquals(
            "三种结局必须是三句不同的话",
            3,
            CompletionOutcome.entries.map { BaopuCompletionRules.titleOf(CompletionKind.RUN, it) }.toSet().size,
        )
    }

    // ── 源码门禁：接线与红线 ──────────────────────────────────────────────

    @Test
    fun 整轮结束与子Agent结束都真的挂了通知() {
        val c = code(controllerSrc)
        assertEquals(
            "整轮结束只有一处通知调用",
            1,
            Regex("BaopuCompletionNotifier\\.onRunSettled\\(").findAll(c).count(),
        )
        assertEquals(
            "子 Agent 结束只有一处通知调用",
            1,
            Regex("BaopuCompletionNotifier\\.onSubAgentsFinished\\(").findAll(c).count(),
        )
        val settledBlock = c.substringAfter("if (force) {").substringBefore("// 147：日记 upsert")
        assertTrue("完成通知必须在 Settled 收尾块里", settledBlock.contains("onRunSettled("))
        assertTrue(
            "普通问答（本轮没调工具）不许发完成通知：onRunSettled 必须包在 roundUsedTools 判据里",
            settledBlock.indexOf("if (roundUsedTools)") in 0 until settledBlock.indexOf("onRunSettled("),
        )
        assertTrue("Settled 后必须清零，排队下一轮才从干净状态起算", settledBlock.contains("roundUsedTools = false"))
        assertTrue(
            "工具调用是「长任务」的唯一证据：ToolCallVisible 分支必须置位",
            c.contains("roundUsedTools = true"),
        )
        val toolBlock = c.substringAfter("if (ev is EngineEvent.ToolCallVisible)").substringBefore("val force")
        assertTrue("子 Agent 通知必须挂在 ToolCallVisible 分支", toolBlock.contains("onSubAgentsFinished("))
        assertTrue("必须只认 run_subtasks（普通工具不通知）", toolBlock.contains("SubTaskTool.NAME"))
    }

    @Test
    fun 通知实现遵守后台_权限_合并_不常驻四条红线() {
        val n = code(notifierSrc)
        assertTrue("前台判定必须来自 AppForeground", n.contains("AppForeground.isForeground()"))
        assertTrue("权限必须现查 POST_NOTIFICATIONS", n.contains("Manifest.permission.POST_NOTIFICATIONS"))
        assertTrue("合并窗口必须走纯规则", n.contains("BaopuCompletionRules.shouldPost("))
        assertTrue("通知必须可划掉", n.contains(".setOngoing(false)"))
        assertTrue("同一 id 的后续更新不再响铃", n.contains(".setOnlyAlertOnce(true)"))
        listOf("startForeground", "startService", "foregroundServiceType", "setStyle(")
            .forEach { assertFalse("完成通知不许常驻/带大段正文：$it", n.contains(it)) }
        listOf("summary", "setContentText(notice", "setContentText(ev", "provider.apiKey").forEach {
            assertFalse("通知实现不许碰正文/Key：$it", n.contains(it))
        }
    }

    @Test
    fun 点击通知能把会话id带回抱朴并打开() {
        val m = code(mainSrc)
        assertTrue("MainActivity 必须读 EXTRA_SESSION", m.contains("BaopuIntents.EXTRA_SESSION"))
        assertTrue("MainActivity 必须挂 requestSession", m.contains("requestSession("))
        assertTrue("主页要观察到请求并切抱朴 Tab", m.contains("sessionRequestedState"))
        assertTrue("ON_RESUME 恢复路径也要能切 Tab", m.contains("peekSession()"))
        val s = code(screenSrc)
        assertTrue("抱朴页要消费请求", s.contains("consumeSession()"))
        assertTrue("消费后走既有的 requestOpenSession（不新造切换逻辑）", s.contains("requestOpenSession("))
        assertTrue("通知 extra 常量与消费点必须同名", code(approvalSrc).contains("EXTRA_SESSION"))
    }

    @Test
    fun 前台探针在Application里安装() {
        assertTrue(
            "Application.onCreate 必须装 AppForeground",
            code(appSrc).contains("AppForeground.install(this)"),
        )
    }

    @Test
    fun 权限页写明后台完成不提醒() {
        val zh = file("src/main/res/values/strings.xml").readText()
        val denied = Regex("<string name=\"perm_hint_notify_denied\">(.*?)</string>").find(zh)!!.groupValues[1]
        assertTrue("拒绝权限时页面要明示后果：$denied", denied.contains("抱朴后台跑完"))
        val detail = Regex("<string name=\"perm_notify_detail\">(.*?)</string>").find(zh)!!.groupValues[1]
        assertTrue("权限说明也要点明完成提醒走通知：$detail", detail.contains("完成提醒"))
    }
}
