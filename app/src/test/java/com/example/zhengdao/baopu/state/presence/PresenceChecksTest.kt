// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceChecksTest {

    @Test
    fun `穷举_红当且仅当两条规则`() {
        val ages: List<AgeTier?> = listOf(null) + AgeTier.entries
        val presences: List<PresenceKind?> = listOf(null) + PresenceKind.entries
        var checked = 0
        for (minor in MinorPresence.entries) {
            for (sexual in SexualSignal.entries) {
                for (age in ages) {
                    for (care in CareAction.entries) {
                        for (venue in Venue.entries) {
                            for (presence in presences) {
                                for (kin in KinAvoidance.entries) {
                                    for (outsider in listOf(false, true)) {
                                        val s = scene(
                                            venue = venue,
                                            presence = presence,
                                            minor = minor,
                                            age = age,
                                            care = care,
                                            kin = kin,
                                            sexual = sexual,
                                            outsider = outsider,
                                        )
                                        val actual = PresenceChecks.issues(s)
                                            .filter { it.verdict == PresenceVerdict.DENY }
                                            .map { it.code }
                                        val expected = mutableListOf<String>()
                                        if (minor == MinorPresence.ACCOMPANIED &&
                                            sexual != SexualSignal.NONE
                                        ) {
                                            expected.add(PresenceChecks.RedRule.CHILD_SEXUAL.code)
                                        }
                                        if (minor == MinorPresence.UNDERAGE_LEAD &&
                                            sexual != SexualSignal.NONE
                                        ) {
                                            expected.add(PresenceChecks.RedRule.UNDERAGE_SEXUAL.code)
                                        }
                                        assertEquals("组合 " + s.toString(), expected, actual)
                                        checked++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(95256, checked)
    }

    @Test
    fun `红规则表恰两条且码冻结`() {
        assertEquals(2, PresenceChecks.RedRule.COUNT)
        assertEquals(2, PresenceChecks.RedRule.entries.size)
        assertEquals(
            listOf("RED-CHILD-SEXUAL", "RED-UNDERAGE-SEXUAL"),
            PresenceChecks.RedRule.entries.map { it.code },
        )
    }

    @Test
    fun `澡堂六场所夹具_issue为空`() {
        assertEquals(6, routineScenes.size)
        routineScenes.forEach { s ->
            assertTrue(s.venue.label, PresenceChecks.issues(s).isEmpty())
            assertEquals(CanBeNude.NATURAL, PresenceChecks.output(s).canBeNude)
        }
    }

    @Test
    fun `亲子共浴同睡换衣_issue为空`() {
        assertTrue(parentChildScenes.isNotEmpty())
        parentChildScenes.forEach { s ->
            assertTrue(s.toString(), PresenceChecks.issues(s).isEmpty())
            PresenceChecks.requireInfantCareClean(s)
        }
    }

    @Test
    fun `三岁前照护_issue为空且零避讳提示`() {
        assertEquals(CareAction.CARES.size * 2 * 2 * 3 * 3, infantCareScenes.size)
        infantCareScenes.forEach { s ->
            assertTrue(s.toString(), PresenceChecks.issues(s).isEmpty())
            assertTrue(s.toString(), PresenceChecks.avoidanceIssues(s).isEmpty())
            PresenceChecks.requireInfantCareClean(s)
        }
    }

    @Test
    fun `哺乳零避讳且不因婴儿性别设限`() {
        nursingScenes.forEach { s ->
            assertTrue(s.toString(), PresenceChecks.issues(s).isEmpty())
            assertEquals(CanBeNude.NATURAL, PresenceChecks.output(s).canBeNude)
            assertEquals(CanDescribe.DAILY, PresenceChecks.output(s).canDescribe)
        }
    }

    @Test
    fun `未成年同性日常_零额外报警`() {
        assertTrue(minorSameSexDailyScenes.isNotEmpty())
        minorSameSexDailyScenes.forEach { s ->
            assertTrue(s.toString(), PresenceChecks.issues(s).isEmpty())
            assertEquals(CanDescribe.DAILY, PresenceChecks.output(s).canDescribe)
        }
    }

    @Test
    fun `未成年异性日常_只倾向不红`() {
        minorOppositeSexDailyScenes.forEach { s ->
            assertTrue(s.toString(), PresenceChecks.issues(s).isEmpty())
            assertEquals(PresenceVerdict.PASS, PresenceChecks.worst(s))
            assertTrue(PresenceChecks.output(s).why.contains("倾向="))
        }
    }
}
