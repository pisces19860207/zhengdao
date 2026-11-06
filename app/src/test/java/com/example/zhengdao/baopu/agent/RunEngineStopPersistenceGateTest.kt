// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零七批 · 停机持久性的**源码级门禁**（150/151/152 同口径：引擎真 SQLite/网络，
// JVM 起不来，能钉形态的先钉形态）＋ 纯判据用例（[RunRules.resumeBlockedWhileStopped]）。
//
// 钉的是 507 派工单 §三 的四条：
//   ① 四处 stopRequested=false 逐处判定落死——新轮该清（一处）、续跑/恢复不清（三处）；
//   ② 叫停落盘、重启读回（"重启仍停"的机侧半边；真机半边见交付报告）；
//   ③ 解除只许人做——模型侧（工具/注册表）没有任何碰停机状态的通道。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEngineStopPersistenceGateTest {

    private fun src(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }.readText()

    private val engine: String get() = src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
    private val runtime: String get() = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")

    private fun slice(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("找不到 $from", a >= 0)
        val b = text.indexOf(to, a)
        assertTrue("找不到结束标记 $to", b > a)
        return text.substring(a, b)
    }

    // ── ① 四处判定落死 ────────────────────────────────────────────────────

    @Test
    fun `判定1_send的STARTED是新轮_该清_并删停机记录`() {
        val started = slice(engine, "if (verdict == SendAcceptance.STARTED) {", "val turn = QueuedTurn(")
        assertTrue("新一轮必须清 stopRequested（507 判定①：新轮该清）", started.contains("stopRequested = false"))
        assertTrue("新一轮必须清记住的会话", started.contains("stoppedSessionId = null"))
        assertTrue("新一轮必须删叫停记录（人手发新消息＝人做的解除）", started.contains("stopState?.clear()"))
    }

    @Test
    fun `判定2_resumeQueue是续跑_不再清_先过闸`() {
        val body = slice(engine, "fun resumeQueue(sessionId: Long): Boolean {", "fun clearQueue(")
        assertFalse("续跑不许清 stopRequested（507 判定②）", body.contains("stopRequested = false"))
        assertTrue("续跑必须先过停机闸", body.contains("if (stopGateForResume(sessionId)) return false"))
    }

    /**
     * 蓝总管验收补（2026-11-07）：闸内那半句原先没人钉——把 `resumeBlockedWhileStopped(...)`
     * 整段换成常量也全绿（我实测变异未被咬）。纯判据与「调用点」都各有人钉，
     * 唯独「闸真的用了那个判据」这一跳是空的；补在这里。
     */
    @Test
    fun `闸内必须真的用纯判据判定_不是常量短路`() {
        val gate = slice(engine, "private fun stopGateForResume(sessionId: Long): Boolean {", "停止标志的**只读观测口**")
        assertTrue(
            "停机闸必须调 RunRules.resumeBlockedWhileStopped（否则续跑闸形同虚设）",
            gate.contains("RunRules.resumeBlockedWhileStopped(stoppedSessionId, sessionId)"),
        )
    }

    @Test
    fun `判定3_resume是恢复_不再清_先过闸`() {
        val body = slice(engine, "fun resume(sessionId: Long, interruptedStepValue: Int) {", "fun resumeWithApproval(")
        assertFalse("恢复不许清 stopRequested（507 判定③）", body.contains("stopRequested = false"))
        assertTrue("恢复必须先过停机闸（在抢活跃门之前）", body.indexOf("stopGateForResume(sessionId)") >= 0)
        assertTrue("被拒时不许碰活跃门（闸在 acquireOrNotice 之前）",
            body.indexOf("stopGateForResume(sessionId)") < body.indexOf("acquireOrNotice(sessionId)"))
    }

    @Test
    fun `判定4_approveRestoredPending是恢复_不再清_先过闸`() {
        val body = slice(engine, "fun approveRestoredPending(sessionId: Long) {", "fun rejectRestoredPending(")
        assertFalse("恢复确认不许清 stopRequested（507 判定④）", body.contains("stopRequested = false"))
        assertTrue("恢复确认必须先过停机闸", body.contains("if (stopGateForResume(sessionId)) return"))
    }

    @Test
    fun `全文件清点_stopRequested=false只剩两处_新轮与跨会话解除`() {
        // 一处在 send 的 STARTED（新轮该清）；一处在 stopGateForResume（停的是别的会话
        // ⇒ 人工切换解除，176 §2 口径）。多出来的任何一处都算「续跑误清」回潮。
        // （字段声明 `private var stopRequested = false` 另算，不计入写入。）
        val decl = Regex("private var stopRequested = false").findAll(engine).count()
        assertEquals("字段声明恰 1 处", 1, decl)
        val writes = Regex("stopRequested = false").findAll(engine).count() - decl
        assertEquals("stopRequested = false 写入只许 2 处（新轮 + 跨会话解除），实际 $writes 处", 2, writes)
    }

    // ── ② 叫停落盘、重启读回 ─────────────────────────────────────────────

    @Test
    fun `stop在门外落盘_记下停的是哪个会话`() {
        val body = slice(engine, "fun stop() {", "fun stopRequestedNow()")
        assertTrue("stop 必须先经门（150 批纪律不动）", body.contains("queueGate.onStopArrived("))
        assertTrue("stop 必须落盘叫停记录", body.contains("stopState?.mark(stoppedSid, System.currentTimeMillis())"))
        assertTrue("stop 必须记住停的是哪个会话", body.contains("stoppedSessionId = stoppedSid"))
        // 落盘在门外：临界区纪律「全是非阻塞调用」
        assertTrue(
            "落盘必须在门返回之后",
            body.indexOf("stopState?.mark(") > body.indexOf("onStopArrived"),
        )
    }

    @Test
    fun `init读回叫停记录_重启仍停`() {
        val init = slice(engine, "init {", "// ── 第八十七批")
        assertTrue("构造时必须读停机记录", init.contains("stopState?.load()"))
        assertTrue("有记录 ⇒ 置停", init.contains("stopRequested = true"))
        assertTrue("会话身份一起恢复", init.contains("stoppedSessionId = saved.sessionId"))
    }

    @Test
    fun `装配层接线_落App私有目录_模型够不着`() {
        assertTrue("生产必须接停机落盘", runtime.contains("stopState = com.example.zhengdao.baopu.agent.StopStateStore("))
        assertTrue(
            "落点必须在 App 私有 filesDir/baopu/（不在 workspace root 里 ⇒ 文件工具改不掉）",
            runtime.contains("File(File(appCtx.filesDir, \"baopu\"), \"run-stop-state.json\")"),
        )
    }

    // ── ③ 解除只能人做：模型侧没有任何通道 ───────────────────────────────

    @Test
    fun `模型侧_工具与注册表碰不到停机状态`() {
        // 引擎里所有碰停机状态的符号，一个都不许出现在 agent/tools/ 与注册表层——
        // 模型的「解除接口」不存在，连改文件的那条路都被落盘位置堵死（上一条用例）。
        val forbidden = listOf("stopRequested", "stopGateForResume", "stoppedSessionId", "StopStateStore")
        val toolsDir = File("src/main/java/com/example/zhengdao/baopu/agent/tools")
            .let { if (it.isDirectory) it else File("app/src/main/java/com/example/zhengdao/baopu/agent/tools") }
        val registry = src("src/main/java/com/example/zhengdao/baopu/agent/ToolRegistry.kt")
        val toolSources = (toolsDir.listFiles()?.filter { it.isFile } ?: emptyList())
            .map { it.readText() }.joinToString("\n")
        for (token in forbidden) {
            assertFalse("工具层不许碰停机状态（$token）", toolSources.contains(token))
            assertFalse("工具注册表不许碰停机状态（$token）", registry.contains(token))
        }
    }

    @Test
    fun `闸的判据本体在纯函数_三个续跑口都走同一把尺`() {
        val rules = src("src/main/java/com/example/zhengdao/baopu/agent/RunRules.kt")
        assertTrue("纯判据必须存在", rules.contains("fun resumeBlockedWhileStopped(stoppedSessionId: Long?, sessionId: Long)"))
        assertEquals(
            "三个续跑口（resumeQueue/resume/approveRestoredPending）都过同一道闸",
            3,
            Regex("stopGateForResume\\(sessionId\\)").findAll(engine).count(),
        )
    }

    // ── ④ 纯判据用例（闸的方向） ─────────────────────────────────────────

    @Test
    fun `叫停的是本会话_续跑被闸`() {
        assertTrue(RunRules.resumeBlockedWhileStopped(stoppedSessionId = 7L, sessionId = 7L))
        assertTrue("没记住会话（叫停时空跑）＝保守全停", RunRules.resumeBlockedWhileStopped(stoppedSessionId = null, sessionId = 7L))
    }

    @Test
    fun `叫停的是别的会话_人工切换放行_176同口径`() {
        assertFalse(RunRules.resumeBlockedWhileStopped(stoppedSessionId = 7L, sessionId = 9L))
    }
}
