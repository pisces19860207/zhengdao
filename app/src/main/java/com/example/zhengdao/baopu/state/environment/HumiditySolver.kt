// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十七批 · 环境求解器：湿度与汗液（档位模型，非精确生理）。
// 湿度：RH ⇒ 露点（Magnus）＋湿球（Stull 近似）＋蒸发档；
// 汗液：产汗档 0–4 ≈ f(活动强度, 气温, 湿度, 风速)＋效应清单（光泽/贴附/沙粘附/体感温度）；
// 沙含水：干/潮/湿三档 ⇒ 束缚系数（供 655 premise_check）。
// 纯函数、确定性；全部输出带 assumption＋「模拟结果，非测量」。
package com.example.zhengdao.baopu.state.environment

import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** 湿度／汗液／沙含水求解器：档位近似，不是生理测量。 */
object HumiditySolver {

    /** 湿度状态：露点与湿球为 °C，蒸发档为 低/中/高。 */
    data class HumidityState(
        val rhPct: Double,
        val dewPointC: Double,
        val wetBulbC: Double,
        val evaporation: String,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    /** 汗液状态：产汗档 0–4 ＋效应清单（复用 WindTable.Effect 的 kind/grade/label 形）。 */
    data class SweatState(
        val grade: Int,
        val effects: List<WindTable.Effect>,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    /** 沙含水档：干沙/潮沙/湿沙 ⇒ 束缚系数（湿沙束缚更强、可塑性更高）。 */
    data class SandMoisture(
        val grade: Int,
        val label: String,
        val binding: Double,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    const val HUMIDITY_ASSUMPTION =
        "湿度档位近似 v1（非生理测量）：露点用 Magnus（a=17.62，b=243.12°C），RH 钳位 1..100；" +
            "湿球用 Stull(2011) 近似式（适用约 RH 5–99%、−20..50°C，域外为外推），" +
            "并钳位湿球≤干球；蒸发档=分数 (100−RH)·(1+0.3·风速)·(0.6+0.02·max(T,0))，" +
            "<60 低 / <150 中 / 否则高（档位近似，非蒸发速率测量）"

    const val SWEAT_ASSUMPTION =
        "汗液档位近似 v1（非生理测量）：产汗分数=2.2·活动强度+0.45·max(0,T−24)" +
            "+0.05·max(0,RH−50)−0.35·风速，下限 0；分数 <1.5/3.5/6/9 ⇒ 档 0/1/2/3/4；" +
            "效应清单四类：皮肤光泽/衣物贴附/沙粘附/体感温度，全部给档位不给毫米；" +
            "体感温度分数=T+0.06·max(0,RH−40)−0.3·风速，<18 偏凉 / <26 适中 / <32 闷热 / 否则烫"

    const val SAND_ASSUMPTION =
        "沙含水档位近似 v1（非测量）：干沙/潮沙/湿沙三档 ⇒ 束缚系数 0.2/0.5/0.9，" +
            "供 655 premise_check（湿沙束缚更强、可塑性更高）；档位由外部给定，不做含水率反演"

    /** Magnus 露点（°C）。RH 钳位 1..100（0% 无露点定义）。 */
    fun dewPointC(tempC: Double, rhPct: Double): Double {
        require(tempC in -60.0..60.0) { "气温超出档位模型域：$tempC" }
        val rh = rhPct.coerceIn(1.0, 100.0)
        val gamma = ln(rh / 100.0) + 17.62 * tempC / (243.12 + tempC)
        return 243.12 * gamma / (17.62 - gamma)
    }

    /** Stull(2011) 湿球近似（°C），钳位湿球≤干球（高温高湿端原式会略超干球）。 */
    fun wetBulbC(tempC: Double, rhPct: Double): Double {
        require(tempC in -60.0..60.0) { "气温超出档位模型域：$tempC" }
        val rh = rhPct.coerceIn(1.0, 100.0)
        val raw = tempC * atan(0.151977 * sqrt(rh + 8.313659)) +
            atan(tempC + rh) - atan(rh - 1.676331) +
            0.00391838 * rh.pow(1.5) * atan(0.023101 * rh) - 4.686035
        return raw.coerceAtMost(tempC)
    }

    /** 湿度主入口：RH＋气温＋风速 ⇒ 露点/湿球/蒸发档。 */
    fun humidityOf(tempC: Double, rhPct: Double, windMs: Double): HumidityState {
        require(windMs >= 0.0) { "风速不可为负：$windMs" }
        val rh = rhPct.coerceIn(1.0, 100.0)
        val score = (100.0 - rh) * (1.0 + 0.3 * windMs) * (0.6 + 0.02 * max(tempC, 0.0))
        val evaporation = when {
            score < 60.0 -> "低"
            score < 150.0 -> "中"
            else -> "高"
        }
        return HumidityState(
            rhPct = rh,
            dewPointC = dewPointC(tempC, rh),
            wetBulbC = wetBulbC(tempC, rh),
            evaporation = evaporation,
            assumption = HUMIDITY_ASSUMPTION,
        )
    }

    /** 汗液主入口：活动强度 0–4＋气温＋RH＋风速＋沙含水档 ⇒ 产汗档与效应清单。 */
    fun sweatOf(activity: Int, tempC: Double, rhPct: Double, windMs: Double, sandMoistureGrade: Int): SweatState {
        require(activity in 0..4) { "活动强度须在 0..4：$activity" }
        require(windMs >= 0.0) { "风速不可为负：$windMs" }
        require(sandMoistureGrade in 0..2) { "沙含水档须在 0..2：$sandMoistureGrade" }
        val rh = rhPct.coerceIn(1.0, 100.0)
        val score = max(
            0.0,
            2.2 * activity + 0.45 * max(0.0, tempC - 24.0) +
                0.05 * max(0.0, rh - 50.0) - 0.35 * windMs
        )
        val grade = when {
            score < 1.5 -> 0
            score < 3.5 -> 1
            score < 6.0 -> 2
            score < 9.0 -> 3
            else -> 4
        }
        val effects = listOf(
            skinSheen(grade),
            clothCling(grade),
            sandStick(grade, sandMoistureGrade),
            feltTemp(tempC, rh, windMs),
        )
        return SweatState(grade = grade, effects = effects, assumption = SWEAT_ASSUMPTION)
    }

    /** 沙含水档 0/1/2 ⇒ 干沙/潮沙/湿沙与束缚系数。 */
    fun sandMoistureOf(grade: Int): SandMoisture {
        require(grade in 0..2) { "沙含水档须在 0..2：$grade" }
        return when (grade) {
            0 -> SandMoisture(0, "干沙", 0.2, SAND_ASSUMPTION)
            1 -> SandMoisture(1, "潮沙", 0.5, SAND_ASSUMPTION)
            else -> SandMoisture(2, "湿沙", 0.9, SAND_ASSUMPTION)
        }
    }

    private fun skinSheen(sweatGrade: Int): WindTable.Effect = when {
        sweatGrade <= 1 -> WindTable.Effect("skin_sheen", 0, "哑")
        sweatGrade <= 3 -> WindTable.Effect("skin_sheen", 1, "微光")
        else -> WindTable.Effect("skin_sheen", 2, "油亮")
    }

    private fun clothCling(sweatGrade: Int): WindTable.Effect = when {
        sweatGrade == 0 -> WindTable.Effect("cloth_cling", 0, "干爽")
        sweatGrade <= 2 -> WindTable.Effect("cloth_cling", 1, "贴身")
        else -> WindTable.Effect("cloth_cling", 2, "湿透贴身")
    }

    private fun sandStick(sweatGrade: Int, moistureGrade: Int): WindTable.Effect {
        val g = minOf(2, sweatGrade / 2 + moistureGrade)
        val label = when (g) {
            0 -> "不粘"
            1 -> "微粘"
            else -> "粘成块"
        }
        return WindTable.Effect("sand_stick", g, label)
    }

    private fun feltTemp(tempC: Double, rh: Double, windMs: Double): WindTable.Effect {
        val score = tempC + 0.06 * max(0.0, rh - 40.0) - 0.3 * windMs
        val g = when {
            score < 18.0 -> 0
            score < 26.0 -> 1
            score < 32.0 -> 2
            else -> 3
        }
        val label = when (g) {
            0 -> "偏凉"
            1 -> "适中"
            2 -> "闷热"
            else -> "烫"
        }
        return WindTable.Effect("felt_temp", g, label)
    }
}