// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 判据检查器：七条方向表齐 / 提睾可回落 / 三条方向断言 /
// 禁连续外泄 / 禁病理性温度 / surface 字段名对齐。纯函数，红即不出值。
package com.example.zhengdao.baopu.state.thermo

/** 判据检查器。每个 check 返回错误码+消息，收集齐了一次性判红。 */
internal object ThermoChecks {

    /** 判据①：七条机制齐全，一个都不能少。 */
    fun mechanismsComplete(): List<String> {
        val ids = MechanismTable.ALL.map { it.id }
        return if (ids.toSet() == MechanismIds.ALL.toSet() && ids.size == 7) emptyList()
        else listOf(ThermoCodes.MECHANISM_MISSING + ":七条方向表不齐全")
    }

    /**
     * 判据②：提睾必须可回落——时间夹具：第 1 拍抬升、走完恢复预算必落回松垂。
     * 只上不落（liftAt 恒等于峰值）在这里会被判红。
     */
    fun recoilWorks(trigger: CremasterTrigger, recoveryLevel: Int): List<String> {
        if (trigger == CremasterTrigger.NONE) return emptyList()
        val top = 3 * ThermoClock.PER_GRADE
        val first = ThermoSolver.liftAt(trigger, 1, recoveryLevel)
        val budget = ThermoClock.recoveryBeats(recoveryLevel)
        val settled = ThermoSolver.liftAt(trigger, budget + 2, recoveryLevel)
        val issues = ArrayList<String>()
        if (first <= 0) issues.add(ThermoCodes.RECOIL_STUCK + ":触发后第1拍未上提")
        if (settled != 0) issues.add(ThermoCodes.RECOIL_STUCK + ":走完恢复预算未回落到松垂")
        if (first > top) issues.add(ThermoCodes.RECOIL_STUCK + ":提睾超过3档上限")
        return issues
    }

    /**
     * 判据③：三条方向断言——冷⇒皱缩↑、热⇒皱缩↓、湿⇒湿膜↑，外加风⇒湿膜消退快。
     * 用同一夹具只翻转目标维度，比较求值结果，反向即红。
     */
    fun directionsHold(): List<String> {
        val issues = ArrayList<String>()
        val cool = evalWith(thermal = ThermalBand.COOL)
        val hot = evalWith(thermal = ThermalBand.HOT)
        val mild = evalWith(thermal = ThermalBand.MILD)
        if (cool.rugosity <= mild.rugosity) {
            issues.add(ThermoCodes.DIRECTION_WRONG + ":冷未使皱缩升")
        }
        if (hot.rugosity >= mild.rugosity) {
            issues.add(ThermoCodes.DIRECTION_WRONG + ":热未使皱缩降")
        }
        // 湿/风比的是湿膜在相同高起点下的走向；起点取满档，避免都衰减到 0 后无法区分。
        val humid = evalWith(humidity = HumidityBand.HUMID, sweat = 3)
        val dry = evalWith(humidity = HumidityBand.DRY, sweat = 3)
        if (humid.surface.wetFilm <= dry.surface.wetFilm) {
            issues.add(ThermoCodes.DIRECTION_WRONG + ":湿未使湿膜升")
        }
        if (wetFilmAfter(WindBand.STILL) <= wetFilmAfter(WindBand.GUSTY)) {
            issues.add(ThermoCodes.DIRECTION_WRONG + ":风大未使湿膜消退更快")
        }
        return issues
    }

    /** 判据⑥对外：文本里不许出现连续读数（温度/长度/体积/时间数值）。 */
    fun noContinuousLeak(text: String): List<String> {
        val hits = ContinuousGuard.hits(text)
        return if (hits.isEmpty()) emptyList()
        else listOf(ThermoCodes.CONTINUOUS_LEAK + ":命中「" + hits.joinToString("、") + "」")
    }

    /** 边界：不做病理性温度。文本命中发热/炎症/感染那一类即红。 */
    fun noPathological(text: String): List<String> {
        val hits = PathologyGuard.hits(text)
        return if (hits.isEmpty()) emptyList()
        else listOf(ThermoCodes.PATHOLOGICAL + ":命中「" + hits.joinToString("、") + "」")
    }

    /** 汇总：一次跑全部静态判据（方向表/方向断言/病理），输出全部红码。 */
    fun staticIssues(note: String): List<String> {
        val out = ArrayList<String>()
        out.addAll(mechanismsComplete())
        out.addAll(directionsHold())
        out.addAll(noPathological(note))
        return out
    }

    private fun wetFilmAfter(wind: WindBand): Int =
        evalWith(humidity = HumidityBand.HUMID, wind = wind, sweat = 3).surface.wetFilm

    private fun evalWith(
        thermal: ThermalBand = ThermalBand.MILD,
        humidity: HumidityBand = HumidityBand.NORMAL,
        wind: WindBand = WindBand.STILL,
        sweat: Int = 1,
    ): ThermoReading = ThermoEval.eval(
        ThermoInput(
            thermal = thermal,
            humidity = humidity,
            wind = wind,
            medium = Medium.NONE,
            posture = Posture.STAND,
            trigger = CremasterTrigger.NONE,
            turgor = Direction.FLAT,
            sweatGrade = sweat,
            steps = ThermoClock.MAX_STEPS,
        )
    )
}
