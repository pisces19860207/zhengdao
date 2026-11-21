// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 形轨.空间容纳与受限姿态：**唯一的度量桥**。
// 全包只有这一个文件碰 655 的度量面（段代理体／关节链／环境高度／可达距离）；它把
// 655 的三态与违规类别压成四项三态交出去，**不带出任何量值**：距离只在本文件内部算一次，
// 随即丢弃——返回值里没有坐标、没有毫米，也没有浮点字段（判据④）。
// 口径「只转写不重判」：本包不另起一套几何判据，四项全部取 655 的裁判结论。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.PoseValidity
import com.example.zhengdao.baopu.state.geometry.Reachability
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.Violation
import com.example.zhengdao.baopu.state.geometry.ViolationKind

/**
 * 一档站位送审的全部输入：这一档的身体（段代理体）、支撑面、手臂链与位姿、要够的目标物。
 *
 * 构造即拦三种会静默变绿的输入：身体为空（四项空转）、位姿不带关节（限位项空转）、
 * 目标物混进身体段（自碰撞把目标当自己人跳过）。
 */
data class StanceScene(
    val stance: Stance,
    val segments: List<SegmentProxy>,
    val env: Environment,
    val chain: JointChain,
    val chainPose: ChainPose,
    val target: SegmentProxy,
    val adjacent: Set<Pair<String, String>> = emptySet(),
) {
    init {
        require(segments.isNotEmpty()) { "姿态缺段代理体：四项会空转成过" }
        require(chainPose.joints.isNotEmpty()) { "缺关节位姿：限位项会空转成过" }
        require(chain.joints.isNotEmpty()) { "缺手臂链：可达项会空转成过" }
        require(segments.none { it.name == target.name }) { "目标物不许混在身体段里" }
    }
}

/**
 * 站位 × 655 四项的转写器。
 *
 * 三项取 655 [PoseValidity] 的违规台账（限位／自碰撞／穿地穿沙），一项取 655
 * [Reachability] 的三态。**未证明照实记未证明**：采样没找到证据不是「不可达」，
 * 更不是「可达」——上层据此不许判「进得去」。
 */
object StanceLegality {

    fun audit(scene: StanceScene): StanceAudit {
        val verdict = PoseValidity.check(scene.segments, scene.env, scene.chainPose, scene.adjacent)
        val reach = Reachability.ofChain(scene.chain, reachSpan(scene.target.shape, scene.chain))
        return StanceAudit(
            stance = scene.stance,
            readings = listOf(
                reading(AuditItem.LIMIT, hit(verdict.violations) { it == ViolationKind.LIMIT_EXCEEDED }),
                reading(AuditItem.REACH, reachState(reach.state)),
                reading(AuditItem.SELF_COLLISION, hit(verdict.violations) { it == ViolationKind.SELF_COLLISION }),
                reading(
                    AuditItem.GROUND,
                    hit(verdict.violations) {
                        it == ViolationKind.GROUND_PENETRATION || it == ViolationKind.SAND_PENETRATION
                    },
                ),
            ),
        )
    }

    private fun hit(violations: List<Violation>, predicate: (ViolationKind) -> Boolean): Boolean =
        violations.any { predicate(it.kind) }

    private fun reachState(state: Reachability.State): AuditState = when (state) {
        Reachability.State.REACHABLE -> AuditState.PASS
        Reachability.State.UNREACHABLE -> AuditState.FAIL
        Reachability.State.UNKNOWN -> AuditState.UNPROVEN
    }

    /** [violated]＝655 判这一项**有违规**（不是「通过」）：有违规 ⇒ 不过，无违规 ⇒ 过。 */
    private fun reading(item: AuditItem, violated: Boolean): AuditReading =
        reading(item, if (violated) AuditState.FAIL else AuditState.PASS)

    private fun reading(item: AuditItem, state: AuditState): AuditReading =
        AuditReading(item, state, detail(item, state))

    /** 目标物中心到链根的距离：只在本文档内部算一次，算完就丢（不外传量值）。 */
    private fun reachSpan(target: ConvexShape, chain: JointChain): Double =
        (centre(target) - chain.rootPosCm).norm()

    /** 包围盒中心：六轴向各问一次支撑函数，不新增 655 的类型与入口。 */
    private fun centre(shape: ConvexShape): Vec3 = Vec3(
        (shape.support(-Vec3.UNIT_X).x + shape.support(Vec3.UNIT_X).x) / 2.0,
        (shape.support(-Vec3.UNIT_Y).y + shape.support(Vec3.UNIT_Y).y) / 2.0,
        (shape.support(-Vec3.UNIT_Z).z + shape.support(Vec3.UNIT_Z).z) / 2.0,
    )

    /** 四类十态各一句人话：只含字词，不含数值与坐标。 */
    private fun detail(item: AuditItem, state: AuditState): String = when (item to state) {
        AuditItem.LIMIT to AuditState.PASS -> "关节全在限位内"
        AuditItem.LIMIT to AuditState.FAIL -> "有关节被截到限位边界"
        AuditItem.LIMIT to AuditState.UNPROVEN -> "限位未判"
        AuditItem.REACH to AuditState.PASS -> "目标落在链长可达范围内"
        AuditItem.REACH to AuditState.FAIL -> "目标超出链长可达范围"
        AuditItem.REACH to AuditState.UNPROVEN -> "采样未找到可达证据，不敢说够得着"
        AuditItem.SELF_COLLISION to AuditState.PASS -> "非相邻段之间没有穿透"
        AuditItem.SELF_COLLISION to AuditState.FAIL -> "有一段穿进了另一段"
        AuditItem.SELF_COLLISION to AuditState.UNPROVEN -> "自碰撞未判"
        AuditItem.GROUND to AuditState.PASS -> "最低点在支撑面之上"
        AuditItem.GROUND to AuditState.FAIL -> "最低点低于支撑面"
        AuditItem.GROUND to AuditState.UNPROVEN -> "穿地未判"
        else -> state.label
    }
}
