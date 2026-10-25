// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百九十批 · 题材与写法卡的**接线与红线**门禁。
 *
 * 纯判据在 `baopu/TopicSuggestTest.kt`；这里钉五条"判据对、但别在接线上走样"的红线：
 * ① 触发词在 send() 里**先于** engine.send 拦下（不进引擎、确认前零落盘）；
 * ② 控制器里 BaopuLongformStore.save 只出现在确认之后的批量产出段（复用 179，不另造路径）；
 * ③ 三张卡长在既有 BaopuActionContainer 里（不新增第五种卡）、不弹 Dialog 挡输入（并 164）；
 * ④ 卡渲染位置在 BaopuComposer **之前**（Column 布局 ⇒ 物理上不遮输入框）；
 * ⑤ 界面文案中英各一份（strings.xml 两份同键同数）。
 */
class BaopuTopicFlowGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `红线-a 触发词先于引擎拦截_不进引擎`() {
        val c = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        val interceptAt = c.indexOf("TopicSuggest.isTopicRequest(text)")
        val engineSendAt = c.indexOf("engine.send(sid, text")
        assertTrue("send() 里必须有触发词拦截", interceptAt >= 0)
        assertTrue("engine.send(sid, text…", engineSendAt >= 0)
        assertTrue(
            "拦截必须在 engine.send 之前（先出卡，确认前不进引擎）",
            interceptAt < engineSendAt,
        )
    }

    @Test
    fun `红线-b 落盘只走179的出口_且只在确认之后`() {
        val c = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        // 落盘/台账只复用 179：不许出现第二套写路径
        assertTrue("必须调 BaopuLongformStore.save（179 已合入的出口）", c.contains("BaopuLongformStore.save"))
        assertTrue("台账/落点选择必须走 BaopuLongformPolicy.pickLedger", c.contains("BaopuLongformPolicy.pickLedger"))
        assertEquals("BaopuLongformStore.save 全文件只许出现一次（只在批量产出段）", 1, Regex("BaopuLongformStore\\.save").findAll(c).count())

        // save 必须在「就按这些写」之后（确认前零落盘的结构保证）
        val confirmAt = c.indexOf("fun topicConfirmWrite()")
        val saveAt = c.indexOf("BaopuLongformStore.save")
        assertTrue("找不到 topicConfirmWrite", confirmAt >= 0)
        assertTrue("save 必须出现在 topicConfirmWrite 之后（确认前零落盘）", confirmAt in 0 until saveAt)

        // 出卡与换批只读不写：topicStart/topicChangeBatch 两段里不许出现任何写盘调用
        fun bodyOf(marker: String): String {
            val at = c.indexOf(marker)
            assertTrue("找不到 $marker", at >= 0)
            val next = c.indexOf("\n    fun ", at + marker.length).let { if (it < 0) c.length else it }
            return c.substring(at, next)
        }
        listOf("fun topicStart()", "fun topicChangeBatch()").forEach { m ->
            val body = bodyOf(m)
            assertFalse("$m 里不许落盘（确认前零落盘）", body.contains("BaopuLongformStore.save"))
            assertFalse("$m 里不许写台账", body.contains("appendLedger"))
        }
    }

    @Test
    fun `红线-c 不新增第五种卡_不用Dialog挡输入`() {
        val topic = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuTopicCard.kt")
        val style = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuStyleCard.kt")
        listOf(topic to "BaopuTopicCard.kt", style to "BaopuStyleCard.kt").forEach { (s, name) ->
            assertTrue("$name 必须长在 BaopuActionContainer（既有四档容器）里", s.contains("BaopuActionContainer"))
            assertFalse("$name 不许弹 Dialog（并 164：卡内联在输入栏上方）", s.contains("Dialog("))
            assertFalse("$name 不许自造底色分卡", s.contains("Surface("))
        }
        // 主按钮触控达标（与 179 大按钮同一口径）
        assertTrue("就按这些写大按钮必须 ≥56dp", topic.contains("heightIn(min = 56.dp)"))
    }

    @Test
    fun `红线-d 卡渲染在输入框上方_物理不遮输入框`() {
        val screen = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        val cardAt = screen.indexOf("controller.topicFlow?.let")
        val composerAt = screen.indexOf("BaopuComposer(")
        assertTrue("BaopuScreen 必须渲染三卡（controller.topicFlow）", cardAt >= 0)
        assertTrue("找不到 BaopuComposer(", composerAt >= 0)
        assertTrue(
            "三卡必须在 BaopuComposer 之前（同一 Column ⇒ 卡在输入框上方，键盘顶起不遮挡）",
            cardAt in 0 until composerAt,
        )
    }

    @Test
    fun `红线-e 收尾对账接线与本地偏好不联网`() {
        val c = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        // Settled 钩子：批量产出必须挂在权威收尾信号上
        assertTrue("Settled 段必须调 topicBatchOnSettled()", c.contains("topicBatchOnSettled()"))
        // 写法偏好记在本地 SharedPreferences（不联网、不进台账）
        assertTrue("上次成功那套必须记本地偏好", c.contains("getSharedPreferences(\"baopu-topic\""))
        assertTrue("整批有成功才写偏好", c.contains("putString(\"style_last\""))
    }

    @Test
    fun `红线-f 界面文案中英各一份`() {
        val zh = src("src/main/res/values/strings.xml")
        val en = src("src/main/res/values-en/strings.xml")
        val zhCount = Regex("name=\"baopu_topic_[^\"]+\"").findAll(zh).count()
        val enCount = Regex("name=\"baopu_topic_[^\"]+\"").findAll(en).count()
        assertTrue("中文 strings 必须有 baopu_topic_* 键", zhCount > 0)
        assertEquals("中英键数必须一致（§3.1 双语）", zhCount, enCount)
    }
}
