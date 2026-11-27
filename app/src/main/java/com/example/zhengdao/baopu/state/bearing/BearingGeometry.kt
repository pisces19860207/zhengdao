// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：工程几何（内部件，所有 Double 活在本文件与采样器，不进结论类型）。
// 消费 655 的凸体/关节链与 660 的形体产出，不另造同类件；
// 档位偏移/带宽/阈值全部标「工程假设○」，不是事实底盘（判据⑥）。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.ConvexShape
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.JointSpec
import com.example.zhengdao.baopu.state.geometry.Rot3
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Vec3
import kotlin.math.PI

/** 距离档→目标点偏移与判定带宽（厘米）。source 全部标工程假设○（判据⑥）。 */
data class TierSpec(val offsetCm: Double, val bandCm: Double, val source: String)

/** 方位六档→世界系单位方向（fixture 站姿直立：+Y 上/背侧、−Y 下/腹侧、+Z 前方、−Z 根向、±X 左右）。 */
internal fun dirOf(bearing: Bearing6): Vec3 = when (bearing) {
    Bearing6.FRONT -> Vec3.UNIT_Z
    Bearing6.BACK -> -Vec3.UNIT_Z
    Bearing6.UP -> Vec3.UNIT_Y
    Bearing6.DOWN -> -Vec3.UNIT_Y
    Bearing6.LEFT -> -Vec3.UNIT_X
    Bearing6.RIGHT -> Vec3.UNIT_X
}

/** 方位可达工程几何：目标点、手臂链、摆姿段、跳过对。纯函数，零随机零时钟。 */
internal object BearingGeometry {

    /** 距离档常量：贴体＝面接触带；近/中/远＝沿方位外推。与 655 同用厘米（不另设单位）。 */
    val TIERS: Map<DistanceTier, TierSpec> = linkedMapOf(
        DistanceTier.CONTACT to
            TierSpec(0.0, 2.0, "工程假设○：贴体＝支承面接触带 2.0cm（整手代理半径 3.5cm 下的指尖接触分辨率），非事实底盘"),
        DistanceTier.NEAR to
            TierSpec(3.0, 2.0, "工程假设○：近＝面外推 3cm 带 2cm，非事实底盘"),
        DistanceTier.MID to
            TierSpec(10.0, 3.0, "工程假设○：中＝面外推 10cm 带 3cm，非事实底盘"),
        DistanceTier.FAR to
            TierSpec(25.0, 4.0, "工程假设○：远＝面外推 25cm 带 4cm，非事实底盘"),
    )

    /** 别扭阈值：合格构型的最大关节利用率≥此值判 awkward（工程假设○）。 */
    const val AWKWARD_UTIL: Double = 0.9

    /** 顶限阈值：最近构型利用率≥此值且仍差带宽 ⇒ 需要越限才够到 ⇒ blockedBy=LIMIT_EXCEEDED（越限即红）。 */
    const val LIMIT_UTIL: Double = 0.98

    /** 利用率阈值的出身声明（判据⑥：档位常量标工程假设○，不当事实底盘）。 */
    const val UTIL_SOURCE: String =
        "工程假设○：别扭阈值 0.9 与顶限阈值 0.98 是本单工程档位口径，非事实底盘、非临床值域"

    /** 接触容差（cm）：EPA 深度≤此值算「搭着」不算「压入」（655 PoseValidity 接触容差同思路）。 */
    const val CONTACT_DEPTH_TOL: Double = 0.5

    /** 目标点豁免守卫（cm）：相邻区面只在目标点不深陷其内（深度≤此值）时才豁免接触。 */
    const val PRESS_GUARD_CM: Double = 0.5

    /** 采样数与细化轮数（工程假设○：覆盖密度预算，与 655 采样同思路）。 */
    const val SAMPLES: Int = 8192
    const val REFINE_ROUNDS: Int = 3
    const val REFINE_SEEDS: Int = 3

    /** 诊断圈：误差≤带宽＋此余量的构型才做碰撞诊断（控 GJK 成本）。 */
    const val DIAG_MARGIN_CM: Double = 2.0

    /** 目标点＝区代理体沿方位的支承面点＋距离档外推（655 support 是唯一入口，不另造）。 */
    fun targetPoint(shape: ConvexShape, bearing: Bearing6, tier: DistanceTier): Vec3 {
        val dir = dirOf(bearing)
        return shape.support(dir) + dir * TIERS.getValue(tier).offsetCm
    }

    /** 手臂链（工程抽象）：肩两自由度＋肘＋腕，四关节；末段长＝手长，链末端即指尖。 */
    fun armChain(side: String, body: BodyState): JointChain {
        val limits = body.jointLimits.associateBy { it.joint }
        val shoulder = requireNotNull(limits["shoulder_$side"]) { "缺 shoulder_$side 限位（660 jointLimits）" }.limit
        val elbow = requireNotNull(limits["elbow_$side"]) { "缺 elbow_$side 限位（660 jointLimits）" }.limit
        val wrist = requireNotNull(limits["wrist_$side"]) { "缺 wrist_$side 限位（660 jointLimits）" }.limit
        val x = if (side == "l") -body.frame.layout.shoulderJointX else body.frame.layout.shoulderJointX
        return JointChain(
            listOf(
                JointSpec("shoulder_yaw_$side", 0.0, Vec3.UNIT_Z, shoulder),
                JointSpec("shoulder_swing_$side", body.frame.cm("upper_arm"), Vec3.UNIT_Y, shoulder),
                JointSpec("elbow_$side", body.frame.cm("forearm"), Vec3.UNIT_Y, elbow),
                JointSpec("wrist_$side", body.frame.cm("hand"), Vec3.UNIT_Y, wrist),
            ),
            rootPosCm = Vec3(x, body.frame.layout.shoulderY, 0.0),
            rootRot = checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Z, -PI / 2)),
        )
    }

    /** 摆姿后的手臂三段（上臂/前臂/手，655 Capsule）；链关节末位置依次为肩、肘、腕、指尖。 */
    fun posedArm(pose: ChainPose, side: String, body: BodyState): List<SegmentProxy> {
        val p = pose.joints.map { it.worldPosCm }
        val rUpper = body.frame.radii.getValue("upper_arm").value
        val rFore = body.frame.radii.getValue("forearm").value
        val rHand = body.frame.radii.getValue("hand").value
        return listOf(
            SegmentProxy("upper_arm_$side", Capsule(p[0], p[1], rUpper)),
            SegmentProxy("forearm_$side", Capsule(p[1], p[2], rFore)),
            SegmentProxy("hand_$side", Capsule(p[2], p[3], rHand)),
        )
    }

    /**
     * 摆姿臂与静置体的接触豁免计划：
     * free＝结构连接（肩-胸）＋目标区本体（按压目标本身是本判定的动作，压入后果归 686）；
     * guarded＝其余区实例——只有目标点**不深陷其面内**才豁免（指腹搭在毗邻区面上算接触，
     * 但包住目标点的面不算豁免：比如阴囊包着睾丸前后面、 radix/mons 吞着柱身根向面
     * ——手指必须穿透它们才够到 ⇒ 照记自碰撞卡死）。实例名单由调用方从 660 代理体实取，
     * 本文件不造第二份区表。
     */
    data class Permissions(val free: Set<Pair<String, String>>, val guarded: Set<Pair<String, String>>)

    fun permissions(side: String, targetInstance: String, otherInstances: List<String>): Permissions {
        val free = HashSet<Pair<String, String>>()
        free.add("upper_arm_$side" to JointNames.CHEST)
        free.add("hand_$side" to targetInstance)
        val guarded = otherInstances.map { "hand_$side" to it }.toSet()
        return Permissions(free, guarded)
    }

    /** 关节利用率：|实际角|/限位上界（限位对称）。肩取两自由度最大值。 */
    fun utilization(pose: ChainPose, chain: JointChain): Double {
        var worst = 0.0
        for (i in pose.joints.indices) {
            val hi = maxOf(kotlin.math.abs(chain.joints[i].limit.minRad), chain.joints[i].limit.maxRad)
            if (hi <= 0.0) continue
            val u = kotlin.math.abs(pose.joints[i].appliedRad) / hi
            if (u > worst) worst = u
        }
        return worst
    }

    /** Halton radical inverse（Halton 1964；与 655 同思路的确定性低差异序列，无随机数）。 */
    fun halton(index: Int, base: Int): Double {
        var f = 1.0
        var r = 0.0
        var idx = index
        while (idx > 0) {
            f /= base
            r += f * (idx % base)
            idx /= base
        }
        return r
    }

    val BASES: List<Int> = listOf(2, 3, 5, 7)
}
