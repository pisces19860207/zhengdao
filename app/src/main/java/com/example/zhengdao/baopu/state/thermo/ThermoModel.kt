// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十批 · 只读数据模型：输入档、输出档、surface 三字段、依据行。
// 全部不可变；对外字段只有闭集档位与依据，没有测量量。
package com.example.zhengdao.baopu.state.thermo

/**
 * 一拍的输入档。温/湿/风/介质/体位/触发/turgor方向 均来自上游或场景，
 * 本单不重算天气、不造温度数值；steps 为本次要推进的拍数（≥1）。
 */
internal data class ThermoInput(
    val thermal: ThermalBand,
    val humidity: HumidityBand,
    val wind: WindBand,
    val medium: Medium,
    val posture: Posture,
    val trigger: CremasterTrigger,
    val turgor: Direction,
    /** 658 出汗档 0-3：湿膜仿真的初值来源，本单不重算出汗量。 */
    val sweatGrade: Int,
    val steps: Int = ThermoClock.MAX_STEPS,
)

/**
 * 对齐 731 的 surface 三字段：字段名逐字与派工单一致，档域 wetFilm 0-3 /
 * colorShift 0-3 / vesselVisible 0-2。每项带依据 id，指不回规则的回落 0。
 */
internal data class SurfaceReading(
    val wetFilm: Int,
    val colorShift: Int,
    val vesselVisible: Int,
    val drivers: List<String>,
) {
    init {
        require(wetFilm in 0..SurfaceCodes.WET_FILM_MAX) { ThermoCodes.SURFACE_MISMATCH + ":wetFilm 越界" }
        require(colorShift in 0..SurfaceCodes.COLOR_SHIFT_MAX) { ThermoCodes.SURFACE_MISMATCH + ":colorShift 越界" }
        require(vesselVisible in 0..SurfaceCodes.VESSEL_VISIBLE_MAX) { ThermoCodes.SURFACE_MISMATCH + ":vesselVisible 越界" }
    }
}

/** 一条依据：非零档必须指回规则 id 或事件 id。 */
internal data class Evidence(val dim: OutDim, val from: String, val why: String)

/**
 * 730 的对外读数。除 surface 三档外，还有体积/垂度/皱缩/贴合/提睾的方向档，
 * 供 731 的 pose/shape 引用；全部闭集档位，无连续量。
 */
internal data class ThermoReading(
    val volume: Int,
    val hang: Int,
    val rugosity: Int,
    val cling: Int,
    val cremasteric: Int,
    val surface: SurfaceReading,
    val note: String = "",
    val evidence: List<Evidence> = emptyList(),
)

/** 判据结论：红则 reading 为 null（红不出值）。 */
internal data class ThermoVerdict(
    val reading: ThermoReading?,
    val codes: List<String>,
    val messages: List<String>,
) {
    val red: Boolean get() = codes.isNotEmpty()

    companion object {
        fun ok(reading: ThermoReading) = ThermoVerdict(reading, emptyList(), emptyList())
        fun bad(codes: List<String>, messages: List<String>) = ThermoVerdict(null, codes, messages)
    }
}
