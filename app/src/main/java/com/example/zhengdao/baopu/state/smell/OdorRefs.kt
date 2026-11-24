// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 外部档位引用：只收上游产出的档，不在本批重算。
package com.example.zhengdao.baopu.state.smell

import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.fluid.DrynessGrade
import com.example.zhengdao.baopu.state.fluid.FluidKind
import com.example.zhengdao.baopu.state.fluid.VolumeGrade
import com.example.zhengdao.baopu.state.surfaceflow.FlowVolume
import com.example.zhengdao.baopu.state.surfaceflow.SweatZone

/** 730 尚未合入：只接收它的热/湿/汗档序，不复制档名，也不重造天气档。 */
internal data class ThermoTierRef(
    val thermalLevel: Int,
    val humidityLevel: Int,
    val sweatGrade: Int,
) {
    val source: String get() = "730"

    init {
        require(thermalLevel in 0..3) { "730 热档须为 0..3" }
        require(humidityLevel in 0..3) { "730 湿档须为 0..3" }
        require(sweatGrade in 0..3) { "730 汗档须为 0..3" }
    }
}

/** 657 已合入可直接消费；730 未合入时用带来源的档位引用，二者至少给一个。 */
internal data class ClimateEvidence(
    val env657: EnvironmentState? = null,
    val thermo730: ThermoTierRef? = null,
) {
    init {
        require(env657 != null || thermo730 != null) { "气候依据必须来自 657 或 730" }
    }
}

/** 883 的强度派生结果只能整档引用；本类型没有计算公式。 */
internal data class SmellIntensityRef(val smellIntensity: Int) {
    val source: String get() = "883"

    init {
        require(smellIntensity in 0..3) { "883 强度档须为 0..3" }
    }
}

/** 下体来源三选一：包皮垢、780 三液残留、806/730 股间汗。 */
internal sealed interface LowerBodySource {
    val label: String
    val source: String
}

internal data class SmegmaSource(val smegmaTier: Int) : LowerBodySource {
    override val source: String get() = "883"
    override val label: String get() = "包皮垢档$smegmaTier"

    init {
        require(smegmaTier in 0..3) { "883 包皮垢档须为 0..3" }
    }
}

internal data class FluidResidueSource(
    val kind: FluidKind,
    val volume: VolumeGrade,
    val dryness: DrynessGrade,
) : LowerBodySource {
    override val source: String get() = "780"
    override val label: String get() = kind.label + "残留·" + dryness.label

    init {
        require(kind != FluidKind.SALIVA) { "780 三液残留只认汗/前液/精液" }
    }
}

internal data class SweatSource(
    val zone: SweatZone,
    val volume: FlowVolume,
) : LowerBodySource {
    override val source: String get() = "806/730"
    override val label: String get() = zone.label + "汗·" + volume.label

    init {
        require(zone == SweatZone.GROIN) { "下体汗源只认 806 股间区" }
    }
}
