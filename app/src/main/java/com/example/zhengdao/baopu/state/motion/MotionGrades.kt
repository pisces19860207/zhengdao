// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第七百二十五批 · 形轨·动作：对外输出类型（全序数档位，零弧度/角度数值）。
package com.example.zhengdao.baopu.state.motion

/** 动作种类（四动作，闭集）。 */
enum class MotionAction(val label: String) {
    WALK("走"), CROUCH("蹲"), STEP_OVER("跨"), BEND("弯腰"),
}

/** 关节角档：1 最直、5 最屈；只给序数，弧度只在内部映射层出现。 */
enum class JointAngleGrade(val level: Int) {
    L1(1), L2(2), L3(3), L4(4), L5(5);

    companion object {
        fun of(level: Int): JointAngleGrade = values().first { it.level == level }
    }
}

/** 重心高度档：1 最低、5 最高；只给序数，无厘米数值。 */
enum class ComHeightGrade(val level: Int) {
    L1(1), L2(2), L3(3), L4(4), L5(5);

    companion object {
        fun of(level: Int): ComHeightGrade = values().first { it.level == level }
    }
}

/** 步态相位三态（闭集）。 */
enum class GaitPhase(val label: String) {
    SUPPORT("支撑相"), SWING("摆动相"), DOUBLE_SUPPORT("双支撑"),
}

/** 左右脚。 */
enum class FootSide(val label: String) {
    LEFT("左"), RIGHT("右"),
}

/** 四项检查读数：全布尔，不携带任何浮点/标量分。 */
data class FrameChecks(
    val limitOk: Boolean,
    val reachOk: Boolean,
    val noSelfCollision: Boolean,
    val noGroundPenetration: Boolean,
) {
    val allOk: Boolean
        get() = limitOk && reachOk && noSelfCollision && noGroundPenetration

    /** 机读渲染：L/R/C/G 四位 yn，逐字节稳定。 */
    fun render(): String = "L${yn(limitOk)}R${yn(reachOk)}C${yn(noSelfCollision)}G${yn(noGroundPenetration)}"

    private fun yn(ok: Boolean) = if (ok) "1" else "0"
}

/** 对外关键姿：动作＋相位＋三处关节角档＋重心档＋四项读数，零弧度/角度/厘米数值。 */
data class KeyPose(
    val action: MotionAction,
    val keyIndex: Int,
    val label: String,
    val phase: GaitPhase,
    val support: FootSide?,
    val swing: FootSide?,
    val jointLeft: JointAngleGrade,
    val jointRight: JointAngleGrade,
    val spine: JointAngleGrade,
    val comHeight: ComHeightGrade,
    val demotedFrom: Int?,
    val checks: FrameChecks,
) {
    /** 逐字节稳定文本：只写档位序数与布尔，不含任何弧度/角度数值。 */
    fun render(): String = listOf(
        action.name,
        "k$keyIndex",
        phase.name,
        support?.name ?: "BOTH",
        swing?.name ?: "NONE",
        "jL${jointLeft.level}",
        "jR${jointRight.level}",
        "sp${spine.level}",
        "com${comHeight.level}",
        checks.render(),
        demotedFrom?.let { "demote$it" } ?: "demoteN",
    ).joinToString("|")
}
