// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百五十六批管线层测试：工作区外「硬拒 → 问一次」在 GuardPipeline ② 段的行为。
// 判据对应派工单 156 §3：
// - 工具级：工作区外调用 ⇒ 弹一次确认（NeedsConfirm），同意后成功；
// - 负例：开关关＋越界 ⇒ 仍拒（原文）；同一运行第二次越界 ⇒ 不再弹卡；跨运行 ⇒ 重新弹；
// - 红线：原件区只读不受「已授权」影响；批准后回灌「用户已授权工作区外」。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.WorkspaceGrants
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class WorkspaceOpenPipelineTest {

    private lateinit var root: File
    private val lines = mutableListOf<String>()

    /** 直接线程的 runner（与 GuardPipelineTest 同款：单测里不需要真线程池）。 */
    private fun directRunner() = ToolRunner(Executors.newSingleThreadExecutor())

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(ReadFileTool.spec())
        register(WriteFileTool.spec { root })
        register(
            ToolSpec(
                name = "fake_write",
                description = "需确认的假写工具（只验证放行与回灌，不真写盘）",
                inputSchema = jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                    "required" to jsonArrayOf("path"),
                ),
                permission = ToolPermission.CONFIRM,
            ) { ToolOutcome.Ok("写了") },
        )
    }

    /** [allowOutside] 缺省关 ⇒ 存量行为；开关开 ⇒ 156 批的「问一次」路径。 */
    private fun pipeline(allowOutside: () -> Boolean): GuardPipeline =
        GuardPipeline(registry(), { root }, { null }, directRunner(), { lines += it }, allowOutside)

    private fun call(name: String, path: String): ToolCall =
        ToolCall("c1", name, JSONObject().put("path", path))

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ws-open").toFile()
        WorkspaceGrants.beginRun()
    }

    @After
    fun tearDown() {
        WorkspaceGrants.beginRun()
    }

    private fun outsideFile(): File =
        File(root.parentFile, "ws-open-outside").apply { mkdirs() }.let {
            File(it, "note.md").apply { writeText("外面的内容") }
        }

    // ── 负例：开关关 ⇒ 行为与 156 批之前逐字一致 ────────────────────────

    @Test
    fun `负例_开关关加越界_仍原文拒绝`() {
        val r = pipeline({ false }).execute(call("read_file", outsideFile().absolutePath), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Rejected)
        assertTrue((r as GuardPipeline.Result.Rejected).reason.contains("路径越界被拒"))
    }

    // ── 开关开：问一次 → 同意就做 ───────────────────────────────────────

    @Test
    fun `开关开加越界_未授权_先弹确认卡而不是拒绝`() {
        val r = pipeline({ true }).execute(call("read_file", outsideFile().absolutePath), { _, _ -> null })
        assertTrue("ASK_USER ⇒ NeedsConfirm（复用现有确认卡）", r is GuardPipeline.Result.NeedsConfirm)
        assertFalse(WorkspaceGrants.granted(root))
    }

    @Test
    fun `批准后_执行成功且回灌授权说明_账本记上`() {
        val p = pipeline({ true })
        val call = call("fake_write", outsideFile().absolutePath)
        assertTrue(p.execute(call, { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        val r = p.executeApproved(call, { _, _ -> null })
        assertTrue("用户点了批准 ⇒ 执行", r is GuardPipeline.Result.Done)
        val done = r as GuardPipeline.Result.Done
        assertTrue("回灌里有「用户已授权工作区外」", done.wrapped.contains("用户已在确认卡上授权访问工作区外"))
        assertTrue("回灌里有路径", done.wrapped.contains("note.md"))
        assertTrue("账本记上（整根）", WorkspaceGrants.granted(root))
    }

    @Test
    fun `同一运行第二次越界_不再弹卡直接执行`() {
        val p = pipeline({ true })
        val path = outsideFile().absolutePath
        assertTrue(p.execute(call("read_file", path), { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        // 用户点批准（记账）之后，同运行的第二次越界不再弹卡
        p.executeApproved(call("read_file", path), { _, _ -> null })
        val r = p.execute(call("read_file", path), { _, _ -> null })
        assertTrue("一次运行只问一次", r is GuardPipeline.Result.Done)
    }

    @Test
    fun `跨运行_beginRun清账_重新弹卡`() {
        val p = pipeline({ true })
        val path = outsideFile().absolutePath
        assertTrue(p.execute(call("read_file", path), { _, _ -> null }) is GuardPipeline.Result.NeedsConfirm)
        p.executeApproved(call("read_file", path), { _, _ -> null })
        WorkspaceGrants.beginRun()
        val r = p.execute(call("read_file", path), { _, _ -> null })
        assertTrue("新一轮必须重新问", r is GuardPipeline.Result.NeedsConfirm)
    }

    // ── 红线：原件区只读、批准 ≠ 绕护栏 ─────────────────────────────────

    @Test
    fun `红线_原件区只读_已授权也拒`() {
        val raw = File(root, "资料库/原始").apply { mkdirs() }
        val target = File(raw, "book.txt").apply { writeText("原件") }
        val p = GuardPipeline(registry(), { root }, { raw }, directRunner(), { lines += it }, { true })
        WorkspaceGrants.grant(root) // 假设本运行已授权
        // 用**写类**工具判红线（P0-2：原件区拦的是改盘，read_file 读它本来就放行）
        val r = p.execute(call("write_file", target.absolutePath).copy(arguments = JSONObject().put("path", target.absolutePath).put("content", "改原件")), { _, _ -> null })
        assertTrue("原件区红线与工作区边界是两回事，授权翻不了它", r is GuardPipeline.Result.Rejected)
    }

    @Test
    fun `留痕_询问与放行都进RunLog_不许静默`() {
        val p = pipeline({ true })
        val path = outsideFile().absolutePath
        p.execute(call("read_file", path), { _, _ -> null })
        assertTrue("弹卡前有留痕", lines.any { it.contains("需用户确认") })
        p.executeApproved(call("read_file", path), { _, _ -> null })
        assertTrue("放行时有留痕", lines.any { it.contains("已授权工作区外") })
    }
}
