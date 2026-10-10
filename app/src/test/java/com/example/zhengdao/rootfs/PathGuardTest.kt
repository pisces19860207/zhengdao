package com.example.zhengdao.rootfs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [PathGuard] 单测（加固-1 / 加固-3，2026-10-10 / E-085）。
 *
 * 为什么值得钉：这两条判据**只在畸形包上才生效**，平时一次都不会被走到 —— 手工回归永远
 * 碰不到它们，只有单测能保证它们不悄悄退回去。特别是 [PathGuard.isInside] 的"同前缀兄弟
 * 目录"一例：它正是审计报告抓到的那个字符串前缀 bug 的形状（`rootfs-evil` 被判成在
 * `rootfs` 内），修完必须有一条用例盯着。
 */
class PathGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 加固-3：成员名越界 ────────────────────────────────────────────────

    @Test
    fun `根内的文件与子目录都算在根内`() {
        val root = File(tmp.root, "rootfs")
        assertTrue(PathGuard.isInside(root, File(root, "etc/os-release")))
        assertTrue(PathGuard.isInside(root, File(root, "usr/lib/aarch64-linux-gnu/libc.so.6")))
        assertTrue("根本身就在根内", PathGuard.isInside(root, root))
    }

    @Test
    fun `同前缀的兄弟目录不算在根内`() {
        // 字符串前缀口径会在这里判错："/…/rootfs-evil".startsWith("/…/rootfs") == true
        val root = File(tmp.root, "rootfs")
        assertFalse(PathGuard.isInside(root, File(tmp.root, "rootfs-evil/payload")))
        assertFalse(PathGuard.isInside(root, File(tmp.root, "rootfs.tmp2/x")))
    }

    @Test
    fun `用点回退出根不算在根内`() {
        val root = File(tmp.root, "rootfs")
        assertFalse(PathGuard.isInside(root, File(root, "../outside")))
        assertFalse(PathGuard.isInside(root, File(root, "etc/../../outside")))
    }

    // ── 加固-1：软链 linkname 越界 ────────────────────────────────────────

    @Test
    fun `相对链接所在目录的软链合法`() {
        val root = File(tmp.root, "rootfs")
        val link = File(root, "usr/lib/aarch64-linux-gnu/libfoo.so")
        // Debian 基础镜像里绝大多数软链就是这个形状
        assertTrue(PathGuard.linkStaysInside(root, link, "libfoo.so.1.2.3"))
        // 用 .. 走回根内其它目录：合法（`/usr/lib/… → ../../share/…` 这类）
        assertTrue(PathGuard.linkStaysInside(root, link, "../../../lib/libfoo.so.1"))
    }

    @Test
    fun `绝对链接按 guest 根解释`() {
        val root = File(tmp.root, "rootfs")
        val link = File(root, "lib64/ld-linux-aarch64.so.1")
        // Debian 里真实存在这种绝对链接：在 guest 内它指向 /lib/…，映射到解压根之内
        assertTrue(PathGuard.linkStaysInside(root, link, "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"))
    }

    @Test
    fun `相对或绝对形式的越界软链都拒`() {
        val root = File(tmp.root, "rootfs")
        val link = File(root, "usr/lib/aarch64-linux-gnu/libfoo.so")
        // 相对形式一路 .. 出去（链接所在目录之下 4 层 ⇒ 5 个 .. 就出根）
        assertFalse(PathGuard.linkStaysInside(root, link, "../../../../../../etc/passwd"))
        // 绝对形式带 .. 出去：按根解释后仍会退到根外
        assertFalse(PathGuard.linkStaysInside(root, link, "/../../etc/passwd"))
        // 空 linkname：没有合法语义
        assertFalse(PathGuard.linkStaysInside(root, link, ""))
    }
}
