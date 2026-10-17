// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContextAssembler 单测（刀 0b · 2026-10-16）。
 *
 * 覆盖派工单 §4.2 与追加一/追加二：
 * - 人设三层拼接顺序 A→B→C；
 * - 记忆召回 ≤ 2k tokens、超限明说；
 * - **超窗口 ×0.85 触发边界（0.849 / 0.850 / 0.851 三点）——三点从
 *   [Provider.contextWindow] 字段算出的阈值推导，不写死常量**（追加二第 1 条）；
 * - **缓存友好性**：同会话尾部新增一条消息 ⇒ 组装结果前缀字节级相同（追加一第 4 条）。
 */
class ContextAssemblerTest {

    private val facts = HardFacts(
        workspacePath = "/sdcard/Download/证道",
        model = "test-model",
        decisions = listOf("三格式"),
        formatRules = listOf("触发词必须真实存在于内容"),
    )

    private fun persona(): List<PersonaSegment> = PersonaRules.assemble(
        kernelText = "A内核文本",
        globalText = "B全局文本",
        projectText = "C项目文本",
    )

    private fun provider(contextWindow: Int? = null, maxOutput: Int? = null) = Provider(
        id = "t", label = "t", protocol = LlmProtocol.OPENAI_COMPAT,
        baseUrl = "https://x/v1", model = "m",
        contextWindow = contextWindow, maxOutput = maxOutput,
    )

    private fun msg(id: String, text: String = "消息$id") =
        HistoryMessage(id = id, role = HistoryMessage.ROLE_USER, text = text)

    private fun mem(id: String, body: String = "记忆$id") = MemoryEntry(
        id = id, scope = MemoryScope.PROJECT, kind = MemoryKind.PREFERENCE,
        title = "t$id", body = body, tags = listOf("x"),
        sourceSession = "s1", sourceFile = "f", status = MemoryStatus.CONFIRMED,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun assemble(
        history: List<HistoryMessage> = listOf(msg("m1")),
        current: String = "",
        sticky: List<MemoryEntry> = emptyList(),
        newMemories: List<MemoryEntry> = emptyList(),
        windowTokens: Int = ContextAssembler.DEFAULT_WINDOW_TOKENS,
        compressionSummary: String? = null,
        compressedIds: Set<String> = emptySet(),
        tokensOf: (String) -> Int = { TokenEstimator.estimate(it) },
    ) = ContextAssembler.assemble(
        persona = persona(),
        facts = facts,
        stickyMemories = sticky,
        newMemories = newMemories,
        history = history,
        current = current,
        windowTokens = windowTokens,
        compressionSummary = compressionSummary,
        compressedIds = compressedIds,
        tokensOf = tokensOf,
    )

    // ── 顺序 ──────────────────────────────────────────────────────────────

    @Test
    fun `组装顺序_A内核到B到C到硬事实到记忆到历史到本轮`() {
        val p = assemble(
            sticky = listOf(mem("mem1")),
            history = listOf(msg("m1")),
            current = "本轮问题",
        )
        assertEquals(
            listOf(
                SegmentKind.PERSONA_A,
                SegmentKind.PERSONA_B,
                SegmentKind.PERSONA_C,
                SegmentKind.HARD_FACTS,
                SegmentKind.MEMORY,
                SegmentKind.HISTORY,
                SegmentKind.CURRENT,
            ),
            p.segments.map { it.kind },
        )
    }

    @Test
    fun `人设三层拼接顺序与内容`() {
        val segs = PersonaRules.assemble("A", "B", "C")
        assertEquals(listOf(PersonaLayer.A_KERNEL, PersonaLayer.B_GLOBAL, PersonaLayer.C_PROJECT), segs.map { it.layer })
        assertEquals(listOf("A", "B", "C"), segs.map { it.text })
    }

    @Test
    fun `人设缺失的层跳过不报错`() {
        val segs = PersonaRules.assemble("A", null, null)
        assertEquals(1, segs.size)
        assertEquals(PersonaLayer.A_KERNEL, segs[0].layer)
    }

    @Test
    fun `人设超限截断且不静默`() {
        val huge = "x".repeat(100_000) // 10 万 ASCII ≈ 2.5 万 tokens > 8k
        val segs = PersonaRules.assemble("A", huge, null)
        assertTrue(segs[1].truncated)
        assertTrue(segs[1].text.endsWith(PersonaRules.TRUNCATION_MARK))
    }

    // ── 记忆召回 ──────────────────────────────────────────────────────────

    @Test
    fun `记忆段只增不改_新记忆追加在已注入之后`() {
        val first = assemble(sticky = listOf(mem("a")), newMemories = listOf(mem("b")))
        val second = assemble(sticky = listOf(mem("a"), mem("b")), newMemories = listOf(mem("c")))
        val seg1 = first.segments.first { it.kind == SegmentKind.MEMORY }.text
        val seg2 = second.segments.first { it.kind == SegmentKind.MEMORY }.text
        assertTrue("已注入部分的字节必须原样在前", seg2.startsWith(seg1))
        assertTrue("新记忆在尾部", seg2.endsWith("记忆c"))
    }

    @Test
    fun `召回超2k上界_截断信息进上下文不静默`() {
        // 每条约 310 字符；10 条 ≈ 3.1k 字符 > 2k tokens 上界（按字符计的一把尺子）
        val news = (1..10).map { mem("m$it", body = "x".repeat(300)) }
        val p = assemble(newMemories = news, tokensOf = { it.length })
        assertTrue(p.recallTruncated)
        val seg = p.segments.first { it.kind == SegmentKind.MEMORY }.text
        assertTrue("截断必须明说", seg.contains("未注入"))
        assertTrue(p.recallNote.contains("装不下"))
    }

    @Test
    fun `召回预算被粘滞占满时新召回全部未注入且说明`() {
        val sticky = (1..10).map { mem("s$it", body = "x".repeat(400)) }
        val p = assemble(sticky = sticky, newMemories = listOf(mem("n1")), tokensOf = { it.length })
        val seg = p.segments.first { it.kind == SegmentKind.MEMORY }.text
        assertTrue(seg.contains("占满"))
    }

    // ── 压缩触发边界（追加二第 1 条：从字段算，不写死） ────────────────────

    @Test
    fun `窗口推导_元数据优先_拿不到回退1M`() {
        // 刀 3 B 段（第十四批 J1/J10）：128k 旧假设作废，回退值 = 1,048,576
        assertEquals(1_048_576, ContextAssembler.DEFAULT_WINDOW_TOKENS)
        assertEquals(1_048_576, ContextAssembler.ContextBudgets.windowOf(provider()))
        assertEquals(200_000, ContextAssembler.ContextBudgets.windowOf(provider(contextWindow = 200_000)))
        assertEquals(1_048_576, ContextAssembler.ContextBudgets.windowOf(provider(contextWindow = 0))) // 非正数当没给
        assertEquals(32_000, ContextAssembler.ContextBudgets.outputOf(provider()))
        assertEquals(8_000, ContextAssembler.ContextBudgets.outputOf(provider(maxOutput = 8_000)))
    }

    @Test
    fun `单轮预算_取窗口与输出上限的较小者`() {
        // 窗口 1000 ×0.6 = 600；输出 32k ×0.9 = 28800 ⇒ 600
        assertEquals(600, ContextAssembler.ContextBudgets.budgetOf(provider(contextWindow = 1000)))
        // 窗口 128k ×0.6 = 76800；输出 4000 ×0.9 = 3600 ⇒ 3600（输出上限兜底的那道）
        assertEquals(3600, ContextAssembler.ContextBudgets.budgetOf(provider(maxOutput = 4000)))
    }

    @Test
    fun `触发边界三点_阈值由窗口字段算出`() {
        val p = provider(contextWindow = 1000)
        val w = ContextAssembler.ContextBudgets.windowOf(p)
        val threshold = ContextAssembler.ContextBudgets.thresholdOf(p)
        assertEquals(850.0, threshold, 1e-9)
        // 0.849 / 0.850 / 0.851 —— 都由 w 与 threshold 推导，无一处写死 850
        val below = Math.round(w * 0.849).toInt()   // 849
        val atLine = Math.round(w * 0.850).toInt()  // 850
        val above = Math.round(w * 0.851).toInt()   // 851
        assertEquals(849, below)
        assertEquals(850, atLine)
        assertEquals(851, above)
        assertFalse("0.849 倍窗口不触发", ContextAssembler.needsCompressing(below, w))
        assertFalse("恰好压线不触发（严格大于）", ContextAssembler.needsCompressing(atLine, w))
        assertTrue("0.851 倍窗口触发", ContextAssembler.needsCompressing(above, w))
    }

    @Test
    fun `assemble层_超窗口置needsCompressing`() {
        // 窗口 1000 ⇒ 触发线 850 tokens；注入一把按字符计的尺子，历史 900 字符（加前缀段后总数 ≈ 960）
        val p = assemble(
            history = listOf(msg("big", "x".repeat(900))),
            windowTokens = 1000,
            tokensOf = { it.length },
        )
        assertTrue(p.needsCompressing)
        assertTrue("估算含人设与硬事实段，≥ 900", p.estimatedTokens >= 900)
        // 同输入把窗口放大 10 倍（触发线 8500）⇒ 不触发
        val q = assemble(
            history = listOf(msg("big", "x".repeat(900))),
            windowTokens = 10_000,
            tokensOf = { it.length },
        )
        assertFalse(q.needsCompressing)
    }

    // ── 缓存友好性（追加一第 4 条：前缀字节级相同） ────────────────────────

    @Test
    fun `缓存友好_尾部新增一条消息_前缀字节级相同`() {
        val h5 = (1..5).map { msg("m$it") }
        val before = assemble(history = h5, sticky = listOf(mem("a")), current = "")
        val after = assemble(history = h5 + msg("m6"), sticky = listOf(mem("a")), current = "")
        val b = before.render().toByteArray(Charsets.UTF_8)
        val a = after.render().toByteArray(Charsets.UTF_8)
        val firstDiff = (0 until minOf(b.size, a.size)).firstOrNull { b[it] != a[it] }
        assertTrue(
            "同会话仅尾部新增一条消息：旧渲染必须是新渲染的字节级前缀" +
                "（旧 ${b.size} B / 新 ${a.size} B，首个差异位 ${firstDiff ?: "无"}）",
            a.size > b.size && b.contentEquals(a.copyOfRange(0, b.size)),
        )
    }

    @Test
    fun `缓存友好_人设与记忆段逐字节不变`() {
        val h = listOf(msg("m1"))
        val first = assemble(history = h, sticky = listOf(mem("a")))
        val second = assemble(history = h + msg("m2"), sticky = listOf(mem("a")))
        assertEquals(
            first.prefixText().toByteArray(Charsets.UTF_8).toList(),
            second.prefixText().toByteArray(Charsets.UTF_8).toList(),
        )
    }

    @Test
    fun `缓存友好_渲染无序号计数`() {
        val p = assemble(history = (1..3).map { msg("m$it") })
        val text = p.render()
        assertFalse("不许出现「第 N 条」（会随追加漂移，废掉缓存）", text.contains("第1条") || text.contains("第 1 条"))
        assertFalse(text.contains("1/3"))
        assertFalse(text.contains("共3"))
    }

    // ── 追加四：前缀确定化自检（逐字节 diff，差异只许在尾部） ──────────────

    @Test
    fun `确定性_同一输入组装两次逐字节相同`() {
        val h = (1..4).map { msg("m$it") }
        val a = assemble(history = h, sticky = listOf(mem("a"), mem("b")), newMemories = listOf(mem("c")))
        val b = assemble(history = h, sticky = listOf(mem("a"), mem("b")), newMemories = listOf(mem("c")))
        assertEquals(
            "同输入两次组装必须逐字节相同（内部若依赖集合迭代顺序就会次次不同＝次次冷启动）",
            a.render().toByteArray(Charsets.UTF_8).toList(),
            b.render().toByteArray(Charsets.UTF_8).toList(),
        )
    }

    @Test
    fun `前缀自检_两轮diff只出现在尾部`() {
        // 追加四要求的自检形态：同会话第 N 轮与第 N+1 轮逐字节 diff。
        // 正确形态：旧渲染整体是新渲染的前缀 ⇒ 重叠区零差异（firstDiff = null），
        // 且新渲染更长（差异全部落在新增的尾部）。出现在前缀里 = 本刀没做完。
        val h5 = (1..5).map { msg("m$it") }
        val before = assemble(history = h5, sticky = listOf(mem("a")))
        val after = assemble(history = h5 + msg("m6"), sticky = listOf(mem("a")))
        val b = before.render().toByteArray(Charsets.UTF_8)
        val a = after.render().toByteArray(Charsets.UTF_8)
        val firstDiff = (0 until minOf(b.size, a.size)).firstOrNull { b[it] != a[it] }
        assertTrue("新渲染必须更长（尾部确有新增）", a.size > b.size)
        assertTrue(
            "差异只许在尾部：重叠区内不许有一个字节不同（firstDiff=${firstDiff ?: "无"}）",
            firstDiff == null,
        )
    }

    // ── 压缩留痕 ──────────────────────────────────────────────────────────

    @Test
    fun `压缩替身带标记与留痕id`() {
        val p = assemble(
            compressionSummary = "目标：做一张卡",
            compressedIds = setOf("m1", "m2"),
        )
        val seg = p.segments.first { it.kind == SegmentKind.COMPRESSION }.text
        assertTrue(seg.contains("【此处已压缩"))
        assertTrue(seg.contains("2 条旧消息压成摘要"))
        assertTrue(seg.contains("原文仍在记录里可回看"))
        assertEquals(setOf("m1", "m2"), p.compressedIds)
    }

    @Test
    fun `压缩选区_只压中段旧段_最近N条不动`() {
        val h = (1..6).map { msg("m$it") }
        val range = ContextAssembler.selectCompressionRange(h, keepRecent = 4)
        assertEquals(listOf("m1", "m2"), range.map { it.id })
        assertTrue(ContextAssembler.selectCompressionRange(h.take(4), keepRecent = 4).isEmpty())
    }

    @Test
    fun `人设与记忆段永不被压缩`() {
        val never = Compressor.neverCompressible()
        assertTrue(SegmentKind.PERSONA_A in never)
        assertTrue(SegmentKind.PERSONA_B in never)
        assertTrue(SegmentKind.PERSONA_C in never)
        assertTrue(SegmentKind.MEMORY in never)
        assertFalse(SegmentKind.COMPRESSION in never)
        assertFalse(SegmentKind.HISTORY in never)
    }

    @Test
    fun `前缀文本只含人设硬事实与记忆`() {
        val p = assemble(sticky = listOf(mem("a")), current = "本轮")
        val prefix = p.prefixText()
        assertTrue(prefix.contains("A内核文本"))
        assertTrue(prefix.contains("【硬事实】"))
        assertTrue(prefix.contains("记忆a"))
        assertFalse("本轮输入不进前缀", prefix.contains("本轮"))
        assertFalse("历史不进前缀", prefix.contains("消息m1"))
    }
}
