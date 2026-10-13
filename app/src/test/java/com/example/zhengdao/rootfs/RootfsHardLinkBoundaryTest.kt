// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * §B 顺序 20：**硬链接条目的源路径也要过树内校验**。
 *
 * 成员目的地一直有校验（`RootfsInstaller.kt:353` → `PathGuard.isInside`），但 `LF_LINK` 条目的
 * **源**（`linkName`）没有；而落盘走的是 `copyTo`（不是真硬链）⇒ 一个 `linkName` 逃出解压根的
 * 条目，等于「把树外文件的内容复制进环境目录」。Rust 侧同一处（`rust/core/src/extract.rs`）已对拍修掉。
 *
 * 这里用真 tar 走一遍 `extractArchiveJava`，钉住三种输入：
 * ① 相对路径逃出解压根 ⇒ 跳过、不落盘；② 绝对路径带 `..` 逃出 ⇒ 跳过、不落盘；
 * ③ 树内硬链接 ⇒ 照旧复制内容（不能把正常功能一起挡掉）。
 */
class RootfsHardLinkBoundaryTest {

    /** 造一只纯 tar（不加压缩）——`openTar` 按扩展名认格式。 */
    private fun tarWith(root: File, build: (TarArchiveOutputStream) -> Unit): File {
        val archive = File(root, "pkg.tar")
        TarArchiveOutputStream(archive.outputStream()).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            build(tar)
        }
        return archive
    }

    private fun TarArchiveOutputStream.file(name: String, bytes: ByteArray) {
        val e = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
        putArchiveEntry(e)
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.hardLink(name: String, linkName: String) {
        val e = TarArchiveEntry(name, TarConstants.LF_LINK).apply { this.linkName = linkName }
        putArchiveEntry(e)
        closeArchiveEntry()
    }

    @Test
    fun `硬链接源逃出解压根时 不把树外文件内容复制进来`() {
        val root = Files.createTempDirectory("zd-hl").toFile()
        val dest = File(root, "dest").apply { mkdirs() }
        // 放在解压根**之外**的"机密"（dest/usr/share 出发要上跳 3 层才到 root）
        val secret = File(root, "secret.txt").apply { writeText("机密内容") }

        val archive = tarWith(root) { tar ->
            tar.file("usr/share/ok.txt", "ok".toByteArray())
            // ① 相对路径逃出：dest/usr/share → ../../../secret.txt = <root>/secret.txt
            tar.hardLink("usr/share/leak-rel.txt", "../../../secret.txt")
            // ② 绝对路径带 `..` 逃出（纯 `/etc/passwd` 反而合法：按 guest 根解释）
            tar.hardLink("usr/share/leak-abs.txt", "/../../secret.txt")
        }

        RootfsInstaller.extractArchiveJava(archive, dest, emptySet()) { }

        assertFalse(
            "树外机密被复制进环境目录了：${File(dest, "usr/share/leak-rel.txt")}",
            File(dest, "usr/share/leak-rel.txt").exists(),
        )
        assertFalse(
            "绝对路径逃逸没挡住：${File(dest, "usr/share/leak-abs.txt")}",
            File(dest, "usr/share/leak-abs.txt").exists(),
        )
        assertTrue("树外的机密文件本身不该被改动", secret.readText() == "机密内容")
        assertEquals("正常成员必须照常解出", "ok", File(dest, "usr/share/ok.txt").readText())
    }

    @Test
    fun `树内硬链接照旧复制内容`() {
        val root = Files.createTempDirectory("zd-hl2").toFile()
        val dest = File(root, "dest").apply { mkdirs() }

        val archive = tarWith(root) { tar ->
            tar.file("usr/share/orig.txt", "same-inode".toByteArray())
            tar.hardLink("usr/share/copy.txt", "usr/share/orig.txt")
        }

        RootfsInstaller.extractArchiveJava(archive, dest, emptySet()) { }

        assertEquals("same-inode", File(dest, "usr/share/copy.txt").readText())
    }

    @Test
    fun `源尚未解出的前向硬链接 二阶段也不越界`() {
        val root = Files.createTempDirectory("zd-hl3").toFile()
        val dest = File(root, "dest").apply { mkdirs() }
        File(root, "secret.txt").writeText("机密内容")

        val archive = tarWith(root) { tar ->
            // 先出现链接、源在后（走二阶段）；且源越界 ⇒ 二阶段同样必须拒
            tar.hardLink("usr/share/forward.txt", "../../../secret.txt")
            tar.file("usr/share/ok.txt", "ok".toByteArray())
        }

        RootfsInstaller.extractArchiveJava(archive, dest, emptySet()) { }

        assertFalse(
            "二阶段把树外内容复制进来了：${File(dest, "usr/share/forward.txt")}",
            File(dest, "usr/share/forward.txt").exists(),
        )
    }
}
