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
        "近退化（共点/共线/共面）子集按相对奇异阈值剔除，单纯形恒不超四点（812 加固）"
private const val MAX_ITER = 64
private const val REL_EPS = 1e-12
private const val ZERO_NORM_SQ = 1e-16
private const val DUP_NORM_SQ = 1e-20
private const val WEIGHT_EPS = 1e-9

/** 相对奇异阈值（812 尺度归一）：|det| < 此值×maxEntry^m 判奇异；det 量纲随 m 随 maxEntry^m 走。 */
private const val REL_SINGULAR = 1e-12

/** GJK 距离求解（纯函数，无状态）。 */
object Gjk {

    fun distance(a: ConvexShape, b: ConvexShape): GjkResult {
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
            val stuck = simplex.any { (it.w - m.w).normSq() < DUP_NORM_SQ }
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
    val weights = listOf(1.0 - t.sum()) + t.toList()
    if (weights.any { it < -WEIGHT_EPS }) return null
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