// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第三十七批 A 段——闸必须落在**四个写类工具动手之前**（账 §A #106）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 四个写类工具的**保护闸接线**单测（第三十七批 A 段，判据 L1）。
 *
 * 钉两件事：
 * 1. `write_file` / `edit_file` / `move_path`（源**或**目标）/ `delete_path` 撞上人设文件
 *    ⇒ 一律 `Rejected`，话术与 [PersonaGuard.REJECT_PREFIX] 一字不差；
 * 2. **回归**：普通文件照旧能写/改/搬/删（闸不许把正常活儿一起堵死）。
 */
class PersonaGuardToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private fun setup() {
        root = tmp.newFolder("workspace")
        File(root, "抱朴记忆").mkdirs()
        File(root, "抱朴记忆/人设-本项目.md").writeText("# 项目人设\n【技能】无\n", Charsets.UTF_8)
    }

    private val prefix = PersonaGuard.REJECT_PREFIX

    private fun assertRejected(outcome: ToolOutcome, path: String) {
        assertTrue(
            "应当是 Rejected（终止本轮），实际是 $outcome",
            outcome is ToolOutcome.Rejected,
        )
        assertEquals(prefix + path, (outcome as ToolOutcome.Rejected).reason)
    }

    // ── ① 四个工具各拒一次 ──────────────────────────────────────────────

    @Test
    fun `write_file_撞C层人设被拒且话术一字不差`() {
        setup()
        val p = "抱朴记忆/人设-本项目.md"
        assertRejected(
            WriteFileTool.write(root, p, "改坏了", overwrite = true, stamp = "20261019-010000"),
            p,
        )
        assertEquals("# 项目人设\n【技能】无\n", File(root, p).readText())
    }

    @Test
    fun `edit_file_撞C层人设被拒`() {
        setup()
        val p = "./抱朴记忆/人设-本项目.md" // 顺手验变体：工具层也绕不过
        assertRejected(
            EditFileTool.edit(root, p, EditFileTool.OP_REPLACE, 1, 1, "x", stamp = "20261019-010000"),
            p,
        )
    }

    @Test
    fun `move_path_源命中被拒`() {
        setup()
        val p = "抱朴记忆/人设-本项目.md"
        assertRejected(
            MovePathTool.move(root, p, "别处.md", createDirs = true, stamp = "20261019-010000"),
            p,
        )
        assertTrue(File(root, p).isFile) // 没被搬走
    }

    @Test
    fun `move_path_目标命中被拒`() {
        setup()
        File(root, "a.md").writeText("素材", Charsets.UTF_8)
        val p = "抱朴记忆/人设-本项目.md"
        assertRejected(
            MovePathTool.move(root, "a.md", p, createDirs = true, stamp = "20261019-010000"),
            p,
        )
        assertEquals("# 项目人设\n【技能】无\n", File(root, p).readText())
    }

    @Test
    fun `delete_path_撞C层人设被拒`() {
        setup()
        val p = "抱朴记忆/人设-本项目.md"
        assertRejected(
            DeletePathTool.run(p, permanent = true, root = root, stamp = "20261019-010000"),
            p,
        )
        assertTrue(File(root, p).isFile) // 没被删，也没进回收站
    }

    @Test
    fun `B层人设路径_工具同样够不到`() {
        setup()
        val p = "抱朴记忆/全局/人设.md"
        assertRejected(
            WriteFileTool.write(root, p, "x", overwrite = true, stamp = "20261019-010000"),
            p,
        )
    }

    // ── ② 回归：普通文件照旧能写/改/搬/删 ────────────────────────────────

    @Test
    fun `回归_write_file写普通文件照旧成功`() {
        setup()
        val out = WriteFileTool.write(root, "notes/随手记.md", "今天的事", false, "20261019-010000")
        assertTrue("普通文件要能写：$out", out is ToolOutcome.Ok)
        assertEquals("今天的事", File(root, "notes/随手记.md").readText())
    }

    @Test
    fun `回归_edit_file改普通文件照旧成功`() {
        setup()
        File(root, "a.md").writeText("第一行\n第二行\n", Charsets.UTF_8)
        val out = EditFileTool.edit(root, "a.md", EditFileTool.OP_REPLACE, 2, 2, "改过的第二行", "20261019-010000")
        assertTrue("普通文件要能改：$out", out is ToolOutcome.Ok)
        assertEquals("第一行\n改过的第二行\n", File(root, "a.md").readText())
    }

    @Test
    fun `回归_move_path搬普通文件照旧成功`() {
        setup()
        File(root, "a.md").writeText("素材", Charsets.UTF_8)
        val out = MovePathTool.move(root, "a.md", "notes/a.md", createDirs = true, stamp = "20261019-010000")
        assertTrue("普通文件要能搬：$out", out is ToolOutcome.Ok)
        assertTrue(File(root, "notes/a.md").isFile)
    }

    @Test
    fun `回归_delete_path删普通文件照旧进回收站`() {
        setup()
        File(root, "a.md").writeText("素材", Charsets.UTF_8)
        val out = DeletePathTool.run("a.md", permanent = false, root = root, stamp = "20261019-010000")
        assertTrue("普通文件要能删：$out", out is ToolOutcome.Ok)
        assertTrue("原文件应已不在", !File(root, "a.md").exists())
    }

    @Test
    fun `回归_抱朴记忆下的其它文件不拦`() {
        setup()
        val out = WriteFileTool.write(
            root,
            "抱朴记忆/记忆-2026-10-19.md",
            "今天学到的事",
            false,
            "20261019-010000",
        )
        assertTrue("同目录下的其它记忆文件要能写：$out", out is ToolOutcome.Ok)
    }
}
