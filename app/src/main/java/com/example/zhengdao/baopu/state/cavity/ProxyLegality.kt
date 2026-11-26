// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 形轨.器官代理与腔隙碰撞：四项合法读数（判据①）。
// **只转写不重判**：自碰撞／穿地穿沙／限位三项直接取 655 PoseValidity 的违规台账，
// 可达一项直接取 655 Reachability 的三态。686 不另起一套几何判据。
// 量值一律整数（毫米／毫弧度），沿用 655 的 ViolationUnit——310「禁标量分」口径。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.PoseValidity
import com.example.zhengdao.baopu.state.geometry.ReachResult
import com.example.zhengdao.baopu.state.geometry.Reachability
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Violation
import com.example.zhengdao.baopu.state.geometry.ViolationKind
import com.example.zhengdao.baopu.state.geometry.ViolationUnit

/** 四项合法：限位／可达／自碰撞／穿地（穿沙同项，靠 detail 分辨）。 */
enum class LegalityItem { LIMIT, REACH, SELF_COLLISION, GROUND }

/** 三态：合法／非法／未证明（采样没找到证据≠不可达，故不算红）。 */
enum class LegalityState { LEGAL, ILLEGAL, NOT_PROVEN }

/** 一个代理体在一项上的读数。 */
data class LegalityReading(
    val proxyId: String,
    val item: LegalityItem,
    val state: LegalityState,
    val amountMilli: Int,
    val unit: ViolationUnit,
    /** 自碰撞时的当事另一方；其余项为空串。 */
    val other: String,
    val detail: String,
    val assumption: String,
    val note: String,
)

/** 把 655 的裁判结论按「代理体 × 项」摊开，供上层取用。 */
object ProxyLegality {

    private const val VALIDITY_ASSUMPTION =
        "四项合法＝655 PoseValidity（GJK/EPA 自碰撞＋最低点穿地穿沙＋限位留痕）与 655 Reachability 的转写；" +
            "接触容差取 CavityTolerances.CONTACT_TOL_CM。自碰撞 655 每对记一条，本单按两侧各摊一条读数。"

    private const val REACH_ASSUMPTION = "可达项转写 655 Reachability：目标距离＝代理体包围盒中心到链根的距离，只判距离不判方位。"

    /**
     * 逐项读数。相邻名单（天然接触，如骨架相邻段、区与包绕它的皮）由调用方给，
     * 与 655 同口径跳过；每个代理体的每个自碰撞违规各出一条（故非法时条数可多于四项）。
     */
    fun check(
        proxies: List<OrganProxy>,
        env: Environment,
        chain: JointChain,
        pose: ChainPose,
        anchors: Map<String, String>,
        adjacent: Set<Pair<String, String>>,
    ): List<LegalityReading> {
        val compounds = CompoundWiring.compoundsFor(proxies.map { it.id })
        val verdict = PoseValidity.check(
            proxies.map { SegmentProxy(it.id, it.shape) }, env, pose, adjacent, CavityTolerances.CONTACT_TOL_CM,
            compounds = compounds,
        )
        val out = ArrayList<LegalityReading>()
        for (p in proxies.sortedBy { it.id }) {
            out += limitOf(p, requireNotNull(anchors[p.id]) { "代理体 ${p.id} 缺关节锚点，限位项无从转写" }, verdict.violations)
            out += reachOf(p, chain)
            out += collideOf(p.id, verdict.violations)
            out += groundOf(p.id, verdict.violations)
        }
        return out
    }

    /** 无非法项即为合法（NOT_PROVEN 不算红，也不算绿证据）。 */
    fun allLegal(readings: List<LegalityReading>): Boolean = illegalCount(readings) == 0

    fun illegalCount(readings: List<LegalityReading>): Int = readings.count { it.state == LegalityState.ILLEGAL }

    private fun limitOf(p: OrganProxy, anchor: String, vs: List<Violation>): LegalityReading {
        val hit = vs.firstOrNull { it.kind == ViolationKind.LIMIT_EXCEEDED && it.segment == anchor }
            ?: return LegalityReading(p.id, LegalityItem.LIMIT, LegalityState.LEGAL, 0, ViolationUnit.MILLIRADIAN, "", "关节 $anchor 在限位内", VALIDITY_ASSUMPTION, GeometryNotes.NOT_MEASUREMENT)
        return illegal(p.id, LegalityItem.LIMIT, hit)
    }

    private fun groundOf(id: String, vs: List<Violation>): LegalityReading {
        val hit = vs.firstOrNull { it.segment == id && (it.kind == ViolationKind.GROUND_PENETRATION || it.kind == ViolationKind.SAND_PENETRATION) }
            ?: return LegalityReading(id, LegalityItem.GROUND, LegalityState.LEGAL, 0, ViolationUnit.MILLIMETRE, "", "最低点在支撑面之上", VALIDITY_ASSUMPTION, GeometryNotes.NOT_MEASUREMENT)
        return illegal(id, LegalityItem.GROUND, hit)
    }

    /** 一个代理体可能同时与多个邻体互穿：逐条摊开，一条都不许并掉。 */
    private fun collideOf(id: String, vs: List<Violation>): List<LegalityReading> {
        val hits = vs.filter { it.kind == ViolationKind.SELF_COLLISION && (it.segment == id || it.other == id) }
        if (hits.isEmpty()) {
            return listOf(LegalityReading(id, LegalityItem.SELF_COLLISION, LegalityState.LEGAL, 0, ViolationUnit.MILLIMETRE, "", "无段间穿透", VALIDITY_ASSUMPTION, GeometryNotes.NOT_MEASUREMENT))
        }
        return hits.map { illegal(id, LegalityItem.SELF_COLLISION, it) }
    }

    private fun illegal(id: String, item: LegalityItem, v: Violation): LegalityReading =
        LegalityReading(
            proxyId = id,
            item = item,
            state = LegalityState.ILLEGAL,
            amountMilli = v.amountMilli,
            unit = v.unit,
            other = if (v.other == id) v.segment else v.other.orEmpty(),
            detail = v.detail,
            assumption = VALIDITY_ASSUMPTION,
            note = GeometryNotes.NOT_MEASUREMENT,
        )

    private fun reachOf(p: OrganProxy, chain: JointChain): LegalityReading {
        val targetCm = (p.aabb().center() - chain.rootPosCm).norm()
        val r = Reachability.ofChain(chain, targetCm, CavityTolerances.REACH_TOL_CM, CavityTolerances.REACH_SAMPLES)
        val text = "目标距链根 ${targetCm}cm，655 判 ${r.state}（最好成绩差 ${r.bestErrorCm}cm，采样 ${r.samplesTried} 点）"
        return LegalityReading(
            proxyId = p.id,
            item = LegalityItem.REACH,
            state = stateOf(r),
            amountMilli = signedMilli(r.bestErrorCm),
            unit = ViolationUnit.MILLIMETRE,
            other = "",
            detail = text,
            assumption = "$REACH_ASSUMPTION${r.assumption}",
            note = GeometryNotes.NOT_MEASUREMENT,
        )
    }

    private fun stateOf(r: ReachResult): LegalityState = when (r.state) {
        Reachability.State.REACHABLE -> LegalityState.LEGAL
        Reachability.State.UNREACHABLE -> LegalityState.ILLEGAL
        Reachability.State.UNKNOWN -> LegalityState.NOT_PROVEN
    }
}
