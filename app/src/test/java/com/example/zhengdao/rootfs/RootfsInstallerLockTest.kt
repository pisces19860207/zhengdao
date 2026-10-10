// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安装锁与替换原子性（#11 / P0-4，见 E-078）。
 *
 * 为什么值得单独测：全 App 唯一能把"已经装好的环境"毁掉的一步就是这里——
 * - 旧 `swapIntoPlace` 先 `rootfs.deleteRecursively()` 再改名：删除之后、改名之前被打断
 *   （进程被杀 / 存储掉线 / 用户强退），用户的环境就没了；
 * - 各入口此前互不设防：设置页四条长流程 + 终端页三处安装 + 增量更新能并发进这一步。
 */
class RootfsInstallerLockTest {

    private fun tmpHome(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "$tag-${System.nanoTime()}").apply { mkdirs() }

    private fun tree(dir: File, name: String, text: String) {
        File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun read(file: File): String? = if (file.isFile) file.readText() else null

    // ── 进程级安装锁 ─────────────────────────────────────────────────────

    @Test
    fun `持锁期间重入被拒并给出可读文案`() {
        var inner: Throwable? = null
        RootfsInstaller.withInstallLock("外层任务") {
            assertTrue("持锁期间 isInstalling 必须为真", RootfsInstaller.isInstalling())
            assertTrue("持锁期间 Compose 状态必须为真", RootfsInstaller.installing.value)
            inner = runCatching { RootfsInstaller.withInstallLock("内层任务") { } }.exceptionOrNull()
        }
        assertNotNull("重入必须被拒绝", inner)
        assertTrue("必须是 InstallFailed，实际：$inner", inner is RootfsInstaller.InstallFailed)
        val msg = inner!!.message ?: ""
        assertTrue("文案要说明原因：$msg", msg.contains("已有安装/更新任务在跑"))
        assertTrue("文案要点出被拒的那件事：$msg", msg.contains("内层任务"))
        assertFalse("退出后必须释放锁", RootfsInstaller.isInstalling())
        assertFalse("退出后 Compose 状态也要复位", RootfsInstaller.installing.value)
    }

    @Test
    fun `块抛异常也必须释放锁`() {
        val err = runCatching {
            RootfsInstaller.withInstallLock("会炸的任务") { throw IllegalStateException("炸了") }
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertFalse("异常路径不能把锁留在手里", RootfsInstaller.isInstalling())
        assertFalse(RootfsInstaller.installing.value)
        // 还能正常再进一次（真机上"上一次安装崩了，之后永远提示已有任务在跑"就是灾难）
        var ran = false
        RootfsInstaller.withInstallLock("第二次") { ran = true }
        assertTrue(ran)
        assertFalse(RootfsInstaller.isInstalling())
    }

    @Test
    fun `withInstallLock 原样返回块的结果`() {
        assertEquals(7, RootfsInstaller.withInstallLock("算个数") { 7 })
        assertEquals("好", RootfsInstaller.withInstallLock("拿个串") { "好" })
    }

    // ── 替换（swapIntoPlace = 自带锁的门面）───────────────────────────────

    @Test
    fun `替换成功后新内容在位且不留 tmp 与旧树备份`() {
        val home = tmpHome("zd-swap-ok")
        val rootfs = File(home, "rootfs")
        val tmp = File(home, RootfsInstaller.TMP_NAME)
        tree(rootfs, "etc/os-release", "old")
        tree(tmp, "etc/os-release", "new")

        RootfsInstaller.swapIntoPlace(tmp, rootfs)

        assertEquals("new", read(File(rootfs, "etc/os-release")))
        assertFalse("tmp 必须被清掉", tmp.exists())
        assertFalse("旧树备份必须被清掉", File(home, RootfsInstaller.OLD_NAME).exists())
        assertFalse("替换结束必须释放锁", RootfsInstaller.isInstalling())
    }

    @Test
    fun `原本没有环境时也能就位（全新安装）`() {
        val home = tmpHome("zd-swap-fresh")
        val rootfs = File(home, "rootfs")
        val tmp = File(home, RootfsInstaller.TMP_NAME)
        tree(tmp, "etc/os-release", "new")

        RootfsInstaller.swapIntoPlace(tmp, rootfs)

        assertEquals("new", read(File(rootfs, "etc/os-release")))
        assertFalse(File(home, RootfsInstaller.OLD_NAME).exists())
    }

    @Test
    fun `临时树不存在时抛错且原环境一个字节都不动`() {
        val home = tmpHome("zd-swap-missing")
        val rootfs = File(home, "rootfs")
        tree(rootfs, "etc/os-release", "old")

        val err = runCatching {
            RootfsInstaller.swapIntoPlace(File(home, "根本没有这个目录"), rootfs)
        }.exceptionOrNull()

        assertTrue("必须抛 InstallFailed，实际：$err", err is RootfsInstaller.InstallFailed)
        assertEquals("原环境必须原样保留", "old", read(File(rootfs, "etc/os-release")))
        assertFalse("不该留下备份", File(home, RootfsInstaller.OLD_NAME).exists())
    }

    @Test
    fun `上次替换中断留下的备份会先复原再替换`() {
        val home = tmpHome("zd-swap-rescue")
        // 残局：让位做完了、就位没做成 ⇒ rootfs 缺失、备份在、tmp 也在（进程就是在这中间被杀）
        val backup = File(home, RootfsInstaller.OLD_NAME)
        tree(backup, "etc/os-release", "old")
        val tmp = File(home, RootfsInstaller.TMP_NAME)
        tree(tmp, "etc/os-release", "new")
        val rootfs = File(home, "rootfs")

        RootfsInstaller.swapIntoPlace(tmp, rootfs)

        assertEquals("new", read(File(rootfs, "etc/os-release")))
        assertFalse("备份用完必须清掉", backup.exists())
    }

    @Test
    fun `替换过程中旧内容一直有备份可回滚（改名而非先删）`() {
        val home = tmpHome("zd-swap-order")
        val rootfs = File(home, "rootfs")
        val tmp = File(home, RootfsInstaller.TMP_NAME.toString() + ".missing")
        tree(rootfs, "etc/os-release", "old")

        // 用"就位一定失败"的临时树（不存在）撞一次：原环境必须还在原处
        runCatching { RootfsInstaller.swapIntoPlace(tmp, rootfs) }
        assertEquals("old", read(File(rootfs, "etc/os-release")))
        assertFalse(File(home, RootfsInstaller.OLD_NAME).exists())
    }
}
