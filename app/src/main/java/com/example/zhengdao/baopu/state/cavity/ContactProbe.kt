// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：三量输出（交付③）。
// 穿透深度（整数毫米）／接触点数（原始数＋档）／法线方向档（六主轴带符号）。
// 不建接触流形（655 明确不做），不输出应力张量；接触点数口径来源：686 澄清 2026-11-20。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.ConvexHull
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Epa
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import kotlin.math.abs

/** 接触点数档：0／1／2–3／4＋。 */
enum class ContactBucket { ZERO, ONE, FEW, MANY }

/**
 * 被计入的一个接触点。
 * depthMilli 是该特征点到对方表面的有符号深度（毫米）：正＝穿入，负＝在对方表面之外，
 * 故与 ContactReading.depthMilli（成对穿透深度，非负）口径不同。
 */
data class ContactPoint(val proxyId: String, val index: Int, val point: Vec3, val depthMilli: Int)

/** 一对代理体的接触三量。pair 已规范化；normal 随调用方向（A 穿入 B），故调转后反向。 */
data class ContactReading(
    val pair: ProxyPair,
    val depthMilli: Int,
    val normal: NormalBucket,
    val contactRaw: Int,
    val contactBucket: ContactBucket,
    val counted: List<ContactPoint>,
    val truncated: Boolean,
    val droppedFeatureCount: Int,
    val assumption: String,
    val note: String,
)

/** 按固定特征点数一遍接触：深度与点数都来自 655 的 GJK／EPA，本单不另起判据。 */
object ContactProbe {

    private val ASSUMPTION =
        "接触三量：深度＝655 EPA 转整数毫米（四舍五入、.5 向上）；" +
            "接触点数＝特征点有符号深度 ≥ 最大深度 − " + CavityTolerances.CONTACT_EPS_REL +
            "×出点方包围盒对角线 的个数（凸包特征点上限 " + CavityTolerances.HULL_FEATURE_CAP +
            "，截断另记账）；法线＝EPA 方向取六主轴带符号档。几何推算，无时间积分。" +
            "口径来源：686 澄清 2026-11-20"

    /** 一侧特征点：出点方 id／序号／坐标＋该侧 ε（绝对厘米）＋要扎进去的对方形状。 */
    private data class Probe(
        val proxyId: String,
        val index: Int,
        val point: Vec3,
        val epsCm: Double,
        val into: ConvexShape,
    )

    /** 一对的接触三量；未相交返回 null（细判已筛过，这里是第二道确认）。 */
    fun read(a: OrganProxy, b: OrganProxy): ContactReading? {
        val ep = Epa.penetration(a.shape, b.shape) ?: return null
        val scored = probes(a, b).map { it to signedDepthCm(it) }
        val top = scored.maxOf { it.second }
        val counted = scored
            .filter { (p, d) -> d >= top - p.epsCm }
            .sortedWith(compareBy({ it.first.proxyId }, { it.first.index }))
        val dropped = a.features().droppedCount + b.features().droppedCount
        return ContactReading(
            pair = ProxyPair.of(a.id, b.id),
            depthMilli = depthMilli(ep.depthCm),
            normal = normalBucket(ep.normal),
            contactRaw = counted.size,
            contactBucket = bucketOf(counted.size),
            counted = counted.map { (p, d) -> ContactPoint(p.proxyId, p.index, p.point, pointMilli(d)) },
            truncated = dropped > 0,
            droppedFeatureCount = dropped,
            assumption = ASSUMPTION,
            note = GeometryNotes.NOT_MEASUREMENT,
        )
    }

    /** 一批代理体的全部接触读数（按成对键升序，只出真相交对）。 */
    fun readAll(proxies: List<OrganProxy>): List<ContactReading> {
        val byId = proxies.associateBy { it.id }
        return Broadphase.fine(proxies).mapNotNull {
            read(requireNotNull(byId[it.aId]) { "接触对引用未知代理体 ${it.aId}" }, requireNotNull(byId[it.bId]) { "接触对引用未知代理体 ${it.bId}" })
        }
    }

    /**
     * 补条 A：Epa.depthCm → 整数毫米，「四舍五入到最近整数，.5 向上」。
     * NaN／±Inf／负零／负值一律抛——静默归零会让「整数毫米」本身失去确定性。
     */
    fun depthMilli(cm: Double): Int {
        require(cm.isFinite()) { "穿透深度必须是有限数，得 $cm（不许静默归零）" }
        val negZero = cm == 0.0 && 1.0 / cm < 0.0
        require(!negZero && cm >= 0.0) { "穿透深度须非负且不许负零，得 $cm（负零会静默改变取整方向）" }
        return signedMilli(cm)
    }

    /** 原始接触点数 → 档（0／1／2–3／4＋）。 */
    fun bucketOf(contactRaw: Int): ContactBucket {
        require(contactRaw >= 0) { "接触点数非负，得 $contactRaw" }
        return when {
            contactRaw == 0 -> ContactBucket.ZERO
            contactRaw == 1 -> ContactBucket.ONE
            contactRaw <= 3 -> ContactBucket.FEW
            else -> ContactBucket.MANY
        }
    }

    /** 六主轴带符号归档；平局按 X＞Y＞Z，分量恰为零记正档。 */
    fun normalBucket(v: Vec3): NormalBucket {
        require(v.x != 0.0 || v.y != 0.0 || v.z != 0.0) { "法线零向量无法归档" }
        val mags = listOf(abs(v.x), abs(v.y), abs(v.z))
        val main = mags.indices.maxBy { mags[it] }
        return AXES[main][if (listOf(v.x, v.y, v.z)[main] < 0.0) 1 else 0]
    }

    private val AXES = listOf(
        listOf(NormalBucket.PLUS_X, NormalBucket.MINUS_X),
        listOf(NormalBucket.PLUS_Y, NormalBucket.MINUS_Y),
        listOf(NormalBucket.PLUS_Z, NormalBucket.MINUS_Z),
    )

    private fun probes(a: OrganProxy, b: OrganProxy): List<Probe> = listOf(a to b, b to a).flatMap { (own, other) ->
        val epsCm = own.aabb().diagonalCm() * CavityTolerances.CONTACT_EPS_REL
        own.features().points.map { Probe(own.id, it.index, it.point, epsCm, other.shape) }
    }

    /** 特征点当成退化凸包，深度与距离都仍由 655 判（判据①「判点」的字面口径）。 */
    private fun signedDepthCm(p: Probe): Double {
        val dot = ConvexHull(listOf(p.point))
        return Epa.penetration(dot, p.into)?.depthCm ?: -Gjk.distance(dot, p.into).distanceCm
    }

    /** 特征点深度可负（点在对方表面之外），取整口径与成对深度同一条。 */
    private fun pointMilli(cm: Double): Int {
        require(cm.isFinite()) { "特征点深度必须是有限数，得 $cm" }
        return signedMilli(cm)
    }
}
