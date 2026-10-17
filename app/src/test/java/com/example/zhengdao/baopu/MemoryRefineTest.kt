// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MemoryRefine 纯函数单测（刀 2 C 段；判据 §5.8）。
 *
 * 钉死派工单原话级口径：
 * - 候选一律 [MemoryStatus.PENDING]（**不落盘**——落盘是用户点「记住」之后的事）；
 * - **恰好一条**自省（空会话也给一条）；
 * - 自省 kind = `introspection`、layer = C_PROJECT（**不进 A 内核**）；
 * - **确定性**：同一份 facts 两次调用结果完全相同（含 id）；
 * - 不额外跑模型（本类零网络零 IO——这条由「无 Context 无 executor」的结构保证）。
 */
class MemoryRefineTest {

    private fun facts(
        userTexts: List<String>,
        toolsUsed: List<String> = emptyList(),
        steps: Int = 2,
        endState: String = "Done",
        now: Long = 1_000L,
    ) = MemoryRefine.SessionFacts(
        sessionId = 7L,
        userTexts = userTexts,
        toolsUsed = toolsUsed,
        steps = steps,
        endState = endState,
        now = now,
    )

    // ── 候选 ──────────────────────────────────────────────────────────────

    @Test
    fun `候选_一律PENDING且带来源`() {
        val r = MemoryRefine.refine(facts(listOf("以后请叫我老师。")))

        assertTrue(r.candidates.isNotEmpty())
        r.candidates.forEach { e ->
            assertEquals(MemoryStatus.PENDING, e.status)
            assertEquals("7", e.sourceSession)
            assertEquals(MemoryScope.PROJECT, e.scope)
            assertEquals(listOf("会话提炼"), e.tags)
        }
    }

    @Test
    fun `候选_偏好与教训分类`() {
        val pref = MemoryRefine.refine(facts(listOf("以后请叫我老师。")))
        assertEquals(MemoryKind.PREFERENCE, pref.candidates.single().kind)

        val lesson = MemoryRefine.refine(facts(listOf("不对，别这样写。")))
        assertEquals(MemoryKind.LESSON, lesson.candidates.single().kind)
    }

    @Test
    fun `候选_没有标记的句子不提炼`() {
        val r = MemoryRefine.refine(facts(listOf("今天天气不错。帮我看看这个文件。")))
        assertTrue(r.candidates.isEmpty())
    }

    @Test
    fun `候选_上限三条且超计数进dropped`() {
        val r = MemoryRefine.refine(
            facts(
                listOf(
                    "以后请叫我老师。",
                    "我喜欢简洁的回复。",
                    "统一用中文回答。",
                    "换成深色主题。",
                    "希望你每次都先给结论。",
                ),
            ),
        )
        assertEquals(MemoryRefine.MAX_CANDIDATES, r.candidates.size)
        assertTrue("超限必须计数，不许静默", r.dropped > 0)
    }

    @Test
    fun `候选_同正文去重`() {
        val r = MemoryRefine.refine(facts(listOf("以后请叫我老师。", "别的话。以后请叫我老师。")))
        assertEquals(1, r.candidates.count { it.body == "以后请叫我老师" })
    }

    @Test
    fun `候选_短碎片不成句`() {
        val r = MemoryRefine.refine(facts(listOf("对。"))) // 太短
        assertTrue(r.candidates.isEmpty())
    }

    // ── 自省（恰好一条） ──────────────────────────────────────────────────

    @Test
    fun `自省_恰好一条且不进A内核`() {
        val r = MemoryRefine.refine(facts(listOf("帮我看看资料库。")))
        val it = r.introspection
        assertEquals(MemoryRefine.INTROSPECTION_KIND, it.kind)
        assertEquals("introspection", it.kind)
        assertEquals(PersonaLayer.C_PROJECT, it.layer)
        assertNotEquals(PersonaLayer.A_KERNEL, it.layer)
        assertTrue("自省要短", it.text.length <= MemoryRefine.INTROSPECTION_LIMIT)
    }

    @Test
    fun `自省_空会话也有一条`() {
        val r = MemoryRefine.refine(facts(emptyList(), steps = 0))
        assertTrue(r.introspection.text.isNotBlank())
        assertTrue(r.introspection.text.contains("没有对话"))
    }

    @Test
    fun `自省_按结束状态分支`() {
        val aborted = MemoryRefine.refine(facts(listOf("开始吧。"), steps = 3, endState = "Aborted"))
        assertTrue(aborted.introspection.text.contains("第 3 步"))

        val lesson = MemoryRefine.refine(facts(listOf("不对，别这样写。")))
        assertTrue(lesson.introspection.text.contains("纠正"))

        val tools = MemoryRefine.refine(facts(listOf("列个目录。"), toolsUsed = listOf("list_dir", "list_dir")))
        assertTrue(tools.introspection.text.contains("list_dir"))

        val plain = MemoryRefine.refine(facts(listOf("聊聊天。"), steps = 1))
        assertTrue(plain.introspection.text.contains("没有动工具"))
    }

    // ── 确定性 ────────────────────────────────────────────────────────────

    @Test
    fun `提炼_同输入两次完全一致含id`() {
        val f = facts(listOf("以后请叫我老师。", "帮我看看资料库。"), toolsUsed = listOf("list_dir"), now = 555L)
        val r1 = MemoryRefine.refine(f)
        val r2 = MemoryRefine.refine(f)
        assertEquals(r1.candidates, r2.candidates)
        assertEquals(r1.introspection, r2.introspection)
        assertEquals(r1.dropped, r2.dropped)
        assertTrue(r1.candidates.all { it.id.startsWith("555-") })
    }

    @Test
    fun `切句_中英标点都认`() {
        val s = MemoryRefine.sentences("第一句。第二句！第三句？\n第四句")
        assertEquals(listOf("第一句", "第二句", "第三句", "第四句"), s)
    }
}
