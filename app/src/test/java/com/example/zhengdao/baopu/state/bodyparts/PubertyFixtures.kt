// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十九批 · 夹具：一份「未成年＋非性目的」的发育史，一份成年对照，
// 一份换过里程碑顺序的，以及三要素各自单独成立的四个目的事实。
//
// 夹具里的年龄与细节是**故意写全**的：撤销过紧口径之后，这些正是要放行的东西。
package com.example.zhengdao.baopu.state.bodyparts

object PubertyFixtures {

    /** 五维同档。 */
    fun tiers(tier: PubertyTier): Map<PubertyDim, PubertyTier> =
        PubertyDim.entries.associateWith { tier }

    /** 未起—初起—渐显—未起：一份典型的「正在长」的单。 */
    val spreading: Map<PubertyDim, PubertyTier> = mapOf(
        PubertyDim.GROWTH_SPURT to PubertyTier.DEVELOPING,
        PubertyDim.VOICE_CHANGE to PubertyTier.EARLY,
        PubertyDim.BODY_HAIR to PubertyTier.EARLY,
        PubertyDim.SHAPE_SHIFT to PubertyTier.EARLY,
        PubertyDim.SOCIAL_SIDE to PubertyTier.NOT_STARTED,
    )

    /** 未成年：年龄数值、身体变化细节、既往史细节都写进来（旧口径禁、现在允许）。 */
    fun boy14(): PubertyHistory = PubertyHistory(
        tiers = spreading,
        ageNote = "14 岁",
        details = listOf(
            "嗓子哑了一阵，后来又稳下来",
            "一年里裤腿短了两回",
            "小时候摔过一次，膝盖留了疤",
        ),
    )

    /** 成年对照：五维全定型。 */
    fun adult24(): PubertyHistory = PubertyHistory(
        tiers = tiers(PubertyTier.SETTLED),
        ageNote = "24 岁",
        details = listOf("早已定型，这几年没再变过"),
    )

    /** 换过顺序：体毛先于生长突增，属个体差异，不该判红。 */
    fun swappedOrder(): PubertyHistory = boy14().copy(
        milestoneOrder = listOf(
            PubertyMilestone.BODY_HAIR,
            PubertyMilestone.GROWTH_SPURT,
            PubertyMilestone.VOICE_LARYNX,
            PubertyMilestone.ODOR_ACNE,
            PubertyMilestone.MUSCLE_SHOULDER,
        ),
    )

    // ── 目的事实 ──────────────────────────────────────────────────────

    val nonSexualMinor: PurposeFrame = PurposeFrame(minor = true)
    val minorScene: PurposeFrame = PurposeFrame(minor = true, sexualScene = true)
    val minorGenital: PurposeFrame = PurposeFrame(minor = true, genitalFocus = true)
    val minorGaze: PurposeFrame = PurposeFrame(minor = true, sexualizedGaze = true)
    val minorAllThree: PurposeFrame = PurposeFrame(
        minor = true, sexualScene = true, genitalFocus = true, sexualizedGaze = true,
    )
    val adultAllThree: PurposeFrame = PurposeFrame(
        minor = false, sexualScene = true, genitalFocus = true, sexualizedGaze = true,
    )

    // ── 文字夹具（诊断结论与推断各两条、合规负例两条）──────────────────

    const val DIAGNOSIS_EARLY: String = "医生说是性早熟，让家里注意点"
    const val DIAGNOSIS_DELAYED: String = "看着像发育迟缓，个子一直没跟上"
    const val INFER_ABILITY: String = "体毛多，所以能力强"
    const val INFER_MORAL: String = "喉结这么早就明显，说明他品性稳重"
    const val OK_HOST: String = "变声比同学早，所以常被叫去当主持"
    const val OK_TALLER: String = "他发育得比同学早，所以个子比同桌高"
}
