// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十二批追加二 §12.3（J1–J5 逐条）＋ §3.2/§3.3（引擎挂起与恢复、
// 落库三列）。判据全压 ConfirmFlow 这个 Context-free 内核（引擎壳要真 SQLite +
// 网络，本仓无 Robolectric ⇒ 整环归真机 T1–T4，见交付报告挂账清单）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.PendingStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认制内核测试（J1–J5 ＋ pending 落库 + 恢复执行）。
 *
 * 每一例都走**真的** GuardPipeline + 真的 write_file 执行器（临时目录），
 * 不 mock 执行链——「批准后文件真的被改」就是要跑到底才叫验过。
 */
class ConfirmFlowTest {

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-confirm-$name").toFile()

    private fun sha256(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun writeCall(id: String, path: String, content: String, overwrite: Boolean = false) =
        ToolCall(id, "write_file", JSONObject().put("path", path).put("content", content).put("overwrite", overwrite))

    /** 内存假件（记录清库次数；行由测试摆进去——恢复路径用）。 */
    private class FakeStore : PendingStore {
        var cleared = 0
        var row: PendingRow? = null
        override fun clear(sessionId: Long) {
            cleared++
            row = null
        }

        override fun load(sessionId: Long): PendingRow? = row
    }

    private class Rig(
        val flow: ConfirmFlow,
        val store: FakeStore,
        /** onPersistPending 的实参流水（sessionId, step, tool, argsJson, at）——「落库」的账。 */
        val persisted: MutableList<Array<Any?>>,
        val cards: MutableList<ConfirmRequest>,
        val root: File,
    )

    private fun rig(root: File, foreground: () -> Long = { 0L }): Rig {
        val reg = ToolRegistry().apply {
            register(WriteFileTool.spec { root })
            register(ReadFileTool.spec())
            // 无内置路径自检的写探针：执行器直接 Ok ⇒ 唯一防线就是管线第 ② 段
            // （J5 与负例演练③的靶心；write_file 另有内置自检，会把观测掩盖掉）
            register(
                ToolSpec(
                    name = "probe_write",
                    description = "无内置路径自检的写探针（只依赖管线守卫）",
                    inputSchema = jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                        "required" to jsonArrayOf("path"),
                    ),
                    permission = ToolPermission.CONFIRM,
                ) { args -> ToolOutcome.Ok("探针真的执行了（写了 ${args.optString("path")}）") },
            )
        }
        val store = FakeStore()
        val persisted = mutableListOf<Array<Any?>>()
        val cards = mutableListOf<ConfirmRequest>()
        val flow = ConfirmFlow(
            pipeline = GuardPipeline(reg, { root }, ToolRunner(Executors.newSingleThreadExecutor())),
            store = store,
            foregroundElapsedMs = foreground,
            onPersistPending = { sid, step, tool, args, at -> persisted += arrayOf(sid, step, tool, args, at) },
            onRequestCard = { cards += it },
        )
        return Rig(flow, store, persisted, cards, root)
    }

    /** 等卡挂上（liveCallId 出现）；超 2 秒算失败。 */
    private suspend fun awaitLive(flow: ConfirmFlow, callId: String) {
        repeat(200) {
            if (flow.liveCallId() == callId) return
            delay(10)
        }
        throw AssertionError("确认卡没挂上（liveCallId=${flow.liveCallId()}）")
    }

    /** 等卡出现或流程自己结束（超时例用：卡可能一闪即逝）。 */
    private suspend fun awaitCardOrDone(rig: Rig, job: Job) {
        repeat(400) {
            if (rig.cards.isNotEmpty() || !job.isActive) return
            delay(5)
        }
    }

    // ── J1 批准后执行器真的跑 ────────────────────────────────────────────

    @Test
    fun `J1_批准后执行器真的跑_目标文件内容变成期望值()`() = runBlocking {
        val root = tmp("j1")
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(1L, 2, writeCall("call-1", "资料库/整理/x.txt", "X\n第二行")) { _, _ -> null }
        }
        awaitLive(r.flow, "call-1")
        r.flow.resumeWithApproval("call-1", approved = true, note = null)
        job.join()

        val f = File(root, "资料库/整理/x.txt")
        assertTrue("文件真的被写了", f.exists())
        assertEquals("第一行就是模型要的 X", "X", f.readText().lines().first())
        val approved = outcome as ConfirmFlow.Outcome.Approved
        assertTrue(
            "批准后走到执行完成（不是再次 NeedsConfirm——那是死循环）",
            approved.result is GuardPipeline.Result.Done,
        )
        assertEquals("批准后清 pending", 1, r.store.cleared)
    }

    // ── J2 拒绝不执行 ───────────────────────────────────────────────────

    @Test
    fun `J2_拒绝不执行_文件sha256不变_回灌用户拒绝`() = runBlocking {
        val root = tmp("j2")
        val target = File(root, "keep.txt").apply { writeText("原内容") }
        val before = sha256(target)
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(1L, 1, writeCall("call-2", "keep.txt", "新内容", overwrite = true)) { _, _ -> null }
        }
        awaitLive(r.flow, "call-2")
        r.flow.resumeWithApproval("call-2", approved = false, note = null)
        job.join()

        assertEquals("拒绝后文件一字节没动", before, sha256(target))
        val rej = outcome as ConfirmFlow.Outcome.Rejected
        assertTrue("回灌以「用户拒绝：」开头", rej.note.startsWith("用户拒绝："))
        assertTrue("摘要里带路径（模型知道拒的是哪个）", rej.note.contains("keep.txt"))
        assertEquals("拒绝后清 pending", 1, r.store.cleared)
    }

    // ── J3 超时自动收尾 ─────────────────────────────────────────────────

    @Test
    fun `J3_超时自动收尾_前台累计到线即TimedOut且清pending不执行`() = runBlocking {
        val root = tmp("j3")
        // 注入超时钟：前台已累计 16 分钟（> 15 分钟线）。真机 T4 拨系统时间 16 分钟同理。
        val r = rig(root) { 16 * 60 * 1000L }
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(1L, 1, writeCall("call-3", "t.txt", "内容")) { _, _ -> null }
        }
        awaitCardOrDone(r, job)
        job.join()

        assertTrue("结局是超时", outcome is ConfirmFlow.Outcome.TimedOut)
        assertFalse("超时不执行", File(root, "t.txt").exists())
        assertEquals("超时清 pending", 1, r.store.cleared)
        assertTrue((outcome as ConfirmFlow.Outcome.TimedOut).note.contains("15 分钟"))
        assertEquals("卡确实落过库才谈得上清", 1, r.persisted.size)
    }

    // ── J4 陈旧点击丢弃 ─────────────────────────────────────────────────

    @Test
    fun `J4_陈旧点击丢弃_不匹配的callId不改任何状态`() = runBlocking {
        val root = tmp("j4")
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(1L, 1, writeCall("call-4", "x.txt", "内容")) { _, _ -> null }
        }
        awaitLive(r.flow, "call-4")
        // 陈旧点击：上个会话/上张卡残留的 id
        r.flow.resumeWithApproval("call-stale", approved = true, note = null)
        assertEquals("卡还在等原来的", "call-4", r.flow.liveCallId())
        assertEquals("没清库", 0, r.store.cleared)
        assertFalse("没执行", File(root, "x.txt").exists())
        assertTrue("流程还在挂起", job.isActive)
        // 对的 id 才生效
        r.flow.resumeWithApproval("call-4", approved = true, note = null)
        job.join()
        assertTrue("对的 id 批准后执行", File(root, "x.txt").exists())
    }

    // ── J5 批准不走权限判定但走路径守卫 ─────────────────────────────────

    @Test
    fun `J5_批准不走权限判定但走路径守卫_越界批准后Rejected`() = runBlocking {
        val parent = tmp("j5-parent")
        val root = File(parent, "ws").apply { mkdirs() }
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            // 探针工具（执行器自己不查路径）⇒ 唯一防线是管线第 ② 段；
            // 批准 ≠ 绕过护栏：越界批准后必须 Rejected（派工单 §3.5 红线 1）
            outcome = r.flow.enter(
                1L, 1,
                ToolCall("call-5", "probe_write", JSONObject().put("path", "../ws-evil/steal.txt")),
            ) { _, _ -> null }
        }
        awaitLive(r.flow, "call-5")
        r.flow.resumeWithApproval("call-5", approved = true, note = null)
        job.join()

        val approved = outcome as ConfirmFlow.Outcome.Approved
        val result = approved.result
        assertTrue(
            "越界路径批准后是 Rejected（不是 NeedsConfirm 死循环，也不是执行成功）",
            result is GuardPipeline.Result.Rejected,
        )
        assertTrue((result as GuardPipeline.Result.Rejected).reason.contains("越界"))
        assertFalse("没写出工作区", File(parent, "ws-evil/steal.txt").exists())
        assertEquals("拒绝（越界）后也清 pending", 1, r.store.cleared)
    }

    @Test
    fun `J5纵深防御_write_file越界_管线与工具自检双拒`() = runBlocking {
        val parent = tmp("j5b-parent")
        val root = File(parent, "ws").apply { mkdirs() }
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(
                1L, 1,
                ToolCall("call-5b", "write_file", JSONObject().put("path", "../ws-evil/x.txt").put("content", "脏手")),
            ) { _, _ -> null }
        }
        awaitLive(r.flow, "call-5b")
        r.flow.resumeWithApproval("call-5b", approved = true, note = null)
        job.join()
        val result = (outcome as ConfirmFlow.Outcome.Approved).result
        assertTrue("write_file 也有内置第二道自检", result is GuardPipeline.Result.Rejected)
        assertFalse(File(parent, "ws-evil/x.txt").exists())
    }

    // ── pending 落库（T3 的 JVM 代理判据；负例演练②靶心） ────────────────

    @Test
    fun `进确认必须落pending_否则重启后卡丢批准无效`() = runBlocking {
        val root = tmp("pend")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 3, writeCall("call-p", "资料库/a.txt", "内容")) { _, _ -> null }
        }
        awaitLive(r.flow, "call-p")

        val args = r.persisted.single()
        assertEquals(1L, args[0])
        assertEquals(3, args[1])
        assertEquals("write_file", args[2])
        assertTrue("参数原样落库（含路径）", (args[3] as String).contains("资料库/a.txt"))
        assertTrue("落库时间戳非 0", (args[4] as Long) > 0L)
        val card = r.cards.single()
        assertEquals("call-p", card.callId)
        assertTrue("摘要带路径（重启后同函数重算）", card.summary.contains("资料库/a.txt"))

        r.flow.resumeWithApproval("call-p", approved = false, note = null)
        job.join()
    }

    // ── 恢复执行（T3 的 JVM 面：从库里参数直接跑，不等活人） ──────────────

    @Test
    fun `恢复执行_从pending行参数直接跑_清库`() {
        val root = tmp("restore")
        val r = rig(root)
        val row = PendingRow(
            tool = "write_file",
            argsJson = JSONObject().put("path", "资料库/r.txt").put("content", "恢复写的").toString(),
            at = 999L,
        )
        val result = r.flow.restoredExecute(7L, row) { _, _ -> null }
        assertTrue("恢复后真的执行", result is GuardPipeline.Result.Done)
        assertEquals("恢复写的", File(root, "资料库/r.txt").readText())
        assertEquals("恢复执行后清 pending", 1, r.store.cleared)
    }

    // ── 用户点停止 ⇒ 作废不执行、不回灌「用户拒绝」 ──────────────────────

    @Test
    fun `点停止_挂起的确认作废_不执行不误述`() = runBlocking {
        val root = tmp("abandon")
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(1L, 1, writeCall("call-a", "a.txt", "内容")) { _, _ -> null }
        }
        awaitLive(r.flow, "call-a")
        r.flow.abandon()
        job.join()
        assertTrue("作废结局", outcome is ConfirmFlow.Outcome.Abandoned)
        assertFalse("不执行", File(root, "a.txt").exists())
        assertEquals("作废也清 pending", 1, r.store.cleared)
    }

    // ── 一次只允许一个 pending（§3.5 红线 2） ───────────────────────────

    @Test
    fun `已有pending在等_第二个确认直接丢弃`() = runBlocking {
        val root = tmp("single")
        val r = rig(root)
        val job = launch {
            r.flow.enter(1L, 1, writeCall("call-s1", "a.txt", "一")) { _, _ -> null }
        }
        awaitLive(r.flow, "call-s1")
        val second = r.flow.enter(1L, 1, writeCall("call-s2", "b.txt", "二")) { _, _ -> null }
        assertTrue("第二个被丢弃", second is ConfirmFlow.Outcome.Ignored)
        assertEquals("只落了一次 pending", 1, r.persisted.size)
        assertEquals("只出了一张卡", 1, r.cards.size)
        r.flow.resumeWithApproval("call-s1", approved = true, note = null)
        job.join()
        assertTrue(File(root, "a.txt").exists())
        assertFalse("第二个没被执行", File(root, "b.txt").exists())
    }
}
