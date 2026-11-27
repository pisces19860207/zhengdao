// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十一批 · 关系单：一对方向格的唯一装配面。
// 只做表内组合与判据转发；不进运行闸、不落库、不接模型上下文。
package com.example.zhengdao.baopu.state.relation

internal class RelationSheet private constructor(
    val a: DirectedCell,
    val b: DirectedCell,
) {
    /** 永远只返回 Two；Merged 只在负例夹具里手工构造。 */
    fun shape(): PairShape = PairShape.Two(a, b)

    fun findings(text: String = "", before: DirectedCell? = null): List<RelFinding> =
        RelationChecks.all(a, shape(), text, before)

    fun summaryLine(): String = a.kind.label + "｜" +
        a.from + "→" + a.to + " " + a.dims.lines().joinToString(" ") + "｜" +
        b.from + "→" + b.to + " " + b.dims.lines().joinToString(" ")

    companion object {
        fun of(a: DirectedCell, b: DirectedCell): RelationSheet {
            require(a.from == b.to && a.to == b.from) { "必须同一对人的反向两格，不许合成一格" }
            return RelationSheet(a, b)
        }

        fun started(
            from: String,
            to: String,
            anchorAB: RelationAnchor,
            anchorBA: RelationAnchor,
        ): RelationSheet = of(
            DirectedCell.started(from, to, anchorAB),
            DirectedCell.started(to, from, anchorBA),
        )
    }
}
