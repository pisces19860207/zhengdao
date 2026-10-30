// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）管线层测试：**工作区不再是权限边界**。
//
// 判据（派工单 287「判据」六条里能在这层验的四条）：
// ① 不开任何授权，绝对路径直接读得到（**不弹卡**）；
// ② 相对路径 / 不给路径 ⇒ 解析到工作区（默认落点）；
// ③ 删 / 移必确认（`NeedsConfirm`，卡面摘要写清删什么、能不能回）；
// ④ 系统与凭据目录一律拒（`Rejected`，文案说清是什么地方）；
// ⑤ 每笔文件操作入账（路径＋用途）。
//
// 负例靶心：越界读还要弹卡（＝287 没做）、系统目录能读（＝硬下限破了）、
// 删文件不弹卡（＝破坏性动作静默执行）、操作不落账（＝不可回放）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.FileOpKind
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class FileAccessPipelineTest {

    private lateinit var root: File
    private val lines = mutableListOf<String>()

    private fun directRunner() = ToolRunner(Executors.newSingleThreadExecutor())

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(ReadFileTool.spec())
        register(WriteFileTool.spec { root })
        register(DeletePathTool.spec { root })
        register(MovePathTool.spec { root })
        register(ListDirTool.spec { root })
    }

    private fun pipeline(): GuardPipeline =
        GuardPipeline(registry(), { root }, { null }, directRunner(), { lines += it })

    private fun call(name: String, path: String, extra: JSONObject.() -> Unit = {}): ToolCall =
        ToolCall("c-$name", name, JSONObject().put("path", path).apply(extra))

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ws-full").toFile()
        FileOpLog.resetForTest()
        FileOpLog.enterSession(SESSION_ID)
    }

    @After
    fun tearDown() {
        FileOpLog.resetForTest()
    }

    private fun outsideFile(name: String = "note.md", body: String = "外面的内容"): File =
        File(root.parentFile, "full-disk-outside").apply { mkdirs() }.let {
            File(it, name).apply { writeText(body) }
        }

    // ── ① 全盘读：不弹卡 ───────────────────────────────────────────────

    @Test
    fun `判据一_绝对路径直接读_零张卡`() {
        val f = outsideFile()
        val r = pipeline().execute(call("read_file", f.absolutePath), { _, _ -> null })
        assertTrue("越界读不该再弹卡（287：读全盘是默认能力）：$r", r is GuardPipeline.Result.Done)
        assertTrue((r as GuardPipeline.Result.Done).wrapped.contains("外面的内容"))
    }

    @Test
    fun `判据二_相对路径落在工作区`() {
        File(root, "资料库").mkdirs()
        File(root, "资料库/设定.md").writeText("工作区里的内容")
        val r = pipeline().execute(call("read_file", "资料库/设定.md"), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Done)
        assertTrue((r as GuardPipeline.Result.Done).wrapped.contains("工作区里的内容"))
    }

    @Test
    fun `判据二_不给路径_列工作区根`() {
        File(root, "产物").mkdirs()
        val r = pipeline().execute(ToolCall("c-list", "list_dir", JSONObject()), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Done)
        assertTrue((r as GuardPipeline.Result.Done).wrapped.contains("产物"))
    }

    // ── ④ 系统与凭据目录一律拒 ─────────────────────────────────────────

    @Test
    fun `判据四_凭据目录拒`() {
        // 为什么不用 `/system` 验系统区：JVM 单测跑在 Windows 上时 `/system/x` 会被 JDK
        // 解释成 `C://system//x`（测试宿主的语义）。系统区那一条由 `FilePolicyTest`
        // 的纯函数用例钉住（denyReasonOf("/system/…")），这里验同一族规则里的凭据目录。
        val r = pipeline().execute(call("read_file", "/storage/emulated/0/.ssh/id_rsa"), { _, _ -> null })
        assertTrue("凭据目录必须拒", r is GuardPipeline.Result.Rejected)
        assertTrue((r as GuardPipeline.Result.Rejected).reason.contains("凭据"))
    }

    @Test
    fun `判据四_别的应用的数据目录拒`() {
        val r = pipeline()
            .execute(call("read_file", "/storage/emulated/0/Android/data/com.other.app/files/x.db"), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Rejected)
    }

    // ── ③ 删 / 移必确认 ───────────────────────────────────────────────

    @Test
    fun `判据三_删笔记必弹确认_取消后文件还在`() {
        val f = outsideFile("要删的笔记.md")
        val p = pipeline()
        val r = p.execute(call("delete_path", f.absolutePath), { _, _ -> null })
        assertTrue("删文件必须先问（287 判据④）", r is GuardPipeline.Result.NeedsConfirm)
        assertTrue("没批准之前文件必须原样在", f.exists())
    }

    @Test
    fun `判据三_确认卡摘要写清删什么_能不能回`() {
        val f = outsideFile("要删的笔记.md")
        val soft = RunRules.confirmSummary(
            "delete_path",
            JSONObject().put("path", f.absolutePath),
            root,
        )
        assertTrue("要写清是哪个文件：$soft", soft.contains("要删的笔记.md"))
        assertTrue("要写清能不能回：$soft", soft.contains("回收站") && soft.contains("捞回来"))

        val hard = RunRules.confirmSummary(
            "delete_path",
            JSONObject().put("path", f.absolutePath).put("permanent", true),
            root,
        )
        assertTrue("彻底删要说不可恢复：$hard", hard.contains("不可恢复") || hard.contains("删了就没了"))

        val trash = RunRules.confirmSummary("delete_path", JSONObject().put("empty_trash", true), root)
        assertTrue("清空回收站要说不可恢复：$trash", trash.contains("不可恢复"))
    }

    @Test
    fun `判据三_批准后真的删_进工作区回收站`() {
        // 管线这一层只验"必问"（上面那条）；"批准之后真的删"直接打工具本体——
        // 管线还要过工具组解锁闸（`capabilities`），那是另一件事，别混进来。
        val f = outsideFile("要删的笔记.md")
        val r = DeletePathTool.run(f.absolutePath, root = root)
        assertTrue("删除成功：$r", r is ToolOutcome.Ok)
        assertFalse("文件不在原位了", f.exists())
        assertTrue("进了回收站（可捞回来）", File(root, DeletePathTool.TRASH_DIR).listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `判据三_移动必确认`() {
        val f = outsideFile("要搬的笔记.md")
        val to = File(root, "产物/搬来的.md")
        val c = ToolCall(
            "c-move",
            "move_path",
            JSONObject().put("from", f.absolutePath).put("to", to.absolutePath),
        )
        val p = pipeline()
        assertTrue("搬文件必须先问", p.execute(c, { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        assertTrue("没批准之前源文件必须原样在", f.exists())
    }

    // ── 红线：原件区照旧只读（与权限模型无关）──────────────────────────

    @Test
    fun `红线_原件区写入仍拒`() {
        val raw = File(root, "资料库/原始").apply { mkdirs() }
        val target = File(raw, "book.txt").apply { writeText("原件") }
        val p = GuardPipeline(registry(), { root }, { raw }, directRunner(), { lines += it })
        val c = call("write_file", target.absolutePath) { put("content", "改原件") }
        val r = p.execute(c, { _, _ -> null })
        assertTrue("原件区只读，全盘可写也翻不了它", r is GuardPipeline.Result.Rejected)
    }

    // ── ⑤ 入账 ────────────────────────────────────────────────────────

    @Test
    fun `判据五_每笔操作入账_路径与用途`() {
        val f = outsideFile()
        val p = pipeline()
        p.execute(call("read_file", f.absolutePath), { _, _ -> null })
        val rows = FileOpLog.recent(10)
        assertEquals("读一笔 ⇒ 一行账", 1, rows.size)
        assertEquals(FileOpKind.READ, rows[0].kind)
        assertEquals("路径要记归一后的绝对路径", f.canonicalPath, rows[0].path)
        assertEquals("用途要写清在干什么", "读文件", rows[0].purpose)
        assertEquals("会话要记", SESSION_ID, rows[0].sessionId)
    }

    @Test
    fun `判据五_被拒的操作不入账_不假装做过`() {
        pipeline().execute(call("read_file", "/storage/emulated/0/.ssh/id_rsa"), { _, _ -> null })
        assertEquals("拒掉的调用没有发生，不该记账", 0, FileOpLog.count())
    }

    private companion object {
        const val SESSION_ID = 88L
    }
}
