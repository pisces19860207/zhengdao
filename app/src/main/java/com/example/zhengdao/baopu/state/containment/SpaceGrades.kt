// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：空间档。
// 三轴（可用高度／宽度／深度）各三档 ＋ 开口位置三选一。**只出档、不出量**：
// 本包不出现厘米／毫米、不出现坐标——档与档差是全包唯一的「尺度」语言（判据④）。
package com.example.zhengdao.baopu.state.containment

/**
 * 一维档位：小／中／大。
 *
 * 档位只做比较，不承载任何物理量：`ordinal` 是「第几档」，不是长度。
 * 需要「够不够」的问法时用 [shortageOf]，别在本类里加量纲字段。
 */
enum class Grade(val label: String) {
    SMALL("小"),
    MEDIUM("中"),
    LARGE("大"),
}

/**
 * [need] 相对 [have] 差几档：
 * ≤0＝这一档就够（含更宽裕），1＝差一档（有可能靠调整姿态将就），≥2＝差两档以上（将就不了）。
 */
fun shortageOf(need: Grade, have: Grade): Int = need.ordinal - have.ordinal

/** 开口位置：前（正对面）／侧（侧面）／上（顶面）。 */
enum class Opening(val label: String) {
    FRONT("前"),
    SIDE("侧"),
    TOP("上"),
}

/**
 * 空间档：可用高度／宽度／深度各一档 ＋ 开口位置。
 *
 * 三条轴彼此独立（不互相换算、不折算成体积）：本单判的是「这一档够不够」，
 * 不是「容积够不够」——容积会把「高够但开口窄」抹平成一个数。
 */
data class SpaceProfile(
    val height: Grade,
    val width: Grade,
    val depth: Grade,
    val opening: Opening,
) {
    /** 一句话档位说明（只含档名，不含任何数值）。 */
    val label: String
        get() = "高${height.label}／宽${width.label}／深${depth.label}／${opening.label}开口"
}
