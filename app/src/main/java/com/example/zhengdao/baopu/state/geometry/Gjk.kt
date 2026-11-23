// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：GJK 最近距离。
// 算法出处（公开文献的标准算法，实现为自研）：Gilbert, Johnson, Keerthi (1988)
// "A fast procedure for computing the distance between complex objects in
// three-dimensional space"。距离子算法用单纯形子集枚举（≤4 点 ⇒ ≤15 个子集，
// 每个子集解一个小 Gram 线性组），不依赖第三方物理库。
// 第八百一十二批加固（790 实跑咬出）：单纯形恒不超四点（全四点特征＝浮点近并列窗口，
// 按收敛处理）；Gram 求解按相对奇异阈值判退化（尺度归一，不随形状大小漂）；
// solve 分派对 >3 元系统显式拒绝（防御纵深）。未改公开语义：距离/相交判定同口径。
// 第八百二十二批（epsilon 量纲与尺度归一）：DUP_NORM_SQ/WEIGHT_EPS 的绝对量纲退役——
// 重复点阈值与负权重窗口改由形体标尺 L（两形体包围盒对角线之和，1 cm 兜底）推出；
// 权重窗口另随 Gram 各向异性比 κ̂ 抬升噪声地板（混合尺度对不再把同一特征判成两个）。
// 边界：只求静态最近距离/是否相交；不做时间积分、不做接触流形。
package com.example.zhengdao.baopu.state.geometry

/** Minkowski 差 A−B 上的一个支撑点，保留两形状上的原像（见证点用）。 */
data class MinkPoint(val a: Vec3, val b: Vec3) {
    val w: Vec3 = a - b
}

/** 单纯形上距原点最近的特征：最近点＋重心权重＋所用顶点下标。 */
internal data class Feature(val point: Vec3, val weights: List<Double>, val indices: List<Int>)

private data class Projection(val point: Vec3, val weights: List<Double>)

/** GJK 读数。distanceCm=0 且 intersecting=true 表示相交或接触。 */
data class GjkResult(
    val distanceCm: Double,
    val witnessA: Vec3,
    val witnessB: Vec3,
    val iterations: Int,
    val intersecting: Boolean,
    val assumption: String = GJK_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
    internal val simplex: List<MinkPoint> = emptyList(),
)

private const val GJK_ASSUMPTION =
    "GJK 最近距离：凸体支撑函数＋单纯形迭代，相对收敛容差 1e-12，上限 64 轮；" +
        "近退化（共点/共线/共面）子集按相对奇异阈值剔除，单纯形恒不超四点（812 加固）；" +
        "重复点与权重阈值随形体标尺 L 归一（822 标定）；" +
        "相交判定为物理口径：绝对间隙 ≤ 1e-8 cm（1 埃）一律报接触（655 确立，838 定性）"

// ── 容差常量表（822 集中登记，source=engineering；标定方法与三档读数见交付报告）──────
// MAX_ITER：性能参数（迭代轮数上限，无量纲），不随形体尺度变。
// source=engineering——非物理口径、非数值容差，调整只需性能证据，不动语义（839 定性）。
private const val MAX_ITER = 64         // 迭代上限（轮数，无量纲）
private const val REL_EPS = 1e-12       // 进度判据：progress ≤ REL_EPS × ‖p‖²（相对，655）
// ZERO_NORM_SQ：相交判定阈（平方口径，cm²）。线性 = 1e-8 cm = 1 埃。
// 性质＝物理口径（838 定性）：公开承诺「两形体表面绝对间隙 ≤ 1 埃一律报
// intersecting=true 且 distanceCm=0」。判定只依赖绝对间隙，不随形体标尺 L、
// 坐标量级、构型大小变——这是它与 REL_EPS/DUP_REL/WEIGHT_REL/REL_SINGULAR
// （四个数值容差，相对量，812/822）的本质分界：两类常量各司其职，禁止合并、
// 禁止互算、禁止把本常量相对化。数值噪声吸收不由本常量承担（域内阈高出
// 噪声地板 ≥3 个数量级，7.5 km 尺度才追平）。改值＝破坏公开语义（声明见
// docs/geometry-semantics.md 与几何包 README），必须另立单＋版本标记。
private const val ZERO_NORM_SQ = 1e-16  // 相交判定：原点命中阈（绝对，物理接触口径，655 原样）
private const val REL_SINGULAR = 1e-12  // 奇异判据：|det| < REL_SINGULAR × maxEntry^m（相对，812 尺度归一）
// 822 相对量纲：标尺 L = 形体A包围盒对角线＋形体B包围盒对角线（支撑函数 ±三轴探针＝凸体精确
// AABB），下限 SCALE_FLOOR_CM——L<1 cm 按 1 cm 兜底，相对阈值不随退化形体放大到无意义。
// SCALE_FLOOR_CM：相对阈的绝对地板（工程口径，822 双阈机制件，839 定性）。
// L<1 cm 按 1 cm 计——防退化形体的相对阈消失；是地板不是对外承诺，
// 与 CONTACT_DIST/ZERO_NORM_SQ 两类公开口径禁止混用。
private const val SCALE_FLOOR_CM = 1.0
// DUP_REL：重复支撑点线性阈值 = DUP_REL × L（平方后比较；GJK stuck 与 EPA 扩张同源同标定）。
// 标定：支撑点坐标噪声 ≲ 60 次舍入 × eps × L ≈ 1.5e-14·L，两点差 ≤ 2 倍 ⇒ ~3e-14·L，
// 取 ~33× 余量 ⇒ 1e-12。连续性：旧绝对 1e-10 cm 在 cm 工作尺度（L≈30）等效 3.3e-12，
// 取紧的一侧防误合（stuck 晚触发由进度判据兜底，至多 +1 轮）；米级档（L=100 cm）线性阈
// 1e-10 cm 与旧绝对值恰好等值。
private const val DUP_REL = 1e-12
// WEIGHT_REL：负权重窗口 = max(WEIGHT_REL, DBL_EPS × κ̂)，κ̂ = Gram 对角比（affineClosest 内算）。
// 标定：重心权重无量纲（Σw=1），其舍入噪声 ≤ eps × 条件数；良态子集取基础窗 1e-9
// （连续性＝旧绝对窗在权重 O(1) 时的等效值；空间语义＝允许 witness 出包 ≤ WEIGHT_REL × L）；
// κ̂ ≳ 4.5e6（长细比 ≳2000 的混合尺度对）时窗口随噪声地板抬升，数值零权不再被误判成出包。
private const val WEIGHT_REL = 1e-9
private val DBL_EPS = Math.ulp(1.0)     // IEEE754 双精度机器舍入 2^-52（κ̂ 噪声地板系数）

/** GJK 距离求解（纯函数，无状态）。 */
object Gjk {

    fun distance(a: ConvexShape, b: ConvexShape): GjkResult {
        val dupTol = dupTolSq(a, b) // 822：重复点阈值由形体标尺 L 推出，不再用绝对常数
        var simplex = listOf(minkSupport(a, b, Vec3.UNIT_X))
        var last = bestFeature(simplex)
        var iter = 0
        while (iter < MAX_ITER) {
            iter++
            val f = bestFeature(simplex)
            last = f
            if (f.point.normSq() <= ZERO_NORM_SQ) return finish(f, simplex, iter, true)
            // 812 加固：全四点特征在精确算术下与边界子集并列（凸包最近点必在边界），
            // 并列时 bestFeature 的严格小于让更早枚举的边界子集胜出 ⇒ 全四点胜出只可能是
            // 近退化四面体的浮点近并列窗口——此时距离已收敛到噪声级，按收敛收口，
            // 不再喂新支撑点（保证单纯形恒 ≤4 点，>4 点正是越界崩溃的路径）。
            if (simplex.size == 4 && f.indices.size == 4) return finish(f, simplex, iter, false)
            val m = minkSupport(a, b, -f.point)
            val progress = f.point.normSq() - f.point.dot(m.w)
            val stuck = simplex.any { (it.w - m.w).normSq() < dupTol }
            if (progress <= REL_EPS * f.point.normSq() || stuck) return finish(f, simplex, iter, false)
            simplex = f.indices.map { simplex[it] } + m
        }
        return finish(last, simplex, iter, false)
    }

    private fun finish(f: Feature, simplex: List<MinkPoint>, iter: Int, hit: Boolean): GjkResult {
        var wa = Vec3.ZERO
        var wb = Vec3.ZERO
        for (k in f.indices.indices) {
            val p = simplex[f.indices[k]]
            wa += p.a * f.weights[k]
            wb += p.b * f.weights[k]
        }
        val d = if (hit) 0.0 else f.point.norm()
        return GjkResult(d, wa, wb, iter, hit, simplex = simplex)
    }
}

/** Minkowski 差支撑：A 沿 dir 最远点 − B 沿 −dir 最远点。 */
internal fun minkSupport(a: ConvexShape, b: ConvexShape, dir: Vec3) =
    MinkPoint(a.support(dir), b.support(-dir))

/** 包围盒对角线：±三轴支撑探针给出凸体的精确 AABB，返回其对角线长。 */
private fun bboxDiag(s: ConvexShape): Double {
    val ex = s.support(Vec3.UNIT_X).x - s.support(-Vec3.UNIT_X).x
    val ey = s.support(Vec3.UNIT_Y).y - s.support(-Vec3.UNIT_Y).y
    val ez = s.support(Vec3.UNIT_Z).z - s.support(-Vec3.UNIT_Z).z
    return Math.sqrt(ex * ex + ey * ey + ez * ez)
}

/** 822 形体标尺 L：两形体包围盒对角线之和，下限 SCALE_FLOOR_CM 兜底（判据③退化保护）。 */
internal fun pairScaleLen(a: ConvexShape, b: ConvexShape): Double {
    val l = bboxDiag(a) + bboxDiag(b)
    return if (l < SCALE_FLOOR_CM) SCALE_FLOOR_CM else l
}

/** 822 重复点判据（平方阈值）＝DUP_REL² × L²：GJK stuck 与 EPA 扩张同源同标定。 */
internal fun dupTolSq(a: ConvexShape, b: ConvexShape): Double {
    val l = pairScaleLen(a, b)
    return DUP_REL * DUP_REL * l * l
}

/** 枚举单纯形的全部非空子集，取「仿射包上距原点最近且重心权重非负」者。 */
private fun bestFeature(simplex: List<MinkPoint>): Feature {
    var best: Feature? = null
    for (mask in 1 until (1 shl simplex.size)) {
        val idx = simplex.indices.filter { mask and (1 shl it) != 0 }
        val proj = affineClosest(idx.map { simplex[it].w }) ?: continue
        if (best == null || proj.point.normSq() < best.point.normSq()) {
            best = Feature(proj.point, proj.weights, idx)
        }
    }
    return best ?: Feature(simplex[0].w, listOf(1.0), listOf(0))
}

/** 点集仿射包上距原点最近的点（重心权重全非负才算有效特征）。 */
private fun affineClosest(pts: List<Vec3>): Projection? {
    if (pts.size == 1) return Projection(pts[0], listOf(1.0))
    val p0 = pts[0]
    val edges = pts.drop(1).map { it - p0 }
    val m = edges.size
    val gram = Array(m) { i -> DoubleArray(m) { j -> edges[i].dot(edges[j]) } }
    // 812 尺度归一：奇异阈值随 Gram 量纲走（det ~ maxEntry^m），不随形状大小漂移；
    // 全同支撑点（maxEntry=0）＝点退化子集，直接剔除。
    val maxEntry = gram.maxOf { row -> row.maxOf { kotlin.math.abs(it) } }
    if (maxEntry <= 0.0) return null
    val singular = REL_SINGULAR * Math.pow(maxEntry, m.toDouble())
    val rhs = DoubleArray(m) { i -> -p0.dot(edges[i]) }
    val t = solve(gram, rhs, singular) ?: return null
    // 822 尺度归一：负权重窗口随解的噪声地板走。κ̂ = Gram 对角比（走到此处必满秩 ⇒ 对角全 >0）：
    // 良态子集取基础窗 WEIGHT_REL；各向异性大（混合尺度对）按 eps×κ̂ 抬升——
    // 数值零权不再被误判成「重心出包」，同一特征不再被拆成两个。
    var maxDiag = gram[0][0]
    var minDiag = gram[0][0]
    for (i in 1 until m) {
        val d = gram[i][i]
        if (d > maxDiag) maxDiag = d
        if (d < minDiag) minDiag = d
    }
    val weightTol = maxOf(WEIGHT_REL, DBL_EPS * (maxDiag / minDiag))
    val weights = listOf(1.0 - t.sum()) + t.toList()
    if (weights.any { it < -weightTol }) return null
    var q = p0
    for (i in 0 until m) q += edges[i] * t[i]
    return Projection(q, weights)
}

/** 812 加固：>3 元系统显式拒绝（单纯形恒 ≤4 点后此支不可达，防御纵深，绝不错解）。 */
private fun solve(g: Array<DoubleArray>, b: DoubleArray, singular: Double): DoubleArray? = when (b.size) {
    1 -> solve1(g, b, singular)
    2 -> solve2(g, b, singular)
    3 -> solve3(g, b, singular)
    else -> null
}

private fun solve1(g: Array<DoubleArray>, b: DoubleArray, singular: Double): DoubleArray? {
    if (kotlin.math.abs(g[0][0]) < singular) return null
    return doubleArrayOf(b[0] / g[0][0])
}

private fun solve2(g: Array<DoubleArray>, b: DoubleArray, singular: Double): DoubleArray? {
    val det = g[0][0] * g[1][1] - g[0][1] * g[1][0]
    if (kotlin.math.abs(det) < singular) return null
    return doubleArrayOf(
        (b[0] * g[1][1] - g[0][1] * b[1]) / det,
        (g[0][0] * b[1] - b[0] * g[1][0]) / det,
    )
}

private fun solve3(g: Array<DoubleArray>, b: DoubleArray, singular: Double): DoubleArray? {
    val det = det3(g)
    if (kotlin.math.abs(det) < singular) return null
    return DoubleArray(3) { i -> det3(withColumn(g, i, b)) / det }
}

private fun det3(g: Array<DoubleArray>): Double =
    g[0][0] * (g[1][1] * g[2][2] - g[1][2] * g[2][1]) -
        g[0][1] * (g[1][0] * g[2][2] - g[1][2] * g[2][0]) +
        g[0][2] * (g[1][0] * g[2][1] - g[1][1] * g[2][0])

private fun withColumn(g: Array<DoubleArray>, col: Int, b: DoubleArray): Array<DoubleArray> =
    Array(3) { r -> DoubleArray(3) { c -> if (c == col) b[r] else g[r][c] } }