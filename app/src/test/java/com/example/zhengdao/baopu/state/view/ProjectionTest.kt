// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 判据③⑤：投影可复算、屏幕格由投影算出、屏幕格不可反推位置。
//
// 期望值全部手算（写在注释里），不从 Projection 里取常量——否则「算错了也能过」。
// 手算式（与 Projection 文件头的三式一一对应）：
//     方位差 = 八方位之差（负左正右，4＝正后）   纵向差 = 目标上格−视点上格＋俯仰抬升
//     距离档 = f(三轴最大格差 × 区域倍数)：≤1 近、≤3 中、更大 远
package com.example.zhengdao.baopu.state.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    // ── 方位合成：三轴格差 → 八方位（九例，含同位） ─────────────────────

    @Test
    fun `方位合成九例_八方位加同位`() {
        val cases = listOf(
            Triple(0, 1, Octant.FRONT),
            Triple(1, 1, Octant.FRONT_RIGHT),
            Triple(1, 0, Octant.RIGHT),
            Triple(1, -1, Octant.BACK_RIGHT),
            Triple(0, -1, Octant.BACK),
            Triple(-1, -1, Octant.BACK_LEFT),
            Triple(-1, 0, Octant.LEFT),
            Triple(-1, 1, Octant.FRONT_LEFT),
            Triple(0, 0, null),
        )
        assertEquals("八个方位＋同位共九例", 9, cases.size)
        cases.forEach { (right, forward, expect) ->
            val got = Projection.bearingOf(CellOffset(right, forward, 0))
            assertEquals("格差($right,$forward) 的方位", expect?.label ?: "同位", got?.label ?: "同位")
        }
    }

    @Test
    fun `方位差不分走几格_只看方向`() {
        val near = Projection.bearingOf(CellOffset(1, 1, 0))
        val far = Projection.bearingOf(CellOffset(2, 2, 0))
        assertEquals("走一格与走两格是同一个方位（方位只到八档）", near, far)
    }

    @Test
    fun `有符号方位差_左负右正正后为四`() {
        assertEquals("朝前看左：−2", -2, Octant.stepsBetween(Octant.FRONT, Octant.LEFT))
        assertEquals("朝左看前：+2（同一对，反过来）", 2, Octant.stepsBetween(Octant.LEFT, Octant.FRONT))
        assertEquals("朝前看右前：+1", 1, Octant.stepsBetween(Octant.FRONT, Octant.FRONT_RIGHT))
        assertEquals("朝前看正后：4（半圈，无左右）", 4, Octant.stepsBetween(Octant.FRONT, Octant.BACK))
        assertEquals("朝后看正前也是 4", 4, Octant.stepsBetween(Octant.BACK, Octant.FRONT))
        assertEquals("原地：0", 0, Octant.stepsBetween(Octant.RIGHT, Octant.RIGHT))
    }

    // ── 距离档：区域倍数 × 格距 ─────────────────────────────────────────

    @Test
    fun `距离档九例_区域倍数乘格距`() {
        // 小室×1：0→近(0) 1→近(1) 2→中(2)
        // 厅堂×2：0→近(0) 1→中(2) 2→远(4)
        // 旷野×3：0→近(0) 1→中(3) 2→远(6)
        data class Row(val region: RegionGrade, val expects: List<DistanceTier>)
        val rows = listOf(
            Row(RegionGrade.SMALL_ROOM, listOf(DistanceTier.NEAR, DistanceTier.NEAR, DistanceTier.MID)),
            Row(RegionGrade.HALL, listOf(DistanceTier.NEAR, DistanceTier.MID, DistanceTier.FAR)),
            Row(RegionGrade.OPEN_AIR, listOf(DistanceTier.NEAR, DistanceTier.MID, DistanceTier.FAR)),
        )
        rows.forEach { row ->
            (0..2).forEach { span ->
                assertEquals(
                    "${row.region.label}：格距 $span 档",
                    row.expects[span], Projection.distanceTier(row.region, CellOffset(span, 0, 0)),
                )
            }
        }
    }

    @Test
    fun `小室里永远没有远档_两格乘一只有中`() {
        val farthest = Projection.distanceTier(RegionGrade.SMALL_ROOM, CellOffset(2, 2, 2))
        assertEquals("小室最远两格 ⇒ 中（不是漏算，小屋里本来没有远景）", DistanceTier.MID, farthest)
        assertTrue("小室任何格距都到不了远", (0..2).none {
            Projection.distanceTier(RegionGrade.SMALL_ROOM, CellOffset(it, 0, 0)) == DistanceTier.FAR
        })
    }

    @Test
    fun `三轴取最大格差_不用勾股`() {
        assertEquals("斜着走两格也只是两格远",
            Projection.distanceTier(RegionGrade.HALL, CellOffset(2, 2, 2)),
            Projection.distanceTier(RegionGrade.HALL, CellOffset(2, 0, 0)))
    }

    // ── 屏幕九宫格 ────────────────────────────────────────────────────

    @Test
    fun `屏幕横列逐格表`() {
        val expects = mapOf(
            -3 to ScreenColumn.LEFT, -2 to ScreenColumn.LEFT, -1 to ScreenColumn.CENTER,
            0 to ScreenColumn.CENTER, 1 to ScreenColumn.CENTER,
            2 to ScreenColumn.RIGHT, 3 to ScreenColumn.RIGHT,
            4 to ScreenColumn.CENTER, // 正后方没有左右之分，列取中，但不是「在中间」
        )
        expects.forEach { (steps, expect) ->
            assertEquals("方位差 $steps 的横列", expect, Projection.columnOf(steps))
        }
    }

    @Test
    fun `屏幕纵列逐格表`() {
        val expects = mapOf(
            3 to ScreenRow.TOP, 2 to ScreenRow.TOP, 1 to ScreenRow.MIDDLE, 0 to ScreenRow.MIDDLE,
            -1 to ScreenRow.MIDDLE, -2 to ScreenRow.BOTTOM, -3 to ScreenRow.BOTTOM,
        )
        expects.forEach { (steps, expect) ->
            assertEquals("纵向差 $steps 的纵列", expect, Projection.rowOf(steps))
        }
    }

    // ── 真跑：整条投影（门口一镜） ──────────────────────────────────────

    @Test
    fun `门口朝前_床正前方两格_画面正中外远处`() {
        val p = Projection.projected(ViewFixtures.atDoor(), Cell(depth = Depth.BACK), Cell(depth = Depth.FRONT))
        assertEquals("床在正前方", Octant.FRONT, p.bearing)
        assertEquals("方位差 0", 0, p.bearingSteps)
        assertEquals("纵向差 0（平视、同高）", 0, p.verticalSteps)
        assertEquals("中焦：0 格 ⇒ 视野内", Visibility.INSIDE, p.visibility)
        assertEquals("厅堂两格：2×2=4 ⇒ 远", DistanceTier.FAR, p.distance)
        assertEquals("屏幕正中", ScreenCell(ScreenColumn.CENTER, ScreenRow.MIDDLE), p.cell)
    }

    @Test
    fun `门口朝前_床沿右前一格_横差一格仍在中列`() {
        val p = Projection.projected(
            ViewFixtures.atDoor(),
            Cell(depth = Depth.BACK),
            Cell(across = Across.RIGHT, depth = Depth.FRONT),
        )
        assertEquals("右前方", Octant.FRONT_RIGHT, p.bearing)
        assertEquals("横差 +1", 1, p.bearingSteps)
        assertEquals("中焦一格仍在画面内", Visibility.INSIDE, p.visibility)
        assertEquals("屏幕仍在中列（横差一格未过中带）", ScreenColumn.CENTER, p.cell.column)
    }

    @Test
    fun `区域中心_窗在右后_出画但屏幕格照给`() {
        // 站在区域正中朝前看：窗在右后 ⇒ 右后方位、横差 3 格（中焦边缘带只到 2 格）⇒ 出画。
        val p = Projection.projected(
            ViewFixtures.atCenter(),
            Cell(),
            Cell(across = Across.RIGHT, depth = Depth.BACK),
        )
        assertEquals("右后方", Octant.BACK_RIGHT, p.bearing)
        assertEquals("横差 +3", 3, p.bearingSteps)
        assertEquals("中焦三格 ⇒ 出画", Visibility.OUTSIDE, p.visibility)
        assertEquals("出画也照样给格：右中", ScreenColumn.RIGHT, p.cell.column)
    }

    @Test
    fun `门口朝前_窗在正右贴着边`() {
        // 门在後中、窗在右後 ⇒ 对站在门口的视点而言，窗就在正右一格半（横差 2 格）⇒ 边缘。
        val p = Projection.projected(
            ViewFixtures.atDoor(),
            Cell(depth = Depth.BACK),
            Cell(across = Across.RIGHT, depth = Depth.BACK),
        )
        assertEquals("正右", Octant.RIGHT, p.bearing)
        assertEquals("横差 +2", 2, p.bearingSteps)
        assertEquals("中焦两格 ⇒ 边缘（还在画面里）", Visibility.EDGE, p.visibility)
        assertEquals("厅堂一格：1×2=2 ⇒ 中", DistanceTier.MID, p.distance)
    }

    @Test
    fun `区域中心_椅子正右一格_中焦贴边`() {
        val p = Projection.projected(
            ViewFixtures.atCenter(),
            Cell(),
            Cell(across = Across.RIGHT),
        )
        assertEquals("正右", Octant.RIGHT, p.bearing)
        assertEquals("横差 +2", 2, p.bearingSteps)
        assertEquals("中焦两格 ⇒ 边缘（不是出画）", Visibility.EDGE, p.visibility)
        assertEquals("厅堂一格：1×2=2 ⇒ 中", DistanceTier.MID, p.distance)
        assertEquals("屏幕右列", ScreenColumn.RIGHT, p.cell.column)
    }

    @Test
    fun `俯仰只动纵向_俯拍把东西抬高一行`() {
        val from = Cell(depth = Depth.BACK)
        val lamp = Cell(across = Across.LEFT, depth = Depth.FRONT, height = Height.UP)
        val level = Projection.projected(ViewFixtures.atDoor(pitch = PitchTier.LEVEL), from, lamp)
        val down = Projection.projected(ViewFixtures.atDoor(pitch = PitchTier.DOWN), from, lamp)
        val up = Projection.projected(ViewFixtures.atDoor(pitch = PitchTier.UP), from, lamp)
        assertEquals("平视：纵向差 1", 1, level.verticalSteps)
        assertEquals("俯拍：抬高一行 ⇒ 2", 2, down.verticalSteps)
        assertEquals("仰拍：压低一行 ⇒ 0", 0, up.verticalSteps)
        assertEquals("俯拍后到上排", ScreenRow.TOP, down.cell.row)
        assertEquals("平视在中排", ScreenRow.MIDDLE, level.cell.row)
        assertEquals("俯仰不动横列", level.cell.column, down.cell.column)
    }

    @Test
    fun `同位_没有方位且距离最近`() {
        val p = Projection.projected(ViewFixtures.atCenter(), Cell(), Cell())
        assertNull("同位没有方位", p.bearing)
        assertEquals(0, p.bearingSteps)
        assertEquals("同位算视野内", Visibility.INSIDE, p.visibility)
        assertEquals("同位算近", DistanceTier.NEAR, p.distance)
    }

    // ── 判据⑤：屏幕格不可反推位置 ──────────────────────────────────────

    @Test
    fun `反推不成立_同一屏幕格对应两个不同位置`() {
        val scene = ViewFixtures.scene()
        val center = ViewFixtures.atCenter()
        val lampAt = Projection.projected(center, Cell(), scene.cellOf(ViewFixtures.lamp)!!)
        val bedAt = Projection.projected(
            ViewFixtures.atCenter(facing = Octant.FRONT_LEFT), Cell(),
            scene.cellOf(ViewFixtures.bed)!!,
        )
        assertEquals("两个镜头都落在「中中」这一格", lampAt.cell, bedAt.cell)
        assertEquals("但方位差相反（−1 与 +1）", -1, lampAt.bearingSteps)
        assertEquals(1, bedAt.bearingSteps)
        assertTrue(
            "位置也不同（灯在左前，床在正前）——所以「看起来在中间」推不出它在哪儿",
            scene.cellOf(ViewFixtures.lamp) != scene.cellOf(ViewFixtures.bed),
        )
    }

    @Test
    fun `可复算_同输入两次投影逐字一致`() {
        val first = Projection.projected(ViewFixtures.atDoor(), Cell(depth = Depth.BACK), Cell(depth = Depth.FRONT))
        val second = Projection.projected(ViewFixtures.atDoor(), Cell(depth = Depth.BACK), Cell(depth = Depth.FRONT))
        assertEquals("同输入同输出", first.line(), second.line())
    }
}
