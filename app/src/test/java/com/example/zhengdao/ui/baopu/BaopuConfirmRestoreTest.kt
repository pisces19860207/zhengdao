// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片）：BaopuConfirmRestore 的 JVM 单测 ＋ 源码门禁。
//
// 盯的是 T3 第一半：进程被杀后重进，那张卡必须还能立起来（参数坏了也得立）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.PendingRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuConfirmRestoreTest {

    private fun controller(): String {
        val f = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 BaopuChatController.kt")
        return f.readText()
    }

    @Test
    fun `C1_三格都按库行合成_callId稳定可复现`() {
        val row = PendingRow(
            tool = "write_file",
            argsJson = "{\"path\":\"资料库/整理/a.txt\",\"content\":\"一\\n二\\n\"}",
            at = 1_726_000_000_000L,
        )
        val card = BaopuConfirmRestore.card(row)
        assertEquals("restored-1726000000000", card.callId)
        assertEquals("write_file", card.tool)
        assertEquals("argsJson 必须原样带回（批准时要按它执行）", row.argsJson, card.argsJson)
        assertTrue("摘要要写出路径", card.summary.contains("资料库/整理/a.txt"))
        assertEquals("同一行两次合成必须一模一样", card, BaopuConfirmRestore.card(row))
    }

    @Test
    fun `C2_参数坏JSON也出卡_摘要兜底不抛`() {
        val row = PendingRow(tool = "edit_file", argsJson = "{半截", at = 42L)
        val card = BaopuConfirmRestore.card(row)
        assertEquals("restored-42", card.callId)
        assertEquals("坏参数也必须给一句摘要（卡是唯一入口）", "编辑 （未给路径）", card.summary)
        assertEquals("原样带回", "{半截", card.argsJson)
    }

    @Test
    fun `C3_未知工具名照样出卡`() {
        val card = BaopuConfirmRestore.card(PendingRow(tool = "some_tool", argsJson = "{}", at = 7L))
        assertEquals("restored-7", card.callId)
        assertTrue("未知工具也要有摘要（含工具名）", card.summary.startsWith("some_tool"))
    }

    @Test
    fun `G1_控制器只剩调用_合成判据不留第二份`() {
        val c = controller()
        assertTrue(c.contains("BaopuConfirmRestore.card(row)"))
        assertTrue("callId 前缀只许在 helper 里", !c.contains("\"restored-"))
    }
}
