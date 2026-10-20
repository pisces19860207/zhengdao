// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第七十三批 · 源码门禁：抽取**只剩调用**，不留第二份实现。
 *
 * §2 的红线是「controller 对应逻辑只剩调用」，这条用文本判据钉住它——纯函数直测能证明
 * helper 自己是对的，证明不了 HomeScreen 真的改走了 helper（两边各留一份也能全绿）。
 *
 * 判据一律读**剥掉注释**后的源码（先剥块注释再剥整行注释），且只用搬走的**代码字面**
 * （`Regex("rm`、`removePrefix`、`distinct()`…），不用宽泛的 `contains("rm -rf")`——
 * 后者会与第六十八批留在 HomeScreen 的 `uninstallCommandWithWipe`（`" && rm -rf "`）自相打架。
 */
class HomePureExtractionGateTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val home by lazy {
        code("app/src/main/java/com/example/zhengdao/ui/HomeScreen.kt")
    }
    private val agentRules by lazy {
        code("app/src/main/java/com/example/zhengdao/ui/home/HomeAgentRules.kt")
    }
    private val installRules by lazy {
        code("app/src/main/java/com/example/zhengdao/ui/home/HomeInstallRules.kt")
    }

    // ── 1. HomeScreen 只剩调用 ──────────────────────────────────────

    @Test
    fun `门禁_五处判据都从 helper 取`() {
        listOf(
            "homePrimaryActionOf(",
            "homeStatusLineOf(",
            "homeMoreMenuVisible(",
            "homeReinstallItemVisible(",
            "uninstallProgramPaths(",
        ).forEach { call ->
            assertTrue("HomeScreen 里没有 $call —— 抽取没落地", home.contains(call))
        }
    }

    @Test
    fun `门禁_helper 的导入真的在`() {
        listOf(
            "import com.example.zhengdao.ui.home.HomePrimaryAction",
            "import com.example.zhengdao.ui.home.HomeStatusLine",
            "import com.example.zhengdao.ui.home.homePrimaryActionOf",
            "import com.example.zhengdao.ui.home.homeStatusLineOf",
            "import com.example.zhengdao.ui.home.homeMoreMenuVisible",
            "import com.example.zhengdao.ui.home.homeReinstallItemVisible",
            "import com.example.zhengdao.ui.home.uninstallProgramPaths",
        ).forEach { imp ->
            assertTrue("HomeScreen 缺 $imp", home.contains(imp))
        }
    }

    @Test
    fun `门禁_HomeScreen 不留第二份实现`() {
        listOf(
            "enum class HomePrimaryAction",
            "enum class HomeStatusLine",
            "fun homePrimaryActionOf",
            "fun homeStatusLineOf",
            "fun homeMoreMenuVisible",
            "fun homeReinstallItemVisible",
            "fun uninstallProgramPaths",
        ).forEach { def ->
            assertFalse("HomeScreen 里还留着本地实现：$def", home.contains(def))
        }
    }

    @Test
    fun `门禁_搬走的判据字面不再出现在 HomeScreen`() {
        // ①②的分支条件
        assertFalse(home.contains("failedInstall && envReady"))
        assertFalse(home.contains("installed && (agent.uninstallCmd != null"))
        // ④的解析与映射
        listOf(
            """Regex("rm""",
            "removePrefix",
            "distinct()",
            "contains('*')",
            """startsWith("/")""",
            """p.startsWith("/usr/")""",
        ).forEach { moved ->
            assertFalse("④ 的字面还留在 HomeScreen：$moved", home.contains(moved))
        }
    }

    // ── 2. 判据确实归位在 helper ────────────────────────────────────

    @Test
    fun `门禁_AgentRules 里是原优先序`() {
        // 六臂的**顺序**就是改前 when 的顺序：失败(带 envReady) → 已装 → 安装中 → 安装 → 暂未提供 → 先装环境
        val order = listOf(
            "failedInstall && envReady -> HomePrimaryAction.RetryInstall",
            "installed -> HomePrimaryAction.Launch",
            "installing -> HomePrimaryAction.Installing",
            "hasInstallCmd && envReady -> HomePrimaryAction.Install",
            "!hasInstallCmd -> HomePrimaryAction.ComingSoon",
            "else -> HomePrimaryAction.InstallEnvFirst",
        )
        var at = -1
        order.forEach { arm ->
            val i = agentRules.indexOf(arm)
            assertTrue("AgentRules 缺臂或次序变了：$arm", i > at)
            at = i
        }
        // 状态行同理
        listOf(
            "failedInstall -> HomeStatusLine.Failure",
            "installing -> HomeStatusLine.InstallHint",
            "installed && hasInstalledVersion -> HomeStatusLine.Version",
            "else -> HomeStatusLine.None",
        ).forEach { arm -> assertTrue("AgentRules 缺状态行判据：$arm", agentRules.contains(arm)) }
        // ③
        assertTrue(agentRules.contains("installed && (hasUninstallCmd || hasInstallCmd)"))
        assertTrue(agentRules.contains("hasInstallCmd && envReady"))
    }

    @Test
    fun `门禁_InstallRules 里是原解析口径`() {
        listOf(
            """Regex("rm\\s+-rf\\s+([^&\\n]+)")""",
            """it.startsWith("/") && !it.contains('*')""",
            ".distinct()",
            """p == "/root" || p.startsWith("/root/")""",
            """"home/" + p.removePrefix("/root/")""",
            """p == "/usr" || p.startsWith("/usr/")""",
            """"rootfs" + p""",
        ).forEach { kept ->
            assertTrue("InstallRules 缺解析口径：$kept", installRules.contains(kept))
        }
        assertTrue(installRules.contains("""p.removePrefix("/root/")"""))
    }

    // ── 3. 别人的东西不许动 ─────────────────────────────────────────

    @Test
    fun `门禁_第六十八批的拼装字面仍留在 HomeScreen`() {
        // 这两条是 68 抽好的（uninstallCommandWithWipe / shellSingleQuote），73 只接手归位、
        // 不重抽。用完整字面而不是宽泛的 "rm -rf"——后者在两个文件里都有，会自相打架。
        assertTrue(home.contains("""cmd + " && rm -rf " + wipe.joinToString(" ")"""))
        assertTrue(home.contains("""raw.replace("'", "'\\''")"""))
    }

    @Test
    fun `门禁_渲染与文案仍在 HomeScreen 原位`() {
        // 抽取只搬"怎么判"：六条按钮文案、两条状态条、浅底 tone 都必须还在调用处。
        listOf(
            "R.string.home_agent_retry_install",
            "R.string.home_agent_start",
            "R.string.home_agent_installing",
            "R.string.home_agent_install",
            "R.string.home_agent_coming_soon",
            "R.string.home_agent_install_env_first",
            "R.string.home_agent_install_hint",
            "R.string.home_agent_version",
            "zhengdaoErrorText()",
            "StatusNote(",
            "BUTTON_MIN_WIDTH_DP.dp",
            "RoundedCornerShape(50)",
        ).forEach { kept ->
            assertTrue("HomeScreen 的渲染/文案少了一处：$kept", home.contains(kept))
        }
        assertTrue(
            "主按钮六臂应当各渲染一次",
            Regex("HomePrimaryAction\\.\\w+ ->").findAll(home).count() == 6,
        )
        assertTrue(
            "状态行四臂 ⇒ 三个渲染出口 + None 不渲染",
            Regex("HomeStatusLine\\.\\w+ ->").findAll(home).count() == 4,
        )
    }

    @Test
    fun `门禁_新文件不引入磁盘与线程依赖`() {
        // 纯逻辑抽取的前提：helper 里不许出现 IO / 线程 / Compose（否则就不是纯函数直测了）
        listOf(agentRules, installRules).forEach { src ->
            listOf(
                "java.io.File", "File(", "SystemInfoProvider", "Thread", "Coroutine", "runBlocking",
                "@Composable", "androidx.compose", "android.content.Context", "SharedPreferences",
                "SimpleDateFormat", "AgentRepository", "stringResource",
            ).forEach { banned ->
                assertFalse("helper 里出现了不该有的依赖：$banned", src.contains(banned))
            }
        }
    }
}
