// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 · A 段第 1 条（257 审计 A-P1-2）复现用例。
//
// **改前的表现**（把 resolveProject 换回 `if (File(p).isAbsolute) File(p) else File(root, p)`
// 即可复现，见本批报告的负例驱动）：
// - 传一个工作区**外**的绝对路径 → 守卫一步都没走 → 只要那目录里有 `book.json`
//   就直接读到（总览里带出书名/大纲/人物/前情/设定，等于给模型开了一个任意目录的读口子）；
// - 传 `../` 爬出去的相对路径同理；同前缀兄弟目录（`<root>-evil`）在字符串前缀口径下也会漏；
// - 而且错误话术只有一句「不是一个小说项目」——越界与不存在混成一个说法，排障时看不出来。
// 改后：一律先过 PathGuard 的组件级判定，越界给「这个路径不在工作区里」。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NovelWorkspaceBoundaryTest {

    private fun tmp(name: String): File = Files.createTempDirectory(name).toFile()

    /** 在 [dir] 下摆一个合法的小说项目（`小说/外书/book.json`）。 */
    private fun makeProject(dir: File, title: String = "外书"): File {
        val project = File(File(dir, NOVEL_ROOT_DIR), title)
        project.mkdirs()
        File(project, NOVEL_META_FILE).writeText(
            JSONObject()
                .put("format", NovelProjectStore.FORMAT)
                .put("title", title)
                .put("outline", "越界探针大纲")
                .toString(),
            Charsets.UTF_8,
        )
        return project
    }

    @Test
    fun `工作区外的绝对路径_必须拒_并说清是不在工作区里`() {
        val workspace = tmp("novel-ws")
        val outside = tmp("novel-outside")
        val project = makeProject(outside)
        assertTrue("前提：外面确实是个合法项目目录", File(project, NOVEL_META_FILE).isFile)

        val r = NovelProjectTool.overview(workspace, project.absolutePath)
        assertTrue("绝对路径越界必须失败（改前这里返回 Ok，把外面的书念了出来）", r is ToolOutcome.Failed)
        val err = (r as ToolOutcome.Failed).error
        assertTrue("话术要说「不在工作区里」，不能混成「不是一个小说项目」：$err", err.contains("不在工作区里"))
        assertFalse("越界拒绝不许把外面的大纲漏出去", err.contains("越界探针大纲"))
    }

    @Test
    fun `相对路径爬出工作区_同样拒`() {
        val parent = tmp("novel-parent")
        val workspace = File(parent, "ws").apply { mkdirs() }
        val project = makeProject(File(parent, "elsewhere"))
        val escaped = "../elsewhere/$NOVEL_ROOT_DIR/外书"

        val r = NovelProjectTool.overview(workspace, escaped)
        assertTrue("`..` 爬出去的相对路径必须失败（改前 File(root, p) 归一后就命中了）", r is ToolOutcome.Failed)
        assertTrue((r as ToolOutcome.Failed).error.contains("不在工作区里"))
        assertTrue("前提：那个目录本身是合法项目", File(project, NOVEL_META_FILE).isFile)
    }

    @Test
    fun `同前缀兄弟目录_组件级判定不许按字符串前缀放行`() {
        val workspace = tmp("novel-root")
        val sibling = File(workspace.absolutePath + "-evil")
        val project = makeProject(sibling)
        val r = NovelProjectTool.overview(workspace, project.absolutePath)
        assertTrue("`<工作区>-evil` 不是工作区的子目录（字符串前缀口径会放它过去）", r is ToolOutcome.Failed)
    }

    @Test
    fun `工作区内的路径_相对与绝对都照常能用`() {
        val workspace = tmp("novel-inside")
        makeProject(workspace)
        // 相对：老口径一直在用的写法，不能被收紧误伤
        val rel = NovelProjectTool.overview(workspace, "$NOVEL_ROOT_DIR/外书")
        assertTrue("工作区内相对路径仍可用：$rel", rel is ToolOutcome.Ok)
        // 绝对：只要真在工作区里，同样合法
        val abs = NovelProjectTool.overview(workspace, File(workspace, NOVEL_ROOT_DIR).absolutePath + "/外书")
        assertTrue("工作区内的绝对路径仍可用：$abs", abs is ToolOutcome.Ok)
    }

    @Test
    fun `续写简报走同一个道口_不另开一条绕过守卫的路`() {
        val workspace = tmp("novel-ws-brief")
        val outside = tmp("novel-outside-brief")
        val project = makeProject(outside)
        val r = NovelContinueTool.brief(workspace, project.absolutePath, 500)
        assertTrue("novel_continue 也必须走 resolveProject 的同一判定", r is ToolOutcome.Failed)
        assertTrue((r as ToolOutcome.Failed).error.contains("不在工作区里"))
    }
}
