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
}
