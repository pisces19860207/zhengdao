// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第一百一十七批派工单《抱朴多任务并发 v1》§测试（负例每条都要能变红）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多任务并发 v1 的**接线门禁**（源码级，JVM）。
 *
 * 纯判据（受理/上限/接力）由 [com.example.zhengdao.baopu.agent.RunQueueTest] 直接测；
 * 这里只钉那些「换了实现就会静默坏掉」的接线点，并且每条都配一个**判别器**：
 * 把关键那句从源码里删掉，判据必须返回 false（证明它真在盯着那句话，而不是盯空气）。
 *
 * 派工单点名的四条负例分别落在：
 * - [N1] 同会话两个 run 同时跑；
 * - [N2] 取消后排队条仍然开跑/写库；
 * - [N3] 离开页面后台丢消息/不续跑；
 * - [N4] 队列满还清输入框（用户原话被吞）。
 */
class MultitaskWiringTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()

    private val engine: String
        get() = src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")

    private val controller: String
        get() = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private val composer: String
        get() = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private val screen: String
        get() = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun slice(text: String, startAnchor: String, endAnchor: String): String {
        val start = text.indexOf(startAnchor)
        require(start >= 0) { "找不到起点：$startAnchor" }
        val end = text.indexOf(endAnchor, start + startAnchor.length)
        return if (end < 0) text.substring(start) else text.substring(start, end)
    }

    // ── 判别用的接线判据（每个都吃「源码字符串」，便于喂变异体） ────────────

    /** 门必须先抢到，才允许入队/开跑；同一会话同时只有一个 drain。 */
    private fun oneRunAtATime(runEngine: String): Boolean {
        val send = slice(runEngine, "fun send(", "fun queuedCount(")
        val acquire = send.indexOf("ActiveSessionGate.tryAcquire(key)")
        val enqueue = send.indexOf("runQueue.enqueue(turn)")
        return acquire >= 0 && enqueue > acquire &&
            send.contains("if (!verdict.accepted) return verdict") &&
            runEngine.contains("if (!draining.add(sessionId)) return")
    }

    /** 停止 = 清排队 ＋ 取出后二次检查 ＋ 落用户消息前再检查。 */
    private fun cancelStopsQueued(runEngine: String): Boolean {
        val stop = slice(runEngine, "fun stop()", "fun resume(")
        val drain = slice(runEngine, "private fun drainQueue(", "private fun ensureQueueRow(")
        val persistGate = slice(runEngine, "if (userText != null) {", "history += HistoryMessage(")
        val poll = drain.indexOf("runQueue.poll(sessionId)")
        val recheck = drain.indexOf("if (stopRequested)", poll)
        return stop.contains("clearQueue(sid)") &&
            poll >= 0 && recheck > poll &&
            persistGate.contains("if (stopRequested)") &&
            persistGate.indexOf("if (stopRequested)") < persistGate.indexOf("persistUserMessage")
    }

    /** 入队即落库；冷启动恢复进内存但**不自动跑**。 */
    private fun queueSurvivesRestart(runEngine: String): Boolean {
        val send = slice(runEngine, "fun send(", "fun queuedCount(")
        val restore = slice(runEngine, "private fun restoreQueuedTurns(", "private fun persistQueuedTurn(")
        return send.contains("persist = persistQueuedTurn(sessionId, text, imageRelPaths)") &&
            runEngine.contains("runExecutor.execute { restoreQueuedTurns() }") &&
            restore.contains("BaopuSql.selectAllRunQueue()") &&
            !restore.contains("startDrain(") && !restore.contains("resumeQueue(") &&
            restore.contains("不自动开跑")
    }

    /** 被拒时输入框不清：controller 回 Boolean，composer 只在 accepted 时清。 */
    private fun keepsTextOnRefusal(ctrl: String, composerSrc: String): Boolean {
        val send = slice(ctrl, "fun send(text: String", "/**")
        return send.contains("return verdict.accepted") &&
            composerSrc.contains("onSend: (String, List<BaopuAttachments.Saved>) -> Boolean") &&
            composerSrc.contains("val accepted = onSend(") &&
            composerSrc.contains("if (accepted) {")
    }

    // ── N1：同会话绝不允许两个 run ──────────────────────────────────────

    @Test
    fun `N1_同会话先抢门再入队_单飞drain`() {
        assertTrue("同会话并发 run 的守门接线不完整", oneRunAtATime(engine))
        assertFalse(
            "判别器：删掉抢门那一句，判据必须变红",
            oneRunAtATime(engine.replace("if (!ActiveSessionGate.tryAcquire(key)) {", "if (false) {")),
        )
        assertFalse(
            "判别器：允许并起第二个 drain，判据必须变红",
            oneRunAtATime(engine.replace("if (!draining.add(sessionId)) return", "")),
        )
    }

    // ── N2：取消后不许再开跑/写库 ───────────────────────────────────────

    @Test
    fun `N2_停止清队列_取出后与落库前都有检查`() {
        assertTrue("取消后排队条仍会开跑的接线漏洞", cancelStopsQueued(engine))
        assertFalse(
            "判别器：stop 不清队列，判据必须变红",
            cancelStopsQueued(engine.replace("val n = clearQueue(sid)", "val n = 0")),
        )
        assertFalse(
            "判别器：拿掉 drain/落库前的 stopRequested 检查，判据必须变红",
            cancelStopsQueued(engine.replace("if (stopRequested) {", "if (false) {")),
        )
    }

    // ── N3：离开页面不丢消息、后台继续 ──────────────────────────────────

    @Test
    fun `N3_消息入队即落库_后台跑在引擎单例`() {
        assertTrue("重启恢复/入队落库接线不完整", queueSurvivesRestart(engine))
        assertFalse(
            "判别器：冷启动不恢复队列，判据必须变红",
            queueSurvivesRestart(engine.replace("runExecutor.execute { restoreQueuedTurns() }", "")),
        )
        // 队列是引擎字段（进程级），不挂在页面/控制器上：离开页面只是不画，不停止。
        assertTrue("队列必须是引擎字段", engine.contains("private val runQueue = SessionRunQueue()"))
        val drain = slice(engine, "private fun drainQueue(", "private fun ensureQueueRow(")
        assertFalse("drain 不许依赖任何 UI 生命周期", drain.contains("onPause") || drain.contains("onDispose"))
        val disposeLines = screen.lines().filter { it.contains("onDispose") }
        assertTrue("屏幕上应能找到 onDispose（否则这条判据没盯到东西）", disposeLines.isNotEmpty())
        assertTrue(
            "离开页面的 onDispose 不许停止任务：$disposeLines",
            disposeLines.none { it.contains("stop()") },
        )
        // 回来能看见状态：控制器 refresh 从引擎读队列计数，QueueChanged 只认本会话。
        assertTrue(controller.contains("engine.queuedCount(sid)"))
        assertTrue(controller.contains("if (ev is EngineEvent.QueueChanged && ev.sessionId == sessionId)"))
    }

    // ── N4：队列满/别会话在跑 ⇒ 拒绝且保留原文 ─────────────────────────

    @Test
    fun `N4_拒绝时原文留在输入框`() {
        assertTrue("拒绝路径会把用户原话吞掉", keepsTextOnRefusal(controller, composer))
        assertFalse(
            "判别器：controller 不再回受理结果，判据必须变红",
            keepsTextOnRefusal(controller.replace("return verdict.accepted", "return true"), composer),
        )
        assertFalse(
            "判别器：composer 无条件清空，判据必须变红",
            keepsTextOnRefusal(controller, composer.replace("if (accepted) {", "if (true) {")),
        )
        // 跑着也能输入/发送（不再 enabled = !busy），否则「排队」根本没有入口。
        assertFalse("跑着时输入框不许禁用", composer.contains("enabled = !busy"))
        assertTrue("跑着时发送键必须还在", composer.contains("Text(stringResource(R.string.baopu_send))"))
        assertTrue("跑着时有停止键", composer.contains("onClick = { onStop() }"))
    }

    // ── 取消后的下一轮必须干净 ─────────────────────────────────────────

    @Test
    fun `N5_新的一轮清掉上一轮的停止与通知与截断`() {
        val send = slice(engine, "fun send(", "fun queuedCount(")
        val started = slice(send, "if (verdict == SendAcceptance.STARTED) {", "val turn = QueuedTurn(")
        assertTrue("新一轮必须清 stopRequested", started.contains("stopRequested = false"))
        assertTrue("新一轮必须清旧 notice", started.contains("_notice.value = null"))
        assertTrue("新一轮必须清截断标记", started.contains("_tailTruncated.value = false"))
        // 只有 STARTED 才清本轮用量（排队消息不许把正在跑那轮的账抹掉）。
        assertTrue(
            "控制器只在本轮真开跑时 resetRoundUsage",
            controller.contains("if (verdict == SendAcceptance.STARTED) {") &&
                controller.contains("resetRoundUsage()"),
        )
    }

    // ── 多会话 v1：拒绝跨会话、计数不串 ────────────────────────────────

    @Test
    fun `N6_多会话状态不串_计数跟当前会话`() {
        assertTrue(controller.contains("if (ev is EngineEvent.QueueChanged && ev.sessionId == sessionId)"))
        assertTrue(
            "切/新会话必须先把计数归零，再由 refresh 写回真值",
            controller.contains("queuedCount = 0"),
        )
        assertTrue(
            "refresh 必须按当前会话读引擎队列",
            controller.contains("val queued = if (sid == null) 0 else engine.queuedCount(sid)"),
        )
        assertTrue(
            "别的会话在跑时发送必须走明确拒绝（不排进别人队列）",
            engine.contains("SendAcceptance.REFUSED_BUSY_OTHER_SESSION"),
        )
    }

    // ── 失败暂停队列（接第八十七批：不许静默接力） ─────────────────────

    @Test
    fun `N7_失败不自动跑下一条_也不静默`() {
        val drain = slice(engine, "private fun drainQueue(", "private fun ensureQueueRow(")
        assertTrue("drain 必须用 canAutoAdvance 判断接力", drain.contains("RunQueueRules.canAutoAdvance("))
        assertTrue("不能接力就停手（剩余条目保留）", drain.contains("break"))
        assertFalse("失败路径不许顺手清队列", drain.contains("runQueue.clear("))
        assertTrue("拒绝必须有可见 notice", engine.contains("排队已满（最多"))
    }

    // ── 重启边界：删队列行与插用户消息同一事务 ─────────────────────────

    @Test
    fun `N8_开跑事务里删队列行与插用户消息原子`() {
        val persist = slice(engine, "private fun persistUserMessage(", "private fun persistAssistantMessage(")
        val begin = persist.indexOf("d.beginTransaction()")
        val del = persist.indexOf("deleteRunQueue(queueRowId)", begin)
        val msg = persist.indexOf("insertMessage(sessionId, seq, HistoryMessage.ROLE_USER", begin)
        val ok = persist.indexOf("d.setTransactionSuccessful()", begin)
        assertTrue("删队列行必须发生在插用户消息之前", begin in 0 until del && del < msg)
        assertTrue("删队列行必须在同一个事务内、提交之前", msg < ok)
        assertTrue("队列行 id 要真正用上（0/负值不许删）", persist.contains("if (queueRowId > 0)"))
        assertTrue(
            "落库成功的事件必须带上这条消息自己的图路径",
            persist.contains("EngineEvent.MessagePersisted(msgId, HistoryMessage.ROLE_USER, imageRelPaths)"),
        )
    }

    // ── 输入栏状态行/按钮接线 ──────────────────────────────────────────

    @Test
    fun `N9_输入栏有排队状态行_屏幕接上四件事`() {
        assertTrue(composer.contains("R.plurals.baopu_queue_count"))
        assertTrue(composer.contains("onContinueQueue"))
        assertTrue(composer.contains("onClearQueue"))
        assertTrue(
            "屏幕必须把排队计数与两个动作接给 composer",
            screen.contains("queuedCount = controller.queuedCount") &&
                screen.contains("onContinueQueue = { controller.continueQueue() }") &&
                screen.contains("onClearQueue = { controller.clearQueue() }"),
        )
        assertTrue(screen.contains("onSend = { text, attachments -> controller.send(text, attachments) }"))
    }
}
