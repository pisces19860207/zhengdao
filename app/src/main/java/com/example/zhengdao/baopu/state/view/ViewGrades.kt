// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 视点与相机的档位词汇表。
//
// 全包只有「档」，没有「量」：不出现厘米、角度、焦距（判据④）。所有几何都在**整数格**上算——
// 一格是八方位的一格，也是场景图格位的一格；格与档的换算法写在 Projection 里，逐条可复算。
// 这样第 859 批要 3D→2D 时，「遮挡／侧影／近大远小」不再各写各的：大家问同一套档。
package com.example.zhengdao.baopu.state.view

import kotlin.math.abs

/**
 * 八方位：以所在区域的「正前」为 0，顺时针一格一档。
 *
 * 方位只表示「第几格」，不表示角度——需要角度时才换算，而换算不在本包做（判据④）。
 * 顺序即顺时针：前 → 右前 → 右 → 右后 → 后 → 左后 → 左 → 左前。
 */
enum class Octant(val label: String) {
    FRONT("前"),
    FRONT_RIGHT("右前"),
    RIGHT("右"),
    BACK_RIGHT("右后"),
    BACK("后"),
    BACK_LEFT("左后"),
    LEFT("左"),
    FRONT_LEFT("左前"),
    ;

    companion object {
        /** 一圈八格。 */
        const val SIZE: Int = 8

        /** 正后方那一档：转过去正好半圈，没有左右之分。 */
        const val BEHIND_STEPS: Int = 4

        /**
         * 从 [from] 看向 [to] 的最短有符号格数。
         *
         * 返回值域 −3..4：负数在左、正数在右（−3 左后、−1 左前、1 右前、3 右后），
         * 4 表示正后方——正后方既不算左也不算右，单独给一个正数，不硬塞进两边。
         * 这一格数是「方位差」的唯一来源，屏幕左右与视锥判定都从它推，不许另算一套。
         */
        fun stepsBetween(from: Octant, to: Octant): Int {
            val raw = (to.ordinal - from.ordinal + SIZE) % SIZE
            return when {
                raw < BEHIND_STEPS -> raw
                raw == BEHIND_STEPS -> BEHIND_STEPS
                else -> raw - SIZE
            }
        }
    }
}

/**
 * 区域档：视点所在区域的**可用空间尺度档**（不是房间名）。
 *
 * **取值来源（明确写死，不许含糊）**：由调用方/装配单按场景图已经定好的空间尺度档**显式传入**；
 * 836 尚未入仓、场景图还没有尺度字段之前，由装配单负责裁定并把裁定结果写进视点。
 * 本包**自己不查场景名、不做「房间名→档位」硬映射**——「客厅」不等于「厅堂」：
 * 有人客厅很大、有人客厅很小，按名字猜档位就是把事实换成了想象。
 * 源码门禁（ViewSourceAssertTest）禁止本包出现房间名字面量与映射函数，改来源请去装配单。
 *
 * [scale] 是「一格有多大」的档位倍数（不是长度）：小室里一格就是一小步，旷野里同样一格
 * 抵得上厅堂的两格。同一个格距在不同区域里因此可以落到不同的距离档——距离档不是格数的别名。
 */
enum class RegionGrade(val label: String, val scale: Int) {
    SMALL_ROOM("小室", 1),
    HALL("厅堂", 2),
    OPEN_AIR("旷野", 3),
}

/** 距离档：近／中／远。三档由格距乘区域倍数得出，见 [Projection.distanceTier]。 */
enum class DistanceTier(val label: String, val rank: Int) {
    NEAR("近", 0),
    MID("中", 1),
    FAR("远", 2),
}

/**
 * 俯仰档：镜头平视／俯拍／仰拍。
 *
 * [liftSteps] 是「画面里往上抬几格」：俯拍时地面物件在画面里上移一格，仰拍时下移一格。
 * 注意方向——**镜头往下压，东西在画面里往上走**，写反了屏幕上下就整体颠倒。
 */
enum class PitchTier(val label: String, val liftSteps: Int) {
    LEVEL("平", 0),
    DOWN("俯", 1),
    UP("仰", -1),
}

/**
 * 视野档：窄／中／宽。
 *
 * [inSteps] 是「正前方左右各几格仍算视野内」，[edgeSteps] 是「算到边缘」的格数（恒为内带 +1）。
 * 三档的格数就是本包的 FOV——没有角度，FOV 收窄一档＝内带少一格。
 */
enum class FovTier(val label: String, val inSteps: Int) {
    NARROW("窄", 0),
    MEDIUM("中", 1),
    WIDE("宽", 2),
    ;

    /** 边缘带到几格：内带再宽一格，越过它就是视野外。 */
    val edgeSteps: Int get() = inSteps + 1
}

/** 可见范围三态：[severity] 越大越「看不见」，取最严时用 severity，不靠声明次序。 */
enum class Visibility(val label: String, val severity: Int) {
    INSIDE("视野内", 0),
    EDGE("边缘", 1),
    OUTSIDE("视野外", 2),
}

/** 屏幕横列三档。 */
enum class ScreenColumn(val label: String) {
    LEFT("左"),
    CENTER("中"),
    RIGHT("右"),
}

/** 屏幕纵列三档。 */
enum class ScreenRow(val label: String) {
    TOP("上"),
    MIDDLE("中"),
    BOTTOM("下"),
}

/**
 * 屏幕九宫格里的一格：左中右 × 上中下。
 *
 * 只是**投影结果**，不是位置。反推位置是被判据⑤ 明令禁止的：同一个屏幕格对应无穷多个
 * 世界位置（见 [Projection] 的不可逆说明），拿它当输入就等于把「看起来在左边」当成了坐标。
 */
data class ScreenCell(val column: ScreenColumn, val row: ScreenRow) {
    val label: String get() = column.label + row.label
}

/**
 * 横向三格（区域平面上的左右）：以区域中心为「中」。
 *
 * 位置用**三档**写，不用三个整数写：档是「第几格」的序号，整数看着像坐标，写着写着就变成量了。
 */
enum class Across(val label: String) {
    LEFT("左"),
    MIDDLE("中"),
    RIGHT("右"),
}

/** 纵深三格：ordinal 越大越靠前（朝区域正前）。 */
enum class Depth(val label: String) {
    BACK("后"),
    MIDDLE("中"),
    FRONT("前"),
}

/** 竖向三格：低于眼高／齐眼高／高于眼高。 */
enum class Height(val label: String) {
    LOW("下"),
    MIDDLE("中"),
    UP("上"),
}

/**
 * 场景图里的一格位：横向／纵深／竖向各一档，缺省是区域正中。
 *
 * 三档合起来最多 27 格，这就是本包全部的「位置」。它不是坐标：没有原点单位、没有轴线刻度，
 * 只有「在左中右的哪一格、前后哪一格、上下哪一格」。要更远的位移请换区域档，不要加格。
 */
data class Cell(
    val across: Across = Across.MIDDLE,
    val depth: Depth = Depth.MIDDLE,
    val height: Height = Height.MIDDLE,
) {
    /** 从本格到 [other] 的格差（三轴各 −2..2）。 */
    fun offsetTo(other: Cell): CellOffset = CellOffset(
        rightSteps = other.across.ordinal - across.ordinal,
        forwardSteps = other.depth.ordinal - depth.ordinal,
        upSteps = other.height.ordinal - height.ordinal,
    )

    /** 档名写法（「左前上」），报告与提示里用，不参与判定。 */
    val label: String get() = across.label + depth.label + height.label
}

/**
 * 两格的格差：三轴各是「序号之差」，不是坐标差。
 *
 * [span] 是三轴里绝对值最大的那一轴（切比雪夫格距）：横竖各走两格也只算「两格远」，
 * 不用勾股——本包不做平方根，也就不用浮点（判据④：出现 Double 就是量进来了）。
 */
data class CellOffset(val rightSteps: Int, val forwardSteps: Int, val upSteps: Int) {
    val span: Int get() = maxOf(abs(rightSteps), abs(forwardSteps), abs(upSteps))
}
