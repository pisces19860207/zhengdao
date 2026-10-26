// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `:core` 的**依赖方向门禁**（第二百四十批，派工单判据②）。
 *
 * 抽 `:core` 的目的只有一个：让证道（将来的终端 APP 也一样）**向下**依赖共享件。
 * 一旦 `:core` 反过来 import 终端 / 抱朴的类，就说明"这刀没解耦，只是把文件换了个目录"——
 * 编译期未必报错（同一个 APK 里都在），但下一刀把终端搬出去时会立刻炸，而且炸在最难查的地方。
 *
 * 所以这里按源码钉死三条：
 * 1. 不许 `import com.termux.**`（PTY / 终端内核，将来属于 `:terminal-core`）；
 * 2. 不许 `import com.example.zhengdao.terminal.**`（安装器 / proot / 会话，同上）；
 * 3. 不许 `import com.example.zhengdao.baopu.**`（抱朴在上层，反向依赖＝分层反了）。
 *
 * 注意：只查 **import 行**，不查 KDoc 里的文字提及 —— 迁移说明里必然要写"原 `terminal/Store`"，
 * 那是注释不是依赖。第 2 条用例把这条边界也钉住（造一行 KDoc 提及不许判红）。
 */
class CoreBoundaryGateTest {

    private fun coreSources(): List<File> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    /** 纯判据（可单测）：给一批源码行，返回命中的「文件:行」清单。 */
    fun violations(files: List<Pair<String, List<String>>>): List<String> {
        val banned = listOf(
            "import com.termux.",
            "import com.example.zhengdao.terminal.",
            "import com.example.zhengdao.baopu.",
        )
        val hits = ArrayList<String>()
        for ((path, lines) in files) {
            lines.forEachIndexed { i, line ->
                val t = line.trim()
                if (banned.any { t.startsWith(it) }) hits += "$path:${i + 1}: $t"
            }
        }
        return hits
    }

    @Test
    fun `core 不许反向 import termux 与 terminal 与 baopu`() {
        val files = coreSources()
        assertTrue(":core 一个源文件都没扫到，门禁失效了（工作目录=${File(".").absolutePath}）", files.isNotEmpty())
        val hits = violations(files.map { it.path.replace('\\', '/') to it.readLines() })
        assertEquals(
            ":core 反向依赖了终端 / 抱朴 —— 抽模块的意义就是把依赖方向掰成单向，不是换个目录：\n" +
                hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `门禁自检_造违规必须被抓到_KDoc 提及不算`() {
        // 负例：把三条被禁的 import 各造一行 ⇒ 必须红（证明上面的判据不是空转）
        val bad = violations(
            listOf(
                "Fake.kt" to listOf(
                    "import com.termux.terminal.TerminalSession",
                    "import com.example.zhengdao.terminal.Store",
                    "import com.example.zhengdao.baopu.kernel.BaopuKernel",
                ),
            ),
        )
        assertEquals("三条违规必须全被抓到", 3, bad.size)

        // 边界：KDoc / 注释里提到旧包名不算依赖（迁移说明必然要写这些字）
        val ok = violations(
            listOf(
                "Fake.kt" to listOf(
                    " * 搬自 `terminal/Store`（现 ZhengdaoDirs）",
                    "// 原 com.example.zhengdao.rootfs.RunLog，现 :core",
                ),
            ),
        )
        assertEquals("注释里的提及不是依赖，不许判红：$ok", emptyList<String>(), ok)
    }
}
