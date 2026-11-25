// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十批 · 体格上下文：身高／体重／骨架／年龄。
//
// 这个类的存在**就是为了被忽略**：尺寸层收下它，但只拿它判一件事——够不够成年。
// 三个体格维度（身高、体重、骨架宽窄）在这条链上没有任何读者；年龄只进成年闸。
// 「不推导」不是写在注释里的自觉，而是 SizeSourceAssertTest 钉住的断言：
// 装配文件（SizeGate.kt）里 `context.` 只许出现一次，出现第二次就红。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 体格上下文。四个字段都是**外部口径的整数**，本包不认单位、不做换算、不参与尺寸档。
 *
 * @param stature 身高（整数记，单位由调用方口径定，本包不认）
 * @param mass 体重（同上）
 * @param skeleton 骨架宽窄 0–3（窄→宽）
 * @param ageYears 年龄（整数年）
 */
data class BodyContext(
    val stature: Int,
    val mass: Int,
    val skeleton: Int,
    val ageYears: Int,
) {
    init {
        require(stature > 0) { STATURE_REJECT }
        require(mass > 0) { MASS_REJECT }
        require(skeleton in 0..SKELETON_LAST) { SKELETON_REJECT }
        require(ageYears in 0..AGE_MAX) { AGE_REJECT }
    }

    /** 成年闸：唯一允许读年龄的地方。未成年 ⇒ 尺寸层整层不出（判据⑤）。 */
    val adult: Boolean get() = ageYears >= ADULT_MIN

    companion object {
        const val ADULT_MIN: Int = 18
        const val AGE_MAX: Int = 120
        const val SKELETON_LAST: Int = 3

        const val STATURE_REJECT: String = "身高须为正整数（本包不认单位，只要求是正数）"
        const val MASS_REJECT: String = "体重须为正整数（本包不认单位，只要求是正数）"
        const val SKELETON_REJECT: String = "骨架宽窄只有 0–3 四档"
        const val AGE_REJECT: String = "年龄须在 0–120 之间"
    }
}
