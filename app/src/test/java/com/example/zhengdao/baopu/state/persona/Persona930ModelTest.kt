package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
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
}
