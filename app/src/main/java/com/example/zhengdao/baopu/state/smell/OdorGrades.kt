// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十七批 · 气味闭集档位与中性描述词。
package com.example.zhengdao.baopu.state.smell

internal const val ODOR_SOURCE =
    "派工单-2026-11-24-第八百二十七批（气味）＋本项目工程假设"
internal const val ODOR_AS_OF = "2026-11-24"
internal const val ODOR_DISCLAIMER = "非诊断·仅档位"

/** 存在档只答有没有；强度派生归 883，本批不重算。 */
internal enum class OdorPresence(val level: Int, val label: String) {
    NONE(0, "无"),
    FAINT(1, "隐约"),
    NOTICEABLE(2, "明显"),
    HEAVY(3, "很重"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 当下来源；职业只提供可能暴露，不能直接填这张表。 */
internal enum class OdorSource(val label: String) {
    BODY_SWEAT("体表汗味"),
    SCALP_OIL("头皮油脂"),
    ORAL("口腔"),
    FOOT("脚"),
    CLOTHING_COOKING_FUME("衣物残留·油烟"),
    CLOTHING_SMOKE("衣物残留·烟"),
    CLOTHING_DETERGENT("衣物残留·洗衣液"),
    ENV_ALCOHOL("环境·酒"),
    ENV_MEDICINE("环境·药"),
    ENV_PERFUME("环境·香水"),
}

/** 中性词表：闭集九个，note 也不许绕开医学与价值判断红线。 */
internal enum class OdorWord(val label: String) {
    SWEAT("汗"),
    SOUR("酸"),
    OILY("油"),
    SMOKE("烟"),
    DISINFECTANT("消毒水"),
    SOAP("皂香"),
    LEATHER("皮革"),
    METAL("金属"),
    STUFFY("潮闷"),
}

/** 下体分区；会阴待 e1，肛周默认不写并由本批判红。 */
internal enum class OdorRegion(val label: String, val lowerBody: Boolean) {
    CORONAL_SULCUS("冠状沟", true),
    PREPUCE_INNER("包皮内板", true),
    SCROTUM("阴囊", true),
    ROOT("根部", true),
    PERIANAL("肛周", true),
    AXILLA("腋下", false),
    SCALP("头皮", false),
    ORAL("口腔", false),
    FOOT("脚", false),
}

/** 时间档自带「为什么现在有这个味」的解释，缺档由判定层判红。 */
internal enum class OdorTemporal(val label: String, val why: String) {
    JUST_WASHED("刚清洗后", "清洗移除了大部分来源，所以几乎无"),
    SAME_DAY("当日", "距清洗近，来源尚未明显积存"),
    DAYS_UNWASHED("数日未洗", "清洗间隔拉长，汗与残留有时间积存"),
    AFTER_EXERCISE("运动后", "运动带来新汗与体温升高，短时更易辨"),
    AFTER_SEX("性活动后", "前液、精液与汗混合，短时可辨"),
    MORNING("晨起", "夜间积存后，晨起更容易注意到口腔与下体气味"),
    NIGHT("夜间", "体温略降、出汗少，气味通常淡"),
}

/** 季节/室内微气候四档；四档方向必须全部出现在读数里。 */
internal enum class OdorSeason(val label: String) {
    SUMMER_HOT("夏·闷热"),
    RAINY_DAMP("梅雨·潮"),
    AUTUMN_WINTER_COLD("秋冬·干冷"),
    INDOOR_DRY("室内暖气·空调"),
}

/** 方向只表升降，不表强度，也不含任何连续量。 */
internal enum class Trend(val label: String) {
    UP("↑"),
    DOWN("↓"),
    FLAT("→"),
}

/** 跨场基线变化必须挂驱动；没有驱动就不许悄悄改。 */
internal enum class OdorChangeDriver(val label: String) {
    NONE("无"),
    CLEANING("清洗"),
    EXERCISE("运动"),
    CHANGE_CLOTHES("换衣"),
    NEW_FLUID("新液"),
    ENVIRONMENT("环境变化"),
}
