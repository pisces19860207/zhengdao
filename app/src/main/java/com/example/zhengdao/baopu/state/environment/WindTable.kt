// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十七批 · 环境求解器：风（档位表，不做流体）。
// 蒲福风级 0–12 ⇒ 体感档＋影响清单（沙/布/声/触），只给档位，不模拟流场。
// 天气由外部给，本件只把「给定天气」翻译成影响；纯函数、确定性。
package com.example.zhengdao.baopu.state.environment

import kotlin.math.pow
import kotlin.math.roundToInt

/** 风档位求解器：蒲福 0–12（或风速 m/s）⇒ 体感档与场景影响清单。 */
object WindTable {

    /** 一条影响：kind 为闭集成员，grade 为该影响的档位，label 为档位文案。 */
    data class Effect(val kind: String, val grade: Int, val label: String)

    /** 风状态。dirDeg 为气象风向（来向，自北顺时针）；gustFactor 为确定性阵风系数。 */
    data class WindState(
        val beaufort: Int,
        val dirDeg: Double,
        val gustFactor: Double,
        val feels: String,
        val effects: List<Effect>,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    const val ASSUMPTION =
        "蒲福档位表 v1（不做流体）：风速→风级按 round((v/0.836)^(2/3)) 钳位 0..12；" +
            "风级→代表风速取各级区间中值（12 级开口，取 34.0 m/s 代表值）；" +
            "阵风系数=1+0.05·风级（档位近似，非阵风谱）；影响清单六类：起沙/吹平/衣摆/发向/风声/体感，" +
            "档位随风级单调不降；不做天气预测，天气由外部给定"

    /** 影响 kind 闭集（顺序即 effects 列表顺序）。 */
    val KINDS = listOf("sand_lift", "sand_flatten", "cloth_hem", "hair_dir", "wind_sound", "touch")

    private val REPRESENTATIVE_MS = doubleArrayOf(
        0.1, 0.9, 2.45, 4.4, 6.7, 9.35, 12.3, 15.5, 18.95, 22.6, 26.45, 30.55, 34.0
    )

    /** 风速 m/s ⇒ 蒲福风级（0..12）。 */
    fun beaufortOf(windMs: Double): Int {
        require(windMs >= 0.0) { "风速不可为负：$windMs" }
        return (windMs / 0.836).pow(2.0 / 3.0).roundToInt().coerceIn(0, 12)
    }

    /** 蒲福风级 ⇒ 代表风速 m/s（区间中值，供湿度/汗液侧使用）。 */
    fun representativeMs(beaufort: Int): Double {
        require(beaufort in 0..12) { "风级须在 0..12：$beaufort" }
        return REPRESENTATIVE_MS[beaufort]
    }

    /** 主入口：风级＋风向 ⇒ 体感档与影响清单。 */
    fun of(beaufort: Int, dirDeg: Double): WindState {
        require(beaufort in 0..12) { "风级须在 0..12：$beaufort" }
        val effects = listOf(
            sandLift(beaufort),
            sandFlatten(beaufort),
            clothHem(beaufort),
            hairDir(beaufort),
            windSound(beaufort),
            touch(beaufort),
        )
        return WindState(
            beaufort = beaufort,
            dirDeg = norm360(dirDeg),
            gustFactor = 1.0 + 0.05 * beaufort,
            feels = feelsOf(beaufort),
            effects = effects,
            assumption = ASSUMPTION,
        )
    }

    private fun feelsOf(b: Int): String = when {
        b <= 0 -> "无"
        b <= 3 -> "微风"
        b <= 6 -> "有风"
        b <= 9 -> "大风"
        else -> "恶劣"
    }

    private fun sandLift(b: Int): Effect = when {
        b < 4 -> Effect("sand_lift", 0, "不起沙")
        b < 7 -> Effect("sand_lift", 1, "微起沙")
        b < 10 -> Effect("sand_lift", 2, "起沙")
        else -> Effect("sand_lift", 3, "沙暴")
    }

    private fun sandFlatten(b: Int): Effect = when {
        b < 5 -> Effect("sand_flatten", 0, "未吹平")
        b < 9 -> Effect("sand_flatten", 1, "微吹平")
        else -> Effect("sand_flatten", 2, "吹平")
    }

    private fun clothHem(b: Int): Effect = when {
        b <= 1 -> Effect("cloth_hem", 0, "静止")
        b <= 4 -> Effect("cloth_hem", 1, "微动")
        b <= 7 -> Effect("cloth_hem", 2, "飘动")
        else -> Effect("cloth_hem", 3, "剧烈")
    }

    private fun hairDir(b: Int): Effect =
        if (b == 0) Effect("hair_dir", 0, "自然下垂") else Effect("hair_dir", 1, "顺风向")

    private fun windSound(b: Int): Effect = when {
        b <= 1 -> Effect("wind_sound", 0, "无")
        b <= 4 -> Effect("wind_sound", 1, "轻")
        b <= 7 -> Effect("wind_sound", 2, "明显")
        else -> Effect("wind_sound", 3, "盖过说话")
    }

    private fun touch(b: Int): Effect = when {
        b <= 2 -> Effect("touch", 0, "无感")
        b <= 6 -> Effect("touch", 1, "微凉")
        else -> Effect("touch", 2, "阻力明显")
    }

    private fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0
}