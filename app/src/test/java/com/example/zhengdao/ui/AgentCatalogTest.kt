// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android ContextWrapper（android.content）、JUnit 4（TemporaryFolder）。
package com.example.zhengdao.ui

import android.content.Context
import android.content.ContextWrapper
import com.example.zhengdao.testkit.TestAssume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Codex CLI 卡（2026-10-13 上架）钉住三处口径 —— 每一处错了都会在现场出洋相，
 * 而它们都不靠"看代码才发现"：
 *
 * 1. **`launchCmd` 必须是 `codex --no-daemon` 整串**：`TerminalActivity.kt:434` 与
 *    `:605-607` 靠「注入的命令字面量 == `launchCmd`」区分「点启动」和「点安装」，
 *    `AgentInstaller.kt:245` 又取 `substringBefore(' ')` 当"装完自动启动"的入口
 *    ⇒ 这条字符串既是启动命令、也是身份指纹，被谁顺手格式化一下就废。
 * 2. **npm 类不下载脚本**：`AgentInstaller.SCRIPT_URLS` 里没有它 ⇒ 走组合命令
 *    `npm i -g @openai/codex`；`npmPackage` 来自 `AppState.agents` 的内置映射，
 *    决定版本探测能不能读到 `rootfs/usr/lib/node_modules/@openai/codex/package.json`。
 * 3. **卸载只删程序、保留 `~/.codex`**：与 claude-code / hermes 同一口径（配置是用户的）。
 *
 * 为什么用 `ContextWrapper(null)`：`agents()` 只用到 `ctx.filesDir`（安装探测）与
 * `getSharedPreferences`（验签清单缓存；base 为 null 会抛 NPE，被 `AgentManifest.cached`
 * 自己吞掉 ⇒ 正常回退出厂版）。单测 classpath 里 `android.test.mock.MockContext` 实测不可见。
 */
class AgentCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }

    /** 造一个"rootfs 已装好"的假私有目录（探测会先看 `.zhengdao-rootfs-ok` 标记）。 */
    private fun ctxWithRootfs(): Pair<Context, File> {
        val files = tmp.newFolder()
        File(files, "rootfs").mkdirs()
        File(files, "rootfs/.zhengdao-rootfs-ok").writeText("installed-by=zhengdao")
        return FakeFilesContext(files) to files
    }

    private fun codexOf(ctx: Context) = AppState.agents(ctx).first { it.id == "codex" }

    @Test
    fun `出厂清单_三条老卡在前_codex 排在最后`() {
        val (ctx, _) = ctxWithRootfs()
        assertEquals(
            listOf("claude-code", "hermes", "antigravity", "codex"),
            AppState.agents(ctx).map { it.id },
        )
    }

    @Test
    fun `codex 卡的启动_安装_卸载口径`() {
        val (ctx, _) = ctxWithRootfs()
        val c = codexOf(ctx)
        assertEquals("Codex CLI", c.name)
        assertEquals("codex --no-daemon", c.launchCmd)
        assertEquals("npm i -g @openai/codex", c.installCmd)
        val uninstall = c.uninstallCmd
        assertNotNull(uninstall)
        assertTrue(uninstall!!.contains("npm uninstall -g @openai/codex"))
        assertTrue(uninstall.contains("~/.codex 保留"))
        assertFalse(uninstall.contains("rm -rf /root/.codex"))
    }

    @Test
    fun `npmPackage 走内置映射_版本从 rootfs 的 package_json 读`() {
        val (ctx, files) = ctxWithRootfs()
        val pkgJson = File(files, "rootfs/usr/lib/node_modules/@openai/codex/package.json")
        pkgJson.parentFile!!.mkdirs()
        pkgJson.writeText("""{"name":"@openai/codex","version":"0.162.1"}""", Charsets.UTF_8)
        val c = codexOf(ctx)
        assertEquals("@openai/codex", c.npmPackage)
        assertEquals("0.162.1", c.installedVersion)
    }

    @Test
    fun `装没装看落点_rootfs 的 usr bin 算装上`() {
        val (ctx, files) = ctxWithRootfs()
        assertFalse(codexOf(ctx).installed)

        File(files, "rootfs/usr/bin").mkdirs()
        File(files, "rootfs/usr/bin/codex").writeText("#!/bin/sh\n", Charsets.UTF_8)
        assertTrue(codexOf(ctx).installed)
    }

    @Test
    fun `home 下的本地 bin 也算装上_且没有 rootfs 标记时全部未装`() {
        val files = tmp.newFolder()
        val ctx = FakeFilesContext(files)
        assertTrue(AppState.agents(ctx).none { it.installed })

        File(files, "home/.local/bin").mkdirs()
        File(files, "home/.local/bin/codex").writeText("#!/bin/sh\n", Charsets.UTF_8)
        File(files, "rootfs").mkdirs()
        File(files, "rootfs/.zhengdao-rootfs-ok").writeText("installed-by=zhengdao", Charsets.UTF_8)
        assertTrue(codexOf(ctx).installed)
    }

    /**
     * 软链映射回归：guest 里 `~/.local/bin/claude` 是指向 `/root/…` 的**软链**，
     * 宿主侧 `File.isFile()` 读它是悬空的（目标 `/root/…` 在宿主上不存在）⇒ 会误判「没装」。
     * 修法是读到软链后把 `/root/` 前缀映射回 `files/home/` 再判一次。
     *
     * Windows 上建软链要特权；建不出来就 `TestAssume.symlinkUnsupported` 跳过（Linux CI 会真跑）。
     */
    @Test
    fun `软链映射_guest 的 home 软链按 files_home 再判一次`() {
        val files = tmp.newFolder()
        val home = File(files, "home").apply { mkdirs() }
        File(home, "real-claude").writeText("#!/bin/sh\n", Charsets.UTF_8)
        val link = File(home, ".local/bin/claude").apply { parentFile!!.mkdirs() }
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(), java.nio.file.Paths.get("/root/real-claude")
            )
        }.isSuccess
        TestAssume.symlinkUnsupported(made)

        val ctx = FakeFilesContext(files)
        assertTrue(existsInHost(ctx, "home/.local/bin/claude"))
        assertFalse(existsInHost(ctx, "home/.local/bin/not-there"))
    }

    /**
     * §B 顺序 26（2026-10-14）：**两跳**软链 —— 真机实测 Codex CLI 正是这条链。
     *
     * guest 视角：
     *   `~/.local/bin/codex` → `~/.codex/packages/standalone/current/bin/codex`
     *                        → `releases/&lt;ver&gt;/bin/codex`
     *
     * 旧实现只把第一跳的 `/root/` 前缀映射成 `files/home` ⇒ 第二跳（`current`）仍然悬空
     * ⇒ 明明装好了却判「没装」。本刀改成**按路径分量逐段解析**后应当认出。
     *
     * 附带修掉一处跨平台缺陷：Windows 上 `readSymbolicLink().toString()` 给的是
     * `\root\...`（反斜杠），旧代码只判 `startsWith("/root/")` ⇒ 在"能建软链的 Windows
     * 机器"上这条链与上面那条一跳链用例都会真红（Linux/Android 上才是 `/root/...`）。
     *
     * Windows 上建软链要特权；建不出来就 `TestAssume.symlinkUnsupported` 跳过（与上面那条同一约定）。
     */
    @Test
    fun `两跳软链_Codex 那条链现在能认出来`() {
        val files = tmp.newFolder()
        val home = File(files, "home").apply { mkdirs() }

        // 真身：home/.codex/packages/standalone/releases/0.162.1/bin/codex
        val real = File(home, ".codex/packages/standalone/releases/0.162.1/bin/codex")
        real.parentFile!!.mkdirs()
        real.writeText("#!/bin/sh\n", Charsets.UTF_8)

        // 第二跳：current → releases/0.162.1（guest 视角的绝对路径）
        val current = File(home, ".codex/packages/standalone/current")
        val hop2 = runCatching {
            java.nio.file.Files.createSymbolicLink(
                current.toPath(),
                java.nio.file.Paths.get("/root/.codex/packages/standalone/releases/0.162.1")
            )
        }.isSuccess
        TestAssume.symlinkUnsupported(hop2)

        // 第一跳：~/.local/bin/codex → ~/.codex/packages/standalone/current/bin/codex
        val bin = File(home, ".local/bin/codex").apply { parentFile!!.mkdirs() }
        val hop1 = runCatching {
            java.nio.file.Files.createSymbolicLink(
                bin.toPath(),
                java.nio.file.Paths.get("/root/.codex/packages/standalone/current/bin/codex")
            )
        }.isSuccess
        TestAssume.symlinkUnsupported(hop1)

        val ctx = FakeFilesContext(files)
        assertTrue(
            "两跳链必须被认出来（旧实现只解一跳 ⇒ 这里会红）",
            existsInHost(ctx, "home/.local/bin/codex")
        )
        // 反例：解到底也到不了的，仍判「没装」
        assertFalse(existsInHost(ctx, "home/.local/bin/not-there"))
    }

    /** `existsInHost` 是 private —— 用反射调，刻意不为测试放宽生产代码可见性。 */
    private fun existsInHost(ctx: Context, relative: String): Boolean {
        val m = AppState::class.java.getDeclaredMethod(
            "existsInHost", Context::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(AppState, ctx, relative) as Boolean
    }
}
