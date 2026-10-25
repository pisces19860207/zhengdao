// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第一百七十六批 §2 · 运行中插话的 UI 接线守卫（源码/资源文本级，先剥注释再断言）。
 *
 * 判据（派工单 §2/§3，UI 半）：
 * ① 运行中输入框**保持可用**（不禁用）；发送 ≠ 停止（停止按钮照旧在）；
 * ② 运行中**纯文字**发送 ⇒ 走引擎插话（下一安全点生效），带附件仍进 117 的回合队列；
 * ③ 待生效插话**可见**（状态句＋chip）且**可撤销**（× 纯撤／点文本撤回输入框改）；
 * ④ 中英字符串双气（读屏与英文环境跟着走）。
 */
class BaopuInterjectWiringTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun composer() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private fun controller() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")

    private fun screen() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    @Test
    fun `composer收插话态与撤销回调`() {
        val src = composer()
        assertTrue("待生效列表参数", src.contains("interjections: List<String> = emptyList()"))
        assertTrue("撤销回调参数", src.contains("onRevokeInterject: (String) -> Unit = {}"))
        assertTrue("chip 组件在场", src.contains("private fun InterjectChip("))
    }

    @Test
    fun `待生效行有状态句与chip_且可读屏`() {
        val src = composer()
        assertTrue("状态句用复数串（中英都由资源出）", src.contains("R.plurals.baopu_interject_pending"))
        assertTrue("点 chip 文本 ＝ 撤回输入框（「可编辑」那一半）", src.contains("onEdit"))
        assertTrue("× 有读屏名", src.contains("baopu_interject_revoke"))
        assertTrue("点 chip 有读屏名", src.contains("baopu_interject_edit"))
    }

    @Test
    fun `运行中纯文字按钮变插话_带附件仍排队`() {
        val src = composer()
        assertTrue("插话按钮串", src.contains("R.string.baopu_send_interject"))
        assertTrue(
            "判定：跑动＋无附件 ⇒ 插话",
            src.contains("busy && attachList.isEmpty() -> R.string.baopu_send_interject"),
        )
        assertTrue("跑动＋带附件 ⇒ 仍排队（117 口径不回归）", src.contains("busy -> R.string.baopu_send_queue"))
    }

    @Test
    fun `输入框运行中不禁用_停止按钮仍在`() {
        val src = composer()
        val i = src.indexOf("OutlinedTextField(")
        assertTrue(i >= 0)
        val args = src.substring(i, src.indexOf("maxLines = 4", i))
        assertFalse("输入框任何时刻都可用（运行中插话的前提）", args.contains("enabled"))
        assertTrue("停止按钮照旧（三态按钮的无草稿态）", src.contains("onClick = { onStop() }"))
    }

    @Test
    fun `控制器接线_运行中纯文字走插话`() {
        val src = controller()
        assertTrue("只纯文字走插话（带附件仍进回合队列）", src.contains("attachments.isEmpty() && engine.interject(text)"))
        assertTrue(
            "插话被接收 ⇒ 直接返回 accepted（不进回合队列/题材记账）",
            src.contains("if (attachments.isEmpty() && engine.interject(text)) return true"),
        )
    }

    @Test
    fun `控制器按会话过滤并直通撤销`() {
        val src = controller()
        assertTrue("按可见会话过滤（停了 A 去看 B，B 不显示 A 的话）", src.contains("engine.interjectionsFor(sessionId"))
        assertTrue("撤销直通引擎", src.contains("engine.revokeInterjection(text)"))
    }

    @Test
    fun `屏幕把插话态接进composer`() {
        val src = screen()
        assertTrue("传待生效列表", src.contains("interjections = controller.interjections"))
        assertTrue("传撤销回调", src.contains("onRevokeInterject = { controller.revokeInterject(it) }"))
    }

    @Test
    fun `字符串中英双气`() {
        val keys = listOf(
            "baopu_send_interject",
            "baopu_interject_pending",
            "baopu_interject_revoke",
            "baopu_interject_edit",
        )
        keys.forEach { k ->
            assertTrue("中文缺 $k", code("src/main/res/values/strings.xml").contains("name=\"$k\""))
            assertTrue("英文缺 $k", code("src/main/res/values-en/strings.xml").contains("name=\"$k\""))
        }
    }
}
