// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十六批（2026-11-05）：批量形参一卡问清的验收套件。
//
// 判据（派工单六条里能在这层验的）：
// ① 批量删 10 个 ⇒ **只弹 1 张卡**，批准后 10 个都进回收站；
// ② 卡上写清：几个 / 总大小 / 能不能回 / 每项一行（>20 折叠成「共 N 项」）；
// ③ 其中一个不存在 ⇒ 其余照删，回执点名哪个失败；
// ④ 同时给 path 与 paths ⇒ 拒，且**一个都没动**；
// ⑤ 拒绝批准 ⇒ 文件系统零变化；
// ⑥ 单文件删除行为与现状一致（0 回归）。
//
// 另外钉住管线的批量接缝：批量里任何一项落在系统/凭据目录 ⇒ **整条调用拒**
// （否则「删一半」就不是原子，是半夜的残局）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.PendingStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BatchOpsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd-batch").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(rel: String, body: String = "x"): File = File(root, rel).apply {
        parentFile?.mkdirs()
        writeText(body, Charsets.UTF_8)
    }

    private fun pathsOf(vararg rels: String): JSONArray = JSONArray().also { arr ->
        rels.forEach { arr.put(it) }
    }

    private fun trashNames(): List<String> =
        File(root, DeletePathTool.TRASH_DIR).listFiles().orEmpty().map { it.name }

    // ── ① 批量删：一次做完 ────────────────────────────────────────────────

    @Test
    fun `B1_批量删十个_全部进回收站_回执报总数`() {
        val rels = (1..10).map { "附件/图$it.png" }
        rels.forEach { file(it, "第 $it 份") }
        val out = DeletePathTool.runBatch(rels, root = root, stamp = "S")
        assertTrue("批量删应当成功：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("回执要说删了几个：$text", text.contains("已删除 10 个文件"))
        rels.forEach { assertFalse("$it 应当已经不在原位", File(root, it).exists()) }
        assertEquals("10 个都应当在回收站里", 10, trashNames().size)
    }

    @Test
    fun `B2_其中一个不存在_其余照删_回执点名失败的`() {
        val rels = (1..9).map { "附件/图$it.png" } + "附件/早就没了.png"
        rels.dropLast(1).forEach { file(it) }
        val out = DeletePathTool.runBatch(rels, root = root, stamp = "S")
        assertTrue("有一项失败不该把整批算失败：$out", out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("要报成功几个：$text", text.contains("已删除 9 个文件"))
        assertTrue("要报失败几个：$text", text.contains("1 个没成"))
        assertTrue("要点名是哪一个：$text", text.contains("早就没了.png"))
        assertTrue("失败的项要有 ✘：$text", text.contains("✘"))
        assertEquals("其余 9 个照样进了回收站", 9, trashNames().size)
    }

    @Test
    fun `B3_全都不存在_整批如实报失败_不假装成功`() {
        val out = DeletePathTool.runBatch(listOf("没有1.png", "没有2.png"), root = root, stamp = "S")
        assertTrue("一个都没成 ⇒ Failed：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("已删除 0 个文件"))
    }

    @Test
    fun `B4_空清单直接拒_不静默成功`() {
        val out = DeletePathTool.runBatch(emptyList(), root = root)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains(PathBatch.ARG_PATHS))
    }

    // ── ④ 二选一：同时给 ⇒ 拒，且一个都没动 ──────────────────────────────

    @Test
    fun `B5_同时给path与paths_拒_文件一个都没动`() {
        val a = file("附件/a.png")
        val b = file("附件/b.png")
        val args = JSONObject().put(DeletePathTool.ARG_PATH, "附件/a.png")
            .put(PathBatch.ARG_PATHS, pathsOf("附件/b.png"))
        val out = DeletePathTool.spec { root }.executor(args)
        assertTrue("同时给两个形参必须拒：$out", out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("只能给一个"))
        assertTrue("a 必须原样在", a.isFile)
        assertTrue("b 必须原样在", b.isFile)
        assertTrue("回收站不许有东西", trashNames().isEmpty())
    }

    @Test
    fun `B6_搬一批_同时给from与paths也拒`() {
        val a = file("投递/a.txt")
        val args = JSONObject().put(MovePathTool.ARG_FROM, "投递/a.txt")
            .put(PathBatch.ARG_PATHS, pathsOf("投递/a.txt"))
            .put(MovePathTool.ARG_TO, "产物")
        val out = MovePathTool.spec { root }.executor(args)
        assertTrue(out is ToolOutcome.Failed)
        assertTrue("源必须原样在", a.isFile)
    }

    // ── ② 卡面：几个 / 总大小 / 能不能回 / 每项一行 ────────────────────────

    @Test
    fun `B7_批量卡面写清数量总大小与可恢复_每项一行`() {
        val rels = arrayOf("附件/a.png", "附件/b.png", "附件/c.png")
        rels.forEach { file(it, "0123456789") } // 每个 10 B ⇒ 共 30 B
        val summary = RunRules.confirmSummary("delete_path", JSONObject().put("paths", pathsOf(*rels)), root)
        assertTrue("要写清几个：$summary", summary.contains("3 个文件"))
        assertTrue("要写清总大小：$summary", summary.contains("共 30 B"))
        assertTrue("要写清能不能回：$summary", summary.contains("可恢复"))
        rels.forEach { assertTrue("每项要一行：$summary", summary.contains("· $it")) }
    }

    @Test
    fun `B8_超过二十项折叠成共N项_不逐条列`() {
        val rels = (1..21).map { "附件/图$it.png" }
        val summary = RunRules.confirmSummary("delete_path", JSONObject().put("paths", pathsOf(*rels.toTypedArray())), root)
        assertTrue("要报总数：$summary", summary.contains("21 个文件"))
        assertTrue("要说明折叠：$summary", summary.contains("共 21 项"))
        assertFalse("不许逐条列（21 行会把按钮挤出屏幕）：$summary", summary.contains("· 附件/图1.png"))
    }

    @Test
    fun `B9_彻底删的批量卡面不说可恢复`() {
        val rels = arrayOf("附件/a.png")
        file(rels[0])
        val summary = RunRules.confirmSummary(
            "delete_path",
            JSONObject().put("paths", pathsOf(*rels)).put("permanent", true),
            root,
        )
        assertTrue("要说清删了就没了：$summary", summary.contains("不再进回收站") || summary.contains("删了就没了"))
        assertFalse("不许说可恢复：$summary", summary.contains("可以捞回来"))
    }

    @Test
    fun `B10_批量搬与批量复制的卡面各有目标与能否回`() {
        file("投递/a.txt")
        val move = RunRules.confirmSummary(
            "move_path",
            JSONObject().put("paths", pathsOf("投递/a.txt")).put("to", "产物"),
            root,
        )
        assertTrue("搬要说目标：$move", move.contains("→ 产物"))
        assertTrue("搬要说原件不在原处：$move", move.contains("原件不在原处"))

        val copy = RunRules.confirmSummary(
            "copy_path",
            JSONObject().put("paths", pathsOf("投递/a.txt")).put("to", "产物"),
            root,
        )
        assertTrue("复制要说源还在：$copy", copy.contains("源文件原地保留"))
    }

    @Test
    fun `B11_单文件卡面一个字没变_0回归`() {
        val single = RunRules.confirmSummary(
            "delete_path",
            JSONObject().put("path", "附件/a.png"),
            root,
        )
        assertEquals("单文件删除的卡面必须与 466 之前逐字一致", "删除 附件/a.png（先进回收站，可以捞回来）", single)
        val move = RunRules.confirmSummary(
            "move_path",
            JSONObject().put("from", "a.txt").put("to", "b.txt"),
            root,
        )
        assertEquals("单文件搬动的卡面必须与 466 之前逐字一致", "移动 a.txt → b.txt（搬走后原件不在原处）", move)
    }

    // ── ⑤⑥ 一张卡 / 拒绝零变化（走真的 ConfirmFlow）──────────────────────

    private class FakeStore : PendingStore {
        override fun clear(sessionId: Long) = Unit

        override fun load(sessionId: Long): PendingRow? = null
    }

    private class Rig(val flow: ConfirmFlow, val cards: MutableList<ConfirmRequest>)

    private fun rig(): Rig {
        // FILES 组默认不可见：不解锁的话 find() 给的是「未解锁」桩，批准后不会真的删
        // （这正是判据⑤/①在真机上成立的前提——解锁是用户的动作，测试里直接摆成已解锁）
        val reg = ToolRegistry(
            unlocks = InMemoryGroupUnlocks(mapOf(1L to setOf(ToolGroup.FILES))),
        ).apply {
            currentSessionId = 1L // 解锁按会话记：find() 读的是当前会话号
            register(DeletePathTool.spec { root })
        }
        val cards = mutableListOf<ConfirmRequest>()
        val flow = ConfirmFlow(
            pipeline = GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor())),
            store = FakeStore(),
            foregroundElapsedMs = { 0L },
            onRequestCard = { cards += it },
            projectRoot = { root },
        )
        return Rig(flow, cards)
    }

    private suspend fun awaitLive(flow: ConfirmFlow, callId: String) {
        repeat(200) {
            if (flow.liveCallId() == callId) return
            delay(10)
        }
        throw AssertionError("确认卡没挂上（liveCallId=${flow.liveCallId()}）")
    }

    @Test
    fun `B12_批量删十个只弹一张卡_批准后十个都进回收站`() = runBlocking {
        val rels = (1..10).map { "附件/图$it.png" }
        rels.forEach { file(it, "第 $it 份") }
        val rig = rig()
        val call = ToolCall("c-batch", "delete_path", JSONObject().put("paths", pathsOf(*rels.toTypedArray())))
        val outcome = java.util.Collections.synchronizedList(mutableListOf<Any?>())
        val job: Job = launch { outcome += rig.flow.enter(1L, 0, call, spillWriter = { _, _ -> null }) }
        awaitLive(rig.flow, "c-batch")
        assertEquals("10 个文件只能弹 1 张卡", 1, rig.cards.size)
        val summary = rig.cards[0].summary
        assertTrue("卡上要有数量：$summary", summary.contains("10 个文件"))
        assertTrue("卡上要有可恢复：$summary", summary.contains("可恢复"))
        rig.flow.resumeWithApproval("c-batch", approved = true, note = null)
        job.join()
        rels.forEach { assertFalse("$it 应当已进回收站；结局=${outcome.firstOrNull()}", File(root, it).exists()) }
        assertEquals("十个都在回收站里", 10, trashNames().size)
    }

    @Test
    fun `B13_拒绝批准_文件系统零变化`() = runBlocking {
        val rels = (1..10).map { "附件/图$it.png" }
        rels.forEach { file(it) }
        val rig = rig()
        val call = ToolCall("c-batch-reject", "delete_path", JSONObject().put("paths", pathsOf(*rels.toTypedArray())))
        val job: Job = launch { rig.flow.enter(1L, 0, call, spillWriter = { _, _ -> null }) }
        awaitLive(rig.flow, "c-batch-reject")
        rig.flow.resumeWithApproval("c-batch-reject", approved = false, note = null)
        job.join()
        rels.forEach { assertTrue("$it 必须原样在", File(root, it).isFile) }
        assertTrue("回收站必须是空的（一个都没动）", trashNames().isEmpty())
    }

    // ── 管线接缝：批量里一项越线 ⇒ 整条拒 ────────────────────────────────

    private fun pipeline(): GuardPipeline {
        val reg = ToolRegistry(
            unlocks = InMemoryGroupUnlocks(mapOf(1L to setOf(ToolGroup.FILES))),
        ).apply {
            currentSessionId = 1L
            register(DeletePathTool.spec { root })
            register(MovePathTool.spec { root })
            register(CopyPathTool.spec { root })
        }
        return GuardPipeline(reg, { root }, { null }, ToolRunner(Executors.newSingleThreadExecutor()))
    }

    @Test
    fun `B14_批量里有一项落在凭据目录_整条拒_其余也不动`() {
        val ok = file("附件/a.png")
        val key = File(root.parentFile, ".ssh/zd-batch-${System.nanoTime()}.txt").apply {
            parentFile?.mkdirs()
            writeText("凭据", Charsets.UTF_8)
        }
        try {
            val args = JSONObject().put(
                PathBatch.ARG_PATHS,
                pathsOf(ok.absolutePath, key.absolutePath),
            )
            val r = pipeline().execute(ToolCall("c-guard", "delete_path", args), { _, _ -> null })
            assertTrue("整条调用必须被拒（不做一半）：$r", r is GuardPipeline.Result.Rejected)
            assertTrue("凭据文件不许动", key.isFile)
            assertTrue("同批里合法的那个也不许动（原子）", ok.isFile)
        } finally {
            key.delete()
        }
    }

    @Test
    fun `B15_批量路径被改写成绝对路径_执行时不再靠工作区`() {
        val a = file("附件/a.png")
        val call = ToolCall("c-abs", "delete_path", JSONObject().put(PathBatch.ARG_PATHS, pathsOf("附件/a.png")))
        val prepared = pipeline().prepare(call)
        assertTrue("应当走到出卡（Ready/NeedsConfirm 都算通过守卫）：$prepared", prepared is GuardPipeline.Prepared.Settled)
        val settled = prepared as GuardPipeline.Prepared.Settled
        val rewritten = (settled.result as GuardPipeline.Result.NeedsConfirm).call.arguments
        assertEquals(
            "paths 必须与单数键同一口径（改写为绝对路径）",
            a.absolutePath,
            rewritten.getJSONArray(PathBatch.ARG_PATHS).optString(0),
        )
    }

    // ── 搬一批 / 复制一批 ────────────────────────────────────────────────

    @Test
    fun `B16_搬一批_都落到目标目录_各自保留原名`() {
        val rels = arrayOf("投递/a.txt", "投递/b.txt", "投递/c.txt")
        rels.forEach { file(it, "内容-$it") }
        val out = MovePathTool.moveBatch(root, rels.toList(), "产物/搬来的", stamp = "S")
        assertTrue("批量搬动应当成功：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("已搬动 3 个文件"))
        listOf("a.txt", "b.txt", "c.txt").forEach {
            assertTrue("$it 应当落到目标目录", File(root, "产物/搬来的/$it").isFile)
        }
        rels.forEach { assertFalse("$it 应当离开原处", File(root, it).exists()) }
    }

    @Test
    fun `B17_复制一批_源原地保留`() {
        val rels = arrayOf("投递/a.txt", "投递/b.txt")
        rels.forEach { file(it) }
        val out = CopyPathTool.copyBatch(root, rels.toList(), "产物/备份")
        assertTrue("批量复制应当成功：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("已复制 2 个文件"))
        rels.forEach { assertTrue("源必须原地保留：$it", File(root, it).isFile) }
        listOf("a.txt", "b.txt").forEach {
            assertTrue("$it 应当复制过去", File(root, "产物/备份/$it").isFile)
        }
    }

    @Test
    fun `B18_搬一批时to是个文件_拒并说清怎么办`() {
        file("投递/a.txt")
        file("产物/已经是个文件.txt")
        val out = MovePathTool.moveBatch(root, listOf("投递/a.txt"), "产物/已经是个文件.txt")
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).error.contains("目录"))
        assertTrue("源必须原样在", File(root, "投递/a.txt").isFile)
    }

    @Test
    fun `B19_批量清单里的重复项只做一次`() {
        file("附件/a.png")
        val args = JSONObject().put(PathBatch.ARG_PATHS, pathsOf("附件/a.png", "附件/a.png"))
        val out = DeletePathTool.spec { root }.executor(args)
        assertTrue("同一个文件写两遍＝一件事：$out", out is ToolOutcome.Ok)
        assertTrue((out as ToolOutcome.Ok).text.contains("已删除 1 个文件"))
        assertEquals("回收站里只有一份", 1, trashNames().size)
    }
}
