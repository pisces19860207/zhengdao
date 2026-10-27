// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二百四十八批（终端解耦第四刀）的**断链门禁**。
 *
 * 这一刀把终端整仓搬去 `zhengdao-term`，主仓从此不该再有任何一行终端代码。但"搬走"这件事
 * 最容易在两个地方悄悄回潮：
 * 1. 有人图省事 `import com.example.zhengdao.rootfs.X`（路径看着还在，其实模块已经没了）；
 * 2. 有人把终端的类"顺手复制一份"回来（复制粘贴比引依赖快）。
 * 两者编译期都能过（只要文件在），所以按源码钉死：
 *
 * - **判据 A**：`app/src/main/java` 下**不许出现** `com.termux` / `com.example.zhengdao.rootfs` /
 *   `com.example.zhengdao.terminal` 这三个包的引用（import 或全限定名都算）；
 *   **KDoc/注释里提到旧路径不算**——迁移说明必然要写"原 `terminal/SessionManager`"，那是文字。
 * - **判据 B**：COMPAT 组（`read` / `bash` 两条免费通道占位）已退役：注册表里不许再有这两条，
 *   且 `ToolGroupTable` 里没有任何工具归属 `COMPAT`。
 *
 * 两条都带负例自检（造一行违规必须被抓到），防的是"判据写错了但永远是绿的"。
 */
class TerminalMovedOutGateTest {

    private fun sources(): List<File> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.path }
            .toList()

    /** 剥掉注释（块注释 + 行注释），只留代码行——KDoc 里提旧路径是说明，不是依赖。 */
    private fun stripComments(text: String): List<String> =
        text.replace(Regex("(?s)/\\*.*?\\*/"), "")
            .lines()
            .map { it.substringBefore("//") }

    /** 纯判据：给一批 (路径, 行) 返回命中的「文件:行」。 */
    fun violations(files: List<Pair<String, List<String>>>): List<String> {
        val banned = listOf(
            "com.termux",
            "com.example.zhengdao.rootfs",
            "com.example.zhengdao.terminal",
        )
        val hits = ArrayList<String>()
        for ((path, lines) in files) {
            lines.forEachIndexed { i, line ->
                val t = line.trim()
                // 注释样式的行不算（KDoc 的 `*`、行注释 `//`、块注释起止）——迁移说明必然要写旧路径
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) return@forEachIndexed
                if (banned.any { line.contains(it) }) hits += "$path:${i + 1}: $t"
            }
        }
        return hits
    }

    @Test
    fun `主仓源码里不许再有终端三包的引用`() {
        val files = sources()
        assertTrue("一个源文件都没扫到，门禁失效了（cwd=${File("").absolutePath}）", files.isNotEmpty())
        val hits = violations(files.map { it.path.replace('\\', '/') to stripComments(it.readText()) })
        assertEquals(
            "主仓还在引用终端/rootfs/termux —— 第四刀之后它们只在 zhengdao-term 里：\n" +
                hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `门禁自检_造违规必须被抓到_注释提及不算`() {
        val bad = violations(
            listOf(
                "Fake.kt" to listOf(
                    "import com.termux.terminal.TerminalSession",
                    "val x = com.example.zhengdao.rootfs.RootfsInstaller",
                    "import com.example.zhengdao.terminal.SessionManager",
                ),
            ),
        )
        assertEquals("三条违规必须全被抓到：$bad", 3, bad.size)

        // 注释里的提及不算（迁移说明必然要写这些字）
        val ok = violations(
            listOf(
                "Fake.kt" to stripComments(
                    " * 搬自 `com.example.zhengdao.terminal.SessionManager`（现 zhengdao-term）\n" +
                        "// 原 com.example.zhengdao.rootfs.RootfsInstaller，第四刀搬走",
                ),
            ),
        )
        assertEquals("注释里的提及不是依赖：$ok", emptyList<String>(), ok)
    }

    @Test
    fun `COMPAT 组已退役_没有任何工具归属它`() {
        val table = com.example.zhengdao.baopu.agent.ToolGroupTable.GROUP_OF
        val compat = table.filterValues { it == com.example.zhengdao.baopu.agent.ToolGroup.COMPAT }
        assertEquals(
            "COMPAT 组（原 read/bash 免费通道占位）应已退役，仍有工具归属：${compat.keys}",
            emptyMap<String, com.example.zhengdao.baopu.agent.ToolGroup>(),
            compat,
        )
    }
}
