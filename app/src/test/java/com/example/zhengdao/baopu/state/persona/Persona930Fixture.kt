package com.example.zhengdao.baopu.state.persona

internal object Persona930Fixture {
    fun values(): PersonaValues = PersonaValues(
        ValueAxis.entries.map { axis ->
            ValueWeight(axis, when (axis) {
                ValueAxis.FAMILY -> 3
                ValueAxis.CAREER -> 2
                ValueAxis.FREEDOM -> 1
                ValueAxis.SECURITY -> 0
                ValueAxis.INTIMACY -> 2
                ValueAxis.FACE -> 1
                ValueAxis.MONEY -> 1
                ValueAxis.FAITH -> 0
            })
        },
    )

    fun conflictingValues(): PersonaValues = PersonaValues(
        ValueAxis.entries.map { axis ->
            ValueWeight(axis, if (axis == ValueAxis.SECURITY) 0 else if (axis == ValueAxis.FREEDOM) 3 else 1)
        },
    )

    fun duplicateFamilyValues(): PersonaValues = PersonaValues(
        values().weights.map { if (it.axis == ValueAxis.FAMILY) it.copy(tier = 0) else it } +
            ValueWeight(ValueAxis.FAMILY, 3),
    )

    fun state(): PersonaMoodState = PersonaMoodState(
        mood = MoodTier.STEADY,
        emotion = Emotion.CALM,
        pressure = 1,
        note = "此刻有些紧",
    )

    fun contradictions(): List<TraitContradiction> = listOf(
        TraitContradiction(
            highAxis = TraitLayer.AGREEABLENESS,
            lowAxis = TraitLayer.CONSCIENTIOUSNESS,
            note = "愿意体谅别人，但自己的承诺常常没跟上",
        ),
    )

    fun enhancement(): PersonaEnhancement = PersonaEnhancement(
        base = PersonaFixture.persona().copy(
            trait = PersonaFixture.trait().copy(
                agreeableness = TraitLayer.explicit(3),
                conscientiousness = TraitLayer.explicit(0),
            ),
            surface = PersonaFixture.persona().surface.copy(tags = listOf("傲娇")),
        ),
        values = values(),
        state = state(),
        contradictions = contradictions(),
    )
}
