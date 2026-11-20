// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 734 合并 736 §四④：六维感受（阻力/顺滑/温度/包覆/压迫/钝痛，各 0–3），
//   顺滑必须引用 730/732 的湿/润滑档（不许自造）；四段入口不许互读
//   （感受不得由节律/冲击/分泌档推出）。
//
// 本文件刻意不 import Rhythm / SensitivityCurve / SecretionCurve / TimeAxes，
// 也不读频率/深度/回撤/强度/累积档：六维全部由各自独立入参构造，
// 顺滑只读 [WetnessSource] 契约缝（732 未接时用 [UnwiredWetness] 恒 0 档）。
package com.example.zhengdao.baopu.state.stimulus

/** 湿/润滑档只读契约缝：由 730/732 提供，本包不自行推演润滑度。 */
internal interface WetnessSource {
    /** 润滑/湿膜档 0–3。 */
    fun wetnessGrade(): Int
}

/** 732 未接时的默认源：恒 0 档（干涩），并明示「未接」。 */
internal object UnwiredWetness : WetnessSource {
    override fun wetnessGrade(): Int = 0
}

/** 六维读数：五维独立入参 + 顺滑只读湿档源，字段齐、各 0–3。 */
internal data class SensationReading(
    val resistance: Int,
    val smoothness: Int,
    val temperature: Int,
    val envelopment: Int,
    val pressure: Int,
    val dullPain: Int,
)

internal object Sensation {
    /**
     * 构造六维：顺滑不接收入参，只读 [source]，保证与 730/732 同源、不自造。
     * 其余五维各自独立入参，钳在 0–3；任何一维都不由节律/冲击计算。
     */
    fun of(
        resistance: Int,
        temperature: Int,
        envelopment: Int,
        pressure: Int,
        dullPain: Int,
        source: WetnessSource = UnwiredWetness,
    ): SensationReading = SensationReading(
        resistance = resistance.coerceIn(0, SensationDimension.MAX),
        smoothness = source.wetnessGrade().coerceIn(0, SensationDimension.MAX),
        temperature = temperature.coerceIn(0, SensationDimension.MAX),
        envelopment = envelopment.coerceIn(0, SensationDimension.MAX),
        pressure = pressure.coerceIn(0, SensationDimension.MAX),
        dullPain = dullPain.coerceIn(0, SensationDimension.MAX),
    )
}
