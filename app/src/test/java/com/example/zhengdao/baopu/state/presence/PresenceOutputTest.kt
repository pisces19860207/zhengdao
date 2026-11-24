// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
package com.example.zhengdao.baopu.state.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PresenceOutputTest {

    @Test
    fun `四档应对全部可达`() {
        val natural = PresenceChecks.output(routineScenes.first()).canBeNude
        val underwear = PresenceChecks.output(
            scene(sceneKind = SceneKind.OTHER, presence = PresenceKind.OPPOSITE_SEX),
        ).canBeNude
        val wrapped = PresenceChecks.output(
            scene(
                sceneKind = SceneKind.OTHER,
                presence = PresenceKind.OPPOSITE_SEX,
                kin = KinAvoidance.ALWAYS,
                age = AgeTier.SCHOOL,
            ),
        ).canBeNude
        val dressed = PresenceChecks.output(scene(outsider = true)).canBeNude
        assertEquals(
            listOf(CanBeNude.NATURAL, CanBeNude.UNDERWEAR, CanBeNude.WRAPPED, CanBeNude.FULLY_DRESSED),
            listOf(natural, underwear, wrapped, dressed),
        )
        assertEquals(4, CanBeNude.COUNT)
    }

    @Test
    fun `输出三件齐且why三段齐`() {
        val out = PresenceChecks.output(scene(presence = PresenceKind.SAME_SEX))
        assertTrue(out.why.contains("在场者="))
        assertTrue(out.why.contains("｜期待="))
        assertTrue(out.why.contains("｜场景="))
        assertTrue(out.line().contains("应对="))
        assertTrue(out.line().contains("可写="))
        assertTrue(out.line().contains("依据="))
    }

    @Test
    fun `穿条内裤足够_过度限制只提示并自动降级`() {
        val s = scene(
            sceneKind = SceneKind.OTHER,
            presence = PresenceKind.OPPOSITE_SEX,
            requestedNude = CanBeNude.FULLY_DRESSED,
        )
        val issues = PresenceChecks.issues(s)
        assertEquals(1, issues.size)
        assertEquals(PresenceChecks.HINT_OVER_DRESS, issues.first().code)
        assertEquals(PresenceVerdict.HINT, issues.first().verdict)
        assertEquals(FindingKind.AVOIDANCE, issues.first().kind)
        assertEquals(CanBeNude.UNDERWEAR, PresenceChecks.output(s).canBeNude)
    }

    @Test
    fun `明确外人时穿戴整齐可用且不提示`() {
        val s = scene(outsider = true, requestedNude = CanBeNude.FULLY_DRESSED)
        assertTrue(PresenceChecks.issues(s).isEmpty())
        assertEquals(CanBeNude.FULLY_DRESSED, PresenceChecks.output(s).canBeNude)
    }

    @Test
    fun `失真写法只提示不判红`() {
        listOf(DistortionFlag.WRAPPED_EVERYONE, DistortionFlag.NURSING_HIDDEN).forEach { flag ->
            val s = scene(distortion = flag)
            val issues = PresenceChecks.issues(s)
            assertEquals(1, issues.size)
            assertEquals(PresenceVerdict.HINT, issues.first().verdict)
            assertEquals(FindingKind.DISTORTION, issues.first().kind)
            assertEquals(PresenceVerdict.HINT, PresenceChecks.worst(s))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `分性别设限_整单抛错`() {
        PresenceChecks.all(scene(narrativeNote = "父亲不能给女儿洗澡"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `把照护写成有问题_整单抛错`() {
        PresenceChecks.all(scene(narrativeNote = "不该帮孩子洗澡"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `群体刻板判断_整单抛错`() {
        PresenceChecks.all(scene(narrativeNote = "某地人都保守"))
    }

    @Test
    fun `三岁前避讳提示自检有牙`() {
        val s = scene(age = AgeTier.UNDER_THREE, care = CareAction.BATH)
        val fake = PresenceChecks.PresenceFinding(
            FindingKind.AVOIDANCE, PresenceVerdict.HINT, "FAKE", "一条假避讳提示",
        )
        try {
            PresenceChecks.requireInfantCareClean(s, listOf(fake))
            fail("注入假避讳提示必须抛错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("三岁前"))
        }
    }

    @Test
    fun `同输入两遍逐字一致`() {
        val s = scene(
            venue = Venue.HOME,
            presence = PresenceKind.OPPOSITE_SEX,
            kin = KinAvoidance.YOUNG_OK_LATER,
            age = AgeTier.SCHOOL,
            oppositeSexKin = true,
            distortion = DistortionFlag.NURSING_HIDDEN,
        )
        assertEquals(PresenceChecks.sceneLine(s), PresenceChecks.sceneLine(s))
        assertEquals(PresenceChecks.output(s).line(), PresenceChecks.output(s).line())
    }

}
