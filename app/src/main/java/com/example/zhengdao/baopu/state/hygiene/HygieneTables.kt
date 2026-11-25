// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.RetractState

internal object SmegmaTable {

    private data class Key(
        val frequency: WashFrequency,
        val coverage: AreaLevel,
        val retract: RetractState,
        val warm: WarmHumidTier,
        val circumcision: Circumcision,
    )

    private data class BaseRow(
        val id: String,
        val frequency: WashFrequency,
        val coverage: AreaLevel,
        val start: Int,
    )

    private data class ModifierRow(
        val id: String,
        val retract: RetractState,
        val warm: WarmHumidTier,
        val circumcision: Circumcision,
        val shift: Int,
    )

    private val BASE_ROWS = listOf(
        BaseRow("S-B00", WashFrequency.DAILY, AreaLevel.FULL_COVER, 0),
        BaseRow("S-B01", WashFrequency.DAILY, AreaLevel.ALMOST_ALL, 0),
        BaseRow("S-B02", WashFrequency.DAILY, AreaLevel.OVER_HALF, 0),
        BaseRow("S-B03", WashFrequency.DAILY, AreaLevel.CORONA_ONLY, 0),
        BaseRow("S-B04", WashFrequency.DAILY, AreaLevel.RETRACTED_BEHIND_CORONA, 0),
        BaseRow("S-B05", WashFrequency.DAILY, AreaLevel.FULLY_EXPOSED, 0),
        BaseRow("S-B10", WashFrequency.EVERY_OTHER_DAY, AreaLevel.FULL_COVER, 0),
        BaseRow("S-B11", WashFrequency.EVERY_OTHER_DAY, AreaLevel.ALMOST_ALL, 0),
        BaseRow("S-B12", WashFrequency.EVERY_OTHER_DAY, AreaLevel.OVER_HALF, 1),
        BaseRow("S-B13", WashFrequency.EVERY_OTHER_DAY, AreaLevel.CORONA_ONLY, 1),
        BaseRow("S-B14", WashFrequency.EVERY_OTHER_DAY, AreaLevel.RETRACTED_BEHIND_CORONA, 1),
        BaseRow("S-B15", WashFrequency.EVERY_OTHER_DAY, AreaLevel.FULLY_EXPOSED, 1),
        BaseRow("S-B20", WashFrequency.EVERY_FEW_DAYS, AreaLevel.FULL_COVER, 1),
        BaseRow("S-B21", WashFrequency.EVERY_FEW_DAYS, AreaLevel.ALMOST_ALL, 1),
        BaseRow("S-B22", WashFrequency.EVERY_FEW_DAYS, AreaLevel.OVER_HALF, 1),
        BaseRow("S-B23", WashFrequency.EVERY_FEW_DAYS, AreaLevel.CORONA_ONLY, 2),
        BaseRow("S-B24", WashFrequency.EVERY_FEW_DAYS, AreaLevel.RETRACTED_BEHIND_CORONA, 2),
        BaseRow("S-B25", WashFrequency.EVERY_FEW_DAYS, AreaLevel.FULLY_EXPOSED, 2),
        BaseRow("S-B30", WashFrequency.OVER_WEEK, AreaLevel.FULL_COVER, 1),
        BaseRow("S-B31", WashFrequency.OVER_WEEK, AreaLevel.ALMOST_ALL, 2),
        BaseRow("S-B32", WashFrequency.OVER_WEEK, AreaLevel.OVER_HALF, 2),
        BaseRow("S-B33", WashFrequency.OVER_WEEK, AreaLevel.CORONA_ONLY, 2),
        BaseRow("S-B34", WashFrequency.OVER_WEEK, AreaLevel.RETRACTED_BEHIND_CORONA, 3),
        BaseRow("S-B35", WashFrequency.OVER_WEEK, AreaLevel.FULLY_EXPOSED, 3),
    )

    private val MODIFIER_ROWS = listOf(
        ModifierRow("S-M000", RetractState.FULL, WarmHumidTier.DRY, Circumcision.KEPT, 0),
        ModifierRow("S-M010", RetractState.FULL, WarmHumidTier.MILD, Circumcision.KEPT, 0),
        ModifierRow("S-M020", RetractState.FULL, WarmHumidTier.HOT_HUMID, Circumcision.KEPT, 1),
        ModifierRow("S-M100", RetractState.PARTIAL, WarmHumidTier.DRY, Circumcision.KEPT, 1),
        ModifierRow("S-M110", RetractState.PARTIAL, WarmHumidTier.MILD, Circumcision.KEPT, 1),
        ModifierRow("S-M120", RetractState.PARTIAL, WarmHumidTier.HOT_HUMID, Circumcision.KEPT, 2),
        ModifierRow("S-M200", RetractState.NONE, WarmHumidTier.DRY, Circumcision.KEPT, 1),
        ModifierRow("S-M210", RetractState.NONE, WarmHumidTier.MILD, Circumcision.KEPT, 1),
        ModifierRow("S-M220", RetractState.NONE, WarmHumidTier.HOT_HUMID, Circumcision.KEPT, 2),
        ModifierRow("S-M201", RetractState.NONE, WarmHumidTier.DRY, Circumcision.DONE, 0),
        ModifierRow("S-M211", RetractState.NONE, WarmHumidTier.MILD, Circumcision.DONE, 0),
        ModifierRow("S-M221", RetractState.NONE, WarmHumidTier.HOT_HUMID, Circumcision.DONE, 1),
    )

    private val CELLS: Map<Key, SmegmaPrediction> = buildMap {
        for (base in BASE_ROWS) {
            for (modifier in MODIFIER_ROWS) {
                val raw = base.start + modifier.shift
                val tier = SmegmaTier.of(raw.coerceIn(0, SmegmaTier.COUNT - 1))
                val key = Key(base.frequency, base.coverage, modifier.retract, modifier.warm, modifier.circumcision)
                put(
                    key,
                    SmegmaPrediction(
                        tier = tier,
                        rowId = "${base.id}+${modifier.id}",
                        basis = "${base.id} 起档 ${base.start}；${modifier.id} 位移 ${modifier.shift}；只查分段表",
                    ),
                )
            }
        }
    }

    fun predict(state: HygieneState, context: HygieneContext): SmegmaPrediction {
        HygieneGuard.requireCircumcisionRetract(state.retractability, context.circumcision)
        return CELLS.getValue(
            Key(state.cleaningFreq, context.coverage, state.retractability, context.warmHumid, context.circumcision),
        )
    }
}

internal object SmellTable {

    private data class Key(
        val sweat: SweatAmount,
        val frequency: WashFrequency,
        val breathability: ClothingBreathability,
        val warm: WarmHumidTier,
        val afterExercise: Boolean,
    )

    private data class SweatRow(val id: String, val amount: SweatAmount, val base: Int)
    private data class CleaningRow(val id: String, val frequency: WashFrequency, val shift: Int)
    private data class BreathRow(val id: String, val breathability: ClothingBreathability, val shift: Int)
    private data class WarmRow(val id: String, val warm: WarmHumidTier, val shift: Int)
    private data class ExerciseRow(val id: String, val afterExercise: Boolean, val shift: Int)

    private val SWEAT_ROWS = listOf(
        SweatRow("S0", SweatAmount.NONE, 0),
        SweatRow("S1", SweatAmount.TRACE, 1),
        SweatRow("S2", SweatAmount.MODERATE, 2),
        SweatRow("S3", SweatAmount.COPIOUS, 3),
    )
    private val CLEANING_ROWS = listOf(
        CleaningRow("C0", WashFrequency.DAILY, -1),
        CleaningRow("C1", WashFrequency.EVERY_OTHER_DAY, 0),
        CleaningRow("C2", WashFrequency.EVERY_FEW_DAYS, 1),
        CleaningRow("C3", WashFrequency.OVER_WEEK, 2),
    )
    private val BREATH_ROWS = listOf(
        BreathRow("T0", ClothingBreathability.GOOD, 0),
        BreathRow("T1", ClothingBreathability.MIDDLE, 1),
        BreathRow("T2", ClothingBreathability.POOR, 2),
    )
    private val WARM_ROWS = listOf(
        WarmRow("H0", WarmHumidTier.DRY, 0),
        WarmRow("H1", WarmHumidTier.MILD, 0),
        WarmRow("H2", WarmHumidTier.HOT_HUMID, 1),
    )
    private val EXERCISE_ROWS = listOf(
        ExerciseRow("E0", false, 0),
        ExerciseRow("E1", true, 1),
    )

    private val CELLS: Map<Key, SmellSlot.Given> = buildMap {
        for (sweat in SWEAT_ROWS) {
            for (cleaning in CLEANING_ROWS) {
                for (breath in BREATH_ROWS) {
                    for (warm in WARM_ROWS) {
                        for (exercise in EXERCISE_ROWS) {
                            val raw = sweat.base + cleaning.shift + breath.shift + warm.shift + exercise.shift
                            val intensity = SmellIntensity.of(raw.coerceIn(0, SmellIntensity.COUNT - 1))
                            val key = Key(sweat.amount, cleaning.frequency, breath.breathability, warm.warm, exercise.afterExercise)
                            put(
                                key,
                                SmellSlot.Given(
                                    intensity = intensity,
                                    rowId = "O-${sweat.id}-${cleaning.id}-${breath.id}-${warm.id}-${exercise.id}",
                                    basis = "${sweat.id} 汗档 ${sweat.amount.label}；${cleaning.id} 清洁 ${cleaning.frequency.label}；" +
                                        "${breath.id} 透气 ${breath.breathability.label}；${warm.id} 温湿 ${warm.warm.label}；" +
                                        "${exercise.id} 运动后 ${exercise.afterExercise}；只查分段表",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    fun predict(state: HygieneState, context: HygieneContext): SmellSlot {
        if (state.smegmaTier.level < SmegmaTier.ODOR_MIN_LEVEL) {
            return Absent("包皮垢未到明显档")
        }
        return CELLS.getValue(
            Key(context.sweat, state.cleaningFreq, context.breathability, context.warmHumid, context.afterExercise),
        )
    }
}

internal object HygieneDeriver {
    fun derive(state: HygieneState, context: HygieneContext): HygieneDerivation =
        HygieneDerivation(
            smegmaTier = SmegmaTable.predict(state, context),
            smellIntensity = SmellTable.predict(state, context),
        )
}
