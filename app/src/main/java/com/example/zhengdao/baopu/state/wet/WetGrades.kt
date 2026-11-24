// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百九十七批 · 浴后状态：五件档位与部位的唯一真相表。
// 只有档、部位与拍数：不给摄氏度、毫升、秒、长度、流速等任何点值。
// 与邻单分工：806 管"水往哪流"，730 管温湿档与拍钟，827 管皂香，861 管"被挡住"；
// 本包只读这些口径的名字，不 import、不改邻单一行。
package com.example.zhengdao.baopu.state.wet

internal const val WET_SOURCE =
    "派工单-第八百九十七批（浴后状态：湿身·水珠·浴巾·蒸汽）＋本项目工程假设"
internal const val WET_AS_OF = "2026-11-24"
internal const val WET_DISCLAIMER = "仅档位与拍数·不作物象解读"

/** 蒸汽与遮挡的分工口径，两个句子不许互换。 */
internal const val STEAM_MEANS = "蒸汽=看不清"
internal const val OCCLUSION_MEANS = "遮挡=被挡住"

/** 湿身四档：半干是衰减终点，不是洗完那一刻。 */
internal enum class SkinWet(val level: Int, val label: String) {
    HALF_DRY(0, "半干"),
    BEADING(1, "挂水"),
    GLOSSY(2, "湿亮"),
    DRIPPING(3, "滴水"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): SkinWet = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/**
 * 滴落起点五个，order 是从头到脚的重力次序；水珠只能往 order 更大的部位走。
 * 次序是结构常量，与体位无关；不做流体仿真（806 已定）。
 */
internal enum class DripSource(val order: Int, val label: String) {
    HAIR_TIP(0, "发梢"),
    JAW(1, "下颌"),
    CHEST_BELLY(2, "胸腹"),
    BACK(3, "背部"),
    LEG(4, "腿"),
    ;

    companion object {
        const val COUNT = 5

        fun of(order: Int): DripSource = entries.first { it.order == order.coerceIn(0, COUNT - 1) }
    }
}

/** 浴巾五种处置；围腰另带松紧档，松档必须能表达滑落。 */
internal enum class TowelState(val label: String) {
    NONE("无（裸）"),
    WAIST("围腰"),
    SHOULDER("搭肩"),
    HANDHELD("手持"),
    SET_DOWN("已放下"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 围腰松紧三档：松档即可滑落，紧档只是当刻状态，不写成永远不掉。 */
internal enum class WaistTier(val level: Int, val label: String, val canSlip: Boolean) {
    LOOSE(0, "松·将滑", true),
    MIDDLE(1, "中", false),
    SECURE(2, "紧", false),
    ;

    companion object {
        const val COUNT = 3

        fun of(level: Int): WaistTier = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 蒸汽三档：只表"看不清"的程度，不表"被挡住"。 */
internal enum class SteamFog(val level: Int, val label: String) {
    NONE(0, "无"),
    THIN(1, "薄"),
    DENSE(2, "浓"),
    ;

    companion object {
        const val COUNT = 3

        fun of(level: Int): SteamFog = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 体表偏温：0 为已回落，1–2 为刚洗完的偏温档。 */
internal enum class WarmLevel(val level: Int, val label: String) {
    SETTLED(0, "已回落"),
    SLIGHT(1, "偏温一档"),
    CLEAR(2, "偏温二档"),
    ;

    companion object {
        const val COUNT = 3
        const val MAX_LEVEL = 2

        fun of(level: Int): WarmLevel = entries.first { it.level == level.coerceIn(0, MAX_LEVEL) }
    }
}

/** 外部传入的干燥空气档：只接档位，不自算天气（730/657 的口径不在这里重算）。 */
internal enum class DryAir(val level: Int, val label: String) {
    STILL(0, "静"),
    BREEZE(1, "微风"),
    WINDY(2, "风大"),
    ;

    companion object {
        const val COUNT = 3
    }
}
