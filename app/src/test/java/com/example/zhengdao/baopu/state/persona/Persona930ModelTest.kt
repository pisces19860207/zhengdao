package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Persona930ModelTest {
    @Test
    fun `五轴省略时为中档一并标记DEFAULT`() {
        val trait = PersonaFixture.trait()
        assertEquals(5, trait.bigFiveLevels().size)
        assertTrue(trait.bigFiveLevels().all { it.second.tier == 1 })
        assertTrue(trait.bigFiveLevels().all { it.second.source == "DEFAULT" })
    }

    @Test
    fun `五轴显式值标记EXPLICIT且越界被拒`() {
        val trait = PersonaFixture.trait().copy(
            agreeableness = TraitLayer.explicit(3),
            conscientiousness = TraitLayer.explicit(0),
        )
        assertEquals(3, trait.bigFiveTier(TraitLayer.AGREEABLENESS))
        assertEquals(0, trait.bigFiveTier(TraitLayer.CONSCIENTIOUSNESS))
        assertEquals("EXPLICIT", trait.agreeableness.source)
        val threw = runCatching { TraitLayer.explicit(4) }.exceptionOrNull()
        assertTrue(threw is IllegalArgumentException)
    }

    @Test
    fun `values八轴完整合法且core直接冲突只WARN`() {
        val clean = PersonaValueChecks.check(Persona930Fixture.values(), PersonaFixture.core())
        assertTrue(clean.none { it.severity == Severity.ERROR })

        val core = PersonaFixture.core().copy(
            drives = listOf(
                CoreDrive(CoreNeed.SECURITY, "被抛下", 1, "关系不确定时"),
                CoreDrive(CoreNeed.BELONGING, "无处可去", 2, "进入陌生场合时"),
                CoreDrive(CoreNeed.RECOGNITION, "被忽略", 3, "当众被忽略时"),
            ),
        )
        val conflict = PersonaValueChecks.check(Persona930Fixture.conflictingValues(), core)
        val warning = conflict.single { it.code == "VALUES_CORE_CONFLICT" }
        assertEquals(Severity.WARNING, warning.severity)
        assertTrue(warning.message.contains("SECURITY"))
        assertTrue(warning.message.contains("FREEDOM"))
        assertTrue(warning.message.contains("安全0"))
        assertTrue(warning.message.contains("自由3"))

        val freedomCore = core.copy(
            drives = listOf(
                CoreDrive(CoreNeed.FREEDOM, "被限制", 1, "被约束时"),
                CoreDrive(CoreNeed.BELONGING, "无处可去", 2, "进入陌生场合时"),
                CoreDrive(CoreNeed.RECOGNITION, "被忽略", 3, "当众被忽略时"),
            ),
        )
        val reverseValues = Persona930Fixture.conflictingValues().copy(
            weights = Persona930Fixture.conflictingValues().weights.map {
                if (it.axis == ValueAxis.SECURITY) it.copy(tier = 3) else if (it.axis == ValueAxis.FREEDOM) it.copy(tier = 0) else it
            },
        )
        val reverse = PersonaValueChecks.check(reverseValues, freedomCore)
        assertEquals("VALUES_CORE_CONFLICT", reverse.single().code)
        assertTrue(reverse.single().message.contains("自由0/安全3"))
    }

    @Test
    fun `values缺轴重轴越界与同轴冲突均红`() {
        val missing = Persona930Fixture.values().copy(weights = Persona930Fixture.values().weights.drop(1))
        val duplicate = Persona930Fixture.duplicateFamilyValues()
        val invalid = Persona930Fixture.values().copy(
            weights = Persona930Fixture.values().weights.map { if (it.axis == ValueAxis.MONEY) it.copy(tier = 4) else it },
        )
        listOf(missing, duplicate, invalid).forEach { value ->
            assertTrue(PersonaValueChecks.check(value, PersonaFixture.core()).any { it.severity == Severity.ERROR })
        }
        assertTrue(PersonaValueChecks.check(duplicate, PersonaFixture.core()).any {
            it.code == "VALUES_AXES" && it.message.contains("FAMILY")
        })
    }

    @Test
    fun `动态状态只记录压力与说明不反推稳定层`() {
        assertTrue(PersonaStateChecks.check(Persona930Fixture.state()).isEmpty())
        val bad = Persona930Fixture.state().copy(pressure = 4)
        assertEquals(
            "STATE_PRESSURE",
            PersonaStateChecks.check(bad).single { it.severity == Severity.ERROR }.code,
        )
    }

    @Test
    fun `927单事件改特质红latent与longterm可带事件`() {
        val before = PersonaFixture.trait()
        val after = before.copy(agreeableness = TraitLayer.explicit(3))
        val eventOnly = TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "e1"))
        assertEquals(listOf("agreeableness"), TraitChanges.changedAxes(before, after))
        assertTrue("TRAIT_CHANGE_SINGLE_EVENT" in eventOnly.errors.map { it.code })
        val latent = TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "e1", latentRef = "l1"))
        assertTrue(latent.passed)
        val longterm = TraitChanges.review(before, after, TraitChangeEvidence(longtermRef = "t1"))
        assertTrue(longterm.passed)
        assertEquals(latent.line(), TraitChanges.review(before, after, TraitChangeEvidence(eventRef = "e1", latentRef = "l1")).line())
    }

    @Test
    fun `927无根红无变化提示`() {
        val trait = PersonaFixture.trait()
        val noRoot = TraitChanges.review(trait, trait.copy(humor = Humor.WARM), TraitChangeEvidence())
        assertTrue("TRAIT_CHANGE_NO_ROOT" in noRoot.errors.map { it.code })
        val none = TraitChanges.review(trait, trait, TraitChangeEvidence(longtermRef = "t1"))
        assertTrue("TRAIT_CHANGE_NONE" in none.warnings.map { it.code })
    }

    @Test
    fun `内部矛盾高宜人低尽责有实际读数`() {
        val trait = PersonaFixture.trait().copy(
            agreeableness = TraitLayer.explicit(3),
            conscientiousness = TraitLayer.explicit(0),
        )
        val report = TraitContradictionChecks.check(trait, Persona930Fixture.contradictions())
        assertTrue(report.isEmpty())
        assertEquals(3, trait.bigFiveTier(TraitLayer.AGREEABLENESS))
        assertEquals(0, trait.bigFiveTier(TraitLayer.CONSCIENTIOUSNESS))
        val missing = TraitContradictionChecks.check(trait, emptyList())
        assertEquals("TRAIT_CONTRADICTION_MISSING", missing.single().code)
        assertTrue(missing.single().message.contains("可能是人设不是人格"))
        val invalidCases = listOf(
            trait.copy(agreeableness = TraitLayer.explicit(1)) to Persona930Fixture.contradictions(),
            trait to listOf(TraitContradiction(TraitLayer.AGREEABLENESS, TraitLayer.AGREEABLENESS, "同轴")),
            trait to listOf(TraitContradiction("unknown", TraitLayer.CONSCIENTIOUSNESS, "未知轴")),
            trait to listOf(TraitContradiction(TraitLayer.AGREEABLENESS, TraitLayer.CONSCIENTIOUSNESS, "")),
        )
        invalidCases.forEach { (changed, items) ->
            assertTrue("TRAIT_CONTRADICTION_INVALID" in TraitContradictionChecks.check(changed, items).map { it.code })
        }
    }

    @Test
    fun `930聚合总检合法且同输入逐字一致`() {
        val a = PersonaEnhancementChecks.check(Persona930Fixture.enhancement())
        val b = PersonaEnhancementChecks.check(Persona930Fixture.enhancement())
        assertTrue(a.line(), a.passed)
        assertEquals(a, b)
        assertEquals(a.line(), b.line())
    }

    @Test
    fun `930聚合总检按四层检查顺序组合完整条目`() {
        val base = PersonaFixture.persona().copy(
            core = PersonaFixture.core().copy(
                drives = PersonaFixture.core().drives.map {
                    if (it.need == CoreNeed.RECOGNITION) it.copy(coreFear = "NPD") else it
                },
            ),
        )
        val x = PersonaEnhancement(
            base = base,
            values = Persona930Fixture.duplicateFamilyValues(),
            state = Persona930Fixture.state().copy(pressure = 4, note = null),
            contradictions = listOf(TraitContradiction(TraitLayer.AGREEABLENESS, TraitLayer.AGREEABLENESS, "同轴")),
        )
        val expected = PersonaChecks.check(x.base).items +
            PersonaValueChecks.check(x.values, x.base.core) +
            PersonaStateChecks.check(x.state) +
            TraitContradictionChecks.check(x.base.trait, x.contradictions)
        val actual = PersonaEnhancementChecks.check(x).items
        assertEquals(expected, actual)
        assertTrue(listOf("DIAGNOSIS", "VALUES_AXES", "STATE_PRESSURE", "TRAIT_CONTRADICTION_INVALID").all {
            code -> actual.any { it.code == code }
        })
    }

    @Test
    fun `930动态说明临床词按台词与叙述者三态接入`() {
        val dialogue = Persona930Fixture.enhancement().copy(
            state = Persona930Fixture.state().copy(note = "他说：「我是NPD。」又说：「你这个舔狗。」她很傲娇。人格决定命运。"),
        )
        val dialogueReport = PersonaEnhancementChecks.check(dialogue)
        assertFalse("DIAGNOSIS" in dialogueReport.errors.map { it.code })
        val dialogueCodes = dialogueReport.items.map { it.code }
        assertTrue("STIGMA_DIALOGUE" in dialogueCodes)
        assertTrue("ANIME_TAG_OFF_SURFACE" in dialogueCodes)
        assertTrue("DETERMINISM_FORBIDDEN" in dialogueCodes)
        val narration = Persona930Fixture.enhancement().copy(
            state = Persona930Fixture.state().copy(note = "他是NPD。"),
        )
        assertTrue("DIAGNOSIS" in PersonaEnhancementChecks.check(narration).errors.map { it.code })
    }
}
