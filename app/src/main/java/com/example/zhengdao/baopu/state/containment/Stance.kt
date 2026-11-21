// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：站位档与朝向。
// 四档站位＝站开口前／侧身／跪／伏。每档自带一张「需求表」：要几档高、几档宽、
// 几档深、认哪些开口、认哪些朝向、能不能靠弯腰将就。需求表是**声明**不是测量，
// 数值（厘米）只可能出现在测量侧（655/660），本文件一个数都没有。
package com.example.zhengdao.baopu.state.containment

/** 站位档：四种进不去就换着试的姿态。 */
enum class Stance(val label: String) {
    STAND_FRONT("站在开口前"),
    SIDLE("侧身"),
    KNEEL("跪"),
    PRONE("伏"),
}

/** 身体相对开口的朝向：正对／侧对／背对。 */
enum class Facing(val label: String) {
    TOWARD("正对"),
    SIDEWAYS("侧对"),
    AWAY("背对"),
}

/** 这一档站位能不能靠弯腰将就高度不足。 */
enum class BendAllowance(val label: String) {
    NONE("弯不下"),
    SLIGHT("可略弯腰"),
    DEEP("可深弯腰"),
}

/**
 * 一档站位的需求声明。
 *
 * [bend] 只影响「高度差一档」时的结论写法：能弯腰 ⇒ 记为「需弯腰」（勉强），
 * 弯不下 ⇒ 记为「高度不够」。它不改档差本身，也不许把差两档说成能弯腰解决。
 */
data class StanceDemand(
    val height: Grade,
    val width: Grade,
    val depth: Grade,
    val openings: Set<Opening>,
    val facings: Set<Facing>,
    val bend: BendAllowance,
)

/**
 * 四档站位的需求表（唯一居所）。
 *
 * 口径（写给将来要改这张表的人）：
 * - **站开口前**：人要站直，高度要中档；肩宽占宽度，宽度要中档；站进去只需要一档深。
 *   开口在前或在上都够得着（在上＝从顶上伸手下去）；只能正对。
 * - **侧身**：肩宽折半 ⇒ 宽度只要小档，这是窄开口的唯一出路；只能侧对，所以只认前／侧开口。
 * - **跪**：高度只要小档（低矮空间的主力）；跪着腿要往后放 ⇒ 深要中档；前／侧／上都够得着；
 *   正对与侧对都成立（偏头看进去）；能深弯腰。
 * - **伏**：高度只要小档，但身体要整个躺进去 ⇒ 深要大档；只有前开口能躺着进；
 *   只能正对；躺姿弯不下腰。
 *
 * 边界：本表只判「这一档能不能将就」，不解「人往哪个方向挪多少」——那属物体摆放求解，不在本单。
 */
object StanceTable {

    private val DEMANDS: Map<Stance, StanceDemand> = linkedMapOf(
        Stance.STAND_FRONT to StanceDemand(
            height = Grade.MEDIUM,
            width = Grade.MEDIUM,
            depth = Grade.SMALL,
            openings = setOf(Opening.FRONT, Opening.TOP),
            facings = setOf(Facing.TOWARD),
            bend = BendAllowance.SLIGHT,
        ),
        Stance.SIDLE to StanceDemand(
            height = Grade.MEDIUM,
            width = Grade.SMALL,
            depth = Grade.SMALL,
            openings = setOf(Opening.FRONT, Opening.SIDE),
            facings = setOf(Facing.SIDEWAYS),
            bend = BendAllowance.SLIGHT,
        ),
        Stance.KNEEL to StanceDemand(
            height = Grade.SMALL,
            width = Grade.MEDIUM,
            depth = Grade.MEDIUM,
            openings = setOf(Opening.FRONT, Opening.SIDE, Opening.TOP),
            facings = setOf(Facing.TOWARD, Facing.SIDEWAYS),
            bend = BendAllowance.DEEP,
        ),
        Stance.PRONE to StanceDemand(
            height = Grade.SMALL,
            width = Grade.MEDIUM,
            depth = Grade.LARGE,
            openings = setOf(Opening.FRONT),
            facings = setOf(Facing.TOWARD),
            bend = BendAllowance.NONE,
        ),
    )

    init {
        require(DEMANDS.keys == Stance.entries.toSet()) { "四档站位必须各有需求表，缺：${Stance.entries - DEMANDS.keys}" }
    }

    /** 取某一档的需求。四档齐备由 [DEMANDS] 的初始化断言保证。 */
    fun of(stance: Stance): StanceDemand = DEMANDS.getValue(stance)

    /** 按枚举次序给出四档（确定性：不依赖 Map 迭代顺序）。 */
    fun stances(): List<Stance> = Stance.entries.toList()
}
