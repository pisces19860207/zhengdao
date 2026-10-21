// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 判据：派工单-2026-10-21-第一百二十八批 §3 的检索侧：命中/无命中/引用来源正确/
// 预算截断；负例 N2（无来源编引用）与 N4（超预算硬塞）的行为靶子。
package com.example.zhengdao.baopu.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KbRetrieverTest {

    private fun index(vararg docs: KbDoc): KbIndex = KbIndex(
        root = "/tmp/ws",
        rootLabel = "ws",
        updatedAt = 1L,
        docs = docs.toList(),
    )

    private fun doc(rel: String, vararg chunks: KbChunk): KbDoc =
        KbDoc(rel, 100, 200, "text", chunks.toList())

    private fun chunk(
        rel: String,
        text: String,
        lineStart: Int = 1,
        lineEnd: Int = lineStart,
        heading: String = "",
        source: String = KbChunk.SOURCE_FILE,
    ) = KbChunk(rel, lineStart, lineEnd, heading, text, source)

    // ── 命中与排序 ────────────────────────────────────────────────────────

    @Test
    fun `命中_按分数排序_平手按路径再按行号`() {
        val idx = index(
            doc(
                "小说.txt",
                chunk("小说.txt", "他只身进了城", 1, 3),
                chunk("小说.txt", "城头的鼓声", 4, 6),
                chunk("小说.txt", "无关的段落", 7, 9),
            ),
            doc(
                "笔记.txt",
                chunk("笔记.txt", "进城要带的地图", 1, 2),
            ),
        )
        // 查询「城」：三块各命中一次（分数打平）⇒ relPath 升序，同文件按行号
        val r = KbRetriever.search(idx, "城")
        assertEquals(3, r.hits.size)
        assertEquals("小说.txt", r.hits[0].chunk.relPath)
        assertEquals(1, r.hits[0].chunk.lineStart)
        assertEquals("城头的鼓声", r.hits[1].chunk.text)
        assertEquals(4, r.hits[1].chunk.lineStart)
        assertEquals("笔记.txt", r.hits[2].chunk.relPath)
    }

    @Test
    fun `标题命中权重高于正文`() {
        val idx = index(
            doc(
                "书.md",
                chunk("书.md", "正文里提到一次关键词", 1, 2, heading = "别的标题"),
                chunk("书.md", "正文也提到关键词", 3, 4, heading = "关键词"),
            ),
        )
        val r = KbRetriever.search(idx, "关键词")
        assertEquals(2, r.hits.size)
        // 标题命中 ×3 + 正文 ×1 = 4 > 只有正文 ×1 = 1
        assertTrue(r.hits[0].score > r.hits[1].score)
        assertEquals("关键词", r.hits[0].chunk.heading)
    }

    @Test
    fun `TopK截断_平手排序稳定`() {
        val chunks = (1..8).map { chunk("书.txt", "第${it}段都含同一个词", it, it) }
        val r = KbRetriever.search(index(doc("书.txt", *chunks.toTypedArray())), "同一个词", topK = 3)
        assertEquals(3, r.hits.size)
        // 全平手 ⇒ 按行号升序（显式稳定，不依赖集合顺序）
        assertEquals(listOf(1, 2, 3), r.hits.map { it.chunk.lineStart })
    }

    // ── 无结果：明说没找到，不硬凑 ────────────────────────────────────────

    @Test
    fun `无命中_返回空_调用方明说没找到`() {
        val idx = index(doc("书.txt", chunk("书.txt", "完全无关的内容", 1, 2)))
        val r = KbRetriever.search(idx, "不存在的词")
        assertTrue(r.hits.isEmpty())
        // 空查询也返回空（不抛）
        assertTrue(KbRetriever.search(idx, "   ").hits.isEmpty())
        assertTrue(KbRetriever.search(idx, "，。！").hits.isEmpty())
    }

    // ── 负例 N2：无来源的块一律不进结果 ──────────────────────────────────

    @Test
    fun `无来源的块_一律不进检索结果`() {
        val idx = index(
            doc(
                "书.txt",
                chunk("", "这块没有来源路径", 1, 2), // relPath 空
                chunk("书.txt", "这块有来源", 3, 4),
            ),
        )
        val r = KbRetriever.search(idx, "这块")
        assertEquals(1, r.hits.size)
        assertEquals("书.txt", r.hits[0].chunk.relPath)
    }

    @Test
    fun `引用_含文件行号标题_提取文本有标明`() {
        val file = chunk("资料库/投递/书.txt", "正文", 12, 18, heading = "第三章")
        val cite = KbRetriever.renderCitation(file)
        assertTrue(cite.contains("资料库/投递/书.txt"))
        assertTrue(cite.contains("12-18"))
        assertTrue(cite.contains("第三章"))
        val extracted = chunk("页.html", "正文", 1, 2, source = KbChunk.SOURCE_EXTRACTED)
        assertTrue("提取文本必须标明，不冒充原文件行号", KbRetriever.renderCitation(extracted).contains("提取文本"))
    }

    @Test
    fun `片段渲染_引用行加正文_永不为空`() {
        val c = chunk("a.txt", "正文", 1, 1)
        val frag = KbRetriever.renderFragment(c)
        assertTrue(frag.contains("【来源："))
        assertTrue(frag.contains("a.txt"))
        assertTrue(frag.endsWith("正文"))
    }

    // ── 负例 N4：预算截断且明说，不静默硬塞 ──────────────────────────────

    @Test
    fun `预算裁剪_超了截断且可判`() {
        val hits = (1..5).map { KbRetriever.Hit(chunk("书.txt", "第${it}段".repeat(50), it, it), 1) }
        // 假估算器：每段恒 100 token ⇒ 250 预算装 2 段（100+100=200；第三段 300>250）
        val (picked, truncated) = KbRetriever.trimToBudget(hits, budgetTokens = 250) { 100 }
        assertEquals(2, picked.size)
        assertTrue(truncated)
        // 预算 0：第一条也给（宁可不完整，不许空手），但 truncated
        val (picked0, truncated0) = KbRetriever.trimToBudget(hits, budgetTokens = 0) { 100 }
        assertEquals(1, picked0.size)
        assertTrue(truncated0)
        // 空命中不截断
        assertFalse(KbRetriever.trimToBudget(emptyList(), 100).second)
    }

    @Test
    fun `默认注入预算与记忆召回同一份2k`() {
        assertEquals(com.example.zhengdao.baopu.MemoryRules.RECALL_TOKEN_BUDGET, KbRetriever.INJECT_TOKEN_BUDGET)
    }
}
