// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 审计 C4 的**源码门禁**：硬件键盘的退格必须镜像行缓冲，且不受粘滞 Ctrl 影响。
 *
 * 为什么用读源码的方式钉（而不是单测行为）：`TerminalActivity.onKeyDown` 是 Android
 * Activity 上的覆写方法，JVM 单测里拿不到（没有真实 KeyEvent／TerminalSession）。
 * 所以这条测试只保证**形状**：谁把退格分支重新包回 `!stickyCtrl`，谁就会看到它红。
 *
 * 背景（改前的样子）：整块 `when` 都挂在 `if (e != null && !stickyCtrl)` 之下 ⇒
 * 点亮粘滞 Ctrl 时退格不再喂 `onUserBackspace()`，行缓冲里留下**已经被删掉的字**，
 * 下一次回车把这段"幽灵文本"当成提问提交（黄点知识库检索会去查一段并不存在的话）。
 * 反过来回车必须**保留**粘滞 Ctrl 守卫：Ctrl 点亮时的回车语义是 Ctrl+Enter，
 * 不该被「提交提问」那套逻辑接管。
 */
class HardwareKeyBufferGuardTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（与 TmuxConfAppendTest 同一约定）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun source(): String {
        val f = appFile("src/main/java/com/example/zhengdao/TerminalActivity.kt")
        assertTrue("找不到 TerminalActivity.kt：${f.path}", f.isFile)
        return f.readText()
    }

    private val enterMark = "KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->"
    private val delMark = "KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->"

    @Test
    fun `硬件退格分支必须喂行缓冲_且不得含 stickyCtrl`() {
        val src = source()
        val del = src.indexOf(delMark)
        assertTrue("源码里找不到硬件退格的 keyCode 分支（锚点变了就更新本测试）", del >= 0)
        val end = src.indexOf("\n            }", del)
        assertTrue("退格分支之后找不到 when 的收尾（锚点变了就更新本测试）", end > del)
        val branch = src.substring(del, end)
        assertTrue("退格没有镜像行缓冲（缺 KnowledgeBaseHints.onUserBackspace()）：\n$branch",
            branch.contains("KnowledgeBaseHints.onUserBackspace()"))
        assertFalse("退格又被包回粘滞 Ctrl 守卫里了（审计 C4 会复现）：\n$branch",
            branch.contains("stickyCtrl"))
    }

    @Test
    fun `硬件回车分支必须保留 stickyCtrl 守卫`() {
        val src = source()
        val enter = src.indexOf(enterMark)
        val del = src.indexOf(delMark)
        assertTrue("源码里找不到硬件回车的 keyCode 分支（锚点变了就更新本测试）", enter >= 0)
        assertTrue("退格分支应当在回车分支之后（锚点变了就更新本测试）", del > enter)
        val branch = src.substring(enter, del)
        assertTrue("回车丢了粘滞 Ctrl 守卫（Ctrl 点亮时会被当成提问提交）：\n$branch",
            branch.contains("!stickyCtrl"))
        assertTrue("回车分支应当仍走 onUserCodePoint：\n$branch",
            branch.contains("KnowledgeBaseHints.onUserCodePoint("))
    }
}
