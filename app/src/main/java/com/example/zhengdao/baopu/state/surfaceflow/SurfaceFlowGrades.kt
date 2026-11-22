// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 体表水流路径：档位与部位的唯一真相表。
// 全包只有档位与部位：没有体积、长度、时长、流速、频率等任何点值。
// 与 780 的关系：只对齐「量档 0–3：无/微/中/多」这一口径，不引用、不 import 其代码；
// 体液分泌与混合归 780，本包只管外部水与汗在体表的走法。
package com.example.zhengdao.baopu.state.surfaceflow

/** 统一依据口径：本单派工单＋工程假设，不引用任何诊疗资料原文。 */
internal const val SURFACE_SOURCE =
    "派工单-2026-11-22-第八百零六批（体表水流路径）＋本项目工程假设"
internal const val SURFACE_AS_OF = "2026-11-22"

/** 本单只产档位与部位，不下结论、不给行为指导。 */
internal const val SURFACE_DISCLAIMER = "非诊断·仅档位与部位"

/** 地形四要素：凸分流、凹积水、尖挂滴、缘导流槽。四类恰好四值。 */
internal enum class TerrainKind(val label: String) {
    CONVEX("凸"),
    CONCAVE("凹"),
    APEX("尖"),
    RIM("缘"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 体表部位：每个部位在声明时就绑定唯一地形，不允许同一部位两种地形。
 * 凸＝喉结/胸肌/腹肌块/膝；凹＝胸沟/脐/腹股沟/冠状沟/腋下；
 * 尖＝龟头顶/乳头/鼻尖；缘＝包皮口缘/耳廓/甲缘。
 */
internal enum class Site(val label: String, val kind: TerrainKind) {
    LARYNX("喉结", TerrainKind.CONVEX),
    PECTORAL("胸肌", TerrainKind.CONVEX),
    ABS_BLOCK("腹肌块", TerrainKind.CONVEX),
    KNEE("膝", TerrainKind.CONVEX),

    STERNAL_GROOVE("胸沟", TerrainKind.CONCAVE),
    NAVEL("脐", TerrainKind.CONCAVE),
    GROIN("腹股沟", TerrainKind.CONCAVE),
    CORONAL_SULCUS("冠状沟", TerrainKind.CONCAVE),
    AXILLA("腋下", TerrainKind.CONCAVE),

    GLANS_TIP("龟头顶", TerrainKind.APEX),
    NIPPLE("乳头", TerrainKind.APEX),
    NOSE_TIP("鼻尖", TerrainKind.APEX),

    PREPUCE_RIM("包皮口缘", TerrainKind.RIM),
    AURICLE("耳廓", TerrainKind.RIM),
    NAIL_EDGE("甲缘", TerrainKind.RIM),
    ;

    companion object {
        const val COUNT = 15

        fun ofKind(kind: TerrainKind): List<Site> = entries.filter { it.kind == kind }
    }
}

/** 水的来路：只认外部水与汗。体液来源不在本包字段里，出现即越界。 */
internal enum class WaterSource(val id: String, val label: String) {
    EXTERNAL("external", "外部水"),
    SWEAT("sweat", "汗"),
    ;

    companion object {
        const val COUNT = 2
    }
}

/** 三行为：分流／积水／挂滴；毛束导流是修饰，不占行为位。 */
internal enum class FlowBehavior(val label: String) {
    SPLIT("分流"),
    POOL("积水"),
    HANG("挂滴"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 路径四型：中线／侧线／沿沟／沿毛束。只给型，不给路线长度与走向角度。 */
internal enum class PathKind(val label: String) {
    MIDLINE("中线"),
    LATERAL("侧线"),
    ALONG_GROOVE("沿沟"),
    ALONG_HAIR("沿毛束"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 积水三档：无／短暂／明显。积水必须有出口，出口另记在 PoolRecord 上。 */
internal enum class PoolLevel(val level: Int, val label: String) {
    NONE(0, "无"),
    BRIEF(1, "短暂"),
    OBVIOUS(2, "明显"),
    ;

    companion object {
        const val COUNT = 3

        fun of(level: Int): PoolLevel = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/**
 * 量档 0–3：无/微/中/多。与 780 的口径同标度、档名逐字相同（只对齐口径，不引用其代码）。
 * 用于「就地渗出量够才汇流」这一道闸，不给任何体积值。
 */
internal enum class FlowVolume(val level: Int, val label: String) {
    NONE(0, "无"),
    TRACE(1, "微"),
    MODERATE(2, "中"),
    COPIOUS(3, "多"),
    ;

    companion object {
        const val COUNT = 4
        const val RUN_MIN_LEVEL = 2

        fun of(level: Int): FlowVolume = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 就地渗出的阶段：先起珠、再成片，量够才汇流。阶段只表先后，不表时长。 */
internal enum class SeepStage(val level: Int, val label: String) {
    BEADING(0, "起珠"),
    SHEETING(1, "成片"),
    RUNNING(2, "汇流"),
    ;

    companion object {
        const val COUNT = 3

        fun of(level: Int): SeepStage = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 汗的分区：不许全身同档；每一个读数必须点名一个区，表内没有「全身」这个档。 */
internal enum class SweatZone(val label: String) {
    SCALP("头皮"),
    FACE("面"),
    NECK("颈"),
    CHEST("胸"),
    BACK("背"),
    BELLY("腹"),
    GROIN("股间"),
    ARM("臂"),
    LEG("腿"),
    ;

    companion object {
        const val COUNT = 9
    }
}

/** 蒸发档 0–3：不显／微／中／显。只表强弱，不给任何蒸发速率。 */
internal enum class EvapTier(val level: Int, val label: String) {
    NONE(0, "不显"),
    SLIGHT(1, "微"),
    MIDDLE(2, "中"),
    CLEAR(3, "显"),
    ;

    companion object {
        const val COUNT = 4

        fun of(level: Int): EvapTier = entries.first { it.level == level.coerceIn(0, COUNT - 1) }
    }
}

/** 体位五档：与 780 的档名逐字对齐，纯字符串口径，不引用其类型。 */
internal enum class FlowPosture(val label: String) {
    STANDING("站立"),
    SITTING("坐姿"),
    SUPINE("仰卧"),
    PRONE("俯卧"),
    LATERAL("侧卧"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 包皮组织档：滴落点在包皮口缘时必填，只给档不给长度值。 */
internal enum class PrepuceTissue(val label: String) {
    TIGHT("紧"),
    MIDDLE("中"),
    LOOSE("松"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 包皮长度档：滴落点在包皮口缘时必填，只给档不给长度值。 */
internal enum class PrepuceLength(val label: String) {
    SHORT("短"),
    MIDDLE("中"),
    LONG("长"),
    ;

    companion object {
        const val COUNT = 3
    }
}
