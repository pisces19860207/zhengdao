// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 第八百一十五批 · 测试夹具：构造各种体型单，判据测试共用。
package com.example.zhengdao.baopu.state.bodytype

/** 夹具工厂：默认一张中中中单，按需改一两个坐标。 */
object BuildFixtures {

    fun mid(
        role: String = "甲",
        basis: Basis = Basis.NONE,
        influence: InfluenceEffect = InfluenceEffect.TENDENCY,
    ): BodyProfile = BodyProfile(
        roleId = role,
        skeletonTier = SkeletonTier.MID,
        bodyFatTier = BodyFatTier.MID,
        muscleTier = MuscleTier.MID,
        ageTier = AgeTier.MID,
        bodyStateTier = BodyStateTier.SEDENTARY,
        basis = basis,
        influence = influence,
    )

    /** 低脂高肌（无依据）：默认纠偏要判红的原例。 */
    fun lowFatHighMuscle(
        role: String = "乙",
        basis: Basis = Basis.NONE,
        influence: InfluenceEffect = InfluenceEffect.TENDENCY,
    ): BodyProfile = BodyProfile(
        roleId = role,
        skeletonTier = SkeletonTier.WIDE,
        bodyFatTier = BodyFatTier.LOW,
        muscleTier = MuscleTier.AMPLE,
        ageTier = AgeTier.YOUNG,
        bodyStateTier = BodyStateTier.TRAINING,
        basis = basis,
        influence = influence,
    )

    /** 与三轴一致的截面（供判据③正例）。 */
    fun matchedSection(p: BodyProfile): SectionSpec = SectionSpec(
        width = BuildCatalog.widthOf(p.skeletonTier),
        thickness = BuildCatalog.thicknessOf(p.bodyFatTier),
        girth = BuildCatalog.girthOf(p.muscleTier),
    )

    /** 与三轴全反的截面（供判据③红例）：三档各错两级以上。 */
    fun opposedSection(p: BodyProfile): SectionSpec {
        val flip = { g: SectionGrade -> if (g.level <= 0) SectionGrade.WIDE else SectionGrade.NARROW }
        return SectionSpec(
            width = flip(BuildCatalog.widthOf(p.skeletonTier)),
            thickness = flip(BuildCatalog.thicknessOf(p.bodyFatTier)),
            girth = flip(BuildCatalog.girthOf(p.muscleTier)),
        )
    }
}
