// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 三条链的**真跑通用例**夹具。
//
// 关键：这里的 657 环境与 658 生理**是真的调求解器算出来的**（不是手写档位），
// 728/725 的档也是真枚举值。所以链②链③跑的是「657/658 的产出 → 本包连档 → 投影」，
// 而不是「照着想象拼几个数字」。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.bodyparts.BodyPart
import com.example.zhengdao.baopu.state.environment.EnvironmentSolver
import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.injury.RecoveryGrade
import com.example.zhengdao.baopu.state.motion.MotionAction
import com.example.zhengdao.baopu.state.physiology.ActivityBand
import com.example.zhengdao.baopu.state.physiology.AmbientTempBand
import com.example.zhengdao.baopu.state.physiology.DurationBand
import com.example.zhengdao.baopu.state.physiology.EmotionBand
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.physiology.HumidityBand
import com.example.zhengdao.baopu.state.physiology.IndividualBand
import com.example.zhengdao.baopu.state.physiology.LightPhysiology
import com.example.zhengdao.baopu.state.physiology.LightPhysiologySolver
import com.example.zhengdao.baopu.state.physiology.PhysioInputs
import com.example.zhengdao.baopu.state.surfaceflow.SweatZone

internal object CausalScenarios {

    /** 657 真跑：午后、34 ℃、湿度 80%、中活动、湿沙。产汗与日照都会被推高。 */
    val ENV: EnvironmentState = EnvironmentSolver.solve(
        latitudeDeg = 23.0,
        dayOfYear = 200,
        trueSolarTimeH = 14.0,
        beaufort = 2,
        windDirDeg = 90.0,
        tempC = 34.0,
        rhPct = 80.0,
        activity = 3,
        sandMoistureGrade = 1,
    )

    /** 658 真跑：炎热／饱和／剧烈／长时 ⇒ 体温偏移与疲劳都被推高。 */
    val PHYSIO: LightPhysiology = LightPhysiologySolver.solve(
        PhysioInputs(
            ambientTemp = AmbientTempBand.HOT,
            humidity = HumidityBand.SATURATED,
            activity = ActivityBand.INTENSE,
            duration = DurationBand.LONG,
            emotion = EmotionBand.MILD,
            individual = IndividualBand.TYPICAL,
        ),
    )

    /** 链①：受伤 → 步态改变 → 后续受限。受伤档取 728 的「肿起＋持续感」。 */
    fun injuryChain(): CausalLog {
        val strike = InjuryChain.strike(
            eventId = "i1",
            at = 0,
            site = SiteRef.Part(BodyPart.THIGH),
            mechanism = InjuryMechanism.BLUNT,
            intensity = 2,
            injury = InjuryGrade.SWELL,
            pain = PainGrade.STEADY,
            healSpan = Span825.CROSS_DAY,
        )
        val gait = InjuryChain.gaitAfter(strike, at = 3)
        val restrict = requireNotNull(InjuryChain.restrictAfter(gait, at = 6, healSpan = Span825.CROSS_DAY)) {
            "肿起档必须推出后续受限"
        }
        return CausalLog.of(strike, gait, restrict)
    }

    /** 链②：环境变化 → 皮肤湿 → 皮肤温 → 体感；光影挂在环境那一条上。 */
    fun environmentChain(): CausalLog {
        val ambient = EnvironmentChain.ambient("e1", at = 0, zone = SweatZone.BACK, env = ENV, intensity = 3)
        val wet = EnvironmentChain.wetAfter(ambient, at = 1, env = ENV, drySpan = Span825.HOURS)
        val temp = EnvironmentChain.tempAfter(wet, at = 1, physio = PHYSIO)
        val feel = EnvironmentChain.feelAfter(temp, at = 2, env = ENV)
        val light = EnvironmentChain.lightAfter(ambient, at = 2, env = ENV)
        return CausalLog.of(ambient, wet, temp, feel, light)
    }

    /** 链③：动作 → 疲劳累积（两步）→ 恢复时限（力竭，跨日才回）。 */
    fun fatigueChain(): CausalLog {
        val act = FatigueChain.act("f1", at = 0, site = SiteRef.Part(BodyPart.THIGH), action = MotionAction.WALK, intensity = 2)
        val first = FatigueChain.firstFatigue(act, at = 1, fatigue = Fatigue.LIGHT)
        val mid = FatigueChain.accumulate(first, at = 10, step = 1)
        val full = FatigueChain.accumulate(mid, at = 20, step = 1)
        val recover = FatigueChain.recover(
            full, at = 21, fatigue = Fatigue.EXHAUSTED, recovery = RecoveryGrade.LONGER, back = 3,
        )
        return CausalLog.of(act, first, mid, full, recover)
    }
}
