// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：腔隙表（交付④）。
// 输入＝669 的十三区关系表（相邻＋方位口径）与 660 的形体代理体；输出＝相邻结构之间的可用空隙。
// 669 的 adjacent 是**有向**的（38 条里有六条只写了单向），腔隙按无序并集出条目（22 条）才不漏。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.state.geometry.Epa
import com.example.zhengdao.baopu.state.geometry.Gjk
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3

/** 腔隙三档：堵死（含互穿）／窄／开。 */
enum class CavityState { BLOCKED, NARROW, OPEN }

/** 一对相邻区之间的空隙读数。gapMilli 为负＝两侧代理体已互穿。 */
data class CavityGap(
    val zoneId: String,
    val otherId: String,
    val gapMilli: Int,
    val state: CavityState,
    /** 从 zoneId 侧指向 otherId 侧的主轴档。 */
    val direction: NormalBucket,
    /** 669 方位口径原文，原样透传不改写。 */
    val anatomyPosition: String,
    val assumption: String,
    val note: String,
)

/** 腔隙表：13 区与相邻结构之间的可用空隙。 */
object CavityMap {

    private val ASSUMPTION =
        "腔隙＝相邻两区**最近实例对**的表面间隙（GJK 距离；互穿取 EPA 深度的负值），整数毫米；" +
            "方向取见证点连线／EPA 穿入方向的主轴档。相邻关系取 669 有向表的无序并集。几何推算，无时间积分。"

    /** 表面间隙（厘米，穿透为负）＋「从 a 侧指向 b 侧」的方向向量。 */
    private data class Surface(val gapCm: Double, val direction: Vec3)

    fun of(proxies: List<OrganProxy>, anatomy: List<AnatomyZone>): List<CavityGap> {
        val byZone = instances(proxies, anatomy)
        val position = anatomy.associate { it.zoneId to it.relations.position }
        return unorderedPairs(anatomy).map { (a, b) -> gapOf(a, b, byZone.getValue(a), byZone.getValue(b), position.getValue(a)) }
    }

    /** 间隙分档：≤0 堵死（贴合与互穿同档），(0, 门限) 窄，≥门限 开。 */
    fun stateOf(gapMilli: Int): CavityState = when {
        gapMilli <= 0 -> CavityState.BLOCKED
        gapMilli < CavityTolerances.CAVITY_OPEN_MILLI -> CavityState.NARROW
        else -> CavityState.OPEN
    }

    /**
     * 相邻关系的**实例级**展开（判据①自碰撞名单用）：一个区有多个实例（睾丸/精索左右）时逐一成对。
     * 两侧同名的自配丢掉；输出规范化排序并去重。
     */
    fun adjacentPairs(proxies: List<OrganProxy>, anatomy: List<AnatomyZone>): List<Pair<String, String>> {
        val byZone = proxies.groupBy { it.id.substringBefore('.') }
        return unorderedPairs(anatomy).flatMap { (a, b) ->
            cross(byZone[a], byZone[b]) { x, y -> if (x < y) x to y else y to x }
        }.filter { it.first != it.second }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun cross(xs: List<OrganProxy>?, ys: List<OrganProxy>?, f: (String, String) -> Pair<String, String>): List<Pair<String, String>> {
        val l = xs.orEmpty().map { it.id }.sorted()
        val r = ys.orEmpty().map { it.id }.sorted()
        return l.flatMap { a -> r.map { b -> f(a, b) } }
    }

    private fun instances(proxies: List<OrganProxy>, anatomy: List<AnatomyZone>): Map<String, List<OrganProxy>> {
        val byZone = proxies.groupBy { it.id.substringBefore('.') }
        val missing = anatomy.map { it.zoneId }.filter { byZone[it].isNullOrEmpty() }
        require(missing.isEmpty()) { "以下区缺代理体，腔隙表不许静默漏条目：$missing" }
        return anatomy.associate { it.zoneId to byZone.getValue(it.zoneId) }
    }

    private fun unorderedPairs(anatomy: List<AnatomyZone>): List<Pair<String, String>> {
        val zoneIds = anatomy.map { it.zoneId }.toSet()
        val dangling = anatomy.flatMap { z -> z.relations.adjacent.filter { it !in zoneIds }.map { "${z.zoneId}→$it" } }
        require(dangling.isEmpty()) { "相邻表指向不存在的区：$dangling" }
        return anatomy
            .flatMap { z -> z.relations.adjacent.map { if (z.zoneId < it) z.zoneId to it else it to z.zoneId } }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun gapOf(zoneId: String, otherId: String, zoneProxies: List<OrganProxy>, otherProxies: List<OrganProxy>, position: String): CavityGap {
        val cands = zoneProxies.sortedBy { it.id }.flatMap { a -> otherProxies.sortedBy { it.id }.map { b -> surfaceGap(a, b) } }
        val best = requireNotNull(cands.minByOrNull { it.gapCm }) { "腔隙候选为空：$zoneId↔$otherId" }
        val milli = signedMilli(best.gapCm)
        return CavityGap(
            zoneId = zoneId,
            otherId = otherId,
            gapMilli = milli,
            state = stateOf(milli),
            direction = ContactProbe.normalBucket(best.direction),
            anatomyPosition = position,
            assumption = ASSUMPTION,
            note = GeometryNotes.NOT_MEASUREMENT,
        )
    }

    /** 分离看 GJK 见证点连线；互穿看 EPA 深度与穿入方向；恰接触退化到两心连线。 */
    private fun surfaceGap(a: OrganProxy, b: OrganProxy): Surface {
        val g = Gjk.distance(a.shape, b.shape)
        if (g.distanceCm > 0.0) return Surface(g.distanceCm, g.witnessB - g.witnessA)
        val ep = Epa.penetration(a.shape, b.shape)
        if (ep != null && ep.depthCm > 0.0) return Surface(-ep.depthCm, ep.normal)
        val centers = b.aabb().center() - a.aabb().center()
        return Surface(-(ep?.depthCm ?: 0.0), if (centers.normSq() > 0.0) centers else g.witnessB - g.witnessA)
    }
}
