// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七十三批 · ①②③ 的 JVM 直测（纯判据，不需要 Robolectric / Compose）。
 *
 * 每条用例断言的都是**改前 HomeScreen 那六臂 `when` / 四段 `if-else` 的实际行为**：
 * 用例表是从 `ui/HomeScreen.kt` 原分支条件一条条抄下来的，不是先写函数再凑测试。
 */
class HomeAgentRulesTest {

    // ── ① 主操作按钮 ────────────────────────────────────────────────

    @Test
    fun `主按钮_失败且环境就绪给重试安装_排在已装前面`() {
        // 2026-10-07 真机实测：安装挂在最后一步时可执行文件往往已落位 ⇒ installed 也为真。
        // 失败必须赢，否则卡片状态行写「点安装可重试」、按钮却给「启动」。
        assertEquals(
            HomePrimaryAction.RetryInstall,
            homePrimaryActionOf(
                failedInstall = true, installed = true, installing = false,
                hasInstallCmd = true, envReady = true,
            ),
        )
    }

    @Test
    fun `主按钮_失败且环境没了_已装仍给启动`() {
        // 2026-10-20 写用例时才暴露的改前实况：`installed` 那一臂排在「先装环境」之前，
        // 所以“装坏了 + 环境也没了”给的是「启动」，不是注释里说的「先装环境」。
        // 本刀是等值搬移 ⇒ 判据照搬，不顺手改这个行为（要改是另一单的事）。
        assertEquals(
            HomePrimaryAction.Launch,
            homePrimaryActionOf(
                failedInstall = true, installed = true, installing = false,
                hasInstallCmd = true, envReady = false,
            ),
        )
    }

    @Test
    fun `主按钮_失败且环境没了_未装才落到先装环境`() {
        // 这才是原注释「环境没了…该落到下面的先装环境」描述的那条路径（未装时才走到）。
        assertEquals(
            HomePrimaryAction.InstallEnvFirst,
            homePrimaryActionOf(
                failedInstall = true, installed = false, installing = false,
                hasInstallCmd = true, envReady = false,
            ),
        )
    }

    @Test
    fun `主按钮_失败且无安装命令给暂未提供`() {
        assertEquals(
            HomePrimaryAction.ComingSoon,
            homePrimaryActionOf(
                failedInstall = true, installed = false, installing = false,
                hasInstallCmd = false, envReady = false,
            ),
        )
    }

    @Test
    fun `主按钮_已装且非失败给启动`() {
        assertEquals(
            HomePrimaryAction.Launch,
            homePrimaryActionOf(
                failedInstall = false, installed = true, installing = true,
                hasInstallCmd = true, envReady = true,
            ),
        )
    }

    @Test
    fun `主按钮_安装中排在安装前面`() {
        // 未装 + 安装标记在 ⇒ 给可点的「安装中」（跳终端看进度），不能给「安装」重跑一遍。
        assertEquals(
            HomePrimaryAction.Installing,
            homePrimaryActionOf(
                failedInstall = false, installed = false, installing = true,
                hasInstallCmd = true, envReady = true,
            ),
        )
    }

    @Test
    fun `主按钮_安装中但环境没装仍给安装中`() {
        // 改前口径：installing 那一臂**不看** envReady（标记是 SharedPreferences 时间戳，
        // 与环境在不在无关），照搬。
        assertEquals(
            HomePrimaryAction.Installing,
            homePrimaryActionOf(
                failedInstall = false, installed = false, installing = true,
                hasInstallCmd = true, envReady = false,
            ),
        )
    }

    @Test
    fun `主按钮_未装未安装中有命令有环境给安装`() {
        assertEquals(
            HomePrimaryAction.Install,
            homePrimaryActionOf(
                failedInstall = false, installed = false, installing = false,
                hasInstallCmd = true, envReady = true,
            ),
        )
    }

    @Test
    fun `主按钮_有命令但环境没装给先装环境`() {
        assertEquals(
            HomePrimaryAction.InstallEnvFirst,
            homePrimaryActionOf(
                failedInstall = false, installed = false, installing = false,
                hasInstallCmd = true, envReady = false,
            ),
        )
    }

    @Test
    fun `主按钮_未装未安装中且无安装命令给暂未提供`() {
        listOf(true, false).forEach { env ->
            assertEquals(
                "hasInstallCmd=false 且没在安装时（envReady=$env）",
                HomePrimaryAction.ComingSoon,
                homePrimaryActionOf(
                    failedInstall = false, installed = false, installing = false,
                    hasInstallCmd = false, envReady = env,
                ),
            )
        }
    }

    @Test
    fun `主按钮_未装且无命令但安装标记在仍给安装中`() {
        // 改前第 3 臂 `installing ->` 就排在「暂未提供」之前：安装标记（SharedPreferences 时间戳）
        // 不看有没有安装命令 ⇒ 先给「安装中」。这条是 run3 实测出来的优先序，照搬不修正。
        assertEquals(
            HomePrimaryAction.Installing,
            homePrimaryActionOf(
                failedInstall = false, installed = false, installing = true,
                hasInstallCmd = false, envReady = true,
            ),
        )
    }

    @Test
    fun `主按钮_已装但无安装命令仍给启动`() {
        // manifest 没给安装命令、盘上却有可执行文件：「启动」赢「暂未提供」（改前 arm 顺序）。
        assertEquals(
            HomePrimaryAction.Launch,
            homePrimaryActionOf(
                failedInstall = false, installed = true, installing = false,
                hasInstallCmd = false, envReady = true,
            ),
        )
    }

    @Test
    fun `主按钮_32种输入组合全部有出口不抛异常`() {
        // 六臂 when 带 else ⇒ 不可能没有匹配分支；这条钉住"穷举安全"，负例改坏 when 时会红。
        var seen = 0
        for (m in 0 until 32) {
            val a = homePrimaryActionOf(
                failedInstall = m and 1 != 0,
                installed = m and 2 != 0,
                installing = m and 4 != 0,
                hasInstallCmd = m and 8 != 0,
                envReady = m and 16 != 0,
            )
            assertTrue("$a 不在枚举里", a in HomePrimaryAction.entries)
            seen++
        }
        assertEquals("32 种组合应当全部走完", 32, seen)
    }

    // ── ② 状态行 ────────────────────────────────────────────────────

    @Test
    fun `状态行_失败优先于安装中与版本号`() {
        assertEquals(
            HomeStatusLine.Failure,
            homeStatusLineOf(
                failedInstall = true, installing = true,
                installed = true, hasInstalledVersion = true,
            ),
        )
    }

    @Test
    fun `状态行_安装中优先于版本号`() {
        assertEquals(
            HomeStatusLine.InstallHint,
            homeStatusLineOf(
                failedInstall = false, installing = true,
                installed = true, hasInstalledVersion = true,
            ),
        )
    }

    @Test
    fun `状态行_已装且有版本号才显示版本`() {
        assertEquals(
            HomeStatusLine.Version,
            homeStatusLineOf(
                failedInstall = false, installing = false,
                installed = true, hasInstalledVersion = true,
            ),
        )
    }

    @Test
    fun `状态行_已装但版本号探不到则整段不渲染`() {
        assertEquals(
            HomeStatusLine.None,
            homeStatusLineOf(
                failedInstall = false, installing = false,
                installed = true, hasInstalledVersion = false,
            ),
        )
    }

    @Test
    fun `状态行_未装时版本号再真也不显示`() {
        // 改前是 `agent.installed && agent.installedVersion != null` 两个条件都要，照搬。
        assertEquals(
            HomeStatusLine.None,
            homeStatusLineOf(
                failedInstall = false, installing = false,
                installed = false, hasInstalledVersion = true,
            ),
        )
    }

    @Test
    fun `状态行_全假不渲染`() {
        assertEquals(
            HomeStatusLine.None,
            homeStatusLineOf(
                failedInstall = false, installing = false,
                installed = false, hasInstalledVersion = false,
            ),
        )
    }

    // ── ③ 「更多」菜单 ──────────────────────────────────────────────

    @Test
    fun `菜单_未装就不给更多`() {
        listOf(true, false).forEach { hasUn ->
            listOf(true, false).forEach { hasIn ->
                assertEquals(
                    "installed=false（uninstall=$hasUn install=$hasIn）",
                    false,
                    homeMoreMenuVisible(installed = false, hasUninstallCmd = hasUn, hasInstallCmd = hasIn),
                )
            }
        }
    }

    @Test
    fun `菜单_已装且有卸载或安装命令之一才给`() {
        assertTrue(homeMoreMenuVisible(installed = true, hasUninstallCmd = true, hasInstallCmd = false))
        // E-059：只有安装命令（没卸载命令）的也要显示——菜单里还有「重新安装」。
        assertTrue(homeMoreMenuVisible(installed = true, hasUninstallCmd = false, hasInstallCmd = true))
        assertTrue(homeMoreMenuVisible(installed = true, hasUninstallCmd = true, hasInstallCmd = true))
        assertEquals(false, homeMoreMenuVisible(installed = true, hasUninstallCmd = false, hasInstallCmd = false))
    }

    @Test
    fun `菜单项_重新安装要求有命令且环境就绪`() {
        assertTrue(homeReinstallItemVisible(hasInstallCmd = true, envReady = true))
        assertEquals(false, homeReinstallItemVisible(hasInstallCmd = true, envReady = false))
        assertEquals(false, homeReinstallItemVisible(hasInstallCmd = false, envReady = true))
        assertEquals(false, homeReinstallItemVisible(hasInstallCmd = false, envReady = false))
    }
}
