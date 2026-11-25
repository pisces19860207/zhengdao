// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十批 · 下体尺寸与身高体重年龄解耦：档位闭集。
//
// 为什么要这一批：「身高高⇒下体大」「胖⇒小」「年龄大⇒大」是最常见的想当然。660 的调节口径里
// 已经写了禁止由身高推尺寸，但那句话只住在文档里——**代码里没有这条闸**。本包把口径变成档位与断言：
//
// · 尺寸只有档，没有量：长短／粗细／龟头相对大小各四档，一个字都不换成长度或围度（沿用 805 边界②）；
// · 来源只有三种：观测／档案／默认中性。默认中性＝每个字段的中性档（三个尺寸轴都落「中」）；
// · 埋没是「看起来」，与本体尺寸各记各的：埋没明显可以配偏大，无埋没也可以配偏小；
// · 发育时相（早／常／晚）只是背景，与尺寸没有推导边；
// · 颜色分两层：色变档（动态·血流）与色档（静态·黑色素＋摩擦）是两个字段、两个类型——
//   类型不同，混写在编译期就不可能，不靠自觉。
//
// 粗细复用 805 的 Girth（细／偏细／中等／粗），色档复用 805 的 ColorRef（相对肤色五档）：
// 同一套相对档不另立第二套档名，免得一处说「中等」、一处说「中」。
package com.example.zhengdao.baopu.state.bodyparts

/** 本单唯一来源口径。对外每条结论都引用它，不许出处不明。 */
const val SIZE_SOURCE: String =
    "派工单-2026-11-25-第九百二十批（尺寸解耦）＋本项目工程假设"

/** 口径成形日：档位定义与组合夹具都以这一天为准。 */
const val SIZE_AS_OF: String = "2026-11-25"

/**
 * 尺寸档的来源。三种，不多不少（判据①）：
 * 观测（当场看到）、档案（旧记录）、默认中性（什么都没给，落中性档）。
 */
enum class SizeSource(val label: String) {
    OBSERVED("观测"),
    ARCHIVE("档案"),
    DEFAULT_NEUTRAL("默认中性"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/**
 * 长短 0–3。中性档＝[NEUTRAL]（「中」）。
 * 四档里只有「中」不带方向：落 3 会把「大」偷偷当默认，落 1 会把「小」偷偷当默认。
 */
enum class LengthTier(val level: Int, val label: String) {
    SHORT(0, "短"),
    BELOW(1, "偏短"),
    MID(2, "中"),
    LONG(3, "长"),
    ;

    companion object {
        const val LAST = 3

        /** 默认中性档：与 805 的 Girth 同构（0/1 在下、2/3 在上）。 */
        const val NEUTRAL = 2

        fun of(level: Int): LengthTier = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 龟头相对大小 0–3（**相对柱身**，不是绝对大小）。
 * 与 660 的口径同向：龟头大小只与柱身比，不落绝对量，也不由身高体重推。
 */
enum class GlansTier(val level: Int, val label: String) {
    SMALL(0, "相对偏小"),
    MID(1, "相对中"),
    LARGE(2, "相对偏大"),
    VERY_LARGE(3, "相对很大"),
    ;

    companion object {
        const val LAST = 3

        /** 中性档＝「相对中」（第 1 档）。这一轴两侧不对称（下只有「偏小」一档、上有两档），所以中档不在 2。 */
        const val NEUTRAL = 1

        fun of(level: Int): GlansTier = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 看着像埋没多少 0–3（无／略／明显／很埋）。
 * 这是**外观**，不是本体尺寸：腹部脂肪、衣物、体位、耻骨前突都能让它看起来不一样。
 * 两个方向都不许推：埋没明显不代表尺寸小，尺寸大也不代表没埋没。
 */
enum class BuriedLook(val level: Int, val label: String) {
    NONE(0, "无埋没"),
    SLIGHT(1, "略"),
    OBVIOUS(2, "明显"),
    DEEP(3, "很埋"),
    ;

    companion object {
        const val LAST = 3

        /** 默认中性：不额外声称埋没。 */
        const val NEUTRAL = 0

        fun of(level: Int): BuriedLook = entries[level.coerceIn(0, LAST)]
    }
}

/** 发育时相（早／常／晚）。**只作背景**：与尺寸没有推导边，也不与年龄联动。 */
enum class DevelopmentTiming(val level: Int, val label: String) {
    EARLY(0, "早"),
    TYPICAL(1, "常"),
    LATE(2, "晚"),
    ;

    companion object {
        const val LAST = 2

        const val NEUTRAL = 1

        fun of(level: Int): DevelopmentTiming = entries[level.coerceIn(0, LAST)]
    }
}

/** 发育相关外观 0–3（无可见标记／轻微／可见／明显）。与年龄、体重都不联动，只按看到的记。 */
enum class DevelopmentLook(val level: Int, val label: String) {
    NONE(0, "无可见标记"),
    FAINT(1, "轻微"),
    VISIBLE(2, "可见"),
    DISTINCT(3, "明显"),
    ;

    companion object {
        const val LAST = 3

        const val NEUTRAL = 0

        fun of(level: Int): DevelopmentLook = entries[level.coerceIn(0, LAST)]
    }
}

/**
 * 色变档 0–3（动态·血流）：此刻比常态深了多少。
 * 与 731 的 `colorShift` 同义；与 [ColorRef]（静态色档）**不是一个字段**，不许并成一句。
 */
enum class FlowShift(val level: Int, val label: String) {
    NONE(0, "无变化"),
    SLIGHT(1, "略深"),
    OBVIOUS(2, "明显深"),
    STRONG(3, "显著深"),
    ;

    companion object {
        const val LAST = 3

        const val NEUTRAL = 0

        fun of(level: Int): FlowShift = entries[level.coerceIn(0, LAST)]
    }
}
