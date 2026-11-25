// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百二十批 · 测试夹具：八个组合。每个组合＝一份体格上下文＋一条尺寸记录。
//
// 组合表是判据①的读数台：身高、体重、骨架、年龄按极端值铺开，尺寸档与埋没档故意反着配
// （高×小、矮×大、胖×大、瘦×小），八条都必须过。两条特别点名的（185 配偏小、胖配粗大）
// 在 SizeCombosTest 里各有一条具名用例。
package com.example.zhengdao.baopu.state.bodyparts

internal object SizeFixtures {

    /** 体格上下文。数字含义见 BodyContext 的字段说明（本包不认单位）。 */
    fun context(stature: Int, mass: Int, skeleton: Int, age: Int): BodyContext =
        BodyContext(stature, mass, skeleton, age)

    fun profile(
        length: LengthTier,
        girth: Girth,
        glans: GlansTier,
        buried: BuriedLook = BuriedLook.NONE,
        timing: DevelopmentTiming = DevelopmentTiming.TYPICAL,
        look: DevelopmentLook = DevelopmentLook.NONE,
        shift: FlowShift = FlowShift.NONE,
        tier: ColorRef = ColorRef.SAME,
        source: SizeSource = SizeSource.OBSERVED,
        basis: String? = "自述观测 2026-11",
    ): SizeProfile = SizeProfile(length, girth, glans, buried, timing, look, shift, tier, source, basis)

    /** 一个组合：名字、上下文、记录、尺寸段的预期读数。 */
    data class Case(
        val name: String,
        val ctx: BodyContext,
        val profile: SizeProfile,
        val expectSize: String,
    )

    /** 八组合。顺序＝报告表顺序；`expectSize` 是手写期望（档名改了这里就红）。 */
    fun eight(): List<Case> = listOf(
        Case(
            "① 高×大 185",
            context(185, 80, 2, 34),
            profile(LengthTier.LONG, Girth.THICK, GlansTier.LARGE, BuriedLook.NONE,
                DevelopmentTiming.TYPICAL, DevelopmentLook.FAINT, FlowShift.NONE, ColorRef.DARKER,
                SizeSource.OBSERVED, "自述观测 2026-11"),
            "尺寸=长／粗／相对偏大",
        ),
        Case(
            "② 高×小 185",
            context(185, 70, 2, 27),
            profile(LengthTier.SHORT, Girth.THIN, GlansTier.SMALL, BuriedLook.OBVIOUS,
                DevelopmentTiming.LATE, DevelopmentLook.NONE, FlowShift.SLIGHT, ColorRef.SAME,
                SizeSource.OBSERVED, "自述观测 2026-11"),
            "尺寸=短／细／相对偏小",
        ),
        Case(
            "③ 矮×大 160",
            context(160, 62, 0, 41),
            profile(LengthTier.LONG, Girth.THICK, GlansTier.VERY_LARGE, BuriedLook.DEEP,
                DevelopmentTiming.EARLY, DevelopmentLook.VISIBLE, FlowShift.OBVIOUS, ColorRef.DARKER,
                SizeSource.ARCHIVE, "旧档案 2019"),
            "尺寸=长／粗／相对很大",
        ),
        Case(
            "④ 矮×小 160",
            context(160, 58, 0, 23),
            profile(LengthTier.BELOW, Girth.SLIM, GlansTier.MID, BuriedLook.SLIGHT,
                DevelopmentTiming.TYPICAL, DevelopmentLook.FAINT, FlowShift.NONE, ColorRef.LIGHTER,
                SizeSource.OBSERVED, "自述观测 2026-11"),
            "尺寸=偏短／偏细／相对中",
        ),
        Case(
            "⑤ 胖×大 165/170",
            context(165, 170, 3, 55),
            profile(LengthTier.LONG, Girth.THICK, GlansTier.LARGE, BuriedLook.OBVIOUS,
                DevelopmentTiming.TYPICAL, DevelopmentLook.NONE, FlowShift.OBVIOUS, ColorRef.DARKER,
                SizeSource.OBSERVED, "自述观测 2026-11"),
            "尺寸=长／粗／相对偏大",
        ),
        Case(
            "⑥ 胖×小 170/150",
            context(170, 150, 3, 61),
            profile(LengthTier.SHORT, Girth.THIN, GlansTier.SMALL, BuriedLook.DEEP,
                DevelopmentTiming.LATE, DevelopmentLook.NONE, FlowShift.NONE, ColorRef.SAME,
                SizeSource.ARCHIVE, "旧档案 2021"),
            "尺寸=短／细／相对偏小",
        ),
        Case(
            "⑦ 瘦×大 178/45",
            context(178, 45, 1, 72),
            profile(LengthTier.LONG, Girth.THICK, GlansTier.VERY_LARGE, BuriedLook.SLIGHT,
                DevelopmentTiming.EARLY, DevelopmentLook.DISTINCT, FlowShift.SLIGHT, ColorRef.MUCH_DARKER,
                SizeSource.OBSERVED, "自述观测 2026-11"),
            "尺寸=长／粗／相对很大",
        ),
        Case(
            "⑧ 瘦×小 175/48",
            context(175, 48, 1, 19),
            profile(LengthTier.BELOW, Girth.SLIM, GlansTier.MID, BuriedLook.NONE,
                DevelopmentTiming.TYPICAL, DevelopmentLook.NONE, FlowShift.NONE, ColorRef.SAME,
                SizeSource.OBSERVED, "自述观测 2026-11"),
            "尺寸=偏短／偏细／相对中",
        ),
    )
}
