// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LibraryPaths 纯函数单测（刀 2 A 段；判据 §5.3／§5.4）。
 *
 * 钉三件事：**只扫投递**（投递外的目录不进结果）、**越界即拒**
 * （`../`／绝对路径／盘符）、**写盘不产生半个文件**。
 * 全部用临时目录，不碰真机存储。
 */
class LibraryPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, text: String = "x"): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    // ── 扫描 ──────────────────────────────────────────────────────────────

    @Test
    fun `扫描_只收投递目录内的文件并按relPath排序`() {
        val deliver = tmp.newFolder("投递")
        file(deliver, "b.txt")
        file(File(deliver, "sub"), "a.md")
        file(deliver, "c.json")

        val r = LibraryPaths.scanDeliver(deliver)

        assertEquals(listOf("b.txt", "c.json", "sub/a.md"), r.files.map { it.relPath })
        assertFalse(r.truncated)
    }

    @Test
    fun `扫描_目录不存在返回空且不炸`() {
        val r = LibraryPaths.scanTree(File(tmp.newFolder(), "nonexistent"))
        assertTrue(r.files.isEmpty())
        assertFalse(r.truncated)
    }

    @Test
    fun `扫描_跳过系统垃圾名`() {
        val deliver = tmp.newFolder("投递")
        file(deliver, "a.txt")
        file(deliver, ".DS_Store")
        file(deliver, "Thumbs.db")
        file(deliver, "._a.txt")

        val r = LibraryPaths.scanDeliver(deliver)

        assertEquals(listOf("a.txt"), r.files.map { it.relPath })
    }

    @Test
    fun `扫描_跳过符号链接`() {
        val deliver = tmp.newFolder("投递")
        val real = file(deliver, "real.txt")
        val link = File(deliver, "link.txt")
        runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), real.toPath())
        }
        // 沙盒可能造不出真链（已知环境限制）⇒ 造不出时本条跳过，不误判
        if (!link.exists()) return

        val r = LibraryPaths.scanDeliver(deliver)

        assertEquals(listOf("real.txt"), r.files.map { it.relPath })
    }

    // ── 分类 ──────────────────────────────────────────────────────────────

    @Test
    fun `分类_文本件与不认领格式`() {
        assertEquals(LibraryPaths.FileKind.TEXT, LibraryPaths.classify("a.txt").first)
        assertEquals(LibraryPaths.FileKind.TEXT, LibraryPaths.classify("a.kt").first)
        assertEquals(LibraryPaths.FileKind.UNCLAIMED, LibraryPaths.classify("a.pdf").first)
        assertTrue(LibraryPaths.classify("a.pdf").second.contains("外部转换"))
        assertEquals(LibraryPaths.FileKind.UNCLAIMED, LibraryPaths.classify("a.png").first)
        assertEquals(LibraryPaths.FileKind.UNCLAIMED, LibraryPaths.classify("a.zip").first)
        assertEquals(LibraryPaths.FileKind.UNCLAIMED, LibraryPaths.classify("README").first)
        assertTrue(LibraryPaths.classify("README").second.contains("没有扩展名"))
    }

    @Test
    fun `分类_大小写不敏感`() {
        assertEquals(LibraryPaths.FileKind.TEXT, LibraryPaths.classify("a.TXT").first)
        assertEquals(LibraryPaths.FileKind.UNCLAIMED, LibraryPaths.classify("a.PDF").first)
    }

    // ── 越界守卫（判据 §5.4） ─────────────────────────────────────────────

    @Test
    fun `目的地_合法相对路径通过`() {
        val raw = File("/data/user/0/com.example.zhengdao/files/baopu/library/原始")
        assertNotNull(LibraryPaths.destinationUnder(raw, "a.txt"))
        assertNotNull(LibraryPaths.destinationUnder(raw, "sub/中文 名.md"))
    }

    @Test
    fun `目的地_四类越界一律拒`() {
        val raw = File("/data/user/0/com.example.zhengdao/files/baopu/library/原始")
        assertNull("../escape.txt".let { LibraryPaths.destinationUnder(raw, it) })
        assertNull(LibraryPaths.destinationUnder(raw, "/etc/passwd"))
        assertNull(LibraryPaths.destinationUnder(raw, "C:\\Windows\\x.txt"))
        assertNull(LibraryPaths.destinationUnder(raw, "sub/../../x.txt"))
        assertNull(LibraryPaths.destinationUnder(raw, ""))
        assertNull(LibraryPaths.destinationUnder(raw, "  "))
        assertNull(LibraryPaths.destinationUnder(raw, "sub//x.txt")) // 空组件
    }

    // ── 原子写 ────────────────────────────────────────────────────────────

    @Test
    fun `原子写_落盘内容一致且不留tmp`() {
        val target = File(tmp.newFolder(), "out.md")
        assertTrue(LibraryPaths.atomicWrite(target, "第一行\n第二行"))
        assertEquals("第一行\n第二行", target.readText())
        val leftovers = target.parentFile.listFiles()?.map { it.name } ?: emptyList()
        assertTrue("不该留下 .tmp 残件：$leftovers", leftovers.none { it.endsWith(".tmp") })
    }

    @Test
    fun `原子写_目标目录不存在也能建`() {
        val target = File(tmp.newFolder(), "deep/sub/out.md")
        assertTrue(LibraryPaths.atomicWrite(target, "x"))
        assertTrue(target.isFile)
    }

    // ── 路径常量（口径不许漂） ────────────────────────────────────────────

    @Test
    fun `路径口径_共享区两目录与私有原件目录`() {
        val ws = File("/workspace")
        val files = File("/data/user/0/com.example.zhengdao/files")
        assertEquals("/workspace/资料库/投递", LibraryPaths.deliverDirUnder(ws).path.replace('\\', '/'))
        assertEquals("/workspace/资料库/整理", LibraryPaths.doneDirUnder(ws).path.replace('\\', '/'))
        assertEquals(
            "/data/user/0/com.example.zhengdao/files/baopu/library/原始",
            LibraryPaths.rawDirUnder(files).path.replace('\\', '/'),
        )
    }
}
