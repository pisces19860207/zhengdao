package com.example.zhengdao.baopu.state.persona

internal enum class ValueAxis { FAMILY, CAREER, FREEDOM, SECURITY, INTIMACY, FACE, MONEY, FAITH }

internal data class ValueWeight(val axis: ValueAxis, val tier: Int)

internal data class PersonaValues(val weights: List<ValueWeight>)

internal object PersonaValueChecks {
    fun check(values: PersonaValues, core: CoreLayer): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        val axes = values.weights.map { it.axis }
        val duplicates = axes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (axes.size != ValueAxis.entries.size || axes.toSet() != ValueAxis.entries.toSet() || duplicates.isNotEmpty()) {
            out += CheckItem(Severity.ERROR, "VALUES_AXES", "values 八轴须各恰好一次；缺/重=${axes.toSet()}-${ValueAxis.entries.toSet()}/${duplicates}")
        }
        if (values.weights.any { it.tier !in 0..3 }) {
            out += CheckItem(Severity.ERROR, "VALUES_TIER", "values 档位只取 0–3")
        }
        if (out.isEmpty()) out += coreConflict(values, core)
        return out
    }

    private fun coreConflict(values: PersonaValues, core: CoreLayer): List<CheckItem> {
        val tier = values.weights.associate { it.axis to it.tier }
        val top = core.drives.filter { it.rank <= 2 }.map { it.need }.toSet()
        val securityCore = CoreNeed.SECURITY in top && tier[ValueAxis.SECURITY] == 0 && tier[ValueAxis.FREEDOM] == 3
        val freedomCore = CoreNeed.FREEDOM in top && tier[ValueAxis.FREEDOM] == 0 && tier[ValueAxis.SECURITY] == 3
        if (!securityCore && !freedomCore) return emptyList()
        val rank = core.drives.first { it.need == if (securityCore) CoreNeed.SECURITY else CoreNeed.FREEDOM }.rank
        return listOf(
            CheckItem(
                Severity.WARNING,
                "VALUES_CORE_CONFLICT",
                "core ${if (securityCore) "SECURITY" else "FREEDOM"} rank=$rank ｜ values " +
                    (if (securityCore) {
                        "安全${tier[ValueAxis.SECURITY]}/自由${tier[ValueAxis.FREEDOM]}"
                    } else {
                        "自由${tier[ValueAxis.FREEDOM]}/安全${tier[ValueAxis.SECURITY]}"
                    }) + " ｜ axes=SECURITY/FREEDOM",
            ),
        )
    }
}
