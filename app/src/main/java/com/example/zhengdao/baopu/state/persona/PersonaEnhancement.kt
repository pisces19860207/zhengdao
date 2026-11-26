package com.example.zhengdao.baopu.state.persona

internal data class PersonaEnhancement(
    val base: Persona,
    val values: PersonaValues,
    val state: PersonaMoodState,
    val contradictions: List<TraitContradiction>,
)

internal object PersonaEnhancementChecks {
    fun check(enhancement: PersonaEnhancement): CheckReport {
        val items = PersonaChecks.check(enhancement.base).items.toMutableList()
        items += PersonaValueChecks.check(enhancement.values, enhancement.base.core)
        items += PersonaStateChecks.check(enhancement.state)
        items += TraitContradictionChecks.check(enhancement.base.trait, enhancement.contradictions)
        enhancement.state.note?.let { note ->
            items += PersonaLanguage.clinical(note)
            items += PersonaLanguage.stigma(note)
            items += PersonaLanguage.determinism(note)
            items += PersonaLanguage.offSurfaceTags(listOf(note))
        }
        return CheckReport(items)
    }
}
