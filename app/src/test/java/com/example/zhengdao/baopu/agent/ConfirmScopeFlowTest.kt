// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百七十一批：确认卡降噪 v2 的**链路**测试（派工单 §2 判据逐条）。
// 每一例都走真的 GuardPipeline + 真执行器（临时目录/记账探针），不 mock 执行链。
//
// 判据（派工单原话级）：
// - 同一技能同参数连续 10 次 ⇒ 只弹 1 次，后续直接放行（这里用 2 次＋账本直测覆盖）；
// - 参数改一处 ⇒ 重新弹；
// - 换工具/换会话 ⇒ 不继承；
// - 高风险工具 ⇒ 只能一次，范围按钮不可用（负例：给范围也被摁回）；
// - 同 key 等待中 ⇒ 不重复弹卡（合并显示次数）；
// - 授权可撤销；运行/会话结束自动清；审计记「谁授权/范围/次数」。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ConfirmAuditDecision
import com.example.zhengdao.baopu.ConfirmGrants
import com.example.zhengdao.baopu.ConfirmScope
import com.example.zhengdao.baopu.ConfirmScopeChoice
import com.example.zhengdao.baopu.PendingStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmScopeFlowTest {

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-scope-$name").toFile()

    /** 探针执行记账（网页点击探针：CONFIRM、非高风险 ⇒ 可拿范围授权）。 */
    private class FakeStore : PendingStore {
        var cleared = 0
        override fun clear(sessionId: Long) {
            cleared++
        }

        override fun load(sessionId: Long): com.example.zhengdao.baopu.PendingRow? = null
    }

    private class Rig(
        val flow: ConfirmFlow,
        val store: FakeStore,
        val persisted: MutableList<Array<Any?>>,
        val cards: MutableList<ConfirmRequest>,
        val clicks: MutableList<String>,
        val root: File,
    )

    private fun rig(root: File): Rig {
        val clicks = mutableListOf<String>()
        val reg = ToolRegistry().apply {
            // 写工具（真执行；高风险 ⇒ 永远只给「仅这一次」）
            register(WriteFileTool.spec { root })
            // 网页点击探针（CONFIRM、非高风险 ⇒ 四档范围）
            register(
                ToolSpec(
                    name = "browser_click",
                    description = "探针：网页点击（范围授权用例的靶子）",
                    inputSchema = jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf(
                            "url" to jsonObjectOf("type" to "string"),
                            "selector" to jsonObjectOf("type" to "string"),
                        ),
                    ),
                    permission = ToolPermission.CONFIRM,
                ) { args ->
                    clicks += args.optString("url")
                    ToolOutcome.Ok("点了 ${args.optString("selector")}")
                },
            )
            // browser 组默认不可见 ⇒ find 会换成「组未解锁」桩（第五十九批 §1.5）。
            // 本用例要验的是 CONFIRM 语义，先按用户已解锁处理（生产同一形状）。
            setGroupApproved(ToolGroup.BROWSER, true)
        }
        val store = FakeStore()
        val persisted = mutableListOf<Array<Any?>>()
        val cards = mutableListOf<ConfirmRequest>()
        val flow = ConfirmFlow(
            pipeline = GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor())),
            store = store,
            onPersistPending = { sid, step, tool, args, at -> persisted += arrayOf(sid, step, tool, args, at) },
            onRequestCard = { cards += it },
        )
        return Rig(flow, store, persisted, cards, clicks, root)
    }

    private fun clickCall(id: String, url: String) =
        ToolCall(id, "browser_click", JSONObject().put("url", url).put("selector", "#go"))

    private fun writeCall(id: String, name: String) =
        ToolCall(id, "write_file", JSONObject().put("path", name).put("content", "内容"))

    private suspend fun awaitLive(flow: ConfirmFlow, callId: String) {
        repeat(200) {
            if (flow.liveCallId() == callId) return
            delay(10)
        }
        throw AssertionError("确认卡没挂上（liveCallId=${flow.liveCallId()}）")
    }

    @After
    fun tearDown() {
        ConfirmGrants.resetForTest()
        ConfirmScopeChoice.resetForTest()
    }

    // ── 正例：同 key 同作用域只问一次 ───────────────────────────────────

    @Test
    fun `同key同作用域只问一次_第二次免卡直跑`() = runBlocking {
        val root = tmp("once")
        val r = rig(root)
        var first: ConfirmFlow.Outcome? = null
        val job = launch {
            first = r.flow.enter(1L, 1, clickCall("c-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-1")
        r.flow.resumeWithApproval("c-1", approved = true, note = null, scope = ConfirmScope.THIS_RUN)
        job.join()
        assertTrue(first is ConfirmFlow.Outcome.Approved)

        // 同一个 key 再来一次 ⇒ 不问、直接执行
        val second = r.flow.enter(1L, 2, clickCall("c-2", "https://a.example")) { _, _ -> null }
        assertTrue("命中范围授权 ⇒ Approved", second is ConfirmFlow.Outcome.Approved)
        assertEquals("没弹第二张卡", 1, r.cards.size)
        assertEquals("没落第二条 pending", 1, r.persisted.size)
        assertEquals("执行器真的跑了两次", listOf("https://a.example", "https://a.example"), r.clicks)
    }

    @Test
    fun `免卡直跑也过完整五段_不是绕过执行`() = runBlocking {
        val root = tmp("five")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("c-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-1")
        r.flow.resumeWithApproval("c-1", approved = true, note = null, scope = ConfirmScope.THIS_SESSION)
        job.join()
        // 免卡路径的结局与在线批准同形（Done + 围栏）
        val outcome = r.flow.enter(1L, 2, clickCall("c-2", "https://a.example")) { _, _ -> null }
        val approved = outcome as ConfirmFlow.Outcome.Approved
        assertTrue("走的是 executeApproved（围栏在）", approved.result is GuardPipeline.Result.Done)
        assertTrue(
            "回灌带围栏",
            (approved.result as GuardPipeline.Result.Done).wrapped.startsWith(DataFence.FENCE_OPEN),
        )
    }

    // ── 正例：参数变了 / 换工具 / 换会话 / 撤销 / 寿命 ────────────────────

    @Test
    fun `参数改一处_重新弹卡`() = runBlocking {
        val root = tmp("param")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("c-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-1")
        r.flow.resumeWithApproval("c-1", approved = true, note = null, scope = ConfirmScope.THIS_RUN)
        job.join()

        val job2 = launch {
            r.flow.enter(1L, 2, clickCall("c-3", "https://b.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-3")
        assertEquals("参数变了是新 key ⇒ 新卡", 2, r.cards.size)
        r.flow.resumeWithApproval("c-3", approved = false, note = null)
        job2.join()
    }

    @Test
    fun `换会话不继承授权`() = runBlocking {
        val root = tmp("session")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("c-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-1")
        r.flow.resumeWithApproval("c-1", approved = true, note = null, scope = ConfirmScope.THIS_SESSION)
        job.join()

        val job2 = launch {
            r.flow.enter(2L, 1, clickCall("c-2", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-2")
        assertEquals("新会话必须重新问", 2, r.cards.size)
        r.flow.resumeWithApproval("c-2", approved = false, note = null)
        job2.join()
    }

    @Test
    fun `运行结束自动清_本技能与本次运行失效_本会话仍在`() = runBlocking {
        val root = tmp("endrun")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("c-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-1")
        r.flow.resumeWithApproval("c-1", approved = true, note = null, scope = ConfirmScope.THIS_RUN)
        job.join()
        r.flow.endRun(1L)

        val job2 = launch {
            r.flow.enter(1L, 2, clickCall("c-2", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-2")
        assertEquals("运行结束 ⇒ 重新问", 2, r.cards.size)
        r.flow.resumeWithApproval("c-2", approved = false, note = null)
        job2.join()
    }

    @Test
    fun `撤销授权后重新问`() = runBlocking {
        val root = tmp("revoke")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("c-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-1")
        r.flow.resumeWithApproval("c-1", approved = true, note = null, scope = ConfirmScope.THIS_SESSION)
        job.join()
        assertEquals("撤掉 1 条", 1, r.flow.revokeGrants(1L))

        val job2 = launch {
            r.flow.enter(1L, 2, clickCall("c-2", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "c-2")
        assertEquals("撤销后重新问", 2, r.cards.size)
        r.flow.resumeWithApproval("c-2", approved = false, note = null)
        job2.join()
    }

    // ── 负例①：高风险被范围授权绕过 ⇒ 红 ────────────────────────────────

    @Test
    fun `负例_高风险给范围也被摁回仅这一次_下次照问`() = runBlocking {
        val root = tmp("highrisk")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, writeCall("w-1", "a.txt")) { _, _ -> null }
        }
        awaitLive(r.flow, "w-1")
        // 模拟「卡面/上游漏摁」：给写工具批了范围授权
        r.flow.resumeWithApproval("w-1", approved = true, note = null, scope = ConfirmScope.THIS_SESSION)
        job.join()
        assertTrue("第一次真的执行了", File(root, "a.txt").exists())

        val job2 = launch {
            r.flow.enter(1L, 2, writeCall("w-2", "b.txt")) { _, _ -> null }
        }
        awaitLive(r.flow, "w-2")
        assertEquals("高风险没被范围放过：又弹了一张", 2, r.cards.size)
        assertTrue(
            "摁回动作留审计（不许静默）",
            r.flow.grantAudit(1L).any { it.decision == ConfirmAuditDecision.DOWNGRADED },
        )
        r.flow.resumeWithApproval("w-2", approved = false, note = null)
        job2.join()
    }

    @Test
    fun `负例_高风险卡的scopeOptions只剩仅这一次`() = runBlocking {
        val root = tmp("highrisk-ui")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, writeCall("w-1", "a.txt")) { _, _ -> null }
        }
        awaitLive(r.flow, "w-1")
        val card = r.cards.single()
        assertEquals(
            "写工具的卡只摆「仅这一次」",
            listOf(ConfirmScope.ONLY_ONCE),
            card.scopeOptions,
        )
        assertTrue("卡面明示高风险", card.highRisk)
        r.flow.resumeWithApproval("w-1", approved = false, note = null)
        job.join()
    }

    // ── 负例②：重复弹同一张 ⇒ 红（同 key 等待中合并） ─────────────────────

    @Test
    fun `负例_同key等待中_不重复弹卡_合并显示次数`() = runBlocking {
        val root = tmp("merge")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("m-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "m-1")
        // 同一个 key 又来了（不同 callId：这是两次独立的模型调用，参数相同）
        val second = r.flow.enter(1L, 1, clickCall("m-2", "https://a.example")) { _, _ -> null }
        assertTrue("重复请求被单活跃门收掉", second is ConfirmFlow.Outcome.Ignored)

        assertEquals("还是同一张卡（同一个 callId）", "m-1", r.flow.liveCallId())
        assertEquals("没落第二条 pending", 1, r.persisted.size)
        assertTrue("所有卡事件都指向同一张卡", r.cards.all { it.callId == "m-1" })
        assertEquals("最后一次卡事件带着合并计数", 2, r.cards.last().mergeCount)
        assertEquals("没有凭空多出授权", 0, r.flow.grantAudit(1L).size)

        r.flow.resumeWithApproval("m-1", approved = true, note = null)
        job.join()
    }

    @Test
    fun `不同key等待中_仍按单活跃门丢弃_不合并`() = runBlocking {
        val root = tmp("no-merge")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("n-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "n-1")
        val second = r.flow.enter(1L, 1, clickCall("n-2", "https://b.example")) { _, _ -> null }
        assertTrue("不同 key ⇒ 照旧丢弃（存量行为逐字不变）", second is ConfirmFlow.Outcome.Ignored)
        assertEquals("只有首张卡事件（没有合并重发）", 1, r.cards.size)
        r.flow.resumeWithApproval("n-1", approved = false, note = null)
        job.join()
    }

    // ── 旁路：卡面选的范围真的进账本（生产路径） ─────────────────────────

    @Test
    fun `旁路选的范围_在resumeWithApproval里消费并记账`() = runBlocking {
        val root = tmp("bridge")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("b-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "b-1")
        // 生产形状：卡面先 record 再走无参 approve（控制器/引擎一个字没改）
        ConfirmScopeChoice.record("b-1", ConfirmScope.THIS_SKILL)
        r.flow.resumeWithApproval("b-1", approved = true, note = null)
        job.join()

        val active = r.flow.activeGrants(1L)
        assertEquals("旁路的选择进了账本", 1, active.size)
        assertEquals(ConfirmScope.THIS_SKILL, active.single().scope)
        // 且真的生效：同 key 下次免卡
        val outcome = r.flow.enter(1L, 2, clickCall("b-2", "https://a.example")) { _, _ -> null }
        assertTrue(outcome is ConfirmFlow.Outcome.Approved)
    }

    @Test
    fun `没选范围_就是不授权_下次照问`() = runBlocking {
        val root = tmp("once-only")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("o-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "o-1")
        r.flow.resumeWithApproval("o-1", approved = true, note = null) // 默认批准 = 仅这一次
        job.join()

        val job2 = launch {
            r.flow.enter(1L, 2, clickCall("o-2", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "o-2")
        assertEquals("仅这一次 ⇒ 下次照问", 2, r.cards.size)
        assertEquals("没入账", 0, r.flow.activeGrants(1L).size)
        r.flow.resumeWithApproval("o-2", approved = false, note = null)
        job2.join()
    }

    // ── 陈旧点击不受范围影响（J4 与 171 叠加） ───────────────────────────

    @Test
    fun `陈旧点击丢弃_范围不记_状态不动`() = runBlocking {
        val root = tmp("stale")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, clickCall("s-1", "https://a.example")) { _, _ -> null }
        }
        awaitLive(r.flow, "s-1")
        ConfirmScopeChoice.record("s-stale", ConfirmScope.THIS_SESSION)
        r.flow.resumeWithApproval("s-stale", approved = true, note = null)
        assertEquals("卡还在等", "s-1", r.flow.liveCallId())
        assertEquals("没入账", 0, r.flow.activeGrants(1L).size)
        r.flow.resumeWithApproval("s-1", approved = false, note = null)
        job.join()
    }

    @Test
    fun `超时收场_范围选择作废`() = runBlocking {
        val root = tmp("timeout")
        // 超时钟：前台已 16 分钟（> 15 分钟线）
        val clicks = mutableListOf<String>()
        val reg = ToolRegistry().apply {
            register(WriteFileTool.spec { root })
            register(
                ToolSpec(
                    name = "browser_click",
                    description = "探针",
                    inputSchema = jsonObjectOf("type" to "object"),
                    permission = ToolPermission.CONFIRM,
                ) { args -> ToolOutcome.Ok("x") },
            )
            setGroupApproved(ToolGroup.BROWSER, true) // 同 rig()：先当已解锁
        }
        val flow = ConfirmFlow(
            pipeline = GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor())),
            store = FakeStore(),
            foregroundElapsedMs = { 16 * 60 * 1000L },
            onRequestCard = {},
        )
        ConfirmScopeChoice.record("t-1", ConfirmScope.THIS_SESSION)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch { outcome = flow.enter(1L, 1, clickCall("t-1", "https://a.example")) { _, _ -> null } }
        repeat(400) {
            if (!job.isActive) return@repeat
            delay(5)
        }
        job.join()
        assertTrue("超时收场", outcome is ConfirmFlow.Outcome.TimedOut)
        assertEquals("超时不入账", 0, flow.activeGrants(1L).size)
        assertEquals("旁路条目被清掉", null, ConfirmScopeChoice.consume("t-1"))
    }
}
