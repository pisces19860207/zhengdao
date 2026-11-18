// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十七批 · 环境求解器：光照（太阳位置，可精确计算）。
// 标准太阳位置公式的简化版：赤纬取 23.45°·sin 近似，输入为当地真太阳时，
// 零依赖、纯函数、无随机/时钟；同输入必得同输出。
// 不做云层/雾/反射/材质——只给几何光影与档位。
package com.example.zhengdao.baopu.state.environment

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tan

/** 光照求解器：经纬度/日期/时刻 ⇒ 方位角、高度角、影长比、影子方向、日照档。 */
object SolarSolver {

    /** 光照结果。方位角与影子方向均自北顺时针 0..360；影长比=影长/物高。 */
    data class SunState(
        val azimuthDeg: Double,
        val elevationDeg: Double,
        val shadowRatio: Double,
        val shadowDirDeg: Double,
        val intensity: String,
        val polar: String,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    /** 日落查询：真太阳时时刻（极昼/极夜为 null）＋极态标记。 */
    data class Sunset(
        val trueSolarTimeH: Double?,
        val polar: String,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    /** 钟表时 → 真太阳时换算结果（经度/时区/时差改正）。 */
    data class TrueSolarTime(
        val hours: Double,
        val assumption: String,
        val note: String = EnvironmentNotes.NOT_MEASUREMENT,
    )

    const val ASSUMPTION =
        "简化太阳位置算法（SPA 简化版 v1）：赤纬=23.45°·sin(360/365·(284+n))；" +
            "输入为当地真太阳时，经度与时差改正已在输入侧完成；忽略大气折射与地磁；" +
            "影长比=cot(高度角)，高度角≤0 记 Infinity（无直射光）；" +
            "极昼/极夜按 cos(日落时角)=-tanφ·tanδ 越界判定（含边界）；" +
            "天顶/极点方位角无定义，按时角约定 180+H；日照档按高度角 0/10/40° 分 无/弱/中/强"

    const val EOT_ASSUMPTION =
        "时差近似式 v1：B=360·(n-81)/364，EoT=9.87·sin2B−7.53·cosB−1.5·sinB（分钟）；" +
            "真太阳时=钟表时+(4·(经度−15·时区)+EoT)/60；忽略大气折射与地磁"

    private const val OBLIQUITY_DEG = 23.45

    /** 赤纬（度）：23.45°·sin 近似，n 为一年中的第几天（1..366）。 */
    fun declinationDeg(dayOfYear: Int): Double {
        require(dayOfYear in 1..366) { "dayOfYear 须在 1..366：$dayOfYear" }
        return OBLIQUITY_DEG * sin(rad(360.0 / 365.0 * (284 + dayOfYear)))
    }

    /** 日落信息：极昼返回 null 时刻＋midnight_sun，极夜返回 null＋polar_night。 */
    fun sunsetOf(latitudeDeg: Double, dayOfYear: Int): Sunset {
        val polar = polarState(latitudeDeg, dayOfYear)
        if (polar != "none") return Sunset(null, polar, ASSUMPTION)
        val x = -tan(rad(latitudeDeg)) * tan(rad(declinationDeg(dayOfYear)))
        val hours = 12.0 + deg(acos(clampUnit(x))) / 15.0
        return Sunset(hours, polar, ASSUMPTION)
    }

    /** 钟表时 → 当地真太阳时（经度、时区、时差三项改正）。 */
    fun toTrueSolarTime(clockTimeH: Double, longitudeDeg: Double, tzOffsetH: Double, dayOfYear: Int): TrueSolarTime {
        require(dayOfYear in 1..366) { "dayOfYear 须在 1..366：$dayOfYear" }
        val b = rad(360.0 * (dayOfYear - 81) / 364.0)
        val eotMin = 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val correctionMin = 4.0 * (longitudeDeg - 15.0 * tzOffsetH) + eotMin
        return TrueSolarTime(clockTimeH + correctionMin / 60.0, EOT_ASSUMPTION)
    }

    /** 主入口：纬度＋日期＋当地真太阳时 ⇒ 太阳几何与日照档。 */
    fun solve(latitudeDeg: Double, dayOfYear: Int, trueSolarTimeH: Double): SunState {
        require(latitudeDeg in -90.0..90.0) { "纬度须在 -90..90：$latitudeDeg" }
        require(trueSolarTimeH in 0.0..24.0) { "真太阳时须在 0..24：$trueSolarTimeH" }
        val dec = declinationDeg(dayOfYear)
        val hourAngle = 15.0 * (trueSolarTimeH - 12.0)
        val sinElev = clampUnit(
            sin(rad(latitudeDeg)) * sin(rad(dec)) +
                cos(rad(latitudeDeg)) * cos(rad(dec)) * cos(rad(hourAngle))
        )
        val elev = deg(asin(sinElev))
        val az = azimuthOf(latitudeDeg, dec, hourAngle, sinElev, elev)
        val ratio = if (elev <= 0.0) Double.POSITIVE_INFINITY else 1.0 / tan(rad(elev))
        return SunState(
            azimuthDeg = az,
            elevationDeg = elev,
            shadowRatio = ratio,
            shadowDirDeg = norm360(az + 180.0),
            intensity = intensityOf(elev),
            polar = polarState(latitudeDeg, dayOfYear),
            assumption = ASSUMPTION,
        )
    }

    /** 极态：-tanφ·tanδ ≤ -1 ⇒ 极昼（太阳不落下）；≥ 1 ⇒ 极夜（太阳不升起）。 */
    private fun polarState(latitudeDeg: Double, dayOfYear: Int): String {
        require(latitudeDeg in -90.0..90.0) { "纬度须在 -90..90：$latitudeDeg" }
        val x = -tan(rad(latitudeDeg)) * tan(rad(declinationDeg(dayOfYear)))
        return when {
            x <= -1.0 -> "midnight_sun"
            x >= 1.0 -> "polar_night"
            else -> "none"
        }
    }

    private fun azimuthOf(lat: Double, dec: Double, hourAngle: Double, sinElev: Double, elev: Double): Double {
        val cosLat = cos(rad(lat))
        val cosElev = cos(rad(elev))
        if (abs(cosLat) < 1e-12 || cosElev < 1e-9) return norm360(180.0 + hourAngle)
        val cosA = clampUnit((sin(rad(dec)) - sin(rad(lat)) * sinElev) / (cosLat * cosElev))
        val a = deg(acos(cosA))
        return if (hourAngle > 0.0) norm360(360.0 - a) else a
    }

    private fun intensityOf(elev: Double): String = when {
        elev <= 0.0 -> "无"
        elev < 10.0 -> "弱"
        elev < 40.0 -> "中"
        else -> "强"
    }

    private fun clampUnit(x: Double): Double = x.coerceIn(-1.0, 1.0)

    private fun norm360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0

    private fun rad(d: Double): Double = d * PI / 180.0

    private fun deg(r: Double): Double = r * 180.0 / PI
}