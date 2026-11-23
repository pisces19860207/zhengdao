// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 求值器：把七条方向表与一维仿真态合成为对外读数。
// 只做"查表聚合 + 毫档量化"，不另立规则、不引入任何连续量到输出。
package com.example.zhengdao.baopu.state.thermo

/** 中性基线档：维度无驱动时落 1 档（不是 0 档"无"，避免无驱动被误读成缺席）。 */
internal object EvalBase {
    const val LEVEL = 1
}

/**
 * 求值：跑一维仿真拿末态，聚合七条方向表的 Effect 得各维档，量化后出读数。
 * 纯函数、无时间源——拍数来自 input.steps，外部可复现。
 */
internal object ThermoEval {

    fun eval(input: ThermoInput): ThermoReading {
        val start = ThermoInit.of(input.sweatGrade, input.thermal)
        val end = ThermoSolver.advance(start, input, input.steps)
        val effects = MechanismTable.effectsFor(input).values.flatten()
        return ThermoReading(
            volume = gradeOf(effects, OutDim.VOLUME),
            hang = gradeOf(effects, OutDim.HANG),
            rugosity = gradeOf(effects, OutDim.RUGOSITY),
            cling = gradeOf(effects, OutDim.CLING),
            cremasteric = ThermoSolver.toGrade(end.lift),
            surface = surfaceOf(input, end, effects),
            evidence = evidenceOf(input, effects),
        )
    }

    /** 单维档：中性基线 + 各机制对该维的升降，钳在 0-3。 */
    private fun gradeOf(effects: List<Effect>, dim: OutDim): Int {
        val delta = effects.filter { it.dim == dim }.sumOf { dirValue(it.dir) }
        return (EvalBase.LEVEL + delta).coerceIn(0, 3)
    }

    private fun dirValue(dir: Direction): Int = when (dir) {
        Direction.UP -> 1
        Direction.DOWN -> -1
        Direction.FLAT -> 0
    }

    /**
     * surface 三字段：湿膜取仿真末态量化（真正被推进过的量）；
     * 色变/血管可见按温与 turgor 给方向档（成因细节归 731，这里只给方向）。
     */
    private fun surfaceOf(input: ThermoInput, end: ThermoState, effects: List<Effect>): SurfaceReading {
        val wet = ThermoSolver.toGrade(end.film)
        val warm = input.thermal == ThermalBand.HOT || input.turgor == Direction.UP
        val cold = input.thermal == ThermalBand.COOL
        val shift = (EvalBase.LEVEL + (if (warm) 1 else 0) - (if (cold) 1 else 0)).coerceIn(0, 3)
        val vessel = (EvalBase.LEVEL + (if (warm) 1 else 0) - (if (cold) 1 else 0))
            .coerceIn(0, SurfaceCodes.VESSEL_VISIBLE_MAX)
        val drivers = effects.filter { it.dim == OutDim.WET_FILM }.map { it.dir.label }
            .ifEmpty { listOf("基线") }
        return SurfaceReading(wet, shift, vessel, drivers)
    }

    /** 每条非零依据指回机制 id；当前无驱动的维度不记（回落 0）。 */
    private fun evidenceOf(input: ThermoInput, effects: List<Effect>): List<Evidence> {
        val drivers = MechanismTable.effectsFor(input).filter { (_, list) -> list.isNotEmpty() }
        if (drivers.isEmpty()) return listOf(Evidence(OutDim.CREMASTERIC, "baseline", "全维中性基线"))
        return drivers.map { (id, list) ->
            Evidence(list.first().dim, id, MechanismTable.byId(id).label + "方向")
        }
    }
}
