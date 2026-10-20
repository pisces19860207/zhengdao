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

    /** 第六十三批那个通知位组件（错误便签的渲染点搬这儿来了）。 */
    private val slot: String
        get() = repoFile("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeSlot.kt").readText()

    @Test
    fun error_note_has_own_label_and_error_tone() {
        // 2026-10-20 第六十三批：三件事一件没少，只是**分工**变了——
        // 「自己的标题」仍长在屏里那条 entry 上（文案跟着数据来源走），
        // 「错误色＋不占地方」改由通知位统一兑现：错误档 errorContainer 底色，
        // 行高由 36–40dp 那两个令牌钉住（原来靠 compact=true 压，现在整条只有一行，比 compact 更矮）。
        val call = screen.substringAfter("controller.pendingNote?.let { note ->").take(400)
        assertTrue("错误便签要有自己的标题：$call", call.contains("memory_write_failed_label"))
        assertTrue("要交进通知位（不再自己占一行）：$call", call.contains("BaopuNoticeEntry("))
        val err = slot.substringAfter("if (ordered.isEmpty()) return")
        assertTrue("错误档要用 errorContainer 那一型：$err", err.contains("MaterialTheme.colorScheme.errorContainer"))
        assertTrue("详情卡仍走 isError = true 那个组件：", slot.contains("isError = true"))
        assertTrue("行高有上限，不许长成第二块正文：", slot.contains("ROW_HEIGHT_MAX = 40.dp"))
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
