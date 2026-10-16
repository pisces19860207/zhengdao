// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第㉒刀-A 的结构门禁：**失败态「重试」必须先确保 serve 在跑，再重开连接**。
 *
 * 背景（2026-10-15 真机实测）：serve 进程死亡后，FailedPane「重试」原先只调
 * `repo.open()` —— open() 四条判据全是连接层，serve 进程死了点多少次都失败。
 *
 * 判据（先剥注释再结构判定，范式同 InstallerCleanBusyGateTest / UserFacingTextGateTest）：
 * 以 `state.phase is BaopuPhase.Failed ->`（when 分支条件）为锚、到 FailedPane 私有
 * Composable 定义处为界，这段**重试接线窗口**里必须同时出现：
 * ① `failedRetryNeedsStartServe(` —— 判据走纯函数，不是裸 if；
 * ② `OcManager.startServe(` —— 拉起复用现成幂等入口，不许另写一套；
 * ③ `Dispatchers.IO` —— 探测与拉起都是阻塞调用，不得上主线程。
 *
 * E-133 教训（判据写死局部变量名会被等价改写绕过）：本门禁不钉局部变量名，钉的是
 * 「重试窗口里的三个**调用形态**」——改成等价助手函数会被 ②/① 的缺席抓红并提示
 * 「锚点/结构变了就更新本测试」；这是有意的响亮失败，不是静默放行。
 */
class BaopuRetryStartServeGateTest {

    private val file = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"

    private fun code(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return stripComments(f.readText())
    }

    /** 只剥 `//` 与块注释（保留字符串字面量）：注释里写一句 startServe 不算数。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    out.append(text, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < text.length) { out.append(text[i]); i++; continue }
                        if (d == quote) break
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 重试接线窗口：when 分支条件 → FailedPane 私有定义（窗口内是重试的全部接线）。 */
    private fun retryWindow(s: String): String {
        val anchor = "state.phase is BaopuPhase.Failed ->"
        val start = s.indexOf(anchor)
        assertTrue("BaopuScreen.kt 里找不到重试分支锚点 `$anchor`（锚点变了就更新本测试）", start >= 0)
        val end = s.indexOf("private fun FailedPane(", start)
        assertTrue("重试分支之后找不到 FailedPane 定义（文件结构变了就更新本测试）", end > start)
        return s.substring(start, end)
    }

    @Test
    fun `㉒A 重试接线必须走判据函数并复用 startServe 且在 IO 上`() {
        val window = retryWindow(code(file))
        assertTrue(
            "失败态重试必须先走 failedRetryNeedsStartServe 判据（裸 if/漏判 ⇒ serve 死后重试永远失败）；窗口：\n$window",
            window.contains("failedRetryNeedsStartServe("),
        )
        assertTrue(
            "失败态重试必须复用 OcManager.startServe 幂等入口拉起 serve（不许另写一套）；窗口：\n$window",
            window.contains("OcManager.startServe("),
        )
        assertTrue(
            "serveRunning() 探测与 startServe 都是阻塞调用，必须在 Dispatchers.IO 上；窗口：\n$window",
            window.contains("Dispatchers.IO"),
        )
    }
}
