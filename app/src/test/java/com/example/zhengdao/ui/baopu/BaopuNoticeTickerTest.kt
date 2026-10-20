// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：2026-10-20 真机 UI-D1 —— 顶栏竖向跑马灯在「只有 1 条短标签」时把第二份标题
// 露了一截（视口 16dp ≈ 一行），显示成两行叠字；胶囊宽 198dp 而标签只有 4 个字。
// 修法＝单条不复制、不滚；两条以上才复制成首尾相接。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI-D1：跑马灯单条不复制。 */
class BaopuNoticeTickerTest {

    private fun item(label: String) = NoticeItem(label = label, body = "$label 的详情")

    private fun tickerSource(): String {
        val candidates = listOf(
            File("src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt"),
            File("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuNoticeTicker.kt"),
        )
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `D1_单条不复制_只摆一份`() {
        val one = listOf(item("这次学到"))
        assertEquals("单条不许复制（复制了第二份就露一截＝重影）", 1, tickerLoop(one).size)
        assertEquals("这次学到", tickerLoop(one).single().label)
    }

    @Test
    fun `D1_两条复制成首尾相接`() {
        val two = listOf(item("这次学到"), item("已压缩"))
        assertEquals(4, tickerLoop(two).size)
        assertEquals(
            listOf("这次学到", "已压缩", "这次学到", "已压缩"),
            tickerLoop(two).map { it.label },
        )
    }

    @Test
    fun `D1_空列表仍是空`() {
        assertTrue(tickerLoop(emptyList()).isEmpty())
    }

    @Test
    fun `D1_接线_组件用tickerLoop且单条不进滚动循环`() {
        val src = tickerSource()
        assertTrue(
            "组件必须通过 tickerLoop 决定摆几份（不许再直接 items + items）",
            src.contains("tickerLoop(items)"),
        )
        assertTrue(
            "不许退回无条件复制两份",
            !src.contains("{ items + items }"),
        )
        assertTrue(
            "单条时滚动循环必须提前返回",
            src.contains("if (items.size <= 1) return@LaunchedEffect"),
        )
    }
}
