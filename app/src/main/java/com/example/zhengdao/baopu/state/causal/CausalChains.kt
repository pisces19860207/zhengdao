// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：三条链的**事件构造器**。
//
// 为什么把它单独做成 API 而不是让调用方手拼事件：链的合法性（谁是父、能写哪些维度）是
// 本批唯一的实质内容。手拼的话，「受伤 → 步态」这根边就只存在于调用方的想法里；
// 做成构造器以后，父事件必须**真的**带那条伤情档，否则 `error`（拿不到档就不许往下接，
// 免得靠默认值把链「接」出来）。
//
// 每个构造器只做「读上一站的档 → 算下一站的档」，不读时钟、不读环境以外的任何东西。
package com.example.zhengdao.baopu.state.causal

import com.example.zhengdao.baopu.state.environment.EnvironmentState
import com.example.zhengdao.baopu.state.injury.InjuryGrade
import com.example.zhengdao.baopu.state.injury.PainGrade
import com.example.zhengdao.baopu.state.injury.RecoveryGrade
import com.example.zhengdao.baopu.state.motion.MotionAction
import com.example.zhengdao.baopu.state.physiology.Fatigue
import com.example.zhengdao.baopu.state.physiology.LightPhysiology
import com.example.zhengdao.baopu.state.surfaceflow.SweatZone

/** 从一串写入里取某维度的**绝对**目标档；增量写法取不到（它没有绝对目标）。 */
internal fun levelOf(writes: List<GradeWrite>, dim: CausalDim): Int? =
    writes.filterIsInstance<GradeWrite.To>().firstOrNull { it.dim == dim }?.level

/**
 * 链①：受伤 → 步态改变 → 后续受限（接 748 机制 / 728 观察档 / 725 姿态档）。
 *
 * 三站各自只写自己那一层的档：受伤写 728 的两档，步态写 725 的两档，受限落回 728 的活动受限档。
 * 「受限」不是第三个新维度，它就是 728 的 `LIMITED`——这正是「不新造档」的样子。
 */
internal object InjuryChain {

    /** 第一站：受伤。机制给 748 的七类之一；伤情/疼痛两档由调用方给（本包不发明 748 的后果表）。 */
    fun strike(
        eventId: String,
        at: Int,
        site: SiteRef.Part,
        mechanism: InjuryMechanism,
        intensity: Int,
        injury: InjuryGrade,
        pain: PainGrade,
        healSpan: Span825,
        note: String = "",
    ): CausalEvent = CausalEvent(
        eventId = eventId,
        parentEventId = null,
        at = at,
        kind = CausalKind.STRIKE,
        site = site,
        intensity = intensity,
        mechanism = mechanism,
        immediate = listOf(
            GradeWrite.to(CausalDim.SITE_INJURY, injury.level),
            GradeWrite.to(CausalDim.SENSE_PAIN, pain.level),
        ),
        delayed = listOf(
            DelayedGrade(GradeWrite.to(CausalDim.SITE_INJURY, InjuryGrade.NONE.level), healSpan),
            DelayedGrade(GradeWrite.to(CausalDim.SENSE_PAIN, PainGrade.NONE.level), healSpan),
        ),
        note = note.ifBlank { "748 ${mechanism.label}" },
    )

    /** 第二站：步态改变。伤情档从父事件读，读不到就抛（不许默认「没事」）。 */
    fun gaitAfter(parent: CausalEvent, at: Int, note: String = ""): CausalEvent {
        val injuryLevel = levelOf(parent.immediate, CausalDim.SITE_INJURY)
            ?: error("父事件 ${parent.eventId} 没写 ${CausalDim.SITE_INJURY.id}，接不了步态")
        return CausalEvent(
            eventId = "${parent.eventId}.gait",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.GAIT,
            site = parent.site,
            intensity = parent.intensity,
            immediate = CausalRules.poseWrites(InjuryGrade.of(injuryLevel)),
            note = note.ifBlank { "728 ${InjuryGrade.of(injuryLevel).label}→725" },
        )
    }

    /**
     * 第三站：后续受限。姿态档没跨过常态就不成立，返回 null（这是合法断点，不是错误）。
     */
    fun restrictAfter(
        parent: CausalEvent,
        at: Int,
        healSpan: Span825,
        note: String = "",
    ): CausalEvent? {
        val joint = levelOf(parent.immediate, CausalDim.POSE_JOINT)
            ?: error("父事件 ${parent.eventId} 没写 ${CausalDim.POSE_JOINT.id}，接不了受限")
        val restricted = CausalRules.restrictedLevel(joint) ?: return null
        return CausalEvent(
            eventId = "${parent.eventId}.restrict",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.RESTRICT,
            site = parent.site,
            intensity = parent.intensity,
            immediate = listOf(GradeWrite.to(CausalDim.SITE_INJURY, restricted)),
            delayed = listOf(
                DelayedGrade(GradeWrite.to(CausalDim.SITE_INJURY, InjuryGrade.NONE.level), healSpan),
            ),
            note = note.ifBlank { "728 ${InjuryGrade.of(restricted).label}" },
        )
    }
}

/**
 * 链②：环境变化 → 皮肤湿/温 → 体感与光影（接 657 求解 / 806 分区 / 658 生理 / 824 缺省）。
 *
 * 「环境变化」自己不写档：它是驱动，值在 657 的 `EnvironmentState` 里；下游每一站把
 * 自己那一档从 657/658 的产出里**取**出来（不是算出来）。这是本批「只连档位」最直白的体现。
 */
internal object EnvironmentChain {

    /** 第一站：环境变化。note 留 657 的日照档与产汗档，回放时一眼能对上源。 */
    fun ambient(
        eventId: String,
        at: Int,
        zone: SweatZone,
        env: EnvironmentState,
        intensity: Int,
        note: String = "",
    ): CausalEvent = CausalEvent(
        eventId = eventId,
        parentEventId = null,
        at = at,
        kind = CausalKind.AMBIENT,
        site = SiteRef.Zone(zone),
        intensity = intensity,
        note = note.ifBlank { "657 日照=${env.sun.intensity} 产汗=${env.sweat.grade}" },
    )

    /** 第二站：皮肤湿。806 量档取 657 产汗档的上封；滞后写「汗略干」＝退一档（有基线，故用 By）。 */
    fun wetAfter(
        parent: CausalEvent,
        at: Int,
        env: EnvironmentState,
        drySpan: Span825,
        note: String = "",
    ): CausalEvent = CausalEvent(
        eventId = "${parent.eventId}.wet",
        parentEventId = parent.eventId,
        at = at,
        kind = CausalKind.WET,
        site = parent.site,
        intensity = parent.intensity,
        immediate = listOf(GradeWrite.to(CausalDim.SKIN_WET, CausalRules.wetLevel(env))),
        delayed = listOf(DelayedGrade(GradeWrite.by(CausalDim.SKIN_WET, -1), drySpan)),
        note = note.ifBlank { "657 产汗=${env.sweat.grade}→806" },
    )

    /** 第三站：皮肤温。取 658 的体温偏移档。 */
    fun tempAfter(parent: CausalEvent, at: Int, physio: LightPhysiology, note: String = ""): CausalEvent =
        CausalEvent(
            eventId = "${parent.eventId}.temp",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.TEMP,
            site = parent.site,
            intensity = parent.intensity,
            immediate = listOf(GradeWrite.to(CausalDim.SKIN_TEMP, CausalRules.tempLevel(physio))),
            note = note.ifBlank { "658 ${physio.bodyTempShift.grade.label}" },
        )

    /** 第四站：体感。取 657 的体感温度档。 */
    fun feelAfter(parent: CausalEvent, at: Int, env: EnvironmentState, note: String = ""): CausalEvent =
        CausalEvent(
            eventId = "${parent.eventId}.feel",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.FEEL,
            site = parent.site,
            intensity = parent.intensity,
            immediate = listOf(GradeWrite.to(CausalDim.SENSE_FEEL, CausalRules.feelLevel(env))),
            note = note.ifBlank { "657 体感=${feltTempLabel(CausalRules.feelLevel(env))}" },
        )

    /** 第五站：光影。657 的日照档；824 五维没有生产类型，note 里显式写缺省。 */
    fun lightAfter(parent: CausalEvent, at: Int, env: EnvironmentState, note: String = ""): CausalEvent =
        CausalEvent(
            eventId = "${parent.eventId}.light",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.LIGHT,
            site = parent.site,
            intensity = parent.intensity,
            immediate = listOf(GradeWrite.to(CausalDim.LIGHT, CausalRules.lightLevel(env))),
            note = note.ifBlank { "657 日照=${env.sun.intensity}；$Pending824" },
        )
}

/**
 * 链③：动作 → 疲劳累积 → 恢复时限（接 725 动作 / 658 疲劳档 / 734 恢复预算 / 825 跨度）。
 */
internal object FatigueChain {

    /** 第一站：动作。只作驱动（动作种类是 725 的闭集），自己不写档。 */
    fun act(
        eventId: String,
        at: Int,
        site: SiteRef.Part,
        action: MotionAction,
        intensity: Int,
        note: String = "",
    ): CausalEvent = CausalEvent(
        eventId = eventId,
        parentEventId = null,
        at = at,
        kind = CausalKind.ACT,
        site = site,
        intensity = intensity,
        note = note.ifBlank { "725 ${action.label}" },
    )

    /** 第二站之一：首次落疲劳档（绝对写法，给基线）。 */
    fun firstFatigue(parent: CausalEvent, at: Int, fatigue: Fatigue, note: String = ""): CausalEvent =
        CausalEvent(
            eventId = "${parent.eventId}.fatigue",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.FATIGUE,
            site = parent.site,
            intensity = parent.intensity,
            immediate = listOf(GradeWrite.to(CausalDim.SENSE_FATIGUE, fatigue.level)),
            note = note.ifBlank { "658 ${fatigue.label}" },
        )

    /** 第二站之二：继续累积（相对写法，必须有基线——没有基线回放会判红）。 */
    fun accumulate(parent: CausalEvent, at: Int, step: Int, note: String = ""): CausalEvent =
        CausalEvent(
            eventId = "${parent.eventId}.+",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.FATIGUE,
            site = parent.site,
            intensity = parent.intensity,
            immediate = listOf(GradeWrite.by(CausalDim.SENSE_FATIGUE, step)),
            note = note.ifBlank { "658 +$step" },
        )

    /**
     * 第三站：恢复时限。跨度由 734 的恢复预算选档（力竭再退一档），到点时把疲劳退 [back] 档。
     */
    fun recover(
        parent: CausalEvent,
        at: Int,
        fatigue: Fatigue,
        recovery: RecoveryGrade,
        back: Int,
        note: String = "",
    ): CausalEvent {
        val span = CausalRules.recoverySpan(fatigue, CausalRules.recoveryLevelOf(recovery))
        return CausalEvent(
            eventId = "${parent.eventId}.recover",
            parentEventId = parent.eventId,
            at = at,
            kind = CausalKind.RECOVER,
            site = parent.site,
            intensity = parent.intensity,
            immediate = emptyList(),
            delayed = listOf(DelayedGrade(GradeWrite.by(CausalDim.SENSE_FATIGUE, -back), span)),
            note = note.ifBlank { "734 预算→825 ${span.label}" },
        )
    }
}
