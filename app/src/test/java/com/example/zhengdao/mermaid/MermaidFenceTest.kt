// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.mermaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 637 · 围栏识别（[MermaidFence]）的纯函数门禁。
 *
 * 这一层判错有两个方向，都会骗人：
 * - **该认的没认**：图当普通文字显示，用户看不到按钮（功能像没做）；
 * - **不该认的认了**：普通代码块被当成图，"渲染成图"必然报错（假故障）。
 * 所以每条用例都成对写：认的正例 + 不认的负例。
 *
 * 另外三条硬口径单独钉死：
 * - 20KB 是**字节**不是字符（中文一个字三字节，按字数算会放行三倍量）；
 * - 超限**不截断**（截断＝残图，见文件头）；
 * - 没有围栏时返回的文本必须**逐字相同**（既有渲染路径靠这条保证零扰动）。
 */
class MermaidFenceTest {

    private fun split(text: String) = MermaidFence.split(text)

    // ── 一、认与不认 ─────────────────────────────────────────────────────────

    @Test
    fun `没有围栏时原样返回单段文本`() {
        val text = "先看结论：\n再讲理由。\n\n```kotlin\nval a = 1\n```\n就这样。"
        val s = split(text)
        assertFalse(s.hasDiagram)
        assertEquals(1, s.segments.size)
        assertEquals(text, (s.segments[0] as MermaidFence.Segment.Text).text)
    }

    @Test
    fun `只有一张图时切成单段图`() {
        val code = "flowchart TD\n  A[开始] --> B[结束]"
        val s = split("```mermaid\n$code\n```")
        assertTrue(s.hasDiagram)
        assertEquals(1, s.segments.size)
        val d = s.segments[0] as MermaidFence.Segment.Diagram
        assertEquals(code, d.code)
        assertEquals(MermaidKind.FLOWCHART, d.kind)
        assertFalse(d.overLimit)
    }

    @Test
    fun `前后都有话时三段且顺序不乱`() {
        val s = split("上面这句。\n\n```mermaid\nsequenceDiagram\n  A->>B: 你好\n```\n\n下面这句。")
        assertEquals(3, s.segments.size)
        assertEquals("上面这句。\n", (s.segments[0] as MermaidFence.Segment.Text).text)
        assertEquals(MermaidKind.SEQUENCE, (s.segments[1] as MermaidFence.Segment.Diagram).kind)
        assertEquals("\n下面这句。", (s.segments[2] as MermaidFence.Segment.Text).text)
    }

    @Test
    fun `波浪线围栏也认`() {
        val s = split("~~~mermaid\nstateDiagram-v2\n  [*] --> A\n~~~")
        assertTrue(s.hasDiagram)
        assertEquals(MermaidKind.STATE, (s.segments[0] as MermaidFence.Segment.Diagram).kind)
    }

    @Test
    fun `其它语言标签不认`() {
        for (lang in listOf("kotlin", "bash", "json", "")) {
            val text = "```$lang\nflowchart TD\n  A-->B\n```"
            val s = split(text)
            assertFalse("语言标签 '$lang' 不该被认成图", s.hasDiagram)
            assertEquals(text, (s.segments[0] as MermaidFence.Segment.Text).text)
        }
    }

    @Test
    fun `语言标签带参数也认`() {
        val s = split("```mermaid theme=dark\nflowchart TD\n  A-->B\n```")
        assertTrue(s.hasDiagram)
    }

    @Test
    fun `未闭合围栏把剩余全部算作图`() {
        val s = split("开头\n```mermaid\nflowchart TD\n  A-->B")
        assertEquals(2, s.segments.size)
        assertEquals("开头\n", (s.segments[0] as MermaidFence.Segment.Text).text)
        assertEquals("flowchart TD\n  A-->B", (s.segments[1] as MermaidFence.Segment.Diagram).code)
    }

    @Test
    fun `收尾围栏更长也认 收尾带文字不闭合`() {
        val ok = split("````mermaid\nflowchart TD\n  A-->B\n`````")
        assertTrue(ok.hasDiagram)
        assertEquals("flowchart TD\n  A-->B", (ok.segments[0] as MermaidFence.Segment.Diagram).code)

        val notClosed = split("```mermaid\nflowchart TD\n``` 这里还有字")
        val d = notClosed.segments[0] as MermaidFence.Segment.Diagram
        assertEquals("flowchart TD\n``` 这里还有字", d.code)
    }

    @Test
    fun `两段图连续出现互不吞并`() {
        val s = split("```mermaid\nflowchart TD\n  A-->B\n```\n中间\n```mermaid\nsequenceDiagram\n  A->>B: x\n```")
        assertEquals(3, s.segments.size)
        assertEquals(MermaidKind.FLOWCHART, (s.segments[0] as MermaidFence.Segment.Diagram).kind)
        assertEquals("中间\n", (s.segments[1] as MermaidFence.Segment.Text).text)
        assertEquals(MermaidKind.SEQUENCE, (s.segments[2] as MermaidFence.Segment.Diagram).kind)
    }

    // ── 二、20KB 是字节，超限不截断 ──────────────────────────────────────────

    @Test
    fun `ASCII 恰好 20480 字节放行 20481 字节拒绝`() {
        val head = "flowchart TD\n"
        val headBytes = head.toByteArray(Charsets.UTF_8).size
        // 正好 20480 字节（按 UTF-8 算，不按字符算）
        val exact = head + "x".repeat(MermaidFence.MAX_CODE_BYTES - headBytes)
        assertEquals(
            MermaidFence.MAX_CODE_BYTES.toLong(),
            exact.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        val d1 = split("```mermaid\n$exact\n```").segments[0] as MermaidFence.Segment.Diagram
        assertEquals(MermaidFence.MAX_CODE_BYTES, d1.bytes)
        assertFalse(d1.overLimit)

        val over = exact + "x" // 20481 字节
        val d2 = split("```mermaid\n$over\n```").segments[0] as MermaidFence.Segment.Diagram
        assertEquals(MermaidFence.MAX_CODE_BYTES + 1, d2.bytes)
        assertTrue(d2.overLimit)
        // 不截断：超限的代码仍然是完整原文（拒绝渲染，但用户看得见自己写了什么）
        assertEquals(over, d2.code)
    }

    @Test
    fun `中文按字节算 7000 个汉字就超限`() {
        val code = "flowchart TD\n" + "节点".repeat(3500) // 7000 字 × 3 字节 = 21000
        val d = split("```mermaid\n$code\n```").segments[0] as MermaidFence.Segment.Diagram
        assertTrue("按字符数算是 7011，按字节算是 21011 —— 必须按字节拒", d.overLimit)
        assertEquals(21000 + "flowchart TD\n".length, d.bytes)
    }

    // ── 三、图类型（只用于显示与文件命名）─────────────────────────────────────

    @Test
    fun `四类声明各判各的`() {
        assertEquals(MermaidKind.FLOWCHART, MermaidFence.kindOf("graph LR\n A-->B"))
        assertEquals(MermaidKind.FLOWCHART, MermaidFence.kindOf("flowchart-elk TD\n A-->B"))
        assertEquals(MermaidKind.SEQUENCE, MermaidFence.kindOf("\n\nsequenceDiagram\n A->>B: x"))
        assertEquals(MermaidKind.STATE, MermaidFence.kindOf("stateDiagram\n [*] --> A"))
        assertEquals(MermaidKind.OTHER, MermaidFence.kindOf("pie title 占比\n \"a\" : 1"))
        assertEquals(MermaidKind.OTHER, MermaidFence.kindOf(""))
    }

    @Test
    fun `注释行跳过后再认类型`() {
        assertEquals(
            MermaidKind.FLOWCHART,
            MermaidFence.kindOf("%%{init: {'theme':'dark'}}%%\n%% 这是注释\nflowchart TD\n A-->B"),
        )
    }

    // ── 四、换行风格 ────────────────────────────────────────────────────────

    @Test
    fun `CRLF 正文也能认出来`() {
        val s = split("话\r\n```mermaid\r\nflowchart TD\r\n  A-->B\r\n```\r\n尾")
        assertTrue(s.hasDiagram)
        assertTrue(s.segments.size >= 2)
    }
}
