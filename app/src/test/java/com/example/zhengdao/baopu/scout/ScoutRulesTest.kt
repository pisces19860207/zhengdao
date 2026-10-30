// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候的四态判据（JVM 直测：不起 Android、不碰文件、不读时钟）。
//
// 覆盖派工单的五条判据里与"判态/措辞"有关的那几条：
// ② 关掉权限再探 ⇒ 变"需授权"（这里是 granted→revoked 与 notGranted→denied 两条路径）；
// ④ 驳回 3 次后不主动提、主动问照答；
// ⑤ revoked 能被识别，且只提一次。
package com.example.zhengdao.baopu.scout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutRulesTest {

    private val dev = "PGT-AN10/16"

    private fun rec(
        cap: Capability = Capability.STORAGE,
        state: ScoutState = ScoutState.DENIED,
        denials: Int = 0,
        mentioned: Boolean = false,
        reason: String = "",
        note: String = "",
        at: Long = 1L,
        intent: String = "",
    ) = ScoutRecord(
        capability = cap,
        state = state,
        denials = denials,
        lastAtMs = at,
        lastIntent = intent,
        reason = reason,
        note = note,
        revokedMentioned = mentioned,
        device = dev,
    )

    private fun probe(cap: Capability, signal: ProbeSignal, prior: ScoutRecord? = null, at: Long = 100L) =
        ScoutRules.resolve(cap, signal, prior, at, dev)

    // ── 四态 ────────────────────────────────────────────────────────────────

    @Test fun `usable now is granted and clears past denials`() {
        val got = probe(Capability.CAMERA, ProbeSignal.Granted(), rec(denials = 3))
        assertEquals(ScoutState.GRANTED, got.state)
        assertEquals(0, got.denials)
        assertFalse(got.blocked)
        assertEquals(100L, got.lastAtMs)
    }

    @Test fun `was granted then not granted is revoked not denied`() {
        val got = probe(
            Capability.STORAGE,
            ProbeSignal.NotGranted("需要「所有文件访问」授权"),
            rec(state = ScoutState.GRANTED, at = 10L),
        )
        assertEquals(ScoutState.REVOKED, got.state)
        assertFalse(got.revokedMentioned)
        assertEquals(0, got.denials)
    }

    @Test fun `revoked survives the next probe and keeps its one mention spent`() {
        val first = probe(Capability.STORAGE, ProbeSignal.NotGranted("没开"), rec(state = ScoutState.GRANTED))
        val mentioned = ScoutRules.withMention(first, 200L)
        val second = probe(Capability.STORAGE, ProbeSignal.NotGranted("没开"), mentioned, at = 300L)

        assertEquals(ScoutState.REVOKED, second.state)
        assertTrue(second.revokedMentioned)
        assertFalse("同一次收回只提一次（判据⑤）", ScoutRules.proactiveAskAllowed(second))
    }

    @Test fun `not granted without history is never-asked not a refusal`() {
        val got = probe(Capability.NOTIFICATIONS, ProbeSignal.NotGranted("通知权限没开"))
        assertEquals(ScoutState.DENIED, got.state)
        assertEquals(0, got.denials)
        assertTrue("还没问过 ⇒ 可以主动问一次", ScoutRules.proactiveAskAllowed(got))
    }

    @Test fun `not granted with history keeps the denial count`() {
        val got = probe(
            Capability.CAMERA,
            ProbeSignal.NotGranted("相机权限没开"),
            rec(denials = 2, intent = "扫一下这页纸"),
        )
        assertEquals(ScoutState.DENIED, got.state)
        assertEquals(2, got.denials)
        assertEquals("扫一下这页纸", got.lastIntent)
        assertFalse(ScoutRules.proactiveAskAllowed(got))
    }

    @Test fun `unsupported and failed both become unavailable carrying a reason`() {
        val unsupported = probe(Capability.ROOT, ProbeSignal.Unsupported("这台手机没有 root"))
        assertEquals(ScoutState.UNAVAILABLE, unsupported.state)
        assertEquals("这台手机没有 root", unsupported.reason)

        val failed = probe(Capability.NETWORK, ProbeSignal.Failed("SecurityException: denied"))
        assertEquals(ScoutState.UNAVAILABLE, failed.state)
        assertTrue("探测失败要说清是探测没成，不许当成本机不支持", failed.reason.startsWith("探测失败："))
        assertFalse(ScoutRules.proactiveAskAllowed(failed))
    }

    @Test fun `unavailable cannot exist without a reason`() {
        val e = runCatching { rec(state = ScoutState.UNAVAILABLE, reason = "") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun `negative three a failed or unsupported reading can never be reported as usable`() {
        // 负例③「把不可用说成可以做」：无论昨天是什么态，炸了的读数只能落到"做不了"，
        // 且那句话里不许出现任何"可以用/没问题/再试试"的口子。
        val signals = listOf<ProbeSignal>(ProbeSignal.Failed("SecurityException"), ProbeSignal.Unsupported("没有该硬件"))
        signals.forEach { signal ->
            ScoutState.entries.forEach { priorState ->
                val prior = rec(
                    state = priorState,
                    reason = if (priorState == ScoutState.UNAVAILABLE) "旧的不可用原因" else "",
                )
                val got = probe(Capability.ROOT, signal, prior)
                assertEquals(ScoutState.UNAVAILABLE, got.state)
                assertTrue(got.blocked)
                val text = ScoutRules.wordingFor(got)
                assertFalse("说成能做了：$text", Regex("可以用|没问题|再试试|可以的").containsMatchIn(text))
            }
        }
        // 对照：唯一能说出"可以用"的读数必须是 Granted（尺子不许把两边量成一样）。
        assertTrue(ScoutRules.wordingFor(probe(Capability.ROOT, ProbeSignal.Granted())).contains("可以用"))
    }

    // ── ④ 主动提的配额 ───────────────────────────────────────────────────────

    @Test fun `denial policy is 0 ask then silent and never proactive from three`() {
        fun allowed(n: Int) = ScoutRules.proactiveAskAllowed(rec(denials = n))
        assertTrue(allowed(0))
        assertFalse(allowed(1))
        assertFalse(allowed(2))
        assertFalse(allowed(3))
        assertFalse(allowed(9))
    }

    @Test fun `asking is still answered after three denials`() {
        listOf(0, 1, 2, 3, 99).forEach { n ->
            assertTrue("3 次不再主动提 ≠ 永久拒绝（判据④后半句）", ScoutRules.answerWhenAsked(rec(denials = n)))
        }
        ScoutState.entries.forEach { state ->
            assertTrue(ScoutRules.answerWhenAsked(rec(state = state, reason = if (state == ScoutState.UNAVAILABLE) "没这功能" else "")))
        }
    }

    @Test fun `mention only marks revoked`() {
        val revoked = rec(state = ScoutState.REVOKED)
        assertTrue(ScoutRules.withMention(revoked, 5L).revokedMentioned)
        assertFalse(ScoutRules.withMention(rec(state = ScoutState.DENIED, denials = 1), 5L).revokedMentioned)
    }

    // ── ③ 措辞：不翻旧账、不把不可用说成可以做 ────────────────────────────────

    @Test fun `wording never scolds or quotes the count`() {
        ScoutState.entries.forEach { state ->
            (0..5).forEach { n ->
                val text = ScoutRules.wordingFor(
                    rec(state = state, denials = n, reason = if (state == ScoutState.UNAVAILABLE) "没插耳机" else ""),
                )
                assertFalse("翻旧账：$text", text.contains("拒"))
                assertFalse("念次数：$text", Regex("\\d").containsMatchIn(text))
                assertFalse("说教：$text", text.contains("又") || text.contains("说了吗"))
            }
        }
    }

    @Test fun `revoked wording says it was on before and is gone now`() {
        val text = ScoutRules.wordingFor(rec(cap = Capability.STORAGE, state = ScoutState.REVOKED))
        assertTrue(text, text.contains("上次开过"))
        assertTrue(text, text.contains("存储"))
        assertTrue(text, text.endsWith("？"))
    }

    @Test fun `a revoked already mentioned stops promising a second ask`() {
        // 判据⑤的另一半：只提一次＝措辞也不许再问第二遍（`proactiveAskAllowed` 已经关了那道门）。
        val spent = rec(cap = Capability.STORAGE, state = ScoutState.REVOKED, mentioned = true)
        assertFalse(ScoutRules.proactiveAskAllowed(spent))
        val text = ScoutRules.wordingFor(spent)
        assertTrue(text, text.contains("上次开过"))
        assertTrue(text, text.contains("系统设置"))
        assertFalse("已经提过一次，不许再问一遍：$text", text.contains("吗"))
    }

    @Test fun `unavailable wording carries the reason instead of an invitation to retry`() {
        val text = ScoutRules.wordingFor(rec(state = ScoutState.DENIED, denials = 3, reason = ""))
        assertTrue(text, text.contains("系统设置"))
        assertFalse(text, text.contains("要开吗"))

        val gone = ScoutRules.wordingFor(rec(cap = Capability.ROOT, state = ScoutState.UNAVAILABLE, reason = "没有 su"))
        assertTrue(gone, gone.contains("没有 su"))
        assertFalse(gone, gone.contains("试试"))
    }

    @Test fun `denial consequence names what stops working`() {
        val text = ScoutRules.consequenceOfDenying(Capability.CAMERA, "拍这页纸")
        assertTrue(text, text.contains("拍这页纸"))
        assertTrue(text, text.contains("拒绝后"))
        assertTrue(text, text.contains("相机"))
        assertTrue(text, ScoutRules.consequenceOfDenying(Capability.CAMERA, "  ").contains("这件事"))
    }

    // ── 何时探 ──────────────────────────────────────────────────────────────

    private val dev1 = ScoutDevice("PGT-AN10", "16", 36, "1.0.0", 100L)
    private val dev2 = ScoutDevice("PGT-AN10", "16", 36, "1.0.1", 101L)
    private val dev3 = ScoutDevice("PGT-AN10", "17", 37, "1.0.0", 100L)

    @Test fun `first run probes only when there is no fingerprint`() {
        assertTrue(ScoutFreshness.shouldProbe(ScoutTrigger.FIRST_RUN, null, dev1))
        assertFalse(ScoutFreshness.shouldProbe(ScoutTrigger.FIRST_RUN, dev1, dev1))
    }

    @Test fun `manual and permission change always probe`() {
        listOf(ScoutTrigger.MANUAL, ScoutTrigger.PERMISSION_CHANGED).forEach {
            assertTrue(ScoutFreshness.shouldProbe(it, dev1, dev1))
        }
    }

    @Test fun `version changes are judged by code and sdk not by name`() {
        assertTrue(ScoutFreshness.shouldProbe(ScoutTrigger.APP_VERSION, dev1, dev2))
        assertFalse(ScoutFreshness.shouldProbe(ScoutTrigger.APP_VERSION, dev1, dev1))
        assertTrue(ScoutFreshness.shouldProbe(ScoutTrigger.OS_VERSION, dev1, dev3))
        assertFalse(ScoutFreshness.shouldProbe(ScoutTrigger.OS_VERSION, dev1, dev1))
    }

    @Test fun `auto triggers list only what actually changed`() {
        assertEquals(
            listOf(ScoutTrigger.FIRST_RUN),
            ScoutFreshness.autoTriggers(null, dev1),
        )
        assertEquals(
            listOf(ScoutTrigger.APP_VERSION),
            ScoutFreshness.autoTriggers(dev1, dev2),
        )
        assertEquals(
            listOf(ScoutTrigger.OS_VERSION),
            ScoutFreshness.autoTriggers(dev1, dev3),
        )
        assertTrue(ScoutFreshness.autoTriggers(dev1, dev1).isEmpty())
    }

    @Test fun `two thousand milliseconds is the budget and one more is late`() {
        assertTrue(ScoutFreshness.withinBudget(ScoutLimits.MAX_PROBE_MS))
        assertFalse(ScoutFreshness.withinBudget(ScoutLimits.MAX_PROBE_MS + 1))
        assertEquals(2_000L, ScoutLimits.MAX_PROBE_MS)
    }

    // ── 没变就不写账 ─────────────────────────────────────────────────────────

    @Test fun `same fact ignores the clock so a foreground return writes nothing`() {
        val prior = rec(state = ScoutState.GRANTED, at = 10L)
        assertTrue(ScoutRules.sameFact(prior, prior.copy(lastAtMs = 99L)))
        assertFalse(ScoutRules.sameFact(prior, prior.copy(state = ScoutState.REVOKED, lastAtMs = 99L)))
        assertFalse(ScoutRules.sameFact(prior, prior.copy(note = "只在前台可用")))
        assertFalse(ScoutRules.sameFact(null, prior))
    }

    @Test fun `granted yields no opinion so the four vessels hear only what is blocked`() {
        val granted = probe(Capability.NETWORK, ProbeSignal.Granted(), at = 10L)
        assertNull(ScoutOpinions.of(granted))
    }
}
