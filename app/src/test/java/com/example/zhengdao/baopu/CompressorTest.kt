// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compressor 单测（刀 0b · 2026-10-16）。
 *
 * 覆盖派工单 §4.2（压缩提示词与结果解析）+ 追加二第 4 条（压缩通道定死：
 * 默认免费通道、不可用降级当前会话 provider 且用户知情、留痕写清用了谁）。
 */
class CompressorTest {

    private fun msg(id: String, role: String = HistoryMessage.ROLE_USER, text: String = "消息$id") =
        HistoryMessage(id = id, role = role, text = text)

    // ── 提示词构造 ────────────────────────────────────────────────────────

    @Test
    fun `压缩请求_系统提示列全字段_正文带全部待压消息`() {
        val req = Compressor.buildRequest(
            listOf(msg("m1", HistoryMessage.ROLE_USER, "你好"), msg("m2", HistoryMessage.ROLE_ASSISTANT, "再见")),
            Providers.BUILTIN_FREE,
        )
        assertEquals(2, req.size)
        assertEquals(LlmMessage.ROLE_SYSTEM, req[0].role)
        Compressor.SUMMARY_FIELDS.forEach { f ->
            assertTrue("系统提示必须列出字段「$f」", req[0].content.contains(f))
        }
        assertTrue(req[1].content.contains("【用户】你好"))
        assertTrue(req[1].content.contains("【抱朴】再见"))
        assertTrue(req[1].content.contains("2 条"))
    }

    // ── 结果解析 ──────────────────────────────────────────────────────────

    @Test
    fun `解析_结构化响应按字段归位`() {
        val resp = """
            目标：做一张角色卡
            已定的决定：三格式、触发词要真
            已排除的路：照搬旧模板
            未决：视觉方向
            涉及的文件路径：资料库/原始/设定.md
        """.trimIndent()
        val r = Compressor.parseSummary(resp, listOf("m1", "m2"), 1000L, "free-model")
        assertEquals("做一张角色卡", r.summary.fieldOf("目标"))
        assertEquals("三格式、触发词要真", r.summary.fieldOf("已定的决定"))
        assertEquals("资料库/原始/设定.md", r.summary.fieldOf("涉及的文件路径"))
        assertEquals(listOf("m1", "m2"), r.compressedIds)
        assertEquals(1000L, r.createdAt)
    }

    @Test
    fun `解析_缺字段按无_不抛`() {
        val r = Compressor.parseSummary("目标：只有一个字段", emptyList(), 1L, "m")
        Compressor.SUMMARY_FIELDS.forEach { f ->
            val line = r.summary.fieldOf(f)
            if (f == "目标") assertEquals("只有一个字段", line) else assertEquals("无", line)
        }
    }

    @Test
    fun `解析_续行拼回上一字段`() {
        val r = Compressor.parseSummary(
            "目标：做卡\n还要包含前后置词检查\n未决：视觉",
            emptyList(), 1L, "m",
        )
        assertEquals("做卡\n还要包含前后置词检查", r.summary.fieldOf("目标"))
        assertEquals("视觉", r.summary.fieldOf("未决"))
    }

    @Test
    fun `解析_完全不成表的响应也不丢_原文落目标字段`() {
        val r = Compressor.parseSummary("模型跑题输出的一大段话", emptyList(), 1L, "m")
        assertTrue(r.summary.contains("模型跑题输出的一大段话"))
    }

    @Test
    fun `解析_空响应全字段无_不抛`() {
        val r = Compressor.parseSummary("", emptyList(), 1L, "m")
        Compressor.SUMMARY_FIELDS.forEach { assertEquals("无", r.summary.fieldOf(it)) }
    }

    // ── 选区 ──────────────────────────────────────────────────────────────

    @Test
    fun `选区_只压中段旧段_最近四条不动`() {
        val h = (1..6).map { msg("m$it") }
        assertEquals(listOf("m1", "m2"), Compressor.selectCompressionRange(h).map { it.id })
        assertTrue(Compressor.selectCompressionRange(h.take(4)).isEmpty())
        assertTrue(Compressor.selectCompressionRange(emptyList()).isEmpty())
    }

    // ── 压缩通道（追加二第 4 条） ─────────────────────────────────────────

    @Test
    fun `通道_默认走内置免费通道`() {
        val session = Providers.OPENAI_COMPAT
        val c = Compressor.chooseChannel(session, freeAvailable = true)
        assertEquals(Providers.BUILTIN_FREE.id, c.provider.id)
        assertFalse(c.degraded)
        assertEquals("", c.reason)
    }

    @Test
    fun `通道_免费不可用降级当前会话provider且带理由`() {
        val session = Providers.ANTHROPIC
        val c = Compressor.chooseChannel(session, freeAvailable = false)
        assertEquals(session.id, c.provider.id)
        assertTrue("降级必须置位", c.degraded)
        assertTrue("降级必须带理由（用户知情的前提）", c.reason.contains("免费通道当前不可用"))
        assertTrue(c.reason.contains(session.model))
    }

    @Test
    fun `通道留痕_降级与默认都说清用了谁`() {
        val free = Compressor.chooseChannel(Providers.OPENAI_COMPAT, true)
        assertTrue(Compressor.channelNote(free).contains("免费通道"))
        val degraded = Compressor.chooseChannel(Providers.ANTHROPIC, false)
        val note = Compressor.channelNote(degraded)
        assertTrue(note.contains("降级"))
        assertTrue(note.contains("为什么") == false && note.contains("不可用"))
    }

    @Test
    fun `记录默认通道即免费通道id`() {
        val r = Compressor.parseSummary("目标：x", emptyList(), 1L, "m")
        assertEquals(Providers.BUILTIN_FREE.id, r.channel)
        assertFalse(r.degraded)
    }

    /**
     * 摘要里取一个字段的值（测试辅助）：从「字段：」行起，吃到下一个已知字段前。
     * 不能只取第一行——续行是拼回字段值的（模型爱把一条决定拆多行）。
     */
    private fun String.fieldOf(field: String): String {
        val lines = this.lines()
        val start = lines.indexOfFirst { it.startsWith("$field：") }
        if (start < 0) return ""
        val out = mutableListOf(lines[start].substringAfter("："))
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            if (Compressor.SUMMARY_FIELDS.any { l.startsWith("$it：") }) break
            out += l
        }
        return out.joinToString("\n")
    }
}
