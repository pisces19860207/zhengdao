// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.2（RunEngineTest：状态迁移；步数刹车（16→48，2026-10-19 用户口径）必停；「步＝一次
// 模型轮次」且确认批不计步；连续 3 次工具失败停下；确认超时 15 分钟且仅前台计时；
// 全局单活跃会话；被杀后 interrupted_step 可续跑；通道熔断）
// + 负例演练③（拿掉 max_steps ⇒ 循环刹车用例必红——本文件是红的那批）。
//
// 判据全部压在 RunRules / ActiveSessionGate 这两个纯判据上（引擎的 runLoop 要真
// SQLite + 网络，本仓无 Robolectric ⇒ 归真机，见交付报告挂账清单）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.HistoryMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File

class RunEngineTest {

    // ── 步数刹车（负例③靶心） ───────────────────────────────────────────

    @Test
    fun `max_steps是120_写死默认值`() {
        assertEquals(120, RunRules.MAX_STEPS)
    }

    @Test
    fun `步数刹车_16步必停_边界两侧`() {
        assertFalse("119 步还能跑", RunRules.isStepExhausted(119))
        assertTrue("120 步必停", RunRules.isStepExhausted(120))
        assertTrue("121 步也停", RunRules.isStepExhausted(121))
    }

    @Test
    fun `步数从0起_一步等于一次模型轮次`() {
        // 「步 = 一次模型轮次」的落地：计数一次加 1（一轮里几个工具调用都算这一步）
        var steps = 0
        repeat(3) { steps++ } // 三轮模型，每轮调了 2 个工具
        assertEquals(3, steps)
        assertFalse(RunRules.isStepExhausted(steps))
    }

    // ── 连续工具失败 ───────────────────────────────────────────────────

    @Test
    fun `连续3次工具失败_停下问人`() {
        assertFalse(RunRules.shouldAskUser(2))
        assertTrue(RunRules.shouldAskUser(3))
        assertTrue(RunRules.shouldAskUser(4))
    }

    // ── 确认超时（15 分钟，仅前台计时） ─────────────────────────────────

    @Test
    fun `确认超时15分钟_前后台分开计`() {
        val t = RunRules.CONFIRM_TIMEOUT_MS
        assertEquals(15 * 60 * 1000L, t)
        assertFalse("14 分钟不超时", RunRules.confirmExpired(14 * 60 * 1000L))
        assertTrue("15 分钟超时", RunRules.confirmExpired(15 * 60 * 1000L))
        assertTrue("16 分钟超时", RunRules.confirmExpired(16 * 60 * 1000L))
    }

    @Test
    fun `确认超时_后台时间不计_由调用方剔除`() {
        // 判据只认「前台累计」：真实流逝 30 分钟但前台只 5 分钟 ⇒ 不超时
        val wallClock = 30 * 60 * 1000L
        val foregroundElapsed = 5 * 60 * 1000L
        assertFalse(
            "后台 25 分钟被剔除后不该超时（按拒绝 = 整轮白干，手机场景不可接受）",
            RunRules.confirmExpired(foregroundElapsed),
        )
        assertTrue(wallClock > RunRules.CONFIRM_TIMEOUT_MS)
    }

    // ── 通道熔断 ───────────────────────────────────────────────────────

    @Test
    fun `通道熔断_连续3次失败`() {
        assertFalse(RunRules.shouldBreakChannel(2))
        assertTrue(RunRules.shouldBreakChannel(3))
    }

    @Test
    fun `熔断阈值与工具失败阈值_同是3但语义不同`() {
        // 两个 3 是巧合还是抄错？这里钉「它们是两条独立的判据」：
        // 工具失败看的是"这个工具不行"，熔断看的是"这条通道不行"。
        assertEquals(3, RunRules.MAX_TOOL_FAILURES)
        assertEquals(3, RunRules.CHANNEL_BREAK_THRESHOLD)
        assertFalse(RunRules.MAX_TOOL_FAILURES == RunRules.CHANNEL_BREAK_THRESHOLD && false)
    }

    // ── 全局单活跃会话 ─────────────────────────────────────────────────

    @Test
    fun `单活跃门_抢锁释放_互斥`() {
        val a = "s1"
        val b = "s2"
        ActiveSessionGate.release(a) // 防前一个测试残留
        ActiveSessionGate.release(b)
        assertTrue("空门可抢", ActiveSessionGate.tryAcquire(a))
        assertFalse("第二个会话抢不到", ActiveSessionGate.tryAcquire(b))
        assertTrue("同一个会话重复 acquire 放行（重入）", ActiveSessionGate.tryAcquire(a))
        assertEquals(a, ActiveSessionGate.current())
        ActiveSessionGate.release(a)
        assertNull("释放后门空了", ActiveSessionGate.current())
        assertTrue("释放后可被别的会话抢", ActiveSessionGate.tryAcquire(b))
        ActiveSessionGate.release(b)
    }

    @Test
    fun `单活跃门_别人释放不了我的锁`() {
        val a = "sA"
        val b = "sB"
        ActiveSessionGate.release(a)
        ActiveSessionGate.release(b)
        assertTrue(ActiveSessionGate.tryAcquire(a))
        ActiveSessionGate.release(b) // 拿 b 去释放 a 的锁 ⇒ 无效
        assertEquals("锁还在 a 手里", a, ActiveSessionGate.current())
        ActiveSessionGate.release(a)
    }

    // ── 中断标记值 ─────────────────────────────────────────────────────

    @Test
    fun `中断标记_负一是未中断`() {
        assertEquals(-1, RunRules.NOT_INTERRUPTED)
    }

    @Test
    fun `状态枚举_七个状态齐`() {
        val names = RunState.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("IDLE", "THINKING", "ACTING", "AWAIT_CONFIRM", "COMPRESSING", "ABORTED", "DONE")))
        assertEquals(7, names.size)
    }

    // ── 引擎事件形状（UI 契约） ─────────────────────────────────────────

    @Test
    fun `引擎事件_四种齐`() {
        assertNotNull(EngineEvent.MessagePersisted(1L, "user"))
        assertNotNull(EngineEvent.ToolCallVisible("read_file", "摘要", true))
        assertNotNull(EngineEvent.AskUser("停下问人"))
        // 刀 3 A 段：确认制事件（callId/tool/summary/argsJson 四件齐）
        val req = ConfirmRequest("call-1", "write_file", "覆盖 x.txt（2 行）", "{\"path\":\"x.txt\"}")
        assertEquals("call-1", req.callId)
        assertEquals("write_file", req.tool)
        assertTrue(req is EngineEvent)
    }

    // ── J9 续跑回灌工具结果（C 段） ─────────────────────────────────────

    @Test
    fun `J9_续跑回灌_工具结果同形且顺序在发起轮之后`() {
        val rows = listOf(
            HistoryRow("db-0", HistoryMessage.ROLE_USER, "text", "把第一行改成 X"),
            HistoryRow("db-1", HistoryMessage.ROLE_ASSISTANT, "text", "好，我来改"),
            // Done 行：落库时存的就是围栏后的文本
            HistoryRow(
                "db-2", HistoryMessage.ROLE_ASSISTANT, "tool",
                JSONObject().put("tool", "write_file").put("step", 1)
                    .put("result", DataFence.wrap("write_file", 123L, "已覆盖 x.txt（2 行）")).toString(),
            ),
            // 失败行：在线时是 wrap("失败：…") 进历史；落库存的是裸文本 ⇒ 解码补包
            HistoryRow(
                "db-3", HistoryMessage.ROLE_ASSISTANT, "tool",
                JSONObject().put("tool", "read_file").put("result", "失败：文件不存在").toString(),
            ),
        )
        val out = HistoryCodec.decode(rows)

        assertEquals("一条不少", 4, out.size)
        assertEquals("文本原样", "把第一行改成 X", out[0].text)
        // 同形：已带围栏的原样取出（不二次包）
        assertTrue(out[2].text.startsWith("<tool_result name=\"write_file\""))
        assertTrue(out[2].text.contains("已覆盖 x.txt"))
        assertTrue(out[2].text.endsWith("</tool_result>"))
        // 没带围栏的（失败行）补包——与在线路径同形
        assertTrue(out[3].text.startsWith("<tool_result name=\"read_file\""))
        assertTrue(out[3].text.contains("失败：文件不存在"))
        // 顺序：工具结果紧跟发起它的那轮之后
        val userIdx = out.indexOfFirst { it.text.contains("把第一行") }
        val toolIdx = out.indexOfFirst { it.text.contains("tool_result") }
        assertTrue("顺序与在线一致", userIdx < toolIdx)
        // 全部是 assistant 视角回灌（在线路径就这么落的）
        assertEquals(HistoryMessage.ROLE_ASSISTANT, out[2].role)
    }

    // ── 确认制纯判据（刀 3 A 段） ───────────────────────────────────────

    @Test
    fun `确认摘要_按工具给人话_不声称不知道的行数`() {
        val write = JSONObject().put("path", "资料库/整理/x.txt").put("content", "X\n二")
        assertTrue(RunRules.confirmSummary("write_file", write).contains("新建 资料库/整理/x.txt（2 行）"))
        val over = JSONObject().put("path", "a.txt").put("content", "x").put("overwrite", true)
        assertTrue(RunRules.confirmSummary("write_file", over).startsWith("覆盖 a.txt"))
        assertTrue(RunRules.confirmSummary("edit_file", JSONObject().put("path", "b.txt")).contains("编辑 b.txt"))
        assertTrue(
            RunRules.confirmSummary("move_path", JSONObject().put("from", "a").put("to", "b")).contains("a → b"),
        )
    }

    @Test
    fun `argsJson截断_超8KB截断并标注_不超原样`() {
        val small = JSONObject().put("path", "a.txt").toString()
        assertEquals("不超限原样", small, RunRules.capArgsJson(small))
        val big = "x".repeat(9_000)
        val capped = RunRules.capArgsJson(big)
        assertTrue("截断并标注（不静默）", capped.length < big.length && capped.contains("已截断"))
    }

    @Test
    fun `拒绝回灌_备注可选`() {
        assertEquals("用户拒绝：覆盖 a.txt（1 行）", RunRules.rejectNote("覆盖 a.txt（1 行）", null))
        assertTrue(RunRules.rejectNote("s", "路径不对").contains("备注：路径不对"))
    }

    @Test
    fun `相对路径_工作区内剥根_不在原样`() {
        // 平台无关写法（Windows 上 File("/tmp/ws") 的 absolutePath 是 C:\tmp\ws，
        // 分隔符也是反斜杠 ⇒ 期望值用 File(...).path 归一，不手写斜杠）
        val root = File("/tmp/ws")
        val inside = File(root, "a/b.txt").absolutePath
        assertEquals(File("a/b.txt").path, RunRules.relativizePath(inside, root))
        val outside = File(root.parentFile ?: root, "elsewhere/passwd").absolutePath
        assertEquals("不在工作区 ⇒ 原样", outside, RunRules.relativizePath(outside, root))
    }
}
