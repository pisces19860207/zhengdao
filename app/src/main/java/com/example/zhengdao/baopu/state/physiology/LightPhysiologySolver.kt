// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十八批 · 档位求解器：把五类档位化驱动合成四状态 + 三液分道。
// 纯函数、零 IO、零时钟、零随机、零模型；输入是档位，输出也是档位——不产生任何点值。
// 只「产出状态」，不判定、不写事实、不进任何闸。
package com.example.zhengdao.baopu.state.physiology

/** 环境温度档（偏凉/温和/偏暖/炎热）。只表档位，不写温度测值。 */
internal enum class AmbientTempBand(val level: Int, val label: String) {
    COOL(0, "偏凉"), MILD(1, "温和"), WARM(2, "偏暖"), HOT(3, "炎热"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): AmbientTempBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 环境湿度档（干燥/适中/偏湿/饱和）。 */
internal enum class HumidityBand(val level: Int, val label: String) {
    DRY(0, "干燥"), COMFORT(1, "适中"), HUMID(2, "偏湿"), SATURATED(3, "饱和"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): HumidityBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 活动强度档（静息/轻动/中动/剧烈）。 */
internal enum class ActivityBand(val level: Int, val label: String) {
    REST(0, "静息"), LIGHT(1, "轻动"), MODERATE(2, "中动"), INTENSE(3, "剧烈"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): ActivityBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 时长档（短时/中时/长时/持久）。只表档位，不写时间测值。 */
internal enum class DurationBand(val level: Int, val label: String) {
    SHORT(0, "短时"), MEDIUM(1, "中时"), LONG(2, "长时"), PROLONGED(3, "持久"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): DurationBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 情绪档（平静/轻微/唤起/强烈）。 */
internal enum class EmotionBand(val level: Int, val label: String) {
    CALM(0, "平静"), MILD(1, "轻微"), AROUSED(2, "唤起"), INTENSE(3, "强烈"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): EmotionBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 个体差异档（偏低/典型/偏高/敏感）。 */
internal enum class IndividualBand(val level: Int, val label: String) {
    LOW(0, "偏低"), TYPICAL(1, "典型"), HIGH(2, "偏高"), SENSITIVE(3, "敏感"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): IndividualBand =
            values().first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 求解入参：五类驱动各给一个档位，没有连续量、没有测值。 */
internal data class PhysioInputs(
    val ambientTemp: AmbientTempBand,
    val humidity: HumidityBand,
    val activity: ActivityBand,
    val duration: DurationBand,
    val emotion: EmotionBand,
    val individual: IndividualBand,
) {
    companion object {
        /** 全零档基线：温和/适中/静息/短时/平静/典型。 */
        val BASELINE = PhysioInputs(
            AmbientTempBand.MILD, HumidityBand.COMFORT, ActivityBand.REST,
            DurationBand.SHORT, EmotionBand.CALM, IndividualBand.TYPICAL,
        )
    }
}

internal object LightPhysiologySolver {

    /** 唯一入口：同输入两次返回逐字节相同的产出。 */
    fun solve(i: PhysioInputs): LightPhysiology = LightPhysiology(
        bodyTempShift = tempOf(i),
        heartRateBand = heartOf(i),
        sweatLevel = sweatOf(i),
        fatigue = fatigueOf(i),
        fluids = fluidsOf(i),
    )

    // ── 四状态 ────────────────────────────────────────────────────────────

    private fun tempOf(i: PhysioInputs): GradeReading<BodyTempShift> {
        val level = (i.ambientTemp.level + bonusActivity(i.activity) +
            bonusDuration(i.duration) + bonusIndividual(i.individual))
            .coerceIn(0, BodyTempShift.COUNT - 1)
        return GradeReading(BodyTempShift.of(level), tempDrivers(i))
    }

    private fun heartOf(i: PhysioInputs): GradeReading<HeartRateBand> {
        val level = (i.activity.level + bonusEmotion(i.emotion) + bonusDuration(i.duration))
            .coerceIn(0, HeartRateBand.COUNT - 1)
        return GradeReading(HeartRateBand.of(level), heartDrivers(i))
    }

    private fun sweatOf(i: PhysioInputs): GradeReading<SweatLevel> {
        val level = (maxOf(0, i.ambientTemp.level - 1) + bonusHumidity(i.humidity) +
            i.activity.level + bonusDuration(i.duration) + bonusIndividual(i.individual))
            .coerceIn(0, SweatLevel.COUNT - 1)
        return GradeReading(SweatLevel.of(level), sweatDrivers(i))
    }

    private fun fatigueOf(i: PhysioInputs): GradeReading<Fatigue> {
        val level = (i.activity.level + bonusDuration(i.duration) + bonusEmotion(i.emotion))
            .coerceIn(0, Fatigue.COUNT - 1)
        return GradeReading(Fatigue.of(level), fatigueDrivers(i))
    }

    // ── 三液分道：三条独立算，不共用同一条字段，也不合成「体液」 ──────────

    private fun fluidsOf(i: PhysioInputs): FluidChannels {
        val sweatLevel = sweatOf(i).grade.level
        return FluidChannels(
            sweat = FluidTrack(
                present = sweatLevel > 0,
                amount = FluidAmount.of(sweatLevel),
                drivers = sweatDrivers(i),
            ),
            saliva = FluidTrack(
                present = i.emotion.level >= 2,
                amount = FluidAmount.of(i.emotion.level - 1),
                drivers = emotionDrivers(i),
            ),
            preSeminal = FluidTrack(
                present = i.emotion.level >= 2,
                amount = FluidAmount.of(i.emotion.level - 1 + bonusIndividual(i.individual)),
                drivers = emotionDrivers(i) + individualDrivers(i),
            ),
        )
    }

    // ── 驱动归集：只把真正抬了档的驱动挂上 ────────────────────────────────

    private fun tempDrivers(i: PhysioInputs): List<PhysioDriver> = buildList {
        if (i.ambientTemp.level != 1 || bonusHumidity(i.humidity) > 0) add(PhysioDriver.ENV_TEMP_HUMIDITY)
        if (bonusActivity(i.activity) > 0) add(PhysioDriver.ACTIVITY_INTENSITY)
        if (bonusDuration(i.duration) > 0) add(PhysioDriver.DURATION)
        if (bonusIndividual(i.individual) > 0) add(PhysioDriver.INDIVIDUAL_DIFFERENCE)
    }

    private fun heartDrivers(i: PhysioInputs): List<PhysioDriver> = buildList {
        if (i.activity.level > 0) add(PhysioDriver.ACTIVITY_INTENSITY)
        if (bonusEmotion(i.emotion) > 0) add(PhysioDriver.EMOTION)
        if (bonusDuration(i.duration) > 0) add(PhysioDriver.DURATION)
    }

    private fun sweatDrivers(i: PhysioInputs): List<PhysioDriver> = buildList {
        if (i.ambientTemp.level > 1 || bonusHumidity(i.humidity) > 0) add(PhysioDriver.ENV_TEMP_HUMIDITY)
        if (i.activity.level > 0) add(PhysioDriver.ACTIVITY_INTENSITY)
        if (bonusDuration(i.duration) > 0) add(PhysioDriver.DURATION)
        if (bonusIndividual(i.individual) > 0) add(PhysioDriver.INDIVIDUAL_DIFFERENCE)
    }

    private fun fatigueDrivers(i: PhysioInputs): List<PhysioDriver> = buildList {
        if (i.activity.level > 0) add(PhysioDriver.ACTIVITY_INTENSITY)
        if (bonusDuration(i.duration) > 0) add(PhysioDriver.DURATION)
        if (bonusEmotion(i.emotion) > 0) add(PhysioDriver.EMOTION)
    }

    private fun emotionDrivers(i: PhysioInputs): List<PhysioDriver> =
        if (i.emotion.level >= 2) listOf(PhysioDriver.EMOTION) else emptyList()

    private fun individualDrivers(i: PhysioInputs): List<PhysioDriver> =
        if (bonusIndividual(i.individual) > 0) listOf(PhysioDriver.INDIVIDUAL_DIFFERENCE) else emptyList()

    // ── 抬档判据：全部是档位比较，没有任何测值门限 ────────────────────────

    private fun bonusActivity(a: ActivityBand): Int = if (a.level >= 2) 1 else 0
    private fun bonusHumidity(h: HumidityBand): Int = if (h.level >= 2) 1 else 0
    private fun bonusEmotion(e: EmotionBand): Int = if (e.level >= 2) 1 else 0
    private fun bonusDuration(d: DurationBand): Int = if (d.level >= 2) 1 else 0
    private fun bonusIndividual(i: IndividualBand): Int = if (i.level >= 3) 1 else 0
}
