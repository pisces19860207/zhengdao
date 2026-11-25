// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

internal object HygieneRenderer {

    fun render(state: HygieneState, derivation: HygieneDerivation): String {
        val text = buildString {
            appendState(state, derivation)
            if (state.smegmaTier.level >= SmegmaTier.ODOR_MIN_LEVEL) {
                append("|气味强度=").append(smellLine(derivation.smellIntensity))
                append('|').append(derivation.unavailableLine())
            }
            append('|').append(HYGIENE_DISCLAIMER)
        }
        HygieneGuard.requireNeutralText(text)
        HygieneGuard.requireOdorMention(state.smegmaTier, text)
        return text
    }

    private fun StringBuilder.appendState(state: HygieneState, derivation: HygieneDerivation) {
        append("包皮垢=").append(state.smegmaTier.label)
            .append("|色档=").append(state.color.label)
            .append("|清洁间隔=").append(state.cleaningFreq.label)
            .append("|可回缩=").append(state.retractability.label)
            .append("|个体意愿=").append(state.hygieneHabit.label)
            .append("|客观洗浴条件=").append(state.washCondition.label)
            .append("|全身洗澡=").append(state.bathFreq.label)
            .append("|洗脚=").append(state.localWash.foot.label)
            .append("|下体=").append(state.localWash.genitals.label)
            .append("|推动者=").append(state.hygieneDriver.label)
            .append("|表推包皮垢=").append(derivation.smegmaTier.line())
    }

    private fun smellLine(slot: SmellSlot): String = when (slot) {
        is SmellSlot.Given -> "${slot.intensity.label}|${slot.rowId}|${slot.basis}"
        is Absent -> "○(${slot.reason})"
    }
}
