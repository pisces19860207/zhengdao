// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百五十五批 · 形轨.几何判定：关节限位与链式传播。
// 每关节 min/max（弧度），超限截到边界并留痕；角度沿链做正向运动学传播
// （上一关节的截断会改变下游所有关节的世界位置）。**不含时间积分**：
// 输入一组角度 ⇒ 输出一组位姿，是静态映射，不是步进仿真。
package com.example.zhengdao.baopu.state.geometry

/** 关节转角限位（弧度，闭区间）。 */
data class JointLimit(val minRad: Double, val maxRad: Double) {
    init {
        require(minRad <= maxRad) { "关节限位 min 不得大于 max" }
    }

    fun clamp(rad: Double) = kotlin.math.min(maxRad, kotlin.math.max(minRad, rad))

    fun contains(rad: Double) = rad in minRad..maxRad
}

/** 单关节规格：名字＋连杆长＋转轴（局部系单位轴）＋限位。 */
data class JointSpec(
    val name: String,
    val lengthCm: Double,
    val axis: Vec3,
    val limit: JointLimit,
) {
    init {
        require(lengthCm >= 0.0) { "连杆长非负" }
        require(axis.norm() > 0.0) { "转轴非零" }
    }
}

/** 单关节解算状态：请求角、截断后实际角、是否越限、连杆末端世界位置。 */
data class JointState(
    val name: String,
    val requestedRad: Double,
    val appliedRad: Double,
    val withinLimit: Boolean,
    val worldPosCm: Vec3,
)

/** 整链位姿读数。 */
data class ChainPose(
    val joints: List<JointState>,
    val endEffectorCm: Vec3,
    val assumption: String = "关节链正向运动学：逐关节截断到限位后沿链传播（静态映射，无时间积分）",
    val note: String = GeometryNotes.NOT_MEASUREMENT,
    val status: KernelStatus = KernelStatus.Success,
    val reason: String? = null,
)

/**
 * 关节链：根位姿＋一串转动关节。约定：第 i 关节先绕自身轴转 appliedRad，
 * 再沿旋转后局部 +X 前进 lengthCm；worldPosCm 记该连杆末端。
 */
class JointChain(
    val joints: List<JointSpec>,
    val rootPosCm: Vec3 = Vec3.ZERO,
    val rootRot: Rot3 = Rot3.IDENTITY,
) {
    init {
        require(joints.isNotEmpty()) { "关节链至少一个关节" }
    }

    fun pose(angles: List<Double>): ChainPose {
        require(angles.size == joints.size) { "角度数必须等于关节数" }
        if (!vec3IsFinite(rootPosCm) || !rootRot.isOrthonormal()) {
            return ChainPose(emptyList(), Vec3.ZERO, status = KernelStatus.Unsupported, reason = "根位姿无效")
        }
        if (angles.any { !it.isFinite() }) {
            return ChainPose(emptyList(), rootPosCm, status = KernelStatus.Unsupported, reason = "关节角非有限数")
        }
        var p = rootPosCm
        var rot = rootRot
        val states = mutableListOf<JointState>()
        for (i in joints.indices) {
            val j = joints[i]
            if (!j.lengthCm.isFinite()) {
                return ChainPose(states, p, status = KernelStatus.Unsupported, reason = "连杆长非有限数")
            }
            if (!j.limit.minRad.isFinite() || !j.limit.maxRad.isFinite()) {
                return ChainPose(states, p, status = KernelStatus.Unsupported, reason = "关节限位非有限数")
            }
            val applied = j.limit.clamp(angles[i])
            val jointRot = Rot3.fromAxisAngle(j.axis, applied)
                ?: return ChainPose(states, p, status = KernelStatus.Unsupported, reason = "关节轴或角度无效")
            val nextRot = rot * jointRot
            val nextP = p + nextRot.times(Vec3(j.lengthCm, 0.0, 0.0))
            if (!vec3IsFinite(nextP)) {
                return ChainPose(states, p, status = KernelStatus.Unsupported, reason = "关节位置溢出")
            }
            rot = nextRot
            p = nextP
            states += JointState(j.name, angles[i], applied, j.limit.contains(angles[i]), p)
        }
        return ChainPose(states, p)
    }
}

/** 标准关节名（通用骨架命名，供 660 形体代理体挂接；配对齐全才通过）。 */
object JointNames {
    const val ROOT = "root"
    const val SPINE = "spine"
    const val CHEST = "chest"
    const val NECK = "neck"
    const val HEAD = "head"
    const val SHOULDER_L = "shoulder_l"
    const val ELBOW_L = "elbow_l"
    const val WRIST_L = "wrist_l"
    const val SHOULDER_R = "shoulder_r"
    const val ELBOW_R = "elbow_r"
    const val WRIST_R = "wrist_r"
    const val HIP_L = "hip_l"
    const val KNEE_L = "knee_l"
    const val ANKLE_L = "ankle_l"
    const val HIP_R = "hip_r"
    const val KNEE_R = "knee_r"
    const val ANKLE_R = "ankle_r"

    val ALL = listOf(
        ROOT, SPINE, CHEST, NECK, HEAD,
        SHOULDER_L, ELBOW_L, WRIST_L,
        SHOULDER_R, ELBOW_R, WRIST_R,
        HIP_L, KNEE_L, ANKLE_L,
        HIP_R, KNEE_R, ANKLE_R,
    )
}