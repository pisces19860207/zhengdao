// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 环境标记文件（协议 §5）的单测。
 *
 * 为什么值得单独测：标记里的 `env=` 是**增量更新的唯一基线**——
 * - 旧安装（没有这一行）必须判成 null，调用方才会老实走全量；
 * - 脏内容/空内容不能把设置页打崩（它每天被点"检查更新"）；
 * - `installed-by=zhengdao` 必须一直写（ProotLauncher/AppState/EnvHealth 与老排查脚本都认它）。
 */
class RootfsMarkerTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "zd-marker-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun `render 与 parse 往返一致且保留 installed-by`() {
        val text = RootfsMarker.render("debian-13.7", "a1b2c3d4e5f60718", "2026-10-08T14:00:00+08:00")
        val d = RootfsMarker.parse(text)
        assertEquals("debian-13.7", d.distro)
        assertEquals("a1b2c3d4e5f60718", d.env)
        assertEquals("2026-10-08T14:00:00+08:00", d.installedAt)
        assertTrue("installed-by=zhengdao 必须保留", text.contains("installed-by=zhengdao"))
    }

    @Test
    fun `旧格式（无 env 行）env 为 null`() {
        val d = RootfsMarker.parse("distro=debian-13.7\ninstalled-by=zhengdao\n")
        assertEquals("debian-13.7", d.distro)
        assertNull(d.env)
        assertNull(d.installedAt)
    }

    @Test
    fun `env 行空值视同没有`() {
        val d = RootfsMarker.parse("distro=debian-13.7\nenv=\ninstalled-by=zhengdao\n")
        assertNull(d.env)
    }

    @Test
    fun `空文本与垃圾文本都不崩`() {
        val garbage = listOf(
            "", "\n\n", "   ", "???", "=x", "distro", "env", "no equals sign here",
            "distro=debian-13.7\ndistro=debian-14\n", "\u0000\u0001乱码", "# 注释行\nenv=a1b2c3d4e5f60718\n",
        )
        for (t in garbage) {
            RootfsMarker.parse(t) // 只要不抛异常就算过
        }
        // 重复键取最后一个（容错优先，不做冲突判定）
        assertEquals("debian-14", RootfsMarker.parse("distro=debian-13.7\ndistro=debian-14\n").distro)
        // # 注释行被忽略，后面的 env 仍能取到
        assertEquals("a1b2c3d4e5f60718", RootfsMarker.parse("# 注释行\nenv=a1b2c3d4e5f60718\n").env)
    }

    @Test
    fun `installed-at 传 null 时不写该行`() {
        val text = RootfsMarker.render("debian-13.7", "a1b2c3d4e5f60718", null)
        assertFalse(text.contains("installed-at"))
    }

    @Test
    fun `write 与 installedEnv 往返`() {
        val dir = tempDir()
        try {
            RootfsMarker.write(dir, "debian-13.7", "A1B2C3D4E5F60718", "2026-10-08T14:00:00+08:00")
            assertEquals("a1b2c3d4e5f60718", RootfsMarker.installedEnv(dir)) // 统一小写，便于直接比对
            assertEquals("debian-13.7", RootfsMarker.read(dir)?.distro)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `标记缺失或 env 非法时 installedEnv 返回 null`() {
        val dir = tempDir()
        try {
            // ① 标记文件不存在
            assertNull(RootfsMarker.installedEnv(File(dir, "nope")))
            // ② 旧格式（没有 env 行）
            RootfsMarker.write(dir, "debian-13.7", null)
            assertNull(RootfsMarker.installedEnv(dir))
            // ③ env 不是 16 位十六进制（ci 写坏 / 人工手改）
            RootfsMarker.write(dir, "debian-13.7", "zzzz")
            assertNull(RootfsMarker.installedEnv(dir))
            RootfsMarker.write(dir, "debian-13.7", "1234")
            assertNull(RootfsMarker.installedEnv(dir))
            // ④ 15 位 / 17 位都不认
            RootfsMarker.write(dir, "debian-13.7", "a1b2c3d4e5f6071")
            assertNull(RootfsMarker.installedEnv(dir))
            RootfsMarker.write(dir, "debian-13.7", "a1b2c3d4e5f607180")
            assertNull(RootfsMarker.installedEnv(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
