// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十五批 · 必做 #3：旧树删除必须**移出完成提示之前的关键路径**。
 *
 * 真机基线（2026-10-20）：换树 0ms，前台 `deleteRecursively()` **3839ms**——「修复完成」
 * 要等 1–2GB 删完。本测试用真实 tar 安装 + 注入的派发器证明：
 * ① [RootfsInstaller.installInto] 返回时旧树**还没被删**（只是改名成 `rootfs.old.<数字>`）；
 * ② 返回之后执行后台任务才真正删除；
 * ③ 汇总行注明「已交后台」；
 * ④ 启动清扫只删带时间戳的垃圾，不碰有恢复语义的 `rootfs.old`。
 */
class RootfsInstallerBackgroundCleanupTest {

    private fun home(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "$tag-${System.nanoTime()}").apply { mkdirs() }

    private fun tree(dir: File, name: String, text: String) {
        File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun read(f: File): String? = if (f.isFile) f.readText() else null

    private fun tarWith(root: File, name: String, entry: String, text: String): File {
        val archive = File(root, name)
        TarArchiveOutputStream(archive.outputStream()).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val bytes = text.toByteArray()
            val e = TarArchiveEntry(entry).apply { size = bytes.size.toLong() }
            tar.putArchiveEntry(e)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
        return archive
    }

    @Test
    fun `安装先返回 旧树删除在后台 汇总注明已交后台`() {
        val h = home("zd-r65-bg")
        tree(File(h, "rootfs"), "etc/os-release", "old")
        val archive = tarWith(h, "pkg.tar", "etc/os-release", "new")

        val captured = ArrayList<Runnable>()
        OldTreeCleaner.dispatch = { captured += it }
        val trace = InstallTrace()
        try {
            RootfsInstaller.installInto(
                rootfsDir = File(h, "rootfs"),
                tmpDir = File(h, RootfsInstaller.TMP_NAME),
                archive = archive,
                env = null,
                archiveSha256 = null,
                trace = trace,
            ) { }

            assertEquals("新内容必须就位", "new", read(File(h, "rootfs/etc/os-release")))
            assertFalse("rootfs.old 必须已让位（否则下次安装会撞名）", File(h, RootfsInstaller.OLD_NAME).exists())
            val trash = h.listFiles { f -> OldTreeCleaner.isTrashName(f.name) }?.singleOrNull()
            assertNotNull("旧树应改名成 rootfs.old.<数字> 的垃圾目录", trash)
            assertTrue("安装返回时旧树应当还没删（删除在后台）", trash!!.isDirectory)
            assertTrue("旧树内容这时候还应在：${trash.name}", File(trash, "etc/os-release").isFile)
            assertTrue(
                "汇总必须注明交给后台：${trace.summary(true)}",
                trace.summary(true).contains("清旧树") && trace.summary(true).contains("已交后台"),
            )
            assertEquals("必须且只能派发一个后台任务", 1, captured.size)

            // 后台真正执行删除
            captured.single().run()
            assertFalse("后台任务跑完，垃圾目录必须消失", trash.exists())
        } finally {
            OldTreeCleaner.dispatch = null
        }
    }

    @Test
    fun `启动清扫只删带数字的垃圾 不碰 rootfs_old`() {
        val files = home("zd-r65-sweep")
        val trash1 = File(files, "rootfs.old.1729400000000").apply { mkdirs() }
        tree(trash1, "etc/os-release", "junk")
        val trash2 = File(files, "rootfs.old.1729400000001").apply { mkdirs() }
        val recoverable = File(files, "rootfs.old").apply { mkdirs() }
        tree(recoverable, "etc/os-release", "only-copy")
        val unrelated = File(files, "rootfs.tmp").apply { mkdirs() }

        val deleted = OldTreeCleaner.sweep(files)
        assertEquals("只应删两个垃圾目录", 2, deleted)
        assertFalse(trash1.exists())
        assertFalse(trash2.exists())
        assertTrue("rootfs.old 有恢复语义，清扫绝不能碰", recoverable.isDirectory)
        assertTrue("不相关目录也不能碰", unrelated.isDirectory)
    }

    @Test
    fun `垃圾目录名判定只认 rootfs_old点纯数字`() {
        assertTrue(OldTreeCleaner.isTrashName("rootfs.old.123"))
        assertFalse(OldTreeCleaner.isTrashName("rootfs.old"))
        assertFalse(OldTreeCleaner.isTrashName("rootfs.old.12a"))
        assertFalse(OldTreeCleaner.isTrashName("rootfs.old."))
        assertFalse(OldTreeCleaner.isTrashName("rootfs.tmp.123"))
    }
}
