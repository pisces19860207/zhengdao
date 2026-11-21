// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第七百二十五批 · 形轨·动作：步态三态与左右交替。
package com.example.zhengdao.baopu.state.motion

/** 步态纯函数：三态是否齐全、左右是否交替（不许同脚连续支撑）。 */
internal object Gait {

    /** 三态闭集在给定帧序列里是否齐全。 */
    fun allThreePhasesPresent(poses: List<KeyPose>): Boolean {
        val seen = poses.map { it.phase }.toSet()
        return GaitPhase.values().all { it in seen }
    }

    /** 单支撑帧按序取支撑脚；相邻两帧不得同为一只脚。 */
    fun supportAlternates(poses: List<KeyPose>): Boolean {
        val singles = poses.filter { it.phase == GaitPhase.SUPPORT }.mapNotNull { it.support }
        if (singles.size < 2) return false
        return singles.zipWithNext().all { (a, b) -> a != b }
    }

    /** 摆动腿档必须高于支撑腿档（同一单支撑帧内比较）。 */
    fun swingHigherThanSupport(poses: List<KeyPose>): Boolean = poses
        .filter { it.phase == GaitPhase.SUPPORT && it.swing != null }
        .all { pose ->
            val swingLevel = levelOf(pose, pose.swing!!)
            val supportLevel = levelOf(pose, pose.support!!)
            swingLevel > supportLevel
        }

    private fun levelOf(pose: KeyPose, side: FootSide): Int =
        if (side == FootSide.LEFT) pose.jointLeft.level else pose.jointRight.level
}
