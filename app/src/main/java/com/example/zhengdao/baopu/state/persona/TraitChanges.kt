package com.example.zhengdao.baopu.state.persona

internal data class TraitChangeEvidence(
    val eventRef: String? = null,
    val latentRef: String? = null,
    val longtermRef: String? = null,
)

internal data class TraitContradiction(val highAxis: String, val lowAxis: String, val note: String)

internal object TraitChanges {
    fun review(before: TraitLayer, after: TraitLayer, evidence: TraitChangeEvidence): CheckReport {
        val changed = changedAxes(before, after)
        if (changed.isEmpty()) {
            return CheckReport(listOf(CheckItem(Severity.WARNING, "TRAIT_CHANGE_NONE", "前后特质无变化")))
        }
        val hasLatent = !evidence.latentRef.isNullOrBlank()
        val hasLongterm = !evidence.longtermRef.isNullOrBlank()
        val hasEvent = !evidence.eventRef.isNullOrBlank()
        val code = when {
            !hasEvent && !hasLatent && !hasLongterm -> "TRAIT_CHANGE_NO_ROOT"
            hasEvent && !hasLatent && !hasLongterm -> "TRAIT_CHANGE_SINGLE_EVENT"
            else -> null
        }
        if (code == null) return CheckReport(emptyList())
        return CheckReport(
            listOf(CheckItem(Severity.ERROR, code, "特质变动须慢变根；变化轴=${changed.joinToString("/")}")),
        )
    }

    fun changedAxes(before: TraitLayer, after: TraitLayer): List<String> {
        val old = listOf(
            "sociability" to (before.sociability to after.sociability),
            "directness" to (before.directness to after.directness),
            "stance" to (before.stance to after.stance),
            "orientation" to (before.orientation to after.orientation),
            "selfControl" to (before.selfControl to after.selfControl),
            "aggressiveness" to (before.aggressiveness to after.aggressiveness),
            "humor" to (before.humor to after.humor),
        )
        val five = TraitLayer.BIG_FIVE_AXES.map { axis ->
            axis to (before.bigFiveTier(axis) to after.bigFiveTier(axis))
        }
        return (old + five).filter { (_, pair) -> pair.first != pair.second }.map { (axis, _) -> axis }
    }
}

internal object TraitContradictionChecks {
    fun check(trait: TraitLayer, contradictions: List<TraitContradiction>): List<CheckItem> {
        if (contradictions.isEmpty()) {
            return listOf(
                CheckItem(Severity.WARNING, "TRAIT_CONTRADICTION_MISSING", "无内部矛盾：可能是人设不是人格"),
            )
        }
        return contradictions.flatMapIndexed { index, item ->
            val high = trait.bigFiveTier(item.highAxis)
            val low = trait.bigFiveTier(item.lowAxis)
            val valid = item.highAxis in TraitLayer.BIG_FIVE_AXES &&
                item.lowAxis in TraitLayer.BIG_FIVE_AXES &&
                item.highAxis != item.lowAxis &&
                high != null && low != null && high >= 2 && low <= 1 &&
                item.note.isNotBlank()
            val errors = if (valid) emptyList() else listOf(
                CheckItem(Severity.ERROR, "TRAIT_CONTRADICTION_INVALID", "矛盾#$index 不满足高档>=2/低档<=1：${item.highAxis}/${item.lowAxis}"),
            )
            errors + PersonaLanguage.clinical(item.note) + PersonaLanguage.stigma(item.note) +
                PersonaLanguage.determinism(item.note) + PersonaLanguage.offSurfaceTags(listOf(item.note))
        }
    }
}
