// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：两级剔除（交付②）。
// 第一级包围盒粗剔、第二级 655 的 GJK 近邻细判；不做有限元，不做连续动力学。
// 正确性由「接触对必 AABB 重叠」保证 ⇒ 粗剔是细判的超集，假阴性率恒 0（判据②）。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.Gjk

/** 一对代理体，键已规范化（名字小的在前），可直接当集合并。 */
data class ProxyPair(val aId: String, val bId: String) {

    init {
        require(aId.isNotBlank() && bId.isNotBlank()) { "成对键缺 id" }
        require(aId != bId) { "成对键不许自配：$aId" }
        require(aId < bId) { "成对键必须规范化（名字小的在前）：$aId/$bId" }
    }

    companion object {

        fun of(a: String, b: String): ProxyPair = if (a < b) ProxyPair(a, b) else ProxyPair(b, a)

        val BY_ID: Comparator<ProxyPair> = compareBy({ it.aId }, { it.bId })
    }
}

/** 粗剔 → 细判。两级都按成对键升序输出，不留哈希序尾巴。 */
object Broadphase {

    /** 第一级：包围盒重叠的候选对（含恰接触；只多不漏）。 */
    fun coarse(proxies: List<OrganProxy>): List<ProxyPair> {
        val boxes = proxies.map { it.aabb() }
        val out = ArrayList<ProxyPair>()
        for (i in proxies.indices) for (j in i + 1 until proxies.size) {
            if (boxes[i].overlaps(boxes[j])) out += ProxyPair.of(proxies[i].id, proxies[j].id)
        }
        out.sortWith(ProxyPair.BY_ID)
        return out
    }

    /** 细判单对：655 的 GJK 距离 ≤ CONTACT_TOL_CM 即算接触。 */
    fun inContact(a: OrganProxy, b: OrganProxy): Boolean =
        Gjk.distance(a.shape, b.shape).distanceCm <= CavityTolerances.CONTACT_TOL_CM

    /** 第二级：只对粗剔候选做 GJK，输出真接触对。 */
    fun fine(proxies: List<OrganProxy>): List<ProxyPair> {
        val byId = proxies.associateBy { it.id }
        return coarse(proxies).filter {
            inContact(requireNotNull(byId[it.aId]) { "候选对引用未知代理体 ${it.aId}" }, requireNotNull(byId[it.bId]) { "候选对引用未知代理体 ${it.bId}" })
        }
    }
}
