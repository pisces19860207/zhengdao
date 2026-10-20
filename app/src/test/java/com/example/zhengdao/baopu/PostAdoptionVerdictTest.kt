// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百批 §6 C1–C6：**四值结论与增量口径**。
// 「正负」这一层不再自创算法——直接复用 `EvolutionPolicy.effectVerdict`，
// 免得同一个「好/坏」在决策口和回环口各有一套阈值。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostAdoptionVerdictTest {

    private fun event(
        baseline: Map<SkillOutcome, Int> = emptyMap(),
        source: PostAdoptionBaseline = PostAdoptionBaseline.MEASURED,
        rollback: Boolean = true,
        version: String = "abc123456789",
    ) = PostAdoptionEvent(
        eventId = "adopt-v",
        channel = PostAdoptionChannel.LEARN_CARD,
        candidateId = "c1",
        skillName = "我的技能",
        skillVersion = version,
        appliedAtMs = 1_000L,
        baseline = baseline,
        baselineSource = source,
        rollbackAvailable = rollback,
    )

    @Test
    fun `C1_正向判KEEP`() {
        val j = judgePostAdoption(
            event(),
            mapOf(SkillOutcome.SUCCESS to 3),
            nowMs = 2_000L,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.KEEP, j.verdict)
        assertEquals(PostAdoptionReason.EFFECT_POSITIVE, j.reason)
        assertTrue("KEEP 可以结案（不再反复打扰）", j.terminal)
    }

    @Test
    fun `C2_负向且可回滚判ROLLBACK`() {
        val j = judgePostAdoption(
            event(rollback = true),
            mapOf(SkillOutcome.FAILURE to 3),
            nowMs = 2_000L,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.ROLLBACK, j.verdict)
        assertEquals(PostAdoptionReason.EFFECT_NEGATIVE_ROLLBACKABLE, j.reason)
        assertFalse("回滚要人批，不能自己结案", j.terminal)
    }

    @Test
    fun `C3_负向且不可回滚判RETIRE`() {
        val j = judgePostAdoption(
            event(rollback = false),
            mapOf(SkillOutcome.FAILURE to 3),
            nowMs = 2_000L,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.RETIRE, j.verdict)
        assertEquals(PostAdoptionReason.EFFECT_NEGATIVE_NO_BACKUP, j.reason)
        assertFalse(j.terminal)
    }

    @Test
    fun `C4_势均力敌判DEFER`() {
        val j = judgePostAdoption(
            event(),
            mapOf(SkillOutcome.SUCCESS to 2, SkillOutcome.FAILURE to 2),
            nowMs = 2_000L,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertEquals(PostAdoptionReason.EFFECT_TIE, j.reason)
    }

    @Test
    fun `C5_增量由after减before得出`() {
        val e = event(
            baseline = mapOf(
                SkillOutcome.SUCCESS to 4,
                SkillOutcome.FAILURE to 2,
                SkillOutcome.USER_CORRECTED to 1,
                SkillOutcome.RETRIED to 1,
            ),
        )
        val delta = postAdoptionDelta(
            e,
            mapOf(
                SkillOutcome.SUCCESS to 6,
                SkillOutcome.FAILURE to 3,
                SkillOutcome.USER_CORRECTED to 1,
                SkillOutcome.RETRIED to 2,
            ),
        )
        assertEquals(2, delta.successes)
        assertEquals(1, delta.failures)
        assertEquals(0, delta.corrections)
        assertEquals(1, delta.retries)
        assertEquals(4, delta.attempts)
    }

    @Test
    fun `C6_正文版本变了判DEFER`() {
        val j = judgePostAdoption(
            event(version = "abc123456789"),
            mapOf(SkillOutcome.FAILURE to 5),
            nowMs = 2_000L,
            currentVersion = "fff999999999", // 采纳之后又被人改过正文
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertEquals(PostAdoptionReason.BODY_CHANGED, j.reason)
        assertTrue(j.terminal)
    }

    @Test
    fun `C7_基线未知只出DEFER`() {
        val j = judgePostAdoption(
            event(source = PostAdoptionBaseline.UNKNOWN),
            mapOf(SkillOutcome.SUCCESS to 99),
            nowMs = 2_000L,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertEquals(PostAdoptionReason.BASELINE_UNKNOWN, j.reason)
    }

    @Test
    fun `C8_读数读不到只出DEFER`() {
        val j = judgePostAdoption(
            event(),
            after = null,
            nowMs = 2_000L,
            currentVersion = "abc123456789",
        )
        assertEquals(PostAdoptionVerdict.DEFER, j.verdict)
        assertEquals(PostAdoptionReason.EFFECT_UNREADABLE, j.reason)
        assertFalse(j.terminal)
    }

    @Test
    fun `C9_版本读不到就不做版本比对`() {
        val j = judgePostAdoption(
            event(version = "-"),
            mapOf(SkillOutcome.SUCCESS to 3),
            nowMs = 2_000L,
            currentVersion = "-",
        )
        assertEquals(PostAdoptionVerdict.KEEP, j.verdict)
    }
}
