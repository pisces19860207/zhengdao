// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：EPA 穿透深度。
// 算法出处（公开文献的标准算法，实现为自研）：van den Bergen (1999)
// "A fast and robust GJK implementation for collision detection of convex
// objects" 的 expanding polytope 思路。从 GJK 终端单纯形出发，沿最近面法向
// 取支撑点扩张多面体，直到改进量小于容差；最近面到原点的距离即穿透深度。
// 边界：只求静态穿透深度与分离方向；不做接触流形、不做时间积分。
package com.example.zhengdao.baopu.state.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/** EPA 读数。normal＝A 穿入 B 的方向；把 A 沿 −normal 平移 depthCm 即恰好分离。 */
data class EpaResult(
    val depthCm: Double,
    val normal: Vec3,
    val iterations: Int,
    val assumption: String = EPA_ASSUMPTION,
    val note: String = GeometryNotes.NOT_MEASUREMENT,
)

private const val EPA_ASSUMPTION = "EPA 穿透深度：支撑点扩张多面体，改进容差 1e-9（绝对＋相对），上限 96 轮"
private const val EPA_MAX_ITER = 96
private const val EPA_EPS_ABS = 1e-9
private const val EPA_EPS_REL = 1e-9
private const val VIS_EPS = 1e-12
private const val DUP_NORM_SQ = 1e-18
private const val CONTACT_DIST = 1e-7

private data class Face(val i0: Int, val i1: Int, val i2: Int, val normal: Vec3, val dist: Double)

private data class Polytope(val verts: List<MinkPoint>, val faces: List<Face>)

/** EPA 穿透深度求解（纯函数）。不相交返回 null；接触/退化相交深度为 0。 */
object Epa {

    fun penetration(a: ConvexShape, b: ConvexShape): EpaResult? {
        val gjk = Gjk.distance(a, b)
        if (gjk.distanceCm > CONTACT_DIST) return null
        val verts = initialVertices(gjk.simplex, a, b)
            ?: return EpaResult(0.0, Vec3.UNIT_Y, 0)
        var poly = Polytope(verts, tetraFaces(verts))
        var iter = 0
        while (iter < EPA_MAX_ITER) {
            iter++
            val face = poly.faces[closestFaceIndex(poly.faces)]
            if (face.dist < 0.0) return EpaResult(0.0, face.normal, iter)
            val m = minkSupport(a, b, face.normal)
            val gain = m.w.dot(face.normal) - face.dist
            val dup = poly.verts.any { (it.w - m.w).normSq() < DUP_NORM_SQ }
            if (gain <= EPA_EPS_ABS + EPA_EPS_REL * face.dist || dup) {
                return EpaResult(face.dist, face.normal, iter)
            }
            poly = expand(poly, m)
        }
        val face = poly.faces[closestFaceIndex(poly.faces)]
        return EpaResult(maxOf(0.0, face.dist), face.normal, iter)
    }

    private fun closestFaceIndex(faces: List<Face>): Int =
        faces.indices.minByOrNull { faces[it].dist } ?: 0
}

/** 初始四面体：优先 GJK 终端单纯形；退化时从轴向＋四面体向支撑点里挑严格含原点的最大体积四面体。 */
private fun initialVertices(simplex: List<MinkPoint>, a: ConvexShape, b: ConvexShape): List<MinkPoint>? {
    if (simplex.size == 4 && tetraVolume(simplex) > 1e-12 &&
        originInside(simplex.map { it.w }, strict = false)
    ) {
        return simplex
    }
    val s = 1.0 / sqrt(3.0)
    val dirs = listOf(
        Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_Y, -Vec3.UNIT_Y, Vec3.UNIT_Z, -Vec3.UNIT_Z,
        Vec3(s, s, s), Vec3(s, -s, -s), Vec3(-s, s, -s), Vec3(-s, -s, s),
    )
    val candidates = simplex + dirs.map { minkSupport(a, b, it) }
    return pickTetra(candidates, strict = true) ?: pickTetra(candidates, strict = false)
}

private fun pickTetra(c: List<MinkPoint>, strict: Boolean): List<MinkPoint>? {
    var best: List<MinkPoint>? = null
    var bestVol = 1e-12
    for (i in c.indices) {
        for (j in i + 1 until c.size) {
            for (k in j + 1 until c.size) {
                for (l in k + 1 until c.size) {
                    val t = listOf(c[i], c[j], c[k], c[l])
                    val vol = tetraVolume(t)
                    if (vol > bestVol && originInside(t.map { it.w }, strict)) {
                        best = t
                        bestVol = vol
                    }
                }
            }
        }
    }
    return best
}

private fun tetraVolume(t: List<MinkPoint>): Double {
    val w = t.map { it.w }
    return abs((w[1] - w[0]).dot((w[2] - w[0]).cross(w[3] - w[0]))) / 6.0
}

/**
 * 原点是否在四面体内：每面用「对顶点在同侧」定向（绕向无关），
 * 再要求原点也在该侧。strict=true 要求严格在内部，不含边界。
 */
private fun originInside(w: List<Vec3>, strict: Boolean): Boolean {
    val faces = listOf(
        listOf(0, 1, 2) to 3, listOf(0, 1, 3) to 2,
        listOf(0, 2, 3) to 1, listOf(1, 2, 3) to 0,
    )
    val margin = if (strict) 1e-9 else -1e-12
    return faces.all { (tri, opp) ->
        val (p, q, r) = tri.map { w[it] }
        val n = (q - p).cross(r - p)
        val side = if (n.dot(w[opp] - p) >= 0.0) 1.0 else -1.0
        side * n.dot(-p) >= margin
    }
}

private fun tetraFaces(verts: List<MinkPoint>): List<Face> {
    val tris = listOf(listOf(0, 1, 2), listOf(0, 1, 3), listOf(0, 2, 3), listOf(1, 2, 3))
    return tris.mapNotNull { buildFace(verts, it[0], it[1], it[2]) }
}

/** 建一面并统一外朝向（原点在体内 ⇒ 外法向与顶点同侧，dist≥0）。 */
private fun buildFace(verts: List<MinkPoint>, i: Int, j: Int, k: Int): Face? {
    val vi = verts[i].w
    val n0 = (verts[j].w - vi).cross(verts[k].w - vi).normalized()
    if (n0 == Vec3.ZERO) return null
    val flip = n0.dot(vi) < 0.0
    val n = if (flip) -n0 else n0
    return if (flip) Face(i, k, j, n, n.dot(vi)) else Face(i, j, k, n, n.dot(vi))
}

/** 扩张：删去从新点可见的面，沿水平线边补新面。 */
private fun expand(poly: Polytope, m: MinkPoint): Polytope {
    val visible = poly.faces.indices.filter { fi ->
        val f = poly.faces[fi]
        f.normal.dot(m.w - poly.verts[f.i0].w) > VIS_EPS
    }
    val horizon = horizonEdges(poly.faces, visible)
    val newIdx = poly.verts.size
    val verts = poly.verts + m
    val kept = poly.faces.filterIndexed { fi, _ -> fi !in visible }
    val added = horizon.mapNotNull { (ea, eb) -> buildFace(verts, ea, eb, newIdx) }
    return Polytope(verts, kept + added)
}

/** 水平线边：可见面集合里只出现一次的无向边，保留可见面里的有向顺序。 */
private fun horizonEdges(faces: List<Face>, visible: List<Int>): List<Pair<Int, Int>> {
    val counts = HashMap<Pair<Int, Int>, Int>()
    val directed = ArrayList<Pair<Int, Int>>()
    for (fi in visible) {
        val f = faces[fi]
        for (e in listOf(f.i0 to f.i1, f.i1 to f.i2, f.i2 to f.i0)) {
            val key = minOf(e.first, e.second) to maxOf(e.first, e.second)
            counts[key] = (counts[key] ?: 0) + 1
            directed.add(e)
        }
    }
    return directed.filter { counts[minOf(it.first, it.second) to maxOf(it.first, it.second)] == 1 }
}