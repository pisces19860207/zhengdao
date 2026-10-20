// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 B1–B5：**窗口**。判的是「什么时候该看一眼」，不是「好不好」。
// 最容易错的两种：①没到期就下结论；②把「没人用」当「用坏了」。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostAdoptionWindowTest {

    private fun event(
        appliedAt: Long = 1_000L,
        uses: Int = PostAdoptionWindow.USES,
        rollback: Boolean = true,
    ) = PostAdoptionEvent(
        eventId = "adopt-w",
        channel = PostAdoptionChannel.LEARN_CARD,
        candidateId = "c1",
        skillName = "我的技能",
        skillVersion = "abc123456789",
        appliedAtMs = appliedAt,
        baseline = emptyMap(),
        baselineSource = PostAdoptionBaseline.NEW,
        rollbackAvailable = rollback,
        windowUses = uses,
    )

    private val day = PostAdoptionWindow.MS_PER_DAY

    @Test
    fun `B1_满N次使用即到期`() {
        val e = event()
        val j = judgePostAdoption(
            e,
            mapOf(SkillOutcome.SUCCESS to PostAdoptionWindow.USES),
            nowMs = 1_000L + 1, // 一天都没过，靠次数到期
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.KEEP, j.verdict)
        assertEquals(PostAdoptionReason.EFFECT_POSITIVE, j.reason)
    }

    @Test
    fun `B2_满T天即到期_先到为准`() {
        val e = event()
        val j = judgePostAdoption(
            e,
            mapOf(SkillOutcome.SUCCESS to PostAdoptionWindow.USES),
            nowMs = 1_000L + PostAdoptionWindow.DAYS * day,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.KEEP, j.verdict)
    }

    @Test
    fun `B3_未到期一律DEFER`() {
        val e = event()
        val j = judgePostAdoption(
            e,
            mapOf(SkillOutcome.SUCCESS to 1), // 只用了 1 次，也没到 7 天
            nowMs = 1_000L + day,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertEquals(PostAdoptionReason.WINDOW_NOT_DUE, j.reason)
        assertFalse("未到期不许结案", j.terminal)
    }

    @Test
    fun `B4_到期但零样本仍DEFER不判退役`() {
        val e = event(rollback = false) // 没备份也不许因此判退役
        val j = judgePostAdoption(
            e,
            emptyMap(),
            nowMs = 1_000L + PostAdoptionWindow.DAYS * day,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertFalse("沉默不是证据，零样本不许判 RETIRE", j.verdict == PostAdoptionVerdict.RETIRE)
        assertFalse(j.terminal)
    }

    @Test
    fun `B5_超过四倍窗口仍零样本结案为DEFER_NO_USE`() {
        val e = event()
        val j = judgePostAdoption(
            e,
            emptyMap(),
            nowMs = 1_000L + PostAdoptionWindow.NO_USE_MS,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertEquals(PostAdoptionReason.NO_USE, j.reason)
        assertTrue("28 天零使用可以结案（省得账本无限长）", j.terminal)
    }

    @Test
    fun `B6_次数窗口与策略下限同源`() {
        // 两处各写一个数字迟早漂：这里直接钉「同源」
        assertEquals(EvolutionPolicy.DEFAULT_MIN_EFFECT_SAMPLES, PostAdoptionWindow.USES)
        assertEquals(3, PostAdoptionWindow.USES)
        assertEquals(7, PostAdoptionWindow.DAYS)
        assertEquals(28, PostAdoptionWindow.NO_USE_DAYS)
    }
}
