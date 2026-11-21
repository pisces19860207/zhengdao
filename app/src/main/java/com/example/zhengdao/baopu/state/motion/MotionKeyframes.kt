// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第七百二十五批 · 形轨·动作：四动作关键帧名录＋退档解析（对外唯一入口）。
package com.example.zhengdao.baopu.state.motion

/** 关键帧规格：动作/序号/标签/相位/支撑脚/摆动脚/脊柱侧重/目标强度档。 */
internal data class KeySpec(
    val action: MotionAction,
    val index: Int,
    val label: String,
    val phase: GaitPhase,
    val support: FootSide?,
    val swing: FootSide?,
    val spineFocus: Boolean,
    val targetIntensity: Int,
)

/**
 * 四动作关键帧库（15 帧：走5/蹲3/跨4/弯腰3）。
 * 对外只暴露 KeyPose（全档位）；强度档经 [resolve] 退档后才派生输出档位。
 */
object MotionKeyframes {

    /** 站立基准：所有动作的档位起点，必过四项。 */
    const val STAND_INTENSITY: Int = 1

    private val SPECS: List<KeySpec> = listOf(
        // 走 · 5 关键姿（完整步态循环）
        KeySpec(MotionAction.WALK, 0, "双支撑·左脚前", GaitPhase.DOUBLE_SUPPORT, null, null, false, 2),
        KeySpec(MotionAction.WALK, 1, "单支撑·左撑右摆", GaitPhase.SUPPORT, FootSide.LEFT, FootSide.RIGHT, false, 3),
        KeySpec(MotionAction.WALK, 2, "摆动·右腿过顶", GaitPhase.SWING, FootSide.LEFT, FootSide.RIGHT, false, 3),
        KeySpec(MotionAction.WALK, 3, "单支撑·右撑左摆", GaitPhase.SUPPORT, FootSide.RIGHT, FootSide.LEFT, false, 3),
        KeySpec(MotionAction.WALK, 4, "双支撑·右脚前", GaitPhase.DOUBLE_SUPPORT, null, null, false, 2),
        // 蹲 · 3 关键姿
        KeySpec(MotionAction.CROUCH, 0, "站立基准", GaitPhase.DOUBLE_SUPPORT, null, null, false, 1),
        KeySpec(MotionAction.CROUCH, 1, "半蹲", GaitPhase.DOUBLE_SUPPORT, null, null, false, 3),
        KeySpec(MotionAction.CROUCH, 2, "全蹲", GaitPhase.DOUBLE_SUPPORT, null, null, false, 5),
        // 跨 · 4 关键姿
        KeySpec(MotionAction.STEP_OVER, 0, "准备", GaitPhase.DOUBLE_SUPPORT, null, null, false, 2),
        KeySpec(MotionAction.STEP_OVER, 1, "抬腿", GaitPhase.SWING, FootSide.LEFT, FootSide.RIGHT, false, 3),
        KeySpec(MotionAction.STEP_OVER, 2, "跨过", GaitPhase.SWING, FootSide.LEFT, FootSide.RIGHT, false, 5),
        KeySpec(MotionAction.STEP_OVER, 3, "落地", GaitPhase.DOUBLE_SUPPORT, null, null, false, 2),
        // 弯腰 · 3 关键姿
        KeySpec(MotionAction.BEND, 0, "直立", GaitPhase.DOUBLE_SUPPORT, null, null, true, 1),
        KeySpec(MotionAction.BEND, 1, "半弯腰", GaitPhase.DOUBLE_SUPPORT, null, null, true, 3),
        KeySpec(MotionAction.BEND, 2, "深弯腰", GaitPhase.DOUBLE_SUPPORT, null, null, true, 5),
    )

    /** 全部关键帧（已退档），按名录顺序；纯函数，同输入逐字节一致。 */
    fun all(): List<KeyPose> = SPECS.map { resolve(it) }

    /** 按动作取帧。 */
    fun of(action: MotionAction): List<KeyPose> = all().filter { it.action == action }

    /** 取某动作某序号帧。 */
    fun get(action: MotionAction, index: Int): KeyPose =
        SPECS.first { it.action == action && it.index == index }.let { resolve(it) }

    /**
     * 退档解析：从目标强度档逐级下降，直到四项全过（下限＝站立基准档）。
     * 返回值含 demotedFrom（目标档，仅在退档时非空）与派生出的输出档位。
     */
    internal fun resolve(spec: KeySpec): KeyPose {
        val actual = demote(spec.targetIntensity, spec.action)
        val checks = FrameCheckEval.evaluate(actual, spec.action)
        val jointLevel = actual
        val spineLevel = if (spec.spineFocus) actual else STAND_INTENSITY
        val (left, right) = deriveLegLevels(jointLevel, spec.support, spec.swing)
        return KeyPose(
            action = spec.action,
            keyIndex = spec.index,
            label = spec.label,
            phase = spec.phase,
            support = spec.support,
            swing = spec.swing,
            jointLeft = JointAngleGrade.of(left),
            jointRight = JointAngleGrade.of(right),
            spine = JointAngleGrade.of(spineLevel),
            comHeight = comGradeOf(actual),
            demotedFrom = if (actual < spec.targetIntensity) spec.targetIntensity else null,
            checks = checks,
        )
    }

    /** 逐级退档：目标→1，第一个四项全过的档即采用；1 档为站立基准必过。 */
    internal fun demote(target: Int, action: MotionAction): Int {
        var level = target.coerceIn(1, 5)
        while (level > STAND_INTENSITY) {
            if (FrameCheckEval.evaluate(level, action).allOk) return level
            level--
        }
        return STAND_INTENSITY
    }

    /**
     * 摆动腿档位更高（保持已过闸的 base）、支撑腿档位更低（base−1）；
     * 双支撑/无摆动则左右同档。输出档位恒不超过四项已通过的实际强度档。
     */
    private fun deriveLegLevels(base: Int, support: FootSide?, swing: FootSide?): Pair<Int, Int> {
        if (swing == null || support == null) return base to base
        val swingLevel = base
        val supportLevel = (base - 1).coerceAtLeast(1)
        return if (swing == FootSide.LEFT) swingLevel to supportLevel else supportLevel to swingLevel
    }

    /** 重心高度档：强度越高（越蹲/越弯）重心越低 ⇒ 档位越小。 */
    private fun comGradeOf(intensity: Int): ComHeightGrade =
        ComHeightGrade.of((6 - intensity).coerceIn(1, 5))
}
