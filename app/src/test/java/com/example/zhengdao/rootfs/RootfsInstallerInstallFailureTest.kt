// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
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
 * R-07（审计第⑮刀，2026-10-14）：[RootfsInstaller.installInto] 的失败路径必须干净。
 *
 * 背景：原 [RootfsInstaller.install] 是「直筒式」——清 tmp → `mkdirs()`（不看返回值）→ 解包 →
 * 写标记 → 替换；任何一步失败都把**半截 tmp 树**留在盘上（既白占 1–2GB，又会被下次启动的
 * `cleanupPartial` 多清一遍），而且没有空间预检。
 *
 * 本测试打的是与 Context 解耦的内核 [RootfsInstaller.installInto]（生产路径
 * [RootfsInstaller.install] 只是把 [RootfsInstaller.ensureFreeSpace] 的闭包塞进来），钉四件事：
 * ① 空间不足在动盘**之前**就拒；② `mkdirs` 失败当失败处理；③ 解包失败清 tmp；
 * ④ 成功路径内容就位、不留 tmp。
 */
class RootfsInstallerInstallFailureTest {

    private fun home(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "$tag-${System.nanoTime()}").apply { mkdirs() }

    private fun tree(dir: File, name: String, text: String) {
        File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun read(f: File): String? = if (f.isFile) f.readText() else null

    /** 造一只纯 tar（不加压缩）——[RootfsInstaller.installInto] 里 `openTar` 按魔数认格式。 */
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

    /** 注入「空间不足」的预检闭包（生产路径由 [RootfsInstaller.ensureFreeSpace] 抛）。 */
    private val outOfSpace: () -> Unit = {
        throw RootfsInstaller.InstallFailed("存储空间不足：本次安装约需 2600MB，当前可用 100MB")
    }

    private fun install(
        home: File,
        archive: File,
        ensureSpace: () -> Unit = {},
    ): Throwable? = runCatching {
        RootfsInstaller.installInto(
            rootfsDir = File(home, "rootfs"),
            tmpDir = File(home, RootfsInstaller.TMP_NAME),
            archive = archive,
            env = null,
            archiveSha256 = null,
            ensureSpace = ensureSpace,
        ) { }
    }.exceptionOrNull()

    /** 失败后盘面必须干净：抛了异常、没有半截 tmp、没有旧树备份、原环境没被动过。 */
    private fun assertNoResidue(home: File, err: Throwable?, msg: String) {
        assertNotNull("$msg：必须抛异常（不能静默继续）", err)
        assertFalse("$msg：不能留下半截 tmp 树", File(home, RootfsInstaller.TMP_NAME).exists())
        assertFalse("$msg：不能留下旧树备份", File(home, RootfsInstaller.OLD_NAME).exists())
        assertEquals("$msg：原环境一个字节都不许动", "old", read(File(home, "rootfs/etc/os-release")))
    }

    @Test
    fun `空间不足时 在动盘之前就拒且不留残渣`() {
        val h = home("zd-r07-space")
        tree(File(h, "rootfs"), "etc/os-release", "old")
        val err = install(h, File(h, "包不存在.tar"), outOfSpace)
        assertTrue("必须是 InstallFailed，实际：$err", err is RootfsInstaller.InstallFailed)
        assertTrue("文案要是人话（点出空间不足）：${err!!.message}", err.message!!.contains("存储空间不足"))
        assertNoResidue(h, err, "空间不足")
    }

    @Test
    fun `临时目录建不出来时 当失败处理且不留残渣`() {
        val h = home("zd-r07-mkdir")
        tree(File(h, "rootfs"), "etc/os-release", "old")
        // tmp 的父路径是普通文件 ⇒ mkdirs 必然失败（跨平台的「不可写」注入，不用改权限位）
        File(h, "blocker").writeText("我不是目录")
        val err = runCatching {
            RootfsInstaller.installInto(
                rootfsDir = File(h, "rootfs"),
                tmpDir = File(File(h, "blocker"), RootfsInstaller.TMP_NAME),
                archive = File(h, "包不存在.tar"),
                env = null,
                archiveSha256 = null,
            ) { }
        }.exceptionOrNull()
        assertTrue("必须是 InstallFailed，实际：$err", err is RootfsInstaller.InstallFailed)
        assertTrue("文案要指向可写/空间：${err!!.message}", err.message!!.contains("无法创建临时解包目录"))
        assertEquals("原环境一个字节都不许动", "old", read(File(h, "rootfs/etc/os-release")))
        assertFalse("不能留下旧树备份", File(h, RootfsInstaller.OLD_NAME).exists())
    }

    @Test
    fun `解包失败时 清掉半截 tmp 且原环境不动`() {
        val h = home("zd-r07-extract")
        tree(File(h, "rootfs"), "etc/os-release", "old")
        val err = install(h, File(h, "根本没有这个包.tar"))
        assertNotNull("打不开的包必须抛异常", err)
        assertNoResidue(h, err, "解包失败")
    }

    @Test
    fun `成功路径 内容就位且不留 tmp`() {
        val h = home("zd-r07-ok")
        tree(File(h, "rootfs"), "etc/os-release", "old")
        val archive = tarWith(h, "pkg.tar", "etc/os-release", "new")
        val err = install(h, archive)
        assertEquals("不该有异常：$err", null, err)
        assertEquals("新内容必须就位", "new", read(File(h, "rootfs/etc/os-release")))
        assertFalse("成功也必须清掉 tmp", File(h, RootfsInstaller.TMP_NAME).exists())
        assertFalse("不能留旧树备份", File(h, RootfsInstaller.OLD_NAME).exists())
    }
}
