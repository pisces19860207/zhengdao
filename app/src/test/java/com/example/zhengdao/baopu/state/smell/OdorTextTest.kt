package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.environment.EnvironmentSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdorTextTest {

    private fun base(note: String = "") = OdorClaim(
        odorPresent = OdorPresence.FAINT,
        odorSource = setOf(OdorSource.CLOTHING_SMOKE),
        odorWord = setOf(OdorWord.SMOKE),
        odorRegion = setOf(OdorRegion.AXILLA),
        odorTemporal = OdorTemporal.SAME_DAY,
        odorSeasonal = OdorSeason.RAINY_DAMP,
        climateEvidence = ClimateEvidence(
            env657 = EnvironmentSolver.solve(31.0, 200, 12.0, 0, 0.0, 22.0, 50.0, 0, 0)
        ),
        odorClimateTrend = Trend.FLAT,
        note = note,
    )

    @Test
    fun `病名诊断_出现即红`() {
        listOf("狐臭", "感染味", "尿道炎", "梅毒", "无病").forEach { word ->
            assertTrue(word, Odor.judge(base(word)).reds.any { it.code == OdorCodes.DIAGNOSIS })
        }
    }

    @Test
    fun `人格地位判断_出现即红`() {
        listOf("不讲卫生", "底层", "邋遢").forEach { word ->
            assertTrue(word, Odor.judge(base(word)).reds.any { it.code == OdorCodes.VALUE_JUDGMENT })
        }
    }

    @Test
    fun `行为指导_出现即红`() {
        listOf("推荐香水", "遮味", "就医", "用些香水", "借助香味遮蔽体味").forEach { word ->
            assertTrue(word, Odor.judge(base(word)).reds.any { it.code == OdorCodes.BEHAVIOR_GUIDANCE })
        }
    }

    @Test
    fun `职业直推当下气味_出现即红`() {
        listOf(
            "因为他是工人所以有汗味",
            "工人所以现在有汗味",
            "因为他是工人，今天有汗味",
            "因为他是工人，今天菜有油味，他也有汗味",
        ).forEach { note ->
            val verdict = Odor.judge(base(note))
            assertTrue(note, verdict.reds.any { it.code == OdorCodes.OCCUPATION_INFERENCE })
        }
    }

    @Test
    fun `点值成分_出现即红`() {
        listOf("pH 5", "浓度 20%", "2mL").forEach { word ->
            assertTrue(word, Odor.judge(base(word)).reds.any { it.code == OdorCodes.POINT_VALUE })
        }
    }

    @Test
    fun `洗后仍味_未洗净例外`() {
        assertTrue(Odor.judge(base("洗过仍有味")).reds.any { it.code == OdorCodes.WASHED_STILL_ODOR })
        assertTrue(Odor.judge(base("洗过仍有味，因为未洗净")).ok)
        assertTrue(Odor.judge(base("洗过仍有味，因为检查发现没洗净")).ok)
        listOf("若没洗净，洗过仍有味", "可能没洗净，洗过仍有味").forEach { note ->
            assertTrue(note, Odor.judge(base(note)).reds.any { it.code == OdorCodes.WASHED_STILL_ODOR })
        }
    }

    @Test
    fun `中性描述_原样放行`() {
        listOf(
            "衣物上有淡淡烟味，靠近时可辨",
            "厨师做的菜有油烟味",
            "厨师说菜可能有油味",
            "天气炎热",
            "强度引用883",
        ).forEach { text ->
            val verdict = Odor.judge(base(text))
            assertTrue(text, verdict.ok)
            assertEquals(text, verdict.value?.note)
        }
    }

    @Test
    fun `文案红线_大小写与变体都不许绕`() {
        assertTrue(Odor.judge(base("异味说明有病")).reds.any { it.code == OdorCodes.DIAGNOSIS })
        assertTrue(Odor.judge(base("可以用香水掩盖气味")).reds.any {
            it.code == OdorCodes.BEHAVIOR_GUIDANCE
        })
        assertTrue(Odor.judge(base("PH 5.2")).reds.any { it.code == OdorCodes.POINT_VALUE })
    }

    @Test
    fun `文案红线_疾病建议与自报强度都不许绕`() {
        assertTrue(Odor.judge(base("糖尿病")).reds.any { it.code == OdorCodes.DIAGNOSIS })
        assertTrue(Odor.judge(base("去洗澡")).reds.any { it.code == OdorCodes.BEHAVIOR_GUIDANCE })
        assertTrue(Odor.judge(base("气味强度为2")).reds.any {
            it.code == OdorCodes.INTENSITY_UNSOURCED
        })
    }

    @Test
    fun `文案改写_五类红线都不许绕`() {
        val note = "疑似龟头炎，没有教养，用些香水盖味；工人所以现在有汗味；气味强度十分"
        val codes = Odor.judge(base(note)).reds.map { it.code }.toSet()
        listOf(
            OdorCodes.DIAGNOSIS,
            OdorCodes.VALUE_JUDGMENT,
            OdorCodes.BEHAVIOR_GUIDANCE,
            OdorCodes.OCCUPATION_INFERENCE,
            OdorCodes.INTENSITY_UNSOURCED,
        ).forEach { assertTrue(it, it in codes) }
    }

    @Test
    fun `禁词表_分类之间无重复且不含中性词`() {
        val banned = OdorWords.DIAGNOSIS + OdorWords.VALUE_JUDGMENT + OdorWords.BEHAVIOR_GUIDANCE +
            OdorWords.OCCUPATION_INFERENCE + OdorWords.FRESH_SWEAT_SOUR + OdorWords.WASHED_STILL_ODOR +
            OdorWords.POINT_VALUE
        assertEquals(banned.size, banned.toSet().size)
        assertTrue(banned.none { it in OdorWords.NEUTRAL })
    }
}
