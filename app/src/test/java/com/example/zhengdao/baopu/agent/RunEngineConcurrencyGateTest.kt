// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第一百五十批派工单《RunEngine 跨轮状态与并发缺陷修复》§1–§8（A/C/D/E/F/G+I）。
//
// 引擎 runLoop 要真 SQLite + 网络（本仓无 Robolectric ⇒ 引擎行为归真机，与
// RunEngineTest 头部口径一致），本文件把「修没修上」钉在源码结构上：
// 复位调用点恰一处、@Volatile、同锁纪律、早退收尾、兜底删行，逐条可 grep。
// 形状用 codeOnly、字面用 codeLines，与 RunLoopExtractionGateTest 同款口径。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.CodeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RunEngineConcurrencyGateTest {

    /** 去注释行、保留字符串（查文案字面用；口径同 RunLoopExtractionGateTest）。 */
    private fun codeLines(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }
            .readText()
            .replace("\r\n", "\n")
            .lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /** 去注释与字符串（查代码形状用）。 */
    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$it") }
            .readText()
            .replace("\r\n", "\n")
            .let { CodeText.codeOnly(it) }

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    // ── A：四个 run 级计数器在新一轮入口统一复位 ──────────────────────────

    @Test
    fun `A_四个run级计数器由resetRunScopedCounters统一复位`() {
        val src = code(engine)
        val m = src.indexOf("private fun resetRunScopedCounters()")
        assertTrue("找不到 resetRunScopedCounters（150批 A 没落地）", m >= 0)
        val body = src.substring(m, src.indexOf('}', m) + 1)
        // 四个字段都要复位——只复位 channelBroken 是派工单 §9 的打回项
        listOf(
            "consecutiveToolFailures = 0",
            "consecutiveChannelFailures = 0",
            "channelSwitches = 0",
            "channelBroken = false",
        ).forEach { a -> assertTrue("复位缺字段：$a", body.contains(a)) }
        // 调用点在 runLoop 第一行、channelBroken 判断**之前**（send/resume/approve/reject/
        // drainQueue 全收敛到 runLoop ⇒ 一处复位全盖住；call 全文件必须恰一处）
        val call = "resetRunScopedCounters()\n        if (channelBroken) {"
        assertEquals(
            "runLoop 入口的复位调用必须紧贴 channelBroken 判断之前且恰好一处",
            1,
            Regex(Regex.escape(call)).findAll(src).count(),
        )
        assertEquals(
            "复位调用全文件只许一处（send/resume 各自再写一份 = 打回项；方法本体声明不算调用）",
            1,
            Regex("(?<!fun )resetRunScopedCounters\\(\\)").findAll(src).count(),
        )
    }

    // ── C：acquireOrNotice 的日志插值补齐 ────────────────────────────────

    @Test
    fun `C_单活跃门占用日志带上当前占用者与会话id`() {
        val src = codeLines(engine)
        assertTrue(
            "占用日志必须带插值（与 send 路径同口径）",
            src.contains("单活跃门占用（当前=\${ActiveSessionGate.current()}，要=id:\$sessionId）"),
        )
        assertFalse("丢插值的旧字面必须消失", src.contains("（当前=，要=id:）"))
    }

    // ── D：provider 跨线程读写必须 @Volatile ─────────────────────────────

    @Test
    fun `D_provider构造参数带Volatile`() {
        val src = code(engine)
        assertTrue(
            "provider 在引擎线程写（熔断降级/安全点切换）、别的线程读（currentProviderNow）",
            src.contains("@Volatile private var provider: Provider,"),
        )
    }

    // ── E：确认卡前台计时三写一读共用一把锁 ──────────────────────────────

    @Test
    fun `E_前台计时三写一读同锁`() {
        val src = code(engine)
        assertTrue("锁对象必须声明", src.contains("confirmTimingLock = Object()"))
        assertEquals(
            "synchronized(confirmTimingLock) 必须恰好四处（onPersistPending/读 lambda/切后台/回前台）",
            4,
            Regex("synchronized\\(confirmTimingLock\\)").findAll(src).count(),
        )
    }

    // ── F：poll 与 stop 同锁；过闸的条不许再丢 ───────────────────────────

    @Test
    fun `F_poll与stop同锁_过闸条目不许在检查点后丢弃`() {
        val src = code(engine)
        assertEquals(
            "synchronized(queueGate) 必须恰好两处（drainQueue 取条 / stop 置位清队）",
            2,
            Regex("synchronized\\(queueGate\\)").findAll(src).count(),
        )
        // drainQueue：取条与停止检查在同一临界区里
        assertTrue(
            "取条必须与 stopRequested 检查同锁",
            src.contains("if (stopRequested) null else runQueue.poll(sessionId)"),
        )
        // 两个旧「丢弃检查点」都必须消失（消息已落库、由循环头收尾 Aborted）
        assertFalse("poll 后的丢弃 break 必须删掉", src.contains("取出的排队条不再开跑"))
        assertFalse("runLoop 里的第二丢弃闸必须撤销", src.contains("排队里的这一条没有开跑"))
        // stop()：置位与 clearQueue 在同一临界区（抽 stop 方法体核对）
        val s = src.indexOf("fun stop() {")
        assertTrue("找不到 stop()", s >= 0)
        val stopBody = src.substring(s, src.indexOf("fun stopRequestedNow()", s))
        assertTrue("stop 的置位与清队必须进同锁临界区", stopBody.contains("synchronized(queueGate)"))
        assertTrue(stopBody.contains("stopRequested = true"))
        assertTrue(stopBody.contains("clearQueue(sid)"))
    }

    // ── G：通道熔断早退也收尾 ────────────────────────────────────────────

    @Test
    fun `G_通道熔断早退settle落Aborted行`() {
        val src = codeLines(engine)
        assertTrue(
            "早退必须 settle（run_log 有 Aborted 行、interrupted_step 更新）",
            src.contains("settle(sessionId, 0, RunState.ABORTED, \"Aborted\", err = \"channel-broken\")"),
        )
    }

    // ── I：ensureQueueRow 失败时按 (session, text) 兜底删残留行 ──────────

    @Test
    fun `I_队列行残留按会话加文本兜底删`() {
        val src = code(engine)
        assertEquals(
            "selectRunQueue(sessionId) 只许兜底删这一处用",
            1,
            Regex("BaopuSql\\.selectRunQueue\\(sessionId\\)").findAll(src).count(),
        )
        val p = src.indexOf("private fun persistUserMessage(")
        assertTrue("找不到 persistUserMessage", p >= 0)
        val body = src.substring(p, src.indexOf("private fun persistAssistantMessage(", p))
        assertTrue(
            "queueRowId 拿不到 id 时必须有按内容兜底删（151 批：只删最新一条）",
            body.contains("deleteRunQueue(newestMatch)"),
        )
    }
}
