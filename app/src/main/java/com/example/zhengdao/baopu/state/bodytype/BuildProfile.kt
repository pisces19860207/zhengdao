// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百一十五批 · 体型档案：一个人的五坐标怎么记。
//
// 「五档齐」在这里是**结构性**的，不靠自觉：五个坐标字段全是非空枚举，少一个就构造不出来。
// 字段名就是对外接口，逐字固定，供 865（骨点可见度）／866（体脂联动）／874（换真枚举）／
// 869（卡四组）／848（档案倾向）引用——**下游按名取字段，本包不许改名**。
//
// 修饰维（年龄／状态）只作坐标，不进三轴合法组合、不计常见程度——状态不打分。
// 依据（basis）与影响方式（influence）记在一起：职业只影响倾向，写成「决定」即红。
package com.example.zhengdao.baopu.state.bodytype

/**
 * 一个人的体型档案。五个 tier 字段即接口，逐字锁定：
 * skeletonTier / bodyFatTier / muscleTier / ageTier / bodyStateTier
 *
 * @param roleId 角色标识，用于结论点名。
 * @param basis 少见组合的依据；[Basis.NONE] 表示没有依据。
 * @param influence 依据的影响方式；职业只能是 [InfluenceEffect.TENDENCY]。
 */
data class BodyProfile(
    val roleId: String,
    val skeletonTier: SkeletonTier,
    val bodyFatTier: BodyFatTier,
    val muscleTier: MuscleTier,
    val ageTier: AgeTier,
    val bodyStateTier: BodyStateTier,
    val basis: Basis = Basis.NONE,
    val influence: InfluenceEffect = InfluenceEffect.TENDENCY,
) {
    init {
        require(roleId.isNotBlank()) { "角色缺标识：结论点名不了" }
    }

    /** 三轴组合（修饰维不进组合）。 */
    val combo: ComboKey get() = ComboKey(skeletonTier, bodyFatTier, muscleTier)

    /** 是不是「低脂高肌」——默认纠偏与依据闸盯的就是它。 */
    val lowFatHighMuscle: Boolean get() = combo.lowFatHighMuscle

    /** 常见程度（表驱动，不另算）。 */
    val commonness: Commonness get() = BuildCatalog.entryOf(skeletonTier, bodyFatTier, muscleTier).commonness

    /** 一行档案快照：五坐标＋依据。这是唯一出口，出门前过三道闸。 */
    fun line(): String {
        val text = roleId + "｜骨架 " + skeletonTier.label +
            "·体脂 " + bodyFatTier.label +
            "·肌量 " + muscleTier.label +
            "·年龄 " + ageTier.label +
            "·状态 " + bodyStateTier.label +
            "｜依据 " + basis.label + "（" + influence.label + "）"
        BuildGuard.rejectAll(text)
        return text
    }

    companion object {
        /** 默认档案：骨架中·体脂中·肌量中（纠偏锚）。修饰维取各自中档，无依据。 */
        val DEFAULT: BodyProfile = BodyProfile(
            roleId = "默认",
            skeletonTier = SkeletonTier.MID,
            bodyFatTier = BodyFatTier.MID,
            muscleTier = MuscleTier.MID,
            ageTier = AgeTier.MID,
            bodyStateTier = BodyStateTier.SEDENTARY,
            basis = Basis.NONE,
            influence = InfluenceEffect.TENDENCY,
        )

        /** 五个字段名——接口锁的真相，源码门禁按名逐一核对。 */
        val TIER_FIELDS: List<String> = listOf(
            "skeletonTier", "bodyFatTier", "muscleTier", "ageTier", "bodyStateTier",
        )
    }
}
