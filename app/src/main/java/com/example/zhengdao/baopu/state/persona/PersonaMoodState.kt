package com.example.zhengdao.baopu.state.persona

internal enum class MoodTier(val label: String) { LOW("低落"), STEADY("平稳"), LIGHT("轻快"), HIGH("高涨") }

internal enum class Emotion { CALM, JOY, SADNESS, ANGER, FEAR, DISGUST }

internal data class PersonaMoodState(
    val mood: MoodTier,
    val emotion: Emotion,
    val pressure: Int,
    val note: String? = null,
)

internal object PersonaStateChecks {
    fun check(state: PersonaMoodState): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        if (state.pressure !in 0..3) {
            out += CheckItem(Severity.ERROR, "STATE_PRESSURE", "压力档位只取 0–3：${state.pressure}")
        }
        return out
    }
}
