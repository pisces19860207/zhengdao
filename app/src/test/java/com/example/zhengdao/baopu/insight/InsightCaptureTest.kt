// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 判据①⑤：capture 带原文锚点 + 不替人下结论。
package com.example.zhengdao.baopu.insight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InsightCaptureTest {

    private fun msg(id: Long, text: String, role: String = "user") =
        TurnMessage(id, 1L, role, text, 1_000L)

    @Test
    fun `判据1_抓到的念头逐字含原文且带锚点`() {
        val raw = "我在想也许可以做一个灵感捕获的功能。"
        val out = captureTurn(listOf(msg(7L, raw)), nowMs = 5_000L, workspace = "ws")
        assertEquals(1, out.thoughts.size)
        val t = out.thoughts.single()
        assertEquals(raw, t.body)
        val a = t.anchor as InsightAnchor.Message
        assertEquals(7L, a.messageId)
        assertEquals("msg:7", a.ref)
        assertEquals(raw, a.quote)
        assertTrue(t.body.contains(a.quote))
    }

    @Test
    fun `判据1_无锚点的消息被跳过并说原因`() {
        val out = captureTurn(listOf(msg(0L, "想做一个东西。")), nowMs = 5_000L, workspace = "ws")
        assertTrue(out.thoughts.isEmpty())
        assertTrue(out.skipped.single().contains("无锚点"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `判据1_负例_无锚点造不出念头_构造期炸`() {
        CapturedThought("x", "想做一件事", InsightAnchor.Message(0L, 1L, ""), 0L, "ws")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `判据1_负例_锚点引不存在的原文_构造期炸`() {
        CapturedThought("x", "想做一件事", InsightAnchor.Message(9L, 1L, "另一句"), 0L, "ws")
    }

    @Test
    fun `判据1_机器的消息不抓_只抓用户原话`() {
        val out = captureTurn(listOf(msg(1L, "你想试试吧。", role = "assistant")), 5_000L, "ws")
        assertTrue(out.thoughts.isEmpty())
    }

    @Test
    fun `判据1_没落地才抓_已完成的句子不抓`() {
        val out = captureTurn(listOf(msg(1L, "我已经做完了那个功能。")), 5_000L, "ws")
        assertTrue(out.thoughts.isEmpty())
    }

    @Test
    fun `判据5_替人下结论的模板被禁词咬住`() {
        assertTrue(InsightCaptureRules.preaches("你应该去看看那条念头"))
        assertTrue(InsightCaptureRules.preaches("建议你记一下"))
        assertTrue(InsightCaptureRules.preaches("最好现在就做"))
        assertFalse(InsightCaptureRules.preaches("之前记下的一个念头：X（依据：msg:1）· 要看吗？"))
    }

    @Test
    fun `判据1_落盘读回往返_坏行带计数`() {
        val f = File(System.getProperty("java.io.tmpdir"), "insight-store-${System.nanoTime()}.jsonl")
        f.deleteOnExit()
        val store = InsightStore(f)
        val t = CapturedThought(
            "insight-1", "我在想也许做一个功能。",
            InsightAnchor.Message(7L, 1L, "我在想也许做一个功能。"), 1_000L, "ws",
        )
        assertTrue(store.append(t))
        f.appendText("{坏行\n") // 直接落一行坏 JSON，测 scan 的坏行计数
        val scan = store.scan()
        assertEquals(1, scan.thoughts.size)
        assertEquals(1, scan.broken)
        assertEquals(t.body, scan.thoughts.single().body)
        assertEquals("msg:7", scan.thoughts.single().anchor.ref)
    }
}
