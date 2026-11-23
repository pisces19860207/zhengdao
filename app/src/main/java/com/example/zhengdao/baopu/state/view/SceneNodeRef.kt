// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 场景图节点引用（判据② 的落点）。
//
// 为什么要有这个文件：3D→2D 的第一句话是「谁在看、从哪儿看」，而「哪儿」如果可以是裸坐标，
// 就会出现一个漂浮在场景外的相机——它拍得到东西，却没人说得清它在场景的哪一层。
// 所以本包的视点**只能挂在场景图节点上**：节点 id ＋ 四层里的某一层，两个都必填。
//
// 四层与 836 的四层节点一一对应（场景 → 区域 → 物件 → 部件）。836 尚未入仓（本单开工时全仓
// grep 零命中），所以这里自带一份最小词汇表：若 836 落地时命名不同，只改本文件的四层枚举名与
// 一处映射，判据一条不动。
package com.example.zhengdao.baopu.state.view

/**
 * 场景图四层，从粗到细。
 *
 * [depth] 越大越细（0 场景根 … 3 部件）。层级参与两条判定：
 * ① 视点不许挂在 [SCENE] 根上——根没有位置，挂上去等于漂浮坐标；
 * ② 取景对象不许是 [SCENE] 根——整场不是一件可以取景的物。
 */
enum class SceneLayer(val label: String, val depth: Int) {
    SCENE("场景", 0),
    REGION("区域", 1),
    OBJECT("物件", 2),
    PART("部件", 3),
}

/**
 * 一个节点引用：场景图里某个节点 + 它所在的层。
 *
 * 两件都必填、都参与比对：只给 id 不给层，等于允许「同一件东西既当物件又当部件」；
 * 只给层不给 id，等于没有指定任何东西。
 */
data class NodeRef(val nodeId: String, val layer: SceneLayer) {
    init {
        require(nodeId.isNotBlank()) { "节点 id 不许空：视点与目标都必须指向场景图里的具体节点" }
    }

    /** 稳定写法，用于报告与确定性比对。 */
    fun line(): String = "${layer.label}:$nodeId"
}

/**
 * 场景图里一个**有位置**的节点：引用 ＋ 它在本区域占的那一格。
 *
 * 位置只在这里出现，视点与目标都不许自带裸坐标——判定层要坐标只能问场景图（判据②）。
 */
data class PlacedNode(val ref: NodeRef, val cell: Cell)

/**
 * 一张场景图节点表：本场所有节点（四层混在一张表里，靠 [NodeRef.layer] 区分）。
 *
 * 表本身不做推断：查不到就是查不到，返回 null，让调用方去判「漂浮」。重复 id 在装配时当场拒——
 * 同一 id 挂两层，后面所有判定都会「看谁先到」，那是不可复算的。
 */
class SceneNodes(nodes: List<PlacedNode>) {

    /** 本场节点，按 id 排序（确定性：同一批节点无论传入次序，快照逐字一致）。 */
    val all: List<PlacedNode> = nodes.sortedWith(compareBy({ it.ref.nodeId }, { it.ref.layer.depth }))

    private val byId: Map<String, SceneLayer> = all.associate { it.ref.nodeId to it.ref.layer }
    private val cells: Map<String, Cell> = all.associate { it.ref.nodeId to it.cell }

    init {
        require(all.size == byId.size) {
            "同一节点 id 在本场挂了多层：${all.groupBy { it.ref.nodeId }.filterValues { it.size > 1 }.keys}"
        }
    }

    val size: Int get() = all.size

    /** 该 id 在场景图里挂在哪一层；不在表里返回 null。 */
    fun layerOf(nodeId: String): SceneLayer? = byId[nodeId]

    /** 引用是否落地：id 在表里，且层与表里一致（挂错层也算没落地）。 */
    fun holds(ref: NodeRef): Boolean = byId[ref.nodeId] == ref.layer

    /** 该节点的格位；id 不在表里或层对不上时返回 null（＝没有位置，别再往下算）。 */
    fun cellOf(ref: NodeRef): Cell? = if (holds(ref)) cells[ref.nodeId] else null

    /** 该节点是否比场景根细（能承载位置）。 */
    fun canCarryPosition(ref: NodeRef): Boolean = holds(ref) && ref.layer != SceneLayer.SCENE
}
