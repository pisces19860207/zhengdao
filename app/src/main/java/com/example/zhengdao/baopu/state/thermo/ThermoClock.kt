// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 一维确定性最小仿真（ERRATA 2026-11-24）：
// 固定 DT / 步数上限 / 初值来源 658·657 / 整型推进 / 对外仍档位。
// 只推进三条看得见的量：湿膜厚度档、提睾回落、体表温度偏移档；
// 用整数毫档刻度（0..3000）避免任何浮点，同输入两次逐位一致。
// 这是"档位的离散推进"，不是热生理/流体仿真，不给秒/度/长度数值。
package com.example.zhengdao.baopu.state.thermo

/**
 * 离散拍钟：唯一的推进常量都在这里，代码里不出现 MagicNumber。
 * DT 固定为 1 拍（无量纲），MAX_STEPS 步上限；时间档借用 726 的拍口径（1/3/6）。
 */
internal object ThermoClock {
    /** 固定步长：1 拍，不给秒。 */
    const val DT = 1
    /** 步数上限：一次推进最多 6 拍（与 726 最慢恢复档 6 拍同口径）。 */
    const val MAX_STEPS = 6
    /** 每毫档：整档 × 1000，推进的最小分辨率。 */
    const val PER_GRADE = 1000
    /** 湿膜上限：3 档 × 1000。 */
    const val FILM_MAX = 3 * PER_GRADE
    /** 温度偏移上限：±1 档。 */
    const val TEMP_MAX = PER_GRADE
    /** 726 三档恢复预算（拍）：快 1 / 中 3 / 慢 6。 */
    private val RECOVERY_BEATS = intArrayOf(1, 3, 6)

    fun recoveryBeats(level: Int): Int = RECOVERY_BEATS[level.coerceIn(0, 2)]
}

/**
 * 推进状态：三条毫档 + 已过拍数。不可变，step() 返回新的状态。
 * film：湿膜厚度毫档；tempOffset：体表温度偏移毫档（-1000..1000）；
 * lift：提睾高度毫档（0..3000，触发后抬升、随后回落）。
 */
internal data class ThermoState(
    val film: Int,
    val tempOffset: Int,
    val lift: Int,
    val beat: Int,
)

/** 初值来源：658 出汗档给湿膜起点；657 温/湿给温度偏移起点；提睾起点松垂。 */
internal object ThermoInit {
    /** 湿膜起点：出汗档 0-3 → 毫档（出汗越多越湿）。658 的出汗档是上游，不重算。 */
    fun filmFromSweat(sweatGrade: Int): Int = sweatGrade.coerceIn(0, 3) * ThermoClock.PER_GRADE

    /** 温度偏移起点：偏凉 -1 档 / 适中 0 / 偏暖 +1 / 偏热 +1（封顶一档，不写度数）。 */
    fun tempFromBand(thermal: ThermalBand): Int = when (thermal) {
        ThermalBand.COOL -> -ThermoClock.PER_GRADE
        ThermalBand.MILD -> 0
        ThermalBand.WARM -> ThermoClock.PER_GRADE
        ThermalBand.HOT -> ThermoClock.PER_GRADE
    }

    /** 提睾起点：常态松垂（高度 0）。 */
    fun liftBaseline(): Int = 0

    fun of(sweatGrade: Int, thermal: ThermalBand): ThermoState = ThermoState(
        film = filmFromSweat(sweatGrade),
        tempOffset = tempFromBand(thermal),
        lift = liftBaseline(),
        beat = 0,
    )
}

/**
 * 一维推进器：每拍按输入档算增量，全部整型、有界、单调可控。
 * 纯函数：无 IO、无随机、无时钟；外部传入 sweatGrade（658）作为初值来源。
 */
internal object ThermoSolver {

    /** 湿膜每拍增量（毫档）：湿/水/湿衣补给为正，干/风消退为负。 */
    fun filmDelta(input: ThermoInput): Int {
        var delta = when (input.humidity) {
            HumidityBand.DRY -> -3
            HumidityBand.NORMAL -> -2
            HumidityBand.HUMID -> 1
            HumidityBand.SATURATED -> 2
        }
        delta += when (input.wind) {
            WindBand.STILL -> 0
            WindBand.BREEZE -> -1
            WindBand.WINDY -> -2
            WindBand.GUSTY -> -3
        }
        delta += when (input.medium) {
            Medium.NONE -> 0
            Medium.WATER -> 2
            Medium.SAND -> -1
            Medium.WET_CLOTH -> 2
        }
        delta += when (input.thermal) {
            ThermalBand.COOL -> 0
            ThermalBand.MILD -> 0
            ThermalBand.WARM -> 1
            ThermalBand.HOT -> 1
        }
        return delta * (ThermoClock.PER_GRADE / 10)
    }

    /** 体表温度偏移每拍向环境目标靠拢（整型趋近，一步最多 500 毫档）。 */
    fun tempDelta(input: ThermoInput, current: Int): Int {
        val target = ThermoInit.tempFromBand(input.thermal)
        val gap = target - current
        val step = ThermoClock.PER_GRADE / 2
        return gap.coerceIn(-step, step)
    }

    /**
     * 提睾高度：触发后的第 1 拍抬到 3 档，之后按 726 恢复预算线性回落。
     * 无触发恒松垂；一旦触发，beats 拍后必然回落——只上不落在这里走不通。
     */
    fun liftAt(trigger: CremasterTrigger, beats: Int, recoveryLevel: Int): Int {
        if (trigger == CremasterTrigger.NONE || beats <= 0) return 0
        val budget = ThermoClock.recoveryBeats(recoveryLevel)
        val top = 3 * ThermoClock.PER_GRADE
        val dropPerBeat = top / budget
        val remaining = (top - (beats - 1) * dropPerBeat).coerceIn(0, top)
        return remaining
    }

    /** 单拍推进：三量各自有界推进，beat 递增。 */
    fun step(state: ThermoState, input: ThermoInput): ThermoState {
        val film = (state.film + filmDelta(input)).coerceIn(0, ThermoClock.FILM_MAX)
        val temp = (state.tempOffset + tempDelta(input, state.tempOffset)).coerceIn(
            -ThermoClock.TEMP_MAX, ThermoClock.TEMP_MAX
        )
        val lift = liftAt(input.trigger, state.beat + 1, 1)
        return ThermoState(film, temp, lift, state.beat + 1)
    }

    /** 推进 n 拍（上限 MAX_STEPS），返回末态。fold 纯函数，确定性由整型保证。 */
    fun advance(start: ThermoState, input: ThermoInput, steps: Int): ThermoState {
        var s = start
        val n = steps.coerceIn(1, ThermoClock.MAX_STEPS)
        repeat(n) { s = step(s, input) }
        return s
    }

    /** 毫档 → 整档（对外唯一量化口，只出 0-3）。 */
    fun toGrade(milli: Int): Int = (milli / ThermoClock.PER_GRADE).coerceIn(0, 3)
}
