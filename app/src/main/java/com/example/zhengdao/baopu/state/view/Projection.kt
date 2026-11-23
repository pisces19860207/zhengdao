// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十批 · 投影：把「视点 + 目标格位」算成四件输出（判据③）。
//
// 本文件是**唯一**算得出屏幕位置的地方。屏幕九宫格不是谁告诉我们的，是从方位差与纵向偏移
// 推出来的；推法只有一套整数加减（见下三式），所以任何人拿同一对视点／目标都能复算出同一格。
//
//     方位差   bearingSteps = Octant.stepsBetween(朝向, 目标方位)      // −3..4
//     纵向差   verticalSteps = (目标上格 − 视点上格) + 俯仰抬升格        // −3..3
//     距离档   distanceTier  = f(切比雪夫格距 × 区域档倍数)             // 近／中／远
//
// ── 判据⑤ 为什么禁反推（写在这里，免得后人手痒加一个 inverse）──
// 上面三式都是**多对一**：同一个屏幕格（比如「左中」）可以由朝左看一件正前方的物体得到，
// 也可以由朝前看一件左前方的物体得到。位置→屏幕是投影，屏幕→位置不是——从「看起来在左边」
// 反推「它一定在左边」会得到无穷多个解，任选一个就是编。所以本包**没有任何**以 ScreenCell
// 为参数的函数；反推测试（ProjectionTest）用两对不同的输入命中同一格来钉死这一点。
package com.example.zhengdao.baopu.state.view

/** 一次投影的全部中间量：方位、两轴偏差、可见三态、距离档、屏幕格。 */
data class Projected(
    /** 目标相对视点的八方位；横向同格时为 null（同位无方位，不是「正前方」）。 */
    val bearing: Octant?,
    /** 有符号方位差（负左正右，4＝正后方）。 */
    val bearingSteps: Int,
    /** 纵向差（已含俯仰抬升，正＝画面偏上）。 */
    val verticalSteps: Int,
    val visibility: Visibility,
    val distance: DistanceTier,
    val cell: ScreenCell,
) {
    fun line(): String = buildString {
        append("方位=").append(bearing?.label ?: "同位")
        append("|横差=").append(bearingSteps)
        append("|纵差=").append(verticalSteps)
        append("|").append(visibility.label)
        append("|").append(distance.label)
        append("|屏幕=").append(cell.label)
    }
}

/** 纯函数集合：无状态、无 IO、无浮点。同样的输入永远同样的输出。 */
object Projection {

    /**
     * 三轴格差合成一个方位：横向与纵深都同格时返回 null（同位，没有方位可言）。
     *
     * 查表而不是写九条分支：三轴各先压成 −1／0／+1（`coerceIn` 就是取符号），
     * 再按「横向三值 × 纵深三值」的九宫格取方位。表本身写成九宫格，跟图一样能对着读：
     *
     * ```
     *            后        中        前
     *   左    左后      左       左前
     *   中     后      （同位）    前
     *   右    右后      右       右前
     * ```
     */
    fun bearingOf(offset: CellOffset): Octant? =
        BY_SIGN[(offset.rightSteps.coerceIn(-1, 1) + 1) * 3 + offset.forwardSteps.coerceIn(-1, 1) + 1]

    /** 九宫格顺序：横向（左中右）× 纵深（后中前）。 */
    private val BY_SIGN: List<Octant?> = listOf(
        Octant.BACK_LEFT, Octant.LEFT, Octant.FRONT_LEFT,
        Octant.BACK, null, Octant.FRONT,
        Octant.BACK_RIGHT, Octant.RIGHT, Octant.FRONT_RIGHT,
    )

    /**
     * 距离档：格距先乘区域档倍数（小室 1／厅堂 2／旷野 3），再分档——
     * 乘出来的数 ≤1 为近、≤3 为中、更大为远。
     *
     * 这条乘法的后果要认下来：**小室里永远没有「远」**（最远两格 ×1＝2，只到中）。
     * 这不是缺陷——小屋里两步开外就是「中」，本来不存在远景；要让远景出现，换区域档。
     */
    fun distanceTier(region: RegionGrade, offset: CellOffset): DistanceTier {
        val span = offset.span * region.scale
        return when {
            span <= 1 -> DistanceTier.NEAR
            span <= 3 -> DistanceTier.MID
            else -> DistanceTier.FAR
        }
    }

    /**
     * 屏幕九宫格：横看方位差、纵看纵向差。
     *
     * 横：−3／−2 在左、−1..1 在中、2／3 在右；**正后方（4）取中**——正后方没有左右之分，
     * 硬分一边就是编；它必然在画面外，列只占位不撒谎。
     * 纵：≥2 在上、≤−2 在下、其余在中。
     *
     * 视野外也照样给格：先算出格、再标「在不在画面里」，两件事分开，才不会把画面外的点
     * 谎报成画面内（那正是「看起来在左边」式反推的起点）。
     */
    fun screenCell(bearingSteps: Int, verticalSteps: Int): ScreenCell =
        ScreenCell(columnOf(bearingSteps), rowOf(verticalSteps))

    /** 横向三列。 */
    fun columnOf(bearingSteps: Int): ScreenColumn = when {
        bearingSteps <= -2 -> ScreenColumn.LEFT
        bearingSteps >= 2 && bearingSteps != Octant.BEHIND_STEPS -> ScreenColumn.RIGHT
        else -> ScreenColumn.CENTER
    }

    /** 纵向三行。 */
    fun rowOf(verticalSteps: Int): ScreenRow = when {
        verticalSteps >= 2 -> ScreenRow.TOP
        verticalSteps <= -2 -> ScreenRow.BOTTOM
        else -> ScreenRow.MIDDLE
    }

    /**
     * 一次投影：从视点在 [from] 这一格看 [target] 那一格，四件输出里的三件
     * （可见三态、距离档、屏幕格）在这里算齐，
     * 第四件（遮挡者）要场景里别节点的结论，归 [ViewChecks] 装配（判据③）。
     */
    fun projected(viewpoint: Viewpoint, from: Cell, target: Cell): Projected {
        val offset = from.offsetTo(target)
        val bearing = bearingOf(offset)
        val steps = if (bearing == null) 0 else Octant.stepsBetween(viewpoint.facing, bearing)
        val vertical = offset.upSteps + viewpoint.pitch.liftSteps
        return Projected(
            bearing = bearing,
            bearingSteps = steps,
            verticalSteps = vertical,
            visibility = viewpoint.frustum.visibility(steps, vertical),
            distance = distanceTier(viewpoint.region, offset),
            cell = screenCell(steps, vertical),
        )
    }
}
