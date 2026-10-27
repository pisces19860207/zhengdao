// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二百五十批（终端收尾）的**零终端门禁**。
 *
 * 这一刀的要求只有一句：**主 APP 不再提"终端"** —— 不提示、不检测、不给链接、不显状态。
 * 而"不提"是最容易回潮的纪律：改一句文案、加一个跳转、留一个 `open_terminal` extra，
 * 编译期都不会报错。所以按**资源 + 源码**两头钉死：
 *
 * - **判据 A（资源）**：`res/values-xx/strings.xml` 里的**字符串值**不许出现
 *   `终端 / 逍遥 / rootfs / tmux / proot / PRoot`。注释不算（历史说明要写"终端拆走了"）。
 * - **判据 B（源码）**：`app/src/main/java` 下的**代码行**（剥掉注释）不许引用终端符号：
 *   `com.termux`、`RootfsInstaller`、`SessionManager`、`ProotLauncher`、`SessionService`、
 *   `open_terminal`、`nav_terminal`、`nav_agents`。
 * - **判据 C（底栏）**：`R.string.nav_*` 恰好三条（抱朴 / 玄览 / 律令），且设置页对外叫「律令」。
 *
 * 三条都带负例自检 —— 防的是"判据写错了但永远绿"。
 */
class NoTerminalWordingGateTest {

    private val bannedWords = listOf("终端", "逍遥", "rootfs", "tmux", "proot", "PRoot")
    private val bannedSymbols = listOf(
        "com.termux", "RootfsInstaller", "SessionManager", "ProotLauncher", "SessionService",
        "open_terminal", "nav_terminal", "nav_agents",
    )

    /** 剥注释：块注释整段去掉，行注释截断（与 TerminalMovedOutGateTest 同一口径）。 */
    private fun stripComments(text: String): List<String> =
        text.replace(Regex("(?s)/\\*.*?\\*/"), "")
            .lines()
            .map { it.substringBefore("//") }

    /** 判据 A：资源里的字符串值。 */
    fun resourceViolations(files: List<Pair<String, List<String>>>): List<String> {
        val hits = ArrayList<String>()
        for ((path, lines) in files) {
            lines.forEachIndexed { i, line ->
                // 只看 <string>/<plurals> 的值行，注释行（<!-- -->）天然不含 <string name=
                if (!line.contains("<string name=") && !line.contains("<item quantity=")) return@forEachIndexed
                bannedWords.forEach { w ->
                    if (line.contains(w)) hits += "$path:${i + 1}: ${line.trim().take(90)}"
                }
            }
        }
        return hits
    }

    /** 判据 B：源码里的代码行（已剥注释）。 */
    fun sourceViolations(files: List<Pair<String, List<String>>>): List<String> {
        val hits = ArrayList<String>()
        for ((path, lines) in files) {
            lines.forEachIndexed { i, line ->
                bannedSymbols.forEach { s ->
                    if (line.contains(s)) hits += "$path:${i + 1}: ${line.trim().take(90)}"
                }
            }
        }
        return hits
    }

    @Test
    fun `资源里不许再出现终端字样`() {
        val files = File("src/main/res").walkTopDown()
            .filter { it.isFile && it.name == "strings.xml" }
            .sortedBy { it.path }
            .map { it.path.replace('\\', '/') to it.readLines() }
            .toList()
        assertTrue("一个 strings.xml 都没扫到（cwd=${File("").absolutePath}）", files.isNotEmpty())
        val hits = resourceViolations(files)
        assertEquals("资源里还有终端字样（不许提示、不许显状态）：\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun `源码里不许再引用终端符号`() {
        val files = File("src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .sortedBy { it.path }
            .map { it.path.replace('\\', '/') to stripComments(it.readText()) }
            .toList()
        assertTrue("一个源文件都没扫到（cwd=${File("").absolutePath}）", files.isNotEmpty())
        val hits = sourceViolations(files)
        assertEquals("源码还在引用终端符号（终端已拆到独立仓）：\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun `底栏只有三个 Tab_设置对外叫律令`() {
        val zh = File("src/main/res/values/strings.xml").readText()
        val navNames = Regex("""<string name="(nav_\w+)">""").findAll(zh).map { it.groupValues[1] }.toList()
        assertEquals("nav_* 必须恰好三条（抱朴 / 玄览 / 律令）：$navNames", 3, navNames.size)
        assertTrue("设置 Tab 的文案必须是「律令」", zh.contains("""<string name="nav_settings">律令</string>"""))
        val en = File("src/main/res/values-en/strings.xml").readText()
        assertTrue("英文文案必须是 Statutes", en.contains("""<string name="nav_settings">Statutes</string>"""))
    }

    @Test
    fun `门禁自检_造违规必须被抓到`() {
        val badRes = resourceViolations(
            listOf("Fake/strings.xml" to listOf("""    <string name="x">进终端看看</string>""")),
        )
        assertEquals("资源负例必须被抓到：$badRes", 1, badRes.size)

        val badSrc = sourceViolations(
            listOf("Fake.kt" to listOf("""val i = Intent(ctx, SessionService::class.java)""")),
        )
        assertEquals("源码负例必须被抓到：$badSrc", 1, badSrc.size)

        // 正例：注释里提到"终端"不算（迁移说明必然要写）
        val okSrc = sourceViolations(
            listOf("Fake.kt" to stripComments(" * 终端已拆到独立仓（zhengdao-term）\n// 原 SessionService")),
        )
        assertEquals("注释里的提及不算：$okSrc", emptyList<String>(), okSrc)
    }
}
