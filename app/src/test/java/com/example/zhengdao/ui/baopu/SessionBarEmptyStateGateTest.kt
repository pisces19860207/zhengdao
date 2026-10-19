// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第四十九批 · 任务 A 的源码门禁：SessionBar 空态整体不渲染。
 *
 * 现场（基线 `1d709f32`，`BaopuSessionBar.kt`）：`:49` 的 `Surface` **不在任何 if 里**，
 * `:52`（中断行）与 `:70`（notice 行）才各自有 if ⇒ 两个条件都空时，
 * `Column(vertical = 8.dp)` 仍然撑出一条 **16dp 的空灰条**，常驻抱朴首屏。
 *
 * 四组判据（源码文本级，先剥注释再断言，与 `BaopuCollapseAffordanceTest` 同一手法）：
 * ① `Surface(` 必须出现在 `if (sessionBarVisible(` **之后**（证明真的包上了，不是只加了个函数）；
 * ② 四个入参一个不许漏（漏 `hasSession` 就变成"无会话也占位"，漏 `notice` 就把引擎人话吞了）；
 * ③ 谓词不许写成恒真/恒假 —— 函数体必须同时问 `busyState(state)` 与 `notice.isNullOrBlank()`；
 * ④ 内部两个 if **保留**：包上外层之后，有中断／有 notice 时的行为与改前一模一样。
 */
class SessionBarEmptyStateGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun bar() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSessionBar.kt")

    @Test
    fun `Surface必须包在sessionBarVisible判断里`() {
        val src = bar()
        val guard = src.indexOf("if (sessionBarVisible(")
        val surface = src.indexOf("Surface(")
        assertTrue("外层判断根本没写（Surface 还是无条件渲染 ⇒ 16dp 空条常驻）", guard >= 0)
        assertTrue("Surface 本体还在（不该顺手删掉）", surface >= 0)
        assertTrue("Surface( 必须出现在 if (sessionBarVisible( 之后", surface > guard)
        assertEquals("Surface 只该有一处（别在别处再补一个无条件 Surface）", 1, Regex("Surface\\(").findAll(src).count())
    }

    @Test
    fun `四个入参一个不许漏`() {
        val src = bar()
        assertTrue(
            "调用必须把 state / interruptedStep / hasSession / notice 四个都传给谓词",
            src.contains("if (sessionBarVisible(state, interruptedStep, hasSession, notice))"),
        )
    }

    @Test
    fun `谓词是真判据_不许写成恒真`() {
        val src = bar()
        val def = src.indexOf("internal fun sessionBarVisible(")
        assertTrue("谓词是同文件可见的 internal 函数（JVM 单测直接调）", def >= 0)
        val body = src.substring(def, minOf(src.length, def + 700))
        val arrow = body.indexOf("): Boolean =")
        assertTrue("谓词得有返回表达式", arrow >= 0)
        val expr = body.substring(arrow + "): Boolean =".length).lines().first { it.isNotBlank() }.trim()
        assertTrue("谓词得有内容，不许退化成恒真／恒假：$expr", expr.isNotEmpty() && expr != "true" && expr != "false")
        assertTrue("空 notice 必须按 isNullOrBlank 算（全空格也算空）", expr.contains("notice.isNullOrBlank()"))
        assertTrue("中断判据必须继续问 busyState", expr.contains("busyState(state)"))
        assertTrue("中断判据必须继续问 hasSession", expr.contains("hasSession"))
    }

    @Test
    fun `内部两个if保留_有内容时行为不变`() {
        val src = bar()
        assertTrue(
            "中断行的 if 原样保留（含 !busyState 与 hasSession）",
            src.contains("if (interruptedStep >= 0 && !busyState(state) && hasSession)"),
        )
        assertTrue("notice 行的 if 原样保留", src.contains("if (!notice.isNullOrBlank())"))
    }
}
