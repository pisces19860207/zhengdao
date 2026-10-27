// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.termcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `:terminal-core` 的**依赖方向门禁**（第二百四十一批，派工单 §3「硬门禁」）。
 *
 * 抽这个模块的目的只有一个：让终端内核**站在 :app 下面**，将来第三刀（`:app-term`）能直接
 * 拿它组一个新 APP。一旦本模块反过来 import 主 APP 的东西，就说明"这刀没解耦，只是把文件
 * 换了个目录"——编译期未必报错（同一个 APK 里都在，Gradle 也拦得住一部分），
 * 但下一刀把 `:app` 摘掉时会立刻炸，而且炸在最难查的地方。
 *
 * 按源码钉死四条（全部是**编译期不可能合法**的边）：
 * 1. 不许 `import com.example.zhengdao.baopu.**`（抱朴在上层，反向依赖＝分层反了）；
 * 2. 不许 `import com.example.zhengdao.ui.**`（页面是壳，第三刀随 `:app-term` 走）；
 * 3. 不许 `import com.example.zhengdao.R` / `com.example.zhengdao.BuildConfig`
 *    （资源与 BuildConfig 属于主 APP，库模块只用自己的 `com.example.zhengdao.termcore.R`）；
 * 4. 不许 `import com.example.zhengdao.MainActivity`（壳的入口；本模块只认组件名字符串，
 *    见 `terminal/TerminalShellTarget.kt`）。
 *
 * 只查 **import 行**：KDoc 里写"原 `ui/ImeProbe`""点通知回 MainActivity"是说明，不是依赖。
 * 第 2 个用例把这条边界也钉住（造 KDoc 提及不许判红），同时证明判据不是空转。
 */
class TerminalCoreBoundaryGateTest {

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.path }
            .toList()

    /** 纯判据（可单测）：给一批源码行，返回命中的「文件:行」清单。 */
    fun violations(files: List<Pair<String, List<String>>>): List<String> {
        val banned = listOf(
            "import com.example.zhengdao.baopu.",
            "import com.example.zhengdao.ui.",
            "import com.example.zhengdao.R",
            "import com.example.zhengdao.BuildConfig",
            "import com.example.zhengdao.MainActivity",
        )
        val hits = ArrayList<String>()
        for ((path, lines) in files) {
            lines.forEachIndexed { i, line ->
                val t = line.trim()
                // 包级禁令（以 `.` 结尾）按前缀判；类级禁令按「整条相等 或 前缀 + .」判，
                // 免得 `import com.example.zhengdao.termcore.R` 被误判成主 APP 的 R。
                val hit = banned.any { b ->
                    if (b.endsWith(".")) t.startsWith(b) else (t == b || t.startsWith("$b."))
                }
                if (hit) hits += "$path:${i + 1}: $t"
            }
        }
        return hits
    }

    @Test
    fun `terminal-core 不许反向 import baopu 与 ui 与主 APP 的 R_MainActivity`() {
        val files = sources()
        assertTrue(
            ":terminal-core 一个源文件都没扫到，门禁失效了（工作目录=${File(".").absolutePath}）",
            files.isNotEmpty(),
        )
        val hits = violations(files.map { it.path.replace('\\', '/') to it.readLines() })
        assertEquals(
            ":terminal-core 反向依赖了主 APP —— 抽模块的意义就是把依赖方向掰成单向，不是换个目录：\n" +
                hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `门禁自检_造违规必须被抓到_KDoc 提及与自有 R 不算`() {
        // 负例：五条被禁的 import 各造一行 ⇒ 必须全红（证明判据不是空转）
        val bad = violations(
            listOf(
                "Fake.kt" to listOf(
                    "import com.example.zhengdao.baopu.kernel.BaopuKernel",
                    "import com.example.zhengdao.ui.EnvHealth",
                    "import com.example.zhengdao.R",
                    "import com.example.zhengdao.MainActivity",
                    "import com.example.zhengdao.BuildConfig",
                ),
            ),
        )
        assertEquals("五条违规必须全被抓到：$bad", 5, bad.size)

        // 边界：注释里提到旧位置不算依赖；库模块**自己的** R 不算违规
        val ok = violations(
            listOf(
                "Fake.kt" to listOf(
                    " * 搬自 `ui/ImeProbe`（页面仍在 :app，第三刀随 :app-term）",
                    "// 点通知回 MainActivity 的 Intent 由 TerminalShellTarget 给",
                    "import com.example.zhengdao.termcore.R",
                    "import com.example.zhengdao.terminal.SessionManager",
                ),
            ),
        )
        assertEquals("注释里的提及与自有 R 不是依赖，不许判红：$ok", emptyList<String>(), ok)
    }
}
