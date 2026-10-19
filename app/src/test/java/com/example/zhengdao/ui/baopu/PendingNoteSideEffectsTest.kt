// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19：50 批副作用修正——错误便签要有自己的标题/错误色/紧凑高度，
// 且换会话要清掉（原来只靠 UI 8 秒计时器兜底，计时器删了它就跨会话留着）。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PendingNoteSideEffectsTest {

    private fun repoFile(rel: String): File {
        val direct = File(rel)
        if (direct.isFile) return direct
        return File("../$rel")
    }

    private val screen: String
        get() = repoFile("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt").readText()

    private val card: String
        get() = repoFile("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuMemoryCard.kt").readText()

    private val controller: String
        get() = repoFile("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()

    @Test
    fun error_note_has_own_label_and_error_tone() {
        val call = screen.substringAfter("controller.pendingNote?.let { note ->").take(400)
        assertTrue("错误便签要有自己的标题：$call", call.contains("memory_write_failed_label"))
        assertTrue("要用错误色：$call", call.contains("isError = true"))
        assertTrue("要压紧凑：$call", call.contains("compact = true"))
    }

    @Test
    fun component_defaults_keep_old_callers_unchanged() {
        val fn = card.substringAfter("internal fun BaopuIntrospectionNote(").substringBefore(") {")
        assertTrue("label 默认必须为空（老调用点仍用「这次学到」）", fn.contains("label: String? = null"))
        assertTrue(fn.contains("isError: Boolean = false"))
        assertTrue(fn.contains("compact: Boolean = false"))
        assertTrue(
            "只有 isError 才切错误色",
            card.contains("if (isError) com.example.zhengdao.ui.theme.zhengdaoErrorText()"),
        )
    }

    @Test
    fun switching_sessions_clears_pending_note() {
        // 50 批遗留：newSession / openSession / clearSessionView 三处都要清
        val count = Regex("pendingNote = null").findAll(controller).count()
        assertTrue("三处会话切换都要清 pendingNote（现在 $count 处）", count >= 3)
        val pendingBlock = screen.substringAfter("controller.pendingNote?.let { note ->").take(400)
        assertFalse("pendingNote 自己不许再挂 8 秒计时器", pendingBlock.contains("delay("))
    }
}
