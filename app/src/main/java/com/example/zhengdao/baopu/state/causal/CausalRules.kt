// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：三条链的**派生链接**（只连档位，不做连续物理）。
//
// 这里每一条函数都只做一件事：把业主包算出来的**档**换成下一位业主包的**档**。
// 没有一个函数做插值、积分、脉冲响应或任何随时间连续变化的东西——判据④（无微分）靠这个前提，
// 由 `CausalSourceAssertTest` 扫源码钉住（本包连一个浮点类型都不许出现）。
//
// 派生表为什么长这样（每一行的出处都写在这里，改之前先读）：
//   · 伤情档 → 姿态：725 的关节角是五档序数、重心是五档序数；728 的伤情是四档序数。
//     一处受伤把患侧关节角往上推「伤情档数」档、把重心往下压同样档数，中位取 725 的 L3。
//     **中位 L3 是本项目的工程假设**（725 只给「1 最直、5 最屈」，没给「常态是几」），
//     所以它写成常量并在测试里被单独断言——要改口径只改这一处。
//   · 皮肤湿：657 的产汗档是 0–4（五档），806 的量档是 0–3（四档）⇒ 五档压到四档取上封，
//     不新增第五档（806 的档是业主，657 多出来的那一档在 806 里没有对应词）。
//   · 皮肤温 / 疲劳：直接取 658 求解器的档，本包不算。
//   · 体感 / 日照：取 657 效应清单里的档位整数，档名由 657 给。
//   · 恢复时限：取 734 的恢复预算（1/3/6 拍），按档序换成 825 的四档跨度；力竭再往后一档。
//     拍只用来**选档**，不进任何输出——输出只有 825 的四个跨度词。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.physiology.LightPhysiology
import com.example.zhengdao.baopu.state.stimulus.Recovery

/**
 * 三条链的派生映射。纯函数、零 IO、零随机、零时钟、零浮点。
 */
internal object CausalRules {

    /** 725 的常态取中位档 L3（工程假设，见文件头）。关节角与重心共用同一个中位。 */
    const val NEUTRAL_JOINT = 3
    const val NEUTRAL_COM = 3

    // ── 链① 受伤 → 步态改变 → 后续受限 ──────────────────────────────────────

    /**
     * 728 伤情档 ⇒ 725 两条姿态档的写入（患侧关节角↑、重心↓，各推移「伤情档数」档）。
     *
     * 档位只做整数平移并钳在 725 自己的区间内，所以「伤情越重、姿态越偏」是单调的，
     * 且永远落在 725 已有的五档里（不会冒出 L6）。
     */
    fun poseWrites(injury: InjuryGrade): List<GradeWrite> {
        val push = injury.level
        return listOf(
            GradeWrite.to(CausalDim.POSE_JOINT, CausalDim.POSE_JOINT.clamp(NEUTRAL_JOINT + push)),
            GradeWrite.to(CausalDim.POSE_COM, CausalDim.POSE_COM.clamp(NEUTRAL_COM - push)),
        )
    }

    /**
     * 姿态档 ⇒ 「后续受限」要不要成立：关节角比常态更屈（至少一档）才算受限。
     * 返回 null＝不受限（链①在这一点上断，是**合法**的断，不判红）。
     */
    fun restrictedLevel(jointLevel: Int): Int? =
        if (jointLevel >= NEUTRAL_JOINT + 1) InjuryGrade.LIMITED.level else null

    // ── 链② 环境变化 → 皮肤湿/温 → 体感与光影 ────────────────────────────────

    /** 657 产汗档（0–4）⇒ 806 量档（0–3）：上封，不造第五档。 */
    fun wetLevel(env: EnvironmentState): Int = CausalDim.SKIN_WET.clamp(env.sweat.grade)

    /** 658 体温偏移档 ⇒ 皮肤温档（同一批的四档，直接取，不再推）。 */
    fun tempLevel(physio: LightPhysiology): Int = physio.bodyTempShift.grade.level

    /** 657 体感温度档；657 的效应清单里取不到就抛（不猜默认档）。 */
    fun feelLevel(env: EnvironmentState): Int {
        val g = feltTempGrade(env.sweat.effects)
            ?: error("657 效应清单里没有 $FELT_TEMP_KIND")
        return CausalDim.SENSE_FEEL.clamp(g)
    }

    /** 657 日照档名 ⇒ 档序（无/弱/中/强）。档名不认识就抛，免得把「阴」当成「弱」。 */
    fun lightLevel(env: EnvironmentState): Int {
        val idx = LIGHT_LABELS.indexOf(env.sun.intensity)
        require(idx >= 0) { "657 日照档名不认识：${env.sun.intensity}" }
        return idx
    }

    // ── 链③ 动作 → 疲劳累积 → 恢复时限 ──────────────────────────────────────

    /**
     * 疲劳累积一步：在当前档上按 [step] 档推进（658 的四档封顶）。
     *
     * 用 [GradeWrite.To] 而不是 [GradeWrite.By]：调用方手里已经有 658 的当前档，
     * 直接给目标档更不容易被「回放顺序」影响；`By` 留给「不知道当前档、只知道相对变化」的场合
     * （链②的「汗略干」就是那样）。
     */
    fun fatigueStep(current: Fatigue, step: Int): GradeWrite =
        GradeWrite.to(CausalDim.SENSE_FATIGUE, CausalDim.SENSE_FATIGUE.clamp(current.level + step))

    /**
     * 734 恢复预算（1/3/6 拍）⇒ 825 四档跨度。
     *
     * 档序映射：预算 1 拍＝分钟级、3 拍＝小时级、6 拍＝过夜；疲劳到力竭再往后退一档（封顶跨日）。
     * 为什么用「拍」选档而不是用小时数：734 的口径就是拍（728/734 同一套），本包不换单位，
     * 只把它的档序译成 825 的词——输出里永远不会出现拍数或小时数。
     */
    fun recoverySpan(fatigue: Fatigue, recoveryLevel: Int): Span825 {
        val budget = Recovery.budgetAt(recoveryLevel)
        val base = when {
            budget <= 1 -> Span825.MINUTES
            budget <= 3 -> Span825.HOURS
            else -> Span825.OVERNIGHT
        }
        return if (fatigue == Fatigue.EXHAUSTED) Span825.later(base) else base
    }

    /** 728 恢复档 ⇒ 734 预算档序（0/1/2），翻译一处写死，别处不许再 `ordinal`。 */
    fun recoveryLevelOf(grade: com.example.zhengdao.baopu.state.injury.RecoveryGrade): Int = grade.ordinal
}
