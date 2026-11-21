// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第七百二十五批 · 形轨·动作：档位→几何的内部映射（仅供 655 判定，不对外）。
// 工程抽象：强度档派生髋高与屈曲弧度，喂给 655 的现成几何内核；非真实解剖正向运动学。
package com.example.zhengdao.baopu.state.motion

import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.ChainPose
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.JointChain
import com.example.zhengdao.baopu.state.geometry.JointLimit
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.JointSpec
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Vec3

/** 档位→几何的内部映射。所有 Double 只活在本文件，绝不进入对外输出类型。 */
internal object RigGeometry {

    /** 可达判定的固定目标距离（两腿链链长 48+44=92，目标落在其闭式可达区间内）。 */
    const val REACH_TARGET_CM: Double = 88.0

    /** 地面/沙面都为 0；穿地靠 hipY 下探到负最低点触发。 */
    val ENV: Environment = Environment(0.0, 0.0)

    /** 相邻段对：天然接触，跳过自碰撞判定（左右对称写法，PoseValidity 双向查）。 */
    val ADJACENT: Set<Pair<String, String>> = setOf(
        "core" to "thigh_l", "core" to "thigh_r",
        "core" to "shin_l", "core" to "shin_r",
        "core" to "foot_l", "core" to "foot_r",
        "thigh_l" to "shin_l", "thigh_r" to "shin_r",
        "shin_l" to "foot_l", "shin_r" to "foot_r",
    )

    /** 髋高：档 1=100，档 5=56；core 胶囊最低 = hipY-60，故档 5 最低点 = -4 ⇒ 穿地。 */
    fun hipY(intensity: Int): Double = 100.0 - 11.0 * (intensity - 1)

    /** 膝屈曲弧度：档 1=0，档 5=1.0；膝限位 ±0.9 ⇒ 档 5 越限、档 4=0.75 合法。 */
    fun kneeRad(intensity: Int): Double = 0.25 * (intensity - 1)

    /** 髋前摆弧度：走/跨有前摆，蹲/弯腰对称；均在 ±1.2 限位内。 */
    fun hipFlexRad(action: MotionAction): Double = when (action) {
        MotionAction.WALK, MotionAction.STEP_OVER -> 0.2
        MotionAction.CROUCH, MotionAction.BEND -> 0.0
    }

    /** 两关节腿链：hip(48) + knee(44)，knee 限位 ±0.9（档 5 会越限）。 */
    fun legChain(): JointChain = JointChain(
        listOf(
            JointSpec(JointNames.HIP_L, 48.0, Vec3.UNIT_Z, JointLimit(-1.2, 1.2)),
            JointSpec(JointNames.KNEE_L, 44.0, Vec3.UNIT_Z, JointLimit(-0.9, 0.9)),
        ),
    )

    /** 当前强度＋动作的关节角 → ChainPose（655 静态 FK，无时间步）。 */
    fun chainPose(intensity: Int, action: MotionAction): ChainPose =
        legChain().pose(listOf(hipFlexRad(action), kneeRad(intensity)))

    /** 七段代理体：core 最低点随 hipY 下探，其余段最低点恒非负（脚底恰 0）。 */
    fun segments(intensity: Int): List<SegmentProxy> {
        val y = hipY(intensity)
        return listOf(
            SegmentProxy("core", Capsule(Vec3(0.0, y - 42.0, 0.0), Vec3(0.0, y + 40.0, 0.0), 18.0)),
            SegmentProxy("thigh_l", Capsule(Vec3(-10.0, y, 0.0), Vec3(-10.0, y - 45.0, 0.0), 8.0)),
            SegmentProxy("thigh_r", Capsule(Vec3(10.0, y, 0.0), Vec3(10.0, y - 45.0, 0.0), 8.0)),
            SegmentProxy("shin_l", Capsule(Vec3(-10.0, y - 45.0, 0.0), Vec3(-10.0, 8.0, 0.0), 6.0)),
            SegmentProxy("shin_r", Capsule(Vec3(10.0, y - 45.0, 0.0), Vec3(10.0, 8.0, 0.0), 6.0)),
            SegmentProxy("foot_l", Box(Vec3(-10.0, 4.0, 7.0), Vec3(7.0, 4.0, 13.0))),
            SegmentProxy("foot_r", Box(Vec3(10.0, 4.0, 7.0), Vec3(7.0, 4.0, 13.0))),
        )
    }
}
