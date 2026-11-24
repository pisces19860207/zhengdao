// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 唯一渲染出口：只印档位、来源与方向，不印连续量。
package com.example.zhengdao.baopu.state.smell

internal object OdorRenderer {

    fun render(reading: OdorReading): String = buildString {
        append("气味[").append(reading.odorPresent.label).append("]")
        append("|来源[").append(labels(reading.odorSource.map { it.label })).append("]")
        append("|词[").append(labels(reading.odorWord.map { it.label })).append("]")
        append("|区域[").append(labels(reading.odorRegion.map { it.label })).append("]")
        append("|时间=").append(reading.odorTemporal.label).append("(").append(reading.odorTemporal.why).append(")")
        append("|季节=").append(reading.currentSeason.label)
        append("|四季方向[").append(reading.odorSeasonal.joinToString(";") { directionLine(it) }).append("]")
        append("|气候=").append(reading.climate.trend.label)
        append("(来源").append(reading.climate.source).append(",汗档").append(reading.climate.sweatGrade).append(")")
        append("|下体依据[").append(labels(reading.lowerBodyOrigins.map { it.label + "@" + it.source })).append("]")
        append("|强度=").append(intensityLine(reading.smellIntensity))
        if (reading.note.isNotBlank()) append("|附言=").append(reading.note)
        append("|").append(ODOR_DISCLAIMER)
    }

    private fun directionLine(value: SeasonalDirection): String = buildString {
        append(value.season.label).append(":汗").append(value.sweat.label)
        append("/湿").append(value.humidity.label)
        append("/体味").append(value.bodyOdor.label)
        append("/衣物").append(value.clothingResidue.label)
        append("/下体").append(value.genitalOdor.label)
    }

    private fun intensityLine(value: SmellIntensityRef?): String =
        if (value == null) "缺省(883未给)" else "引用" + value.source + "=档" + value.smellIntensity

    private fun labels(values: Collection<String>): String =
        values.sorted().joinToString("+").ifEmpty { "无" }
}
