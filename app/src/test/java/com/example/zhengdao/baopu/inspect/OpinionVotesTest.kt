package com.example.zhengdao.baopu.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionVotesTest {
    private val now = 100L * 24 * 60 * 60 * 1_000
    private fun feedback(signal: OpinionSignal, kind: String = "risk", at: Long = now) =
        OpinionFeedback(kind, at, signal)

    @Test fun `signals use exact half vote units`() {
        assertEquals(2, OpinionSignal.AGREE.halfVotes)
        assertEquals(-2, OpinionSignal.REJECT.halfVotes)
        assertEquals(-1, OpinionSignal.SKIP.halfVotes)
        assertEquals(0, OpinionSignal.CLOSE.halfVotes)
        assertEquals(-2, OpinionSignal.ASK_AGAIN.halfVotes)
        assertEquals(2, OpinionSignal.CONTINUE.halfVotes)
        assertEquals(2, OpinionSignal.PRAISE.halfVotes)
    }

    @Test fun `negative five a single closed card never lowers sensitivity`() {
        val input = listOf(feedback(OpinionSignal.CLOSE))
        assertEquals(0L, OpinionVotes.total("risk", input, now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", input, now))
    }

    @Test fun `many closed cards also remain neutral`() {
        val input = List(100) { feedback(OpinionSignal.CLOSE, at = now - it) }
        assertEquals(0L, OpinionVotes.total("risk", input, now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", input, now))
    }

    @Test fun `a skipped card counts as negative half a vote`() {
        val input = listOf(feedback(OpinionSignal.SKIP))
        assertEquals(-1L, OpinionVotes.total("risk", input, now))
        assertTrue(OpinionVotes.shouldLowerSensitivity("risk", input, now))
    }

    @Test fun `only strictly negative totals lower sensitivity`() {
        val positive = listOf(feedback(OpinionSignal.AGREE), feedback(OpinionSignal.SKIP))
        val neutral = positive + feedback(OpinionSignal.SKIP)
        val negative = neutral + feedback(OpinionSignal.SKIP)
        assertEquals(1L, OpinionVotes.total("risk", positive, now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", positive, now))
        assertEquals(0L, OpinionVotes.total("risk", neutral, now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", neutral, now))
        assertTrue(OpinionVotes.shouldLowerSensitivity("risk", negative, now))
    }

    @Test fun `repeating a question is negative and continuing cancels it`() {
        val repeated = listOf(feedback(OpinionSignal.ASK_AGAIN))
        assertTrue(OpinionVotes.shouldLowerSensitivity("risk", repeated, now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", repeated + feedback(OpinionSignal.CONTINUE), now))
    }

    @Test fun `closing does not erase an earlier explicit rejection`() {
        val input = listOf(feedback(OpinionSignal.REJECT, at = now - 1), feedback(OpinionSignal.CLOSE))
        assertEquals(-2L, OpinionVotes.total("risk", input, now))
        assertTrue(OpinionVotes.shouldLowerSensitivity("risk", input, now))
    }

    @Test fun `thirty day window includes lower boundary and excludes older and future votes`() {
        val boundary = feedback(OpinionSignal.REJECT, at = now - OpinionVotes.WINDOW_MS)
        val stale = boundary.copy(atMs = boundary.atMs - 1)
        val future = boundary.copy(atMs = now + 1)
        assertEquals(-2L, OpinionVotes.total("risk", listOf(boundary), now))
        assertEquals(0L, OpinionVotes.total("risk", listOf(stale, future), now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", listOf(stale, future), now))
    }

    @Test fun `other card categories do not affect sensitivity`() {
        val input = listOf(feedback(OpinionSignal.CLOSE), feedback(OpinionSignal.REJECT, kind = "audit"))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", input, now))
        assertTrue(OpinionVotes.shouldLowerSensitivity("audit", input, now))
    }

    @Test fun `empty history does not lower sensitivity`() {
        assertEquals(0L, OpinionVotes.total("risk", emptyList(), now))
        assertFalse(OpinionVotes.shouldLowerSensitivity("risk", emptyList(), now))
    }

    @Test fun `feedback half votes can be copied into the Opinion integer field`() {
        val opinion = Opinion(Source.PATROL, Severity.MINOR, "缺证", ActionCode.REVIEW, LawRef.NONE, listOf("event:1"), OpinionSignal.SKIP.halfVotes)
        assertEquals(-1, opinion.vote)
    }
}
