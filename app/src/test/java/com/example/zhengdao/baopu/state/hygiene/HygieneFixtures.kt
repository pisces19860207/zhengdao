package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import com.example.zhengdao.baopu.state.prepuce.Circumcision
import com.example.zhengdao.baopu.state.prepuce.RetractState

internal object HygieneFixtures {

    fun state(
        smegmaTier: SmegmaTier = SmegmaTier.TRACE,
        cleaningFreq: WashFrequency = WashFrequency.EVERY_FEW_DAYS,
        retractability: RetractState = RetractState.FULL,
        color: SmegmaColor = SmegmaColor.WHITE,
        hygieneHabit: HygieneHabit = HygieneHabit.MIDDLE,
        washCondition: WashCondition = WashCondition.AVAILABLE,
        bathFreq: WashFrequency = WashFrequency.EVERY_OTHER_DAY,
        foot: WashFrequency = WashFrequency.DAILY,
        genitals: WashFrequency = WashFrequency.EVERY_FEW_DAYS,
        hygieneDriver: HygieneDriver = HygieneDriver.SELF,
    ): HygieneState = HygieneState(
        smegmaTier = smegmaTier,
        cleaningFreq = cleaningFreq,
        retractability = retractability,
        color = color,
        hygieneHabit = hygieneHabit,
        washCondition = washCondition,
        bathFreq = bathFreq,
        localWash = LocalWash(foot = foot, genitals = genitals),
        hygieneDriver = hygieneDriver,
    )

    fun context(
        coverage: AreaLevel = AreaLevel.OVER_HALF,
        circumcision: Circumcision = Circumcision.KEPT,
        warmHumid: WarmHumidTier = WarmHumidTier.MILD,
        sweat: SweatAmount = SweatAmount.MODERATE,
        breathability: ClothingBreathability = ClothingBreathability.MIDDLE,
        afterExercise: Boolean = false,
    ): HygieneContext = HygieneContext(
        coverage = coverage,
        circumcision = circumcision,
        warmHumid = warmHumid,
        sweat = sweat,
        breathability = breathability,
        afterExercise = afterExercise,
    )
}
