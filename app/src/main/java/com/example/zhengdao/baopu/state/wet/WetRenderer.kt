// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十七批 · 唯一文字出口：固定顺序拼读数，过 805 的 BodyPartGuard。
// 纯函数、同输入逐字一致；红时没有读数可渲染。
package com.example.zhengdao.baopu.state.wet

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard

internal object WetRenderer {

    /** 唯一出口：五件逐个列出；蒸汽只写"看不清"，不写"被挡住"。 */
    fun line(value: WetReading): String {
        val text = listOf(
            "湿身=" + value.skinWet.label,
            "水珠=" + dripText(value.drips),
            "浴巾=" + towelText(value),
            "蒸汽=" + value.steamFog.label + "（" + STEAM_MEANS + "）",
            "偏温=" + value.warm.label,
            WET_DISCLAIMER,
        ).joinToString(" · ")
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        return text
    }

    private fun dripText(drips: List<DripRecord>): String = drips.joinToString("、") { drip ->
        drip.from.label + if (drip.to == null) "→离体" else "→" + drip.to.label
    }

    private fun towelText(value: WetReading): String {
        val tier = value.waistTier ?: return value.towel.label
        val slip = if (value.canSlip) "/可滑落" else ""
        return value.towel.label + "/" + tier.label + slip
    }
}
