// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零六批 · 判红码册的唯一真相：红与提示分两档；判不出来就不出值。
// 码名固定，报告、单测、变异三处逐字对齐，不在别处另抄一份。
package com.example.zhengdao.baopu.state.surfaceflow

/** 一条问题的严重度：红＝这样写就是错的；提示＝缺个交代，补上再用。 */
internal enum class IssueSeverity(val label: String) {
    RED("红"),
    PROMPT("提示"),
}

/** 码册：八条判据 + 汗三点 + 完整性，共 15 条，逐个唯一。 */
internal object SurfaceCodes {
    const val UNIFORM_RUNOFF = "SF-01-全身均匀流下"
    const val POOL_MISSING_STERNAL = "SF-02-胸沟不积水"
    const val POOL_MISSING_CONCAVE = "SF-02-凹处不积水"
    const val POOL_NO_OUTLET = "SF-02-积水无出口"
    const val DRIP_NO_LOWEST = "SF-03-最低点未标"
    const val PREPUCE_NO_BASIS = "SF-04-包皮依据缺失"
    const val LARYNX_NO_SPLIT = "SF-05-喉结未分流"
    const val HAIR_DRY = "SF-06-毛未沾水"
    const val HAIR_NO_WICK = "SF-06-沿毛束无毛束"
    const val NUMERIC_VALUE = "SF-07-给点值"
    const val POSTURE_MISSING = "SF-08-体位缺失"
    const val SWEAT_MISSING = "SF-S-汗无分区"
    const val SWEAT_NO_ZONES = "SF-S-分区不足"
    const val SWEAT_DUP_ZONE = "SF-S-分区重复"
    const val SWEAT_UNIFORM = "SF-S-分区同档"
    const val SWEAT_NO_EVAP = "SF-S-蒸发档缺失"
    const val SWEAT_SKIP_SEEP = "SF-S-跳过就地渗出"
    const val SWEAT_EARLY_RUN = "SF-S-量不够先汇流"
    const val CROSS_DOMAIN = "SF-X-体液越界"

    /** 唯一真相：全部码按声明序，供去重与计数核对。 */
    val ALL: List<String> = listOf(
        UNIFORM_RUNOFF,
        POOL_MISSING_STERNAL,
        POOL_MISSING_CONCAVE,
        POOL_NO_OUTLET,
        DRIP_NO_LOWEST,
        PREPUCE_NO_BASIS,
        LARYNX_NO_SPLIT,
        HAIR_DRY,
        HAIR_NO_WICK,
        NUMERIC_VALUE,
        POSTURE_MISSING,
        SWEAT_MISSING,
        SWEAT_NO_ZONES,
        SWEAT_DUP_ZONE,
        SWEAT_UNIFORM,
        SWEAT_NO_EVAP,
        SWEAT_SKIP_SEEP,
        SWEAT_EARLY_RUN,
        CROSS_DOMAIN,
    )
}
