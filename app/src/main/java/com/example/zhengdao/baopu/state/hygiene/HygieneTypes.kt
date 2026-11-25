// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.RetractState

internal const val HYGIENE_SOURCE =
    "派工单-2026-11-24-第八百八十三批（包皮垢与清洁状态档）＋本项目工程假设"
internal const val HYGIENE_AS_OF = "2026-11-24"
internal const val HYGIENE_DISCLAIMER = "非医学·只记档位与分段派生"

internal enum class SmegmaTier(val level: Int, val label: String) {
    NONE(0, "无"),
    TRACE(1, "少量"),
    OBVIOUS(2, "明显"),
    BUILDUP(3, "堆积"),
    ;

    companion object {
        const val COUNT = 4
        const val ODOR_MIN_LEVEL = 2

        fun of(level: Int): SmegmaTier =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("包皮垢档越界：只收 0–3")
    }
}

internal enum class SmegmaColor(val label: String) {
    WHITE("白"),
    PALE_YELLOW("微黄"),
    YELLOW("黄"),
}

internal enum class WashFrequency(val level: Int, val label: String) {
    DAILY(0, "每日"),
    EVERY_OTHER_DAY(1, "隔日"),
    EVERY_FEW_DAYS(2, "数日"),
    OVER_WEEK(3, "一周以上"),
    ;

    companion object {
        const val COUNT = 4
    }
}

internal enum class HygieneHabit(val level: Int, val label: String) {
    NONE(0, "无意愿"),
    LOW(1, "弱意愿"),
    MIDDLE(2, "中意愿"),
    HIGH(3, "强意愿"),
    ;

    companion object {
        const val COUNT = 4
    }
}

internal enum class WashCondition(val level: Int, val label: String) {
    AVAILABLE(0, "随时可洗"),
    RESTRICTED(1, "受限"),
    VERY_RESTRICTED(2, "极受限"),
    ;

    companion object {
        const val COUNT = 3
    }
}

internal enum class HygieneDriver(val label: String) {
    SELF("自主"),
    REMINDED("被提醒"),
    EXPLICITLY_REQUIRED("被明确要求"),
    OTHER_DONE("他人代做（备水／放好衣物）"),
}

internal data class LocalWash(
    val foot: WashFrequency,
    val genitals: WashFrequency,
)

internal data class HygieneState(
    val smegmaTier: SmegmaTier,
    val cleaningFreq: WashFrequency,
    val retractability: RetractState,
    val color: SmegmaColor,
    val hygieneHabit: HygieneHabit,
    val washCondition: WashCondition,
    val bathFreq: WashFrequency,
    val localWash: LocalWash,
    val hygieneDriver: HygieneDriver,
)

internal enum class WarmHumidTier(val level: Int, val label: String) {
    DRY(0, "干爽"),
    MILD(1, "温和"),
    HOT_HUMID(2, "湿热"),
    ;

    companion object {
        const val COUNT = 3
    }
}

internal enum class SweatAmount(val level: Int, val label: String) {
    NONE(0, "无"),
    TRACE(1, "微"),
    MODERATE(2, "中"),
    COPIOUS(3, "多"),
    ;

    companion object {
        const val COUNT = 4
    }
}

internal enum class ClothingBreathability(val level: Int, val label: String) {
    GOOD(0, "好"),
    MIDDLE(1, "中"),
    POOR(2, "差"),
    ;

    companion object {
        const val COUNT = 3
    }
}

internal data class HygieneContext(
    val coverage: AreaLevel,
    val circumcision: Circumcision,
    val warmHumid: WarmHumidTier,
    val sweat: SweatAmount,
    val breathability: ClothingBreathability,
    val afterExercise: Boolean,
)

internal data class SmegmaPrediction(val tier: SmegmaTier, val rowId: String, val basis: String) {
    init {
        require(basis.isNotBlank()) { "表推包皮垢必须带依据" }
        HygieneGuard.requireRowId(rowId)
        HygieneGuard.requireInlineField(basis)
    }

    fun line(): String = "$rowId|${tier.label}|$basis"
}

internal sealed interface SmellSlot {
    data class Given(
        val intensity: SmellIntensity,
        val rowId: String,
        val basis: String,
    ) : SmellSlot {
        init {
            require(basis.isNotBlank()) { "气味强度必须带依据" }
            HygieneGuard.requireRowId(rowId)
            HygieneGuard.requireInlineField(basis)
        }
    }
}

internal data class Absent(val reason: String) : SmellSlot {
    init {
        require(reason.isNotBlank()) { "absent 必须带理由" }
        HygieneGuard.requireInlineField(reason)
    }
}

internal enum class SmellIntensity(val level: Int, val label: String) {
    NONE(0, "无"),
    SLIGHT(1, "轻"),
    OBVIOUS(2, "明显"),
    STRONG(3, "强"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): SmellIntensity =
            entries.firstOrNull { it.level == level }
                ?: throw IllegalArgumentException("气味强度档越界：只收 0–3")
    }
}

internal data class HygieneDerivation(
    val smegmaTier: SmegmaPrediction,
    val smellIntensity: SmellSlot,
    val smellType: Absent = Absent("味型不可算"),
    val flora: Absent = Absent("菌群不可算"),
    val pathologicalOdor: Absent = Absent("不做病理性判断"),
) {
    fun unavailableLine(): String =
        "味型=○(${smellType.reason})|菌群=○(${flora.reason})|病理性气味=○(${pathologicalOdor.reason})"
}
