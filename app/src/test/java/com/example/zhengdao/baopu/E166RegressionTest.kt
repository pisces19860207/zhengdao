// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// E-166（2026-10-19 真机走查 44 批）：两个「单测全绿、真机全哑」的回归。
//   D-01 守卫把 path 改写成绝对路径 ⇒ 产物三处一起哑；
//   D-02 带工具/计划卡的轮漏传 onUserLongPress ⇒ 这一型长按没菜单。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class E166RegressionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── D-01：绝对路径归一化 ─────────────────────────────────────────────

    @Test
    fun absolute_path_under_workspace_becomes_relative_and_is_selected() {
        val root = tmp.newFolder("ws")
        val sub = File(root, "verify44").apply { mkdirs() }
        val f = File(sub, "out.txt").apply { writeText("hello") }
        val abs = f.absolutePath.replace('\\', '/')

        assertEquals("verify44/out.txt", toWorkspaceRelative(root, abs))

        val kept = selectTextArtifacts(root, listOf(abs))
        assertEquals(listOf("verify44/out.txt"), kept)
    }

    @Test
    fun relative_path_stays_and_outside_absolute_is_untouched() {
        val root = tmp.newFolder("ws2")
        assertEquals("a/b.txt", toWorkspaceRelative(root, "a/b.txt"))
        assertEquals("a/b.txt", toWorkspaceRelative(root, "./a/b.txt"))
        val outside = if (System.getProperty("os.name").startsWith("Windows")) "/C:/外面/x.txt" else "/外面/x.txt"
        assertEquals(outside, toWorkspaceRelative(root, outside))
    }

    @Test
    fun select_still_filters_non_text_and_missing() {
        val root = tmp.newFolder("ws3")
        val f = File(root, "x.bin").apply { writeText("abc") }
        val kept = selectTextArtifacts(
            root,
            listOf(f.absolutePath.replace('\\', '/'), "不存在.txt"),
        )
        assertTrue("非文本/不存在都不该进：$kept", kept.isEmpty())
    }

    // ── D-02：带工具的轮必须传长按回调 ───────────────────────────────────

    @Test
    fun tool_round_passes_on_user_long_press() {
        val screen = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")
            .let { if (it.isFile) it else File("app/$it") }
            .readText()
        // 取带工具分支里那次 BaopuRoundItem(...) 调用
        val idx = screen.indexOf("BaopuRoundItem(")
        assertTrue("找不到 BaopuRoundItem 调用", idx > 0)
        // 139 批把调用挪进了 `if (roundHasProcess…) else` 分支，缩进变了——
        // 取调用点之后的一段够用的窗口，而不是依赖某个固定缩进的右括号。
        val tail = screen.substring(idx)
        val call = tail.substring(0, minOf(tail.length, 800))
        assertTrue("带工具的轮漏传 onUserLongPress：\n$call", call.contains("onUserLongPress = onUserLongPress"))
    }
}
