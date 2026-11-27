// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：EPA 穿透深度。
// 算法出处（公开文献的标准算法，实现为自研）：van den Bergen (1999)
// "A fast and robust GJK implementation for collision detection of convex
// objects" 的 expanding polytope 思路。从 GJK 终端单纯形出发，沿最近面法向
// 取支撑点扩张多面体，直到改进量小于容差；最近面到原点的距离即穿透深度。
// 第八百二十二批（epsilon 量纲与尺度归一）：本文件的 DUP_NORM_SQ（1e-18，绝对）退役——
// 重复点阈值改由形体标尺 L 推出（Gjk.dupTolSq，与 GJK stuck 同源同标定）。
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
    val status: KernelStatus = KernelStatus.Success,
    val reason: String? = null,
) {
    override fun toString(): String =
        "EpaResult(depthCm=$depthCm, normal=$normal, iterations=$iterations, " +
            "assumption=$assumption, note=$note" +
            if (status == KernelStatus.Success && reason == null) ")" else ", status=$status, reason=$reason)"
}

private sealed interface EpaStepOutcome {
    data class Done(val result: EpaResult) : EpaStepOutcome
    data class Grown(val poly: Polytope) : EpaStepOutcome
}

private sealed interface FacePick {
    data class Ok(val face: Face) : FacePick
    data class Bad(val result: EpaResult) : FacePick
}

internal enum class InitialTetraSource { GJK_SIMPLEX, STRICT_SCAN, NONE }

internal data class InitialTetraTrace(
    val vertices: List<MinkPoint>,
    val source: InitialTetraSource,
)

private const val EPA_ASSUMPTION = "EPA 穿透深度：支撑点扩张多面体，改进容差 1e-9（绝对＋相对），" +
    "重复点阈值随形体标尺 L 归一（822），上限 96 轮"
// EPA_MAX_ITER：性能参数（迭代轮数上限，无量纲），不随形体尺度变。
// source=engineering——非物理口径、非数值容差，调整只需性能证据，不动语义（839 定性）。
private const val EPA_MAX_ITER = 96
private const val EPA_EPS_ABS = 1e-9
private const val EPA_EPS_REL = 1e-9
private const val VIS_EPS = 1e-12
// CONTACT_DIST：EPA 入口口径（物理口径，839 定性）。公开承诺：GJK 距离 ≤ 1e-7 cm
// （10 埃）的对才进 EPA 出深度读数；更远的分离对返回 null。本带是 838 接触带
// （1 埃）的 10 倍，为 EPA 初始构造留数值余量；入口带 ≥ 接触带为硬互锁。
// 改它＝破坏 null/非 null 公开语义（消费方：BearingSampler/PoseValidity/
// ContactProbe/790 协议，全清单见 docs/geometry-semantics.md §四），必须另立单＋版本标记。
private const val CONTACT_DIST = 1e-7

private data class Face(val i0: Int, val i1: Int, val i2: Int, val normal: Vec3, val dist: Double)

private data class Polytope(val verts: List<MinkPoint>, val faces: List<Face>)

/** EPA 穿透深度求解（纯函数）。不相交返回 null；边界状态由 status/reason 显式给出。 */
object Epa {

    fun penetration(a: ConvexShape, b: ConvexShape): EpaResult? = try {
        val gjk = Gjk.distance(a, b)
        if (gjk.status == KernelStatus.Unsupported) {
            unsupported(gjk.iterations, gjk.reason ?: "GJK 输入超出支持域")
        } else if (a is Sphere && b is Sphere && a.center == b.center && a.radius == b.radius) {
            // 同心等球没有唯一分离方向；其它退化体仍可保留有限深度。
            degenerate(0.0, gjk.iterations, gjk.reason ?: "同心等球缺少唯一分离方向")
        } else if (gjk.distanceCm > CONTACT_DIST) {
            if (gjk.status == KernelStatus.Success) {
                null
            } else {
                degenerate(0.0, gjk.iterations, gjk.reason ?: "退化形体分离")
            }
        } else if (!gjk.intersecting) {
            degenerate(0.0, gjk.iterations, "分离近贴无穿透深度")
        } else {
            penetrate(gjk, a, b)
        }
    } catch (e: Exception) {
        unsupported(0, "EPA 边界异常：${e.javaClass.simpleName}")
    }

    internal fun initialTetraTrace(a: ConvexShape, b: ConvexShape): InitialTetraTrace {
        val gjk = Gjk.distance(a, b)
        if (gjk.status == KernelStatus.Unsupported) return InitialTetraTrace(emptyList(), InitialTetraSource.NONE)
        return initialTetra(gjk, a, b)
    }

    private fun penetrate(gjk: GjkResult, a: ConvexShape, b: ConvexShape): EpaResult {
        val dupTol = dupTolSq(a, b) // 822：随形体标尺 L 走，与 GJK stuck 同源同标定
        val initial = initialTetra(gjk, a, b)
        val verts = initial.vertices
        if (initial.source == InitialTetraSource.NONE) {
            return degenerate(0.0, gjk.iterations, "无严格含原点的初始四面体")
        }
        if (verts.size != 4) return degenerate(0.0, gjk.iterations, "初始四面体顶点不足")
        var poly = Polytope(verts, tetraFaces(verts))
        if (poly.faces.size < 4) return degenerate(0.0, gjk.iterations, "初始四面体面集不足")
        var iter = 0
        while (iter < EPA_MAX_ITER) {
            iter++
            when (val step = expandOnce(poly, a, b, dupTol, iter, gjk)) {
                is EpaStepOutcome.Done -> return step.result
                is EpaStepOutcome.Grown -> {
                    poly = step.poly
                    if (poly.faces.size < 4) return degenerate(0.0, iter, "扩张后面集不足")
                }
            }
        }
        return finalFace(poly, iter, gjk)
    }

    /** 单步扩张；三种失败结论与原文逐字一致（面集空/读数非有限/距离为负 各走各的）。 */
    private fun expandOnce(
        poly: Polytope,
        a: ConvexShape,
        b: ConvexShape,
        dupTol: Double,
        iter: Int,
        gjk: GjkResult,
    ): EpaStepOutcome {
        val face = iterationFace(poly, iter)
        if (face is FacePick.Bad) return EpaStepOutcome.Done(face.result)
        val ok = (face as FacePick.Ok).face
        val m = minkSupport(a, b, ok.normal)
        if (!vec3IsFinite(m.w)) return EpaStepOutcome.Done(unsupported(iter, "EPA 支撑点非有限数"))
        val gain = m.w.dot(ok.normal) - ok.dist
        if (!gain.isFinite()) return EpaStepOutcome.Done(unsupported(iter, "EPA 改进量非有限数"))
        val dup = poly.verts.any { (it.w - m.w).normSq() < dupTol }
        if (gain <= EPA_EPS_ABS + EPA_EPS_REL * ok.dist || dup) {
            return EpaStepOutcome.Done(EpaResult(ok.dist, ok.normal, iter, status = gjk.status, reason = gjk.reason))
        }
        return EpaStepOutcome.Grown(expand(poly, m))
    }

    /** 迭代耗尽后的收口；三条结论与原文一致（最终面集空/最终读数非有限/最终距离为负）。 */
    private fun finalFace(poly: Polytope, iter: Int, gjk: GjkResult): EpaResult {
        val face = finalPick(poly, iter)
        if (face is FacePick.Bad) return face.result
        val ok = (face as FacePick.Ok).face
        return EpaResult(ok.dist, ok.normal, iter, status = gjk.status, reason = gjk.reason)
    }

    private fun iterationFace(poly: Polytope, iter: Int): FacePick {
        val faceIndex = closestFaceIndex(poly.faces) ?: return FacePick.Bad(degenerate(0.0, iter, "扩张面集为空"))
        val face = poly.faces[faceIndex]
        if (!vec3IsFinite(face.normal) || !face.dist.isFinite()) {
            return FacePick.Bad(unsupported(iter, "EPA 面读数非有限数"))
        }
        if (face.dist < 0.0) return FacePick.Bad(degenerate(0.0, iter, "EPA 最近面距离为负"))
        return FacePick.Ok(face)
    }

    private fun finalPick(poly: Polytope, iter: Int): FacePick {
        val faceIndex = closestFaceIndex(poly.faces) ?: return FacePick.Bad(degenerate(0.0, iter, "最终面集为空"))
        val face = poly.faces[faceIndex]
        if (!vec3IsFinite(face.normal) || !face.dist.isFinite()) {
            return FacePick.Bad(unsupported(iter, "EPA 最终面读数非有限数"))
        }
        if (face.dist < 0.0) return FacePick.Bad(degenerate(0.0, iter, "EPA 最终面距离为负"))
        return FacePick.Ok(face)
    }

    private fun closestFaceIndex(faces: List<Face>): Int? =
        faces.indices.minByOrNull { faces[it].dist }

    private fun degenerate(depth: Double, iterations: Int, reason: String): EpaResult = EpaResult(
        depth,
        Vec3.UNIT_Y,
        iterations,
        status = KernelStatus.Degenerate,
        reason = reason,
    )

    private fun unsupported(iterations: Int, reason: String): EpaResult = EpaResult(
        Double.NaN,
        Vec3.UNIT_Y,
        iterations,
        status = KernelStatus.Unsupported,
        reason = reason,
    )
}

/** 初始四面体：优先严格含原点的 GJK 终端单纯形；否则只从严格候选里挑最大体积。 */
private fun initialTetra(gjk: GjkResult, a: ConvexShape, b: ConvexShape): InitialTetraTrace {
    val simplex = gjk.simplex
    if (simplex.size == 4 && tetraVolume(simplex) > 1e-12 && originInside(simplex.map { it.w }, strict = false)) {
        return InitialTetraTrace(simplex, InitialTetraSource.GJK_SIMPLEX)
    }
    val s = 1.0 / sqrt(3.0)
    val dirs = listOf(
        Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_Y, -Vec3.UNIT_Y, Vec3.UNIT_Z, -Vec3.UNIT_Z,
        Vec3(s, s, s), Vec3(s, -s, -s), Vec3(-s, s, -s), Vec3(-s, -s, s),
    )
    val candidates = simplex + dirs.map { minkSupport(a, b, it) }
    val picked = pickTetra(candidates)
    return if (picked == null) {
        InitialTetraTrace(emptyList(), InitialTetraSource.NONE)
    } else {
        InitialTetraTrace(picked, InitialTetraSource.STRICT_SCAN)
    }
}

private fun pickTetra(c: List<MinkPoint>): List<MinkPoint>? {
    var best: List<MinkPoint>? = null
    var bestVol = 1e-12
    for (i in c.indices) {
        for (j in i + 1 until c.size) {
            for (k in j + 1 until c.size) {
                for (l in k + 1 until c.size) {
                    val t = listOf(c[i], c[j], c[k], c[l])
                    if (t.any { !vec3IsFinite(it.w) }) continue
                    val vol = tetraVolume(t)
                    if (vol.isFinite() && vol > bestVol && originInside(t.map { it.w }, strict = true)) {
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

/** 原点含入判定：GJK 单纯形沿用 -1e-12 裕度，严格扫描沿用 1e-9 裕度。 */
private fun originInside(w: List<Vec3>, strict: Boolean): Boolean {
    if (w.size != 4 || w.any { !vec3IsFinite(it) }) return false
    val faces = listOf(
        listOf(0, 1, 2) to 3, listOf(0, 1, 3) to 2,
        listOf(0, 2, 3) to 1, listOf(1, 2, 3) to 0,
    )
    val margin = if (strict) 1e-9 else -1e-12
    return faces.all { (tri, opp) ->
        val (p, q, r) = tri.map { w[it] }
        val n = (q - p).cross(r - p)
        if (!vec3IsFinite(n) || n == Vec3.ZERO) return@all false
        val side = if (n.dot(w[opp] - p) >= 0.0) 1.0 else -1.0
        val sideDistance = side * n.dot(-p)
        sideDistance.isFinite() && sideDistance >= margin
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