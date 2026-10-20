// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [nextPaneCountAfterSplit] / [nextPaneCountAfterClose] 的验收（第七十四批）。
 *
 * 钉的是「客户端计数」的两个取舍（见 [TerminalPaneRules] 的 KDoc）：
 * - 未知（`-1`，attach 到旧会话）时：分屏按"两块"记、关闭收敛到"一块"；
 * - 计数不设上限：守卫在按钮分支（`paneCount >= 2` 就提示"最多两块"），
 *   计数函数只负责"这一步加/减一"，照搬既有行为。
 */
class TerminalPaneRulesTest {

    @Test
    fun `分屏_已知一块变两块`() {
        assertEquals(2, nextPaneCountAfterSplit(1))
    }

    @Test
    fun `分屏_未知按两块记`() {
        // attach 到旧会话时 paneCount = -1（服务端有几块问不到）。
        assertEquals(2, nextPaneCountAfterSplit(-1))
        assertEquals(2, nextPaneCountAfterSplit(0))
    }

    @Test
    fun `分屏_计数照加一不设上限`() {
        // 未知态偶发分出 3 块是已知最坏情况：计数如实记 3，用户点黄点收回。
        assertEquals(3, nextPaneCountAfterSplit(2))
        assertEquals(4, nextPaneCountAfterSplit(3))
    }

    @Test
    fun `关闭_两块变一块`() {
        assertEquals(1, nextPaneCountAfterClose(2))
        assertEquals(2, nextPaneCountAfterClose(3))
    }

    @Test
    fun `关闭_一块或未知都收敛到一块`() {
        assertEquals(1, nextPaneCountAfterClose(1))
        assertEquals(1, nextPaneCountAfterClose(-1))
        assertEquals(1, nextPaneCountAfterClose(0))
    }

    // ── 源码门禁 ──────────────────────────────────────────────────────────

    private fun activitySource(): String =
        File("src/main/java/com/example/zhengdao/TerminalActivity.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/TerminalActivity.kt") }
            .readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `两个按钮只调计数函数旧的三元表达式不得残留`() {
        val src = activitySource()
        assertTrue("分屏按钮必须调用 nextPaneCountAfterSplit", src.contains("nextPaneCountAfterSplit(paneCount)"))
        assertTrue("黄点必须调用 nextPaneCountAfterClose", src.contains("nextPaneCountAfterClose(paneCount)"))
        assertFalse(
            "旧的手写三元（paneCount + 1 / - 1）不得残留 —— 两份实现必然漂移",
            src.contains("paneCount = if (paneCount > 0)") || src.contains("paneCount = if (paneCount > 1)"),
        )
    }
}
