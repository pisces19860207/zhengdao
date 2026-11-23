// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 测试夹具：一间卧室（区域 → 物件 → 部件 三层各有节点）＋ 两个视点。
//
// 夹具只负责「摆好」，期望值一律写在测试里手算——不从被测实现里取常量自证。
package com.example.zhengdao.baopu.state.view

object ViewFixtures {

    /** 区域节点：卧室本体（视点也允许挂在区域层，只要它在场景图上）。 */
    val room = NodeRef("room", SceneLayer.REGION)

    /** 物件：房门（视点常站这里）。 */
    val door = NodeRef("door", SceneLayer.OBJECT)

    /** 物件：床。 */
    val bed = NodeRef("bed", SceneLayer.OBJECT)

    /** 部件：床沿（比床细一层）。 */
    val bedEdge = NodeRef("bed-edge", SceneLayer.PART)

    /** 部件：灯（左前上）。 */
    val lamp = NodeRef("lamp", SceneLayer.PART)

    /** 物件：窗（右后）。 */
    val window = NodeRef("window", SceneLayer.OBJECT)

    /** 物件：椅子（正右，用来取「边缘」那一档）。 */
    val chair = NodeRef("chair", SceneLayer.OBJECT)

    /**
     * 场景图（区域中心为原点）：
     *
     * ```
     *            前
     *   灯(左前上)   床(前中)   床沿(右前)
     *            椅(右中)
     *            区域中心(room)
     *   窗(右后)              门(后中)
     *            后
     * ```
     */
    fun scene(): SceneNodes = SceneNodes(
        listOf(
            PlacedNode(room, Cell()),
            PlacedNode(door, Cell(depth = Depth.BACK)),
            PlacedNode(bed, Cell(depth = Depth.FRONT)),
            PlacedNode(bedEdge, Cell(across = Across.RIGHT, depth = Depth.FRONT)),
            PlacedNode(lamp, Cell(across = Across.LEFT, depth = Depth.FRONT, height = Height.UP)),
            PlacedNode(window, Cell(across = Across.RIGHT, depth = Depth.BACK)),
            PlacedNode(chair, Cell(across = Across.RIGHT)),
        ),
    )

    /** 站在门口朝前看：厅堂、中焦、平视，取景声明「拍远的」（远）。 */
    fun atDoor(
        facing: Octant = Octant.FRONT,
        pitch: PitchTier = PitchTier.LEVEL,
        fov: FovTier = FovTier.MEDIUM,
        focus: DistanceTier = DistanceTier.FAR,
        region: RegionGrade = RegionGrade.HALL,
    ): Viewpoint = Viewpoint(
        anchor = door,
        region = region,
        at = Cell(depth = Depth.BACK),
        facing = facing,
        pitch = pitch,
        frustum = Frustum(fov),
        focusDistance = focus,
    )

    /** 站在区域正中朝前看（视点挂在区域节点上）。 */
    fun atCenter(
        facing: Octant = Octant.FRONT,
        fov: FovTier = FovTier.MEDIUM,
        focus: DistanceTier = DistanceTier.MID,
    ): Viewpoint = Viewpoint(
        anchor = room,
        region = RegionGrade.HALL,
        at = Cell(),
        facing = facing,
        pitch = PitchTier.LEVEL,
        frustum = Frustum(fov),
        focusDistance = focus,
    )

    /** 一条遮挡结论：谁挡住谁。 */
    fun claim(target: NodeRef, blocker: NodeRef, blocks: Boolean = true): OcclusionClaim =
        OcclusionClaim(target = target, blocker = blocker, blocks = blocks)
}
