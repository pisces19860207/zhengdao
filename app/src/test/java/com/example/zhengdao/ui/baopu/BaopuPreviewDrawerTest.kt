// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 刀 4-B 的两个零件函数**接到抽屉里**（第二十九批追加）。
 *
 * ## 为什么这批值得一组测试
 *
 * 刀 4-B 交付时 `previewLastReply`／`clearPreviewCache` **一个调用方都没有**（那是刻意的，
 * 抽屉要重排成工作区树）。结果就是：函数写得再好，用户在界面上点不到 —— 这类「零件有了，
 * 但没人接线」的缺口在账上只能靠翻代码发现。
 *
 * 所以这里钉两层：
 * - **行为层**：挑「最近一条有正文的回复」是纯函数，喂脏数据看它挑得对不对；
 * - **接线层**：抽屉里真有两行、`BaopuScreen` 真的调了那两个函数（源码级）。
 */
class BaopuPreviewDrawerTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun read(rel: String): String = appFile(rel).readText()

    private fun msg(id: Long, role: String, vararg parts: BaopuUiPart) =
        BaopuUiMessage(id = id, role = role, parts = parts.toList())

    private fun text(t: String) = BaopuUiPart(kind = "text", text = t)

    private fun image(path: String) = BaopuUiPart(kind = "image", text = path)

    // ── 行为层：挑最近一条有正文的助手回复 ───────────────────────────────────

    @Test
    fun `P1_挑最近一条助手回复_不是最早那条`() {
        val msgs = listOf(
            msg(1, "user", text("你好")),
            msg(2, "assistant", text("第一版")),
            msg(3, "assistant", text("最新一版")),
        )
        assertEquals("最新一版", lastAssistantReply(msgs))
    }

    @Test
    fun `P2_纯工具轮不算回复_要往前找有正文的那条`() {
        val msgs = listOf(
            msg(1, "assistant", text("有正文的一条")),
            // 纯工具轮：只有 tool part，没有正文（捞这条去渲卡只会得到一张空卡）
            msg(2, "assistant", BaopuUiPart(kind = "tool", text = "{}", tool = "read_file")),
        )
        assertEquals("有正文的一条", lastAssistantReply(msgs))
    }

    @Test
    fun `P3_图片part不算正文_只有图的助手消息要跳过`() {
        val msgs = listOf(
            msg(1, "assistant", text("正文在")),
            msg(2, "assistant", image("附件/a.png")),
        )
        assertEquals("正文在", lastAssistantReply(msgs))
    }

    @Test
    fun `P4_一条回复里有多个text段_按顺序拼起来`() {
        val msgs = listOf(msg(1, "assistant", text("第一段"), text("第二段")))
        assertEquals("第一段\n\n第二段", lastAssistantReply(msgs))
    }

    @Test
    fun `P5_没有助手回复或全是空白_回null_不假装有`() {
        assertNull(lastAssistantReply(emptyList()))
        assertNull(lastAssistantReply(listOf(msg(1, "user", text("只有我说的话")))))
        assertNull(lastAssistantReply(listOf(msg(1, "assistant", text("   ")))))
    }

    // ── 行为层：那句提示怎么说 ───────────────────────────────────────────────

    @Test
    fun `P6_放好了必须说清切到玄览_不然用户以为点了没反应`() {
        val line = previewToastText(PreviewResult.Opened(url = "http://127.0.0.1:7000/p/x", id = "x", evicted = null))
        assertTrue("必须提到玄览：$line", line.contains("玄览"))
        assertTrue("槽满被挤掉时要说明：${PreviewNotice.opened("old")}", PreviewNotice.opened("old").contains("挤掉"))
    }

    @Test
    fun `P7_失败照搬原因_不改写不吞`() {
        val line = previewToastText(PreviewResult.Failed("预览服务起不来（端口被占）：7000=BindException"))
        assertTrue("失败原因要原样带出：$line", line.contains("7000=BindException"))
        assertTrue("0 张也要说话", PreviewNotice.cleared(0).contains("0 张"))
    }

    @Test
    fun `P8_没回复时那句话要在`() {
        assertTrue(PreviewNotice.NO_REPLY_YET.contains("还没有可预览"))
    }

    // ── 接线层：抽屉两行 ＋ 页面真调 ─────────────────────────────────────────

    @Test
    fun `P9_抽屉里真有两行_且都有回调`() {
        val drawer = read("src/main/java/com/example/zhengdao/ui/baopu/BaopuDrawer.kt")
        assertTrue("抽屉要有「预览这条回复」", drawer.contains("R.string.baopu_drawer_preview_last"))
        assertTrue("抽屉要有「清空预览缓存」", drawer.contains("R.string.baopu_drawer_preview_clear"))
        assertTrue("分组标题", drawer.contains("R.string.baopu_drawer_group_preview"))
        assertTrue("回调形参", drawer.contains("onPreviewLastReply: () -> Unit"))
        assertTrue("回调形参", drawer.contains("onPreviewClear: () -> Unit"))
        assertTrue("点了要接上回调", drawer.contains("onClick = onPreviewLastReply"))
        assertTrue("点了要接上回调", drawer.contains("onClick = onPreviewClear"))
    }

    @Test
    fun `P10_页面真的调了那两个零件函数_不是只有抽屉两行`() {
        val screen = read("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")
        assertTrue("必须调 previewLastReply", screen.contains("previewLastReply(reply)"))
        assertTrue("必须调 clearPreviewCache", screen.contains("clearPreviewCache()"))
        assertTrue("必须取最近一条回复", screen.contains("lastAssistantReply(controller.messages)"))
        assertTrue("没回复时要说", screen.contains("PreviewNotice.NO_REPLY_YET"))
        assertTrue("清空后要说张数", screen.contains("PreviewNotice.cleared(n)"))
    }

    @Test
    fun `P11_两个新键中英对称`() {
        val zh = read("src/main/res/values/strings.xml")
        val en = read("src/main/res/values-en/strings.xml")
        listOf(
            "baopu_drawer_group_preview",
            "baopu_drawer_preview_last",
            "baopu_drawer_preview_last_desc",
            "baopu_drawer_preview_clear",
            "baopu_drawer_preview_clear_desc",
        ).forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
}
