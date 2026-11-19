// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数：事实底盘 md 常量表实现（临时真源）。
// 本文件是全仓唯一允许出现分布字面量的地方；665 的 facts.json 落地后换 FactsFromJson，
// 调用点一行不动（同 RecallSearchPort 先例）。
package com.example.zhengdao.baopu.state.form

/** md §四 的来源标签与日期：每条取值都必须带。 */
private const val MD_SOURCE = "事实底盘 md §四"
private const val MD_AS_OF = "2026-11-16"

/**
 * 临时真源：事实底盘 md §四（用户已核，数值在）。
 * 长度分位照抄；周长分位照抄 95/99，50 分位以均值代（正态假设，标 ○）。
 * 不提供任何"身高 → 尺寸"的换算入口。
 */
object FactsFromMd : FactSource {
    override val lengthRangePct = PercentileTable(
        id = "length",
        unit = "mm",
        points = listOf(
            PercentilePoint(50, 131.0, Evidence.VERIFIED),
            PercentilePoint(90, 153.0, Evidence.VERIFIED),
            PercentilePoint(95, 159.0, Evidence.VERIFIED),
            PercentilePoint(99, 170.0, Evidence.VERIFIED),
        ),
        source = MD_SOURCE,
        asOf = MD_AS_OF,
    )

    override val girthRangePct = PercentileTable(
        id = "girth",
        unit = "mm",
        points = listOf(
            PercentilePoint(50, 116.6, Evidence.ASSUMPTION),
            PercentilePoint(95, 134.7, Evidence.VERIFIED),
            PercentilePoint(99, 142.2, Evidence.VERIFIED),
        ),
        source = MD_SOURCE,
        asOf = MD_AS_OF,
    )

    override val defaultCapMm = 46.0

    /** EHS 1–4 加 0＝无反应，共 5 档。 */
    override val hardnessGradeCount = 5

    override val skinTempRiseC = FactRange(
        id = "skinTempRise",
        low = 0.0,
        high = 1.0,
        unit = "℃",
        source = MD_SOURCE,
        asOf = MD_AS_OF,
        evidence = Evidence.ASSUMPTION,
    )

    override val statureCorrelationR = FactRange(
        id = "statureCorrelation",
        low = 0.2,
        high = 0.6,
        unit = "r",
        source = MD_SOURCE,
        asOf = MD_AS_OF,
        evidence = Evidence.CONSENSUS,
    )

    override val colorDeepenStep = FactRange(
        id = "colorDeepen",
        low = 1.0,
        high = 2.0,
        unit = "档",
        source = MD_SOURCE,
        asOf = MD_AS_OF,
        evidence = Evidence.CONSENSUS,
    )

    override val rareLength = RareLength(
        valueMm = 180.0,
        rarity = "≈1/600（约 0.16%）",
        notDefault = true,
        source = MD_SOURCE,
        asOf = MD_AS_OF,
        evidence = Evidence.VERIFIED,
    )

    override val diameterAdvisories = listOf(
        DiameterAdvisory(43.0, 46.0, "少见、需适应", MD_SOURCE, MD_AS_OF),
        DiameterAdvisory(46.0, 50.0, "极罕见＋硬度不足", MD_SOURCE, MD_AS_OF),
    )
}