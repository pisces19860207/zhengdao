// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第㉛刀形态门禁（Qoder 审查 🟡-3，台账 §B 顺序 24）：**terminal（底层）不得依赖 ui**。
 *
 * 背景：terminal 目录下的 5 个文件曾反向 `import com.example.zhengdao.ui.Settings`（19 处
 * 调用）拿 App 设置 prefs——编译期无碍但依赖方向反了。第㉛刀把存储访问器下沉到
 * [com.example.zhengdao.core.AppPrefs]（`ui.Settings.prefs` 保留同名 API 委托，
 * 行为零变化），本门禁把「terminal 不 import ui」钉成形态纪律，防回潮。
 *
 * 两条判据（剥注释后对 terminal 包**全部** .kt 生效）：
 * - A：不得出现 `import com.example.zhengdao.ui`（import 形态）；
 * - B：不得出现 `com.example.zhengdao.ui.`（全限定调用形态——ProotLauncher 曾
 *   不写 import 直接 FQN 调用，只查 import 拦不住它）。
 *
 * ## 正例 + 负例自证
 *
 * 正例打真源码；负例内嵌两种退化形态的回归串，断言判据**必然不通过**。
 * 真源码级演练（往 Workspace.kt 临时加回一行 import ⇒ 必红 ⇒ 还原复绿）留痕于报告。
 */
class TerminalUiDecoupleGateTest {

    // ── 源码收集（双路径兜底：cwd 可能是仓根或 app/） ──

    private fun terminalDir(): File {
        val roots = listOf(
            File("app/src/main/java/com/example/zhengdao/terminal"),
            File("src/main/java/com/example/zhengdao/terminal"),
        )
        return roots.firstOrNull { it.isDirectory }
            ?: error("找不到 terminal 源码目录（cwd=${File("").absolutePath}）")
    }

    /** terminal 包全部 .kt 源文件（含子包，若有）。 */
    private fun terminalSources(): List<File> =
        terminalDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 去注释（先剥再断言，否则 KDoc 里提一嘴 ui 就算自己拦自己）。 */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    // ── 判据纯函数 ──

    /** 判据 A：terminal 源码不得 import ui 包。 */
    internal fun noUiImport(strippedSource: String): Boolean =
        strippedSource.lines().none { it.contains("import com.example.zhengdao.ui") }

    /** 判据 B：不得以全限定名引用 ui 包（`com.example.zhengdao.ui.`）。 */
    internal fun noUiFqnReference(strippedSource: String): Boolean =
        !strippedSource.contains("com.example.zhengdao.ui.")

    private fun allPass(strippedSource: String): Boolean =
        noUiImport(strippedSource) && noUiFqnReference(strippedSource)

    // ── 正例（打真源码） ──

    @Test
    fun `门禁_terminal 包内不得 import ui`() {
        val files = terminalSources()
        assertTrue("terminal 包应有源码文件", files.isNotEmpty())
        val bad = files.filter { !noUiImport(stripped(it.readText())) }
        assertTrue("违例文件：${bad.map { it.name }}", bad.isEmpty())
    }

    @Test
    fun `门禁_terminal 包内不得全限定引用 ui`() {
        val files = terminalSources()
        val bad = files.filter { !noUiFqnReference(stripped(it.readText())) }
        assertTrue("违例文件：${bad.map { it.name }}", bad.isEmpty())
    }

    // ── 负例自证（旧形态 ⇒ 判据必不通过） ──

    @Test
    fun `负例一_加回 import ui 必须不过判据A`() {
        val regressed = """
            package com.example.zhengdao.terminal

            import com.example.zhengdao.ui.Settings

            object FakeWorkspace {
                fun p(ctx: android.content.Context) = Settings.prefs(ctx)
            }
        """.trimIndent()
        assertFalse(noUiImport(stripped(regressed)))
    }

    @Test
    fun `负例二_全限定调用 ui 必须不过判据B`() {
        // ProotLauncher 曾有的形态：不写 import，调用处直接 FQN
        val regressed = """
            package com.example.zhengdao.terminal

            object FakeLauncher {
                val memLimitMb = com.example.zhengdao.ui.Settings.prefs(null)
            }
        """.trimIndent()
        assertFalse(noUiFqnReference(stripped(regressed)))
    }
}
