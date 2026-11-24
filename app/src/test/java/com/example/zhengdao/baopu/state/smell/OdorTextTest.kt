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
        listOf("狐臭", "臭汗症", "感染味", "尿道炎", "梅毒", "癌症", "癌症味", "细菌感染", "脚气", "无病").forEach { word ->
            assertTrue(word, Odor.judge(base(word)).reds.any { it.code == OdorCodes.DIAGNOSIS })
        }
    }

    @Test
    fun `人格地位判断_出现即红`() {
        listOf(
            "不讲卫生", "底层", "邋遢", "你真脏", "他真脏", "体味说明他脏", "体味说明此人很脏",
        ).forEach { word ->
            assertTrue(word, Odor.judge(base(word)).reds.any { it.code == OdorCodes.VALUE_JUDGMENT })
        }
    }

    @Test
    fun `行为指导_出现即红`() {
        listOf(
            "推荐香水", "遮味", "就医", "用些香水", "借助香味遮蔽体味",
            "已经打算用香水", "最好拿香水应急", "上次建议用香水", "之前推荐香水",
            "他说要洗澡，以后买香水", "以后买香水", "下次买香水",
            "他说要洗澡，叮嘱他买香水", "他提醒我买香水", "他要求我买香水",
        ).forEach { word ->
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
            "他是教师，所以今天有汗味",
            "职业是教师，因此现在有汗味",
            "身为厨师，所以今天有汗味",
            "他做厨师，所以有汗味",
            "教师所以今天有汗味",
            "警察所以今天有汗味",
            "记者所以现在有气味",
            "他是屠夫，所以现在有汗味",
            "他是泥瓦匠，因此今天有体味",
            "他是警察，所以今天有汗味",
            "他是记者，所以现在有气味",
            "商人所以现在有汗味",
            "他是商人，所以现在有汗味",
            "他是老师，所以自上周以来一直有汗味",
            "他是老师，所以上周至今一直有汗味",
            "他是老师，自上周一直有汗味",
            "他是老师，自上周五一直有汗味",
            "他是老师，从上周五起一直有汗味",
            "他是老师，所以自上周以来一直没汗味",
            "他是老师，所以上周到今日一直有汗味",
            "他是老师，自上周以来并非没有汗味",
            "他是老师，自上周以来不是没有汗味",
            "他是老师，因此，今天有汗味",
            "他是老师，所以今天有异味",
            "工人所以现在没汗味",
            "因为他是工人所以没有汗味",
        ).forEach { note ->
            val verdict = Odor.judge(base(note))
            assertTrue(note, verdict.reds.any { it.code == OdorCodes.OCCUPATION_INFERENCE })
        }
    }

    @Test
    fun `文案自报强度_强弱词数字与算式都不许绕`() {
        listOf(
            "汗味很重", "汗味很重啊", "汗味很重了", "汗味很重的", "气味=2+1", "体味2", "气味很浓",
            "气味强度等于2", "气味强度达到2", "气味强度≈2", "体味约为2", "气味强度已经达到2",
            "汗味已经达到2", "气味很轻", "气味较轻", "气味极淡",
        ).forEach { note ->
            val verdict = Odor.judge(base(note))
            assertTrue(note, verdict.reds.any { it.code == OdorCodes.INTENSITY_UNSOURCED })
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
        assertTrue(Odor.judge(base("不排除天气原因，他明确没洗净，洗过仍有味")).ok)
        listOf(
            "若没洗净，洗过仍有味",
            "可能没洗净，洗过仍有味",
            "不排除没洗净，洗过仍有味",
            "待核实没洗净，洗过仍有味",
            "尚需确认没洗净，洗过仍有味",
        ).forEach { note ->
            assertTrue(note, Odor.judge(base(note)).reds.any { it.code == OdorCodes.WASHED_STILL_ODOR })
        }
    }

    @Test
    fun `历史香水使用_只陈述不指导_历史建议仍红`() {
        listOf(
            "以前喷过香水", "之前喷过香水", "上次用了香水", "昨天洒了香水", "上次用香水掩盖了汗味",
            "上次香水掩盖了汗味", "上次买了香水，今天要洗澡", "上次买了香水今天要洗澡",
            "他说要洗澡，上次买了香水", "他买了香水", "昨天买香水",
            "昨天说今天用了香水", "他昨天说今天已经用了香水", "昨天说今天买了香水",
            "昨天说今天没用香水", "昨天说今天没有用香水", "昨天说今天没买香水",
        ).forEach { text ->
            val verdict = Odor.judge(base(text))
            assertTrue(text, verdict.ok)
            assertEquals(text, verdict.value?.note)
        }
        listOf(
            "上次建议用香水", "之前推荐香水", "刚才说应该用香水", "已经打算用香水", "上次让我用香水",
            "上次叫我用香水掩盖体味", "他上次说以后喷香水掩盖体味", "昨天说要多喷点香水",
            "上次建议用香水掩盖体味", "上次想用香水", "他喷香水",
            "他上次说以后喷香水", "他上次说以后买香水",
            "他说以后买香水", "他要求以后买香水", "他禁止以后喷香水",
            "他说，以后买香水", "他上次禁止以后喷香水", "昨天说今天用香水",
        ).forEach { text ->
            assertTrue(text, Odor.judge(base(text)).reds.any { it.code == OdorCodes.BEHAVIOR_GUIDANCE })
        }
    }

    @Test
    fun `职业与历史气味_仅共现不直推_当前仍有才红`() {
        val historical = "他是老师，上周有汗味，今天没味"
        val historicalVerdict = Odor.judge(base(historical))
        assertTrue(historicalVerdict.issues.joinToString { it.line() }, historicalVerdict.ok)
        val datedHistorical = "他是老师，上周五一直有汗味"
        assertTrue(Odor.judge(base(datedHistorical)).ok)
        val bareWeekHistorical = "他是老师，上周一直有汗味"
        assertTrue(Odor.judge(base(bareWeekHistorical)).ok)
        val negatedCurrent = "他是老师，上周有汗味，今天无体味"
        assertTrue(Odor.judge(base(negatedCurrent)).ok)
        val negatedIntensity = "他是老师，今天没有明显汗味"
        assertTrue(Odor.judge(base(negatedIntensity)).ok)
        val negatedUn = "他是老师，今天未有汗味"
        assertTrue(Odor.judge(base(negatedUn)).ok)
        val postposedHistory = "他是老师，有汗味是上周发生的"
        assertTrue(Odor.judge(base(postposedHistory)).ok)
        val historicalOdorSynonym = "他是老师，上周有异味，今天没味"
        assertTrue(Odor.judge(base(historicalOdorSynonym)).ok)
        val continuousNegated = "他是老师，自上周以来一直没汗味"
        assertTrue(Odor.judge(base(continuousNegated)).ok)
        val continuousDirectNegated = "他是老师，自上周一直没汗味"
        assertTrue(Odor.judge(base(continuousDirectNegated)).ok)
        val continuousNegatedSynonym = "他是老师，上周至今一直没有体味"
        assertTrue(Odor.judge(base(continuousNegatedSynonym)).ok)
        val continuousNegatedUn = "他是老师，自上周以来一直未有汗味"
        assertTrue(Odor.judge(base(continuousNegatedUn)).ok)
        val current = "他是老师，上周有汗味，今天有汗味"
        assertTrue(Odor.judge(base(current)).reds.any { it.code == OdorCodes.OCCUPATION_INFERENCE })
        val continuousDoubleNegated = "他是老师，自上周以来并非一直没有汗味"
        assertTrue(Odor.judge(base(continuousDoubleNegated)).reds.any {
            it.code == OdorCodes.OCCUPATION_INFERENCE
        })
    }

    @Test
    fun `中性描述_原样放行`() {
        listOf(
            "衣物上有淡淡烟味，靠近时可辨",
            "厨师做的菜有油烟味",
            "厨师说菜可能有油味",
            "天气炎热",
            "强度引用883",
            "衣物不脏，只有淡烟味",
            "上次用了香水，今天只有淡烟味",
            "她看着脏衣物",
            "脚气味",
            "他做饭，所以有汗味",
            "他认为有汗味",
            "他是学生，所以有汗味",
            "他是丈夫，所以有汗味",
            "他是我的丈夫，所以有汗味",
            "他是老师的丈夫，所以有汗味",
            "她是女学生，所以有体味",
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
