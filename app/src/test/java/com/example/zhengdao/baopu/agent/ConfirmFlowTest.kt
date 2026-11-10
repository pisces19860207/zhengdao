// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十二批追加二 §12.3（J1–J5 逐条）＋ §3.2/§3.3（引擎挂起与恢复、
// 落库三列）。判据全压 ConfirmFlow 这个 Context-free 内核（引擎壳要真 SQLite +
// 网络，本仓无 Robolectric ⇒ 整环归真机 T1–T4，见交付报告挂账清单）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CardDisposition
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.baopu.inspect.DeviationEntry
import com.example.zhengdao.baopu.inspect.DeviationKind
import com.example.zhengdao.baopu.inspect.DeviationLedger
import com.example.zhengdao.ui.baopu.BaopuConfirmRestore
import java.io.File
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
import com.example.zhengdao.testkit.TempSandboxPool
import org.junit.After

/**
 * 确认制内核测试（J1–J5 ＋ pending 落库 + 恢复执行）。
 *
 * 每一例都走**真的** GuardPipeline + 真的 write_file 执行器（临时目录），
 * 不 mock 执行链——「批准后文件真的被改」就是要跑到底才叫验过。
 */
class ConfirmFlowTest {
    // 573：临时目录统一走 testkit/TempSandbox（经 TempSandboxPool 登记，@After 收口）；断言零改动。
    @After
    fun zdCleanSandboxes() = TempSandboxPool.clean()


    private fun tmp(name: String): File =
        TempSandboxPool.create("baopu-confirm-$name")

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
        /** onPersistPending 的实参流水（sessionId, step, tool, argsJson, at, runId, taskId）——「落库」的账。 */
        val persisted: MutableList<Array<Any?>>,
        val cards: MutableList<ConfirmRequest>,
        val root: File,
    )

    // foreground 保持末位：调用点的尾随 lambda（J3 超时钟）靠位置绑定，插在它前面会静默改绑。
    private fun rig(root: File, projectRoot: () -> File? = { null }, foreground: () -> Long = { 0L }): Rig {
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
            pipeline = GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor())),
            store = store,
            foregroundElapsedMs = foreground,
            onPersistPending = { sid, step, tool, args, at, runId, taskId ->
                persisted += arrayOf(sid, step, tool, args, at, runId, taskId)
            },
            onRequestCard = { cards += it },
            projectRoot = projectRoot,
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
                ToolCall("call-5", "probe_write", JSONObject().put("path", "../.ssh/steal.txt")),
            ) { _, _ -> null }
        }
        awaitLive(r.flow, "call-5")
        r.flow.resumeWithApproval("call-5", approved = true, note = null)
        job.join()

        val approved = outcome as ConfirmFlow.Outcome.Approved
        val result = approved.result
        assertTrue(
            "受保护路径（凭据目录）批准后仍是 Rejected（不是 NeedsConfirm 死循环，也不是执行成功）",
            result is GuardPipeline.Result.Rejected,
        )
        assertTrue((result as GuardPipeline.Result.Rejected).reason.contains("凭据"))
        assertFalse("凭据目录里没写出东西", File(parent, ".ssh/steal.txt").exists())
        assertEquals("拒绝（越界）后也清 pending", 1, r.store.cleared)
    }

    @Test
    fun `J5纵深防御_write_file凭据目录_管线与工具自检双拒`() = runBlocking {
        // 287：工作区外不再是红线（全盘可写），但**凭据目录**仍是——
        // 「批准 ≠ 绕过护栏」这一条改由它钉住（同一条红线，换了个判据面）。
        val parent = tmp("j5b-parent")
        val root = File(parent, "ws").apply { mkdirs() }
        val r = rig(root)
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(
                1L, 1,
                ToolCall("call-5b", "write_file", JSONObject().put("path", "../.ssh/x.txt").put("content", "脏手")),
            ) { _, _ -> null }
        }
        awaitLive(r.flow, "call-5b")
        r.flow.resumeWithApproval("call-5b", approved = true, note = null)
        job.join()
        val result = (outcome as ConfirmFlow.Outcome.Approved).result
        assertTrue("write_file 也有内置第二道自检", result is GuardPipeline.Result.Rejected)
        assertFalse(File(parent, ".ssh/x.txt").exists())
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

    /**
     * 第四百三十批·判据①：起单写 pending 必带**挂起那一轮**的 runId。
     *
     * 为什么钉在内核这一层：引擎把这两格搬进 session 的 `pending_run_id`／`pending_task_id`
     * 没有判断可做，真正会错的是**取哪一轮的号**——取成重启后的现轮，号码格式合法、
     * 账上看不出来，但 375 串「偏差→处置→恢复」串到第三段就串错了轮。
     */
    @Test
    fun `落pending必带挂起那轮runId_判据1`() = runBlocking {
        val root = tmp("pend-430")
        val r = rig(root)
        DeviationWiring.resetForTest()
        // 引擎 newRun 处的同一口信箱：装本轮 runId；taskId 生产没有真值来源 ⇒ 空串
        DeviationWiring.installRun("r-430@1000", "")
        try {
            val job = launch {
                r.flow.enter(1L, 2, writeCall("call-430", "资料库/b.txt", "内容")) { _, _ -> null }
            }
            awaitLive(r.flow, "call-430")
            val args = r.persisted.single()
            assertEquals("挂起那轮的 runId 必须跟着 pending 落库", "r-430@1000", args[5])
            assertEquals("taskId 取不到 ⇒ 交空串（引擎侧绑 NULL），编一个就是造假", "", args[6])
            r.flow.resumeWithApproval("call-430", approved = false, note = null)
            job.join()
        } finally {
            DeviationWiring.resetForTest()
        }
    }

    /**
     * 第四百三十批·负例：出处信箱是空的（收工后／测试摘过）⇒ 落库两格都留空。
     *
     * 打的是「拿别的号顶上」这一类变异：把 sessionId、`"unknown"`、现轮号写进出处都红。
     * 宁空勿猜是 415 定下的纪律，本批只是把真值存进了 pending，没把它换成「总得有值」。
     */
    @Test
    fun `信箱空时出处如实留空_不许编`() = runBlocking {
        val root = tmp("pend-430-none")
        val r = rig(root)
        DeviationWiring.resetForTest()
        try {
            val job = launch {
                r.flow.enter(1L, 1, writeCall("call-none", "资料库/c.txt", "内容")) { _, _ -> null }
            }
            awaitLive(r.flow, "call-none")
            val args = r.persisted.single()
            assertEquals("信箱空 ⇒ runId 不带任何编出来的号", "", args[5])
            assertEquals("taskId 同上", "", args[6])
            r.flow.resumeWithApproval("call-none", approved = false, note = null)
            job.join()
        } finally {
            DeviationWiring.resetForTest()
        }
    }

    /**
     * 第四百三十批·判据①（引擎侧）：引擎那条手写 UPDATE 必须**带着出处两格**，
     * 且值只许来自内核传进来的形参。
     *
     * 引擎整环要真 SQLite（无 Robolectric ⇒ 归真机），所以这一条钉在源码形状上（房型照
     * RunEngineConcurrencyGateTest：去注释、可 grep）。打的两类变异：
     * ① UPDATE 里漏掉两列 ⇒ 列加了、库里永远 NULL，恢复行照旧是空键（判据②静默失效）；
     * ② 落库这一刻现取出处信箱 ⇒ 恢复路径上没有信箱（重启后的现轮），拿到的号是**别的轮**，
     *    号码格式合法、账上看不出来——比留空更坏。
     */
    @Test
    fun `引擎落pending的UPDATE必带出处两格_值取自内核不许现取信箱`() {
        val rel = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
        val src = File(rel).let { if (it.isFile) it else File("app/$it") }
            .readText()
            .replace("\r\n", "\n")
            .lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
        val head = "private fun enterAwaitConfirm("
        val i = src.indexOf(head)
        assertTrue("找不到 enterAwaitConfirm", i >= 0)
        // 注释已在上面剥掉 ⇒ 函数体的尽头就是下一个 `private fun`（取本体，不把邻居拖进来）
        val end = src.indexOf("\n    private fun", i + head.length)
        assertTrue("enterAwaitConfirm 之后找不到函数边界（形状变了，这一条得跟着改）", end > i)
        val body = src.substring(i, end)
        assertTrue("签名要收出处两格（内核传的）", body.contains("runId: String,") && body.contains("taskId: String,"))
        assertTrue("手写 UPDATE 必带两列", body.contains("pending_run_id = ?, pending_task_id = ? WHERE id = ?"))
        assertTrue("空白绑 NULL 不写空串", body.contains("runId.takeIf { it.isNotBlank() }, taskId.takeIf { it.isNotBlank() },"))
        assertFalse("不许在落库这一刻现取出处信箱（取到的是别的轮）", body.contains("DeviationWiring.currentRunId()"))
        assertFalse("taskId 同上", body.contains("DeviationWiring.currentTaskId()"))
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

    // ── 第三百二十六批 判据③：恢复卡批准也要落一行处置 ─────────────────────

    @Test
    fun `判据③_恢复卡批准落一行处置_与在线批准逐格一致`() = runBlocking {
        val root = tmp("restore-disp")
        val ledger = tmp("restore-disp-ledger")
        // 处置账的落点＝208 装配的那棵目录（与生产同源）；不装就只写内存，验不出"盘上真有那行"
        DecisionLedger.install(ledger)
        CardDisposition.resetForTest()
        try {
            val r = rig(root, projectRoot = { root })

            // ① 在线路径：真挂一张卡、真点批准 —— 这就是"正常路径"的参照行
            val onlinePath = File(root, "资料库/在线.txt").absolutePath
            var outcome: ConfirmFlow.Outcome? = null
            val job = launch {
                outcome = r.flow.enter(1L, 1, writeCall("call-online", onlinePath, "甲")) { _, _ -> null }
            }
            awaitLive(r.flow, "call-online")
            r.flow.resumeWithApproval("call-online", approved = true, note = null)
            job.join()
            assertTrue("在线路径得先真的批准执行：$outcome", outcome is ConfirmFlow.Outcome.Approved)
            val online = CardDisposition.rowsFor("call-online").single()

            // ② 重启恢复：库里那一行（同工具、同形状参数、另一个文件）。
            //    runId 这一格是第四百三十批新加的列——挂起那轮的号跟着 pending 一起落库，
            //    重启后由它把恢复行的出处填上；taskId 还没有真值来源，库里就是 NULL。
            val restoredPath = File(root, "资料库/恢复.txt").absolutePath
            val row = PendingRow(
                tool = "write_file",
                argsJson = JSONObject().put("path", restoredPath).put("content", "乙").toString(),
                at = 4_242L,
                runId = "r-326@1000",
            )
            val before = CardDisposition.replay().size
            val clearedBefore = r.store.cleared

            val result = r.flow.restoredExecute(7L, row) { _, _ -> null }

            // ③ 恢复逻辑一字未动：还是真执行、还清库（负例靶心：不许"只为写账"动这条路）
            assertTrue("恢复后仍要真的执行（恢复逻辑没改）：$result", result is GuardPipeline.Result.Done)
            assertEquals("乙", File(root, "资料库/恢复.txt").readText())
            assertEquals("清 pending 的时机没变", clearedBefore + 1, r.store.cleared)

            // ④ 处置账 +1 行，且与在线批准逐格一致
            assertEquals("恢复卡批准必须 +1 行", before + 1, CardDisposition.replay().size)
            val restored = CardDisposition.rowsFor("restored-4242").single()
            // 第四百三十批判据②：恢复行不再是空键——出处从 pending 那两列取（415 只能留空）
            assertEquals("恢复行带挂起那轮的 runId", "r-326@1000", restored.runId)
            assertEquals("taskId 无真值来源 ⇒ 库里 NULL、账上空串，不许编", "", restored.taskId)
            assertEquals("选了哪个要一致", online.choice, restored.choice)
            assertEquals("哪张卡要一致", online.face, restored.face)
            assertEquals("谁批的要一致（是她点的，不是时钟）", online.actor, restored.actor)
            assertEquals("理由用同一句类别句兜底（批准路径两边都留空）", online.why, restored.why)
            assertEquals(
                "subject 必须与恢复卡面那句话同一个函数同一个根算出来",
                BaopuConfirmRestore.card(row, root).summary,
                restored.subject,
            )
            assertTrue("subject 要落在这张卡的路径上：${restored.subject}", restored.subject.contains("恢复.txt"))

            // ⑤ 真落盘（不是只在内存里过了一遍）
            val onDisk = CardDisposition.fileIn(ledger).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertEquals("盘上就是这两行（在线一条、恢复一条）", 2, onDisk.size)
            assertTrue(
                "恢复那行要能被本账自己的解析器读回（读不回＝318 学不到）",
                onDisk.any { CardDisposition.CardDispositionRow.parse(it)?.cardId == "restored-4242" },
            )
        } finally {
            DecisionLedger.uninstallForTest()
            CardDisposition.resetForTest()
        }
    }

    // ── 第三百二十六批 判据③b：恢复卡拒绝也要落一行处置 ─────────────────────

    @Test
    fun `判据③b_恢复卡拒绝落一行处置_与在线拒绝逐格一致`() = runBlocking {
        val root = tmp("restore-rej")
        val ledger = tmp("restore-rej-ledger")
        DecisionLedger.install(ledger)
        CardDisposition.resetForTest()
        try {
            val r = rig(root, projectRoot = { root })

            // ① 在线拒绝：真挂一张卡、真点拒绝 —— 参照行
            val onlinePath = File(root, "资料库/在线拒.txt").absolutePath
            var outcome: ConfirmFlow.Outcome? = null
            val job = launch {
                outcome = r.flow.enter(1L, 1, writeCall("call-online-r", onlinePath, "甲")) { _, _ -> null }
            }
            awaitLive(r.flow, "call-online-r")
            r.flow.resumeWithApproval("call-online-r", approved = false, note = null)
            job.join()
            assertTrue("在线路径得先真的拒绝：$outcome", outcome is ConfirmFlow.Outcome.Rejected)
            val online = CardDisposition.rowsFor("call-online-r").single()

            // ② 恢复拒绝：库里那一行（先 load 再记，清库之后就没有"哪张卡"了）
            //    出处一格同批准路径：第四百三十批起由 pending 带着走（判据②两路都要有键）
            val restoredPath = File(root, "资料库/恢复拒.txt").absolutePath
            val row = PendingRow(
                tool = "write_file",
                argsJson = JSONObject().put("path", restoredPath).put("content", "乙").toString(),
                at = 5_252L,
                runId = "r-326@2000",
            )
            val before = CardDisposition.replay().size
            val noted = r.flow.restoredReject(row, BaopuConfirmRestore.card(row, root).summary)

            // ③ 拒绝不执行、处置账 +1 行、与在线拒绝逐格一致
            assertTrue("恢复拒绝要真的记下这一行", noted)
            assertFalse("拒绝就是不执行", File(root, "资料库/恢复拒.txt").exists())
            assertEquals("恢复卡拒绝必须 +1 行", before + 1, CardDisposition.replay().size)
            val restored = CardDisposition.rowsFor("restored-5252").single()
            assertEquals("恢复行带挂起那轮的 runId", "r-326@2000", restored.runId)
            assertEquals("taskId 无真值来源 ⇒ 库里 NULL、账上空串，不许编", "", restored.taskId)
            assertEquals("选了哪个要一致", online.choice, restored.choice)
            assertEquals("驳就是驳", CardDisposition.Choice.REJECT, restored.choice)
            assertEquals("哪张卡要一致", online.face, restored.face)
            assertEquals("谁驳的要一致（是她点的，不是时钟）", online.actor, restored.actor)
            assertEquals("理由用同一句类别句兜底（两句都得是「人类驳了」而不是「没人表态」）", online.why, restored.why)
            assertEquals(
                "subject 必须与恢复卡面那句话同一个函数同一个根算出来",
                BaopuConfirmRestore.card(row, root).summary,
                restored.subject,
            )

            // ④ 真落盘：在线一条、恢复一条
            val onDisk = CardDisposition.fileIn(ledger).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertEquals("盘上就是这两行", 2, onDisk.size)
            assertTrue(
                "恢复拒绝那行要能被本账自己的解析器读回",
                onDisk.any { CardDisposition.CardDispositionRow.parse(it)?.cardId == "restored-5252" },
            )
        } finally {
            DecisionLedger.uninstallForTest()
            CardDisposition.resetForTest()
        }
    }

    // ── 第四百一十五批 判据③：挂起超时三行账用键串成一条链（偏差→处置→恢复） ──

    @Test
    fun `判据415_挂起超时三行账按键串链_偏差到处置到恢复`() = runBlocking {
        val root = tmp("chain-415")
        val ledger = tmp("chain-415-ledger")
        DecisionLedger.install(ledger)
        DeviationLedger.install(ledger)
        CardDisposition.resetForTest()
        DeviationWiring.resetForTest()
        try {
            // ① 引擎开轮（RunEngine.newRun 处的同一口：出处信箱装本轮 runId）
            DeviationWiring.installRun("r7@1000", "")
            // ② 挂起超时：注入超时钟（前台已累计 16 分钟 > 15 分钟线）
            val r = rig(root, projectRoot = { root }) { 16 * 60 * 1000L }
            var outcome: ConfirmFlow.Outcome? = null
            val job = launch {
                outcome = r.flow.enter(1L, 1, writeCall("call-chain", "t.txt", "内容")) { _, _ -> null }
            }
            awaitCardOrDone(r, job)
            job.join()
            assertTrue("先真的超时：$outcome", outcome is ConfirmFlow.Outcome.TimedOut)

            // ③ 偏差行：主键 runId ＋ 事实键 suspended_at／op_id
            val dev = DeviationLedger.read(ledger).single { it.kind == DeviationKind.SUSPEND_TIMEOUT }
            assertEquals("偏差行主键＝本轮 runId", "r7@1000", dev.runId)
            val suspendedAt = checkNotNull(dev.facts[DeviationEntry.F_SUSPENDED_AT]?.toLongOrNull()) {
                "挂起时刻必须是事实键：${dev.facts.keys}"
            }
            assertEquals("偏差行带卡级键 op_id＝挂起那次调用的 id", "call-chain", dev.facts[DeviationEntry.F_OP_ID])

            // ④ 处置行（时钟收卡）：主键同轮 ＋ card 就是那个 op_id —— 偏差→处置按键 join
            val dismiss = CardDisposition.rowsFor("call-chain").single()
            assertEquals("处置行主键＝同一轮 runId", dev.runId, dismiss.runId)
            assertEquals("处置行 card＝偏差行 op_id（不再靠 subject 文本硬凑）", dev.facts[DeviationEntry.F_OP_ID], dismiss.cardId)
            assertEquals("没人表态是时钟收卡", CardDisposition.Actor.CLOCK, dismiss.actor)

            // ④' 挂起那轮的出处跟着 pending 一起落库（第四百三十批判据①）——与偏差行同键
            val persistedRunId = r.persisted.single()[5] as String
            assertEquals(
                "pending 落库带的就是挂起那轮的 runId（与偏差行主键同值，重启后才取得回来）",
                dev.runId,
                persistedRunId,
            )

            // ⑤ 恢复行（重启后对恢复卡表态那半）：出处从 pending 那两列取。
            //    第四百三十批改的就是这一格——415 当时只能留空（挂起那轮的号没落库），
            //    现在库里带着它，恢复行因此与偏差行同键，链的第三段接得上。
            DeviationWiring.clearRun() // 模拟重启：新进程的信箱里没有挂起那轮的 runId
            val restoredArgs = JSONObject()
                .put("path", File(root, "t.txt").absolutePath)
                .put("content", "恢复写的")
                .toString()
            val result = r.flow.restoredExecute(
                7L,
                PendingRow(
                    tool = "write_file",
                    argsJson = restoredArgs,
                    at = suspendedAt,
                    runId = persistedRunId, // 库里那一列读回来的样子
                ),
            ) { _, _ -> null }
            assertTrue("恢复要真的执行（链的第三行是恢复卡处置行的副产物）：$result", result is GuardPipeline.Result.Done)
            val restored = CardDisposition.rowsFor("restored-$suspendedAt").single()
            assertEquals(
                "恢复行带挂起那轮的 runId（取代 415 的「留空」——出处已存进 pending，拿现轮才是编）",
                dev.runId,
                restored.runId,
            )
            assertEquals("taskId 还没有真值来源 ⇒ 恢复行那一格如实空", "", restored.taskId)
            assertEquals("恢复行 card＝restored-<suspended_at>，与偏差行事实键同刻", "restored-$suspendedAt", restored.cardId)

            // ⑥ 盘上都能被各自解析器读回：偏差 1 行、处置 2 行（新列与老行口径并存）
            assertEquals(1, DeviationLedger.fileIn(ledger).readLines(Charsets.UTF_8).count { it.isNotBlank() })
            val dispOnDisk = CardDisposition.fileIn(ledger).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertEquals("处置账两行（时钟收卡＋恢复批准）", 2, dispOnDisk.size)
            assertTrue("每行都要能被本账解析器读回", dispOnDisk.all { CardDisposition.CardDispositionRow.parse(it) != null })
        } finally {
            DecisionLedger.uninstallForTest()
            CardDisposition.resetForTest()
            DeviationWiring.resetForTest()
            // 卸账＝挂一个空目录（本仓的既有口径：DeviationWiringSilentTest 同款）
            DeviationLedger.install(TempSandboxPool.create("baopu-415-detach"))
        }
    }

    @Test
    fun `判据415_处置账旧行没有出处两列_读成空串照认`() {
        val legacy = JSONObject()
            .put("v", 1)
            .put("id", "cd:1000:1")
            .put("at", 1_000L)
            .put("face", "confirm")
            .put("card", "call-old")
            .put("choice", "dismiss")
            .put("actor", "clock")
            .put("subject", "旧摘要")
            .put("why", "确认超时，没人表态 ⇒ 这张卡作废（既不算批也不算驳）")
            .toString()
        val row = checkNotNull(CardDisposition.CardDispositionRow.parse(legacy)) { "旧行不许被新列拒掉" }
        assertEquals("call-old", row.cardId)
        assertEquals("老行 runId 读成空串", "", row.runId)
        assertEquals("老行 taskId 读成空串", "", row.taskId)
    }

    @Test
    fun `判据③b_pending已经不在了_不落账`() {
        val root = tmp("restore-rej-null")
        val ledger = tmp("restore-rej-null-ledger")
        DecisionLedger.install(ledger)
        CardDisposition.resetForTest()
        try {
            val r = rig(root)
            // 已经处理过（库里没这行）⇒ 这条"问答"根本没发生，给它留一行处置就是编的
            assertFalse("null 必须返回 false（没落）", r.flow.restoredReject(null, "删 资料库/x.md"))
            assertEquals("一行都不许落", 0, CardDisposition.replay().size)
            assertFalse("盘上也不许有这本账", CardDisposition.fileIn(ledger).exists())
        } finally {
            DecisionLedger.uninstallForTest()
            CardDisposition.resetForTest()
        }
    }

    /**
     * ③b 的接线门禁：**引擎的拒绝分支真调了记账，而且是在清库之前**。
     *
     * 为什么这条只能扫源码：`rejectRestoredPending` 要真 SQLite（本仓无 Robolectric），
     * JVM 面测不到"它到底调没调"；而"有实现、零调用点"正是 276 那类病的形状——
     * 内核那三条测试全绿，引擎却没接，谁也不会发现。断言的是**顺序**（load → 记账 → clear），
     * 不是"文件里出现过这几个字"：清库之后 `load` 只能拿到 null，"哪张卡"就丢了，
     * 这个顺序本身就是判据。
     */
    @Test
    fun `判据③b_引擎拒绝分支先load再记账_记账在clear之前`() {
        val src = mainSource("baopu/agent/RunEngine.kt")
        val start = src.indexOf("fun rejectRestoredPending(")
        assertTrue("找不到 rejectRestoredPending（改名/挪走了？先改这条门禁）", start > 0)
        val body = src.substring(start).substringBefore("\n    /**")
        val iLoad = body.indexOf("pendingStore.load(")
        val iNote = body.indexOf("confirmFlow.restoredReject(")
        val iClear = body.indexOf("pendingStore.clear(")
        assertTrue("恢复拒绝必须先 load 取出 row（否则 cardId 无处可取）", iLoad > 0)
        assertTrue("必须真调记账——有实现零调用点＝没做", iNote > 0)
        assertTrue("清库之后 load 只能拿到 null ⇒ 记账必须在 clear 之前", iClear > 0 && iLoad < iNote && iNote < iClear)
    }

    /** 生产源码（剥注释；与 HealthWiringGateTest 同一手法）。 */
    private fun mainSource(rel: String): String {
        val f = File("src/main/java/com/example/zhengdao", rel)
        assertTrue("找不到 $rel（被挪走/改名了？先改这条门禁）", f.isFile)
        return f.readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
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
    // ── 第一百四十九批 组5：确认卡显示相对路径（走完整条引擎链路）───────────

    @Test
    fun `组5_摘要按工作区根剥成相对_落库参数仍是绝对原样`() = runBlocking {
        val root = tmp("relpath")
        val r = rig(root, projectRoot = { root })
        // 生产形状：管线②段把 "资料库/x.txt" 改写成了绝对路径，③段才出卡
        // （GuardPipeline.prepare → Result.NeedsConfirm(effectiveCall)）。
        val abs = File(root, "资料库/x.txt").absolutePath
        var outcome: ConfirmFlow.Outcome? = null
        val job = launch {
            outcome = r.flow.enter(1L, 1, writeCall("call-rel", abs, "甲\n乙")) { _, _ -> null }
        }
        awaitLive(r.flow, "call-rel")
        val card = r.cards.first()
        val shown = File("资料库", "x.txt").path
        assertTrue("摘要没剥成相对路径（卡面泄目录结构）：${card.summary}", card.summary.contains(shown))
        assertFalse("摘要里还留着绝对前缀：${card.summary}", card.summary.contains(root.absolutePath))
        // 落库参数按 JSON 取值比（argsJson 里反斜杠是转义过的，字符串 contains 会假红）。
        assertEquals("执行参数被显示逻辑改掉了（批准后会写到别处）", abs,
            JSONObject(card.argsJson).getString("path"))
        r.flow.resumeWithApproval("call-rel", approved = true, note = null)
        job.join()
        assertTrue("剥路径只该动显示，文件仍落在工作区内原位", File(root, "资料库/x.txt").exists())
        assertTrue(outcome is ConfirmFlow.Outcome.Approved)
    }

    @Test
    fun `组5_不给工作区根时摘要逐字节保持旧行为`() = runBlocking {
        val root = tmp("relpath-off")
        val r = rig(root) // projectRoot 默认 { null } = 接线前的形状
        val abs = File(root, "keep2.txt").absolutePath
        val job = launch { r.flow.enter(1L, 1, writeCall("call-keep", abs, "一")) { _, _ -> null } }
        awaitLive(r.flow, "call-keep")
        assertTrue("没给根却剥了路径 = 凭空发明短路径",
            r.cards.first().summary.contains(abs))
        r.flow.resumeWithApproval("call-keep", approved = false, note = "停")
        job.join()
    }
}
