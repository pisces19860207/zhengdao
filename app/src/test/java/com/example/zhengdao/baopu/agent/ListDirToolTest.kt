// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.2（ListDirToolTest：软链不跟随、深度/条数上限触顶明说）
// + 负例演练②（去掉生效逻辑 ⇒ 范围/越界用例必红——本文件与 ReadFileToolTest 是红的那批）。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ListDirToolTest {

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-listdir-$name").toFile()

    @Test
    fun `列目录_目录在前_名称序_文件带大小`() {
        val root = tmp("order")
        File(root, "b.txt").writeText("bb")
        File(root, "a.txt").writeText("a")
        File(root, "sub").mkdirs()
        val out = ListDirTool.list(root, depth = 1, limit = 100)
        assertTrue(out is ToolOutcome.Ok)
        val text = (out as ToolOutcome.Ok).text
        assertTrue("目录排在前", text.indexOf("sub/") < text.indexOf("a.txt"))
        assertTrue("名称序", text.indexOf("a.txt") < text.indexOf("b.txt"))
        assertTrue("文件带字节数", text.contains("b.txt（2 B）"))
    }

    @Test
    fun `空目录_明说空_不报错`() {
        val root = tmp("empty")
        val out = ListDirTool.list(root, 1, 100)
        assertTrue((out as ToolOutcome.Ok).text.contains("空目录"))
    }

    @Test
    fun `目标不是目录_Failed`() {
        val root = tmp("notdir")
        val f = File(root, "a.txt").apply { writeText("x") }
        assertTrue(ListDirTool.list(f, 1, 100) is ToolOutcome.Failed)
    }

    @Test
    fun `软链_不跟随不列出_即使指向目录`() {
        val root = tmp("symlink")
        File(root, "inside").mkdirs()
        File(File(root, "inside"), "secret.txt").writeText("s")
        val outside = tmp("outside-target")
        File(outside, "leak.txt").writeText("l")
        runCatching { Files.createSymbolicLink(File(root, "link-out").toPath(), outside.toPath()) }
        // 沙盒造不出真软链（已知环境限制，与 GuardTest 同款处理）：造不出就跳过本用例
        org.junit.Assume.assumeTrue(
            "沙盒造不出真软链（已知环境限制）",
            Files.isSymbolicLink(File(root, "link-out").toPath()),
        )
        val out = ListDirTool.list(root, depth = 3, limit = 500)
        val text = (out as ToolOutcome.Ok).text
        assertFalse("软链本身不列出", text.contains("link-out"))
        assertFalse("软链目标内容不带进来", text.contains("leak.txt"))
        assertTrue("真目录照列", text.contains("inside/"))
    }

    @Test
    fun `深度1_不递归`() {
        val root = tmp("depth1")
        File(root, "sub/deep").mkdirs()
        File(root, "sub/deep/x.txt").writeText("x")
        val text = (ListDirTool.list(root, depth = 1, limit = 100) as ToolOutcome.Ok).text
        assertTrue(text.contains("sub/"))
        assertFalse("depth=1 不进子目录", text.contains("x.txt"))
    }

    @Test
    fun `深度超上限_按上限钳掉_不静默`() {
        val root = tmp("depth3")
        File(root, "a/b/c/d").mkdirs()
        File(root, "a/b/c/d/deep.txt").writeText("x")
        // depth 传 99（Schema 的 maximum 会拦，但工具自身也钳——双保险）
        val text = (ListDirTool.list(root, depth = 99, limit = 500) as ToolOutcome.Ok).text
        assertFalse("第 4 层不进（MAX_DEPTH=3）", text.contains("deep.txt"))
    }

    @Test
    fun `条数触顶_明说可能还有更多`() {
        val root = tmp("limit")
        (1..10).forEach { File(root, "f$it.txt").writeText("x") }
        val text = (ListDirTool.list(root, depth = 1, limit = 3) as ToolOutcome.Ok).text
        assertTrue("触顶必须明说", text.contains("可能还有更多"))
        assertTrue("告诉模型怎么办", text.contains("limit") || text.contains("read_file"))
    }

    @Test
    fun `条数未触顶_不提触顶`() {
        val root = tmp("nofit")
        (1..3).forEach { File(root, "f$it.txt").writeText("x") }
        val text = (ListDirTool.list(root, depth = 1, limit = 100) as ToolOutcome.Ok).text
        assertFalse(text.contains("可能还有更多"))
    }

    @Test
    fun `limit为0或负_钳到1_不返回空表假装没有`() {
        val root = tmp("clamp")
        (1..3).forEach { File(root, "f$it.txt").writeText("x") }
        val text = (ListDirTool.list(root, depth = 1, limit = 0) as ToolOutcome.Ok).text
        assertTrue("钳到 1 ⇒ 至少给 1 条", text.contains("f1.txt") || text.contains("f2.txt") || text.contains("f3.txt"))
        assertTrue("且明说触顶", text.contains("可能还有更多"))
    }
}
