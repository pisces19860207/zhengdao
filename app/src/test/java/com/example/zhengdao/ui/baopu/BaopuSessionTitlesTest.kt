// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片）：BaopuSessionTitles 的 JVM 单测 ＋ 源码门禁。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuSessionTitlesTest {

    private fun entry(id: Long, title: String) =
        BaopuSessionEntry(id = id, title = title, rounds = 1, updatedAt = id, closedAt = null, workspace = null)

    private fun controller(): String {
        val f = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"),
        ).firstOrNull { it.isFile } ?: error("找不到 BaopuChatController.kt")
        return f.readText()
    }

    @Test
    fun `T1_空标题取首条用户消息前20字并去空白`() {
        val rows = listOf(entry(1, "  "), entry(2, "已有标题"))
        val out = BaopuSessionTitles.withFallback(rows, mapOf(1L to "  一二三四五六七八九十一二三四五六七八九十超额  "))
        assertEquals("一二三四五六七八九十一二三四五六七八九十", out[0].title)
        assertEquals("有标题的一行原样不动", "已有标题", out[1].title)
    }

    @Test
    fun `T2_取不到首条正文就留空_不编名字`() {
        val out = BaopuSessionTitles.withFallback(listOf(entry(3, "")), emptyMap())
        assertEquals("", out[0].title)
    }

    @Test
    fun `T3_只有空白标题才算空_有标题连对象都不换`() {
        val withTitle = entry(4, "真标题")
        val out = BaopuSessionTitles.withFallback(listOf(withTitle, entry(5, "   ")), mapOf(5L to "补的"))
        assertSame("非空标题不许 copy 出新对象", withTitle, out[0])
        assertEquals("补的", out[1].title)
    }

    @Test
    fun `T4_blankIds只挑空白标题且保序`() {
        val rows = listOf(entry(7, ""), entry(8, "有"), entry(9, "  "), entry(10, ""))
        assertEquals(listOf(7L, 9L, 10L), BaopuSessionTitles.blankIds(rows))
        assertTrue("全有标题 ⇒ 不需要补查", BaopuSessionTitles.blankIds(listOf(entry(1, "x"))).isEmpty())
    }

    @Test
    fun `G1_控制器只剩调用_兜底判据不留第二份`() {
        val c = controller()
        assertTrue(c.contains("BaopuSessionTitles.blankIds(rows)"))
        assertTrue(c.contains("BaopuSessionTitles.withFallback(rows, readFirstUserTexts(blankIds))"))
        assertTrue("20 字截断只许在 helper 里", !c.contains(".take(20)"))
    }
}
