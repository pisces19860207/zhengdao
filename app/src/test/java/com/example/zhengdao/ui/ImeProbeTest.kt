package com.example.zhengdao.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入回归页的判定逻辑单测（Issue #2）。
 *
 * 这里只测**纯逻辑**：真机上的 IME 组合输入要靠人敲（回归页负责把它变成 30 秒的事），
 * 但「什么叫通过、什么叫重复上屏、读缓冲哪一行」这些口径必须可回归。
 */
class ImeProbeTest {

    @After
    fun tearDown() = ImeProbe.clear()

    @Test
    fun `三个场景覆盖 issue 要求的三种情形`() {
        assertEquals(3, ImeProbe.SCENARIOS.size)
        val ids = ImeProbe.SCENARIOS.map { it.id }
        assertEquals(listOf("compose", "delete", "rapid"), ids)
        ImeProbe.SCENARIOS.forEach {
            assertTrue("场景 ${it.id} 的期望串不能为空", it.expected.isNotBlank())
            assertTrue("场景 ${it.id} 要有操作脚本", it.script.isNotBlank())
        }
    }

    @Test
    fun `上屏与期望一致算通过`() {
        val v = ImeProbe.verdict("你好", "你好")
        assertEquals(ImeProbe.Status.PASS, v.status)
    }

    @Test
    fun `忽略空白与换行`() {
        assertEquals(ImeProbe.Status.PASS, ImeProbe.verdict("你好", " 你 好 \n").status)
    }

    @Test
    fun `空输入是待输入而不是失败`() {
        val v = ImeProbe.verdict("你好", "   ")
        assertEquals(ImeProbe.Status.EMPTY, v.status)
    }

    @Test
    fun `同一串被送两遍判重复上屏`() {
        val v = ImeProbe.verdict("你好", "你好你好")
        assertEquals(ImeProbe.Status.FAIL, v.status)
        assertTrue(v.detail.contains("重复上屏"))
    }

    @Test
    fun `少字多字串字分别给出可读结论`() {
        assertTrue(ImeProbe.verdict("你好吗", "你好").detail.contains("少字"))
        assertTrue(ImeProbe.verdict("你好", "你好吗").detail.contains("多字"))
        assertTrue(ImeProbe.verdict("你好", "你号").detail.contains("串字"))
    }

    @Test
    fun `取终端缓冲最后一行非空文本`() {
        assertEquals("b", ImeProbe.lastNonEmptyLine("a\nb   \n\n  \n"))
        assertEquals("你好世界测试", ImeProbe.lastNonEmptyLine("old\n你好世界测试"))
        assertEquals("", ImeProbe.lastNonEmptyLine("\n\n"))
    }

    @Test
    fun `相邻相同提交被认出是重复插入`() {
        val lines = listOf(
            "10:00:00.100 setComposingText 你好",
            "10:00:00.400 commitText 你好",
            "10:00:00.500 commitText 你好",
            "10:00:01.000 commitText 世界",
        )
        assertEquals(listOf("你好"), ImeProbe.duplicateCommits(lines))
        assertEquals(emptyList<String>(), ImeProbe.duplicateCommits(listOf("commitText a", "commitText b")))
    }

    @Test
    fun `事件日志有上限且可清空`() {
        repeat(ImeProbe.MAX_EVENTS + 50) { ImeProbe.record("commitText", "x$it") }
        val snap = ImeProbe.snapshot()
        assertEquals(ImeProbe.MAX_EVENTS, snap.size)
        // 保留的是最新的那一批
        assertTrue(snap.last().contains("x${ImeProbe.MAX_EVENTS + 49}"))
        ImeProbe.clear()
        assertEquals(0, ImeProbe.snapshot().size)
    }

    @Test
    fun `事件里的换行被转义成一行`() {
        ImeProbe.record("commitText", "a\nb")
        assertTrue(ImeProbe.snapshot().single().contains("a\\nb"))
    }

    @Test
    fun `词中删字符后重输的擦除空格不影响判定`() {
        // 终端把退掉的字擦成空格：最后一行是「你好 吗」，按忽略空白判就是期望的「你好吗」。
        assertEquals(ImeProbe.Status.PASS, ImeProbe.verdict("你好吗", "你好 吗").status)
        // 只退不补：少字
        assertEquals(ImeProbe.Status.FAIL, ImeProbe.verdict("你好吗", "你好 ").status)
        // 退格没生效：擦掉的字还在（多字）
        assertEquals(ImeProbe.Status.FAIL, ImeProbe.verdict("你好吗", "你好吗吗").status)
    }
}
