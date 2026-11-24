// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十七批 · 一维确定性推进：湿身随拍衰减、偏温随拍回落。
// 整数毫档推进，同输入两次逐位一致；这是档位的离散推进，不是流体/热生理仿真。
package com.example.zhengdao.baopu.state.wet

/** 推进中的内部状态：湿身毫档 + 偏温起点 + 偏温毫档 + 已过拍数。 */
internal data class WetProgress(
    val skinMilli: Int,
    val warmStart: Int,
    val warmMilli: Int,
    val beat: Int,
)

internal object WetSolver {

    /** 刚洗完的起点：湿身按档，偏温按档；拍数从零起。 */
    fun start(skin: SkinWet, warm: WarmLevel): WetProgress = WetProgress(
        skinMilli = skin.level * WetClock.PER_GRADE,
        warmStart = warm.level,
        warmMilli = warm.level * WetClock.PER_GRADE,
        beat = 0,
    )

    /** 每拍湿身衰减：围腰紧档吸水变慢，但保底仍让三拍降一整档以上。 */
    fun decayMilli(air: DryAir, waist: WaistTier?): Int {
        val base = airBase(air)
        val withTowel = if (waist == WaistTier.SECURE) minOf(base, WetClock.PER_GRADE) else base
        return maxOf(withTowel, WetClock.MIN_DECAY)
    }

    private fun airBase(air: DryAir): Int = when (air) {
        DryAir.STILL -> WetClock.PER_GRADE
        DryAir.BREEZE -> WetClock.PER_GRADE
        DryAir.WINDY -> WetClock.PER_GRADE + WetClock.MIN_DECAY
    }

    /**
     * 偏温回落：第 1 拍守住起点，其后按 1/3/6 拍预算线性回落，走完预算必归零。
     * 预算来自 726 派工单计划口径（未合），溯源见 889；本包不把它写成已实现口径。
     */
    fun warmAt(startLevel: Int, beats: Int, recoveryLevel: Int): Int {
        if (startLevel <= 0 || beats <= 0) return 0
        val budget = WetClock.recoveryBeats(recoveryLevel)
        val top = startLevel.coerceIn(0, WarmLevel.MAX_LEVEL) * WetClock.PER_GRADE
        // 向上取整：整档不能被预算整除时也要在走完预算后归零，不能留下尾毫档。
        val drop = (top + budget - 1) / budget
        return (top - (beats - 1) * drop).coerceIn(0, top)
    }

    /** 单拍推进：两量各自有界单调，拍数递增。 */
    fun step(state: WetProgress, air: DryAir, waist: WaistTier?, recoveryLevel: Int): WetProgress {
        val next = state.beat + WetClock.DT
        val skin = (state.skinMilli - decayMilli(air, waist)).coerceIn(0, WetClock.WET_MAX)
        return WetProgress(skin, state.warmStart, warmAt(state.warmStart, next, recoveryLevel), next)
    }

    /** 多拍推进：上限封顶，纯 fold，无时钟源。 */
    fun advance(
        start: WetProgress,
        air: DryAir,
        waist: WaistTier?,
        recoveryLevel: Int,
        beats: Int,
    ): WetProgress {
        var state = start
        val n = beats.coerceIn(WetClock.DT, WetClock.MAX_STEPS)
        repeat(n) { state = step(state, air, waist, recoveryLevel) }
        return state
    }

    /** 毫档 → 对外整档：湿身 0–3。 */
    fun skinGrade(milli: Int): SkinWet = SkinWet.of(milli / WetClock.PER_GRADE)

    /** 毫档 → 对外整档：偏温 0–2。 */
    fun warmLevel(milli: Int): WarmLevel = WarmLevel.of(milli / WetClock.PER_GRADE)
}
