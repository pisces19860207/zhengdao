// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第七百二十五批 · 形轨·动作：四项判定（组合 655 现成几何内核，零另造）。
package com.example.zhengdao.baopu.state.motion

import com.example.zhengdao.baopu.state.geometry.PoseValidity
import com.example.zhengdao.baopu.state.geometry.Reachability
import com.example.zhengdao.baopu.state.geometry.ViolationKind

/**
 * 每帧过 655 四项：
 *  - 限位 / 自碰撞 / 穿地 → PoseValidity.check（一次调用出三类违规）
 *  - 可达 → Reachability.ofChain（PoseValidity 不含此项，单独接）
 * 全纯函数，输出只有布尔，不携带任何角度/距离数值。
 */
internal object FrameCheckEval {

    fun evaluate(intensity: Int, action: MotionAction): FrameChecks {
        val verdict = PoseValidity.check(
            segments = RigGeometry.segments(intensity),
            env = RigGeometry.ENV,
            chain = RigGeometry.chainPose(intensity, action),
            adjacent = RigGeometry.ADJACENT,
        )
        val reach = Reachability.ofChain(RigGeometry.legChain(), RigGeometry.REACH_TARGET_CM)
        val kinds = verdict.violations.map { it.kind }.toSet()
        return FrameChecks(
            limitOk = ViolationKind.LIMIT_EXCEEDED !in kinds,
            reachOk = reach.state == Reachability.State.REACHABLE,
            noSelfCollision = ViolationKind.SELF_COLLISION !in kinds,
            noGroundPenetration = ViolationKind.GROUND_PENETRATION !in kinds &&
                ViolationKind.SAND_PENETRATION !in kinds,
        )
    }
}
