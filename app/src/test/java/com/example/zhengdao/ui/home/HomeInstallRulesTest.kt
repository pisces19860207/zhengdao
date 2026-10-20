// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七十三批 · ④ 的 JVM 直测：卸载命令 → 宿主侧待统计目录（纯字符串解析，不碰磁盘）。
 *
 * 期望值全部按**改前 `programSizeMb()` 的口径**手推（正则、过滤、保序去重、两类映射、
 * 未知前缀丢弃），`du` 那半留在 HomeScreen，不在本文件射程内。
 */
class HomeInstallRulesTest {

    @Test
    fun `解析_root 子目录映射到 home`() {
        assertEquals(
            listOf("home/.hermes/bin"),
            uninstallProgramPaths("npm rm -g hermes && rm -rf /root/.hermes/bin"),
        )
    }

    @Test
    fun `解析_root 本身映射留双斜杠_由 File 归一`() {
        // 改前就是 "home/" + p.removePrefix("/root/")：p="/root" 时 removePrefix 不命中，
        // 拼出 "home//root"，再交给 File(filesDir, …) 归一成 home/root。
        // 也就是说 `/root` 这条统计的是 home/root 子目录，**不是整个 home**——照搬，不修正。
        assertEquals(listOf("home//root"), uninstallProgramPaths("rm -rf /root"))
    }

    @Test
    fun `解析_usr 子目录映射到 rootfs`() {
        assertEquals(
            listOf("rootfs/usr/lib/node_modules/@anthropic-ai/claude-code"),
            uninstallProgramPaths("rm -rf /usr/lib/node_modules/@anthropic-ai/claude-code"),
        )
    }

    @Test
    fun `解析_usr 本身映射为 rootfs usr`() {
        assertEquals(listOf("rootfs/usr"), uninstallProgramPaths("rm -rf /usr"))
    }

    @Test
    fun `解析_同一命令多路径按出现顺序返回`() {
        assertEquals(
            listOf("home/.codex", "rootfs/usr/bin", "home/.cache"),
            uninstallProgramPaths("rm -rf /root/.codex /usr/bin /root/.cache"),
        )
    }

    @Test
    fun `解析_重复路径保序去重`() {
        // distinct() 是保序去重：/root/.a 先出现就留第一次的位置
        assertEquals(
            listOf("home/.a", "rootfs/usr/x"),
            uninstallProgramPaths("rm -rf /root/.a /usr/x /root/.a"),
        )
    }

    @Test
    fun `解析_多个 rm rf 段都抓`() {
        assertEquals(
            listOf("home/.a", "rootfs/usr/b"),
            uninstallProgramPaths("rm -rf /root/.a && npm uninstall x && rm -rf /usr/b"),
        )
    }

    @Test
    fun `解析_多余空白仍能切分`() {
        assertEquals(
            listOf("home/.a", "home/.b"),
            uninstallProgramPaths("rm   -rf   /root/.a    /root/.b"),
        )
    }

    @Test
    fun `解析_含通配符的路径丢弃`() {
        // 带 * 没法 du（改前就跳过），同段里的正常路径不受影响
        assertEquals(
            listOf("home/.a"),
            uninstallProgramPaths("rm -rf /root/.a /root/.cache/*"),
        )
    }

    @Test
    fun `解析_相对路径与波浪号路径丢弃`() {
        assertEquals(emptyList<String>(), uninstallProgramPaths("rm -rf ~/.hermes"))
        assertEquals(emptyList<String>(), uninstallProgramPaths("rm -rf var/lib/x"))
    }

    @Test
    fun `解析_未知前缀不猜测`() {
        // 只认 /root 与 /usr 两类；/data、/opt 一律丢掉（映射不到宿主就别编数字）
        assertEquals(emptyList<String>(), uninstallProgramPaths("rm -rf /data/local/tmp/x /opt/ycm"))
    }

    @Test
    fun `解析_近似前缀不误伤`() {
        // /rootfs、/usrlocal 都不该命中（改前用 p == "/root" || p.startsWith("/root/")）
        assertEquals(emptyList<String>(), uninstallProgramPaths("rm -rf /rootfs/x /usrlocal/y"))
    }

    @Test
    fun `解析_没有 rm rf 段返回空表`() {
        assertEquals(emptyList<String>(), uninstallProgramPaths("npm uninstall -g hermes"))
        assertEquals(emptyList<String>(), uninstallProgramPaths(""))
    }

    @Test
    fun `解析_换行截断不误吞后续命令`() {
        // [^&\n]+ 到换行为止
        assertEquals(
            listOf("home/.a"),
            uninstallProgramPaths("rm -rf /root/.a\nrm -rf /data/x"),
        )
    }

    @Test
    fun `解析_真实 manifest 形态给非空表`() {
        val cmd = "yes | npm uninstall -g @anthropic-ai/claude-code 2>/dev/null; " +
            "rm -rf /usr/lib/node_modules/@anthropic-ai/claude-code /usr/bin/claude"
        val got = uninstallProgramPaths(cmd)
        assertTrue("真实卸载命令应解析出 2 个宿主目录，实际 $got", got.size == 2)
        assertEquals(listOf("rootfs/usr/lib/node_modules/@anthropic-ai/claude-code", "rootfs/usr/bin/claude"), got)
    }
}
