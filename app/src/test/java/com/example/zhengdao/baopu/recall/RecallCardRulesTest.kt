// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 540 卡面纯层判据（`LedgerBoardRules` 同房型：跳原文给不给，一处判完）。 */
class RecallCardRulesTest {

    private fun outcome(
        found: Boolean,
        vararg points: RecallEngine.Point,
        calls: Int = 1,
        groups: Int = 1,
    ): RecallEngine.Outcome = RecallEngine.Outcome(
        found = found,
        points = points.toList(),
        searchCalls = calls,
        clueGroupCount = groups,
    )

    @Test
    fun `要点卡_每行带来源id与会话id_两态开关`() {
        val view = RecallCardRules.viewOf(
            outcome(true, RecallEngine.Point("部署用 8080", 7L, 42L)),
        )
        assertTrue(!view.notFound)
        assertEquals(1, view.rows.size)
        assertEquals("部署用 8080", view.rows.single().text)
        assertEquals(42L, view.rows.single().sourceId)
        assertEquals(7L, view.rows.single().sessionId)
    }

    @Test
    fun `没找到卡_零行零动作_不许垫凑数行`() {
        val view = RecallCardRules.viewOf(outcome(false))
        assertTrue(view.notFound)
        assertTrue("没找到就是没找到", view.rows.isEmpty())
    }

    @Test
    fun `引擎判没找到_卡面就是没找到形态`() {
        val engineOutcome = RecallEngine.recall(listOf(listOf("部署"))) { emptyList() }
        val view = RecallCardRules.viewOf(engineOutcome)
        assertTrue(view.notFound)
        assertTrue(view.rows.isEmpty())
    }

    @Test
    fun `跳原文_两端id真才给_缺一端不给`() {
        assertEquals(
            "正好是 resolveJump／LedgerJump 吃的那对数",
            7L to 42L,
            RecallCardRules.jumpOf(RecallRowView("部署用 8080", sourceId = 42L, sessionId = 7L)),
        )
        assertNull("会话 id 不真 ⇒ 不硬跳", RecallCardRules.jumpOf(RecallRowView("t", 42L, 0L)))
        assertNull("消息 id 不真 ⇒ 不硬跳", RecallCardRules.jumpOf(RecallRowView("t", 0L, 7L)))
    }
}
